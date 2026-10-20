"""근거 검색 기준선(TASK-016): ES BM25 후보 → 관련성 보류 → Core Tool 1(사용 가능 여부) → 후보마다 Tool 2(근거) → 사용 가능한 근거만.

원칙(ADR-013): ES는 규칙 version ID와 점수만 쓴다. 행원에게 가는 문장·위치·해시·구조화 값은 Tool 2와 Tool 1 응답에서만 온다.
인자(공문군·업무일)는 호출자가 명시하며 질의 문장에서 추론하지 않는다. 실패는 전부 근거 보류다(fail-closed).
실패 처리 계약(TASK-016 계획): Tool 2의 403 EVIDENCE_NOT_AVAILABLE만 후보 제외, 그 밖의 인증 실패·통신 실패·계약 위반은 전체 보류.
"""
from __future__ import annotations

import json
import re
import time
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Callable, Optional
from zoneinfo import ZoneInfo

from jsonschema import Draft202012Validator, FormatChecker

from . import decision_guard, embedding, search_messages
from .config import Settings
from .core_client import CoreClient, CoreResponse, HttpTransport, TransportTimeout, TransportUnavailable
from .es_client import EsTransport, SearchUnavailable
from .messages import NOTICES

BUSINESS_TIMEZONE = ZoneInfo("Asia/Seoul")
TOOL_SCHEMA_PATH = Path("contracts/tool-applicable-checklist.schema.json")
EVIDENCE_SCHEMA_PATH = Path("contracts/tool-rule-evidence.schema.json")
FAMILY_ID = re.compile(r"^SIN-[A-Z0-9-]+$")
RULE_VERSION_ID = re.compile(r"^policy-rule:sha256:[a-f0-9]{64}$")
QUERY_MAX = 200
TOP_K_DEFAULT = 5
TOP_K_MAX = 10
SEARCH_VERSION = "search-bm25-v1"
SEARCH_VERSIONS = {"bm25": "search-bm25-v1", "vector": "search-vector-v1", "hybrid": "search-hybrid-v1"}
SEARCH_MODES = tuple(SEARCH_VERSIONS)
HYBRID_GATES = ("either", "both", "vector")
RRF_K_DEFAULT = 60
VECTOR_FIELD = "evidence_vector"
HOLD_METHODS = ("absolute", "ratio", "combined")
DISCLAIMER = "프로젝트 시연을 위해 생성한 합성 공문의 승인 근거이며 실제 은행 내부자료가 아닙니다. 검색 후보는 Core가 다시 확인한 것만 제공합니다."


class SearchInputError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class RelevanceHold:
    """관련성 보류 기준(TASK-014 AC-05c). 구성별로 초기 점검용 자료로 정해 버전과 함께 고정한다."""

    method: str = "combined"
    min_score: str = "0"
    min_ratio: str = "0"
    version: str = "untuned"

    def keep(self, score: float, top_score: float) -> bool:
        absolute = score >= float(self.min_score)
        ratio = top_score <= 0 or (score / top_score) >= float(self.min_ratio)
        if self.method == "absolute":
            return absolute
        if self.method == "ratio":
            return ratio
        return absolute and ratio

    def as_dict(self) -> dict:
        return {"method": self.method, "min_score": self.min_score, "min_ratio": self.min_ratio, "version": self.version}


@dataclass(frozen=True)
class SearchSettings:
    index_alias: str
    hold: RelevanceHold
    diagnostics: bool
    fuzziness: Optional[str] = None  # multi_match fuzziness(예: AUTO). standard 분석기 비교용(TASK-016 제안 1). 없으면 정확 일치
    decision_guard: bool = True  # 결정 요청 판별(decision_guard.py). 평가에서 효과를 분리하려고 끌 수 있다
    min_should_match: Optional[str] = None  # multi_match minimum_should_match(예: 60%). 질의어 일치 비율 조건(관련성 확인 검토용). 없으면 조건 없음
    mode: str = "bm25"  # bm25 | vector | hybrid(BM25+kNN을 RRF로 순위 결합). 같은 조건 비교를 위해 셋 다 지원한다
    rrf_k: int = RRF_K_DEFAULT  # RRF 상수. 조정하지 않는 고정값(문헌 기본값 60)
    vector_min_cosine: str = "-1"  # 벡터 후보 관련성 하한(cosine). 조정용 자료로만 정한다. -1은 하한 없음
    gate: str = "either"  # hybrid 관련성 통과 조건: either(BM25 또는 벡터 하한 통과) | both(둘 다 통과) | vector(벡터 하한만). RRF 점수로 관련성을 정하지 않는다


def validate_request(query: Any, family_id: Any, business_date: Any, consultation_id: Any, top_k: Any, now: datetime) -> tuple[str, str, str, Optional[str], int]:
    if not isinstance(query, str) or not query.strip():
        raise SearchInputError("INVALID_QUERY", "질의는 1~200자여야 합니다.")
    query = query.strip()
    if len(query) > QUERY_MAX:
        raise SearchInputError("INVALID_QUERY", "질의는 1~200자여야 합니다.")
    if not isinstance(family_id, str) or not FAMILY_ID.match(family_id):
        # 공문군은 호출자가 명시한다. 질의 문장에서 추론하지 않는다.
        raise SearchInputError("FAMILY_ID_REQUIRED", "요청 범위(familyId)가 필요합니다.")
    if business_date is None or (isinstance(business_date, str) and not business_date.strip()):
        effective = now.astimezone(BUSINESS_TIMEZONE).date().isoformat()
    else:
        try:
            effective = date.fromisoformat(str(business_date).strip()).isoformat()
        except ValueError as error:
            raise SearchInputError("INVALID_BUSINESS_DATE", "업무일은 YYYY-MM-DD 형식이어야 합니다.") from error
    if consultation_id is not None and (not isinstance(consultation_id, str) or not consultation_id.strip() or len(consultation_id) > 64):
        raise SearchInputError("INVALID_CONSULTATION_ID", "상담 ID는 1~64자여야 합니다.")
    if top_k is None:
        top_k = TOP_K_DEFAULT
    if isinstance(top_k, bool) or not isinstance(top_k, int) or top_k < 1 or top_k > TOP_K_MAX:
        raise SearchInputError("INVALID_TOP_K", f"topK는 1~{TOP_K_MAX}여야 합니다.")
    return query, family_id, effective, consultation_id, top_k


def es_query(query: str, family_id: str, business_date: str, size: int, fuzziness: Optional[str] = None,
             min_should_match: Optional[str] = None, allowed_ids: Optional[list[str]] = None) -> dict:
    """BM25 질의 + metadata filter. 업무일은 effective_ranges(date_range)에 한 날짜를 intersects로 묻는다(ADR-013 3-1항)."""
    match: dict[str, Any] = {"query": query, "fields": ["evidence_text", "structured_text"], "operator": "or"}
    if fuzziness:
        match["fuzziness"] = fuzziness
    if min_should_match:
        match["minimum_should_match"] = min_should_match
    return {
        "size": size,
        "_source": False,
        "query": {
            "bool": {
                "must": [{"multi_match": match}],
                "filter": [
                    {"term": {"family_id": family_id}},
                    {"range": {"effective_ranges": {"gte": business_date, "lte": business_date, "relation": "intersects"}}},
                ] + ([{"ids": {"values": list(allowed_ids)}}] if allowed_ids is not None else []),
            }
        },
    }


def knn_query(vector: list[float], family_id: str, business_date: str, size: int, model: str, allowed_ids: Optional[list[str]]) -> dict:
    """벡터 kNN(같은 색인의 dense_vector). BM25와 같은 공문군·업무일·허용 ID 필터에 임베딩 모델 표식을 더한다(다른 모델 벡터와 섞지 않음)."""
    filters: list[dict] = [
        {"term": {"family_id": family_id}},
        {"range": {"effective_ranges": {"gte": business_date, "lte": business_date, "relation": "intersects"}}},
        {"term": {"embedding_model": model}},
    ]
    if allowed_ids is not None:
        filters.append({"ids": {"values": list(allowed_ids)}})
    return {"size": size, "_source": False,
            "knn": {"field": VECTOR_FIELD, "query_vector": vector, "k": size, "num_candidates": max(50, size * 5), "filter": {"bool": {"filter": filters}}}}


def cosine_from_es(score: float) -> float:
    """ES dense_vector cosine 점수 (1 + cos) / 2를 cosine으로 되돌린다."""
    return 2.0 * score - 1.0


def fuse(bm25: list[tuple[str, float]], vector: list[tuple[str, float]], settings: "SearchSettings") -> list[dict]:
    """후보별 BM25 점수·벡터 cosine·RRF 점수와 관련성 통과 여부. 순위는 모드별(bm25 점수, cosine, RRF)로 정한다.
    RRF는 순위 결합에만 쓰고 관련성 통과는 BM25 보류 기준과 벡터 cosine 하한으로만 판단한다."""
    bm25_top = bm25[0][1] if bm25 else 0.0
    bm25_rank = {rid: index + 1 for index, (rid, _) in enumerate(bm25)}
    vector_rank = {rid: index + 1 for index, (rid, _) in enumerate(vector)}
    bm25_score = dict(bm25)
    cosine = {rid: cosine_from_es(score) for rid, score in vector}
    minimum = float(settings.vector_min_cosine)
    rows = []
    for rid in list(dict.fromkeys([rid for rid, _ in bm25] + [rid for rid, _ in vector])):
        bm25_keep = rid in bm25_score and settings.hold.keep(bm25_score[rid], bm25_top)
        vector_keep = rid in cosine and cosine[rid] >= minimum
        if settings.mode == "bm25":
            keep = bm25_keep
        elif settings.mode == "vector":
            keep = vector_keep
        else:
            if settings.gate == "both":
                keep = bm25_keep and vector_keep
            elif settings.gate == "vector":
                keep = vector_keep  # 관련성은 벡터 하한으로만, 순위는 RRF
            else:
                keep = bm25_keep or vector_keep
        rrf = sum(1.0 / (settings.rrf_k + rank[rid]) for rank in (bm25_rank, vector_rank) if rid in rank)
        rows.append({"rule_version_id": rid, "bm25": bm25_score.get(rid), "cosine": cosine.get(rid), "rrf": rrf, "keep": keep})
    if settings.mode == "bm25":
        rows.sort(key=lambda row: (-(row["bm25"] or 0.0), row["rule_version_id"]))
    elif settings.mode == "vector":
        rows.sort(key=lambda row: (-(row["cosine"] if row["cosine"] is not None else -2.0), row["rule_version_id"]))
    else:
        rows.sort(key=lambda row: (-row["rrf"], row["rule_version_id"]))
    return rows


def search(query: Any, family_id: Any, business_date: Any = None, consultation_id: Any = None, top_k: Any = None, *,
           settings: Settings, search_settings: SearchSettings, es: EsTransport, transport: Optional[HttpTransport] = None,
           clock: Optional[Callable[[], datetime]] = None, grant_id: Optional[str] = None,
           embed_query: Optional[Callable[[str], tuple[str, list[float]]]] = None) -> dict:
    now = (clock or (lambda: datetime.now(timezone.utc)))()
    started = time.perf_counter()
    query, family_id, business_date, consultation_id, top_k = validate_request(query, family_id, business_date, consultation_id, top_k, now)
    root = settings.repository_root
    tool_validator = _validator(root / TOOL_SCHEMA_PATH)
    evidence_validator = _validator(root / EVIDENCE_SCHEMA_PATH)
    client = CoreClient(settings.core_base_url, settings.tool_token, None, settings.timeout_seconds, transport, grant_id=grant_id)
    hold = search_settings.hold
    diagnostics: dict[str, Any] = {"raw_candidates": [], "forwarded_candidates": [], "removed": [], "tool_1": None, "decision_guard_match": None,
                                   "tool_calls": {"applicable_checklist": 0, "rule_evidence": 0}, "timings_us": {}}

    def finish(status: str, hold_kind: Optional[str], reasons: list[str], evidence: list[dict], evaluated_at: Optional[str]) -> dict:
        diagnostics["timings_us"]["total"] = int((time.perf_counter() - started) * 1_000_000)
        output: dict[str, Any] = {
            "search_version": SEARCH_VERSIONS[search_settings.mode],
            "search_messages_hash": search_messages.search_messages_hash(),
            "synthetic": True,
            "disclaimer": DISCLAIMER,
            "query": query,
            "family_id": family_id,
            "business_date": business_date,
            "evaluated_at": evaluated_at,
            "status": status,
            "hold_kind": hold_kind,
            "hold_reasons": reasons,
            "hold_message": " ".join(search_messages.reason_message(code) for code in reasons) if reasons else None,
            "evidence": evidence,
            "notices": {
                "human_decision_notice": NOTICES["human_decision_notice"],
                "source_notice": NOTICES["source_notice"],
                "search_notice": search_messages.SEARCH_NOTICES["search_notice"],
                "hold_notice": search_messages.SEARCH_NOTICES["hold_notice"] if status == "EVIDENCE_HOLD" else None,
            },
            "relevance_hold": {**hold.as_dict(), **({} if search_settings.mode == "bm25" else {
                "mode": search_settings.mode, "gate": search_settings.gate, "vector_min_cosine": search_settings.vector_min_cosine, "rrf_k": search_settings.rrf_k})},
            "decision_guard": {"enabled": search_settings.decision_guard, "version": decision_guard.VERSION},
        }
        if consultation_id:
            output["consultation_id"] = consultation_id
        if search_settings.diagnostics:
            output["diagnostics"] = diagnostics
        return output

    # 0) 결정 요청 판별: 근거를 찾을 질문이 아니므로 ES·Core를 부르지 않고 보류한다.
    if search_settings.decision_guard:
        matched = decision_guard.detect(query)
        if matched is not None:
            diagnostics["decision_guard_match"] = matched
            return finish("EVIDENCE_HOLD", "DECISION_REQUEST", ["DECISION_REQUEST_NOT_SUPPORTED"], [], None)

    # 1) Core 재확인 1(검색보다 먼저): grant의 상담 건·공문군·업무일·workspace와 승인 상태를 Core가 확인한다.
    #    통과하지 못하면 ES를 부르지 않으므로 권한 없는 요청에는 후보 ID·점수도 생기지 않는다(R2).
    diagnostics["tool_calls"]["applicable_checklist"] += 1
    tool_started = time.perf_counter()
    try:
        checklist_response = client.applicable_checklist(family_id, business_date, consultation_id)
    except TransportTimeout:
        return finish("EVIDENCE_HOLD", "UNVERIFIED", ["CORE_TIMEOUT"], [], None)
    except TransportUnavailable:
        return finish("EVIDENCE_HOLD", "UNVERIFIED", ["CORE_UNAVAILABLE"], [], None)
    finally:
        diagnostics["timings_us"]["tool_1"] = int((time.perf_counter() - tool_started) * 1_000_000)
    outcome = _classify_tool_response(checklist_response, tool_validator)
    if outcome != "OK":
        kind, code = outcome
        return finish("EVIDENCE_HOLD", kind, [code], [], None)
    checklist = checklist_response.json
    evaluated_at = checklist.get("evaluatedAt")
    approved = checklist.get("approvedChecklist") or {}
    diagnostics["tool_1"] = {"usable": checklist["usable"], "blocking_reasons": list(checklist.get("blockingReasons") or []),
                             "decision_id": approved.get("decisionId"), "approved_checklist_version_id": approved.get("approvedChecklistVersionId"),
                             "selected_notice_id": (checklist.get("selectedNotice") or {}).get("noticeId")}
    if not checklist["usable"]:
        reasons = list(checklist.get("blockingReasons") or []) or ["USABLE_FALSE_WITHOUT_REASON"]
        return finish("EVIDENCE_HOLD", "CORE_DECISION", reasons, [], evaluated_at)
    items_by_rule = {item["sourceRuleVersionId"]: item for item in checklist["approvedChecklist"]["items"]}
    allowed_ids = sorted(items_by_rule)

    # 2) 검색 단계: ES 후보(ID·점수만). Core가 승인한 항목의 규칙 ID로만 범위를 제한한다.
    #    모드별로 BM25, 벡터 kNN, 또는 둘을 RRF로 순위 결합한다. 관련성 통과는 BM25 보류 기준·벡터 cosine 하한으로만 정한다(RRF 점수로 정하지 않음).
    size = top_k * 2
    bm25_raw: list[tuple[str, float]] = []
    vector_raw: list[tuple[str, float]] = []
    if search_settings.mode in ("bm25", "hybrid"):
        es_started = time.perf_counter()
        try:
            bm25_raw = _hits(es.search(search_settings.index_alias, es_query(query, family_id, business_date, size, search_settings.fuzziness,
                                                                             search_settings.min_should_match, allowed_ids)))
        except SearchUnavailable as error:
            return finish("EVIDENCE_HOLD", "UNVERIFIED", [error.code], [], evaluated_at)
        finally:
            diagnostics["timings_us"]["es"] = int((time.perf_counter() - es_started) * 1_000_000)
    if search_settings.mode in ("vector", "hybrid"):
        embed_started = time.perf_counter()
        try:
            model, query_vector = (embed_query or _local_query_embedding)(query)
        except embedding.EmbeddingUnavailable:
            return finish("EVIDENCE_HOLD", "UNVERIFIED", ["EMBEDDING_UNAVAILABLE"], [], evaluated_at)
        finally:
            diagnostics["timings_us"]["embed"] = int((time.perf_counter() - embed_started) * 1_000_000)
        knn_started = time.perf_counter()
        try:
            vector_raw = _hits(es.search(search_settings.index_alias, knn_query(query_vector, family_id, business_date, size, model, allowed_ids)))
        except SearchUnavailable as error:
            return finish("EVIDENCE_HOLD", "UNVERIFIED", [error.code], [], evaluated_at)
        finally:
            diagnostics["timings_us"]["knn"] = int((time.perf_counter() - knn_started) * 1_000_000)
        if allowed_ids and not vector_raw:
            # 허용 문서가 있는데 kNN이 하나도 못 찾으면 색인에 같은 모델의 벡터가 없다(모델 불일치·벡터 미색인). 섞어 쓰지 않고 보류한다.
            return finish("EVIDENCE_HOLD", "UNVERIFIED", ["EMBEDDING_INDEX_MISMATCH"], [], evaluated_at)
    rows = fuse(bm25_raw, vector_raw, search_settings)
    diagnostics["raw_candidates"] = [_candidate(row, search_settings.mode) for row in rows]
    kept = [row for row in rows if row["keep"]]
    forwarded = [(row["rule_version_id"], _rank_score(row, search_settings.mode)) for row in kept[:top_k]]
    forwarded_ids = {rid for rid, _ in forwarded}
    for row in rows:
        if row["rule_version_id"] not in forwarded_ids:
            diagnostics["removed"].append({"rule_version_id": row["rule_version_id"], "stage": "HOLD",
                                           "reason": "BEYOND_TOP_K" if row["keep"] else "RELEVANCE_BELOW_THRESHOLD"})
    diagnostics["forwarded_candidates"] = [_candidate(row, search_settings.mode) for row in kept[:top_k]]
    if not forwarded:
        return finish("EVIDENCE_HOLD", "NO_CANDIDATE", ["NO_RELEVANT_CANDIDATE"], [], evaluated_at)

    # 3) Core 재확인 2: 후보마다 Tool 2. 정상 판정(EVIDENCE_NOT_AVAILABLE)만 제외하고 그 밖의 실패는 전체 보류.
    evidence: list[dict] = []
    tool2_started = time.perf_counter()
    for rid, score in forwarded:
        diagnostics["tool_calls"]["rule_evidence"] += 1
        try:
            evidence_response = client.rule_evidence(family_id, rid, consultation_id)
        except TransportTimeout:
            return finish("EVIDENCE_HOLD", "UNVERIFIED", ["CORE_TIMEOUT"], [], evaluated_at)
        except TransportUnavailable:
            return finish("EVIDENCE_HOLD", "UNVERIFIED", ["CORE_UNAVAILABLE"], [], evaluated_at)
        verdict = _classify_evidence_response(evidence_response, evidence_validator, rid)
        if verdict == "OK":
            item = items_by_rule.get(rid)
            if item is None:
                # Tool 2가 통과시켰는데 Tool 1 항목에 없는 경우: 두 응답이 어긋나므로 계약 위반으로 전체 보류.
                return finish("EVIDENCE_HOLD", "UNVERIFIED", ["TOOL_RESPONSE_INVALID"], [], evaluated_at)
            body = evidence_response.json
            evidence.append({
                "rank": len(evidence) + 1,
                "rule_version_id": rid,
                "rule_key": item["ruleKey"],
                "score": _score(score),
                "instruction": item["instruction"],
                "evidence_required": item["evidenceRequired"],
                "structured_change": item["structuredChange"],
                "evidence": {"notice_id": body["noticeId"], "evidence_text": body["evidenceText"], "json_pointer": body["jsonPointer"],
                             "evidence_hash": body["evidenceHash"]},
            })
        elif verdict == "EXCLUDE":
            diagnostics["removed"].append({"rule_version_id": rid, "stage": "CORE", "reason": "EVIDENCE_NOT_AVAILABLE"})
        else:
            kind, code = verdict
            return finish("EVIDENCE_HOLD", kind, [code], [], evaluated_at)
    diagnostics["timings_us"]["tool_2"] = int((time.perf_counter() - tool2_started) * 1_000_000)
    if not evidence:
        return finish("EVIDENCE_HOLD", "NO_CANDIDATE", ["NO_RELEVANT_CANDIDATE"], [], evaluated_at)
    return finish("EVIDENCE", None, [], evidence, evaluated_at)


def _classify_tool_response(response: CoreResponse, validator: Draft202012Validator):
    if response.status in (401, 403):
        return ("UNVERIFIED", "TOOL_AUTH_FAILED")
    if response.status == 404 and response.problem_code == "POLICY_FAMILY_NOT_FOUND":
        return ("CORE_DECISION", "POLICY_FAMILY_NOT_FOUND")
    if response.status >= 500:
        return ("UNVERIFIED", "CORE_UNAVAILABLE")
    if response.status != 200 or not isinstance(response.json, dict):
        return ("UNVERIFIED", "TOOL_RESPONSE_INVALID")
    if next(validator.iter_errors(response.json), None) is not None:
        return ("UNVERIFIED", "TOOL_RESPONSE_INVALID")
    return "OK"


def _classify_evidence_response(response: CoreResponse, validator: Draft202012Validator, rule_version_id: str):
    """HTTP 상태와 Core 오류 코드를 함께 본다. 403이라도 코드가 EVIDENCE_NOT_AVAILABLE일 때만 '정상 판정에 의한 제외'다."""
    if response.status == 403 and response.problem_code == "EVIDENCE_NOT_AVAILABLE":
        return "EXCLUDE"
    if response.status in (401, 403):
        return ("UNVERIFIED", "TOOL_AUTH_FAILED")
    if response.status >= 500:
        return ("UNVERIFIED", "CORE_UNAVAILABLE")
    if response.status != 200 or not isinstance(response.json, dict):
        return ("UNVERIFIED", "TOOL_RESPONSE_INVALID")
    if next(validator.iter_errors(response.json), None) is not None or response.json.get("ruleVersionId") != rule_version_id:
        return ("UNVERIFIED", "TOOL_RESPONSE_INVALID")
    return "OK"


def _validator(schema_path: Path) -> Draft202012Validator:
    with schema_path.open(encoding="utf-8") as source:
        return Draft202012Validator(json.load(source), format_checker=FormatChecker())


def _score(value: float) -> str:
    return f"{value:.6f}"


def _hits(response: dict) -> list[tuple[str, float]]:
    hits: list[tuple[str, float]] = []
    for hit in (response.get("hits") or {}).get("hits") or []:
        rule_id = hit.get("_id")
        score = hit.get("_score")
        if isinstance(rule_id, str) and RULE_VERSION_ID.match(rule_id) and isinstance(score, (int, float)):
            hits.append((rule_id, float(score)))
    return hits


def _rank_score(row: dict, mode: str) -> float:
    """정렬에 쓴 점수(기록용, 0 이상): bm25는 BM25 점수, vector는 ES cosine 점수 (1+cos)/2, hybrid는 RRF 점수."""
    if mode == "bm25":
        return row["bm25"] or 0.0
    if mode == "vector":
        return (1.0 + row["cosine"]) / 2.0 if row["cosine"] is not None else 0.0
    return row["rrf"]


def _candidate(row: dict, mode: str) -> dict:
    candidate = {"rule_version_id": row["rule_version_id"], "score": _score(_rank_score(row, mode))}
    if mode != "bm25":
        if row["bm25"] is not None:
            candidate["bm25_score"] = _score(row["bm25"])
        if row["cosine"] is not None:
            candidate["vector_cosine"] = _score(row["cosine"])
        candidate["rrf_score"] = _score(row["rrf"])
    return candidate


def _local_query_embedding(query: str) -> tuple[str, list[float]]:
    embedder = embedding.get_embedder()
    return embedder.tag, embedder.encode([query], "query")[0]
