package com.trustagent.core.internalpolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class InternalChecklistUsePolicy {

    public enum NoticeStatus {
        SELECTED,
        NO_APPLICABLE_NOTICE,
        NOT_YET_EFFECTIVE,
        WITHDRAWN,
        AMBIGUOUS
    }

    public enum ChecklistStatus {
        AVAILABLE,
        PENDING_EXTRACTION,
        PENDING_VALIDATION,
        PENDING_REVIEW,
        VALIDATION_STALE,
        VALIDATION_FAILED,
        UNAVAILABLE
    }

    private static final Map<NoticeStatus, Integer> NOTICE_PRIORITY = Map.of(
            NoticeStatus.SELECTED, 0, NoticeStatus.NO_APPLICABLE_NOTICE, 1,
            NoticeStatus.NOT_YET_EFFECTIVE, 2, NoticeStatus.WITHDRAWN, 3,
            NoticeStatus.AMBIGUOUS, 4);
    private static final Map<ChecklistStatus, Integer> CHECKLIST_PRIORITY = Map.of(
            ChecklistStatus.AVAILABLE, 0, ChecklistStatus.PENDING_EXTRACTION, 1,
            ChecklistStatus.PENDING_VALIDATION, 2, ChecklistStatus.PENDING_REVIEW, 3,
            ChecklistStatus.VALIDATION_STALE, 4, ChecklistStatus.VALIDATION_FAILED, 5,
            ChecklistStatus.UNAVAILABLE, 6);

    public Result evaluate(Input input) {
        NoticeStatus notice = highest(input.noticeConditions(), NOTICE_PRIORITY);
        ChecklistStatus checklist = highest(input.checklistConditions(), CHECKLIST_PRIORITY);
        boolean allowed = !input.historicalKnowledgeQuery()
                && !input.futureBusinessDate()
                && notice == NoticeStatus.SELECTED
                && checklist == ChecklistStatus.AVAILABLE
                && input.validationFresh()
                && input.publicEvidenceConfirmed()
                && input.semanticMatch();
        return new Result(notice, checklist, allowed);
    }

    public boolean validationFresh(Instant validatedAt, Instant evaluatedAt, Duration maxValidationAge) {
        if (validatedAt == null || evaluatedAt == null || maxValidationAge == null
                || maxValidationAge.isNegative() || maxValidationAge.isZero()
                || validatedAt.isAfter(evaluatedAt)) {
            return false;
        }
        return !Duration.between(validatedAt, evaluatedAt).minus(maxValidationAge).isPositive();
    }

    private static <T extends Enum<T>> T highest(Set<T> values, Map<T, Integer> priorities) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("상태 조건은 하나 이상이어야 합니다.");
        }
        return values.stream().max(Comparator.comparingInt(priorities::get)).orElseThrow();
    }

    public record Input(
            EnumSet<NoticeStatus> noticeConditions,
            EnumSet<ChecklistStatus> checklistConditions,
            boolean historicalKnowledgeQuery,
            boolean futureBusinessDate,
            boolean validationFresh,
            boolean publicEvidenceConfirmed,
            boolean semanticMatch) {}

    public record Result(
            NoticeStatus noticeStatus,
            ChecklistStatus checklistStatus,
            boolean internalChecklistUseAllowed) {}
}
