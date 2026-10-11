package com.trustagent.core.consultation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 상담 건과 합성 신청 조회(업무 DB). 쓰기는 상담 건 INSERT 하나다. */
class ConsultationRepository {

    record Application(String applicationId, String companyId, String productKey, String sourceHash, String companyName,
                       long requestedAmountKrw, String purpose, OffsetDateTime requestedAt, String status) {}

    record Family(String familyId, boolean required, int order) {}

    record Consultation(String consultationId, String workspaceId, String applicationId, String assignedUserId, String status, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;

    ConsultationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Application> findApplication(String applicationId) {
        return jdbc.sql(APPLICATION_SELECT + " where a.application_id = :id").param("id", applicationId).query(this::mapApplication).optional();
    }

    List<Application> listApplications() {
        return jdbc.sql(APPLICATION_SELECT + " order by a.application_id").query(this::mapApplication).list();
    }

    private static final String APPLICATION_SELECT = """
            select a.application_id, a.company_id, a.product_key, a.source_hash, c.legal_name, a.requested_amount_krw, a.purpose, a.requested_at, a.status
            from synthetic_work_application a join synthetic_work_company c on c.company_id = a.company_id
            """;

    private Application mapApplication(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Application(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6),
                rs.getString(7), rs.getObject(8, OffsetDateTime.class), rs.getString(9));
    }

    /** 최신 매핑에서 상품의 공문군과 필수 여부(매핑 순서). */
    List<Family> familyDetailsForProduct(String productKey) {
        return jdbc.sql("""
                select family_id, required, family_order from consultation_family_mapping
                where product_key = :product and mapping_hash = (
                    select mapping_hash from consultation_family_mapping order by loaded_at desc, mapping_hash limit 1)
                order by family_order
                """).param("product", productKey).query((rs, rowNum) -> new Family(rs.getString(1), rs.getBoolean(2), rs.getInt(3))).list();
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
