package com.trustagent.core.tool;

import com.trustagent.core.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** AI 서비스용 Tool 진입점 하나. 읽기 전용이며 쓰기 Tool은 없다. */
@RestController
public class ToolController {

    private final ToolService service;

    ToolController(ToolService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/tools/{toolName}")
    Object call(@PathVariable String toolName, @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        Object serviceId = request.getAttribute(ToolAuthenticationFilter.SERVICE_ID_ATTRIBUTE);
        Object traceId = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        return service.call(toolName, body, serviceId == null ? "UNKNOWN" : serviceId.toString(),
                traceId == null ? "unavailable" : traceId.toString());
    }
}
