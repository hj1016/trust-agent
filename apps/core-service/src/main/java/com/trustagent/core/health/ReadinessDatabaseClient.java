package com.trustagent.core.health;

import com.trustagent.core.config.RuntimeDatasourceProperties;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class ReadinessDatabaseClient {

    private final DataSource dataSource;
    private final int queryTimeoutSeconds;

    public ReadinessDatabaseClient(
            DataSource dataSource,
            RuntimeDatasourceProperties runtimeProperties) {
        this.dataSource = dataSource;
        this.queryTimeoutSeconds = Math.toIntExact(
                Math.max(1, runtimeProperties.readinessQueryTimeout().toSeconds()));
    }

    public boolean canQuery() throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            try (var result = statement.executeQuery("select 1")) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    public Optional<String> latestSchemaVersion() throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            try (var result = statement.executeQuery("""
                    select version
                    from flyway_schema_history
                    where success = true and version is not null
                    order by installed_rank desc
                    limit 1
                    """)) {
                return result.next() ? Optional.of(result.getString("version")) : Optional.empty();
            }
        }
    }
}
