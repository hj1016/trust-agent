package com.trustagent.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            assertEquals("2026-10-01", hit.get("_source").get("effective_from").stringValue());
            assertTrue(hit.get("_source").get("effective_to").isNull());
            assertEquals("SIN-PREPAYMENT-FEE", hit.get("_source").get("family_id").stringValue());
            assertFalse(hit.get("_source").get("evidence_text").stringValue().isBlank());
        }
    }

    @Test
    @Order(4)
    void approvingAnotherFamilyAddsItsRulesAndReplacesIndex() {
        PreparationScenario.approveSeller(jdbc, mapper, PreparationScenario.manager(dataSource), CLOCK, state.sellerProposalId());
        SearchReindexService.Result result = service.reindex();
        assertEquals(SearchReindexService.Outcome.CREATED, result.outcome());
        assertEquals(List.of(firstIndex), result.removedIndices());
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
    @Order(5)
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
    @Order(6)
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
    @Order(7)
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
