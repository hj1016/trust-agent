package com.trustagent.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.preparation.PreparationScenario;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
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
 * TASK-016 AC-01(멱등), AC-02(색인 불변식: 승인 항목 규칙만, 미승인·FIXTURE·철회 제외, 상태 변화 반영), AC-13(읽기 전용 사용자).
 * TASK-015 상태(중도상환수수료 v2 승인, 셀러론 v2 검토 대기)에서 시작한다.
 */
@SpringBootTest(classes = {TrustAgentCoreApplication.class, SearchReindexIntegrationTest.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SearchReindexIntegrationTest {

    private static final PreparationScenario.AdjustableClock CLOCK = new PreparationScenario.AdjustableClock();
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
        ElasticsearchTestContainer.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("management.server.port", () -> 0);
        registry.add("trust-agent.public-evidence.max-confirmation-age", () -> "30d");
        registry.add("trust-agent.search.base-url", ElasticsearchTestContainer::baseUrl);
        registry.add("trust-agent.search.username", () -> ElasticsearchTestContainer.REINDEX_USER);
        registry.add("trust-agent.search.password", () -> ElasticsearchTestContainer.REINDEX_PASSWORD);
    }

    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper mapper;
    @Autowired private PublicProductObservedStateService publicProducts;
    @Autowired private SearchReindexService service;
    @Autowired private ElasticsearchClient elasticsearch;
    @Autowired private SearchProperties properties;

    private JdbcClient jdbc;
    private PreparationScenario.State state;
    private String firstIndex;

    @BeforeAll
    void loadScenario() {
        jdbc = JdbcClient.create(dataSource);
        Path root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        state = PreparationScenario.load(jdbc, mapper, PreparationScenario.manager(dataSource), CLOCK, publicProducts, root);
    }

    @Test
    @Order(1)
    void reindexIndexesOnlyApprovedChecklistRules() {
        SearchReindexService.Result result = service.reindex();
        assertEquals(SearchReindexService.Outcome.CREATED, result.outcome());
        assertEquals(properties.alias(), result.alias());
        firstIndex = result.indexName();

        Set<String> approvedRules = approvedRuleIds();
        assertEquals(3, approvedRules.size(), approvedRules.toString());
        assertEquals(approvedRules, indexedIds());
        assertEquals(3, result.documentCount());
        assertEquals(result.indexName(), elasticsearch.aliasTarget(properties.alias()).orElseThrow());

        Set<String> excluded = fixtureAndUnapprovedRuleIds();
        assertFalse(excluded.isEmpty());
        for (String rule : excluded) {
            assertFalse(indexedIds().contains(rule), "미승인·FIXTURE 규칙이 색인에 있다: " + rule);
        }
        JsonNode meta = elasticsearch.mappingMeta(result.indexName());
        assertEquals(result.contentHash(), meta.get("content_hash").stringValue());
        assertEquals("main", meta.get("workspace_id").stringValue());
        assertEquals(SearchDocuments.REINDEX_VERSION, meta.get("reindex_version").stringValue());
        assertEquals(List.of(result.indexName()), elasticsearch.indicesWithPrefix(properties.indexNamePrefix()));
    }

    @Test
    @Order(2)
    void reindexAgainIsIdempotent() {
        SearchReindexService.Result again = service.reindex();
        assertEquals(SearchReindexService.Outcome.ALREADY_CURRENT, again.outcome());
        assertEquals(firstIndex, again.indexName());
        assertEquals(3, elasticsearch.count(properties.alias()));
        assertEquals(List.of(firstIndex), elasticsearch.indicesWithPrefix(properties.indexNamePrefix()));
        assertEquals(firstIndex, elasticsearch.aliasTarget(properties.alias()).orElseThrow());
    }

    @Test
    @Order(3)
    void documentFieldsStayWithinToolScopeAndSourceHashIsDeterministic() {
        JsonNode hits = elasticsearch.search(properties.alias(), mapper.readTree("{\"size\":100,\"query\":{\"match_all\":{}}}")).path("hits").path("hits");
        assertEquals(3, hits.size());
        for (JsonNode hit : hits) {
            Set<String> fields = new TreeSet<>();
            hit.get("_source").propertyNames().forEach(fields::add);
            assertEquals(new TreeSet<>(SearchDocuments.FIELDS), fields);
            assertEquals("SYNTHETIC_INTERNAL", hit.get("_source").get("dataset_class").stringValue());
            assertTrue(hit.get("_source").get("synthetic").booleanValue());
            assertEquals(1, hit.get("_source").get("approvals").size());
            assertEquals("2026-10-01", hit.get("_source").get("approvals").get(0).get("effective_from").stringValue());
            assertTrue(hit.get("_source").get("approvals").get(0).get("effective_to").isNull());
            assertEquals("2026-10-01", hit.get("_source").get("effective_ranges").get(0).get("gte").stringValue());
            assertTrue(!hit.get("_source").get("effective_ranges").get(0).has("lt"), "무기한");
            assertEquals("SIN-PREPAYMENT-FEE", hit.get("_source").get("family_id").stringValue());
            assertFalse(hit.get("_source").get("evidence_text").stringValue().isBlank());
        }
    }

    @Test
    @Order(4)
    void sameRuleVersionInTwoScheduleRangesKeepsBothRangesAndFiltersByDateWithGap() {
        // 같은 승인 버전이 최신 일정 revision의 두 구간([09-15,09-20), [10-01,∞))에 있으면 문서 하나에 구간 둘과 승인 대응이 들어간다.
        // 구간 사이 공백(09-20~09-30)과 경계(시작 포함, 종료 제외), 무기한 종료를 ES date_range 질의로 확인한다.
        String versionId = jdbc.sql("""
                select d.approved_checklist_version_id from human_review_decision d
                join approved_checklist_version v on v.approved_checklist_version_id = d.approved_checklist_version_id
                where v.family_id = 'SIN-PREPAYMENT-FEE' and d.decision = 'APPROVE'
                """).query(String.class).single();
        String latest = jdbc.sql("""
                select r.schedule_revision_id from approved_checklist_schedule_revision r
                where r.family_id = 'SIN-PREPAYMENT-FEE' and not exists (
                    select 1 from approved_checklist_schedule_revision s where s.supersedes_schedule_revision_id = r.schedule_revision_id)
                """).query(String.class).single();
        String revision = "checklist-schedule:" + "d".repeat(32);
        jdbc.sql("insert into approved_checklist_schedule_revision values (:id, 'SYNTHETIC_INTERNAL', 'SIN-PREPAYMENT-FEE', :supersedes, '2026-10-06T02:00:00Z', :hash)")
                .param("id", revision).param("supersedes", latest).param("hash", "sha256:" + "d".repeat(64)).update();
        jdbc.sql("insert into approved_checklist_schedule_entry values (:id, 'SIN-PREPAYMENT-FEE', 0, :version, '2026-09-15', '2026-09-20', :hash)")
                .param("id", revision).param("version", versionId).param("hash", "sha256:" + "d".repeat(64)).update();
        jdbc.sql("insert into approved_checklist_schedule_entry values (:id, 'SIN-PREPAYMENT-FEE', 1, :version, '2026-10-01', null, :hash)")
                .param("id", revision).param("version", versionId).param("hash", "sha256:" + "d".repeat(64)).update();

        List<IndexedRule> rows = new RuleEvidenceIndexRepository(jdbc).loadApprovedRules();
        assertEquals(6, rows.stream().filter(rule -> rule.familyId().equals("SIN-PREPAYMENT-FEE")).count(), "규칙 3개 × 구간 2개");
        SearchReindexService.Result result = service.reindex();
        assertEquals(SearchReindexService.Outcome.CREATED, result.outcome(), "적용기간이 바뀌었으므로 새 색인");
        assertEquals(3, result.documentCount(), "문서는 규칙 version당 하나");

        JsonNode hits = elasticsearch.search(properties.alias(), mapper.readTree("{\"size\":100,\"query\":{\"term\":{\"family_id\":\"SIN-PREPAYMENT-FEE\"}}}")).path("hits").path("hits");
        assertEquals(3, hits.size());
        for (JsonNode hit : hits) {
            JsonNode approvals = hit.get("_source").get("approvals");
            assertEquals(2, approvals.size());
            assertEquals(versionId, approvals.get(0).get("approved_checklist_version_id").stringValue());
            assertEquals("2026-09-15", approvals.get(0).get("effective_from").stringValue());
            assertEquals("2026-09-20", approvals.get(0).get("effective_to").stringValue());
            assertEquals("2026-10-01", approvals.get(1).get("effective_from").stringValue());
            assertTrue(approvals.get(1).get("effective_to").isNull());
            assertEquals(2, hit.get("_source").get("effective_ranges").size());
        }
        // 날짜별 후보 수(규칙 3개): 시작 포함, 종료 제외, 공백 없음, 무기한.
        assertEquals(0, countOnDate("2026-09-14"), "시작 전");
        assertEquals(3, countOnDate("2026-09-15"), "시작일 포함");
        assertEquals(3, countOnDate("2026-09-19"));
        assertEquals(0, countOnDate("2026-09-20"), "종료일 제외");
        assertEquals(0, countOnDate("2026-09-25"), "구간 사이 공백");
        assertEquals(0, countOnDate("2026-09-30"));
        assertEquals(3, countOnDate("2026-10-01"), "두 번째 구간 시작");
        assertEquals(3, countOnDate("2027-12-31"), "종료일 없음 = 무기한");
    }

    /** 검색 API가 쓸 업무일 필터와 같은 질의: date_range 필드에 점 하나를 intersects로 묻는다. */
    private int countOnDate(String businessDate) {
        JsonNode query = mapper.readTree("{\"size\":0,\"query\":{\"bool\":{\"filter\":[{\"term\":{\"family_id\":\"SIN-PREPAYMENT-FEE\"}},"
                + "{\"range\":{\"effective_ranges\":{\"gte\":\"" + businessDate + "\",\"lte\":\"" + businessDate + "\",\"relation\":\"intersects\"}}}]}}}");
        return elasticsearch.search(properties.alias(), query).path("hits").path("total").path("value").intValue();
    }

    @Test
    @Order(5)
    void approvingAnotherFamilyAddsItsRulesAndReplacesIndex() {
        String before = elasticsearch.aliasTarget(properties.alias()).orElseThrow();
        PreparationScenario.approveSeller(jdbc, mapper, PreparationScenario.manager(dataSource), CLOCK, state.sellerProposalId());
        SearchReindexService.Result result = service.reindex();
        assertEquals(SearchReindexService.Outcome.CREATED, result.outcome());
        assertEquals(List.of(before), result.removedIndices());
        Set<String> approved = approvedRuleIds();
        assertEquals(approved, indexedIds());
        assertTrue(approved.size() > 3, "셀러론 승인 항목 규칙이 더해져야 한다");
        assertEquals(List.of(result.indexName()), elasticsearch.indicesWithPrefix(properties.indexNamePrefix()));
        Set<String> families = new HashSet<>();
        elasticsearch.search(properties.alias(), mapper.readTree("{\"size\":100,\"query\":{\"match_all\":{}}}")).path("hits").path("hits")
                .forEach(hit -> families.add(hit.get("_source").get("family_id").stringValue()));
        assertEquals(Set.of("SIN-PREPAYMENT-FEE", "SIN-SELLER-CHECKLIST"), families);
    }

    @Test
    @Order(6)
    void withdrawnNoticeRulesDisappearAfterReindex() {
        jdbc.sql("""
                insert into internal_notice_lifecycle_event values
                ('notice-event:%s','SYNTHETIC_INTERNAL','SIN-PREPAYMENT-FEE-V2','WITHDRAWN','2026-10-07T00:00:00Z','합성 철회 사건','sha256:%s')
                """.formatted("e".repeat(32), "e".repeat(64))).update();
        SearchReindexService.Result result = service.reindex();
        assertEquals(SearchReindexService.Outcome.CREATED, result.outcome());
        Set<String> ids = indexedIds();
        Set<String> families = new HashSet<>();
        elasticsearch.search(properties.alias(), mapper.readTree("{\"size\":100,\"query\":{\"match_all\":{}}}")).path("hits").path("hits")
                .forEach(hit -> families.add(hit.get("_source").get("family_id").stringValue()));
        assertEquals(Set.of("SIN-SELLER-CHECKLIST"), families, "철회된 공문의 규칙은 색인에서 사라진다");
        assertEquals(approvedRuleIds(), ids);
    }

    @Test
    @Order(7)
    void analyzerChangeIsNotServedByTheExistingIndex() {
        // 같은 문서, 분석기만 nori로 바꾸면 생략하지 않고 새 색인을 만들려 한다. 이 환경에는 nori 플러그인이 없어 생성이 실패하고 기존 alias는 그대로다.
        String current = elasticsearch.aliasTarget(properties.alias()).orElseThrow();
        SearchProperties nori = new SearchProperties(properties.baseUrl(), properties.username(), properties.password(), properties.indexPrefix(),
                properties.workspaceId(), SearchProperties.ANALYZER_NORI, properties.timeout());
        SearchReindexService noriService = new SearchReindexService(JdbcClient.create(dataSource), elasticsearch, nori, mapper, CLOCK);
        SearchIndexException error = assertThrows(SearchIndexException.class, noriService::reindex);
        assertEquals("SEARCH_INDEX_CREATE_FAILED", error.code(), "생략(ALREADY_CURRENT)이 아니라 새 색인 생성을 시도했다");
        assertTrue(error.getMessage().contains("nori"), error.getMessage());
        assertEquals(current, elasticsearch.aliasTarget(properties.alias()).orElseThrow());
        assertEquals(List.of(current), elasticsearch.indicesWithPrefix(properties.indexNamePrefix()));
        assertEquals(SearchReindexService.Outcome.ALREADY_CURRENT, service.reindex().outcome(), "standard로 돌아오면 기존 색인을 그대로 쓴다");
    }

    @Test
    @Order(8)
    void currentIndexWithUnexpectedDocumentCountIsNeverDeletedBeforeSwap() {
        // 현재 색인 이름은 예상과 같지만 문서가 하나 지워진 상태. 실패 주입 시 alias·검색 가능 상태가 유지되고, 성공 시 다른 이름의 새 색인으로 전환한다.
        String current = elasticsearch.aliasTarget(properties.alias()).orElseThrow();
        String victim = indexedIds().iterator().next();
        ElasticsearchClient.Response deleted = elasticsearch.exchange("DELETE", "/" + current + "/_doc/" + victim + "?refresh=true", null, null);
        assertEquals(200, deleted.status());
        long expected = new SearchDocuments(mapper).build(new RuleEvidenceIndexRepository(jdbc).loadApprovedRules(), CLOCK.instant()).documents().size();
        assertEquals(expected - 1, elasticsearch.count(current));

        ElasticsearchClient failing = new ElasticsearchClient(ElasticsearchTestContainer.baseUrl(), ElasticsearchTestContainer.REINDEX_USER,
                ElasticsearchTestContainer.REINDEX_PASSWORD, Duration.ofSeconds(10), mapper) {
            @Override
            public void refresh(String index) {
                throw new SearchIndexException("SEARCH_REFRESH_FAILED", "주입한 실패");
            }
        };
        assertThrows(SearchIndexException.class, () -> new SearchReindexService(JdbcClient.create(dataSource), failing, properties, mapper, CLOCK).reindex());
        assertEquals(current, elasticsearch.aliasTarget(properties.alias()).orElseThrow(), "실패해도 현재 색인은 지워지지 않는다");
        assertEquals(expected - 1, elasticsearch.count(properties.alias()), "검색 가능 상태 유지");
        assertEquals(List.of(current), elasticsearch.indicesWithPrefix(properties.indexNamePrefix()), "부분 색인은 남지 않는다");

        SearchReindexService.Result repaired = service.reindex();
        assertEquals(SearchReindexService.Outcome.CREATED, repaired.outcome());
        assertNotEquals(current, repaired.indexName(), "현재 이름과 다른 이름으로 만든 뒤 전환한다");
        assertTrue(repaired.indexName().startsWith(current + "-r"), repaired.indexName());
        assertEquals(List.of(current), repaired.removedIndices());
        assertEquals(expected, elasticsearch.count(properties.alias()));
        SearchReindexService.Result again = service.reindex();
        assertEquals(SearchReindexService.Outcome.ALREADY_CURRENT, again.outcome(), "복구 접미사 이름이라도 내용·설정·문서 수가 맞으면 생략");
        assertEquals(repaired.indexName(), again.indexName());
        assertEquals(List.of(repaired.indexName()), elasticsearch.indicesWithPrefix(properties.indexNamePrefix()));
    }

    @Test
    @Order(9)
    void failedReindexKeepsCurrentIndexAndAliasAndRemovesPartialIndex() {
        // 내용이 바뀐 상태(셀러론 v2도 철회 → 문서 0개)에서 refresh 단계가 실패하면 alias와 기존 색인(셀러론 3개)이 그대로 남아야 한다.
        jdbc.sql("""
                insert into internal_notice_lifecycle_event values
                ('notice-event:%s','SYNTHETIC_INTERNAL','SIN-SELLER-CHECKLIST-V2','WITHDRAWN','2026-10-07T01:00:00Z','합성 철회 사건 2','sha256:%s')
                """.formatted("f".repeat(32), "f".repeat(64))).update();
        String currentIndex = elasticsearch.aliasTarget(properties.alias()).orElseThrow();
        long currentCount = elasticsearch.count(properties.alias());
        assertTrue(currentCount > 0);
        ElasticsearchClient failing = new ElasticsearchClient(ElasticsearchTestContainer.baseUrl(), ElasticsearchTestContainer.REINDEX_USER,
                ElasticsearchTestContainer.REINDEX_PASSWORD, Duration.ofSeconds(10), mapper) {
            @Override
            public void refresh(String index) {
                throw new SearchIndexException("SEARCH_REFRESH_FAILED", "주입한 실패");
            }
        };
        SearchReindexService failingService = new SearchReindexService(JdbcClient.create(dataSource), failing, properties, mapper, CLOCK);
        SearchIndexException error = assertThrows(SearchIndexException.class, failingService::reindex);
        assertEquals("SEARCH_REFRESH_FAILED", error.code());
        assertEquals(currentIndex, elasticsearch.aliasTarget(properties.alias()).orElseThrow(), "실패하면 alias가 바뀌지 않는다");
        assertEquals(currentCount, elasticsearch.count(properties.alias()), "기존 색인이 그대로 남는다");
        assertEquals(List.of(currentIndex), elasticsearch.indicesWithPrefix(properties.indexNamePrefix()), "부분 색인은 지워진다");

        // 정상 재색인은 문서 0개의 새 색인으로 교체한다(두 공문 모두 철회).
        SearchReindexService.Result recovered = service.reindex();
        assertEquals(SearchReindexService.Outcome.CREATED, recovered.outcome());
        assertEquals(0, recovered.documentCount());
        assertEquals(List.of(currentIndex), recovered.removedIndices());
    }

    @Test
    @Order(10)
    void readOnlySearchUserCannotWriteButCanSearch() {
        ElasticsearchClient searchUser = new ElasticsearchClient(ElasticsearchTestContainer.baseUrl(), ElasticsearchTestContainer.SEARCH_USER,
                ElasticsearchTestContainer.SEARCH_PASSWORD, Duration.ofSeconds(10), mapper);
        ElasticsearchClient.Response write = searchUser.exchange("PUT", "/" + properties.alias() + "/_doc/forged",
                "{\"rule_version_id\":\"forged\"}", "application/json");
        assertEquals(403, write.status());
        SearchIndexException denied = assertThrows(SearchIndexException.class, () -> searchUser.createIndex(properties.indexNamePrefix() + "forged",
                mapper.readTree("{\"settings\":{\"index\":{\"number_of_shards\":1}}}")));
        assertEquals(403, denied.httpStatus());
        JsonNode hits = searchUser.search(properties.alias(), mapper.readTree("{\"query\":{\"match_all\":{}}}"));
        assertTrue(hits.path("hits").path("total").path("value").asLong() >= 0, "읽기는 허용된다(앞 단계에서 문서가 0개일 수 있다)");
        assertFalse(indexedIds().contains("forged"));
    }

    private Set<String> indexedIds() {
        Set<String> ids = new TreeSet<>();
        elasticsearch.search(properties.alias(), mapper.readTree("{\"size\":100,\"query\":{\"match_all\":{}},\"_source\":false}"))
                .path("hits").path("hits").forEach(hit -> ids.add(hit.get("_id").stringValue()));
        return ids;
    }

    private Set<String> approvedRuleIds() {
        return new TreeSet<>(jdbc.sql("""
                select i.source_rule_version_id from approved_checklist_item i
                join human_review_decision d on d.approved_checklist_version_id = i.approved_checklist_version_id
                join approved_checklist_version v on v.approved_checklist_version_id = i.approved_checklist_version_id
                where d.decision = 'APPROVE'
                  and not exists (select 1 from internal_notice_lifecycle_event le where le.notice_id = v.notice_id and le.event_type = 'WITHDRAWN')
                """).query(String.class).list());
    }

    private Set<String> fixtureAndUnapprovedRuleIds() {
        Set<String> ids = new TreeSet<>(jdbc.sql("""
                select i.source_rule_version_id from approved_checklist_item i
                where i.source_rule_version_id is not null
                  and not exists (select 1 from human_review_decision d where d.approved_checklist_version_id = i.approved_checklist_version_id)
                """).query(String.class).list());
        ids.addAll(jdbc.sql("""
                select rv.rule_version_id from internal_policy_rule_version rv
                where not exists (select 1 from approved_checklist_item i
                    join human_review_decision d on d.approved_checklist_version_id = i.approved_checklist_version_id
                    where i.source_rule_version_id = rv.rule_version_id)
                """).query(String.class).list());
        ids.removeAll(approvedRuleIds());
        return ids;
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
