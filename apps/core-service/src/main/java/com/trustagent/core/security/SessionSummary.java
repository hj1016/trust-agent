package com.trustagent.core.security;

import java.util.List;

/** 세션 조회 응답. 토큰·비밀번호·내부 ID는 없다. */
public record SessionSummary(String principal, List<String> roles, String activeRole, String workspaceId, boolean synthetic) {
}
