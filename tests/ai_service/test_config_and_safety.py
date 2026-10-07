"""TASK-015 AC-11(SAFE-A·B·D·E·F), AC-12: 설정에 DB 자격증명 없음, 고정 문구에 결정 표현 없음, 사유 코드 표 완전성, 계약의 금지 필드."""
import re
import unittest

from tests.ai_service.support import AI_SERVICE_DIR, ROOT, load_json

from ai_service import config, messages  # noqa: E402
from ai_service.config import SettingsError, load_settings  # noqa: E402

# 결정을 내리는 서술. "승인·거절·한도·금리·신용등급은 이 준비안이 정하지 않는다"처럼 결정하지 않음을 밝히는 문장은 허용한다.
FORBIDDEN_PHRASES = ("승인합니다", "거절합니다", "승인 가능", "거절 권고", "승인해도 됩니다", "대출을 승인", "대출을 거절",
                     "확정합니다", "확정했습니다", "한도를 정", "금리를 정", "신용등급을 정", "한도는 ", "금리는 ")
FORBIDDEN_FIELD_NAMES = ("decision", "approved", "rejected", "approval", "rejection", "limit", "credit_grade", "credit_score",
                         "rate_decision", "interest_rate", "recommendation")
CORE_SERVICE_SOURCE = ROOT / "apps/core-service/src/main/java/com/trustagent/core/internalpolicy/query/InternalPolicyApplicableService.java"


def field_names(schema: dict) -> set:
    names = set()

    def walk(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "properties" and isinstance(value, dict):
                    names.update(value.keys())
                walk(value)
        elif isinstance(node, list):
            for child in node:
                walk(child)

    walk(schema)
    return names


class SettingsTest(unittest.TestCase):
    def test_missing_required_settings_is_a_start_refusal_without_token_values(self):
        with self.assertRaises(SettingsError) as raised:
            load_settings({})
        self.assertEqual("SETTINGS_MISSING", raised.exception.code)
        self.assertEqual([config.ENV_CORE_BASE_URL, config.ENV_TOOL_TOKEN], raised.exception.missing)
        with self.assertRaises(SettingsError):
            load_settings({config.ENV_CORE_BASE_URL: "http://core", config.ENV_TOOL_TOKEN: "t", config.ENV_TIMEOUT_SECONDS: "abc"})

    def test_settings_do_not_expose_token_values(self):
        settings = load_settings({config.ENV_CORE_BASE_URL: "http://core/", config.ENV_TOOL_TOKEN: "tool-secret-value",
                                  config.ENV_RECORD_TOKEN: "record-secret-value"})
        masked = settings.masked()
        self.assertNotIn("tool-secret-value", str(masked))
        self.assertNotIn("record-secret-value", str(masked))
        self.assertTrue(masked["record_token_configured"])
        self.assertEqual("http://core", settings.core_base_url)
        self.assertIsNone(load_settings({config.ENV_CORE_BASE_URL: "http://core", config.ENV_TOOL_TOKEN: "t"}).record_token)

    def test_no_database_credential_settings_or_drivers(self):
        for name in config.ENV_NAMES:
            self.assertNotRegex(name, r"DB|DATABASE|JDBC|POSTGRES|PASSWORD|SQL")
        requirements = "\n".join(line.split("#", 1)[0].strip().lower()
                                 for line in (AI_SERVICE_DIR / "requirements-ai.txt").read_text(encoding="utf-8").splitlines())
        for driver in ("psycopg", "sqlalchemy", "asyncpg", "pg8000", "oracledb", "cx_oracle", "pymysql"):
            self.assertNotIn(driver, requirements)
        for path in (AI_SERVICE_DIR / "ai_service").glob("*.py"):
            source = path.read_text(encoding="utf-8")
            self.assertNotRegex(source, r"import (psycopg|sqlalchemy|asyncpg|pg8000)", str(path))
            self.assertNotIn("jdbc:", source, str(path))


class SafetyTest(unittest.TestCase):
    def test_message_table_has_no_decision_phrases(self):
        table = messages.message_table()
        flattened = " ".join(value for group in table.values() for value in group.values())
        for phrase in FORBIDDEN_PHRASES:
            self.assertNotIn(phrase, flattened, phrase)
        self.assertIn("담당자가 판단", table["notices"]["human_decision_notice"])
        self.assertTrue(messages.messages_hash().startswith("sha256:"))
        self.assertIn("끝나지 않았습니다", table["headlines"]["PARTIAL"])
        self.assertIn("끝나지 않았습니다", table["headlines"]["HOLD"])
        self.assertIn("상담이나 대출 결정의 완료가 아닙니다", table["headlines"]["READY"])

    def test_reason_table_covers_every_core_blocking_and_warning_code(self):
        source = CORE_SERVICE_SOURCE.read_text(encoding="utf-8")
        core_codes = set(re.findall(r'(?:blockingReasons|warningReasons)\.add\("([A-Z_]+)"\)', source))
        self.assertTrue(core_codes, "Core 사유 코드를 찾지 못했습니다")
        missing = core_codes - set(messages.CORE_REASON_MESSAGES)
        self.assertEqual(set(), missing)
        self.assertEqual(("TOOL_AUTH_FAILED", "CORE_UNAVAILABLE", "CORE_TIMEOUT", "EVIDENCE_UNAVAILABLE", "TOOL_RESPONSE_INVALID"),
                         messages.SERVICE_REASON_CODES)
        self.assertIn("UNKNOWN_CODE_X", messages.reason_message("UNKNOWN_CODE_X"))

    def test_service_reason_codes_match_record_contract_allowlist(self):
        schema = load_json(ROOT / "contracts/consultation-preparation-record.schema.json")
        allowed = None
        for rule in schema["$defs"]["section"]["allOf"]:
            then = rule.get("then", {}).get("properties", {}).get("blocking_reasons", {})
            if "items" in then and "enum" in then["items"]:
                allowed = then["items"]["enum"]
        self.assertEqual(sorted(messages.SERVICE_REASON_CODES), sorted(allowed))

    def test_contracts_forbid_decision_fields_and_extra_properties(self):
        for name in ("consultation-preparation.schema.json", "consultation-preparation-record.schema.json"):
            schema = load_json(ROOT / "contracts" / name)
            self.assertFalse(schema["additionalProperties"], name)
            names = {field.lower() for field in field_names(schema)}
            for forbidden in FORBIDDEN_FIELD_NAMES:
                self.assertNotIn(forbidden, names, f"{name}: {forbidden}")
            # decision_id는 사람 검토 결정의 참조 ID이지 AI의 결정이 아니다. 그 밖에 'decision'으로 끝나는 필드는 없다.
            for field in names:
                if field.endswith("decision"):
                    self.fail(f"{name}: 결정 필드처럼 읽히는 이름 {field}")
        output_schema = load_json(ROOT / "contracts/consultation-preparation.schema.json")
        self.assertIn("human_decision_notice", output_schema["properties"]["notices"]["required"])


if __name__ == "__main__":
    unittest.main()
