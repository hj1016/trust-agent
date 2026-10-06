package com.trustagent.core.tool;

import com.trustagent.core.web.RequestTraceFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * /api/v1/tools/ 아래 요청의 서비스 인증. Bearer 토큰이 설정값과 같을 때만 통과한다.
 * 토큰이 설정되지 않은 환경에서는 모든 호출을 거부한다. 거부도 감사 기록을 남긴다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ToolAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolAuthenticationFilter.class);
    static final String PATH_PREFIX = "/api/v1/tools/";
    static final String SERVICE_ID_ATTRIBUTE = ToolAuthenticationFilter.class.getName() + ".serviceId";

    private final ToolApiProperties properties;
    private final ToolCallAuditRecorder recorder;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ToolAuthenticationFilter(ToolApiProperties properties, ToolCallAuditRecorder recorder, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.recorder = recorder;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = traceId(request);
        if (!authenticated(request.getHeader("Authorization"))) {
            String toolName = request.getRequestURI().substring(PATH_PREFIX.length());
            try {
                recorder.record(new ToolCallAudit("UNAUTHENTICATED", toolName.isBlank() ? "UNKNOWN" : truncate(toolName),
                        null, null, null, "UNAUTHENTICATED", null, List.of(), traceId, clock.instant()));
            } catch (RuntimeException auditFailure) {
                LOGGER.error("Tool 인증 거부 감사 기록 실패 traceId={}", traceId, auditFailure);
            }
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(mapper.writeValueAsString(java.util.Map.of(
                    "status", 401, "title", "Unauthorized", "code", "UNAUTHENTICATED",
                    "detail", "서비스 인증에 실패했습니다.", "traceId", traceId)));
            return;
        }
        request.setAttribute(SERVICE_ID_ATTRIBUTE, properties.serviceId());
        chain.doFilter(request, response);
    }

    private boolean authenticated(String header) {
        if (!properties.configured() || header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] presented = header.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8);
        byte[] expected = properties.serviceToken().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, expected);
    }

    private static String truncate(String value) {
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        return value == null ? "unavailable" : value.toString();
    }
}
