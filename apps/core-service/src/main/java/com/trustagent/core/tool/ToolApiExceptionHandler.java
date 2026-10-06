package com.trustagent.core.tool;

import com.trustagent.core.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ToolController.class)
class ToolApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolApiExceptionHandler.class);
    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.of(
            "TOOL_NOT_FOUND", HttpStatus.NOT_FOUND,
            "POLICY_FAMILY_NOT_FOUND", HttpStatus.NOT_FOUND,
            "INVALID_REQUEST", HttpStatus.BAD_REQUEST,
            "INVALID_BUSINESS_DATE", HttpStatus.BAD_REQUEST,
            "EVIDENCE_NOT_AVAILABLE", HttpStatus.FORBIDDEN,
            "AUDIT_WRITE_FAILED", HttpStatus.INTERNAL_SERVER_ERROR);

    @ExceptionHandler(ToolApiException.class)
    ProblemDetail handle(ToolApiException exception, HttpServletRequest request) {
        HttpStatus status = STATUS_BY_CODE.getOrDefault(exception.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        if (status.is5xxServerError()) {
            LOGGER.error("Tool API 오류 traceId={} code={}", traceId(request), exception.code(), exception);
        }
        return problem(status, exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 본문을 읽을 수 없습니다.", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("처리하지 못한 Tool API 오류 traceId={}", traceId(request), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "요청을 처리할 수 없습니다.", request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("traceId", traceId(request));
        return problem;
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        return value == null ? "unavailable" : value.toString();
    }
}
