package com.trustagent.core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestTraceFilterTest {

    @Test
    void traceIdConnectsRequestResponseAndMdcAndIsClearedAfterRequest() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/public-products/example/observed-state");
        var response = new MockHttpServletResponse();
        var filter = new RequestTraceFilter();
        jakarta.servlet.FilterChain chain = (servletRequest, servletResponse) -> {
            String traceId = (String) servletRequest.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
            assertTrue(traceId.matches("[a-f0-9]{32}"));
            assertEquals(traceId, MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY));
        };

        filter.doFilterInternal(request, response, chain);

        String traceId = (String) request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        assertEquals(traceId, response.getHeader(RequestTraceFilter.TRACE_ID_HEADER));
        assertNull(MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY));
    }
}
