package com.trustagent.core.publicproduct.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
class PublicProductObservedStateRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    PublicProductObservedStateRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    Optional<ProductRow> findProduct(String productKey) {
        return jdbc.sql("""
                        select product_key, display_name
                        from public_product
                        where product_key = :productKey
                        """)
                .param("productKey", productKey)
                .query((resultSet, rowNumber) -> new ProductRow(
                        resultSet.getString("product_key"),
                        resultSet.getString("display_name")))
                .optional();
    }

    Optional<ObservationRow> findLatestObservation(String productKey, Instant asOf) {
        return jdbc.sql("""
                        select observation_id, observed_at
                        from public_observation
                        where product_key = :productKey and observed_at <= :asOf
                        order by observed_at desc, observation_id desc
                        limit 1
                        """)
                .param("productKey", productKey)
                .param("asOf", utc(asOf))
                .query((resultSet, rowNumber) -> new ObservationRow(
                        resultSet.getString("observation_id"),
                        instant(resultSet, "observed_at")))
                .optional();
    }

    Optional<PublicEvidenceConfirmationPolicy.AttemptStatus> findLatestObservationExtractionStatus(
            String observationId,
            Instant asOf) {
        return jdbc.sql("""
                        select status
                        from extraction_attempt
                        where observation_id = :observationId and attempted_at <= :asOf
                        order by attempted_at desc, extraction_attempt_id desc
                        limit 1
                        """)
                .param("observationId", observationId)
                .param("asOf", utc(asOf))
                .query(String.class)
                .optional()
                .map(PublicEvidenceConfirmationPolicy.AttemptStatus::valueOf);
    }

    Optional<PublicEvidenceConfirmationPolicy.AttemptStatus> findLatestCollectionStatus(
            String productKey,
            Instant asOf) {
        return jdbc.sql("""
                        select status
                        from collection_attempt
                        where product_key = :productKey and attempted_at <= :asOf
                        order by attempted_at desc, collection_attempt_id desc
                        limit 1
                        """)
                .param("productKey", productKey)
                .param("asOf", utc(asOf))
                .query(String.class)
                .optional()
                .map(PublicEvidenceConfirmationPolicy.AttemptStatus::valueOf);
    }

    Optional<ConfirmedRow> findLatestConfirmed(String productKey, Instant asOf) {
        return jdbc.sql("""
                        select ea.attempted_at, ea.rate_quote_id,
                               o.observation_id, o.observed_at, o.source_url, o.final_url,
                               o.acquisition_method, o.snapshot_hash,
                               ptv.product_terms_version_id, ptv.terms_hash,
                               ptv.effective_from, ptv.effective_to,
                               ve.version_evidence_id, ve.parser_version
                        from extraction_attempt ea
                        join public_observation o on o.observation_id = ea.observation_id
                        join product_terms_version ptv
                          on ptv.product_terms_version_id = ea.product_terms_version_id
                        join version_evidence ve
                          on ve.version_evidence_id = ea.version_evidence_id
                         and ve.observation_id = ea.observation_id
                         and ve.product_terms_version_id = ea.product_terms_version_id
                        where ea.product_key = :productKey
                          and ea.status = 'SUCCEEDED'
                          and ea.attempted_at <= :asOf
                          and o.observed_at <= :asOf
                        order by o.observed_at desc, o.observation_id desc,
                                 ea.attempted_at desc, ea.extraction_attempt_id desc
                        limit 1
                        """)
                .param("productKey", productKey)
                .param("asOf", utc(asOf))
                .query((resultSet, rowNumber) -> new ConfirmedRow(
                        resultSet.getString("product_terms_version_id"),
                        resultSet.getString("terms_hash"),
                        resultSet.getObject("effective_from", java.time.LocalDate.class),
                        resultSet.getObject("effective_to", java.time.LocalDate.class),
                        resultSet.getString("observation_id"),
                        instant(resultSet, "observed_at"),
                        resultSet.getString("source_url"),
                        resultSet.getString("final_url"),
                        resultSet.getString("acquisition_method"),
                        resultSet.getString("snapshot_hash"),
                        resultSet.getString("version_evidence_id"),
                        resultSet.getString("parser_version"),
                        instant(resultSet, "attempted_at"),
                        resultSet.getString("rate_quote_id")))
                .optional();
    }

    List<PublicProductObservedState.Fact> findFacts(String versionId) {
        return jdbc.sql("""
                        select fact_id, fact_order, fact_key, subject_type, value_type,
                               value_text, value_integer, value_status, unit
                        from product_term_fact
                        where product_terms_version_id = :versionId
                        order by fact_order
                        """)
                .param("versionId", versionId)
                .query((resultSet, rowNumber) -> new PublicProductObservedState.Fact(
                        resultSet.getString("fact_id"),
                        resultSet.getInt("fact_order"),
                        resultSet.getString("fact_key"),
                        resultSet.getString("subject_type"),
                        resultSet.getString("value_type"),
                        resultSet.getString("value_text"),
                        resultSet.getObject("value_integer", Long.class),
                        resultSet.getString("value_status"),
                        resultSet.getString("unit")))
                .list();
    }

    Optional<PublicProductObservedState.RateQuote> findRateQuote(String rateQuoteId) {
        return jdbc.sql("""
                        select rate_quote_id, advertised_rate_text, advertised_rate_reference_date,
                               text_locator::text, reference_date_locator::text
                        from observed_rate_quote
                        where rate_quote_id = :rateQuoteId
                        """)
                .param("rateQuoteId", rateQuoteId)
                .query((resultSet, rowNumber) -> new PublicProductObservedState.RateQuote(
                        resultSet.getString("rate_quote_id"),
                        resultSet.getString("advertised_rate_text"),
                        resultSet.getObject("advertised_rate_reference_date", java.time.LocalDate.class),
                        json(resultSet.getString("text_locator")),
                        json(resultSet.getString("reference_date_locator"))))
                .optional();
    }

    List<PublicProductObservedState.FactEvidence> findEvidence(String evidenceId) {
        List<LocatorRow> rows = jdbc.sql("""
                        select locator.fact_id, locator.locator_order, locator.strategy,
                               locator.selector, locator.label, locator.evidence_text,
                               locator.evidence_hash, locator.snapshot_hash, locator.source_url
                        from fact_evidence_locator locator
                        join product_term_fact fact
                          on fact.product_terms_version_id = locator.product_terms_version_id
                         and fact.fact_id = locator.fact_id
                        where locator.version_evidence_id = :evidenceId
                        order by fact.fact_order, locator.locator_order
                        """)
                .param("evidenceId", evidenceId)
                .query((resultSet, rowNumber) -> new LocatorRow(
                        resultSet.getString("fact_id"),
                        new PublicProductObservedState.Locator(
                                resultSet.getInt("locator_order"),
                                resultSet.getString("strategy"),
                                resultSet.getString("selector"),
                                resultSet.getString("label"),
                                resultSet.getString("evidence_text"),
                                resultSet.getString("evidence_hash"),
                                resultSet.getString("snapshot_hash"),
                                resultSet.getString("source_url"))))
                .list();
        Map<String, List<PublicProductObservedState.Locator>> byFact = new LinkedHashMap<>();
        rows.forEach(row -> byFact.computeIfAbsent(row.factId(), ignored -> new ArrayList<>()).add(row.locator()));
        return byFact.entrySet().stream()
                .map(entry -> new PublicProductObservedState.FactEvidence(
                        entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }

    private JsonNode json(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("DB의 JSON evidence를 읽을 수 없습니다.", exception);
        }
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    record ProductRow(String productKey, String displayName) {
    }

    record ObservationRow(String observationId, Instant observedAt) {
    }

    record ConfirmedRow(
            String versionId,
            String termsHash,
            java.time.LocalDate effectiveFrom,
            java.time.LocalDate effectiveTo,
            String observationId,
            Instant observedAt,
            String sourceUrl,
            String finalUrl,
            String acquisitionMethod,
            String snapshotHash,
            String evidenceId,
            String parserVersion,
            Instant evidenceAvailableAt,
            String rateQuoteId) {
    }

    private record LocatorRow(String factId, PublicProductObservedState.Locator locator) {
    }
}
