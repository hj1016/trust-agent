package com.trustagent.core.publicproduct.baseline;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "trust-agent.baseline-import.enabled", havingValue = "true")
final class BaselineImportRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaselineImportRunner.class);

    private final BaselineImporter importer;
    private final Environment environment;

    BaselineImportRunner(BaselineImporter importer, Environment environment) {
        this.importer = importer;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String root = environment.getProperty("trust-agent.baseline-import.root");
        if (root == null || root.isBlank()) {
            throw new BaselineImportException("INVALID_INPUT_ROOT", "baseline import root를 명시해야 합니다.");
        }
        BaselineImportResult result = importer.importBaseline(
                Path.of(root), environment.getProperty("trust-agent.baseline-import.run-id"));
        LOGGER.info(
                "Baseline import completed: runId={}, fingerprint={}, counts={}",
                result.runId(), result.baselineFingerprint(), result.importedCounts());
    }
}
