package com.trustagent.core.preparation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * TASK-015 AC-06(전체 READY): 별도 환경에서 두 필수 공문군(중도상환수수료·셀러론)을 모두 승인한 뒤 전체 READY 준비안이 기록되는지 본다.
 * 기존 PARTIAL·HOLD 사례(ConsultationPreparationIntegrationTest, AiServicePreparationIntegrationTest)는 고정 조건 그대로다.
 */
@SpringBootTest(
        classes = {TrustAgentCoreApplication.class, ConsultationPreparationReadyIntegrationTest.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsultationPreparationReadyIntegrationTest {

    private static final String TOOL_TOKEN = "test-tool-" + UUID.randomUUID();
    private static final String RECORD_TOKEN = "test-record-" + UUID.randomUUID();
    private static final PreparationScenario.AdjustableClock CLOCK = new PreparationScenario.AdjustableClock();
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
    private String mappingHash;
    private String sellerDecisionId;
    private String readyPreparationId;

    @BeforeAll
    void loadScenarioWithBothFamiliesApproved() {
        jdbc = JdbcClient.create(dataSource);
        root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        var manager = PreparationScenario.manager(dataSource);
        PreparationScenario.State state = PreparationScenario.load(jdbc, mapper, manager, CLOCK, publicProducts, root);
        mappingHash = state.mappingHash();
        sellerDecisionId = PreparationScenario.approveSeller(jdbc, mapper, manager, CLOCK, state.sellerProposalId());
    }

    // ---- AC-06: 두 섹션 모두 READY → 전체 READY, preparation_complete=true, 필수 보류 0 ----

    @Test
    @Order(1)
    void fullyReadyPreparationIsRecorded() throws Exception {
        ObjectNode body = readyBody();
        assertEquals("READY", body.get("status").stringValue());
        HttpResponse<String> response = record(body, RECORD_TOKEN);
        assertEquals(201, response.statusCode(), response.body());
        readyPreparationId = body.get("preparation_id").stringValue();
        assertEquals("READY | true | 2 | 0", single("select status || ' | ' || preparation_complete || ' | ' || section_count || ' | ' || required_hold_count from consultation_preparation where preparation_id = '" + readyPreparationId + "'"));
        assertEquals("READY,READY | true,true", single("select string_agg(status, ',' order by family_id) || ' | ' || string_agg(recheck_usable::text, ',' order by family_id) from consultation_preparation_section where preparation_id = '" + readyPreparationId + "'"));
        assertEquals(sellerDecisionId, single("select decision_id from consultation_preparation_section where preparation_id = '" + readyPreparationId + "' and family_id = '" + PreparationScenario.SELLER + "'"));
        assertTrue(count("consultation_preparation_section where preparation_id = '" + readyPreparationId + "' and family_id = '" + PreparationScenario.SELLER + "' and jsonb_array_length(item_rule_version_ids) >= 1") == 1);
        assertEquals("RECORDED", single("select outcome from consultation_preparation_run where preparation_id = '" + readyPreparationId + "'"));
        assertEquals(2, count("consultation_preparation_run r, jsonb_array_elements(r.section_evaluations) e where r.preparation_id = '" + readyPreparationId + "' and e->>'recheck_usable' = 'true'"));
    }

    // ---- 전체 READY라도 기록은 사용 허가가 아니다: 상태를 거짓으로 낮추거나 높인 주장은 거부 ----

    @Test
    @Order(2)
    void readyClaimMustMatchComputedStatus() throws Exception {
        ObjectNode partialClaim = readyBody();
        partialClaim.put("status", "PARTIAL");
        partialClaim.put("preparation_complete", false);
        partialClaim.put("preparation_id", PreparationScenario.preparationId(mapper, partialClaim));
        HttpResponse<String> response = record(partialClaim, RECORD_TOKEN);
        assertEquals(422, response.statusCode(), response.body());
        assertEquals("PREPARATION_STATUS_INVALID", mapper.readTree(response.body()).get("code").stringValue());
        assertEquals(1, count("consultation_preparation"));
    }

    // ---- 연결 검증(요청·필수 조건에서만): CLI가 전체 READY 준비안을 만들고 기록한다 ----

    @Test
    @Order(3)
    void prepareCommandProducesFullyReadyPreparation() throws Exception {
        Path python = PreparationScenario.aiServicePython(root);
        Path reports = root.resolve("apps/core-service/build/reports/ai-service");
        Files.createDirectories(reports);
        List<String> command = List.of(python.toString(), "-m", "ai_service", "prepare",
                "--application", PreparationScenario.APPLICATION, "--business-date", "2026-10-06",
                "--consultation-id", "e2e-ready", "--repository-root", root.toString());
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.resolve("apps/ai-service").toFile());
        builder.environment().put("TRUST_AGENT_CORE_BASE_URL", "http://127.0.0.1:" + port);
        builder.environment().put("TRUST_AGENT_TOOL_SERVICE_TOKEN", TOOL_TOKEN);
        builder.environment().put("TRUST_AGENT_PREPARATION_RECORD_TOKEN", RECORD_TOKEN);
        builder.environment().put("TRUST_AGENT_CORE_TIMEOUT_SECONDS", "5");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Path out = reports.resolve("ready.out.txt");
        Path err = reports.resolve("ready.err.txt");
        builder.redirectOutput(out.toFile()).redirectError(err.toFile());
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            org.junit.jupiter.api.Assertions.fail("AI 서비스 명령이 60초 안에 끝나지 않았습니다.");
        }
        assertEquals(0, process.exitValue(), Files.readString(err, StandardCharsets.UTF_8));
        JsonNode preparation = mapper.readTree(Files.readString(out, StandardCharsets.UTF_8));
        assertEquals("READY", preparation.get("status").stringValue());
        assertTrue(preparation.get("preparation_complete").booleanValue());
        assertTrue(preparation.get("headline").stringValue().contains("기준 자료 준비 완료"));
        assertTrue(preparation.get("headline").stringValue().contains("상담·대출 결정이 끝났다는 뜻이 아닙니다"));
        assertTrue(preparation.get("notices").get("staff_check_notice").stringValue().contains("고객별 적용 조건과 제출서류는 직원 확인이 필요합니다"));
        assertFalse(preparation.get("notices").get("staff_check_notice").stringValue().contains("끝나지 않았습니다"));
        assertFalse(preparation.get("headline").stringValue().contains("끝나지 않았습니다"));
        assertEquals(0, preparation.get("remaining_checks").size());
        assertEquals("READY,READY", preparation.get("sections").get(0).get("status").stringValue() + "," + preparation.get("sections").get(1).get("status").stringValue());
        assertTrue(preparation.get("sections").get(1).get("items").size() >= 1);
        assertEquals("RECORDED", preparation.get("record").get("status").stringValue());
        assertTrue(preparation.get("record").get("recorded").booleanValue());
        assertTrue(preparation.get("notices").get("human_decision_notice").stringValue().contains("담당자"));
        assertEquals("READY | true", single("select status || ' | ' || preparation_complete from consultation_preparation where preparation_id = '" + preparation.get("preparation_id").stringValue() + "'"));
    }

    // ---- helpers ----

    private ObjectNode readyBody() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("run_id", "consultation-preparation-run:" + UUID.randomUUID().toString().replace("-", ""));
        body.put("assembler_version", "preparation-assembler-v1");
        body.put("messages_hash", "sha256:" + "e".repeat(64));
        body.put("family_mapping_hash", mappingHash);
        ObjectNode application = body.putObject("application");
        application.put("application_id", PreparationScenario.APPLICATION);
        application.put("company_id", "SW-COMPANY-001");
        application.put("product_key", "kb-seller-loan");
        application.put("source_hash", jdbc.sql("select source_hash from synthetic_work_application where application_id = :id").param("id", PreparationScenario.APPLICATION).query(String.class).single()); // Core에 등록된 원본 해시(A안)
        body.put("business_date", "2026-10-06");
        body.put("status", "READY");
        body.put("preparation_complete", true);
        ArrayNode sections = body.putArray("sections");
        for (String family : List.of(PreparationScenario.PREPAYMENT, PreparationScenario.SELLER)) {
            JsonNode checklist = mapper.readTree(tool("applicable_checklist", Map.of("familyId", family, "businessDate", "2026-10-06")).body());
            assertTrue(checklist.get("usable").booleanValue(), family + " " + checklist);
            ObjectNode section = sections.addObject();
            section.put("family_id", family);
            section.put("required", true);
            section.put("status", "READY");
            section.putNull("hold_kind");
            section.putNull("hold_claim_basis");
            section.put("evaluated_at", checklist.get("evaluatedAt").stringValue());
            section.put("selected_notice_id", checklist.get("selectedNotice").get("noticeId").stringValue());
            JsonNode approved = checklist.get("approvedChecklist");
            section.put("approved_checklist_version_id", approved.get("approvedChecklistVersionId").stringValue());
            section.put("decision_id", approved.get("decisionId").stringValue());
            ArrayNode ruleIds = section.putArray("item_rule_version_ids");
            ArrayNode evidenceHashes = section.putArray("item_evidence_hashes");
            for (JsonNode item : approved.get("items")) {
                String ruleId = item.get("sourceRuleVersionId").stringValue();
                JsonNode evidence = mapper.readTree(tool("rule_evidence", Map.of("familyId", family, "ruleVersionId", ruleId)).body());
                ruleIds.add(ruleId);
                evidenceHashes.add(evidence.get("evidenceHash").stringValue());
            }
            section.putArray("blocking_reasons");
            section.put("tool_response_hash", "sha256:" + "1".repeat(64));
        }
        body.put("preparation_id", PreparationScenario.preparationId(mapper, body));
        return body;
    }

    private HttpResponse<String> record(JsonNode body, String token) throws Exception {
        return send("/api/v1/consultation-preparations", body, token);
    }

    private HttpResponse<String> tool(String name, Map<String, String> body) throws Exception {
        return send("/api/v1/tools/" + name, mapper.valueToTree(body), TOOL_TOKEN);
    }

    private HttpResponse<String> send(String path, JsonNode body, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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
