"""환경변수 기반 설정. 업무 DB 자격증명 항목은 존재하지 않는다(CLAUDE.md: AI 서비스는 업무 DB에 접근하지 않는다).

토큰 값은 어디에도 출력·기록하지 않는다. 설정 누락은 시작 거부(종료 코드 4)다.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Optional

ENV_CORE_BASE_URL = "TRUST_AGENT_CORE_BASE_URL"
ENV_TOOL_TOKEN = "TRUST_AGENT_TOOL_SERVICE_TOKEN"
ENV_RECORD_TOKEN = "TRUST_AGENT_PREPARATION_RECORD_TOKEN"
ENV_TIMEOUT_SECONDS = "TRUST_AGENT_CORE_TIMEOUT_SECONDS"
ENV_REPOSITORY_ROOT = "TRUST_AGENT_REPOSITORY_ROOT"

# 이 서비스가 읽는 환경변수 전체. DB 접속 정보는 없다(테스트가 이 목록을 검사한다).
ENV_NAMES = (ENV_CORE_BASE_URL, ENV_TOOL_TOKEN, ENV_RECORD_TOKEN, ENV_TIMEOUT_SECONDS, ENV_REPOSITORY_ROOT)
REQUIRED_ENV_NAMES = (ENV_CORE_BASE_URL, ENV_TOOL_TOKEN)
DEFAULT_TIMEOUT_SECONDS = 5.0
DEFAULT_REPOSITORY_ROOT = Path(__file__).resolve().parents[3]


class SettingsError(ValueError):
    """필수 설정 누락 또는 형식 오류. 메시지에 설정 이름만 있고 값은 없다."""

    def __init__(self, code: str, message: str, missing: Optional[list[str]] = None) -> None:
        super().__init__(message)
        self.code = code
        self.missing = missing or []


@dataclass(frozen=True)
class Settings:
    core_base_url: str
    tool_token: str
    record_token: Optional[str]
    timeout_seconds: float
    repository_root: Path

    def masked(self) -> dict:
        """로그·출력용. 토큰 값 대신 설정 여부만 보여 준다."""
        return {
            "core_base_url": self.core_base_url,
            "tool_token_configured": bool(self.tool_token),
            "record_token_configured": bool(self.record_token),
            "timeout_seconds": self.timeout_seconds,
            "repository_root": str(self.repository_root),
        }


def load_settings(environ: Optional[Mapping[str, str]] = None) -> Settings:
    env = os.environ if environ is None else environ
    missing = [name for name in REQUIRED_ENV_NAMES if not (env.get(name) or "").strip()]
    if missing:
        raise SettingsError("SETTINGS_MISSING", "필수 설정이 없습니다: " + ", ".join(missing), missing)
    raw_timeout = (env.get(ENV_TIMEOUT_SECONDS) or "").strip()
    try:
        timeout = float(raw_timeout) if raw_timeout else DEFAULT_TIMEOUT_SECONDS
    except ValueError as error:
        raise SettingsError("SETTINGS_INVALID", f"{ENV_TIMEOUT_SECONDS}는 숫자여야 합니다.") from error
    if timeout <= 0:
        raise SettingsError("SETTINGS_INVALID", f"{ENV_TIMEOUT_SECONDS}는 0보다 커야 합니다.")
    root_value = (env.get(ENV_REPOSITORY_ROOT) or "").strip()
    repository_root = Path(root_value).resolve() if root_value else DEFAULT_REPOSITORY_ROOT
    record_token = (env.get(ENV_RECORD_TOKEN) or "").strip() or None
    return Settings(
        core_base_url=env[ENV_CORE_BASE_URL].strip().rstrip("/"),
        tool_token=env[ENV_TOOL_TOKEN].strip(),
        record_token=record_token,
        timeout_seconds=timeout,
        repository_root=repository_root,
    )


# ---- 검색 설정(TASK-016). 준비안(prepare)에는 필요 없고 검색 API에만 쓴다. ----
ENV_ES_URL = "TRUST_AGENT_ES_URL"
ENV_ES_SEARCH_USERNAME = "TRUST_AGENT_ES_SEARCH_USERNAME"
ENV_ES_SEARCH_PASSWORD = "TRUST_AGENT_ES_SEARCH_PASSWORD"
ENV_ES_TIMEOUT_SECONDS = "TRUST_AGENT_ES_TIMEOUT_SECONDS"
ENV_SEARCH_INDEX_ALIAS = "TRUST_AGENT_SEARCH_INDEX_ALIAS"
ENV_SEARCH_HOLD_METHOD = "TRUST_AGENT_SEARCH_HOLD_METHOD"
ENV_SEARCH_HOLD_MIN_SCORE = "TRUST_AGENT_SEARCH_HOLD_MIN_SCORE"
ENV_SEARCH_HOLD_MIN_RATIO = "TRUST_AGENT_SEARCH_HOLD_MIN_RATIO"
ENV_SEARCH_HOLD_VERSION = "TRUST_AGENT_SEARCH_HOLD_VERSION"
ENV_SEARCH_DIAGNOSTICS = "TRUST_AGENT_SEARCH_DIAGNOSTICS"
ENV_SEARCH_FUZZINESS = "TRUST_AGENT_SEARCH_FUZZINESS"
SEARCH_ENV_NAMES = (ENV_ES_URL, ENV_ES_SEARCH_USERNAME, ENV_ES_SEARCH_PASSWORD, ENV_ES_TIMEOUT_SECONDS, ENV_SEARCH_INDEX_ALIAS,
                    ENV_SEARCH_HOLD_METHOD, ENV_SEARCH_HOLD_MIN_SCORE, ENV_SEARCH_HOLD_MIN_RATIO, ENV_SEARCH_HOLD_VERSION, ENV_SEARCH_DIAGNOSTICS, ENV_SEARCH_FUZZINESS)
DEFAULT_SEARCH_INDEX_ALIAS = "trustagent-rule-evidence-main-current"


@dataclass(frozen=True)
class EsSettings:
    base_url: str
    username: Optional[str]
    password: Optional[str]
    timeout_seconds: float


def load_es_settings(environ: Optional[Mapping[str, str]] = None) -> EsSettings:
    env = os.environ if environ is None else environ
    raw_timeout = (env.get(ENV_ES_TIMEOUT_SECONDS) or "").strip()
    try:
        timeout = float(raw_timeout) if raw_timeout else DEFAULT_TIMEOUT_SECONDS
    except ValueError as error:
        raise SettingsError("SETTINGS_INVALID", f"{ENV_ES_TIMEOUT_SECONDS}는 숫자여야 합니다.") from error
    if timeout <= 0:
        raise SettingsError("SETTINGS_INVALID", f"{ENV_ES_TIMEOUT_SECONDS}는 0보다 커야 합니다.")
    return EsSettings(
        base_url=(env.get(ENV_ES_URL) or "http://127.0.0.1:9200").strip().rstrip("/"),
        username=(env.get(ENV_ES_SEARCH_USERNAME) or "").strip() or None,
        password=(env.get(ENV_ES_SEARCH_PASSWORD) or "").strip() or None,
        timeout_seconds=timeout,
    )


def load_search_settings(environ: Optional[Mapping[str, str]] = None):
    """관련성 보류 기준과 진단 모드. 값이 없으면 보류 없음(version untuned)이며 평가 스크립트는 untuned를 거부한다."""
    from .search import RelevanceHold, SearchSettings  # 순환 import 회피

    env = os.environ if environ is None else environ
    method = (env.get(ENV_SEARCH_HOLD_METHOD) or "combined").strip()
    if method not in ("absolute", "ratio", "combined"):
        raise SettingsError("SETTINGS_INVALID", f"{ENV_SEARCH_HOLD_METHOD}는 absolute, ratio, combined 중 하나여야 합니다.")
    min_score = (env.get(ENV_SEARCH_HOLD_MIN_SCORE) or "0").strip()
    min_ratio = (env.get(ENV_SEARCH_HOLD_MIN_RATIO) or "0").strip()
    for name, value in ((ENV_SEARCH_HOLD_MIN_SCORE, min_score), (ENV_SEARCH_HOLD_MIN_RATIO, min_ratio)):
        try:
            if float(value) < 0:
                raise ValueError
        except ValueError as error:
            raise SettingsError("SETTINGS_INVALID", f"{name}는 0 이상의 숫자여야 합니다.") from error
    return SearchSettings(
        index_alias=(env.get(ENV_SEARCH_INDEX_ALIAS) or DEFAULT_SEARCH_INDEX_ALIAS).strip(),
        hold=RelevanceHold(method=method, min_score=min_score, min_ratio=min_ratio, version=(env.get(ENV_SEARCH_HOLD_VERSION) or "untuned").strip()),
        diagnostics=(env.get(ENV_SEARCH_DIAGNOSTICS) or "").strip() == "1",
        fuzziness=_fuzziness(env.get(ENV_SEARCH_FUZZINESS)),
    )


def _fuzziness(value: Optional[str]) -> Optional[str]:
    text = (value or "").strip().upper()
    if not text:
        return None
    if text not in ("AUTO", "0", "1", "2"):
        raise SettingsError("SETTINGS_INVALID", f"{ENV_SEARCH_FUZZINESS}는 AUTO, 0, 1, 2 중 하나여야 합니다.")
    return text
