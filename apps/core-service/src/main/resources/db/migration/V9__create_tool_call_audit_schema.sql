-- TASK-008: AI 서비스용 Tool API 호출 감사 기록. 기록 없는 제공은 없다(저장 실패 시 응답도 실패).
-- 공문 본문, 규칙 원문, 토큰은 기록하지 않는다. 상담 ID는 추적용이며 권한을 주지 않는다.

CREATE TABLE tool_call_audit (
    audit_id text PRIMARY KEY CHECK (audit_id ~ '^tool-call:[a-f0-9]{32}$'),
    service_id text NOT NULL CHECK (length(service_id) BETWEEN 1 AND 64),
    tool_name text NOT NULL CHECK (length(tool_name) BETWEEN 1 AND 64),
    family_id text CHECK (family_id IS NULL OR length(family_id) BETWEEN 1 AND 64),
    business_date date,
    consultation_id text CHECK (consultation_id IS NULL OR length(consultation_id) BETWEEN 1 AND 64),
    outcome text NOT NULL CHECK (outcome ~ '^[A-Z][A-Z0-9_]+$'),
    usable boolean,
    blocking_reasons jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(blocking_reasons) = 'array'),
    trace_id text NOT NULL CHECK (length(trace_id) BETWEEN 1 AND 64),
    called_at timestamptz NOT NULL
);

COMMENT ON TABLE tool_call_audit IS
    'AI 서비스 Tool 호출 감사. 결과 코드와 사유만 남기며 본문·원문·자격증명은 저장하지 않는다.';

CREATE TRIGGER guard_tool_call_audit_append_only BEFORE UPDATE OR DELETE ON tool_call_audit
    FOR EACH ROW EXECUTE FUNCTION trust_agent_guard_append_only();
CREATE TRIGGER guard_tool_call_audit_truncate BEFORE TRUNCATE ON tool_call_audit
    FOR EACH STATEMENT EXECUTE FUNCTION trust_agent_guard_truncate();

CREATE OR REPLACE FUNCTION trust_agent_protected_tables()
RETURNS TABLE (protected_table_name text, guard_trigger_name text, truncate_trigger_name text, guard_function_name text, migration_owned boolean)
LANGUAGE sql IMMUTABLE
AS $$
    VALUES
        ('public_product', 'guard_public_product_append_only', 'guard_public_product_truncate', 'trust_agent_guard_append_only', true),
        ('public_snapshot', 'guard_public_snapshot_append_only', 'guard_public_snapshot_truncate', 'trust_agent_guard_append_only', true),
        ('collection_attempt', 'guard_collection_attempt_append_only', 'guard_collection_attempt_truncate', 'trust_agent_guard_append_only', true),
        ('public_observation', 'guard_public_observation_append_only', 'guard_public_observation_truncate', 'trust_agent_guard_append_only', true),
        ('product_terms_version', 'guard_product_terms_version_append_only', 'guard_product_terms_version_truncate', 'trust_agent_guard_append_only', true),
        ('product_term_fact', 'guard_product_term_fact_append_only', 'guard_product_term_fact_truncate', 'trust_agent_guard_append_only', true),
        ('version_evidence', 'guard_version_evidence_append_only', 'guard_version_evidence_truncate', 'trust_agent_guard_append_only', true),
        ('fact_evidence_locator', 'guard_fact_evidence_locator_append_only', 'guard_fact_evidence_locator_truncate', 'trust_agent_guard_append_only', true),
        ('observed_rate_quote', 'guard_observed_rate_quote_append_only', 'guard_observed_rate_quote_truncate', 'trust_agent_guard_append_only', true),
        ('extraction_attempt', 'guard_extraction_attempt_append_only', 'guard_extraction_attempt_truncate', 'trust_agent_guard_append_only', true),
        ('change_detection_result', 'guard_change_detection_result_append_only', 'guard_change_detection_result_truncate', 'trust_agent_guard_append_only', true),
        ('change_detection_classification', 'guard_change_detection_classification_append_only', 'guard_change_detection_classification_truncate', 'trust_agent_guard_append_only', true),
        ('baseline_import_run', 'guard_baseline_import_run_append_only', 'guard_baseline_import_run_truncate', 'trust_agent_guard_append_only', true),
        ('internal_notice_version', 'guard_internal_notice_version_append_only', 'guard_internal_notice_version_truncate', 'trust_agent_guard_append_only', true),
        ('internal_notice_reference', 'guard_internal_notice_reference_append_only', 'guard_internal_notice_reference_truncate', 'trust_agent_guard_append_only', true),
        ('internal_notice_receipt', 'guard_internal_notice_receipt_append_only', 'guard_internal_notice_receipt_truncate', 'trust_agent_guard_append_only', true),
        ('internal_notice_lifecycle_event', 'guard_internal_notice_lifecycle_event_append_only', 'guard_internal_notice_lifecycle_event_truncate', 'trust_agent_guard_append_only', true),
        ('policy_extraction_attempt', 'guard_policy_extraction_attempt_append_only', 'guard_policy_extraction_attempt_truncate', 'trust_agent_guard_append_only', true),
        ('internal_policy_rule_version', 'guard_internal_policy_rule_version_append_only', 'guard_internal_policy_rule_version_truncate', 'trust_agent_guard_append_only', true),
        ('internal_policy_rule_evidence', 'guard_internal_policy_rule_evidence_append_only', 'guard_internal_policy_rule_evidence_truncate', 'trust_agent_guard_append_only', true),
        ('approved_checklist_version', 'guard_approved_checklist_version_append_only', 'guard_approved_checklist_version_truncate', 'trust_agent_guard_append_only', true),
        ('approved_checklist_schedule_revision', 'guard_approved_checklist_schedule_revision_append_only', 'guard_approved_checklist_schedule_revision_truncate', 'trust_agent_guard_append_only', true),
        ('approved_checklist_schedule_entry', 'guard_approved_checklist_schedule_entry_append_only', 'guard_approved_checklist_schedule_entry_truncate', 'trust_agent_guard_append_only', true),
        ('synthetic_internal_import_run', 'guard_synthetic_internal_import_run_append_only', 'guard_synthetic_internal_import_run_truncate', 'trust_agent_guard_append_only', true),
        ('approved_checklist_item', 'guard_approved_checklist_item_append_only', 'guard_approved_checklist_item_truncate', 'trust_agent_guard_append_only', true),
        ('approved_checklist_fixture_run', 'guard_approved_checklist_fixture_run_append_only', 'guard_approved_checklist_fixture_run_truncate', 'trust_agent_guard_append_only', true),
        ('checklist_change_proposal', 'guard_checklist_change_proposal_append_only', 'guard_checklist_change_proposal_truncate', 'trust_agent_guard_append_only', true),
        ('checklist_change_proposal_item', 'guard_checklist_change_proposal_item_append_only', 'guard_checklist_change_proposal_item_truncate', 'trust_agent_guard_append_only', true),
        ('proposal_generation_run', 'guard_proposal_generation_run_append_only', 'guard_proposal_generation_run_truncate', 'trust_agent_guard_append_only', true),
        ('automated_validation_result', 'guard_automated_validation_result_append_only', 'guard_automated_validation_result_truncate', 'trust_agent_guard_append_only', true),
        ('automated_validation_issue', 'guard_automated_validation_issue_append_only', 'guard_automated_validation_issue_truncate', 'trust_agent_guard_append_only', true),
        ('validation_run', 'guard_validation_run_append_only', 'guard_validation_run_truncate', 'trust_agent_guard_append_only', true),
        ('human_review_decision', 'guard_human_review_decision_append_only', 'guard_human_review_decision_truncate', 'trust_agent_guard_append_only', true),
        ('human_review_run', 'guard_human_review_run_append_only', 'guard_human_review_run_truncate', 'trust_agent_guard_append_only', true),
        ('tool_call_audit', 'guard_tool_call_audit_append_only', 'guard_tool_call_audit_truncate', 'trust_agent_guard_append_only', true),
        ('maintenance_change_audit', 'guard_maintenance_change_audit_immutable', 'guard_maintenance_change_audit_truncate', 'trust_agent_guard_audit_log', false)
$$;

ALTER TABLE tool_call_audit OWNER TO trust_agent_migration;
GRANT SELECT, INSERT ON tool_call_audit TO trust_agent_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON tool_call_audit TO trust_agent_maintenance;

ALTER FUNCTION trust_agent_protected_tables() OWNER TO trust_agent_audit_owner;
REVOKE ALL ON FUNCTION trust_agent_protected_tables() FROM PUBLIC;
