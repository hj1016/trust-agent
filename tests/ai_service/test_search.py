"""TASK-016 검색 API 단위 테스트(가짜 ES·가짜 Core). AC-04(응답 출처), AC-05(Tool 1 사용 불가), AC-06(Tool 2 제외), AC-07(입력 오류),
AC-08(통신 실패 fail-closed), AC-09(진단 분리)."""
from __future__ import annotations

import json
import os
import unittest
from datetime import datetime, timezone
from unittest import mock

from tests.ai_service.support import PREPAYMENT, FakeTransport, evidence_for, make_root, prepayment_usable, problem, raise_timeout, \
    raise_unavailable, seller_pending, settings_for

from ai_service import config  # noqa: E402
from ai_service.es_client import SearchUnavailable  # noqa: E402
from ai_service.search import RelevanceHold, SearchInputError, SearchSettings, search  # noqa: E402

CLOCK = lambda: datetime(2026, 10, 6, 3, 0, tzinfo=timezone.utc)  # noqa: E731


class FakeEs:
    def __init__(self, hits=None, error: Exception | None = None) -> None:
        self.hits = hits or []
        self.error = error
        self.requests: list[tuple[str, dict]] = []

    def search(self, alias, body):
        self.requests.append((alias, body))
        if self.error:
            raise self.error
        return {"hits": {"hits": [{"_id": rid, "_score": score} for rid, score in self.hits]}}


def no_hold(diagnostics=False) -> SearchSettings:
    return SearchSettings(index_alias="trustagent-rule-evidence-main-current", hold=RelevanceHold(), diagnostics=diagnostics)


class SearchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = make_root()
        self.settings = settings_for(self.root, record_token=None)
        self.checklist = prepayment_usable()
        self.items = self.checklist["approvedChecklist"]["items"]
        self.rule_ids = [item["sourceRuleVersionId"] for item in self.items]

    def run_search(self, es, transport, query="중도상환수수료율 얼마", family_id=PREPAYMENT, **kwargs):
        return search(query, family_id, "2026-10-06", "consult-1", settings=self.settings, search_settings=kwargs.pop("search_settings", no_hold()),
                      es=es, transport=transport, clock=CLOCK, **kwargs)

    # ---- AC-04: 응답 내용은 Tool 응답에서만 ----
    def test_evidence_comes_from_tool_responses_only_and_keeps_es_order(self):
        es = FakeEs([(self.rule_ids[1], 7.5), (self.rule_ids[0], 3.25)])
        transport = FakeTransport().usable(PREPAYMENT, self.checklist)
        result = self.run_search(es, transport)
        self.assertEqual("EVIDENCE", result["status"])
        self.assertIsNone(result["hold_kind"])
        self.assertEqual([self.rule_ids[1], self.rule_ids[0]], [e["rule_version_id"] for e in result["evidence"]])
        self.assertEqual([1, 2], [e["rank"] for e in result["evidence"]])
        for entry in result["evidence"]:
            item = next(i for i in self.items if i["sourceRuleVersionId"] == entry["rule_version_id"])
            expected = evidence_for(item)
            self.assertEqual(item["instruction"], entry["instruction"])
            self.assertEqual(item["structuredChange"], entry["structured_change"])
            self.assertEqual(item["ruleKey"], entry["rule_key"])
            self.assertEqual({"notice_id": expected["noticeId"], "evidence_text": expected["evidenceText"], "json_pointer": expected["jsonPointer"],
                              "evidence_hash": expected["evidenceHash"]}, entry["evidence"])
        self.assertEqual(result["evaluated_at"], self.checklist["evaluatedAt"])
        self.assertEqual("consult-1", result["consultation_id"])
        self.assertNotIn("diagnostics", result)
        # ES 질의는 _source 없이 ID·점수만, 공문군 term + 업무일 intersects 필터.
        alias, body = es.requests[0]
        self.assertEqual("trustagent-rule-evidence-main-current", alias)
        self.assertFalse(body["_source"])
        self.assertIn({"term": {"family_id": PREPAYMENT}}, body["query"]["bool"]["filter"])
        self.assertNotIn("fuzziness", body["query"]["bool"]["must"][0]["multi_match"])
        self.assertIn({"range": {"effective_ranges": {"gte": "2026-10-06", "lte": "2026-10-06", "relation": "intersects"}}}, body["query"]["bool"]["filter"])
        # Tool 1 한 번, Tool 2 후보마다, consultationId 전달.
        self.assertEqual(1, len(transport.tool_calls("/api/v1/tools/applicable_checklist")))
        self.assertEqual([self.rule_ids[1], self.rule_ids[0]], [c["ruleVersionId"] for c in transport.tool_calls("/api/v1/tools/rule_evidence")])
        self.assertTrue(all(c["consultationId"] == "consult-1" for _, c, _ in transport.calls))
        fuzzy = SearchSettings("alias", RelevanceHold(), False, fuzziness="AUTO")
        self.run_search(es, transport, search_settings=fuzzy)
        self.assertEqual("AUTO", es.requests[-1][1]["query"]["bool"]["must"][0]["multi_match"]["fuzziness"])

    # ---- AC-05: Tool 1 사용 불가 ----
    def test_tool_1_unusable_is_core_decision_hold_even_with_candidates(self):
        es = FakeEs([("policy-rule:sha256:" + "a" * 64, 9.0)])
        transport = FakeTransport().respond("SIN-SELLER-CHECKLIST", lambda: FakeTransport.ok_response(seller_pending()))
        result = search("셀러론 체크리스트", "SIN-SELLER-CHECKLIST", "2026-10-06", None, settings=self.settings, search_settings=no_hold(True),
                        es=es, transport=transport, clock=CLOCK)
        self.assertEqual("EVIDENCE_HOLD", result["status"])
        self.assertEqual("CORE_DECISION", result["hold_kind"])
        self.assertEqual(["HUMAN_REVIEW_PENDING"], result["hold_reasons"])
        self.assertEqual([], result["evidence"])
        self.assertEqual(0, len(transport.tool_calls("/api/v1/tools/rule_evidence")), "사용 불가면 Tool 2를 부르지 않는다")
        self.assertEqual([{"rule_version_id": "policy-rule:sha256:" + "a" * 64, "stage": "CORE", "reason": "HUMAN_REVIEW_PENDING"}], result["diagnostics"]["removed"])
        self.assertIsNotNone(result["notices"]["hold_notice"])

    def test_unknown_family_from_core_is_core_decision_hold(self):
        es = FakeEs([(self.rule_ids[0], 2.0)])
        result = self.run_search(es, FakeTransport(), family_id="SIN-NOT-A-FAMILY")
        self.assertEqual(("EVIDENCE_HOLD", "CORE_DECISION", ["POLICY_FAMILY_NOT_FOUND"]), (result["status"], result["hold_kind"], result["hold_reasons"]))

    # ---- AC-06: Tool 2 ----
    def test_tool_2_evidence_not_available_removes_only_that_candidate(self):
        stale = "policy-rule:sha256:" + "b" * 64
        es = FakeEs([(stale, 8.0), (self.rule_ids[0], 5.0)])
        transport = FakeTransport().usable(PREPAYMENT, self.checklist)  # stale은 응답 표에 없어 403 EVIDENCE_NOT_AVAILABLE
        result = self.run_search(es, transport, search_settings=no_hold(True))
        self.assertEqual("EVIDENCE", result["status"])
        self.assertEqual([self.rule_ids[0]], [e["rule_version_id"] for e in result["evidence"]])
        self.assertEqual(1, result["evidence"][0]["rank"])
        self.assertIn({"rule_version_id": stale, "stage": "CORE", "reason": "EVIDENCE_NOT_AVAILABLE"}, result["diagnostics"]["removed"])
        self.assertEqual(2, result["diagnostics"]["tool_calls"]["rule_evidence"])

    def test_tool_2_auth_failure_holds_everything(self):
        es = FakeEs([(self.rule_ids[0], 5.0), (self.rule_ids[1], 4.0)])
        transport = FakeTransport().usable(PREPAYMENT, self.checklist)
        transport.evidence[self.rule_ids[1]] = lambda: problem(403, "SERVICE_TOKEN_INVALID")
        result = self.run_search(es, transport)
        self.assertEqual(("EVIDENCE_HOLD", "UNVERIFIED", ["TOOL_AUTH_FAILED"]), (result["status"], result["hold_kind"], result["hold_reasons"]))
        self.assertEqual([], result["evidence"], "일부 후보가 통과했어도 인증 실패는 전체 보류")

    def test_tool_2_contract_violation_holds_everything(self):
        es = FakeEs([(self.rule_ids[0], 5.0)])
        transport = FakeTransport().usable(PREPAYMENT, self.checklist)
        bad = evidence_for(self.items[0]); bad["evidenceHash"] = "not-a-hash"
        transport.evidence[self.rule_ids[0]] = lambda: FakeTransport.ok_response(bad)
        result = self.run_search(es, transport)
        self.assertEqual(["TOOL_RESPONSE_INVALID"], result["hold_reasons"])
        other = evidence_for(self.items[0]); other["ruleVersionId"] = self.rule_ids[1]
        transport.evidence[self.rule_ids[0]] = lambda: FakeTransport.ok_response(other)
        self.assertEqual(["TOOL_RESPONSE_INVALID"], self.run_search(es, transport)["hold_reasons"], "다른 규칙의 근거를 돌려주면 계약 위반")

    def test_all_candidates_removed_is_no_candidate_hold(self):
        es = FakeEs([("policy-rule:sha256:" + "c" * 64, 8.0)])
        result = self.run_search(es, FakeTransport().usable(PREPAYMENT, self.checklist))
        self.assertEqual(("EVIDENCE_HOLD", "NO_CANDIDATE", ["NO_RELEVANT_CANDIDATE"]), (result["status"], result["hold_kind"], result["hold_reasons"]))

    # ---- AC-07: 입력 오류 ----
    def test_input_errors(self):
        es = FakeEs(); transport = FakeTransport()
        cases = [
            (("", PREPAYMENT, None, None, None), "INVALID_QUERY"),
            (("가" * 201, PREPAYMENT, None, None, None), "INVALID_QUERY"),
            (("질문", None, None, None, None), "FAMILY_ID_REQUIRED"),
            (("중도상환수수료 질문", "", None, None, None), "FAMILY_ID_REQUIRED"),
            (("질문", PREPAYMENT, "2026/10/06", None, None), "INVALID_BUSINESS_DATE"),
            (("질문", PREPAYMENT, None, "", None), "INVALID_CONSULTATION_ID"),
            (("질문", PREPAYMENT, None, None, 11), "INVALID_TOP_K"),
            (("질문", PREPAYMENT, None, None, 0), "INVALID_TOP_K"),
            (("질문", PREPAYMENT, None, None, True), "INVALID_TOP_K"),
        ]
        for args, code in cases:
            with self.subTest(code=code, args=args):
                with self.assertRaises(SearchInputError) as caught:
                    search(*args, settings=self.settings, search_settings=no_hold(), es=es, transport=transport, clock=CLOCK)
                self.assertEqual(code, caught.exception.code)
        self.assertEqual([], es.requests, "입력 오류면 ES도 Core도 부르지 않는다")
        self.assertEqual([], transport.calls)

    def test_business_date_defaults_to_seoul_today(self):
        es = FakeEs(); transport = FakeTransport().usable(PREPAYMENT, self.checklist)
        late = lambda: datetime(2026, 10, 6, 16, 30, tzinfo=timezone.utc)  # noqa: E731  서울 10-07 01:30
        result = search("수수료", PREPAYMENT, None, None, None, settings=self.settings, search_settings=no_hold(), es=es, transport=transport, clock=late)
        self.assertEqual("2026-10-07", result["business_date"])
        self.assertEqual("2026-10-07", transport.tool_calls("/api/v1/tools/applicable_checklist")[0]["businessDate"])

    # ---- AC-08: 통신 실패 ----
    def test_es_and_core_failures_are_holds(self):
        transport = FakeTransport().usable(PREPAYMENT, self.checklist)
        for error, code in ((SearchUnavailable("SEARCH_UNAVAILABLE", "x"), "SEARCH_UNAVAILABLE"), (SearchUnavailable("SEARCH_TIMEOUT", "x"), "SEARCH_TIMEOUT")):
            result = self.run_search(FakeEs(error=error), transport)
            self.assertEqual(("EVIDENCE_HOLD", "UNVERIFIED", [code]), (result["status"], result["hold_kind"], result["hold_reasons"]))
        self.assertEqual([], transport.calls, "ES 실패면 Core를 부르지 않는다")
        es = FakeEs([(self.rule_ids[0], 5.0)])
        for responder, code in ((raise_timeout, "CORE_TIMEOUT"), (raise_unavailable, "CORE_UNAVAILABLE"), (lambda: problem(500, "AUDIT_WRITE_FAILED"), "CORE_UNAVAILABLE"),
                                (lambda: problem(401, "SERVICE_TOKEN_INVALID"), "TOOL_AUTH_FAILED")):
            with self.subTest(code=code):
                result = self.run_search(es, FakeTransport().respond(PREPAYMENT, responder))
                self.assertEqual(("EVIDENCE_HOLD", "UNVERIFIED", [code], []), (result["status"], result["hold_kind"], result["hold_reasons"], result["evidence"]))
        t2 = FakeTransport().usable(PREPAYMENT, self.checklist); t2.evidence[self.rule_ids[0]] = raise_timeout
        self.assertEqual(["CORE_TIMEOUT"], self.run_search(es, t2)["hold_reasons"])
        self.assertIsNotNone(self.run_search(es, t2)["hold_message"])

    def test_malformed_es_hits_are_ignored(self):
        es = FakeEs([("not-a-rule-id", 9.0), (self.rule_ids[0], "high")])
        result = self.run_search(es, FakeTransport().usable(PREPAYMENT, self.checklist), search_settings=no_hold(True))
        self.assertEqual([], result["diagnostics"]["raw_candidates"])
        self.assertEqual("NO_CANDIDATE", result["hold_kind"])

    # ---- 관련성 보류 + top_k ----
    def test_relevance_hold_methods_and_top_k(self):
        hits = [(self.rule_ids[0], 10.0), (self.rule_ids[1], 4.0), (self.rule_ids[2], 1.0)]
        transport = FakeTransport().usable(PREPAYMENT, self.checklist)
        combined = SearchSettings("a", RelevanceHold("combined", "2", "0.3", "bm25-hold-test"), True)
        result = self.run_search(FakeEs(hits), transport, search_settings=combined)
        self.assertEqual([self.rule_ids[0], self.rule_ids[1]], [e["rule_version_id"] for e in result["evidence"]])
        self.assertEqual({"method": "combined", "min_score": "2", "min_ratio": "0.3", "version": "bm25-hold-test"}, result["relevance_hold"])
        self.assertIn({"rule_version_id": self.rule_ids[2], "stage": "HOLD", "reason": "RELEVANCE_BELOW_THRESHOLD"}, result["diagnostics"]["removed"])
        self.assertEqual(3, len(result["diagnostics"]["raw_candidates"]))
        self.assertEqual(2, len(result["diagnostics"]["forwarded_candidates"]))
        absolute = SearchSettings("a", RelevanceHold("absolute", "5", "0.9", "t"), False)
        self.assertEqual([self.rule_ids[0]], [e["rule_version_id"] for e in self.run_search(FakeEs(hits), transport, search_settings=absolute)["evidence"]])
        ratio = SearchSettings("a", RelevanceHold("ratio", "99", "0.35", "t"), False)
        self.assertEqual([self.rule_ids[0], self.rule_ids[1]], [e["rule_version_id"] for e in self.run_search(FakeEs(hits), transport, search_settings=ratio)["evidence"]])
        self.assertTrue(RelevanceHold("ratio", "0", "0.5").keep(1.0, 0.0), "상위 점수 0이면 비율 조건은 통과")
        limited = self.run_search(FakeEs(hits), transport, top_k=1, search_settings=no_hold(True))
        self.assertEqual(1, len(limited["evidence"]))
        self.assertIn({"rule_version_id": self.rule_ids[1], "stage": "HOLD", "reason": "BEYOND_TOP_K"}, limited["diagnostics"]["removed"])

    # ---- AC-09: 진단 분리 ----
    def test_diagnostics_only_when_enabled(self):
        es = FakeEs([(self.rule_ids[0], 5.0)])
        transport = FakeTransport().usable(PREPAYMENT, self.checklist)
        plain = self.run_search(es, transport)
        self.assertNotIn("diagnostics", plain)
        self.assertNotIn("raw_candidates", json.dumps(plain))
        diagnosed = self.run_search(es, transport, search_settings=no_hold(True))
        self.assertEqual({"raw_candidates", "forwarded_candidates", "removed", "tool_1", "tool_calls", "timings_us"}, set(diagnosed["diagnostics"]))
        self.assertEqual(self.checklist["approvedChecklist"]["decisionId"], diagnosed["diagnostics"]["tool_1"]["decision_id"])
        self.assertEqual({"applicable_checklist": 1, "rule_evidence": 1}, diagnosed["diagnostics"]["tool_calls"])
        self.assertTrue({"es", "tool_1", "tool_2", "total"} <= set(diagnosed["diagnostics"]["timings_us"]))
        self.assertEqual("5.000000", diagnosed["diagnostics"]["raw_candidates"][0]["score"])


class SearchSettingsTest(unittest.TestCase):
    def test_defaults_and_validation(self):
        settings = config.load_search_settings({})
        self.assertEqual(("combined", "0", "0", "untuned", False), (settings.hold.method, settings.hold.min_score, settings.hold.min_ratio, settings.hold.version, settings.diagnostics))
        self.assertEqual(config.DEFAULT_SEARCH_INDEX_ALIAS, settings.index_alias)
        tuned = config.load_search_settings({config.ENV_SEARCH_HOLD_METHOD: "absolute", config.ENV_SEARCH_HOLD_MIN_SCORE: "3.5", config.ENV_SEARCH_HOLD_VERSION: "bm25-hold-v1",
                                             config.ENV_SEARCH_DIAGNOSTICS: "1", config.ENV_SEARCH_INDEX_ALIAS: "x-current"})
        self.assertEqual(("absolute", "3.5", "bm25-hold-v1", True, "x-current"), (tuned.hold.method, tuned.hold.min_score, tuned.hold.version, tuned.diagnostics, tuned.index_alias))
        for bad in ({config.ENV_SEARCH_HOLD_METHOD: "fuzzy"}, {config.ENV_SEARCH_HOLD_MIN_SCORE: "-1"}, {config.ENV_SEARCH_HOLD_MIN_RATIO: "abc"}, {config.ENV_ES_TIMEOUT_SECONDS: "0"}):
            with self.assertRaises(config.SettingsError):
                config.load_search_settings(bad); config.load_es_settings(bad)
        es = config.load_es_settings({config.ENV_ES_URL: "http://es:9200/", config.ENV_ES_SEARCH_USERNAME: "u", config.ENV_ES_SEARCH_PASSWORD: "p"})
        self.assertEqual(("http://es:9200", "u", "p"), (es.base_url, es.username, es.password))
        self.assertNotIn("p", json.dumps(config.load_search_settings({}).hold.as_dict()))
        self.assertIsNone(config.load_search_settings({}).fuzziness)
        self.assertEqual("AUTO", config.load_search_settings({config.ENV_SEARCH_FUZZINESS: "auto"}).fuzziness)
        with self.assertRaises(config.SettingsError):
            config.load_search_settings({config.ENV_SEARCH_FUZZINESS: "3"})


class SearchRouteTest(unittest.TestCase):
    def test_route_validates_input_and_returns_hold_status_header(self):
        from fastapi.testclient import TestClient
        from ai_service import app as app_module
        from tests.ai_service.test_cli_and_app import environ_for

        root = make_root()
        es = FakeEs([(prepayment_usable()["approvedChecklist"]["items"][0]["sourceRuleVersionId"], 5.0)])
        transport = FakeTransport().usable(PREPAYMENT, prepayment_usable())
        original = app_module.search

        def fake_search(query, family_id, business_date, consultation_id, top_k, *, settings, search_settings, es=None):
            return original(query, family_id, business_date, consultation_id, top_k, settings=settings, search_settings=search_settings,
                            es=es_fake, transport=transport, clock=CLOCK)

        es_fake = es
        with mock.patch.dict(os.environ, environ_for(root), clear=False), mock.patch.object(app_module, "search", fake_search):
            client = TestClient(app_module.app)
            self.assertEqual(400, client.post("/api/v1/ai/search", json={"query": "수수료"}).status_code, "familyId 없음")
            self.assertEqual(400, client.post("/api/v1/ai/search", json={"query": "수수료", "familyId": PREPAYMENT, "topK": 11}).status_code)
            self.assertEqual(400, client.post("/api/v1/ai/search", json={"query": "수수료", "familyId": PREPAYMENT, "product": "x"}).status_code, "모르는 필드")
            self.assertEqual(400, client.post("/api/v1/ai/search", json={"query": "가" * 201, "familyId": PREPAYMENT}).status_code)
            bad_date = client.post("/api/v1/ai/search", json={"query": "수수료", "familyId": PREPAYMENT, "businessDate": "2026/10/06"})
            self.assertEqual((400, "INVALID_BUSINESS_DATE"), (bad_date.status_code, bad_date.json()["detail"]["code"]))
            ok = client.post("/api/v1/ai/search", json={"query": "수수료", "familyId": PREPAYMENT, "businessDate": "2026-10-06"})
            self.assertEqual(200, ok.status_code, ok.text)
            self.assertEqual("EVIDENCE", ok.headers["X-Evidence-Status"])
            self.assertEqual(1, len(ok.json()["evidence"]))
            self.assertNotIn("diagnostics", ok.json())
        with mock.patch.dict(os.environ, {**environ_for(root), config.ENV_SEARCH_DIAGNOSTICS: "1"}, clear=False), mock.patch.object(app_module, "search", fake_search):
            es_fake = FakeEs(error=SearchUnavailable("SEARCH_UNAVAILABLE", "x"))
            hold = TestClient(app_module.app).post("/api/v1/ai/search", json={"query": "수수료", "familyId": PREPAYMENT})
            self.assertEqual((200, "EVIDENCE_HOLD"), (hold.status_code, hold.headers["X-Evidence-Status"]))
            self.assertIn("diagnostics", hold.json())
        with mock.patch.dict(os.environ, {**environ_for(root), config.ENV_SEARCH_HOLD_METHOD: "bad"}, clear=False):
            self.assertEqual(503, TestClient(app_module.app).post("/api/v1/ai/search", json={"query": "수수료", "familyId": PREPAYMENT}).status_code)


if __name__ == "__main__":
    unittest.main()
