package com.trustagent.core.internalpolicy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class InternalPolicyConfiguration {
    @Bean
    BusinessTimePolicy businessTimePolicy(
            @Value("${trust-agent.internal-policy.business-timezone}") String businessTimezone,
            @Value("${trust-agent.internal-policy.timezone-policy-version}") String policyVersion) {
        if (!BusinessTimePolicy.POLICY_VERSION.equals(policyVersion)) {
            throw new IllegalStateException("지원하지 않는 internal business timezone policy version입니다.");
        }
        return new BusinessTimePolicy(businessTimezone);
    }

    @Bean
    InternalChecklistUsePolicy internalChecklistUsePolicy() {
        return new InternalChecklistUsePolicy();
    }
}
