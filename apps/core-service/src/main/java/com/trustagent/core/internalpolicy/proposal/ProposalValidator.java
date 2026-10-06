package com.trustagent.core.internalpolicy.proposal;

import com.trustagent.core.json.CanonicalJsonHasher;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 변경안을 공문 원문, 기준 checklist, 공개 근거와 대조해 PASS, WARN, FAIL로 판정한다. SQL이 없고 LLM을 쓰지 않는다.
 * 판정은 승인이 아니다.
 */
public class ProposalValidator {

    public enum Severity { FAIL, WARN, INFO }

    public enum Status { PASS, WARN, FAIL }

    public record Issue(Severity severity, String code, String ruleKey, String message, ObjectNode details) {}

    public record Outcome(Status status, List<Issue> issues) {}

    /** 변경안 항목(저장된 형태). before/after는 ChecklistItemContent.toJson 형태의 JSON 또는 null. */
    public record ProposalItem(String ruleKey, String changeType, JsonNode before, JsonNode after) {}

    /** 공개 근거 교차 검증 입력. 서비스가 공개 상품 조회로 미리 계산한다. */
    public record PublicFactCheck(
            String productKey,
            String factKey,
            String subjectType,
            String unit,
            long expectedValue,
            String evidenceRequirement,
            boolean confirmed,
            Long publicValue,
            List<String> blockingReasons) {}

    public record Input(
            List<ProposalItem> items,
            List<ChecklistItemContent> targetRules,
            List<ChecklistItemContent> baseItems,
            LocalDate targetEffectiveFrom,
            boolean targetWithdrawn,
            boolean baseChecklistCurrent,
            Set<String> knownProductKeys,
            List<PublicFactCheck> publicFactChecks) {}

    private static final Pattern DECIMAL = Pattern.compile("^-?[0-9]+(\\.[0-9]+)?$");

    private final ObjectMapper mapper;
    private final CanonicalJsonHasher hasher;

    public ProposalValidator(ObjectMapper mapper) {
        this.mapper = mapper;
        this.hasher = new CanonicalJsonHasher(mapper);
    }

    public Outcome validate(Input input) {
        List<Issue> issues = new ArrayList<>();
        Map<String, ChecklistItemContent> target = index(input.targetRules());
        Map<String, ChecklistItemContent> base = index(input.baseItems());

        for (ProposalItem item : input.items()) {
            validateItem(item, target, base, input, issues);
        }
        validatePublicFacts(input, issues);
        if (!input.baseChecklistCurrent()) {
            issues.add(issue(Severity.FAIL, "BASE_CHECKLIST_STALE", null,
                    "변경안의 기준 checklist가 더 이상 최신 적용 일정에 없습니다.", details()));
        }
        if (input.targetWithdrawn()) {
            issues.add(issue(Severity.FAIL, "TARGET_NOTICE_WITHDRAWN", null,
                    "대상 공문이 철회됐습니다.", details()));
        }
        return new Outcome(aggregate(issues), List.copyOf(issues));
    }

    public static Status aggregate(List<Issue> issues) {
        boolean fail = issues.stream().anyMatch(issue -> issue.severity() == Severity.FAIL);
        boolean warn = issues.stream().anyMatch(issue -> issue.severity() == Severity.WARN);
        return fail ? Status.FAIL : warn ? Status.WARN : Status.PASS;
    }

    private void validateItem(
            ProposalItem item,
            Map<String, ChecklistItemContent> target,
            Map<String, ChecklistItemContent> base,
            Input input,
            List<Issue> issues) {
        String key = item.ruleKey();
        ChecklistItemContent targetRule = target.get(key);
        ChecklistItemContent baseItem = base.get(key);

        if ("REMOVE".equals(item.changeType())) {
            issues.add(issue(Severity.WARN, "ITEM_REMOVED", key,
                    "기준 checklist 항목이 새 공문에 없어 삭제 후보입니다. 의도된 삭제인지 확인이 필요합니다.", details()));
            return;
        }

        // V-01a 구조화 값 일치: rule_key, evidence_required, structured_change 전체가 공문 규칙과 같아야 한다 (FAIL).
        // V-01b 설명 문구 일치: instruction만 다르면 WARN. 자동 검증은 문구의 의미를 보장하지 않으므로 사람이 원문과 대조한다 (TASK-013).
        if (targetRule == null) {
            issues.add(issue(Severity.FAIL, "VALUE_MISMATCH", key,
                    "공문에 없는 규칙이 변경안에 있습니다.", details("proposal_after", item.after())));
        } else if (item.after() == null) {
            issues.add(issue(Severity.FAIL, "VALUE_MISMATCH", key,
                    "변경안에 변경 후 내용이 없습니다.", details("notice_rule", targetRule.toJson(mapper))));
        } else {
            ObjectNode noticeJson = targetRule.toJson(mapper);
            List<String> mismatched = mismatchedFields(item.after(), noticeJson);
            if (!mismatched.isEmpty()) {
                ObjectNode details = details("proposal_after", item.after(), "notice_rule", noticeJson);
                details.set("mismatched_fields", mapper.valueToTree(mismatched));
                issues.add(issue(Severity.FAIL, "VALUE_MISMATCH", key,
                        "변경안의 업무 값(규칙 키, 근거 필요 여부, 구조화 변경)이 공문의 구조화 규칙과 다릅니다: " + String.join(", ", mismatched),
                        details));
            } else if (!text(item.after(), "instruction").equals(targetRule.instruction())) {
                issues.add(issue(Severity.WARN, "INSTRUCTION_EDITED", key,
                        "설명 문구가 공문 규칙과 다릅니다. 자동 검증은 문구의 의미를 보장하지 않으므로 검수자가 원문과 대조하고 승인 사유를 남겨야 합니다.",
                        details("notice_instruction", textNode(targetRule.instruction()),
                                "proposal_instruction", textNode(text(item.after(), "instruction")))));
            }
        }

        JsonNode change = item.after() == null ? null : item.after().get("structured_change");
        if (change == null || change.isNull()) {
            crossCheckNotApplicable(item, input, issues);
            return;
        }

        // V-02 시행일 일치
        String effectiveOn = text(change, "effective_on");
        if (effectiveOn == null || input.targetEffectiveFrom() == null
                || !LocalDate.parse(effectiveOn).equals(input.targetEffectiveFrom())) {
            issues.add(issue(Severity.FAIL, "EFFECTIVE_DATE_MISMATCH", key,
                    "구조화 변경의 적용일이 공문 시행일과 다릅니다.",
                    details("effective_on", textNode(effectiveOn), "notice_effective_from",
                            textNode(input.targetEffectiveFrom() == null ? null : input.targetEffectiveFrom().toString()))));
        }

        // V-03 변경 전 값 연속성 (수정 항목만)
        if ("MODIFY".equals(item.changeType())) {
            JsonNode statedBefore = change.get("before_value");
            JsonNode baseChange = baseItem == null ? null : baseItem.structuredChange();
            JsonNode baseCurrent = baseChange == null || baseChange.isNull() ? null : baseChange.get("after_value");
            if (statedBefore == null || statedBefore.isNull()) {
                issues.add(issue(Severity.WARN, "BEFORE_VALUE_NOT_STATED", key,
                        "공문이 변경 전 값을 명시하지 않았습니다.", details()));
            } else if (baseCurrent == null || baseCurrent.isNull() || !baseCurrent.equals(statedBefore)) {
                issues.add(issue(Severity.FAIL, "BEFORE_VALUE_MISMATCH", key,
                        "공문이 말하는 변경 전 값이 기준 checklist의 현재 값과 다릅니다.",
                        details("notice_before_value", statedBefore, "base_current_value", baseCurrent)));
            }
        }

        // V-04 숫자 형식과 범위
        String unit = text(change, "unit");
        JsonNode afterValue = change.get("after_value");
        if ("PERCENT".equals(unit) || "KRW".equals(unit)) {
            String reason = numericProblem(afterValue, unit);
            if (reason != null) {
                issues.add(issue(Severity.FAIL, "INVALID_NUMERIC_VALUE", key, reason,
                        details("after_value", afterValue, "unit", textNode(unit))));
            }
        }

        // V-05 대상 상품 존재
        JsonNode products = change.get("applicable_product_keys");
        if (products != null && products.isArray()) {
            for (JsonNode product : products) {
                if (!input.knownProductKeys().contains(product.stringValue())) {
                    issues.add(issue(Severity.FAIL, "UNKNOWN_PRODUCT_KEY", key,
                            "대상 상품이 공개 상품 목록에 없습니다.", details("product_key", product)));
                }
            }
        }

        // V-06 조건
        if ("NUMERIC_POLICY_CHANGE".equals(text(change, "change_type"))) {
            JsonNode conditions = change.get("conditions");
            if (conditions == null || !conditions.isArray() || conditions.isEmpty()) {
                issues.add(issue(Severity.WARN, "MISSING_CONDITIONS", key,
                        "숫자 정책 변경에 적용 조건이 없습니다.", details()));
            }
        }
        crossCheckNotApplicable(item, input, issues);
    }

    /** V-09: 이 규칙의 값이 공개 근거 교차 검증 대상(fact_key)과 연결되지 않으면 INFO. 판정에 영향 없음. */
    private void crossCheckNotApplicable(ProposalItem item, Input input, List<Issue> issues) {
        JsonNode change = item.after() == null ? null : item.after().get("structured_change");
        String fieldKey = change == null || change.isNull() ? null : text(change, "field_key");
        boolean applicable = fieldKey != null
                && input.publicFactChecks().stream().anyMatch(check -> check.factKey().equals(fieldKey));
        if (!applicable) {
            issues.add(issue(Severity.INFO, "PUBLIC_CROSS_CHECK_NOT_APPLICABLE", item.ruleKey(),
                    "이 규칙에는 공개 근거 교차 검증 항목이 없습니다.", details()));
        }
    }

    private void validatePublicFacts(Input input, List<Issue> issues) {
        for (PublicFactCheck check : input.publicFactChecks()) {
            ObjectNode details = details();
            details.put("product_key", check.productKey());
            details.put("fact_key", check.factKey());
            details.put("subject_type", check.subjectType());
            details.put("expected_value", check.expectedValue());
            details.put("evidence_requirement", check.evidenceRequirement());
            if (!check.confirmed()) {
                details.set("public_blocking_reasons", mapper.valueToTree(check.blockingReasons()));
                boolean required = "REQUIRED".equals(check.evidenceRequirement());
                issues.add(issue(required ? Severity.FAIL : Severity.WARN, "PUBLIC_EVIDENCE_UNCONFIRMED", null,
                        required ? "필수 공개 근거를 현재 확인할 수 없습니다." : "참고용 공개 근거를 현재 확인할 수 없습니다.",
                        details));
                continue;
            }
            details.put("public_value", check.publicValue());
            Long proposalValue = proposalValueFor(input.items(), check.factKey(), check.unit());
            if (proposalValue != null) {
                details.put("proposal_value", proposalValue);
            }
            boolean mismatch = check.publicValue() == null
                    || check.publicValue() != check.expectedValue()
                    || (proposalValue != null && proposalValue.longValue() != check.publicValue());
            if (mismatch) {
                issues.add(issue(Severity.FAIL, "PUBLIC_FACT_MISMATCH", null,
                        "공개 상품 정보의 값이 공문 또는 변경안의 값과 다릅니다. 내부 규칙을 자동 수정하지 않습니다.", details));
            } else {
                issues.add(issue(Severity.INFO, "PUBLIC_FACT_MATCH", null, "공개 근거와 값이 일치합니다.", details));
            }
        }
    }

    private static Long proposalValueFor(List<ProposalItem> items, String factKey, String unit) {
        for (ProposalItem item : items) {
            JsonNode change = item.after() == null ? null : item.after().get("structured_change");
            if (change == null || change.isNull()) {
                continue;
            }
            if (factKey.equals(text(change, "field_key")) && unit.equals(text(change, "unit"))) {
                JsonNode value = change.get("after_value");
                if (value != null && !value.isNull()) {
                    try {
                        return value.isNumber() ? value.longValue() : Long.parseLong(value.stringValue());
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static String numericProblem(JsonNode value, String unit) {
        if (value == null || value.isNull()) {
            return "숫자 값이 비어 있습니다.";
        }
        if (value.isFloatingPointNumber()) {
            return "부동소수점 값은 허용하지 않습니다.";
        }
        String text = value.isNumber() ? value.asString() : value.isString() ? value.stringValue() : null;
        if (text == null || !DECIMAL.matcher(text).matches()) {
            return "숫자 형식이 아닙니다: " + value;
        }
        java.math.BigDecimal number = new java.math.BigDecimal(text);
        if (number.signum() < 0) {
            return "음수는 허용하지 않습니다.";
        }
        if ("PERCENT".equals(unit) && number.compareTo(java.math.BigDecimal.valueOf(100)) > 0) {
            return "퍼센트 값이 100을 넘습니다.";
        }
        return null;
    }

    /** 설명 문구(instruction)를 뺀 나머지 필드 중 다른 것. structured_change는 하위 필드 단위로 적는다. */
    private List<String> mismatchedFields(JsonNode proposalAfter, ObjectNode noticeRule) {
        List<String> mismatched = new ArrayList<>();
        for (String field : List.of("rule_key", "evidence_required")) {
            if (!canonical(proposalAfter.get(field)).equals(canonical(noticeRule.get(field)))) {
                mismatched.add(field);
            }
        }
        JsonNode proposalChange = proposalAfter.get("structured_change");
        JsonNode noticeChange = noticeRule.get("structured_change");
        boolean proposalNull = proposalChange == null || proposalChange.isNull();
        boolean noticeNull = noticeChange == null || noticeChange.isNull();
        if (proposalNull != noticeNull) {
            mismatched.add("structured_change");
        } else if (!proposalNull) {
            java.util.TreeSet<String> keys = new java.util.TreeSet<>();
            proposalChange.propertyNames().forEach(keys::add);
            noticeChange.propertyNames().forEach(keys::add);
            for (String subField : keys) {
                if (!canonical(proposalChange.get(subField)).equals(canonical(noticeChange.get(subField)))) {
                    mismatched.add("structured_change." + subField);
                }
            }
        }
        return mismatched;
    }

    private String canonical(JsonNode node) {
        return node == null ? "<absent>" : hasher.canonicalize(node).json();
    }

    private static Map<String, ChecklistItemContent> index(List<ChecklistItemContent> contents) {
        Map<String, ChecklistItemContent> byKey = new LinkedHashMap<>();
        contents.forEach(content -> byKey.put(content.ruleKey(), content));
        return byKey;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.isString() ? value.stringValue() : value.asString();
    }

    private JsonNode textNode(String value) {
        return value == null ? mapper.getNodeFactory().nullNode() : mapper.getNodeFactory().stringNode(value);
    }

    private ObjectNode details(Object... pairs) {
        ObjectNode node = mapper.createObjectNode();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value == null) {
                node.putNull((String) pairs[i]);
            } else {
                node.set((String) pairs[i], (JsonNode) value);
            }
        }
        return node;
    }

    private static Issue issue(Severity severity, String code, String ruleKey, String message, ObjectNode details) {
        return new Issue(severity, code, ruleKey, message, details);
    }
}
