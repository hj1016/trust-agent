from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta


FRESHNESS_PRIORITY = (
    "UNAVAILABLE",
    "UNCONFIRMED_AFTER_FAILURE",
    "PENDING_EXTRACTION",
    "STALE",
    "CONFIRMED",
)


@dataclass(frozen=True)
class FreshnessProjection:
    freshness_status: str
    last_confirmed_at: datetime | None
    blocking_reasons: tuple[str, ...]
    warning_reasons: tuple[str, ...]


def evaluate_freshness(
    *,
    now: datetime,
    max_confirmation_age: timedelta,
    last_confirmed_at: datetime | None,
    latest_observation_at: datetime | None,
    latest_observation_extraction_status: str | None,
    latest_collection_status: str | None,
) -> FreshnessProjection:
    """최신 Observation의 처리 상태로 조회 freshness를 계산합니다.

    ``latest_observation_extraction_status``는 전체 상품에서 가장 최근에 실행된 추출
    시도가 아니라 ``latest_observation_at``이 가리키는 Observation의 최신 추출 시도
    상태입니다. 그 Observation에 대한 시도가 아직 없으면 ``None``이어야 합니다.
    """
    values = [value for value in (now, last_confirmed_at, latest_observation_at) if value]
    if any(value.tzinfo is None or value.utcoffset() is None for value in values):
        raise ValueError("freshness date-time은 timezone-aware 값이어야 합니다.")
    if max_confirmation_age <= timedelta(0):
        raise ValueError("max_confirmation_age는 0보다 커야 합니다.")

    reasons = []
    conditions = set()
    if last_confirmed_at is None:
        conditions.add("UNAVAILABLE")
        reasons.append("NO_CONFIRMED_TERMS")
    else:
        if now - last_confirmed_at > max_confirmation_age:
            conditions.add("STALE")
            reasons.append("CONFIRMATION_AGE_EXCEEDED")

        has_new_observation = (
            latest_observation_at is not None
            and latest_observation_at > last_confirmed_at
        )
        if has_new_observation and latest_observation_extraction_status == "FAILED":
            conditions.add("UNCONFIRMED_AFTER_FAILURE")
            reasons.append("LATEST_EXTRACTION_FAILED")
        elif has_new_observation and latest_observation_extraction_status is None:
            conditions.add("PENDING_EXTRACTION")
            reasons.append("LATEST_OBSERVATION_NOT_EXTRACTED")

    warnings = []
    if latest_collection_status == "FAILED":
        warnings.append("LATEST_COLLECTION_FAILED")
    if not conditions:
        conditions.add("CONFIRMED")
    representative = next(item for item in FRESHNESS_PRIORITY if item in conditions)
    return FreshnessProjection(
        representative, last_confirmed_at, tuple(reasons), tuple(warnings)
    )
