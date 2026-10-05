package com.trustagent.core.internalpolicy.proposal;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * test/demo 전용 기능. production profile에서는 이 설정 전체가 로드되지 않으며,
 * production에서 두 설정 중 하나라도 켜면 ProductionRequiredSettingsConfiguration이 기동을 거부한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!prod")
class ProposalDemoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "trust-agent.proposal-generation.enabled", havingValue = "true")
    ProposalGenerationService proposalGenerationService(
            JdbcClient jdbc, ObjectMapper mapper, PlatformTransactionManager manager, Clock clock) {
        return new ProposalGenerationService(jdbc, mapper, manager, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "trust-agent.proposal-generation.enabled", havingValue = "true")
    ApplicationRunner proposalGenerationRunner(ProposalGenerationService service, Environment environment) {
        return (ApplicationArguments args) -> {
            String familyId = required(environment, "trust-agent.proposal-generation.family-id");
            String targetNoticeId = required(environment, "trust-agent.proposal-generation.target-notice-id");
            String generatorVersion = required(environment, "trust-agent.proposal-generation.generator-version");
            service.generate(new ProposalGenerationService.Request(
                    familyId,
                    targetNoticeId,
                    environment.getProperty("trust-agent.proposal-generation.run-id"),
                    generatorVersion));
        };
    }

    @Bean
    @ConditionalOnProperty(name = "trust-agent.fixture-approved-checklist.enabled", havingValue = "true")
    FixtureApprovedChecklistLoader fixtureApprovedChecklistLoader(
            JdbcClient jdbc, ObjectMapper mapper, PlatformTransactionManager manager, Clock clock) {
        return new FixtureApprovedChecklistLoader(jdbc, mapper, manager, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "trust-agent.fixture-approved-checklist.enabled", havingValue = "true")
    ApplicationRunner fixtureApprovedChecklistRunner(FixtureApprovedChecklistLoader loader, Environment environment) {
        return (ApplicationArguments args) -> loader.load(
                Path.of(required(environment, "trust-agent.fixture-approved-checklist.root")),
                environment.getProperty("trust-agent.fixture-approved-checklist.run-id"));
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("demo 설정 " + key + "가 필요합니다.");
        }
        return value;
    }
}
