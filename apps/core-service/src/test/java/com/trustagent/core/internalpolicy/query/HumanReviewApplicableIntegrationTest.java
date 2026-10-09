package com.trustagent.core.internalpolicy.query;

import org.springframework.beans.factory.annotation.Qualifier;
import com.trustagent.core.support.SessionClient;
import com.trustagent.core.control.ControlDataSourceConfiguration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.internalpolicy.bootstrap.SyntheticInternalImporter;
import com.trustagent.core.internalpolicy.proposal.FixtureApprovedChecklistLoader;
import com.trustagent.core.internalpolicy.proposal.HumanReviewService;
import com.trustagent.core.internalpolicy.proposal.ProposalGenerationService;
import com.trustagent.core.internalpolicy.proposal.ProposalValidationService;
import com.trustagent.core.publicproduct.baseline.BaselineImporter;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TASK-007 AC-02, 03, 04, 13: 실제 승인 경로로 발행한 checklist가 적용 공문 조회에서 언제 사용 가능해지고 언제 계속 차단되는가.
 * 공개 근거 유효 기간은 30일로 둔다(공개 관측이 2026-09-21이라 24h 정책에서는 셀러론을 승인할 수 없다).
 */
@SpringBootTest(
        classes = {TrustAgentCoreApplication.class, HumanReviewApplicableIntegrationTest.FixedClockConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HumanReviewApplicableIntegrationTest {

    private static final Instant EVALUATED_AT = Instant.parse("2026-10-05T03:00:00Z");
    private static final Instant VALIDATED_AT = Instant.parse("2026-10-05T04:00:00Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-10-05T04:30:00Z");
    private static final String HASH = "sha256:" + "b".repeat(64);
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
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PublicProductObservedStateService publicProducts;

    @Value("${local.server.port}")
    private int port;

    private String prepaymentVersionId;
    private String prepaymentDecisionId;
    private String prepaymentValidationId;
    private String sellerVersionId;

    @BeforeAll
    void importAndApproveThroughTheRealPath() throws Exception {
        Path root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        new BaselineImporter(jdbc, objectMapper, manager).importBaseline(root, "baseline:" + "4".repeat(32));
        new SyntheticInternalImporter(jdbc, objectMapper, manager, "Asia/Seoul").importBaseline(root, "synthetic-import:" + "4".repeat(32));
        new FixtureApprovedChecklistLoader(jdbc, objectMapper, manager, CLOCK).load(root, "checklist-fixture-run:" + "4".repeat(32));

        CLOCK.set(VALIDATED_AT);
        var generation = new ProposalGenerationService(jdbc, objectMapper, manager, CLOCK);
        var validation = new ProposalValidationService(jdbc, objectMapper, manager, CLOCK, publicProducts);
        String prepayment = generation.generate(new ProposalGenerationService.Request("SIN-PREPAYMENT-FEE", "SIN-PREPAYMENT-FEE-V2", null, "proposal-generator-v1")).proposalId();
        String seller = generation.generate(new ProposalGenerationService.Request("SIN-SELLER-CHECKLIST", "SIN-SELLER-CHECKLIST-V2", null, "proposal-generator-v1")).proposalId();
        var prepaymentPass = validation.validate(new ProposalValidationService.Request(prepayment, null, "proposal-validator-v1"));
        var sellerPass = validation.validate(new ProposalValidationService.Request(seller, null, "proposal-validator-v1"));
        assertEquals("PASS", prepaymentPass.status().name());
        assertEquals("PASS", sellerPass.status().name());
        prepaymentValidationId = prepaymentPass.validationResultId();

        CLOCK.set(APPROVED_AT);
        var review = new HumanReviewService(jdbc, objectMapper, manager, CLOCK, Duration.ofHours(24));
        var prepaymentApproval = review.decide(new HumanReviewService.Request(
                prepayment, prepaymentPass.validationResultId(), HumanReviewService.Decision.APPROVE, "SYN-REVIEWER-01", null, List.of(), null));
        var sellerApproval = review.decide(new HumanReviewService.Request(
                seller, sellerPass.validationResultId(), HumanReviewService.Decision.APPROVE, "SYN-REVIEWER-01", null, List.of(), null));
        prepaymentVersionId = prepaymentApproval.approvedChecklistVersionId();
        prepaymentDecisionId = prepaymentApproval.decisionId();
        sellerVersionId = sellerApproval.approvedChecklistVersionId();
        CLOCK.reset();

        // 승인 뒤 일어나는 사건들(수신·발생 시각이 평가 시각보다 뒤라 각 테스트가 시계를 옮겨야 보인다)
        insertNotice("SIN-PREPAYMENT-FEE-V3", "SIN-PREPAYMENT-FEE", 3, "SIN-PREPAYMENT-FEE-V2", "2026-11-01", null, "2026-10-06T00:00:00Z", "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1");
        // v4는 v3를 잇는다고 적혀 있지만 시행 구간(10-01~10-15)이 v2와 겹치고 v3는 그 기간에 없다 → 10-07 업무일의 선택이 모호해진다.
        insertNotice("SIN-PREPAYMENT-FEE-V4", "SIN-PREPAYMENT-FEE", 4, "SIN-PREPAYMENT-FEE-V3", "2026-10-01", "2026-10-15", "2026-10-07T00:00:00Z", "a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into internal_notice_lifecycle_event values (
                      'notice-event:a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3','SYNTHETIC_INTERNAL',
                      'SIN-SELLER-CHECKLIST-V2','WITHDRAWN','2026-10-30T00:00:00Z','합성 철회 사건','%s')
                    """.formatted(HASH));
        }
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void approvedChecklistIsUsableForTheNoticePeriodWithDecisionIdAndOrigin() throws Exception {
        // AC-02: 승인 뒤(평가 시각 05:00) 2026-10-01 조회 → 사용 가능
        CLOCK.set(Instant.parse("2026-10-05T05:00:00Z"));
        try {
            JsonNode usable = body(get(path("SIN-PREPAYMENT-FEE", "2026-10-01", null)));
            assertEquals("AVAILABLE", usable.get("checklistAvailabilityStatus").stringValue());
            assertTrue(usable.get("internalChecklistUseAllowed").booleanValue());
            assertEquals(List.of(), strings(usable.get("blockingReasons")));
            assertEquals(prepaymentVersionId, usable.get("approvedChecklist").get("approvedChecklistVersionId").stringValue());
            assertEquals("HUMAN_REVIEW", usable.get("approvedChecklist").get("origin").stringValue());
            assertEquals(prepaymentDecisionId, usable.get("approvedChecklist").get("decisionId").stringValue());
            assertEquals("2026-10-01", usable.get("approvedChecklist").get("effectiveFrom").stringValue());
            assertTrue(usable.get("approvedChecklist").get("effectiveTo").isNull());
            // AC-02: 응답의 승인 checklist 항목 3개를 내용·순서·근거 규칙 version으로 확인한다.
            JsonNode items = usable.get("approvedChecklist").get("items");
            assertEquals(3, items.size());
            assertEquals(List.of("CHECK_PREPAYMENT_FEE_RATE", "CHECK_NOTICE_SOURCE", "CHECK_CUSTOMER_CONTRACT_DATE"),
                    List.of(items.get(0).get("ruleKey").stringValue(), items.get(1).get("ruleKey").stringValue(), items.get(2).get("ruleKey").stringValue()));
            assertEquals(List.of(0, 1, 2), List.of(items.get(0).get("order").intValue(), items.get(1).get("order").intValue(), items.get(2).get("order").intValue()));
            assertEquals("0.8", items.get(0).get("structuredChange").get("after_value").stringValue());
            assertEquals("1.2", items.get(0).get("structuredChange").get("before_value").stringValue());
            assertEquals("2026-10-01", items.get(0).get("structuredChange").get("effective_on").stringValue());
            assertTrue(items.get(1).get("structuredChange").isNull());
            assertTrue(items.get(2).get("structuredChange").get("after_value").booleanValue());
            for (JsonNode item : items) {
                assertTrue(item.get("evidenceRequired").booleanValue());
                assertFalse(item.get("instruction").stringValue().isBlank());
                // 항목의 근거 규칙 version은 같은 응답의 공문 규칙(rules) version과 같다.
                String expectedRule = null;
                for (JsonNode rule : usable.get("rules")) {
                    if (rule.get("ruleKey").stringValue().equals(item.get("ruleKey").stringValue())) {
                        expectedRule = rule.get("ruleVersionId").stringValue();
                    }
                }
                assertEquals(expectedRule, item.get("sourceRuleVersionId").stringValue(), item.get("ruleKey").stringValue());
            }
            assertEquals("기업여신 상담 시 변경된 중도상환수수료율 0.8퍼센트와 적용 조건을 원문 근거에서 확인한다.",
                    items.get(0).get("instruction").stringValue());

            // AC-03: 2026-09-30은 테스트용 v1 checklist → 보이지만 사용 불가
            JsonNode fixture = body(get(path("SIN-PREPAYMENT-FEE", "2026-09-30", null)));
            assertEquals("AVAILABLE", fixture.get("checklistAvailabilityStatus").stringValue());
            assertEquals("FIXTURE", fixture.get("approvedChecklist").get("origin").stringValue());
            assertTrue(fixture.get("approvedChecklist").get("decisionId").isNull());
            assertTrue(strings(fixture.get("blockingReasons")).contains("FIXTURE_CHECKLIST_NOT_APPROVED"));
            assertFalse(fixture.get("internalChecklistUseAllowed").booleanValue());
            assertEquals(2, fixture.get("approvedChecklist").get("items").size());
            assertEquals("1.2", fixture.get("approvedChecklist").get("items").get(0).get("structuredChange").get("after_value").stringValue());

            // AC-04: 승인 1초 전 기준 시각 → 승인 전 상태(검토 대기), 승인과 같은 시각 → 보이지만 과거 조회라 사용 불가
            JsonNode beforeApproval = body(get(path("SIN-PREPAYMENT-FEE", "2026-10-01", "2026-10-05T04:29:59Z")));
            assertEquals("PENDING_REVIEW", beforeApproval.get("checklistAvailabilityStatus").stringValue());
            assertEquals(prepaymentValidationId, beforeApproval.get("validationResultId").stringValue());
            assertFalse(beforeApproval.get("internalChecklistUseAllowed").booleanValue());
            JsonNode atApproval = body(get(path("SIN-PREPAYMENT-FEE", "2026-10-01", "2026-10-05T04:30:00Z")));
            assertEquals("AVAILABLE", atApproval.get("checklistAvailabilityStatus").stringValue());
            assertEquals(prepaymentDecisionId, atApproval.get("approvedChecklist").get("decisionId").stringValue());
            assertTrue(strings(atApproval.get("blockingReasons")).contains("HISTORICAL_KNOWN_AT"));
            assertFalse(atApproval.get("internalChecklistUseAllowed").booleanValue());
        } finally {
            CLOCK.reset();
        }
    }

    @Test
    void approvalSurvivesValidationAgeButNotRequiredPublicEvidenceGoingStale() throws Exception {
        // AC-13(a)(b): 셀러론은 필수 공개 참조가 있다. 공개 관측 2026-09-21, 유효 기간 30일.
        CLOCK.set(Instant.parse("2026-10-06T05:00:00Z"));
        try {
            // 검증(10-05 04:00)으로부터 25시간 뒤에도 승인 checklist는 사용 가능하다(기간 경과로 자동 만료 없음)
            JsonNode seller = body(get(path("SIN-SELLER-CHECKLIST", "2026-10-06", null)));
            assertEquals("AVAILABLE", seller.get("checklistAvailabilityStatus").stringValue());
            assertEquals(sellerVersionId, seller.get("approvedChecklist").get("approvedChecklistVersionId").stringValue());
            assertTrue(seller.get("internalChecklistUseAllowed").booleanValue());
            JsonNode prepayment = body(get(path("SIN-PREPAYMENT-FEE", "2026-10-06", null)));
            assertTrue(prepayment.get("internalChecklistUseAllowed").booleanValue());

            // 공개 관측이 30일을 넘긴 2026-10-25: 승인 checklist는 보이지만 필수 공개 근거 미확인으로 차단
            CLOCK.set(Instant.parse("2026-10-25T03:00:00Z"));
            JsonNode stale = body(get(path("SIN-SELLER-CHECKLIST", "2026-10-25", null)));
            assertEquals("AVAILABLE", stale.get("checklistAvailabilityStatus").stringValue());
            assertTrue(strings(stale.get("blockingReasons")).contains("PUBLIC_EVIDENCE_UNCONFIRMED"));
            assertFalse(stale.get("internalChecklistUseAllowed").booleanValue());
            // 공개 참조가 없는 중도상환수수료는 같은 날에도 사용 가능하다
            JsonNode noReference = body(get(path("SIN-PREPAYMENT-FEE", "2026-10-25", null)));
            assertTrue(noReference.get("internalChecklistUseAllowed").booleanValue());
        } finally {
            CLOCK.reset();
        }
    }

    @Test
    void laterNoticeEventsBlockOnlyWhenSelectionActuallyChanges() throws Exception {
        try {
            // AC-13(d1): 미래 시행 공문 v3(2026-11-01)가 10-06에 수신돼도 10-06 업무일의 선택 공문은 v2 그대로 → 사용 가능
            CLOCK.set(Instant.parse("2026-10-06T03:00:00Z"));
            JsonNode futureReceived = body(get(path("SIN-PREPAYMENT-FEE", "2026-10-06", null)));
            assertEquals("SIN-PREPAYMENT-FEE-V2", futureReceived.get("selectedNotice").get("noticeId").stringValue());
            assertTrue(strings(futureReceived.get("candidateNoticeIds")).contains("SIN-PREPAYMENT-FEE-V2"));
            assertTrue(futureReceived.get("internalChecklistUseAllowed").booleanValue());

            // AC-13(d3): 10-07에 v4(시행 10-01~10-15, 끊긴 chain)가 수신되면 10-07 업무일의 선택이 모호해져 차단
            CLOCK.set(Instant.parse("2026-10-07T03:00:00Z"));
            JsonNode ambiguous = body(get(path("SIN-PREPAYMENT-FEE", "2026-10-07", null)));
            assertEquals("AMBIGUOUS", ambiguous.get("noticeSelectionStatus").stringValue());
            assertTrue(strings(ambiguous.get("blockingReasons")).contains("AMBIGUOUS_EFFECTIVE_NOTICE"));
            assertTrue(ambiguous.get("approvedChecklist").isNull());
            assertFalse(ambiguous.get("internalChecklistUseAllowed").booleanValue());

            // AC-13(d2): 11-01 업무일에는 v3가 선택된다. 일정 구간은 그날을 덮지만 그 checklist는 v2용이라 맞지 않음 → 차단
            CLOCK.set(Instant.parse("2026-11-02T03:00:00Z"));
            JsonNode changed = body(get(path("SIN-PREPAYMENT-FEE", "2026-11-01", null)));
            assertEquals("SIN-PREPAYMENT-FEE-V3", changed.get("selectedNotice").get("noticeId").stringValue());
            assertTrue(changed.get("approvedChecklist").isNull());
            assertTrue(strings(changed.get("blockingReasons")).contains("APPROVED_CHECKLIST_NOTICE_MISMATCH"));
            assertFalse(changed.get("internalChecklistUseAllowed").booleanValue());

            // AC-13(c): 셀러론 v2가 10-30에 철회되면 그 뒤 업무일은 철회로 차단
            CLOCK.set(Instant.parse("2026-10-30T03:00:00Z"));
            JsonNode withdrawn = body(get(path("SIN-SELLER-CHECKLIST", "2026-10-30", null)));
            assertEquals("WITHDRAWN", withdrawn.get("noticeSelectionStatus").stringValue());
            assertTrue(strings(withdrawn.get("blockingReasons")).contains("EFFECTIVE_NOTICE_WITHDRAWN"));
            assertFalse(withdrawn.get("internalChecklistUseAllowed").booleanValue());
            // 철회는 공문 단위 사건이라 철회를 알게 된 뒤에는 이전 업무일(10-29) 조회도 철회로 차단된다.
            JsonNode dayBefore = body(get(path("SIN-SELLER-CHECKLIST", "2026-10-29", null)));
            assertEquals("WITHDRAWN", dayBefore.get("noticeSelectionStatus").stringValue());
            // 철회를 알기 전 기준 시각(10-29 23:59:59)으로는 선택돼 있었다(과거 조회라 사용은 불가).
            JsonNode beforeWithdrawal = body(get(path("SIN-SELLER-CHECKLIST", "2026-10-29", "2026-10-29T23:59:59Z")));
            assertEquals("SELECTED", beforeWithdrawal.get("noticeSelectionStatus").stringValue());
            assertEquals("AVAILABLE", beforeWithdrawal.get("checklistAvailabilityStatus").stringValue());
            assertTrue(strings(beforeWithdrawal.get("blockingReasons")).contains("HISTORICAL_KNOWN_AT"));
            assertFalse(beforeWithdrawal.get("internalChecklistUseAllowed").booleanValue());
        } finally {
            CLOCK.reset();
        }
    }

    // ---- helpers ----

    private void insertNotice(String noticeId, String familyId, int version, String supersedes, String effectiveFrom,
            String effectiveTo, String receivedAt, String suffix) throws Exception {
        String supersedesSql = supersedes == null ? "null" : "'" + supersedes + "'";
        String effectiveToSql = effectiveTo == null ? "null" : "'" + effectiveTo + "'";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into internal_notice_version values (
                      '%s','SYNTHETIC_INTERNAL',true,
                      '프로젝트 시연을 위해 생성한 합성 공문이며 실제 KB 내부자료가 아닙니다.',
                      '%s',%d,'[합성] 승인 뒤 사건 테스트','ISSUED','2026-10-05','%s',%s,%s,'%s')
                    """.formatted(noticeId, familyId, version, effectiveFrom, effectiveToSql, supersedesSql, HASH));
            statement.execute("""
                    insert into internal_notice_receipt values (
                      'notice-receipt:%s','SYNTHETIC_INTERNAL',true,'%s','%s',
                      ((timestamptz '%s') at time zone 'Asia/Seoul')::date,
                      'Asia/Seoul','internal-business-time-v1','%s')
                    """.formatted(suffix, noticeId, receivedAt, receivedAt, HASH));
        }
    }

    private String path(String familyId, String businessDate, String knownAt) {
        String query = "businessDate=" + businessDate + (knownAt == null ? "" : "&knownAt=" + knownAt);
        return "/api/v1/internal-policy/checklists/" + familyId + "/applicable?" + query;
    }


    @Autowired @Qualifier(ControlDataSourceConfiguration.CONTROL_JDBC_CLIENT) private JdbcClient controlJdbc;
    private SessionClient session;

    private SessionClient session() throws Exception {
        if (session == null) session = SessionClient.staff(port, controlJdbc);
        return session;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return session().get(path);
    }

    private JsonNode body(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.stringValue()));
        return values;
    }

    static final class AdjustableClock extends Clock {
        private volatile Instant instant = EVALUATED_AT;

        void set(Instant value) {
            instant = value;
        }

        void reset() {
            instant = EVALUATED_AT;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final AdjustableClock CLOCK = new AdjustableClock();

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return CLOCK;
        }
    }
}
