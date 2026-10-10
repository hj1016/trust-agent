package com.trustagent.core.consultation;

import com.trustagent.core.ai.AiServiceException;
import com.trustagent.core.grant.GrantException;
import com.trustagent.core.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ConsultationController.class)
class ConsultationExceptionHandler {

    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.of(
            "APPLICATION_NOT_REGISTERED", HttpStatus.UNPROCESSABLE_CONTENT,
            "PRODUCT_NOT_MAPPED", HttpStatus.UNPROCESSABLE_CONTENT,
            "INVALID_BUSINESS_DATE", HttpStatus.BAD_REQUEST);

    @ExceptionHandler(ConsultationException.class)
    ProblemDetail handle(ConsultationException exception, HttpServletRequest request) {
        return problem(STATUS_BY_CODE.getOrDefault(exception.code(), HttpStatus.INTERNAL_SERVER_ERROR), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(GrantException.class)
    ProblemDetail handleGrant(GrantException exception, HttpServletRequest request) {
        return problem(HttpStatus.valueOf(exception.httpStatus()), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(AiServiceException.class)
    ProblemDetail handleAi(AiServiceException exception, HttpServletRequest request) {
        return problem(HttpStatus.valueOf(exception.httpStatus()), exception.code(), exception.getMessage(), request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        Object trace = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        problem.setProperty("traceId", trace == null ? "unavailable" : trace.toString());
        return problem;
    }
}
