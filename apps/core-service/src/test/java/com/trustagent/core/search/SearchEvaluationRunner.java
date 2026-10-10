package com.trustagent.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
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
import java.time.Clock;
import java.util.ArrayList;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TASK-016 평가 하네스(CI 미포함, -PsearchEvaluation=true). TASK-021 runner와 같은 구조.
 * 고정 시계 2026-10-06T03:00:00Z, TASK-015 상태, 재색인, AI 서비스 HTTP(진단 모드) 기동, 평가 스크립트 실행.
 * 순서: (1) 초기 점검용 10건을 보류 없음(untuned)으로 실행 → (2) 세 방식 비교(tune) → (3) 선택값을 bm25-hold-v1로 고정해 AI 서비스 재기동
 * → (4) 초기 점검용 재실행(고정값 확인)과 최종 평가용 27건 실행. 수치 기준 충족 여부는 결과 파일에 기록만 하고 여기서 단정하지 않는다.
 * 결과: apps/core-service/build/reports/search-evaluation/. 분석기는 -PsearchAnalyzer=standard|nori(플러그인 이미지 필요).
 */
@SpringBootTest(classes = {TrustAgentCoreApplication.class, SearchEvaluationRunner.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "trustAgent.searchEvaluation", matches = "true")
class SearchEvaluationRunner {

    private static final PreparationScenario.AdjustableClock CLOCK = new PreparationScenario.AdjustableClock();
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
    private static final String TOOL_TOKEN = "eval-tool-" + UUID.randomUUID();
    private static final String RECORD_TOKEN = "eval-record-" + UUID.randomUUID();
    private static final String ANALYZER = System.getProperty("trustAgent.searchAnalyzer", "standard");
    private static final String HOLD_VERSION = System.getProperty("trustAgent.searchHoldVersion", "bm25-hold-v1");
    private static final String FUZZINESS = System.getProperty("trustAgent.searchFuzziness", "");
    private static final String CONFIGURATION = "bm25-" + ANALYZER + (FUZZINESS.isBlank() ? "" : "-fuzzy" + FUZZINESS.toLowerCase());
    private static final String SMOKE = "datasets/synthetic/search-goldenset/prepayment-fee-smoke-v1.json";
    private static final String EVAL = "datasets/synthetic/search-goldenset/prepayment-fee-eval-v1.json";

    static {
        if ("true".equals(System.getProperty("trustAgent.searchEvaluation"))) {
            POSTGRES.start();
            ElasticsearchTestContainer.start();
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
        registry.add("trust-agent.tool-api.service-token", () -> TOOL_TOKEN);
        registry.add("trust-agent.preparation-record.service-token", () -> RECORD_TOKEN);
        registry.add("trust-agent.search.base-url", ElasticsearchTestContainer::baseUrl);
        registry.add("trust-agent.search.username", () -> ElasticsearchTestContainer.REINDEX_USER);
        registry.add("trust-agent.search.password", () -> ElasticsearchTestContainer.REINDEX_PASSWORD);
        registry.add("trust-agent.search.analyzer", () -> ANALYZER);
    }

    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper mapper;
    @Autowired private PublicProductObservedStateService publicProducts;
    @Autowired private SearchReindexService reindex;
    @Autowired private SearchProperties properties;
    @Value("${local.server.port}") private int port;

    private Path root;
    private Path python;
    private Path output;
    private Process aiServer;
    private int aiPort;

    @BeforeAll
    void prepare() throws Exception {
        root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        python = PreparationScenario.aiServicePython(root);
        output = root.resolve("apps/core-service/build/reports/search-evaluation").resolve(CONFIGURATION);
        if (Files.isDirectory(output)) {
            try (var paths = Files.walk(output)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        Files.createDirectories(output);
        JdbcClient jdbc = JdbcClient.create(dataSource);
        PreparationScenario.load(jdbc, mapper, PreparationScenario.manager(dataSource), CLOCK, publicProducts, root);
        SearchReindexService.Result result = reindex.reindex();
        assertEquals(SearchReindexService.Outcome.CREATED, result.outcome());
        Files.writeString(output.resolve("reindex.json"), mapper.writeValueAsString(Map.of(
                "analyzer", ANALYZER, "configuration", CONFIGURATION, "index", result.indexName(), "alias", result.alias(), "documents", result.documentCount(), "contentHash", result.contentHash())));
    }

    @AfterAll
    void stop() throws Exception {
        stopAiService();
    }

    @Test
    void tuneOnSmokeSetThenEvaluate() throws Exception {
        // (1) 보류 없음으로 초기 점검용 실행 → 원시 후보와 점수 기록
        startAiService(Map.of(), "untuned");
        Path untuned = runScript("run", "--goldenset", SMOKE, "--search-url", "http://127.0.0.1:" + aiPort, "--configuration", CONFIGURATION,
                "--out-dir", output.toString(), "--allow-untuned", "--repository-root", root.toString());
        Path untunedResult = output.resolve("prepayment-fee-smoke-v1." + CONFIGURATION + ".untuned.result.json");
        assertTrue(Files.isRegularFile(untunedResult), untuned.toString());
        // (2) 세 방식 비교
        Path tuning = output.resolve("hold-tuning.json");
        runScript("tune", "--result", untunedResult.toString(), "--goldenset", SMOKE, "--out", tuning.toString());
        JsonNode chosen = mapper.readTree(Files.readString(tuning)).get("chosen");
        assertEquals(0, chosen.get("leaks").intValue(), "초기 점검용에서 보류 질의 후보 유출 0건을 만족하는 값이 없다: " + chosen);
        // (3) 선택값으로 고정해 재기동
        stopAiService();
        Map<String, String> hold = Map.of(
                "TRUST_AGENT_SEARCH_HOLD_METHOD", chosen.get("method").stringValue(),
                "TRUST_AGENT_SEARCH_HOLD_MIN_SCORE", chosen.get("min_score").stringValue(),
                "TRUST_AGENT_SEARCH_HOLD_MIN_RATIO", chosen.get("min_ratio").stringValue(),
                "TRUST_AGENT_SEARCH_HOLD_VERSION", HOLD_VERSION);
        startAiService(hold, HOLD_VERSION);
        // (4) 고정값으로 초기 점검용 재실행과 최종 평가용 실행
        runScript("run", "--goldenset", SMOKE, "--search-url", "http://127.0.0.1:" + aiPort, "--configuration", CONFIGURATION,
                "--out-dir", output.toString(), "--repository-root", root.toString());
        runScript("run", "--goldenset", EVAL, "--search-url", "http://127.0.0.1:" + aiPort, "--configuration", CONFIGURATION,
                "--out-dir", output.toString(), "--repository-root", root.toString());
        Path evalResult = output.resolve("prepayment-fee-eval-v1." + CONFIGURATION + "." + HOLD_VERSION + ".result.json");
        assertTrue(Files.isRegularFile(evalResult));
        JsonNode summary = mapper.readTree(Files.readString(evalResult));
        assertTrue(summary.get("final_evaluation").booleanValue());
        System.out.println("SEARCH_EVALUATION configuration=" + CONFIGURATION + " hold=" + hold + " verdict=" + summary.get("verdict") + " final_stage=" + summary.get("final_stage"));
    }

    private Path runScript(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(python.toString(), root.resolve("scripts/evaluate_search.py").toString()));
        command.addAll(List.of(arguments));
        Path log = output.resolve("script-" + arguments[0] + "-" + System.nanoTime() + ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process = builder.start();
        assertTrue(process.waitFor(10, TimeUnit.MINUTES), "평가 스크립트 시간 초과");
        assertEquals(0, process.exitValue(), "평가 스크립트 실패: " + Files.readString(log));
        return log;
    }

    private void startAiService(Map<String, String> extra, String label) throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            aiPort = socket.getLocalPort();
        }
        ProcessBuilder builder = new ProcessBuilder(python.toString(), "-m", "uvicorn", "ai_service.app:app", "--host", "127.0.0.1", "--port", Integer.toString(aiPort))
                .directory(root.resolve("apps/ai-service").toFile());
        Map<String, String> environment = builder.environment();
        environment.put("TRUST_AGENT_CORE_BASE_URL", "http://127.0.0.1:" + port);
        environment.put("TRUST_AGENT_TOOL_SERVICE_TOKEN", TOOL_TOKEN);
        environment.put("TRUST_AGENT_REPOSITORY_ROOT", root.toString());
        environment.put("TRUST_AGENT_CORE_TIMEOUT_SECONDS", "10");
        environment.put("TRUST_AGENT_ES_URL", ElasticsearchTestContainer.baseUrl());
        environment.put("TRUST_AGENT_ES_SEARCH_USERNAME", ElasticsearchTestContainer.SEARCH_USER);
        environment.put("TRUST_AGENT_ES_SEARCH_PASSWORD", ElasticsearchTestContainer.SEARCH_PASSWORD);
        environment.put("TRUST_AGENT_SEARCH_INDEX_ALIAS", properties.alias());
        environment.put("TRUST_AGENT_SEARCH_DIAGNOSTICS", "1");
        if (!FUZZINESS.isBlank()) environment.put("TRUST_AGENT_SEARCH_FUZZINESS", FUZZINESS);
        environment.put("PYTHONIOENCODING", "utf-8");
        environment.putAll(extra);
        builder.redirectOutput(output.resolve("uvicorn-" + label + ".out.txt").toFile()).redirectError(output.resolve("uvicorn-" + label + ".err.txt").toFile());
        aiServer = builder.start();
        HttpClient http = HttpClient.newHttpClient();
        for (int attempt = 0; attempt < 60; attempt++) {
            if (!aiServer.isAlive()) throw new IllegalStateException("AI 서비스가 기동 중 종료됐습니다: " + output.resolve("uvicorn-" + label + ".err.txt"));
            try {
                HttpResponse<String> probe = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + aiPort + "/api/v1/ai/search"))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
                if (probe.statusCode() == 400) return;
            } catch (IOException ignored) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("AI 서비스가 30초 안에 응답하지 않았습니다.");
    }

    private void stopAiService() throws Exception {
        if (aiServer != null) {
            aiServer.destroy();
            if (!aiServer.waitFor(10, TimeUnit.SECONDS)) aiServer.destroyForcibly();
            aiServer = null;
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
