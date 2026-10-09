package com.trustagent.core.security;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 합성 직원 3명(TASK-017a). 비밀번호는 환경변수로만 받아 bcrypt 해시로 저장한다. 가입·재설정·관리 화면은 없다.
 * STAFF 전용, REVIEWER 전용, 겸임(활성 역할 전환 검증용). 실제 행원 인증이나 직무 분리가 아니다.
 */
public final class DemoUsers {

    public static final String STAFF_USER = "SYN-STAFF-01";
    public static final String REVIEWER_USER = "SYN-REVIEWER-01";
    public static final String BOTH_USER = "SYN-STAFF-REVIEWER-01";
    public static final Map<String, List<String>> ROLES = new LinkedHashMap<>();
    public static final String ENV_PREFIX = "TRUST_AGENT_DEMO_PASSWORD_";

    static {
        ROLES.put(STAFF_USER, List.of(Roles.STAFF));
        ROLES.put(REVIEWER_USER, List.of(Roles.REVIEWER));
        ROLES.put(BOTH_USER, List.of(Roles.STAFF, Roles.REVIEWER));
    }

    private DemoUsers() {
    }

    public static String environmentName(String userId) {
        return ENV_PREFIX + userId.replace('-', '_');
    }

    /** 사용자와 역할을 넣거나(없으면) 비밀번호 해시를 갱신한다(있으면). 설정 표이므로 갱신을 허용한다. */
    public static void upsert(JdbcClient control, PasswordEncoder encoder, Clock clock, String userId, List<String> roles, String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("DEMO_PASSWORD_MISSING: " + environmentName(userId));
        }
        var now = clock.instant().atOffset(java.time.ZoneOffset.UTC);
        control.sql("""
                insert into app_user (user_id, password_hash, display_name, synthetic, enabled, created_at, updated_at)
                values (:id, :hash, :name, true, true, :now, :now)
                on conflict (user_id) do update set password_hash = excluded.password_hash, enabled = true, updated_at = excluded.updated_at
                """)
                .param("id", userId).param("hash", encoder.encode(rawPassword)).param("name", "합성 직원 " + userId).param("now", now).update();
        control.sql("delete from app_user_role where user_id = :id").param("id", userId).update();
        for (String role : roles) {
            control.sql("insert into app_user_role (user_id, role) values (:id, :role)").param("id", userId).param("role", role).update();
        }
    }
}
