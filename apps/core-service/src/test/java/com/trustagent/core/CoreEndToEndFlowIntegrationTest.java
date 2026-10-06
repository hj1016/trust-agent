package com.trustagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.internalpolicy.bootstrap.SyntheticInternalImporter;
import com.trustagent.core.internalpolicy.proposal.HumanReviewException;
import com.trustagent.core.internalpolicy.proposal.ProposalValidationException;
import com.trustagent.core.publicproduct.baseline.BaselineImporter;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * TASK-011a: 합성 공문 적재(importer 진입점) → 예시 checklist 적재 → 변경안 생성 → 자동 검증 → 사람 결정(승인, 수정)을
 * core README의 demo 설정 키와 ApplicationRunner로 순서대로 실행하고, web 컨텍스트에서 적용 공문 조회와 Tool 조회까지 확인한다.
 * 업무 로직은 바꾸지 않는다. 각 단계는 별도 non-web Spring 컨텍스트(명령 1회 실행)로 띄운다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CoreEndToEndFlowIntegrationTest {

    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
    private static final Instant EVALUATED_AT = Instant.parse("2026-10-05T05:00:00Z");
    private static final String TEMPORARY_TOKEN = "test-" + UUID.randomUUID();
    private static final String PREPAYMENT = "SIN-PREPAYMENT-FEE";
    private static final String SELLER = "SIN-SELLER-CHECKLIST";
    private static final String GENERATION_RUN = "proposal-run:" + "1".repeat(32);
    private static final String VALIDATION_RUN = "validation-run:" + "1".repeat(32);
    private static final String APPROVAL_RUN = "review-run:" + "1".repeat(32);
    private static final String EXPECTED_DECISION = "review-decision:" + "1".repeat(32);

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private Path repositoryRoot;
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private String prepaymentProposalId;
    private String prepaymentValidationId;
    private String prepaymentVersionId;
    private String sellerRevisionId;
    private String sellerVersionId;

    @BeforeAll
    void migrateAndImportThroughImporterEntryPoints() {
        POSTGRES.start();
        repositoryRoot = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        // 1) migration만 수행하는 컨텍스트(명령 없음)
        runCommand(Map.of()).close();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setMaximumPoolSize(2);
        jdbc = JdbcClient.create(dataSource);
        // 2) 적재는 importer 진입점 그대로(사용자 판단: importer 권한 유지)
        var manager = new DataSourceTransactionManager(dataSource);
        new BaselineImporter(jdbc, mapper, manager).importBaseline(repositoryRoot, "baseline:" + "1".repeat(32));
        new SyntheticInternalImporter(jdbc, mapper, manager, "Asia/Seoul").importBaseline(repositoryRoot, "synthetic-import:" + "1".repeat(32));
    }

    @AfterAll
    void stop() {
        if (dataSource != null) dataSource.close();
        POSTGRES.stop();
    }

    // ---- AC-04: 잘못된 순서와 누락 인자는 각 단계가 거부한다 ----

    @Test
    @Order(1)
    void wrongOrderAndMissingSettingsAreRefusedBeforeAnythingIsIssued() {
        // 변경안이 없는데 승인
        Throwable notFound = expectFailure(Map.of(
                "trust-agent.human-review.enabled", "true",
                "trust-agent.human-review.proposal-id", "checklist-proposal:sha256:" + "0".repeat(64),
                "trust-agent.human-review.decision", "APPROVE",
                "trust-agent.human-review.reviewer-id", "SYN-REVIEWER-01"));
        assertEquals("PROPOSAL_NOT_FOUND", code(notFound));
        // 필수 설정 누락: 검증 명령에 proposal-id 없음
        Throwable missing = expectFailure(Map.of("trust-agent.proposal-validation.enabled", "true"));
        assertTrue(rootMessage(missing).contains("trust-agent.proposal-validation.proposal-id"), rootMessage(missing));
        // 예시 checklist가 없으면 변경안 생성은 NO_BASE_CHECKLIST로 실패하고 실패 기록만 남는다
        Throwable noBase = expectFailure(generation(PREPAYMENT, "SIN-PREPAYMENT-FEE-V2", "proposal-run:" + "9".repeat(32)));
        assertEquals("NO_BASE_CHECKLIST", code(noBase));
        assertEquals("FAILED", single("select status from proposal_generation_run where generation_run_id = 'proposal-run:" + "9".repeat(32) + "'"));
        assertEquals(0, count("checklist_change_proposal"));
        assertEquals(0, count("human_review_decision"));
    }

    // ---- AC-01: 예시 적재 → 변경안 생성 → 검증 → 승인을 demo 설정 키로 순서대로 실행 ----

    @Test
    @Order(2)
    void demoCommandsRunTheCoreFlowInOrderAndLeaveRunRecords() {
        CLOCK.set(Instant.parse("2026-10-05T04:00:00Z"));
        runCommand(Map.of(
                "trust-agent.fixture-approved-checklist.enabled", "true",
                "trust-agent.fixture-approved-checklist.root", repositoryRoot.toString(),
                "trust-agent.fixture-approved-checklist.run-id", "checklist-fixture-run:" + "1".repeat(32))).close();
        assertEquals("SUCCEEDED", single("select status from approved_checklist_fixture_run"));
        assertEquals(2, count("approved_checklist_version where origin = 'FIXTURE'"));

        runCommand(generation(PREPAYMENT, "SIN-PREPAYMENT-FEE-V2", GENERATION_RUN)).close();
        prepaymentProposalId = single("select proposal_id from proposal_generation_run where generation_run_id = '" + GENERATION_RUN + "' and status = 'SUCCEEDED'");
        assertNotNull(prepaymentProposalId);
        assertEquals(2, count("checklist_change_proposal_item where proposal_id = '" + prepaymentProposalId + "'"));

        // 검증 전 승인은 거부된다
        assertEquals("VALIDATION_MISSING", code(expectFailure(approval(prepaymentProposalId, null, null, "review-run:" + "8".repeat(32)))));
        assertEquals("FAILED | VALIDATION_MISSING", single("select status || ' | ' || error_code from human_review_run where review_run_id = 'review-run:" + "8".repeat(32) + "'"));

        runCommand(Map.of(
                "trust-agent.proposal-validation.enabled", "true",
                "trust-agent.proposal-validation.proposal-id", prepaymentProposalId,
                "trust-agent.proposal-validation.run-id", VALIDATION_RUN)).close();
        prepaymentValidationId = single("select validation_result_id from validation_run where validation_run_id = '" + VALIDATION_RUN + "' and status = 'SUCCEEDED'");
        assertEquals("PASS", single("select status from automated_validation_result where validation_result_id = '" + prepaymentValidationId + "'"));

        CLOCK.set(Instant.parse("2026-10-05T04:30:00Z"));
        runCommand(approval(prepaymentProposalId, prepaymentValidationId, null, APPROVAL_RUN)).close();
        assertEquals("SUCCEEDED | " + EXPECTED_DECISION, single("select status || ' | ' || decision_id from human_review_run where review_run_id = '" + APPROVAL_RUN + "'"));
        prepaymentVersionId = single("select approved_checklist_version_id from human_review_decision where decision_id = '" + EXPECTED_DECISION + "'");
        assertEquals(3, count("approved_checklist_item where approved_checklist_version_id = '" + prepaymentVersionId + "'"));
        assertEquals("HUMAN_REVIEW", single("select origin from approved_checklist_version where approved_checklist_version_id = '" + prepaymentVersionId + "'"));
        CLOCK.reset();
    }

    // ---- AC-03: 수정(revised-rules-json) → 재검증 WARN → 사유 승인 ----

    @Test
    @Order(3)
    void modifyRevalidateAndApproveThroughDemoCommands() {
        CLOCK.set(Instant.parse("2026-10-05T04:00:00Z"));
        String sellerRun = "proposal-run:" + "2".repeat(32);
        runCommand(generation(SELLER, "SIN-SELLER-CHECKLIST-V2", sellerRun)).close();
        String sellerProposal = single("select proposal_id from proposal_generation_run where generation_run_id = '" + sellerRun + "'");

        // 공문 규칙을 그대로 가져와 CHECK_SETTLEMENT_EVIDENCE의 설명 문구만 고친다
        ArrayNode revised = mapper.createArrayNode();
        jdbc.sql("""
                        select rule.rule_key, rule.instruction, rule.evidence_required, rule.structured_change::text as change
                        from internal_policy_rule_evidence evidence
                        join internal_policy_rule_version rule on rule.rule_version_id = evidence.rule_version_id
                        join policy_extraction_attempt attempt on attempt.extraction_attempt_id = evidence.extraction_attempt_id
                        where attempt.notice_id = 'SIN-SELLER-CHECKLIST-V2' and attempt.status = 'SUCCEEDED'
                        order by evidence.rule_order
                        """)
                .query((rs, row) -> {
                    ObjectNode node = revised.addObject();
                    String key = rs.getString("rule_key");
                    node.put("rule_key", key);
                    node.put("instruction", rs.getString("instruction") + ("CHECK_SETTLEMENT_EVIDENCE".equals(key) ? " (검수자 보완 문구)" : ""));
                    node.put("evidence_required", rs.getBoolean("evidence_required"));
                    node.set("structured_change", mapper.readTree(rs.getString("change")));
                    return node;
                }).list();
        runCommand(decision(sellerProposal, null, "MODIFY", "안내 문구 보완", "review-run:" + "2".repeat(32),
                Map.of("trust-agent.human-review.revised-rules-json", mapper.writeValueAsString(revised)))).close();
        sellerRevisionId = single("select revision_proposal_id from human_review_decision where proposal_id = '" + sellerProposal + "'");
        assertNotNull(sellerRevisionId);
        assertEquals(sellerProposal, single("select supersedes_proposal_id from checklist_change_proposal where proposal_id = '" + sellerRevisionId + "'"));

        String revalidation = "validation-run:" + "2".repeat(32);
        runCommand(Map.of(
                "trust-agent.proposal-validation.enabled", "true",
                "trust-agent.proposal-validation.proposal-id", sellerRevisionId,
                "trust-agent.proposal-validation.run-id", revalidation)).close();
        String resultId = single("select validation_result_id from validation_run where validation_run_id = '" + revalidation + "'");
        assertEquals("WARN", single("select status from automated_validation_result where validation_result_id = '" + resultId + "'"));
        assertEquals("INSTRUCTION_EDITED", single("select code from automated_validation_issue where validation_result_id = '" + resultId + "' and severity = 'WARN'"));

        // 사유 없는 승인은 거부, 사유 있는 승인은 발행
        assertEquals("REASON_REQUIRED", code(expectFailure(approval(sellerRevisionId, resultId, null, "review-run:" + "7".repeat(32)))));
        CLOCK.set(Instant.parse("2026-10-05T04:30:00Z"));
        runCommand(approval(sellerRevisionId, resultId, "원문과 대조함: 공문 뜻을 바꾸지 않는 안내 문구 보완", "review-run:" + "3".repeat(32))).close();
        sellerVersionId = single("select approved_checklist_version_id from human_review_decision where proposal_id = '" + sellerRevisionId + "'");
        assertNotNull(sellerVersionId);
        CLOCK.reset();
    }

    // ---- AC-02, AC-03: web 컨텍스트에서 조회와 Tool이 같은 ID를 돌려주고 고친 문구가 보인다 ----

    @Test
    @Order(4)
    void applicableQueryAndToolReturnTheIssuedChecklistsAndEditedInstruction() throws Exception {
        try (ConfigurableApplicationContext context = runWeb()) {
            int port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));

            JsonNode core = json(get(port, "/api/v1/internal-policy/checklists/" + PREPAYMENT + "/applicable?businessDate=2026-10-01"));
            assertTrue(core.get("internalChecklistUseAllowed").booleanValue(), core.toString());
            assertEquals(prepaymentVersionId, core.get("approvedChecklist").get("approvedChecklistVersionId").stringValue());
            assertEquals(EXPECTED_DECISION, core.get("approvedChecklist").get("decisionId").stringValue());

            JsonNode tool = json(tool(port, "applicable_checklist", Map.of("familyId", PREPAYMENT, "businessDate", "2026-10-01")));
            assertTrue(tool.get("usable").booleanValue(), tool.toString());
            assertEquals(prepaymentVersionId, tool.get("approvedChecklist").get("approvedChecklistVersionId").stringValue());
            assertEquals(EXPECTED_DECISION, tool.get("approvedChecklist").get("decisionId").stringValue());
            assertEquals(3, tool.get("approvedChecklist").get("items").size());
            String ruleVersionId = tool.get("approvedChecklist").get("items").get(0).get("sourceRuleVersionId").stringValue();
            JsonNode evidence = json(tool(port, "rule_evidence", Map.of("familyId", PREPAYMENT, "ruleVersionId", ruleVersionId)));
            assertEquals("CHECK_PREPAYMENT_FEE_RATE", evidence.get("ruleKey").stringValue());

            // 수정 경로: 고친 문구가 조회와 Tool 항목에 보인다
            JsonNode seller = json(get(port, "/api/v1/internal-policy/checklists/" + SELLER + "/applicable?businessDate=2026-10-05"));
            assertTrue(seller.get("internalChecklistUseAllowed").booleanValue(), seller.toString());
            assertEquals(sellerVersionId, seller.get("approvedChecklist").get("approvedChecklistVersionId").stringValue());
            JsonNode sellerTool = json(tool(port, "applicable_checklist", Map.of("familyId", SELLER)));
            assertTrue(sellerTool.get("usable").booleanValue(), sellerTool.toString());
            List<String> instructions = new ArrayList<>();
            sellerTool.get("approvedChecklist").get("items").forEach(item -> instructions.add(item.get("instruction").stringValue()));
            assertTrue(instructions.stream().anyMatch(text -> text.endsWith("(검수자 보완 문구)")), instructions.toString());
            assertTrue(instructions.stream().anyMatch(text -> text.startsWith("매출 정산 내역")), instructions.toString());

            // 승인 전 기간(테스트용 checklist)은 둘 다 사용 불가
            JsonNode fixture = json(tool(port, "applicable_checklist", Map.of("familyId", PREPAYMENT, "businessDate", "2026-09-30")));
            assertFalse(fixture.get("usable").booleanValue());
            assertTrue(fixture.get("approvedChecklist").isNull());
        }
    }

    // ---- AC-05: README의 demo 설정 키가 코드에 전부 있다 ----

    @Test
    @Order(5)
    void readmeDemoSettingKeysAllExistInTheCode() throws Exception {
        String readme = Files.readString(repositoryRoot.resolve("apps/core-service/README.md"));
        Matcher matcher = Pattern.compile("--(trust-agent\\.[a-z-]+\\.[a-z-]+)").matcher(readme);
        List<String> readmeKeys = new ArrayList<>();
        while (matcher.find()) {
            if (!readmeKeys.contains(matcher.group(1))) readmeKeys.add(matcher.group(1));
        }
        assertTrue(readmeKeys.size() >= 12, readmeKeys.toString());
        StringBuilder code = new StringBuilder();
        try (Stream<Path> files = Files.walk(repositoryRoot.resolve("apps/core-service/src/main"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".java") || name.endsWith(".yml")) code.append(Files.readString(file)).append('\n');
            }
        }
        List<String> missing = readmeKeys.stream().filter(key -> !code.toString().contains(key)).toList();
        assertEquals(List.of(), missing, "README에만 있는 설정 키");
    }

    // ---- helpers ----

    private Map<String, Object> generation(String familyId, String noticeId, String runId) {
        return Map.of(
                "trust-agent.proposal-generation.enabled", "true",
                "trust-agent.proposal-generation.family-id", familyId,
                "trust-agent.proposal-generation.target-notice-id", noticeId,
                "trust-agent.proposal-generation.run-id", runId);
    }

    private Map<String, Object> approval(String proposalId, String validationId, String reason, String runId) {
        return decision(proposalId, validationId, "APPROVE", reason, runId, Map.of());
    }

    private Map<String, Object> decision(String proposalId, String validationId, String kind, String reason, String runId, Map<String, Object> extra) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("trust-agent.human-review.enabled", "true");
        props.put("trust-agent.human-review.proposal-id", proposalId);
        props.put("trust-agent.human-review.decision", kind);
        props.put("trust-agent.human-review.reviewer-id", "SYN-REVIEWER-01");
        props.put("trust-agent.human-review.run-id", runId);
        if (validationId != null) props.put("trust-agent.human-review.validation-result-id", validationId);
        if (reason != null) props.put("trust-agent.human-review.reason", reason);
        props.putAll(extra);
        return props;
    }

    private Map<String, Object> baseProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        props.put("spring.datasource.username", POSTGRES.getUsername());
        props.put("spring.datasource.password", POSTGRES.getPassword());
        props.put("spring.flyway.enabled", "true");
        props.put("spring.main.banner-mode", "off");
        props.put("management.server.port", "0");
        props.put("server.port", "0");
        props.put("trust-agent.public-evidence.max-confirmation-age", "30d");
        props.put("trust-agent.tool-api.service-token", TEMPORARY_TOKEN);
        return props;
    }

    /** core README의 bootRun 명령과 같은 조건: web 없이 기동해 ApplicationRunner 1회 실행. */
    private ConfigurableApplicationContext runCommand(Map<String, Object> commandProperties) {
        Map<String, Object> props = baseProperties();
        props.putAll(commandProperties);
        // README의 bootRun --args와 같은 우선순위(명령행 인자)로 넘긴다. builder.properties는 application.yml보다 낮아 쓰지 않는다.
        return new SpringApplicationBuilder(TrustAgentCoreApplication.class, FixedClockConfiguration.class)
                .web(WebApplicationType.NONE)
                .run(arguments(props));
    }

    private ConfigurableApplicationContext runWeb() {
        return new SpringApplicationBuilder(TrustAgentCoreApplication.class, FixedClockConfiguration.class)
                .web(WebApplicationType.SERVLET)
                .run(arguments(baseProperties()));
    }

    private static String[] arguments(Map<String, Object> props) {
        return props.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
    }

    private Throwable expectFailure(Map<String, Object> commandProperties) {
        try (ConfigurableApplicationContext context = runCommand(commandProperties)) {
            throw new AssertionError("명령이 거부되어야 하는데 성공했습니다: " + commandProperties.keySet());
        } catch (AssertionError error) {
            throw error;
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private static String code(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof HumanReviewException review) return review.code();
            if (cause instanceof ProposalValidationException validation) return validation.code();
            if (cause instanceof com.trustagent.core.internalpolicy.proposal.ProposalGenerationException generation) return generation.code();
            cause = cause.getCause();
        }
        return "NO_CODE: " + failure;
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return String.valueOf(cause.getMessage());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> tool(int port, String name, Map<String, String> body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/tools/" + name))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TEMPORARY_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private int count(String tableAndFilter) {
        return jdbc.sql("select count(*) from " + tableAndFilter).query(Integer.class).single();
    }

    private String single(String sql) {
        return jdbc.sql(sql).query(String.class).optional().orElse(null);
    }

    static final class AdjustableClock extends Clock {
        private volatile Instant instant = EVALUATED_AT;
        void set(Instant value) { instant = value; }
        void reset() { instant = EVALUATED_AT; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static final AdjustableClock CLOCK = new AdjustableClock();

    @Configuration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return CLOCK;
        }
    }
}
