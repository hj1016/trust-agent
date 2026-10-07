"""TASK-015 AC-05(종료 코드), AC-13(명령 한 번): CLI 종료 코드와 stdout JSON, FastAPI 진입점."""
import io
import json
import unittest
from datetime import datetime, timezone

from tests.ai_service.support import (
    PREPAYMENT, SELLER, FakeTransport, make_root, ok, prepayment_usable, problem, seller_pending,
)

from ai_service import config  # noqa: E402
from ai_service.cli import EXIT_INPUT_ERROR, EXIT_NOT_RECORDED, EXIT_RECORDED, EXIT_SETTINGS_ERROR, main  # noqa: E402

CLOCK = lambda: datetime(2026, 10, 6, 3, 0, 0, tzinfo=timezone.utc)  # noqa: E731


def environ_for(root, record_token="temporary-record-token"):
    env = {config.ENV_CORE_BASE_URL: "http://core.test", config.ENV_TOOL_TOKEN: "temporary-tool-token",
           config.ENV_REPOSITORY_ROOT: str(root)}
    if record_token:
        env[config.ENV_RECORD_TOKEN] = record_token
    return env


def partial_transport():
    return FakeTransport().usable(PREPAYMENT, prepayment_usable()).respond(SELLER, lambda: ok(seller_pending()))


class CliTest(unittest.TestCase):
    def setUp(self):
        self.root = make_root()

    def run_cli(self, args, environ, transport):
        out, err = io.StringIO(), io.StringIO()
        code = main(args, environ=environ, stdout=out, stderr=err, transport=transport, clock=CLOCK)
        return code, out.getvalue(), err.getvalue()

    def test_recorded_preparation_exits_zero_and_prints_json(self):
        code, out, err = self.run_cli(["prepare", "--application", "SW-APPLICATION-001", "--business-date", "2026-10-06",
                                      "--consultation-id", "demo-1"], environ_for(self.root), partial_transport())
        self.assertEqual(EXIT_RECORDED, code, err)
        preparation = json.loads(out)
        self.assertEqual("PARTIAL", preparation["status"])
        self.assertEqual("RECORDED", preparation["record"]["status"])
        self.assertTrue(preparation["record"]["recorded"])
        self.assertEqual("demo-1", preparation["consultation_id"])
        self.assertNotIn("temporary-tool-token", out + err)
        self.assertNotIn("temporary-record-token", out + err)

    def test_rejected_record_exits_three_with_preparation_and_warning(self):
        transport = partial_transport()
        transport.record = lambda body: problem(422, "PREPARATION_NOT_USABLE")
        code, out, err = self.run_cli(["prepare", "--application", "SW-APPLICATION-001"], environ_for(self.root), transport)
        self.assertEqual(EXIT_NOT_RECORDED, code)
        self.assertEqual("REJECTED", json.loads(out)["record"]["status"])
        self.assertIn("사용하지 말고", err)
        self.assertIn("PREPARATION_NOT_USABLE", err)

    def test_missing_record_token_exits_three(self):
        code, out, _ = self.run_cli(["prepare", "--application", "SW-APPLICATION-001"], environ_for(self.root, record_token=None), partial_transport())
        self.assertEqual(EXIT_NOT_RECORDED, code)
        self.assertEqual("NOT_ATTEMPTED", json.loads(out)["record"]["status"])

    def test_input_error_exits_two_without_json(self):
        code, out, err = self.run_cli(["prepare", "--application", "SW-APPLICATION-404"], environ_for(self.root), partial_transport())
        self.assertEqual(EXIT_INPUT_ERROR, code)
        self.assertEqual("", out)
        self.assertIn("APPLICATION_NOT_FOUND", err)

    def test_missing_settings_exits_four_without_output(self):
        code, out, err = self.run_cli(["prepare", "--application", "SW-APPLICATION-001"], {}, partial_transport())
        self.assertEqual(EXIT_SETTINGS_ERROR, code)
        self.assertEqual("", out)
        self.assertIn(config.ENV_CORE_BASE_URL, err)
        self.assertIn(config.ENV_TOOL_TOKEN, err)

    def test_repository_root_argument_overrides_environment(self):
        environ = environ_for(self.root)
        environ[config.ENV_REPOSITORY_ROOT] = "/nonexistent/root"
        code, out, _ = self.run_cli(["prepare", "--application", "SW-APPLICATION-001", "--repository-root", str(self.root)],
                                    environ, partial_transport())
        self.assertEqual(EXIT_RECORDED, code)
        self.assertEqual("SW-APPLICATION-001", json.loads(out)["application"]["application_id"])


class AppTest(unittest.TestCase):
    def setUp(self):
        try:
            from fastapi.testclient import TestClient  # noqa: F401
            import httpx  # noqa: F401
        except ImportError:
            self.skipTest("fastapi 또는 httpx가 설치되지 않았습니다.")
        self.root = make_root()

    def test_http_entry_point_returns_preparation_and_maps_errors(self):
        import os
        from unittest import mock

        from fastapi.testclient import TestClient

        from ai_service import app as app_module

        transport = partial_transport()
        original_prepare = app_module.prepare

        def prepare_with_fake(application_id, business_date, consultation_id, *, settings):
            return original_prepare(application_id, business_date, consultation_id, settings=settings, transport=transport, clock=CLOCK)

        with mock.patch.dict(os.environ, environ_for(self.root), clear=False), \
                mock.patch.object(app_module, "prepare", prepare_with_fake):
            client = TestClient(app_module.app)
            response = client.post("/api/v1/ai/consultation-preparations",
                                   json={"applicationId": "SW-APPLICATION-001", "businessDate": "2026-10-06"})
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual("PARTIAL", response.json()["status"])
            self.assertEqual("true", response.headers.get("x-preparation-recorded"))
            self.assertTrue(response.json()["record"]["recorded"])
            bad = client.post("/api/v1/ai/consultation-preparations", json={"applicationId": "SW-APPLICATION-404"})
            self.assertEqual(400, bad.status_code)
            self.assertEqual("APPLICATION_NOT_FOUND", bad.json()["detail"]["code"])
        with mock.patch.dict(os.environ, {config.ENV_CORE_BASE_URL: "", config.ENV_TOOL_TOKEN: ""}, clear=False):
            unavailable = TestClient(app_module.app).post("/api/v1/ai/consultation-preparations", json={"applicationId": "SW-APPLICATION-001"})
            self.assertEqual(503, unavailable.status_code)


if __name__ == "__main__":
    unittest.main()
