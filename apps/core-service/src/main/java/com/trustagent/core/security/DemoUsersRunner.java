package com.trustagent.core.security;

import com.trustagent.core.control.ControlDataSourceConfiguration;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 합성 직원 적재 명령(demo 전용, production 기동 거부). --trust-agent.demo-users.enabled=true. 비밀번호 값은 출력하지 않는다. */
@Component
@ConditionalOnProperty(name = "trust-agent.demo-users.enabled", havingValue = "true")
final class DemoUsersRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoUsersRunner.class);

    private final JdbcClientHolder control;
    private final PasswordEncoder encoder;
    private final Environment environment;
    private final Clock clock;

    DemoUsersRunner(@Qualifier(ControlDataSourceConfiguration.CONTROL_JDBC_CLIENT) org.springframework.jdbc.core.simple.JdbcClient control,
                    PasswordEncoder encoder, Environment environment, Clock clock) {
        this.control = new JdbcClientHolder(control);
        this.encoder = encoder;
        this.environment = environment;
        this.clock = clock;
    }

    record JdbcClientHolder(org.springframework.jdbc.core.simple.JdbcClient client) {}

    @Override
    public void run(ApplicationArguments args) {
        List<String> missing = new ArrayList<>();
        for (String userId : DemoUsers.ROLES.keySet()) {
            String value = environment.getProperty(DemoUsers.environmentName(userId));
            if (value == null || value.isBlank()) missing.add(DemoUsers.environmentName(userId));
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("DEMO_PASSWORD_MISSING: 환경변수가 필요합니다: " + String.join(", ", missing));
        }
        for (Map.Entry<String, List<String>> entry : DemoUsers.ROLES.entrySet()) {
            DemoUsers.upsert(control.client(), encoder, clock, entry.getKey(), entry.getValue(), environment.getProperty(DemoUsers.environmentName(entry.getKey())));
            LOGGER.info("합성 직원 적재: {} roles={}", entry.getKey(), entry.getValue());
        }
    }
}
