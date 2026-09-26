package com.trustagent.core.publicproduct.query;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("trust-agent.public-evidence")
public record PublicEvidencePolicyProperties(
        String freshnessPolicyVersion,
        Duration maxConfirmationAge) {

    public PublicEvidencePolicyProperties {
        if (freshnessPolicyVersion == null || freshnessPolicyVersion.isBlank()) {
            throw new IllegalArgumentException("freshness policy version을 명시해야 합니다.");
        }
        if (maxConfirmationAge == null || maxConfirmationAge.isZero() || maxConfirmationAge.isNegative()) {
            throw new IllegalArgumentException("max confirmation age는 0보다 커야 합니다.");
        }
    }
}
