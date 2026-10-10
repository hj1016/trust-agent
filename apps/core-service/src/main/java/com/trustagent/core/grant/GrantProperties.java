package com.trustagent.core.grant;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 요청 승인(grant) 설정(ADR-014 7·9·10항). TTL은 설정값이며 발급 시점 값이 grant 행에 기록된다.
 * require=true면 Tool·기록 경로가 서비스 토큰과 함께 grant를 요구한다(cloud/prod 강제). false는 로컬 CLI·하네스 전용이며 헤더가 있으면 검사한다.
 */
@ConfigurationProperties("trust-agent.ai-grant")
public record GrantProperties(Duration ttl, Integer readCallLimit, boolean require) {

    public GrantProperties {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) ttl = Duration.ofSeconds(60);
        if (readCallLimit == null || readCallLimit < 1) readCallLimit = 50;
    }
}
