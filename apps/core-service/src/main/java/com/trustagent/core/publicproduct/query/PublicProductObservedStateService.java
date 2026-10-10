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

    /** HTTP 조회: 평가 시각은 이 요청에서 한 번 읽은 현재 시각이다. asOf를 생략하면 그 시각이다. */
    public PublicProductObservedState get(String productKey, String requestedAsOf) {
        Instant evaluatedAt = clock.instant();
        Instant asOf = parseAsOf(requestedAsOf, evaluatedAt);
        return evaluateAt(productKey, asOf, evaluatedAt);
    }

    /**
     * 다른 작업(변경안 검증, 적용 checklist 조회)이 자기 기준 시각으로 평가할 때 쓴다. 그 작업이 정한 evaluatedAt을 그대로 쓰고
     * 시계를 다시 읽지 않는다. asOf가 evaluatedAt과 같으면 현재 조회, 앞서면 과거 조회(확인 불가), 뒤면 FUTURE_AS_OF_NOT_ALLOWED로
     * 정책은 HTTP 조회와 같다. 이전에는 호출자가 asOf만 넘기고 이 서비스가 시계를 다시 읽어, 실제로 시간이 흐르는 시계에서
     * asOf가 몇 마이크로초 앞서 과거 조회로 판정됐다.
     */
    public PublicProductObservedState evaluateAt(String productKey, Instant asOf, Instant evaluatedAt) {
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
