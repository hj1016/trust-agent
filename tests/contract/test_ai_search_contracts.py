"""TASK-016 검색 API 계약 테스트. 요청 schema(AC-07)와, 가짜 ES·가짜 Core로 만든 실제 응답이 응답 schema에 맞는지(AC-04·09) 확인한다."""
from __future__ import annotations

import json
import unittest
from datetime import datetime, timezone
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

from tests.ai_service.support import PREPAYMENT, FakeTransport, make_root, prepayment_usable, settings_for
from tests.ai_service.test_search import FakeEs, no_hold

from ai_service.es_client import SearchUnavailable  # noqa: E402
from ai_service.search import RelevanceHold, SearchSettings, search  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CLOCK = lambda: datetime(2026, 10, 6, 3, 0, tzinfo=timezone.utc)  # noqa: E731


def validator(name: str) -> Draft202012Validator:
    with (ROOT / "contracts" / name).open(encoding="utf-8") as source:
        schema = json.load(source)
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FormatChecker())


class AiSearchContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.request = validator("ai-search-request.schema.json")
        cls.response = validator("ai-search-response.schema.json")
        cls.root = make_root()
        cls.settings = settings_for(cls.root, record_token=None)
        cls.checklist = prepayment_usable()
        cls.rule_ids = [item["sourceRuleVersionId"] for item in cls.checklist["approvedChecklist"]["items"]]

    def test_request_schema_accepts_minimal_and_rejects_unknown_fields_and_ranges(self):
        self.assertEqual([], list(self.request.iter_errors({"query": "수수료", "familyId": PREPAYMENT})))
        self.assertEqual([], list(self.request.iter_errors({"query": "수수료", "familyId": PREPAYMENT, "businessDate": "2026-10-06", "consultationId": "c", "topK": 10})))
        for bad in ({"query": "수수료"}, {"query": "", "familyId": PREPAYMENT}, {"query": "가" * 201, "familyId": PREPAYMENT},
                    {"query": "수수료", "familyId": PREPAYMENT, "topK": 11}, {"query": "수수료", "familyId": PREPAYMENT, "topK": 0},
                    {"query": "수수료", "familyId": PREPAYMENT, "product": "x"}, {"query": "수수료", "familyId": "seller"},
                    {"query": "수수료", "familyId": PREPAYMENT, "businessDate": "2026/10/06"}):
            with self.subTest(bad=bad):
                self.assertTrue(list(self.request.iter_errors(bad)))

    def test_evidence_hold_and_diagnostic_responses_match_schema(self):
        transport = FakeTransport().usable(PREPAYMENT, self.checklist)
        es = FakeEs([(self.rule_ids[0], 6.0), (self.rule_ids[1], 2.0)])
        evidence = search("수수료율", PREPAYMENT, "2026-10-06", "consult-1", 5, settings=self.settings, search_settings=no_hold(), es=es, transport=transport, clock=CLOCK)
        self.assertEqual([], [e.message for e in self.response.iter_errors(evidence)])
        self.assertNotIn("diagnostics", evidence)
        tuned = SearchSettings("alias", RelevanceHold("combined", "1.5", "0.25", "bm25-hold-v1"), True)
        diagnosed = search("수수료율", PREPAYMENT, "2026-10-06", None, None, settings=self.settings, search_settings=tuned, es=es, transport=transport, clock=CLOCK)
        self.assertEqual([], [e.message for e in self.response.iter_errors(diagnosed)])
        self.assertIn("diagnostics", diagnosed)
        self.assertNotIn("consultation_id", diagnosed)
        hold = search("수수료율", PREPAYMENT, "2026-10-06", None, None, settings=self.settings, search_settings=no_hold(True),
                      es=FakeEs(error=SearchUnavailable("SEARCH_TIMEOUT", "x")), transport=transport, clock=CLOCK)
        self.assertEqual([], [e.message for e in self.response.iter_errors(hold)])
        self.assertEqual(("EVIDENCE_HOLD", "UNVERIFIED", None), (hold["status"], hold["hold_kind"], hold["evaluated_at"]))
        self.assertIsNone(hold["diagnostics"]["tool_1"])
        # 응답에 ES 문서 필드명이나 토큰이 없다.
        text = json.dumps(evidence, ensure_ascii=False)
        for forbidden in ("structured_text", "approvals", "effective_ranges", "Bearer", "temporary"):
            self.assertNotIn(forbidden, text)


if __name__ == "__main__":
    unittest.main()
