package com.trustagent.core.publicproduct.query;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record PublicProductObservedState(
        String productKey,
        String displayName,
        Instant asOf,
        Instant evaluatedAt,
        boolean historicalQuery,
        Instant lastConfirmedAt,
        Instant latestObservationAt,
        PublicEvidenceConfirmationPolicy.FreshnessStatus freshnessStatus,
        List<String> blockingReasons,
        List<String> warningReasons,
        boolean publicEvidenceConfirmationAllowed,
        List<String> confirmationBlockingReasons,
        String freshnessPolicyVersion,
        Duration maxConfirmationAge,
        ProductTerms terms,
        ConfirmedObservation confirmedObservation,
        RateQuote rateQuote,
        VersionEvidence evidence) {

    public record ProductTerms(
            String productTermsVersionId,
            String termsHash,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            List<Fact> facts) {
    }

    public record Fact(
            String factId,
            int order,
            String factKey,
            String subjectType,
            String valueType,
            String textValue,
            Long integerValue,
            String statusValue,
            String unit) {
    }

    public record ConfirmedObservation(
            String observationId,
            Instant observedAt,
            String sourceUrl,
            String finalUrl,
            String acquisitionMethod,
            String snapshotHash) {
    }

    public record RateQuote(
            String rateQuoteId,
            String advertisedRateText,
            LocalDate advertisedRateReferenceDate,
            JsonNode textLocator,
            JsonNode referenceDateLocator) {
    }

    public record VersionEvidence(
            String versionEvidenceId,
            String parserVersion,
            Instant availableAt,
            List<FactEvidence> facts) {
    }

    public record FactEvidence(String factId, List<Locator> locators) {
    }

    public record Locator(
            int order,
            String strategy,
            String selector,
            String label,
            String evidenceText,
            String evidenceHash,
            String snapshotHash,
            String sourceUrl) {
    }
}
