"""평가 전용 기록(로컬 평가 하네스에서만). 운영 응답·Core 감사 기록과 분리한다.

TRUST_AGENT_EVAL_TRACE_DIR가 있고 TRUST_AGENT_AI_DEPLOYMENT=local일 때만 쓴다(운영 기동에서는 설정 자체를 거부한다: config.check_startup).
한 답변 요청마다 JSONL 한 줄: 추적 ID(X-Trace-Id), 질문 sha256(원문 없음), 검색 결과 요약(상태·보류 사유·근거 ID·단계별 시간·후보 진단),
생성 초안(보류된 초안 포함), 코드 검증·LLM 검증 결과, LLM 호출별 성공·실패·토큰·시간, 단계별 시간.
검증 전(초안)과 검증 후(최종 상태)를 같은 줄에서 비교할 수 있게 한다. 검증에서 막힌 초안은 이 기록에만 있고 직원 화면으로 가지 않는다.
"""
from __future__ import annotations

import fcntl
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Optional

ENV_TRACE_DIR = "TRUST_AGENT_EVAL_TRACE_DIR"
TRACE_FILE = "answer-trace.jsonl"


def trace_dir(environ: Optional[Mapping[str, str]] = None) -> Optional[Path]:
    from .config import DEPLOYMENT_LOCAL, ENV_DEPLOYMENT

    env = os.environ if environ is None else environ
    raw = (env.get(ENV_TRACE_DIR) or "").strip()
    if not raw or (env.get(ENV_DEPLOYMENT) or "").strip() != DEPLOYMENT_LOCAL:
        return None
    return Path(raw)


def record(trace_id: Optional[str], question: str, family_id: str, search_result: dict, rag_result: dict, wall_ms: int) -> dict:
    diagnostics = search_result.get("diagnostics") or {}
    held = rag_result.get("_eval") or {}
    return {
        "recorded_at": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
        "trace_id": trace_id,
        "question_sha256": "sha256:" + hashlib.sha256(question.encode("utf-8")).hexdigest(),
        "family_id": family_id,
        "business_date": rag_result.get("business_date"),
        "search": {"status": search_result.get("status"), "hold_kind": search_result.get("hold_kind"), "hold_reasons": search_result.get("hold_reasons") or [],
                   "search_version": search_result.get("search_version"), "relevance_hold": search_result.get("relevance_hold"),
                   "decision_guard": search_result.get("decision_guard"),
                   "evidence_ids": [e["rule_version_id"] for e in search_result.get("evidence") or []],
                   "raw_candidates": diagnostics.get("raw_candidates"), "forwarded_candidates": diagnostics.get("forwarded_candidates"),
                   "decision_guard_match": diagnostics.get("decision_guard_match"), "timings_us": diagnostics.get("timings_us")},
        "status": rag_result.get("status"),
        "reasons": rag_result.get("reasons") or [],
        "draft": held.get("draft"),
        "verification": held.get("verification"),
        "answer_released": rag_result.get("answer") is not None,
        "model": rag_result.get("model"),
        "prompts": rag_result.get("prompts"),
        "rag_version": rag_result.get("rag_version"),
        "model_business_date": rag_result.get("model_business_date"),
        "llm_calls": rag_result.get("llm_calls"),
        "llm_call_log": rag_result.get("llm_call_log") or [],
        "usage": rag_result.get("usage"),
        "timings_ms": {**(rag_result.get("timings_ms") or {}), "ai_service_total": wall_ms},
    }


def write(directory: Path, entry: dict) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    line = json.dumps(entry, ensure_ascii=False, sort_keys=True) + "\n"
    with (directory / TRACE_FILE).open("a", encoding="utf-8") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        try:
            handle.write(line)
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


def strip(rag_result: dict) -> dict[str, Any]:
    """HTTP 응답에서 평가 전용 필드를 뺀다(초안·LLM 검증 원문은 응답으로 나가지 않는다)."""
    rag_result.pop("_eval", None)
    return rag_result
