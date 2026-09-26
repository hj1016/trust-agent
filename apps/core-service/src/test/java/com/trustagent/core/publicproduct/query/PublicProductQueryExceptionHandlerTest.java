package com.trustagent.core.publicproduct.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.trustagent.core.web.RequestTraceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class PublicProductQueryExceptionHandlerTest {

    private final PublicProductQueryExceptionHandler handler = new PublicProductQueryExceptionHandler();

    @Test
    void explicitCodeMapKeepsClientAndServerErrorsSeparate() {
        assertProblem(
                handler.handlePublicProductQuery(
                        new PublicProductQueryException("PRODUCT_NOT_FOUND", "없음"), request()),
                HttpStatus.NOT_FOUND,
                "PRODUCT_NOT_FOUND");
        assertProblem(
                handler.handlePublicProductQuery(
                        new PublicProductQueryException("INVALID_AS_OF", "잘못된 시각"), request()),
                HttpStatus.BAD_REQUEST,
                "INVALID_AS_OF");
        assertProblem(
                handler.handlePublicProductQuery(
                        new PublicProductQueryException("UNMAPPED_CODE", "내부 오류"), request()),
                HttpStatus.INTERNAL_SERVER_ERROR,
                "UNMAPPED_CODE");
    }

    @Test
    void unexpectedFailuresExposeOnlyStableCodesAndTraceId() {
        var database = handler.handleDatabaseFailure(
                new DataAccessResourceFailureException("jdbc:postgresql://secret-host/private"), request());
        assertProblem(database, HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE");
        assertFalse(database.getDetail().contains("secret-host"));

        var integrity = handler.handleEvidenceIntegrityFailure(
                new IllegalStateException("missing quote internal detail"), request());
        assertProblem(integrity, HttpStatus.INTERNAL_SERVER_ERROR, "EVIDENCE_INTEGRITY_VIOLATION");
        assertFalse(integrity.getDetail().contains("missing quote"));

        var unexpected = handler.handleUnexpected(new RuntimeException("private detail"), request());
        assertProblem(unexpected, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertFalse(unexpected.getDetail().contains("private detail"));
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE, "a".repeat(32));
        return request;
    }

    private static void assertProblem(
            org.springframework.http.ProblemDetail problem,
            HttpStatus status,
            String code) {
        assertEquals(status.value(), problem.getStatus());
        assertEquals(code, problem.getProperties().get("code"));
        assertEquals("a".repeat(32), problem.getProperties().get("traceId"));
    }
}
