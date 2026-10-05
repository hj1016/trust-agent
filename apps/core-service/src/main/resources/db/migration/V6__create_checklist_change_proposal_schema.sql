-- TASK-005: 승인 checklist 내용(item), test/demo 전용 fixture 적재 기록, checklist 변경안(proposal)과 생성 실행 기록.
-- 보호 테이블 추가 절차(V2 주석): 1) CREATE TABLE  2) append-only 가드 trigger  3) TRUNCATE 가드
-- 4) trust_agent_protected_tables() 갱신  5) OWNER 변경.

-- origin은 출처 구분이다. FIXTURE는 test/demo 예시 데이터이며 인간 승인 증거가 아니다.
ALTER TABLE approved_checklist_version
    ADD COLUMN origin text NOT NULL DEFAULT 'HUMAN_REVIEW'
        CONSTRAINT approved_checklist_version_origin_check CHECK (origin IN ('HUMAN_REVIEW', 'FIXTURE'));

COMMENT ON COLUMN approved_checklist_version.origin IS
    '출처 구분. HUMAN_REVIEW는 사람 검토 결정으로 발행된 checklist, FIXTURE는 test/demo 전용 예시 데이터. 승인 증거가 아니다.';

CREATE TABLE approved_checklist_item (
    approved_checklist_version_id text NOT NULL,
    family_id text NOT NULL,
    item_order integer NOT NULL CHECK (item_order >= 0),
    rule_key text NOT NULL CHECK (rule_key ~ '^[A-Z][A-Z0-9_]+$'),
    instruction text NOT NULL CHECK (length(instruction) > 0),
    evidence_required boolean NOT NULL,
    structured_change jsonb NOT NULL DEFAULT 'null'::jsonb
        CHECK (jsonb_typeof(structured_change) IN ('object', 'null')),
    source_rule_version_id text REFERENCES internal_policy_rule_version(rule_version_id),
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    PRIMARY KEY (approved_checklist_version_id, item_order),
    UNIQUE (approved_checklist_version_id, rule_key),
    FOREIGN KEY (approved_checklist_version_id, family_id)
        REFERENCES approved_checklist_version(approved_checklist_version_id, family_id)
);

CREATE TABLE approved_checklist_fixture_run (
    fixture_run_id text PRIMARY KEY CHECK (fixture_run_id ~ '^checklist-fixture-run:[a-f0-9]{32}$'),
    fixture_fingerprint text NOT NULL CHECK (fixture_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    started_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    imported_counts jsonb,
    error_code text,
    CHECK ((status = 'SUCCEEDED' AND imported_counts IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND error_code IS NOT NULL))
);

CREATE TABLE checklist_change_proposal (
    proposal_id text PRIMARY KEY CHECK (proposal_id ~ '^checklist-proposal:sha256:[a-f0-9]{64}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'DERIVED'),
    family_id text NOT NULL,
    base_checklist_version_id text NOT NULL,
    target_notice_id text NOT NULL,
    generator_version text NOT NULL CHECK (generator_version ~ '^[a-z][a-z0-9-]+$'),
    supersedes_proposal_id text UNIQUE REFERENCES checklist_change_proposal(proposal_id),
    revision_reason text CHECK (revision_reason IS NULL OR length(revision_reason) > 0),
    before_hash text NOT NULL CHECK (before_hash ~ '^sha256:[a-f0-9]{64}$'),
    after_hash text NOT NULL CHECK (after_hash ~ '^sha256:[a-f0-9]{64}$'),
    item_count integer NOT NULL CHECK (item_count >= 0),
    created_at timestamptz NOT NULL,
    FOREIGN KEY (base_checklist_version_id, family_id)
        REFERENCES approved_checklist_version(approved_checklist_version_id, family_id),
    FOREIGN KEY (target_notice_id, family_id)
        REFERENCES internal_notice_version(notice_id, family_id),
    -- 최초 proposal에는 대체 대상과 사유가 없고, revision에는 둘 다 있다.
    CHECK ((supersedes_proposal_id IS NULL) = (revision_reason IS NULL))
);

CREATE TABLE checklist_change_proposal_item (
    proposal_id text NOT NULL REFERENCES checklist_change_proposal(proposal_id),
    item_order integer NOT NULL CHECK (item_order >= 0),
    rule_key text NOT NULL CHECK (rule_key ~ '^[A-Z][A-Z0-9_]+$'),
    change_type text NOT NULL CHECK (change_type IN ('ADD', 'MODIFY', 'REMOVE')),
    before_json jsonb CHECK (before_json IS NULL OR jsonb_typeof(before_json) = 'object'),
    after_json jsonb CHECK (after_json IS NULL OR jsonb_typeof(after_json) = 'object'),
    before_hash text CHECK (before_hash ~ '^sha256:[a-f0-9]{64}$'),
    after_hash text CHECK (after_hash ~ '^sha256:[a-f0-9]{64}$'),
    PRIMARY KEY (proposal_id, item_order),
    UNIQUE (proposal_id, rule_key),
    CHECK ((change_type = 'ADD' AND before_json IS NULL AND before_hash IS NULL
                AND after_json IS NOT NULL AND after_hash IS NOT NULL)
        OR (change_type = 'REMOVE' AND after_json IS NULL AND after_hash IS NULL
                AND before_json IS NOT NULL AND before_hash IS NOT NULL)
        OR (change_type = 'MODIFY' AND before_json IS NOT NULL AND before_hash IS NOT NULL
                AND after_json IS NOT NULL AND after_hash IS NOT NULL))
);

CREATE TABLE proposal_generation_run (
    generation_run_id text PRIMARY KEY CHECK (generation_run_id ~ '^proposal-run:[a-f0-9]{32}$'),
    family_id text NOT NULL,
    target_notice_id text NOT NULL,
    generator_version text NOT NULL,
    proposal_id text REFERENCES checklist_change_proposal(proposal_id),
    started_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    error_code text,
    CHECK ((status = 'SUCCEEDED' AND proposal_id IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND proposal_id IS NULL AND error_code IS NOT NULL))
);

DO $$
DECLARE
    target text;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'approved_checklist_item', 'approved_checklist_fixture_run',
        'checklist_change_proposal', 'checklist_change_proposal_item', 'proposal_generation_run'
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
        ('maintenance_change_audit', 'guard_maintenance_change_audit_immutable', 'guard_maintenance_change_audit_truncate', 'trust_agent_guard_audit_log', false)
$$;

DO $$
DECLARE target text;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'approved_checklist_item', 'approved_checklist_fixture_run',
        'checklist_change_proposal', 'checklist_change_proposal_item', 'proposal_generation_run'
    ] LOOP EXECUTE format('ALTER TABLE %I OWNER TO trust_agent_migration', target); END LOOP;
END
$$;

-- runtime은 조회와 추가만. fixture 적재와 변경안 생성은 runtime 계정으로 실행한다.
-- importer 계정(공개 baseline, 합성 공문)에는 권한을 주지 않는다(R-05: importer는 승인을 기록할 수 없다).
GRANT SELECT, INSERT ON approved_checklist_item, approved_checklist_fixture_run,
    checklist_change_proposal, checklist_change_proposal_item, proposal_generation_run
    TO trust_agent_runtime;

GRANT SELECT, INSERT, UPDATE, DELETE ON approved_checklist_item, approved_checklist_fixture_run,
    checklist_change_proposal, checklist_change_proposal_item, proposal_generation_run
    TO trust_agent_maintenance;

ALTER FUNCTION trust_agent_protected_tables() OWNER TO trust_agent_audit_owner;
REVOKE ALL ON FUNCTION trust_agent_protected_tables() FROM PUBLIC;
