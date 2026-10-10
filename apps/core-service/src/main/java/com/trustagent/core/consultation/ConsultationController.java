package com.trustagent.core.consultation;

import com.trustagent.core.ai.AiServiceClient;
import com.trustagent.core.grant.GrantService;
import com.trustagent.core.security.ActiveRole;
import com.trustagent.core.security.SecurityEventRecorder;
import com.trustagent.core.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상담 건 API(STAFF 활성). 담당자가 아니면 404이며 거부는 보안 사건으로 남긴다.
 * 준비안 요청은 grant를 발급해 AI 서비스를 내부망에서 부르고 응답을 그대로 전달한다(상태 코드 포함).
 */
@RestController
@RequestMapping("/api/v1/consultations")
@Validated
public class ConsultationController {

    public record CreateRequest(@NotBlank String applicationId) {}

    public record PreparationRequest(String businessDate) {}

    private final ConsultationService consultations;
    private final AiServiceClient aiService;
    private final GrantService grants;
    private final SecurityEventRecorder events;

    public ConsultationController(ConsultationService consultations, AiServiceClient aiService, GrantService grants, SecurityEventRecorder events) {
        this.consultations = consultations;
        this.aiService = aiService;
        this.grants = grants;
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<ConsultationService.View> create(@RequestBody @Validated CreateRequest body, Authentication authentication, HttpServletRequest request) {
        ConsultationService.View view = consultations.create(body.applicationId().trim(), authentication.getName(), ActiveRole.workspace(request), traceId(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping
    public Map<String, List<ConsultationService.View>> mine(Authentication authentication, HttpServletRequest request) {
        return Map.of("consultations", consultations.mine(authentication.getName(), ActiveRole.workspace(request)));
    }

    @GetMapping("/{consultationId}")
    public ResponseEntity<?> get(@PathVariable String consultationId, Authentication authentication, HttpServletRequest request) {
        Optional<ConsultationService.View> view = consultations.findOwned(consultationId, authentication.getName(), ActiveRole.workspace(request));
        if (view.isEmpty()) {
            return notOwned(consultationId, authentication, request);
        }
        return ResponseEntity.ok(view.get());
    }

    @PostMapping("/{consultationId}/preparation")
    public ResponseEntity<String> preparation(@PathVariable String consultationId, @RequestBody(required = false) PreparationRequest body,
                                              Authentication authentication, HttpServletRequest request) {
        Optional<ConsultationService.View> view = consultations.findOwned(consultationId, authentication.getName(), ActiveRole.workspace(request));
        if (view.isEmpty()) {
            ResponseEntity<?> denied = notOwned(consultationId, authentication, request);
            return ResponseEntity.status(denied.getStatusCode()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(String.valueOf(denied.getBody()));
        }
        LocalDate businessDate;
        try {
            businessDate = body == null || body.businessDate() == null || body.businessDate().isBlank()
                    ? LocalDate.now(java.time.ZoneId.of("Asia/Seoul")) : LocalDate.parse(body.businessDate().trim());
        } catch (DateTimeParseException exception) {
            throw new ConsultationException("INVALID_BUSINESS_DATE", "업무일은 YYYY-MM-DD 형식이어야 합니다.");
        }
        String activeRole = ActiveRole.current(request).orElse("STAFF");
        GrantService.Grant grant = consultations.issueGrant(view.get(), businessDate, authentication.getName(), activeRole, traceId(request));
        AiServiceClient.Response response = aiService.prepare(view.get().applicationId(), businessDate.toString(), consultationId, grant.grantId(), traceId(request));
        return ResponseEntity.status(response.status())
                .contentType(response.status() >= 400 && !response.body().startsWith("{\"preparation_id\"") ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_JSON)
                .header("X-TrustAgent-Grant", grant.grantId())
                .body(response.body());
    }

    private ResponseEntity<ProblemDetail> notOwned(String consultationId, Authentication authentication, HttpServletRequest request) {
        events.record(request, SecurityEventRecorder.ACCESS_DENIED, authentication.getName(), ActiveRole.current(request).orElse(null),
                "CONSULTATION_NOT_OWNED", consultationId.length() > 64 ? consultationId.substring(0, 64) : consultationId);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "상담 건을 찾을 수 없습니다.");
        problem.setTitle(HttpStatus.NOT_FOUND.getReasonPhrase());
        problem.setProperty("code", "CONSULTATION_NOT_FOUND");
        problem.setProperty("traceId", traceId(request));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        return value == null ? "unavailable" : value.toString();
    }
}
