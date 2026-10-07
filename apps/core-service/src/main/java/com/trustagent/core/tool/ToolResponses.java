package com.trustagent.core.tool;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Tool 응답 계약. 공문 본문 전체, 검수자 ID, 변경안·검증 ID, 정책 입력 플래그는 들어가지 않는다. */
public final class ToolResponses {

    private ToolResponses() {}

    public record ApplicableChecklist(
            String familyId,
            String datasetClass,
            boolean synthetic,
            String disclaimer,
            LocalDate businessDate,
            Instant evaluatedAt,
            SelectedNotice selectedNotice,
            boolean usable,
            List<String> blockingReasons,
            List<String> warningReasons,
            ApprovedChecklist approvedChecklist) {
    }

    public record SelectedNotice(String noticeId, int version, String title, LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    public record ApprovedChecklist(
            String approvedChecklistVersionId,
            String origin,
            String decisionId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            List<Item> items) {
    }

    public record Item(
            int order,
            String ruleKey,
            String instruction,
            boolean evidenceRequired,
            JsonNode structuredChange,
            String sourceRuleVersionId) {
    }

    public record RuleEvidence(
            String familyId,
            String noticeId,
            LocalDate effectiveFrom,
            String ruleVersionId,
            String ruleKey,
            String evidenceText,
            String jsonPointer,
            String evidenceHash,
            String disclaimer) {
    }
}
