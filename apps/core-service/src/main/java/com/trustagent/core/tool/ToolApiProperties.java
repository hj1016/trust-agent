package com.trustagent.core.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 서비스용 Tool API의 test/demo 수준 서비스 인증 설정. 토큰 값은 환경변수로만 들어오며 저장소와 로그에 남기지 않는다.
 * 토큰이 비어 있으면 모든 Tool 호출을 거부한다(누락은 허용이 아니다). 사용자별 인증·권한은 미구현이다.
 */
@ConfigurationProperties("trust-agent.tool-api")
public record ToolApiProperties(String serviceToken, String serviceId) {

    public ToolApiProperties {
        if (serviceId == null || serviceId.isBlank()) {
            serviceId = "ai-service";
        }
    }

    boolean configured() {
        return serviceToken != null && !serviceToken.isBlank();
    }
}
