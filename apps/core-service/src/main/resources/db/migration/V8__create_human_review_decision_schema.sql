-- TASK-007: 사람 검토 결정(승인, 수정, 반려)과 실행 기록.
-- 승인은 approved_checklist_version(origin HUMAN_REVIEW)과 일정 revision을 같은 트랜잭션에서 발행하고 결정이 그 둘을 가리킨다.
-- 결정은 변경안당 1건이다. 자동 검증 결과는 결정의 근거이지 승인이 아니다.

CREATE TABLE human_review_decision (
    decision_id text PRIMARY KEY CHECK (decision_id ~ '^review-decision:[a-f0-9]{32}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'SYNTHETIC_WORK'),
    proposal_id text NOT NULL UNIQUE REFERENCES checklist_change_proposal(proposal_id),
    validation_result_id text REFERENCES automated_validation_result(validation_result_id),
    proposal_hash text NOT NULL CHECK (proposal_hash ~ '^sha256:[a-f0-9]{64}$'),
    decision text NOT NULL CHECK (decision IN ('APPROVE', 'MODIFY', 'REJECT')),
    reviewer_id text NOT NULL CHECK (reviewer_id ~ '^[A-Z][A-Z0-9-]+$'),
    reason text CHECK (reason IS NULL OR length(reason) > 0),
    decided_at timestamptz NOT NULL,
    approved_checklist_version_id text UNIQUE REFERENCES approved_checklist_version(approved_checklist_version_id),
    schedule_revision_id text UNIQUE REFERENCES approved_checklist_schedule_revision(schedule_revision_id),
    revision_proposal_id text UNIQUE REFERENCES checklist_change_proposal(proposal_id),
    -- 결정 종류별로 반드시 있어야 하는 것과 없어야 하는 것.
    CONSTRAINT human_review_decision_shape CHECK (
        (decision = 'APPROVE' AND validation_result_id IS NOT NULL
            AND approved_checklist_version_id IS NOT NULL AND schedule_revision_id IS NOT NULL
            AND revision_proposal_id IS NULL)
        OR (decision = 'MODIFY' AND reason IS NOT NULL AND revision_proposal_id IS NOT NULL
            AND approved_checklist_version_id IS NULL AND schedule_revision_id IS NULL)
        OR (decision = 'REJECT' AND reason IS NOT NULL AND revision_proposal_id IS NULL
            AND approved_checklist_version_id IS NULL AND schedule_revision_id IS NULL)),
    CHECK (revision_proposal_id IS NULL OR revision_proposal_id <> proposal_id)
);

COMMENT ON TABLE human_review_decision IS
    '사람 검토 결정. 승인은 HUMAN_REVIEW 출처의 승인 checklist와 일정 revision을 가리킨다. 합성 검수자 ID를 쓰는 test/demo 기록이며 실제 승인 체계가 아니다.';

CREATE TABLE human_review_run (
    review_run_id text PRIMARY KEY CHECK (review_run_id ~ '^review-run:[a-f0-9]{32}$'),
    proposal_id text NOT NULL,
    decision text NOT NULL,
    reviewer_id text NOT NULL,
    decision_id text REFERENCES human_review_decision(decision_id),
    started_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    error_code text,
    CHECK ((status = 'SUCCEEDED' AND decision_id IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND decision_id IS NULL AND error_code IS NOT NULL))
);

DO $$
DECLARE
    target text;
BEGIN
    FOREACH target IN ARRAY ARRAY['human_review_decision', 'human_review_run'] LOOP
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
        ('maintenance_change_audit', 'guard_maintenance_change_audit_immutable', 'guard_maintenance_change_audit_truncate', 'trust_agent_guard_audit_log', false)
$$;

ALTER TABLE human_review_decision OWNER TO trust_agent_migration;
ALTER TABLE human_review_run OWNER TO trust_agent_migration;

GRANT SELECT, INSERT ON human_review_decision, human_review_run TO trust_agent_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON human_review_decision, human_review_run TO trust_agent_maintenance;

ALTER FUNCTION trust_agent_protected_tables() OWNER TO trust_agent_audit_owner;
REVOKE ALL ON FUNCTION trust_agent_protected_tables() FROM PUBLIC;
