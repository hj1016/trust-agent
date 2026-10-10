package com.trustagent.core.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Core → AI 서비스 호출 설정(ADR-014 2항). 수신 토큰은 환경변수로만 제공한다. */
@ConfigurationProperties("trust-agent.ai-service")
public record AiServiceProperties(String baseUrl, String inboundToken, Duration timeout) {

    public AiServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://127.0.0.1:8090";
        if (timeout == null || timeout.isZero() || timeout.isNegative()) timeout = Duration.ofSeconds(30);
    }

    public boolean tokenConfigured() {
        return inboundToken != null && !inboundToken.isBlank();
    }
}
