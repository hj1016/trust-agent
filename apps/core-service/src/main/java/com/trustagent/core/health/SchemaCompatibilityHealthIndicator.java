package com.trustagent.core.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("schemaCompatibility")
public class SchemaCompatibilityHealthIndicator implements HealthIndicator {

    private final ReadinessDatabaseClient databaseClient;
    private final String expectedVersion;

    public SchemaCompatibilityHealthIndicator(
            ReadinessDatabaseClient databaseClient,
            @Value("${trust-agent.schema.expected-version}") String expectedVersion) {
        this.databaseClient = databaseClient;
        this.expectedVersion = expectedVersion;
    }

    @Override
    public Health health() {
        try {
            String actualVersion = databaseClient.latestSchemaVersion().orElse(null);
            if (expectedVersion.equals(actualVersion)) {
                return Health.up().withDetail("schemaVersion", actualVersion).build();
            }
            return Health.down()
                    .withDetail("code", "SCHEMA_VERSION_MISMATCH")
                    .withDetail("expectedVersion", expectedVersion)
                    .withDetail("actualVersion", actualVersion == null ? "NONE" : actualVersion)
                    .build();
        } catch (Exception exception) {
            return Health.down().withDetail("code", "SCHEMA_VERSION_UNAVAILABLE").build();
        }
    }
}
