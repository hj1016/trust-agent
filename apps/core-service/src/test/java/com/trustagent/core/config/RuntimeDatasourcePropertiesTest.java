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
                new PathMatchingResourcePatternResolver(), "6");

        assertEquals("6", validator.classpathVersion());
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
                "TRUST_AGENT_SCHEMA_EXPECTED_VERSION", "6"))) {
            assertThrows(RuntimeException.class, missingPolicyContext::refresh);
        }

        try (var configuredContext = productionContext(Map.of(
                "TRUST_AGENT_DB_URL", "jdbc:postgresql://db/trust_agent",
                "TRUST_AGENT_DB_USERNAME", "runtime",
                "TRUST_AGENT_DB_PASSWORD", "secret",
                "TRUST_AGENT_SCHEMA_EXPECTED_VERSION", "6",
                "TRUST_AGENT_FRESHNESS_POLICY_VERSION", "public-evidence-confirmation-v1",
                "TRUST_AGENT_MAX_CONFIRMATION_AGE", "24h",
                "TRUST_AGENT_INTERNAL_BUSINESS_TIMEZONE", "Asia/Seoul",
                "TRUST_AGENT_INTERNAL_TIMEZONE_POLICY_VERSION", "internal-business-time-v1"))) {
            configuredContext.refresh();
        }
    }

    @Test
    void productionImporterRequiresCredentialsSeparateFromRuntimeSettings() {
        Map<String, Object> runtimeOnly = Map.of(
                "TRUST_AGENT_DB_URL", "jdbc:postgresql://db/trust_agent",
                "TRUST_AGENT_DB_USERNAME", "runtime",
                "TRUST_AGENT_DB_PASSWORD", "runtime-secret",
                "TRUST_AGENT_SCHEMA_EXPECTED_VERSION", "6",
                "TRUST_AGENT_FRESHNESS_POLICY_VERSION", "public-evidence-confirmation-v1",
                "TRUST_AGENT_MAX_CONFIRMATION_AGE", "24h",
                "TRUST_AGENT_INTERNAL_BUSINESS_TIMEZONE", "Asia/Seoul",
                "TRUST_AGENT_INTERNAL_TIMEZONE_POLICY_VERSION", "internal-business-time-v1",
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

    @Test
    void productionSyntheticImporterRequiresItsOwnCredentials() {
        var settings = new java.util.HashMap<String, Object>();
        settings.put("TRUST_AGENT_DB_URL", "jdbc:postgresql://db/trust_agent");
        settings.put("TRUST_AGENT_DB_USERNAME", "runtime");
        settings.put("TRUST_AGENT_DB_PASSWORD", "runtime-secret");
        settings.put("TRUST_AGENT_SCHEMA_EXPECTED_VERSION", "6");
        settings.put("TRUST_AGENT_FRESHNESS_POLICY_VERSION", "public-evidence-confirmation-v1");
        settings.put("TRUST_AGENT_MAX_CONFIRMATION_AGE", "24h");
        settings.put("TRUST_AGENT_INTERNAL_BUSINESS_TIMEZONE", "Asia/Seoul");
        settings.put("TRUST_AGENT_INTERNAL_TIMEZONE_POLICY_VERSION", "internal-business-time-v1");
        settings.put("trust-agent.synthetic-internal-import.enabled", "true");
        try (var context = productionContext(settings)) {
            assertThrows(RuntimeException.class, context::refresh);
        }
        settings.put("TRUST_AGENT_SYNTHETIC_IMPORT_DB_URL", "jdbc:postgresql://db/trust_agent");
        settings.put("TRUST_AGENT_SYNTHETIC_IMPORT_DB_USERNAME", "synthetic-importer");
        settings.put("TRUST_AGENT_SYNTHETIC_IMPORT_DB_PASSWORD", "synthetic-secret");
        try (var context = productionContext(settings)) {
            context.refresh();
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
