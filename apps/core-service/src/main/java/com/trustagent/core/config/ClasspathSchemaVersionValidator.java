package com.trustagent.core.config;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class ClasspathSchemaVersionValidator {

    private static final String MIGRATION_PATTERN = "classpath*:db/migration/V*__*.sql";
    private static final Pattern VERSION_PATTERN = Pattern.compile("^V(.+)__.+\\.sql$");

    private final String classpathVersion;

    public ClasspathSchemaVersionValidator(
            ResourcePatternResolver resourceResolver,
            @Value("${trust-agent.schema.expected-version}") String expectedVersion) {
        this.classpathVersion = findLatestVersion(resourceResolver);
        if (!MigrationVersion.fromVersion(expectedVersion)
                .equals(MigrationVersion.fromVersion(classpathVersion))) {
            throw new IllegalStateException(
                    "설정한 schema version과 classpath Flyway migration version이 다릅니다.");
        }
    }

    String classpathVersion() {
        return classpathVersion;
    }

    private static String findLatestVersion(ResourcePatternResolver resourceResolver) {
        try {
            return Arrays.stream(resourceResolver.getResources(MIGRATION_PATTERN))
                    .map(Resource::getFilename)
                    .map(ClasspathSchemaVersionValidator::extractVersion)
                    .max(Comparator.comparing(MigrationVersion::fromVersion))
                    .orElseThrow(() -> new IllegalStateException("Flyway versioned migration이 없습니다."));
        } catch (IOException exception) {
            throw new IllegalStateException("Classpath Flyway migration을 확인할 수 없습니다.", exception);
        }
    }

    private static String extractVersion(String filename) {
        if (filename == null) {
            throw new IllegalStateException("Flyway migration 파일명이 없습니다.");
        }
        var matcher = VERSION_PATTERN.matcher(filename);
        if (!matcher.matches()) {
            throw new IllegalStateException("Flyway migration 파일명을 해석할 수 없습니다: " + filename);
        }
        return matcher.group(1).replace('_', '.');
    }
}
