-- V13: 상담 화면 '추가 질문'(근거 검색 + RAG 설명)의 감사 기록.
-- 질문 원문은 저장하지 않는다(sha256만). 요청 ID, 수행 사용자·역할, 근거 version, 처리 결과, 보류 이유, 지연, LLM 사용량을 남긴다.
-- 이 기록은 직원 근거 확인(consultation_confirmation)이나 업무 승인이 아니다.

CREATE TABLE consultation_answer_audit (
    answer_request_id text PRIMARY KEY CHECK (answer_request_id ~ '^consultation-answer:[a-f0-9]{32}$'),
    consultation_id text NOT NULL REFERENCES consultation(consultation_id),
    family_id text NOT NULL CHECK (family_id ~ '^SIN-[A-Z0-9-]+$'),
    business_date date NOT NULL,
    requested_by text NOT NULL CHECK (requested_by ~ '^[A-Z][A-Z0-9-]{1,63}$'),
    active_role text NOT NULL CHECK (active_role = 'STAFF'),
    question_sha256 text NOT NULL CHECK (question_sha256 ~ '^sha256:[a-f0-9]{64}$'),
    question_length integer NOT NULL CHECK (question_length BETWEEN 1 AND 200),
    status text NOT NULL CHECK (status IN ('ANSWERED', 'EVIDENCE_HOLD', 'DECISION_DECLINED', 'ANSWER_HOLD', 'AI_UNAVAILABLE')),
    reasons jsonb NOT NULL CHECK (jsonb_typeof(reasons) = 'array'),
    evidence_rule_version_ids jsonb NOT NULL CHECK (jsonb_typeof(evidence_rule_version_ids) = 'array'),
    cited_rule_version_ids jsonb NOT NULL CHECK (jsonb_typeof(cited_rule_version_ids) = 'array'),
    search_version text,
    rag_version text,
    model text,
    llm_calls integer NOT NULL CHECK (llm_calls >= 0),
    input_tokens integer NOT NULL CHECK (input_tokens >= 0),
    output_tokens integer NOT NULL CHECK (output_tokens >= 0),
    charged_usd_estimate numeric(12, 6) NOT NULL CHECK (charged_usd_estimate >= 0),
    latency_ms integer NOT NULL CHECK (latency_ms >= 0),
    requested_at timestamptz NOT NULL,
    trace_id text NOT NULL CHECK (length(trace_id) BETWEEN 1 AND 64)
);

COMMENT ON TABLE consultation_answer_audit IS '상담 화면 추가 질문의 처리 기록. 질문 원문 없음(해시만). 직원 근거 확인·업무 승인·고객별 적용 확정이 아니다. charged_usd_estimate는 보수적 추정 단가 기준이며 실제 청구액이 아니다.';
CREATE INDEX consultation_answer_audit_consultation_idx ON consultation_answer_audit (consultation_id, requested_at DESC);

CREATE TRIGGER guard_consultation_answer_audit_append_only BEFORE UPDATE OR DELETE ON consultation_answer_audit FOR EACH ROW EXECUTE FUNCTION trust_agent_guard_append_only();
CREATE TRIGGER guard_consultation_answer_audit_truncate BEFORE TRUNCATE ON consultation_answer_audit FOR EACH STATEMENT EXECUTE FUNCTION trust_agent_guard_truncate();

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
        ('consultation_answer_audit', 'guard_consultation_answer_audit_append_only', 'guard_consultation_answer_audit_truncate', 'trust_agent_guard_append_only', true),
        ('maintenance_change_audit', 'guard_maintenance_change_audit_immutable', 'guard_maintenance_change_audit_truncate', 'trust_agent_guard_audit_log', false)
$$;

ALTER TABLE consultation_answer_audit OWNER TO trust_agent_migration;
GRANT SELECT, INSERT ON consultation_answer_audit TO trust_agent_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON consultation_answer_audit TO trust_agent_maintenance;
