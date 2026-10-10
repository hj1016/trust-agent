package com.trustagent.core.preparation;

import com.trustagent.core.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    public static final String GRANT_HEADER = "X-TrustAgent-Grant";

    /**
     * 업무 트랜잭션 전에 던지거나 트랜잭션 안에서 던져 롤백되는 오류만 모은다(ConsultationPreparationService.record 참조).
     * 커밋 뒤에 날 수 있는 FAILURE_AUDIT_WRITE_FAILED(성공 실행 기록 쓰기 실패)와 커밋 결과를 알 수 없는 RECORD_WRITE_FAILED는 넣지 않는다.
     * 새 오류 코드는 기본적으로 '불확실'로 처리된다(허용 목록 방식).
     */
    static final Set<String> PRE_COMMIT_CODES = Set.of(
            "INVALID_REQUEST", "HOLD_SECTION_INVALID", "PREPARATION_ID_MISMATCH", "RUN_ID_CONFLICT", "MAPPING_MISMATCH", "MAPPING_NOT_LOADED",
            "PRODUCT_NOT_MAPPED", "SECTION_NOT_IN_MAPPING", "REQUIRED_SECTION_MISSING", "REQUIRED_FLAG_MISMATCH", "PREPARATION_STATUS_INVALID",
            "NO_REQUIRED_FAMILY", "PREPARATION_NOT_USABLE", "PREPARATION_STALE", "PREPARATION_CONFLICT", "SERIALIZATION_FAILED",
            "APPLICATION_NOT_REGISTERED", "APPLICATION_SOURCE_MISMATCH");

    private final ConsultationPreparationService service;
    private final com.trustagent.core.grant.GrantService grants;
    private final com.trustagent.core.grant.RecordOutcomeLookup recordLookup;

    ConsultationPreparationController(ConsultationPreparationService service, com.trustagent.core.grant.GrantService grants,
                                      com.trustagent.core.grant.RecordOutcomeLookup recordLookup) {
        this.service = service;
        this.grants = grants;
        this.recordLookup = recordLookup;
    }

    @PostMapping(PreparationRecordAuthenticationFilter.PATH)
    ResponseEntity<Map<String, Object>> record(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        Object serviceId = request.getAttribute(PreparationRecordAuthenticationFilter.SERVICE_ID_ATTRIBUTE);
        Object scope = request.getAttribute(PreparationRecordAuthenticationFilter.TOKEN_SCOPE_ATTRIBUTE);
        Object traceId = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        String trace = traceId == null ? "unavailable" : traceId.toString();
        // grant(ADR-014 9-1항): ISSUED → CONSUMING → 업무 기록 → CONSUMED.
        String grantId = request.getHeader(GRANT_HEADER);
        boolean withGrant = grantId != null && !grantId.isBlank();
        String consultationId = body == null ? null : text(body, "consultation_id");
        String applicationId = body == null || body.get("application") == null ? null : text(body.get("application"), "application_id");
        String runId = body == null ? null : text(body, "run_id");
        String preparationId = body == null ? null : text(body, "preparation_id");
        try {
            grants.beginRecord(grantId, consultationId, applicationId, null, runId, preparationId, trace);
        } catch (com.trustagent.core.grant.GrantException exception) {
            throw new ConsultationPreparationException(exception.code(), exception.getMessage());
        }
        ConsultationPreparationService.Result result;
        try {
            result = service.record(
                    body,
                    serviceId == null ? "UNKNOWN" : serviceId.toString(),
                    scope == null ? "UNKNOWN" : scope.toString(),
                    trace);
        } catch (RuntimeException exception) {
            if (withGrant) {
                settleAfterFailure(grantId, runId, exception, trace);
            }
            throw exception;
        }
        if (withGrant) {
            grants.completeRecord(grantId, result.runId(), trace);
        }
        HttpStatus status = result.outcome() == ConsultationPreparationService.Outcome.RECORDED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(Map.of(
                "preparationId", result.preparationId(),
                "runId", result.runId(),
                "status", result.outcome().name(),
                "recordedAt", result.recordedAt().toString()));
    }

    /**
     * 기록 실패 뒤 grant 처리. 업무 기록이 커밋된 뒤의 실패에서 grant를 재사용 가능(ISSUED)으로 돌리지 않는 것이 목적이다.
     * (1) 커밋 전 거부가 코드 구조상 확정된 오류(PRE_COMMIT_CODES): ISSUED로 되돌린다.
     * (2) 그 밖(실행 기록 쓰기 실패, 커밋 결과를 알 수 없는 DB 오류, 예상 밖 예외): 업무 DB에서 이번 run_id의 성공 실행 기록을 찾아
     *     있으면 CONSUMED, 없거나 조회가 실패하면 CONSUMING으로 두고 만료 뒤 대조에 맡긴다.
     */
    private void settleAfterFailure(String grantId, String runId, RuntimeException exception, String trace) {
        String code = exception instanceof ConsultationPreparationException failure ? failure.code() : "UNEXPECTED_ERROR";
        if (PRE_COMMIT_CODES.contains(code)) {
            grants.releaseRecord(grantId, code, trace);
            return;
        }
        try {
            Optional<String> committed = recordLookup.committedRun(runId);
            if (committed.isPresent()) {
                grants.completeRecord(grantId, committed.get(), trace);
                return;
            }
        } catch (RuntimeException lookupFailure) {
            org.slf4j.LoggerFactory.getLogger(ConsultationPreparationController.class)
                    .error("기록 결과 조회 실패(grant CONSUMING 유지) grant={} traceId={}", grantId, trace, lookupFailure);
        }
        grants.markUncertain(grantId, code, trace);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? null : value.stringValue();
    }
}
