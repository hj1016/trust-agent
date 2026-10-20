package com.trustagent.core.consultation;

import com.trustagent.core.ai.AiServiceClient;
import com.trustagent.core.ai.AiServiceException;
import com.trustagent.core.grant.GrantService;
import com.trustagent.core.internalpolicy.query.InternalPolicyApplicableService;
import com.trustagent.core.internalpolicy.query.InternalPolicyApplicableState;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 상담 화면 '추가 질문'. 담당자 검사 뒤 질문 식별 정보 검사 → grant 발급(이 공문군·업무일만) → AI 서비스(근거 검색 + RAG) → Core 재확인 → 감사 기록.
 * Core 재확인: AI가 돌려준 근거·인용 규칙이 그 업무일에 사용 가능한 승인 Checklist 항목인지 다시 조회하고, 근거 문장은 Core 자료로 다시 채운다.
 * 어긋나면 설명을 내보내지 않는다(ANSWER_HOLD). 질문 원문은 저장하지 않는다. 이 응답은 업무 승인·고객별 적용 확정이 아니다.
 */
@Service
public class ConsultationAnswerService {

    public record Request(String question, String familyId, LocalDate businessDate) {}

    static final int QUESTION_MAX = 200;
    private static final Set<String> AI_STATUSES = Set.of("ANSWERED", "EVIDENCE_HOLD", "DECISION_DECLINED", "ANSWER_HOLD");
    private static final Pattern AMOUNT = Pattern.compile("\\d[\\d,.]*\\s*(원|만\\s*원|억|천만|백만)");
    private static final Pattern BUSINESS_NUMBER = Pattern.compile("\\b\\d{3}-?\\d{2}-?\\d{5}\\b");

    private final GrantService grants;
    private final AiServiceClient ai;
    private final InternalPolicyApplicableService applicable;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ConsultationAnswerService(GrantService grants, AiServiceClient ai, InternalPolicyApplicableService applicable, JdbcClient jdbc,
                                     ObjectMapper mapper, Clock clock) {
        this.grants = grants;
        this.ai = ai;
        this.applicable = applicable;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public ObjectNode answer(ConsultationService.View view, Request request, String userId, String activeRole, String traceId) {
        String question = request.question() == null ? "" : request.question().strip();
        if (question.isEmpty() || question.length() > QUESTION_MAX) {
            throw new ConsultationException("INVALID_QUESTION", "질문은 1~200자여야 합니다.");
        }
        if (view.families().stream().noneMatch(family -> family.familyId().equals(request.familyId()))) {
            throw new ConsultationException("FAMILY_NOT_IN_CONSULTATION", "이 상담 건의 상품 공문군이 아닙니다.");
        }
        long started = System.nanoTime();
        String requestId = "consultation-answer:" + UUID.randomUUID().toString().replace("-", "");
        ObjectNode body = mapper.createObjectNode();
        body.put("answerRequestId", requestId);
        body.put("familyId", request.familyId());
        body.put("businessDate", request.businessDate().toString());

        List<String> identifiers = identifierKinds(question, view);
        if (!identifiers.isEmpty()) {
            // 고객 식별 정보·금액이 든 질문은 AI 서비스로 보내지 않는다(마스킹하면 뜻이 바뀌므로 다시 쓰게 한다).
            return finish(body, view, request, question, userId, activeRole, traceId, started, "ANSWER_HOLD", List.of("QUESTION_CONTAINS_IDENTIFIER"),
                    null, List.of(), null);
        }
        GrantService.Grant grant = grants.issue(view.workspaceId(), view.consultationId(), view.applicationId(), List.of(request.familyId()),
                request.businessDate(), userId, activeRole, traceId);
        JsonNode result;
        try {
            AiServiceClient.Response response = ai.answer(question, request.familyId(), request.businessDate().toString(), view.consultationId(),
                    grant.grantId(), traceId);
            if (response.status() != 200) {
                return finish(body, view, request, question, userId, activeRole, traceId, started, "AI_UNAVAILABLE",
                        List.of("AI_RESPONSE_" + response.status()), null, List.of(), null);
            }
            result = mapper.readTree(response.body());
        } catch (AiServiceException exception) {
            return finish(body, view, request, question, userId, activeRole, traceId, started, "AI_UNAVAILABLE", List.of(exception.code()),
                    null, List.of(), null);
        } catch (RuntimeException exception) {
            return finish(body, view, request, question, userId, activeRole, traceId, started, "AI_UNAVAILABLE", List.of("AI_RESPONSE_INVALID"),
                    null, List.of(), null);
        }
        String status = result.path("status").stringValue();
        if (status == null || !AI_STATUSES.contains(status) || !result.path("evidence").isArray()) {
            return finish(body, view, request, question, userId, activeRole, traceId, started, "AI_UNAVAILABLE", List.of("AI_RESPONSE_INVALID"),
                    null, List.of(), result);
        }
        List<String> reasons = new ArrayList<>();
        result.path("reasons").forEach(reason -> reasons.add(reason.stringValue()));

        // Core 재확인: 그 업무일에 사용 가능한 승인 항목만 근거로 인정하고, 문장·위치·해시는 Core 자료로 다시 채운다.
        InternalPolicyApplicableState state = applicable.get(request.familyId(), request.businessDate().toString(), null);
        Map<String, InternalPolicyApplicableState.Rule> rulesById = new LinkedHashMap<>();
        Map<String, InternalPolicyApplicableState.ApprovedItem> itemsById = new LinkedHashMap<>();
        if (state.internalChecklistUseAllowed() && state.approvedChecklist() != null) {
            state.rules().forEach(rule -> rulesById.put(rule.ruleVersionId(), rule));
            state.approvedChecklist().items().forEach(item -> itemsById.put(item.sourceRuleVersionId(), item));
        }
        List<String> evidenceIds = new ArrayList<>();
        result.path("evidence").forEach(item -> evidenceIds.add(item.path("rule_version_id").stringValue()));
        List<String> citations = new ArrayList<>();
        result.path("answer").path("citations").forEach(item -> citations.add(item.stringValue()));
        boolean recheckFailed = evidenceIds.stream().anyMatch(id -> !itemsById.containsKey(id) || !rulesById.containsKey(id))
                || citations.stream().anyMatch(id -> !evidenceIds.contains(id));
        // 성공 상태만 믿지 않는다. 빈 근거나 깨진 답변 형식도 정상 설명으로 내보낼 수 없다.
        if ("ANSWERED".equals(status)) {
            JsonNode candidate = result.path("answer");
            recheckFailed |= evidenceIds.isEmpty() || citations.isEmpty() || !candidate.isObject()
                    || !candidate.path("explanation").isString() || candidate.path("explanation").asString().isBlank();
            for (String key : List.of("conditions", "exceptions", "needs_check", "citations")) {
                JsonNode values = candidate.path(key);
                if (!values.isArray()) {
                    recheckFailed = true;
                } else {
                    for (JsonNode value : values) {
                        if (!value.isString() || value.asString().isBlank()) recheckFailed = true;
                    }
                }
            }
        }
        JsonNode answer = "ANSWERED".equals(status) ? result.path("answer") : null;
        if (recheckFailed) {
            status = "ANSWER_HOLD";
            reasons.add("CORE_RECHECK_FAILED");
            answer = null;
        }
        // 결정 요청으로 거절한 응답에는 근거를 싣지 않는다(결정 요청에 근거를 내주지 않는다).
        List<String> verifiedIds = "DECISION_DECLINED".equals(status) ? List.of()
                : evidenceIds.stream().filter(id -> itemsById.containsKey(id) && rulesById.containsKey(id)).toList();
        ArrayNode evidence = body.putArray("evidence");
        for (String id : verifiedIds) {
            InternalPolicyApplicableState.Rule rule = rulesById.get(id);
            InternalPolicyApplicableState.ApprovedItem item = itemsById.get(id);
            ObjectNode entry = evidence.addObject();
            entry.put("ruleVersionId", id);
            entry.put("ruleKey", item.ruleKey());
            entry.put("instruction", item.instruction());
            entry.put("noticeId", state.selectedNotice() == null ? null : state.selectedNotice().noticeId());
            entry.put("noticeTitle", state.selectedNotice() == null ? null : state.selectedNotice().title());
            entry.put("evidenceText", rule.evidenceText());
            entry.put("jsonPointer", rule.jsonPointer());
            entry.put("evidenceHash", rule.evidenceHash());
            entry.set("structuredChange", item.structuredChange());
            // 설명이 실제로 인용한 근거인지(화면은 인용 근거를 '답변 근거'로 먼저 보여 주고 나머지는 함께 검색된 근거로 접어 둔다)
            entry.put("cited", answer != null && citations.contains(id));
        }
        return finish(body, view, request, question, userId, activeRole, traceId, started, status, reasons, answer, verifiedIds, result);
    }

    private ObjectNode finish(ObjectNode body, ConsultationService.View view, Request request, String question, String userId, String activeRole,
                              String traceId, long started, String status, List<String> reasons, JsonNode answer, List<String> evidenceIds,
                              JsonNode result) {
        if (!body.has("evidence")) body.putArray("evidence");
        body.put("status", status);
        ArrayNode reasonArray = body.putArray("reasons");
        reasons.forEach(reasonArray::add);
        if (answer != null && answer.isObject()) {
            ObjectNode copy = body.putObject("answer");
            copy.put("explanation", answer.path("explanation").stringValue());
            for (String key : List.of("conditions", "exceptions", "needs_check", "citations")) {
                ArrayNode array = copy.putArray("needs_check".equals(key) ? "needsCheck" : key);
                answer.path(key).forEach(value -> array.add(value.stringValue()));
            }
        } else {
            body.putNull("answer");
        }
        List<String> cited = new ArrayList<>();
        if (answer != null) answer.path("citations").forEach(value -> cited.add(value.stringValue()));
        ObjectNode usage = body.putObject("usage");
        int calls = result == null ? 0 : result.path("llm_calls").asInt(0);
        int inputTokens = result == null ? 0 : result.path("usage").path("input_tokens").asInt(0);
        int outputTokens = result == null ? 0 : result.path("usage").path("output_tokens").asInt(0);
        BigDecimal charged = result == null ? BigDecimal.ZERO : new BigDecimal(result.path("usage").path("charged_usd_estimate").asString().isBlank() ? "0" : result.path("usage").path("charged_usd_estimate").asString());
        usage.put("llmCalls", calls);
        usage.put("inputTokens", inputTokens);
        usage.put("outputTokens", outputTokens);
        usage.put("chargedUsdEstimate", charged);
        String model = result == null ? null : text(result.path("model"));
        body.put("model", model);
        body.put("searchVersion", result == null ? null : text(result.path("search_version")));
        body.put("ragVersion", result == null ? null : text(result.path("rag_version")));
        body.put("notice", "AI 설명은 승인된 근거를 읽는 것을 돕는 참고 자료입니다. 대출 승인·거절, 금리·한도 확정, 고객별 적용 확정이 아니며 직원이 원문으로 확인해야 합니다.");
        int latency = (int) ((System.nanoTime() - started) / 1_000_000);
        body.put("latencyMs", latency);
        jdbc.sql("""
                insert into consultation_answer_audit (answer_request_id, consultation_id, family_id, business_date, requested_by, active_role,
                    question_sha256, question_length, status, reasons, evidence_rule_version_ids, cited_rule_version_ids, search_version, rag_version,
                    model, llm_calls, input_tokens, output_tokens, charged_usd_estimate, latency_ms, requested_at, trace_id)
                values (:id, :consultation, :family, :businessDate, :user, :role, :hash, :length, :status, cast(:reasons as jsonb),
                    cast(:evidence as jsonb), cast(:cited as jsonb), :searchVersion, :ragVersion, :model, :calls, :inputTokens, :outputTokens,
                    :charged, :latency, :requestedAt, :trace)
                """)
                .param("id", body.path("answerRequestId").stringValue()).param("consultation", view.consultationId())
                .param("family", request.familyId()).param("businessDate", request.businessDate()).param("user", userId).param("role", activeRole)
                .param("hash", sha256(question)).param("length", question.length()).param("status", status)
                .param("reasons", mapper.writeValueAsString(body.path("reasons"))).param("evidence", mapper.writeValueAsString(evidenceIds))
                .param("cited", mapper.writeValueAsString(cited)).param("searchVersion", text(body.path("searchVersion")))
                .param("ragVersion", text(body.path("ragVersion"))).param("model", model).param("calls", calls)
                .param("inputTokens", inputTokens).param("outputTokens", outputTokens).param("charged", charged).param("latency", latency)
                .param("requestedAt", Timestamp.from(clock.instant())).param("trace", traceId == null ? "unavailable" : traceId)
                .update();
        return body;
    }

    /** 질문에 이 상담 건의 기업명·신청·상담 ID·신청 금액 또는 금액·사업자번호 형식이 있으면 그 종류를 돌려준다(값은 돌려주지 않는다). */
    static List<String> identifierKinds(String question, ConsultationService.View view) {
        Set<String> kinds = new LinkedHashSet<>();
        String compact = question.replaceAll("\\s+", "");
        for (String term : new String[] {view.application().companyName(), view.applicationId(), view.companyId(), view.consultationId()}) {
            if (term != null && !term.isBlank() && compact.contains(term.replaceAll("\\s+", ""))) kinds.add("REGISTERED_IDENTIFIER");
        }
        String amount = Long.toString(view.application().requestedAmountKrw());
        if (compact.replace(",", "").contains(amount)) kinds.add("REQUESTED_AMOUNT");
        if (AMOUNT.matcher(question).find()) kinds.add("AMOUNT");
        if (BUSINESS_NUMBER.matcher(question).find()) kinds.add("BUSINESS_NUMBER");
        return List.copyOf(kinds);
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.stringValue();
    }

    private static String sha256(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
