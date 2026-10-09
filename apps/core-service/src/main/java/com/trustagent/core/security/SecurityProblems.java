package com.trustagent.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

/** 보안 응답은 항상 JSON problem이다(HTML 로그인 페이지나 리다이렉트 없음). 다른 예외 처리기와 같은 모양(code, traceId)을 쓴다. */
final class SecurityProblems {

    private final ObjectMapper mapper;

    SecurityProblems(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("traceId", SecurityEventRecorder.traceId(request));
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(problem));
        response.getWriter().flush();
    }

    void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}
