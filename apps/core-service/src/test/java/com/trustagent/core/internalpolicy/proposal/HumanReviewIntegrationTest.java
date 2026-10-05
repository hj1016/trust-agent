package com.trustagent.core.internalpolicy.proposal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.internalpolicy.bootstrap.SyntheticInternalImporter;
import com.trustagent.core.publicproduct.baseline.BaselineImporter;
import com.trustagent.core.publicproduct.query.PublicEvidenceConfirmationPolicy;
import com.trustagent.core.publicproduct.query.PublicEvidencePolicyProperties;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateRepository;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * TASK-007 AC-01(승인 발행), AC-05/06(승인 거부), AC-07(수정), AC-08(반려), AC-09(중복과 동시), AC-10(원자성), AC-12(권한).
 * 각 테스트는 격리된 DB에서 baseline, 합성 공문, 예시 checklist를 적재하고 시작한다.
 */
class HumanReviewIntegrationTest {

    private static final String IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();
    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");
    private static final String PREPAYMENT = "SIN-PREPAYMENT-FEE";
    private static final String SELLER = "SIN-SELLER-CHECKLIST";
    private static final String REVIEWER = "SYN-REVIEWER-01";
    private static final String FIXTURE_SCHEDULE = "checklist-schedule:40343b3eaa1792400802a3e38cbb8c7d";
    private static final String FIXTURE_VERSION = "approved-checklist:2eaa5be6af97d149ee1d05a293c7e3a2";

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
    void createIsolatedDatabase() throws Exception {
        String databaseName = "review_" + DATABASE_SEQUENCE.incrementAndGet();
        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("create database " + databaseName);
        }
        String jdbcUrl = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)", "/" + databaseName);
        Flyway.configure().dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setMaximumPoolSize(4);
        jdbc = JdbcClient.create(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        repositoryRoot = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        new BaselineImporter(jdbc, mapper, manager).importBaseline(repositoryRoot, "baseline:" + "5".repeat(32));
        new SyntheticInternalImporter(jdbc, mapper, manager, "Asia/Seoul")
                .importBaseline(repositoryRoot, "synthetic-import:" + "5".repeat(32));
        new FixtureApprovedChecklistLoader(jdbc, mapper, manager, clock)
                .load(repositoryRoot, "checklist-fixture-run:" + "5".repeat(32));
        generation = new ProposalGenerationService(jdbc, mapper, manager, clock);
    }

    @AfterEach
    void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ---- AC-01: PASS 변경안 승인은 결정, HUMAN_REVIEW checklist, 일정 revision을 한 번에 발행한다 ----

    @Test
    void approvingPassProposalIssuesChecklistAndScheduleRevision() {
        String proposalId = generatePrepayment();
        var validation = validation(Duration.ofHours(24)).validate(new ProposalValidationService.Request(proposalId, null, "proposal-validator-v1"));
        assertEquals(ProposalValidator.Status.PASS, validation.status());

        var result = review(clock).decide(new HumanReviewService.Request(
                proposalId, validation.validationResultId(), HumanReviewService.Decision.APPROVE, REVIEWER, null, List.of(),
                "review-run:" + "a".repeat(32)));

        assertEquals("review-decision:" + "a".repeat(32), result.decisionId());
        assertEquals("APPROVE | SYN-REVIEWER-01 | " + proposalId + " | " + validation.validationResultId() + " | 2026-10-05T03:00:00Z",
                single("select decision || ' | ' || reviewer_id || ' | ' || proposal_id || ' | ' || validation_result_id || ' | ' || to_char(decided_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') from human_review_decision"));
        assertEquals(single("select after_hash from checklist_change_proposal where proposal_id = '" + proposalId + "'"),
                single("select proposal_hash from human_review_decision"));

        // 승인 checklist: HUMAN_REVIEW 출처, 대상 공문 v2, 항목 3개(수정 반영, 기준 유지, 추가)
        assertEquals("HUMAN_REVIEW | SIN-PREPAYMENT-FEE-V2",
                single("select origin || ' | ' || notice_id from approved_checklist_version where approved_checklist_version_id = '" + result.approvedChecklistVersionId() + "'"));
        assertEquals(List.of(
                        "0 | CHECK_PREPAYMENT_FEE_RATE | 0.8",
                        "1 | CHECK_NOTICE_SOURCE | -",
                        "2 | CHECK_CUSTOMER_CONTRACT_DATE | true"),
                jdbc.sql("select item_order || ' | ' || rule_key || ' | ' || coalesce(structured_change->>'after_value', '-') from approved_checklist_item where approved_checklist_version_id = :id order by item_order")
                        .param("id", result.approvedChecklistVersionId()).query(String.class).list());
        assertEquals(3, count("approved_checklist_item where approved_checklist_version_id = '" + result.approvedChecklistVersionId() + "' and source_rule_version_id is not null"));

        // 일정 revision: fixture 일정을 잇고 구간 2개
        assertEquals(FIXTURE_SCHEDULE, single("select supersedes_schedule_revision_id from approved_checklist_schedule_revision where schedule_revision_id = '" + result.scheduleRevisionId() + "'"));
        assertEquals(List.of(
                        "0 | " + FIXTURE_VERSION + " | 2026-09-15 | 2026-10-01",
                        "1 | " + result.approvedChecklistVersionId() + " | 2026-10-01 | -"),
                jdbc.sql("select entry_order || ' | ' || approved_checklist_version_id || ' | ' || effective_from || ' | ' || coalesce(effective_to::text, '-') from approved_checklist_schedule_entry where schedule_revision_id = :id order by entry_order")
                        .param("id", result.scheduleRevisionId()).query(String.class).list());
        assertEquals("SUCCEEDED | " + result.decisionId(), single("select status || ' | ' || decision_id from human_review_run"));
        // 변경안과 검증 결과는 그대로
        assertEquals(1, count("checklist_change_proposal"));
        assertEquals(1, count("automated_validation_result"));
    }

    // ---- AC-05, AC-06: 승인 거부 ----

    @Test
    void approvalIsRefusedForFailedMissingStaleMismatchedOrUnexplainedWarnValidation() {
        String seller = generateSeller();
        validation(Duration.ofHours(24)).validate(new ProposalValidationService.Request(seller, null, "proposal-validator-v1"));
        assertRefused("VALIDATION_FAILED", seller, null, HumanReviewService.Decision.APPROVE, null, clock);

        String prepayment = generatePrepayment();
        assertRefused("VALIDATION_MISSING", prepayment, null, HumanReviewService.Decision.APPROVE, null, clock);

        var pass = validation(Duration.ofHours(24)).validate(new ProposalValidationService.Request(prepayment, null, "proposal-validator-v1"));
        assertRefused("VALIDATION_STALE", prepayment, pass.validationResultId(), HumanReviewService.Decision.APPROVE, null,
                Clock.fixed(NOW.plus(Duration.ofHours(24)).plusSeconds(1), ZoneOffset.UTC));
        assertRefused("VALIDATION_RESULT_MISMATCH", prepayment, "validation:" + "9".repeat(32), HumanReviewService.Decision.APPROVE, null, clock);

        // 검증 뒤 내용이 바뀐 것처럼 다른 해시의 최신 결과를 넣으면 해시 불일치 (결정 시각은 그 결과보다 뒤인 03:00:10)
        Clock later = Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC);
        insertResult("validation:" + "7".repeat(32), prepayment, "sha256:" + "7".repeat(64), "PASS", "2026-10-05T03:00:01Z");
        assertRefused("PROPOSAL_HASH_MISMATCH", prepayment, null, HumanReviewService.Decision.APPROVE, null, later);

        // WARN 결과(사유 필수)
        String afterHash = single("select after_hash from checklist_change_proposal where proposal_id = '" + prepayment + "'");
        insertResult("validation:" + "8".repeat(32), prepayment, afterHash, "WARN", "2026-10-05T03:00:02Z");
        assertRefused("REASON_REQUIRED", prepayment, null, HumanReviewService.Decision.APPROVE, null, later);
        var approved = review(later).decide(new HumanReviewService.Request(
                prepayment, null, HumanReviewService.Decision.APPROVE, REVIEWER, "조건 누락은 상담 안내문으로 보완", List.of(), null));
        assertNotNull(approved.approvedChecklistVersionId());

        assertEquals(1, count("human_review_decision"));
        assertEquals(1, count("approved_checklist_version where origin = 'HUMAN_REVIEW'"));
        assertEquals(List.of("PROPOSAL_HASH_MISMATCH", "REASON_REQUIRED", "VALIDATION_FAILED", "VALIDATION_MISSING",
                        "VALIDATION_RESULT_MISMATCH", "VALIDATION_STALE"),
                jdbc.sql("select error_code from human_review_run where status = 'FAILED' order by error_code").query(String.class).list());
    }

    // ---- AC-07: 수정은 새 revision만 만들고 재검증 뒤에야 승인할 수 있다 ----

    @Test
    void modifyCreatesRevisionThatNeedsRevalidationBeforeApproval() {
        String proposalId = generatePrepayment();
        var pass = validation(Duration.ofHours(24)).validate(new ProposalValidationService.Request(proposalId, null, "proposal-validator-v1"));
        List<ChecklistItemContent> noticeRules = new ProposalRepository(jdbc, mapper)
                .findRules(single("select extraction_attempt_id from policy_extraction_attempt where notice_id = 'SIN-PREPAYMENT-FEE-V2' and status = 'SUCCEEDED'"));
        List<ChecklistItemContent> edited = noticeRules.stream()
                .map(rule -> rule.ruleKey().equals("CHECK_NOTICE_SOURCE")
                        ? new ChecklistItemContent(rule.ruleKey(), rule.instruction() + " (검수자 보완 문구)", rule.evidenceRequired(), rule.structuredChange())
                        : rule)
                .toList();

        assertRefused("REASON_REQUIRED", proposalId, null, HumanReviewService.Decision.MODIFY, null, clock);
        var modified = review(clock).decide(new HumanReviewService.Request(
                proposalId, pass.validationResultId(), HumanReviewService.Decision.MODIFY, REVIEWER, "안내 문구 보완", edited, null));

        String revision = modified.revisionProposalId();
        assertEquals("MODIFY | " + revision, single("select decision || ' | ' || revision_proposal_id from human_review_decision"));
        assertEquals(proposalId + " | 안내 문구 보완 | human-revision-v1",
                single("select supersedes_proposal_id || ' | ' || revision_reason || ' | ' || generator_version from checklist_change_proposal where proposal_id = '" + revision + "'"));
        assertEquals(0, count("approved_checklist_version where origin = 'HUMAN_REVIEW'"));
        assertEquals(0, count("approved_checklist_schedule_revision where supersedes_schedule_revision_id is not null"));

        // 원래 변경안은 이미 결정됐고, 새 revision은 검증 전이라 승인 불가
        assertRefused("PROPOSAL_ALREADY_DECIDED", proposalId, null, HumanReviewService.Decision.APPROVE, null, clock);
        assertRefused("VALIDATION_MISSING", revision, null, HumanReviewService.Decision.APPROVE, null, clock);

        // TASK-006 V-01은 공문 규칙과 다른 문구를 FAIL로 잡으므로 이 revision은 승인할 수 없다(설계 상 한계, Task 문서 참조).
        var revalidated = validation(Duration.ofHours(24)).validate(new ProposalValidationService.Request(revision, null, "proposal-validator-v1"));
        assertEquals(ProposalValidator.Status.FAIL, revalidated.status());
        assertRefused("VALIDATION_FAILED", revision, null, HumanReviewService.Decision.APPROVE, null, clock);

        // 공문 규칙과 같은 내용으로 다시 수정하면 PASS가 나오고 승인할 수 있다.
        var again = review(clock).decide(new HumanReviewService.Request(
                revision, null, HumanReviewService.Decision.MODIFY, REVIEWER, "공문 문구로 원복", noticeRules, null));
        var finalPass = validation(Duration.ofHours(24)).validate(new ProposalValidationService.Request(again.revisionProposalId(), null, "proposal-validator-v1"));
        assertEquals(ProposalValidator.Status.PASS, finalPass.status());
        var approved = review(clock).decide(new HumanReviewService.Request(
                again.revisionProposalId(), finalPass.validationResultId(), HumanReviewService.Decision.APPROVE, REVIEWER, null, List.of(), null));
        assertNotNull(approved.scheduleRevisionId());
        assertEquals(3, count("human_review_decision"));
        assertEquals(3, count("checklist_change_proposal"));
    }

    // ---- AC-08: 반려는 결정만 남기고 이후 승인·수정을 막는다 ----

    @Test
    void rejectLeavesOnlyTheDecisionAndBlocksFurtherDecisions() {
        String proposalId = generatePrepayment();
        validation(Duration.ofHours(24)).validate(new ProposalValidationService.Request(proposalId, null, "proposal-validator-v1"));
        assertRefused("REASON_REQUIRED", proposalId, null, HumanReviewService.Decision.REJECT, null, clock);

        var rejected = review(clock).decide(new HumanReviewService.Request(
                proposalId, null, HumanReviewService.Decision.REJECT, REVIEWER, "공문 원문 재확인 필요", List.of(), null));

        assertEquals("REJECT | 공문 원문 재확인 필요", single("select decision || ' | ' || reason from human_review_decision where decision_id = '" + rejected.decisionId() + "'"));
        assertEquals(0, count("approved_checklist_version where origin = 'HUMAN_REVIEW'"));
        assertEquals(1, count("checklist_change_proposal"));
        assertRefused("PROPOSAL_REJECTED", proposalId, null, HumanReviewService.Decision.APPROVE, null, clock);
        assertRefused("PROPOSAL_REJECTED", proposalId, null, HumanReviewService.Decision.MODIFY, "다시 수정", clock);
        assertEquals(1, count("human_review_decision"));
    }

    // ---- AC-09: 같은 변경안 결정 2번, 같은 일정 leaf 동시 대체는 하나만 성공 ----

    @Test
    void concurrentApprovalsOfTheSameProposalLeaveExactlyOneDecision() throws Exception {
        String proposalId = generatePrepayment();
        var pass = validation(Duration.ofHours(24)).validate(new ProposalValidationService.Request(proposalId, null, "proposal-validator-v1"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> outcomes = List.of(executor.submit(() -> attempt(proposalId, pass.validationResultId(), start)),
                    executor.submit(() -> attempt(proposalId, pass.validationResultId(), start)));
            start.countDown();
            List<String> codes = List.of(outcomes.get(0).get(), outcomes.get(1).get());
            assertEquals(1, codes.stream().filter("OK"::equals).count(), codes.toString());
            assertTrue(codes.stream().anyMatch(code -> code.equals("PROPOSAL_ALREADY_DECIDED") || code.equals("HUMAN_APPROVAL_EXISTS")), codes.toString());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("human_review_decision"));
        assertEquals(1, count("approved_checklist_schedule_revision where supersedes_schedule_revision_id = '" + FIXTURE_SCHEDULE + "'"));
        // DB 제약: 같은 변경안의 두 번째 결정과 같은 leaf를 잇는 두 번째 일정 revision은 23505
        assertEquals("23505", sqlState(() -> jdbc.sql("""
                insert into human_review_decision (decision_id, dataset_class, proposal_id, validation_result_id, proposal_hash, decision, reviewer_id, reason, decided_at)
                values ('review-decision:%s','SYNTHETIC_WORK',:proposal,null,'sha256:%s','REJECT','SYN-REVIEWER-02','중복','2026-10-05T03:00:00Z')
                """.formatted("e".repeat(32), "e".repeat(64))).param("proposal", proposalId).update()));
        assertEquals("23505", sqlState(() -> jdbc.sql("""
                insert into approved_checklist_schedule_revision values ('checklist-schedule:%s','SYNTHETIC_INTERNAL','SIN-PREPAYMENT-FEE','%s','2026-10-05T03:00:00Z','sha256:%s')
                """.formatted("e".repeat(32), FIXTURE_SCHEDULE, "e".repeat(64))).update()));
    }

    // ---- AC-10: 승인 트랜잭션 중간 실패 시 아무것도 남지 않는다 ----

    @Test
    void failureInsideApprovalTransactionLeavesNoPartialRows() {
        String proposalId = generatePrepayment();
        var pass = validation(Duration.ofHours(24)).validate(new ProposalValidationService.Request(proposalId, null, "proposal-validator-v1"));
        // 두 번째 clock 호출(실행 기록의 완료 시각, checklist·일정·결정 저장 뒤)에서 실패를 유도한다.
        Clock failingClock = new Clock() {
            private final AtomicInteger calls = new AtomicInteger();
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() {
                if (calls.incrementAndGet() == 2) throw new IllegalStateException("유도한 실패");
                return NOW;
            }
        };

        var exception = assertThrows(HumanReviewException.class, () -> review(failingClock).decide(new HumanReviewService.Request(
                proposalId, pass.validationResultId(), HumanReviewService.Decision.APPROVE, REVIEWER, null, List.of(), null)));

        assertEquals("REVIEW_WRITE_FAILED", exception.code());
        assertEquals(0, count("human_review_decision"));
        assertEquals(0, count("approved_checklist_version where origin = 'HUMAN_REVIEW'"));
        assertEquals(0, count("approved_checklist_item item join approved_checklist_version v on v.approved_checklist_version_id = item.approved_checklist_version_id where v.origin = 'HUMAN_REVIEW'"));
        assertEquals(0, count("approved_checklist_schedule_revision where supersedes_schedule_revision_id is not null"));
        assertEquals("FAILED | REVIEW_WRITE_FAILED", single("select status || ' | ' || error_code from human_review_run"));
    }

    // ---- AC-12: append-only와 역할 권한 ----

    @Test
    void decisionTablesAreAppendOnlyAndImportersCannotWriteThem() throws Exception {
        String proposalId = generatePrepayment();
        validation(Duration.ofHours(24)).validate(new ProposalValidationService.Request(proposalId, null, "proposal-validator-v1"));
        review(clock).decide(new HumanReviewService.Request(proposalId, null, HumanReviewService.Decision.REJECT, REVIEWER, "테스트", List.of(), null));
        assertEquals("42501", sqlState(() -> jdbc.sql("update human_review_decision set decision = 'APPROVE'").update()));
        assertEquals("42501", sqlState(() -> jdbc.sql("delete from human_review_run").update()));
        assertEquals("false | false | true | true | false",
                single("""
                        select has_table_privilege('trust_agent_synthetic_importer','human_review_decision','INSERT')::text || ' | ' ||
                               has_table_privilege('trust_agent_importer','human_review_decision','INSERT')::text || ' | ' ||
                               has_table_privilege('trust_agent_runtime','human_review_decision','INSERT')::text || ' | ' ||
                               has_table_privilege('trust_agent_runtime','human_review_run','INSERT')::text || ' | ' ||
                               has_table_privilege('trust_agent_runtime','human_review_decision','UPDATE')::text
                        """));
    }

    // ---- helpers ----

    private String attempt(String proposalId, String validationId, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            review(clock).decide(new HumanReviewService.Request(proposalId, validationId, HumanReviewService.Decision.APPROVE, REVIEWER, null, List.of(), null));
            return "OK";
        } catch (HumanReviewException exception) {
            return exception.code();
        }
    }

    private void assertRefused(String code, String proposalId, String validationId, HumanReviewService.Decision decision, String reason, Clock at) {
        int decisions = count("human_review_decision");
        var exception = assertThrows(HumanReviewException.class, () -> review(at).decide(new HumanReviewService.Request(
                proposalId, validationId, decision, REVIEWER, reason, List.of(), null)));
        assertEquals(code, exception.code());
        assertEquals(decisions, count("human_review_decision"));
        assertEquals("FAILED", single("select status from human_review_run where error_code = '" + code + "' order by started_at desc limit 1"));
    }

    private String generatePrepayment() {
        return generation.generate(new ProposalGenerationService.Request(PREPAYMENT, "SIN-PREPAYMENT-FEE-V2", null, "proposal-generator-v1")).proposalId();
    }

    private String generateSeller() {
        return generation.generate(new ProposalGenerationService.Request(SELLER, "SIN-SELLER-CHECKLIST-V2", null, "proposal-generator-v1")).proposalId();
    }

    private HumanReviewService review(Clock at) {
        return new HumanReviewService(jdbc, mapper, manager, at, Duration.ofHours(24));
    }

    private ProposalValidationService validation(Duration maxConfirmationAge) {
        return new ProposalValidationService(jdbc, mapper, manager, clock, new PublicProductObservedStateService(
                new PublicProductObservedStateRepository(jdbc, mapper), new PublicEvidenceConfirmationPolicy(),
                new PublicEvidencePolicyProperties("public-evidence-confirmation-v1", maxConfirmationAge), clock));
    }

    private void insertResult(String id, String proposalId, String hash, String status, String validatedAt) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        insert into automated_validation_result (validation_result_id, dataset_class, proposal_id, proposal_hash, validator_version, status, validated_at)
                        values ('%s','DERIVED','%s','%s','proposal-validator-v1','%s','%s')
                        """.formatted(id, proposalId, hash, status, validatedAt));
                if ("WARN".equals(status)) {
                    statement.execute("""
                            insert into automated_validation_issue (validation_result_id, result_status, issue_order, severity, code, rule_key, message)
                            values ('%s','WARN',0,'WARN','MISSING_CONDITIONS',null,'테스트 WARN')
                            """.formatted(id));
                }
            }
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
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
