package com.trustagent.core.publicproduct.baseline;

import java.util.List;
import tools.jackson.databind.JsonNode;

final class BaselineDtos {

    private BaselineDtos() {
    }

    sealed interface BaselineRecord permits Product, Snapshot, CollectionAttempt, Observation,
            ProductTermsVersion, VersionEvidence, RateQuote, ExtractionAttempt,
            ChangeDetectionResult {
    }

    record Product(
            DatasetClass dataset_class,
            boolean synthetic,
            String product_key,
            String display_name,
            String source_marker,
            String source_url) implements BaselineRecord {
    }

    record Snapshot(
            DatasetClass dataset_class,
            String product_key,
            String source_url,
            SnapshotStorage snapshot_storage,
            String snapshot_object_key,
            String content_type,
            long byte_size,
            String snapshot_hash,
            boolean synthetic) implements BaselineRecord {
    }

    record CollectionAttempt(
            DatasetClass dataset_class,
            String collection_attempt_id,
            String collection_run_id,
            String product_key,
            int attempt_sequence,
            String attempted_at,
            AttemptStatus status,
            String observation_id,
            String error_code,
            String error_message) implements BaselineRecord {
    }

    record Observation(
            DatasetClass dataset_class,
            boolean synthetic,
            String observation_id,
            String collection_attempt_id,
            String collection_run_id,
            String product_key,
            String source_url,
            String final_url,
            String observed_at,
            AcquisitionMethod acquisition_method,
            String acquisition_note,
            String snapshot_manifest_path,
            String snapshot_hash) implements BaselineRecord {
    }

    record ProductTermsVersion(
            DatasetClass dataset_class,
            boolean synthetic,
            String product_terms_version_id,
            String product_key,
            String terms_hash,
            String effective_from,
            String effective_to,
            List<ProductTermFact> facts) implements BaselineRecord {
    }

    record ProductTermFact(
            String fact_id,
            FactKey fact_key,
            SubjectType subject_type,
            ValueType value_type,
            JsonNode value,
            Unit unit) {
    }

    record VersionEvidence(
            DatasetClass dataset_class,
            boolean synthetic,
            String version_evidence_id,
            String observation_id,
            String product_terms_version_id,
            String product_key,
            String snapshot_hash,
            String parser_version,
            List<FactEvidence> fact_evidence) implements BaselineRecord {
    }

    record FactEvidence(String fact_id, List<Locator> locators) {
    }

    record Locator(
            LocatorStrategy strategy,
            String selector,
            String label,
            String evidence_text,
            String evidence_hash,
            String snapshot_hash,
            String source_url) {
    }

    record RateQuote(
            DatasetClass dataset_class,
            boolean synthetic,
            String rate_quote_id,
            String observation_id,
            String product_key,
            String advertised_rate_text,
            String advertised_rate_reference_date,
            Locator text_locator,
            Locator reference_date_locator) implements BaselineRecord {
    }

    record ExtractionAttempt(
            DatasetClass dataset_class,
            String extraction_attempt_id,
            String extraction_run_id,
            String observation_id,
            String product_key,
            int attempt_sequence,
            String attempted_at,
            AttemptedAtSource attempted_at_source,
            String parser_version,
            AttemptStatus status,
            String product_terms_version_id,
            String version_evidence_id,
            String rate_quote_id,
            String error_code,
            String error_message) implements BaselineRecord {
    }

    record ChangeDetectionResult(
            DatasetClass dataset_class,
            String change_detection_result_id,
            String product_key,
            String observation_id,
            String previous_observation_id,
            String evaluated_at,
            String policy_version,
            List<ChangeClassification> classifications,
            String supersedes_result_id) implements BaselineRecord {
    }

    enum DatasetClass { PUBLIC_KB, DERIVED }
    enum SnapshotStorage { LOCAL_PRIVATE, PRIVATE_OBJECT_STORAGE }
    enum AttemptStatus { SUCCEEDED, FAILED }
    enum AcquisitionMethod { HTTP_DOWNLOAD, MANUAL_DOWNLOAD }
    enum AttemptedAtSource { MEASURED, BACKFILLED_FROM_OBSERVATION }
    enum FactKey {
        product_name,
        applicant_eligibility_text,
        max_limit_individual_krw,
        max_limit_corporate_krw,
        repayment_method_text,
        sale_status
    }
    enum SubjectType { PRODUCT, SOLE_PROPRIETOR, CORPORATION }
    enum ValueType { TEXT, INTEGER, STATUS }
    enum Unit { TEXT, KRW, STATUS }
    enum LocatorStrategy { CSS_ATTRIBUTE, LABELED_ADJACENT_TEXT, CSS_TEXT_MATCH }
    enum ChangeClassification {
        BASELINE_ESTABLISHED,
        PRODUCT_TERMS_CHANGED,
        RATE_QUOTE_CHANGED,
        QUOTE_REFRESHED,
        NO_SEMANTIC_CHANGE
    }
}
