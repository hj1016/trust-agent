package com.trustagent.core.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionRequiredSettingsConfiguration {

    static final List<String> REQUIRED_ENVIRONMENT_SETTINGS = List.of(
            "TRUST_AGENT_DB_URL",
            "TRUST_AGENT_DB_USERNAME",
            "TRUST_AGENT_DB_PASSWORD",
            "TRUST_AGENT_SCHEMA_EXPECTED_VERSION",
            "TRUST_AGENT_FRESHNESS_POLICY_VERSION",
            "TRUST_AGENT_MAX_CONFIRMATION_AGE");
    static final List<String> REQUIRED_BASELINE_IMPORT_SETTINGS = List.of(
            "TRUST_AGENT_IMPORT_DB_URL",
            "TRUST_AGENT_IMPORT_DB_USERNAME",
            "TRUST_AGENT_IMPORT_DB_PASSWORD");

    public ProductionRequiredSettingsConfiguration(Environment environment) {
        validate(environment);
    }

    static void validate(Environment environment) {
        var required = new java.util.ArrayList<>(REQUIRED_ENVIRONMENT_SETTINGS);
        if (environment.getProperty("trust-agent.baseline-import.enabled", Boolean.class, false)) {
            required.addAll(REQUIRED_BASELINE_IMPORT_SETTINGS);
        }
        var missing = required.stream()
                .filter(name -> {
                    String value = environment.getProperty(name);
                    return value == null || value.isBlank();
                })
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("운영 필수 설정이 없습니다: " + String.join(", ", missing));
        }
    }
}
