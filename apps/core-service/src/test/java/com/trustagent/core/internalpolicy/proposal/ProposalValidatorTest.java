package com.trustagent.core.internalpolicy.proposal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.internalpolicy.proposal.ProposalValidator.Issue;
import com.trustagent.core.internalpolicy.proposal.ProposalValidator.Outcome;
import com.trustagent.core.internalpolicy.proposal.ProposalValidator.ProposalItem;
import com.trustagent.core.internalpolicy.proposal.ProposalValidator.PublicFactCheck;
import com.trustagent.core.internalpolicy.proposal.ProposalValidator.Severity;
import com.trustagent.core.internalpolicy.proposal.ProposalValidator.Status;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** TASK-006 검사 규칙 단위 검증. 사례 2~7, 9~10과 판정 집계. */
class ProposalValidatorTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 10, 1);
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ProposalValidator validator = new ProposalValidator(mapper);

    private static final String TARGET_CHANGE = """
            {"change_type":"NUMERIC_POLICY_CHANGE","field_key":"prepayment_fee_rate_percent","before_value":"1.2","after_value":"0.8",
             "unit":"PERCENT","effective_on":"2026-10-01","applicable_product_keys":["kb-seller-loan"],"conditions":["기업 고객"],"exceptions":[]}""";
    private static final String BASE_CHANGE = """
            {"change_type":"NUMERIC_POLICY_CHANGE","field_key":"prepayment_fee_rate_percent","before_value":null,"after_value":"1.2",
             "unit":"PERCENT","effective_on":"2026-09-15","applicable_product_keys":["kb-seller-loan"],"conditions":["기업 고객"],"exceptions":[]}""";

    @Test
    void consistentProposalPassesWithOnlyInfo() {
        Outcome outcome = validator.validate(input(List.of(modify(TARGET_CHANGE)), List.of()));
        assertEquals(Status.PASS, outcome.status());
        assertEquals(List.of("PUBLIC_CROSS_CHECK_NOT_APPLICABLE"), codes(outcome, Severity.INFO));
        assertTrue(codes(outcome, Severity.FAIL).isEmpty());
    }

    @Test
    void afterValueDifferentFromNoticeFails() {
        Outcome outcome = validator.validate(input(List.of(modify(TARGET_CHANGE.replace("\"0.8\"", "\"0.08\""))), List.of()));
        assertEquals(Status.FAIL, outcome.status());
        assertTrue(codes(outcome, Severity.FAIL).contains("VALUE_MISMATCH"));
    }

    @Test
    void effectiveDateUnknownProductAndNumericProblemsFail() {
        Outcome date = validator.validate(input(List.of(modify(TARGET_CHANGE.replace("2026-10-01", "2026-10-02"))), List.of()));
        assertTrue(codes(date, Severity.FAIL).contains("EFFECTIVE_DATE_MISMATCH"));

        Outcome product = validator.validate(input(List.of(modify(TARGET_CHANGE.replace("kb-seller-loan", "kb-seller-loan-x"))), List.of()));
        assertTrue(codes(product, Severity.FAIL).contains("UNKNOWN_PRODUCT_KEY"));

        Outcome numeric = validator.validate(input(List.of(modify(TARGET_CHANGE.replace("\"0.8\"", "\"12e-1\""))), List.of()));
        assertTrue(codes(numeric, Severity.FAIL).contains("INVALID_NUMERIC_VALUE"));

        Outcome range = validator.validate(input(List.of(modify(TARGET_CHANGE.replace("\"0.8\"", "\"120\""))), List.of()));
        assertTrue(codes(range, Severity.FAIL).contains("INVALID_NUMERIC_VALUE"));
    }

    @Test
    void beforeValueContinuityIsCheckedAgainstBaseChecklist() {
        ProposalValidator.Input mismatch = new ProposalValidator.Input(
                List.of(modify(TARGET_CHANGE)),
                List.of(content("CHECK_PREPAYMENT_FEE_RATE", TARGET_CHANGE)),
                List.of(content("CHECK_PREPAYMENT_FEE_RATE", BASE_CHANGE.replace("\"1.2\"", "\"1.0\""))),
                EFFECTIVE, false, true, Set.of("kb-seller-loan"), List.of());
        assertTrue(codes(validator.validate(mismatch), Severity.FAIL).contains("BEFORE_VALUE_MISMATCH"));

        ProposalValidator.Input notStated = new ProposalValidator.Input(
                List.of(modify(TARGET_CHANGE.replace("\"before_value\":\"1.2\"", "\"before_value\":null"))),
                List.of(content("CHECK_PREPAYMENT_FEE_RATE", TARGET_CHANGE.replace("\"before_value\":\"1.2\"", "\"before_value\":null"))),
                List.of(content("CHECK_PREPAYMENT_FEE_RATE", BASE_CHANGE)),
                EFFECTIVE, false, true, Set.of("kb-seller-loan"), List.of());
        Outcome outcome = validator.validate(notStated);
        assertEquals(Status.WARN, outcome.status());
        assertTrue(codes(outcome, Severity.WARN).contains("BEFORE_VALUE_NOT_STATED"));
    }

    @Test
    void removedItemMissingConditionsAndInformationalEvidenceAreWarnings() {
        // 공문 규칙 자체에 조건이 없고 변경안도 같은 내용이면 값은 일치하지만 조건 누락 WARN이 붙는다.
        String noConditions = TARGET_CHANGE.replace("[\"기업 고객\"]", "[]");
        ProposalItem removed = new ProposalItem("CHECK_OLD", "REMOVE", content("CHECK_OLD", null).toJson(mapper), null);
        Outcome outcome = validator.validate(new ProposalValidator.Input(
                List.of(removed, modify(noConditions)),
                List.of(content("CHECK_PREPAYMENT_FEE_RATE", noConditions)),
                List.of(content("CHECK_PREPAYMENT_FEE_RATE", BASE_CHANGE)),
                EFFECTIVE, false, true, Set.of("kb-seller-loan"), List.of(
                        new PublicFactCheck("kb-seller-loan", "max_limit_corporate_krw", "CORPORATION", "KRW", 2_000_000_000L,
                                "INFORMATIONAL", false, null, List.of("PUBLIC_EVIDENCE_STALE")))));
        assertEquals(Status.WARN, outcome.status());
        assertTrue(codes(outcome, Severity.WARN).containsAll(List.of("ITEM_REMOVED", "MISSING_CONDITIONS", "PUBLIC_EVIDENCE_UNCONFIRMED")));
        assertTrue(codes(outcome, Severity.FAIL).isEmpty());
    }

    @Test
    void publicFactChecksProduceMatchMismatchAndRequiredUnconfirmedFailures() {
        PublicFactCheck match = new PublicFactCheck("kb-seller-loan", "max_limit_corporate_krw", "CORPORATION", "KRW",
                2_000_000_000L, "REQUIRED", true, 2_000_000_000L, List.of());
        assertEquals(List.of("PUBLIC_FACT_MATCH"), codes(validator.validate(input(List.of(), List.of(match))), Severity.INFO));

        PublicFactCheck mismatch = new PublicFactCheck("kb-seller-loan", "max_limit_corporate_krw", "CORPORATION", "KRW",
                2_000_000_000L, "REQUIRED", true, 200_000_000L, List.of());
        assertTrue(codes(validator.validate(input(List.of(), List.of(mismatch))), Severity.FAIL).contains("PUBLIC_FACT_MISMATCH"));

        PublicFactCheck unconfirmed = new PublicFactCheck("kb-seller-loan", "max_limit_corporate_krw", "CORPORATION", "KRW",
                2_000_000_000L, "REQUIRED", false, null, List.of("PUBLIC_EVIDENCE_STALE"));
        assertTrue(codes(validator.validate(input(List.of(), List.of(unconfirmed))), Severity.FAIL).contains("PUBLIC_EVIDENCE_UNCONFIRMED"));

        // 변경안 값이 공개 근거와 다르면 공문 기대값이 맞아도 실패한다.
        String limitChange = """
                {"change_type":"NUMERIC_POLICY_CHANGE","field_key":"max_limit_corporate_krw","before_value":null,"after_value":200000000,
                 "unit":"KRW","effective_on":"2026-10-01","applicable_product_keys":["kb-seller-loan"],"conditions":["법인"],"exceptions":[]}""";
        ProposalItem limitItem = new ProposalItem("CHECK_CORPORATE_LIMIT_SOURCE", "MODIFY", null, content("CHECK_CORPORATE_LIMIT_SOURCE", limitChange).toJson(mapper));
        ProposalValidator.Input proposalMismatch = new ProposalValidator.Input(
                List.of(limitItem), List.of(content("CHECK_CORPORATE_LIMIT_SOURCE", limitChange)), List.of(),
                EFFECTIVE, false, true, Set.of("kb-seller-loan"), List.of(match));
        assertTrue(codes(validator.validate(proposalMismatch), Severity.FAIL).contains("PUBLIC_FACT_MISMATCH"));
    }

    @Test
    void staleBaseAndWithdrawnTargetFailAndAggregationFollowsSeverity() {
        ProposalValidator.Input stale = new ProposalValidator.Input(
                List.of(), List.of(), List.of(), EFFECTIVE, true, false, Set.of(), List.of());
        Outcome outcome = validator.validate(stale);
        assertEquals(Status.FAIL, outcome.status());
        assertTrue(codes(outcome, Severity.FAIL).containsAll(List.of("BASE_CHECKLIST_STALE", "TARGET_NOTICE_WITHDRAWN")));
        assertEquals(Status.PASS, ProposalValidator.aggregate(List.of()));
    }

    private ProposalValidator.Input input(List<ProposalItem> items, List<PublicFactCheck> checks) {
        return new ProposalValidator.Input(
                items,
                List.of(content("CHECK_PREPAYMENT_FEE_RATE", TARGET_CHANGE)),
                List.of(content("CHECK_PREPAYMENT_FEE_RATE", BASE_CHANGE)),
                EFFECTIVE, false, true, Set.of("kb-seller-loan"), checks);
    }

    private ProposalItem modify(String afterChange) {
        return new ProposalItem("CHECK_PREPAYMENT_FEE_RATE", "MODIFY",
                content("CHECK_PREPAYMENT_FEE_RATE", BASE_CHANGE).toJson(mapper),
                content("CHECK_PREPAYMENT_FEE_RATE", afterChange).toJson(mapper));
    }

    private ChecklistItemContent content(String key, String change) {
        JsonNode node = change == null ? null : mapper.readTree(change);
        return new ChecklistItemContent(key, "지시 " + key, true, node);
    }

    private static List<String> codes(Outcome outcome, Severity severity) {
        return outcome.issues().stream().filter(issue -> issue.severity() == severity).map(Issue::code).toList();
    }
}
