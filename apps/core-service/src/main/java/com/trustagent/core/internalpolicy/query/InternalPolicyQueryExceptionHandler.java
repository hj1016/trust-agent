package com.trustagent.core.internalpolicy.query;

import com.trustagent.core.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = InternalPolicyApplicableController.class)
class InternalPolicyQueryExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalPolicyQueryExceptionHandler.class);
    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.of(
            "POLICY_FAMILY_NOT_FOUND", HttpStatus.NOT_FOUND,
            "FUTURE_KNOWN_AT_NOT_ALLOWED", HttpStatus.BAD_REQUEST,
            "INVALID_KNOWN_AT", HttpStatus.BAD_REQUEST,
            "INVALID_BUSINESS_DATE", HttpStatus.BAD_REQUEST);

    @ExceptionHandler(InternalPolicyQueryException.class)
    ProblemDetail handleQuery(InternalPolicyQueryException exception, HttpServletRequest request) {
        HttpStatus status = STATUS_BY_CODE.getOrDefault(exception.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        if (status.is5xxServerError()) {
            LOGGER.error("알 수 없는 내부 정책 query 오류입니다. traceId={}, code={}",
                    traceId(request), exception.code(), exception);
        }
        return problem(status, exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail handleDatabaseFailure(DataAccessException exception, HttpServletRequest request) {
        LOGGER.error("내부 정책 조회 DB 오류입니다. traceId={}", traceId(request), exception);
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DATABASE_UNAVAILABLE",
                "내부 정책 저장소에 일시적으로 접근할 수 없습니다.",
                request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIntegrityFailure(IllegalStateException exception, HttpServletRequest request) {
        LOGGER.error("내부 정책 데이터 무결성 오류입니다. traceId={}", traceId(request), exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "POLICY_INTEGRITY_VIOLATION",
                "내부 정책 데이터의 무결성을 확인할 수 없습니다.",
                request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("처리하지 못한 내부 정책 조회 오류입니다. traceId={}", traceId(request), exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "요청을 처리할 수 없습니다.",
                request);
    }

    private static ProblemDetail problem(
            HttpStatus status,
            String code,
            String detail,
            HttpServletRequest request) {
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
