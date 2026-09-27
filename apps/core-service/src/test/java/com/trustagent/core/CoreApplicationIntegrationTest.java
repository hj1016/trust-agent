package com.trustagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.config.RuntimeDatasourceProperties;
import com.trustagent.core.health.DatabaseConnectivityHealthIndicator;
import com.trustagent.core.health.ReadinessDatabaseClient;
import com.trustagent.core.health.SchemaCompatibilityHealthIndicator;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class CoreApplicationIntegrationTest {

    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withPassword("day4a-secret-sentinel-8c1f2e");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("management.server.port", () -> 0);
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private RuntimeDatasourceProperties runtimeProperties;

    @Autowired
    private DatabaseConnectivityHealthIndicator databaseConnectivity;

    @Autowired
    private SchemaCompatibilityHealthIndicator schemaCompatibility;

    @Autowired
    private ReadinessDatabaseClient readinessDatabaseClient;

    @Autowired
    private HealthEndpointGroups healthEndpointGroups;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @Value("${local.server.port}")
    private int applicationPort;

    @Value("${local.management.port}")
    private int managementPort;

    @Test
    void applicationStartsWithExpectedSchemaAndRuntimePoolSettings() throws SQLException {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertEquals(2_000, hikari.getConnectionTimeout());
        assertEquals(1_000, hikari.getValidationTimeout());
        assertEquals(5, hikari.getMaximumPoolSize());
        assertEquals(5_000, runtimeProperties.statementTimeout().toMillis());

        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("show statement_timeout")) {
            assertTrue(result.next());
            assertEquals("5s", result.getString(1));
        }

        assertEquals(Status.UP, databaseConnectivity.health().getStatus());
        assertEquals(Status.UP, schemaCompatibility.health().getStatus());
    }

    @Test
    void livenessDoesNotDependOnDatabaseAndReadinessComponentsStayDistinct() {
        var liveness = healthEndpointGroups.get("liveness");
        var readiness = healthEndpointGroups.get("readiness");

        assertTrue(liveness.isMember("livenessState"));
        assertFalse(liveness.isMember("databaseConnectivity"));
        assertFalse(liveness.isMember("schemaCompatibility"));
        assertTrue(readiness.isMember("databaseConnectivity"));
        assertTrue(readiness.isMember("schemaCompatibility"));
    }

    @Test
    void schemaMismatchHasASeparateOperationalCode() {
        var mismatch = new SchemaCompatibilityHealthIndicator(readinessDatabaseClient, "999").health();

        assertEquals(Status.DOWN, mismatch.getStatus());
        assertEquals("SCHEMA_VERSION_MISMATCH", mismatch.getDetails().get("code"));
        assertEquals("999", mismatch.getDetails().get("expectedVersion"));
        assertEquals("4", mismatch.getDetails().get("actualVersion"));
    }

    @Test
    void readinessHttpResponseExposesDistinctComponentsAndOperationalCode() throws Exception {
        assertTrue(managementPort > 0);
        assertFalse(managementPort == applicationPort);
        var applicationHealthRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + applicationPort + "/actuator/health"))
                .GET()
                .build();
        var applicationHealthResponse = HttpClient.newHttpClient()
                .send(applicationHealthRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, applicationHealthResponse.statusCode());

        jdbcClient.sql("update flyway_schema_history set version = '999' where version = '4'")
                .update();
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + managementPort
                            + "/actuator/health/readiness"))
                    .GET()
                    .build();
            var response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(503, response.statusCode());
            assertTrue(response.body().contains("databaseConnectivity"));
            assertTrue(response.body().contains("schemaCompatibility"));
            assertTrue(response.body().contains("SCHEMA_VERSION_MISMATCH"));
        } finally {
            jdbcClient.sql("update flyway_schema_history set version = '4' where version = '999'")
                    .update();
        }
    }

    @Test
    void statementTimeoutInterruptsSlowQueries() {
        long startedAt = System.nanoTime();
        assertThrows(
                RuntimeException.class,
                () -> jdbcClient.sql("select pg_sleep(10)").query().singleRow());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(elapsedMillis >= 4_000, "statement_timeout보다 너무 일찍 종료됐습니다.");
        assertTrue(elapsedMillis < 8_000, "statement_timeout이 느린 query를 제한하지 못했습니다.");
    }

    @Test
    void actuatorExposureIsMinimalAndStartupLogsDoNotContainTheDatabasePassword(
            CapturedOutput output) {
        Set<String> exposed = Set.of(
                environment.getRequiredProperty("management.endpoints.web.exposure.include").split(","));

        assertEquals(Set.of("health", "info", "metrics"), exposed);
        assertFalse(exposed.contains("env"));
        assertFalse(exposed.contains("beans"));
        assertFalse(exposed.contains("heapdump"));
        assertFalse(output.getAll().contains(POSTGRES.getPassword()));
    }

    @Test
    void normalServerStartupDoesNotCreateOrRunTheBaselineImporter() {
        assertEquals(
                0,
                applicationContext.getBeanNamesForType(
                                com.trustagent.core.publicproduct.baseline.BaselineImporter.class)
                        .length);
        assertEquals(0, jdbcClient.sql("select count(*) from baseline_import_run")
                .query(Integer.class)
                .single());
    }
}
