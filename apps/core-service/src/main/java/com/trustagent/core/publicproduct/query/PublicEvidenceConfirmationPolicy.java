package com.trustagent.core.publicproduct.query;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PublicEvidenceConfirmationPolicy {

    private static final List<FreshnessStatus> PRIORITY = List.of(
            FreshnessStatus.UNAVAILABLE,
            FreshnessStatus.UNCONFIRMED_AFTER_FAILURE,
            FreshnessStatus.PENDING_EXTRACTION,
            FreshnessStatus.STALE,
            FreshnessStatus.CONFIRMED);

    public Result evaluate(Input input) {
        if (input.asOf().isAfter(input.evaluatedAt())) {
            throw new PublicProductQueryException(
                    "FUTURE_AS_OF_NOT_ALLOWED", "asOf는 현재 평가 시각보다 미래일 수 없습니다.");
        }
        if (input.maxConfirmationAge().isZero() || input.maxConfirmationAge().isNegative()) {
            throw new IllegalArgumentException("maxConfirmationAge는 0보다 커야 합니다.");
        }

        List<String> blockingReasons = new ArrayList<>();
        List<String> warningReasons = new ArrayList<>();
        List<FreshnessStatus> conditions = new ArrayList<>();
        if (input.lastConfirmedAt() == null) {
            conditions.add(FreshnessStatus.UNAVAILABLE);
            blockingReasons.add("NO_CONFIRMED_TERMS");
        } else {
            if (Duration.between(input.lastConfirmedAt(), input.evaluatedAt())
                    .compareTo(input.maxConfirmationAge()) > 0) {
                conditions.add(FreshnessStatus.STALE);
                blockingReasons.add("CONFIRMATION_AGE_EXCEEDED");
            }
            boolean hasNewObservation = input.hasNewerObservation();
            if (hasNewObservation && input.latestObservationExtractionStatus() == AttemptStatus.FAILED) {
                conditions.add(FreshnessStatus.UNCONFIRMED_AFTER_FAILURE);
                blockingReasons.add("LATEST_EXTRACTION_FAILED");
            } else if (hasNewObservation && input.latestObservationExtractionStatus() == null) {
                conditions.add(FreshnessStatus.PENDING_EXTRACTION);
                blockingReasons.add("LATEST_OBSERVATION_NOT_EXTRACTED");
            }
        }
        if (input.latestCollectionStatus() == AttemptStatus.FAILED) {
            warningReasons.add("LATEST_COLLECTION_FAILED");
        }
        if (conditions.isEmpty()) {
            conditions.add(FreshnessStatus.CONFIRMED);
        }
        FreshnessStatus representative = PRIORITY.stream()
                .filter(conditions::contains)
                .findFirst()
                .orElseThrow();
        boolean historical = input.asOf().isBefore(input.evaluatedAt());
        List<String> confirmationReasons = new ArrayList<>(blockingReasons);
        if (historical) {
            confirmationReasons.add("HISTORICAL_AS_OF");
        }
        return new Result(
                representative,
                List.copyOf(blockingReasons),
                List.copyOf(warningReasons),
                historical,
                !historical && representative == FreshnessStatus.CONFIRMED,
                List.copyOf(confirmationReasons));
    }

    public enum FreshnessStatus {
        UNAVAILABLE,
        UNCONFIRMED_AFTER_FAILURE,
        PENDING_EXTRACTION,
        STALE,
        CONFIRMED
    }

    public enum AttemptStatus {
        SUCCEEDED,
        FAILED
    }

    public record Input(
            Instant evaluatedAt,
            Instant asOf,
            Duration maxConfirmationAge,
            Instant lastConfirmedAt,
            Instant latestObservationAt,
            boolean hasNewerObservation,
            AttemptStatus latestObservationExtractionStatus,
            AttemptStatus latestCollectionStatus) {
    }

    public record Result(
            FreshnessStatus freshnessStatus,
            List<String> blockingReasons,
            List<String> warningReasons,
            boolean historicalQuery,
            boolean publicEvidenceConfirmationAllowed,
            List<String> confirmationBlockingReasons) {
    }
}
