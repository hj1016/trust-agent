package com.trustagent.core.publicproduct.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class PublicProductObservedStateServiceTest {

    @Test
    void requestReadsClockOnceAndUsesThatInstantAsDefaultAsOf() {
        Instant now = Instant.parse("2026-09-23T12:00:00Z");
        AtomicInteger reads = new AtomicInteger();
        Clock singleReadClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                if (reads.incrementAndGet() > 1) {
                    throw new AssertionError("한 요청에서 Clock을 두 번 읽었습니다.");
                }
                return now;
            }
        };
        var repository = mock(PublicProductObservedStateRepository.class);
        when(repository.findProduct("product-a"))
                .thenReturn(Optional.of(new PublicProductObservedStateRepository.ProductRow(
                        "product-a", "상품 A")));
        when(repository.findLatestObservation("product-a", now)).thenReturn(Optional.empty());
        when(repository.findLatestCollectionStatus("product-a", now)).thenReturn(Optional.empty());
        when(repository.findLatestConfirmed("product-a", now)).thenReturn(Optional.empty());
        var service = new PublicProductObservedStateService(
                repository,
                new PublicEvidenceConfirmationPolicy(),
                new PublicEvidencePolicyProperties(
                        "public-evidence-confirmation-v1", Duration.ofDays(1)),
                singleReadClock);

        PublicProductObservedState result = service.get("product-a", null);

        assertEquals(1, reads.get());
        assertEquals(now, result.evaluatedAt());
        assertEquals(now, result.asOf());
        assertEquals(PublicEvidenceConfirmationPolicy.FreshnessStatus.UNAVAILABLE, result.freshnessStatus());
    }

    @Test
    void databaseFailureIsNotConvertedToUnavailableFreshness() {
        var repository = mock(PublicProductObservedStateRepository.class);
        when(repository.findProduct("product-a"))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        var service = new PublicProductObservedStateService(
                repository,
                new PublicEvidenceConfirmationPolicy(),
                new PublicEvidencePolicyProperties(
                        "public-evidence-confirmation-v1", Duration.ofDays(1)),
                Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC));

        org.junit.jupiter.api.Assertions.assertThrows(
                DataAccessResourceFailureException.class,
                () -> service.get("product-a", null));
    }
}
