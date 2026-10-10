"""검색 응답 고정 문구. 준비안 문구 표(messages.py)와 분리해 준비안 ID(messages_hash)에 영향을 주지 않는다."""
from __future__ import annotations

from .ids import sha256_of
from .messages import CORE_REASON_MESSAGES, NOTICES, SERVICE_REASON_MESSAGES

SEARCH_NOTICES: dict[str, str] = {
    "search_notice": "후보는 검색이 골랐고 사용 가능 여부와 내용은 Core가 정했습니다. 검색 색인은 업무 원장이 아닙니다.",
    "hold_notice": "제공할 수 있는 근거가 없습니다. 검색 결과로 처리하지 말고 Core의 적용 공문 조회나 수기 checklist로 확인하세요.",
}

SEARCH_REASON_MESSAGES: dict[str, str] = {
    "SEARCH_UNAVAILABLE": "검색 엔진에 연결하지 못해 근거를 찾지 못했습니다. 잠시 뒤 다시 시도하세요.",
    "SEARCH_TIMEOUT": "검색 엔진 응답이 시간 안에 오지 않았습니다. 잠시 뒤 다시 시도하세요.",
    "NO_RELEVANT_CANDIDATE": "질문과 관련된 승인 근거 후보가 없습니다.",
    "DECISION_REQUEST_NOT_SUPPORTED": "대출 승인·거절, 금리·한도 확정, 신용등급 결정은 AI가 하지 않으며 담당자와 결재 절차가 정합니다. 확인할 규정이 있으면 규정 내용을 묻는 질문으로 다시 검색하세요.",
}


def reason_message(code: str) -> str:
    for table in (CORE_REASON_MESSAGES, SERVICE_REASON_MESSAGES, SEARCH_REASON_MESSAGES):
        if code in table:
            return table[code]
    return NOTICES["unknown_reason"].format(code=code)


def search_messages_hash() -> str:
    return sha256_of({"search_notices": SEARCH_NOTICES, "search_reasons": SEARCH_REASON_MESSAGES})
