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


@dataclass(frozen=True)
class ConfirmationProjection:
    freshness: FreshnessProjection
    historical_query: bool
    public_evidence_confirmation_allowed: bool
    confirmation_blocking_reasons: tuple[str, ...]


def evaluate_freshness(
    *,
    now: datetime,
    max_confirmation_age: timedelta,
    last_confirmed_at: datetime | None,
    latest_observation_at: datetime | None,
    latest_observation_extraction_status: str | None,
    latest_collection_status: str | None,
    has_newer_observation: bool | None = None,
) -> FreshnessProjection:
    """최신 Observation의 처리 상태로 조회 freshness를 계산합니다.

    ``latest_observation_extraction_status``는 전체 상품에서 가장 최근에 실행된 추출
    시도가 아니라 ``latest_observation_at``이 가리키는 Observation의 최신 추출 시도
    상태입니다. 그 Observation에 대한 시도가 아직 없으면 ``None``이어야 합니다.
    ``has_newer_observation``은 동일한 관측 시각의 서로 다른 Observation을 ID로 구분해야
    하는 조회 계층이 명시적으로 전달합니다. 생략하면 기존 시각 비교를 사용합니다.
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
            has_newer_observation
            if has_newer_observation is not None
            else latest_observation_at is not None
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


def evaluate_confirmation_policy(
    *,
    evaluated_at: datetime,
    as_of: datetime,
    max_confirmation_age: timedelta,
    last_confirmed_at: datetime | None,
    latest_observation_at: datetime | None,
    has_newer_observation: bool,
    latest_observation_extraction_status: str | None,
    latest_collection_status: str | None,
) -> ConfirmationProjection:
    if evaluated_at.tzinfo is None or evaluated_at.utcoffset() is None:
        raise ValueError("evaluated_at은 timezone-aware 값이어야 합니다.")
    if as_of.tzinfo is None or as_of.utcoffset() is None:
        raise ValueError("as_of는 timezone-aware 값이어야 합니다.")
    if as_of > evaluated_at:
        raise ValueError("FUTURE_AS_OF_NOT_ALLOWED")

    freshness = evaluate_freshness(
        now=evaluated_at,
        max_confirmation_age=max_confirmation_age,
        last_confirmed_at=last_confirmed_at,
        latest_observation_at=latest_observation_at,
        latest_observation_extraction_status=latest_observation_extraction_status,
        latest_collection_status=latest_collection_status,
        has_newer_observation=has_newer_observation,
    )
    historical_query = as_of < evaluated_at
    confirmation_reasons = list(freshness.blocking_reasons)
    if historical_query:
        confirmation_reasons.append("HISTORICAL_AS_OF")
    return ConfirmationProjection(
        freshness=freshness,
        historical_query=historical_query,
        public_evidence_confirmation_allowed=(
            not historical_query and freshness.freshness_status == "CONFIRMED"
        ),
        confirmation_blocking_reasons=tuple(confirmation_reasons),
    )
