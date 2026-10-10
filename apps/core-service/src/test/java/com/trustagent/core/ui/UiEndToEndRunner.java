package com.trustagent.core.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.preparation.PreparationScenario;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import com.trustagent.core.security.DemoUsers;
import java.io.IOException;
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
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
import tools.jackson.databind.ObjectMapper;

/**
 * TASK-017b 브라우저 E2E 하네스(CI 필수 Job 미포함, -PuiE2E=true -PaiServiceIntegration=true).
 * 실제 Core(PostgreSQL Testcontainer, 별도 제어 DB, require-grant=true, 고정 시계 2026-10-06T03:00:00Z, TASK-015 상태)와 실제 AI 서비스(uvicorn)를 띄우고,
 * Core가 빌드된 화면(apps/frontend/dist → static)을 제공하는 상태에서 Playwright를 실행한다. 이어서 AI 서비스를 내리고 AI 중단 시나리오를 실행한다.
 * 화면 캡처와 Playwright 결과는 docs/evidence/task-017b/에 남긴다. 목 서버나 가짜 AI 응답은 없다.
 */
@SpringBootTest(classes = {TrustAgentCoreApplication.class, UiEndToEndRunner.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "trustAgent.uiE2E", matches = "true")
class UiEndToEndRunner {

    private static final PreparationScenario.AdjustableClock CLOCK = new PreparationScenario.AdjustableClock();
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
    private static final String CONTROL_DB = "trust_agent_control_ui_e2e";
    private static final String STAFF_PASSWORD = "e2e-" + UUID.randomUUID();
    private static final String REVIEWER_PASSWORD = "e2e-" + UUID.randomUUID();
    private static final String BOTH_PASSWORD = "e2e-" + UUID.randomUUID();
    private static final String TOOL_TOKEN = "test-tool-" + UUID.randomUUID();
    private static final String RECORD_TOKEN = "test-record-" + UUID.randomUUID();
    private static final String INBOUND_TOKEN = "test-inbound-" + UUID.randomUUID();
    private static final int AI_PORT = freePort();

    static {
        if ("true".equals(System.getProperty("trustAgent.uiE2E"))) {
            POSTGRES.start();
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("create database " + CONTROL_DB);
            } catch (SQLException exception) {
                throw new IllegalStateException("제어 DB 생성 실패", exception);
            }
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
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
        registry.add(DemoUsers.environmentName(DemoUsers.REVIEWER_USER), () -> REVIEWER_PASSWORD);
        registry.add(DemoUsers.environmentName(DemoUsers.BOTH_USER), () -> BOTH_PASSWORD);
        registry.add("trust-agent.tool-api.service-token", () -> TOOL_TOKEN);
        registry.add("trust-agent.preparation-record.service-token", () -> RECORD_TOKEN);
        registry.add("trust-agent.ai-grant.require", () -> "true");
        registry.add("trust-agent.ai-service.base-url", () -> "http://127.0.0.1:" + AI_PORT);
        registry.add("trust-agent.ai-service.inbound-token", () -> INBOUND_TOKEN);
    }

    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper mapper;
    @Autowired private PublicProductObservedStateService publicProducts;
    @Value("${local.server.port}") private int port;

    private Path root;
    private Path evidence;
    private Process aiServer;

    @BeforeAll
    void startStack() throws Exception {
        root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        Path python = PreparationScenario.aiServicePython(root);
        evidence = root.resolve("docs/evidence/task-017b");
        Files.createDirectories(evidence);
        PreparationScenario.load(JdbcClient.create(dataSource), mapper, PreparationScenario.manager(dataSource), CLOCK, publicProducts, root);
        HttpResponse<String> index = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, index.statusCode(), "Core가 화면(index.html)을 제공해야 한다. apps/frontend에서 npm run build 뒤 실행한다.");
        ProcessBuilder builder = new ProcessBuilder(python.toString(), "-m", "uvicorn", "ai_service.app:app", "--host", "127.0.0.1", "--port", Integer.toString(AI_PORT))
                .directory(root.resolve("apps/ai-service").toFile());
        Map<String, String> environment = builder.environment();
        environment.put("TRUST_AGENT_CORE_BASE_URL", "http://127.0.0.1:" + port);
        environment.put("TRUST_AGENT_TOOL_SERVICE_TOKEN", TOOL_TOKEN);
        environment.put("TRUST_AGENT_PREPARATION_RECORD_TOKEN", RECORD_TOKEN);
        environment.put("TRUST_AGENT_AI_INBOUND_TOKEN", INBOUND_TOKEN);
        environment.put("TRUST_AGENT_REPOSITORY_ROOT", root.toString());
        environment.put("PYTHONIOENCODING", "utf-8");
        Path logs = root.resolve("apps/core-service/build/reports/ui-e2e");
        Files.createDirectories(logs);
        builder.redirectOutput(logs.resolve("uvicorn.out.txt").toFile()).redirectError(logs.resolve("uvicorn.err.txt").toFile());
        aiServer = builder.start();
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + AI_PORT + "/api/v1/ai/consultation-preparations"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
                return;
            } catch (IOException ignored) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("AI 서비스가 기동하지 않았습니다.");
    }

    @AfterAll
    void stopStack() throws Exception {
        stopAi();
    }

    @Test
    void browserEndToEnd() throws Exception {
        int main = playwright("e2e/main.spec.ts", "main", Map.of());
        stopAi();
        int down = playwright("e2e/ai-down.spec.ts", "ai-down", Map.of("E2E_AI_DOWN", "1"));
        assertEquals(0, main, "Playwright 본 시나리오 실패. docs/evidence/task-017b/playwright-main.log");
        assertEquals(0, down, "Playwright AI 중단 시나리오 실패. docs/evidence/task-017b/playwright-ai-down.log");
        assertTrue(Files.isRegularFile(evidence.resolve("04-confirmed.png")));
    }

    private int playwright(String spec, String label, Map<String, String> extra) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(List.of("npx", "playwright", "test", spec))
                .directory(root.resolve("apps/frontend").toFile()).redirectErrorStream(true)
                .redirectOutput(evidence.resolve("playwright-" + label + ".log").toFile());
        Map<String, String> environment = builder.environment();
        environment.put("E2E_BASE_URL", "http://127.0.0.1:" + port);
        environment.put("E2E_STAFF_PASSWORD", STAFF_PASSWORD);
        environment.put("E2E_REVIEWER_PASSWORD", REVIEWER_PASSWORD);
        environment.put("E2E_BOTH_PASSWORD", BOTH_PASSWORD);
        environment.put("E2E_BUSINESS_DATE", "2026-10-06");
        environment.put("E2E_EVIDENCE_DIR", evidence.toString());
        environment.put("E2E_REPORT", evidence.resolve("playwright-" + label + ".json").toString());
        environment.putAll(extra);
        Process process = builder.start();
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("Playwright 시간 초과");
        }
        // 증거 파일에는 로컬 절대 경로를 남기지 않는다(저장소 기준 <repo>로 바꾼다).
        for (Path file : List.of(evidence.resolve("playwright-" + label + ".log"), evidence.resolve("playwright-" + label + ".json"))) {
            if (Files.isRegularFile(file)) {
                Files.writeString(file, Files.readString(file).replace(root.toAbsolutePath().toString(), "<repo>"));
            }
        }
        return process.exitValue();
    }

    private void stopAi() throws Exception {
        if (aiServer != null && aiServer.isAlive()) {
            aiServer.destroy();
            if (!aiServer.waitFor(10, TimeUnit.SECONDS)) aiServer.destroyForcibly();
        }
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
