-- 제어 DB(ADR-014): 사용자·역할·보안 사건. 업무 표와 분리된 데이터베이스에 두며 Flyway history도 별도(flyway_control_schema_history)다.
-- app_user·app_user_role은 원장이 아니라 설정이므로 갱신을 허용한다. security_event는 append-only다.

CREATE TABLE app_user (
    user_id text PRIMARY KEY CHECK (user_id ~ '^[A-Z][A-Z0-9-]{1,63}$'),
    password_hash text NOT NULL CHECK (length(password_hash) > 0),
    display_name text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 128),
    synthetic boolean NOT NULL DEFAULT true,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

COMMENT ON TABLE app_user IS '합성 직원 계정. 비밀번호는 bcrypt 해시만 저장하며 실제 행원 인증이 아니다. 가입·재설정·관리 화면은 없다.';

CREATE TABLE app_user_role (
    user_id text NOT NULL REFERENCES app_user(user_id),
    role text NOT NULL CHECK (role IN ('STAFF', 'REVIEWER')),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE security_event (
    event_id text PRIMARY KEY CHECK (event_id ~ '^security-event:[a-f0-9]{32}$'),
    occurred_at timestamptz NOT NULL,
    event_type text NOT NULL CHECK (event_type ~ '^[A-Z][A-Z_]+$'),
    principal text CHECK (principal IS NULL OR length(principal) BETWEEN 1 AND 64),
    active_role text CHECK (active_role IS NULL OR active_role IN ('STAFF', 'REVIEWER')),
    workspace_id text NOT NULL CHECK (length(workspace_id) BETWEEN 1 AND 64),
    http_method text NOT NULL CHECK (length(http_method) BETWEEN 1 AND 16),
    path text NOT NULL CHECK (length(path) BETWEEN 1 AND 512),
    outcome text NOT NULL CHECK (outcome ~ '^[A-Z][A-Z0-9_]+$'),
    trace_id text NOT NULL CHECK (length(trace_id) BETWEEN 1 AND 64),
    detail text CHECK (detail IS NULL OR length(detail) <= 512)
);

COMMENT ON TABLE security_event IS '로그인 성공·실패, 로그아웃, 역할 전환, 비인증·권한 거부, CSRF 거부. 본문·토큰·비밀번호는 기록하지 않는다. append-only.';

CREATE OR REPLACE FUNCTION trust_agent_control_guard_append_only()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'append-only table % does not allow %', TG_TABLE_NAME, TG_OP USING ERRCODE = '42501';
END
$$;

CREATE OR REPLACE FUNCTION trust_agent_control_guard_truncate()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'append-only table % does not allow TRUNCATE', TG_TABLE_NAME USING ERRCODE = '42501';
END
$$;

CREATE TRIGGER guard_security_event_append_only BEFORE UPDATE OR DELETE ON security_event
    FOR EACH ROW EXECUTE FUNCTION trust_agent_control_guard_append_only();
CREATE TRIGGER guard_security_event_truncate BEFORE TRUNCATE ON security_event
    FOR EACH STATEMENT EXECUTE FUNCTION trust_agent_control_guard_truncate();

CREATE INDEX security_event_occurred_at_idx ON security_event (occurred_at);
CREATE INDEX security_event_principal_idx ON security_event (principal, occurred_at);
