"""FastAPI 진입점. 로컬·demo 전용이며 사용자별 인증이 없다(127.0.0.1에만 바인딩해 실행한다. 후속 ADR).

POST /api/v1/ai/consultation-preparations  본문 {"applicationId", "businessDate"?, "consultationId"?, "grantId"?} → 준비안 JSON.
수신 토큰(TRUST_AGENT_AI_INBOUND_TOKEN)이 설정되면 Authorization: Bearer가 같아야 한다(401). grantId는 Core가 발급한 AI 요청 승인이며 Tool·기록 호출에 헤더로 전달된다.
입력 오류 400, 설정 누락 503. 응답 본문은 CLI와 같은 함수(prepare)의 결과이고 헤더 X-Preparation-Recorded가 record.recorded와 같다.
HTTP 상태(제안 9, 사용자 수정 채택): 기록 성공(READY·PARTIAL·HOLD 모두) 200. 기록 실패는 본문을 그대로 두고 상태만 바꾼다:
Core 422(현재 업무 조건상 사용 불가) → 422, Core 409(상태 충돌) → 409, Core 401·기록 토큰 미설정(운영 설정) → 503,
Core 400(AI 서비스가 만든 요청의 계약 위반) → 500, Core 연결 실패·Core 5xx → 502, 시간 초과 → 504.
"""
from __future__ import annotations

from typing import Optional

from typing import Optional as _Optional

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from .assembler import InputError, prepare, record_class
from .config import SettingsError, load_settings

# 기록 결과 종류 → HTTP 상태. 본문(record.recorded=false, error_code, usage_notice)은 바뀌지 않는다.
HTTP_STATUS_BY_RECORD_CLASS = {
    "RECORDED": 200,
    "ALREADY_RECORDED": 200,
    "REJECTED_NOT_USABLE": 422,
    "REJECTED_CONFLICT": 409,
    "REJECTED_SETTINGS": 503,
    "REJECTED_CONTRACT": 500,
    "REJECTED_OTHER": 502,
    "FAILED_CORE": 502,
    "FAILED_TIMEOUT": 504,
    "NOT_ATTEMPTED": 503,
}

app = FastAPI(title="TrustAgent AI Service", version="0.1.0", docs_url=None, redoc_url=None)


class PreparationRequest(BaseModel):
    applicationId: str = Field(min_length=1, max_length=64)
    businessDate: Optional[str] = Field(default=None, max_length=10)
    consultationId: Optional[str] = Field(default=None, min_length=1, max_length=64)
    grantId: Optional[str] = Field(default=None, min_length=1, max_length=64)


def _check_inbound_token(settings, authorization: _Optional[str]) -> None:
    """수신 토큰(ADR-014 2항). 설정돼 있으면 Core가 보낸 Bearer 토큰과 같아야 한다. 토큰 값은 응답·로그에 넣지 않는다."""
    if not settings.inbound_token:
        return
    import hmac
    presented = (authorization or "").strip()
    if not presented.startswith("Bearer ") or not hmac.compare_digest(presented[len("Bearer "):].strip(), settings.inbound_token):
        raise HTTPException(status_code=401, detail={"code": "UNAUTHENTICATED", "message": "AI 서비스 수신 인증에 실패했습니다."})


@app.post("/api/v1/ai/consultation-preparations")
def create_preparation(request: PreparationRequest, authorization: _Optional[str] = Header(default=None)) -> JSONResponse:
    try:
        settings = load_settings()
    except SettingsError as error:
        raise HTTPException(status_code=503, detail={"code": error.code, "message": str(error)}) from error
    _check_inbound_token(settings, authorization)
    try:
        preparation = prepare(request.applicationId, request.businessDate, request.consultationId, settings=settings, grant_id=request.grantId)
    except InputError as error:
        raise HTTPException(status_code=400, detail={"code": error.code, "message": str(error)}) from error
    # 기록되지 않은 준비안도 본문(사용 금지 안내 포함)은 그대로 돌려주고, HTTP 상태와 헤더로 실패를 구분한다.
    recorded = preparation["record"]["recorded"]
    status_code = HTTP_STATUS_BY_RECORD_CLASS[record_class(preparation["record"])]
    assert (status_code == 200) == recorded
    return JSONResponse(status_code=status_code, content=preparation,
                        headers={"X-Preparation-Recorded": "true" if recorded else "false"})
