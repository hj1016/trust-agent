package com.trustagent.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 세션 조회와 활성 역할 전환(ADR-014 6항). 전환은 재로그인 없이 서버 호출이며 사건으로 기록된다. */
@RestController
@RequestMapping("/api/v1/session")
@Validated
public class SessionController {

    public record ActiveRoleRequest(@NotBlank String role) {}

    private final SecurityEventRecorder events;

    public SessionController(SecurityEventRecorder events) {
        this.events = events;
    }

    @GetMapping
    public SessionSummary current(Authentication authentication, HttpServletRequest request) {
        return summary(authentication, request);
    }

    @PostMapping("/active-role")
    public ResponseEntity<?> switchRole(@RequestBody @Validated ActiveRoleRequest body, Authentication authentication, HttpServletRequest request) {
        String role = body.role().trim();
        if (!Roles.isRole(role)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem(HttpStatus.BAD_REQUEST, "ROLE_INVALID", "역할은 STAFF 또는 REVIEWER입니다.", request));
        }
        List<String> held = ActiveRole.heldRoles(authentication);
        if (!held.contains(role)) {
            events.record(request, SecurityEventRecorder.ACCESS_DENIED, authentication.getName(), ActiveRole.current(request).orElse(null), "ROLE_NOT_HELD", role);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(HttpStatus.FORBIDDEN, "ROLE_NOT_HELD", "보유하지 않은 역할입니다.", request));
        }
        request.getSession(true).setAttribute(ActiveRole.SESSION_ATTRIBUTE, role);
        events.recordOrFail(request, SecurityEventRecorder.ROLE_SWITCH, authentication.getName(), role, "OK", null);
        return ResponseEntity.ok(summary(authentication, request));
    }

    private static SessionSummary summary(Authentication authentication, HttpServletRequest request) {
        return new SessionSummary(authentication.getName(), ActiveRole.heldRoles(authentication), ActiveRole.current(request).orElse(null),
                ActiveRole.workspace(request), true);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("traceId", SecurityEventRecorder.traceId(request));
        return problem;
    }
}
