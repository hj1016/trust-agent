package com.trustagent.core.control;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 제어 DB 설정(ADR-014). 사용자·역할·보안 사건·AI 요청 승인을 담고 업무 DB와 분리한다.
 * 로컬 기본값은 업무 DB 설정으로 대체되지만 cloud/prod는 다른 데이터베이스를 요구한다(ProductionRequiredSettingsConfiguration).
 */
@ConfigurationProperties("trust-agent.control-datasource")
public record ControlDataSourceProperties(String url, String username, String password, boolean flywayEnabled, Integer maximumPoolSize) {

    public ControlDataSourceProperties {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("CONTROL_DB_URL_MISSING: trust-agent.control-datasource.url이 필요합니다.");
        }
        if (maximumPoolSize == null || maximumPoolSize < 1) {
            maximumPoolSize = 2;
        }
    }
}
