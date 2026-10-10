package com.trustagent.core.consultation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 상담 건과 합성 신청 조회(업무 DB). 쓰기는 상담 건 INSERT 하나다. */
class ConsultationRepository {

    record Application(String applicationId, String companyId, String productKey, String sourceHash) {}

    record Consultation(String consultationId, String workspaceId, String applicationId, String assignedUserId, String status, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;

    ConsultationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Application> findApplication(String applicationId) {
        return jdbc.sql("select application_id, company_id, product_key, source_hash from synthetic_work_application where application_id = :id")
                .param("id", applicationId)
                .query((rs, rowNum) -> new Application(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional();
    }

    List<String> familiesForProduct(String productKey) {
        return jdbc.sql("""
                select family_id from consultation_family_mapping
                where product_key = :product and mapping_hash = (
                    select mapping_hash from consultation_family_mapping order by loaded_at desc, mapping_hash limit 1)
                order by family_order
                """).param("product", productKey).query(String.class).list();
    }

    void insert(Consultation consultation, String traceId) {
        jdbc.sql("""
                insert into consultation (consultation_id, workspace_id, application_id, assigned_user_id, status, created_at, trace_id)
                values (:id, :workspace, :application, :user, :status, :createdAt, :trace)
                """)
                .param("id", consultation.consultationId()).param("workspace", consultation.workspaceId())
                .param("application", consultation.applicationId()).param("user", consultation.assignedUserId())
                .param("status", consultation.status()).param("createdAt", consultation.createdAt()).param("trace", traceId)
                .update();
    }

    Optional<Consultation> find(String consultationId) {
        return jdbc.sql("select consultation_id, workspace_id, application_id, assigned_user_id, status, created_at from consultation where consultation_id = :id")
                .param("id", consultationId).query(this::map).optional();
    }

    List<Consultation> findMine(String workspaceId, String userId) {
        return jdbc.sql("""
                select consultation_id, workspace_id, application_id, assigned_user_id, status, created_at from consultation
                where workspace_id = :workspace and assigned_user_id = :user order by created_at desc, consultation_id
                """).param("workspace", workspaceId).param("user", userId).query(this::map).list();
    }

    private Consultation map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Consultation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, OffsetDateTime.class));
    }
}
