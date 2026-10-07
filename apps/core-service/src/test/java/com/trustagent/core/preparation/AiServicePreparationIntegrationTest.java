package com.trustagent.core.preparation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
 * TASK-015 AC-01, 02, 13, 14: Core(Java)를 띄우고 AI 서비스(Python CLI)를 실제로 실행하는 연결 검증.
 * 로컬 기본은 명시적 skip(-PaiServiceIntegration=true로 실행). 필수 CI(TRUST_AGENT_REQUIRE_AI_INTEGRATION=1)에서는 건너뜀이 허용되지 않고
 * Python·venv·의존성 누락은 AI_INTEGRATION_ENV_MISSING 실패로 드러난다. 프로세스 제한 60초, 출력은 build/reports/ai-service/에 남긴다.
 */
@SpringBootTest(
        classes = {TrustAgentCoreApplication.class, AiServicePreparationIntegrationTest.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiServicePreparationIntegrationTest {

    private static final String TOOL_TOKEN = "test-tool-" + UUID.randomUUID();
    private static final String RECORD_TOKEN = "test-record-" + UUID.randomUUID();
    private static final PreparationScenario.AdjustableClock CLOCK = new PreparationScenario.AdjustableClock();
    private static final long PROCESS_TIMEOUT_SECONDS = 60;
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));

    static {
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
    private Path reportDirectory;
    private String firstPreparationId;

    @BeforeAll
    void loadScenarioAndCheckEnvironment() throws Exception {
        root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        python = PreparationScenario.aiServicePython(root);
        reportDirectory = root.resolve("apps/core-service/build/reports/ai-service");
        Files.createDirectories(reportDirectory);
        jdbc = JdbcClient.create(dataSource);
        PreparationScenario.load(jdbc, mapper, PreparationScenario.manager(dataSource), CLOCK, publicProducts, root);
    }

    // ---- AC-01, 02, 13: 명령 한 번으로 PARTIAL 준비안과 Core 기록 ID ----

    @Test
    @Order(1)
    void prepareCommandAssemblesPartialPreparationAndRecordsIt() throws Exception {
        CliResult result = runPrepare("first", "e2e-1");
        assertEquals(0, result.exitCode(), result.stderr());
        JsonNode preparation = mapper.readTree(result.stdout());
        assertEquals("PARTIAL", preparation.get("status").stringValue());
        assertFalse(preparation.get("preparation_complete").booleanValue());
        assertTrue(preparation.get("headline").stringValue().contains("끝나지 않았습니다"));
        assertEquals("RECORDED", preparation.get("record").get("status").stringValue());
        firstPreparationId = preparation.get("preparation_id").stringValue();
        assertEquals(firstPreparationId, preparation.get("record").get("core_preparation_id").stringValue());

        JsonNode prepayment = preparation.get("sections").get(0);
        assertEquals("READY", prepayment.get("status").stringValue());
        assertEquals(3, prepayment.get("items").size());
        assertEquals("CHECK_PREPAYMENT_FEE_RATE", prepayment.get("items").get(0).get("rule_key").stringValue());
        assertTrue(prepayment.get("items").get(0).get("evidence").get("evidence_text").stringValue().contains("0.8퍼센트"));
        assertEquals("0.8", prepayment.get("items").get(0).get("structured_change").get("after_value").stringValue());
        JsonNode seller = preparation.get("sections").get(1);
        assertEquals("HOLD | CORE_DECISION | CORE_REPORTED", seller.get("status").stringValue() + " | " + seller.get("hold_kind").stringValue() + " | " + seller.get("hold_claim_basis").stringValue());
        List<String> sellerReasons = new ArrayList<>();
        seller.get("blocking_reasons").forEach(reason -> sellerReasons.add(reason.stringValue()));
        assertTrue(sellerReasons.contains("HUMAN_REVIEW_PENDING"), sellerReasons.toString()); // 예시 checklist 일정 불일치 코드도 함께 온다
        assertTrue(seller.get("hold_message").stringValue().contains("검토 대기"));
        assertEquals(0, seller.get("items").size());
        assertEquals(1, preparation.get("remaining_checks").size());
        assertTrue(preparation.get("notices").get("human_decision_notice").stringValue().contains("담당자"));
        assertFalse(result.stdout().contains(TOOL_TOKEN));
        assertFalse(result.stdout().contains(RECORD_TOKEN));

        assertEquals("PARTIAL | 2 | 1", single("select status || ' | ' || section_count || ' | ' || required_hold_count from consultation_preparation where preparation_id = '" + firstPreparationId + "'"));
        assertEquals("RECORDED", single("select outcome from consultation_preparation_run where preparation_id = '" + firstPreparationId + "' order by started_at limit 1"));
        assertEquals(3, count("tool_call_audit where tool_name = 'rule_evidence' and consultation_id = 'e2e-1'"));
    }

    // ---- AC-13: 재실행은 Core를 다시 보고 저장만 멱등 ----

    @Test
    @Order(2)
    void rerunRechecksCoreAndRecordsOnlyOnce() throws Exception {
        int toolCalls = count("tool_call_audit where tool_name = 'applicable_checklist'");
        CLOCK.set(PreparationScenario.EVALUATED_AT.plusSeconds(60)); // 평가 시각이 달라져 Tool 응답 해시도 달라진다
        CliResult result;
        try {
            result = runPrepare("second", "e2e-2");
        } finally {
            CLOCK.reset();
        }
        assertEquals(0, result.exitCode(), result.stderr());
        JsonNode preparation = mapper.readTree(result.stdout());
        assertEquals(firstPreparationId, preparation.get("preparation_id").stringValue());
        assertEquals("ALREADY_RECORDED", preparation.get("record").get("status").stringValue());
        assertTrue(preparation.get("record").get("recorded").booleanValue());
        assertEquals("2026-10-06T03:01:00Z", preparation.get("sections").get(0).get("evaluated_at").stringValue());
        assertEquals(2, count("consultation_preparation_run r, jsonb_array_elements(r.section_evaluations) e where r.preparation_id = '" + firstPreparationId + "' and e->>'family_id' = '" + PreparationScenario.PREPAYMENT + "'"));
        assertEquals(2, count("(select distinct e->>'tool_response_hash' h from consultation_preparation_run r, jsonb_array_elements(r.section_evaluations) e where r.preparation_id = '" + firstPreparationId + "' and e->>'family_id' = '" + PreparationScenario.PREPAYMENT + "') x"), "실행별 Tool 응답 해시가 둘 다 남는다");
        assertEquals("2026-10-06T03:00:00Z", single("select to_char(evaluated_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') from consultation_preparation_section where preparation_id = '" + firstPreparationId + "' and family_id = '" + PreparationScenario.PREPAYMENT + "'"), "첫 기록의 섹션 행은 그대로다");
        assertTrue(count("tool_call_audit where tool_name = 'applicable_checklist'") >= toolCalls + 2, "재실행도 Tool 1을 다시 호출한다");
        assertEquals(1, count("consultation_preparation where preparation_id = '" + firstPreparationId + "'"));
        assertEquals(2, count("consultation_preparation_run where preparation_id = '" + firstPreparationId + "'"));
    }

    // ---- AC-02, 13: 승인이 아직 알려지지 않은 시각이면 새 ID의 HOLD 준비안이 기록된다 ----

    @Test
    @Order(3)
    void stateChangeProducesNewHoldPreparation() throws Exception {
        CLOCK.set(Instant.parse("2026-10-05T04:00:00Z"));
        try {
            CliResult result = runPrepare("before-approval", "e2e-3");
            assertEquals(0, result.exitCode(), result.stderr());
            JsonNode preparation = mapper.readTree(result.stdout());
            assertEquals("HOLD", preparation.get("status").stringValue());
            assertNotEquals(firstPreparationId, preparation.get("preparation_id").stringValue());
            assertEquals("RECORDED", preparation.get("record").get("status").stringValue());
            assertEquals(2, preparation.get("remaining_checks").size());
            assertTrue(preparation.get("notices").get("usage_notice").stringValue().contains("다시 확인"));
        } finally {
            CLOCK.reset();
        }
    }

    // ---- AC-13: 철회 뒤 재실행은 새 ID의 HOLD 준비안을 기록한다 ----

    @Test
    @Order(5)
    void withdrawalAfterRecordingProducesNewHoldPreparation() throws Exception {
        jdbc.sql("""
                insert into internal_notice_lifecycle_event values
                ('notice-event:%s','SYNTHETIC_INTERNAL','SIN-PREPAYMENT-FEE-V2','WITHDRAWN','2026-10-07T00:00:00Z','합성 철회 사건','sha256:%s')
                """.formatted("6".repeat(32), "6".repeat(64))).update();
        CLOCK.set(Instant.parse("2026-10-07T01:00:00Z"));
        try {
            CliResult result = runPrepare("after-withdrawal", "e2e-5", Map.of(), "2026-10-07");
            assertEquals(0, result.exitCode(), result.stderr());
            JsonNode preparation = mapper.readTree(result.stdout());
            assertEquals("HOLD", preparation.get("status").stringValue());
            assertNotEquals(firstPreparationId, preparation.get("preparation_id").stringValue());
            assertEquals("RECORDED", preparation.get("record").get("status").stringValue());
            JsonNode prepayment = preparation.get("sections").get(0);
            assertEquals("HOLD | CORE_DECISION", prepayment.get("status").stringValue() + " | " + prepayment.get("hold_kind").stringValue());
            assertEquals(0, prepayment.get("items").size());
            List<String> reasons = new ArrayList<>();
            prepayment.get("blocking_reasons").forEach(reason -> reasons.add(reason.stringValue()));
            assertTrue(reasons.contains("EFFECTIVE_NOTICE_WITHDRAWN"), reasons.toString());
        } finally {
            CLOCK.reset();
        }
    }

    // ---- AC-05, 14: 기록 토큰이 틀리면 준비안은 나오되 기록되지 않고 종료 코드 3 ----

    @Test
    @Order(6)
    void wrongRecordTokenLeavesPreparationUnrecorded() throws Exception {
        CliResult result = runPrepare("wrong-token", "e2e-4", Map.of("TRUST_AGENT_PREPARATION_RECORD_TOKEN", "wrong-" + UUID.randomUUID()));
        assertEquals(3, result.exitCode(), result.stderr());
        JsonNode preparation = mapper.readTree(result.stdout());
        assertEquals("REJECTED", preparation.get("record").get("status").stringValue());
        assertFalse(preparation.get("record").get("recorded").booleanValue());
        assertEquals("UNAUTHENTICATED", preparation.get("record").get("error_code").stringValue());
        assertTrue(preparation.get("notices").get("usage_notice").stringValue().contains("사용하지"));
        assertTrue(result.stderr().contains("사용하지 말고"));
    }

    // ---- helpers ----

    private record CliResult(int exitCode, String stdout, String stderr) {}

    private CliResult runPrepare(String label, String consultationId) throws Exception {
        return runPrepare(label, consultationId, Map.of());
    }

    private CliResult runPrepare(String label, String consultationId, Map<String, String> overrides) throws Exception {
        return runPrepare(label, consultationId, overrides, "2026-10-06");
    }

    private CliResult runPrepare(String label, String consultationId, Map<String, String> overrides, String businessDate) throws Exception {
        List<String> command = List.of(python.toString(), "-m", "ai_service", "prepare",
                "--application", PreparationScenario.APPLICATION, "--business-date", businessDate,
                "--consultation-id", consultationId, "--repository-root", root.toString());
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.resolve("apps/ai-service").toFile());
        Map<String, String> environment = builder.environment();
        environment.put("TRUST_AGENT_CORE_BASE_URL", "http://127.0.0.1:" + port);
        environment.put("TRUST_AGENT_TOOL_SERVICE_TOKEN", TOOL_TOKEN);
        environment.put("TRUST_AGENT_PREPARATION_RECORD_TOKEN", RECORD_TOKEN);
        environment.put("TRUST_AGENT_CORE_TIMEOUT_SECONDS", "5");
        environment.put("PYTHONIOENCODING", "utf-8");
        environment.putAll(overrides);
        Path out = reportDirectory.resolve(label + ".out.txt");
        Path err = reportDirectory.resolve(label + ".err.txt");
        builder.redirectOutput(out.toFile()).redirectError(err.toFile());
        Process process = builder.start();
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("AI 서비스 명령이 " + PROCESS_TIMEOUT_SECONDS + "초 안에 끝나지 않아 강제 종료했습니다. stderr 끝부분: " + tail(err));
        }
        return new CliResult(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
    }

    private static String tail(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 50), lines.size()));
    }

    private int count(String tableAndFilter) {
        return jdbc.sql("select count(*) from " + tableAndFilter).query(Integer.class).single();
    }

    private String single(String sql) {
        return jdbc.sql(sql).query(String.class).optional().orElse(null);
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
