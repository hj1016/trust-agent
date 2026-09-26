package com.trustagent.core.publicproduct.baseline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BaselineImportCommandIntegrationTest {

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
        registry.add("trust-agent.baseline-import.enabled", () -> true);
        registry.add("trust-agent.baseline-import.root", () -> System.getProperty("trustAgent.repositoryRoot"));
        registry.add("trust-agent.baseline-import.run-id", () -> "baseline:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        registry.add("trust-agent.baseline-import.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("trust-agent.baseline-import.datasource.username", POSTGRES::getUsername);
        registry.add("trust-agent.baseline-import.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Autowired
    @Qualifier("dataSource")
    private DataSource runtimeDataSource;

    @Autowired
    @Qualifier("baselineImportDataSource")
    private DataSource importerDataSource;

    @Test
    void explicitCommandModeUsesItsOwnPoolAndImportsExactlyOnce() {
        JdbcClient importerJdbc = JdbcClient.create(importerDataSource);
        assertNotSame(runtimeDataSource, importerDataSource);
        assertEquals("trust-agent-baseline-import", ((HikariDataSource) importerDataSource).getPoolName());
        assertEquals(1, importerJdbc.sql("""
                        select count(*) from baseline_import_run
                        where baseline_import_run_id = 'baseline:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'
                          and status = 'SUCCEEDED'
                        """).query(Integer.class).single());
        assertEquals(3, importerJdbc.sql("select count(*) from public_product")
                .query(Integer.class).single());
    }
}
