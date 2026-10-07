"""FastAPI 진입점. 로컬·demo 전용이며 사용자별 인증이 없다(127.0.0.1에만 바인딩해 실행한다. 후속 ADR).

POST /api/v1/ai/consultation-preparations  본문 {"applicationId", "businessDate"?, "consultationId"?} → 준비안 JSON.
입력 오류 400, 설정 누락 503. 응답은 CLI와 같은 함수(prepare)의 결과이며 헤더 X-Preparation-Recorded가 record.recorded와 같다.
"""
from __future__ import annotations

from typing import Optional

from fastapi import FastAPI, HTTPException, Response
from pydantic import BaseModel, Field

from .assembler import InputError, prepare
from .config import SettingsError, load_settings

app = FastAPI(title="TrustAgent AI Service", version="0.1.0", docs_url=None, redoc_url=None)


class PreparationRequest(BaseModel):
    applicationId: str = Field(min_length=1, max_length=64)
    businessDate: Optional[str] = Field(default=None, max_length=10)
    consultationId: Optional[str] = Field(default=None, min_length=1, max_length=64)


@app.post("/api/v1/ai/consultation-preparations")
def create_preparation(request: PreparationRequest, response: Response) -> dict:
    try:
        settings = load_settings()
    except SettingsError as error:
        raise HTTPException(status_code=503, detail={"code": error.code, "message": str(error)}) from error
    try:
        preparation = prepare(request.applicationId, request.businessDate, request.consultationId, settings=settings)
    except InputError as error:
        raise HTTPException(status_code=400, detail={"code": error.code, "message": str(error)}) from error
    # 기록되지 않은 준비안도 본문은 돌려주되(사용 금지 안내 포함) 헤더로 구분한다. 본문 record.recorded와 같은 값이다.
    response.headers["X-Preparation-Recorded"] = "true" if preparation["record"]["recorded"] else "false"
    return preparation
