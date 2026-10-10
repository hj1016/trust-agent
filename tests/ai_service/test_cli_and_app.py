"""TASK-015 AC-05(종료 코드), AC-13(명령 한 번): CLI 종료 코드와 stdout JSON, FastAPI 진입점."""
import io
import json
import unittest
from datetime import datetime, timezone

from tests.ai_service.support import (
    PREPAYMENT, SELLER, FakeTransport, make_root, ok, prepayment_usable, problem, raise_timeout, raise_unavailable, seller_pending,
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

    def test_inbound_token_is_required_when_configured_and_grant_id_is_forwarded(self):
        import os
        from unittest import mock

        from fastapi.testclient import TestClient

        from ai_service import app as app_module

        transport = partial_transport()
        original_prepare = app_module.prepare

        def prepare_with_fake(application_id, business_date, consultation_id, *, settings, grant_id=None):
            return original_prepare(application_id, business_date, consultation_id, settings=settings, transport=transport, clock=CLOCK, grant_id=grant_id)

        environ = environ_for(self.root)
        environ[config.ENV_INBOUND_TOKEN] = "temporary-inbound-token"
        with mock.patch.dict(os.environ, environ, clear=False), mock.patch.object(app_module, "prepare", prepare_with_fake):
            client = TestClient(app_module.app)
            body = {"applicationId": "SW-APPLICATION-001", "businessDate": "2026-10-06", "grantId": "ai-grant:" + "a" * 32}
            missing = client.post("/api/v1/ai/consultation-preparations", json=body)
            self.assertEqual(401, missing.status_code)
            self.assertEqual("UNAUTHENTICATED", missing.json()["detail"]["code"])
            self.assertEqual([], transport.calls, "수신 인증 실패면 Core를 부르지 않는다")
            wrong = client.post("/api/v1/ai/consultation-preparations", json=body, headers={"Authorization": "Bearer wrong"})
            self.assertEqual(401, wrong.status_code)
            self.assertNotIn("temporary-inbound-token", wrong.text)
            ok = client.post("/api/v1/ai/consultation-preparations", json=body, headers={"Authorization": "Bearer temporary-inbound-token"})
            self.assertEqual(200, ok.status_code, ok.text)
            self.assertEqual(6, len(transport.calls))
            self.assertTrue(all(h.get("X-TrustAgent-Grant") == "ai-grant:" + "a" * 32 for h in transport.headers_seen), "grant 헤더가 Tool·기록 호출 전부에 실린다")
            self.assertNotIn("ai-grant:", ok.text, "grant ID는 준비안 본문에 들어가지 않는다")
        with mock.patch.dict(os.environ, environ_for(self.root), clear=False), mock.patch.object(app_module, "prepare", prepare_with_fake):
            transport.calls.clear(); transport.headers_seen.clear()
            no_grant = TestClient(app_module.app).post("/api/v1/ai/consultation-preparations", json={"applicationId": "SW-APPLICATION-001", "businessDate": "2026-10-06"})
            self.assertEqual(200, no_grant.status_code)
            self.assertTrue(all("X-TrustAgent-Grant" not in h for h in transport.headers_seen), "grant 없이(로컬 CLI 모드) 헤더를 보내지 않는다")

    def test_http_entry_point_returns_preparation_and_maps_errors(self):
        import os
        from unittest import mock

        from fastapi.testclient import TestClient

        from ai_service import app as app_module

        transport = partial_transport()
        original_prepare = app_module.prepare

        def prepare_with_fake(application_id, business_date, consultation_id, *, settings, grant_id=None):
            return original_prepare(application_id, business_date, consultation_id, settings=settings, transport=transport, clock=CLOCK, grant_id=grant_id)

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

            # 기록 실패는 본문(recorded=false, 원인 코드, 사용 금지 안내)을 그대로 두고 HTTP 상태로 원인을 구분한다.
            failure_cases = [
                (lambda body: problem(422, "PREPARATION_NOT_USABLE"), 422, "REJECTED", "PREPARATION_NOT_USABLE"),
                (lambda body: problem(409, "PREPARATION_STALE"), 409, "REJECTED", "PREPARATION_STALE"),
                (lambda body: problem(400, "PREPARATION_ID_MISMATCH"), 500, "REJECTED", "PREPARATION_ID_MISMATCH"),
                (lambda body: problem(401, "UNAUTHENTICATED"), 503, "REJECTED", "UNAUTHENTICATED"),
                (lambda body: problem(500, "RECORD_WRITE_FAILED"), 502, "FAILED", "CORE_ERROR_RESPONSE"),
                (lambda body: raise_unavailable(), 502, "FAILED", "CORE_UNAVAILABLE"),
                (lambda body: raise_timeout(), 504, "FAILED", "CORE_TIMEOUT"),
            ]
            for record, http_status, status, code in failure_cases:
                with self.subTest(code=code):
                    transport.record = record
                    failed = client.post("/api/v1/ai/consultation-preparations",
                                         json={"applicationId": "SW-APPLICATION-001", "businessDate": "2026-10-06"})
                    self.assertEqual(http_status, failed.status_code, failed.text)
                    self.assertEqual("false", failed.headers.get("x-preparation-recorded"))
                    body = failed.json()
                    self.assertEqual("PARTIAL", body["status"], "준비안 본문은 그대로다")
                    self.assertEqual((False, status, code), (body["record"]["recorded"], body["record"]["status"], body["record"]["error_code"]))
                    self.assertIn("사용하지 마세요", body["notices"]["usage_notice"])
                    self.assertNotIn("temporary-record-token", failed.text)
                    self.assertNotIn("temporary-tool-token", failed.text)
        # 기록 토큰 미설정(운영 설정 문제)은 503이며 본문은 NOT_ATTEMPTED다.
        with mock.patch.dict(os.environ, environ_for(self.root, record_token=None), clear=False), \
                mock.patch.object(app_module, "prepare", prepare_with_fake):
            transport.record = partial_transport().record
            not_attempted = TestClient(app_module.app).post("/api/v1/ai/consultation-preparations",
                                                             json={"applicationId": "SW-APPLICATION-001", "businessDate": "2026-10-06"})
            self.assertEqual(503, not_attempted.status_code, not_attempted.text)
            self.assertEqual(("false", False, "NOT_ATTEMPTED"), (not_attempted.headers.get("x-preparation-recorded"),
                                                                not_attempted.json()["record"]["recorded"], not_attempted.json()["record"]["status"]))
        with mock.patch.dict(os.environ, {config.ENV_CORE_BASE_URL: "", config.ENV_TOOL_TOKEN: ""}, clear=False):
            unavailable = TestClient(app_module.app).post("/api/v1/ai/consultation-preparations", json={"applicationId": "SW-APPLICATION-001"})
            self.assertEqual(503, unavailable.status_code)


if __name__ == "__main__":
    unittest.main()
