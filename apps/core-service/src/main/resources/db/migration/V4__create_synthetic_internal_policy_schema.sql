-- 보호 테이블을 추가하는 migration은 반드시 다음 순서를 지킵니다.
-- 1) CREATE TABLE  2) append-only trigger  3) truncate trigger
-- 4) trust_agent_protected_tables() 갱신  5) OWNER 변경
-- 목록을 먼저 갱신하면 ddl_command_end 검사가 MISSING guard로 migration을 중단합니다.

-- btree_gist is a hard prerequisite. CREATE EXTENSION permission or availability failures
-- must abort this migration; the overlap constraint must never be omitted silently.
CREATE EXTENSION IF NOT EXISTS btree_gist;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'btree_gist') THEN
        RAISE EXCEPTION 'btree_gist extension is required for approved checklist schedule overlap protection';
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'trust_agent_synthetic_importer') THEN
        CREATE ROLE trust_agent_synthetic_importer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO trust_agent_synthetic_importer;

CREATE TABLE internal_notice_version (
    notice_id text PRIMARY KEY CHECK (notice_id ~ '^SIN-[A-Z0-9-]+-V[1-9][0-9]*$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'SYNTHETIC_INTERNAL'),
    synthetic boolean NOT NULL CHECK (synthetic),
    disclaimer text NOT NULL CHECK (length(disclaimer) >= 20),
    family_id text NOT NULL CHECK (family_id ~ '^SIN-[A-Z0-9-]+$'),
    version integer NOT NULL CHECK (version >= 1),
    title text NOT NULL CHECK (length(title) > 0),
    document_status text NOT NULL CHECK (document_status = 'ISSUED'),
    issued_on date NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    supersedes_notice_id text UNIQUE,
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    UNIQUE (family_id, version),
    UNIQUE (notice_id, family_id),
    FOREIGN KEY (supersedes_notice_id, family_id)
        REFERENCES internal_notice_version(notice_id, family_id),
    CHECK (effective_to IS NULL OR effective_from < effective_to)
);

CREATE UNIQUE INDEX internal_notice_one_root_per_family_idx
    ON internal_notice_version(family_id) WHERE supersedes_notice_id IS NULL;

CREATE TABLE internal_notice_reference (
    notice_id text NOT NULL REFERENCES internal_notice_version(notice_id),
    reference_order integer NOT NULL CHECK (reference_order >= 0),
    dataset_class text NOT NULL CHECK (dataset_class = 'PUBLIC_KB'),
    product_key text NOT NULL REFERENCES public_product(product_key),
    snapshot_hash text NOT NULL REFERENCES public_snapshot(snapshot_hash),
    fact_key text NOT NULL CHECK (fact_key = 'max_limit_corporate_krw'),
    subject_type text NOT NULL CHECK (subject_type = 'CORPORATION'),
    value_type text NOT NULL CHECK (value_type = 'INTEGER'),
    expected_value bigint NOT NULL CHECK (expected_value > 0),
    unit text NOT NULL CHECK (unit = 'KRW'),
    evidence_requirement text NOT NULL CHECK (evidence_requirement IN ('REQUIRED', 'INFORMATIONAL')),
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    PRIMARY KEY (notice_id, reference_order)
);

CREATE TABLE internal_notice_receipt (
    receipt_id text PRIMARY KEY CHECK (receipt_id ~ '^notice-receipt:[a-f0-9]{32}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'SYNTHETIC_INTERNAL'),
    synthetic boolean NOT NULL CHECK (synthetic),
    notice_id text NOT NULL REFERENCES internal_notice_version(notice_id),
    received_at timestamptz NOT NULL,
    received_business_date date NOT NULL,
    business_timezone text NOT NULL CHECK (business_timezone = 'Asia/Seoul'),
    timezone_policy_version text NOT NULL CHECK (timezone_policy_version = 'internal-business-time-v1'),
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    UNIQUE (notice_id, received_at)
);

CREATE TABLE internal_notice_lifecycle_event (
    lifecycle_event_id text PRIMARY KEY CHECK (lifecycle_event_id ~ '^notice-event:[a-f0-9]{32}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'SYNTHETIC_INTERNAL'),
    notice_id text NOT NULL REFERENCES internal_notice_version(notice_id),
    event_type text NOT NULL CHECK (event_type = 'WITHDRAWN'),
    occurred_at timestamptz NOT NULL,
    reason text NOT NULL CHECK (length(reason) > 0),
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE policy_extraction_attempt (
    extraction_attempt_id text PRIMARY KEY CHECK (extraction_attempt_id ~ '^policy-extract:[a-f0-9]{32}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'DERIVED'),
    receipt_id text NOT NULL REFERENCES internal_notice_receipt(receipt_id),
    notice_id text NOT NULL REFERENCES internal_notice_version(notice_id),
    attempted_at timestamptz NOT NULL,
    parser_version text NOT NULL CHECK (parser_version = 'synthetic-notice-json-v1'),
    status text NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    error_code text,
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    CHECK ((status = 'SUCCEEDED' AND error_code IS NULL) OR (status = 'FAILED' AND error_code IS NOT NULL))
);

CREATE TABLE internal_policy_rule_version (
    rule_version_id text PRIMARY KEY CHECK (rule_version_id ~ '^policy-rule:sha256:[a-f0-9]{64}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'SYNTHETIC_INTERNAL'),
    rule_key text NOT NULL CHECK (rule_key ~ '^[A-Z][A-Z0-9_]+$'),
    instruction text NOT NULL CHECK (length(instruction) > 0),
    evidence_required boolean NOT NULL,
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE internal_policy_rule_evidence (
    extraction_attempt_id text NOT NULL REFERENCES policy_extraction_attempt(extraction_attempt_id),
    notice_id text NOT NULL REFERENCES internal_notice_version(notice_id),
    rule_version_id text NOT NULL REFERENCES internal_policy_rule_version(rule_version_id),
    rule_order integer NOT NULL CHECK (rule_order >= 0),
    json_pointer text NOT NULL CHECK (json_pointer ~ '^/rules/[0-9]+$'),
    evidence_text text NOT NULL CHECK (length(evidence_text) > 0),
    evidence_hash text NOT NULL CHECK (evidence_hash ~ '^sha256:[a-f0-9]{64}$'),
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    PRIMARY KEY (extraction_attempt_id, rule_version_id),
    UNIQUE (extraction_attempt_id, rule_order)
);

CREATE TABLE approved_checklist_version (
    approved_checklist_version_id text PRIMARY KEY CHECK (approved_checklist_version_id ~ '^approved-checklist:[a-f0-9]{32}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'SYNTHETIC_INTERNAL'),
    family_id text NOT NULL,
    notice_id text NOT NULL,
    created_at timestamptz NOT NULL,
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    FOREIGN KEY (notice_id, family_id) REFERENCES internal_notice_version(notice_id, family_id),
    UNIQUE (approved_checklist_version_id, family_id)
);

CREATE TABLE approved_checklist_schedule_revision (
    schedule_revision_id text PRIMARY KEY CHECK (schedule_revision_id ~ '^checklist-schedule:[a-f0-9]{32}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'SYNTHETIC_INTERNAL'),
    family_id text NOT NULL,
    supersedes_schedule_revision_id text UNIQUE,
    created_at timestamptz NOT NULL,
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    UNIQUE (schedule_revision_id, family_id),
    FOREIGN KEY (supersedes_schedule_revision_id, family_id)
        REFERENCES approved_checklist_schedule_revision(schedule_revision_id, family_id)
);

CREATE UNIQUE INDEX approved_checklist_one_root_schedule_per_family_idx
    ON approved_checklist_schedule_revision(family_id)
    WHERE supersedes_schedule_revision_id IS NULL;

CREATE TABLE approved_checklist_schedule_entry (
    schedule_revision_id text NOT NULL,
    family_id text NOT NULL,
    entry_order integer NOT NULL CHECK (entry_order >= 0),
    approved_checklist_version_id text NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    PRIMARY KEY (schedule_revision_id, entry_order),
    FOREIGN KEY (schedule_revision_id, family_id)
        REFERENCES approved_checklist_schedule_revision(schedule_revision_id, family_id),
    FOREIGN KEY (approved_checklist_version_id, family_id)
        REFERENCES approved_checklist_version(approved_checklist_version_id, family_id),
    CHECK (effective_to IS NULL OR effective_from < effective_to),
    EXCLUDE USING gist (
        schedule_revision_id WITH =,
        family_id WITH =,
        daterange(effective_from, effective_to, '[)') WITH &&
    )
);

CREATE TABLE synthetic_internal_import_run (
    import_run_id text PRIMARY KEY CHECK (import_run_id ~ '^synthetic-import:[a-f0-9]{32}$'),
    baseline_fingerprint text NOT NULL CHECK (baseline_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    started_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    imported_counts jsonb,
    error_code text,
    CHECK ((status = 'SUCCEEDED' AND imported_counts IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND error_code IS NOT NULL))
);

DO $$
DECLARE
    target text;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'internal_notice_version', 'internal_notice_reference', 'internal_notice_receipt',
        'internal_notice_lifecycle_event', 'policy_extraction_attempt',
        'internal_policy_rule_version', 'internal_policy_rule_evidence',
        'approved_checklist_version', 'approved_checklist_schedule_revision',
        'approved_checklist_schedule_entry', 'synthetic_internal_import_run'
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
        ('maintenance_change_audit', 'guard_maintenance_change_audit_immutable', 'guard_maintenance_change_audit_truncate', 'trust_agent_guard_audit_log', false)
$$;

DO $$
DECLARE target text;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'internal_notice_version', 'internal_notice_reference', 'internal_notice_receipt',
        'internal_notice_lifecycle_event', 'policy_extraction_attempt', 'internal_policy_rule_version',
        'internal_policy_rule_evidence', 'approved_checklist_version',
        'approved_checklist_schedule_revision', 'approved_checklist_schedule_entry',
        'synthetic_internal_import_run'
    ] LOOP EXECUTE format('ALTER TABLE %I OWNER TO trust_agent_migration', target); END LOOP;
END
$$;

GRANT SELECT, INSERT ON internal_notice_version, internal_notice_reference,
    internal_notice_receipt, policy_extraction_attempt, internal_policy_rule_version,
    internal_policy_rule_evidence, synthetic_internal_import_run
    TO trust_agent_synthetic_importer;

GRANT SELECT ON internal_notice_version, internal_notice_reference, internal_notice_receipt,
    internal_notice_lifecycle_event, policy_extraction_attempt, internal_policy_rule_version,
    internal_policy_rule_evidence, approved_checklist_version,
    approved_checklist_schedule_revision, approved_checklist_schedule_entry
    TO trust_agent_runtime;

GRANT SELECT, INSERT ON internal_notice_lifecycle_event, approved_checklist_version,
    approved_checklist_schedule_revision, approved_checklist_schedule_entry
    TO trust_agent_runtime;

GRANT SELECT, INSERT, UPDATE, DELETE ON internal_notice_version, internal_notice_reference,
    internal_notice_receipt, internal_notice_lifecycle_event, policy_extraction_attempt,
    internal_policy_rule_version, internal_policy_rule_evidence, approved_checklist_version,
    approved_checklist_schedule_revision, approved_checklist_schedule_entry,
    synthetic_internal_import_run TO trust_agent_maintenance;

ALTER FUNCTION trust_agent_protected_tables() OWNER TO trust_agent_audit_owner;
REVOKE ALL ON FUNCTION trust_agent_protected_tables() FROM PUBLIC;
