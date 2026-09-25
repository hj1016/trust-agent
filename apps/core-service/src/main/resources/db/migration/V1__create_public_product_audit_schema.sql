DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'trust_agent_runtime') THEN
        CREATE ROLE trust_agent_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'trust_agent_migration') THEN
        CREATE ROLE trust_agent_migration NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'trust_agent_maintenance') THEN
        CREATE ROLE trust_agent_maintenance NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'trust_agent_audit_owner') THEN
        CREATE ROLE trust_agent_audit_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
END
$$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO trust_agent_runtime, trust_agent_migration, trust_agent_maintenance;

CREATE TABLE public_product (
    product_key text PRIMARY KEY CHECK (product_key ~ '^[a-z0-9-]+$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'PUBLIC_KB'),
    synthetic boolean NOT NULL CHECK (synthetic = false),
    display_name text NOT NULL CHECK (length(display_name) > 0),
    source_marker text NOT NULL CHECK (length(source_marker) > 0),
    source_url text NOT NULL CHECK (source_url ~ '^https://([a-z0-9-]+\.)*kbstar\.com/'),
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE public_snapshot (
    snapshot_hash text PRIMARY KEY CHECK (snapshot_hash ~ '^sha256:[a-f0-9]{64}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'PUBLIC_KB'),
    synthetic boolean NOT NULL CHECK (synthetic = false),
    product_key text NOT NULL REFERENCES public_product(product_key),
    source_url text NOT NULL CHECK (source_url ~ '^https://([a-z0-9-]+\.)*kbstar\.com/'),
    snapshot_storage text NOT NULL CHECK (snapshot_storage IN ('LOCAL_PRIVATE', 'PRIVATE_OBJECT_STORAGE')),
    snapshot_object_key text NOT NULL CHECK (snapshot_object_key ~ '^public-kb/sha256/[a-f0-9]{64}\.html$'),
    content_type text NOT NULL CHECK (content_type = 'text/html'),
    byte_size bigint NOT NULL CHECK (byte_size > 0),
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE collection_attempt (
    collection_attempt_id text PRIMARY KEY CHECK (collection_attempt_id ~ '^collect:[a-z0-9-]+:[a-f0-9]{32}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'DERIVED'),
    collection_run_id text NOT NULL CHECK (collection_run_id ~ '^run:[a-f0-9]{32}$'),
    product_key text NOT NULL REFERENCES public_product(product_key),
    attempt_sequence integer NOT NULL CHECK (attempt_sequence >= 1),
    attempted_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    observation_id text,
    error_code text CHECK (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]*$'),
    error_message text CHECK (error_message IS NULL OR length(error_message) BETWEEN 1 AND 300),
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    UNIQUE (collection_run_id, product_key, attempt_sequence),
    CHECK (
        (status = 'SUCCEEDED' AND observation_id IS NOT NULL AND error_code IS NULL AND error_message IS NULL)
        OR
        (status = 'FAILED' AND observation_id IS NULL AND error_code IS NOT NULL AND error_message IS NOT NULL)
    )
);

CREATE TABLE public_observation (
    observation_id text PRIMARY KEY CHECK (observation_id ~ '^obs:[a-z0-9-]+:[a-f0-9]{32}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'PUBLIC_KB'),
    synthetic boolean NOT NULL CHECK (synthetic = false),
    collection_attempt_id text NOT NULL UNIQUE REFERENCES collection_attempt(collection_attempt_id) DEFERRABLE INITIALLY DEFERRED,
    collection_run_id text NOT NULL CHECK (collection_run_id ~ '^run:[a-f0-9]{32}$'),
    product_key text NOT NULL REFERENCES public_product(product_key),
    source_url text NOT NULL CHECK (source_url ~ '^https://([a-z0-9-]+\.)*kbstar\.com/'),
    final_url text CHECK (final_url IS NULL OR final_url ~ '^https://([a-z0-9-]+\.)*kbstar\.com/'),
    observed_at timestamptz NOT NULL,
    acquisition_method text NOT NULL CHECK (acquisition_method IN ('HTTP_DOWNLOAD', 'MANUAL_DOWNLOAD')),
    acquisition_note text,
    snapshot_manifest_path text NOT NULL CHECK (snapshot_manifest_path ~ '^datasets/public/kb/manifests/[0-9]{4}-[0-9]{2}-[0-9]{2}/[a-z0-9-]+.*\.manifest\.json$'),
    snapshot_hash text NOT NULL REFERENCES public_snapshot(snapshot_hash),
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (
        (acquisition_method = 'MANUAL_DOWNLOAD' AND acquisition_note IS NOT NULL AND length(acquisition_note) > 0)
        OR
        (acquisition_method = 'HTTP_DOWNLOAD' AND acquisition_note IS NULL)
    )
);

ALTER TABLE collection_attempt
    ADD CONSTRAINT collection_attempt_observation_fk
    FOREIGN KEY (observation_id) REFERENCES public_observation(observation_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE product_terms_version (
    product_terms_version_id text PRIMARY KEY CHECK (product_terms_version_id ~ '^ptv:[a-z0-9-]+:sha256:[a-f0-9]{64}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'PUBLIC_KB'),
    synthetic boolean NOT NULL CHECK (synthetic = false),
    product_key text NOT NULL REFERENCES public_product(product_key),
    terms_hash text NOT NULL CHECK (terms_hash ~ '^sha256:[a-f0-9]{64}$'),
    effective_from date,
    effective_to date,
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    UNIQUE (product_key, terms_hash),
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from)
);

CREATE TABLE product_term_fact (
    product_terms_version_id text NOT NULL REFERENCES product_terms_version(product_terms_version_id),
    fact_id text NOT NULL CHECK (fact_id ~ '^fact:[a-z0-9-]+:[a-z0-9_]+$'),
    fact_order integer NOT NULL CHECK (fact_order >= 0),
    fact_key text NOT NULL CHECK (fact_key IN (
        'product_name', 'applicant_eligibility_text', 'max_limit_individual_krw',
        'max_limit_corporate_krw', 'repayment_method_text', 'sale_status'
    )),
    subject_type text NOT NULL CHECK (subject_type IN ('PRODUCT', 'SOLE_PROPRIETOR', 'CORPORATION')),
    value_type text NOT NULL CHECK (value_type IN ('TEXT', 'INTEGER', 'STATUS')),
    value_text text,
    value_integer bigint,
    value_status text CHECK (value_status IS NULL OR value_status IN ('SELLING', 'DISCONTINUED')),
    unit text NOT NULL CHECK (unit IN ('TEXT', 'KRW', 'STATUS')),
    PRIMARY KEY (product_terms_version_id, fact_id),
    UNIQUE (product_terms_version_id, fact_order),
    CHECK (
        (value_type = 'TEXT' AND value_text IS NOT NULL AND value_integer IS NULL AND value_status IS NULL AND unit = 'TEXT')
        OR
        (value_type = 'INTEGER' AND value_text IS NULL AND value_integer IS NOT NULL AND value_status IS NULL AND unit = 'KRW')
        OR
        (value_type = 'STATUS' AND value_text IS NULL AND value_integer IS NULL AND value_status IS NOT NULL AND unit = 'STATUS')
    ),
    CHECK (fact_key <> 'sale_status' OR value_type = 'STATUS')
);

CREATE TABLE version_evidence (
    version_evidence_id text PRIMARY KEY CHECK (version_evidence_id ~ '^evidence:[a-z0-9-]+:[a-f0-9]{32}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'PUBLIC_KB'),
    synthetic boolean NOT NULL CHECK (synthetic = false),
    observation_id text NOT NULL UNIQUE REFERENCES public_observation(observation_id),
    product_terms_version_id text NOT NULL REFERENCES product_terms_version(product_terms_version_id),
    product_key text NOT NULL REFERENCES public_product(product_key),
    snapshot_hash text NOT NULL REFERENCES public_snapshot(snapshot_hash),
    parser_version text NOT NULL CHECK (parser_version ~ '^public-kb-html-v[0-9]+$'),
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    UNIQUE (version_evidence_id, product_terms_version_id)
);

CREATE TABLE fact_evidence_locator (
    version_evidence_id text NOT NULL,
    product_terms_version_id text NOT NULL,
    fact_id text NOT NULL,
    locator_order integer NOT NULL CHECK (locator_order >= 0),
    strategy text NOT NULL CHECK (strategy IN ('CSS_ATTRIBUTE', 'LABELED_ADJACENT_TEXT', 'CSS_TEXT_MATCH')),
    selector text NOT NULL CHECK (length(selector) > 0),
    label text NOT NULL CHECK (length(label) > 0),
    evidence_text text NOT NULL CHECK (length(evidence_text) > 0),
    evidence_hash text NOT NULL CHECK (evidence_hash ~ '^sha256:[a-f0-9]{64}$'),
    snapshot_hash text NOT NULL REFERENCES public_snapshot(snapshot_hash),
    source_url text NOT NULL CHECK (source_url ~ '^https://([a-z0-9-]+\.)*kbstar\.com/'),
    PRIMARY KEY (version_evidence_id, fact_id, locator_order),
    FOREIGN KEY (version_evidence_id, product_terms_version_id)
        REFERENCES version_evidence(version_evidence_id, product_terms_version_id),
    FOREIGN KEY (product_terms_version_id, fact_id)
        REFERENCES product_term_fact(product_terms_version_id, fact_id)
);

CREATE TABLE observed_rate_quote (
    rate_quote_id text PRIMARY KEY CHECK (rate_quote_id ~ '^quote:[a-z0-9-]+:[a-f0-9]{32}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'PUBLIC_KB'),
    synthetic boolean NOT NULL CHECK (synthetic = false),
    observation_id text NOT NULL UNIQUE REFERENCES public_observation(observation_id),
    product_key text NOT NULL REFERENCES public_product(product_key),
    advertised_rate_text text NOT NULL CHECK (length(advertised_rate_text) > 0),
    advertised_rate_reference_date date,
    text_locator jsonb NOT NULL CHECK (jsonb_typeof(text_locator) = 'object'),
    reference_date_locator jsonb CHECK (reference_date_locator IS NULL OR jsonb_typeof(reference_date_locator) = 'object'),
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE extraction_attempt (
    extraction_attempt_id text PRIMARY KEY CHECK (extraction_attempt_id ~ '^extract:[a-z0-9-]+:[a-f0-9]{32}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'DERIVED'),
    extraction_run_id text NOT NULL CHECK (extraction_run_id ~ '^run:[a-f0-9]{32}$'),
    observation_id text NOT NULL REFERENCES public_observation(observation_id),
    product_key text NOT NULL REFERENCES public_product(product_key),
    attempt_sequence integer NOT NULL CHECK (attempt_sequence >= 1),
    attempted_at timestamptz NOT NULL,
    attempted_at_source text NOT NULL CHECK (attempted_at_source IN ('MEASURED', 'BACKFILLED_FROM_OBSERVATION')),
    parser_version text NOT NULL CHECK (parser_version ~ '^public-kb-html-v[0-9]+$'),
    status text NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    product_terms_version_id text REFERENCES product_terms_version(product_terms_version_id),
    version_evidence_id text REFERENCES version_evidence(version_evidence_id),
    rate_quote_id text REFERENCES observed_rate_quote(rate_quote_id),
    error_code text CHECK (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]*$'),
    error_message text CHECK (error_message IS NULL OR length(error_message) BETWEEN 1 AND 300),
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    UNIQUE (extraction_run_id, observation_id, attempt_sequence),
    CHECK (
        (status = 'SUCCEEDED' AND product_terms_version_id IS NOT NULL
            AND version_evidence_id IS NOT NULL AND rate_quote_id IS NOT NULL
            AND error_code IS NULL AND error_message IS NULL)
        OR
        (status = 'FAILED' AND product_terms_version_id IS NULL
            AND version_evidence_id IS NULL AND rate_quote_id IS NULL
            AND error_code IS NOT NULL AND error_message IS NOT NULL)
    )
);

CREATE TABLE change_detection_result (
    change_detection_result_id text PRIMARY KEY CHECK (change_detection_result_id ~ '^change:[a-z0-9-]+:[a-f0-9]{64}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'DERIVED'),
    product_key text NOT NULL REFERENCES public_product(product_key),
    observation_id text NOT NULL REFERENCES public_observation(observation_id),
    previous_observation_id text REFERENCES public_observation(observation_id),
    evaluated_at timestamptz NOT NULL,
    policy_version text NOT NULL CHECK (policy_version = 'public-kb-change-v1'),
    supersedes_result_id text REFERENCES change_detection_result(change_detection_result_id),
    source_record_hash text NOT NULL CHECK (source_record_hash ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE change_detection_classification (
    change_detection_result_id text NOT NULL REFERENCES change_detection_result(change_detection_result_id),
    classification_order integer NOT NULL CHECK (classification_order >= 0),
    classification text NOT NULL CHECK (classification IN (
        'BASELINE_ESTABLISHED', 'PRODUCT_TERMS_CHANGED', 'RATE_QUOTE_CHANGED',
        'QUOTE_REFRESHED', 'NO_SEMANTIC_CHANGE'
    )),
    PRIMARY KEY (change_detection_result_id, classification_order),
    UNIQUE (change_detection_result_id, classification)
);

CREATE TABLE baseline_import_run (
    baseline_import_run_id text PRIMARY KEY CHECK (baseline_import_run_id ~ '^baseline:[a-f0-9]{32}$'),
    baseline_fingerprint text NOT NULL CHECK (baseline_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    status text NOT NULL CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED')),
    input_files jsonb NOT NULL CHECK (jsonb_typeof(input_files) = 'array'),
    imported_counts jsonb CHECK (imported_counts IS NULL OR jsonb_typeof(imported_counts) = 'object'),
    error_code text CHECK (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]*$'),
    CHECK (
        (status = 'STARTED' AND completed_at IS NULL AND error_code IS NULL)
        OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND error_code IS NOT NULL)
    )
);

CREATE TABLE maintenance_change_audit (
    maintenance_change_audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    table_name text NOT NULL,
    operation text NOT NULL CHECK (operation IN ('UPDATE', 'DELETE')),
    primary_key jsonb NOT NULL,
    actor_id text NOT NULL,
    ticket_id text NOT NULL,
    reason text NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    old_record jsonb NOT NULL,
    new_record jsonb,
    old_record_hash text NOT NULL CHECK (old_record_hash ~ '^sha256:[a-f0-9]{64}$'),
    new_record_hash text CHECK (new_record_hash IS NULL OR new_record_hash ~ '^sha256:[a-f0-9]{64}$')
);

ALTER TABLE maintenance_change_audit OWNER TO trust_agent_audit_owner;

CREATE OR REPLACE FUNCTION trust_agent_sha256(value jsonb)
RETURNS text
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
    SELECT 'sha256:' || encode(sha256(convert_to(value::text, 'UTF8')), 'hex')
$$;

CREATE OR REPLACE FUNCTION trust_agent_protected_tables()
RETURNS TABLE (
    protected_table_name text,
    guard_trigger_name text,
    truncate_trigger_name text,
    guard_function_name text,
    migration_owned boolean
)
LANGUAGE sql
IMMUTABLE
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
        ('maintenance_change_audit', 'guard_maintenance_change_audit_immutable', 'guard_maintenance_change_audit_truncate', 'trust_agent_guard_audit_log', false)
$$;

CREATE OR REPLACE FUNCTION trust_agent_primary_key(target_table_name text, record jsonb)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
STRICT
AS $$
DECLARE
    result jsonb;
BEGIN
    SELECT jsonb_object_agg(attribute.attname, record -> attribute.attname ORDER BY key_column.ordinality)
    INTO result
    FROM pg_catalog.pg_class relation
    JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
    JOIN pg_catalog.pg_index primary_index
        ON primary_index.indrelid = relation.oid AND primary_index.indisprimary
    CROSS JOIN LATERAL unnest(primary_index.indkey)
        WITH ORDINALITY AS key_column(attribute_number, ordinality)
    JOIN pg_catalog.pg_attribute attribute
        ON attribute.attrelid = relation.oid
        AND attribute.attnum = key_column.attribute_number
    WHERE namespace.nspname = 'public'
      AND relation.relname = target_table_name;

    IF result IS NULL OR result = '{}'::jsonb THEN
        RAISE EXCEPTION 'primary key mapping is unavailable for protected table %', target_table_name
            USING ERRCODE = '55000';
    END IF;
    RETURN result;
END
$$;

CREATE OR REPLACE FUNCTION trust_agent_guard_append_only()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
SET timezone = 'UTC'
SET datestyle = 'ISO, YMD'
AS $$
DECLARE
    ticket text := current_setting('trust_agent.maintenance_ticket', true);
    reason text := current_setting('trust_agent.maintenance_reason', true);
    actor text := current_setting('trust_agent.maintenance_actor', true);
    old_json jsonb := to_jsonb(OLD);
    new_json jsonb := CASE WHEN TG_OP = 'UPDATE' THEN to_jsonb(NEW) ELSE NULL END;
BEGIN
    IF NOT pg_has_role(session_user, 'trust_agent_maintenance', 'member') THEN
        RAISE EXCEPTION 'append-only table % does not allow %', TG_TABLE_NAME, TG_OP
            USING ERRCODE = '42501';
    END IF;
    IF coalesce(ticket, '') = '' OR coalesce(reason, '') = '' OR coalesce(actor, '') = '' THEN
        RAISE EXCEPTION 'maintenance ticket, reason and actor are required'
            USING ERRCODE = '42501';
    END IF;

    INSERT INTO maintenance_change_audit (
        table_name, operation, primary_key, actor_id, ticket_id, reason,
        old_record, new_record, old_record_hash, new_record_hash
    ) VALUES (
        TG_TABLE_NAME, TG_OP, trust_agent_primary_key(TG_TABLE_NAME, old_json),
        actor, ticket, reason, old_json, new_json, trust_agent_sha256(old_json),
        CASE WHEN new_json IS NULL THEN NULL ELSE trust_agent_sha256(new_json) END
    );

    IF TG_OP = 'UPDATE' THEN
        RETURN NEW;
    END IF;
    RETURN OLD;
END
$$;

CREATE OR REPLACE FUNCTION trust_agent_guard_audit_log()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'maintenance_change_audit is immutable' USING ERRCODE = '42501';
END
$$;

CREATE OR REPLACE FUNCTION trust_agent_guard_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'protected table % does not allow TRUNCATE', TG_TABLE_NAME
        USING ERRCODE = '42501';
END
$$;

DO $$
DECLARE
    protected record;
BEGIN
    FOR protected IN SELECT * FROM trust_agent_protected_tables()
    LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION %I()',
            protected.guard_trigger_name,
            protected.protected_table_name,
            protected.guard_function_name
        );
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION trust_agent_guard_truncate()',
            protected.truncate_trigger_name,
            protected.protected_table_name
        );
    END LOOP;
END
$$;

CREATE OR REPLACE FUNCTION trust_agent_guard_protection_ddl()
RETURNS event_trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    IF pg_has_role(session_user, 'trust_agent_migration', 'member')
        AND TG_TAG = 'DROP TRIGGER'
    THEN
        RAISE EXCEPTION 'migration role cannot drop audit triggers'
            USING ERRCODE = '42501';
    END IF;
END
$$;

CREATE EVENT TRIGGER guard_audit_trigger_ddl
ON ddl_command_start
WHEN TAG IN ('ALTER TABLE', 'DROP TRIGGER')
EXECUTE FUNCTION trust_agent_guard_protection_ddl();

CREATE OR REPLACE FUNCTION trust_agent_verify_protection_state()
RETURNS event_trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    invalid_guards text;
BEGIN
    SELECT string_agg(
        format(
            '%I.%I:%s',
            expected.protected_table_name,
            expected_trigger.trigger_name,
            CASE
                WHEN actual.oid IS NULL THEN 'MISSING'
                ELSE 'STATE=' || actual.tgenabled::text
            END
        ),
        ', ' ORDER BY expected.protected_table_name
    )
    INTO invalid_guards
    FROM public.trust_agent_protected_tables() expected
    CROSS JOIN LATERAL (
        VALUES (expected.guard_trigger_name), (expected.truncate_trigger_name)
    ) expected_trigger(trigger_name)
    LEFT JOIN pg_catalog.pg_namespace namespace ON namespace.nspname = 'public'
    LEFT JOIN pg_catalog.pg_class relation
        ON relation.relnamespace = namespace.oid
        AND relation.relname = expected.protected_table_name
    LEFT JOIN pg_catalog.pg_trigger actual
        ON actual.tgrelid = relation.oid
        AND actual.tgname = expected_trigger.trigger_name
        AND NOT actual.tgisinternal
    WHERE actual.oid IS NULL OR actual.tgenabled <> 'O';

    IF invalid_guards IS NOT NULL THEN
        RAISE EXCEPTION 'audit guard protection is incomplete: %', invalid_guards
            USING ERRCODE = '42501';
    END IF;
END
$$;

CREATE EVENT TRIGGER verify_audit_guard_state
ON ddl_command_end
EXECUTE FUNCTION trust_agent_verify_protection_state();

DO $$
DECLARE
    protected_table text;
BEGIN
    FOR protected_table IN
        SELECT protected_table_name
        FROM trust_agent_protected_tables()
        WHERE migration_owned
    LOOP
        EXECUTE format('ALTER TABLE %I OWNER TO trust_agent_migration', protected_table);
    END LOOP;
END
$$;

ALTER FUNCTION trust_agent_guard_append_only() OWNER TO trust_agent_audit_owner;
ALTER FUNCTION trust_agent_guard_audit_log() OWNER TO trust_agent_audit_owner;
ALTER FUNCTION trust_agent_guard_truncate() OWNER TO trust_agent_audit_owner;
ALTER FUNCTION trust_agent_primary_key(text, jsonb) OWNER TO trust_agent_audit_owner;
ALTER FUNCTION trust_agent_protected_tables() OWNER TO trust_agent_audit_owner;
ALTER FUNCTION trust_agent_sha256(jsonb) OWNER TO trust_agent_audit_owner;
ALTER FUNCTION trust_agent_guard_protection_ddl() OWNER TO trust_agent_audit_owner;
ALTER FUNCTION trust_agent_verify_protection_state() OWNER TO trust_agent_audit_owner;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
GRANT CREATE ON SCHEMA public TO trust_agent_migration;
GRANT SELECT, INSERT ON public_product, public_snapshot, collection_attempt,
    public_observation, product_terms_version, product_term_fact, version_evidence,
    fact_evidence_locator, observed_rate_quote, extraction_attempt,
    change_detection_result, change_detection_classification, baseline_import_run
    TO trust_agent_runtime;
GRANT SELECT ON maintenance_change_audit TO trust_agent_runtime;

GRANT SELECT, INSERT, UPDATE, DELETE ON public_product, public_snapshot, collection_attempt,
    public_observation, product_terms_version, product_term_fact, version_evidence,
    fact_evidence_locator, observed_rate_quote, extraction_attempt,
    change_detection_result, change_detection_classification, baseline_import_run
    TO trust_agent_maintenance;
GRANT SELECT ON maintenance_change_audit TO trust_agent_maintenance;
GRANT USAGE, SELECT ON SEQUENCE maintenance_change_audit_maintenance_change_audit_id_seq
    TO trust_agent_audit_owner;

REVOKE ALL ON FUNCTION trust_agent_guard_append_only() FROM PUBLIC;
REVOKE ALL ON FUNCTION trust_agent_guard_audit_log() FROM PUBLIC;
REVOKE ALL ON FUNCTION trust_agent_guard_truncate() FROM PUBLIC;
REVOKE ALL ON FUNCTION trust_agent_guard_protection_ddl() FROM PUBLIC;
REVOKE ALL ON FUNCTION trust_agent_verify_protection_state() FROM PUBLIC;
REVOKE ALL ON FUNCTION trust_agent_protected_tables() FROM PUBLIC;
REVOKE ALL ON FUNCTION trust_agent_primary_key(text, jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION trust_agent_sha256(jsonb) FROM PUBLIC;
