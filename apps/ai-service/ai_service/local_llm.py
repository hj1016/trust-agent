"""로컬 모델 provider(Ollama). Bedrock 게이트웨이(llm.Gateway)와 분리돼 있고 설정으로만 고른다(TRUST_AGENT_LLM_PROVIDER=ollama).

- 전송 자료는 Bedrock과 같은 allowlist(llm.assert_allowlisted)만 보낸다. 주소는 루프백(127.0.0.1·localhost·::1)만 허용한다:
  원격 GPU 서버는 SSH 포트 전달로만 연결하며, 이 코드는 SSH·인증 정보를 다루지 않는다.
- 정형 출력: Ollama `/api/chat`의 `format`에 JSON Schema를 넘기고, 받은 JSON을 같은 스키마로 다시 검사한다(모델이 형식을 어겨도 통과시키지 않는다).
- 실패는 모두 LlmError이며 RAG가 설명을 보류한다: 시간 초과 LLM_TIMEOUT, 연결 실패 LLM_UNAVAILABLE, HTTP 오류 LLM_HTTP_{코드},
  출력 길이 초과 LLM_TRUNCATED, JSON 해석 실패 LLM_RESPONSE_INVALID, 필수 필드 누락·형식 위반 LLM_FIELD_MISSING. 자동 재시도는 없다.
- 과금이 없어 원장(llm_budget)을 쓰지 않는다. 토큰 수는 Ollama가 돌려준 prompt_eval_count·eval_count를 그대로 기록한다.
"""
from __future__ import annotations

import http.client
import json
import os
import re
import socket
import urllib.error
import urllib.request
from typing import Optional
from urllib.parse import urlparse

from jsonschema import Draft202012Validator

from .llm import LlmError, LlmResult, assert_allowlisted, canonical

ENV_PROVIDER = "TRUST_AGENT_LLM_PROVIDER"  # bedrock(기본) | ollama
ENV_OLLAMA_URL = "TRUST_AGENT_OLLAMA_URL"  # 예: http://127.0.0.1:21434 (SSH 포트 전달 주소)
ENV_OLLAMA_MODEL = "TRUST_AGENT_OLLAMA_MODEL"  # 예: qwen3:14b-q4_K_M
ENV_OLLAMA_TIMEOUT = "TRUST_AGENT_OLLAMA_TIMEOUT_SECONDS"
DEFAULT_TIMEOUT_SECONDS = 180.0
MAX_OUTPUT_TOKENS = 1500
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}
THINK_BLOCK = re.compile(r"<think>.*?</think>", re.DOTALL)


class LocalModelGateway:
    def __init__(self, base_url: str, model: str, timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS, max_output_tokens: int = MAX_OUTPUT_TOKENS) -> None:
        parsed = urlparse(base_url)
        if parsed.scheme != "http" or parsed.hostname not in LOOPBACK_HOSTS or not parsed.port:
            raise LlmError("LLM_SETTINGS_INVALID", f"{ENV_OLLAMA_URL}는 루프백 주소(http://127.0.0.1:포트)만 허용합니다(원격 서버는 SSH 포트 전달로 연결).")
        if not model:
            raise LlmError("LLM_SETTINGS_INVALID", f"{ENV_OLLAMA_MODEL}가 필요합니다.")
        self.base_url = base_url.rstrip("/")
        self.model = "ollama:" + model
        self.ollama_model = model
        self.timeout_seconds = timeout_seconds
        self.max_output_tokens = max_output_tokens

    def request_body(self, system: str, payload: dict, schema: dict) -> dict:
        return {
            "model": self.ollama_model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": canonical(payload)}],
            "stream": False,
            "format": schema,
            "think": False,  # 추론 모델의 사고 출력을 끈다(정형 출력만 받는다)
            "options": {"temperature": 0, "num_predict": self.max_output_tokens},
        }

    def call(self, system: str, payload: dict, schema: dict) -> LlmResult:
        assert_allowlisted(payload)
        body = json.dumps(self.request_body(system, payload, schema), ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.base_url + "/api/chat", data=body, method="POST", headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:  # noqa: S310 (루프백만 허용)
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            raise LlmError("LLM_HTTP_" + str(error.code), "로컬 모델 오류 응답") from error
        except (socket.timeout, TimeoutError) as error:
            raise LlmError("LLM_TIMEOUT", "로컬 모델 응답 시간 초과") from error
        except urllib.error.URLError as error:
            if isinstance(error.reason, (socket.timeout, TimeoutError)):
                raise LlmError("LLM_TIMEOUT", "로컬 모델 응답 시간 초과") from error
            raise LlmError("LLM_UNAVAILABLE", "로컬 모델에 연결하지 못했습니다(포트 전달 확인).") from error
        except (http.client.HTTPException, ConnectionError) as error:
            # 응답 없이 연결이 끊긴 경우(포트 전달 종료 등)도 호출 실패로 보류한다(평가 eval-v4 3회차에서 발견)
            raise LlmError("LLM_UNAVAILABLE", "로컬 모델 연결이 응답 없이 끊겼습니다.") from error
        try:
            envelope = json.loads(raw)
        except ValueError as error:
            raise LlmError("LLM_RESPONSE_INVALID", "로컬 모델 응답을 해석하지 못했습니다.") from error
        if envelope.get("done_reason") == "length":
            raise LlmError("LLM_TRUNCATED", "응답이 출력 길이 상한에서 잘렸습니다.")
        content = THINK_BLOCK.sub("", str((envelope.get("message") or {}).get("content") or "")).strip()
        try:
            data = json.loads(content)
        except ValueError as error:
            raise LlmError("LLM_RESPONSE_INVALID", "구조화 응답을 해석하지 못했습니다.") from error
        if not isinstance(data, dict):
            raise LlmError("LLM_RESPONSE_INVALID", "구조화 응답이 객체가 아닙니다.")
        if next(Draft202012Validator(schema).iter_errors(data), None) is not None:
            raise LlmError("LLM_FIELD_MISSING", "구조화 응답이 필수 필드·형식을 지키지 않았습니다.")
        return LlmResult(data, envelope.get("prompt_eval_count"), envelope.get("eval_count"), 0.0, self.model)


def gateway_from_env(environ: Optional[dict] = None) -> Optional[LocalModelGateway]:
    env = os.environ if environ is None else environ
    if (env.get(ENV_PROVIDER) or "bedrock").strip() != "ollama":
        return None
    raw_timeout = (env.get(ENV_OLLAMA_TIMEOUT) or "").strip()
    try:
        timeout = float(raw_timeout) if raw_timeout else DEFAULT_TIMEOUT_SECONDS
    except ValueError as error:
        raise LlmError("LLM_SETTINGS_INVALID", f"{ENV_OLLAMA_TIMEOUT}는 숫자여야 합니다.") from error
    return LocalModelGateway((env.get(ENV_OLLAMA_URL) or "").strip(), (env.get(ENV_OLLAMA_MODEL) or "").strip(), timeout)
