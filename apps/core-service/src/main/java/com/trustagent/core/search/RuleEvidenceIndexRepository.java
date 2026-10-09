package com.trustagent.core.search;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 색인 대상 조회(ADR-013 결정 1). 사람 결정(APPROVE)이 있는 승인 checklist의 항목이 가리키는 규칙 version만,
 * 그 checklist가 공문군의 최신 일정 revision에 있을 때만, 공문이 철회되지 않았을 때만 돌려준다.
 * FIXTURE 출처 checklist(사람 결정 없음), 미승인·반려 변경안, 철회 공문의 규칙은 결과에 없다. 읽기 전용 SQL이며 쓰지 않는다.
 */
class RuleEvidenceIndexRepository {

    private static final String SQL = """
            with latest_schedule as (
                select r.schedule_revision_id, r.family_id
                from approved_checklist_schedule_revision r
                where not exists (
                    select 1 from approved_checklist_schedule_revision s
                    where s.supersedes_schedule_revision_id = r.schedule_revision_id)
            ),
            evidence as (
                select distinct on (ev.notice_id, ev.rule_version_id)
                    ev.notice_id, ev.rule_version_id, ev.json_pointer, ev.evidence_text, ev.evidence_hash
                from internal_policy_rule_evidence ev
                join policy_extraction_attempt pa on pa.extraction_attempt_id = ev.extraction_attempt_id
                order by ev.notice_id, ev.rule_version_id, pa.attempted_at desc, ev.extraction_attempt_id
            )
            select i.source_rule_version_id as rule_version_id, v.family_id, v.notice_id, i.rule_key,
                   ev.evidence_text, ev.json_pointer, ev.evidence_hash, i.structured_change::text as structured_change,
                   v.approved_checklist_version_id, d.decision_id, e.effective_from, e.effective_to
            from human_review_decision d
            join approved_checklist_version v on v.approved_checklist_version_id = d.approved_checklist_version_id
            join latest_schedule ls on ls.family_id = v.family_id
            join approved_checklist_schedule_entry e
                on e.schedule_revision_id = ls.schedule_revision_id
                and e.approved_checklist_version_id = v.approved_checklist_version_id
            join approved_checklist_item i on i.approved_checklist_version_id = v.approved_checklist_version_id
            join evidence ev on ev.rule_version_id = i.source_rule_version_id and ev.notice_id = v.notice_id
            where d.decision = 'APPROVE'
              and i.source_rule_version_id is not null
              and not exists (
                  select 1 from internal_notice_lifecycle_event le
                  where le.notice_id = v.notice_id and le.event_type = 'WITHDRAWN')
            order by v.family_id, e.effective_from, i.item_order
            """;

    private final JdbcClient jdbc;

    RuleEvidenceIndexRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<IndexedRule> loadApprovedRules() {
        return jdbc.sql(SQL).query((rs, rowNum) -> new IndexedRule(
                rs.getString("rule_version_id"), rs.getString("family_id"), rs.getString("notice_id"), rs.getString("rule_key"),
                rs.getString("evidence_text"), rs.getString("json_pointer"), rs.getString("evidence_hash"), rs.getString("structured_change"),
                rs.getString("approved_checklist_version_id"), rs.getString("decision_id"),
                rs.getObject("effective_from", java.time.LocalDate.class), rs.getObject("effective_to", java.time.LocalDate.class))).list();
    }
}
