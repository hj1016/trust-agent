"""AI 호출 계측(TASK-021). 기존 HttpTransport를 감싸 호출마다 종류, 공문군, 결과, 소요 시간을 기록한다.

준비안 출력·기록 계약은 바꾸지 않는다. 계측 결과는 별도 파일(--metrics-file)에만 쓰며 토큰, 근거 원문, 항목 문장은 담지 않는다.
시간은 정수 마이크로초(elapsed_us)로 기록해 부동소수점을 피한다. 일괄 조회·병렬화·캐시는 없다(측정 전에는 바꾸지 않는다).
"""
from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Optional

from .core_client import RECORD_PATH, TOOL_PATH, HttpTransport, TransportResponse, TransportTimeout, TransportUnavailable

METRICS_VERSION = "ai-call-metrics-v1"
CALL_KINDS = ("applicable_checklist", "rule_evidence", "record")


def call_kind(url: str) -> str:
    if url.endswith(TOOL_PATH + "applicable_checklist"):
        return "applicable_checklist"
    if url.endswith(TOOL_PATH + "rule_evidence"):
        return "rule_evidence"
    if url.endswith(RECORD_PATH):
        return "record"
    return "unknown"


class TimingTransport:
    """전송 계층 장식자. 응답과 예외를 그대로 돌려주고 호출 기록만 남긴다."""

    def __init__(self, inner: HttpTransport) -> None:
        self._inner = inner
        self.calls: list[dict] = []

    def send(self, method: str, url: str, headers: Mapping[str, str], body: Optional[bytes], timeout: float) -> TransportResponse:
        kind = call_kind(url)
        family_id = _family_id(body) if kind != "record" else None
        started = time.perf_counter()
        try:
            response = self._inner.send(method, url, headers, body, timeout)
        except TransportTimeout:
            self._record(kind, family_id, "TIMEOUT", None, started)
            raise
        except TransportUnavailable:
            self._record(kind, family_id, "UNAVAILABLE", None, started)
            raise
        outcome = "OK" if 200 <= response.status < 300 else "HTTP_ERROR"
        self._record(kind, family_id, outcome, response.status, started)
        return response

    def _record(self, kind: str, family_id: Optional[str], outcome: str, status: Optional[int], started: float) -> None:
        self.calls.append({
            "seq": len(self.calls) + 1,
            "kind": kind,
            "family_id": family_id,
            "outcome": outcome,
            "http_status": status,
            "elapsed_us": int((time.perf_counter() - started) * 1_000_000),
        })


def _family_id(body: Optional[bytes]) -> Optional[str]:
    if not body:
        return None
    try:
        payload = json.loads(body.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None
    value = payload.get("familyId") if isinstance(payload, dict) else None
    return value if isinstance(value, str) else None


def metrics_document(preparation: Mapping[str, Any], transport: TimingTransport, started_at: datetime, total_elapsed_us: int) -> dict:
    """준비안 결과(식별자와 상태만)와 호출 기록으로 계측 문서를 만든다."""
    calls = [dict(call) for call in transport.calls]
    call_sum = sum(call["elapsed_us"] for call in calls)
    counts = {kind: sum(1 for call in calls if call["kind"] == kind) for kind in CALL_KINDS}
    counts["total"] = len(calls)
    record = preparation["record"]
    document = {
        "metrics_version": METRICS_VERSION,
        "run_id": preparation["run_id"],
        "preparation_id": preparation["preparation_id"],
        "application_id": preparation["application"]["application_id"],
        "business_date": preparation["business_date"],
        "status": preparation["status"],
        "record_status": record["status"],
        "recorded": bool(record["recorded"]),
        "started_at": started_at.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
        "total_elapsed_us": total_elapsed_us,
        "call_elapsed_us_sum": call_sum,
        "assembly_elapsed_us": max(total_elapsed_us - call_sum, 0),
        "counts": counts,
        "calls": calls,
    }
    if preparation.get("consultation_id"):
        document["consultation_id"] = preparation["consultation_id"]
    return document


def write_metrics(path: Path, document: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as target:
        json.dump(document, target, ensure_ascii=False, indent=2)
        target.write("\n")
