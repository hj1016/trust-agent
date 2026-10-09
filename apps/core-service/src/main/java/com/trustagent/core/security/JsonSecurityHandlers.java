package com.trustagent.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * 로그인·로그아웃·거부 응답(ADR-014 1·17항). 모두 JSON이며 리다이렉트가 없다.
 * 로그인 성공은 활성 역할·workspace를 세션에 넣고 사건을 기록한 뒤에만 200이다. 기록 실패면 세션을 버리고 503이다.
 */
final class JsonSecurityHandlers {

    private final SecurityProblems problems;
    private final SecurityEventRecorder events;

    JsonSecurityHandlers(SecurityProblems problems, SecurityEventRecorder events) {
        this.problems = problems;
        this.events = events;
    }

    AuthenticationEntryPoint entryPoint() {
        return (request, response, exception) -> {
            events.record(request, SecurityEventRecorder.UNAUTHENTICATED, null, null, "UNAUTHENTICATED", null);
            problems.write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요합니다.");
        };
    }

    AccessDeniedHandler accessDenied() {
        return (request, response, exception) -> {
            String principal = principal(request);
            String active = ActiveRole.current(request).orElse(null);
            if (exception instanceof CsrfException) {
                events.record(request, SecurityEventRecorder.CSRF_REJECTED, principal, active, "CSRF_REJECTED", null);
                problems.write(request, response, HttpStatus.FORBIDDEN, "CSRF_REJECTED", "CSRF 토큰이 없거나 맞지 않습니다.");
                return;
            }
            events.record(request, SecurityEventRecorder.ACCESS_DENIED, principal, active, "FORBIDDEN", null);
            problems.write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "이 경로를 수행할 권한이나 활성 역할이 없습니다.");
        };
    }

    AuthenticationSuccessHandler loginSuccess() {
        return (request, response, authentication) -> {
            List<String> held = ActiveRole.heldRoles(authentication);
            HttpSession session = request.getSession(true);
            ActiveRole.initialize(session, held);
            String active = ActiveRole.defaultFor(held);
            try {
                events.recordOrFail(request, SecurityEventRecorder.LOGIN_SUCCESS, authentication.getName(), active, "OK", null);
            } catch (DataAccessException exception) {
                session.invalidate();
                problems.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "SECURITY_EVENT_WRITE_FAILED", "보안 사건을 기록하지 못해 로그인을 완료하지 않았습니다.");
                return;
            }
            problems.writeJson(response, 200, new SessionSummary(authentication.getName(), held, active, ActiveRole.DEFAULT_WORKSPACE, true));
        };
    }

    AuthenticationFailureHandler loginFailure() {
        return (request, response, exception) -> {
            String attempted = request.getParameter("username");
            events.record(request, SecurityEventRecorder.LOGIN_FAILURE, attempted == null || attempted.isBlank() ? null : attempted.substring(0, Math.min(64, attempted.length())),
                    null, "LOGIN_FAILED", exception.getClass().getSimpleName());
            problems.write(request, response, HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "사용자 ID 또는 비밀번호가 맞지 않습니다.");
        };
    }

    LogoutSuccessHandler logoutSuccess() {
        return (request, response, authentication) -> {
            events.record(request, SecurityEventRecorder.LOGOUT, authentication == null ? null : authentication.getName(), null, "OK", null);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        };
    }

    private static String principal(HttpServletRequest request) {
        // CsrfFilter는 SecurityContextHolderAwareRequestFilter보다 먼저 실행되므로 request.getUserPrincipal()이 아니라 컨텍스트를 본다.
        Authentication authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }

    static boolean isAccessDenied(Exception exception) {
        return exception instanceof AccessDeniedException;
    }
}
