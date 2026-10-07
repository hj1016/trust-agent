package com.trustagent.core.preparation;

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

/** 기록 경로의 오류 응답. 코드별 HTTP 상태는 TASK-015 계획 4절·ADR-012 13~15항을 따른다. */
@RestControllerAdvice(assignableTypes = ConsultationPreparationController.class)
class ConsultationPreparationExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsultationPreparationExceptionHandler.class);
    static final Map<String, HttpStatus> STATUS_BY_CODE = Map.ofEntries(
            Map.entry("INVALID_REQUEST", HttpStatus.BAD_REQUEST),
            Map.entry("HOLD_SECTION_INVALID", HttpStatus.BAD_REQUEST),
            Map.entry("PREPARATION_ID_MISMATCH", HttpStatus.BAD_REQUEST),
            Map.entry("RUN_ID_CONFLICT", HttpStatus.CONFLICT),
            Map.entry("MAPPING_MISMATCH", HttpStatus.CONFLICT),
            Map.entry("MAPPING_NOT_LOADED", HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry("PRODUCT_NOT_MAPPED", HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry("SECTION_NOT_IN_MAPPING", HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry("REQUIRED_SECTION_MISSING", HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry("REQUIRED_FLAG_MISMATCH", HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry("PREPARATION_STATUS_INVALID", HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry("NO_REQUIRED_FAMILY", HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry("PREPARATION_NOT_USABLE", HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry("PREPARATION_STALE", HttpStatus.CONFLICT),
            Map.entry("PREPARATION_CONFLICT", HttpStatus.CONFLICT),
            Map.entry("SERIALIZATION_FAILED", HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry("RECORD_WRITE_FAILED", HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry("FAILURE_AUDIT_WRITE_FAILED", HttpStatus.INTERNAL_SERVER_ERROR));

    @ExceptionHandler(ConsultationPreparationException.class)
    ProblemDetail handle(ConsultationPreparationException exception, HttpServletRequest request) {
        HttpStatus status = STATUS_BY_CODE.getOrDefault(exception.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        if (status.is5xxServerError()) {
            LOGGER.error("준비안 기록 오류 traceId={} code={}", traceId(request), exception.code(), exception);
        }
        return problem(status, exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 본문을 읽을 수 없습니다.", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("처리하지 못한 준비안 기록 오류 traceId={}", traceId(request), exception);
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
