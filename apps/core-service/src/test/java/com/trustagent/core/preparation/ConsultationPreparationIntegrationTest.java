package com.trustagent.core.preparation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * TASK-015 AC-06~10, 16, 18, 19: AI 서비스 산출물 기록 경로. 기록 본문은 AI 서비스와 같은 방식으로 Tool 1·2 응답에서 만든다.
 * 토큰 두 개는 실행 중 생성한 임시 값이다. 기록은 사용 허가가 아니며, 승인·일정·변경안·검증·공문 사건·매핑 표는 바뀌지 않는다.
 */
@SpringBootTest(
        classes = {TrustAgentCoreApplication.class, ConsultationPreparationIntegrationTest.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsultationPreparationIntegrationTest {

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
    @Autowired private com.trustagent.core.internalpolicy.query.InternalPolicyApplicableService applicableService;
    @Value("${local.server.port}") private int port;

    private JdbcClient jdbc;
    private String mappingHash;
    private Map<String, Integer> businessCountsAtStart;

    @BeforeAll
    void loadScenario() {
        jdbc = JdbcClient.create(dataSource);
        Path root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        mappingHash = PreparationScenario.load(jdbc, mapper, PreparationScenario.manager(dataSource), CLOCK, publicProducts, root).mappingHash();
        businessCountsAtStart = businessCounts();
    }

    // ---- AC-06, AC-18: PARTIAL 준비안 기록, 재확인 값, 실행 기록 ----

    @Test
    @Order(1)
    void partialPreparationIsRecordedWithRecheckValuesAndRunRecord() throws Exception {
        ObjectNode body = partialBody();
        HttpResponse<String> response = record(body, RECORD_TOKEN);
        assertEquals(201, response.statusCode(), response.body());
        JsonNode result = mapper.readTree(response.body());
        assertEquals("RECORDED", result.get("status").stringValue());
        assertEquals(body.get("preparation_id").stringValue(), result.get("preparationId").stringValue());

        String id = body.get("preparation_id").stringValue();
        assertEquals("PARTIAL | false | 2 | 1", single("select status || ' | ' || preparation_complete || ' | ' || section_count || ' | ' || required_hold_count from consultation_preparation where preparation_id = '" + id + "'"));
        assertEquals("READY | true | 3", single("select status || ' | ' || recheck_usable || ' | ' || jsonb_array_length(item_rule_version_ids) from consultation_preparation_section where preparation_id = '" + id + "' and family_id = '" + PreparationScenario.PREPAYMENT + "'"));
        assertEquals("HOLD | CORE_DECISION | CORE_REPORTED | false", single("select status || ' | ' || hold_kind || ' | ' || hold_claim_basis || ' | ' || recheck_usable from consultation_preparation_section where preparation_id = '" + id + "' and family_id = '" + PreparationScenario.SELLER + "'"));
        assertTrue(single("select recheck_reasons::text from consultation_preparation_section where preparation_id = '" + id + "' and family_id = '" + PreparationScenario.SELLER + "'").contains("HUMAN_REVIEW_PENDING"));
        assertEquals("RECORDED | " + id, single("select outcome || ' | ' || preparation_id from consultation_preparation_run where run_id = '" + body.get("run_id").stringValue() + "'"));
        // 근거 원문은 어느 열에도 없다.
        assertFalse(jdbc.sql("select column_name from information_schema.columns where table_name = 'consultation_preparation_section'")
                .query(String.class).list().stream().anyMatch(column -> column.contains("text") || column.contains("instruction")));
    }

    // ---- AC-08, AC-13: 같은 내용 재기록은 ALREADY_RECORDED, 그래도 재확인은 실행된다 ----

    @Test
    @Order(2)
    void sameContentIsRecordedOnceAndRerunStillRechecksCurrentState() throws Exception {
        ObjectNode body = partialBody();
        String id = body.get("preparation_id").stringValue();
        int preparations = count("consultation_preparation");
        // 실행마다 달라지는 값(평가 시각·Tool 응답 해시)을 바꿔도 준비안 ID는 같다.
        readySection(body).put("evaluated_at", "2026-10-06T03:05:00Z");
        readySection(body).put("tool_response_hash", "sha256:" + "5".repeat(64));
        assertEquals(id, PreparationScenario.preparationId(mapper, body));
        HttpResponse<String> again = record(body, RECORD_TOKEN);
        assertEquals(200, again.statusCode(), again.body());
        assertEquals("ALREADY_RECORDED", mapper.readTree(again.body()).get("status").stringValue());
        assertEquals(preparations, count("consultation_preparation"));
        assertEquals("ALREADY_RECORDED", single("select outcome from consultation_preparation_run where run_id = '" + body.get("run_id").stringValue() + "'"));
        // 첫 기록의 섹션 행은 그대로이고(덮어쓰기 없음), 재실행의 값은 실행 기록의 section_evaluations에 실행 단위로 남는다.
        assertEquals("sha256:" + "1".repeat(64) + " | 2026-10-06T03:00:00Z", single("select tool_response_hash || ' | ' || to_char(evaluated_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') from consultation_preparation_section where preparation_id = '" + id + "' and family_id = '" + PreparationScenario.PREPAYMENT + "'"));
        assertEquals("READY | 2026-10-06T03:05:00Z | sha256:" + "5".repeat(64) + " | true | []", single("""
                select e->>'status' || ' | ' || (e->>'evaluated_at') || ' | ' || (e->>'tool_response_hash') || ' | ' || (e->>'recheck_usable') || ' | ' || (e->'recheck_reasons')::text
                from consultation_preparation_run r, jsonb_array_elements(r.section_evaluations) e
                where r.run_id = '%s' and e->>'family_id' = '%s'
                """.formatted(body.get("run_id").stringValue(), PreparationScenario.PREPAYMENT)));
        assertEquals(2, count("consultation_preparation_run r, jsonb_array_elements(r.section_evaluations) e where r.preparation_id = '" + id + "' and e->>'family_id' = '" + PreparationScenario.PREPAYMENT + "'"), "실행 2건 각각에 추적 값이 있다");

        // 승인이 알려지기 전 시각으로 돌리면 같은 ID가 이미 있어도 재확인이 거부한다(기존 기록 조회로 재확인을 생략하지 않는다).
        CLOCK.set(Instant.parse("2026-10-05T04:00:00Z"));
        try {
            ObjectNode rerun = withNewRun(body);
            HttpResponse<String> rejected = record(rerun, RECORD_TOKEN);
            assertProblem(rejected, 422, "PREPARATION_NOT_USABLE");
            assertEquals("REJECTED | PREPARATION_NOT_USABLE", single("select outcome || ' | ' || error_code from consultation_preparation_run where run_id = '" + rerun.get("run_id").stringValue() + "'"));
            assertEquals(preparations, count("consultation_preparation where preparation_id = '" + id + "'") == 1 ? preparations : -1);
        } finally {
            CLOCK.reset();
        }
    }

    // ---- AC-07: C1~C5 재확인 거부 ----

    @Test
    @Order(3)
    void tamperedReadyClaimsAreRejectedWithoutRows() throws Exception {
        int preparations = count("consultation_preparation");
        ObjectNode decision = partialBody();
        readySection(decision).put("decision_id", "review-decision:" + "9".repeat(32));
        assertRejected(reseal(decision), 409, "PREPARATION_STALE");

        ObjectNode reordered = partialBody();
        ArrayNode ids = (ArrayNode) readySection(reordered).get("item_rule_version_ids");
        ArrayNode hashes = (ArrayNode) readySection(reordered).get("item_evidence_hashes");
        JsonNode firstId = ids.get(0);
        JsonNode firstHash = hashes.get(0);
        ids.remove(0);
        hashes.remove(0);
        ids.add(firstId);
        hashes.add(firstHash);
        assertRejected(reseal(reordered), 409, "PREPARATION_STALE");

        ObjectNode evidence = partialBody();
        ((ArrayNode) readySection(evidence).get("item_evidence_hashes")).set(0, mapper.getNodeFactory().stringNode("sha256:" + "a".repeat(64)));
        assertRejected(reseal(evidence), 409, "PREPARATION_STALE");

        ObjectNode notice = partialBody();
        readySection(notice).put("selected_notice_id", "SIN-PREPAYMENT-FEE-V1");
        assertRejected(reseal(notice), 409, "PREPARATION_STALE");

        ObjectNode dropped = partialBody();
        ArrayNode droppedIds = (ArrayNode) readySection(dropped).get("item_rule_version_ids");
        ArrayNode droppedHashes = (ArrayNode) readySection(dropped).get("item_evidence_hashes");
        droppedIds.remove(droppedIds.size() - 1);
        droppedHashes.remove(droppedHashes.size() - 1);
        assertRejected(reseal(dropped), 409, "PREPARATION_STALE");

        ObjectNode fixturePeriod = partialBody();
        fixturePeriod.put("business_date", "2026-09-30");
        assertRejected(reseal(fixturePeriod), 422, "PREPARATION_NOT_USABLE");

        assertEquals(preparations, count("consultation_preparation"));
    }

    // ---- AC-16, AC-17: 매핑·필수 여부·상태 주장 거부 ----

    @Test
    @Order(4)
    void mappingAndStatusClaimsAreRejected() throws Exception {
        int preparations = count("consultation_preparation");
        ObjectNode missing = partialBody();
        ((ArrayNode) missing.get("sections")).remove(1);
        missing.put("status", "READY");
        missing.put("preparation_complete", true);
        assertRejected(reseal(missing), 422, "REQUIRED_SECTION_MISSING");

        ObjectNode flag = partialBody();
        holdSection(flag).put("required", false);
        flag.put("status", "READY");
        flag.put("preparation_complete", true);
        assertRejected(reseal(flag), 422, "REQUIRED_FLAG_MISMATCH");

        ObjectNode falseReady = partialBody();
        falseReady.put("status", "READY");
        falseReady.put("preparation_complete", true);
        assertRejected(reseal(falseReady), 422, "PREPARATION_STATUS_INVALID");

        ObjectNode completeClaim = partialBody();
        completeClaim.put("preparation_complete", true);
        assertRejected(reseal(completeClaim), 422, "PREPARATION_STATUS_INVALID");

        ObjectNode wrongMapping = partialBody();
        wrongMapping.put("family_mapping_hash", "sha256:" + "b".repeat(64));
        assertRejected(reseal(wrongMapping), 409, "MAPPING_MISMATCH");

        ObjectNode unknownFamily = partialBody();
        ObjectNode extra = holdSection(unknownFamily).deepCopy();
        extra.put("family_id", "SIN-UNKNOWN");
        ((ArrayNode) unknownFamily.get("sections")).add(extra);
        assertRejected(reseal(unknownFamily), 422, "SECTION_NOT_IN_MAPPING");
        assertEquals(preparations, count("consultation_preparation"));
    }

    // ---- AC-18: HOLD 섹션 공통 검사와 보류 근거 저장 ----

    @Test
    @Order(5)
    void holdSectionsAreCheckedAndStoredWithClaimBasis() throws Exception {
        ObjectNode withItems = partialBody();
        holdSection(withItems).set("item_rule_version_ids", readySection(withItems).get("item_rule_version_ids").deepCopy());
        assertRejected(reseal(withItems), 400, "HOLD_SECTION_INVALID");

        ObjectNode badCode = partialBody();
        holdSection(badCode).put("hold_kind", "UNVERIFIED");
        holdSection(badCode).put("hold_claim_basis", "SERVICE_REPORTED");
        ((ArrayNode) holdSection(badCode).get("blocking_reasons")).removeAll().add("HUMAN_REVIEW_PENDING");
        assertRejected(reseal(badCode), 400, "HOLD_SECTION_INVALID");

        // 서비스가 보고한 통신 오류 보류: Core는 그 보고를 보증하지 않고 지금 본 상태(사용 가능)를 함께 적는다.
        ObjectNode unverified = partialBody();
        ObjectNode prepayment = readySection(unverified);
        prepayment.put("status", "HOLD");
        prepayment.put("hold_kind", "UNVERIFIED");
        prepayment.put("hold_claim_basis", "SERVICE_REPORTED");
        prepayment.putNull("approved_checklist_version_id");
        prepayment.putNull("decision_id");
        prepayment.set("item_rule_version_ids", mapper.createArrayNode());
        prepayment.set("item_evidence_hashes", mapper.createArrayNode());
        prepayment.set("blocking_reasons", mapper.createArrayNode().add("CORE_TIMEOUT"));
        unverified.put("status", "HOLD");
        unverified.put("preparation_complete", false);
        reseal(unverified);
        HttpResponse<String> response = record(unverified, RECORD_TOKEN);
        assertEquals(201, response.statusCode(), response.body());
        String id = unverified.get("preparation_id").stringValue();
        assertEquals("HOLD | UNVERIFIED | SERVICE_REPORTED | true | []", single("select status || ' | ' || hold_kind || ' | ' || hold_claim_basis || ' | ' || recheck_usable || ' | ' || recheck_reasons::text from consultation_preparation_section where preparation_id = '" + id + "' and family_id = '" + PreparationScenario.PREPAYMENT + "'"));
        assertEquals("HOLD | false | 2", single("select status || ' | ' || preparation_complete || ' | ' || required_hold_count from consultation_preparation where preparation_id = '" + id + "'"));
    }

    // ---- AC-08, AC-19: ID 해시, run_id 재전송, 요청당 실행 기록 1건 ----

    @Test
    @Order(6)
    void idHashRunIdAndSchemaRulesAreEnforced() throws Exception {
        ObjectNode mismatch = partialBody();
        mismatch.put("preparation_id", "consultation-preparation:sha256:" + "c".repeat(64));
        assertRejected(mismatch, 400, "PREPARATION_ID_MISMATCH");

        ObjectNode reused = partialBody();
        reused.put("run_id", mismatch.get("run_id").stringValue());
        int runs = count("consultation_preparation_run");
        assertProblem(record(reused, RECORD_TOKEN), 409, "RUN_ID_CONFLICT");
        assertEquals(runs, count("consultation_preparation_run"));

        ObjectNode unknownField = partialBody();
        unknownField.put("decision", "APPROVE");
        assertRejected(reseal(unknownField), 400, "INVALID_REQUEST");

        ObjectNode badRun = partialBody();
        badRun.put("run_id", "run-1");
        runs = count("consultation_preparation_run");
        assertProblem(record(badRun, RECORD_TOKEN), 400, "INVALID_REQUEST");
        assertEquals(runs, count("consultation_preparation_run"));
    }

    // ---- AC-09: 인증. 읽기 토큰으로 기록 불가, 기록 토큰으로 읽기 불가 ----

    @Test
    @Order(7)
    void recordAndToolTokensAreNotInterchangeable() throws Exception {
        int runs = count("consultation_preparation_run");
        ObjectNode body = partialBody();
        assertEquals(401, record(body, null).statusCode());
        assertEquals(401, record(body, TOOL_TOKEN).statusCode());
        assertEquals(401, tool("applicable_checklist", Map.of("familyId", PreparationScenario.PREPAYMENT), RECORD_TOKEN).statusCode());
        assertEquals(runs, count("consultation_preparation_run"));
    }

    // ---- AC-08: 동시 같은 ID ----

    @Test
    @Order(8)
    void concurrentSameIdRecordsOnce() throws Exception {
        ObjectNode base = partialBody();
        base.put("business_date", "2026-10-05");
        reseal(base);
        String id = base.get("preparation_id").stringValue();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ObjectNode copy = withNewRun(base);
                results.add(executor.submit(() -> {
                    start.await();
                    return record(copy, RECORD_TOKEN).statusCode();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : results) statuses.add(future.get());
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 201), statuses);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("consultation_preparation where preparation_id = '" + id + "'"));
        assertEquals(2, count("consultation_preparation_run where preparation_id = '" + id + "'"));
    }

    // ---- AC-10: 표 불변, append-only, 권한 ----

    @Test
    @Order(9)
    void businessTablesAreUntouchedAndRecordTablesAreAppendOnly() throws Exception {
        assertEquals(businessCountsAtStart, businessCounts());
        assertEquals("42501", sqlState(() -> jdbc.sql("update consultation_preparation set status = 'READY'").update()));
        assertEquals("42501", sqlState(() -> jdbc.sql("delete from consultation_preparation_run").update()));
        assertEquals("42501", sqlState(() -> jdbc.sql("delete from consultation_family_mapping").update()));
        assertEquals("true | true | true | true | false | false",
                single("""
                        select has_table_privilege('trust_agent_runtime','consultation_preparation','INSERT')::text || ' | ' ||
                               has_table_privilege('trust_agent_runtime','consultation_preparation_section','INSERT')::text || ' | ' ||
                               has_table_privilege('trust_agent_runtime','consultation_preparation_run','INSERT')::text || ' | ' ||
                               has_table_privilege('trust_agent_runtime','consultation_family_mapping','INSERT')::text || ' | ' ||
                               has_table_privilege('trust_agent_runtime','consultation_preparation','UPDATE')::text || ' | ' ||
                               has_table_privilege('trust_agent_runtime','consultation_preparation_section','DELETE')::text
                        """));
        assertEquals(6, count("trust_agent_protected_tables() where protected_table_name like 'consultation_%'")); // V10 네 표 + V12 연결·직원 확인
        // CHECK: HOLD 섹션에 version ID가 있으면 DB가 거부한다(코드를 우회해도 막힌다).
        String violation = sqlState(() -> jdbc.sql("""
                insert into consultation_preparation_section (preparation_id, family_id, required, status, hold_kind, hold_claim_basis,
                    item_rule_version_ids, item_evidence_hashes, blocking_reasons, approved_checklist_version_id)
                select preparation_id, 'SIN-X', true, 'HOLD', 'CORE_DECISION', 'CORE_REPORTED', '[]', '[]', '["X"]',
                    (select approved_checklist_version_id from consultation_preparation_section where status = 'READY' limit 1)
                from consultation_preparation limit 1
                """).update());
        assertEquals("23514", violation);
    }

    // ---- AC-08: 동시 저장 충돌(23505) 뒤 기존 기록을 돌려주는 경로도 재확인을 거친다(시간에 의존하지 않는 재현) ----

    @Test
    @Order(10)
    void duplicateKeyConflictPathRechecksBeforeReturningExistingRecord() throws Exception {
        // 첫 시도에서는 기존 행이 아직 보이지 않는 것처럼 꾸며 저장을 시도하게 한다. 행은 이미 있으므로 PostgreSQL이 실제 23505를 낸다.
        ObjectNode body = partialBody();
        assertEquals(1, count("consultation_preparation where preparation_id = '" + body.get("preparation_id").stringValue() + "'"));
        BlindFirstLookupRepository blind = new BlindFirstLookupRepository(jdbc, mapper, null);
        ConsultationPreparationService service = new ConsultationPreparationService(blind, mapper, PreparationScenario.manager(dataSource), applicableService, CLOCK);
        ConsultationPreparationService.Result result = service.record(body, "test-service", "record", "trace-conflict");
        assertEquals(ConsultationPreparationService.Outcome.ALREADY_RECORDED, result.outcome());
        assertEquals(2, blind.mappingLookups, "충돌 뒤 새 트랜잭션에서 매핑 확인부터 다시 했다");
        assertEquals(2, blind.preparationLookups);
        assertEquals("ALREADY_RECORDED", single("select outcome from consultation_preparation_run where run_id = '" + body.get("run_id").stringValue() + "'"));
        assertEquals(2, count("consultation_preparation_run r, jsonb_array_elements(r.section_evaluations) e where r.run_id = '" + body.get("run_id").stringValue() + "' and e->>'recheck_usable' is not null"), "해결 경로의 재확인 결과가 실행 기록에 남는다");

        // 충돌 뒤 재확인에서 매핑이 달라져 있으면 기존 행이 있어도 성공으로 답하지 않는다.
        ObjectNode changed = withNewRun(body);
        BlindFirstLookupRepository mappingChanged = new BlindFirstLookupRepository(jdbc, mapper, "sha256:" + "a".repeat(64));
        ConsultationPreparationService rejecting = new ConsultationPreparationService(mappingChanged, mapper, PreparationScenario.manager(dataSource), applicableService, CLOCK);
        ConsultationPreparationException exception = org.junit.jupiter.api.Assertions.assertThrows(
                ConsultationPreparationException.class, () -> rejecting.record(changed, "test-service", "record", "trace-conflict-2"));
        assertEquals("MAPPING_MISMATCH", exception.code());
        assertEquals(2, mappingChanged.mappingLookups);
        assertEquals("REJECTED | MAPPING_MISMATCH", single("select outcome || ' | ' || error_code from consultation_preparation_run where run_id = '" + changed.get("run_id").stringValue() + "'"));

        // 충돌 뒤 재확인에서 READY 섹션이 사용 불가(승인 전 시각)면 기존 행이 있어도 거부한다.
        ObjectNode stale = withNewRun(body);
        BlindFirstLookupRepository blindAgain = new BlindFirstLookupRepository(jdbc, mapper, null);
        ConsultationPreparationService staleService = new ConsultationPreparationService(blindAgain, mapper, PreparationScenario.manager(dataSource), applicableService, CLOCK);
        blindAgain.afterConflict = () -> CLOCK.set(Instant.parse("2026-10-05T04:00:00Z"));
        try {
            ConsultationPreparationException notUsable = org.junit.jupiter.api.Assertions.assertThrows(
                    ConsultationPreparationException.class, () -> staleService.record(stale, "test-service", "record", "trace-conflict-3"));
            assertEquals("PREPARATION_NOT_USABLE", notUsable.code());
        } finally {
            CLOCK.reset();
        }
        assertEquals("REJECTED | PREPARATION_NOT_USABLE", single("select outcome || ' | ' || error_code from consultation_preparation_run where run_id = '" + stale.get("run_id").stringValue() + "'"));
    }

    /**
     * 첫 번째 기존 기록 조회만 "없음"으로 답해 저장을 시도하게 만든다(동시 요청에서 상대가 먼저 commit한 상황). 두 번째 조회부터는 실제 DB다.
     * 매핑 조회 횟수를 세고, 필요하면 두 번째 매핑 조회의 해시를 바꾸거나 충돌 해결 트랜잭션 직전 동작(afterConflict)을 끼워 넣는다.
     */
    static final class BlindFirstLookupRepository extends ConsultationPreparationRepository {
        int preparationLookups;
        int mappingLookups;
        private final String secondMappingHash;
        Runnable afterConflict = () -> {};

        BlindFirstLookupRepository(JdbcClient jdbc, ObjectMapper mapper, String secondMappingHash) {
            super(jdbc, mapper);
            this.secondMappingHash = secondMappingHash;
        }

        @Override
        java.util.Optional<StoredPreparation> findPreparation(String preparationId) {
            preparationLookups++;
            if (preparationLookups == 1) {
                return java.util.Optional.empty();
            }
            return super.findPreparation(preparationId);
        }

        @Override
        java.util.Optional<ActiveMapping> activeMapping(String productKey) {
            mappingLookups++;
            if (mappingLookups == 2) {
                afterConflict.run(); // 충돌 해결 트랜잭션의 첫 조회 직전: 그 사이 상태가 바뀐 상황을 만든다
                afterConflict = () -> {};
            }
            java.util.Optional<ActiveMapping> mapping = super.activeMapping(productKey);
            if (mappingLookups >= 2 && secondMappingHash != null) {
                return mapping.map(m -> new ActiveMapping(secondMappingHash, m.mappingVersion(), m.families()));
            }
            return mapping;
        }
    }

    // ---- AC-19: 직렬화 실패(40001)는 재확인부터 1회 재시도하고, 재시도도 실패하면 FAILED 실행 기록 1건만 남는다 ----

    @Test
    @Order(11)
    void serializationFailureIsRetriedOnceAndSecondFailureIsRecordedAsFailed() throws Exception {
        ObjectNode retried = partialBody();
        retried.put("messages_hash", "sha256:" + "3".repeat(64));
        reseal(retried);
        CommitFailingManager once = new CommitFailingManager(PreparationScenario.manager(dataSource), 1);
        ConsultationPreparationService service = new ConsultationPreparationService(jdbc, mapper, once, applicableService, CLOCK);
        ConsultationPreparationService.Result result = service.record(retried, "test-service", "record", "trace-retry");
        assertEquals(ConsultationPreparationService.Outcome.RECORDED, result.outcome());
        assertEquals(1, once.failedCommits, "첫 저장은 실패했고 그 뒤 성공 commit이 있었다(재확인부터 재실행)");
        assertEquals(1, count("consultation_preparation where preparation_id = '" + retried.get("preparation_id").stringValue() + "'"));
        assertEquals("RECORDED", single("select outcome from consultation_preparation_run where run_id = '" + retried.get("run_id").stringValue() + "'"));

        ObjectNode failed = partialBody();
        failed.put("messages_hash", "sha256:" + "4".repeat(64));
        reseal(failed);
        CommitFailingManager twice = new CommitFailingManager(PreparationScenario.manager(dataSource), 2);
        ConsultationPreparationService failing = new ConsultationPreparationService(jdbc, mapper, twice, applicableService, CLOCK);
        ConsultationPreparationException exception = org.junit.jupiter.api.Assertions.assertThrows(
                ConsultationPreparationException.class, () -> failing.record(failed, "test-service", "record", "trace-retry-2"));
        assertEquals("SERIALIZATION_FAILED", exception.code());
        assertEquals(2, twice.failedCommits, "재시도는 1회뿐이다(세 번째 저장 시도 없음)");
        assertEquals(0, count("consultation_preparation where preparation_id = '" + failed.get("preparation_id").stringValue() + "'"));
        assertEquals("FAILED | SERIALIZATION_FAILED", single("select outcome || ' | ' || error_code from consultation_preparation_run where run_id = '" + failed.get("run_id").stringValue() + "'"));
        assertEquals(1, count("consultation_preparation_run where run_id = '" + failed.get("run_id").stringValue() + "'"));
    }

    /** 앞의 n번 commit(저장 트랜잭션)을 롤백하고 SQLSTATE 40001로 실패시킨다. 그 뒤 commit(재시도·실행 기록)은 그대로 통과시킨다. */
    static final class CommitFailingManager implements org.springframework.transaction.PlatformTransactionManager {
        private final org.springframework.transaction.PlatformTransactionManager delegate;
        private final int failures;
        int failedCommits;

        CommitFailingManager(org.springframework.transaction.PlatformTransactionManager delegate, int failures) {
            this.delegate = delegate;
            this.failures = failures;
        }

        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
            if (failedCommits < failures) {
                failedCommits++;
                delegate.rollback(status);
                throw new org.springframework.dao.PessimisticLockingFailureException("simulated serialization failure",
                        new java.sql.SQLException("could not serialize access due to concurrent update", "40001"));
            }
            delegate.commit(status);
        }

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
            delegate.rollback(status);
        }
    }

    // ---- AC-07, AC-08: 승인 뒤 철회가 알려지면 같은 주장은 기존 행이 있어도 거부된다(업무 사건 표가 바뀌므로 행 수 불변 검사 뒤에 둔다) ----

    @Test
    @Order(12)
    void withdrawalKnownAfterRecordingRejectsTheSameClaim() throws Exception {
        ObjectNode body = partialBody();
        body.put("business_date", "2026-10-07");
        reseal(body);
        jdbc.sql("""
                insert into internal_notice_lifecycle_event values
                ('notice-event:%s','SYNTHETIC_INTERNAL','SIN-PREPAYMENT-FEE-V2','WITHDRAWN','2026-10-07T00:00:00Z','합성 철회 사건','sha256:%s')
                """.formatted("7".repeat(32), "7".repeat(64))).update();
        CLOCK.set(Instant.parse("2026-10-07T01:00:00Z"));
        try {
            assertRejected(body, 422, "PREPARATION_NOT_USABLE");
            assertEquals(0, count("consultation_preparation where preparation_id = '" + body.get("preparation_id").stringValue() + "'"));
        } finally {
            CLOCK.reset();
        }
    }

    // ---- AC-17: 필수 공문군이 없는 매핑에서는 READY를 허용하지 않는다(마지막 순서: 활성 매핑이 바뀐다) ----

    @Test
    @Order(13)
    void mappingWithoutRequiredFamilyNeverAllowsReady() throws Exception {
        String optionalHash = "sha256:" + "d".repeat(64);
        for (String family : List.of(PreparationScenario.PREPAYMENT, PreparationScenario.SELLER)) {
            jdbc.sql("insert into consultation_family_mapping values (:hash, 'v2', 'kb-seller-loan', :family, false, 0, '2026-10-06T04:00:00Z')")
                    .param("hash", optionalHash).param("family", family).update();
        }
        ObjectNode body = partialBody();
        body.put("family_mapping_hash", optionalHash);
        for (JsonNode section : body.get("sections")) ((ObjectNode) section).put("required", false);
        body.put("status", "READY");
        body.put("preparation_complete", true);
        assertRejected(reseal(body), 422, "NO_REQUIRED_FAMILY");
    }

    // ---- 기록 본문 조립(AI 서비스와 같은 방식: Tool 1·2 응답 기준) ----

    private ObjectNode partialBody() throws Exception {
        JsonNode prepayment = mapper.readTree(tool("applicable_checklist", Map.of("familyId", PreparationScenario.PREPAYMENT, "businessDate", "2026-10-06"), TOOL_TOKEN).body());
        assertTrue(prepayment.get("usable").booleanValue(), prepayment.toString());
        JsonNode seller = mapper.readTree(tool("applicable_checklist", Map.of("familyId", PreparationScenario.SELLER, "businessDate", "2026-10-06"), TOOL_TOKEN).body());
        assertFalse(seller.get("usable").booleanValue(), seller.toString());

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
        body.put("consultation_id", "test-consultation");
        body.put("status", "PARTIAL");
        body.put("preparation_complete", false);
        ArrayNode sections = body.putArray("sections");

        ObjectNode ready = sections.addObject();
        ready.put("family_id", PreparationScenario.PREPAYMENT);
        ready.put("required", true);
        ready.put("status", "READY");
        ready.putNull("hold_kind");
        ready.putNull("hold_claim_basis");
        ready.put("evaluated_at", prepayment.get("evaluatedAt").stringValue());
        ready.put("selected_notice_id", prepayment.get("selectedNotice").get("noticeId").stringValue());
        JsonNode approved = prepayment.get("approvedChecklist");
        ready.put("approved_checklist_version_id", approved.get("approvedChecklistVersionId").stringValue());
        ready.put("decision_id", approved.get("decisionId").stringValue());
        ArrayNode ruleIds = ready.putArray("item_rule_version_ids");
        ArrayNode evidenceHashes = ready.putArray("item_evidence_hashes");
        for (JsonNode item : approved.get("items")) {
            String ruleId = item.get("sourceRuleVersionId").stringValue();
            JsonNode evidence = mapper.readTree(tool("rule_evidence", Map.of("familyId", PreparationScenario.PREPAYMENT, "ruleVersionId", ruleId), TOOL_TOKEN).body());
            ruleIds.add(ruleId);
            evidenceHashes.add(evidence.get("evidenceHash").stringValue());
        }
        ready.putArray("blocking_reasons");
        ready.put("tool_response_hash", "sha256:" + "1".repeat(64));

        ObjectNode hold = sections.addObject();
        hold.put("family_id", PreparationScenario.SELLER);
        hold.put("required", true);
        hold.put("status", "HOLD");
        hold.put("hold_kind", "CORE_DECISION");
        hold.put("hold_claim_basis", "CORE_REPORTED");
        hold.put("evaluated_at", seller.get("evaluatedAt").stringValue());
        hold.put("selected_notice_id", seller.get("selectedNotice").get("noticeId").stringValue());
        hold.putNull("approved_checklist_version_id");
        hold.putNull("decision_id");
        hold.putArray("item_rule_version_ids");
        hold.putArray("item_evidence_hashes");
        ArrayNode reasons = hold.putArray("blocking_reasons");
        seller.get("blockingReasons").forEach(reason -> reasons.add(reason.stringValue()));
        hold.put("tool_response_hash", "sha256:" + "2".repeat(64));
        return reseal(body);
    }

    private ObjectNode reseal(ObjectNode body) {
        body.put("preparation_id", PreparationScenario.preparationId(mapper, body));
        return body;
    }

    private ObjectNode withNewRun(ObjectNode body) {
        ObjectNode copy = body.deepCopy();
        copy.put("run_id", "consultation-preparation-run:" + UUID.randomUUID().toString().replace("-", ""));
        return copy;
    }

    private static ObjectNode readySection(ObjectNode body) {
        return (ObjectNode) body.get("sections").get(0);
    }

    private static ObjectNode holdSection(ObjectNode body) {
        return (ObjectNode) body.get("sections").get(1);
    }

    private void assertRejected(ObjectNode body, int status, String code) throws Exception {
        HttpResponse<String> response = record(body, RECORD_TOKEN);
        assertProblem(response, status, code);
        assertEquals("REJECTED | " + code, single("select outcome || ' | ' || error_code from consultation_preparation_run where run_id = '" + body.get("run_id").stringValue() + "'"));
        assertEquals(0, count("consultation_preparation where preparation_id = '" + body.get("preparation_id").stringValue() + "' and status = 'READY' and preparation_complete and required_hold_count = 0 and application_id = 'NONE'"));
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, mapper.readTree(response.body()).get("code").stringValue(), response.body());
    }

    private HttpResponse<String> record(JsonNode body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/consultation-preparations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> tool(String name, Map<String, String> body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/tools/" + name))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Integer> businessCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : PreparationScenario.BUSINESS_TABLES) {
            if (table.equals("tool_call_audit")) continue; // Tool 호출은 이 테스트가 직접 하므로 늘어난다
            counts.put(table, count(table));
        }
        return counts;
    }

    private int count(String tableAndFilter) {
        return jdbc.sql("select count(*) from " + tableAndFilter).query(Integer.class).single();
    }

    private String single(String sql) {
        return jdbc.sql(sql).query(String.class).optional().orElse(null);
    }

    private static String sqlState(Runnable action) {
        try {
            action.run();
            return "NO_ERROR";
        } catch (RuntimeException exception) {
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof java.sql.SQLException sql) return sql.getSQLState();
                cause = cause.getCause();
            }
            return "UNKNOWN";
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
