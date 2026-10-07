"""ID와 해시. 준비안 ID는 업무 내용의 canonical sha256이고 실행 ID는 실행마다 새 값이다(TASK-015 3절, ADR-012 7항)."""
from __future__ import annotations

import copy
import hashlib
import json
import uuid
from typing import Any

ASSEMBLER_VERSION = "preparation-assembler-v1"
PREPARATION_ID_PREFIX = "consultation-preparation:"
RUN_ID_PREFIX = "consultation-preparation-run:"

# preparation_id 계산에서 빼는 것: 실행마다 바뀌거나 추적용인 값. Core가 같은 규칙으로 다시 계산해 대조한다.
HASH_EXCLUDED_TOP_LEVEL = ("preparation_id", "run_id", "consultation_id")
HASH_EXCLUDED_SECTION = ("evaluated_at", "tool_response_hash")  # Tool 응답에는 평가 시각이 들어 있어 해시가 매번 달라진다


def canonical_json(value: Any) -> str:
    """저장소 공통 canonical 규칙(CanonicalJsonHasher와 같음). 부동소수점은 허용하지 않는다."""
    _reject_floating_point(value)
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_of(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def hash_subject(record_body: dict) -> dict:
    subject = copy.deepcopy(record_body)
    for key in HASH_EXCLUDED_TOP_LEVEL:
        subject.pop(key, None)
    for section in subject.get("sections", []):
        for key in HASH_EXCLUDED_SECTION:
            section.pop(key, None)
    return subject


def preparation_id(record_body: dict) -> str:
    return PREPARATION_ID_PREFIX + sha256_of(hash_subject(record_body))


def new_run_id() -> str:
    return RUN_ID_PREFIX + uuid.uuid4().hex


def _reject_floating_point(item: Any) -> None:
    if isinstance(item, float):
        raise ValueError("FLOATING_POINT_NOT_ALLOWED")
    if isinstance(item, dict):
        for child in item.values():
            _reject_floating_point(child)
    elif isinstance(item, list):
        for child in item:
            _reject_floating_point(child)
