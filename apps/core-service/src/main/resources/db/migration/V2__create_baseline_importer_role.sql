-- 보호 테이블을 추가하는 후속 migration은 반드시 다음 순서를 지킵니다.
-- verify_audit_guard_state가 ddl_command_end에서 상태를 검사하므로
-- trust_agent_protected_tables()를 먼저 갱신하면 CREATE TABLE 시점에 실패합니다.
-- 1) CREATE TABLE  2) append_only trigger  3) truncate trigger
-- 4) trust_agent_protected_tables() 갱신  5) OWNER 변경
-- 이미 적용된 migration은 Flyway checksum이 바뀌므로 이 주석을 V1에 소급하지 않습니다.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'trust_agent_importer') THEN
        CREATE ROLE trust_agent_importer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO trust_agent_importer;

GRANT SELECT, INSERT ON public_product, public_snapshot, collection_attempt,
    public_observation, product_terms_version, product_term_fact, version_evidence,
    fact_evidence_locator, observed_rate_quote, extraction_attempt,
    change_detection_result, change_detection_classification, baseline_import_run
    TO trust_agent_importer;
