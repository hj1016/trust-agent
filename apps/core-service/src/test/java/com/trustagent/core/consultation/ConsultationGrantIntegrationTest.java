package com.trustagent.core.consultation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.control.ControlDataSourceConfiguration;
import com.trustagent.core.grant.GrantService;
import com.trustagent.core.preparation.PreparationScenario;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import com.trustagent.core.security.DemoUsers;
import com.trustagent.core.support.SessionClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * TASK-017a 두 번째 PR: 상담 건(A안 신청 등록·담당자 권한), grant(범위·만료·읽기 상한·단일 사용·동시 기록), 기록 경로의 신청 해시 대조,
 * 서비스 토큰과 grant의 동시 검증(require-grant=true), 두 DB 사이 정합성(기록 커밋 뒤 CONSUMED 갱신 실패 주입과 대조).
 * AI 서비스 호출 자체는 AiGrantEndToEndIntegrationTest(연결 검증)가 맡는다.
 */
@SpringBootTest(classes = {TrustAgentCoreApplication.class, ConsultationGrantIntegrationTest.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsultationGrantIntegrationTest {

    private static final PreparationScenario.AdjustableClock CLOCK = new PreparationScenario.AdjustableClock();
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
    private static final String CONTROL_DB = "trust_agent_control_grant_test";
    private static final String STAFF_PASSWORD = "demo-" + UUID.randomUUID();
    private static final String REVIEWER_PASSWORD = "demo-" + UUID.randomUUID();
    private static final String BOTH_PASSWORD = "demo-" + UUID.randomUUID();
    private static final String TOOL_TOKEN = "test-tool-" + UUID.randomUUID();
    private static final String RECORD_TOKEN = "test-record-" + UUID.randomUUID();

    static {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + CONTROL_DB);
        } catch (SQLException exception) {
            throw new IllegalStateException("제어 DB 생성 실패", exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("management.server.port", () -> 0);
        registry.add("trust-agent.public-evidence.max-confirmation-age", () -> "30d");
        registry.add("trust-agent.control-datasource.url", () -> POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + CONTROL_DB));
        registry.add("trust-agent.control-datasource.username", POSTGRES::getUsername);
        registry.add("trust-agent.control-datasource.password", POSTGRES::getPassword);
        registry.add("trust-agent.control-datasource.flyway-enabled", () -> true);
        registry.add("trust-agent.demo-users.enabled", () -> true);
        registry.add(DemoUsers.environmentName(DemoUsers.STAFF_USER), () -> STAFF_PASSWORD);
        registry.add(DemoUsers.environmentName(DemoUsers.REVIEWER_USER), () -> REVIEWER_PASSWORD);
        registry.add(DemoUsers.environmentName(DemoUsers.BOTH_USER), () -> BOTH_PASSWORD);
        registry.add("trust-agent.tool-api.service-token", () -> TOOL_TOKEN);
        registry.add("trust-agent.preparation-record.service-token", () -> RECORD_TOKEN);
        registry.add("trust-agent.ai-grant.require", () -> "true");
        registry.add("trust-agent.ai-grant.ttl", () -> "60s");
        registry.add("trust-agent.ai-grant.read-call-limit", () -> "5");
        registry.add("trust-agent.ai-service.base-url", () -> "http://127.0.0.1:1"); // 이 테스트는 AI 서비스를 띄우지 않는다(연결 실패 502 검증용)
    }

    @Autowired private DataSource dataSource;
    @Autowired @Qualifier(ControlDataSourceConfiguration.CONTROL_JDBC_CLIENT) private JdbcClient control;
    @Autowired private ObjectMapper mapper;
    @Autowired private PublicProductObservedStateService publicProducts;
    @Autowired private GrantService grants;
    @Autowired private com.trustagent.core.grant.RecordOutcomeLookup recordLookup;
    @Value("${local.server.port}") private int port;

    private JdbcClient jdbc;
    private Path root;

    @BeforeAll
    void loadScenario() {
        root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        jdbc = JdbcClient.create(dataSource);
        PreparationScenario.load(jdbc, mapper, PreparationScenario.manager(dataSource), CLOCK, publicProducts, root);
    }

    // ---- A안: 신청 등록과 상담 건 ----

    @Test
    void syntheticApplicationsAreRegisteredWithSourceHashAndReloadIsIdempotent() {
        assertEquals(1, jdbc.sql("select count(*) from synthetic_work_application").query(Integer.class).single());
        assertEquals(1, jdbc.sql("select count(*) from synthetic_work_company").query(Integer.class).single());
        String hash = jdbc.sql("select source_hash from synthetic_work_application where application_id = 'SW-APPLICATION-001'").query(String.class).single();
        assertTrue(hash.startsWith("sha256:"));
        var again = new SyntheticWorkLoader(jdbc, mapper, PreparationScenario.manager(dataSource), CLOCK).load(root);
        assertEquals((0 + 0 + 2), again.companiesInserted() + again.applicationsInserted() + again.skipped());
        assertEquals(2, again.skipped());
    }

    @Test
    void consultationRequiresRegisteredApplicationAndIsVisibleOnlyToOwner() throws Exception {
        SessionClient staff = SessionClient.login(port, DemoUsers.STAFF_USER, STAFF_PASSWORD);
        int before = jdbc.sql("select count(*) from consultation").query(Integer.class).single();
        HttpResponse<String> unregistered = staff.postJson("/api/v1/consultations", "{\"applicationId\":\"SW-APPLICATION-999\"}");
        assertEquals(422, unregistered.statusCode());
        assertEquals("APPLICATION_NOT_REGISTERED", mapper.readTree(unregistered.body()).get("code").stringValue());
        assertEquals(before, jdbc.sql("select count(*) from consultation").query(Integer.class).single(), "등록되지 않은 신청으로는 상담 건이 생기지 않는다");

        HttpResponse<String> created = staff.postJson("/api/v1/consultations", "{\"applicationId\":\"SW-APPLICATION-001\"}");
        assertEquals(201, created.statusCode(), created.body());
        JsonNode view = mapper.readTree(created.body());
        String consultationId = view.get("consultationId").stringValue();
        assertEquals(DemoUsers.STAFF_USER, view.get("assignedUserId").stringValue());
        assertEquals("kb-seller-loan", view.get("productKey").stringValue());
        assertEquals("main", view.get("workspaceId").stringValue());
        assertEquals(200, staff.get("/api/v1/consultations/" + consultationId).statusCode());
        assertTrue(mapper.readTree(staff.get("/api/v1/consultations").body()).get("consultations").size() >= 1);

        SessionClient other = SessionClient.login(port, DemoUsers.BOTH_USER, BOTH_PASSWORD);
        HttpResponse<String> hidden = other.get("/api/v1/consultations/" + consultationId);
        assertEquals(404, hidden.statusCode(), "담당자가 아니면 존재를 숨긴다");
        assertEquals("CONSULTATION_NOT_FOUND", mapper.readTree(hidden.body()).get("code").stringValue());
        assertEquals(404, other.postJson("/api/v1/consultations/" + consultationId + "/preparation", "{}").statusCode());
        assertTrue(control.sql("select count(*) from security_event where outcome = 'CONSULTATION_NOT_OWNED' and principal = :p").param("p", DemoUsers.BOTH_USER).query(Integer.class).single() >= 2);

        SessionClient reviewer = SessionClient.login(port, DemoUsers.REVIEWER_USER, REVIEWER_PASSWORD);
        assertEquals(403, reviewer.postJson("/api/v1/consultations", "{\"applicationId\":\"SW-APPLICATION-001\"}").statusCode(), "STAFF 활성만 상담 건을 만든다");
    }

    // ---- grant: 토큰과 함께 검증, 범위·만료·읽기 상한 ----

    @Test
    void toolPathRequiresGrantTogetherWithServiceTokenAndEnforcesScope() throws Exception {
        String consultationId = createConsultation();
        HttpResponse<String> tokenOnly = tool(TOOL_TOKEN, null, "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"businessDate\":\"2026-10-06\",\"consultationId\":\"" + consultationId + "\"}");
        assertEquals(401, tokenOnly.statusCode());
        assertEquals("GRANT_REQUIRED", mapper.readTree(tokenOnly.body()).get("code").stringValue());

        GrantService.Grant grant = grants.issue("main", consultationId, "SW-APPLICATION-001", List.of("SIN-PREPAYMENT-FEE", "SIN-SELLER-CHECKLIST"),
                LocalDate.of(2026, 10, 6), DemoUsers.STAFF_USER, "STAFF", "t-issue");
        assertEquals(60, grant.ttlSeconds());

        HttpResponse<String> grantOnly = tool("wrong-token", grant.grantId(), "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"consultationId\":\"" + consultationId + "\"}");
        assertEquals(401, grantOnly.statusCode(), "grant만으로는 토큰 검사를 넘지 못한다");

        HttpResponse<String> ok = tool(TOOL_TOKEN, grant.grantId(), "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"businessDate\":\"2026-10-06\",\"consultationId\":\"" + consultationId + "\"}");
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(mapper.readTree(ok.body()).get("usable").booleanValue());

        HttpResponse<String> otherFamily = tool(TOOL_TOKEN, grant.grantId(), "{\"familyId\":\"SIN-OTHER-FAMILY\",\"consultationId\":\"" + consultationId + "\"}");
        assertEquals(403, otherFamily.statusCode());
        assertEquals("GRANT_SCOPE_MISMATCH", mapper.readTree(otherFamily.body()).get("code").stringValue());
        HttpResponse<String> otherConsultation = tool(TOOL_TOKEN, grant.grantId(), "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"consultationId\":\"someone-else\"}");
        assertEquals(403, otherConsultation.statusCode());
        HttpResponse<String> unknownGrant = tool(TOOL_TOKEN, "ai-grant:" + "0".repeat(32), "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"consultationId\":\"" + consultationId + "\"}");
        assertEquals(403, unknownGrant.statusCode());
        assertEquals("GRANT_NOT_FOUND", mapper.readTree(unknownGrant.body()).get("code").stringValue());

        // 읽기 상한 5: 이미 1회 썼으므로 4회 더 가능, 6번째는 소진
        for (int i = 0; i < 4; i++) {
            assertEquals(200, tool(TOOL_TOKEN, grant.grantId(), "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"businessDate\":\"2026-10-06\",\"consultationId\":\"" + consultationId + "\"}").statusCode());
        }
        HttpResponse<String> exhausted = tool(TOOL_TOKEN, grant.grantId(), "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"businessDate\":\"2026-10-06\",\"consultationId\":\"" + consultationId + "\"}");
        assertEquals(403, exhausted.statusCode());
        assertEquals("GRANT_EXHAUSTED", mapper.readTree(exhausted.body()).get("code").stringValue());
        assertEquals(5, grants.find(grant.grantId()).orElseThrow().readCalls());
        assertTrue(grants.usesOf(grant.grantId()).contains("TOOL:GRANT_EXHAUSTED"));
        assertEquals(5, jdbc.sql("select count(*) from tool_call_audit where consultation_id = :c and outcome = 'OK'").param("c", consultationId).query(Integer.class).single());
    }

    /** 업무일 경계: grant에 저장된 업무일과 다른 업무일로 Tool 조회·준비안 기록을 할 수 없다. */
    @Test
    void businessDateMustMatchGrantForToolAndRecord() throws Exception {
        String consultationId = createConsultation();
        GrantService.Grant grant = grants.issue("main", consultationId, "SW-APPLICATION-001", List.of("SIN-PREPAYMENT-FEE", "SIN-SELLER-CHECKLIST"),
                LocalDate.of(2026, 10, 6), DemoUsers.STAFF_USER, "STAFF", "t-date");
        HttpResponse<String> otherDate = tool(TOOL_TOKEN, grant.grantId(), "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"businessDate\":\"2026-10-07\",\"consultationId\":\"" + consultationId + "\"}");
        assertEquals(403, otherDate.statusCode());
        assertEquals("GRANT_SCOPE_MISMATCH", mapper.readTree(otherDate.body()).get("code").stringValue());
        HttpResponse<String> noDate = tool(TOOL_TOKEN, grant.grantId(), "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"consultationId\":\"" + consultationId + "\"}");
        assertEquals(403, noDate.statusCode(), "grant를 쓸 때 업무일 생략(오늘로 채워짐)은 허용하지 않는다");
        assertEquals("GRANT_SCOPE_MISMATCH", mapper.readTree(noDate.body()).get("code").stringValue());
        assertEquals(0, grants.find(grant.grantId()).orElseThrow().readCalls(), "거부된 호출은 읽기 횟수에 들어가지 않는다");

        ObjectNode body = recordBody(consultationId, grant.grantId());
        ObjectNode shifted = body.deepCopy();
        shifted.put("business_date", "2026-10-07");
        shifted.put("preparation_id", preparationId(shifted));
        HttpResponse<String> recorded = record(RECORD_TOKEN, grant.grantId(), shifted);
        assertEquals(403, recorded.statusCode(), recorded.body());
        assertEquals("GRANT_SCOPE_MISMATCH", mapper.readTree(recorded.body()).get("code").stringValue());
        assertEquals("ISSUED", grants.find(grant.grantId()).orElseThrow().state(), "기록 시작 전 거부라 grant는 그대로");
        assertEquals(0, jdbc.sql("select count(*) from consultation_preparation_run where run_id = :r").param("r", shifted.get("run_id").stringValue()).query(Integer.class).single());
        assertTrue(grants.usesOf(grant.grantId()).contains("RECORD_BEGIN:GRANT_SCOPE_MISMATCH"));
        assertEquals(201, record(RECORD_TOKEN, grant.grantId(), body).statusCode(), "같은 grant로 맞는 업무일 기록은 된다");
    }

    /** workspace 경계: Tool·기록 경로는 Core가 서비스하는 workspace로 검사하며 null로 우회되지 않는다. */
    @Test
    void workspaceIsCheckedOnToolAndRecordAndNullIsRejected() throws Exception {
        String consultationId = createConsultation();
        GrantService.Grant foreign = grants.issue("trial-other", consultationId, "SW-APPLICATION-001", List.of("SIN-PREPAYMENT-FEE", "SIN-SELLER-CHECKLIST"),
                LocalDate.of(2026, 10, 6), DemoUsers.STAFF_USER, "STAFF", "t-ws");
        HttpResponse<String> toolResponse = tool(TOOL_TOKEN, foreign.grantId(), "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"businessDate\":\"2026-10-06\",\"consultationId\":\"" + consultationId + "\"}");
        assertEquals(403, toolResponse.statusCode());
        assertEquals("WORKSPACE_UNAVAILABLE", mapper.readTree(toolResponse.body()).get("code").stringValue());
        GrantService.Grant main = grants.issue("main", consultationId, "SW-APPLICATION-001", List.of("SIN-PREPAYMENT-FEE", "SIN-SELLER-CHECKLIST"),
                LocalDate.of(2026, 10, 6), DemoUsers.STAFF_USER, "STAFF", "t-ws-main");
        ObjectNode body = recordBody(consultationId, main.grantId());
        HttpResponse<String> recorded = record(RECORD_TOKEN, foreign.grantId(), body);
        assertEquals(403, recorded.statusCode());
        assertEquals("WORKSPACE_UNAVAILABLE", mapper.readTree(recorded.body()).get("code").stringValue());
        assertEquals("ISSUED", grants.find(foreign.grantId()).orElseThrow().state());
        assertEquals(0, grants.find(foreign.grantId()).orElseThrow().readCalls());
        // 서비스 계층에서 workspace를 비워 호출해도 우회가 아니라 거부다.
        var nullTool = org.junit.jupiter.api.Assertions.assertThrows(com.trustagent.core.grant.GrantException.class,
                () -> grants.authorizeTool(main.grantId(), consultationId, "SIN-PREPAYMENT-FEE", "2026-10-06", "applicable_checklist", null, "t-null"));
        assertEquals("WORKSPACE_UNAVAILABLE", nullTool.code());
        var nullRecord = org.junit.jupiter.api.Assertions.assertThrows(com.trustagent.core.grant.GrantException.class,
                () -> grants.beginRecord(main.grantId(), consultationId, "SW-APPLICATION-001", "2026-10-06", null, body.get("run_id").stringValue(), null, "t-null"));
        assertEquals("WORKSPACE_UNAVAILABLE", nullRecord.code());
        assertEquals("ISSUED", grants.find(main.grantId()).orElseThrow().state());
    }

    @Test
    void expiredGrantIsRefusedAfterTtl() throws Exception {
        String consultationId = createConsultation();
        GrantService.Grant grant = grants.issue("main", consultationId, "SW-APPLICATION-001", List.of("SIN-PREPAYMENT-FEE"), LocalDate.of(2026, 10, 6),
                DemoUsers.STAFF_USER, "STAFF", "t-expire");
        try {
            CLOCK.set(PreparationScenario.EVALUATED_AT.plusSeconds(61));
            HttpResponse<String> expired = tool(TOOL_TOKEN, grant.grantId(), "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"consultationId\":\"" + consultationId + "\"}");
            assertEquals(403, expired.statusCode());
            assertEquals("GRANT_EXPIRED", mapper.readTree(expired.body()).get("code").stringValue());
            assertEquals("EXPIRED", grants.find(grant.grantId()).orElseThrow().state());
        } finally {
            CLOCK.reset();
        }
    }

    // ---- 기록 경로: 신청 해시 대조, 단일 사용, 동시 기록, 정합성 ----

    @Test
    void recordPathChecksApplicationHashAndConsumesGrantOnce() throws Exception {
        String consultationId = createConsultation();
        GrantService.Grant grant = grants.issue("main", consultationId, "SW-APPLICATION-001", List.of("SIN-PREPAYMENT-FEE", "SIN-SELLER-CHECKLIST"),
                LocalDate.of(2026, 10, 6), DemoUsers.STAFF_USER, "STAFF", "t-record");
        ObjectNode body = recordBody(consultationId, grant.grantId());

        ObjectNode tampered = body.deepCopy();
        ((ObjectNode) tampered.get("application")).put("source_hash", "sha256:" + "f".repeat(64));
        tampered.put("preparation_id", preparationId(tampered));
        HttpResponse<String> mismatch = record(RECORD_TOKEN, grant.grantId(), tampered);
        assertEquals(422, mismatch.statusCode(), mismatch.body());
        assertEquals("APPLICATION_SOURCE_MISMATCH", mapper.readTree(mismatch.body()).get("code").stringValue());
        assertEquals("ISSUED", grants.find(grant.grantId()).orElseThrow().state(), "커밋 전 거부면 grant는 ISSUED로 돌아온다");

        HttpResponse<String> noGrant = record(RECORD_TOKEN, null, body);
        assertEquals(401, noGrant.statusCode());
        assertEquals("GRANT_REQUIRED", mapper.readTree(noGrant.body()).get("code").stringValue());

        ObjectNode fresh = body.deepCopy();
        fresh.put("run_id", "consultation-preparation-run:" + UUID.randomUUID().toString().replace("-", "")); // 거부된 실행의 run_id는 이미 기록됐다
        HttpResponse<String> recorded = record(RECORD_TOKEN, grant.grantId(), fresh);
        assertEquals(201, recorded.statusCode(), recorded.body());
        GrantService.Grant consumed = grants.find(grant.grantId()).orElseThrow();
        assertEquals("CONSUMED", consumed.state());
        assertTrue(grants.usesOf(grant.grantId()).containsAll(List.of("RECORD_BEGIN:OK", "RECORD_COMPLETE:OK")));

        ObjectNode again = body.deepCopy();
        again.put("run_id", "consultation-preparation-run:" + "b".repeat(32));
        HttpResponse<String> reused = record(RECORD_TOKEN, grant.grantId(), again);
        assertEquals(409, reused.statusCode());
        assertEquals("GRANT_CONSUMED", mapper.readTree(reused.body()).get("code").stringValue());
        String preparationId = mapper.readTree(recorded.body()).get("preparationId").stringValue();
        assertEquals(1, jdbc.sql("select count(*) from consultation_preparation where preparation_id = :p").param("p", preparationId).query(Integer.class).single());
        HttpResponse<String> toolAfter = tool(TOOL_TOKEN, grant.grantId(), "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"consultationId\":\"" + consultationId + "\"}");
        assertEquals(409, toolAfter.statusCode(), "기록에 쓴 grant는 Tool에도 못 쓴다");
        assertEquals("GRANT_CONSUMED", mapper.readTree(toolAfter.body()).get("code").stringValue());
    }

    @Test
    void concurrentRecordsWithTheSameGrantLetOnlyOneThrough() throws Exception {
        String consultationId = createConsultation();
        GrantService.Grant grant = grants.issue("main", consultationId, "SW-APPLICATION-001", List.of("SIN-PREPAYMENT-FEE", "SIN-SELLER-CHECKLIST"),
                LocalDate.of(2026, 10, 6), DemoUsers.STAFF_USER, "STAFF", "t-concurrent");
        ObjectNode first = recordBody(consultationId, grant.grantId());
        ObjectNode second = first.deepCopy();
        second.put("run_id", "consultation-preparation-run:" + "c".repeat(32));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> a = pool.submit(() -> { start.await(); return record(RECORD_TOKEN, grant.grantId(), first).statusCode(); });
            Future<Integer> b = pool.submit(() -> { start.await(); return record(RECORD_TOKEN, grant.grantId(), second).statusCode(); });
            start.countDown();
            List<Integer> statuses = List.of(a.get(), b.get());
            assertTrue(statuses.contains(409), statuses.toString());
            assertTrue(statuses.contains(201), statuses.toString());
        } finally {
            pool.shutdownNow();
        }
        // 같은 업무 내용은 다른 테스트의 기록과 preparation_id가 같아 ALREADY_RECORDED(200)일 수 있다. 실행 기록은 통과한 한 건만 남는다.
        int runs = jdbc.sql("select count(*) from consultation_preparation_run where run_id in (:a, :b)")
                .param("a", first.get("run_id").stringValue()).param("b", second.get("run_id").stringValue()).query(Integer.class).single();
        assertEquals(1, runs, "409로 끝난 요청은 실행 기록도 남기지 않는다(grant 단계에서 거부)");
        assertEquals("CONSUMED", grants.find(grant.grantId()).orElseThrow().state());
    }

    /**
     * ADR-014 9-1항 두 번째 행, TASK-017a AC-12: 업무 기록 커밋 뒤 제어 DB의 CONSUMED 갱신 실패(제어 DB 트리거로 실제 주입).
     * grant는 ISSUED로 돌아가지 않고 CONSUMING으로 남으며, 재전송은 중복 기록을 만들지 않고, 만료 뒤 대조가 업무 DB에서 run_id를 찾아 CONSUMED로 정리한다.
     */
    @Test
    void consumedUpdateFailureAfterCommitKeepsGrantUnusableAndIsSettledByReconciliation() throws Exception {
        String consultationId = createConsultation();
        GrantService.Grant grant = grants.issue("main", consultationId, "SW-APPLICATION-001", List.of("SIN-PREPAYMENT-FEE", "SIN-SELLER-CHECKLIST"),
                LocalDate.of(2026, 10, 6), DemoUsers.STAFF_USER, "STAFF", "t-inject-consumed");
        ObjectNode body = recordBody(consultationId, grant.grantId());
        String runId = body.get("run_id").stringValue();
        control.sql("""
                create or replace function test_fail_consumed() returns trigger language plpgsql as $$
                begin
                    if new.state = 'CONSUMED' and new.grant_id = '%s' then
                        raise exception 'injected CONSUMED update failure';
                    end if;
                    return new;
                end $$
                """.formatted(grant.grantId())).update();
        control.sql("create trigger test_fail_consumed before update on ai_request_grant for each row execute function test_fail_consumed()").update();
        try {
            HttpResponse<String> recorded = record(RECORD_TOKEN, grant.grantId(), body);
            assertEquals(201, recorded.statusCode(), "업무 기록은 커밋됐으므로 응답은 성공이다: " + recorded.body());
            GrantService.Grant after = grants.find(grant.grantId()).orElseThrow();
            assertEquals("CONSUMING", after.state(), "커밋 뒤 갱신 실패에서 grant를 ISSUED로 돌리지 않는다");
            assertEquals(runId, after.recordRunId());
            assertTrue(grants.usesOf(grant.grantId()).contains("RECORD_COMPLETE_FAILED:CONSUMED_UPDATE_FAILED"));
            assertEquals(1, preparationRows(body), "업무 기록은 하나");

            // 재전송: 같은 grant는 409 GRANT_CONSUMED, 새 grant + 같은 run_id는 409 RUN_ID_CONFLICT, 새 grant + 새 run_id는 ALREADY_RECORDED.
            HttpResponse<String> sameGrant = record(RECORD_TOKEN, grant.grantId(), body);
            assertEquals(409, sameGrant.statusCode());
            assertEquals("GRANT_CONSUMED", mapper.readTree(sameGrant.body()).get("code").stringValue());
            GrantService.Grant second = grants.issue("main", consultationId, "SW-APPLICATION-001", List.of("SIN-PREPAYMENT-FEE", "SIN-SELLER-CHECKLIST"),
                    LocalDate.of(2026, 10, 6), DemoUsers.STAFF_USER, "STAFF", "t-inject-second");
            HttpResponse<String> sameRun = record(RECORD_TOKEN, second.grantId(), body);
            assertEquals(409, sameRun.statusCode());
            assertEquals("RUN_ID_CONFLICT", mapper.readTree(sameRun.body()).get("code").stringValue());
            assertEquals("ISSUED", grants.find(second.grantId()).orElseThrow().state(), "커밋 전 거부(RUN_ID_CONFLICT)는 되돌린다");
            ObjectNode newRun = body.deepCopy();
            newRun.put("run_id", "consultation-preparation-run:" + UUID.randomUUID().toString().replace("-", ""));
            HttpResponse<String> already = record(RECORD_TOKEN, second.grantId(), newRun);
            assertEquals(200, already.statusCode(), already.body());
            assertEquals("ALREADY_RECORDED", mapper.readTree(already.body()).get("status").stringValue());
            assertEquals("CONSUMED", grants.find(second.grantId()).orElseThrow().state());
            assertEquals(1, preparationRows(body), "재전송 뒤에도 업무 기록은 하나");
        } finally {
            control.sql("drop trigger if exists test_fail_consumed on ai_request_grant").update();
            control.sql("drop function if exists test_fail_consumed()").update();
        }
        grants.reconcile(recordLookup::committedForGrant, "t-reconcile");
        assertEquals("CONSUMING", grants.find(grant.grantId()).orElseThrow().state(), "만료 전에는 건드리지 않는다");
        try {
            CLOCK.set(PreparationScenario.EVALUATED_AT.plusSeconds(120));
            assertTrue(grants.reconcile(recordLookup::committedForGrant, "t-reconcile") >= 1);
        } finally {
            CLOCK.reset();
        }
        GrantService.Grant settled = grants.find(grant.grantId()).orElseThrow();
        assertEquals("CONSUMED", settled.state());
        assertTrue(grants.usesOf(grant.grantId()).contains("RECONCILE:CONSUMED"));
        try {
            CLOCK.set(PreparationScenario.EVALUATED_AT.plusSeconds(120));
            grants.reconcile(recordLookup::committedForGrant, "t-reconcile");
        } finally {
            CLOCK.reset();
        }
        assertEquals(1, grants.usesOf(grant.grantId()).stream().filter(use -> use.startsWith("RECONCILE")).count(), "대조는 한 번만 정리한다");
    }

    /**
     * 커밋 뒤 성공 실행 기록(consultation_preparation_run) 쓰기 실패(업무 DB 트리거로 실제 주입). 서비스는 FAILURE_AUDIT_WRITE_FAILED(500)를
     * 던지지만 준비안은 커밋됐다. grant는 ISSUED로 돌아가면 안 되며 CONSUMING(불확실)으로 남고, 대조가 준비안 행으로 커밋을 확인해 정리한다.
     */
    @Test
    void runAuditFailureAfterCommitDoesNotReleaseGrant() throws Exception {
        String consultationId = createConsultation();
        GrantService.Grant grant = grants.issue("main", consultationId, "SW-APPLICATION-001", List.of("SIN-PREPAYMENT-FEE", "SIN-SELLER-CHECKLIST"),
                LocalDate.of(2026, 10, 6), DemoUsers.STAFF_USER, "STAFF", "t-inject-audit");
        ObjectNode body = recordBody(consultationId, grant.grantId());
        String runId = body.get("run_id").stringValue();
        jdbc.sql("""
                create or replace function test_fail_run_audit() returns trigger language plpgsql as $$
                begin
                    if new.run_id = '%s' and new.outcome in ('RECORDED', 'ALREADY_RECORDED') then
                        raise exception 'injected run audit failure';
                    end if;
                    return new;
                end $$
                """.formatted(runId)).update();
        jdbc.sql("create trigger test_fail_run_audit before insert on consultation_preparation_run for each row execute function test_fail_run_audit()").update();
        HttpResponse<String> response;
        try {
            response = record(RECORD_TOKEN, grant.grantId(), body);
        } finally {
            // 보호 표의 트리거 삭제는 DDL 가드(V1 guard_audit_trigger_ddl)가 막는다. 이 클래스 전용 컨테이너이므로 함수를 무해하게 바꿔 무력화한다.
            jdbc.sql("create or replace function test_fail_run_audit() returns trigger language plpgsql as $$ begin return new; end $$").update();
        }
        assertEquals(500, response.statusCode(), response.body());
        assertEquals("FAILURE_AUDIT_WRITE_FAILED", mapper.readTree(response.body()).get("code").stringValue());
        assertEquals(1, preparationRows(body), "준비안은 커밋됐다");
        assertEquals(0, jdbc.sql("select count(*) from consultation_preparation_run where run_id = :r").param("r", runId).query(Integer.class).single());
        GrantService.Grant after = grants.find(grant.grantId()).orElseThrow();
        assertEquals("CONSUMING", after.state(), "커밋 여부가 불확실한 실패에서 grant를 재사용 가능 상태로 돌리지 않는다");
        assertTrue(grants.usesOf(grant.grantId()).contains("RECORD_UNCERTAIN:FAILURE_AUDIT_WRITE_FAILED"));
        assertFalse(grants.usesOf(grant.grantId()).stream().anyMatch(use -> use.startsWith("RECORD_RELEASE")));
        ObjectNode retry = body.deepCopy();
        retry.put("run_id", "consultation-preparation-run:" + UUID.randomUUID().toString().replace("-", ""));
        HttpResponse<String> reused = record(RECORD_TOKEN, grant.grantId(), retry);
        assertEquals(409, reused.statusCode());
        assertEquals("GRANT_CONSUMED", mapper.readTree(reused.body()).get("code").stringValue());
        try {
            CLOCK.set(PreparationScenario.EVALUATED_AT.plusSeconds(120));
            assertTrue(grants.reconcile(recordLookup::committedForGrant, "t-reconcile-audit") >= 1);
        } finally {
            CLOCK.reset();
        }
        assertEquals("CONSUMED", grants.find(grant.grantId()).orElseThrow().state(), "준비안 행으로 커밋을 확인해 사후 CONSUMED");
    }

    /** 커밋되지 않았고 결과도 확인되지 않은 CONSUMING grant는 대조에서 EXPIRED가 된다(ISSUED 아님). */
    @Test
    void uncommittedConsumingGrantExpiresOnReconciliation() throws Exception {
        String consultationId = createConsultation();
        GrantService.Grant grant = grants.issue("main", consultationId, "SW-APPLICATION-001", List.of("SIN-PREPAYMENT-FEE"),
                LocalDate.of(2026, 10, 6), DemoUsers.STAFF_USER, "STAFF", "t-orphan");
        String runId = "consultation-preparation-run:" + UUID.randomUUID().toString().replace("-", "");
        grants.beginRecord(grant.grantId(), consultationId, "SW-APPLICATION-001", "2026-10-06", grants.servedWorkspace(), runId, null, "t-orphan"); // Core 재시작으로 끊긴 기록을 흉내
        assertEquals("CONSUMING", grants.find(grant.grantId()).orElseThrow().state());
        try {
            CLOCK.set(PreparationScenario.EVALUATED_AT.plusSeconds(120));
            assertTrue(grants.reconcile(recordLookup::committedForGrant, "t-orphan") >= 1);
        } finally {
            CLOCK.reset();
        }
        assertEquals("EXPIRED", grants.find(grant.grantId()).orElseThrow().state());
        assertTrue(grants.usesOf(grant.grantId()).contains("RECONCILE:EXPIRED"));
    }

    private int preparationRows(ObjectNode body) {
        return jdbc.sql("select count(*) from consultation_preparation where preparation_id = :p")
                .param("p", body.get("preparation_id").stringValue()).query(Integer.class).single();
    }

    @Test
    void preparationRequestIssuesGrantAndFailsClosedWhenAiServiceIsDown() throws Exception {
        SessionClient staff = SessionClient.login(port, DemoUsers.STAFF_USER, STAFF_PASSWORD);
        String consultationId = mapper.readTree(staff.postJson("/api/v1/consultations", "{\"applicationId\":\"SW-APPLICATION-001\"}").body()).get("consultationId").stringValue();
        HttpResponse<String> response = staff.postJson("/api/v1/consultations/" + consultationId + "/preparation", "{\"businessDate\":\"2026-10-06\"}");
        assertEquals(502, response.statusCode(), response.body());
        assertEquals("AI_SERVICE_UNAVAILABLE", mapper.readTree(response.body()).get("code").stringValue());
        assertTrue(response.headers().firstValue("X-TrustAgent-Grant").isEmpty(), "grant ID를 브라우저에 보내지 않는다");
        assertFalse(response.body().contains("ai-grant:"));
        assertEquals(1, control.sql("select count(*) from ai_request_grant where consultation_id = :c and state = 'ISSUED'").param("c", consultationId).query(Integer.class).single(),
                "grant는 발급됐지만 사용되지 않은 채 만료된다");
        assertEquals(400, staff.postJson("/api/v1/consultations/" + consultationId + "/preparation", "{\"businessDate\":\"2026/10/06\"}").statusCode());
    }

    // ---- helpers ----

    private String createConsultation() throws Exception {
        SessionClient staff = SessionClient.login(port, DemoUsers.STAFF_USER, STAFF_PASSWORD);
        HttpResponse<String> created = staff.postJson("/api/v1/consultations", "{\"applicationId\":\"SW-APPLICATION-001\"}");
        assertEquals(201, created.statusCode(), created.body());
        return mapper.readTree(created.body()).get("consultationId").stringValue();
    }

    private HttpResponse<String> tool(String token, String grantId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tools/applicable_checklist"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (grantId != null) request.header("X-TrustAgent-Grant", grantId);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> record(String token, String grantId, ObjectNode body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/consultation-preparations"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (grantId != null) request.header("X-TrustAgent-Grant", grantId);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Tool 1·2 실제 응답으로 PARTIAL 준비안 기록 본문을 만든다(TASK-015 계약). */
    private ObjectNode recordBody(String consultationId, String grantId) throws Exception {
        JsonNode checklist = mapper.readTree(tool(TOOL_TOKEN, grantId, "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"businessDate\":\"2026-10-06\",\"consultationId\":\"" + consultationId + "\"}").body());
        assertTrue(checklist.get("usable").booleanValue(), checklist.toString());
        ObjectNode body = mapper.createObjectNode();
        body.put("run_id", "consultation-preparation-run:" + UUID.randomUUID().toString().replace("-", ""));
        body.put("assembler_version", "preparation-assembler-v1");
        // 테스트마다 다른 준비안이 되도록 messages_hash를 달리한다(상담 ID는 준비안 ID 계산에서 빠진다).
        body.put("messages_hash", "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(consultationId.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        body.put("family_mapping_hash", jdbc.sql("select mapping_hash from consultation_family_mapping order by loaded_at desc, mapping_hash limit 1").query(String.class).single());
        ObjectNode application = body.putObject("application");
        application.put("application_id", "SW-APPLICATION-001");
        application.put("company_id", "SW-COMPANY-001");
        application.put("product_key", "kb-seller-loan");
        application.put("source_hash", jdbc.sql("select source_hash from synthetic_work_application where application_id = 'SW-APPLICATION-001'").query(String.class).single());
        body.put("business_date", "2026-10-06");
        body.put("status", "PARTIAL");
        body.put("preparation_complete", false);
        body.put("consultation_id", consultationId);
        var sections = body.putArray("sections");
        ObjectNode ready = sections.addObject();
        ready.put("family_id", "SIN-PREPAYMENT-FEE"); ready.put("required", true); ready.put("status", "READY");
        ready.putNull("hold_kind"); ready.putNull("hold_claim_basis");
        ready.put("evaluated_at", checklist.get("evaluatedAt").stringValue());
        ready.put("selected_notice_id", checklist.get("selectedNotice").get("noticeId").stringValue());
        ready.put("approved_checklist_version_id", checklist.get("approvedChecklist").get("approvedChecklistVersionId").stringValue());
        ready.put("decision_id", checklist.get("approvedChecklist").get("decisionId").stringValue());
        var ruleIds = ready.putArray("item_rule_version_ids");
        var hashes = ready.putArray("item_evidence_hashes");
        for (JsonNode item : checklist.get("approvedChecklist").get("items")) {
            String ruleId = item.get("sourceRuleVersionId").stringValue();
            ruleIds.add(ruleId);
            JsonNode evidence = mapper.readTree(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tools/rule_evidence"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + TOOL_TOKEN).header("X-TrustAgent-Grant", grantId)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"ruleVersionId\":\"" + ruleId + "\",\"consultationId\":\"" + consultationId + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            hashes.add(evidence.get("evidenceHash").stringValue());
        }
        ready.putArray("blocking_reasons");
        ready.put("tool_response_hash", "sha256:" + "2".repeat(64));
        ObjectNode hold = sections.addObject();
        hold.put("family_id", "SIN-SELLER-CHECKLIST"); hold.put("required", true); hold.put("status", "HOLD");
        hold.put("hold_kind", "CORE_DECISION"); hold.put("hold_claim_basis", "CORE_REPORTED");
        hold.put("evaluated_at", checklist.get("evaluatedAt").stringValue());
        hold.put("selected_notice_id", "SIN-SELLER-CHECKLIST-V2");
        hold.putNull("approved_checklist_version_id"); hold.putNull("decision_id");
        hold.putArray("item_rule_version_ids"); hold.putArray("item_evidence_hashes");
        hold.putArray("blocking_reasons").add("HUMAN_REVIEW_PENDING");
        hold.put("tool_response_hash", "sha256:" + "3".repeat(64));
        body.put("preparation_id", preparationId(body));
        return body;
    }

    private String preparationId(ObjectNode body) {
        return PreparationScenario.preparationId(mapper, body);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        Clock fixedClock() {
            return CLOCK;
        }
    }
}
