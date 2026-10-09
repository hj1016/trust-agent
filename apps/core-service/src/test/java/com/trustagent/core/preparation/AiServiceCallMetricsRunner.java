package com.trustagent.core.preparation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import tools.jackson.databind.ObjectMapper;

/**
 * TASK-021 AI 호출 계측 runner. CI 테스트가 아니라 측정 도구이며 -PaiCallMetrics=true(와 -PaiServiceIntegration=true)일 때만 실행한다.
 * TASK-015 연결 검증과 같은 환경(Testcontainers PostgreSQL, 고정 시계, PreparationScenario 상태)에서 AI 서비스 CLI를 시나리오별로 N회 실행하고
 * --metrics-file 결과를 build/reports/ai-call-metrics/<시나리오>/NNN.metrics.json에 남긴 뒤 요약 스크립트를 실행한다.
 * 측정값은 이 하네스 오버헤드(컨테이너, 테스트 컨텍스트)를 포함하며 evidence에 그 사실을 적는다. Core 코드, 계약, 스키마는 바꾸지 않는다.
 */
@SpringBootTest(
        classes = {TrustAgentCoreApplication.class, AiServiceCallMetricsRunner.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "trustAgent.aiCallMetrics", matches = "true")
class AiServiceCallMetricsRunner {

    private static final String TOOL_TOKEN = "metrics-tool-" + UUID.randomUUID();
    private static final String RECORD_TOKEN = "metrics-record-" + UUID.randomUUID();
    private static final PreparationScenario.AdjustableClock CLOCK = new PreparationScenario.AdjustableClock();
    private static final long PROCESS_TIMEOUT_SECONDS = 60;
    private static final int WARMUP_RUNS = 5;
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
    /** 시나리오 이름 → 업무일. partial: 중도상환수수료 READY + 셀러론 HOLD. hold: FIXTURE 기간이라 두 공문군 모두 HOLD. */
    private static final Map<String, String> SCENARIOS = new LinkedHashMap<>(Map.of());

    static {
        SCENARIOS.put("partial-2026-10-06", "2026-10-06");
        SCENARIOS.put("hold-2026-09-30", "2026-09-30");
        POSTGRES.start();
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
    }

    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper mapper;
    @Autowired private PublicProductObservedStateService publicProducts;
    @Value("${local.server.port}") private int port;

    private JdbcClient jdbc;
    private Path root;
    private Path python;
    private Path outputDirectory;
    private int runs;

    @BeforeAll
    void loadScenarioAndCheckEnvironment() throws Exception {
        root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        python = PreparationScenario.aiServicePython(root);
        runs = Integer.parseInt(System.getProperty("trustAgent.aiCallMetrics.runs", "30"));
        outputDirectory = root.resolve("apps/core-service/build/reports/ai-call-metrics");
        if (Files.isDirectory(outputDirectory)) {
            try (var paths = Files.walk(outputDirectory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        Files.createDirectories(outputDirectory);
        jdbc = JdbcClient.create(dataSource);
        PreparationScenario.load(jdbc, mapper, PreparationScenario.manager(dataSource), CLOCK, publicProducts, root);
    }

    @Test
    void measureScenarios() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> scenario : SCENARIOS.entrySet()) {
            Path scenarioDirectory = outputDirectory.resolve(scenario.getKey());
            Files.createDirectories(scenarioDirectory);
            for (int index = 1; index <= runs; index++) {
                String label = String.format("%03d", index);
                Path metricsFile = scenarioDirectory.resolve(label + ".metrics.json");
                int exit = runPrepare(scenario.getKey(), scenario.getValue(), "metrics-" + scenario.getKey() + "-" + label, metricsFile, scenarioDirectory.resolve(label));
                if (exit != 0 || !Files.isRegularFile(metricsFile)) {
                    failures.add(scenario.getKey() + "#" + label + " exit=" + exit);
                }
            }
        }
        assertTrue(failures.isEmpty(), "계측 실행 실패: " + failures);

        // Core 쪽 감사 표로 호출 수 교차 확인: Tool 호출 감사 행 수 = 계측 파일의 Tool 호출 수 합.
        long auditedToolCalls = jdbc.sql("select count(*) from tool_call_audit where consultation_id like 'metrics-%'").query(Long.class).single();
        long measuredToolCalls = 0;
        for (String scenario : SCENARIOS.keySet()) {
            try (var files = Files.list(outputDirectory.resolve(scenario))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".metrics.json")).toList()) {
                    var counts = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8)).get("counts");
                    measuredToolCalls += counts.get("applicable_checklist").longValue() + counts.get("rule_evidence").longValue();
                }
            }
        }
        assertEquals(measuredToolCalls, auditedToolCalls, "계측된 Tool 호출 수와 tool_call_audit 행 수가 같아야 한다");

        writeEnvironment(auditedToolCalls);
        int summaryExit = runSummary();
        assertEquals(0, summaryExit, "요약 스크립트 실패");
    }

    private int runPrepare(String scenario, String businessDate, String consultationId, Path metricsFile, Path logPrefix) throws Exception {
        List<String> command = List.of(python.toString(), "-m", "ai_service", "prepare",
                "--application", PreparationScenario.APPLICATION, "--business-date", businessDate,
                "--consultation-id", consultationId, "--repository-root", root.toString(),
                "--metrics-file", metricsFile.toString());
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.resolve("apps/ai-service").toFile());
        Map<String, String> environment = builder.environment();
        environment.put("TRUST_AGENT_CORE_BASE_URL", "http://127.0.0.1:" + port);
        environment.put("TRUST_AGENT_TOOL_SERVICE_TOKEN", TOOL_TOKEN);
        environment.put("TRUST_AGENT_PREPARATION_RECORD_TOKEN", RECORD_TOKEN);
        environment.put("TRUST_AGENT_CORE_TIMEOUT_SECONDS", "5");
        environment.put("PYTHONIOENCODING", "utf-8");
        Path out = Path.of(logPrefix + ".out.txt");
        Path err = Path.of(logPrefix + ".err.txt");
        builder.redirectOutput(out.toFile()).redirectError(err.toFile());
        Process process = builder.start();
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    private int runSummary() throws Exception {
        List<String> command = List.of(python.toString(), root.resolve("scripts/summarize_ai_call_metrics.py").toString(),
                "--input-dir", outputDirectory.toString(), "--warmup", Integer.toString(WARMUP_RUNS),
                "--markdown", outputDirectory.resolve("summary.md").toString(),
                "--json", outputDirectory.resolve("summary.json").toString());
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile());
        builder.redirectOutput(outputDirectory.resolve("summary.out.txt").toFile()).redirectError(outputDirectory.resolve("summary.err.txt").toFile());
        Process process = builder.start();
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    private void writeEnvironment(long auditedToolCalls) throws IOException {
        var environment = mapper.createObjectNode();
        environment.put("java_version", System.getProperty("java.version"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        environment.put("python", python.toString());
        environment.put("postgres_image", POSTGRES_IMAGE);
        environment.put("runs_per_scenario", runs);
        environment.put("warmup_runs_excluded_in_summary", WARMUP_RUNS);
        environment.put("tool_call_audit_rows_for_metrics_consultations", auditedToolCalls);
        environment.put("core_timeout_seconds", 5);
        environment.put("harness", "Testcontainers PostgreSQL + Spring Boot RANDOM_PORT + CLI subprocess per run (오버헤드 포함)");
        Files.writeString(outputDirectory.resolve("environment.json"), environment.toPrettyString() + "\n", StandardCharsets.UTF_8);
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
