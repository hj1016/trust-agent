package com.trustagent.core.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** web 유무와 무관하게 필요한 보안 bean(비밀번호 해시). demo-users runner가 web 없는 명령 모드에서도 쓴다. */
@Configuration(proxyBeanMethods = false)
public class SecuritySupportConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
