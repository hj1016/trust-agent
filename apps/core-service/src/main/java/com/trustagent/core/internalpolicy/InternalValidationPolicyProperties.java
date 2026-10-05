package com.trustagent.core.internalpolicy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 자동 검증 결과의 유효 기간과 정책 version. ADR-008: 값이 없거나 0 이하면 기동을 거부한다. */
@ConfigurationProperties("trust-agent.validation-policy")
public record InternalValidationPolicyProperties(Duration maxValidationAge, String policyVersion) {

    public InternalValidationPolicyProperties {
        if (maxValidationAge == null || maxValidationAge.isZero() || maxValidationAge.isNegative()) {
            throw new IllegalArgumentException("max validation age는 0보다 커야 합니다.");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("validation policy version을 명시해야 합니다.");
        }
    }
}
