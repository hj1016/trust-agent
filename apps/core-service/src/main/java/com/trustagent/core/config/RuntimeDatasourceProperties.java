package com.trustagent.core.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("trust-agent.datasource")
public record RuntimeDatasourceProperties(
        @NotNull Duration connectionTimeout,
        @NotNull Duration validationTimeout,
        @NotNull Duration statementTimeout,
        @NotNull Duration readinessQueryTimeout,
        @Min(1) @Max(20) int maximumPoolSize) {
}
