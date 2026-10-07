package com.trustagent.core.preparation;

import com.trustagent.core.internalpolicy.query.InternalPolicyApplicableService;
import com.trustagent.core.internalpolicy.query.InternalPolicyApplicableState;
import com.trustagent.core.internalpolicy.query.InternalPolicyQueryException;
import com.trustagent.core.json.CanonicalJsonHasher;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI 서비스 산출물 기록(TASK-015, ADR-012). AI가 보낸 "승인된 항목이다", "사용 가능하다"는 주장을 믿지 않는다.
 * 검사 순서: 인증(필터) → schema → 준비안 ID 해시 → 매핑·필수 여부·상태 → 보류 섹션 공란 → 저장 전 재확인(기존 기록 유무와 무관하게 항상)
 * → 기존 행과 해시 비교 → 저장. READY 섹션은 재확인 C1~C5를 전부 통과해야 하고, HOLD 섹션은 공통 검사 뒤 Core가 다시 본 상태와 함께 저장한다.
 * 기록은 사용 허가가 아니다. 재확인과 저장은 SERIALIZABLE 트랜잭션 하나이며 직렬화 실패(40001)만 1회 재시도한다.
 */
@Service
public class ConsultationPreparationService {

    public enum Outcome { RECORDED, ALREADY_RECORDED }

    public record Result(String preparationId, String runId, Outcome outcome, Instant recordedAt) {}

    static final String ID_PREFIX = "consultation-preparation:";
    static final Set<String> SERVICE_REPORTED_REASONS = Set.of(
            "TOOL_AUTH_FAILED", "CORE_UNAVAILABLE", "CORE_TIMEOUT", "EVIDENCE_UNAVAILABLE", "TOOL_RESPONSE_INVALID");
    private static final Set<String> TOP_FIELDS = Set.of(
            "preparation_id", "run_id", "assembler_version", "messages_hash", "family_mapping_hash", "application",
            "business_date", "consultation_id", "status", "preparation_complete", "sections");
    private static final Set<String> APPLICATION_FIELDS = Set.of("application_id", "company_id", "product_key", "source_hash");
    private static final Set<String> SECTION_FIELDS = Set.of(
            "family_id", "required", "status", "hold_kind", "hold_claim_basis", "evaluated_at", "selected_notice_id",
            "approved_checklist_version_id", "decision_id", "item_rule_version_ids", "item_evidence_hashes",
            "blocking_reasons", "tool_response_hash");
    private static final Pattern PREPARATION_ID = Pattern.compile("^consultation-preparation:sha256:[a-f0-9]{64}$");
    private static final Pattern RUN_ID = Pattern.compile("^consultation-preparation-run:[a-f0-9]{32}$");
    private static final Pattern SHA256 = Pattern.compile("^sha256:[a-f0-9]{64}$");
    private static final Pattern ASSEMBLER = Pattern.compile("^preparation-assembler-v[1-9][0-9]*$");
    private static final Pattern APPLICATION_ID = Pattern.compile("^SW-APPLICATION-[0-9]{3}$");
    private static final Pattern COMPANY_ID = Pattern.compile("^SW-COMPANY-[0-9]{3}$");
    private static final Pattern PRODUCT_KEY = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern FAMILY_ID = Pattern.compile("^SIN-[A-Z0-9-]+$");
    private static final Pattern NOTICE_ID = Pattern.compile("^SIN-[A-Z0-9-]+-V[1-9][0-9]*$");
    private static final Pattern CHECKLIST_VERSION_ID = Pattern.compile("^approved-checklist:[a-f0-9]{32}$");
    private static final Pattern DECISION_ID = Pattern.compile("^review-decision:[a-f0-9]{32}$");
    private static final Pattern RULE_VERSION_ID = Pattern.compile("^policy-rule:sha256:[a-f0-9]{64}$");
    private static final Pattern REASON_CODE = Pattern.compile("^[A-Z][A-Z0-9_]+$");
    private static final Set<String> FAILED_CODES = Set.of("SERIALIZATION_FAILED", "RECORD_WRITE_FAILED");

    private final ConsultationPreparationRepository repository;
    private final InternalPolicyApplicableService applicable;
    private final TransactionTemplate serializable;
    private final TransactionTemplate requiresNew;
    private final CanonicalJsonHasher hasher;
    private final ObjectMapper mapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ConsultationPreparationService(
            JdbcClient jdbc, ObjectMapper mapper, PlatformTransactionManager manager,
            InternalPolicyApplicableService applicable, Clock clock) {
        this(new ConsultationPreparationRepository(jdbc, mapper), mapper, manager, applicable, clock);
    }

    /** 테스트가 저장소를 바꿔 동시 저장 충돌(23505) 경로를 시간에 의존하지 않고 재현할 수 있게 한다. */
    ConsultationPreparationService(
            ConsultationPreparationRepository repository, ObjectMapper mapper, PlatformTransactionManager manager,
            InternalPolicyApplicableService applicable, Clock clock) {
        this.repository = repository;
        this.applicable = applicable;
        this.serializable = new TransactionTemplate(manager);
        this.serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.requiresNew = new TransactionTemplate(manager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.hasher = new CanonicalJsonHasher(mapper);
        this.mapper = mapper;
        this.clock = clock;
    }

    public Result record(JsonNode body, String serviceId, String tokenScope, String traceId) {
        Instant startedAt = clock.instant();
        if (body == null || !body.isObject()) {
            throw new ConsultationPreparationException("INVALID_REQUEST", "요청 본문은 JSON 객체여야 합니다.");
        }
        String runId = optionalText(body, "run_id");
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            throw new ConsultationPreparationException("INVALID_REQUEST", "run_id 형식이 올바르지 않습니다.");
        }
        if (repository.runExists(runId)) {
            throw new ConsultationPreparationException("RUN_ID_CONFLICT", "이미 사용한 run_id입니다. 같은 준비안을 다시 기록하려면 새 run_id를 쓰세요.");
        }
        String preparationId = body.get("preparation_id") != null && body.get("preparation_id").isString()
                ? body.get("preparation_id").stringValue() : null;
        Result result;
        Parsed parsed = null;
        List<ConsultationPreparationRepository.SectionRow> rechecked = new ArrayList<>();
        try {
            parsed = parse(body);
            result = executeWithRetry(parsed, serviceId, tokenScope, rechecked);
        } catch (ConsultationPreparationException exception) {
            String outcome = FAILED_CODES.contains(exception.code()) ? "FAILED" : "REJECTED";
            recordRun(runId, preparationId, serviceId, tokenScope, outcome, exception.code(), traceId, startedAt,
                    sectionEvaluations(parsed, rechecked));
            throw exception;
        } catch (RuntimeException exception) {
            recordRun(runId, preparationId, serviceId, tokenScope, "FAILED", "RECORD_WRITE_FAILED", traceId, startedAt,
                    sectionEvaluations(parsed, rechecked));
            throw new ConsultationPreparationException("RECORD_WRITE_FAILED",
                    "기록 저장 중 DB 처리에 실패했습니다. 준비안과 섹션 어느 것도 저장되지 않았습니다.", exception);
        }
        recordRun(runId, result.preparationId(), serviceId, tokenScope, result.outcome().name(), null, traceId, startedAt,
                sectionEvaluations(parsed, rechecked));
        return result;
    }

    /**
     * 실행 단위 추적 값. AI가 보낸 섹션별 평가 시각·Tool 응답 해시(준비안 ID 계산에서는 빠지는 값)와 이번 실행의 재확인 결과를
     * 실행 기록에 남긴다. 준비안·섹션 행은 첫 기록 그대로이고 이후 실행은 여기에만 쌓인다. 본문 해석 전 거부면 빈 배열이다.
     */
    private String sectionEvaluations(Parsed parsed, List<ConsultationPreparationRepository.SectionRow> rechecked) {
        if (parsed == null) {
            return "[]";
        }
        Map<String, ConsultationPreparationRepository.SectionRow> byFamily = new HashMap<>();
        rechecked.forEach(row -> byFamily.put(row.familyId(), row));
        List<Map<String, Object>> evaluations = new ArrayList<>();
        for (Section section : parsed.sections()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("family_id", section.familyId());
            entry.put("status", section.ready() ? "READY" : "HOLD");
            entry.put("evaluated_at", section.evaluatedAt() == null ? null : section.evaluatedAt().toString());
            entry.put("tool_response_hash", section.toolResponseHash());
            ConsultationPreparationRepository.SectionRow row = byFamily.get(section.familyId());
            entry.put("recheck_usable", row == null ? null : row.recheckUsable());
            entry.put("recheck_reasons", row == null ? List.of() : row.recheckReasons());
            evaluations.add(entry);
        }
        return mapper.writeValueAsString(evaluations);
    }

    // ---- 트랜잭션과 재시도 ----

    private Result executeWithRetry(Parsed parsed, String serviceId, String tokenScope,
            List<ConsultationPreparationRepository.SectionRow> rechecked) {
        RuntimeException first = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return serializable.execute(status -> doRecord(parsed, serviceId, tokenScope, rechecked));
            } catch (ConsultationPreparationException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                if (sqlState(exception, "23505")) {
                    // 동시에 같은 ID가 먼저 저장됐다. 앞선 재확인은 취소된 트랜잭션의 것이므로 새 트랜잭션에서 재확인부터 다시 한다.
                    return resolveConcurrentInsert(parsed, serviceId, tokenScope, rechecked);
                }
                if (!sqlState(exception, "40001")) {
                    throw exception;
                }
                first = exception;
            }
        }
        throw new ConsultationPreparationException("SERIALIZATION_FAILED",
                "저장 전 재확인과 저장이 동시 변경과 충돌해 1회 재시도 뒤에도 실패했습니다. 다시 실행하세요.", first);
    }

    /**
     * 동시 저장 충돌(23505) 해결. 실패한 시도의 재확인은 취소된 트랜잭션에서 한 것이라 믿지 않는다. 새 SERIALIZABLE 트랜잭션에서
     * 매핑 확인(M1~M5)과 READY 섹션 재확인(C1~C5), HOLD 재조회를 다시 한 뒤에만 기존 기록을 비교해 ALREADY_RECORDED로 답한다.
     * 재시도 횟수는 늘리지 않는다. 이 한 번의 해결 시도에서 다시 23505나 40001이 나면 FAILED로 끝낸다.
     */
    private Result resolveConcurrentInsert(Parsed parsed, String serviceId, String tokenScope,
            List<ConsultationPreparationRepository.SectionRow> rechecked) {
        try {
            Result result = serializable.execute(status -> doRecord(parsed, serviceId, tokenScope, rechecked));
            if (result == null) {
                throw new ConsultationPreparationException("RECORD_WRITE_FAILED", "동시 저장 충돌 뒤 기존 기록을 찾지 못했습니다.");
            }
            return result;
        } catch (ConsultationPreparationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (sqlState(exception, "23505")) {
                throw new ConsultationPreparationException("RECORD_WRITE_FAILED",
                        "동시 저장 충돌 해결 중 다시 충돌했습니다. 다시 실행하세요.", exception);
            }
            if (sqlState(exception, "40001")) {
                throw new ConsultationPreparationException("SERIALIZATION_FAILED",
                        "동시 저장 충돌 해결 중 재확인이 동시 변경과 충돌했습니다. 다시 실행하세요.", exception);
            }
            throw exception;
        }
    }

    /** 한 트랜잭션(SERIALIZABLE): 매핑·상태 검사 → HOLD 참고 재조회 → READY 재확인 → 기존 행 비교 → 저장. */
    private Result doRecord(Parsed parsed, String serviceId, String tokenScope,
            List<ConsultationPreparationRepository.SectionRow> rechecked) {
        verifyMapping(parsed);
        List<ConsultationPreparationRepository.SectionRow> rows = new ArrayList<>();
        rechecked.clear(); // 재시도면 이번 시도의 재확인 결과로 바꾼다
        for (Section section : parsed.sections()) {
            ConsultationPreparationRepository.SectionRow row = section.ready() ? recheckReady(parsed, section) : recheckHold(parsed, section);
            rows.add(row);
            rechecked.add(row);
        }
        Optional<ConsultationPreparationRepository.StoredPreparation> existing = repository.findPreparation(parsed.preparationId());
        if (existing.isPresent()) {
            if (!existing.get().contentHash().equals(parsed.contentHash())) {
                throw new ConsultationPreparationException("PREPARATION_CONFLICT", "같은 준비안 ID에 다른 내용이 이미 기록돼 있습니다.");
            }
            return new Result(parsed.preparationId(), parsed.runId(), Outcome.ALREADY_RECORDED, existing.get().recordedAt());
        }
        Instant recordedAt = clock.instant();
        int requiredHoldCount = (int) parsed.sections().stream().filter(section -> section.required() && !section.ready()).count();
        repository.insertPreparation(parsed.preparationId(), serviceId, tokenScope, parsed.applicationId(), parsed.companyId(),
                parsed.productKey(), parsed.sourceHash(), parsed.businessDate(), parsed.status(), parsed.preparationComplete(),
                parsed.assemblerVersion(), parsed.messagesHash(), parsed.familyMappingHash(), parsed.sections().size(),
                requiredHoldCount, parsed.consultationId(), parsed.contentHash(), recordedAt);
        for (ConsultationPreparationRepository.SectionRow row : rows) {
            repository.insertSection(parsed.preparationId(), row);
        }
        return new Result(parsed.preparationId(), parsed.runId(), Outcome.RECORDED, recordedAt);
    }

    // ---- M1~M5: 승인된 매핑과 필수 여부, 상태 ----

    private void verifyMapping(Parsed parsed) {
        ConsultationPreparationRepository.ActiveMapping mapping = repository.activeMapping(parsed.productKey())
                .orElseThrow(() -> new ConsultationPreparationException("MAPPING_NOT_LOADED", "승인된 공문군 매핑이 적재돼 있지 않습니다."));
        if (!mapping.mappingHash().equals(parsed.familyMappingHash())) {
            throw new ConsultationPreparationException("MAPPING_MISMATCH",
                    "요청의 공문군 매핑 해시가 Core의 활성 매핑(" + mapping.mappingVersion() + ")과 다릅니다.");
        }
        if (mapping.families().isEmpty()) {
            throw new ConsultationPreparationException("PRODUCT_NOT_MAPPED", "매핑에 없는 상품입니다: " + parsed.productKey());
        }
        Map<String, ConsultationPreparationRepository.MappingFamily> byFamily = new LinkedHashMap<>();
        mapping.families().forEach(family -> byFamily.put(family.familyId(), family));
        for (Section section : parsed.sections()) {
            ConsultationPreparationRepository.MappingFamily family = byFamily.get(section.familyId());
            if (family == null) {
                throw new ConsultationPreparationException("SECTION_NOT_IN_MAPPING", "매핑에 없는 공문군 섹션입니다: " + section.familyId());
            }
            if (family.required() != section.required()) {
                throw new ConsultationPreparationException("REQUIRED_FLAG_MISMATCH",
                        "공문군의 필수 여부가 승인된 매핑과 다릅니다: " + section.familyId());
            }
        }
        List<String> requiredFamilies = mapping.families().stream()
                .filter(ConsultationPreparationRepository.MappingFamily::required)
                .map(ConsultationPreparationRepository.MappingFamily::familyId)
                .toList();
        for (String family : requiredFamilies) {
            if (parsed.sections().stream().noneMatch(section -> section.familyId().equals(family))) {
                throw new ConsultationPreparationException("REQUIRED_SECTION_MISSING", "필수 공문군 섹션이 없습니다: " + family);
            }
        }
        if (requiredFamilies.isEmpty() && "READY".equals(parsed.status())) {
            throw new ConsultationPreparationException("NO_REQUIRED_FAMILY", "필수 공문군이 없는 매핑에서는 준비 완료(READY)를 허용하지 않습니다.");
        }
        String computed = computeStatus(parsed.sections(), requiredFamilies);
        if (!computed.equals(parsed.status()) || parsed.preparationComplete() != "READY".equals(computed)) {
            throw new ConsultationPreparationException("PREPARATION_STATUS_INVALID",
                    "요청의 상태(" + parsed.status() + ")가 필수 섹션으로 계산한 상태(" + computed + ")와 다릅니다.");
        }
    }

    static String computeStatus(List<Section> sections, List<String> requiredFamilies) {
        if (requiredFamilies.isEmpty()) {
            return "HOLD";
        }
        long ready = sections.stream().filter(section -> requiredFamilies.contains(section.familyId()) && section.ready()).count();
        if (ready == requiredFamilies.size()) {
            return "READY";
        }
        return ready == 0 ? "HOLD" : "PARTIAL";
    }

    // ---- C0~C5: READY 섹션 재확인(기존 기록 유무와 무관하게 항상) ----

    private ConsultationPreparationRepository.SectionRow recheckReady(Parsed parsed, Section section) {
        InternalPolicyApplicableState state;
        try {
            state = applicable.get(section.familyId(), parsed.businessDate().toString(), null);
        } catch (InternalPolicyQueryException exception) {
            throw new ConsultationPreparationException("PREPARATION_NOT_USABLE",
                    "재확인 조회가 거부됐습니다(" + exception.code() + "): " + section.familyId());
        }
        if (!state.internalChecklistUseAllowed() || state.approvedChecklist() == null) {
            throw new ConsultationPreparationException("PREPARATION_NOT_USABLE",
                    "저장 직전 재확인에서 사용 불가입니다: " + section.familyId() + " " + state.blockingReasons());
        }
        InternalPolicyApplicableState.ApprovedChecklist checklist = state.approvedChecklist();
        if (!checklist.approvedChecklistVersionId().equals(section.approvedChecklistVersionId())
                || !checklist.decisionId().equals(section.decisionId())) {
            throw new ConsultationPreparationException("PREPARATION_STALE",
                    "승인 checklist version 또는 결정 ID가 현재 상태와 다릅니다: " + section.familyId());
        }
        List<String> currentRuleIds = checklist.items().stream().map(InternalPolicyApplicableState.ApprovedItem::sourceRuleVersionId).toList();
        if (!currentRuleIds.equals(section.itemRuleVersionIds())) {
            throw new ConsultationPreparationException("PREPARATION_STALE",
                    "항목 규칙 version 목록(순서 포함)이 현재 승인 checklist와 다릅니다: " + section.familyId());
        }
        Map<String, String> evidenceHashes = new HashMap<>();
        state.rules().forEach(rule -> evidenceHashes.put(rule.ruleVersionId(), rule.evidenceHash()));
        for (int i = 0; i < section.itemRuleVersionIds().size(); i++) {
            String expected = evidenceHashes.get(section.itemRuleVersionIds().get(i));
            if (expected == null || !expected.equals(section.itemEvidenceHashes().get(i))) {
                throw new ConsultationPreparationException("PREPARATION_STALE",
                        "항목 근거 해시가 현재 규칙 근거와 다릅니다: " + section.familyId() + " 항목 " + i);
            }
        }
        if (state.selectedNotice() == null || !state.selectedNotice().noticeId().equals(section.selectedNoticeId())) {
            throw new ConsultationPreparationException("PREPARATION_STALE", "선택 공문이 현재 상태와 다릅니다: " + section.familyId());
        }
        return new ConsultationPreparationRepository.SectionRow(
                section.familyId(), section.required(), "READY", null, null, section.evaluatedAt(),
                section.selectedNoticeId(), section.approvedChecklistVersionId(), section.decisionId(),
                section.itemRuleVersionIds(), section.itemEvidenceHashes(), List.of(),
                true, List.of(), section.toolResponseHash());
    }

    /** HOLD 섹션은 상태를 고치지 않고 Core가 지금 본 상태를 참고로 함께 적는다. 사유의 진실성은 보증하지 않는다. */
    private ConsultationPreparationRepository.SectionRow recheckHold(Parsed parsed, Section section) {
        Boolean recheckUsable;
        List<String> recheckReasons;
        try {
            InternalPolicyApplicableState state = applicable.get(section.familyId(), parsed.businessDate().toString(), null);
            recheckUsable = state.internalChecklistUseAllowed();
            recheckReasons = state.blockingReasons();
        } catch (InternalPolicyQueryException exception) {
            recheckUsable = false;
            recheckReasons = List.of(exception.code());
        }
        return new ConsultationPreparationRepository.SectionRow(
                section.familyId(), section.required(), "HOLD", section.holdKind(), section.holdClaimBasis(), section.evaluatedAt(),
                section.selectedNoticeId(), null, null, List.of(), List.of(), section.blockingReasons(),
                recheckUsable, recheckReasons, section.toolResponseHash());
    }

    // ---- schema 검사와 준비안 ID 해시 ----

    record Section(
            String familyId, boolean required, boolean ready, String holdKind, String holdClaimBasis, Instant evaluatedAt,
            String selectedNoticeId, String approvedChecklistVersionId, String decisionId,
            List<String> itemRuleVersionIds, List<String> itemEvidenceHashes, List<String> blockingReasons, String toolResponseHash) {}

    record Parsed(
            String preparationId, String runId, String assemblerVersion, String messagesHash, String familyMappingHash,
            String applicationId, String companyId, String productKey, String sourceHash, LocalDate businessDate,
            String consultationId, String status, boolean preparationComplete, List<Section> sections, String contentHash) {}

    Parsed parse(JsonNode body) {
        rejectUnknown(body, TOP_FIELDS, "본문");
        String preparationId = requireText(body, "preparation_id", PREPARATION_ID);
        String runId = requireText(body, "run_id", RUN_ID);
        String assembler = requireText(body, "assembler_version", ASSEMBLER);
        String messagesHash = requireText(body, "messages_hash", SHA256);
        String mappingHash = requireText(body, "family_mapping_hash", SHA256);
        JsonNode application = body.get("application");
        if (application == null || !application.isObject()) {
            throw invalid("application은 객체여야 합니다.");
        }
        rejectUnknown(application, APPLICATION_FIELDS, "application");
        String applicationId = requireText(application, "application_id", APPLICATION_ID);
        String companyId = requireText(application, "company_id", COMPANY_ID);
        String productKey = requireText(application, "product_key", PRODUCT_KEY);
        String sourceHash = requireText(application, "source_hash", SHA256);
        LocalDate businessDate;
        try {
            businessDate = LocalDate.parse(requireText(body, "business_date", null));
        } catch (DateTimeParseException exception) {
            throw invalid("business_date 형식이 올바르지 않습니다.");
        }
        String consultationId = optionalText(body, "consultation_id");
        if (consultationId != null && (consultationId.isBlank() || consultationId.length() > 64)) {
            throw invalid("consultation_id는 1~64자여야 합니다.");
        }
        String status = requireText(body, "status", null);
        if (!Set.of("READY", "PARTIAL", "HOLD").contains(status)) {
            throw invalid("status는 READY, PARTIAL, HOLD 가운데 하나여야 합니다.");
        }
        boolean complete = requireBoolean(body, "preparation_complete");
        if (complete != "READY".equals(status)) {
            throw new ConsultationPreparationException("PREPARATION_STATUS_INVALID", "preparation_complete는 READY일 때만 true여야 합니다.");
        }
        JsonNode sections = body.get("sections");
        if (sections == null || !sections.isArray() || sections.isEmpty()) {
            throw invalid("sections는 비어 있지 않은 배열이어야 합니다.");
        }
        List<Section> parsedSections = new ArrayList<>();
        Set<String> families = new java.util.HashSet<>();
        for (JsonNode node : sections) {
            Section section = parseSection(node);
            if (!families.add(section.familyId())) {
                throw invalid("같은 공문군 섹션이 두 번 있습니다: " + section.familyId());
            }
            parsedSections.add(section);
        }
        String contentHash = contentHash(body);
        if (!preparationId.equals(ID_PREFIX + contentHash)) {
            throw new ConsultationPreparationException("PREPARATION_ID_MISMATCH",
                    "preparation_id가 본문 내용의 해시와 다릅니다.");
        }
        return new Parsed(preparationId, runId, assembler, messagesHash, mappingHash, applicationId, companyId, productKey,
                sourceHash, businessDate, consultationId, status, complete, parsedSections, contentHash);
    }

    private Section parseSection(JsonNode node) {
        if (!node.isObject()) {
            throw invalid("섹션은 객체여야 합니다.");
        }
        rejectUnknown(node, SECTION_FIELDS, "섹션");
        for (String field : SECTION_FIELDS) {
            if (!node.has(field)) {
                throw invalid("섹션 필드가 없습니다: " + field);
            }
        }
        String familyId = requireText(node, "family_id", FAMILY_ID);
        boolean required = requireBoolean(node, "required");
        String status = requireText(node, "status", null);
        String holdKind = optionalText(node, "hold_kind");
        String claimBasis = optionalText(node, "hold_claim_basis");
        String evaluatedAtText = optionalText(node, "evaluated_at");
        Instant evaluatedAt = null;
        if (evaluatedAtText != null) {
            try {
                evaluatedAt = Instant.parse(evaluatedAtText);
            } catch (DateTimeParseException exception) {
                throw invalid("섹션 evaluated_at 형식이 올바르지 않습니다: " + familyId);
            }
        }
        String noticeId = optionalText(node, "selected_notice_id");
        if (noticeId != null && !NOTICE_ID.matcher(noticeId).matches()) {
            throw invalid("선택 공문 ID 형식이 올바르지 않습니다: " + familyId);
        }
        String versionId = optionalText(node, "approved_checklist_version_id");
        String decisionId = optionalText(node, "decision_id");
        List<String> ruleIds = stringList(node, "item_rule_version_ids", RULE_VERSION_ID);
        List<String> evidenceHashes = stringList(node, "item_evidence_hashes", SHA256);
        List<String> reasons = stringList(node, "blocking_reasons", REASON_CODE);
        String toolHash = optionalText(node, "tool_response_hash");
        if (toolHash != null && !SHA256.matcher(toolHash).matches()) {
            throw invalid("tool_response_hash 형식이 올바르지 않습니다: " + familyId);
        }
        if ("READY".equals(status)) {
            if (holdKind != null || claimBasis != null) {
                throw invalid("READY 섹션에는 보류 표시가 없어야 합니다: " + familyId);
            }
            if (versionId == null || !CHECKLIST_VERSION_ID.matcher(versionId).matches()
                    || decisionId == null || !DECISION_ID.matcher(decisionId).matches()
                    || noticeId == null || evaluatedAt == null || toolHash == null) {
                throw invalid("READY 섹션에는 version·결정·선택 공문 ID, 평가 시각, Tool 응답 해시가 있어야 합니다: " + familyId);
            }
            if (ruleIds.isEmpty() || ruleIds.size() != evidenceHashes.size() || !reasons.isEmpty()) {
                throw invalid("READY 섹션의 항목·근거 해시 수가 맞지 않거나 사유가 있습니다: " + familyId);
            }
            return new Section(familyId, required, true, null, null, evaluatedAt, noticeId, versionId, decisionId,
                    ruleIds, evidenceHashes, List.of(), toolHash);
        }
        if (!"HOLD".equals(status)) {
            throw invalid("섹션 status는 READY 또는 HOLD여야 합니다: " + familyId);
        }
        // 보류 섹션 공란 검사(HOLD_SECTION_INVALID): 항목·근거·version·결정이 없고 보류 종류·근거·사유가 있어야 한다.
        if (!ruleIds.isEmpty() || !evidenceHashes.isEmpty() || versionId != null || decisionId != null) {
            throw hold("보류 섹션에는 항목, 근거 해시, 승인 checklist version, 결정 ID가 없어야 합니다: " + familyId);
        }
        if (holdKind == null || reasons.isEmpty()) {
            throw hold("보류 섹션에는 hold_kind와 사유 코드가 1개 이상 있어야 합니다: " + familyId);
        }
        if ("CORE_DECISION".equals(holdKind)) {
            if (!"CORE_REPORTED".equals(claimBasis)) {
                throw hold("CORE_DECISION 보류의 근거는 CORE_REPORTED여야 합니다: " + familyId);
            }
        } else if ("UNVERIFIED".equals(holdKind)) {
            if (!"SERVICE_REPORTED".equals(claimBasis)) {
                throw hold("UNVERIFIED 보류의 근거는 SERVICE_REPORTED여야 합니다: " + familyId);
            }
            for (String reason : reasons) {
                if (!SERVICE_REPORTED_REASONS.contains(reason)) {
                    throw hold("UNVERIFIED 보류의 사유 코드가 서비스 코드 목록 밖입니다: " + reason);
                }
            }
        } else {
            throw hold("hold_kind는 CORE_DECISION 또는 UNVERIFIED여야 합니다: " + familyId);
        }
        return new Section(familyId, required, false, holdKind, claimBasis, evaluatedAt, noticeId, null, null,
                List.of(), List.of(), reasons, toolHash);
    }

    /**
     * preparation_id = "consultation-preparation:" + sha256(canonical(본문 - preparation_id, run_id, consultation_id,
     * sections[].evaluated_at, sections[].tool_response_hash)). 평가 시각과 Tool 응답 해시(응답에 평가 시각이 들어 있음)는
     * 실행마다 바뀌므로 뺀다. 같은 입력·같은 Core 상태면 같은 ID가 된다. AI 서비스(ids.py)와 같은 규칙이다.
     */
    String contentHash(JsonNode body) {
        ObjectNode subject = (ObjectNode) body.deepCopy();
        subject.remove("preparation_id");
        subject.remove("run_id");
        subject.remove("consultation_id");
        JsonNode sections = subject.get("sections");
        if (sections != null && sections.isArray()) {
            for (JsonNode section : sections) {
                if (section instanceof ObjectNode object) {
                    object.remove("evaluated_at");
                    object.remove("tool_response_hash");
                }
            }
        }
        try {
            return hasher.canonicalize(subject).sha256();
        } catch (IllegalArgumentException exception) {
            throw invalid("본문을 canonical JSON으로 만들 수 없습니다(부동소수점 등).");
        }
    }

    // ---- 실행 기록(요청당 1건, REQUIRES_NEW) ----

    private void recordRun(String runId, String preparationId, String serviceId, String tokenScope, String outcome,
            String errorCode, String traceId, Instant startedAt, String sectionEvaluations) {
        try {
            requiresNew.executeWithoutResult(status -> repository.insertRun(
                    runId, preparationId != null && PREPARATION_ID.matcher(preparationId).matches() ? preparationId : null,
                    serviceId, tokenScope, outcome, errorCode, traceId, startedAt, clock.instant(), sectionEvaluations));
        } catch (RuntimeException auditFailure) {
            throw new ConsultationPreparationException("FAILURE_AUDIT_WRITE_FAILED",
                    "실행 기록을 저장할 수 없습니다 (원래 결과: " + outcome + (errorCode == null ? "" : " " + errorCode) + ")", auditFailure);
        }
    }

    // ---- helpers ----

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String where) {
        List<String> unknown = new ArrayList<>();
        node.propertyNames().forEach(name -> {
            if (!allowed.contains(name)) unknown.add(name);
        });
        if (!unknown.isEmpty()) {
            throw invalid(where + "에 허용되지 않는 필드가 있습니다: " + String.join(", ", unknown));
        }
    }

    private static String requireText(JsonNode node, String field, Pattern pattern) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw invalid("필수 문자열 필드가 없습니다: " + field);
        }
        if (pattern != null && !pattern.matcher(value.stringValue()).matches()) {
            throw invalid("필드 형식이 올바르지 않습니다: " + field);
        }
        return value.stringValue();
    }

    private static boolean requireBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid("필수 boolean 필드가 없습니다: " + field);
        }
        return value.booleanValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw invalid("문자열이어야 합니다: " + field);
        }
        return value.stringValue();
    }

    private static List<String> stringList(JsonNode node, String field, Pattern pattern) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw invalid("배열이어야 합니다: " + field);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isString() || !pattern.matcher(element.stringValue()).matches()) {
                throw invalid("배열 원소 형식이 올바르지 않습니다: " + field);
            }
            values.add(element.stringValue());
        }
        return values;
    }

    private static ConsultationPreparationException invalid(String message) {
        return new ConsultationPreparationException("INVALID_REQUEST", message);
    }

    private static ConsultationPreparationException hold(String message) {
        return new ConsultationPreparationException("HOLD_SECTION_INVALID", message);
    }

    private static boolean sqlState(Throwable exception, String state) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
