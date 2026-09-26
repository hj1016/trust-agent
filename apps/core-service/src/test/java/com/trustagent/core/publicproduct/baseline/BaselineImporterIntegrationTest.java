package com.trustagent.core.publicproduct.baseline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.json.CanonicalJsonHasher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class BaselineImporterIntegrationTest {

    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC);

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void importsAllBaselineRowsAndReimportIsIdempotent() throws Exception {
        TestDatabase database = newDatabase();
        Path repositoryRoot = Path.of(System.getProperty("trustAgent.repositoryRoot"));

        BaselineImportResult first = database.importer().importBaseline(
                repositoryRoot, "baseline:11111111111111111111111111111111");
        assertEquals("SUCCEEDED", first.status());
        assertEquals(expectedCounts(), first.importedCounts());
        assertDatabaseCounts(database.jdbc(), expectedCounts());
        BaselineDataset loaded = new BaselineDatasetLoader(JSON, new CanonicalJsonHasher(JSON))
                .load(repositoryRoot);
        assertInstanceOf(BaselineDtos.Product.class, loaded.records(BaselineDataset.RecordType.PRODUCT).getFirst().typedRecord());
        BaselineDtos.ExtractionAttempt typedAttempt = assertInstanceOf(
                BaselineDtos.ExtractionAttempt.class,
                loaded.records(BaselineDataset.RecordType.EXTRACTION_ATTEMPT).getFirst().typedRecord());
        assertEquals(BaselineDtos.AttemptedAtSource.BACKFILLED_FROM_OBSERVATION, typedAttempt.attempted_at_source());
        assertTopLevelIdsAndHashesMatch(database.jdbc(), loaded);
        assertEquals(1, count(database.jdbc(), "baseline_import_run"));
        assertEquals(5, database.jdbc().sql("""
                        select count(*) from extraction_attempt
                        where attempted_at_source = 'BACKFILLED_FROM_OBSERVATION'
                        """).query(Integer.class).single());
        OffsetDateTime storedObservationTime = database.jdbc().sql("""
                        select observed_at from public_observation
                        where observation_id = 'obs:small-business-credit:3aad2aab1ffa5171a5b39b1c3b806c6c'
                        """).query(OffsetDateTime.class).single();
        assertEquals(Instant.parse("2026-09-22T14:00:36.524273Z"), storedObservationTime.toInstant());
        OffsetDateTime storedRunTime = database.jdbc().sql("""
                        select started_at from baseline_import_run
                        where baseline_import_run_id = 'baseline:11111111111111111111111111111111'
                        """).query(OffsetDateTime.class).single();
        assertEquals(CLOCK.instant(), storedRunTime.toInstant());
        String inputFiles = database.jdbc().sql("""
                        select input_files::text from baseline_import_run
                        where baseline_import_run_id = 'baseline:11111111111111111111111111111111'
                        """).query(String.class).single();
        JsonNode inputInventory = JSON.readTree(inputFiles);
        assertEquals(39, inputInventory.size());
        assertEquals(loaded.inputFilesJson(), inputInventory);
        assertEquals(loaded.fingerprint(), first.baselineFingerprint());
        JsonNode storedCounts = JSON.readTree(database.jdbc().sql("""
                        select imported_counts::text from baseline_import_run
                        where baseline_import_run_id = 'baseline:11111111111111111111111111111111'
                        """).query(String.class).single());
        expectedCounts().forEach((table, count) ->
                assertEquals(count, storedCounts.get(table).intValue(), table));

        BaselineImportResult second = database.importer().importBaseline(
                repositoryRoot, "baseline:22222222222222222222222222222222");
        assertEquals(first.baselineFingerprint(), second.baselineFingerprint());
        assertDatabaseCounts(database.jdbc(), expectedCounts());
        assertEquals(2, count(database.jdbc(), "baseline_import_run"));
        assertEquals(2, database.jdbc().sql("""
                        select count(*) from baseline_import_run
                        where status = 'SUCCEEDED' and baseline_fingerprint = :fingerprint
                        """).param("fingerprint", first.baselineFingerprint()).query(Integer.class).single());
    }

    @Test
    void differentBaselineIsRejectedBeforeIndividualSourceConflict(@TempDir Path tempDir) throws Exception {
        TestDatabase database = newDatabase();
        Path baseline = copyBaseline(tempDir);
        database.importer().importBaseline(baseline, "baseline:33333333333333333333333333333333");

        Path catalog = baseline.resolve("datasets/public/kb/catalog/product-catalog.json");
        ObjectNode catalogJson = (ObjectNode) JSON.readTree(Files.readAllBytes(catalog));
        ((ObjectNode) catalogJson.get("products").get(0)).put("display_name", "충돌 상품명");
        Files.writeString(catalog, JSON.writeValueAsString(catalogJson));

        BaselineImportException error = assertThrows(
                BaselineImportException.class,
                () -> database.importer().importBaseline(
                        baseline, "baseline:44444444444444444444444444444444"));
        assertEquals("BASELINE_MISMATCH", error.code());
        assertDatabaseCounts(database.jdbc(), expectedCounts());
        assertFailedRun(database.jdbc(), "baseline:44444444444444444444444444444444", "BASELINE_MISMATCH");
    }

    @Test
    void sameFingerprintWithCorruptedStoredSourceHashReportsSourceConflict() throws Exception {
        TestDatabase database = newDatabase();
        Path repositoryRoot = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        database.importer().importBaseline(
                repositoryRoot, "baseline:34343434343434343434343434343434");

        performMaintenanceUpdate(
                database.url(),
                "update public_product set source_record_hash = 'sha256:' || repeat('f', 64) "
                        + "where product_key = 'kb-seller-loan'",
                "TEST-HASH-1",
                "source hash 충돌 진단 검증");

        BaselineImportException error = assertThrows(
                BaselineImportException.class,
                () -> database.importer().importBaseline(
                        repositoryRoot, "baseline:35353535353535353535353535353535"));
        assertEquals("SOURCE_RECORD_CONFLICT", error.code());
        assertFailedRun(database.jdbc(), "baseline:35353535353535353535353535353535", "SOURCE_RECORD_CONFLICT");
    }

    @Test
    void runtimeRowsRejectBaselineReimportWithDedicatedCode() throws Exception {
        TestDatabase database = newDatabase();
        Path repositoryRoot = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        database.importer().importBaseline(
                repositoryRoot, "baseline:36363636363636363636363636363636");
        database.jdbc().sql("""
                        insert into public_product (
                            product_key, dataset_class, synthetic, display_name, source_marker,
                            source_url, source_record_hash
                        ) values (
                            'runtime-product', 'PUBLIC_KB', false, '운영 수집 상품', '운영상품',
                            'https://obank.kbstar.com/runtime-product', :hash
                        )
                        """)
                .param("hash", "sha256:" + "e".repeat(64))
                .update();

        BaselineImportException error = assertThrows(
                BaselineImportException.class,
                () -> database.importer().importBaseline(
                        repositoryRoot, "baseline:37373737373737373737373737373737"));
        assertEquals("RUNTIME_DATA_PRESENT", error.code());
        assertEquals(4, count(database.jdbc(), "public_product"));
        assertFailedRun(database.jdbc(), "baseline:37373737373737373737373737373737", "RUNTIME_DATA_PRESENT");
    }

    @Test
    void auditWriteFailurePreservesOriginalFailureCodeAndException() {
        BaselineImportException original = new BaselineImportException(
                "BASELINE_MISMATCH", "원래 baseline 오류");
        RuntimeException auditFailure = new RuntimeException("감사 저장 실패");

        BaselineImportException wrapped = BaselineImporter.failureAuditWriteException(
                original, auditFailure);

        assertEquals("FAILURE_AUDIT_WRITE_FAILED", wrapped.code());
        assertTrue(wrapped.getMessage().contains("BASELINE_MISMATCH"));
        assertEquals(auditFailure, wrapped.getCause());
        assertEquals(1, wrapped.getSuppressed().length);
        assertEquals(original, wrapped.getSuppressed()[0]);
    }

    @Test
    void sqlIdentifiersOutsideTheInternalAllowlistAreRejected() {
        BaselineImporter.requireBusinessTableIdentifier("public_product");
        BaselineImporter.requireSourceIdentifier("public_product", "product_key");

        assertThrows(
                IllegalArgumentException.class,
                () -> BaselineImporter.requireBusinessTableIdentifier("public_product; drop table public_product"));
        assertThrows(
                IllegalArgumentException.class,
                () -> BaselineImporter.requireSourceIdentifier("public_product", "display_name"));
        assertThrows(
                IllegalArgumentException.class,
                () -> BaselineImporter.requireSourceIdentifier("unknown_table", "product_key"));
    }

    @Test
    void differentFingerprintCannotBeMixedIntoNonEmptyDatabase(@TempDir Path tempDir) throws Exception {
        TestDatabase database = newDatabase();
        Path baseline = copyBaseline(tempDir);
        BaselineImportResult original = database.importer().importBaseline(
                baseline, "baseline:55555555555555555555555555555555");

        Path catalog = baseline.resolve("datasets/public/kb/catalog/product-catalog.json");
        ObjectNode catalogJson = (ObjectNode) JSON.readTree(Files.readAllBytes(catalog));
        ObjectNode additional = catalogJson.withArray("products").addObject();
        additional.put("product_key", "future-product");
        additional.put("display_name", "미래 상품");
        additional.put("source_marker", "미래상품");
        additional.put("source_url", "https://obank.kbstar.com/future-product");
        Files.writeString(catalog, JSON.writeValueAsString(catalogJson));

        BaselineImportException error = assertThrows(
                BaselineImportException.class,
                () -> database.importer().importBaseline(
                        baseline, "baseline:66666666666666666666666666666666"));
        assertEquals("BASELINE_MISMATCH", error.code());
        assertDatabaseCounts(database.jdbc(), expectedCounts());
        assertFailedRun(database.jdbc(), "baseline:66666666666666666666666666666666", "BASELINE_MISMATCH");
        String failedFingerprint = database.jdbc().sql("""
                        select baseline_fingerprint from baseline_import_run
                        where baseline_import_run_id = 'baseline:66666666666666666666666666666666'
                        """).query(String.class).single();
        assertNotEquals(original.baselineFingerprint(), failedFingerprint);
    }

    @Test
    void missingChildRowIsNotHiddenAsIdempotentSuccess() throws Exception {
        TestDatabase database = newDatabase();
        Path repositoryRoot = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        database.importer().importBaseline(
                repositoryRoot, "baseline:77777777777777777777777777777777");

        try (Connection connection = DriverManager.getConnection(
                database.url(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select set_config('trust_agent.maintenance_ticket', 'TEST-CHILD-1', true)");
                statement.execute("select set_config('trust_agent.maintenance_reason', '하위 행 손상 검증', true)");
                statement.execute("select set_config('trust_agent.maintenance_actor', 'integration-test', true)");
                statement.executeUpdate("""
                        delete from fact_evidence_locator
                        where ctid = (select ctid from fact_evidence_locator limit 1)
                        """);
                connection.commit();
            }
        }

        BaselineImportException error = assertThrows(
                BaselineImportException.class,
                () -> database.importer().importBaseline(
                        repositoryRoot, "baseline:88888888888888888888888888888888"));
        assertEquals("BASELINE_CONTENT_MISMATCH", error.code());
        assertEquals(29, count(database.jdbc(), "fact_evidence_locator"));
        assertFailedRun(database.jdbc(), "baseline:88888888888888888888888888888888", "BASELINE_CONTENT_MISMATCH");
    }

    @Test
    void invalidEnumMissingFileAndBrokenReferenceLeaveOnlyFailureAudit(@TempDir Path tempDir) throws Exception {
        TestDatabase invalidEnumDb = newDatabase();
        Path invalidEnumBaseline = copyBaseline(tempDir.resolve("enum"));
        Path attempt = firstJson(invalidEnumBaseline.resolve("datasets/derived/public-kb/extraction-attempts"));
        ObjectNode attemptJson = (ObjectNode) JSON.readTree(Files.readAllBytes(attempt));
        attemptJson.put("attempted_at_source", "GUESSED");
        Files.writeString(attempt, JSON.writeValueAsString(attemptJson));

        BaselineImportException enumError = assertThrows(
                BaselineImportException.class,
                () -> invalidEnumDb.importer().importBaseline(
                        invalidEnumBaseline, "baseline:99999999999999999999999999999999"));
        assertEquals("INVALID_BASELINE", enumError.code());
        assertNoBusinessRows(invalidEnumDb.jdbc());
        assertFailedRun(invalidEnumDb.jdbc(), "baseline:99999999999999999999999999999999", "INVALID_BASELINE");
        assertEquals(39, invalidEnumDb.jdbc().sql("""
                        select jsonb_array_length(input_files) from baseline_import_run
                        where baseline_import_run_id = 'baseline:99999999999999999999999999999999'
                        """).query(Integer.class).single());

        TestDatabase brokenReferenceDb = newDatabase();
        Path brokenBaseline = copyBaseline(tempDir.resolve("reference"));
        Path observation = firstJson(brokenBaseline.resolve("datasets/public/kb/observations"));
        ObjectNode observationJson = (ObjectNode) JSON.readTree(Files.readAllBytes(observation));
        observationJson.put("snapshot_hash", "sha256:" + "f".repeat(64));
        Files.writeString(observation, JSON.writeValueAsString(observationJson));

        BaselineImportException referenceError = assertThrows(
                BaselineImportException.class,
                () -> brokenReferenceDb.importer().importBaseline(
                        brokenBaseline, "baseline:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertEquals("INVALID_BASELINE", referenceError.code());
        assertNoBusinessRows(brokenReferenceDb.jdbc());
        assertFailedRun(brokenReferenceDb.jdbc(), "baseline:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "INVALID_BASELINE");

        TestDatabase missingFileDb = newDatabase();
        Path missingFileBaseline = copyBaseline(tempDir.resolve("missing"));
        Files.delete(firstJson(missingFileBaseline.resolve("datasets/public/kb/rate-quotes")));

        BaselineImportException missingFileError = assertThrows(
                BaselineImportException.class,
                () -> missingFileDb.importer().importBaseline(
                        missingFileBaseline, "baseline:abababababababababababababababab"));
        assertEquals("INVALID_BASELINE", missingFileError.code());
        assertNoBusinessRows(missingFileDb.jdbc());
        assertFailedRun(missingFileDb.jdbc(), "baseline:abababababababababababababababab", "INVALID_BASELINE");
    }

    @Test
    void changedChildRowIsNotHiddenWhenParentHashAndRowCountStillMatch() throws Exception {
        TestDatabase database = newDatabase();
        Path repositoryRoot = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        database.importer().importBaseline(
                repositoryRoot, "baseline:acacacacacacacacacacacacacacacac");

        try (Connection connection = DriverManager.getConnection(
                database.url(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select set_config('trust_agent.maintenance_ticket', 'TEST-CHILD-2', true)");
                statement.execute("select set_config('trust_agent.maintenance_reason', '하위 행 내용 손상 검증', true)");
                statement.execute("select set_config('trust_agent.maintenance_actor', 'integration-test', true)");
                statement.executeUpdate("""
                        update fact_evidence_locator set evidence_text = '손상된 근거'
                        where ctid = (select ctid from fact_evidence_locator limit 1)
                        """);
                connection.commit();
            }
        }

        BaselineImportException error = assertThrows(
                BaselineImportException.class,
                () -> database.importer().importBaseline(
                        repositoryRoot, "baseline:adadadadadadadadadadadadadadadad"));
        assertEquals("BASELINE_CONTENT_MISMATCH", error.code());
        assertEquals(30, count(database.jdbc(), "fact_evidence_locator"));
        assertFailedRun(database.jdbc(), "baseline:adadadadadadadadadadadadadadadad", "BASELINE_CONTENT_MISMATCH");
    }

    @Test
    void fingerprintDoesNotDependOnFileCreationOrder(@TempDir Path tempDir) throws Exception {
        TestDatabase firstDb = newDatabase();
        TestDatabase secondDb = newDatabase();
        Path firstCopy = copyBaseline(tempDir.resolve("first"));
        Path secondCopy = copyBaselineInReverseOrder(tempDir.resolve("second"));

        BaselineImportResult first = firstDb.importer().importBaseline(
                firstCopy, "baseline:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        BaselineImportResult second = secondDb.importer().importBaseline(
                secondCopy, "baseline:cccccccccccccccccccccccccccccccc");
        assertEquals(first.baselineFingerprint(), second.baselineFingerprint());
    }

    @Test
    void rawHtmlInsideBaselineBoundaryIsRejectedWithoutBusinessRows(@TempDir Path tempDir) throws Exception {
        TestDatabase database = newDatabase();
        Path baseline = copyBaseline(tempDir);
        Files.writeString(
                baseline.resolve("datasets/public/kb/manifests/2026-09-21/raw-source.html"),
                "<html>private source</html>");

        BaselineImportException error = assertThrows(
                BaselineImportException.class,
                () -> database.importer().importBaseline(
                        baseline, "baseline:dddddddddddddddddddddddddddddddd"));
        assertEquals("INVALID_BASELINE", error.code());
        assertNoBusinessRows(database.jdbc());
        assertFailedRun(database.jdbc(), "baseline:dddddddddddddddddddddddddddddddd", "INVALID_BASELINE");
    }

    @Test
    void extractionAttemptBeforeItsObservationIsRejected(@TempDir Path tempDir) throws Exception {
        TestDatabase database = newDatabase();
        Path baseline = copyBaseline(tempDir);
        Path attempt = firstJson(baseline.resolve("datasets/derived/public-kb/extraction-attempts"));
        ObjectNode attemptJson = (ObjectNode) JSON.readTree(Files.readAllBytes(attempt));
        attemptJson.put("attempted_at", "2000-01-01T00:00:00Z");
        Files.writeString(attempt, JSON.writeValueAsString(attemptJson));

        BaselineImportException error = assertThrows(
                BaselineImportException.class,
                () -> database.importer().importBaseline(
                        baseline, "baseline:dededededededededededededededede"));
        assertEquals("INVALID_BASELINE", error.code());
        assertNoBusinessRows(database.jdbc());
        assertFailedRun(database.jdbc(), "baseline:dededededededededededededededede", "INVALID_BASELINE");
    }

    private static TestDatabase newDatabase() throws SQLException {
        String name = "day4b_" + DATABASE_SEQUENCE.incrementAndGet();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create database " + name);
        }
        String url = databaseUrl(name);
        Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(true)
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcClient jdbc = JdbcClient.create(dataSource);
        BaselineImporter importer = new BaselineImporter(
                jdbc, JSON, new DataSourceTransactionManager(dataSource), CLOCK);
        return new TestDatabase(url, jdbc, importer);
    }

    private static String databaseUrl(String database) {
        String url = POSTGRES.getJdbcUrl();
        int query = url.indexOf('?');
        String suffix = query >= 0 ? url.substring(query) : "";
        String withoutQuery = query >= 0 ? url.substring(0, query) : url;
        return withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1) + database + suffix;
    }

    private static void performMaintenanceUpdate(
            String databaseUrl,
            String sql,
            String ticket,
            String reason) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                databaseUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select set_config('trust_agent.maintenance_ticket', '" + ticket + "', true)");
                statement.execute("select set_config('trust_agent.maintenance_reason', '" + reason + "', true)");
                statement.execute("select set_config('trust_agent.maintenance_actor', 'integration-test', true)");
                statement.executeUpdate(sql);
                connection.commit();
            }
        }
    }

    private static Map<String, Integer> expectedCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("public_product", 3);
        result.put("public_snapshot", 5);
        result.put("collection_attempt", 5);
        result.put("public_observation", 5);
        result.put("product_terms_version", 3);
        result.put("product_term_fact", 16);
        result.put("version_evidence", 5);
        result.put("fact_evidence_locator", 30);
        result.put("observed_rate_quote", 5);
        result.put("extraction_attempt", 5);
        result.put("change_detection_result", 5);
        result.put("change_detection_classification", 5);
        return result;
    }

    private static void assertDatabaseCounts(JdbcClient jdbc, Map<String, Integer> expected) {
        expected.forEach((table, count) -> assertEquals(count, count(jdbc, table), table));
    }

    private static void assertTopLevelIdsAndHashesMatch(JdbcClient jdbc, BaselineDataset dataset) {
        Map<BaselineDataset.RecordType, String[]> mappings = Map.of(
                BaselineDataset.RecordType.PRODUCT, new String[] {"public_product", "product_key", "product_key"},
                BaselineDataset.RecordType.SNAPSHOT, new String[] {"public_snapshot", "snapshot_hash", "snapshot_hash"},
                BaselineDataset.RecordType.COLLECTION_ATTEMPT, new String[] {"collection_attempt", "collection_attempt_id", "collection_attempt_id"},
                BaselineDataset.RecordType.OBSERVATION, new String[] {"public_observation", "observation_id", "observation_id"},
                BaselineDataset.RecordType.PRODUCT_TERMS_VERSION, new String[] {"product_terms_version", "product_terms_version_id", "product_terms_version_id"},
                BaselineDataset.RecordType.VERSION_EVIDENCE, new String[] {"version_evidence", "version_evidence_id", "version_evidence_id"},
                BaselineDataset.RecordType.RATE_QUOTE, new String[] {"observed_rate_quote", "rate_quote_id", "rate_quote_id"},
                BaselineDataset.RecordType.EXTRACTION_ATTEMPT, new String[] {"extraction_attempt", "extraction_attempt_id", "extraction_attempt_id"},
                BaselineDataset.RecordType.CHANGE_DETECTION_RESULT, new String[] {"change_detection_result", "change_detection_result_id", "change_detection_result_id"});
        mappings.forEach((type, mapping) -> {
            Set<String> databaseIds = Set.copyOf(jdbc.sql("select " + mapping[1] + " from " + mapping[0])
                    .query(String.class).list());
            Set<String> inputIds = dataset.records(type).stream()
                    .map(record -> record.json().get(mapping[2]).stringValue())
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(inputIds, databaseIds, mapping[0]);
            dataset.records(type).forEach(record -> assertEquals(
                    record.sourceRecordHash(),
                    jdbc.sql("select source_record_hash from " + mapping[0] + " where " + mapping[1] + " = :id")
                            .param("id", record.json().get(mapping[2]).stringValue())
                            .query(String.class)
                            .single(),
                    mapping[0]));
        });
    }

    private static void assertNoBusinessRows(JdbcClient jdbc) {
        expectedCounts().keySet().forEach(table -> assertEquals(0, count(jdbc, table), table));
    }

    private static int count(JdbcClient jdbc, String table) {
        return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
    }

    private static void assertFailedRun(JdbcClient jdbc, String runId, String errorCode) {
        Map<String, Object> row = jdbc.sql("""
                        select status, error_code, completed_at, imported_counts
                        from baseline_import_run where baseline_import_run_id = :runId
                        """).param("runId", runId).query().singleRow();
        assertEquals("FAILED", row.get("status"));
        assertEquals(errorCode, row.get("error_code"));
        assertEquals(null, row.get("imported_counts"));
    }

    private static Path copyBaseline(Path targetRoot) throws IOException {
        Path source = Path.of(System.getProperty("trustAgent.repositoryRoot"), "datasets");
        Path target = targetRoot.resolve("datasets");
        copyTree(source.resolve("public/kb"), target.resolve("public/kb"), false);
        copyTree(source.resolve("derived/public-kb"), target.resolve("derived/public-kb"), false);
        return targetRoot;
    }

    private static Path copyBaselineInReverseOrder(Path targetRoot) throws IOException {
        Path source = Path.of(System.getProperty("trustAgent.repositoryRoot"), "datasets");
        Path target = targetRoot.resolve("datasets");
        copyTree(source.resolve("public/kb"), target.resolve("public/kb"), true);
        copyTree(source.resolve("derived/public-kb"), target.resolve("derived/public-kb"), true);
        return targetRoot;
    }

    private static void copyTree(Path source, Path target, boolean reverse) throws IOException {
        Files.createDirectories(target);
        try (var paths = Files.walk(source)) {
            var ordered = paths.sorted(reverse ? java.util.Comparator.reverseOrder() : java.util.Comparator.naturalOrder()).toList();
            for (Path path : ordered) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private static Path firstJson(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().findFirst().orElseThrow();
        }
    }

    private record TestDatabase(String url, JdbcClient jdbc, BaselineImporter importer) {
    }
}
