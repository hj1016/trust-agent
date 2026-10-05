package com.trustagent.core.internalpolicy.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

class SyntheticInternalImporterIntegrationTest {

    private static final String IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final String HASH =
            "sha256:def6b5b670fc0adf36909ffd1ce47bd416a67e213f7127b5058c44681fe7e44b";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    private HikariDataSource dataSource;
    private SyntheticInternalImporter importer;
    private Path repositoryRoot;

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void createIsolatedDatabase() throws Exception {
        String databaseName = "synthetic_import_" + DATABASE_SEQUENCE.incrementAndGet();
        try (Connection connection = POSTGRES.createConnection("");
                Statement statement = connection.createStatement()) {
            statement.execute("create database " + databaseName);
        }
        String jdbcUrl = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)", "/" + databaseName);
        Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("alter database " + databaseName + " set timezone='America/New_York'");
            statement.execute("""
                    insert into public_product values (
                      'kb-seller-loan','PUBLIC_KB',false,'KB 셀러론','KB셀러론',
                      'https://obank.kbstar.com/example','%s')
                    """.formatted(HASH));
            statement.execute("""
                    insert into public_snapshot values (
                      '%s','PUBLIC_KB',false,'kb-seller-loan','https://obank.kbstar.com/example',
                      'LOCAL_PRIVATE','public-kb/sha256/%s.html','text/html',1,'%s')
                    """.formatted(HASH, HASH.substring(7), HASH));
        }
        importer = new SyntheticInternalImporter(
                JdbcClient.create(dataSource),
                new ObjectMapper(),
                new DataSourceTransactionManager(dataSource),
                "Asia/Seoul");
        repositoryRoot = Path.of(System.getProperty("trustAgent.repositoryRoot"));
    }

    @AfterEach
    void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void importsAllRecordsAndUsesConfiguredBusinessTimezone() throws Exception {
        SyntheticInternalImportResult result = importBaseline("11");

        assertEquals(4, result.counts().get("internal_notice_version"));
        assertEquals("2026-09-11", scalar("""
                select received_business_date::text from internal_notice_receipt
                where receipt_id='notice-receipt:11111111111111111111111111111111'
                """));
    }

    @Test
    void sameFingerprintIsIdempotent() throws Exception {
        importBaseline("11");
        importBaseline("22");

        assertEquals(4, countNotices());
    }

    @Test
    void fingerprintDoesNotDependOnCheckoutPath() throws Exception {
        SyntheticInternalImportResult original = importBaseline("11");
        Path relocated = copyDataset(repositoryRoot);

        SyntheticInternalImportResult replay = importer.importBaseline(relocated, runId("22"));

        assertEquals(original.fingerprint(), replay.fingerprint());
    }

    @Test
    void differentFingerprintIsRejectedAndFailureIsAudited() throws Exception {
        importBaseline("11");
        Path changed = copyDataset(repositoryRoot);
        Path v2 = changed.resolve("datasets/synthetic/internal/notices/seller-loan-checklist-v2.json");
        Files.writeString(v2, Files.readString(v2).replace("안내 v2", "안내 변경 v2"));

        SyntheticInternalImportException error = assertThrows(
                SyntheticInternalImportException.class,
                () -> importer.importBaseline(changed, runId("22")));

        assertEquals("BASELINE_MISMATCH", error.code());
        assertEquals("FAILED", scalar("""
                select status from synthetic_internal_import_run
                where import_run_id='synthetic-import:22222222222222222222222222222222'
                """));
        assertEquals(4, countNotices());
    }

    @Test
    void changedChildRowIsNotHiddenByParentHashAndCounts() throws Exception {
        importBaseline("11");
        maintenanceUpdate("""
                update internal_policy_rule_evidence set evidence_text='변조'
                where extraction_attempt_id='policy-extract:11111111111111111111111111111111'
                  and rule_order=0
                """);

        SyntheticInternalImportException error = assertThrows(
                SyntheticInternalImportException.class,
                () -> importer.importBaseline(repositoryRoot, runId("22")));

        assertEquals("BASELINE_CONTENT_MISMATCH", error.code());
    }

    @Test
    void changedSourceHashIsReportedAsSourceRecordConflict() throws Exception {
        importBaseline("11");
        maintenanceUpdate("""
                update internal_notice_version
                set source_record_hash='sha256:%s'
                where notice_id='SIN-SELLER-CHECKLIST-V1'
                """.formatted("c".repeat(64)));

        SyntheticInternalImportException error = assertThrows(
                SyntheticInternalImportException.class,
                () -> importer.importBaseline(repositoryRoot, runId("22")));

        assertEquals("SOURCE_RECORD_CONFLICT", error.code());
    }

    @Test
    void runtimeRowsAreReportedSeparatelyFromMissingBaselineRows() throws Exception {
        importBaseline("11");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into internal_notice_receipt values (
                      'notice-receipt:99999999999999999999999999999999',
                      'SYNTHETIC_INTERNAL',true,'SIN-SELLER-CHECKLIST-V1',
                      '2026-09-12T00:00:00Z','2026-09-12','Asia/Seoul',
                      'internal-business-time-v1','sha256:%s')
                    """.formatted("d".repeat(64)));
        }

        SyntheticInternalImportException error = assertThrows(
                SyntheticInternalImportException.class,
                () -> importer.importBaseline(repositoryRoot, runId("22")));

        assertEquals("RUNTIME_DATA_PRESENT", error.code());
        assertTrue(error.getMessage().contains("internal_notice_receipt"));
    }

    @Test
    void sqlIdentifiersAreRestrictedToInternalAllowlists() {
        SyntheticInternalImporter.requireSourceIdentifier("internal_notice_version", "notice_id");
        assertThrows(
                IllegalArgumentException.class,
                () -> SyntheticInternalImporter.requireSourceIdentifier(
                        "internal_notice_version; drop table internal_notice_version", "notice_id"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SyntheticInternalImporter.requireSourceIdentifier(
                        "internal_notice_version", "title"));
    }

    private SyntheticInternalImportResult importBaseline(String repeatedPair) {
        return importer.importBaseline(repositoryRoot, runId(repeatedPair));
    }

    private static String runId(String repeatedPair) {
        return "synthetic-import:" + repeatedPair.repeat(16);
    }

    private void maintenanceUpdate(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("select set_config('trust_agent.maintenance_ticket','TEST-1',false)");
            statement.execute("select set_config('trust_agent.maintenance_reason','충돌 검증',false)");
            statement.execute("select set_config('trust_agent.maintenance_actor','synthetic-tester',false)");
            statement.execute(sql);
        }
    }

    private static Path copyDataset(Path root) throws IOException {
        Path target = Files.createTempDirectory("trust-agent-synthetic-");
        for (String relative : new String[] {
            "datasets/synthetic/internal/notices",
            "datasets/synthetic/internal/receipts",
            "datasets/derived/synthetic-internal/policy-extraction-attempts"
        }) {
            Path source = root.resolve(relative);
            Path destination = target.resolve(relative);
            Files.createDirectories(destination);
            try (var paths = Files.list(source)) {
                for (Path path : paths.toList()) {
                    Files.copy(path, destination.resolve(path.getFileName()));
                }
            }
        }
        return target;
    }

    private int countNotices() throws Exception {
        return Integer.parseInt(scalar("select count(*) from internal_notice_version"));
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
