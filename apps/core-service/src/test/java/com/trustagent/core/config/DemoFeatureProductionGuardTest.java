package com.trustagent.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** TASK-005 AC-17~21: production에서 demo 전용 설정이 하나라도 켜지면 기동을 거부한다. */
class DemoFeatureProductionGuardTest {

    private static final Map<String, Object> COMPLETE_PRODUCTION_SETTINGS = Map.of(
            "TRUST_AGENT_DB_URL", "jdbc:postgresql://db/trust_agent",
            "TRUST_AGENT_DB_USERNAME", "runtime",
            "TRUST_AGENT_DB_PASSWORD", "runtime-secret",
            "TRUST_AGENT_SCHEMA_EXPECTED_VERSION", "6",
            "TRUST_AGENT_FRESHNESS_POLICY_VERSION", "public-evidence-confirmation-v1",
            "TRUST_AGENT_MAX_CONFIRMATION_AGE", "24h",
            "TRUST_AGENT_INTERNAL_BUSINESS_TIMEZONE", "Asia/Seoul",
            "TRUST_AGENT_INTERNAL_TIMEZONE_POLICY_VERSION", "internal-business-time-v1");

    @Test
    void proposalGenerationAloneRefusesProductionStartup() {
        var error = refusal(Map.of("trust-agent.proposal-generation.enabled", "true"));
        assertTrue(error.contains("DEMO_FEATURE_ENABLED_IN_PROD"));
        assertTrue(error.contains("trust-agent.proposal-generation.enabled"));
    }

    @Test
    void fixtureLoaderAloneRefusesProductionStartup() {
        var error = refusal(Map.of("trust-agent.fixture-approved-checklist.enabled", "true"));
        assertTrue(error.contains("DEMO_FEATURE_ENABLED_IN_PROD"));
        assertTrue(error.contains("trust-agent.fixture-approved-checklist.enabled"));
    }

    @Test
    void bothDemoSettingsAreReportedTogether() {
        var error = refusal(Map.of(
                "trust-agent.proposal-generation.enabled", "true",
                "trust-agent.fixture-approved-checklist.enabled", "true"));
        assertTrue(error.contains("trust-agent.proposal-generation.enabled"));
        assertTrue(error.contains("trust-agent.fixture-approved-checklist.enabled"));
    }

    @Test
    void productionStartsWhenDemoSettingsAreOffOrAbsent() {
        try (var context = productionContext(Map.of())) {
            context.refresh();
        }
        try (var context = productionContext(Map.of(
                "trust-agent.proposal-generation.enabled", "false",
                "trust-agent.fixture-approved-checklist.enabled", "false"))) {
            context.refresh();
        }
    }

    private static String refusal(Map<String, Object> demoSettings) {
        try (var context = productionContext(demoSettings)) {
            var error = assertThrows(RuntimeException.class, context::refresh);
            Throwable cause = error;
            while (cause.getCause() != null && !(cause instanceof IllegalStateException)) {
                cause = cause.getCause();
            }
            assertEquals(IllegalStateException.class, cause.getClass());
            return cause.getMessage();
        }
    }

    private static AnnotationConfigApplicationContext productionContext(Map<String, Object> demoSettings) {
        var properties = new HashMap<>(COMPLETE_PRODUCTION_SETTINGS);
        properties.putAll(demoSettings);
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("prod");
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test-production-settings", properties));
        context.register(ProductionRequiredSettingsConfiguration.class);
        return context;
    }
}
