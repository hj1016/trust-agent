package com.trustagent.core.publicproduct.baseline;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "trust-agent.baseline-import.enabled", havingValue = "true")
class BaselineImportDatasourceConfiguration {

    @Bean(name = "baselineImportDataSource", destroyMethod = "close")
    HikariDataSource baselineImportDataSource(
            @Value("${trust-agent.baseline-import.datasource.url}") String url,
            @Value("${trust-agent.baseline-import.datasource.username}") String username,
            @Value("${trust-agent.baseline-import.datasource.password}") String password,
            @Value("${trust-agent.baseline-import.datasource.connection-timeout:5s}") java.time.Duration connectionTimeout,
            @Value("${trust-agent.baseline-import.datasource.validation-timeout:2s}") java.time.Duration validationTimeout,
            @Value("${trust-agent.baseline-import.datasource.statement-timeout:30s}") java.time.Duration statementTimeout,
            @Value("${trust-agent.baseline-import.datasource.maximum-pool-size:2}") int maximumPoolSize) {
        if (url.isBlank() || username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("baseline importer DB URL, username과 password를 명시해야 합니다.");
        }
        if (connectionTimeout.isNegative() || connectionTimeout.isZero()
                || validationTimeout.isNegative() || validationTimeout.isZero()
                || statementTimeout.isNegative() || statementTimeout.isZero()
                || maximumPoolSize < 1 || maximumPoolSize > 5) {
            throw new IllegalStateException("baseline importer datasource timeout 또는 pool 크기가 올바르지 않습니다.");
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("trust-agent-baseline-import");
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setConnectionTimeout(connectionTimeout.toMillis());
        dataSource.setValidationTimeout(validationTimeout.toMillis());
        dataSource.setMaximumPoolSize(maximumPoolSize);
        dataSource.setConnectionInitSql("SET statement_timeout = '" + statementTimeout.toMillis() + "ms'");
        return dataSource;
    }

    @Bean
    BaselineImporter baselineImporter(
            @Qualifier("baselineImportDataSource") HikariDataSource dataSource,
            ObjectMapper objectMapper) {
        return new BaselineImporter(
                JdbcClient.create(dataSource),
                objectMapper,
                new DataSourceTransactionManager(dataSource));
    }
}
