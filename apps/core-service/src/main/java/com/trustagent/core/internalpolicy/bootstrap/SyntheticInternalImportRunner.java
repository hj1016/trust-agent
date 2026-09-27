package com.trustagent.core.internalpolicy.bootstrap;

import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="trust-agent.synthetic-internal-import.enabled",havingValue="true")
final class SyntheticInternalImportRunner implements ApplicationRunner {

    private final SyntheticInternalImporter importer;
    private final Environment environment;

    SyntheticInternalImportRunner(
            SyntheticInternalImporter importer, Environment environment) {
        this.importer = importer;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String root = environment.getProperty("trust-agent.synthetic-internal-import.root");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("synthetic internal import root가 필요합니다.");
        }
        importer.importBaseline(
                Path.of(root),
                environment.getProperty("trust-agent.synthetic-internal-import.run-id"));
    }
}
