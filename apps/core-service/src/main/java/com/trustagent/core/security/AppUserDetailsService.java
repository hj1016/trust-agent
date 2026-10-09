package com.trustagent.core.security;

import com.trustagent.core.control.ControlDataSourceConfiguration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** 제어 DB의 합성 직원 계정을 읽는다. 비밀번호 해시 외의 값은 응답·로그에 내지 않는다. */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final JdbcClient control;

    public AppUserDetailsService(@Qualifier(ControlDataSourceConfiguration.CONTROL_JDBC_CLIENT) JdbcClient control) {
        this.control = control;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        var user = control.sql("select user_id, password_hash, enabled from app_user where user_id = :id")
                .param("id", username)
                .query((rs, rowNum) -> new Object[] {rs.getString("user_id"), rs.getString("password_hash"), rs.getBoolean("enabled")})
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("USER_NOT_FOUND"));
        List<String> roles = control.sql("select role from app_user_role where user_id = :id order by role")
                .param("id", username).query(String.class).list();
        return User.withUsername((String) user[0])
                .password((String) user[1])
                .roles(roles.toArray(String[]::new))
                .disabled(!(Boolean) user[2])
                .build();
    }
}
