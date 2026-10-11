package com.trustagent.core.preparation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 기록 경로의 저장소. 준비안·섹션·실행 기록 세 표에만 INSERT하고 매핑 표는 읽기만 한다.
 * 승인·일정·변경안·검증·공문 사건·감사 표에는 어떤 문장도 보내지 않는다(통합 테스트 AC-10이 행 수 불변을 확인).
 */
class ConsultationPreparationRepository {

    record MappingFamily(String familyId, boolean required, int order) {}

    record ActiveMapping(String mappingHash, String mappingVersion, List<MappingFamily> families) {}

    record StoredPreparation(String contentHash, Instant recordedAt) {}

    record SectionRow(
            String familyId, boolean required, String status, String holdKind, String holdClaimBasis, Instant evaluatedAt,
            String selectedNoticeId, String approvedChecklistVersionId, String decisionId,
            List<String> itemRuleVersionIds, List<String> itemEvidenceHashes, List<String> blockingReasons,
            Boolean recheckUsable, List<String> recheckReasons, String toolResponseHash) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    /** 등록된 합성 신청의 원본 해시(A안). 없으면 비어 있다. */
    java.util.Optional<String> applicationSourceHash(String applicationId) {
        return jdbc.sql("select source_hash from synthetic_work_application where application_id = :id").param("id", applicationId)
                .query(String.class).optional();
    }

    ConsultationPreparationRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 활성 매핑 = 가장 최근에 적재된 mapping_hash. 상품에 해당하는 공문군만 돌려준다(없으면 빈 목록). */
    Optional<ActiveMapping> activeMapping(String productKey) {
        Optional<String> hash = jdbc.sql("select mapping_hash from consultation_family_mapping order by loaded_at desc, mapping_hash limit 1")
                .query(String.class)
                .optional();
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        String version = jdbc.sql("select mapping_version from consultation_family_mapping where mapping_hash = :hash limit 1")
                .param("hash", hash.get())
                .query(String.class)
                .single();
        List<MappingFamily> families = jdbc.sql("""
                        select family_id, required, family_order from consultation_family_mapping
                        where mapping_hash = :hash and product_key = :product
                        order by family_order
                        """)
                .param("hash", hash.get())
                .param("product", productKey)
                .query((rs, row) -> new MappingFamily(rs.getString("family_id"), rs.getBoolean("required"), rs.getInt("family_order")))
                .list();
        return Optional.of(new ActiveMapping(hash.get(), version, families));
    }

    boolean runExists(String runId) {
        return jdbc.sql("select count(*) from consultation_preparation_run where run_id = :id")
                .param("id", runId)
                .query(Integer.class)
                .single() > 0;
    }

    Optional<StoredPreparation> findPreparation(String preparationId) {
        return jdbc.sql("select content_hash, recorded_at from consultation_preparation where preparation_id = :id")
                .param("id", preparationId)
                .query((rs, row) -> new StoredPreparation(
                        rs.getString("content_hash"), rs.getObject("recorded_at", java.time.OffsetDateTime.class).toInstant()))
                .optional();
    }

    void insertPreparation(
            String preparationId, String serviceId, String tokenScope, String applicationId, String companyId, String productKey,
            String applicationSourceHash, LocalDate businessDate, String status, boolean preparationComplete,
            String assemblerVersion, String messagesHash, String familyMappingHash, int sectionCount, int requiredHoldCount,
            String consultationId, String contentHash, Instant recordedAt) {
        jdbc.sql("""
                        insert into consultation_preparation (
                            preparation_id, dataset_class, service_id, token_scope, application_id, company_id, product_key,
                            application_source_hash, business_date, status, preparation_complete, assembler_version,
                            messages_hash, family_mapping_hash, section_count, required_hold_count, consultation_id,
                            content_hash, recorded_at)
                        values (:id, 'SYNTHETIC_WORK', :service, :scope, :application, :company, :product,
                                :sourceHash, :businessDate, :status, :complete, :assembler,
                                :messagesHash, :mappingHash, :sectionCount, :requiredHoldCount, :consultation,
                                :contentHash, :recordedAt)
                        """)
                .param("id", preparationId)
                .param("service", serviceId)
                .param("scope", tokenScope)
                .param("application", applicationId)
                .param("company", companyId)
                .param("product", productKey)
                .param("sourceHash", applicationSourceHash)
                .param("businessDate", businessDate)
                .param("status", status)
                .param("complete", preparationComplete)
                .param("assembler", assemblerVersion)
                .param("messagesHash", messagesHash)
                .param("mappingHash", familyMappingHash)
                .param("sectionCount", sectionCount)
                .param("requiredHoldCount", requiredHoldCount)
                .param("consultation", consultationId)
                .param("contentHash", contentHash)
                .param("recordedAt", recordedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    void insertSection(String preparationId, SectionRow section) {
        jdbc.sql("""
                        insert into consultation_preparation_section (
                            preparation_id, family_id, required, status, hold_kind, hold_claim_basis, evaluated_at,
                            selected_notice_id, approved_checklist_version_id, decision_id,
                            item_rule_version_ids, item_evidence_hashes, blocking_reasons,
                            recheck_usable, recheck_reasons, tool_response_hash)
                        values (:id, :family, :required, :status, :holdKind, :claimBasis, :evaluatedAt,
                                :notice, :version, :decision,
                                cast(:ruleIds as jsonb), cast(:evidenceHashes as jsonb), cast(:reasons as jsonb),
                                :recheckUsable, cast(:recheckReasons as jsonb), :toolHash)
                        """)
                .param("id", preparationId)
                .param("family", section.familyId())
                .param("required", section.required())
                .param("status", section.status())
                .param("holdKind", section.holdKind())
                .param("claimBasis", section.holdClaimBasis())
                .param("evaluatedAt", section.evaluatedAt() == null ? null : section.evaluatedAt().atOffset(ZoneOffset.UTC))
                .param("notice", section.selectedNoticeId())
                .param("version", section.approvedChecklistVersionId())
                .param("decision", section.decisionId())
                .param("ruleIds", mapper.writeValueAsString(section.itemRuleVersionIds()))
                .param("evidenceHashes", mapper.writeValueAsString(section.itemEvidenceHashes()))
                .param("reasons", mapper.writeValueAsString(section.blockingReasons()))
                .param("recheckUsable", section.recheckUsable())
                .param("recheckReasons", mapper.writeValueAsString(section.recheckReasons()))
                .param("toolHash", section.toolResponseHash())
                .update();
    }

    void insertLink(String runId, String consultationId, String preparationId, Instant linkedAt) {
        jdbc.sql("insert into consultation_preparation_link (run_id, consultation_id, preparation_id, linked_at) values (:run, :consultation, :preparation, :linked)")
                .param("run", runId).param("consultation", consultationId).param("preparation", preparationId)
                .param("linked", linkedAt.atOffset(java.time.ZoneOffset.UTC)).update();
    }

    void insertRun(String runId, String preparationId, String serviceId, String tokenScope, String outcome, String errorCode,
            String traceId, Instant startedAt, Instant finishedAt, String sectionEvaluationsJson) {
        jdbc.sql("""
                        insert into consultation_preparation_run (
                            run_id, preparation_id, service_id, token_scope, outcome, error_code, trace_id, started_at, finished_at,
                            section_evaluations)
                        values (:id, :preparation, :service, :scope, :outcome, :error, :trace, :started, :finished,
                            cast(:evaluations as jsonb))
                        """)
                .param("evaluations", sectionEvaluationsJson == null ? "[]" : sectionEvaluationsJson)
                .param("id", runId)
                .param("preparation", preparationId)
                .param("service", serviceId)
                .param("scope", tokenScope)
                .param("outcome", outcome)
                .param("error", errorCode)
                .param("trace", traceId)
                .param("started", startedAt.atOffset(ZoneOffset.UTC))
                .param("finished", finishedAt.atOffset(ZoneOffset.UTC))
                .update();
    }
}
