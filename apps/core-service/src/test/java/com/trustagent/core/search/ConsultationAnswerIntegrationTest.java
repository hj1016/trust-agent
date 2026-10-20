package com.trustagent.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.control.ControlDataSourceConfiguration;
import com.trustagent.core.preparation.PreparationScenario;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import com.trustagent.core.security.DemoUsers;
import com.trustagent.core.support.SessionClient;
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
 * 상담 화면 추가 질문(Core API → grant → AI 서비스 검색·RAG → Core 재확인 → 감사 기록). 실제 Core·PostgreSQL·Elasticsearch·AI 서비스(uvicorn)를 띄우고,
 * LLM은 로컬 전용 결정론 응답기(fake-echo)를 쓴다. 화면·권한·감사 흐름 검증이며 실제 모델 답변 품질 검증이 아니다.
 */
@SpringBootTest(classes = {TrustAgentCoreApplication.class, ConsultationAnswerIntegrationTest.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "trustAgent.aiServiceIntegration", matches = "true")
class ConsultationAnswerIntegrationTest {

    private static final PreparationScenario.AdjustableClock CLOCK = new PreparationScenario.AdjustableClock();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae").asCompatibleSubstituteFor("postgres"));
    private static final String CONTROL_DB = "trust_agent_control_answer_it";
    private static final String STAFF_PASSWORD = "it-" + UUID.randomUUID();
    private static final String BOTH_PASSWORD = "it-" + UUID.randomUUID();
    private static final String TOOL_TOKEN = "test-tool-" + UUID.randomUUID();
    private static final String RECORD_TOKEN = "test-record-" + UUID.randomUUID();
    private static final String INBOUND_TOKEN = "test-inbound-" + UUID.randomUUID();
    private static final int AI_PORT = freePort();
    private static final String QUESTION = "중도상환수수료율과 적용 조건을 확인해야 하나요?";

    static {
        if ("true".equals(System.getProperty("trustAgent.aiServiceIntegration"))) {
            POSTGRES.start();
            ElasticsearchTestContainer.start();
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("create database " + CONTROL_DB);
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
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
        registry.add(DemoUsers.environmentName(DemoUsers.REVIEWER_USER), () -> "it-" + UUID.randomUUID());
        registry.add(DemoUsers.environmentName(DemoUsers.BOTH_USER), () -> BOTH_PASSWORD);
        registry.add("trust-agent.tool-api.service-token", () -> TOOL_TOKEN);
        registry.add("trust-agent.preparation-record.service-token", () -> RECORD_TOKEN);
        registry.add("trust-agent.ai-grant.require", () -> "true");
        registry.add("trust-agent.ai-service.base-url", () -> "http://127.0.0.1:" + AI_PORT);
        registry.add("trust-agent.ai-service.inbound-token", () -> INBOUND_TOKEN);
        registry.add("trust-agent.ai-service.timeout", () -> "30s");
        registry.add("trust-agent.search.base-url", ElasticsearchTestContainer::baseUrl);
        registry.add("trust-agent.search.username", () -> ElasticsearchTestContainer.REINDEX_USER);
        registry.add("trust-agent.search.password", () -> ElasticsearchTestContainer.REINDEX_PASSWORD);
        registry.add("trust-agent.search.analyzer", () -> ElasticsearchTestContainer.ANALYZER);
        registry.add("trust-agent.search.index-prefix", () -> "trustagent-rule-evidence-answerit");
    }

    @Autowired private DataSource dataSource;
    @Autowired @Qualifier(ControlDataSourceConfiguration.CONTROL_JDBC_CLIENT) private JdbcClient control;
    @Autowired private ObjectMapper mapper;
    @Autowired private PublicProductObservedStateService publicProducts;
    @Autowired private SearchReindexService reindex;
    @Autowired private SearchProperties properties;
    @Value("${local.server.port}") private int port;

    private JdbcClient jdbc;
    private Process aiServer;
    private String consultationId;
    private SessionClient staff;

    @BeforeAll
    void start() throws Exception {
        Path root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        jdbc = JdbcClient.create(dataSource);
        PreparationScenario.load(jdbc, mapper, PreparationScenario.manager(dataSource), CLOCK, publicProducts, root);
        assertEquals(SearchReindexService.Outcome.CREATED, reindex.reindex().outcome());
        Path reports = root.resolve("apps/core-service/build/reports/answer-it");
        Files.createDirectories(reports);
        ProcessBuilder builder = new ProcessBuilder(PreparationScenario.aiServicePython(root).toString(), "-m", "uvicorn", "ai_service.app:app",
                "--host", "127.0.0.1", "--port", Integer.toString(AI_PORT)).directory(root.resolve("apps/ai-service").toFile());
        Map<String, String> environment = builder.environment();
        environment.put("TRUST_AGENT_CORE_BASE_URL", "http://127.0.0.1:" + port);
        environment.put("TRUST_AGENT_TOOL_SERVICE_TOKEN", TOOL_TOKEN);
        environment.put("TRUST_AGENT_AI_INBOUND_TOKEN", INBOUND_TOKEN);
        environment.put("TRUST_AGENT_REPOSITORY_ROOT", root.toString());
        environment.put("TRUST_AGENT_ES_URL", ElasticsearchTestContainer.baseUrl());
        environment.put("TRUST_AGENT_ES_SEARCH_USERNAME", ElasticsearchTestContainer.SEARCH_USER);
        environment.put("TRUST_AGENT_ES_SEARCH_PASSWORD", ElasticsearchTestContainer.SEARCH_PASSWORD);
        environment.put("TRUST_AGENT_SEARCH_INDEX_ALIAS", properties.alias());
        environment.put("TRUST_AGENT_AI_DEPLOYMENT", "local");
        environment.put("TRUST_AGENT_LLM_FAKE", "echo");
        environment.put("PYTHONIOENCODING", "utf-8");
        builder.redirectOutput(reports.resolve("uvicorn.out.txt").toFile()).redirectError(reports.resolve("uvicorn.err.txt").toFile());
        aiServer = builder.start();
        HttpClient http = HttpClient.newHttpClient();
        for (int attempt = 0; attempt < 60; attempt++) {
            if (!aiServer.isAlive()) throw new IllegalStateException("AI 서비스 기동 실패: " + reports.resolve("uvicorn.err.txt"));
            try {
                http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + AI_PORT + "/api/v1/ai/answers"))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                break;
            } catch (IOException ignored) {
                Thread.sleep(500);
            }
        }
        staff = SessionClient.login(port, DemoUsers.STAFF_USER, STAFF_PASSWORD);
        consultationId = mapper.readTree(staff.postJson("/api/v1/consultations", "{\"applicationId\":\"SW-APPLICATION-001\"}").body())
                .get("consultationId").stringValue();
    }

    @AfterAll
    void stop() throws Exception {
        if (aiServer != null) {
            aiServer.destroy();
            if (!aiServer.waitFor(10, TimeUnit.SECONDS)) aiServer.destroyForcibly();
        }
    }

    private JsonNode ask(SessionClient client, String question, String familyId) throws Exception {
        HttpResponse<String> response = client.postJson("/api/v1/consultations/" + consultationId + "/answers",
                mapper.writeValueAsString(Map.of("question", question, "familyId", familyId, "businessDate", "2026-10-06")));
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains("ai-grant:"), "grant ID는 브라우저 응답에 없다");
        assertFalse(response.body().contains(INBOUND_TOKEN) || response.body().contains(TOOL_TOKEN));
        return mapper.readTree(response.body());
    }

    @Test
    void approvedEvidenceAnswerIsShownWithCoreEvidenceAndAuditedWithoutQuestionText() throws Exception {
        JsonNode body = ask(staff, QUESTION, PreparationScenario.PREPAYMENT);
        assertEquals("ANSWERED", body.get("status").stringValue(), body.toString());
        assertEquals("fake-echo", body.get("model").stringValue());
        assertTrue(body.get("evidence").size() > 0);
        JsonNode first = body.get("evidence").get(0);
        assertTrue(first.get("noticeTitle").stringValue().contains("중도상환수수료"), "근거는 Core 자료로 다시 채운다");
        assertTrue(body.get("answer").get("citations").size() > 0);
        JsonNode audit = mapper.readTree(jdbc.sql("select row_to_json(a)::text from consultation_answer_audit a where answer_request_id = :id")
                .param("id", body.get("answerRequestId").stringValue()).query(String.class).single());
        assertEquals("ANSWERED", audit.get("status").stringValue());
        assertTrue(audit.get("question_sha256").stringValue().startsWith("sha256:"));
        assertFalse(audit.toString().contains(QUESTION), "감사 기록에 질문 원문이 없다");
        assertEquals("SYN-STAFF-01", audit.get("requested_by").stringValue());
    }

    @Test
    void decisionRequestIsDeclinedWithoutLlmCall() throws Exception {
        JsonNode body = ask(staff, "이 기업 대출을 승인해도 되나요?", PreparationScenario.PREPAYMENT);
        assertEquals("DECISION_DECLINED", body.get("status").stringValue(), body.toString());
        assertEquals(0, body.get("usage").get("llmCalls").intValue());
        assertTrue(body.get("answer").isNull());
    }

    @Test
    void unapprovedFamilyIsEvidenceHold() throws Exception {
        JsonNode body = ask(staff, "정산 내역 확인 여부를 기록하라는 항목이 있나요?", PreparationScenario.SELLER);
        assertEquals("EVIDENCE_HOLD", body.get("status").stringValue(), body.toString());
        assertEquals(0, body.get("evidence").size());
        assertTrue(body.get("answer").isNull());
    }

    @Test
    void questionWithCustomerIdentifierIsHeldBeforeAiAndGrant() throws Exception {
        int grantsBefore = control.sql("select count(*) from ai_request_grant where consultation_id = :c").param("c", consultationId).query(Integer.class).single();
        JsonNode body = ask(staff, "가상상사 주식회사 신청 건도 0.8%가 적용되나요?", PreparationScenario.PREPAYMENT);
        assertEquals("ANSWER_HOLD", body.get("status").stringValue());
        assertEquals("QUESTION_CONTAINS_IDENTIFIER", body.get("reasons").get(0).stringValue());
        int grantsAfter = control.sql("select count(*) from ai_request_grant where consultation_id = :c").param("c", consultationId).query(Integer.class).single();
        assertEquals(grantsBefore, grantsAfter, "식별 정보가 든 질문은 grant 발급·AI 호출 전에 막는다");
    }

    @Test
    void otherStaffCannotAskOnSomeoneElsesConsultation() throws Exception {
        SessionClient other = SessionClient.login(port, DemoUsers.BOTH_USER, BOTH_PASSWORD);
        HttpResponse<String> response = other.postJson("/api/v1/consultations/" + consultationId + "/answers",
                mapper.writeValueAsString(Map.of("question", QUESTION, "familyId", PreparationScenario.PREPAYMENT, "businessDate", "2026-10-06")));
        assertEquals(404, response.statusCode());
    }

    @Autowired private com.trustagent.core.grant.GrantService grants;
    @Autowired private com.trustagent.core.consultation.ConsultationService consultations;
    @Autowired private com.trustagent.core.internalpolicy.query.InternalPolicyApplicableService applicable;
    @Autowired private com.trustagent.core.ai.AiServiceProperties aiProperties;

    /** AI 응답을 바꿔 끼운 서비스(Core 재확인 검증용). AI가 무엇을 돌려줘도 Core가 승인 상태로 다시 확인해야 한다. */
    private com.trustagent.core.consultation.ConsultationAnswerService withAiResponse(int status, String body) {
        com.trustagent.core.ai.AiServiceClient fake = new com.trustagent.core.ai.AiServiceClient(aiProperties, mapper) {
            @Override
            public Response answer(String question, String familyId, String businessDate, String consultation, String grantId, String traceId) {
                return new Response(status, body);
            }
        };
        return new com.trustagent.core.consultation.ConsultationAnswerService(grants, fake, applicable, jdbc, mapper, CLOCK);
    }

    private JsonNode askWith(com.trustagent.core.consultation.ConsultationAnswerService service) {
        var view = consultations.findOwned(consultationId, DemoUsers.STAFF_USER, com.trustagent.core.security.ActiveRole.DEFAULT_WORKSPACE).orElseThrow();
        return service.answer(view, new com.trustagent.core.consultation.ConsultationAnswerService.Request(QUESTION, PreparationScenario.PREPAYMENT,
                java.time.LocalDate.parse("2026-10-06")), DemoUsers.STAFF_USER, "STAFF", "trace-answer-it");
    }

    @Test
    void coreRecheckRejectsUnapprovedOrForeignEvidenceFromAi() {
        // 셀러론 v2 규칙(검토 대기, 미승인). 수수료 공문군 승인 항목이 아니므로 Core 재확인에서 막혀야 한다.
        String seller = jdbc.sql("""
                select e.rule_version_id from internal_policy_rule_evidence e where e.notice_id = 'SIN-SELLER-CHECKLIST-V2' order by e.rule_version_id limit 1""")
                .query(String.class).single();
        String tampered = """
                {"status":"ANSWERED","reasons":[],"evidence":[{"rule_version_id":"%s"}],
                 "answer":{"explanation":"x","citations":["%s"],"conditions":[],"exceptions":[],"needs_check":[]},
                 "llm_calls":2,"usage":{"input_tokens":10,"output_tokens":5,"charged_usd_estimate":0.0},"model":"tampered"}""".formatted(seller, seller);
        JsonNode body = askWith(withAiResponse(200, tampered));
        assertEquals("ANSWER_HOLD", body.get("status").stringValue(), body.toString());
        assertTrue(body.get("reasons").toString().contains("CORE_RECHECK_FAILED"));
        assertTrue(body.get("answer").isNull(), "재확인 실패면 설명을 내보내지 않는다");
        assertEquals(0, body.get("evidence").size(), "승인되지 않은 근거는 보여 주지 않는다");
    }

    @Test
    void coreRejectsSuccessWithEmptyEvidenceOrMalformedAnswer() {
        String rule = applicable.get(PreparationScenario.PREPAYMENT, "2026-10-06", null)
                .approvedChecklist().items().getFirst().sourceRuleVersionId();
        for (String candidate : java.util.List.of(
                "{\"status\":\"ANSWERED\",\"evidence\":[],\"answer\":{\"explanation\":\"근거 없는 설명\",\"citations\":[],\"conditions\":[],\"exceptions\":[],\"needs_check\":[]}}",
                "{\"status\":\"ANSWERED\",\"evidence\":[{\"rule_version_id\":\"%s\"}],\"answer\":null}".formatted(rule),
                "{\"status\":\"ANSWERED\",\"evidence\":[{\"rule_version_id\":\"%s\"}],\"answer\":{\"explanation\":\"x\",\"citations\":[\"%s\"],\"conditions\":\"invalid\",\"exceptions\":[],\"needs_check\":[]}}".formatted(rule, rule))) {
            JsonNode body = askWith(withAiResponse(200, candidate));
            assertEquals("ANSWER_HOLD", body.path("status").stringValue(), body.toString());
            assertTrue(body.path("answer").isNull());
            assertEquals("ANSWER_HOLD", jdbc.sql("select status from consultation_answer_audit where answer_request_id = :id")
                    .param("id", body.path("answerRequestId").stringValue()).query(String.class).single());
        }
    }

    @Test
    void aiFailureIsReportedAsUnavailableAndAudited() {
        for (var failure : java.util.List.of(java.util.Map.entry(503, "{}"), java.util.Map.entry(200, "not json"), java.util.Map.entry(200, "{\"status\":\"WHATEVER\"}"))) {
            JsonNode body = askWith(withAiResponse(failure.getKey(), failure.getValue()));
            assertEquals("AI_UNAVAILABLE", body.get("status").stringValue(), body.toString());
            assertTrue(body.get("answer").isNull());
            assertEquals(1, jdbc.sql("select count(*) from consultation_answer_audit where answer_request_id = :id")
                    .param("id", body.get("answerRequestId").stringValue()).query(Integer.class).single());
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
