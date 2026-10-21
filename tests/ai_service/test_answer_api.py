"""답변 HTTP 경계: 인증 전 검색 금지, grant 전달, 내부 초안 응답 제외."""
import copy
import unittest
from types import SimpleNamespace
from unittest import mock

import tests.ai_service.support  # noqa: F401
from fastapi.testclient import TestClient
from ai_service import app as api


class AnswerApiTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(api.app)
        self.body = {"question": "수수료 조건은?", "familyId": "SIN-PREPAYMENT-FEE", "businessDate": "2026-10-06",
                     "consultationId": "consultation:test", "grantId": "ai-grant:test"}
        self.settings = SimpleNamespace(inbound_token="test-answer-inbound")
        self.es = SimpleNamespace(base_url="http://127.0.0.1:1", username="test", password="test", timeout_seconds=1)
        self.patches = [mock.patch.object(api, "load_settings", return_value=self.settings),
                        mock.patch.object(api, "load_es_settings", return_value=self.es),
                        mock.patch.object(api, "load_search_settings", return_value=None)]
        for patch in self.patches:
            patch.start()
            self.addCleanup(patch.stop)

    def test_missing_or_wrong_token_never_searches(self):
        with mock.patch.object(api, "search") as search:
            for headers in ({}, {"Authorization": "Bearer wrong"}):
                response = self.client.post("/api/v1/ai/answers", json=self.body, headers=headers)
                self.assertEqual(401, response.status_code)
            search.assert_not_called()

    def test_forwards_grant_and_strips_internal_draft(self):
        found = {"business_date": "2026-10-06", "family_id": "SIN-PREPAYMENT-FEE", "search_version": "test-search"}
        result = {"status": "ANSWER_HOLD", "answer": None, "_eval": {"draft": {"explanation": "검증에 실패한 내부 초안"}}}
        with mock.patch.object(api, "search", return_value=found) as search, \
                mock.patch.object(api.llm, "gateway_for_service", return_value=None), \
                mock.patch.object(api.rag, "answer", return_value=copy.deepcopy(result)), \
                mock.patch.object(api.eval_trace, "trace_dir", return_value=None):
            response = self.client.post("/api/v1/ai/answers", json=self.body,
                                        headers={"Authorization": "Bearer test-answer-inbound"})
        self.assertEqual(200, response.status_code)
        self.assertEqual("ai-grant:test", search.call_args.kwargs["grant_id"])
        self.assertNotIn("_eval", response.json())
        self.assertNotIn("검증에 실패한 내부 초안", response.text)
        self.assertNotIn("ai-grant:test", response.text)
        self.assertEqual("ANSWER_HOLD", response.json()["status"])

    def test_extra_field_is_rejected_before_search(self):
        with mock.patch.object(api, "search") as search:
            response = self.client.post("/api/v1/ai/answers", json={**self.body, "actor": "forged"},
                                        headers={"Authorization": "Bearer test-answer-inbound"})
            self.assertEqual(422, response.status_code)
            search.assert_not_called()
