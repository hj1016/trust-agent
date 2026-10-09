package com.trustagent.core.support;

import com.trustagent.core.security.DemoUsers;
import com.trustagent.core.security.Roles;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 테스트용 합성 사용자. 비밀번호는 실행 중 생성한 임시 값이며 저장소에 없다. */
public final class TestUsers {

    public static final String STAFF = "TEST-STAFF-01";
    public static final String REVIEWER = "TEST-REVIEWER-01";
    public static final String BOTH = "TEST-STAFF-REVIEWER-01";
    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private TestUsers() {
    }

    public static String ensure(JdbcClient control, String userId, List<String> roles) {
        String password = "test-" + UUID.randomUUID();
        DemoUsers.upsert(control, ENCODER, Clock.systemUTC(), userId, roles, password);
        return password;
    }

    public static String ensureStaff(JdbcClient control) {
        return ensure(control, STAFF, List.of(Roles.STAFF));
    }

    public static String ensureReviewer(JdbcClient control) {
        return ensure(control, REVIEWER, List.of(Roles.REVIEWER));
    }

    public static String ensureBoth(JdbcClient control) {
        return ensure(control, BOTH, List.of(Roles.STAFF, Roles.REVIEWER));
    }
}
