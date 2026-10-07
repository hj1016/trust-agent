"""고정 문구 표. 준비안의 모든 자유 문장은 이 표, 승인 checklist 항목의 지시 문장, 근거 Tool의 규칙 원문, 구조화 값에서만 온다.

결정(승인·거절·한도·금리·신용등급)을 뜻하는 표현은 두지 않는다(SAFE-B). messages_hash는 이 표 전체의 canonical sha256이다.
"""
from __future__ import annotations

from .ids import sha256_of

# Core(InternalPolicyApplicableService, Tool API)가 내는 사유 코드. 사람이 읽는 설명으로 바꾸되 코드 자체는 바꾸지 않는다.
CORE_REASON_MESSAGES: dict[str, str] = {
    "HUMAN_REVIEW_PENDING": "변경안이 담당 부서의 검토 대기 상태라 승인 checklist를 아직 쓸 수 없습니다.",
    "HUMAN_DECISION_MISSING": "사람 검토 결정이 없는 checklist라 쓸 수 없습니다.",
    "FIXTURE_CHECKLIST_NOT_APPROVED": "이 업무일에는 테스트용 예시 checklist만 있어 업무에 쓸 수 없습니다.",
    "EFFECTIVE_NOTICE_WITHDRAWN": "적용 공문이 철회되어 checklist를 쓸 수 없습니다.",
    "PROPOSAL_REJECTED": "변경안이 반려되어 checklist를 쓸 수 없습니다.",
    "VALIDATION_FAILED": "변경안 자동 검증이 실패해 checklist를 쓸 수 없습니다.",
    "VALIDATION_STALE": "변경안 검증 결과가 오래되어 재검증 전에는 쓸 수 없습니다.",
    "CHECKLIST_VALIDATION_PENDING": "변경안 자동 검증이 아직 끝나지 않았습니다.",
    "AMBIGUOUS_EFFECTIVE_NOTICE": "적용 공문을 하나로 고를 수 없어(중첩 또는 끊긴 연결) 쓸 수 없습니다.",
    "APPROVED_CHECKLIST_NOTICE_MISMATCH": "적용 일정의 checklist가 선택된 공문과 맞지 않아 쓸 수 없습니다.",
    "FUTURE_BUSINESS_DATE": "미래 업무일로는 checklist를 쓸 수 없습니다.",
    "HISTORICAL_KNOWN_AT": "과거 기준 시각 조회는 업무 사용이 허용되지 않습니다.",
    "NO_APPLICABLE_NOTICE": "이 업무일에 적용되는 공문이 없습니다.",
    "NOTICE_NOT_YET_EFFECTIVE": "공문이 아직 시행 전이라 쓸 수 없습니다.",
    "POLICY_EXTRACTION_FAILED": "공문 구조화가 실패해 checklist를 만들 수 없습니다.",
    "POLICY_EXTRACTION_PENDING": "공문 구조화가 아직 끝나지 않았습니다.",
    "POLICY_FAMILY_NOT_FOUND": "Core에 없는 공문군입니다.",
    "PUBLIC_EVIDENCE_UNCONFIRMED": "필수 공개 근거가 확인되지 않아 쓸 수 없습니다.",
    "INFORMATIONAL_PUBLIC_EVIDENCE_UNCONFIRMED": "참고용 공개 근거가 확인되지 않았습니다(사용은 가능).",
    "RETROACTIVE_NOTICE": "소급 적용 공문입니다(사용은 가능).",
    "INVALID_BUSINESS_DATE": "업무일 형식이 올바르지 않습니다.",
    "INVALID_KNOWN_AT": "기준 시각 형식이 올바르지 않습니다.",
    "FUTURE_KNOWN_AT_NOT_ALLOWED": "미래 기준 시각은 허용되지 않습니다.",
}

# 이 서비스가 직접 내는 사유 코드(Core가 판정한 것이 아니라 확인하지 못한 것). 기록 계약의 허용 목록과 같아야 한다.
SERVICE_REASON_MESSAGES: dict[str, str] = {
    "TOOL_AUTH_FAILED": "Core 인증에 실패해 확인하지 못했습니다. 서비스 설정을 점검한 뒤 다시 실행하세요.",
    "CORE_UNAVAILABLE": "Core에 연결하지 못해 확인하지 못했습니다. 잠시 뒤 다시 실행하세요.",
    "CORE_TIMEOUT": "Core 응답이 시간 안에 오지 않아 확인하지 못했습니다. 잠시 뒤 다시 실행하세요.",
    "EVIDENCE_UNAVAILABLE": "항목 근거를 받지 못해 이 공문군의 준비안을 만들지 않았습니다. 다시 실행하세요.",
    "TOOL_RESPONSE_INVALID": "Core 응답이 계약과 달라 확인하지 못했습니다. 다시 실행하세요.",
}
SERVICE_REASON_CODES = tuple(SERVICE_REASON_MESSAGES)

# 매핑에 필수 공문군이 없을 때 전체 보류에 붙이는 코드(머리 문구에 표시한다).
NO_REQUIRED_FAMILY_CODE = "NO_REQUIRED_FAMILY_CONFIGURED"

HEADLINES: dict[str, str] = {
    "READY": "공문군 {total}개 가운데 필수 {required}개가 모두 준비됐습니다. 승인된 매핑에 따른 필수 준비 자료를 갖췄습니다. 상담이나 대출 결정의 완료가 아닙니다.",
    "PARTIAL": "공문군 {total}개 가운데 {ready}개 준비됨, 필수 공문군 {hold}개({hold_families}) 확인 남음. 상담 준비가 끝나지 않았습니다.",
    "HOLD": "공문군 {total}개 가운데 준비된 필수 공문군이 없습니다. 필수 공문군 {hold}개({hold_families}) 확인 남음. 상담 준비가 끝나지 않았습니다.",
    "NO_REQUIRED_FAMILY": "이 상품에는 필수 공문군이 설정되지 않았습니다(" + NO_REQUIRED_FAMILY_CODE + "). 설정 승인 전에는 준비 완료로 보지 않습니다. 상담 준비가 끝나지 않았습니다.",
}

HOLD_KIND_PREFIX: dict[str, str] = {
    "CORE_DECISION": "Core가 사용 불가로 판정했습니다.",
    "UNVERIFIED": "Core에 확인하지 못했습니다. Core의 판정이 아닙니다.",
}

NOTICES: dict[str, str] = {
    "human_decision_notice": "이 준비안은 확인할 항목과 근거만 담습니다. 승인·거절·한도·금리·신용등급은 이 준비안이 정하지 않으며 담당자가 판단합니다.",
    "source_notice": "항목은 Core의 승인 checklist이고 근거는 공문 원문의 규칙 문장과 위치입니다. 시행일·조건·예외 같은 값은 항목의 구조화 값이며 원문 직접 인용이 아닙니다.",
    "manual_checklist_notice": "보류된 공문군은 Core의 적용 공문 조회(GET /api/v1/internal-policy/checklists/{family_id}/applicable) 또는 수기 checklist로 확인하세요.",
    "unknown_reason": "Core가 사용 불가로 판정했습니다({code}).",
}

USAGE_NOTICES: dict[str, str] = {
    "RECORDED": "Core에 기록됐습니다. 기록은 사용 허가가 아니므로 사용 전 Core 조회로 사용 가능 여부를 다시 확인하세요.",
    "ALREADY_RECORDED": "같은 내용이 이미 Core에 기록돼 있습니다. 기록은 사용 허가가 아니므로 사용 전 Core 조회로 사용 가능 여부를 다시 확인하세요.",
    "REJECTED": "Core 저장이 거부됐습니다. 이 준비안은 기록되지 않았으므로 사용하지 말고 다시 실행하세요.",
    "FAILED": "Core 저장에 실패했습니다. 이 준비안은 기록되지 않았으므로 사용하지 말고 다시 실행하세요.",
    "NOT_ATTEMPTED": "기록 토큰이 없어 Core에 기록하지 않았습니다. 이 준비안은 기록되지 않았으므로 사용하지 말고 설정을 점검한 뒤 다시 실행하세요.",
}


def message_table() -> dict:
    return {
        "core_reasons": CORE_REASON_MESSAGES,
        "service_reasons": SERVICE_REASON_MESSAGES,
        "headlines": HEADLINES,
        "hold_kind_prefix": HOLD_KIND_PREFIX,
        "notices": NOTICES,
        "usage_notices": USAGE_NOTICES,
    }


def messages_hash() -> str:
    return sha256_of(message_table())


def reason_message(code: str) -> str:
    if code in CORE_REASON_MESSAGES:
        return CORE_REASON_MESSAGES[code]
    if code in SERVICE_REASON_MESSAGES:
        return SERVICE_REASON_MESSAGES[code]
    return NOTICES["unknown_reason"].format(code=code)


def hold_message(hold_kind: str, blocking_reasons: list[str]) -> str:
    return HOLD_KIND_PREFIX[hold_kind] + " " + " ".join(reason_message(code) for code in blocking_reasons)


def manual_checklist_notice(family_id: str) -> str:
    return NOTICES["manual_checklist_notice"].replace("{family_id}", family_id)


def headline(status: str, *, total: int, required: int, ready: int, hold: int, hold_families: list[str],
             no_required_family: bool) -> str:
    if no_required_family:
        return HEADLINES["NO_REQUIRED_FAMILY"]
    return HEADLINES[status].format(total=total, required=required, ready=ready, hold=hold, hold_families=", ".join(hold_families))


def usage_notice(record_status: str) -> str:
    return USAGE_NOTICES[record_status]
