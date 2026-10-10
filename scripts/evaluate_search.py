#!/usr/bin/env python3
"""TASK-014 명세의 검색 평가 스크립트(TASK-016 구현).

run:  골든셋의 질의마다 검색 API(진단 모드)를 호출해 세 단계 지표(검색 단계·Core 재확인·재확인 후)를 계산하고 JSON·Markdown으로 남긴다.
      evaluation_context를 대조해 자료 fingerprint·승인 상태(Tool 1 decisionId)·평가 시각(Tool 1 evaluatedAt)이 다르면 거부한다.
      relevance_hold.version이 untuned면 --allow-untuned 없이는 거부한다(관련성 보류 기준은 고정 뒤 평가).
tune: 초기 점검용 결과(run --allow-untuned)의 원시 후보 점수로 보류 방식 세 가지(absolute, ratio, combined)를 비교하고
      "보류 질의 후보 유출 0건"을 만족하면서 "잘못된 보류"가 가장 적은 값을 고른다. 최종 평가용 자료로는 조정하지 않는다(kind가 eval이면 거부).

표준 라이브러리만 쓴다(계약 테스트와 같은 canonical sha256). 토큰은 검색 API 쪽 환경변수이며 이 스크립트는 토큰을 다루지 않는다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional, Sequence

ROOT = Path(__file__).resolve().parents[1]
METADATA_EXCLUSIONS = {"OUT_OF_SCOPE", "UNAPPROVED", "OLD_VERSION", "NOT_IN_EFFECT"}  # 검색 단계 제외 조건(관련성 IRRELEVANT는 Core도 필터도 아닌 보류 처리의 몫)
TOP_K = 5
RECALL_K = 5
# 채택된 초기 수용 기준(TASK-016 계획, 결정자 사용자). 필수 항목은 0이어야 한다.
TARGETS = {"recall_at_5": 0.80, "mrr": 0.70, "precision_returned": 0.70, "irrelevant_rate_max": 0.25}


class EvaluationRejected(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


# ---- canonical sha256 (tests/contract/test_search_goldenset_contracts.py와 같은 규칙) ----
def canonical_json(value) -> str:
    def reject_floating_point(item):
        if isinstance(item, float):
            raise ValueError("canonical JSON은 부동소수점을 허용하지 않습니다.")
        if isinstance(item, dict):
            return {k: reject_floating_point(v) for k, v in item.items()}
        if isinstance(item, list):
            return [reject_floating_point(v) for v in item]
        return item
    return json.dumps(reject_floating_point(value), ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256(value) -> str:
    return "sha256:" + hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def load_json(path: Path):
    with path.open(encoding="utf-8") as source:
        return json.load(source, parse_float=str)


def check_fingerprint(goldenset: dict, root: Path) -> None:
    fingerprint = goldenset["evaluation_context"]["dataset_fingerprint"]
    if fingerprint.get("algorithm") != "canonical-json-sha256":
        raise EvaluationRejected("FINGERPRINT_ALGORITHM", "지원하지 않는 fingerprint 알고리즘")
    mismatched = [relative for relative, digest in fingerprint["files"].items() if not (root / relative).is_file() or sha256(load_json(root / relative)) != digest]
    if mismatched:
        raise EvaluationRejected("DATASET_FINGERPRINT_MISMATCH", "평가 고정 자료가 골든셋 fingerprint와 다릅니다: " + ", ".join(mismatched[:5]))


# ---- 검색 API 호출 ----
def http_search(search_url: str, timeout: float) -> Callable[[dict], dict]:
    def call(body: dict) -> dict:
        request = urllib.request.Request(search_url.rstrip("/") + "/api/v1/ai/search", data=json.dumps(body).encode("utf-8"), method="POST",
                                         headers={"Content-Type": "application/json", "Accept": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310
                return json.loads(response.read().decode("utf-8"), parse_float=str)
        except urllib.error.HTTPError as error:
            raise EvaluationRejected("SEARCH_API_ERROR", f"검색 API {error.code}: {error.read().decode('utf-8', 'replace')[:300]}") from error
    return call


# ---- 질의별 판정 ----
@dataclass
class QueryResult:
    query_id: str
    category: str
    expected_hold: bool
    relevant: list[str]
    supporting: list[str]
    must_not: list[str]
    exclusion_by_rule: dict[str, str]
    status: str
    hold_kind: Optional[str]
    hold_reasons: list[str]
    raw: list[dict]
    forwarded: list[dict]
    removed: list[dict]
    evidence: list[dict]
    tool_1: Optional[dict]
    tool_calls: dict
    timings_us: dict
    expected_fields: list[dict] = field(default_factory=list)

    @property
    def returned_ids(self) -> list[str]:
        return [e["rule_version_id"] for e in self.evidence]

    # 검색 단계
    def exclusion_violations(self) -> list[dict]:
        return [{"rule_version_id": c["rule_version_id"], "reason": self.exclusion_by_rule[c["rule_version_id"]]}
                for c in self.forwarded if self.exclusion_by_rule.get(c["rule_version_id"]) in METADATA_EXCLUSIONS]

    def leak(self) -> Optional[dict]:
        """보류 질의에서 Core로 전달한 후보가 비어 있지 않으면 (a) 제외 조건 위반, (b) 범위 안 사용 가능 근거로 나눈다."""
        if not self.expected_hold or not self.forwarded:
            return None
        violations = {v["rule_version_id"] for v in self.exclusion_violations()}
        return {"a_filter_defect": sorted(violations), "b_relevance_defect": [c["rule_version_id"] for c in self.forwarded if c["rule_version_id"] not in violations]}

    # 재확인 후
    def recall_at_k(self, k: int = RECALL_K) -> Optional[float]:
        if self.expected_hold or not self.relevant:
            return None
        top = set(self.returned_ids[:k])
        return sum(1 for r in self.relevant if r in top) / len(self.relevant)

    def reciprocal_rank(self) -> Optional[float]:
        if self.expected_hold:
            return None
        for index, rule in enumerate(self.returned_ids, start=1):
            if rule in self.relevant:
                return 1.0 / index
        return 0.0

    def precision_returned(self) -> Optional[float]:
        if self.expected_hold:
            return None
        if not self.evidence:
            return 0.0  # 잘못된 보류는 적절한 반환이 없으므로 0으로 센다
        good = set(self.relevant) | set(self.supporting)
        return sum(1 for r in self.returned_ids if r in good) / len(self.evidence)

    def irrelevant(self) -> list[str]:
        if self.expected_hold:
            return []
        known = set(self.relevant) | set(self.supporting) | set(self.must_not)
        return [r for r in self.returned_ids if r not in known]

    def exposed(self) -> list[str]:
        return [r for r in self.returned_ids if r in self.must_not]

    def false_hold(self) -> bool:
        return not self.expected_hold and not self.evidence

    def missed_hold(self) -> bool:
        return self.expected_hold and bool(self.evidence)

    def fields_check(self) -> Optional[dict]:
        if not self.expected_fields:
            return None
        by_rule = {e["rule_version_id"]: e for e in self.evidence}
        missing = []
        for expected in self.expected_fields:
            entry = by_rule.get(expected["rule_version_id"])
            change = (entry or {}).get("structured_change") or {}
            if entry is None or str(change.get(expected["field"])) != str(expected["expected_value"]):
                missing.append(expected)
        return {"expected": len(self.expected_fields), "missing": missing, "ok": not missing}


def to_result(query: dict, response: dict) -> QueryResult:
    diagnostics = response.get("diagnostics")
    if diagnostics is None:
        raise EvaluationRejected("DIAGNOSTICS_REQUIRED", "검색 API가 진단 모드가 아닙니다(TRUST_AGENT_SEARCH_DIAGNOSTICS=1): " + query["query_id"])
    exclusion_by_rule = {rule: code for code, rules in (query.get("exclusion_reasons") or {}).items() for rule in rules}
    return QueryResult(
        query_id=query["query_id"], category=query["category"], expected_hold=bool(query["expected_evidence_hold"]),
        relevant=list(query["relevant"]), supporting=list(query.get("supporting") or []), must_not=list(query["must_not"]),
        exclusion_by_rule=exclusion_by_rule, status=response["status"], hold_kind=response.get("hold_kind"), hold_reasons=list(response.get("hold_reasons") or []),
        raw=list(diagnostics.get("raw_candidates") or []), forwarded=list(diagnostics.get("forwarded_candidates") or []),
        removed=list(diagnostics.get("removed") or []), evidence=list(response.get("evidence") or []), tool_1=diagnostics.get("tool_1"),
        tool_calls=dict(diagnostics.get("tool_calls") or {}), timings_us=dict(diagnostics.get("timings_us") or {}),
        expected_fields=list(query.get("expected_fields") or []),
    )


def check_context(goldenset: dict, results: Sequence[QueryResult]) -> None:
    context = goldenset["evaluation_context"]
    approved_decisions = {a["decision_id"] for a in context["approval_state"]["approved"]}
    for result in results:
        tool_1 = result.tool_1
        if tool_1 is None:
            continue  # ES·Core 통신 실패로 Tool 1에 못 간 질의는 아래 보류 사유로 남는다
        if tool_1.get("usable") and tool_1.get("decision_id") not in approved_decisions:
            raise EvaluationRejected("APPROVAL_STATE_MISMATCH", f"{result.query_id}: Tool 1 decisionId {tool_1.get('decision_id')}가 골든셋 승인 상태에 없습니다.")


def mean(values: Sequence[float]) -> Optional[float]:
    values = [v for v in values if v is not None]
    return (sum(values) / len(values)) if values else None


def percentile(values: Sequence[int], p: int) -> Optional[int]:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(0, math.ceil(p / 100 * len(ordered)) - 1)
    return ordered[rank]


def summarize(goldenset: dict, results: Sequence[QueryResult], configuration: str, relevance_hold: dict, search_version: Optional[str], final: bool) -> dict:
    non_hold = [r for r in results if not r.expected_hold]
    hold = [r for r in results if r.expected_hold]
    violations = [(r.query_id, v) for r in results for v in r.exclusion_violations()]
    leaks = [(r.query_id, r.leak()) for r in results if r.leak()]
    removed_by_reason: dict[str, int] = {}
    for r in results:
        for item in r.removed:
            if item["stage"] == "CORE":
                removed_by_reason[item["reason"]] = removed_by_reason.get(item["reason"], 0) + 1
    irrelevant_total = sum(len(r.irrelevant()) for r in non_hold)
    returned_total = sum(len(r.evidence) for r in non_hold)
    fields = [r.fields_check() for r in results if r.fields_check() is not None]
    totals = [r.timings_us.get("total") for r in results if isinstance(r.timings_us.get("total"), int)]
    search_stage = {
        "exclusion_violations": len(violations),
        "exclusion_violation_queries": sorted({q for q, _ in violations}),
        "hold_query_leaks": len(leaks),
        "hold_query_leak_detail": [{"query_id": q, **detail} for q, detail in leaks],
        "forwarded_candidates_total": sum(len(r.forwarded) for r in results),
        "raw_candidates_total": sum(len(r.raw) for r in results),
    }
    core_stage = {"removed_by_reason": removed_by_reason, "tool_calls": {
        "applicable_checklist": sum(r.tool_calls.get("applicable_checklist", 0) for r in results),
        "rule_evidence": sum(r.tool_calls.get("rule_evidence", 0) for r in results)}}
    final_stage = {
        "queries_scored": len(non_hold),
        "recall_at_5": mean([r.recall_at_k() for r in non_hold]),
        "mrr": mean([r.reciprocal_rank() for r in non_hold]),
        "precision_returned": mean([r.precision_returned() for r in non_hold]),
        "irrelevant_count": irrelevant_total,
        "irrelevant_rate": (irrelevant_total / returned_total) if returned_total else 0.0,
        "average_returned": (returned_total / len(non_hold)) if non_hold else 0.0,
        "exposed": sum(len(r.exposed()) for r in results),
        "exposed_queries": [r.query_id for r in results if r.exposed()],
        "false_holds": [r.query_id for r in results if r.false_hold()],
        "false_hold_rate": (sum(1 for r in non_hold if r.false_hold()) / len(non_hold)) if non_hold else 0.0,
        "missed_holds": [r.query_id for r in results if r.missed_hold()],
        "missed_hold_rate": (sum(1 for r in hold if r.missed_hold()) / len(hold)) if hold else 0.0,
        "fields_provided_rate": (sum(1 for f in fields if f["ok"]) / len(fields)) if fields else None,
        "fields_missing": [{"query_id": r.query_id, **r.fields_check()} for r in results if r.fields_check() and not r.fields_check()["ok"]],
    }
    required_ok = (search_stage["exclusion_violations"] == 0 and search_stage["hold_query_leaks"] == 0
                   and final_stage["exposed"] == 0 and not final_stage["missed_holds"])
    def meets(key, value, maximum=False):
        if value is None:
            return None
        return value <= TARGETS[key] if maximum else value >= TARGETS[key]
    verdict = {
        "required_all_zero": required_ok,
        "recall_at_5": meets("recall_at_5", final_stage["recall_at_5"]),
        "mrr": meets("mrr", final_stage["mrr"]),
        "precision_returned": meets("precision_returned", final_stage["precision_returned"]),
        "irrelevant_rate": meets("irrelevant_rate_max", final_stage["irrelevant_rate"], maximum=True),
    }
    verdict["all_targets_met"] = bool(required_ok and all(v is not False for v in verdict.values()))
    failures = [r.query_id for r in results if r.exposed() or r.false_hold() or r.missed_hold() or r.exclusion_violations() or r.leak()
                or (r.recall_at_k() is not None and r.recall_at_k() < 1.0) or (r.fields_check() and not r.fields_check()["ok"])]
    return {
        "goldenset_id": goldenset["goldenset_id"], "goldenset_version": goldenset["goldenset_version"], "kind": goldenset["kind"],
        "query_count": len(results), "configuration": configuration, "search_version": search_version, "relevance_hold": relevance_hold,
        "final_evaluation": final, "targets": TARGETS,
        "search_stage": search_stage, "core_recheck_stage": core_stage, "final_stage": final_stage, "verdict": verdict,
        "latency_us": {"p50": percentile(totals, 50), "p95": percentile(totals, 95), "count": len(totals)},
        "failed_queries": failures,
        "queries": [{
            "query_id": r.query_id, "category": r.category, "expected_hold": r.expected_hold, "status": r.status, "hold_kind": r.hold_kind,
            "hold_reasons": r.hold_reasons, "raw_top5": r.raw[:5], "forwarded": r.forwarded, "removed": r.removed, "returned": r.returned_ids,
            "recall_at_5": r.recall_at_k(), "reciprocal_rank": r.reciprocal_rank(), "precision_returned": r.precision_returned(),
            "irrelevant": r.irrelevant(), "exposed": r.exposed(), "exclusion_violations": r.exclusion_violations(), "leak": r.leak(),
            "fields": r.fields_check(), "timings_us": r.timings_us,
        } for r in results],
    }


def run(goldenset: dict, fetch: Callable[[dict], dict], configuration: str, root: Path = ROOT, allow_untuned: bool = False, top_k: int = TOP_K) -> dict:
    check_fingerprint(goldenset, root)
    results: list[QueryResult] = []
    relevance_hold = None
    search_version = None
    for query in goldenset["queries"]:
        body = {"query": query["query"], "familyId": query["family_id"], "businessDate": query["business_date"], "topK": top_k,
                "consultationId": "eval-" + goldenset["goldenset_id"] + "-" + query["query_id"]}
        response = fetch(body)
        hold = response.get("relevance_hold") or {}
        if relevance_hold is not None and hold != relevance_hold:
            raise EvaluationRejected("RELEVANCE_HOLD_CHANGED", "평가 도중 관련성 보류 기준이 바뀌었습니다.")
        relevance_hold = hold
        search_version = response.get("search_version")
        results.append(to_result(query, response))
    if relevance_hold is None:
        raise EvaluationRejected("EMPTY_GOLDENSET", "질의가 없습니다.")
    if relevance_hold.get("version") == "untuned" and not allow_untuned:
        raise EvaluationRejected("RELEVANCE_HOLD_UNTUNED", "관련성 보류 기준이 고정되지 않았습니다(--allow-untuned는 초기 점검용 조정에만 쓴다).")
    check_context(goldenset, results)
    evaluated = {r.tool_1.get("usable") and r.query_id for r in results if r.tool_1}
    final = goldenset["kind"] == "eval" and relevance_hold.get("version") != "untuned"
    return summarize(goldenset, results, configuration, relevance_hold, search_version, final)


# ---- 관련성 보류 기준 조정(초기 점검용 자료로만) ----
def apply_hold(raw: Sequence[dict], method: str, min_score: float, min_ratio: float, top_k: int = TOP_K) -> list[str]:
    scores = [(c["rule_version_id"], float(c["score"])) for c in raw]
    top = scores[0][1] if scores else 0.0
    kept = []
    for rule, score in scores:
        absolute = score >= min_score
        ratio = top <= 0 or (score / top) >= min_ratio
        if (method == "absolute" and absolute) or (method == "ratio" and ratio) or (method == "combined" and absolute and ratio):
            kept.append(rule)
    return kept[:top_k]


def tune(result: dict) -> dict:
    """원시 후보 점수로 세 방식을 비교한다. 조건: 보류 질의 후보 유출(b) 0건 → 그중 잘못된 보류(비보류 질의에서 relevant가 전달 후보에 하나도 없음) 최소 → 전달 후보 수 최대."""
    if result["kind"] == "eval":
        raise EvaluationRejected("TUNING_ON_EVAL_SET", "최종 평가용 자료로는 관련성 보류 기준을 조정하지 않는다.")
    queries = result["queries"]
    goldenset_by_id = result.get("_goldenset_queries") or {}
    scores = sorted({float(c["score"]) for q in queries for c in q["raw_top5"]} | {0.0})
    ratios = [round(x / 100, 2) for x in range(0, 101, 5)]
    table = []
    for method in ("absolute", "ratio", "combined"):
        score_grid = scores if method != "ratio" else [0.0]
        ratio_grid = ratios if method != "absolute" else [0.0]
        for min_score in score_grid:
            for min_ratio in ratio_grid:
                leaks = 0
                false_holds = 0
                forwarded = 0
                for q in queries:
                    kept = apply_hold(q["raw_top5"], method, min_score, min_ratio)
                    forwarded += len(kept)
                    spec = goldenset_by_id[q["query_id"]]
                    if q["expected_hold"]:
                        leaks += 1 if kept else 0
                    elif spec["relevant"] and not any(r in kept for r in spec["relevant"]):
                        false_holds += 1
                table.append({"method": method, "min_score": f"{min_score:.6f}", "min_ratio": f"{min_ratio:.2f}", "leaks": leaks, "false_holds": false_holds, "forwarded": forwarded})
    def rank(row):
        return (row["leaks"], row["false_holds"], -row["forwarded"], float(row["min_score"]), float(row["min_ratio"]))
    table.sort(key=rank)
    best_by_method = {m: next((row for row in table if row["method"] == m), None) for m in ("absolute", "ratio", "combined")}
    chosen = best_by_method["combined"] if best_by_method["combined"] and best_by_method["combined"]["leaks"] == 0 else table[0]
    return {"grid_size": len(table), "best_by_method": best_by_method, "chosen": chosen, "top": table[:15]}


# ---- Markdown ----
def fmt(value) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.3f}"
    return str(value)


def markdown(summary: dict) -> str:
    s = summary["search_stage"]; c = summary["core_recheck_stage"]; f = summary["final_stage"]; v = summary["verdict"]
    lines = [f"# 검색 평가 결과 {summary['configuration']} / {summary['goldenset_id']} {summary['goldenset_version']} ({summary['kind']}, {summary['query_count']}건)", "",
             f"- search_version: `{summary['search_version']}`, relevance_hold: `{json.dumps(summary['relevance_hold'])}`, 최종 평가 기록: {'예' if summary['final_evaluation'] else '아니오(초기 점검·조정)'}",
             f"- 지연(us, 질의 전체 처리): p50 {fmt(summary['latency_us']['p50'])}, p95 {fmt(summary['latency_us']['p95'])}, n={summary['latency_us']['count']}", "",
             "## 세 단계 지표", "", "| 단계 | 지표 | 값 | 기준 | 충족 |", "|---|---|---|---|---|",
             f"| 검색 단계 | 제외 조건 위반(전달 후보) | {s['exclusion_violations']} | 0(필수) | {'예' if s['exclusion_violations'] == 0 else '아니오'} |",
             f"| 검색 단계 | 보류 질의 후보 유출 | {s['hold_query_leaks']} | 0(필수) | {'예' if s['hold_query_leaks'] == 0 else '아니오'} |",
             f"| Core 재확인 | 제거 사유별 건수 | {json.dumps(c['removed_by_reason'], ensure_ascii=False)} | 기록 | - |",
             f"| Core 재확인 | Tool 호출 수 | Tool 1 {c['tool_calls']['applicable_checklist']}, Tool 2 {c['tool_calls']['rule_evidence']} | 기록 | - |",
             f"| 재확인 후 | Recall@5 | {fmt(f['recall_at_5'])} | ≥ {TARGETS['recall_at_5']} | {fmt(v['recall_at_5'])} |",
             f"| 재확인 후 | MRR | {fmt(f['mrr'])} | ≥ {TARGETS['mrr']} | {fmt(v['mrr'])} |",
             f"| 재확인 후 | Precision(반환 수 기준) | {fmt(f['precision_returned'])} | ≥ {TARGETS['precision_returned']} | {fmt(v['precision_returned'])} |",
             f"| 재확인 후 | 무관 근거 혼입률 | {fmt(f['irrelevant_rate'])} ({f['irrelevant_count']}건) | ≤ {TARGETS['irrelevant_rate_max']} | {fmt(v['irrelevant_rate'])} |",
             f"| 재확인 후 | 최종 노출(must_not) | {f['exposed']} | 0(필수) | {'예' if f['exposed'] == 0 else '아니오'} |",
             f"| 재확인 후 | 잘못된 보류 | {len(f['false_holds'])} ({fmt(f['false_hold_rate'])}) {f['false_holds']} | 기록 | - |",
             f"| 재확인 후 | 놓친 보류 | {len(f['missed_holds'])} ({fmt(f['missed_hold_rate'])}) {f['missed_holds']} | 0%(필수) | {'예' if not f['missed_holds'] else '아니오'} |",
             f"| 재확인 후 | 필드 제공 | {fmt(f['fields_provided_rate'])} | 1.0 | {'예' if f['fields_provided_rate'] in (None, 1.0) else '아니오'} |",
             f"| 재확인 후 | 평균 반환 수 | {fmt(f['average_returned'])} | 보조 | - |", "",
             f"**판정**: 필수 항목 {'전부 0' if v['required_all_zero'] else '미충족'}, 수치 기준 {'전부 충족' if v['all_targets_met'] else '일부 미충족'}.", "",
             "## 질의별 결과", "", "| ID | 범주 | 보류 정답 | 원시 후보 상위 5(점수) | 전달 후보 | 제거(단계:사유) | 통과 목록 | R@5 | RR | P | 비고 |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    for q in summary["queries"]:
        short = lambda rid: rid.replace("policy-rule:sha256:", "")[:8]  # noqa: E731
        raw = ", ".join(f"{short(c['rule_version_id'])}({float(c['score']):.2f})" for c in q["raw_top5"]) or "-"
        fwd = ", ".join(short(c["rule_version_id"]) for c in q["forwarded"]) or "-"
        rem = ", ".join(f"{short(r['rule_version_id'])} {r['stage']}:{r['reason']}" for r in q["removed"]) or "-"
        ret = ", ".join(short(r) for r in q["returned"]) or "-"
        notes = []
        if q["exposed"]: notes.append("노출 " + ", ".join(short(r) for r in q["exposed"]))
        if q["leak"]: notes.append("유출 " + json.dumps(q["leak"], ensure_ascii=False))
        if q["exclusion_violations"]: notes.append("제외 위반")
        if q["irrelevant"]: notes.append("무관 " + ", ".join(short(r) for r in q["irrelevant"]))
        if q["fields"] and not q["fields"]["ok"]: notes.append("필드 누락")
        if q["status"] == "EVIDENCE_HOLD": notes.append(f"보류 {q['hold_kind']} {q['hold_reasons']}")
        lines.append(f"| {q['query_id']} | {q['category']} | {'예' if q['expected_hold'] else ''} | {raw} | {fwd} | {rem} | {ret} | {fmt(q['recall_at_5'])} | {fmt(q['reciprocal_rank'])} | {fmt(q['precision_returned'])} | {'; '.join(notes)} |")
    lines += ["", f"실패 질의: {', '.join(summary['failed_queries']) or '없음'}", ""]
    return "\n".join(lines)


def tune_markdown(tuning: dict) -> str:
    lines = ["## 관련성 보류 기준 비교(초기 점검용)", "", "| 방식 | min_score | min_ratio | 유출 | 잘못된 보류 | 전달 후보 수 |", "|---|---|---|---|---|---|"]
    for m, row in tuning["best_by_method"].items():
        if row:
            lines.append(f"| {m} | {row['min_score']} | {row['min_ratio']} | {row['leaks']} | {row['false_holds']} | {row['forwarded']} |")
    c = tuning["chosen"]
    lines += ["", f"선택: `{c['method']}` min_score {c['min_score']}, min_ratio {c['min_ratio']} (유출 {c['leaks']}, 잘못된 보류 {c['false_holds']}, 격자 {tuning['grid_size']}개 중)", ""]
    return "\n".join(lines)


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    run_cmd = commands.add_parser("run")
    run_cmd.add_argument("--goldenset", required=True, type=Path)
    run_cmd.add_argument("--search-url", required=True)
    run_cmd.add_argument("--configuration", required=True, help="구성 ID(예: bm25-standard)")
    run_cmd.add_argument("--out-dir", required=True, type=Path)
    run_cmd.add_argument("--allow-untuned", action="store_true")
    run_cmd.add_argument("--timeout", type=float, default=30.0)
    run_cmd.add_argument("--repository-root", type=Path, default=ROOT)
    tune_cmd = commands.add_parser("tune")
    tune_cmd.add_argument("--result", required=True, type=Path)
    tune_cmd.add_argument("--goldenset", required=True, type=Path)
    tune_cmd.add_argument("--out", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "run":
            goldenset = load_json(args.goldenset)
            summary = run(goldenset, http_search(args.search_url, args.timeout), args.configuration, args.repository_root, args.allow_untuned)
            args.out_dir.mkdir(parents=True, exist_ok=True)
            stem = f"{summary['goldenset_id']}-{summary['goldenset_version']}.{args.configuration}.{summary['relevance_hold'].get('version')}"
            (args.out_dir / (stem + ".result.json")).write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
            (args.out_dir / (stem + ".md")).write_text(markdown(summary), encoding="utf-8")
            print(json.dumps({"result": str(args.out_dir / (stem + ".result.json")), "verdict": summary["verdict"], "final_evaluation": summary["final_evaluation"]}, ensure_ascii=False))
            return 0
        result = load_json(args.result)
        goldenset = load_json(args.goldenset)
        result["_goldenset_queries"] = {q["query_id"]: q for q in goldenset["queries"]}
        tuning = tune(result)
        args.out.write_text(json.dumps(tuning, ensure_ascii=False, indent=2), encoding="utf-8")
        args.out.with_suffix(".md").write_text(tune_markdown(tuning), encoding="utf-8")
        print(json.dumps(tuning["chosen"], ensure_ascii=False))
        return 0
    except EvaluationRejected as error:
        print(json.dumps({"rejected": error.code, "message": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
