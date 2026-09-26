package com.trustagent.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.validation.Validation;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class RuntimeDatasourcePropertiesTest {

    @Test
    void approvedRuntimeDefaultsAreRepresentable() {
        RuntimeDatasourceProperties properties = new RuntimeDatasourceProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                5);

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertEquals(0, validatorFactory.getValidator().validate(properties).size());
        }
        assertEquals(Duration.ofSeconds(2), properties.connectionTimeout());
        assertEquals(Duration.ofSeconds(1), properties.validationTimeout());
        assertEquals(Duration.ofSeconds(5), properties.statementTimeout());
        assertEquals(Duration.ofSeconds(1), properties.readinessQueryTimeout());
        assertEquals(5, properties.maximumPoolSize());
    }

    @Test
    void poolSizeOutsideApprovedBoundaryIsRejected() {
        RuntimeDatasourceProperties properties = new RuntimeDatasourceProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                21);

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(validatorFactory.getValidator().validate(properties).isEmpty());
        }
    }

    @Test
    void configuredSchemaVersionMatchesTheLatestClasspathMigration() {
        var validator = new ClasspathSchemaVersionValidator(
                new PathMatchingResourcePatternResolver(), "3");

        assertEquals("3", validator.classpathVersion());
        assertThrows(
                IllegalStateException.class,
                () -> new ClasspathSchemaVersionValidator(
                        new PathMatchingResourcePatternResolver(), "1"));
    }

    @Test
    void productionRequiresExplicitDatabaseAndSchemaSettings() {
        try (var missingContext = productionContext(Map.of(
                "TRUST_AGENT_DB_URL", "",
                "TRUST_AGENT_DB_USERNAME", "",
                "TRUST_AGENT_DB_PASSWORD", "",
                "TRUST_AGENT_SCHEMA_EXPECTED_VERSION", ""))) {
            assertThrows(RuntimeException.class, missingContext::refresh);
        }

        try (var missingPolicyContext = productionContext(Map.of(
                "TRUST_AGENT_DB_URL", "jdbc:postgresql://db/trust_agent",
                "TRUST_AGENT_DB_USERNAME", "runtime",
                "TRUST_AGENT_DB_PASSWORD", "secret",
                "TRUST_AGENT_SCHEMA_EXPECTED_VERSION", "3"))) {
            assertThrows(RuntimeException.class, missingPolicyContext::refresh);
        }

        try (var configuredContext = productionContext(Map.of(
                "TRUST_AGENT_DB_URL", "jdbc:postgresql://db/trust_agent",
                "TRUST_AGENT_DB_USERNAME", "runtime",
                "TRUST_AGENT_DB_PASSWORD", "secret",
                "TRUST_AGENT_SCHEMA_EXPECTED_VERSION", "3",
                "TRUST_AGENT_FRESHNESS_POLICY_VERSION", "public-evidence-confirmation-v1",
                "TRUST_AGENT_MAX_CONFIRMATION_AGE", "24h"))) {
            configuredContext.refresh();
        }
    }

    @Test
    void productionImporterRequiresCredentialsSeparateFromRuntimeSettings() {
        Map<String, Object> runtimeOnly = Map.of(
                "TRUST_AGENT_DB_URL", "jdbc:postgresql://db/trust_agent",
                "TRUST_AGENT_DB_USERNAME", "runtime",
                "TRUST_AGENT_DB_PASSWORD", "runtime-secret",
                "TRUST_AGENT_SCHEMA_EXPECTED_VERSION", "3",
                "TRUST_AGENT_FRESHNESS_POLICY_VERSION", "public-evidence-confirmation-v1",
                "TRUST_AGENT_MAX_CONFIRMATION_AGE", "24h",
                "trust-agent.baseline-import.enabled", "true");
        try (var missingImporterContext = productionContext(runtimeOnly)) {
            assertThrows(RuntimeException.class, missingImporterContext::refresh);
        }

        var allSettings = new java.util.HashMap<String, Object>(runtimeOnly);
        allSettings.put("TRUST_AGENT_IMPORT_DB_URL", "jdbc:postgresql://db/trust_agent");
        allSettings.put("TRUST_AGENT_IMPORT_DB_USERNAME", "importer");
        allSettings.put("TRUST_AGENT_IMPORT_DB_PASSWORD", "importer-secret");
        try (var configuredContext = productionContext(allSettings)) {
            configuredContext.refresh();
        }
    }

    private static AnnotationConfigApplicationContext productionContext(
            Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("prod");
        context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("test-production-settings", properties));
        context.register(ProductionRequiredSettingsConfiguration.class);
        return context;
    }
}
