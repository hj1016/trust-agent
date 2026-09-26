package com.trustagent.core.publicproduct.baseline;

import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.CHANGE_DETECTION_RESULT;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.COLLECTION_ATTEMPT;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.EXTRACTION_ATTEMPT;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.OBSERVATION;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.PRODUCT;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.PRODUCT_TERMS_VERSION;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.RATE_QUOTE;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.SNAPSHOT;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.VERSION_EVIDENCE;

import com.trustagent.core.json.CanonicalJsonHasher;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class BaselineImporter {

    private static final Pattern RUN_ID = Pattern.compile("^baseline:[a-f0-9]{32}$");
    private static final List<String> BUSINESS_TABLES = List.of(
            "public_product", "public_snapshot", "collection_attempt", "public_observation",
            "product_terms_version", "product_term_fact", "version_evidence", "fact_evidence_locator",
            "observed_rate_quote", "extraction_attempt", "change_detection_result",
            "change_detection_classification");
    private static final Map<String, String> SOURCE_ID_COLUMNS = Map.of(
            "public_product", "product_key",
            "public_snapshot", "snapshot_hash",
            "collection_attempt", "collection_attempt_id",
            "public_observation", "observation_id",
            "product_terms_version", "product_terms_version_id",
            "version_evidence", "version_evidence_id",
            "observed_rate_quote", "rate_quote_id",
            "extraction_attempt", "extraction_attempt_id",
            "change_detection_result", "change_detection_result_id");

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final BaselineDatasetLoader loader;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public BaselineImporter(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(jdbc, objectMapper, transactionManager, Clock.systemUTC());
    }

    BaselineImporter(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.loader = new BaselineDatasetLoader(objectMapper, new CanonicalJsonHasher(objectMapper));
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public BaselineImportResult importBaseline(Path inputRoot, String requestedRunId) {
        String runId = requestedRunId == null || requestedRunId.isBlank()
                ? "baseline:" + UUID.randomUUID().toString().replace("-", "")
                : requestedRunId;
        if (!RUN_ID.matcher(runId).matches()) {
            throw new BaselineImportException("INVALID_RUN_ID", "baseline run ID 형식이 올바르지 않습니다.");
        }
        Instant startedAt = clock.instant();
        BaselineDataset dataset;
        try {
            dataset = loader.load(inputRoot);
        } catch (BaselineImportException exception) {
            String fingerprint = exception.baselineFingerprint() == null
                    ? emptyFingerprint()
                    : exception.baselineFingerprint();
            JsonNode inputFiles = exception.inputFiles() == null
                    ? emptyInputFiles()
                    : exception.inputFiles();
            recordFailure(runId, fingerprint, inputFiles, startedAt, exception);
            throw exception;
        }

        try {
            return transaction.execute(status -> importInTransaction(dataset, runId, startedAt));
        } catch (BaselineImportException exception) {
            if (exception.code().equals("RUN_ID_CONFLICT")) {
                throw exception;
            }
            recordFailure(runId, dataset.fingerprint(), dataset.inputFilesJson(), startedAt, exception);
            throw exception;
        } catch (RuntimeException exception) {
            BaselineImportException translated = new BaselineImportException(
                    "BASELINE_IMPORT_FAILED", "baseline 적재 중 DB 검증에 실패했습니다.", exception);
            recordFailure(runId, dataset.fingerprint(), dataset.inputFilesJson(), startedAt, translated);
            throw translated;
        }
    }

    private BaselineImportResult importInTransaction(BaselineDataset dataset, String runId, Instant startedAt) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('trust-agent-baseline-import'))").query().singleRow();
        rejectExistingRunId(runId);
        validateFingerprintBoundary(dataset.fingerprint());
        preflightExistingHashes(dataset);

        insertProducts(dataset.records(PRODUCT));
        insertSnapshots(dataset.records(SNAPSHOT));
        insertCollectionAttempts(dataset.records(COLLECTION_ATTEMPT));
        insertObservations(dataset.records(OBSERVATION));
        insertTermsVersions(dataset.records(PRODUCT_TERMS_VERSION));
        insertVersionEvidence(dataset.records(VERSION_EVIDENCE));
        insertRateQuotes(dataset.records(RATE_QUOTE));
        insertExtractionAttempts(dataset.records(EXTRACTION_ATTEMPT));
        insertChangeDetectionResults(dataset.records(CHANGE_DETECTION_RESULT));

        Map<String, Integer> counts = expectedCounts(dataset);
        verifyGlobalCounts(counts);
        verifyFacts(dataset.records(PRODUCT_TERMS_VERSION));
        verifyLocators(dataset.records(VERSION_EVIDENCE));
        verifyClassifications(dataset.records(CHANGE_DETECTION_RESULT));

        Instant completedAt = clock.instant();
        jdbc.sql("""
                        insert into baseline_import_run (
                            baseline_import_run_id, baseline_fingerprint, started_at, completed_at,
                            status, input_files, imported_counts, error_code
                        ) values (:runId, :fingerprint, :startedAt, :completedAt,
                            'SUCCEEDED', cast(:inputFiles as jsonb), cast(:counts as jsonb), null)
                        """)
                .param("runId", runId)
                .param("fingerprint", dataset.fingerprint())
                .param("startedAt", startedAt.atOffset(ZoneOffset.UTC))
                .param("completedAt", completedAt.atOffset(ZoneOffset.UTC))
                .param("inputFiles", json(dataset.inputFilesJson()))
                .param("counts", json(counts))
                .update();
        return new BaselineImportResult(runId, dataset.fingerprint(), "SUCCEEDED", Map.copyOf(counts));
    }

    private void rejectExistingRunId(String runId) {
        if (jdbc.sql("select count(*) from baseline_import_run where baseline_import_run_id = :id")
                        .param("id", runId).query(Integer.class).single() > 0) {
            throw new BaselineImportException("RUN_ID_CONFLICT", "이미 사용한 baseline run ID입니다.");
        }
    }

    private void validateFingerprintBoundary(String fingerprint) {
        int businessRows = BUSINESS_TABLES.stream().mapToInt(this::tableCount).sum();
        List<String> fingerprints = jdbc.sql("""
                        select distinct baseline_fingerprint
                        from baseline_import_run
                        where status = 'SUCCEEDED'
                        """)
                .query(String.class).list();
        if (businessRows == 0 && fingerprints.isEmpty()) return;
        if (fingerprints.size() != 1 || !fingerprints.getFirst().equals(fingerprint)) {
            throw new BaselineImportException(
                    "BASELINE_MISMATCH",
                    "비어 있지 않은 DB에는 다른 baseline을 섞어 적재할 수 없습니다.");
        }
        if (businessRows == 0) {
            throw new BaselineImportException(
                    "BASELINE_CONTENT_MISMATCH",
                    "성공 import 이력이 있는 DB의 업무 데이터가 비어 있습니다.");
        }
    }

    private void preflightExistingHashes(BaselineDataset dataset) {
        preflightHashes(dataset.records(PRODUCT), "public_product", "product_key", "product_key");
        preflightHashes(dataset.records(SNAPSHOT), "public_snapshot", "snapshot_hash", "snapshot_hash");
        preflightHashes(dataset.records(COLLECTION_ATTEMPT), "collection_attempt", "collection_attempt_id", "collection_attempt_id");
        preflightHashes(dataset.records(OBSERVATION), "public_observation", "observation_id", "observation_id");
        preflightHashes(dataset.records(PRODUCT_TERMS_VERSION), "product_terms_version", "product_terms_version_id", "product_terms_version_id");
        preflightHashes(dataset.records(VERSION_EVIDENCE), "version_evidence", "version_evidence_id", "version_evidence_id");
        preflightHashes(dataset.records(RATE_QUOTE), "observed_rate_quote", "rate_quote_id", "rate_quote_id");
        preflightHashes(dataset.records(EXTRACTION_ATTEMPT), "extraction_attempt", "extraction_attempt_id", "extraction_attempt_id");
        preflightHashes(dataset.records(CHANGE_DETECTION_RESULT), "change_detection_result", "change_detection_result_id", "change_detection_result_id");
    }

    private void preflightHashes(
            List<BaselineDataset.SourceRecord> records,
            String table,
            String idColumn,
            String jsonIdField) {
        records.forEach(record -> existingHash(
                table, idColumn, text(record.json(), jsonIdField), record.sourceRecordHash()));
    }

    private void insertProducts(List<BaselineDataset.SourceRecord> records) {
        for (var record : records) {
            BaselineDtos.Product product = (BaselineDtos.Product) record.typedRecord();
            if (existingHash("public_product", "product_key", product.product_key(), record.sourceRecordHash())) continue;
            jdbc.sql("""
                            insert into public_product (
                                product_key, dataset_class, synthetic, display_name, source_marker,
                                source_url, source_record_hash
                            ) values (:productKey, 'PUBLIC_KB', false, :displayName, :sourceMarker,
                                :sourceUrl, :hash)
                            """)
                    .param("productKey", product.product_key())
                    .param("displayName", product.display_name())
                    .param("sourceMarker", product.source_marker())
                    .param("sourceUrl", product.source_url())
                    .param("hash", record.sourceRecordHash())
                    .update();
        }
    }

    private void insertSnapshots(List<BaselineDataset.SourceRecord> records) {
        for (var record : records) {
            BaselineDtos.Snapshot snapshot = (BaselineDtos.Snapshot) record.typedRecord();
            if (existingHash("public_snapshot", "snapshot_hash", snapshot.snapshot_hash(), record.sourceRecordHash())) continue;
            jdbc.sql("""
                            insert into public_snapshot (
                                snapshot_hash, dataset_class, synthetic, product_key, source_url,
                                snapshot_storage, snapshot_object_key, content_type, byte_size, source_record_hash
                            ) values (:id, 'PUBLIC_KB', false, :product, :url, :storage, :objectKey,
                                :contentType, :byteSize, :hash)
                            """)
                    .param("id", snapshot.snapshot_hash())
                    .param("product", snapshot.product_key())
                    .param("url", snapshot.source_url())
                    .param("storage", snapshot.snapshot_storage().name())
                    .param("objectKey", snapshot.snapshot_object_key())
                    .param("contentType", snapshot.content_type())
                    .param("byteSize", snapshot.byte_size())
                    .param("hash", record.sourceRecordHash())
                    .update();
        }
    }

    private void insertCollectionAttempts(List<BaselineDataset.SourceRecord> records) {
        for (var record : records) {
            BaselineDtos.CollectionAttempt attempt = (BaselineDtos.CollectionAttempt) record.typedRecord();
            if (existingHash("collection_attempt", "collection_attempt_id", attempt.collection_attempt_id(), record.sourceRecordHash())) continue;
            jdbc.sql("""
                            insert into collection_attempt (
                                collection_attempt_id, dataset_class, collection_run_id, product_key,
                                attempt_sequence, attempted_at, status, observation_id, error_code,
                                error_message, source_record_hash
                            ) values (:id, 'DERIVED', :runId, :product, :sequence, :attemptedAt,
                                :status, :observationId, :errorCode, :errorMessage, :hash)
                            """)
                    .param("id", attempt.collection_attempt_id())
                    .param("runId", attempt.collection_run_id())
                    .param("product", attempt.product_key())
                    .param("sequence", attempt.attempt_sequence())
                    .param("attemptedAt", utc(Instant.parse(attempt.attempted_at())))
                    .param("status", attempt.status().name())
                    .param("observationId", attempt.observation_id())
                    .param("errorCode", attempt.error_code())
                    .param("errorMessage", attempt.error_message())
                    .param("hash", record.sourceRecordHash())
                    .update();
        }
    }

    private void insertObservations(List<BaselineDataset.SourceRecord> records) {
        for (var record : records) {
            BaselineDtos.Observation observation = (BaselineDtos.Observation) record.typedRecord();
            if (existingHash("public_observation", "observation_id", observation.observation_id(), record.sourceRecordHash())) continue;
            jdbc.sql("""
                            insert into public_observation (
                                observation_id, dataset_class, synthetic, collection_attempt_id,
                                collection_run_id, product_key, source_url, final_url, observed_at,
                                acquisition_method, acquisition_note, snapshot_manifest_path,
                                snapshot_hash, source_record_hash
                            ) values (:id, 'PUBLIC_KB', false, :attemptId, :runId, :product,
                                :sourceUrl, :finalUrl, :observedAt, :method, :note, :manifestPath,
                                :snapshotHash, :hash)
                            """)
                    .param("id", observation.observation_id())
                    .param("attemptId", observation.collection_attempt_id())
                    .param("runId", observation.collection_run_id())
                    .param("product", observation.product_key())
                    .param("sourceUrl", observation.source_url())
                    .param("finalUrl", observation.final_url())
                    .param("observedAt", utc(Instant.parse(observation.observed_at())))
                    .param("method", observation.acquisition_method().name())
                    .param("note", observation.acquisition_note())
                    .param("manifestPath", observation.snapshot_manifest_path())
                    .param("snapshotHash", observation.snapshot_hash())
                    .param("hash", record.sourceRecordHash())
                    .update();
        }
    }

    private void insertTermsVersions(List<BaselineDataset.SourceRecord> records) {
        for (var record : records) {
            BaselineDtos.ProductTermsVersion version = (BaselineDtos.ProductTermsVersion) record.typedRecord();
            String id = version.product_terms_version_id();
            if (existingHash("product_terms_version", "product_terms_version_id", id, record.sourceRecordHash())) continue;
            jdbc.sql("""
                            insert into product_terms_version (
                                product_terms_version_id, dataset_class, synthetic, product_key,
                                terms_hash, effective_from, effective_to, source_record_hash
                            ) values (:id, 'PUBLIC_KB', false, :product, :termsHash,
                                :effectiveFrom, :effectiveTo, :hash)
                            """)
                    .param("id", id)
                    .param("product", version.product_key())
                    .param("termsHash", version.terms_hash())
                    .param("effectiveFrom", nullableDate(version.effective_from()))
                    .param("effectiveTo", nullableDate(version.effective_to()))
                    .param("hash", record.sourceRecordHash())
                    .update();
            int order = 0;
            for (BaselineDtos.ProductTermFact fact : version.facts()) insertFact(id, order++, fact);
        }
    }

    private void insertFact(String versionId, int order, BaselineDtos.ProductTermFact fact) {
        String valueType = fact.value_type().name();
        jdbc.sql("""
                        insert into product_term_fact (
                            product_terms_version_id, fact_id, fact_order, fact_key, subject_type,
                            value_type, value_text, value_integer, value_status, unit
                        ) values (:versionId, :factId, :factOrder, :factKey, :subjectType,
                            :valueType, :valueText, :valueInteger, :valueStatus, :unit)
                        """)
                .param("versionId", versionId)
                .param("factId", fact.fact_id())
                .param("factOrder", order)
                .param("factKey", fact.fact_key().name())
                .param("subjectType", fact.subject_type().name())
                .param("valueType", valueType)
                .param("valueText", valueType.equals("TEXT") ? fact.value().stringValue() : null)
                .param("valueInteger", valueType.equals("INTEGER") ? fact.value().longValue() : null)
                .param("valueStatus", valueType.equals("STATUS") ? fact.value().stringValue() : null)
                .param("unit", fact.unit().name())
                .update();
    }

    private void insertVersionEvidence(List<BaselineDataset.SourceRecord> records) {
        for (var record : records) {
            BaselineDtos.VersionEvidence evidence = (BaselineDtos.VersionEvidence) record.typedRecord();
            String id = evidence.version_evidence_id();
            if (existingHash("version_evidence", "version_evidence_id", id, record.sourceRecordHash())) continue;
            jdbc.sql("""
                            insert into version_evidence (
                                version_evidence_id, dataset_class, synthetic, observation_id,
                                product_terms_version_id, product_key, snapshot_hash, parser_version,
                                source_record_hash
                            ) values (:id, 'PUBLIC_KB', false, :observationId, :versionId,
                                :product, :snapshotHash, :parserVersion, :hash)
                            """)
                    .param("id", id)
                    .param("observationId", evidence.observation_id())
                    .param("versionId", evidence.product_terms_version_id())
                    .param("product", evidence.product_key())
                    .param("snapshotHash", evidence.snapshot_hash())
                    .param("parserVersion", evidence.parser_version())
                    .param("hash", record.sourceRecordHash())
                    .update();
            for (BaselineDtos.FactEvidence item : evidence.fact_evidence()) {
                int order = 0;
                for (BaselineDtos.Locator locator : item.locators()) {
                    insertLocator(id, evidence.product_terms_version_id(), item.fact_id(), order++, locator);
                }
            }
        }
    }

    private void insertLocator(
            String evidenceId,
            String versionId,
            String factId,
            int order,
            BaselineDtos.Locator locator) {
        jdbc.sql("""
                        insert into fact_evidence_locator (
                            version_evidence_id, product_terms_version_id, fact_id, locator_order,
                            strategy, selector, label, evidence_text, evidence_hash, snapshot_hash,
                            source_url
                        ) values (:evidenceId, :versionId, :factId, :locatorOrder, :strategy,
                            :selector, :label, :evidenceText, :evidenceHash, :snapshotHash, :sourceUrl)
                        """)
                .param("evidenceId", evidenceId)
                .param("versionId", versionId)
                .param("factId", factId)
                .param("locatorOrder", order)
                .param("strategy", locator.strategy().name())
                .param("selector", locator.selector())
                .param("label", locator.label())
                .param("evidenceText", locator.evidence_text())
                .param("evidenceHash", locator.evidence_hash())
                .param("snapshotHash", locator.snapshot_hash())
                .param("sourceUrl", locator.source_url())
                .update();
    }

    private void insertRateQuotes(List<BaselineDataset.SourceRecord> records) {
        for (var record : records) {
            BaselineDtos.RateQuote quote = (BaselineDtos.RateQuote) record.typedRecord();
            if (existingHash("observed_rate_quote", "rate_quote_id", quote.rate_quote_id(), record.sourceRecordHash())) continue;
            jdbc.sql("""
                            insert into observed_rate_quote (
                                rate_quote_id, dataset_class, synthetic, observation_id, product_key,
                                advertised_rate_text, advertised_rate_reference_date, text_locator,
                                reference_date_locator, source_record_hash
                            ) values (:id, 'PUBLIC_KB', false, :observationId, :product, :rateText,
                                :referenceDate, cast(:textLocator as jsonb), cast(:dateLocator as jsonb), :hash)
                            """)
                    .param("id", quote.rate_quote_id())
                    .param("observationId", quote.observation_id())
                    .param("product", quote.product_key())
                    .param("rateText", quote.advertised_rate_text())
                    .param("referenceDate", nullableDate(quote.advertised_rate_reference_date()))
                    .param("textLocator", json(quote.text_locator()))
                    .param("dateLocator", quote.reference_date_locator() == null ? null : json(quote.reference_date_locator()))
                    .param("hash", record.sourceRecordHash())
                    .update();
        }
    }

    private void insertExtractionAttempts(List<BaselineDataset.SourceRecord> records) {
        for (var record : records) {
            BaselineDtos.ExtractionAttempt attempt = (BaselineDtos.ExtractionAttempt) record.typedRecord();
            if (existingHash("extraction_attempt", "extraction_attempt_id", attempt.extraction_attempt_id(), record.sourceRecordHash())) continue;
            jdbc.sql("""
                            insert into extraction_attempt (
                                extraction_attempt_id, dataset_class, extraction_run_id, observation_id,
                                product_key, attempt_sequence, attempted_at, attempted_at_source,
                                parser_version, status, product_terms_version_id, version_evidence_id,
                                rate_quote_id, error_code, error_message, source_record_hash
                            ) values (:id, 'DERIVED', :runId, :observationId, :product, :sequence,
                                :attemptedAt, :attemptedAtSource, :parserVersion, :status, :versionId,
                                :evidenceId, :quoteId, :errorCode, :errorMessage, :hash)
                            """)
                    .param("id", attempt.extraction_attempt_id())
                    .param("runId", attempt.extraction_run_id())
                    .param("observationId", attempt.observation_id())
                    .param("product", attempt.product_key())
                    .param("sequence", attempt.attempt_sequence())
                    .param("attemptedAt", utc(Instant.parse(attempt.attempted_at())))
                    .param("attemptedAtSource", attempt.attempted_at_source().name())
                    .param("parserVersion", attempt.parser_version())
                    .param("status", attempt.status().name())
                    .param("versionId", attempt.product_terms_version_id())
                    .param("evidenceId", attempt.version_evidence_id())
                    .param("quoteId", attempt.rate_quote_id())
                    .param("errorCode", attempt.error_code())
                    .param("errorMessage", attempt.error_message())
                    .param("hash", record.sourceRecordHash())
                    .update();
        }
    }

    private void insertChangeDetectionResults(List<BaselineDataset.SourceRecord> records) {
        for (var record : records) {
            BaselineDtos.ChangeDetectionResult change =
                    (BaselineDtos.ChangeDetectionResult) record.typedRecord();
            String id = change.change_detection_result_id();
            if (existingHash("change_detection_result", "change_detection_result_id", id, record.sourceRecordHash())) continue;
            jdbc.sql("""
                            insert into change_detection_result (
                                change_detection_result_id, dataset_class, product_key, observation_id,
                                previous_observation_id, evaluated_at, policy_version,
                                supersedes_result_id, source_record_hash
                            ) values (:id, 'DERIVED', :product, :observationId, :previousObservationId,
                                :evaluatedAt, :policyVersion, :supersedesResultId, :hash)
                            """)
                    .param("id", id)
                    .param("product", change.product_key())
                    .param("observationId", change.observation_id())
                    .param("previousObservationId", change.previous_observation_id())
                    .param("evaluatedAt", utc(Instant.parse(change.evaluated_at())))
                    .param("policyVersion", change.policy_version())
                    .param("supersedesResultId", change.supersedes_result_id())
                    .param("hash", record.sourceRecordHash())
                    .update();
            int order = 0;
            for (BaselineDtos.ChangeClassification value : change.classifications()) {
                jdbc.sql("""
                                insert into change_detection_classification (
                                    change_detection_result_id, classification_order, classification
                                ) values (:id, :classificationOrder, :classification)
                                """)
                        .param("id", id)
                        .param("classificationOrder", order++)
                        .param("classification", value.name())
                        .update();
            }
        }
    }

    private boolean existingHash(String table, String idColumn, String id, String expectedHash) {
        requireSourceIdentifier(table, idColumn);
        Optional<String> actual = jdbc.sql("select source_record_hash from " + table + " where " + idColumn + " = :id")
                .param("id", id).query(String.class).optional();
        if (actual.isEmpty()) return false;
        if (!actual.get().equals(expectedHash)) {
            throw new BaselineImportException("SOURCE_RECORD_CONFLICT", "동일 ID의 canonical JSON hash가 다릅니다.");
        }
        return true;
    }

    private Map<String, Integer> expectedCounts(BaselineDataset dataset) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("public_product", dataset.records(PRODUCT).size());
        counts.put("public_snapshot", dataset.records(SNAPSHOT).size());
        counts.put("collection_attempt", dataset.records(COLLECTION_ATTEMPT).size());
        counts.put("public_observation", dataset.records(OBSERVATION).size());
        counts.put("product_terms_version", dataset.records(PRODUCT_TERMS_VERSION).size());
        counts.put("product_term_fact", nestedCount(dataset.records(PRODUCT_TERMS_VERSION), "facts"));
        counts.put("version_evidence", dataset.records(VERSION_EVIDENCE).size());
        counts.put("fact_evidence_locator", locatorCount(dataset.records(VERSION_EVIDENCE)));
        counts.put("observed_rate_quote", dataset.records(RATE_QUOTE).size());
        counts.put("extraction_attempt", dataset.records(EXTRACTION_ATTEMPT).size());
        counts.put("change_detection_result", dataset.records(CHANGE_DETECTION_RESULT).size());
        counts.put("change_detection_classification", nestedCount(dataset.records(CHANGE_DETECTION_RESULT), "classifications"));
        return counts;
    }

    private int nestedCount(List<BaselineDataset.SourceRecord> records, String field) {
        return records.stream().mapToInt(record -> record.json().get(field).size()).sum();
    }

    private int locatorCount(List<BaselineDataset.SourceRecord> records) {
        int count = 0;
        for (var record : records) for (JsonNode fact : record.json().get("fact_evidence")) count += fact.get("locators").size();
        return count;
    }

    private void verifyGlobalCounts(Map<String, Integer> expected) {
        Map<String, Integer> actual = new LinkedHashMap<>();
        expected.keySet().forEach(table -> actual.put(table, tableCount(table)));
        List<String> tablesWithExtraRows = expected.keySet().stream()
                .filter(table -> actual.get(table) > expected.get(table))
                .toList();
        if (!tablesWithExtraRows.isEmpty()) {
            throw new BaselineImportException(
                    "RUNTIME_DATA_PRESENT",
                    "baseline에 없는 runtime 행이 있어 재적재할 수 없습니다: "
                            + String.join(", ", tablesWithExtraRows));
        }
        List<String> tablesWithMissingRows = expected.keySet().stream()
                .filter(table -> actual.get(table) < expected.get(table))
                .toList();
        if (!tablesWithMissingRows.isEmpty()) {
            throw new BaselineImportException(
                    "BASELINE_CONTENT_MISMATCH",
                    "DB의 baseline 행 개수가 입력보다 적습니다: "
                            + String.join(", ", tablesWithMissingRows));
        }
    }

    private void verifyFacts(List<BaselineDataset.SourceRecord> records) {
        for (var record : records) {
            String versionId = text(record.json(), "product_terms_version_id");
            int order = 0;
            for (JsonNode fact : record.json().get("facts")) {
                String valueType = text(fact, "value_type");
                int matches = jdbc.sql("""
                                select count(*) from product_term_fact
                                where product_terms_version_id = :versionId and fact_id = :factId
                                  and fact_order = :factOrder and fact_key = :factKey
                                  and subject_type = :subjectType and value_type = :valueType
                                  and value_text is not distinct from :valueText
                                  and value_integer is not distinct from :valueInteger
                                  and value_status is not distinct from :valueStatus and unit = :unit
                                """)
                        .param("versionId", versionId).param("factId", text(fact, "fact_id"))
                        .param("factOrder", order++).param("factKey", text(fact, "fact_key"))
                        .param("subjectType", text(fact, "subject_type")).param("valueType", valueType)
                        .param("valueText", valueType.equals("TEXT") ? fact.get("value").stringValue() : null)
                        .param("valueInteger", valueType.equals("INTEGER") ? fact.get("value").longValue() : null)
                        .param("valueStatus", valueType.equals("STATUS") ? fact.get("value").stringValue() : null)
                        .param("unit", text(fact, "unit")).query(Integer.class).single();
                if (matches != 1) throw new BaselineImportException("BASELINE_CONTENT_MISMATCH", "상품 fact 하위 행이 입력과 다릅니다.");
            }
        }
    }

    private void verifyLocators(List<BaselineDataset.SourceRecord> records) {
        for (var record : records) {
            JsonNode root = record.json();
            for (JsonNode fact : root.get("fact_evidence")) {
                int order = 0;
                for (JsonNode locator : fact.get("locators")) {
                    int matches = jdbc.sql("""
                                    select count(*) from fact_evidence_locator
                                    where version_evidence_id = :evidenceId
                                      and product_terms_version_id = :versionId and fact_id = :factId
                                      and locator_order = :locatorOrder and strategy = :strategy
                                      and selector = :selector and label = :label
                                      and evidence_text = :evidenceText and evidence_hash = :evidenceHash
                                      and snapshot_hash = :snapshotHash and source_url = :sourceUrl
                                    """)
                            .param("evidenceId", text(root, "version_evidence_id"))
                            .param("versionId", text(root, "product_terms_version_id"))
                            .param("factId", text(fact, "fact_id")).param("locatorOrder", order++)
                            .param("strategy", text(locator, "strategy")).param("selector", text(locator, "selector"))
                            .param("label", text(locator, "label")).param("evidenceText", text(locator, "evidence_text"))
                            .param("evidenceHash", text(locator, "evidence_hash")).param("snapshotHash", text(locator, "snapshot_hash"))
                            .param("sourceUrl", text(locator, "source_url")).query(Integer.class).single();
                    if (matches != 1) throw new BaselineImportException("BASELINE_CONTENT_MISMATCH", "근거 locator 하위 행이 입력과 다릅니다.");
                }
            }
        }
    }

    private void verifyClassifications(List<BaselineDataset.SourceRecord> records) {
        for (var record : records) {
            JsonNode root = record.json();
            int order = 0;
            for (JsonNode classification : root.get("classifications")) {
                int matches = jdbc.sql("""
                                select count(*) from change_detection_classification
                                where change_detection_result_id = :id
                                  and classification_order = :classificationOrder
                                  and classification = :classification
                                """)
                        .param("id", text(root, "change_detection_result_id"))
                        .param("classificationOrder", order++)
                        .param("classification", classification.stringValue())
                        .query(Integer.class).single();
                if (matches != 1) throw new BaselineImportException("BASELINE_CONTENT_MISMATCH", "변경 분류 하위 행이 입력과 다릅니다.");
            }
        }
    }

    private int tableCount(String table) {
        requireBusinessTableIdentifier(table);
        return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
    }

    static void requireBusinessTableIdentifier(String table) {
        if (!BUSINESS_TABLES.contains(table)) {
            throw new IllegalArgumentException("허용되지 않은 baseline table 식별자입니다: " + table);
        }
    }

    static void requireSourceIdentifier(String table, String idColumn) {
        if (!idColumn.equals(SOURCE_ID_COLUMNS.get(table))) {
            throw new IllegalArgumentException(
                    "허용되지 않은 baseline source 식별자입니다: " + table + "." + idColumn);
        }
    }

    private void recordFailure(
            String runId,
            String fingerprint,
            JsonNode inputFiles,
            Instant startedAt,
            BaselineImportException originalFailure) {
        try {
            transaction.executeWithoutResult(status -> jdbc.sql("""
                            insert into baseline_import_run (
                                baseline_import_run_id, baseline_fingerprint, started_at, completed_at,
                                status, input_files, imported_counts, error_code
                            ) values (:runId, :fingerprint, :startedAt, :completedAt,
                                'FAILED', cast(:inputFiles as jsonb), null, :errorCode)
                            """)
                    .param("runId", runId)
                    .param("fingerprint", fingerprint)
                    .param("startedAt", utc(startedAt))
                    .param("completedAt", utc(clock.instant()))
                    .param("inputFiles", json(inputFiles))
                    .param("errorCode", originalFailure.code())
                    .update());
        } catch (RuntimeException auditFailure) {
            throw failureAuditWriteException(originalFailure, auditFailure);
        }
    }

    static BaselineImportException failureAuditWriteException(
            BaselineImportException originalFailure,
            RuntimeException auditFailure) {
        BaselineImportException wrapped = new BaselineImportException(
                "FAILURE_AUDIT_WRITE_FAILED",
                "baseline 실패 감사 기록을 저장할 수 없습니다 (원래 실패: "
                        + originalFailure.code() + ").",
                auditFailure);
        wrapped.addSuppressed(originalFailure);
        return wrapped;
    }

    private String emptyFingerprint() {
        return new CanonicalJsonHasher(objectMapper).canonicalize(objectMapper.createArrayNode()).sha256();
    }

    private JsonNode emptyInputFiles() {
        return objectMapper.createArrayNode();
    }

    private String text(JsonNode node, String field) {
        return node.get(field).stringValue();
    }

    private LocalDate nullableDate(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new BaselineImportException("JSON_SERIALIZATION_FAILED", "감사 JSON을 직렬화할 수 없습니다.", exception);
        }
    }
}
