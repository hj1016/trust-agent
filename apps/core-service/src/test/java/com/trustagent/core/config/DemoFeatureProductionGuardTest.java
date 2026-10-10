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

    private static final Map<String, Object> COMPLETE_PRODUCTION_SETTINGS = Map.ofEntries(
            Map.entry("TRUST_AGENT_DB_URL", "jdbc:postgresql://db/trust_agent"),
            Map.entry("TRUST_AGENT_DB_USERNAME", "runtime"),
            Map.entry("TRUST_AGENT_DB_PASSWORD", "runtime-secret"),
            Map.entry("TRUST_AGENT_SCHEMA_EXPECTED_VERSION", "11"),
            Map.entry("TRUST_AGENT_FRESHNESS_POLICY_VERSION", "public-evidence-confirmation-v1"),
            Map.entry("TRUST_AGENT_MAX_CONFIRMATION_AGE", "24h"),
            Map.entry("TRUST_AGENT_INTERNAL_BUSINESS_TIMEZONE", "Asia/Seoul"),
            Map.entry("TRUST_AGENT_INTERNAL_TIMEZONE_POLICY_VERSION", "internal-business-time-v1"),
            Map.entry("TRUST_AGENT_VALIDATION_MAX_AGE", "24h"),
            Map.entry("TRUST_AGENT_VALIDATION_POLICY_VERSION", "internal-validation-v1"),
            Map.entry("TRUST_AGENT_TOOL_SERVICE_TOKEN", "temporary-token-for-test"),
            Map.entry("TRUST_AGENT_PREPARATION_RECORD_TOKEN", "temporary-record-token-for-test"),
            Map.entry("TRUST_AGENT_CONTROL_DB_URL", "jdbc:postgresql://db.example.invalid:5432/trust_agent_control"),
            Map.entry("TRUST_AGENT_CONTROL_DB_USERNAME", "control_user"),
            Map.entry("TRUST_AGENT_CONTROL_DB_PASSWORD", "control-password-for-test"),
            Map.entry("TRUST_AGENT_AI_SERVICE_BASE_URL", "http://ai-service:8090"),
            Map.entry("TRUST_AGENT_AI_INBOUND_TOKEN", "inbound-token-for-test"));

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
    void proposalValidationAloneRefusesProductionStartup() {
        // TASK-006 AC-13: 변경안 검증 runner 설정이 켜지면 production 기동 거부.
        var error = refusal(Map.of("trust-agent.proposal-validation.enabled", "true"));
        assertTrue(error.contains("DEMO_FEATURE_ENABLED_IN_PROD"));
        assertTrue(error.contains("trust-agent.proposal-validation.enabled"));
    }

    @Test
    void humanReviewAloneRefusesProductionStartup() {
        // TASK-007 AC-12: 사람 검토 결정 runner 설정이 켜지면 production 기동 거부.
        var error = refusal(Map.of("trust-agent.human-review.enabled", "true"));
        assertTrue(error.contains("DEMO_FEATURE_ENABLED_IN_PROD"));
        assertTrue(error.contains("trust-agent.human-review.enabled"));
    }

    @Test
    void toolServiceTokenIsRequiredInProduction() {
        // TASK-008 AC-10: 토큰 설정이 없으면 production 기동 거부. 테스트 값은 임시 문자열이며 실제 자격증명이 아니다.
        try (var context = productionContext(Map.of("TRUST_AGENT_TOOL_SERVICE_TOKEN", ""))) {
            assertThrows(RuntimeException.class, context::refresh);
        }
    }

    @Test
    void validationAgeMustBePositiveInProduction() {
        // TASK-006 AC-13: 검증 유효 기간이 0이거나 음수면 기동 거부.
        try (var context = productionContext(Map.of("TRUST_AGENT_VALIDATION_MAX_AGE", "0s"))) {
            assertThrows(RuntimeException.class, context::refresh);
        }
        try (var context = productionContext(Map.of("TRUST_AGENT_VALIDATION_MAX_AGE", "-1h"))) {
            assertThrows(RuntimeException.class, context::refresh);
        }
    }

    @Test
    void demoUsersAloneRefusesProductionStartup() {
        var error = refusal(Map.of("trust-agent.demo-users.enabled", "true"));
        assertTrue(error.contains("DEMO_FEATURE_ENABLED_IN_PROD"));
        assertTrue(error.contains("trust-agent.demo-users.enabled"));
    }

    @Test
    void controlDatabaseMustDifferFromBusinessDatabaseInProduction() {
        var error = refusal(Map.of("TRUST_AGENT_CONTROL_DB_URL", COMPLETE_PRODUCTION_SETTINGS.get("TRUST_AGENT_DB_URL")));
        assertTrue(error.contains("CONTROL_DB_NOT_SEPARATED"), error);
    }

    @Test
    void sameDatabaseWrittenDifferentlyIsStillRefused() {
        // 업무 DB는 jdbc:postgresql://db/trust_agent. 포트 명시·대소문자·질의 문자열이 달라도 같은 DB다.
        var error = refusal(Map.of("TRUST_AGENT_CONTROL_DB_URL", "jdbc:postgresql://DB:5432/trust_agent?sslmode=require"));
        assertTrue(error.contains("CONTROL_DB_NOT_SEPARATED"), error);
        assertTrue(ProductionRequiredSettingsConfiguration.sameDatabase("jdbc:postgresql://db/trust_agent", "jdbc:postgresql://db:5432/trust_agent/"));
        assertTrue(!ProductionRequiredSettingsConfiguration.sameDatabase("jdbc:postgresql://db/trust_agent", "jdbc:postgresql://db/trust_agent_control"));
    }

    @Test
    void grantMustBeRequiredInProduction() {
        var error = refusal(Map.of("trust-agent.ai-grant.require", "false"));
        assertTrue(error.contains("GRANT_NOT_REQUIRED_IN_PROD"), error);
    }

    @Test
    void controlDatabaseAccountMustDifferFromBusinessAccountInProduction() {
        var error = refusal(Map.of("TRUST_AGENT_CONTROL_DB_USERNAME", COMPLETE_PRODUCTION_SETTINGS.get("TRUST_AGENT_DB_USERNAME")));
        assertTrue(error.contains("CONTROL_DB_ACCOUNT_NOT_SEPARATED"), error);
    }

    @Test
    void bothDemoSettingsAreReportedTogether() {
        var error = refusal(Map.of(
                "trust-agent.proposal-generation.enabled", "true",
                "trust-agent.fixture-approved-checklist.enabled", "true",
                "trust-agent.proposal-validation.enabled", "true",
                "trust-agent.human-review.enabled", "true"));
        assertTrue(error.contains("trust-agent.human-review.enabled"));
        assertTrue(error.contains("trust-agent.proposal-generation.enabled"));
        assertTrue(error.contains("trust-agent.fixture-approved-checklist.enabled"));
        assertTrue(error.contains("trust-agent.proposal-validation.enabled"));
    }

    @Test
    void productionStartsWhenDemoSettingsAreOffOrAbsent() {
        try (var context = productionContext(Map.of())) {
            context.refresh();
        }
        try (var context = productionContext(Map.of(
                "trust-agent.proposal-generation.enabled", "false",
                "trust-agent.fixture-approved-checklist.enabled", "false",
                "trust-agent.proposal-validation.enabled", "false",
                "trust-agent.human-review.enabled", "false"))) {
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
