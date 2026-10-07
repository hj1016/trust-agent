package com.trustagent.core.tool;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 감사 기록을 별도 트랜잭션으로 저장한다. 실패하면 호출자가 응답을 실패시킨다(fail-closed). */
@Component
public class ToolCallAuditRecorder {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public ToolCallAuditRecorder(JdbcClient jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public String record(ToolCallAudit audit) {
        String auditId = "tool-call:" + UUID.randomUUID().toString().replace("-", "");
        transaction.executeWithoutResult(status -> jdbc.sql("""
                        insert into tool_call_audit (
                            audit_id, service_id, tool_name, family_id, business_date, consultation_id,
                            outcome, usable, blocking_reasons, trace_id, called_at)
                        values (:id, :service, :tool, :family, :businessDate, :consultation,
                                :outcome, :usable, cast(:reasons as jsonb), :trace, :calledAt)
                        """)
                .param("id", auditId)
                .param("service", audit.serviceId())
                .param("tool", audit.toolName())
                .param("family", audit.familyId())
                .param("businessDate", audit.businessDate())
                .param("consultation", audit.consultationId())
                .param("outcome", audit.outcome())
                .param("usable", audit.usable())
                .param("reasons", mapper.writeValueAsString(audit.blockingReasons()))
                .param("trace", audit.traceId())
                .param("calledAt", audit.calledAt().atOffset(java.time.ZoneOffset.UTC))
                .update());
        return auditId;
    }
}
