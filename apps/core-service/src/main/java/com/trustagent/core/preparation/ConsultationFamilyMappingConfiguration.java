package com.trustagent.core.preparation;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * 공문군 매핑 적재 명령(bootstrap 경로). trust-agent.consultation-family-mapping.enabled=true와 root가 있을 때 기동 시 1회 적재한다.
 * demo 전용 기능이 아니라 production에서도 쓸 수 있는 적재 경로이며, AI 기록 토큰과는 무관하다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "trust-agent.consultation-family-mapping.enabled", havingValue = "true")
class ConsultationFamilyMappingConfiguration {

    @Bean
    ConsultationFamilyMappingLoader consultationFamilyMappingLoader(
            JdbcClient jdbc, ObjectMapper mapper, PlatformTransactionManager manager, Clock clock) {
        return new ConsultationFamilyMappingLoader(jdbc, mapper, manager, clock);
    }

    @Bean
    ApplicationRunner consultationFamilyMappingRunner(ConsultationFamilyMappingLoader loader, Environment environment) {
        return (ApplicationArguments args) -> {
            String root = environment.getProperty("trust-agent.consultation-family-mapping.root");
            if (root == null || root.isBlank()) {
                throw new IllegalStateException("설정 trust-agent.consultation-family-mapping.root가 필요합니다.");
            }
            loader.load(Path.of(root));
        };
    }
}
