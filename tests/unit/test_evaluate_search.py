"""TASK-016 평가 스크립트 단위 테스트(AC-10 평가 거부, 지표 계산). 검색 API는 가짜 응답으로 대체한다."""
from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from scripts import evaluate_search as ev

ROOT = Path(__file__).resolve().parents[2]
SMOKE = ROOT / "datasets/synthetic/search-goldenset/prepayment-fee-smoke-v1.json"
EVAL = ROOT / "datasets/synthetic/search-goldenset/prepayment-fee-eval-v1.json"
DECISION = "review-decision:33333333333333333333333333333333"


FIELDS_BY_RULE: dict[str, dict] = {}  # 골든셋 expected_fields로 만든 가짜 Tool 1 구조화 값(규칙별)
for _goldenset in (ev.load_json(SMOKE), ev.load_json(EVAL)):
    for _query in _goldenset["queries"]:
        for _field in _query["expected_fields"]:
            FIELDS_BY_RULE.setdefault(_field["rule_version_id"], {})[_field["field"]] = _field["expected_value"]


def response_for(query: dict, returned: list[str], hold: dict, raw: list[tuple[str, float]] | None = None, usable: bool = True) -> dict:
    raw = raw if raw is not None else [(r, 5.0 - i) for i, r in enumerate(returned)]
    forwarded = [{"rule_version_id": r, "score": f"{s:.6f}"} for r, s in raw if r in returned or not returned]
    evidence = [{"rank": i + 1, "rule_version_id": r, "rule_key": "K", "score": "1.000000", "instruction": "x", "evidence_required": True,
                 "structured_change": FIELDS_BY_RULE.get(r),
                 "evidence": {"notice_id": "N", "evidence_text": "t", "json_pointer": "/rules/0", "evidence_hash": "sha256:" + "0" * 64}} for i, r in enumerate(returned)]
    return {"search_version": "search-bm25-v1", "decision_guard": {"enabled": True, "version": "decision-guard-v1"}, "status": "EVIDENCE" if evidence else "EVIDENCE_HOLD", "hold_kind": None if evidence else "NO_CANDIDATE",
            "hold_reasons": [] if evidence else ["NO_RELEVANT_CANDIDATE"], "evidence": evidence, "relevance_hold": hold,
            "diagnostics": {"raw_candidates": [{"rule_version_id": r, "score": f"{s:.6f}"} for r, s in raw], "forwarded_candidates": forwarded,
                            "removed": [], "tool_1": {"usable": usable, "decision_id": DECISION if usable else None, "blocking_reasons": []},
                            "tool_calls": {"applicable_checklist": 1, "rule_evidence": len(forwarded)}, "timings_us": {"total": 1000 + len(returned)}}}


class EvaluateSearchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.smoke = ev.load_json(SMOKE)
        cls.eval = ev.load_json(EVAL)
        cls.hold = {"method": "combined", "min_score": "1", "min_ratio": "0.5", "version": "bm25-hold-v1"}

    def perfect_fetch(self, goldenset, hold=None):
        by_query = {q["query"]: q for q in goldenset["queries"]}
        def fetch(body):
            q = by_query[body["query"]]
            return response_for(q, [] if q["expected_evidence_hold"] else list(q["relevant"]), hold or self.hold)
        return fetch

    def test_perfect_responses_meet_every_target_and_are_final_for_eval_set(self):
        summary = ev.run(self.eval, self.perfect_fetch(self.eval), "bm25-standard")
        self.assertTrue(summary["final_evaluation"])
        self.assertEqual(27, summary["query_count"])
        self.assertEqual(1.0, summary["final_stage"]["recall_at_5"])
        self.assertEqual(1.0, summary["final_stage"]["mrr"])
        self.assertEqual(1.0, summary["final_stage"]["precision_returned"])
        self.assertEqual(0, summary["final_stage"]["exposed"])
        self.assertEqual([], summary["final_stage"]["missed_holds"])
        self.assertEqual(0, summary["search_stage"]["hold_query_leaks"])
        self.assertTrue(summary["verdict"]["all_targets_met"])
        self.assertEqual(1.0, summary["final_stage"]["fields_provided_rate"], "expected_fields를 Tool 1 구조화 값으로 받았다")
        self.assertIn("| 재확인 후 | Recall@5 | 1.000 |", ev.markdown(summary))
        self.assertEqual([], summary["failed_queries"])

    def test_smoke_set_is_never_final_and_untuned_requires_flag(self):
        untuned = dict(self.hold, version="untuned")
        with self.assertRaises(ev.EvaluationRejected) as rejected:
            ev.run(self.smoke, self.perfect_fetch(self.smoke, untuned), "bm25-standard")
        self.assertEqual("RELEVANCE_HOLD_UNTUNED", rejected.exception.code)
        summary = ev.run(self.smoke, self.perfect_fetch(self.smoke, untuned), "bm25-standard", allow_untuned=True)
        self.assertFalse(summary["final_evaluation"])
        self.assertFalse(ev.run(self.smoke, self.perfect_fetch(self.smoke), "bm25-standard")["final_evaluation"], "초기 점검용은 고정 뒤에도 최종 평가가 아니다")

    def test_fingerprint_and_approval_state_and_diagnostics_are_enforced(self):
        tampered = copy.deepcopy(self.smoke)
        first = next(iter(tampered["evaluation_context"]["dataset_fingerprint"]["files"]))
        tampered["evaluation_context"]["dataset_fingerprint"]["files"][first] = "sha256:" + "0" * 64
        with self.assertRaises(ev.EvaluationRejected) as rejected:
            ev.run(tampered, self.perfect_fetch(tampered), "x", allow_untuned=True)
        self.assertEqual("DATASET_FINGERPRINT_MISMATCH", rejected.exception.code)

        def other_decision(body):
            response = self.perfect_fetch(self.smoke)(body)
            response["diagnostics"]["tool_1"]["decision_id"] = "review-decision:" + "9" * 32
            return response
        with self.assertRaises(ev.EvaluationRejected) as rejected:
            ev.run(self.smoke, other_decision, "x")
        self.assertEqual("APPROVAL_STATE_MISMATCH", rejected.exception.code)

        def no_diagnostics(body):
            response = self.perfect_fetch(self.smoke)(body); response.pop("diagnostics"); return response
        with self.assertRaises(ev.EvaluationRejected) as rejected:
            ev.run(self.smoke, no_diagnostics, "x")
        self.assertEqual("DIAGNOSTICS_REQUIRED", rejected.exception.code)

    def test_exposure_leak_false_hold_and_irrelevant_are_counted(self):
        by_query = {q["query"]: q for q in self.smoke["queries"]}
        all_prepayment = sorted({r for q in self.smoke["queries"] for r in q["relevant"] + q["must_not"] if q["family_id"] == "SIN-PREPAYMENT-FEE"})
        def fetch(body):
            q = by_query[body["query"]]
            if q["query_id"] == "S08":   # 보류 질의인데 범위 안 근거 2개가 딸려 나옴(b 유형 유출 + 놓친 보류)
                return response_for(q, [r for r in all_prepayment if r not in q["must_not"]][:2] or q["must_not"][:1], self.hold)
            if q["query_id"] == "S01":   # 구버전 노출
                return response_for(q, q["must_not"][:1] + q["relevant"], self.hold)
            if q["query_id"] == "S04":   # 잘못된 보류
                return response_for(q, [], self.hold)
            return response_for(q, list(q["relevant"]), self.hold)
        summary = ev.run(self.smoke, fetch, "x")
        f = summary["final_stage"]; s = summary["search_stage"]
        self.assertGreaterEqual(f["exposed"], 1)
        self.assertIn("S01", f["exposed_queries"])
        self.assertEqual(["S04"], f["false_holds"])
        self.assertIn("S08", f["missed_holds"])
        self.assertEqual(1, s["hold_query_leaks"])
        self.assertFalse(summary["verdict"]["required_all_zero"])
        self.assertFalse(summary["verdict"]["all_targets_met"])
        s01 = next(q for q in summary["queries"] if q["query_id"] == "S01")
        self.assertEqual(0.5, s01["reciprocal_rank"], "노출된 구버전이 1위면 relevant는 2위")
        self.assertTrue({"S01", "S04", "S08"} <= set(summary["failed_queries"]))

    def test_supporting_counts_as_appropriate_but_not_relevant(self):
        by_query = {q["query"]: q for q in self.smoke["queries"]}
        s07 = next(q for q in self.smoke["queries"] if q["query_id"] == "S07")
        self.assertTrue(s07["supporting"])
        def fetch(body):
            q = by_query[body["query"]]
            if q["query_id"] == "S07":
                return response_for(q, list(q["supporting"]) + list(q["relevant"]), self.hold)
            return response_for(q, [] if q["expected_evidence_hold"] else list(q["relevant"]), self.hold)
        row = next(q for q in ev.run(self.smoke, fetch, "x")["queries"] if q["query_id"] == "S07")
        self.assertEqual(0.5, row["reciprocal_rank"])
        self.assertEqual(1.0, row["precision_returned"])
        self.assertEqual([], row["irrelevant"])

    def test_tuning_picks_combined_with_zero_leaks_and_rejects_eval_set(self):
        by_query = {q["query"]: q for q in self.smoke["queries"]}
        in_scope = [r for q in self.smoke["queries"] if q["query_id"] == "S05" for r in q["relevant"]]
        def fetch(body):
            q = by_query[body["query"]]
            if q["expected_evidence_hold"]:
                raw = [(r, 1.5 - 0.2 * i) for i, r in enumerate(in_scope)]  # 낮은 점수의 범위 안 후보가 딸려 나오는 원시 후보
                return response_for(q, [], dict(self.hold, version="untuned"), raw=raw)
            raw = [(r, 9.0 - i) for i, r in enumerate(q["relevant"])] + [(r, 1.0) for r in in_scope if r not in q["relevant"]]
            return response_for(q, list(q["relevant"]), dict(self.hold, version="untuned"), raw=raw)
        summary = ev.run(self.smoke, fetch, "x", allow_untuned=True)
        summary["_goldenset_queries"] = {q["query_id"]: q for q in self.smoke["queries"]}
        tuning = ev.tune(summary)
        chosen = tuning["chosen"]
        self.assertEqual(0, chosen["leaks"])
        self.assertEqual(0, chosen["false_holds"])
        self.assertEqual("combined", chosen["method"])
        self.assertIn("| combined |", ev.tune_markdown(tuning))
        self.assertEqual(["policy" in r for r in in_scope], [True] * len(in_scope))
        with self.assertRaises(ev.EvaluationRejected):
            ev.tune({"kind": "eval", "queries": []})

    def test_tuning_combines_smoke_and_tuning_sets_but_never_eval(self):
        tuning_set = ev.load_json(ROOT / "datasets/synthetic/search-goldenset/prepayment-fee-tuning-v1.json")
        untuned = dict(self.hold, version="untuned")
        smoke = ev.run(self.smoke, self.perfect_fetch(self.smoke, untuned), "x", allow_untuned=True)
        tuning = ev.run(tuning_set, self.perfect_fetch(tuning_set, untuned), "x", allow_untuned=True)
        self.assertFalse(tuning["final_evaluation"], "조정용 자료는 최종 평가로 기록하지 않는다")
        smoke["_goldenset_queries"] = {q["query_id"]: q for q in self.smoke["queries"]}
        tuning["_goldenset_queries"] = {q["query_id"]: q for q in tuning_set["queries"]}
        combined = ev.tune(smoke, [tuning])
        self.assertEqual(2, len(combined["sources"]))
        evaluated = ev.run(self.eval, self.perfect_fetch(self.eval), "x")
        with self.assertRaises(ev.EvaluationRejected):
            ev.tune(smoke, [evaluated])
        other = dict(tuning, configuration="y")
        with self.assertRaises(ev.EvaluationRejected) as rejected:
            ev.tune(smoke, [other])
        self.assertEqual("TUNING_CONFIGURATION_MISMATCH", rejected.exception.code)

    def test_apply_hold_methods(self):
        raw = [{"rule_version_id": "a", "score": "10"}, {"rule_version_id": "b", "score": "4"}, {"rule_version_id": "c", "score": "1"}]
        self.assertEqual(["a", "b"], ev.apply_hold(raw, "absolute", 2.0, 0.0))
        self.assertEqual(["a", "b"], ev.apply_hold(raw, "ratio", 0.0, 0.3))
        self.assertEqual(["a"], ev.apply_hold(raw, "combined", 5.0, 0.3))
        self.assertEqual([], ev.apply_hold([], "combined", 1.0, 0.5))


if __name__ == "__main__":
    unittest.main()
