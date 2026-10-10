package com.trustagent.core.consultation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.control.ControlDataSourceConfiguration;
import com.trustagent.core.grant.GrantService;
import com.trustagent.core.preparation.PreparationScenario;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import com.trustagent.core.security.DemoUsers;
import com.trustagent.core.support.SessionClient;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
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

/**
 * TASK-017a AC-09: STAFF 세션 → 상담 건 → Core가 grant 발급 → AI 서비스(HTTP, 수신 토큰) → Tool·기록(grant 헤더) → 준비안 응답.
 * AI 서비스는 venv의 uvicorn으로 실제 기동한다. 로컬 기본은 명시적 skip(-PaiServiceIntegration=true), 필수 CI에서는 환경 누락이 실패다.
 */
@SpringBootTest(classes = {TrustAgentCoreApplication.class, AiGrantEndToEndIntegrationTest.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiGrantEndToEndIntegrationTest {

    private static final PreparationScenario.AdjustableClock CLOCK = new PreparationScenario.AdjustableClock();
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
    private static final String CONTROL_DB = "trust_agent_control_e2e_test";
    private static final String STAFF_PASSWORD = "demo-" + UUID.randomUUID();
    private static final String TOOL_TOKEN = "test-tool-" + UUID.randomUUID();
    private static final String RECORD_TOKEN = "test-record-" + UUID.randomUUID();
    private static final String INBOUND_TOKEN = "test-inbound-" + UUID.randomUUID();
    private static final int AI_PORT = freePort();

    static {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + CONTROL_DB);
        } catch (SQLException exception) {
            throw new IllegalStateException("제어 DB 생성 실패", exception);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
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
        registry.add(DemoUsers.environmentName(DemoUsers.REVIEWER_USER), () -> "demo-" + UUID.randomUUID());
        registry.add(DemoUsers.environmentName(DemoUsers.BOTH_USER), () -> "demo-" + UUID.randomUUID());
        registry.add("trust-agent.tool-api.service-token", () -> TOOL_TOKEN);
        registry.add("trust-agent.preparation-record.service-token", () -> RECORD_TOKEN);
        registry.add("trust-agent.ai-grant.require", () -> "true");
        registry.add("trust-agent.ai-service.base-url", () -> "http://127.0.0.1:" + AI_PORT);
        registry.add("trust-agent.ai-service.inbound-token", () -> INBOUND_TOKEN);
        registry.add("trust-agent.ai-service.timeout", () -> "30s");
    }

    @Autowired private DataSource dataSource;
    @Autowired @Qualifier(ControlDataSourceConfiguration.CONTROL_JDBC_CLIENT) private JdbcClient control;
    @Autowired private ObjectMapper mapper;
    @Autowired private PublicProductObservedStateService publicProducts;
    @Autowired private GrantService grants;
    @Value("${local.server.port}") private int port;

    private JdbcClient jdbc;
    private Path root;
    private Process aiServer;

    @BeforeAll
    void startAiServiceAndLoadScenario() throws Exception {
        root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        Path python = PreparationScenario.aiServicePython(root);
        jdbc = JdbcClient.create(dataSource);
        PreparationScenario.load(jdbc, mapper, PreparationScenario.manager(dataSource), CLOCK, publicProducts, root);
        Path reports = root.resolve("apps/core-service/build/reports/ai-service");
        Files.createDirectories(reports);
        ProcessBuilder builder = new ProcessBuilder(python.toString(), "-m", "uvicorn", "ai_service.app:app", "--host", "127.0.0.1", "--port", Integer.toString(AI_PORT))
                .directory(root.resolve("apps/ai-service").toFile());
        Map<String, String> environment = builder.environment();
        environment.put("TRUST_AGENT_CORE_BASE_URL", "http://127.0.0.1:" + port);
        environment.put("TRUST_AGENT_TOOL_SERVICE_TOKEN", TOOL_TOKEN);
        environment.put("TRUST_AGENT_PREPARATION_RECORD_TOKEN", RECORD_TOKEN);
        environment.put("TRUST_AGENT_AI_INBOUND_TOKEN", INBOUND_TOKEN);
        environment.put("TRUST_AGENT_REPOSITORY_ROOT", root.toString());
        environment.put("TRUST_AGENT_CORE_TIMEOUT_SECONDS", "5");
        environment.put("PYTHONIOENCODING", "utf-8");
        builder.redirectOutput(reports.resolve("grant-e2e-uvicorn.out.txt").toFile()).redirectError(reports.resolve("grant-e2e-uvicorn.err.txt").toFile());
        aiServer = builder.start();
        waitForAiService();
    }

    private void waitForAiService() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        for (int attempt = 0; attempt < 60; attempt++) {
            if (!aiServer.isAlive()) throw new IllegalStateException("AI 서비스가 기동 중 종료됐습니다(build/reports/ai-service/grant-e2e-uvicorn.err.txt).");
            try {
                HttpResponse<String> probe = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + AI_PORT + "/api/v1/ai/consultation-preparations"))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                if (probe.statusCode() > 0) return;
            } catch (java.io.IOException ignored) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("AI 서비스가 30초 안에 응답하지 않았습니다.");
    }

    @AfterAll
    void stopAiService() throws Exception {
        if (aiServer != null) {
            aiServer.destroy();
            if (!aiServer.waitFor(10, TimeUnit.SECONDS)) aiServer.destroyForcibly();
        }
    }

    @Test
    void staffSessionGetsPreparationThroughCoreIssuedGrant() throws Exception {
        SessionClient staff = SessionClient.login(port, DemoUsers.STAFF_USER, STAFF_PASSWORD);
        String consultationId = mapper.readTree(staff.postJson("/api/v1/consultations", "{\"applicationId\":\"SW-APPLICATION-001\"}").body())
                .get("consultationId").stringValue();

        HttpResponse<String> response = staff.postJson("/api/v1/consultations/" + consultationId + "/preparation", "{\"businessDate\":\"2026-10-06\"}");
        assertEquals(200, response.statusCode(), response.body());
        JsonNode preparation = mapper.readTree(response.body());
        assertEquals("PARTIAL", preparation.get("status").stringValue());
        assertEquals("RECORDED", preparation.get("record").get("status").stringValue());
        assertEquals(consultationId, preparation.get("consultation_id").stringValue());
        assertFalse(response.body().contains(TOOL_TOKEN));
        assertFalse(response.body().contains(RECORD_TOKEN));
        assertFalse(response.body().contains(INBOUND_TOKEN));
        String grantId = response.headers().firstValue("X-TrustAgent-Grant").orElseThrow();

        GrantService.Grant grant = grants.find(grantId).orElseThrow();
        assertEquals("CONSUMED", grant.state());
        assertEquals(consultationId, grant.consultationId());
        assertEquals(List.of("SIN-PREPAYMENT-FEE", "SIN-SELLER-CHECKLIST"), grant.allowedFamilyIds());
        assertEquals(5, grant.readCalls(), "Tool 1 두 번 + Tool 2 세 번");
        List<String> uses = grants.usesOf(grantId);
        assertEquals(5, uses.stream().filter("TOOL:OK"::equals).count());
        assertTrue(uses.contains("RECORD_BEGIN:OK") && uses.contains("RECORD_COMPLETE:OK"), uses.toString());
        assertEquals(1, jdbc.sql("select count(*) from consultation_preparation where consultation_id = :c").param("c", consultationId).query(Integer.class).single());
        assertEquals(5, jdbc.sql("select count(*) from tool_call_audit where consultation_id = :c and outcome = 'OK'").param("c", consultationId).query(Integer.class).single());

        // 같은 상담 건 재요청: 새 grant, Core는 ALREADY_RECORDED, 실행 기록 2건.
        HttpResponse<String> again = staff.postJson("/api/v1/consultations/" + consultationId + "/preparation", "{\"businessDate\":\"2026-10-06\"}");
        assertEquals(200, again.statusCode(), again.body());
        assertEquals("ALREADY_RECORDED", mapper.readTree(again.body()).get("record").get("status").stringValue());
        assertEquals(2, control.sql("select count(*) from ai_request_grant where consultation_id = :c and state = 'CONSUMED'").param("c", consultationId).query(Integer.class).single());
    }

    @Test
    void aiServiceRejectsCallsWithoutInboundToken() throws Exception {
        HttpResponse<String> direct = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + AI_PORT + "/api/v1/ai/consultation-preparations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"applicationId\":\"SW-APPLICATION-001\",\"businessDate\":\"2026-10-06\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, direct.statusCode());
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
