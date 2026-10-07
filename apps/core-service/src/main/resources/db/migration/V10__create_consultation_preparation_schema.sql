-- TASK-015: AI 서비스가 규칙으로 조립한 상담 준비안·보류와 실행 기록, 상품별 공문군 매핑(ADR-012).
-- 네 표 모두 append-only다. AI 기록 경로(기록 토큰)는 준비안·섹션·실행 기록 세 표에만 INSERT한다.
-- 매핑 표는 별도 bootstrap 경로로만 적재되며 기록 경로가 쓰지 않는다.
-- 근거 원문 문장, 메모, 토큰은 저장하지 않는다. 기록은 사용 허가가 아니다.

CREATE TABLE consultation_family_mapping (
    mapping_hash text NOT NULL CHECK (mapping_hash ~ '^sha256:[a-f0-9]{64}$'),
    mapping_version text NOT NULL CHECK (mapping_version ~ '^v[1-9][0-9]*$'),
    product_key text NOT NULL CHECK (product_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    family_id text NOT NULL CHECK (family_id ~ '^SIN-[A-Z0-9-]+$'),
    required boolean NOT NULL,
    family_order integer NOT NULL CHECK (family_order >= 0),
    loaded_at timestamptz NOT NULL,
    PRIMARY KEY (mapping_hash, product_key, family_id)
);

COMMENT ON TABLE consultation_family_mapping IS
    '상품별 공문군 매핑과 필수 여부. 합성 시나리오의 사용자 승인 설정 파일을 bootstrap importer가 적재한다. 활성 매핑은 가장 최근 loaded_at의 mapping_hash다.';

CREATE TABLE consultation_preparation (
    preparation_id text PRIMARY KEY CHECK (preparation_id ~ '^consultation-preparation:sha256:[a-f0-9]{64}$'),
    dataset_class text NOT NULL CHECK (dataset_class = 'SYNTHETIC_WORK'),
    service_id text NOT NULL CHECK (length(service_id) BETWEEN 1 AND 64),
    token_scope text NOT NULL CHECK (token_scope = 'record'),
    application_id text NOT NULL CHECK (application_id ~ '^SW-APPLICATION-[0-9]{3}$'),
    company_id text NOT NULL CHECK (company_id ~ '^SW-COMPANY-[0-9]{3}$'),
    product_key text NOT NULL CHECK (product_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    application_source_hash text NOT NULL CHECK (application_source_hash ~ '^sha256:[a-f0-9]{64}$'),
    business_date date NOT NULL,
    status text NOT NULL CHECK (status IN ('READY', 'PARTIAL', 'HOLD')),
    preparation_complete boolean NOT NULL,
    assembler_version text NOT NULL CHECK (assembler_version ~ '^preparation-assembler-v[1-9][0-9]*$'),
    messages_hash text NOT NULL CHECK (messages_hash ~ '^sha256:[a-f0-9]{64}$'),
    family_mapping_hash text NOT NULL CHECK (family_mapping_hash ~ '^sha256:[a-f0-9]{64}$'),
    section_count integer NOT NULL CHECK (section_count >= 1),
    required_hold_count integer NOT NULL CHECK (required_hold_count >= 0),
    consultation_id text CHECK (consultation_id IS NULL OR length(consultation_id) BETWEEN 1 AND 64),
    content_hash text NOT NULL CHECK (content_hash ~ '^sha256:[a-f0-9]{64}$'),
    recorded_at timestamptz NOT NULL,
    -- READY일 때만 준비 완료다. READY는 필수 준비 자료를 갖췄다는 뜻이며 상담·대출 결정의 완료가 아니다.
    CONSTRAINT consultation_preparation_complete_shape CHECK (
        (status = 'READY' AND preparation_complete AND required_hold_count = 0)
        OR (status <> 'READY' AND NOT preparation_complete))
);

COMMENT ON TABLE consultation_preparation IS
    'AI 서비스가 규칙으로 조립한 상담 준비안·보류의 기록. 저장 전 Core가 READY 섹션을 재확인했다는 증거이지 사용 허가가 아니다. 근거 원문과 메모는 저장하지 않는다.';

CREATE TABLE consultation_preparation_section (
    preparation_id text NOT NULL REFERENCES consultation_preparation(preparation_id),
    family_id text NOT NULL CHECK (family_id ~ '^SIN-[A-Z0-9-]+$'),
    required boolean NOT NULL,
    status text NOT NULL CHECK (status IN ('READY', 'HOLD')),
    hold_kind text CHECK (hold_kind IS NULL OR hold_kind IN ('CORE_DECISION', 'UNVERIFIED')),
    hold_claim_basis text CHECK (hold_claim_basis IS NULL OR hold_claim_basis IN ('CORE_REPORTED', 'SERVICE_REPORTED')),
    evaluated_at timestamptz,
    selected_notice_id text CHECK (selected_notice_id IS NULL OR selected_notice_id ~ '^SIN-[A-Z0-9-]+-V[1-9][0-9]*$'),
    approved_checklist_version_id text REFERENCES approved_checklist_version(approved_checklist_version_id),
    decision_id text REFERENCES human_review_decision(decision_id),
    item_rule_version_ids jsonb NOT NULL CHECK (jsonb_typeof(item_rule_version_ids) = 'array'),
    item_evidence_hashes jsonb NOT NULL CHECK (jsonb_typeof(item_evidence_hashes) = 'array'),
    blocking_reasons jsonb NOT NULL CHECK (jsonb_typeof(blocking_reasons) = 'array'),
    recheck_usable boolean,
    recheck_reasons jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(recheck_reasons) = 'array'),
    tool_response_hash text CHECK (tool_response_hash IS NULL OR tool_response_hash ~ '^sha256:[a-f0-9]{64}$'),
    PRIMARY KEY (preparation_id, family_id),
    -- READY 섹션은 version·결정 ID와 항목이 있고 보류 표시가 없다. HOLD 섹션은 둘 다 없고 보류 종류·근거와 사유가 있다.
    CONSTRAINT consultation_preparation_section_shape CHECK (
        (status = 'READY' AND hold_kind IS NULL AND hold_claim_basis IS NULL
            AND approved_checklist_version_id IS NOT NULL AND decision_id IS NOT NULL AND selected_notice_id IS NOT NULL
            AND jsonb_array_length(item_rule_version_ids) >= 1
            AND jsonb_array_length(item_rule_version_ids) = jsonb_array_length(item_evidence_hashes)
            AND jsonb_array_length(blocking_reasons) = 0)
        OR (status = 'HOLD' AND hold_kind IS NOT NULL AND hold_claim_basis IS NOT NULL
            AND approved_checklist_version_id IS NULL AND decision_id IS NULL
            AND jsonb_array_length(item_rule_version_ids) = 0
            AND jsonb_array_length(item_evidence_hashes) = 0
            AND jsonb_array_length(blocking_reasons) >= 1)),
    CONSTRAINT consultation_preparation_section_claim_basis CHECK (
        hold_kind IS NULL
        OR (hold_kind = 'CORE_DECISION' AND hold_claim_basis = 'CORE_REPORTED')
        OR (hold_kind = 'UNVERIFIED' AND hold_claim_basis = 'SERVICE_REPORTED'))
);

COMMENT ON TABLE consultation_preparation_section IS
    '준비안의 공문군별 섹션. READY는 Core가 저장 시점에 사용 가능과 항목·근거 일치를 직접 확인한 것, HOLD는 서비스가 보고한 보류와 Core가 저장 시점에 다시 본 상태(recheck_*)를 구분해 남긴 것이다. 기록은 보류 사유의 진실성을 보증하지 않는다.';

CREATE TABLE consultation_preparation_run (
    run_id text PRIMARY KEY CHECK (run_id ~ '^consultation-preparation-run:[a-f0-9]{32}$'),
    preparation_id text CHECK (preparation_id IS NULL OR preparation_id ~ '^consultation-preparation:sha256:[a-f0-9]{64}$'),
    service_id text NOT NULL CHECK (length(service_id) BETWEEN 1 AND 64),
    token_scope text NOT NULL CHECK (token_scope = 'record'),
    outcome text NOT NULL CHECK (outcome IN ('RECORDED', 'ALREADY_RECORDED', 'REJECTED', 'FAILED')),
    error_code text CHECK (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]+$'),
    trace_id text NOT NULL CHECK (length(trace_id) BETWEEN 1 AND 64),
    started_at timestamptz NOT NULL,
    finished_at timestamptz NOT NULL,
    -- 실행마다 달라지는 값의 추적: 섹션별 {family_id, status, evaluated_at, tool_response_hash, recheck_usable, recheck_reasons}.
    -- 준비안·섹션 행은 첫 기록 그대로 두고(덮어쓰기 없음) 재실행의 값은 여기에 실행 단위로 쌓인다.
    section_evaluations jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(section_evaluations) = 'array'),
    CHECK ((outcome IN ('RECORDED', 'ALREADY_RECORDED') AND error_code IS NULL AND preparation_id IS NOT NULL)
        OR (outcome IN ('REJECTED', 'FAILED') AND error_code IS NOT NULL))
);

COMMENT ON TABLE consultation_preparation_run IS
    '기록 요청 하나당 실행 기록 하나. 거부·실패도 남긴다. 같은 run_id 재전송은 거부된다. section_evaluations에 실행별 평가 시각·Tool 응답 해시·재확인 결과를 남긴다.';

DO $$
DECLARE
    target text;
BEGIN
    FOREACH target IN ARRAY ARRAY['consultation_family_mapping', 'consultation_preparation',
                                  'consultation_preparation_section', 'consultation_preparation_run'] LOOP
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
        ('maintenance_change_audit', 'guard_maintenance_change_audit_immutable', 'guard_maintenance_change_audit_truncate', 'trust_agent_guard_audit_log', false)
$$;

ALTER TABLE consultation_family_mapping OWNER TO trust_agent_migration;
ALTER TABLE consultation_preparation OWNER TO trust_agent_migration;
ALTER TABLE consultation_preparation_section OWNER TO trust_agent_migration;
ALTER TABLE consultation_preparation_run OWNER TO trust_agent_migration;
GRANT SELECT, INSERT ON consultation_family_mapping, consultation_preparation,
    consultation_preparation_section, consultation_preparation_run TO trust_agent_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON consultation_family_mapping, consultation_preparation,
    consultation_preparation_section, consultation_preparation_run TO trust_agent_maintenance;

ALTER FUNCTION trust_agent_protected_tables() OWNER TO trust_agent_audit_owner;
REVOKE ALL ON FUNCTION trust_agent_protected_tables() FROM PUBLIC;
