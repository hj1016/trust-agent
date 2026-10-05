package com.trustagent.core.internalpolicy.proposal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.internalpolicy.bootstrap.SyntheticInternalImporter;
import com.trustagent.core.publicproduct.baseline.BaselineImporter;
import com.trustagent.core.publicproduct.query.PublicEvidenceConfirmationPolicy;
import com.trustagent.core.publicproduct.query.PublicEvidencePolicyProperties;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateRepository;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * TASK-006 AC-01(PASS), AC-07(공개 근거 교차 검증), AC-08(철회), AC-09(재검증), AC-10(기준 checklist 오래됨),
 * AC-11(원자성과 DB 제약), AC-15(저장 내용). 각 테스트는 격리된 DB에서 시작한다.
 */
class ProposalValidationIntegrationTest {

    private static final String IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();
    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");
    private static final String GENERATOR = "proposal-generator-v1";
    private static final String VALIDATOR = "proposal-validator-v1";
    private static final String PREPAYMENT = "SIN-PREPAYMENT-FEE";
    private static final String SELLER = "SIN-SELLER-CHECKLIST";

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private DataSourceTransactionManager manager;
    private Path repositoryRoot;
    private ProposalGenerationService generation;

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void createIsolatedDatabaseWithBaselinesAndFixtures() throws Exception {
        String databaseName = "validation_" + DATABASE_SEQUENCE.incrementAndGet();
        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("create database " + databaseName);
        }
        String jdbcUrl = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)", "/" + databaseName);
        Flyway.configure().dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        repositoryRoot = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        new BaselineImporter(jdbc, mapper, manager).importBaseline(repositoryRoot, "baseline:" + "6".repeat(32));
        new SyntheticInternalImporter(jdbc, mapper, manager, "Asia/Seoul")
                .importBaseline(repositoryRoot, "synthetic-import:" + "6".repeat(32));
        new FixtureApprovedChecklistLoader(jdbc, mapper, manager, clock)
                .load(repositoryRoot, "checklist-fixture-run:" + "6".repeat(32));
        generation = new ProposalGenerationService(jdbc, mapper, manager, clock);
    }

    @AfterEach
    void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ---- AC-01, AC-15: 일치하는 변경안은 PASS이고 결과, issue, 실행 기록이 저장된다 ----

    @Test
    void consistentPrepaymentProposalPassesAndMatchesExpectedFixture() throws Exception {
        String proposalId = generatePrepayment();
        var result = service(Duration.ofHours(24)).validate(
                new ProposalValidationService.Request(proposalId, "validation-run:" + "a".repeat(32), VALIDATOR));

        assertEquals(ProposalValidator.Status.PASS, result.status());
        assertEquals("validation:" + "a".repeat(32), result.validationResultId());
        assertEquals("PASS", single("select status from automated_validation_result"));
        assertEquals("DERIVED", single("select dataset_class from automated_validation_result"));
        assertEquals(single("select after_hash from checklist_change_proposal"),
                single("select proposal_hash from automated_validation_result"));
        assertEquals("2026-10-05T03:00:00Z", single("select to_char(validated_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') from automated_validation_result"));
        assertEquals("SUCCEEDED", single("select status from validation_run"));
        assertEquals(result.validationResultId(), single("select validation_result_id from validation_run"));
        assertEquals(List.of("INFO:PUBLIC_CROSS_CHECK_NOT_APPLICABLE", "INFO:PUBLIC_CROSS_CHECK_NOT_APPLICABLE"), issues(result.validationResultId()));
        assertEquals(List.of("CHECK_CUSTOMER_CONTRACT_DATE", "CHECK_PREPAYMENT_FEE_RATE"),
                jdbc.sql("select rule_key from automated_validation_issue order by issue_order").query(String.class).list());

        // 정답표 fixture와 비교. Python 계약 테스트가 같은 파일을 schema로 검증한다.
        JsonNode expected = mapper.readTree(Files.readString(
                repositoryRoot.resolve("contracts/fixtures/prepayment-fee-v2-validation.expected.json")));
        assertEquals(expected.get("proposal_id").stringValue(), proposalId);
        assertEquals(expected.get("status").stringValue(), result.status().name());
        assertEquals(expected.get("validator_version").stringValue(), single("select validator_version from automated_validation_result"));
        assertEquals(expected.get("issues").size(), result.issueCount());
        for (JsonNode issue : expected.get("issues")) {
            String stored = single("select severity || ':' || code || ':' || coalesce(rule_key, '-') from automated_validation_issue where issue_order = " + issue.get("issue_order").intValue());
            assertEquals(issue.get("severity").stringValue() + ":" + issue.get("code").stringValue() + ":"
                    + (issue.get("rule_key").isNull() ? "-" : issue.get("rule_key").stringValue()), stored);
        }
        assertEquals("[]", single("select public_evidence_refs::text from automated_validation_result"));
    }

    // ---- AC-07: 셀러론 공개 근거 교차 검증 ----

    @Test
    void sellerCrossCheckFailsWhenRequiredPublicEvidenceIsStaleAndPassesWhenConfirmed() {
        String proposalId = generateSeller();

        // 공개 관측(2026-09-21)은 24h 정책에서 오래됨 → 필수 근거 미확인 → FAIL
        var stale = service(Duration.ofHours(24)).validate(
                new ProposalValidationService.Request(proposalId, null, VALIDATOR));
        assertEquals(ProposalValidator.Status.FAIL, stale.status());
        assertEquals(List.of("INFO:PUBLIC_CROSS_CHECK_NOT_APPLICABLE", "FAIL:PUBLIC_EVIDENCE_UNCONFIRMED"), issues(stale.validationResultId()));
        assertEquals("CHECK_SETTLEMENT_EVIDENCE", single("select rule_key from automated_validation_issue where validation_result_id = '" + stale.validationResultId() + "' and issue_order = 0"));
        String refs = single("select public_evidence_refs::text from automated_validation_result where validation_result_id = '" + stale.validationResultId() + "'");
        assertTrue(refs.contains("\"product_key\": \"kb-seller-loan\""), refs);
        assertTrue(refs.contains("\"confirmation_allowed\": false"), refs);

        // 30일 정책에서는 확인 가능 → 공개 값 20억과 공문 기대값 일치 → PASS, 관측/약관/근거/fact ID 기록
        var confirmed = service(Duration.ofDays(30)).validate(
                new ProposalValidationService.Request(proposalId, null, VALIDATOR));
        assertEquals(ProposalValidator.Status.PASS, confirmed.status());
        assertEquals(List.of("INFO:PUBLIC_CROSS_CHECK_NOT_APPLICABLE", "INFO:PUBLIC_FACT_MATCH"), issues(confirmed.validationResultId()));
        String confirmedRefs = single("select public_evidence_refs::text from automated_validation_result where validation_result_id = '" + confirmed.validationResultId() + "'");
        for (String key : List.of("observation_id", "product_terms_version_id", "version_evidence_id", "fact_id")) {
            assertTrue(confirmedRefs.contains("\"" + key + "\""), key + " 없음: " + confirmedRefs);
        }
        assertTrue(confirmedRefs.contains("fact:kb-seller-loan:max_limit_corporate_krw"), confirmedRefs);
        assertEquals("2000000000", single("select details->>'public_value' from automated_validation_issue where validation_result_id = '" + confirmed.validationResultId() + "' and code = 'PUBLIC_FACT_MATCH'"));

        // AC-10: 뒤의 검증(다른 정책, 다른 결과)이 앞서 저장된 결과와 issue, 참조를 바꾸지 않는다.
        assertEquals("FAIL", single("select status from automated_validation_result where validation_result_id = '" + stale.validationResultId() + "'"));
        assertEquals(List.of("INFO:PUBLIC_CROSS_CHECK_NOT_APPLICABLE", "FAIL:PUBLIC_EVIDENCE_UNCONFIRMED"), issues(stale.validationResultId()));
        assertEquals(refs, single("select public_evidence_refs::text from automated_validation_result where validation_result_id = '" + stale.validationResultId() + "'"));
        assertEquals(2, count("automated_validation_result"));
    }

    @Test
    void sellerCrossCheckFailsWhenPublicFactDiffersFromNoticeExpectation() {
        String proposalId = generateSeller();
        // 공문이 공개 값과 다른 한도(10억)를 기대한다고 참조를 추가한다. 공개 값(20억)과 다르므로 FAIL.
        jdbc.sql("""
                insert into internal_notice_reference
                select notice_id, 1, dataset_class, product_key, snapshot_hash, fact_key, subject_type, value_type,
                       1000000000, unit, 'REQUIRED', source_record_hash
                from internal_notice_reference where notice_id = 'SIN-SELLER-CHECKLIST-V2' and reference_order = 0
                """).update();

        var result = service(Duration.ofDays(30)).validate(
                new ProposalValidationService.Request(proposalId, null, VALIDATOR));

        assertEquals(ProposalValidator.Status.FAIL, result.status());
        assertEquals(List.of("INFO:PUBLIC_CROSS_CHECK_NOT_APPLICABLE", "INFO:PUBLIC_FACT_MATCH", "FAIL:PUBLIC_FACT_MISMATCH"), issues(result.validationResultId()));
        // 내부 규칙을 자동 수정하지 않는다: 변경안과 승인 checklist는 그대로다.
        assertEquals(1, count("checklist_change_proposal"));
        assertEquals(2, count("approved_checklist_version"));
    }

    // ---- AC-08, AC-10: 철회된 공문과 오래된 기준 checklist ----

    @Test
    void withdrawnTargetAndSupersededProposalFailValidation() {
        String proposalId = generatePrepayment();
        jdbc.sql("""
                insert into internal_notice_lifecycle_event values
                ('notice-event:%s','SYNTHETIC_INTERNAL','SIN-PREPAYMENT-FEE-V2','WITHDRAWN','2026-10-04T00:00:00Z','철회 테스트','sha256:%s')
                """.formatted("5".repeat(32), "5".repeat(64))).update();
        var withdrawn = service(Duration.ofHours(24)).validate(new ProposalValidationService.Request(proposalId, null, VALIDATOR));
        assertEquals(ProposalValidator.Status.FAIL, withdrawn.status());
        assertTrue(issues(withdrawn.validationResultId()).contains("FAIL:TARGET_NOTICE_WITHDRAWN"));

        String revision = "checklist-proposal:sha256:" + "b".repeat(64);
        jdbc.sql("""
                insert into checklist_change_proposal values
                (:id,'DERIVED',:family,:base,'SIN-PREPAYMENT-FEE-V2','proposal-generator-v1',:supersedes,'재생성','sha256:%s','sha256:%s',0,'2026-10-05T02:00:00Z')
                """.formatted("d".repeat(64), "e".repeat(64)))
                .param("id", revision).param("family", PREPAYMENT)
                .param("base", single("select base_checklist_version_id from checklist_change_proposal where proposal_id = '" + proposalId + "'"))
                .param("supersedes", proposalId)
                .update();
        var superseded = service(Duration.ofHours(24)).validate(new ProposalValidationService.Request(proposalId, null, VALIDATOR));
        assertTrue(issues(superseded.validationResultId()).contains("FAIL:BASE_CHECKLIST_STALE"));
    }

    // ---- AC-09: 재검증은 새 결과를 추가하고 이전 결과를 바꾸지 않는다 ----

    @Test
    void revalidationAppendsNewResultAndRunIdConflictIsRejected() {
        String proposalId = generatePrepayment();
        var service = service(Duration.ofHours(24));
        var first = service.validate(new ProposalValidationService.Request(proposalId, "validation-run:" + "1".repeat(32), VALIDATOR));
        var second = service.validate(new ProposalValidationService.Request(proposalId, null, VALIDATOR));

        assertNotEquals(first.validationResultId(), second.validationResultId());
        assertEquals(2, count("automated_validation_result"));
        assertEquals(2, count("validation_run"));
        assertEquals("PASS", single("select status from automated_validation_result where validation_result_id = '" + first.validationResultId() + "'"));

        var conflict = assertThrows(ProposalValidationException.class, () -> service.validate(
                new ProposalValidationService.Request(proposalId, "validation-run:" + "1".repeat(32), VALIDATOR)));
        assertEquals("RUN_ID_CONFLICT", conflict.code());
        assertEquals(2, count("validation_run"));

        var missing = assertThrows(ProposalValidationException.class, () -> service.validate(
                new ProposalValidationService.Request("checklist-proposal:sha256:" + "9".repeat(64), null, VALIDATOR)));
        assertEquals("PROPOSAL_NOT_FOUND", missing.code());
        assertEquals("FAILED", single("select status from validation_run where error_code = 'PROPOSAL_NOT_FOUND'"));
    }

    // ---- AC-11(a): issue 저장 중 실패하면 결과, issue, 성공 실행 기록이 모두 없다 ----

    @Test
    void failureWhileSavingIssuesRollsBackResultAndIssues() {
        String proposalId = generatePrepayment();
        ProposalValidator broken = new ProposalValidator(mapper) {
            @Override
            public Outcome validate(Input input) {
                // 두 번째 issue의 빈 message가 CHECK 제약에 걸려 저장이 중간에 실패한다.
                return new Outcome(Status.PASS, List.of(
                        new Issue(Severity.INFO, "PUBLIC_CROSS_CHECK_NOT_APPLICABLE", null, "첫 issue", mapper.createObjectNode()),
                        new Issue(Severity.INFO, "PUBLIC_CROSS_CHECK_NOT_APPLICABLE", null, "", mapper.createObjectNode())));
            }
        };
        var service = new ProposalValidationService(jdbc, mapper, manager, clock, publicProducts(Duration.ofHours(24)), broken);

        var exception = assertThrows(ProposalValidationException.class, () -> service.validate(
                new ProposalValidationService.Request(proposalId, "validation-run:" + "2".repeat(32), VALIDATOR)));

        assertEquals("VALIDATION_WRITE_FAILED", exception.code());
        assertEquals(0, count("automated_validation_result"));
        assertEquals(0, count("automated_validation_issue"));
        assertEquals(0, count("validation_run where status = 'SUCCEEDED'"));
        assertEquals("FAILED", single("select status from validation_run"));
        assertEquals("VALIDATION_WRITE_FAILED", single("select error_code from validation_run"));
    }

    @Test
    void inconsistentOutcomeIsRejectedBeforeAnythingIsSaved() {
        String proposalId = generatePrepayment();
        ProposalValidator broken = new ProposalValidator(mapper) {
            @Override
            public Outcome validate(Input input) {
                return new Outcome(Status.FAIL, List.of());
            }
        };
        var service = new ProposalValidationService(jdbc, mapper, manager, clock, publicProducts(Duration.ofHours(24)), broken);

        var exception = assertThrows(ProposalValidationException.class, () -> service.validate(
                new ProposalValidationService.Request(proposalId, null, VALIDATOR)));

        assertEquals("VALIDATION_INCONSISTENT", exception.code());
        assertEquals(0, count("automated_validation_result"));
        assertEquals("FAILED", single("select status from validation_run"));
    }

    // ---- AC-11(b)(c): DB가 직접 모순을 거부한다 ----

    @Test
    void databaseRejectsIssueSeverityThatContradictsResultStatus() {
        String proposalId = generatePrepayment();
        String hash = single("select after_hash from checklist_change_proposal");
        insertResult("validation:" + "7".repeat(32), proposalId, hash, "PASS");

        assertEquals("23514", sqlState(() -> insertIssue("validation:" + "7".repeat(32), "PASS", 0, "FAIL", "VALUE_MISMATCH")), "PASS 결과에 FAIL issue");
        assertEquals("23514", sqlState(() -> insertIssue("validation:" + "7".repeat(32), "PASS", 0, "WARN", "MISSING_CONDITIONS")), "PASS 결과에 WARN issue");
        // result_status를 거짓으로 적어도 복합 FK가 거부한다.
        assertEquals("23503", sqlState(() -> insertIssue("validation:" + "7".repeat(32), "FAIL", 0, "FAIL", "VALUE_MISMATCH")), "결과 상태와 다른 result_status");
        assertEquals(0, count("automated_validation_issue"));
    }

    @Test
    void databaseRejectsFailResultWithoutFailIssueAndWarnResultWithoutWarnIssueAtCommit() throws Exception {
        String proposalId = generatePrepayment();
        String hash = single("select after_hash from checklist_change_proposal");

        assertEquals("23514", commitState(connection -> {
            insertResult(connection, "validation:" + "8".repeat(32), proposalId, hash, "FAIL");
            insertIssue(connection, "validation:" + "8".repeat(32), "FAIL", 0, "WARN", "MISSING_CONDITIONS");
        }), "FAIL issue 없는 FAIL 결과");
        assertEquals("23514", commitState(connection -> {
            insertResult(connection, "validation:" + "9".repeat(32), proposalId, hash, "WARN");
            insertIssue(connection, "validation:" + "9".repeat(32), "WARN", 0, "INFO", "PUBLIC_CROSS_CHECK_NOT_APPLICABLE");
        }), "WARN issue 없는 WARN 결과");
        assertEquals("23514", commitState(connection ->
                insertResult(connection, "validation:" + "0".repeat(32), proposalId, hash, "FAIL")), "issue 없는 FAIL 결과");
        assertEquals(0, count("automated_validation_result"));

        // 같은 트랜잭션에 알맞은 issue가 있으면 commit된다.
        assertEquals(null, commitState(connection -> {
            insertResult(connection, "validation:" + "8".repeat(32), proposalId, hash, "FAIL");
            insertIssue(connection, "validation:" + "8".repeat(32), "FAIL", 0, "FAIL", "VALUE_MISMATCH");
        }));
        assertEquals(1, count("automated_validation_result"));
    }

    // ---- AC-11(d): 신규 테이블 append-only ----

    @Test
    void validationTablesAreAppendOnly() {
        String proposalId = generatePrepayment();
        var result = service(Duration.ofHours(24)).validate(new ProposalValidationService.Request(proposalId, null, VALIDATOR));
        assertEquals("42501", sqlState(() -> jdbc.sql("update automated_validation_result set status = 'FAIL'").update()));
        assertEquals("42501", sqlState(() -> jdbc.sql("delete from automated_validation_issue").update()));
        assertEquals("42501", sqlState(() -> jdbc.sql("delete from validation_run").update()));
        assertEquals("PASS", single("select status from automated_validation_result where validation_result_id = '" + result.validationResultId() + "'"));
    }

    // ---- helpers ----

    private String generatePrepayment() {
        return generation.generate(new ProposalGenerationService.Request(PREPAYMENT, "SIN-PREPAYMENT-FEE-V2", null, GENERATOR)).proposalId();
    }

    private String generateSeller() {
        return generation.generate(new ProposalGenerationService.Request(SELLER, "SIN-SELLER-CHECKLIST-V2", null, GENERATOR)).proposalId();
    }

    private ProposalValidationService service(Duration maxConfirmationAge) {
        return new ProposalValidationService(jdbc, mapper, manager, clock, publicProducts(maxConfirmationAge));
    }

    private PublicProductObservedStateService publicProducts(Duration maxConfirmationAge) {
        return new PublicProductObservedStateService(
                new PublicProductObservedStateRepository(jdbc, mapper),
                new PublicEvidenceConfirmationPolicy(),
                new PublicEvidencePolicyProperties("public-evidence-confirmation-v1", maxConfirmationAge),
                clock);
    }

    private List<String> issues(String resultId) {
        return jdbc.sql("select severity || ':' || code from automated_validation_issue where validation_result_id = :id order by issue_order")
                .param("id", resultId).query(String.class).list();
    }

    private void insertResult(String id, String proposalId, String hash, String status) {
        jdbc.sql("""
                insert into automated_validation_result (validation_result_id, dataset_class, proposal_id, proposal_hash, validator_version, status, validated_at)
                values (:id,'DERIVED',:proposal,:hash,'proposal-validator-v1',:status,'2026-10-05T03:00:00Z')
                """).param("id", id).param("proposal", proposalId).param("hash", hash).param("status", status).update();
    }

    private void insertIssue(String resultId, String resultStatus, int order, String severity, String code) {
        jdbc.sql("""
                insert into automated_validation_issue (validation_result_id, result_status, issue_order, severity, code, rule_key, message)
                values (:id,:status,:order,:severity,:code,null,'테스트')
                """).param("id", resultId).param("status", resultStatus).param("order", order).param("severity", severity).param("code", code).update();
    }

    private static void insertResult(Connection connection, String id, String proposalId, String hash, String status) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into automated_validation_result (validation_result_id, dataset_class, proposal_id, proposal_hash, validator_version, status, validated_at)
                    values ('%s','DERIVED','%s','%s','proposal-validator-v1','%s','2026-10-05T03:00:00Z')
                    """.formatted(id, proposalId, hash, status));
        }
    }

    private static void insertIssue(Connection connection, String resultId, String resultStatus, int order, String severity, String code) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into automated_validation_issue (validation_result_id, result_status, issue_order, severity, code, rule_key, message)
                    values ('%s','%s',%d,'%s','%s',null,'테스트')
                    """.formatted(resultId, resultStatus, order, severity, code));
        }
    }

    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    /** 한 트랜잭션에서 작업 후 commit한다. commit 시점 trigger가 거부하면 그 SQLSTATE, 성공하면 null. */
    private String commitState(SqlWork work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
                return null;
            } catch (SQLException exception) {
                connection.rollback();
                return exception.getSQLState();
            }
        }
    }

    private int count(String tableAndFilter) {
        return jdbc.sql("select count(*) from " + tableAndFilter).query(Integer.class).single();
    }

    private String single(String sql) {
        return jdbc.sql(sql).query(String.class).optional().orElse(null);
    }

    private static String sqlState(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException exception) {
            Throwable cause = exception;
            while (cause != null && !(cause instanceof SQLException)) {
                cause = cause.getCause();
            }
            return cause == null ? exception.getClass().getSimpleName() : ((SQLException) cause).getSQLState();
        }
    }
}
