package com.trustagent.core.internalpolicy.query;

import com.trustagent.core.internalpolicy.InternalChecklistUsePolicy.ChecklistStatus;
import com.trustagent.core.internalpolicy.InternalChecklistUsePolicy.NoticeStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record InternalPolicyApplicableState(
        String familyId,
        String datasetClass,
        boolean synthetic,
        String disclaimer,
        LocalDate businessDate,
        Instant knownAt,
        Instant evaluatedAt,
        LocalDate evaluatedBusinessDate,
        boolean businessDateInFuture,
        boolean historicalKnownAt,
        NoticeStatus noticeSelectionStatus,
        ChecklistStatus checklistAvailabilityStatus,
        List<String> blockingReasons,
        List<String> warningReasons,
        boolean internalChecklistUseAllowed,
        List<String> candidateNoticeIds,
        Notice selectedNotice,
        List<Rule> rules,
        ApprovedChecklist approvedChecklist,
        String validatedProposalId,
        String validationResultId) {

    public record Notice(
            String noticeId,
            int version,
            String title,
            LocalDate issuedOn,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String supersedesNoticeId,
            Instant receivedAt,
            LocalDate receivedBusinessDate) {
    }

    public record Rule(
            String ruleVersionId,
            int order,
            String ruleKey,
            String instruction,
            boolean evidenceRequired,
            JsonNode structuredChange,
            String jsonPointer,
            String evidenceText,
            String evidenceHash) {
    }

    public record ApprovedChecklist(
            String approvedChecklistVersionId,
            String scheduleRevisionId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant createdAt) {
    }
}
