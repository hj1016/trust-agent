package com.trustagent.core.publicproduct.query;

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

@RestControllerAdvice(assignableTypes = PublicProductObservedStateController.class)
class PublicProductQueryExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicProductQueryExceptionHandler.class);
    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.of(
            "PRODUCT_NOT_FOUND", HttpStatus.NOT_FOUND,
            "FUTURE_AS_OF_NOT_ALLOWED", HttpStatus.BAD_REQUEST,
            "INVALID_AS_OF", HttpStatus.BAD_REQUEST);

    @ExceptionHandler(PublicProductQueryException.class)
    ProblemDetail handlePublicProductQuery(
            PublicProductQueryException exception,
            HttpServletRequest request) {
        HttpStatus status = STATUS_BY_CODE.getOrDefault(
                exception.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        if (status.is5xxServerError()) {
            LOGGER.error("알 수 없는 공개 상품 query 오류 code입니다. traceId={}, code={}",
                    traceId(request), exception.code(), exception);
        }
        return problem(status, exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail handleDatabaseFailure(DataAccessException exception, HttpServletRequest request) {
        LOGGER.error("공개 상품 조회 DB 오류입니다. traceId={}", traceId(request), exception);
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DATABASE_UNAVAILABLE",
                "공개 상품 저장소에 일시적으로 접근할 수 없습니다.",
                request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleEvidenceIntegrityFailure(
            IllegalStateException exception,
            HttpServletRequest request) {
        LOGGER.error("공개 상품 evidence 무결성 오류입니다. traceId={}", traceId(request), exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "EVIDENCE_INTEGRITY_VIOLATION",
                "공개 상품 근거의 무결성을 확인할 수 없습니다.",
                request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("처리하지 못한 공개 상품 조회 오류입니다. traceId={}", traceId(request), exception);
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
