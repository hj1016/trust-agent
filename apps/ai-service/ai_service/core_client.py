"""Core 호출 클라이언트. 표준 라이브러리 urllib만 쓰며 HttpTransport로 전송 계층을 바꿔 끼울 수 있다(테스트용 가짜 전송).

Tool 1·2는 읽기 토큰, 기록 경로는 별도 기록 토큰을 쓴다(ADR-012). 토큰은 오류 메시지·예외에 넣지 않는다.
"""
from __future__ import annotations

import json
import socket
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Mapping, Optional, Protocol

TOOL_PATH = "/api/v1/tools/"
RECORD_PATH = "/api/v1/consultation-preparations"


class TransportTimeout(Exception):
    """Core 응답 시간 초과."""


class TransportUnavailable(Exception):
    """연결 실패 등 Core에 닿지 못함."""


@dataclass(frozen=True)
class TransportResponse:
    status: int
    body: bytes


class HttpTransport(Protocol):
    def send(self, method: str, url: str, headers: Mapping[str, str], body: Optional[bytes], timeout: float) -> TransportResponse:
        """요청을 보내고 (상태 코드, 본문 바이트)를 돌려준다. 시간 초과는 TransportTimeout, 연결 실패는 TransportUnavailable."""


class UrllibTransport:
    def send(self, method: str, url: str, headers: Mapping[str, str], body: Optional[bytes], timeout: float) -> TransportResponse:
        request = urllib.request.Request(url, data=body, method=method, headers=dict(headers))
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310 - 설정된 Core 주소만 호출한다
                return TransportResponse(response.status, response.read())
        except urllib.error.HTTPError as error:
            return TransportResponse(error.code, error.read())
        except TimeoutError as error:
            raise TransportTimeout(str(error)) from error
        except urllib.error.URLError as error:
            if isinstance(error.reason, (socket.timeout, TimeoutError)):
                raise TransportTimeout(str(error.reason)) from error
            raise TransportUnavailable(str(error.reason)) from error
        except (socket.timeout, OSError) as error:
            raise TransportUnavailable(str(error)) from error


@dataclass(frozen=True)
class CoreResponse:
    status: int
    raw: bytes
    json: Optional[Any]

    @property
    def problem_code(self) -> Optional[str]:
        if isinstance(self.json, dict):
            code = self.json.get("code")
            return code if isinstance(code, str) else None
        return None


class CoreClient:
    def __init__(self, base_url: str, tool_token: str, record_token: Optional[str], timeout_seconds: float,
                 transport: Optional[HttpTransport] = None) -> None:
        self._base_url = base_url.rstrip("/")
        self._tool_token = tool_token
        self._record_token = record_token
        self._timeout = timeout_seconds
        self._transport = transport or UrllibTransport()
        self.call_count = 0

    @property
    def record_token_configured(self) -> bool:
        return bool(self._record_token)

    def applicable_checklist(self, family_id: str, business_date: str, consultation_id: Optional[str]) -> CoreResponse:
        body: dict[str, Any] = {"familyId": family_id, "businessDate": business_date}
        if consultation_id:
            body["consultationId"] = consultation_id
        return self._post(TOOL_PATH + "applicable_checklist", body, self._tool_token)

    def rule_evidence(self, family_id: str, rule_version_id: str, consultation_id: Optional[str]) -> CoreResponse:
        body: dict[str, Any] = {"familyId": family_id, "ruleVersionId": rule_version_id}
        if consultation_id:
            body["consultationId"] = consultation_id
        return self._post(TOOL_PATH + "rule_evidence", body, self._tool_token)

    def record_preparation(self, record_body: Mapping[str, Any]) -> CoreResponse:
        if not self._record_token:
            raise ValueError("RECORD_TOKEN_MISSING")
        return self._post(RECORD_PATH, record_body, self._record_token)

    def _post(self, path: str, body: Mapping[str, Any], token: str) -> CoreResponse:
        self.call_count += 1
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "application/json", "Authorization": "Bearer " + token}
        response = self._transport.send("POST", self._base_url + path, headers, payload, self._timeout)
        return CoreResponse(response.status, response.body, _parse_json(response.body))


def _parse_json(raw: bytes) -> Optional[Any]:
    if not raw:
        return None
    try:
        # 부동소수점은 canonical JSON에서 금지되므로 문자열로 읽는다(해시 계산 안전).
        return json.loads(raw.decode("utf-8"), parse_float=str)
    except (ValueError, UnicodeDecodeError):
        return None
