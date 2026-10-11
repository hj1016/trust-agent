package com.trustagent.core.consultation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.control.ControlDataSourceConfiguration;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * TASK-017b 화면용 API: 신청 목록, 상담 건 응답 확장, 상담 건별 최신 준비안(같은 신청의 여러 상담 건 분리), 직원 확인 기록(READY 섹션 단위,
 * 기록 직전 재확인, 거부 경계), 화면 경로 허용과 API 규칙 유지. 기록 경로는 require-grant=false(로컬 CLI 모드)로 직접 호출한다.
 */
@SpringBootTest(classes = {TrustAgentCoreApplication.class, ConsultationWorkspaceIntegrationTest.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsultationWorkspaceIntegrationTest {

    private static final PreparationScenario.AdjustableClock CLOCK = new PreparationScenario.AdjustableClock();
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
    private static final String CONTROL_DB = "trust_agent_control_workspace_test";
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
        registry.add("trust-agent.ai-grant.require", () -> "false");
    }

    @Autowired private DataSource dataSource;
    @Autowired @Qualifier(ControlDataSourceConfiguration.CONTROL_JDBC_CLIENT) private JdbcClient control;
    @Autowired private ObjectMapper mapper;
    @Autowired private PublicProductObservedStateService publicProducts;
    @Value("${local.server.port}") private int port;

    private JdbcClient jdbc;

    @BeforeAll
    void loadScenario() {
        jdbc = JdbcClient.create(dataSource);
        PreparationScenario.load(jdbc, mapper, PreparationScenario.manager(dataSource), CLOCK, publicProducts,
                Path.of(System.getProperty("trustAgent.repositoryRoot")));
    }

    // ---- 신청 목록과 상담 건 응답 ----

    @Test
    void applicationListKeepsRoleRules() throws Exception {
        HttpResponse<String> staff = staff().get("/api/v1/applications");
        assertEquals(200, staff.statusCode(), staff.body());
        JsonNode application = mapper.readTree(staff.body()).get("applications").get(0);
        assertEquals("SW-APPLICATION-001", application.get("applicationId").stringValue());
        assertFalse(application.get("companyName").stringValue().isBlank());
        assertTrue(application.get("requestedAmountKrw").longValue() > 0);
        assertEquals(403, SessionClient.login(port, DemoUsers.REVIEWER_USER, REVIEWER_PASSWORD).get("/api/v1/applications").statusCode(), "STAFF 활성만");
        HttpResponse<String> anonymous = SessionClient.anonymous(port).get("/api/v1/applications");
        assertEquals(401, anonymous.statusCode());
        assertEquals("UNAUTHENTICATED", mapper.readTree(anonymous.body()).get("code").stringValue());
    }

    @Test
    void consultationViewIncludesApplicationSummaryAndFamilies() throws Exception {
        SessionClient staff = staff();
        JsonNode created = mapper.readTree(staff.postJson("/api/v1/consultations", "{\"applicationId\":\"SW-APPLICATION-001\"}").body());
        JsonNode detail = mapper.readTree(staff.get("/api/v1/consultations/" + created.get("consultationId").stringValue()).body());
        assertEquals("SW-APPLICATION-001", detail.get("application").get("applicationId").stringValue());
        assertFalse(detail.get("application").get("companyName").stringValue().isBlank());
        List<String> families = new ArrayList<>();
        detail.get("families").forEach(family -> families.add(family.get("familyId").stringValue() + ":" + family.get("required").booleanValue()));
        assertEquals(List.of("SIN-PREPAYMENT-FEE:true", "SIN-SELLER-CHECKLIST:true"), families);
        JsonNode listed = mapper.readTree(staff.get("/api/v1/consultations").body()).get("consultations").get(0);
        assertTrue(listed.has("application") && listed.has("families"));
    }

    // ---- 상담 건별 준비안: 같은 신청의 여러 상담 건 ----

    @Test
    void preparationAndConfirmationsStayWithTheirOwnConsultation() throws Exception {
        SessionClient staff = staff();
        String first = createConsultation(staff);
        String second = createConsultation(staff);
        String third = createConsultation(staff);
        HttpResponse<String> none = staff.get("/api/v1/consultations/" + first + "/preparation");
        assertEquals(404, none.statusCode());
        assertEquals("PREPARATION_NOT_FOUND", mapper.readTree(none.body()).get("code").stringValue());

        ObjectNode body = recordBody(first);
        HttpResponse<String> recorded = record(body);
        assertTrue(recorded.statusCode() == 201 || recorded.statusCode() == 200, recorded.body());
        String preparationId = body.get("preparation_id").stringValue();
        JsonNode firstPreparation = mapper.readTree(staff.get("/api/v1/consultations/" + first + "/preparation").body());
        assertEquals(first, firstPreparation.get("consultationId").stringValue());
        assertEquals(preparationId, firstPreparation.get("preparationId").stringValue());
        assertEquals("PARTIAL", firstPreparation.get("status").stringValue());
        assertEquals(List.of("READY", "HOLD"), statuses(firstPreparation));

        // 같은 신청·같은 내용이라 준비안 ID가 같아도 두 번째 상담 건에는 연결되지 않는다.
        assertEquals(404, staff.get("/api/v1/consultations/" + second + "/preparation").statusCode(), "다른 상담 건의 준비안을 보이지 않는다");
        ObjectNode secondBody = body.deepCopy();
        secondBody.put("consultation_id", second);
        secondBody.put("run_id", "consultation-preparation-run:" + UUID.randomUUID().toString().replace("-", ""));
        HttpResponse<String> again = record(secondBody);
        assertEquals(200, again.statusCode(), again.body());
        assertEquals("ALREADY_RECORDED", mapper.readTree(again.body()).get("status").stringValue());
        JsonNode secondPreparation = mapper.readTree(staff.get("/api/v1/consultations/" + second + "/preparation").body());
        assertEquals(second, secondPreparation.get("consultationId").stringValue());
        assertEquals(preparationId, secondPreparation.get("preparationId").stringValue());

        // 확인 기록은 상담 건마다 따로이며, 연결되지 않은 상담 건에서 그 준비안을 쓸 수 없다.
        HttpResponse<String> unlinked = confirm(staff, third, preparationId, PreparationScenario.PREPAYMENT, readyRules(firstPreparation));
        assertEquals(404, unlinked.statusCode(), unlinked.body());
        assertEquals("PREPARATION_NOT_FOUND", mapper.readTree(unlinked.body()).get("code").stringValue());
        assertEquals(201, confirm(staff, first, preparationId, PreparationScenario.PREPAYMENT, readyRules(firstPreparation)).statusCode());
        assertEquals(0, mapper.readTree(staff.get("/api/v1/consultations/" + second + "/confirmations").body()).get("confirmations").size(),
                "첫 상담 건의 확인 기록이 두 번째 상담 건에 보이지 않는다");
        assertEquals(201, confirm(staff, second, preparationId, PreparationScenario.PREPAYMENT, readyRules(secondPreparation)).statusCode(),
                "두 번째 상담 건은 자기 확인을 따로 남긴다");

        // 다른 사용자: 상담 건 존재를 숨긴다.
        SessionClient other = SessionClient.login(port, DemoUsers.BOTH_USER, BOTH_PASSWORD);
        assertEquals(404, other.get("/api/v1/consultations/" + first + "/preparation").statusCode());
        assertEquals(404, other.get("/api/v1/consultations/" + first + "/confirmations").statusCode());
        assertEquals(404, confirm(other, first, preparationId, PreparationScenario.PREPAYMENT, readyRules(firstPreparation)).statusCode());
    }

    // ---- 직원 확인 기록 ----

    @Test
    void confirmationRecordsTheCheckedChecklistAfterRecheckAndRejectsBoundaries() throws Exception {
        SessionClient staff = staff();
        String consultationId = createConsultation(staff);
        ObjectNode body = recordBody(consultationId);
        record(body);
        JsonNode preparation = mapper.readTree(staff.get("/api/v1/consultations/" + consultationId + "/preparation").body());
        String preparationId = preparation.get("preparationId").stringValue();
        List<String> rules = readyRules(preparation);
        String path = "/api/v1/consultations/" + consultationId + "/confirmations";

        assertProblem(confirm(staff, consultationId, preparationId, PreparationScenario.SELLER, List.of()), 409, "SECTION_ON_HOLD");
        assertProblem(confirm(staff, consultationId, preparationId, PreparationScenario.PREPAYMENT, rules.subList(0, 1)), 422, "CONFIRMATION_INCOMPLETE");
        List<String> withUnknown = new ArrayList<>(rules);
        withUnknown.set(0, "policy-rule:sha256:" + "0".repeat(64));
        assertProblem(confirm(staff, consultationId, preparationId, PreparationScenario.PREPAYMENT, withUnknown), 422, "CONFIRMATION_MISMATCH");
        assertProblem(confirm(staff, consultationId, preparationId, "SIN-NOT-IN-PREPARATION", rules), 422, "SECTION_NOT_IN_PREPARATION");
        HttpResponse<String> noCsrf = staff.postJsonWithoutCsrf(path, confirmationBody(preparationId, PreparationScenario.PREPAYMENT, rules));
        assertEquals(403, noCsrf.statusCode());
        SessionClient both = SessionClient.login(port, DemoUsers.BOTH_USER, BOTH_PASSWORD);
        String bothConsultation = createConsultation(both);
        both.postJson("/api/v1/session/active-role", "{\"role\":\"REVIEWER\"}");
        assertEquals(403, confirm(both, bothConsultation, preparationId, PreparationScenario.PREPAYMENT, rules).statusCode(), "REVIEWER 활성은 확인 불가");

        // 기록 직전 재확인: 현재 시각에서 승인 checklist를 쓸 수 없으면 거부하고 행을 남기지 않는다.
        try {
            CLOCK.set(Instant.parse("2026-10-05T04:00:00Z"));
            assertProblem(confirm(staff, consultationId, preparationId, PreparationScenario.PREPAYMENT, rules), 409, "PREPARATION_STALE");
        } finally {
            CLOCK.reset();
        }
        assertEquals(0, rows(consultationId));

        HttpResponse<String> created = confirm(staff, consultationId, preparationId, PreparationScenario.PREPAYMENT, rules);
        assertEquals(201, created.statusCode(), created.body());
        JsonNode confirmation = mapper.readTree(created.body());
        JsonNode section = preparation.get("sections").get(0);
        assertEquals(section.get("approvedChecklistVersionId").stringValue(), confirmation.get("approvedChecklistVersionId").stringValue());
        assertEquals(section.get("decisionId").stringValue(), confirmation.get("decisionId").stringValue());
        assertEquals(section.get("selectedNoticeId").stringValue(), confirmation.get("selectedNoticeId").stringValue());
        assertEquals(rules, strings(confirmation.get("confirmedRuleVersionIds")));
        assertEquals(strings(section.get("itemEvidenceHashes")), strings(confirmation.get("confirmedEvidenceHashes")));
        assertEquals(DemoUsers.STAFF_USER, confirmation.get("confirmedBy").stringValue());
        assertEquals("STAFF", confirmation.get("activeRole").stringValue());
        assertEquals(PreparationScenario.EVALUATED_AT.toString(), confirmation.get("confirmedAt").stringValue());
        assertFalse(confirmation.get("recheckEvaluatedAt").stringValue().isBlank());
        // 확인 기록 필드는 근거 확인 사실뿐이다. 대출 승인·거절, 금리·한도, 신용등급, 준비 완료 같은 결정 값 필드는 없다.
        List<String> fields = new ArrayList<>();
        confirmation.propertyNames().forEach(fields::add);
        assertEquals(List.of("confirmationId", "consultationId", "preparationId", "familyId", "businessDate", "selectedNoticeId",
                "approvedChecklistVersionId", "decisionId", "confirmedRuleVersionIds", "confirmedEvidenceHashes", "confirmedBy", "activeRole",
                "recheckEvaluatedAt", "confirmedAt"), fields);
        assertProblem(confirm(staff, consultationId, preparationId, PreparationScenario.PREPAYMENT, rules), 409, "ALREADY_CONFIRMED");
        assertEquals(1, rows(consultationId));
        assertEquals(1, mapper.readTree(staff.get(path).body()).get("confirmations").size());
    }

    // ---- 화면 경로와 API 규칙 ----

    @Test
    void screenRoutesArePublicButApiRulesAreUnchanged() throws Exception {
        SessionClient anonymous = SessionClient.anonymous(port);
        for (String screen : List.of("/", "/login", "/consultations", "/consultations/consultation:abc", "/reviews")) {
            assertNotEquals(401, anonymous.get(screen).statusCode(), "화면 경로는 로그인 없이 열린다(데이터 없음): " + screen);
        }
        for (String api : List.of("/api/v1/consultations", "/api/v1/applications", "/api/v1/reviews/proposals", "/api/v1/session",
                "/api/v1/consultations/consultation:abc/preparation")) {
            assertEquals(401, anonymous.get(api).statusCode(), api);
        }
        assertEquals(401, anonymous.get("/unknown-screen").statusCode(), "나열하지 않은 경로는 열지 않는다");
    }

    // ---- helpers ----

    private SessionClient staff() throws Exception {
        return SessionClient.login(port, DemoUsers.STAFF_USER, STAFF_PASSWORD);
    }

    private String createConsultation(SessionClient client) throws Exception {
        HttpResponse<String> created = client.postJson("/api/v1/consultations", "{\"applicationId\":\"SW-APPLICATION-001\"}");
        assertEquals(201, created.statusCode(), created.body());
        return mapper.readTree(created.body()).get("consultationId").stringValue();
    }

    private HttpResponse<String> confirm(SessionClient client, String consultationId, String preparationId, String familyId, List<String> rules) throws Exception {
        return client.postJson("/api/v1/consultations/" + consultationId + "/confirmations", confirmationBody(preparationId, familyId, rules));
    }

    private String confirmationBody(String preparationId, String familyId, List<String> rules) {
        ObjectNode body = mapper.createObjectNode();
        body.put("preparationId", preparationId);
        body.put("familyId", familyId);
        rules.forEach(body.putArray("ruleVersionIds")::add);
        return mapper.writeValueAsString(body);
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, mapper.readTree(response.body()).get("code").stringValue());
    }

    private int rows(String consultationId) {
        return jdbc.sql("select count(*) from consultation_confirmation where consultation_id = :c").param("c", consultationId).query(Integer.class).single();
    }

    private List<String> statuses(JsonNode preparation) {
        List<String> values = new ArrayList<>();
        preparation.get("sections").forEach(section -> values.add(section.get("status").stringValue()));
        return values;
    }

    private List<String> readyRules(JsonNode preparation) {
        return strings(preparation.get("sections").get(0).get("itemRuleVersionIds"));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.stringValue()));
        return values;
    }

    private HttpResponse<String> record(ObjectNode body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/consultation-preparations"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + RECORD_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode tool(String name, String body) throws Exception {
        return mapper.readTree(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tools/" + name))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + TOOL_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    /** Tool 1·2 실제 응답으로 PARTIAL(수수료 READY, 셀러론 HOLD) 기록 본문을 만든다(TASK-015 계약). */
    private ObjectNode recordBody(String consultationId) throws Exception {
        JsonNode checklist = tool("applicable_checklist", "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"businessDate\":\"2026-10-06\",\"consultationId\":\"" + consultationId + "\"}");
        assertTrue(checklist.get("usable").booleanValue(), checklist.toString());
        ObjectNode body = mapper.createObjectNode();
        body.put("run_id", "consultation-preparation-run:" + UUID.randomUUID().toString().replace("-", ""));
        body.put("assembler_version", "preparation-assembler-v1");
        body.put("messages_hash", "sha256:" + "1".repeat(64));
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
            hashes.add(tool("rule_evidence", "{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"ruleVersionId\":\"" + ruleId + "\",\"consultationId\":\"" + consultationId + "\"}")
                    .get("evidenceHash").stringValue());
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
        body.put("preparation_id", PreparationScenario.preparationId(mapper, body));
        return body;
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
