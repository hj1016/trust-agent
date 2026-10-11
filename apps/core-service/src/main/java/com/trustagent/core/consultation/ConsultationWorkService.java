package com.trustagent.core.consultation;

import com.trustagent.core.internalpolicy.query.InternalPolicyApplicableService;
import com.trustagent.core.internalpolicy.query.InternalPolicyApplicableState;
import com.trustagent.core.internalpolicy.query.InternalPolicyQueryException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 상담 건 화면의 업무 데이터(TASK-017b): 상담 건별 최신 준비안 조회와 직원 확인 기록.
 * 준비안은 상담 건-준비안 연결(V12 consultation_preparation_link)로만 찾는다. 준비안 행의 consultation_id는 처음 기록한 상담 건이라 쓰지 않는다.
 * 직원 확인은 READY 섹션 단위의 "근거 확인" 기록이며 대출 승인·거절, 금리·한도 확정, 신용등급 결정, 상담 준비 완료, 고객별 적용 승인이 아니다.
 * 담당자 검사는 호출 전에 컨트롤러가 한다(ConsultationService.findOwned).
 */
@Service
public class ConsultationWorkService {

    public record Section(String familyId, boolean required, String status, String holdKind, List<String> blockingReasons,
                          String selectedNoticeId, String approvedChecklistVersionId, String decisionId,
                          List<String> itemRuleVersionIds, List<String> itemEvidenceHashes) {}

    public record Preparation(String consultationId, String preparationId, String runId, String status, boolean preparationComplete,
                              String businessDate, String recordedAt, String linkedAt, List<Section> sections) {}

    public record Confirmation(String confirmationId, String consultationId, String preparationId, String familyId, String businessDate,
                               String selectedNoticeId, String approvedChecklistVersionId, String decisionId,
                               List<String> confirmedRuleVersionIds, List<String> confirmedEvidenceHashes, String confirmedBy,
                               String activeRole, String recheckEvaluatedAt, String confirmedAt) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final InternalPolicyApplicableService applicable;
    private final Clock clock;

    public ConsultationWorkService(JdbcClient jdbc, ObjectMapper mapper, InternalPolicyApplicableService applicable, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.applicable = applicable;
        this.clock = clock;
    }

    /** 이 상담 건에 연결된 최신 준비안. 없으면 비어 있다. */
    public Optional<Preparation> latestPreparation(String consultationId) {
        return jdbc.sql("""
                select l.run_id, l.preparation_id, l.linked_at, p.status, p.preparation_complete, p.business_date, p.recorded_at
                from consultation_preparation_link l join consultation_preparation p on p.preparation_id = l.preparation_id
                where l.consultation_id = :consultation
                order by l.linked_at desc, l.run_id desc limit 1
                """).param("consultation", consultationId)
                .query((rs, rowNum) -> new Preparation(consultationId, rs.getString("preparation_id"), rs.getString("run_id"), rs.getString("status"),
                        rs.getBoolean("preparation_complete"), rs.getObject("business_date", LocalDate.class).toString(),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant().toString(), rs.getObject("linked_at", OffsetDateTime.class).toInstant().toString(),
                        sections(rs.getString("preparation_id"))))
                .optional();
    }

    public List<Confirmation> confirmations(String consultationId) {
        return jdbc.sql("select * from consultation_confirmation where consultation_id = :consultation order by confirmed_at, confirmation_id")
                .param("consultation", consultationId).query(this::mapConfirmation).list();
    }

    /**
     * 직원 확인 기록. 순서: 준비안이 이 상담 건에 연결됐는가 → 섹션이 있는가 → READY인가 → 항목 전체를 확인했는가 → 중복 아님 →
     * 기록 직전 현재 승인 상태 재조회(사용 가능, 선택 공문·승인 checklist version·결정 ID·항목 규칙 version·근거 해시가 준비안과 같음) → 기록.
     */
    public Confirmation confirm(String consultationId, String preparationId, String familyId, List<String> ruleVersionIds,
                                String userId, String activeRole, String traceId) {
        if (!"STAFF".equals(activeRole)) {
            throw new ConsultationException("ROLE_NOT_ACTIVE", "STAFF 활성 역할에서만 확인을 기록합니다.");
        }
        Optional<String> businessDate = jdbc.sql("""
                select p.business_date from consultation_preparation_link l join consultation_preparation p on p.preparation_id = l.preparation_id
                where l.consultation_id = :consultation and l.preparation_id = :preparation limit 1
                """).param("consultation", consultationId).param("preparation", preparationId == null ? "" : preparationId)
                .query((rs, rowNum) -> rs.getObject(1, LocalDate.class).toString()).optional();
        if (businessDate.isEmpty()) {
            throw new ConsultationException("PREPARATION_NOT_FOUND", "이 상담 건에 기록된 준비안이 아닙니다.");
        }
        Section section = sections(preparationId).stream().filter(candidate -> candidate.familyId().equals(familyId)).findFirst()
                .orElseThrow(() -> new ConsultationException("SECTION_NOT_IN_PREPARATION", "준비안에 없는 공문군입니다."));
        if (!"READY".equals(section.status())) {
            throw new ConsultationException("SECTION_ON_HOLD", "보류된 공문군은 확인 기록 대상이 아닙니다.");
        }
        List<String> requested = ruleVersionIds == null ? List.of() : ruleVersionIds;
        Set<String> requestedSet = new HashSet<>(requested);
        if (requestedSet.size() != requested.size() || !section.itemRuleVersionIds().containsAll(requestedSet)) {
            throw new ConsultationException("CONFIRMATION_MISMATCH", "준비안 항목에 없는 규칙이거나 중복입니다.");
        }
        if (requestedSet.size() != section.itemRuleVersionIds().size()) {
            throw new ConsultationException("CONFIRMATION_INCOMPLETE", "섹션의 모든 항목 근거를 확인해야 기록할 수 있습니다.");
        }
        if (jdbc.sql("select count(*) from consultation_confirmation where consultation_id = :c and preparation_id = :p and family_id = :f")
                .param("c", consultationId).param("p", preparationId).param("f", familyId).query(Integer.class).single() > 0) {
            throw new ConsultationException("ALREADY_CONFIRMED", "이 준비안의 이 공문군은 이미 확인 기록이 있습니다.");
        }
        OffsetDateTime evaluatedAt = recheck(section, businessDate.get());
        String confirmationId = "consultation-confirmation:" + UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime confirmedAt = clock.instant().atOffset(ZoneOffset.UTC);
        try {
            jdbc.sql("""
                    insert into consultation_confirmation (confirmation_id, consultation_id, preparation_id, family_id, business_date, selected_notice_id,
                        approved_checklist_version_id, decision_id, confirmed_rule_version_ids, confirmed_evidence_hashes, confirmed_by, active_role,
                        recheck_evaluated_at, confirmed_at, trace_id)
                    values (:id, :consultation, :preparation, :family, :businessDate, :notice, :checklist, :decision, cast(:rules as jsonb),
                        cast(:hashes as jsonb), :user, :role, :evaluatedAt, :confirmedAt, :trace)
                    """)
                    .param("id", confirmationId).param("consultation", consultationId).param("preparation", preparationId).param("family", familyId)
                    .param("businessDate", LocalDate.parse(businessDate.get())).param("notice", section.selectedNoticeId())
                    .param("checklist", section.approvedChecklistVersionId()).param("decision", section.decisionId())
                    .param("rules", mapper.writeValueAsString(section.itemRuleVersionIds()))
                    .param("hashes", mapper.writeValueAsString(section.itemEvidenceHashes()))
                    .param("user", userId).param("role", activeRole).param("evaluatedAt", evaluatedAt).param("confirmedAt", confirmedAt)
                    .param("trace", traceId == null ? "unavailable" : traceId)
                    .update();
        } catch (DuplicateKeyException exception) {
            throw new ConsultationException("ALREADY_CONFIRMED", "이 준비안의 이 공문군은 이미 확인 기록이 있습니다.");
        }
        return confirmations(consultationId).stream().filter(row -> row.confirmationId().equals(confirmationId)).findFirst().orElseThrow();
    }

    /** 기록 직전 재확인. 준비안 기록 경로의 READY 재확인(C1~C5)과 같은 기준이다. */
    private OffsetDateTime recheck(Section section, String businessDate) {
        InternalPolicyApplicableState state;
        try {
            state = applicable.get(section.familyId(), businessDate, null);
        } catch (InternalPolicyQueryException exception) {
            throw new ConsultationException("PREPARATION_STALE", "현재 승인 상태를 다시 조회하지 못했습니다(" + exception.code() + ").");
        }
        if (!state.internalChecklistUseAllowed() || state.approvedChecklist() == null) {
            throw new ConsultationException("PREPARATION_STALE", "현재 이 공문군은 사용할 수 없습니다: " + state.blockingReasons());
        }
        InternalPolicyApplicableState.ApprovedChecklist checklist = state.approvedChecklist();
        if (!checklist.approvedChecklistVersionId().equals(section.approvedChecklistVersionId()) || !checklist.decisionId().equals(section.decisionId())
                || state.selectedNotice() == null || !state.selectedNotice().noticeId().equals(section.selectedNoticeId())) {
            throw new ConsultationException("PREPARATION_STALE", "준비안 이후 승인 checklist·결정·적용 공문이 바뀌었습니다. 준비안을 다시 만드세요.");
        }
        List<String> currentRuleIds = checklist.items().stream().map(InternalPolicyApplicableState.ApprovedItem::sourceRuleVersionId).toList();
        Map<String, String> hashes = new HashMap<>();
        state.rules().forEach(rule -> hashes.put(rule.ruleVersionId(), rule.evidenceHash()));
        if (!currentRuleIds.equals(section.itemRuleVersionIds())) {
            throw new ConsultationException("PREPARATION_STALE", "준비안 이후 승인 항목이 바뀌었습니다. 준비안을 다시 만드세요.");
        }
        for (int i = 0; i < currentRuleIds.size(); i++) {
            if (!section.itemEvidenceHashes().get(i).equals(hashes.get(currentRuleIds.get(i)))) {
                throw new ConsultationException("PREPARATION_STALE", "준비안 이후 규칙 근거가 바뀌었습니다. 준비안을 다시 만드세요.");
            }
        }
        return state.evaluatedAt().atOffset(ZoneOffset.UTC);
    }

    private List<Section> sections(String preparationId) {
        return jdbc.sql("""
                select s.family_id, s.required, s.status, s.hold_kind, s.blocking_reasons, s.selected_notice_id, s.approved_checklist_version_id,
                       s.decision_id, s.item_rule_version_ids, s.item_evidence_hashes
                from consultation_preparation_section s
                join consultation_family_mapping m on m.family_id = s.family_id
                    and m.mapping_hash = (select family_mapping_hash from consultation_preparation where preparation_id = :id)
                    and m.product_key = (select product_key from consultation_preparation where preparation_id = :id)
                where s.preparation_id = :id order by m.family_order, s.family_id
                """).param("id", preparationId)
                .query((rs, rowNum) -> new Section(rs.getString("family_id"), rs.getBoolean("required"), rs.getString("status"), rs.getString("hold_kind"),
                        strings(rs.getString("blocking_reasons")), rs.getString("selected_notice_id"), rs.getString("approved_checklist_version_id"),
                        rs.getString("decision_id"), strings(rs.getString("item_rule_version_ids")), strings(rs.getString("item_evidence_hashes"))))
                .list();
    }

    private Confirmation mapConfirmation(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Confirmation(rs.getString("confirmation_id"), rs.getString("consultation_id"), rs.getString("preparation_id"), rs.getString("family_id"),
                rs.getObject("business_date", LocalDate.class).toString(), rs.getString("selected_notice_id"), rs.getString("approved_checklist_version_id"),
                rs.getString("decision_id"), strings(rs.getString("confirmed_rule_version_ids")), strings(rs.getString("confirmed_evidence_hashes")),
                rs.getString("confirmed_by"), rs.getString("active_role"), rs.getObject("recheck_evaluated_at", OffsetDateTime.class).toInstant().toString(),
                rs.getObject("confirmed_at", OffsetDateTime.class).toInstant().toString());
    }

    private List<String> strings(String json) {
        List<String> values = new ArrayList<>();
        mapper.readTree(json).forEach(node -> values.add(node.stringValue()));
        return values;
    }
}
