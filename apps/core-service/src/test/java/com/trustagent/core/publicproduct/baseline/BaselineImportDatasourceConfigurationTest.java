package com.trustagent.core.publicproduct.baseline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BaselineImportDatasourceConfigurationTest {

    private final BaselineImportDatasourceConfiguration configuration =
            new BaselineImportDatasourceConfiguration();

    @Test
    void importerUsesAnExplicitlyNamedPoolWithItsOwnTimeoutBudget() {
        try (HikariDataSource importer = configuration.baselineImportDataSource(
                "jdbc:postgresql://localhost:5432/trust_agent",
                "trust_agent_import_user",
                "secret",
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                2);
                HikariDataSource runtime = new HikariDataSource()) {
            runtime.setPoolName("trust-agent-runtime");

            assertEquals("trust-agent-baseline-import", importer.getPoolName());
            assertNotSame(runtime, importer);
            assertEquals(5_000, importer.getConnectionTimeout());
            assertEquals(2_000, importer.getValidationTimeout());
            assertEquals(2, importer.getMaximumPoolSize());
            assertEquals("SET statement_timeout = '30000ms'", importer.getConnectionInitSql());
        }
    }

    @Test
    void importerRejectsMissingCredentialsAndUnsafePoolBounds() {
        assertThrows(
                IllegalStateException.class,
                () -> configuration.baselineImportDataSource(
                        "jdbc:postgresql://localhost:5432/trust_agent",
                        "trust_agent_import_user",
                        "",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(30),
                        2));
        assertThrows(
                IllegalStateException.class,
                () -> configuration.baselineImportDataSource(
                        "jdbc:postgresql://localhost:5432/trust_agent",
                        "trust_agent_import_user",
                        "secret",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(30),
                        6));
    }
}
