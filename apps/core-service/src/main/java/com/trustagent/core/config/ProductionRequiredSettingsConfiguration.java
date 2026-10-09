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
            "TRUST_AGENT_MAX_CONFIRMATION_AGE",
            "TRUST_AGENT_INTERNAL_BUSINESS_TIMEZONE",
            "TRUST_AGENT_INTERNAL_TIMEZONE_POLICY_VERSION",
            "TRUST_AGENT_VALIDATION_MAX_AGE",
            "TRUST_AGENT_VALIDATION_POLICY_VERSION",
            "TRUST_AGENT_TOOL_SERVICE_TOKEN",
            "TRUST_AGENT_PREPARATION_RECORD_TOKEN",
            "TRUST_AGENT_CONTROL_DB_URL",
            "TRUST_AGENT_CONTROL_DB_USERNAME",
            "TRUST_AGENT_CONTROL_DB_PASSWORD");
    static final List<String> REQUIRED_BASELINE_IMPORT_SETTINGS = List.of(
            "TRUST_AGENT_IMPORT_DB_URL",
            "TRUST_AGENT_IMPORT_DB_USERNAME",
            "TRUST_AGENT_IMPORT_DB_PASSWORD");
    static final List<String> REQUIRED_SYNTHETIC_IMPORT_SETTINGS = List.of(
            "TRUST_AGENT_SYNTHETIC_IMPORT_DB_URL",
            "TRUST_AGENT_SYNTHETIC_IMPORT_DB_USERNAME",
            "TRUST_AGENT_SYNTHETIC_IMPORT_DB_PASSWORD");

    /** test/demo 전용 기능. production에서 하나라도 켜져 있으면 기동을 거부한다. */
    static final List<String> DEMO_ONLY_SETTINGS = List.of(
            "trust-agent.proposal-generation.enabled",
            "trust-agent.fixture-approved-checklist.enabled",
            "trust-agent.proposal-validation.enabled",
            "trust-agent.human-review.enabled",
            "trust-agent.demo-users.enabled");

    public ProductionRequiredSettingsConfiguration(Environment environment) {
        validate(environment);
    }

    /** JDBC URL을 호스트·포트·데이터베이스 이름으로 정규화해 비교한다. 같은 물리 DB를 다른 표기(포트 생략, 대소문자, 질의 문자열)로 가리키는 경우도 같은 DB로 본다. */
    static boolean sameDatabase(String first, String second) {
        return normalizeJdbcUrl(first).equals(normalizeJdbcUrl(second));
    }

    static String normalizeJdbcUrl(String url) {
        String value = url.trim();
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^jdbc:postgresql://([^/:]+)(?::(\\d+))?/([^/?]+)/?$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value);
        if (!matcher.matches()) {
            return value.toLowerCase(java.util.Locale.ROOT);
        }
        String port = matcher.group(2) == null ? "5432" : matcher.group(2);
        return matcher.group(1).toLowerCase(java.util.Locale.ROOT) + ":" + port + "/" + matcher.group(3);
    }

    static void validate(Environment environment) {
        var enabledDemoSettings = DEMO_ONLY_SETTINGS.stream()
                .filter(name -> environment.getProperty(name, Boolean.class, false))
                .toList();
        if (!enabledDemoSettings.isEmpty()) {
            throw new IllegalStateException(
                    "DEMO_FEATURE_ENABLED_IN_PROD: production profile에서는 demo 전용 기능을 켤 수 없습니다: "
                            + String.join(", ", enabledDemoSettings));
        }
        var required = new java.util.ArrayList<>(REQUIRED_ENVIRONMENT_SETTINGS);
        if (environment.getProperty("trust-agent.baseline-import.enabled", Boolean.class, false)) {
            required.addAll(REQUIRED_BASELINE_IMPORT_SETTINGS);
        }
        if (environment.getProperty("trust-agent.synthetic-internal-import.enabled", Boolean.class, false)) {
            required.addAll(REQUIRED_SYNTHETIC_IMPORT_SETTINGS);
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
        String controlUrl = environment.getProperty("TRUST_AGENT_CONTROL_DB_URL");
        String businessUrl = environment.getProperty("TRUST_AGENT_DB_URL");
        if (controlUrl != null && businessUrl != null && sameDatabase(controlUrl, businessUrl)) {
            throw new IllegalStateException("CONTROL_DB_NOT_SEPARATED: production profile에서는 제어 DB가 업무 DB와 다른 데이터베이스여야 합니다(호스트·포트·데이터베이스 이름 기준).");
        }
        String controlUser = environment.getProperty("TRUST_AGENT_CONTROL_DB_USERNAME");
        if (controlUser != null && controlUser.equals(environment.getProperty("TRUST_AGENT_DB_USERNAME"))) {
            throw new IllegalStateException("CONTROL_DB_ACCOUNT_NOT_SEPARATED: production profile에서는 제어 DB 계정이 업무 DB 계정과 달라야 합니다(최소 권한 분리).");
        }
        String maxValidationAge = environment.getProperty("TRUST_AGENT_VALIDATION_MAX_AGE");
        try {
            java.time.Duration age = org.springframework.boot.convert.DurationStyle.detectAndParse(maxValidationAge);
            if (age.isZero() || age.isNegative()) {
                throw new IllegalStateException("TRUST_AGENT_VALIDATION_MAX_AGE는 0보다 커야 합니다: " + maxValidationAge);
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("TRUST_AGENT_VALIDATION_MAX_AGE 형식이 올바르지 않습니다: " + maxValidationAge, invalid);
        }
    }
}
