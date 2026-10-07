package com.trustagent.core.tool;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 감사 기록 한 건. 본문, 규칙 원문, 토큰은 들어가지 않는다. */
public record ToolCallAudit(
        String serviceId,
        String toolName,
        String familyId,
        LocalDate businessDate,
        String consultationId,
        String outcome,
        Boolean usable,
        List<String> blockingReasons,
        String traceId,
        Instant calledAt) {
}
