package com.trustagent.core.preparation;

import com.trustagent.core.web.RequestTraceFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * /api/v1/consultation-preparations 요청의 서비스 인증. 기록 토큰이 설정값과 같을 때만 통과한다(ADR-012 9항).
 * 인증 실패는 401이며 아무것도 저장하지 않고 실행 기록도 남기지 않는다(인증 전이라 서비스 ID를 신뢰할 수 없다).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class PreparationRecordAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PreparationRecordAuthenticationFilter.class);
    static final String PATH = "/api/v1/consultation-preparations";
    static final String SERVICE_ID_ATTRIBUTE = PreparationRecordAuthenticationFilter.class.getName() + ".serviceId";
    static final String TOKEN_SCOPE_ATTRIBUTE = PreparationRecordAuthenticationFilter.class.getName() + ".tokenScope";

    private final PreparationRecordProperties properties;
    private final ObjectMapper mapper;

    public PreparationRecordAuthenticationFilter(PreparationRecordProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.equals(PATH) || uri.startsWith(PATH + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!authenticated(request.getHeader("Authorization"))) {
            String traceId = traceId(request);
            LOGGER.warn("준비안 기록 인증 거부 traceId={}", traceId);
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(mapper.writeValueAsString(java.util.Map.of(
                    "status", 401, "title", "Unauthorized", "code", "UNAUTHENTICATED",
                    "detail", "기록 서비스 인증에 실패했습니다.", "traceId", traceId)));
            return;
        }
        request.setAttribute(SERVICE_ID_ATTRIBUTE, properties.serviceId());
        request.setAttribute(TOKEN_SCOPE_ATTRIBUTE, PreparationRecordProperties.TOKEN_SCOPE);
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

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        return value == null ? "unavailable" : value.toString();
    }
}
