package com.trustagent.core.internalpolicy.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
class InternalPolicyApplicableRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    InternalPolicyApplicableRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    boolean familyExists(String familyId) {
        return jdbc.sql("select exists(select 1 from internal_notice_version where family_id = :familyId)")
                .param("familyId", familyId)
                .query(Boolean.class)
                .single();
    }

    List<NoticeRow> findKnownNotices(String familyId, Instant knownAt) {
        return jdbc.sql("""
                        select n.notice_id, n.family_id, n.version, n.title, n.issued_on,
                               n.effective_from, n.effective_to, n.supersedes_notice_id,
                               receipt.received_at, receipt.received_business_date,
                               exists(
                                   select 1 from internal_notice_lifecycle_event event
                                   where event.notice_id = n.notice_id
                                     and event.event_type = 'WITHDRAWN'
                                     and event.occurred_at <= :knownAt
                               ) as withdrawn
                        from internal_notice_version n
                        join lateral (
                            select r.received_at, r.received_business_date
                            from internal_notice_receipt r
                            where r.notice_id = n.notice_id and r.received_at <= :knownAt
                            order by r.received_at, r.receipt_id
                            limit 1
                        ) receipt on true
                        where n.family_id = :familyId and n.document_status = 'ISSUED'
                        order by n.version, n.notice_id
                        """)
                .param("familyId", familyId)
                .param("knownAt", utc(knownAt))
                .query((resultSet, rowNumber) -> new NoticeRow(
                        resultSet.getString("notice_id"),
                        resultSet.getString("family_id"),
                        resultSet.getInt("version"),
                        resultSet.getString("title"),
                        resultSet.getObject("issued_on", LocalDate.class),
                        resultSet.getObject("effective_from", LocalDate.class),
                        resultSet.getObject("effective_to", LocalDate.class),
                        resultSet.getString("supersedes_notice_id"),
                        instant(resultSet, "received_at"),
                        resultSet.getObject("received_business_date", LocalDate.class),
                        resultSet.getBoolean("withdrawn")))
                .list();
    }

    Optional<ExtractionRow> findLatestExtraction(String noticeId, Instant knownAt) {
        return jdbc.sql("""
                        select extraction_attempt_id, attempted_at, status, error_code
                        from policy_extraction_attempt
                        where notice_id = :noticeId and attempted_at <= :knownAt
                        order by attempted_at desc, extraction_attempt_id desc
                        limit 1
                        """)
                .param("noticeId", noticeId)
                .param("knownAt", utc(knownAt))
                .query((resultSet, rowNumber) -> new ExtractionRow(
                        resultSet.getString("extraction_attempt_id"),
                        instant(resultSet, "attempted_at"),
                        resultSet.getString("status"),
                        resultSet.getString("error_code")))
                .optional();
    }

    List<InternalPolicyApplicableState.Rule> findRules(String extractionAttemptId) {
        return jdbc.sql("""
                        select rule.rule_version_id, evidence.rule_order, rule.rule_key,
                               rule.instruction, rule.evidence_required,
                               rule.structured_change::text as structured_change,
                               evidence.json_pointer,
                               evidence.evidence_text, evidence.evidence_hash
                        from internal_policy_rule_evidence evidence
                        join internal_policy_rule_version rule
                          on rule.rule_version_id = evidence.rule_version_id
                        where evidence.extraction_attempt_id = :attemptId
                        order by evidence.rule_order, rule.rule_version_id
                        """)
                .param("attemptId", extractionAttemptId)
                .query((resultSet, rowNumber) -> new InternalPolicyApplicableState.Rule(
                        resultSet.getString("rule_version_id"),
                        resultSet.getInt("rule_order"),
                        resultSet.getString("rule_key"),
                        resultSet.getString("instruction"),
                        resultSet.getBoolean("evidence_required"),
                        json(resultSet.getString("structured_change")),
                        resultSet.getString("json_pointer"),
                        resultSet.getString("evidence_text"),
                        resultSet.getString("evidence_hash")))
                .list();
    }

    private JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("저장된 구조화 변경 JSON이 올바르지 않습니다.", exception);
        }
    }

    List<ScheduleRevisionRow> findVisibleScheduleLeaves(String familyId, Instant knownAt) {
        return jdbc.sql("""
                        select revision.schedule_revision_id, revision.created_at
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
                .query((resultSet, rowNumber) -> new ScheduleRevisionRow(
                        resultSet.getString("schedule_revision_id"),
                        instant(resultSet, "created_at")))
                .list();
    }

    Optional<InternalPolicyApplicableState.ApprovedChecklist> findApplicableChecklist(
            String scheduleRevisionId,
            String selectedNoticeId,
            LocalDate businessDate,
            Instant knownAt) {
        return jdbc.sql("""
                        select checklist.approved_checklist_version_id,
                               entry.schedule_revision_id, entry.effective_from,
                               entry.effective_to, checklist.created_at
                        from approved_checklist_schedule_entry entry
                        join approved_checklist_version checklist
                          on checklist.approved_checklist_version_id = entry.approved_checklist_version_id
                         and checklist.family_id = entry.family_id
                        where entry.schedule_revision_id = :scheduleRevisionId
                          and checklist.notice_id = :noticeId
                          and checklist.created_at <= :knownAt
                          and entry.effective_from <= :businessDate
                          and (entry.effective_to is null or :businessDate < entry.effective_to)
                        """)
                .param("scheduleRevisionId", scheduleRevisionId)
                .param("noticeId", selectedNoticeId)
                .param("knownAt", utc(knownAt))
                .param("businessDate", businessDate)
                .query((resultSet, rowNumber) -> new InternalPolicyApplicableState.ApprovedChecklist(
                        resultSet.getString("approved_checklist_version_id"),
                        resultSet.getString("schedule_revision_id"),
                        resultSet.getObject("effective_from", LocalDate.class),
                        resultSet.getObject("effective_to", LocalDate.class),
                        instant(resultSet, "created_at")))
                .optional();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    record NoticeRow(
            String noticeId,
            String familyId,
            int version,
            String title,
            LocalDate issuedOn,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String supersedesNoticeId,
            Instant receivedAt,
            LocalDate receivedBusinessDate,
            boolean withdrawn) {
    }

    record ExtractionRow(String extractionAttemptId, Instant attemptedAt, String status, String errorCode) {
    }

    record ScheduleRevisionRow(String scheduleRevisionId, Instant createdAt) {
    }
}
