package com.trustagent.core.internalpolicy.bootstrap;

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
@ConditionalOnProperty(name = "trust-agent.synthetic-internal-import.enabled", havingValue = "true")
class SyntheticInternalImportConfiguration {
    @Bean(name = "syntheticInternalImportDataSource", destroyMethod = "close")
    HikariDataSource dataSource(
            @Value("${trust-agent.synthetic-internal-import.datasource.url}") String url,
            @Value("${trust-agent.synthetic-internal-import.datasource.username}") String username,
            @Value("${trust-agent.synthetic-internal-import.datasource.password}") String password) {
        if (url.isBlank() || username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("synthetic internal importer DB 설정이 필요합니다.");
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("trust-agent-synthetic-import");
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(2);
        dataSource.setConnectionTimeout(5000);
        dataSource.setValidationTimeout(2000);
        dataSource.setConnectionInitSql("SET statement_timeout = '30000ms'");
        return dataSource;
    }

    @Bean
    SyntheticInternalImporter importer(
            @Qualifier("syntheticInternalImportDataSource") HikariDataSource dataSource,
            ObjectMapper mapper,
            @Value("${trust-agent.internal-policy.business-timezone}") String zone) {
        return new SyntheticInternalImporter(
                JdbcClient.create(dataSource),
                mapper,
                new DataSourceTransactionManager(dataSource),
                zone);
    }
}
