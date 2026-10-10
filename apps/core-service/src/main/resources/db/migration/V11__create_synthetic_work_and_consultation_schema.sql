-- V11 (TASK-017a 두 번째 PR, A안): 합성 신청·기업 자료를 Core 업무 DB에 적재하고 상담 건을 Core가 소유한다.
-- 1) CREATE TABLE  2) append-only 가드 trigger  3) truncate 가드  4) 보호 목록 함수 갱신  5) 권한

CREATE TABLE synthetic_work_company (
    company_id text PRIMARY KEY CHECK (company_id ~ '^SW-COMPANY-[0-9]{3}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'SYNTHETIC_WORK'),
    legal_name text NOT NULL CHECK (length(legal_name) BETWEEN 1 AND 128),
    business_id text NOT NULL CHECK (length(business_id) BETWEEN 1 AND 64),
    business_type text NOT NULL CHECK (length(business_type) BETWEEN 1 AND 64),
    industry text NOT NULL CHECK (length(industry) BETWEEN 1 AND 128),
    established_on date NOT NULL,
    source_hash text NOT NULL CHECK (source_hash ~ '^sha256:[a-f0-9]{64}$'),
    loaded_at timestamptz NOT NULL
);

COMMENT ON TABLE synthetic_work_company IS '합성 기업(SYNTHETIC_WORK). datasets/synthetic/work/companies의 JSON을 canonical 해시와 함께 적재한다. 실제 기업·고객 자료가 아니다.';

CREATE TABLE synthetic_work_application (
    application_id text PRIMARY KEY CHECK (application_id ~ '^SW-APPLICATION-[0-9]{3}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'SYNTHETIC_WORK'),
    company_id text NOT NULL REFERENCES synthetic_work_company(company_id),
    product_key text NOT NULL CHECK (product_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    requested_at timestamptz NOT NULL,
    requested_amount_krw bigint NOT NULL CHECK (requested_amount_krw > 0),
    purpose text NOT NULL CHECK (length(purpose) BETWEEN 1 AND 512),
    status text NOT NULL CHECK (status ~ '^[A-Z][A-Z_]+$'),
    source_hash text NOT NULL CHECK (source_hash ~ '^sha256:[a-f0-9]{64}$'),
    loaded_at timestamptz NOT NULL
);

COMMENT ON TABLE synthetic_work_application IS '합성 상담 신청(SYNTHETIC_WORK). source_hash는 신청 JSON의 canonical sha256이며 AI 서비스가 보내는 application.source_hash와 대조한다. 여신 신청·승인 자료가 아니다.';

CREATE TABLE consultation (
    consultation_id text PRIMARY KEY CHECK (consultation_id ~ '^consultation:[a-f0-9]{32}$'),
    workspace_id text NOT NULL CHECK (length(workspace_id) BETWEEN 1 AND 64),
    application_id text NOT NULL REFERENCES synthetic_work_application(application_id),
    assigned_user_id text NOT NULL CHECK (assigned_user_id ~ '^[A-Z][A-Z0-9-]{1,63}$'),
    status text NOT NULL CHECK (status = 'OPEN'),
    created_at timestamptz NOT NULL,
    trace_id text NOT NULL CHECK (length(trace_id) BETWEEN 1 AND 64)
);

COMMENT ON TABLE consultation IS '상담 건(ADR-014 5항). 담당 사용자가 아니면 외부에는 404다. 재배정·종료는 범위 밖이며 append-only다.';
CREATE INDEX consultation_assigned_user_idx ON consultation (workspace_id, assigned_user_id, created_at);

DO $$
DECLARE
    target text;
BEGIN
    FOREACH target IN ARRAY ARRAY['synthetic_work_company', 'synthetic_work_application', 'consultation'] LOOP
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
        ('maintenance_change_audit', 'guard_maintenance_change_audit_immutable', 'guard_maintenance_change_audit_truncate', 'trust_agent_guard_audit_log', false)
$$;

ALTER TABLE synthetic_work_company OWNER TO trust_agent_migration;
ALTER TABLE synthetic_work_application OWNER TO trust_agent_migration;
ALTER TABLE consultation OWNER TO trust_agent_migration;
GRANT SELECT, INSERT ON synthetic_work_company, synthetic_work_application, consultation TO trust_agent_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON synthetic_work_company, synthetic_work_application, consultation TO trust_agent_maintenance;
