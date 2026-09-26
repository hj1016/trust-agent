package com.trustagent.core.publicproduct.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.publicproduct.baseline.BaselineImporter;
import com.trustagent.core.web.RequestTraceFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
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
        classes = {TrustAgentCoreApplication.class, PublicProductObservedStateIntegrationTest.FixedClockConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublicProductObservedStateIntegrationTest {

    private static final Instant EVALUATED_AT = Instant.parse("2026-09-23T12:00:00Z");
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
        registry.add("trust-agent.public-evidence.freshness-policy-version", () -> "public-evidence-confirmation-v1");
        registry.add("trust-agent.public-evidence.max-confirmation-age", () -> "7d");
        registry.add("management.server.port", () -> 0);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PublicProductObservedStateRepository repository;

    @Value("${local.server.port}")
    private int port;

    @BeforeAll
    void importBaselineAndAddDelayedExtraction() throws Exception {
        var importer = new BaselineImporter(
                JdbcClient.create(dataSource),
                objectMapper,
                new DataSourceTransactionManager(dataSource));
        importer.importBaseline(
                Path.of(System.getProperty("trustAgent.repositoryRoot")),
                "baseline:12121212121212121212121212121212");
        insertDelayedExtractionFixture();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void currentSellerLoanResponsePreservesTwoBillionCorporateLimitAndNullRateDate() throws Exception {
        HttpResponse<String> response = get("/api/v1/public-products/kb-seller-loan/observed-state");
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(EVALUATED_AT, Instant.parse(body.get("evaluatedAt").stringValue()));
        assertEquals(EVALUATED_AT, Instant.parse(body.get("asOf").stringValue()));
        assertFalse(body.get("historicalQuery").booleanValue());
        assertEquals("CONFIRMED", body.get("freshnessStatus").stringValue());
        assertTrue(body.get("publicEvidenceConfirmationAllowed").booleanValue());
        assertEquals(
                "public-evidence-confirmation-v1",
                body.get("freshnessPolicyVersion").stringValue());
        assertEquals("PT168H", body.get("maxConfirmationAge").stringValue());
        JsonNode corporateLimit = findFact(body, "max_limit_corporate_krw");
        assertEquals(2_000_000_000L, corporateLimit.get("integerValue").longValue());
        assertEquals("KRW", corporateLimit.get("unit").stringValue());
        assertTrue(body.get("rateQuote").get("advertisedRateReferenceDate").isNull());
        assertTrue(body.get("terms").get("effectiveFrom").isNull());
        assertTrue(body.get("terms").get("effectiveTo").isNull());
        JsonNode locator = body.get("evidence").get("facts").get(0).get("locators").get(0);
        assertTrue(locator.get("evidenceText").stringValue().length() > 0);
        assertTrue(locator.get("evidenceHash").stringValue().matches("sha256:[a-f0-9]{64}"));
        assertTrue(locator.get("sourceUrl").stringValue().startsWith("https://"));
    }

    @Test
    void allThreeCommittedProductsReturnObservedState() throws Exception {
        for (String productKey : java.util.List.of(
                "small-business-credit", "boss-plus-overdraft", "kb-seller-loan")) {
            HttpResponse<String> response = get(
                    "/api/v1/public-products/" + productKey + "/observed-state");
            assertEquals(200, response.statusCode(), productKey);
            assertEquals(
                    productKey,
                    objectMapper.readTree(response.body()).get("productKey").stringValue());
        }
    }

    @Test
    void historicalQueryNeverAllowsConfirmationAndOffsetInputIsNormalized() throws Exception {
        HttpResponse<String> response = get(
                "/api/v1/public-products/kb-seller-loan/observed-state?asOf=2026-09-21T14:11:37%2B09:00");
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals("2026-09-21T05:11:37Z", body.get("asOf").stringValue());
        assertTrue(body.get("historicalQuery").booleanValue());
        assertFalse(body.get("publicEvidenceConfirmationAllowed").booleanValue());
        assertTrue(strings(body.get("confirmationBlockingReasons")).contains("HISTORICAL_AS_OF"));
    }

    @Test
    void futureInvalidAndUnknownRequestsHaveStableErrorCodes() throws Exception {
        assertErrorCode(
                get("/api/v1/public-products/kb-seller-loan/observed-state?asOf=2026-09-23T12:00:01Z"),
                400,
                "FUTURE_AS_OF_NOT_ALLOWED");
        assertErrorCode(
                get("/api/v1/public-products/kb-seller-loan/observed-state?asOf=not-a-time"),
                400,
                "INVALID_AS_OF");
        assertErrorCode(
                get("/api/v1/public-products/kb-seller-loan/observed-state?asOf="),
                400,
                "INVALID_AS_OF");
        assertErrorCode(
                get("/api/v1/public-products/not-registered/observed-state"),
                404,
                "PRODUCT_NOT_FOUND");
    }

    @Test
    void evidenceIsInvisibleUntilItsSuccessfulExtractionAttemptTime() throws Exception {
        JsonNode before = objectMapper.readTree(get(
                "/api/v1/public-products/kb-seller-loan/observed-state?asOf=2026-09-23T09:30:00Z").body());
        assertEquals("2026-09-23T09:00:00Z", before.get("latestObservationAt").stringValue());
        assertEquals("PENDING_EXTRACTION", before.get("freshnessStatus").stringValue());
        assertEquals(
                "2026-09-21T05:11:37Z",
                before.get("confirmedObservation").get("observedAt").stringValue());
        assertFalse(before.get("evidence").get("versionEvidenceId").stringValue().equals(
                "evidence:kb-seller-loan:34343434343434343434343434343434"));

        JsonNode atAttempt = objectMapper.readTree(get(
                "/api/v1/public-products/kb-seller-loan/observed-state?asOf=2026-09-23T10:00:00Z").body());
        assertEquals(
                "2026-09-23T09:00:00Z",
                atAttempt.get("confirmedObservation").get("observedAt").stringValue());
        assertEquals(
                "2026-09-23T10:00:00Z",
                atAttempt.get("evidence").get("availableAt").stringValue());
        assertEquals(
                "evidence:kb-seller-loan:34343434343434343434343434343434",
                atAttempt.get("evidence").get("versionEvidenceId").stringValue());
    }

    @Test
    void observationIdProvidesStableOrderingWhenObservedTimesAreEqual() {
        var latest = repository.findLatestObservation("tie-product", EVALUATED_AT).orElseThrow();

        assertEquals("obs:tie-product:22222222222222222222222222222222", latest.observationId());
        assertEquals(Instant.parse("2026-09-23T08:00:00Z"), latest.observedAt());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void assertErrorCode(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(code, body.get("code").stringValue());
        String traceId = body.get("traceId").stringValue();
        assertTrue(traceId.matches("[a-f0-9]{32}"));
        assertEquals(traceId, response.headers().firstValue(RequestTraceFilter.TRACE_ID_HEADER).orElseThrow());
    }

    private static JsonNode findFact(JsonNode body, String factKey) {
        for (JsonNode fact : body.get("terms").get("facts")) {
            if (factKey.equals(fact.get("factKey").stringValue())) return fact;
        }
        throw new AssertionError("fact가 없습니다: " + factKey);
    }

    private static java.util.List<String> strings(JsonNode array) {
        java.util.List<String> result = new java.util.ArrayList<>();
        array.forEach(value -> result.add(value.stringValue()));
        return result;
    }

    private void insertDelayedExtractionFixture() throws Exception {
        String hash = "sha256:" + "d".repeat(64);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("set constraints all deferred");
                statement.execute("""
                        insert into public_snapshot values (
                          'sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                          'PUBLIC_KB', false, 'kb-seller-loan',
                          'https://obank.kbstar.com/quics?page=C060709', 'LOCAL_PRIVATE',
                          'public-kb/sha256/dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd.html',
                          'text/html', 100, '%s')
                        """.formatted(hash));
                statement.execute("""
                        insert into collection_attempt values (
                          'collect:kb-seller-loan:12121212121212121212121212121212', 'DERIVED',
                          'run:12121212121212121212121212121212', 'kb-seller-loan', 1,
                          '2026-09-23T09:00:00Z', 'SUCCEEDED',
                          'obs:kb-seller-loan:12121212121212121212121212121212', null, null, '%s')
                        """.formatted(hash));
                statement.execute("""
                        insert into public_observation values (
                          'obs:kb-seller-loan:12121212121212121212121212121212', 'PUBLIC_KB', false,
                          'collect:kb-seller-loan:12121212121212121212121212121212',
                          'run:12121212121212121212121212121212', 'kb-seller-loan',
                          'https://obank.kbstar.com/quics?page=C060709',
                          'https://obank.kbstar.com/quics?page=C060709', '2026-09-23T09:00:00Z',
                          'HTTP_DOWNLOAD', null,
                          'datasets/public/kb/manifests/2026-09-23/kb-seller-loan--delayed.manifest.json',
                          'sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', '%s')
                        """.formatted(hash));
                String versionId;
                try (ResultSet result = statement.executeQuery("""
                        select product_terms_version_id from product_terms_version
                        where product_key = 'kb-seller-loan'
                        """)) {
                    if (!result.next()) throw new IllegalStateException("셀러론 terms version이 없습니다.");
                    versionId = result.getString(1);
                }
                statement.execute("""
                        insert into version_evidence values (
                          'evidence:kb-seller-loan:34343434343434343434343434343434',
                          'PUBLIC_KB', false,
                          'obs:kb-seller-loan:12121212121212121212121212121212',
                          '%s', 'kb-seller-loan',
                          'sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                          'public-kb-html-v1', '%s')
                        """.formatted(versionId, hash));
                statement.execute("""
                        insert into fact_evidence_locator
                        select 'evidence:kb-seller-loan:34343434343434343434343434343434',
                               product_terms_version_id, fact_id, locator_order, strategy, selector,
                               label, evidence_text, evidence_hash,
                               'sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                               'https://obank.kbstar.com/quics?page=C060709'
                        from fact_evidence_locator
                        where product_terms_version_id = '%s'
                          and version_evidence_id = (
                            select version_evidence_id from version_evidence
                            where product_terms_version_id = '%s'
                            order by version_evidence_id limit 1)
                        """.formatted(versionId, versionId));
                statement.execute("""
                        insert into observed_rate_quote values (
                          'quote:kb-seller-loan:56565656565656565656565656565656',
                          'PUBLIC_KB', false,
                          'obs:kb-seller-loan:12121212121212121212121212121212',
                          'kb-seller-loan', '연 3.85%% (쿠팡셀러 기준)', null,
                          '{"strategy":"CSS_TEXT_MATCH"}'::jsonb, null, '%s')
                        """.formatted(hash));
                statement.execute("""
                        insert into extraction_attempt values (
                          'extract:kb-seller-loan:78787878787878787878787878787878', 'DERIVED',
                          'run:78787878787878787878787878787878',
                          'obs:kb-seller-loan:12121212121212121212121212121212',
                          'kb-seller-loan', 1, '2026-09-23T10:00:00Z', 'MEASURED',
                          'public-kb-html-v1', 'SUCCEEDED', '%s',
                          'evidence:kb-seller-loan:34343434343434343434343434343434',
                          'quote:kb-seller-loan:56565656565656565656565656565656',
                          null, null, '%s')
                        """.formatted(versionId, hash));
                statement.execute("""
                        insert into public_product values (
                          'tie-product', 'PUBLIC_KB', false, '동시각 정렬 상품', '동시각상품',
                          'https://obank.kbstar.com/tie-product', '%s')
                        """.formatted(hash));
                statement.execute("""
                        insert into public_snapshot values
                          ('sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                           'PUBLIC_KB', false, 'tie-product', 'https://obank.kbstar.com/tie-product',
                           'LOCAL_PRIVATE',
                           'public-kb/sha256/cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc.html',
                           'text/html', 100, '%s'),
                          ('sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                           'PUBLIC_KB', false, 'tie-product', 'https://obank.kbstar.com/tie-product',
                           'LOCAL_PRIVATE',
                           'public-kb/sha256/eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee.html',
                           'text/html', 100, '%s')
                        """.formatted(hash, hash));
                statement.execute("""
                        insert into collection_attempt values
                          ('collect:tie-product:11111111111111111111111111111111', 'DERIVED',
                           'run:11111111111111111111111111111111', 'tie-product', 1,
                           '2026-09-23T08:00:00Z', 'SUCCEEDED',
                           'obs:tie-product:11111111111111111111111111111111', null, null, '%s'),
                          ('collect:tie-product:22222222222222222222222222222222', 'DERIVED',
                           'run:22222222222222222222222222222222', 'tie-product', 1,
                           '2026-09-23T08:00:00Z', 'SUCCEEDED',
                           'obs:tie-product:22222222222222222222222222222222', null, null, '%s')
                        """.formatted(hash, hash));
                statement.execute("""
                        insert into public_observation values
                          ('obs:tie-product:11111111111111111111111111111111', 'PUBLIC_KB', false,
                           'collect:tie-product:11111111111111111111111111111111',
                           'run:11111111111111111111111111111111', 'tie-product',
                           'https://obank.kbstar.com/tie-product', 'https://obank.kbstar.com/tie-product',
                           '2026-09-23T08:00:00Z', 'HTTP_DOWNLOAD', null,
                           'datasets/public/kb/manifests/2026-09-23/tie-product--one.manifest.json',
                           'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', '%s'),
                          ('obs:tie-product:22222222222222222222222222222222', 'PUBLIC_KB', false,
                           'collect:tie-product:22222222222222222222222222222222',
                           'run:22222222222222222222222222222222', 'tie-product',
                           'https://obank.kbstar.com/tie-product', 'https://obank.kbstar.com/tie-product',
                           '2026-09-23T08:00:00Z', 'HTTP_DOWNLOAD', null,
                           'datasets/public/kb/manifests/2026-09-23/tie-product--two.manifest.json',
                           'sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee', '%s')
                        """.formatted(hash, hash));
                connection.commit();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(EVALUATED_AT, ZoneOffset.UTC);
        }
    }
}
