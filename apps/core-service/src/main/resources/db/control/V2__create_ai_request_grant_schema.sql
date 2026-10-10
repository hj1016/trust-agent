-- 제어 DB V2 (ADR-014 7·9·9-1항): AI 요청 승인(grant)과 사용 기록.
-- ai_request_grant는 상태가 바뀌는 표(ISSUED → CONSUMING → CONSUMED, 또는 EXPIRED). ai_request_grant_use는 append-only다.

CREATE TABLE ai_request_grant (
    grant_id text PRIMARY KEY CHECK (grant_id ~ '^ai-grant:[a-f0-9]{32}$'),
    workspace_id text NOT NULL CHECK (length(workspace_id) BETWEEN 1 AND 64),
    consultation_id text NOT NULL CHECK (length(consultation_id) BETWEEN 1 AND 64),
    application_id text NOT NULL CHECK (application_id ~ '^SW-APPLICATION-[0-9]{3}$'),
    allowed_family_ids jsonb NOT NULL CHECK (jsonb_typeof(allowed_family_ids) = 'array'),
    business_date date NOT NULL,
    user_id text NOT NULL CHECK (length(user_id) BETWEEN 1 AND 64),
    active_role text NOT NULL CHECK (active_role IN ('STAFF', 'REVIEWER')),
    issued_at timestamptz NOT NULL,
    ttl_seconds integer NOT NULL CHECK (ttl_seconds > 0),
    expires_at timestamptz NOT NULL,
    state text NOT NULL CHECK (state IN ('ISSUED', 'CONSUMING', 'CONSUMED', 'EXPIRED')),
    read_calls integer NOT NULL DEFAULT 0 CHECK (read_calls >= 0),
    read_call_limit integer NOT NULL CHECK (read_call_limit > 0),
    record_run_id text CHECK (record_run_id IS NULL OR record_run_id ~ '^consultation-preparation-run:[a-f0-9]{32}$'),
    record_preparation_id text CHECK (record_preparation_id IS NULL OR record_preparation_id ~ '^consultation-preparation:sha256:[a-f0-9]{64}$'),
    consumed_run_id text,
    updated_at timestamptz NOT NULL,
    CHECK (state <> 'CONSUMING' OR record_run_id IS NOT NULL)
);

COMMENT ON TABLE ai_request_grant IS 'Core가 사용자·상담 건 검사를 통과한 요청마다 발급하는 AI 요청 승인. 서비스 토큰과 함께 Tool·기록 경로에서 검사한다. 발급 시점 TTL을 행에 기록한다.';
COMMENT ON COLUMN ai_request_grant.record_run_id IS '기록 시작(CONSUMING) 때의 run_id. 커밋 여부가 불확실하면 대조가 이 값으로 업무 DB 실행 기록을 찾는다.';
COMMENT ON COLUMN ai_request_grant.record_preparation_id IS '기록 시작 때 본문의 preparation_id. 실행 기록 쓰기가 커밋 뒤 실패한 경우 대조가 준비안 행으로 커밋을 확인한다.';
CREATE INDEX ai_request_grant_state_idx ON ai_request_grant (state, expires_at);

CREATE TABLE ai_request_grant_use (
    use_id text PRIMARY KEY CHECK (use_id ~ '^ai-grant-use:[a-f0-9]{32}$'),
    grant_id text NOT NULL REFERENCES ai_request_grant(grant_id),
    used_at timestamptz NOT NULL,
    kind text NOT NULL CHECK (kind IN ('TOOL', 'RECORD_BEGIN', 'RECORD_COMPLETE', 'RECORD_COMPLETE_FAILED', 'RECORD_RELEASE', 'RECORD_UNCERTAIN', 'RECONCILE')),
    tool_name text CHECK (tool_name IS NULL OR length(tool_name) BETWEEN 1 AND 64),
    outcome text NOT NULL CHECK (outcome ~ '^[A-Z][A-Z0-9_]+$'),
    trace_id text NOT NULL CHECK (length(trace_id) BETWEEN 1 AND 64)
);

COMMENT ON TABLE ai_request_grant_use IS 'grant 사용 기록(append-only). 거부도 남긴다.';
CREATE INDEX ai_request_grant_use_grant_idx ON ai_request_grant_use (grant_id, used_at);

CREATE TRIGGER guard_ai_request_grant_use_append_only BEFORE UPDATE OR DELETE ON ai_request_grant_use
    FOR EACH ROW EXECUTE FUNCTION trust_agent_control_guard_append_only();
CREATE TRIGGER guard_ai_request_grant_use_truncate BEFORE TRUNCATE ON ai_request_grant_use
    FOR EACH STATEMENT EXECUTE FUNCTION trust_agent_control_guard_truncate();
