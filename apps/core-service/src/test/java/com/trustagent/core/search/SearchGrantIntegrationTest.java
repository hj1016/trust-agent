package com.trustagent.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.grant.GrantService;
import com.trustagent.core.preparation.PreparationScenario;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 근거 검색 경로의 수신 토큰·grant 통합 확인(require-grant=true). 실제 Core·PostgreSQL·Elasticsearch·AI 서비스(uvicorn)를 띄운다.
 * 정상 grant만 근거를 받고, 토큰 누락·grant 없음·만료·범위 밖(공문군·상담 건·업무일)은 근거 문장도 규칙 ID도 받지 못한다.
 * 진단 모드는 끈다(운영과 같은 응답 모양). -PaiServiceIntegration=true일 때만 실행한다.
 */
@SpringBootTest(classes = {TrustAgentCoreApplication.class, SearchGrantIntegrationTest.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "trustAgent.aiServiceIntegration", matches = "true")
class SearchGrantIntegrationTest {

    private static final PreparationScenario.AdjustableClock CLOCK = new PreparationScenario.AdjustableClock();
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
    private static final String CONTROL_DB = "trust_agent_control_search_grant";
    private static final String TOOL_TOKEN = "test-tool-" + UUID.randomUUID();
    private static final String RECORD_TOKEN = "test-record-" + UUID.randomUUID();
    private static final String INBOUND_TOKEN = "test-inbound-" + UUID.randomUUID();
    private static final String PREPAYMENT = "SIN-PREPAYMENT-FEE";
    private static final String SELLER = "SIN-SELLER-CHECKLIST";
    private static final String QUERY = "기업여신 중도상환수수료율과 적용 조건을 확인해야 하나요?";

    static {
        if ("true".equals(System.getProperty("trustAgent.aiServiceIntegration"))) {
            POSTGRES.start();
            ElasticsearchTestContainer.start();
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("create database " + CONTROL_DB);
            } catch (SQLException exception) {
                throw new IllegalStateException("제어 DB 생성 실패", exception);
            }
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
        registry.add("trust-agent.tool-api.service-token", () -> TOOL_TOKEN);
        registry.add("trust-agent.preparation-record.service-token", () -> RECORD_TOKEN);
        registry.add("trust-agent.ai-grant.require", () -> "true");
        registry.add("trust-agent.search.base-url", ElasticsearchTestContainer::baseUrl);
        registry.add("trust-agent.search.username", () -> ElasticsearchTestContainer.REINDEX_USER);
        registry.add("trust-agent.search.password", () -> ElasticsearchTestContainer.REINDEX_PASSWORD);
        registry.add("trust-agent.search.analyzer", () -> ElasticsearchTestContainer.ANALYZER);
        // 같은 JVM의 다른 검색 테스트와 ES 컨테이너를 공유하므로 색인 이름 접두어를 따로 둔다(재색인 결과가 서로 섞이지 않게).
        registry.add("trust-agent.search.index-prefix", () -> "trustagent-rule-evidence-grantit");
    }

    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper mapper;
    @Autowired private PublicProductObservedStateService publicProducts;
    @Autowired private SearchReindexService reindex;
    @Autowired private SearchProperties properties;
    @Autowired private GrantService grants;
    @Value("${local.server.port}") private int port;

    private Path reports;
    private Process aiServer;
    private int aiPort;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void prepare() throws Exception {
        Path root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        Path python = PreparationScenario.aiServicePython(root);
        reports = root.resolve("apps/core-service/build/reports/search-grant");
        Files.createDirectories(reports);
        PreparationScenario.load(JdbcClient.create(dataSource), mapper, PreparationScenario.manager(dataSource), CLOCK, publicProducts, root);
        assertEquals(SearchReindexService.Outcome.CREATED, reindex.reindex().outcome());
        try (ServerSocket socket = new ServerSocket(0)) {
            aiPort = socket.getLocalPort();
        }
        ProcessBuilder builder = new ProcessBuilder(python.toString(), "-m", "uvicorn", "ai_service.app:app", "--host", "127.0.0.1", "--port", Integer.toString(aiPort))
                .directory(root.resolve("apps/ai-service").toFile());
        Map<String, String> environment = builder.environment();
        environment.put("TRUST_AGENT_CORE_BASE_URL", "http://127.0.0.1:" + port);
        environment.put("TRUST_AGENT_TOOL_SERVICE_TOKEN", TOOL_TOKEN);
        environment.put("TRUST_AGENT_AI_INBOUND_TOKEN", INBOUND_TOKEN);
        environment.put("TRUST_AGENT_REPOSITORY_ROOT", root.toString());
        environment.put("TRUST_AGENT_CORE_TIMEOUT_SECONDS", "10");
        environment.put("TRUST_AGENT_ES_URL", ElasticsearchTestContainer.baseUrl());
        environment.put("TRUST_AGENT_ES_SEARCH_USERNAME", ElasticsearchTestContainer.SEARCH_USER);
        environment.put("TRUST_AGENT_ES_SEARCH_PASSWORD", ElasticsearchTestContainer.SEARCH_PASSWORD);
        environment.put("TRUST_AGENT_SEARCH_INDEX_ALIAS", properties.alias());
        environment.put("TRUST_AGENT_SEARCH_DIAGNOSTICS", "0");
        environment.put("PYTHONIOENCODING", "utf-8");
        builder.redirectOutput(reports.resolve("uvicorn.out.txt").toFile()).redirectError(reports.resolve("uvicorn.err.txt").toFile());
        aiServer = builder.start();
        for (int attempt = 0; attempt < 60; attempt++) {
            if (!aiServer.isAlive()) throw new IllegalStateException("AI 서비스가 기동 중 종료됐습니다: " + reports.resolve("uvicorn.err.txt"));
            try {
                if (send(Map.of(), INBOUND_TOKEN).statusCode() == 400) return;
            } catch (IOException ignored) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("AI 서비스가 30초 안에 응답하지 않았습니다.");
    }

    @AfterAll
    void stop() throws Exception {
        if (aiServer != null) {
            aiServer.destroy();
            if (!aiServer.waitFor(10, TimeUnit.SECONDS)) aiServer.destroyForcibly();
        }
    }

    @AfterEach
    void resetClock() {
        CLOCK.reset();
    }

    @Test
    void validGrantReceivesEvidenceAndCountsReads() throws Exception {
        GrantService.Grant grant = issue("consultation-search-ok", List.of(PREPAYMENT), "2026-10-06");
        JsonNode body = ok(search(grant.grantId(), "consultation-search-ok", PREPAYMENT, "2026-10-06"));
        assertEquals("EVIDENCE", body.get("status").stringValue(), body.toString());
        assertTrue(body.get("evidence").size() > 0, body.toString());
        assertTrue(grants.find(grant.grantId()).orElseThrow().readCalls() > 0, "Tool 호출이 grant 읽기 수로 집계돼야 한다");
    }

    @Test
    void missingInboundTokenIsRejectedBeforeSearch() throws Exception {
        GrantService.Grant grant = issue("consultation-search-token", List.of(PREPAYMENT), "2026-10-06");
        HttpResponse<String> response = send(request(grant.grantId(), "consultation-search-token", PREPAYMENT, "2026-10-06"), null);
        assertEquals(401, response.statusCode(), response.body());
        assertFalse(response.body().contains("policy-rule:"), response.body());
        assertEquals(0, grants.find(grant.grantId()).orElseThrow().readCalls());
    }

    @Test
    void serviceTokenWithoutGrantGetsNoEvidence() throws Exception {
        assertHeldWithoutEvidence(search(null, "consultation-search-none", PREPAYMENT, "2026-10-06"));
    }

    @Test
    void expiredGrantGetsNoEvidence() throws Exception {
        GrantService.Grant grant = issue("consultation-search-expired", List.of(PREPAYMENT), "2026-10-06");
        CLOCK.set(grant.expiresAt().plusSeconds(1));
        assertHeldWithoutEvidence(search(grant.grantId(), "consultation-search-expired", PREPAYMENT, "2026-10-06"));
    }

    @Test
    void familyOutsideGrantGetsNoEvidence() throws Exception {
        GrantService.Grant grant = issue("consultation-search-family", List.of(SELLER), "2026-10-06");
        assertHeldWithoutEvidence(search(grant.grantId(), "consultation-search-family", PREPAYMENT, "2026-10-06"));
    }

    @Test
    void otherConsultationGetsNoEvidence() throws Exception {
        GrantService.Grant grant = issue("consultation-search-owner", List.of(PREPAYMENT), "2026-10-06");
        assertHeldWithoutEvidence(search(grant.grantId(), "consultation-search-other", PREPAYMENT, "2026-10-06"));
    }

    @Test
    void businessDateOutsideGrantGetsNoEvidence() throws Exception {
        GrantService.Grant grant = issue("consultation-search-date", List.of(PREPAYMENT), "2026-10-05");
        assertHeldWithoutEvidence(search(grant.grantId(), "consultation-search-date", PREPAYMENT, "2026-10-06"));
    }

    private GrantService.Grant issue(String consultationId, List<String> families, String businessDate) {
        return grants.issue(grants.servedWorkspace(), consultationId, "SW-APPLICATION-001", families, LocalDate.parse(businessDate),
                "SYN-STAFF-01", "STAFF", "trace-search-grant");
    }

    private void assertHeldWithoutEvidence(HttpResponse<String> response) throws Exception {
        JsonNode body = ok(response);
        assertEquals("EVIDENCE_HOLD", body.get("status").stringValue(), body.toString());
        assertEquals(0, body.get("evidence").size(), body.toString());
        assertFalse(response.body().contains("policy-rule:"), "권한 없는 응답에 규칙 ID가 없어야 한다: " + response.body());
        assertFalse(body.has("diagnostics"), "진단 정보(후보 ID·점수)가 없어야 한다");
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> search(String grantId, String consultationId, String familyId, String businessDate) throws Exception {
        return send(request(grantId, consultationId, familyId, businessDate), INBOUND_TOKEN);
    }

    private Map<String, Object> request(String grantId, String consultationId, String familyId, String businessDate) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("query", QUERY);
        body.put("familyId", familyId);
        body.put("businessDate", businessDate);
        body.put("consultationId", consultationId);
        if (grantId != null) body.put("grantId", grantId);
        return body;
    }

    private HttpResponse<String> send(Map<String, Object> body, String inboundToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + aiPort + "/api/v1/ai/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (inboundToken != null) builder.header("Authorization", "Bearer " + inboundToken);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
