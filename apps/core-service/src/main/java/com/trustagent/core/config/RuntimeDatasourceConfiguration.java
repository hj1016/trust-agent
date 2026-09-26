package com.trustagent.core.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
public class RuntimeDatasourceConfiguration {

    @Bean
    @Primary
    DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            RuntimeDatasourceProperties runtimeProperties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setConnectionTimeout(runtimeProperties.connectionTimeout().toMillis());
        dataSource.setValidationTimeout(runtimeProperties.validationTimeout().toMillis());
        dataSource.setMaximumPoolSize(runtimeProperties.maximumPoolSize());
        dataSource.setConnectionInitSql(
                "SET statement_timeout = '" + runtimeProperties.statementTimeout().toMillis() + "ms'");
        return dataSource;
    }
}
