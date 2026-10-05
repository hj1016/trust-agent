package com.trustagent.core.internalpolicy.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.internalpolicy.bootstrap.SyntheticInternalImporter;
import com.trustagent.core.publicproduct.baseline.BaselineImporter;
import com.trustagent.core.web.RequestTraceFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

@SpringBootTest(
        classes = {TrustAgentCoreApplication.class, InternalPolicyApplicableIntegrationTest.FixedClockConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InternalPolicyApplicableIntegrationTest {

    private static final Instant EVALUATED_AT = Instant.parse("2026-10-05T03:00:00Z");
    private static final String HASH = "sha256:" + "a".repeat(64);
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
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${local.server.port}")
    private int port;

    @BeforeAll
    void importBaselinesAndInsertSelectionFixtures() throws Exception {
        Path root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        var manager = new DataSourceTransactionManager(dataSource);
        new BaselineImporter(JdbcClient.create(dataSource), objectMapper, manager)
                .importBaseline(root, "baseline:89898989898989898989898989898989");
        new SyntheticInternalImporter(JdbcClient.create(dataSource), objectMapper, manager, "Asia/Seoul")
                .importBaseline(root, "synthetic-import:89898989898989898989898989898989");
        insertApprovedSchedules();
        insertRetroactiveFamily();
        insertWithdrawnFamily();
        insertBrokenChainFamily();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void fourSelectionConditionsAreAppliedAtTheirBoundaries() throws Exception {
        JsonNode beforeEffective = body(get(path(
                "SIN-SELLER-CHECKLIST", "2026-09-14", "2026-09-20T00:00:00Z")));
        assertEquals("NOT_YET_EFFECTIVE", beforeEffective.get("noticeSelectionStatus").stringValue());

        JsonNode effective = body(get(path(
                "SIN-SELLER-CHECKLIST", "2026-09-15", "2026-09-20T00:00:00Z")));
        assertEquals("SIN-SELLER-CHECKLIST-V1", effective.get("selectedNotice").get("noticeId").stringValue());

        JsonNode v2NotReceived = body(get(path(
                "SIN-SELLER-CHECKLIST", "2026-10-01", "2026-09-24T23:59:59Z")));
        assertEquals("NO_APPLICABLE_NOTICE", v2NotReceived.get("noticeSelectionStatus").stringValue());
        assertTrue(v2NotReceived.get("selectedNotice").isNull());

        JsonNode v2Received = body(get(path(
                "SIN-SELLER-CHECKLIST", "2026-10-01", "2026-09-25T02:00:00Z")));
        assertEquals("SIN-SELLER-CHECKLIST-V2", v2Received.get("selectedNotice").get("noticeId").stringValue());

        JsonNode withdrawn = body(get(path(
                "SIN-WITHDRAWN", "2026-09-20", "2026-09-23T00:00:00Z")));
        assertEquals("WITHDRAWN", withdrawn.get("noticeSelectionStatus").stringValue());
    }

    @Test
    void ambiguousBrokenChainReturns200AndCandidateNoticeIds() throws Exception {
        HttpResponse<String> response = get(path(
                "SIN-BROKEN-CHAIN", "2026-09-20", "2026-09-25T00:00:00Z"));

        assertEquals(200, response.statusCode());
        JsonNode result = body(response);
        assertEquals("AMBIGUOUS", result.get("noticeSelectionStatus").stringValue());
        assertEquals(2, result.get("candidateNoticeIds").size());
        assertTrue(strings(result.get("blockingReasons")).contains("AMBIGUOUS_EFFECTIVE_NOTICE"));
        assertFalse(result.get("internalChecklistUseAllowed").booleanValue());
    }

    @Test
    void retroactiveNoticeWarnsButIsInvisibleBeforeReceipt() throws Exception {
        JsonNode beforeReceipt = body(get(path(
                "SIN-RETROACTIVE", "2026-09-05", "2026-09-09T23:59:59Z")));
        assertEquals("NO_APPLICABLE_NOTICE", beforeReceipt.get("noticeSelectionStatus").stringValue());

        JsonNode afterReceipt = body(get(path(
                "SIN-RETROACTIVE", "2026-09-05", "2026-09-10T01:00:00Z")));
        assertEquals("SELECTED", afterReceipt.get("noticeSelectionStatus").stringValue());
        assertTrue(strings(afterReceipt.get("warningReasons")).contains("RETROACTIVE_NOTICE"));
    }

    @Test
    void latestVisibleScheduleRevisionIsSelectedByKnownAt() throws Exception {
        JsonNode beforeRevision = body(get(path(
                "SIN-SELLER-CHECKLIST", "2026-09-20", "2026-09-20T00:00:00Z")));
        assertEquals(
                "checklist-schedule:11111111111111111111111111111111",
                beforeRevision.get("approvedChecklist").get("scheduleRevisionId").stringValue());

        JsonNode afterRevision = body(get(path(
                "SIN-SELLER-CHECKLIST", "2026-09-20", "2026-10-03T00:00:00Z")));
        assertEquals(
                "checklist-schedule:22222222222222222222222222222222",
                afterRevision.get("approvedChecklist").get("scheduleRevisionId").stringValue());
    }

    @Test
    void pendingNewNoticeNeverFallsBackToPreviousChecklist() throws Exception {
        JsonNode result = body(get(path(
                "SIN-SELLER-CHECKLIST", "2026-10-01", "2026-09-25T02:00:00Z")));

        assertEquals("SIN-SELLER-CHECKLIST-V2", result.get("selectedNotice").get("noticeId").stringValue());
        assertEquals("PENDING_VALIDATION", result.get("checklistAvailabilityStatus").stringValue());
        assertTrue(result.get("approvedChecklist").isNull());
    }

    @Test
    void finalProposalPrepaymentFeeChangeIsReturnedAsStructuredEvidence() throws Exception {
        JsonNode result = body(get(path(
                "SIN-PREPAYMENT-FEE", "2026-10-01", "2026-09-25T02:00:00Z")));

        assertEquals("SIN-PREPAYMENT-FEE-V2", result.get("selectedNotice").get("noticeId").stringValue());
        JsonNode change = result.get("rules").get(0).get("structuredChange");
        assertEquals("prepayment_fee_rate_percent", change.get("field_key").stringValue());
        assertEquals("1.2", change.get("before_value").stringValue());
        assertEquals("0.8", change.get("after_value").stringValue());
        assertEquals("PERCENT", change.get("unit").stringValue());
    }

    @Test
    void futureBusinessDateAndHistoricalKnowledgeAreExplicitlyBlocked() throws Exception {
        JsonNode future = body(get(path(
                "SIN-SELLER-CHECKLIST", "2026-10-06", null)));
        assertTrue(future.get("businessDateInFuture").booleanValue());
        assertTrue(strings(future.get("blockingReasons")).contains("FUTURE_BUSINESS_DATE"));
        assertFalse(future.get("internalChecklistUseAllowed").booleanValue());

        JsonNode historical = body(get(path(
                "SIN-SELLER-CHECKLIST", "2026-09-20", "2026-09-20T00:00:00Z")));
        assertTrue(historical.get("historicalKnownAt").booleanValue());
        assertTrue(strings(historical.get("blockingReasons")).contains("HISTORICAL_KNOWN_AT"));
    }

    @Test
    void sameBusinessDayAsEvaluationIsNotFutureButRemainsBlockedByPendingValidation() throws Exception {
        // 평가 시각 2026-10-05T03:00:00Z는 서울 2026-10-05 12:00이므로 같은 날 업무일은 미래가 아니다.
        JsonNode result = body(get(path("SIN-SELLER-CHECKLIST", "2026-10-05", null)));

        assertEquals("2026-10-05", result.get("evaluatedBusinessDate").stringValue());
        assertFalse(result.get("businessDateInFuture").booleanValue());
        assertFalse(result.get("historicalKnownAt").booleanValue());
        java.util.List<String> blocking = strings(result.get("blockingReasons"));
        assertFalse(blocking.contains("FUTURE_BUSINESS_DATE"));
        assertFalse(blocking.contains("HISTORICAL_KNOWN_AT"));
        // 시간 차단 사유가 없고 승인 checklist fixture가 있어도(AVAILABLE) 검증과 공개 근거 재평가 전에는 사용이 차단된다.
        assertEquals("SIN-SELLER-CHECKLIST-V2", result.get("selectedNotice").get("noticeId").stringValue());
        assertEquals("AVAILABLE", result.get("checklistAvailabilityStatus").stringValue());
        assertTrue(blocking.contains("CURRENT_VALIDATION_NOT_EVALUATED"));
        assertTrue(blocking.contains("CURRENT_PUBLIC_EVIDENCE_NOT_EVALUATED"));
        assertFalse(result.get("internalChecklistUseAllowed").booleanValue());

        // 승인 checklist가 없는 family는 같은 날이어도 검증 대기로 차단된다.
        JsonNode pending = body(get(path("SIN-PREPAYMENT-FEE", "2026-10-05", null)));
        assertFalse(pending.get("businessDateInFuture").booleanValue());
        assertEquals("SIN-PREPAYMENT-FEE-V2", pending.get("selectedNotice").get("noticeId").stringValue());
        assertEquals("PENDING_VALIDATION", pending.get("checklistAvailabilityStatus").stringValue());
        assertTrue(strings(pending.get("blockingReasons")).contains("CHECKLIST_VALIDATION_PENDING"));
        assertFalse(pending.get("internalChecklistUseAllowed").booleanValue());
    }

    @Test
    void knownAtEqualToEvaluatedAtIsNeitherHistoricalNorFuture() throws Exception {
        JsonNode same = body(get(path("SIN-SELLER-CHECKLIST", "2026-10-05", "2026-10-05T03:00:00Z")));
        assertEquals("2026-10-05T03:00:00Z", same.get("knownAt").stringValue());
        assertFalse(same.get("historicalKnownAt").booleanValue());
        java.util.List<String> blocking = strings(same.get("blockingReasons"));
        assertFalse(blocking.contains("HISTORICAL_KNOWN_AT"));
        assertFalse(blocking.contains("FUTURE_BUSINESS_DATE"));
        // 시간 사유는 없지만 검증 미완료 사유로 사용은 차단된다.
        assertTrue(blocking.contains("CURRENT_VALIDATION_NOT_EVALUATED"));
        assertFalse(same.get("internalChecklistUseAllowed").booleanValue());

        // 1초만 앞서도 과거 지식 조회로 차단 사유가 붙는다. 1초 뒤는 기존 테스트가 400으로 확인한다.
        JsonNode oneSecondEarlier = body(get(path("SIN-SELLER-CHECKLIST", "2026-10-05", "2026-10-05T02:59:59Z")));
        assertTrue(oneSecondEarlier.get("historicalKnownAt").booleanValue());
        assertTrue(strings(oneSecondEarlier.get("blockingReasons")).contains("HISTORICAL_KNOWN_AT"));
    }

    @Test
    void evaluatedBusinessDateFollowsSeoulMidnightNotUtcDate() throws Exception {
        try {
            // UTC 2026-10-05 15:30 = 서울 2026-10-06 00:30. 평가 업무일은 10-06이어야 하고 10-06 조회는 미래가 아니다.
            CLOCK.set(Instant.parse("2026-10-05T15:30:00Z"));
            JsonNode afterMidnight = body(get(path("SIN-SELLER-CHECKLIST", "2026-10-06", null)));
            assertEquals("2026-10-06", afterMidnight.get("evaluatedBusinessDate").stringValue());
            assertEquals("2026-10-05T15:30:00Z", afterMidnight.get("evaluatedAt").stringValue());
            assertFalse(afterMidnight.get("businessDateInFuture").booleanValue());
            assertFalse(strings(afterMidnight.get("blockingReasons")).contains("FUTURE_BUSINESS_DATE"));
            assertFalse(afterMidnight.get("internalChecklistUseAllowed").booleanValue());

            JsonNode nextDay = body(get(path("SIN-SELLER-CHECKLIST", "2026-10-07", null)));
            assertTrue(nextDay.get("businessDateInFuture").booleanValue());
            assertTrue(strings(nextDay.get("blockingReasons")).contains("FUTURE_BUSINESS_DATE"));

            // UTC 2026-10-05 14:59:59 = 서울 2026-10-05 23:59:59. 평가 업무일은 아직 10-05이므로 10-06 조회는 미래다.
            CLOCK.set(Instant.parse("2026-10-05T14:59:59Z"));
            JsonNode beforeMidnight = body(get(path("SIN-SELLER-CHECKLIST", "2026-10-06", null)));
            assertEquals("2026-10-05", beforeMidnight.get("evaluatedBusinessDate").stringValue());
            assertTrue(beforeMidnight.get("businessDateInFuture").booleanValue());
            assertTrue(strings(beforeMidnight.get("blockingReasons")).contains("FUTURE_BUSINESS_DATE"));
        } finally {
            CLOCK.reset();
        }
    }

    @Test
    void futureInvalidAndUnknownRequestsHaveStableTraceableErrors() throws Exception {
        assertErrorCode(get(path(
                "SIN-SELLER-CHECKLIST", "2026-09-20", "2026-10-05T03:00:01Z")),
                400, "FUTURE_KNOWN_AT_NOT_ALLOWED");
        assertErrorCode(get("/api/v1/internal-policy/checklists/SIN-SELLER-CHECKLIST/applicable"
                        + "?businessDate=bad-date"),
                400, "INVALID_BUSINESS_DATE");
        assertErrorCode(get(path("SIN-NOT-REGISTERED", "2026-09-20", null)),
                404, "POLICY_FAMILY_NOT_FOUND");
    }

    private void insertApprovedSchedules() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into approved_checklist_version values
                    ('approved-checklist:11111111111111111111111111111111','SYNTHETIC_INTERNAL',
                     'SIN-SELLER-CHECKLIST','SIN-SELLER-CHECKLIST-V1','2026-09-18T00:00:00Z','%s'),
                    ('approved-checklist:22222222222222222222222222222222','SYNTHETIC_INTERNAL',
                     'SIN-SELLER-CHECKLIST','SIN-SELLER-CHECKLIST-V2','2026-10-02T00:00:00Z','%s')
                    """.formatted(HASH, HASH));
            statement.execute("""
                    insert into approved_checklist_schedule_revision values
                    ('checklist-schedule:11111111111111111111111111111111','SYNTHETIC_INTERNAL',
                     'SIN-SELLER-CHECKLIST',null,'2026-09-18T00:00:00Z','%s'),
                    ('checklist-schedule:22222222222222222222222222222222','SYNTHETIC_INTERNAL',
                     'SIN-SELLER-CHECKLIST','checklist-schedule:11111111111111111111111111111111',
                     '2026-10-02T00:00:00Z','%s')
                    """.formatted(HASH, HASH));
            statement.execute("""
                    insert into approved_checklist_schedule_entry values
                    ('checklist-schedule:11111111111111111111111111111111','SIN-SELLER-CHECKLIST',0,
                     'approved-checklist:11111111111111111111111111111111','2026-09-15','2026-10-01','%s'),
                    ('checklist-schedule:22222222222222222222222222222222','SIN-SELLER-CHECKLIST',0,
                     'approved-checklist:11111111111111111111111111111111','2026-09-15','2026-10-01','%s'),
                    ('checklist-schedule:22222222222222222222222222222222','SIN-SELLER-CHECKLIST',1,
                     'approved-checklist:22222222222222222222222222222222','2026-10-01',null,'%s')
                    """.formatted(HASH, HASH, HASH));
        }
    }

    private void insertRetroactiveFamily() throws Exception {
        insertNotice(
                "SIN-RETROACTIVE-V1", "SIN-RETROACTIVE", 1, null,
                "2026-09-01", null, "2026-09-10T00:00:00Z", "33333333333333333333333333333333");
    }

    private void insertWithdrawnFamily() throws Exception {
        insertNotice(
                "SIN-WITHDRAWN-V1", "SIN-WITHDRAWN", 1, null,
                "2026-09-01", null, "2026-09-10T00:00:00Z", "44444444444444444444444444444444");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into internal_notice_lifecycle_event values (
                      'notice-event:44444444444444444444444444444444','SYNTHETIC_INTERNAL',
                      'SIN-WITHDRAWN-V1','WITHDRAWN','2026-09-22T00:00:00Z','합성 철회 사건','%s')
                    """.formatted(HASH));
        }
    }

    private void insertBrokenChainFamily() throws Exception {
        insertNotice(
                "SIN-BROKEN-CHAIN-V1", "SIN-BROKEN-CHAIN", 1, null,
                "2026-09-01", null, "2026-09-05T00:00:00Z", "55555555555555555555555555555555");
        insertNotice(
                "SIN-BROKEN-CHAIN-V2", "SIN-BROKEN-CHAIN", 2, "SIN-BROKEN-CHAIN-V1",
                "2027-01-01", null, "2026-09-06T00:00:00Z", "66666666666666666666666666666666");
        insertNotice(
                "SIN-BROKEN-CHAIN-V3", "SIN-BROKEN-CHAIN", 3, "SIN-BROKEN-CHAIN-V2",
                "2026-09-15", null, "2026-09-07T00:00:00Z", "77777777777777777777777777777777");
    }

    private void insertNotice(
            String noticeId,
            String familyId,
            int version,
            String supersedes,
            String effectiveFrom,
            String effectiveTo,
            String receivedAt,
            String suffix) throws Exception {
        String supersedesSql = supersedes == null ? "null" : "'" + supersedes + "'";
        String effectiveToSql = effectiveTo == null ? "null" : "'" + effectiveTo + "'";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into internal_notice_version values (
                      '%s','SYNTHETIC_INTERNAL',true,
                      '프로젝트 시연을 위해 생성한 합성 공문이며 실제 KB 내부자료가 아닙니다.',
                      '%s',%d,'[합성] 선택 테스트','ISSUED','2026-09-01','%s',%s,%s,'%s')
                    """.formatted(
                    noticeId, familyId, version, effectiveFrom, effectiveToSql, supersedesSql, HASH));
            statement.execute("""
                    insert into internal_notice_receipt values (
                      'notice-receipt:%s','SYNTHETIC_INTERNAL',true,'%s','%s',
                      ((timestamptz '%s') at time zone 'Asia/Seoul')::date,
                      'Asia/Seoul','internal-business-time-v1','%s')
                    """.formatted(suffix, noticeId, receivedAt, receivedAt, HASH));
        }
    }

    private String path(String familyId, String businessDate, String knownAt) {
        String path = "/api/v1/internal-policy/checklists/" + familyId
                + "/applicable?businessDate=" + businessDate;
        return knownAt == null ? path : path + "&knownAt=" + knownAt;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    private void assertErrorCode(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());
        JsonNode result = objectMapper.readTree(response.body());
        assertEquals(code, result.get("code").stringValue());
        String traceId = result.get("traceId").stringValue();
        assertTrue(traceId.matches("[a-f0-9]{32}"));
        assertEquals(traceId, response.headers().firstValue(RequestTraceFilter.TRACE_ID_HEADER).orElseThrow());
    }

    private static java.util.List<String> strings(JsonNode array) {
        java.util.List<String> result = new java.util.ArrayList<>();
        array.forEach(value -> result.add(value.stringValue()));
        return result;
    }

    /**
     * 기본값은 EVALUATED_AT으로 고정된 시계다. 서울 자정 경계 테스트만 평가 시각을 바꾸고 끝나면 되돌린다.
     * 업무 로직은 Clock.instant()만 읽으므로 테스트 전용 조정이 서비스 동작을 바꾸지 않는다.
     */
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
