package com.trustagent.core.preparation;

import com.trustagent.core.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * AI 서비스 산출물(상담 준비안·보류) 기록 경로. Tool이 아니며 allowlist 밖이다(ADR-012).
 * 준비안·섹션·실행 기록 세 표에만 쓰고 업무 상태(승인·일정·변경안·검증·공문 사건·매핑)는 바꾸지 않는다.
 */
@RestController
public class ConsultationPreparationController {

    private final ConsultationPreparationService service;

    ConsultationPreparationController(ConsultationPreparationService service) {
        this.service = service;
    }

    @PostMapping(PreparationRecordAuthenticationFilter.PATH)
    ResponseEntity<Map<String, Object>> record(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        Object serviceId = request.getAttribute(PreparationRecordAuthenticationFilter.SERVICE_ID_ATTRIBUTE);
        Object scope = request.getAttribute(PreparationRecordAuthenticationFilter.TOKEN_SCOPE_ATTRIBUTE);
        Object traceId = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        ConsultationPreparationService.Result result = service.record(
                body,
                serviceId == null ? "UNKNOWN" : serviceId.toString(),
                scope == null ? "UNKNOWN" : scope.toString(),
                traceId == null ? "unavailable" : traceId.toString());
        HttpStatus status = result.outcome() == ConsultationPreparationService.Outcome.RECORDED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(Map.of(
                "preparationId", result.preparationId(),
                "runId", result.runId(),
                "status", result.outcome().name(),
                "recordedAt", result.recordedAt().toString()));
    }
}
