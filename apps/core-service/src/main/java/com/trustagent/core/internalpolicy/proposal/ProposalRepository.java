package com.trustagent.core.internalpolicy.proposal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;
import java.util.Map;
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
        insertProposalRevision(proposal, familyId, createdAt, null, null);
    }

    /** supersedes와 reason이 있으면 revision(TASK-007 수정 결정), 둘 다 없으면 최초 변경안. */
    void insertProposalRevision(
            ChecklistChangeProposalGenerator.Proposal proposal, String familyId, Instant createdAt,
            String supersedesProposalId, String revisionReason) {
        jdbc.sql("""
                        insert into checklist_change_proposal (
                            proposal_id, dataset_class, family_id, base_checklist_version_id, target_notice_id,
                            generator_version, supersedes_proposal_id, revision_reason, before_hash, after_hash,
                            item_count, created_at)
                        values (:id, 'DERIVED', :family, :base, :target, :generator, :supersedes, :reason, :beforeHash,
                                :afterHash, :itemCount, :createdAt)
                        """)
                .param("supersedes", supersedesProposalId)
                .param("reason", revisionReason)
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

    record ProposalRow(String proposalId, String familyId, String baseChecklistVersionId, String targetNoticeId,
            String afterHash, String supersedesProposalId) {}

    record ReferenceRow(String productKey, String factKey, String subjectType, String unit, long expectedValue,
            String evidenceRequirement) {}

    Optional<ProposalRow> findProposal(String proposalId) {
        return jdbc.sql("""
                        select proposal_id, family_id, base_checklist_version_id, target_notice_id, after_hash,
                               supersedes_proposal_id
                        from checklist_change_proposal where proposal_id = :id
                        """)
                .param("id", proposalId)
                .query((rs, row) -> new ProposalRow(
                        rs.getString("proposal_id"), rs.getString("family_id"), rs.getString("base_checklist_version_id"),
                        rs.getString("target_notice_id"), rs.getString("after_hash"), rs.getString("supersedes_proposal_id")))
                .optional();
    }

    List<ProposalValidator.ProposalItem> findProposalItems(String proposalId) {
        return jdbc.sql("""
                        select rule_key, change_type, before_json::text as before_json, after_json::text as after_json
                        from checklist_change_proposal_item where proposal_id = :id order by item_order
                        """)
                .param("id", proposalId)
                .query((rs, row) -> new ProposalValidator.ProposalItem(
                        rs.getString("rule_key"),
                        rs.getString("change_type"),
                        rs.getString("before_json") == null ? null : mapper.readTree(rs.getString("before_json")),
                        rs.getString("after_json") == null ? null : mapper.readTree(rs.getString("after_json"))))
                .list();
    }

    /** 변경안이 다른 revision에 의해 대체됐는지. 대체된 변경안은 더 이상 최신이 아니다. */
    boolean proposalSuperseded(String proposalId) {
        return jdbc.sql("select count(*) from checklist_change_proposal where supersedes_proposal_id = :id")
                .param("id", proposalId)
                .query(Integer.class)
                .single() > 0;
    }

    List<ReferenceRow> findReferences(String noticeId) {
        return jdbc.sql("""
                        select product_key, fact_key, subject_type, unit, expected_value, evidence_requirement
                        from internal_notice_reference where notice_id = :noticeId order by reference_order
                        """)
                .param("noticeId", noticeId)
                .query((rs, row) -> new ReferenceRow(
                        rs.getString("product_key"), rs.getString("fact_key"), rs.getString("subject_type"),
                        rs.getString("unit"), rs.getLong("expected_value"), rs.getString("evidence_requirement")))
                .list();
    }

    java.util.Set<String> findProductKeys() {
        return new java.util.HashSet<>(jdbc.sql("select product_key from public_product").query(String.class).list());
    }

    boolean validationRunExists(String runId) {
        return jdbc.sql("select count(*) from validation_run where validation_run_id = :id")
                .param("id", runId)
                .query(Integer.class)
                .single() > 0;
    }

    void insertValidation(
            String resultId, ProposalRow proposal, String validatorVersion, ProposalValidator.Outcome outcome,
            Instant validatedAt, JsonNode publicEvidenceRefs) {
        jdbc.sql("""
                        insert into automated_validation_result (
                            validation_result_id, dataset_class, proposal_id, proposal_hash, validator_version, status,
                            validated_at, public_evidence_refs)
                        values (:id, 'DERIVED', :proposal, :hash, :validator, :status, :validatedAt, cast(:refs as jsonb))
                        """)
                .param("id", resultId)
                .param("proposal", proposal.proposalId())
                .param("hash", proposal.afterHash())
                .param("validator", validatorVersion)
                .param("status", outcome.status().name())
                .param("validatedAt", utc(validatedAt))
                .param("refs", mapper.writeValueAsString(publicEvidenceRefs))
                .update();
        int order = 0;
        for (ProposalValidator.Issue issue : outcome.issues()) {
            jdbc.sql("""
                            insert into automated_validation_issue (
                                validation_result_id, result_status, issue_order, severity, code, rule_key, message, details)
                            values (:id, :status, :order, :severity, :code, :ruleKey, :message, cast(:details as jsonb))
                            """)
                    .param("id", resultId)
                    .param("status", outcome.status().name())
                    .param("order", order++)
                    .param("severity", issue.severity().name())
                    .param("code", issue.code())
                    .param("ruleKey", issue.ruleKey())
                    .param("message", issue.message())
                    .param("details", mapper.writeValueAsString(issue.details()))
                    .update();
        }
    }

    void insertValidationRun(
            String runId, String proposalId, String validatorVersion, String resultId,
            Instant startedAt, Instant completedAt, String status, String errorCode) {
        jdbc.sql("""
                        insert into validation_run (
                            validation_run_id, proposal_id, validator_version, validation_result_id,
                            started_at, completed_at, status, error_code)
                        values (:id, :proposal, :validator, :result, :started, :completed, :status, :error)
                        """)
                .param("id", runId)
                .param("proposal", proposalId)
                .param("validator", validatorVersion)
                .param("result", resultId)
                .param("started", utc(startedAt))
                .param("completed", utc(completedAt))
                .param("status", status)
                .param("error", errorCode)
                .update();
    }

    // ---- TASK-007 사람 검토 결정 ----

    record DecisionRow(String decisionId, String decision, Instant decidedAt, String approvedChecklistVersionId) {}

    record ScheduleEntryRow(String approvedChecklistVersionId, LocalDate effectiveFrom, LocalDate effectiveTo) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("approved_checklist_version_id", approvedChecklistVersionId);
            node.put("effective_from", effectiveFrom.toString());
            if (effectiveTo == null) {
                node.putNull("effective_to");
            } else {
                node.put("effective_to", effectiveTo.toString());
            }
            return node;
        }
    }

    record ApprovedItemRow(ChecklistItemContent content, String sourceRuleVersionId) {
        String ruleKey() {
            return content.ruleKey();
        }

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = content.toJson(mapper);
            if (sourceRuleVersionId == null) {
                node.putNull("source_rule_version_id");
            } else {
                node.put("source_rule_version_id", sourceRuleVersionId);
            }
            return node;
        }

        static ApprovedItemRow fromContent(JsonNode after, String sourceRuleVersionId) {
            if (after == null || after.isNull()) {
                throw new HumanReviewException("PROPOSAL_INTEGRITY_VIOLATION", "변경 후 내용이 없는 항목은 발행할 수 없습니다.");
            }
            JsonNode change = after.get("structured_change");
            return new ApprovedItemRow(new ChecklistItemContent(
                    after.get("rule_key").stringValue(),
                    after.get("instruction").stringValue(),
                    after.get("evidence_required").asBoolean(),
                    change == null || change.isNull() ? null : change), sourceRuleVersionId);
        }
    }

    Optional<DecisionRow> findDecision(String proposalId) {
        return jdbc.sql("""
                        select decision_id, decision, decided_at, approved_checklist_version_id
                        from human_review_decision where proposal_id = :id
                        """)
                .param("id", proposalId)
                .query((rs, row) -> new DecisionRow(rs.getString("decision_id"), rs.getString("decision"),
                        rs.getObject("decided_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getString("approved_checklist_version_id")))
                .optional();
    }

    Optional<ValidationRow> findLatestValidation(String proposalId) {
        return jdbc.sql("""
                        select validation_result_id, status, validated_at, proposal_hash
                        from automated_validation_result where proposal_id = :id
                        order by validated_at desc, validation_result_id desc limit 1
                        """)
                .param("id", proposalId)
                .query((rs, row) -> new ValidationRow(rs.getString("validation_result_id"), rs.getString("status"),
                        rs.getObject("validated_at", java.time.OffsetDateTime.class).toInstant(), rs.getString("proposal_hash")))
                .optional();
    }

    record ValidationRow(String validationResultId, String status, Instant validatedAt, String proposalHash) {}

    boolean humanApprovalExists(String noticeId) {
        return jdbc.sql("select count(*) from approved_checklist_version where notice_id = :id and origin = 'HUMAN_REVIEW'")
                .param("id", noticeId).query(Integer.class).single() > 0;
    }

    boolean reviewRunExists(String runId) {
        return jdbc.sql("select count(*) from human_review_run where review_run_id = :id")
                .param("id", runId).query(Integer.class).single() > 0;
    }

    List<ApprovedItemRow> findItemRows(String approvedChecklistVersionId) {
        return jdbc.sql("""
                        select rule_key, instruction, evidence_required, structured_change::text as structured_change,
                               source_rule_version_id
                        from approved_checklist_item where approved_checklist_version_id = :versionId order by item_order
                        """)
                .param("versionId", approvedChecklistVersionId)
                .query((rs, row) -> new ApprovedItemRow(content(rs, row), rs.getString("source_rule_version_id")))
                .list();
    }

    Map<String, String> findRuleVersionIds(String extractionAttemptId) {
        Map<String, String> ids = new java.util.HashMap<>();
        jdbc.sql("""
                        select rule.rule_key, rule.rule_version_id
                        from internal_policy_rule_evidence evidence
                        join internal_policy_rule_version rule on rule.rule_version_id = evidence.rule_version_id
                        where evidence.extraction_attempt_id = :attemptId
                        """)
                .param("attemptId", extractionAttemptId)
                .query((rs, row) -> ids.put(rs.getString("rule_key"), rs.getString("rule_version_id")))
                .list();
        return ids;
    }

    List<ScheduleEntryRow> findScheduleEntries(String scheduleRevisionId) {
        return jdbc.sql("""
                        select approved_checklist_version_id, effective_from, effective_to
                        from approved_checklist_schedule_entry where schedule_revision_id = :id order by entry_order
                        """)
                .param("id", scheduleRevisionId)
                .query((rs, row) -> new ScheduleEntryRow(rs.getString("approved_checklist_version_id"),
                        rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class)))
                .list();
    }

    void insertApprovedVersion(String versionId, String familyId, String noticeId, Instant createdAt, String hash, String origin) {
        jdbc.sql("""
                        insert into approved_checklist_version (
                            approved_checklist_version_id, dataset_class, family_id, notice_id, created_at, source_record_hash, origin)
                        values (:id, 'SYNTHETIC_INTERNAL', :family, :notice, :createdAt, :hash, :origin)
                        """)
                .param("id", versionId).param("family", familyId).param("notice", noticeId)
                .param("createdAt", utc(createdAt)).param("hash", hash).param("origin", origin)
                .update();
    }

    void insertApprovedItem(String versionId, String familyId, int order, ApprovedItemRow item, String hash) {
        jdbc.sql("""
                        insert into approved_checklist_item (
                            approved_checklist_version_id, family_id, item_order, rule_key, instruction,
                            evidence_required, structured_change, source_rule_version_id, source_record_hash)
                        values (:id, :family, :order, :ruleKey, :instruction, :evidenceRequired,
                                cast(:structuredChange as jsonb), :sourceRule, :hash)
                        """)
                .param("id", versionId).param("family", familyId).param("order", order)
                .param("ruleKey", item.content().ruleKey()).param("instruction", item.content().instruction())
                .param("evidenceRequired", item.content().evidenceRequired())
                .param("structuredChange", item.content().structuredChange() == null ? "null" : json(item.content().structuredChange()))
                .param("sourceRule", item.sourceRuleVersionId()).param("hash", hash)
                .update();
    }

    void insertScheduleRevision(String scheduleId, String familyId, String supersedes, Instant createdAt, String hash) {
        jdbc.sql("""
                        insert into approved_checklist_schedule_revision (
                            schedule_revision_id, dataset_class, family_id, supersedes_schedule_revision_id, created_at, source_record_hash)
                        values (:id, 'SYNTHETIC_INTERNAL', :family, :supersedes, :createdAt, :hash)
                        """)
                .param("id", scheduleId).param("family", familyId).param("supersedes", supersedes)
                .param("createdAt", utc(createdAt)).param("hash", hash)
                .update();
    }

    void insertScheduleEntry(String scheduleId, String familyId, int order, ScheduleEntryRow entry, String hash) {
        jdbc.sql("""
                        insert into approved_checklist_schedule_entry (
                            schedule_revision_id, family_id, entry_order, approved_checklist_version_id,
                            effective_from, effective_to, source_record_hash)
                        values (:id, :family, :order, :version, :from, :to, :hash)
                        """)
                .param("id", scheduleId).param("family", familyId).param("order", order)
                .param("version", entry.approvedChecklistVersionId())
                .param("from", entry.effectiveFrom()).param("to", entry.effectiveTo()).param("hash", hash)
                .update();
    }

    void insertDecision(
            String decisionId, String proposalId, String validationResultId, String proposalHash, String decision,
            String reviewerId, String reason, Instant decidedAt, String versionId, String scheduleId, String revisionProposalId) {
        jdbc.sql("""
                        insert into human_review_decision (
                            decision_id, dataset_class, proposal_id, validation_result_id, proposal_hash, decision,
                            reviewer_id, reason, decided_at, approved_checklist_version_id, schedule_revision_id, revision_proposal_id)
                        values (:id, 'SYNTHETIC_WORK', :proposal, :validation, :hash, :decision, :reviewer, :reason,
                                :decidedAt, :version, :schedule, :revision)
                        """)
                .param("id", decisionId).param("proposal", proposalId).param("validation", validationResultId)
                .param("hash", proposalHash).param("decision", decision).param("reviewer", reviewerId)
                .param("reason", reason).param("decidedAt", utc(decidedAt)).param("version", versionId)
                .param("schedule", scheduleId).param("revision", revisionProposalId)
                .update();
    }

    void insertReviewRun(
            String runId, String proposalId, String decision, String reviewerId, String decisionId,
            Instant startedAt, Instant completedAt, String status, String errorCode) {
        jdbc.sql("""
                        insert into human_review_run (
                            review_run_id, proposal_id, decision, reviewer_id, decision_id, started_at, completed_at, status, error_code)
                        values (:id, :proposal, :decision, :reviewer, :decisionId, :started, :completed, :status, :error)
                        """)
                .param("id", runId).param("proposal", proposalId).param("decision", decision).param("reviewer", reviewerId)
                .param("decisionId", decisionId).param("started", utc(startedAt)).param("completed", utc(completedAt))
                .param("status", status).param("error", errorCode)
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
