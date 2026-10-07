package com.trustagent.core.tool;

import com.trustagent.core.internalpolicy.query.InternalPolicyApplicableService;
import com.trustagent.core.internalpolicy.query.InternalPolicyApplicableState;
import com.trustagent.core.internalpolicy.BusinessTimePolicy;
import com.trustagent.core.internalpolicy.query.InternalPolicyQueryException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 읽기 전용 Tool 2개. 사용 허용 여부는 Core의 적용 공문 조회가 결정하고 이 서비스는 그 결과를 줄여서 전달할 뿐이다.
 * 사용 불가 상태에서는 사유만 주고 항목과 근거 ID를 주지 않는다. 근거 Tool은 소속과 사용 가능 여부를 다시 확인한다.
 */
@Service
public class ToolService {

    public static final String APPLICABLE_CHECKLIST = "applicable_checklist";
    public static final String RULE_EVIDENCE = "rule_evidence";
    public static final List<String> ALLOWLIST = List.of(APPLICABLE_CHECKLIST, RULE_EVIDENCE);

    private static final Set<String> APPLICABLE_FIELDS = Set.of("familyId", "businessDate", "consultationId");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("familyId", "ruleVersionId", "consultationId");

    private final InternalPolicyApplicableService applicable;
    private final ToolCallAuditRecorder recorder;
    private final BusinessTimePolicy businessTimePolicy;
    private final Clock clock;

    public ToolService(InternalPolicyApplicableService applicable, ToolCallAuditRecorder recorder,
            BusinessTimePolicy businessTimePolicy, Clock clock) {
        this.applicable = applicable;
        this.recorder = recorder;
        this.businessTimePolicy = businessTimePolicy;
        this.clock = clock;
    }

    public Object call(String toolName, JsonNode body, String serviceId, String traceId) {
        if (!ALLOWLIST.contains(toolName)) {
            audit(serviceId, toolName, null, null, null, "TOOL_NOT_FOUND", null, List.of(), traceId);
            throw new ToolApiException("TOOL_NOT_FOUND", "허용 목록에 없는 tool입니다: " + toolName);
        }
        return switch (toolName) {
            case APPLICABLE_CHECKLIST -> applicableChecklist(body, serviceId, traceId);
            case RULE_EVIDENCE -> ruleEvidence(body, serviceId, traceId);
            default -> throw new IllegalStateException(toolName);
        };
    }

    private ToolResponses.ApplicableChecklist applicableChecklist(JsonNode body, String serviceId, String traceId) {
        String familyId = null;
        String consultationId = null;
        try {
            requireFields(body, APPLICABLE_FIELDS, List.of("familyId"));
            familyId = text(body, "familyId");
            consultationId = optionalText(body, "consultationId");
            String businessDate = optionalText(body, "businessDate");
            // knownAt은 schema에 없다(위 requireFields에서 400). 조회는 항상 현재 시각 기준이다.
            InternalPolicyApplicableState state = query(familyId, businessDate);
            ToolResponses.ApplicableChecklist response = reduce(state);
            audit(serviceId, APPLICABLE_CHECKLIST, familyId, state.businessDate(), consultationId, "OK",
                    response.usable(), response.blockingReasons(), traceId);
            return response;
        } catch (ToolApiException exception) {
            audit(serviceId, APPLICABLE_CHECKLIST, familyId, null, consultationId, exception.code(), null, List.of(), traceId);
            throw exception;
        }
    }

    private ToolResponses.RuleEvidence ruleEvidence(JsonNode body, String serviceId, String traceId) {
        String familyId = null;
        String consultationId = null;
        try {
            requireFields(body, EVIDENCE_FIELDS, List.of("familyId", "ruleVersionId"));
            familyId = text(body, "familyId");
            consultationId = optionalText(body, "consultationId");
            String ruleVersionId = text(body, "ruleVersionId");
            // 서버가 다시 확인한다: 오늘 기준으로 이 공문군의 checklist가 사용 가능하고, 요청한 규칙이 그 항목의 근거여야 한다.
            InternalPolicyApplicableState state = query(familyId, null);
            boolean usable = state.internalChecklistUseAllowed();
            boolean belongs = usable && state.approvedChecklist() != null && state.approvedChecklist().items().stream()
                    .anyMatch(item -> ruleVersionId.equals(item.sourceRuleVersionId()));
            if (!belongs) {
                audit(serviceId, RULE_EVIDENCE, familyId, state.businessDate(), consultationId, "EVIDENCE_NOT_AVAILABLE",
                        usable, state.blockingReasons(), traceId);
                throw new ToolApiException("EVIDENCE_NOT_AVAILABLE",
                        "현재 사용 가능한 승인 checklist 항목의 근거가 아니어서 제공할 수 없습니다.");
            }
            InternalPolicyApplicableState.Rule rule = state.rules().stream()
                    .filter(candidate -> ruleVersionId.equals(candidate.ruleVersionId()))
                    .findFirst()
                    .orElseThrow(() -> new ToolApiException("EVIDENCE_NOT_AVAILABLE", "선택된 공문에 그 규칙의 근거가 없습니다."));
            ToolResponses.RuleEvidence response = new ToolResponses.RuleEvidence(
                    state.familyId(), state.selectedNotice().noticeId(), state.selectedNotice().effectiveFrom(),
                    rule.ruleVersionId(), rule.ruleKey(), rule.evidenceText(), rule.jsonPointer(), rule.evidenceHash(),
                    state.disclaimer());
            audit(serviceId, RULE_EVIDENCE, familyId, state.businessDate(), consultationId, "OK", true, List.of(), traceId);
            return response;
        } catch (ToolApiException exception) {
            if (!"EVIDENCE_NOT_AVAILABLE".equals(exception.code())) {
                audit(serviceId, RULE_EVIDENCE, familyId, null, consultationId, exception.code(), null, List.of(), traceId);
            }
            throw exception;
        }
    }

    /** businessDate가 없으면 오늘(서울 업무일)이다. knownAt은 항상 현재 시각(null)이다. */
    private InternalPolicyApplicableState query(String familyId, String businessDate) {
        try {
            String effectiveDate = businessDate == null ? businessTimePolicy.businessDate(clock.instant()).toString() : businessDate;
            return applicable.get(familyId, effectiveDate, null);
        } catch (InternalPolicyQueryException exception) {
            throw new ToolApiException(exception.code(), exception.getMessage(), exception);
        }
    }

    /** 사용 가능할 때만 항목을 싣는다. 사용 불가면 사유만 남기고 approvedChecklist는 null이다. */
    static ToolResponses.ApplicableChecklist reduce(InternalPolicyApplicableState state) {
        ToolResponses.SelectedNotice notice = state.selectedNotice() == null ? null : new ToolResponses.SelectedNotice(
                state.selectedNotice().noticeId(), state.selectedNotice().version(), state.selectedNotice().title(),
                state.selectedNotice().effectiveFrom(), state.selectedNotice().effectiveTo());
        ToolResponses.ApprovedChecklist checklist = null;
        if (state.internalChecklistUseAllowed() && state.approvedChecklist() != null) {
            var approved = state.approvedChecklist();
            checklist = new ToolResponses.ApprovedChecklist(
                    approved.approvedChecklistVersionId(), approved.origin(), approved.decisionId(),
                    approved.effectiveFrom(), approved.effectiveTo(),
                    approved.items().stream().map(item -> new ToolResponses.Item(
                            item.order(), item.ruleKey(), item.instruction(), item.evidenceRequired(),
                            item.structuredChange(), item.sourceRuleVersionId())).toList());
        }
        return new ToolResponses.ApplicableChecklist(
                state.familyId(), state.datasetClass(), state.synthetic(), state.disclaimer(), state.businessDate(),
                state.evaluatedAt(), notice, state.internalChecklistUseAllowed(), state.blockingReasons(),
                state.warningReasons(), checklist);
    }

    private void audit(String serviceId, String tool, String familyId, java.time.LocalDate businessDate, String consultationId,
            String outcome, Boolean usable, List<String> reasons, String traceId) {
        try {
            recorder.record(new ToolCallAudit(serviceId, tool, familyId, businessDate, consultationId, outcome, usable,
                    reasons, traceId, clock.instant()));
        } catch (RuntimeException exception) {
            throw new ToolApiException("AUDIT_WRITE_FAILED", "감사 기록을 저장할 수 없어 응답을 제공하지 않습니다.", exception);
        }
    }

    private static void requireFields(JsonNode body, Set<String> allowed, List<String> required) {
        if (body == null || !body.isObject()) {
            throw new ToolApiException("INVALID_REQUEST", "요청 본문은 JSON 객체여야 합니다.");
        }
        List<String> unknown = new java.util.ArrayList<>();
        body.propertyNames().forEach(name -> {
            if (!allowed.contains(name)) unknown.add(name);
        });
        if (!unknown.isEmpty()) {
            throw new ToolApiException("INVALID_REQUEST", "허용되지 않는 필드입니다: " + String.join(", ", unknown));
        }
        for (String name : required) {
            JsonNode value = body.get(name);
            if (value == null || !value.isString() || value.stringValue().isBlank()) {
                throw new ToolApiException("INVALID_REQUEST", "필수 필드가 없거나 문자열이 아닙니다: " + name);
            }
        }
        for (String name : allowed) {
            JsonNode value = body.get(name);
            int maxLength = "ruleVersionId".equals(name) ? 128 : 64;
            if (value != null && !value.isNull() && (!value.isString() || value.stringValue().length() > maxLength)) {
                throw new ToolApiException("INVALID_REQUEST", "필드는 " + maxLength + "자 이하 문자열이어야 합니다: " + name);
            }
        }
    }

    private static String text(JsonNode body, String name) {
        return body.get(name).stringValue();
    }

    private static String optionalText(JsonNode body, String name) {
        JsonNode value = body.get(name);
        return value == null || value.isNull() ? null : value.stringValue();
    }
}
