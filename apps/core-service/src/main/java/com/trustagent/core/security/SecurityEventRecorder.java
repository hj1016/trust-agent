package com.trustagent.core.security;

import com.trustagent.core.control.ControlDataSourceConfiguration;
import com.trustagent.core.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 보안 사건 기록(ADR-014 17항). 로그인 성공·실패, 로그아웃, 역할 전환, 비인증·권한·CSRF 거부를 제어 DB에 append-only로 남긴다.
 * 본문·토큰·비밀번호는 기록하지 않는다. 로그인 성공 기록 실패는 호출자가 세션 발급을 막고(fail-closed), 거부 기록 실패는 로그만 남긴다.
 */
@Service
public class SecurityEventRecorder {

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String LOGOUT = "LOGOUT";
    public static final String ROLE_SWITCH = "ROLE_SWITCH";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String CSRF_REJECTED = "CSRF_REJECTED";

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityEventRecorder.class);

    private final JdbcClient control;
    private final Clock clock;

    public SecurityEventRecorder(@Qualifier(ControlDataSourceConfiguration.CONTROL_JDBC_CLIENT) JdbcClient control, Clock clock) {
        this.control = control;
        this.clock = clock;
    }

    /** 기록하고 실패하면 예외를 던진다(로그인 성공처럼 기록 없이는 진행하지 않을 때). */
    public void recordOrFail(HttpServletRequest request, String eventType, String principal, String activeRole, String outcome, String detail) {
        control.sql("""
                insert into security_event (event_id, occurred_at, event_type, principal, active_role, workspace_id, http_method, path, outcome, trace_id, detail)
                values (:id, :at, :type, :principal, :role, :workspace, :method, :path, :outcome, :trace, :detail)
                """)
                .param("id", "security-event:" + UUID.randomUUID().toString().replace("-", ""))
                .param("at", clock.instant().atOffset(java.time.ZoneOffset.UTC))
                .param("type", eventType)
                .param("principal", principal)
                .param("role", activeRole)
                .param("workspace", ActiveRole.workspace(request))
                .param("method", request.getMethod())
                .param("path", truncate(request.getRequestURI(), 512))
                .param("outcome", outcome)
                .param("trace", traceId(request))
                .param("detail", truncate(detail, 512))
                .update();
    }

    /** 기록을 시도하고 실패하면 로그만 남긴다(거부 응답은 그대로 간다). */
    public void record(HttpServletRequest request, String eventType, String principal, String activeRole, String outcome, String detail) {
        try {
            recordOrFail(request, eventType, principal, activeRole, outcome, detail);
        } catch (DataAccessException exception) {
            LOGGER.error("보안 사건 기록 실패 type={} outcome={} traceId={}", eventType, outcome, traceId(request), exception);
        }
    }

    static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        return value == null ? "unavailable" : value.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
