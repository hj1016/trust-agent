package com.trustagent.core.consultation;

import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** 합성 신청·기업 적재 명령. --trust-agent.synthetic-work-import.enabled=true --trust-agent.synthetic-work-import.root=<저장소 루트>. */
@Component
@ConditionalOnProperty(name = "trust-agent.synthetic-work-import.enabled", havingValue = "true")
final class SyntheticWorkImportRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyntheticWorkImportRunner.class);

    private final SyntheticWorkLoader loader;
    private final Environment environment;

    SyntheticWorkImportRunner(JdbcClient jdbc, ObjectMapper mapper, PlatformTransactionManager manager, Clock clock, Environment environment) {
        this.loader = new SyntheticWorkLoader(jdbc, mapper, manager, clock);
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String root = environment.getProperty("trust-agent.synthetic-work-import.root");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("synthetic work import root가 필요합니다.");
        }
        SyntheticWorkLoader.Result result = loader.load(Path.of(root));
        LOGGER.info("SYNTHETIC_WORK_IMPORT companies={} applications={} skipped={}", result.companiesInserted(), result.applicationsInserted(), result.skipped());
    }
}
