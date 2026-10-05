-- TASK-006: checklist 변경안 자동 검증 결과, 세부 오류(issue), 실행 기록.
-- 결과와 issue는 한 트랜잭션으로 저장한다. 행 단위 모순은 복합 FK + CHECK로, 개수 단위 모순은
-- 저장 완료(commit) 시점 constraint trigger로 거부한다. 자동 검증 결과는 승인이 아니다.

CREATE TABLE automated_validation_result (
    validation_result_id text PRIMARY KEY CHECK (validation_result_id ~ '^validation:[a-f0-9]{32}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'DERIVED'),
    proposal_id text NOT NULL REFERENCES checklist_change_proposal(proposal_id),
    proposal_hash text NOT NULL CHECK (proposal_hash ~ '^sha256:[a-f0-9]{64}$'),
    validator_version text NOT NULL CHECK (validator_version ~ '^[a-z][a-z0-9-]+$'),
    status text NOT NULL CHECK (status IN ('PASS', 'WARN', 'FAIL')),
    validated_at timestamptz NOT NULL,
    public_evidence_refs jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(public_evidence_refs) = 'array'),
    -- issue가 (결과 ID, 결과 상태)로 참조할 수 있게 한다.
    UNIQUE (validation_result_id, status)
);

CREATE TABLE automated_validation_issue (
    validation_result_id text NOT NULL,
    result_status text NOT NULL,
    issue_order integer NOT NULL CHECK (issue_order >= 0),
    severity text NOT NULL CHECK (severity IN ('FAIL', 'WARN', 'INFO')),
    code text NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]+$'),
    rule_key text CHECK (rule_key IS NULL OR rule_key ~ '^[A-Z][A-Z0-9_]+$'),
    message text NOT NULL CHECK (length(message) > 0),
    details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object'),
    PRIMARY KEY (validation_result_id, issue_order),
    FOREIGN KEY (validation_result_id, result_status)
        REFERENCES automated_validation_result(validation_result_id, status),
    -- 행 단위 모순 거부: FAIL issue는 FAIL 결과에만, WARN issue는 WARN 또는 FAIL 결과에만 붙는다.
    CONSTRAINT automated_validation_issue_severity_matches_result CHECK (
        severity = 'INFO'
        OR (severity = 'WARN' AND result_status IN ('WARN', 'FAIL'))
        OR (severity = 'FAIL' AND result_status = 'FAIL'))
);

CREATE TABLE validation_run (
    validation_run_id text PRIMARY KEY CHECK (validation_run_id ~ '^validation-run:[a-f0-9]{32}$'),
    proposal_id text NOT NULL,
    validator_version text NOT NULL,
    validation_result_id text REFERENCES automated_validation_result(validation_result_id),
    started_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    error_code text,
    CHECK ((status = 'SUCCEEDED' AND validation_result_id IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND validation_result_id IS NULL AND error_code IS NOT NULL))
);

-- 개수 단위 모순 거부: FAIL 결과는 FAIL issue 1개 이상, WARN 결과는 WARN issue 1개 이상.
-- commit 시점에 검사하므로 결과와 issue를 같은 트랜잭션에서 저장해야 한다.
-- SECURITY INVOKER: 저장하는 역할(runtime, maintenance)이 issue 테이블 SELECT 권한을 가진다.
CREATE OR REPLACE FUNCTION trust_agent_check_validation_result_consistency()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
DECLARE
    fail_count integer;
    warn_count integer;
BEGIN
    SELECT count(*) FILTER (WHERE severity = 'FAIL'), count(*) FILTER (WHERE severity = 'WARN')
      INTO fail_count, warn_count
      FROM automated_validation_issue
     WHERE validation_result_id = NEW.validation_result_id;
    IF NEW.status = 'FAIL' AND fail_count = 0 THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
            MESSAGE = 'FAIL 결과에는 FAIL issue가 하나 이상 있어야 합니다: ' || NEW.validation_result_id;
    END IF;
    IF NEW.status = 'WARN' AND warn_count = 0 THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
            MESSAGE = 'WARN 결과에는 WARN issue가 하나 이상 있어야 합니다: ' || NEW.validation_result_id;
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER validation_result_consistency
    AFTER INSERT ON automated_validation_result
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION trust_agent_check_validation_result_consistency();

DO $$
DECLARE
    target text;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'automated_validation_result', 'automated_validation_issue', 'validation_run'
    ] LOOP
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
        ('maintenance_change_audit', 'guard_maintenance_change_audit_immutable', 'guard_maintenance_change_audit_truncate', 'trust_agent_guard_audit_log', false)
$$;

DO $$
DECLARE target text;
BEGIN
    FOREACH target IN ARRAY ARRAY['automated_validation_result', 'automated_validation_issue', 'validation_run']
    LOOP EXECUTE format('ALTER TABLE %I OWNER TO trust_agent_migration', target); END LOOP;
END
$$;

GRANT SELECT, INSERT ON automated_validation_result, automated_validation_issue, validation_run
    TO trust_agent_runtime;

GRANT SELECT, INSERT, UPDATE, DELETE ON automated_validation_result, automated_validation_issue, validation_run
    TO trust_agent_maintenance;

ALTER FUNCTION trust_agent_protected_tables() OWNER TO trust_agent_audit_owner;
REVOKE ALL ON FUNCTION trust_agent_protected_tables() FROM PUBLIC;
ALTER FUNCTION trust_agent_check_validation_result_consistency() OWNER TO trust_agent_audit_owner;
REVOKE ALL ON FUNCTION trust_agent_check_validation_result_consistency() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION trust_agent_check_validation_result_consistency() TO trust_agent_runtime, trust_agent_maintenance;
