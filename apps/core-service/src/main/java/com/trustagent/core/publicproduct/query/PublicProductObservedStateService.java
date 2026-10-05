package com.trustagent.core.publicproduct.query;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Service;

@Service
public class PublicProductObservedStateService {

    private final PublicProductObservedStateRepository repository;
    private final PublicEvidenceConfirmationPolicy policy;
    private final PublicEvidencePolicyProperties properties;
    private final Clock clock;

    public PublicProductObservedStateService(
            PublicProductObservedStateRepository repository,
            PublicEvidenceConfirmationPolicy policy,
            PublicEvidencePolicyProperties properties,
            Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.properties = properties;
        this.clock = clock;
    }

    /** TASK-006 변경안 검증이 공개 근거 교차 검증에 같은 조회 경로를 쓴다. 동작 변경 없음. */
    public PublicProductObservedState get(String productKey, String requestedAsOf) {
        Instant evaluatedAt = clock.instant();
        Instant asOf = parseAsOf(requestedAsOf, evaluatedAt);
        var product = repository.findProduct(productKey).orElseThrow(() ->
                new PublicProductQueryException("PRODUCT_NOT_FOUND", "등록되지 않은 공개 상품입니다."));
        var latestObservation = repository.findLatestObservation(productKey, asOf);
        var latestExtractionStatus = latestObservation.flatMap(observation ->
                repository.findLatestObservationExtractionStatus(observation.observationId(), asOf));
        var latestCollectionStatus = repository.findLatestCollectionStatus(productKey, asOf);
        var confirmed = repository.findLatestConfirmed(productKey, asOf);
        Instant lastConfirmedAt = confirmed.map(
                PublicProductObservedStateRepository.ConfirmedRow::observedAt).orElse(null);
        Instant latestObservationAt = latestObservation.map(
                PublicProductObservedStateRepository.ObservationRow::observedAt).orElse(null);
        boolean hasNewerObservation = latestObservation.isPresent()
                && confirmed.map(value -> !value.observationId().equals(
                                latestObservation.orElseThrow().observationId()))
                        .orElse(true);

        var policyResult = policy.evaluate(new PublicEvidenceConfirmationPolicy.Input(
                evaluatedAt,
                asOf,
                properties.maxConfirmationAge(),
                lastConfirmedAt,
                latestObservationAt,
                hasNewerObservation,
                latestExtractionStatus.orElse(null),
                latestCollectionStatus.orElse(null)));

        PublicProductObservedState.ProductTerms terms = null;
        PublicProductObservedState.ConfirmedObservation confirmedObservation = null;
        PublicProductObservedState.RateQuote quote = null;
        PublicProductObservedState.VersionEvidence evidence = null;
        if (confirmed.isPresent()) {
            var value = confirmed.orElseThrow();
            terms = new PublicProductObservedState.ProductTerms(
                    value.versionId(),
                    value.termsHash(),
                    value.effectiveFrom(),
                    value.effectiveTo(),
                    repository.findFacts(value.versionId()));
            confirmedObservation = new PublicProductObservedState.ConfirmedObservation(
                    value.observationId(),
                    value.observedAt(),
                    value.sourceUrl(),
                    value.finalUrl(),
                    value.acquisitionMethod(),
                    value.snapshotHash());
            quote = repository.findRateQuote(value.rateQuoteId()).orElseThrow(() ->
                    new IllegalStateException("성공 추출의 rate quote가 없습니다."));
            evidence = new PublicProductObservedState.VersionEvidence(
                    value.evidenceId(),
                    value.parserVersion(),
                    value.evidenceAvailableAt(),
                    repository.findEvidence(value.evidenceId()));
        }

        return new PublicProductObservedState(
                product.productKey(),
                product.displayName(),
                asOf,
                evaluatedAt,
                policyResult.historicalQuery(),
                lastConfirmedAt,
                latestObservationAt,
                policyResult.freshnessStatus(),
                policyResult.blockingReasons(),
                policyResult.warningReasons(),
                policyResult.publicEvidenceConfirmationAllowed(),
                policyResult.confirmationBlockingReasons(),
                properties.freshnessPolicyVersion(),
                properties.maxConfirmationAge(),
                terms,
                confirmedObservation,
                quote,
                evidence);
    }

    private static Instant parseAsOf(String requestedAsOf, Instant evaluatedAt) {
        if (requestedAsOf == null) return evaluatedAt;
        if (requestedAsOf.isBlank()) {
            throw new PublicProductQueryException("INVALID_AS_OF", "asOf는 유효한 RFC 3339 instant여야 합니다.");
        }
        try {
            return Instant.parse(requestedAsOf);
        } catch (DateTimeParseException exception) {
            throw new PublicProductQueryException("INVALID_AS_OF", "asOf는 유효한 RFC 3339 instant여야 합니다.");
        }
    }
}
