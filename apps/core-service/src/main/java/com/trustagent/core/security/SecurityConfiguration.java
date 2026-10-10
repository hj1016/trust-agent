package com.trustagent.core.security;

import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * 보안 경계(ADR-014).
 * 체인 1(서비스 토큰 경로): Tool·기록 경로와 actuator는 세션·CSRF 없이 기존 토큰 필터만으로 동작한다(stateless, permitAll).
 * 체인 2(세션 경로): form 로그인(JSON 응답), 쿠키 CSRF, 경로별 "보유 + 활성 역할" 검사, 비인증 401 JSON, 거부 403 JSON.
 * web 없는 명령 실행(bootRun --spring.main.web-application-type=none)에서는 필터 체인을 만들지 않는다(PasswordEncoder는 SecuritySupportConfiguration).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    public static final String[] SERVICE_TOKEN_PATHS = {"/api/v1/tools/**", "/api/v1/consultation-preparations", "/actuator/**"};

    @Bean
    SecurityProblems securityProblems(ObjectMapper mapper) {
        return new SecurityProblems(mapper);
    }

    @Bean
    @Order(1)
    SecurityFilterChain serviceTokenChain(HttpSecurity http) throws Exception {
        http.securityMatcher(SERVICE_TOKEN_PATHS)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain sessionChain(HttpSecurity http, SecurityProblems problems, SecurityEventRecorder events) throws Exception {
        JsonSecurityHandlers handlers = new JsonSecurityHandlers(problems, events);
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfRepository).csrfTokenRequestHandler(csrfHandler))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/login").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/session/**").authenticated()
                        .requestMatchers("/api/v1/reviews/**").access(new ActiveRoleAuthorizationManager(Set.of(Roles.REVIEWER)))
                        .requestMatchers("/api/v1/consultations/**").access(new ActiveRoleAuthorizationManager(Set.of(Roles.STAFF)))
                        .requestMatchers("/api/v1/internal-policy/**", "/api/v1/public-products/**")
                            .access(new ActiveRoleAuthorizationManager(Set.of(Roles.STAFF, Roles.REVIEWER)))
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").loginProcessingUrl("/login")
                        .successHandler(handlers.loginSuccess()).failureHandler(handlers.loginFailure()))
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessHandler(handlers.logoutSuccess())
                        .invalidateHttpSession(true).deleteCookies("JSESSIONID"))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(handlers.entryPoint()).accessDeniedHandler(handlers.accessDenied()))
                .sessionManagement(session -> session.sessionFixation(Customizer.withDefaults()))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable());
        return http.build();
    }
}
