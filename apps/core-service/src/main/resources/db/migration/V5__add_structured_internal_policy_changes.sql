ALTER TABLE internal_policy_rule_version
    ADD COLUMN structured_change jsonb NOT NULL DEFAULT 'null'::jsonb,
    ADD CONSTRAINT internal_policy_rule_structured_change_type_check
        CHECK (jsonb_typeof(structured_change) IN ('object', 'null'));

COMMENT ON COLUMN internal_policy_rule_version.structured_change IS
    '검수 화면과 상담 지원이 공유하는 변경 전후 값, 적용일, 조건과 예외의 구조화 표현';
