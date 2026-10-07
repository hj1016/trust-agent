package com.trustagent.core.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.preparation.PreparationRecordProperties;
import com.trustagent.core.tool.ToolApiProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/** TASK-015 AC-20: 읽기 토큰과 기록 토큰이 같은 값이면 기동 거부, 다른 값이면 정상. 값은 임시 문자열이며 오류 메시지에 나오지 않는다. */
class ServiceTokenSeparationTest {

    private static final String SHARED = "temporary-shared-token-" + System.nanoTime();

    @Test
    void sameTokenValueIsRefusedWithoutRevealingTheValue() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ServiceTokenSeparationConfiguration.validate(SHARED, SHARED));
        assertTrue(error.getMessage().contains("SERVICE_TOKEN_NOT_SEPARATED"));
        assertTrue(error.getMessage().contains("TRUST_AGENT_TOOL_SERVICE_TOKEN"));
        assertTrue(error.getMessage().contains("TRUST_AGENT_PREPARATION_RECORD_TOKEN"));
        assertFalse(error.getMessage().contains(SHARED), "토큰 값은 오류에 나오지 않는다");
    }

    @Test
    void differentOrMissingTokensPassThisCheck() {
        assertDoesNotThrow(() -> ServiceTokenSeparationConfiguration.validate("temporary-tool-token", "temporary-record-token"));
        // 비어 있는 경우는 이 검사가 아니라 각 경로의 거부(401)와 production 필수 설정 검사가 다룬다.
        assertDoesNotThrow(() -> ServiceTokenSeparationConfiguration.validate("", "temporary-record-token"));
        assertDoesNotThrow(() -> ServiceTokenSeparationConfiguration.validate("temporary-tool-token", null));
    }

    @Test
    void contextRefusesStartupWhenBothPropertiesResolveToTheSameValue() {
        try (var context = context(SHARED, SHARED)) {
            RuntimeException error = assertThrows(RuntimeException.class, context::refresh);
            Throwable cause = error;
            while (cause.getCause() != null && !(cause instanceof IllegalStateException)) {
                cause = cause.getCause();
            }
            assertTrue(cause.getMessage().contains("SERVICE_TOKEN_NOT_SEPARATED"), cause.getMessage());
            assertFalse(error.toString().contains(SHARED), "기동 거부 오류 전체에 토큰 값이 없다");
        }
    }

    @Test
    void contextStartsWhenTokensAreSeparated() {
        try (var context = context("temporary-tool-token", "temporary-record-token")) {
            assertDoesNotThrow(context::refresh);
        }
    }

    private static AnnotationConfigApplicationContext context(String toolToken, String recordToken) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("tokens", Map.of(
                "trust-agent.tool-api.service-token", toolToken,
                "trust-agent.tool-api.service-id", "ai-service",
                "trust-agent.preparation-record.service-token", recordToken,
                "trust-agent.preparation-record.service-id", "ai-service")));
        context.register(Properties.class, ServiceTokenSeparationConfiguration.class);
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ToolApiProperties.class, PreparationRecordProperties.class})
    static class Properties {}
}
