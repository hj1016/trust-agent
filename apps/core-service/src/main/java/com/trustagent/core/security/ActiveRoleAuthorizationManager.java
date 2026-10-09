package com.trustagent.core.security;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * 경로별 권한 검사(ADR-014 6항): principal이 요구 역할 가운데 하나를 **보유**하고, 현재 **활성 역할**이 그 역할이어야 한다.
 * 비인증은 거부(진입점이 401로 바꾼다), 보유만 하고 활성이 다르면 403이다.
 */
public final class ActiveRoleAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final Set<String> allowedRoles;

    public ActiveRoleAuthorizationManager(Set<String> allowedRoles) {
        this.allowedRoles = Set.copyOf(allowedRoles);
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }
        List<String> held = ActiveRole.heldRoles(auth);
        Optional<String> active = ActiveRole.current(context.getRequest());
        boolean granted = active.isPresent() && allowedRoles.contains(active.get()) && held.contains(active.get());
        return new AuthorizationDecision(granted);
    }
}
