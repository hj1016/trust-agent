package com.trustagent.core.preparation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 서비스 산출물 기록 경로의 서비스 인증 설정(ADR-012). 읽기용 Tool 토큰과 별도의 기록 토큰이며 환경변수로만 들어온다.
 * 토큰이 비어 있으면 모든 기록 호출을 거부한다(누락은 허용이 아니다). 기록 토큰으로는 Tool을 읽을 수 없다.
 */
@ConfigurationProperties("trust-agent.preparation-record")
public record PreparationRecordProperties(String serviceToken, String serviceId) {

    public static final String TOKEN_SCOPE = "record";

    public PreparationRecordProperties {
        if (serviceId == null || serviceId.isBlank()) {
            serviceId = "ai-service";
        }
    }

    boolean configured() {
        return serviceToken != null && !serviceToken.isBlank();
    }
}
