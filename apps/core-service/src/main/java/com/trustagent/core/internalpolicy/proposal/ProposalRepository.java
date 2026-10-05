package com.trustagent.core.internalpolicy.proposal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** checklist 변경안 생성에 필요한 조회와 append-only 저장. 수정과 삭제 메서드는 없다. */
final class ProposalRepository {

    record TargetNotice(String noticeId, String familyId, LocalDate effectiveFrom, boolean withdrawn) {}

    record BaseChecklist(String versionId, String scheduleRevisionId, List<ChecklistItemContent> items) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    ProposalRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void lockGeneration() {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('trust-agent-checklist-proposal-generation'))")
                .query()
                .singleRow();
    }

    Optional<TargetNotice> findVisibleNotice(String familyId, String noticeId, Instant knownAt) {
        return jdbc.sql("""
                        select notice.notice_id, notice.family_id, notice.effective_from,
                               exists (
                                   select 1 from internal_notice_lifecycle_event event
                                   where event.notice_id = notice.notice_id
                                     and event.event_type = 'WITHDRAWN'
                                     and event.occurred_at <= :knownAt
                               ) as withdrawn
                        from internal_notice_version notice
                        where notice.family_id = :familyId
                          and notice.notice_id = :noticeId
                          and exists (
                              select 1 from internal_notice_receipt receipt
                              where receipt.notice_id = notice.notice_id
                                and receipt.received_at <= :knownAt
                          )
                        """)
                .param("familyId", familyId)
                .param("noticeId", noticeId)
                .param("knownAt", utc(knownAt))
                .query((rs, row) -> new TargetNotice(
                        rs.getString("notice_id"),
                        rs.getString("family_id"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getBoolean("withdrawn")))
                .optional();
    }

    Optional<String> findLatestSucceededExtraction(String noticeId, Instant knownAt) {
        return jdbc.sql("""
                        select extraction_attempt_id
                        from policy_extraction_attempt
                        where notice_id = :noticeId
                          and attempted_at <= :knownAt
                          and status = 'SUCCEEDED'
                        order by attempted_at desc, extraction_attempt_id desc
                        limit 1
                        """)
                .param("noticeId", noticeId)
                .param("knownAt", utc(knownAt))
                .query(String.class)
                .optional();
    }

    List<ChecklistItemContent> findRules(String extractionAttemptId) {
        return jdbc.sql("""
                        select rule.rule_key, rule.instruction, rule.evidence_required,
                               rule.structured_change::text as structured_change
                        from internal_policy_rule_evidence evidence
                        join internal_policy_rule_version rule
                          on rule.rule_version_id = evidence.rule_version_id
                        where evidence.extraction_attempt_id = :attemptId
                        order by evidence.rule_order
                        """)
                .param("attemptId", extractionAttemptId)
                .query(this::content)
                .list();
    }

    List<String> findVisibleScheduleLeaves(String familyId, Instant knownAt) {
        return jdbc.sql("""
                        select revision.schedule_revision_id
                        from approved_checklist_schedule_revision revision
                        where revision.family_id = :familyId
                          and revision.created_at <= :knownAt
                          and not exists (
                              select 1 from approved_checklist_schedule_revision child
                              where child.family_id = revision.family_id
                                and child.supersedes_schedule_revision_id = revision.schedule_revision_id
                                and child.created_at <= :knownAt
                          )
                        order by revision.created_at desc, revision.schedule_revision_id desc
                        """)
                .param("familyId", familyId)
                .param("knownAt", utc(knownAt))
                .query(String.class)
                .list();
    }

    Optional<BaseChecklist> findChecklistCovering(
            String scheduleRevisionId, String familyId, LocalDate date, Instant knownAt) {
        Optional<String> versionId = jdbc.sql("""
                        select entry.approved_checklist_version_id
                        from approved_checklist_schedule_entry entry
                        join approved_checklist_version checklist
                          on checklist.approved_checklist_version_id = entry.approved_checklist_version_id
                         and checklist.family_id = entry.family_id
                        where entry.schedule_revision_id = :scheduleRevisionId
                          and entry.family_id = :familyId
                          and checklist.created_at <= :knownAt
                          and entry.effective_from <= :date
                          and (entry.effective_to is null or :date < entry.effective_to)
                        """)
                .param("scheduleRevisionId", scheduleRevisionId)
                .param("familyId", familyId)
                .param("knownAt", utc(knownAt))
                .param("date", date)
                .query(String.class)
                .optional();
        return versionId.map(id -> new BaseChecklist(id, scheduleRevisionId, findItems(id)));
    }

    List<ChecklistItemContent> findItems(String approvedChecklistVersionId) {
        return jdbc.sql("""
                        select rule_key, instruction, evidence_required,
                               structured_change::text as structured_change
                        from approved_checklist_item
                        where approved_checklist_version_id = :versionId
                        order by item_order
                        """)
                .param("versionId", approvedChecklistVersionId)
                .query(this::content)
                .list();
    }

    boolean proposalExists(String proposalId) {
        return jdbc.sql("select count(*) from checklist_change_proposal where proposal_id = :id")
                .param("id", proposalId)
                .query(Integer.class)
                .single() > 0;
    }

    boolean runExists(String runId) {
        return jdbc.sql("select count(*) from proposal_generation_run where generation_run_id = :id")
                .param("id", runId)
                .query(Integer.class)
                .single() > 0;
    }

    void insertProposal(ChecklistChangeProposalGenerator.Proposal proposal, String familyId, Instant createdAt) {
        jdbc.sql("""
                        insert into checklist_change_proposal (
                            proposal_id, dataset_class, family_id, base_checklist_version_id, target_notice_id,
                            generator_version, supersedes_proposal_id, revision_reason, before_hash, after_hash,
                            item_count, created_at)
                        values (:id, 'DERIVED', :family, :base, :target, :generator, null, null, :beforeHash,
                                :afterHash, :itemCount, :createdAt)
                        """)
                .param("id", proposal.proposalId())
                .param("family", familyId)
                .param("base", proposal.baseChecklistVersionId())
                .param("target", proposal.targetNoticeId())
                .param("generator", proposal.generatorVersion())
                .param("beforeHash", proposal.beforeHash())
                .param("afterHash", proposal.afterHash())
                .param("itemCount", proposal.items().size())
                .param("createdAt", utc(createdAt))
                .update();
        for (ChecklistChangeProposalGenerator.Item item : proposal.items()) {
            jdbc.sql("""
                            insert into checklist_change_proposal_item (
                                proposal_id, item_order, rule_key, change_type, before_json, after_json,
                                before_hash, after_hash)
                            values (:id, :order, :ruleKey, :type, cast(:before as jsonb), cast(:after as jsonb),
                                    :beforeHash, :afterHash)
                            """)
                    .param("id", proposal.proposalId())
                    .param("order", item.order())
                    .param("ruleKey", item.ruleKey())
                    .param("type", item.changeType().name())
                    .param("before", json(item.before()))
                    .param("after", json(item.after()))
                    .param("beforeHash", item.beforeHash())
                    .param("afterHash", item.afterHash())
                    .update();
        }
    }

    void insertRun(
            String runId,
            String familyId,
            String targetNoticeId,
            String generatorVersion,
            String proposalId,
            Instant startedAt,
            Instant completedAt,
            String status,
            String errorCode) {
        jdbc.sql("""
                        insert into proposal_generation_run (
                            generation_run_id, family_id, target_notice_id, generator_version, proposal_id,
                            started_at, completed_at, status, error_code)
                        values (:id, :family, :target, :generator, :proposal, :started, :completed, :status, :error)
                        """)
                .param("id", runId)
                .param("family", familyId)
                .param("target", targetNoticeId)
                .param("generator", generatorVersion)
                .param("proposal", proposalId)
                .param("started", utc(startedAt))
                .param("completed", utc(completedAt))
                .param("status", status)
                .param("error", errorCode)
                .update();
    }

    private ChecklistItemContent content(ResultSet rs, int row) throws SQLException {
        return new ChecklistItemContent(
                rs.getString("rule_key"),
                rs.getString("instruction"),
                rs.getBoolean("evidence_required"),
                readJson(rs.getString("structured_change")));
    }

    private JsonNode readJson(String value) {
        return mapper.readTree(value == null ? "null" : value);
    }

    private String json(JsonNode node) {
        return node == null ? null : mapper.writeValueAsString(node);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
