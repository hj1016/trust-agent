"""외부 LLM(Bedrock Claude Haiku) 호출 게이트웨이. 모든 호출은 이 모듈을 거친다.

- 전송 자료는 allowlist로만 만든다: 행원 질문(식별자 검사 통과), 업무일, Core가 확인한 승인 근거(규칙 ID·공문 ID·근거 원문·구조화 값 일부).
  상담·신청·기업·사용자 ID, 기업명, 금액, grant, 토큰, 검색 점수는 보내지 않는다.
- 호출 전 원장 예약(llm_budget), 동시 1건, SDK 자동 재시도 끔(max_retries=0), 응답 뒤 usage 정산, 실패는 예약액 확정.
- 모델·profile ID와 리전은 설정으로 고정한다. 유료 호출은 사용자가 원장에 등록한 예산 안에서만 일어난다.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
from dataclasses import dataclass
from typing import Any, Callable, Optional, Protocol

from jsonschema import Draft202012Validator

from .llm_budget import BudgetError, Ledger, ledger_path

ENV_MODEL = "TRUST_AGENT_LLM_MODEL"  # 예: global.anthropic.claude-haiku-5-5 (inference profile)
ENV_REGION = "TRUST_AGENT_LLM_REGION"  # 예: ap-northeast-2 (호출 리전. global profile은 처리 리전을 한국으로 제한하지 못한다)
ENV_CLIENT = "TRUST_AGENT_LLM_CLIENT"  # invoke(AnthropicBedrock, InvokeModel) | mantle(AnthropicBedrockMantle) | converse(boto3 Converse, OpenAI 모델)
STRUCTURED_TOOL = "submit_answer"
ENV_APPROVAL_ID = "TRUST_AGENT_LLM_APPROVAL_ID"
ENV_PURPOSE = "TRUST_AGENT_LLM_PURPOSE"  # smoke | eval | dev (원장 승인 용도와 같아야 함)
ENV_WORKTREE = "TRUST_AGENT_LLM_WORKTREE"
DEFAULT_TIMEOUT_SECONDS = 30.0
MAX_OUTPUT_TOKENS = 1500
MAX_QUESTION_CHARS = 200
MAX_EVIDENCE_ITEMS = 5
STRUCTURED_FIELDS = ("before_value", "after_value", "unit", "effective_on", "applicable_product_keys", "applicable_product_names", "conditions", "exceptions")
EVIDENCE_FIELDS = ("rule_version_id", "notice_id", "notice_title", "evidence_text", "structured")
PAYLOAD_FIELDS = ("question", "business_date", "evidence", "version_changes")

# 질문 속 고객 식별 정보·금액(호출 전 거부. 마스킹하지 않는다: 뜻이 바뀌므로 질문을 다시 쓰게 한다). 규정 값(퍼센트·날짜)은 허용한다.
IDENTIFIER_PATTERNS = {
    "AMOUNT": re.compile(r"\d[\d,\.]*\s*(원|만\s*원|억|천만|백만)"),
    "KOREAN_AMOUNT": re.compile(r"(일|이|삼|사|오|육|칠|팔|구|십|백|천)+\s*(억|천만|백만|만\s*원)"),
    "BUSINESS_NUMBER": re.compile(r"\b\d{3}-?\d{2}-?\d{5}\b"),
    "RESIDENT_NUMBER": re.compile(r"\b\d{6}-?[1-4]\d{6}\b"),
    "PHONE": re.compile(r"\b01[016789]-?\d{3,4}-?\d{4}\b"),
    "ACCOUNT": re.compile(r"\b\d{2,6}-\d{2,6}-\d{2,8}\b"),
}


class LlmError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class SenderResult:
    text: str
    input_tokens: Optional[int]
    output_tokens: Optional[int]
    stop_reason: Optional[str]


class Sender(Protocol):
    def __call__(self, request: dict) -> SenderResult: ...


def identifier_kinds(question: str, blocked_terms: tuple[str, ...] = ()) -> list[str]:
    found = [kind for kind, pattern in IDENTIFIER_PATTERNS.items() if pattern.search(question)]
    if any(term and term in question for term in blocked_terms):
        found.append("REGISTERED_IDENTIFIER")
    return found


VERSION_STRUCTURED = ("before_value", "after_value", "unit", "effective_on", "conditions", "exceptions")


def version_changes_payload(comparison: Optional[dict]) -> dict:
    """Core가 계산한 이전 version 대비 규칙 변경(Tool 1 versionComparison)을 모델 입력 형식으로 옮긴다. 없으면 비교 불가로 표시한다."""
    if not comparison:
        return {"available": False, "unavailable_reason": "COMPARISON_NOT_PROVIDED", "kind": "UNKNOWN", "previous_notice_title": None, "changes": []}

    def subset(structured):
        return {key: structured[key] for key in VERSION_STRUCTURED if isinstance(structured, dict) and key in structured} if structured else None

    return {"available": bool(comparison.get("available")) and comparison.get("kind") == "CHANGED",
            "unavailable_reason": comparison.get("unavailableReason") or ("NO_PREVIOUS_VERSION" if comparison.get("kind") == "NEW" else None),
            "kind": comparison.get("kind"), "previous_notice_title": comparison.get("previousTitle"),
            "changes": [{"rule_key": change.get("ruleKey"), "change_type": change.get("changeType"), "before_rule_text": change.get("beforeInstruction"),
                         "after_rule_text": change.get("afterInstruction"), "before": subset(change.get("beforeStructuredChange")),
                         "after": subset(change.get("afterStructuredChange"))} for change in comparison.get("changes") or []]}


def build_payload(question: str, business_date: str, evidence: list[dict], version_comparison: Optional[dict] = None) -> dict:
    """검색 응답의 근거 항목(Core Tool 응답 유래)에서 allowlist 필드만 옮긴다."""
    if not isinstance(question, str) or not question.strip() or len(question) > MAX_QUESTION_CHARS:
        raise LlmError("LLM_PAYLOAD_INVALID", "질문 길이가 허용 범위를 벗어났습니다.")
    if not evidence or len(evidence) > MAX_EVIDENCE_ITEMS:
        raise LlmError("LLM_PAYLOAD_INVALID", "근거 개수가 허용 범위를 벗어났습니다.")
    items = []
    for item in evidence:
        change = item.get("structured_change") or {}
        items.append({
            "rule_version_id": item["rule_version_id"],
            "notice_id": item["evidence"]["notice_id"],
            "notice_title": item.get("notice_title"),
            "evidence_text": item["evidence"]["evidence_text"],
            "structured": {**{key: change[key] for key in STRUCTURED_FIELDS if key in change},
                           # 적용 상품 이름(공개 상품 카탈로그 PUBLIC_KB). 모델이 상품 키 대신 상품군으로 넓혀 부르지 않게 한다
                           **({"applicable_product_names": _product_names(change["applicable_product_keys"])} if change.get("applicable_product_keys") else {})},
        })
    payload = {"question": question.strip(), "business_date": business_date, "evidence": items, "version_changes": version_changes_payload(version_comparison)}
    assert_allowlisted(payload)
    return payload


def _product_names(keys: list) -> list[str]:
    from . import product_scope
    names = []
    for key in keys:
        catalog = product_scope.catalog_display_name(key)
        names.append(catalog or key)
    return names


DRAFT_FIELDS = ("explanation", "citations", "conditions", "exceptions", "needs_check")  # 답변 검증 호출에만: 직전 생성 결과


def assert_allowlisted(payload: dict) -> None:
    keys = set(payload) - {"draft_answer"}
    # business_date는 선택 필드다(모델 입력에서 뺄 수 있다: rag.ENV_MODEL_BUSINESS_DATE). question·evidence는 필수
    if not {"question", "evidence"} <= keys or not keys <= set(PAYLOAD_FIELDS):
        raise LlmError("LLM_PAYLOAD_NOT_ALLOWED", "허용되지 않은 전송 필드가 있습니다.")
    if "draft_answer" in payload and not set(payload["draft_answer"]) <= set(DRAFT_FIELDS):
        raise LlmError("LLM_PAYLOAD_NOT_ALLOWED", "허용되지 않은 답변 필드가 있습니다.")
    for item in payload["evidence"]:
        if set(item) != set(EVIDENCE_FIELDS) or not set(item["structured"]) <= set(STRUCTURED_FIELDS):
            raise LlmError("LLM_PAYLOAD_NOT_ALLOWED", "허용되지 않은 근거 필드가 있습니다.")


def canonical(payload: dict) -> str:
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def estimate_input_tokens(system: str, payload_json: str, schema: dict) -> int:
    """보수적 추정: UTF-8 바이트 ÷ 2(한국어는 대체로 이보다 적다) + 요청 틀 여유 200."""
    total = len(system.encode("utf-8")) + len(payload_json.encode("utf-8")) + len(json.dumps(schema).encode("utf-8"))
    return total // 2 + 200


@dataclass(frozen=True)
class LlmResult:
    data: dict
    input_tokens: Optional[int]
    output_tokens: Optional[int]
    charged_usd: float
    model: str


class Gateway:
    def __init__(self, ledger: Ledger, sender: Sender, model: str, approval_id: str, purpose: str, worktree: str,
                 max_output_tokens: int = MAX_OUTPUT_TOKENS) -> None:
        self.ledger = ledger
        self.sender = sender
        self.model = model
        self.approval_id = approval_id
        self.purpose = purpose
        self.worktree = worktree
        self.max_output_tokens = max_output_tokens

    def call(self, system: str, payload: dict, schema: dict) -> LlmResult:
        assert_allowlisted(payload)
        payload_json = canonical(payload)
        digest = hashlib.sha256(payload_json.encode("utf-8")).hexdigest()
        # 요청은 공통 형식(Anthropic Messages)으로 만든다. Bedrock의 Claude Haiku 5.5는 JSON Schema 정형 출력을 지원하지 않으므로(모델 카드)
        # 스키마를 강제 도구 호출(tool_choice)로 받고 사고 출력은 끈다. Converse 송신기(GPT-6 Luna 등)는 이 요청을 Converse 형식으로 옮긴다.
        request = {
            "model": self.model,
            "max_tokens": self.max_output_tokens,
            "system": system,
            "messages": [{"role": "user", "content": payload_json}],
            "tools": [{"name": STRUCTURED_TOOL, "description": "답변을 지정된 JSON 형식으로 제출한다.", "input_schema": schema}],
            "tool_choice": {"type": "tool", "name": STRUCTURED_TOOL},
            "thinking": {"type": "disabled"},
            "temperature": 0,
        }
        with self.ledger.single_flight():
            reservation = self.ledger.reserve(self.approval_id, self.purpose, self.model, digest,
                                              estimate_input_tokens(system, payload_json, schema), self.max_output_tokens, self.worktree)
            try:
                result = self.sender(request)
            except LlmError as error:
                self.ledger.settle(reservation, error.code, reason=error.code)
                raise
            except Exception as error:  # 예상 못 한 실패도 과금됐다고 보고 예약액을 확정한다
                self.ledger.settle(reservation, "LLM_UNAVAILABLE", reason=type(error).__name__)
                raise LlmError("LLM_UNAVAILABLE", "외부 LLM 호출에 실패했습니다.") from error
            charged = self.ledger.settle(reservation, "OK" if result.input_tokens is not None and result.output_tokens is not None else "USAGE_MISSING",
                                         result.input_tokens, result.output_tokens)
        if result.stop_reason == "refusal":
            raise LlmError("LLM_REFUSED", "모델이 응답을 거절했습니다.")
        if result.stop_reason == "max_tokens":
            raise LlmError("LLM_TRUNCATED", "응답이 출력 길이 상한에서 잘렸습니다.")
        try:
            data = json.loads(result.text)
        except (TypeError, ValueError) as error:
            raise LlmError("LLM_RESPONSE_INVALID", "구조화 응답을 해석하지 못했습니다.") from error
        if not isinstance(data, dict):
            raise LlmError("LLM_RESPONSE_INVALID", "구조화 응답이 객체가 아닙니다.")
        # 로컬 모델과 같은 기준: 받은 JSON을 같은 스키마로 다시 검사한다(형식을 어겨도 통과시키지 않는다)
        if next(Draft202012Validator(schema).iter_errors(data), None) is not None:
            raise LlmError("LLM_FIELD_MISSING", "구조화 응답이 필수 필드·형식을 지키지 않았습니다.")
        return LlmResult(data, result.input_tokens, result.output_tokens, charged / 1_000_000, self.model)


def bedrock_sender(model_client: str, region: str, timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS) -> Sender:
    """실제 Bedrock 호출(유료). 자동 재시도 0회. 자격 증명은 표준 AWS 설정(프로필·환경)에서 SDK가 읽으며 이 코드는 다루지 않는다."""
    import anthropic

    if model_client == "mantle":
        client: Any = anthropic.AnthropicBedrockMantle(aws_region=region, max_retries=0, timeout=timeout_seconds)
    elif model_client == "invoke":
        client = anthropic.AnthropicBedrock(aws_region=region, max_retries=0, timeout=timeout_seconds)
    else:
        raise LlmError("LLM_SETTINGS_INVALID", "LLM 클라이언트는 invoke 또는 mantle")

    def send(request: dict) -> SenderResult:
        try:
            response = client.messages.create(**request)
        except anthropic.RateLimitError as error:
            raise LlmError("LLM_RATE_LIMITED", "호출 한도(429)에 걸렸습니다. 자동 재시도하지 않습니다.") from error
        except anthropic.APITimeoutError as error:
            raise LlmError("LLM_TIMEOUT", "외부 LLM 응답 시간 초과") from error
        except anthropic.APIConnectionError as error:
            raise LlmError("LLM_UNAVAILABLE", "외부 LLM 연결 실패") from error
        except anthropic.APIStatusError as error:
            raise LlmError("LLM_HTTP_" + str(error.status_code), "외부 LLM 오류 응답") from error
        tool_inputs = [block.input for block in response.content if getattr(block, "type", None) == "tool_use" and getattr(block, "name", None) == STRUCTURED_TOOL]
        text = json.dumps(tool_inputs[0], ensure_ascii=False) if tool_inputs else "".join(
            block.text for block in response.content if getattr(block, "type", None) == "text")
        usage = getattr(response, "usage", None)
        stop = "end_turn" if response.stop_reason == "tool_use" else response.stop_reason
        return SenderResult(text, getattr(usage, "input_tokens", None), getattr(usage, "output_tokens", None), stop)

    return send


def converse_request(request: dict, reasoning_effort: str = "none") -> dict:
    """공통 요청을 Bedrock Converse 형식으로 옮긴다(GPT-6 Luna 등 OpenAI 모델). JSON Schema 정형 출력(strict), 추론 노력 none.
    API별 차이: 정형 출력을 도구 대신 outputConfig로 받고, temperature는 추론 모델이 받지 않을 수 있어 연결 점검에서 확인한다."""
    schema = request["tools"][0]["input_schema"]
    return {
        "modelId": request["model"],
        "system": [{"text": request["system"]}],
        "messages": [{"role": "user", "content": [{"text": request["messages"][0]["content"]}]}],
        "inferenceConfig": {"maxTokens": request["max_tokens"]},
        "outputConfig": {"textFormat": {"type": "json_schema", "structure": {"jsonSchema": {"name": "answer", "schema": json.dumps(schema, ensure_ascii=False)}}}},
        "additionalModelRequestFields": {"text": {"format": {"strict": True}}, "reasoning_effort": reasoning_effort},
    }


def converse_sender(region: str, timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS) -> Sender:
    """실제 Bedrock Converse 호출(유료). 자동 재시도 0회. 자격 증명은 표준 AWS 설정(프로필)에서 boto3가 읽는다."""
    import boto3
    from botocore.config import Config
    from botocore.exceptions import ClientError, EndpointConnectionError, ReadTimeoutError

    client = boto3.client("bedrock-runtime", region_name=region,
                          config=Config(retries={"max_attempts": 1, "mode": "standard"}, read_timeout=timeout_seconds, connect_timeout=10))

    def send(request: dict) -> SenderResult:
        try:
            response = client.converse(**converse_request(request))
        except ClientError as error:
            code = error.response.get("Error", {}).get("Code", "")
            status = error.response.get("ResponseMetadata", {}).get("HTTPStatusCode", 0)
            if code == "ThrottlingException" or status == 429:
                raise LlmError("LLM_RATE_LIMITED", "호출 한도(429)에 걸렸습니다. 자동 재시도하지 않습니다.") from error
            raise LlmError("LLM_HTTP_" + str(status or code), "외부 LLM 오류 응답") from error
        except ReadTimeoutError as error:
            raise LlmError("LLM_TIMEOUT", "외부 LLM 응답 시간 초과") from error
        except EndpointConnectionError as error:
            raise LlmError("LLM_UNAVAILABLE", "외부 LLM 연결 실패") from error
        blocks = ((response.get("output") or {}).get("message") or {}).get("content") or []
        text = "".join(block.get("text", "") for block in blocks)
        usage = response.get("usage") or {}
        stop = {"max_tokens": "max_tokens", "content_filtered": "refusal", "guardrail_intervened": "refusal"}.get(response.get("stopReason"), "end_turn")
        return SenderResult(text, usage.get("inputTokens"), usage.get("outputTokens"), stop)

    return send


def gateway_from_env(environ: Optional[dict] = None, sender: Optional[Sender] = None) -> Gateway:
    env = os.environ if environ is None else environ
    model = (env.get(ENV_MODEL) or "").strip()
    approval = (env.get(ENV_APPROVAL_ID) or "").strip()
    if not model or not approval:
        raise BudgetError("LLM_BUDGET_NOT_APPROVED", "LLM 모델·호출 예산 승인 ID가 설정되지 않았습니다.")
    purpose = (env.get(ENV_PURPOSE) or "").strip()
    region = (env.get(ENV_REGION) or "").strip()
    if sender is None:
        if not region:
            raise LlmError("LLM_SETTINGS_INVALID", f"{ENV_REGION}가 필요합니다.")
        client_kind = (env.get(ENV_CLIENT) or "invoke").strip()
        sender = converse_sender(region) if client_kind == "converse" else bedrock_sender(client_kind, region)
    return Gateway(Ledger(ledger_path(env)), sender, model, approval, purpose, (env.get(ENV_WORKTREE) or "unknown").strip())


ENV_FAKE = "TRUST_AGENT_LLM_FAKE"  # local 배포에서만: echo(근거를 그대로 옮기는 결정론 응답기). 화면·흐름 시험용이며 모델 품질 검증이 아니다


class FakeEchoGateway:
    """외부 호출 없는 결정론 응답기(로컬 시험 전용). 생성 요청에는 첫 근거의 원문·조건·예외를 그대로 옮기고, 검증 요청에는 통과를 돌려준다."""

    model = "fake-echo"

    def call(self, system: str, payload: dict, schema: dict) -> LlmResult:
        assert_allowlisted(payload)
        if "draft_answer" in payload:
            data = {"intent_match": True, "unsupported_claims": [], "missing_conditions": [], "decision_made": False, "scope_mismatch": False, "uncovered_case_asserted": []}
        else:
            first = payload["evidence"][0]
            structured = first["structured"]
            data = {"request_type": "RULE_QUESTION", "answerable": True,
                    "statements": [{"text": "[시험용 응답] 승인 근거 원문: " + first["evidence_text"], "source": first["rule_version_id"],
                                    "quote": first["evidence_text"]}],
                    "citations": [first["rule_version_id"]], "needs_check": ["근거 원문과 적용 조건을 직원이 확인했나요?"]}
        return LlmResult(data, 0, 0, 0.0, self.model)


def gateway_for_service(environ: Optional[dict] = None):
    """HTTP 서비스용: 가짜 응답기(로컬 명시 시) → 로컬 모델(TRUST_AGENT_LLM_PROVIDER=ollama) → 승인 예산이 있는 Bedrock 게이트웨이
    → 없으면 None(LLM 없이 근거만 제공). provider 설정이 잘못되면 LlmError로 기동·요청을 거부한다."""
    from . import local_llm
    from .config import DEPLOYMENT_LOCAL, ENV_DEPLOYMENT

    env = os.environ if environ is None else environ
    fake = (env.get(ENV_FAKE) or "").strip()
    provider = (env.get(local_llm.ENV_PROVIDER) or "bedrock").strip()
    if provider not in ("bedrock", "ollama"):
        raise LlmError("LLM_SETTINGS_INVALID", f"{local_llm.ENV_PROVIDER}는 bedrock 또는 ollama여야 합니다.")
    if fake:
        if fake != "echo" or (env.get(ENV_DEPLOYMENT) or "").strip() != DEPLOYMENT_LOCAL:
            raise LlmError("LLM_SETTINGS_INVALID", f"{ENV_FAKE}=echo는 {ENV_DEPLOYMENT}=local에서만 허용됩니다.")
        if provider == "ollama":
            raise LlmError("LLM_SETTINGS_INVALID", f"{ENV_FAKE}와 로컬 모델 provider를 함께 켤 수 없습니다.")
        return FakeEchoGateway()
    if provider == "ollama":
        return local_llm.gateway_from_env(env)
    try:
        return gateway_from_env(env)
    except (BudgetError, LlmError):
        return None
