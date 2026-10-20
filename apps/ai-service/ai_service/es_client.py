"""Elasticsearch 읽기 전용 클라이언트(ADR-013 결정 2·7). 검색 사용자(읽기 전용)로 alias에 질의하고 규칙 version ID와 점수만 쓴다.

표준 라이브러리 urllib만 쓴다. 실패는 전부 SearchUnavailable(코드)로 바꾸며 문서 본문은 응답에 쓰지 않는다.
"""
from __future__ import annotations

import base64
import json
import socket
import urllib.error
import urllib.request
from typing import Any, Mapping, Optional, Protocol


class SearchUnavailable(Exception):
    """ES 연결 실패·시간 초과·오류 응답. code는 SEARCH_UNAVAILABLE 또는 SEARCH_TIMEOUT."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class EsTransport(Protocol):
    def search(self, alias: str, body: Mapping[str, Any]) -> dict:
        """_search 응답 JSON을 돌려준다. 실패는 SearchUnavailable."""


class UrllibEsTransport:
    def __init__(self, base_url: str, username: Optional[str], password: Optional[str], timeout_seconds: float) -> None:
        self._base_url = base_url.rstrip("/")
        self._auth = None
        if username and password:
            self._auth = "Basic " + base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        self._timeout = timeout_seconds

    def search(self, alias: str, body: Mapping[str, Any]) -> dict:
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self._auth:
            headers["Authorization"] = self._auth
        request = urllib.request.Request(self._base_url + "/" + alias + "/_search", data=json.dumps(body).encode("utf-8"),
                                         method="POST", headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=self._timeout) as response:  # noqa: S310 - 설정된 ES 주소만 호출한다
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            raise SearchUnavailable("SEARCH_UNAVAILABLE", f"Elasticsearch 오류 응답 {error.code}") from error
        except (TimeoutError, socket.timeout) as error:
            raise SearchUnavailable("SEARCH_TIMEOUT", "Elasticsearch 응답 시간 초과") from error
        except urllib.error.URLError as error:
            if isinstance(error.reason, (socket.timeout, TimeoutError)):
                raise SearchUnavailable("SEARCH_TIMEOUT", "Elasticsearch 응답 시간 초과") from error
            raise SearchUnavailable("SEARCH_UNAVAILABLE", "Elasticsearch에 연결하지 못했습니다") from error
        except (OSError, ValueError) as error:
            raise SearchUnavailable("SEARCH_UNAVAILABLE", "Elasticsearch 호출 실패: " + error.__class__.__name__) from error
