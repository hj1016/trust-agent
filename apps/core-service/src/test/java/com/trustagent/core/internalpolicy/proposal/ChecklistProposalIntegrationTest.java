package com.trustagent.core.internalpolicy.proposal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.internalpolicy.bootstrap.SyntheticInternalImporter;
import com.trustagent.core.publicproduct.baseline.BaselineImporter;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
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
 * TASK-005 AC-01~07(변경안 생성), AC-11~16(fixture와 우회 금지). 각 테스트는 격리된 DB에서 시작한다.
 */
class ChecklistProposalIntegrationTest {

    private static final String IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();
    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");
    private static final String FAMILY = "SIN-PREPAYMENT-FEE";
    private static final String TARGET = "SIN-PREPAYMENT-FEE-V2";
    private static final String GENERATOR = "proposal-generator-v1";

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private Path repositoryRoot;
    private FixtureApprovedChecklistLoader loader;
    private ProposalGenerationService service;

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void createIsolatedDatabaseWithBaselines() throws Exception {
        String databaseName = "proposal_" + DATABASE_SEQUENCE.incrementAndGet();
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
        var manager = new DataSourceTransactionManager(dataSource);
        repositoryRoot = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        new BaselineImporter(jdbc, mapper, manager).importBaseline(repositoryRoot, "baseline:" + "7".repeat(32));
        new SyntheticInternalImporter(jdbc, mapper, manager, "Asia/Seoul")
                .importBaseline(repositoryRoot, "synthetic-import:" + "7".repeat(32));
        loader = new FixtureApprovedChecklistLoader(jdbc, mapper, manager, clock);
        service = new ProposalGenerationService(jdbc, mapper, manager, clock);
    }

    @AfterEach
    void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ---- fixture (AC-11~16) ----

    @Test
    void fixtureLoadIsIdempotentAndRecordsEveryRun() {
        var first = loader.load(repositoryRoot, "checklist-fixture-run:" + "a".repeat(32));
        assertEquals(1, first.counts().get("approved_checklist_version"));
        assertEquals(2, first.counts().get("approved_checklist_item"));
        assertEquals(1, first.counts().get("approved_checklist_schedule_revision"));
        assertEquals(1, first.counts().get("approved_checklist_schedule_entry"));
        assertEquals("FIXTURE", single("select origin from approved_checklist_version"));

        var second = loader.load(repositoryRoot, "checklist-fixture-run:" + "b".repeat(32));
        assertEquals(0, second.counts().get("approved_checklist_version"));
        assertEquals(1, count("approved_checklist_version"));
        assertEquals(2, count("approved_checklist_item"));
        assertEquals(1, count("approved_checklist_schedule_revision"));
        assertEquals(2, count("approved_checklist_fixture_run"));
        assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void fixtureWithSameIdButDifferentContentIsRejectedAndNothingChanges() throws Exception {
        loader.load(repositoryRoot, null);
        Path modified = copyFixtures(repositoryRoot, content -> content.replace("\"instruction\": \"기업여신 상담 시 중도상환수수료율 1.2퍼센트", "\"instruction\": \"바뀐 문구 1.2퍼센트"));

        var exception = assertThrows(FixtureApprovedChecklistException.class, () -> loader.load(modified, "checklist-fixture-run:" + "c".repeat(32)));

        assertEquals("FIXTURE_CONTENT_CONFLICT", exception.code());
        assertEquals(1, count("approved_checklist_version"));
        assertEquals(2, count("approved_checklist_item"));
        assertEquals("FAILED", single("select status from approved_checklist_fixture_run where fixture_run_id = 'checklist-fixture-run:" + "c".repeat(32) + "'"));
    }

    @Test
    void fixtureIsRefusedWhenHumanReviewedChecklistExistsForTheFamily() {
        jdbc.sql("""
                insert into approved_checklist_version values
                ('approved-checklist:%s','SYNTHETIC_INTERNAL','SIN-PREPAYMENT-FEE','SIN-PREPAYMENT-FEE-V1','2026-09-14T00:00:00Z','sha256:%s','HUMAN_REVIEW')
                """.formatted("9".repeat(32), "9".repeat(64))).update();

        var exception = assertThrows(FixtureApprovedChecklistException.class, () -> loader.load(repositoryRoot, null));

        assertEquals("HUMAN_APPROVAL_EXISTS", exception.code());
        assertEquals(1, count("approved_checklist_version"));
        assertEquals(0, count("approved_checklist_item"));
    }

    @Test
    void checklistItemsEnforceFamilyMatchUniqueRuleKeysAndAppendOnly() throws Exception {
        loader.load(repositoryRoot, null);
        String versionId = single("select approved_checklist_version_id from approved_checklist_version");

        assertEquals("23503", sqlState(() -> jdbc.sql("""
                insert into approved_checklist_item values (:id,'SIN-SELLER-CHECKLIST',9,'CHECK_X','x',true,'null'::jsonb,null,'sha256:%s')
                """.formatted("1".repeat(64))).param("id", versionId).update()), "family 불일치는 복합 FK 위반");
        assertEquals("23505", sqlState(() -> jdbc.sql("""
                insert into approved_checklist_item values (:id,'SIN-PREPAYMENT-FEE',9,'CHECK_NOTICE_SOURCE','dup',true,'null'::jsonb,null,'sha256:%s')
                """.formatted("1".repeat(64))).param("id", versionId).update()), "같은 version 안 rule_key 중복");
        assertEquals("23505", sqlState(() -> jdbc.sql("""
                insert into approved_checklist_schedule_revision values ('checklist-schedule:%s','SYNTHETIC_INTERNAL','SIN-PREPAYMENT-FEE',null,'2026-09-14T00:00:00Z','sha256:%s')
                """.formatted("8".repeat(32), "8".repeat(64))).update()), "family별 두 번째 root schedule");
        assertEquals("42501", sqlState(() -> jdbc.sql("update approved_checklist_item set instruction = 'x'").update()), "append-only 가드");
        assertEquals("42501", sqlState(() -> jdbc.sql("delete from approved_checklist_version").update()), "append-only 가드");
    }

    @Test
    void importerAccountsCannotWriteApprovalOrProposalTablesButRuntimeCan() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        select has_table_privilege('trust_agent_synthetic_importer','approved_checklist_item','INSERT'),
                               has_table_privilege('trust_agent_importer','approved_checklist_item','INSERT'),
                               has_table_privilege('trust_agent_synthetic_importer','checklist_change_proposal','INSERT'),
                               has_table_privilege('trust_agent_runtime','approved_checklist_item','INSERT'),
                               has_table_privilege('trust_agent_runtime','checklist_change_proposal','INSERT'),
                               has_table_privilege('trust_agent_runtime','checklist_change_proposal','UPDATE'),
                               has_table_privilege('trust_agent_runtime','proposal_generation_run','INSERT')
                        """)) {
            assertTrue(result.next());
            assertFalse(result.getBoolean(1));
            assertFalse(result.getBoolean(2));
            assertFalse(result.getBoolean(3));
            assertTrue(result.getBoolean(4));
            assertTrue(result.getBoolean(5));
            assertFalse(result.getBoolean(6));
            assertTrue(result.getBoolean(7));
        }
    }

    // ---- proposal (AC-01~07) ----

    @Test
    void generatesTheExpectedPrepaymentFeeProposalFromFixtureBaseline() throws Exception {
        loader.load(repositoryRoot, null);
        JsonNode expected = mapper.readTree(Files.readString(repositoryRoot.resolve("contracts/fixtures/prepayment-fee-v2-proposal.expected.json")));

        var result = service.generate(new ProposalGenerationService.Request(FAMILY, TARGET, "proposal-run:" + "a".repeat(32), GENERATOR));

        assertTrue(result.created());
        assertEquals(expected.get("proposal_id").stringValue(), result.proposalId());
        assertEquals(2, result.itemCount());
        var stored = jdbc.sql("select * from checklist_change_proposal where proposal_id = :id").param("id", result.proposalId()).query().singleRow();
        assertEquals("DERIVED", stored.get("dataset_class"));
        assertEquals(expected.get("base_checklist_version_id").stringValue(), stored.get("base_checklist_version_id"));
        assertEquals(TARGET, stored.get("target_notice_id"));
        assertEquals(expected.get("before_hash").stringValue(), stored.get("before_hash"));
        assertEquals(expected.get("after_hash").stringValue(), stored.get("after_hash"));
        List<java.util.Map<String, Object>> items = jdbc.sql("""
                select item_order, rule_key, change_type, before_json::text as before_json, after_json::text as after_json, before_hash, after_hash
                from checklist_change_proposal_item where proposal_id = :id order by item_order
                """).param("id", result.proposalId()).query().listOfRows();
        assertEquals(2, items.size());
        for (int i = 0; i < items.size(); i++) {
            JsonNode expectedItem = expected.get("items").get(i);
            assertEquals(expectedItem.get("rule_key").stringValue(), items.get(i).get("rule_key"));
            assertEquals(expectedItem.get("change_type").stringValue(), items.get(i).get("change_type"));
            assertEquals(expectedItem.get("before_json"), readJson(items.get(i).get("before_json")));
            assertEquals(expectedItem.get("after_json"), readJson(items.get(i).get("after_json")));
        }
        JsonNode modify = readJson(items.get(1).get("after_json"));
        assertEquals("0.8", modify.get("structured_change").get("after_value").stringValue());
        assertEquals("1.2", readJson(items.get(1).get("before_json")).get("structured_change").get("after_value").stringValue());
        assertEquals("SUCCEEDED", single("select status from proposal_generation_run"));
    }

    @Test
    void sameInputIsIdempotentAndOnlyAddsARunRecord() {
        loader.load(repositoryRoot, null);
        var first = service.generate(new ProposalGenerationService.Request(FAMILY, TARGET, null, GENERATOR));
        var second = service.generate(new ProposalGenerationService.Request(FAMILY, TARGET, null, GENERATOR));

        assertEquals(first.proposalId(), second.proposalId());
        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(1, count("checklist_change_proposal"));
        assertEquals(2, count("checklist_change_proposal_item"));
        assertEquals(2, count("proposal_generation_run"));
    }

    @Test
    void familyWithoutApprovedChecklistFailsWithNoBaseChecklistAndAuditsTheRun() {
        var exception = assertThrows(ProposalGenerationException.class, () -> service.generate(
                new ProposalGenerationService.Request("SIN-SELLER-CHECKLIST", "SIN-SELLER-CHECKLIST-V2", "proposal-run:" + "b".repeat(32), GENERATOR)));

        assertEquals("NO_BASE_CHECKLIST", exception.code());
        assertEquals(0, count("checklist_change_proposal"));
        assertEquals("FAILED", single("select status from proposal_generation_run"));
        assertEquals("NO_BASE_CHECKLIST", single("select error_code from proposal_generation_run"));
    }

    @Test
    void unknownOrWithdrawnTargetIsNotVisible() {
        loader.load(repositoryRoot, null);
        var unknown = assertThrows(ProposalGenerationException.class, () -> service.generate(
                new ProposalGenerationService.Request(FAMILY, "SIN-PREPAYMENT-FEE-V9", null, GENERATOR)));
        assertEquals("TARGET_NOTICE_NOT_VISIBLE", unknown.code());

        jdbc.sql("""
                insert into internal_notice_lifecycle_event values
                ('notice-event:%s','SYNTHETIC_INTERNAL','SIN-PREPAYMENT-FEE-V2','WITHDRAWN','2026-09-30T00:00:00Z','철회 테스트','sha256:%s')
                """.formatted("5".repeat(32), "5".repeat(64))).update();
        var withdrawn = assertThrows(ProposalGenerationException.class, () -> service.generate(
                new ProposalGenerationService.Request(FAMILY, TARGET, null, GENERATOR)));
        assertEquals("TARGET_NOTICE_NOT_VISIBLE", withdrawn.code());
        assertEquals(0, count("checklist_change_proposal"));
    }

    @Test
    void proposalItemConstraintsRejectInconsistentChangeShapes() {
        loader.load(repositoryRoot, null);
        var result = service.generate(new ProposalGenerationService.Request(FAMILY, TARGET, null, GENERATOR));
        String json = "{\"rule_key\":\"CHECK_X\",\"instruction\":\"x\",\"evidence_required\":true,\"structured_change\":null}";
        String hash = "sha256:" + "1".repeat(64);

        assertEquals("23514", sqlState(() -> insertItem(result.proposalId(), 7, "ADD", json, json, hash, hash)), "ADD인데 before 있음");
        assertEquals("23514", sqlState(() -> insertItem(result.proposalId(), 7, "REMOVE", json, json, hash, hash)), "REMOVE인데 after 있음");
        assertEquals("23514", sqlState(() -> insertItem(result.proposalId(), 7, "MODIFY", null, json, null, hash)), "MODIFY인데 before 없음");
        assertEquals("23514", sqlState(() -> insertItem(result.proposalId(), 7, "RENAME", json, json, hash, hash)), "알 수 없는 change_type");
        assertEquals("42501", sqlState(() -> jdbc.sql("delete from checklist_change_proposal_item").update()), "append-only 가드");
    }

    @Test
    void supersedingTheSameProposalTwiceOrAcrossFamiliesIsRejected() {
        loader.load(repositoryRoot, null);
        var result = service.generate(new ProposalGenerationService.Request(FAMILY, TARGET, null, GENERATOR));
        String base = single("select base_checklist_version_id from checklist_change_proposal");

        insertRevision("checklist-proposal:sha256:" + "a".repeat(64), FAMILY, base, result.proposalId());
        assertEquals("23505", sqlState(() -> insertRevision("checklist-proposal:sha256:" + "b".repeat(64), FAMILY, base, result.proposalId())), "같은 proposal을 두 번 supersede");
        // 아직 대체되지 않은 revision 'a'를 다른 family로 대체하려 하면 unique가 아니라 (base checklist, family) 복합 FK가 거부한다.
        assertEquals("23503", sqlState(() -> insertRevision("checklist-proposal:sha256:" + "c".repeat(64), "SIN-SELLER-CHECKLIST", base, "checklist-proposal:sha256:" + "a".repeat(64))), "다른 family의 base checklist는 FK 위반");
    }

    // ---- helpers ----

    private void insertItem(String proposalId, int order, String type, String before, String after, String beforeHash, String afterHash) {
        jdbc.sql("""
                insert into checklist_change_proposal_item values
                (:id,:order,'CHECK_X',:type,cast(:before as jsonb),cast(:after as jsonb),:beforeHash,:afterHash)
                """)
                .param("id", proposalId).param("order", order).param("type", type)
                .param("before", before).param("after", after).param("beforeHash", beforeHash).param("afterHash", afterHash)
                .update();
    }

    private void insertRevision(String proposalId, String familyId, String baseVersionId, String supersedes) {
        jdbc.sql("""
                insert into checklist_change_proposal values
                (:id,'DERIVED',:family,:base,:target,'proposal-generator-v1',:supersedes,'수정 사유','sha256:%s','sha256:%s',0,'2026-10-05T03:00:00Z')
                """.formatted("d".repeat(64), "e".repeat(64)))
                .param("id", proposalId).param("family", familyId).param("base", baseVersionId)
                .param("target", TARGET).param("supersedes", supersedes)
                .update();
    }

    private static Path copyFixtures(Path root, java.util.function.UnaryOperator<String> edit) throws Exception {
        Path target = Files.createTempDirectory("trust-agent-fixture-");
        Path source = root.resolve("datasets/synthetic/internal/approved-checklists");
        Path destination = target.resolve("datasets/synthetic/internal/approved-checklists");
        Files.createDirectories(destination);
        try (var paths = Files.list(source)) {
            for (Path path : paths.toList()) {
                String content = Files.readString(path);
                if (path.getFileName().toString().endsWith(".approved-checklist.json")) {
                    String edited = edit.apply(content);
                    assertFalse(edited.equals(content), "fixture 내용 변경이 적용되어야 한다");
                    content = edited;
                }
                Files.writeString(destination.resolve(path.getFileName()), content);
            }
        }
        return target;
    }

    private int count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
    }

    private String single(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }

    private JsonNode readJson(Object value) {
        return value == null ? mapper.readTree("null") : mapper.readTree(value.toString());
    }

    private static String sqlState(Runnable action) {
        try {
            action.run();
            return "no-error";
        } catch (org.springframework.dao.DataAccessException exception) {
            Throwable cause = exception.getMostSpecificCause();
            return cause instanceof SQLException sql ? sql.getSQLState() : cause.getClass().getSimpleName();
        }
    }
}
