package com.trustagent.core.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("databaseConnectivity")
public class DatabaseConnectivityHealthIndicator implements HealthIndicator {

    private final ReadinessDatabaseClient databaseClient;

    public DatabaseConnectivityHealthIndicator(ReadinessDatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Health health() {
        try {
            return databaseClient.canQuery()
                    ? Health.up().build()
                    : Health.down().withDetail("code", "DATABASE_VALIDATION_FAILED").build();
        } catch (Exception exception) {
            return Health.down().withDetail("code", "DATABASE_UNREACHABLE").build();
        }
    }
}
