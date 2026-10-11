-- V12 (TASK-017b): 상담 건별 준비안 연결과 직원 확인 기록.
-- 준비안 ID는 상담 ID를 빼고 계산하므로 같은 신청의 다른 상담 건이 같은 내용을 기록하면 ALREADY_RECORDED가 되고
-- consultation_preparation.consultation_id에는 처음 기록한 상담 건만 남는다. 상담 건과 준비안의 연결은 실행마다 이 표에 남긴다.

CREATE TABLE consultation_preparation_link (
    run_id text PRIMARY KEY REFERENCES consultation_preparation_run(run_id),
    consultation_id text NOT NULL CHECK (length(consultation_id) BETWEEN 1 AND 64),
    preparation_id text NOT NULL REFERENCES consultation_preparation(preparation_id),
    linked_at timestamptz NOT NULL
);

COMMENT ON TABLE consultation_preparation_link IS '기록에 성공한 실행(RECORDED·ALREADY_RECORDED)마다 그 실행의 상담 건과 준비안을 잇는다. 상담 건별 최신 준비안과 직원 확인의 기준이다.';
CREATE INDEX consultation_preparation_link_consultation_idx ON consultation_preparation_link (consultation_id, linked_at DESC);

CREATE TABLE consultation_confirmation (
    confirmation_id text PRIMARY KEY CHECK (confirmation_id ~ '^consultation-confirmation:[a-f0-9]{32}$'),
    consultation_id text NOT NULL REFERENCES consultation(consultation_id),
    preparation_id text NOT NULL REFERENCES consultation_preparation(preparation_id),
    family_id text NOT NULL CHECK (family_id ~ '^SIN-[A-Z0-9-]+$'),
    business_date date NOT NULL,
    selected_notice_id text NOT NULL CHECK (selected_notice_id ~ '^SIN-[A-Z0-9-]+-V[1-9][0-9]*$'),
    approved_checklist_version_id text NOT NULL REFERENCES approved_checklist_version(approved_checklist_version_id),
    decision_id text NOT NULL REFERENCES human_review_decision(decision_id),
    confirmed_rule_version_ids jsonb NOT NULL CHECK (jsonb_typeof(confirmed_rule_version_ids) = 'array' AND jsonb_array_length(confirmed_rule_version_ids) >= 1),
    confirmed_evidence_hashes jsonb NOT NULL CHECK (jsonb_typeof(confirmed_evidence_hashes) = 'array'),
    confirmed_by text NOT NULL CHECK (confirmed_by ~ '^[A-Z][A-Z0-9-]{1,63}$'),
    active_role text NOT NULL CHECK (active_role = 'STAFF'),
    recheck_evaluated_at timestamptz NOT NULL,
    confirmed_at timestamptz NOT NULL,
    trace_id text NOT NULL CHECK (length(trace_id) BETWEEN 1 AND 64),
    CHECK (jsonb_array_length(confirmed_rule_version_ids) = jsonb_array_length(confirmed_evidence_hashes)),
    UNIQUE (consultation_id, preparation_id, family_id)
);

COMMENT ON TABLE consultation_confirmation IS '직원이 READY 섹션의 항목별 근거를 확인했다는 기록. 대출 승인·거절, 금리·한도 확정, 신용등급 결정이 아니며 상담 준비 완료나 고객별 적용 승인도 아니다.';

DO $$
DECLARE
    target text;
BEGIN
    FOREACH target IN ARRAY ARRAY['consultation_preparation_link', 'consultation_confirmation'] LOOP
        EXECUTE format('CREATE TRIGGER guard_%I_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION trust_agent_guard_append_only()', target, target);
        EXECUTE format('CREATE TRIGGER guard_%I_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION trust_agent_guard_truncate()', target, target);
    END LOOP;
END
$$;

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
        ('consultation_family_mapping', 'guard_consultation_family_mapping_append_only', 'guard_consultation_family_mapping_truncate', 'trust_agent_guard_append_only', true),
        ('consultation_preparation', 'guard_consultation_preparation_append_only', 'guard_consultation_preparation_truncate', 'trust_agent_guard_append_only', true),
        ('consultation_preparation_section', 'guard_consultation_preparation_section_append_only', 'guard_consultation_preparation_section_truncate', 'trust_agent_guard_append_only', true),
        ('consultation_preparation_run', 'guard_consultation_preparation_run_append_only', 'guard_consultation_preparation_run_truncate', 'trust_agent_guard_append_only', true),
        ('synthetic_work_company', 'guard_synthetic_work_company_append_only', 'guard_synthetic_work_company_truncate', 'trust_agent_guard_append_only', true),
        ('synthetic_work_application', 'guard_synthetic_work_application_append_only', 'guard_synthetic_work_application_truncate', 'trust_agent_guard_append_only', true),
        ('consultation', 'guard_consultation_append_only', 'guard_consultation_truncate', 'trust_agent_guard_append_only', true),
        ('consultation_preparation_link', 'guard_consultation_preparation_link_append_only', 'guard_consultation_preparation_link_truncate', 'trust_agent_guard_append_only', true),
        ('consultation_confirmation', 'guard_consultation_confirmation_append_only', 'guard_consultation_confirmation_truncate', 'trust_agent_guard_append_only', true),
        ('maintenance_change_audit', 'guard_maintenance_change_audit_immutable', 'guard_maintenance_change_audit_truncate', 'trust_agent_guard_audit_log', false)
$$;

ALTER TABLE consultation_preparation_link OWNER TO trust_agent_migration;
ALTER TABLE consultation_confirmation OWNER TO trust_agent_migration;
GRANT SELECT, INSERT ON consultation_preparation_link, consultation_confirmation TO trust_agent_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON consultation_preparation_link, consultation_confirmation TO trust_agent_maintenance;
