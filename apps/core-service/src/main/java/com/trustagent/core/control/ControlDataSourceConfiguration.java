package com.trustagent.core.control;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 제어 DB DataSource와 Flyway(별도 history 표). 업무 DataSource가 기본 후보로 남도록 두 bean은 defaultCandidate=false다.
 */
@Configuration(proxyBeanMethods = false)
public class ControlDataSourceConfiguration {

    public static final String CONTROL_DATA_SOURCE = "controlDataSource";
    public static final String CONTROL_JDBC_CLIENT = "controlJdbcClient";
    static final String FLYWAY_TABLE = "flyway_control_schema_history";
    private static final Logger LOGGER = LoggerFactory.getLogger(ControlDataSourceConfiguration.class);

    @Bean(name = CONTROL_DATA_SOURCE, defaultCandidate = false)
    HikariDataSource controlDataSource(ControlDataSourceProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("trust-agent-control");
        dataSource.setJdbcUrl(properties.url());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(properties.password());
        dataSource.setMaximumPoolSize(properties.maximumPoolSize());
        dataSource.setConnectionTimeout(2_000);
        if (properties.flywayEnabled()) {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/control")
                    .table(FLYWAY_TABLE)
                    // 로컬 기본(업무 DB와 같은 데이터베이스)에서는 schema가 비어 있지 않으므로 제어 history를 0에서 baseline한다.
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load()
                    .migrate();
            LOGGER.info("제어 DB migration 적용 완료(history 표 {})", FLYWAY_TABLE);
        }
        return dataSource;
    }

    @Bean(name = CONTROL_JDBC_CLIENT, defaultCandidate = false)
    JdbcClient controlJdbcClient(@Qualifier(CONTROL_DATA_SOURCE) DataSource controlDataSource) {
        return JdbcClient.create(controlDataSource);
    }
}
