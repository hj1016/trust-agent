package com.trustagent.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * 활성 역할(ADR-014 6항). 세션 속성으로 서버가 관리하며 보유 역할과 별개로 검사한다.
 * 로그인 직후 기본값은 STAFF를 보유하면 STAFF, 아니면 보유한 첫 역할이다.
 */
public final class ActiveRole {

    public static final String SESSION_ATTRIBUTE = "trustagent.activeRole";
    public static final String WORKSPACE_ATTRIBUTE = "trustagent.workspaceId";
    public static final String DEFAULT_WORKSPACE = "main";

    private ActiveRole() {
    }

    public static List<String> heldRoles(Authentication authentication) {
        if (authentication == null) return List.of();
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(Roles.AUTHORITY_PREFIX))
                .map(authority -> authority.substring(Roles.AUTHORITY_PREFIX.length()))
                .filter(Roles::isRole)
                .sorted()
                .toList();
    }

    public static String defaultFor(List<String> heldRoles) {
        if (heldRoles.contains(Roles.STAFF)) return Roles.STAFF;
        return heldRoles.isEmpty() ? null : heldRoles.get(0);
    }

    public static Optional<String> current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return Optional.empty();
        Object value = session.getAttribute(SESSION_ATTRIBUTE);
        return value instanceof String role && Roles.isRole(role) ? Optional.of(role) : Optional.empty();
    }

    public static String workspace(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(WORKSPACE_ATTRIBUTE);
        return value instanceof String workspace ? workspace : DEFAULT_WORKSPACE;
    }

    static void initialize(HttpSession session, List<String> heldRoles) {
        session.setAttribute(SESSION_ATTRIBUTE, defaultFor(heldRoles));
        session.setAttribute(WORKSPACE_ATTRIBUTE, DEFAULT_WORKSPACE);
    }
}
