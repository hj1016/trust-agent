"""승인 근거 기반 설명 생성(RAG)과 답변 검증.

흐름: 식별자 검사 → 근거 검색(Core 재확인 포함) → [보류·결정 요청이면 LLM 없이 종료] → 설명 생성(LLM 1회) → 코드 검증 → 답변 검증(LLM 1회).
검증을 하나라도 통과하지 못하면 ANSWER_HOLD이며 설명을 정상 답변으로 내보내지 않는다. 승인 상태·시행일·권한은 Core가 다시 확인한다.
한계: 생성과 답변 검증이 같은 모델이면 같은 오류를 함께 놓칠 수 있다. 그래서 근거 ID·숫자·날짜·조건·예외 누락은 코드가 검사하고,
LLM 검증은 의도 부합·근거 밖 내용의 보조 판정으로만 쓴다(통과 근거가 아니라 추가 차단 근거).
평가용: 결과의 `_eval`(생성 초안·검증 결과·호출별 기록)은 HTTP 응답으로 내보내지 않고, 로컬 평가 전용 기록(eval_trace)에만 남긴다.
"""
from __future__ import annotations

import hashlib
import json
import re
import time
from typing import Any, Callable, Optional

from . import grounding, product_scope
from .llm import Gateway, LlmError, build_payload, identifier_kinds
from .llm_budget import BudgetError

RAG_VERSION = "rag-v3.5"
ENV_MODEL_BUSINESS_DATE = "TRUST_AGENT_RAG_MODEL_BUSINESS_DATE"  # include | omit: 생성·검증 모델 입력에 업무일을 넣을지(Core 판단·검사에는 항상 쓴다)


def model_business_date_mode(environ: Optional[dict] = None) -> str:
    import os
    value = ((os.environ if environ is None else environ).get(ENV_MODEL_BUSINESS_DATE) or "include").strip()
    if value not in ("include", "omit"):
        raise ValueError(f"{ENV_MODEL_BUSINESS_DATE}는 include 또는 omit이어야 합니다.")
    return value
MAX_EVIDENCE = 5  # 검색이 질문 단위로 넘긴 공문군 승인 후보 전체(llm.MAX_EVIDENCE_ITEMS와 같다). 필요한 근거가 여기서 잘리지 않게 한다

GENERATE_SYSTEM = """당신은 은행 직원이 상담 중 승인된 내부 규정 근거를 이해하도록 돕는 보조자입니다.
입력 JSON의 evidence(승인 근거)와 version_changes(Core가 계산한 이전 공문 대비 규칙 변경)만 사용해 질문에 답하십시오.
근거에 없는 사실·숫자·날짜·기준·의무·절차·순서·처리 결과·이유를 만들지 마십시오. 수치·상품·시행일·조건·예외는 화면에 승인 근거 값으로 따로
표시되므로, 설명에서는 질문이 묻는 내용을 근거 문장 그대로의 뜻으로 짧게 풀어 주십시오.
- request_type: 규정·기준·절차·적용 조건을 묻는 질문은 RULE_QUESTION입니다. 특정 고객에 대해 "면제받을 수 있나요?", "적용되나요?",
  "해당되나요?"처럼 가능성·조건을 묻는 질문도 RULE_QUESTION이며, 조건을 설명하고 고객별 사실은 needs_check로 넘기십시오.
  DECISION_REQUEST는 직원이 결정 자체를 맡기거나("승인해 주세요", "등급 매겨 줘", "한도 정해 줘") 내린 결정에 동의를 구하는
  ("승인으로 올릴게요, 괜찮죠?", "면제로 처리하면 되죠?") 경우에만 씁니다.
- answerable: evidence가 질문에 직접 답하면 true. 근거가 다루지 않는 경우(근거에 없는 상환 방식·대상·절차·기간 등)를 물으면,
  비슷한 일반 규정을 그 경우에 적용된다고 답하지 말고 false로 하고 statements에 "해당 질문은 승인 근거로 확인할 수 없음"과 그 이유를 쓰십시오.
  질문이 근거의 적용 대상이 아닌지를 묻고 근거에 적용 대상이 적혀 있으면 true로 하고 근거에 적힌 적용 대상을 그대로 설명하십시오.
- statements: 설명 문장 목록(4문장 이내). 각 문장은 {text, source, quote}입니다. source는 그 문장의 근거인 evidence의 rule_version_id,
  변경 이력을 말하는 문장이면 "VERSION_CHANGES"입니다. quote는 source의 evidence_text·notice_title·structured 값(또는 version_changes)에서
  그대로 복사한 원문 조각입니다. 원문 조각으로 뒷받침할 수 없는 문장은 쓰지 마십시오.
  승인 근거로 확인할 수 없다는 사실만 말하는 문장은 source를 "NONE", quote를 빈 문자열로 쓰십시오(이 문장에는 수치·적용 여부를 넣지 마십시오).
  적용 상품은 근거의 applicable_product_names 그대로 말하고, 상품군 전체·다른 상품·모든 상황처럼 넓히지 마십시오.
  시행일은 근거의 effective_on만 쓰고, 입력의 business_date(오늘 업무일)를 시행일·적용 시점이라고 말하지 마십시오.
- 변경·추가·삭제를 묻는 질문: version_changes.available이 true일 때만 그 changes(change_type, before·after)로 답하십시오.
  false이면 이전 버전과 비교할 수 없다는 사실과 사유를 말하고, 현재 근거로 확인되는 내용만 따로 설명하십시오. 현재 버전만으로 이전 상태를 추측하지 마십시오.
- 질문이 evidence에 없는 공문 버전·개정판, 근거의 시행 기간 밖 날짜, 근거의 적용 상품이 아닌 상품의 기준을 물으면: 먼저 그 내용은
  "현재 제공된 승인 근거로는 확인할 수 없습니다"라고 말하십시오. 승인 근거의 현재 내용은 "참고로"로 시작하는 별도 문장으로 구분해 말할 수 있지만,
  요청한 버전·날짜·상품의 답처럼 말하지 마십시오. 근거에 없다는 이유만으로 '미승인'이라고 단정하지 마십시오.
  적용 상품이 아닌 상품은 "이 공문에서 확인되는 적용 상품은 …입니다. 다른 상품의 기준은 해당 상품의 규정을 별도로 확인해야 합니다"처럼 말하고,
  다른 상품에 무엇이 적용되거나 적용되지 않는다고 단정하지 마십시오.
- version_changes는 질문이 변경·추가·삭제나 이전 기준을 물을 때만 사용하십시오. 그렇지 않은 질문에는 변경 이력 문장을 넣지 마십시오.
  이전 값을 말해야 하면 그 값이 변경 비교 자료에 표시된 이전 값이라는 점을 밝히고, 특정 날짜·건에 이전 값이 적용된다고 단정하지 마십시오
  (그 날짜의 적용 기준은 확인할 수 없다고 말하십시오). quote에는 지시문의 문장이 아니라 version_changes의 실제 값을 복사하십시오.
- citations: statements에 실제로 사용한 근거의 rule_version_id만. 목록("전부", "모두", "항목", "체크리스트")을 묻는 질문이면 관련 항목을
  빠짐없이 설명하고 모두 인용하십시오. 질문이 묻지 않은 근거는 인용하지 마십시오.
- needs_check: 고객별로 확인되지 않은 사실(계약서·약정·상환 예정일 등)이 답에 필요하면 직원이 확인할 질문으로 쓰십시오. 단순 규정 조회면 빈 배열이어도 됩니다.
고객에게 적용된다·면제된다고 확정하지 마십시오. 대출 승인·거절, 금리·한도 확정, 신용 판단을 하지 마십시오.
evidence의 rule_version_id(E1, E2…)는 source·citations에만 쓰고 text·needs_check에는 쓰지 마십시오(공문은 notice_title로 부르십시오)."""

GENERATE_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "required": ["request_type", "answerable", "statements", "citations", "needs_check"],
    "properties": {
        "request_type": {"type": "string", "enum": ["RULE_QUESTION", "DECISION_REQUEST"]},
        "answerable": {"type": "boolean"},
        "statements": {"type": "array", "items": {"type": "object", "additionalProperties": False, "required": ["text", "source", "quote"],
                                                  "properties": {"text": {"type": "string"}, "source": {"type": "string"}, "quote": {"type": "string"}}}},
        "citations": {"type": "array", "items": {"type": "string"}},
        "needs_check": {"type": "array", "items": {"type": "string"}},
    },
}

VERIFY_SYSTEM = """당신은 답변 검증자입니다. 입력 JSON의 question, evidence, draft_answer를 비교해 판정만 하십시오.
- intent_match: draft_answer가 question이 묻는 규정·기준에 답하면 true. 특정 고객에 관한 질문에 규정상 조건·예외를 설명하고
  고객별 사실을 needs_check로 넘긴 답변도 true입니다.
- unsupported_claims: evidence·version_changes에 근거가 없는 진술(숫자·날짜·기준·의무·절차·순서·처리 결과·이유·변경 이력·적용 대상 일반화 포함)을
  그대로 인용. 없으면 빈 배열.
- missing_conditions: citations에 있는 근거의 조건·예외 중 답변이 빠뜨린 것. 없으면 빈 배열.
- decision_made: 대출 승인·거절, 금리·한도 확정, 특정 고객의 적용·면제 확정, 신용 판단을 했으면 true.
- scope_mismatch: draft_answer가 적용 대상(적용 상품 applicable_product_keys, 조건)을 근거보다 넓히거나 다르게 말했으면 true.
- uncovered_case_asserted: question이 묻는 특정 경우(예: 근거에 없는 상환 방식·대상·기간)를 evidence가 직접 다루지 않는데
  draft_answer가 일반 규정을 그 경우에도 적용된다·안 된다고 단정한 문장을 그대로 인용. 근거로 확인할 수 없다고 말했으면 빈 배열.
새 설명을 쓰지 마십시오."""

VERIFY_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "required": ["intent_match", "unsupported_claims", "missing_conditions", "decision_made", "scope_mismatch", "uncovered_case_asserted"],
    "properties": {
        "intent_match": {"type": "boolean"},
        "unsupported_claims": {"type": "array", "items": {"type": "string"}},
        "missing_conditions": {"type": "array", "items": {"type": "string"}},
        "decision_made": {"type": "boolean"},
        "scope_mismatch": {"type": "boolean"},
        "uncovered_case_asserted": {"type": "array", "items": {"type": "string"}},
    },
}

DECISION_ASSERTIONS = [
    re.compile(r"(승인|거절|부결|가결|반려)\s*(합니다|됩니다|하세요|처리하)"),
    # 문장 경계는 '마침표+공백'·줄바꿈으로 본다(0.8 같은 소수점은 문장 끝이 아님)
    re.compile(r"(금리|한도)(?:(?!\.\s|。)[^\n]){0,20}(확정|정해졌|결정됩니다)"),
    re.compile(r"(이|해당|이번)\s*고객(?:(?!\.\s|。)[^\n]){0,30}적용(됩니다|대상입니다)"),
    # v2: 특정 고객·건의 면제 확정(조건부 '…인 경우 면제'는 '경우'에서 끊는다)
    re.compile(r"(이|해당|이번)\s*(고객|기업|법인|건)(?:(?!\.\s|。|경우)[^\n]){0,30}(면제됩니다|면제 대상입니다|면제받을 수 있습니다)"),
]
NUMBER = re.compile(r"\d+(?:\.\d+)?")
DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
KOREAN_DATE = re.compile(r"(\d{4})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일|(\d{4})[./](\d{1,2})[./](\d{1,2})")


def iso_dates(text: str) -> str:
    """'2026년 10월 6일'·'2026.10.6' 같은 표기를 ISO 날짜로 바꿔 날짜 검사가 놓치지 않게 한다."""
    def replace(match: re.Match) -> str:
        year, month, day = [g for g in match.groups() if g is not None]
        return f"{year}-{int(month):02d}-{int(day):02d}"
    return KOREAN_DATE.sub(replace, text)


def prompt_versions() -> dict:
    def digest(text: str) -> str:
        return "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest()
    return {"rag_version": RAG_VERSION, "generate_system": digest(GENERATE_SYSTEM), "verify_system": digest(VERIFY_SYSTEM)}


def _date_parts(dates: set[str]) -> set[str]:
    parts: set[str] = set()
    for date in dates:
        parts.update(date.split("-"))
        parts.update(part.lstrip("0") for part in date.split("-"))
    return parts


def _evidence_facts(payload: dict) -> tuple[set[str], set[str], set[str], set[str]]:
    """근거에서만 뽑은 숫자·날짜·조건·예외(코드 대조 기준). 업무일·질문 날짜 같은 업무 맥락 날짜는 넣지 않는다(_context_dates)."""
    numbers: set[str] = set()
    dates: set[str] = set()
    conditions: set[str] = set()
    exceptions: set[str] = set()
    for item in payload["evidence"]:
        # 공문 제목(Core가 확인한 근거 필드)의 숫자(예: 'v2')도 근거 값이다
        texts = [item["evidence_text"], item.get("notice_title") or ""] + [str(v) for v in item["structured"].values() if not isinstance(v, list)]
        for key in ("conditions", "exceptions", "applicable_product_keys"):
            texts += [str(v) for v in item["structured"].get(key) or []]
        conditions.update(item["structured"].get("conditions") or [])
        exceptions.update(item["structured"].get("exceptions") or [])
        for text in texts:
            text = iso_dates(text)
            dates.update(DATE.findall(text))
            numbers.update(NUMBER.findall(DATE.sub(" ", text)))
    # Core가 계산한 이전·현재 규칙 비교의 숫자·날짜도 승인 자료다(이전 시행일 등). 이전 기준이 지금 적용된다는 단정은 grounding이 따로 막는다
    version = payload.get("version_changes") or {}
    if version.get("available"):
        text = iso_dates(json.dumps({"title": version.get("previous_notice_title"), "changes": version.get("changes")}, ensure_ascii=False))
        dates.update(DATE.findall(text))
        numbers.update(NUMBER.findall(DATE.sub(" ", text)))
    return numbers | _date_parts(dates), dates, conditions, exceptions


def _context_dates(payload: dict) -> tuple[set[str], set[str]]:
    """업무 맥락: 질문에 사용자가 쓴 날짜(설명·확인 질문 모두 허용)와 업무일(확인 질문에서만 허용). 질문의 숫자도 사용자가 준 값이다."""
    question = iso_dates(payload.get("question") or "")
    question_dates = set(DATE.findall(question))
    question_numbers = set(NUMBER.findall(DATE.sub(" ", question))) | _date_parts(question_dates)
    return question_dates, question_numbers


def deterministic_checks(payload: dict, draft: dict, business_date: Optional[str] = None) -> list[str]:
    """코드 검증: 인용 ID, 근거 밖 숫자·날짜, 조건·예외 원문 일치와 누락, 결정 표현.
    날짜 기준: 설명 본문 = 근거 날짜 ∪ 질문 날짜. 확인 질문(needs_check) = 여기에 업무일을 더한다.
    업무일과 시행일이 같은 날이면 그 날짜는 근거 날짜이므로 설명에 써도 된다. 업무일은 모델 입력에 없어도 Core가 정한 값으로 검사한다."""
    failures: list[str] = []
    allowed_ids = {item["rule_version_id"] for item in payload["evidence"]}
    citations = list(draft.get("citations") or [])
    if not citations:
        failures.append("CITATION_MISSING")
    if any(c not in allowed_ids for c in citations):
        failures.append("CITATION_NOT_IN_EVIDENCE")
    business_date = business_date or payload.get("business_date")
    numbers, evidence_dates, conditions, exceptions = _evidence_facts(payload)
    question_dates, question_numbers = _context_dates(payload)
    explanation = iso_dates(draft.get("explanation", ""))
    checks = iso_dates(" ".join(draft.get("needs_check") or []))
    text = explanation + " " + checks
    explanation_allowed = evidence_dates | question_dates
    for date in DATE.findall(explanation):
        if date not in explanation_allowed:
            failures.append("BUSINESS_DATE_AS_RULE_DATE" if date == business_date else "DATE_NOT_IN_EVIDENCE")
    if any(date not in explanation_allowed | ({business_date} if business_date else set()) for date in DATE.findall(checks)):
        failures.append("DATE_NOT_IN_EVIDENCE")
    numbers = numbers | question_numbers | (_date_parts({business_date}) if business_date and business_date in DATE.findall(checks) else set())
    failures = list(dict.fromkeys(failures))
    if any(n not in numbers and n.lstrip("0") not in numbers for n in NUMBER.findall(DATE.sub(" ", text))):
        failures.append("NUMBER_NOT_IN_EVIDENCE")
    if any(c not in conditions for c in draft.get("conditions") or []):
        failures.append("CONDITION_NOT_VERBATIM")
    if any(e not in exceptions for e in draft.get("exceptions") or []):
        failures.append("EXCEPTION_NOT_VERBATIM")
    cited = [item for item in payload["evidence"] if item["rule_version_id"] in citations]
    required_conditions = {c for item in cited for c in item["structured"].get("conditions") or []}
    required_exceptions = {e for item in cited for e in item["structured"].get("exceptions") or []}
    if not required_conditions <= set(draft.get("conditions") or []):
        failures.append("CONDITION_OMITTED")
    if not required_exceptions <= set(draft.get("exceptions") or []):
        failures.append("EXCEPTION_OMITTED")
    if any(pattern.search(text) for pattern in DECISION_ASSERTIONS):
        failures.append("DECISION_ASSERTION")
    products = [key for item in cited for key in item["structured"].get("applicable_product_keys") or []]
    if product_scope.scope_violations(text, products):
        failures.append("SCOPE_BROADENED")
    return failures


def answer(question: str, business_date: str, search_result: dict, gateway: Optional[Gateway], blocked_terms: tuple[str, ...] = (),
           clock: Callable[[], float] = time.perf_counter) -> dict:
    """search_result는 이미 grant·Core 재확인을 거친 검색 응답이다(이 함수는 검색을 다시 하지 않는다)."""
    started = clock()
    base: dict[str, Any] = {"rag_version": RAG_VERSION, "prompts": prompt_versions(), "business_date": business_date,
                            "evidence": search_result.get("evidence") or [], "search_status": search_result.get("status"),
                            "llm_calls": 0, "usage": {"input_tokens": 0, "output_tokens": 0, "charged_usd_estimate": 0.0}, "model": None,
                            "timings_ms": {}, "llm_call_log": []}
    held: dict[str, Any] = {"draft": None}

    def finish(status: str, reasons: list[str], draft: Optional[dict] = None, verification: Optional[dict] = None) -> dict:
        base["timings_ms"]["rag_total"] = int((clock() - started) * 1000)
        base.update({"status": status, "reasons": reasons, "verification": verification,
                     "answer": draft if status == "ANSWERED" else None, "latency_ms": base["timings_ms"]["rag_total"],
                     "_eval": {"draft": held["draft"], "verification": verification}})
        return base

    if search_result.get("status") != "EVIDENCE":
        if search_result.get("hold_kind") == "DECISION_REQUEST":
            return finish("DECISION_DECLINED", list(search_result.get("hold_reasons") or []))
        return finish("EVIDENCE_HOLD", list(search_result.get("hold_reasons") or []))
    kinds = identifier_kinds(question, blocked_terms)
    if kinds:
        return finish("ANSWER_HOLD", ["QUESTION_CONTAINS_IDENTIFIER"], verification={"identifier_kinds": kinds})
    if gateway is None:
        return finish("ANSWER_HOLD", ["LLM_NOT_CONFIGURED"])
    payload = build_payload(question, business_date, base["evidence"][:MAX_EVIDENCE], search_result.get("version_comparison"))
    # 모델 입력: Core가 고른 승인 근거(시행일·조건·예외 포함)와 질문. 업무일은 설정에 따라 뺀다(질문에 사용자가 쓴 날짜는 질문에 그대로 남는다).
    base["model_business_date"] = model_business_date_mode()
    model_payload = payload if base["model_business_date"] == "include" else {key: value for key, value in payload.items() if key != "business_date"}
    # 모델에는 64자리 근거 ID 대신 짧은 별칭(E1, E2…)을 보낸다(긴 해시를 옮겨 쓰다 틀리는 것을 막는다). 받은 뒤 코드가 원래 ID로 되돌린다.
    alias = {item["rule_version_id"]: f"E{index}" for index, item in enumerate(payload["evidence"], start=1)}
    real = {value: key for key, value in alias.items()}
    model_payload = {**model_payload, "evidence": [{**item, "rule_version_id": alias[item["rule_version_id"]]} for item in model_payload["evidence"]]}

    def call(stage: str, system: str, body: dict, schema: dict) -> dict:
        call_started = clock()
        log: dict[str, Any] = {"stage": stage, "ok": False, "code": None, "input_tokens": None, "output_tokens": None}
        base["llm_call_log"].append(log)
        try:
            result = gateway.call(system, body, schema)
        except (LlmError, BudgetError) as error:
            log["code"] = error.code
            raise
        finally:
            log["latency_ms"] = int((clock() - call_started) * 1000)
            base["timings_ms"][stage] = log["latency_ms"]
        log.update({"ok": True, "code": "OK", "input_tokens": result.input_tokens, "output_tokens": result.output_tokens})
        base["llm_calls"] += 1
        base["model"] = result.model
        base["usage"]["input_tokens"] += result.input_tokens or 0
        base["usage"]["output_tokens"] += result.output_tokens or 0
        base["usage"]["charged_usd_estimate"] = round(base["usage"]["charged_usd_estimate"] + result.charged_usd, 6)
        return result.data

    try:
        draft = call("generate", GENERATE_SYSTEM, model_payload, GENERATE_SCHEMA)
    except (LlmError, BudgetError) as error:
        return finish("ANSWER_HOLD", [error.code])
    # 별칭을 원래 ID로 되돌린다. 입력에 없는 값은 그대로 두어 인용·근거 대조 검사에서 막힌다
    draft = {**draft, "citations": [real.get(c, c) for c in draft.get("citations") or []],
             "statements": [{**st, "source": ", ".join(real.get(part.strip(), part.strip()) for part in str(st.get("source") or "").split(","))}
                            if isinstance(st, dict) else st for st in draft.get("statements") or []]}
    held["draft"] = draft
    # 생성 단계 판정: 결정 요청이면 설명을 내보내지 않고 결정 요청으로 답한다(검색 전 판별이 놓친 표현의 두 번째 방어선).
    # 근거로 답할 수 없다고 판정하면 검증 호출 없이 근거 부족으로 보류한다(보류는 안전한 쪽이므로 추가 검증이 필요 없다).
    if draft.get("request_type") == "DECISION_REQUEST":
        return finish("DECISION_DECLINED", ["LLM_DECISION_REQUEST"], verification={"code_checks": None, "llm_check": None})
    if draft.get("answerable") is False:
        return finish("ANSWER_HOLD", ["EVIDENCE_INSUFFICIENT"], verification={"code_checks": None, "llm_check": None})
    statements = [s for s in draft.get("statements") or [] if isinstance(s, dict)]
    grounding_failures = grounding.check_statements(statements, payload["evidence"], payload.get("version_changes"),
                                                    {item["rule_version_id"]: item["structured"].get("applicable_product_names") or [] for item in payload["evidence"]})
    # 설명문은 근거 대조를 거친 문장을 코드가 이어 붙인다
    # VERSION_CHANGES는 문장 출처 표기이지 승인 근거 ID가 아니므로 화면 인용 목록에서 뺀다(변경 비교 근거는 version_changes로 따로 확인된다)
    draft = {"explanation": " ".join(s.get("text", "").strip() for s in statements).strip(),
             "citations": [c for c in draft.get("citations") or [] if c not in (grounding.VERSION_SOURCE, grounding.NONE_SOURCE)],
             "needs_check": draft.get("needs_check") or []}
    # 확정 값(조건·예외)은 모델이 옮겨 쓰지 않는다: 인용 근거의 구조화 값을 코드가 순서대로 그대로 채운다(누락·변형 방지).
    cited_items = [item for item in payload["evidence"] if item["rule_version_id"] in (draft.get("citations") or [])]
    draft["conditions"] = list(dict.fromkeys(c for item in cited_items for c in item["structured"].get("conditions") or []))
    draft["exceptions"] = list(dict.fromkeys(e for item in cited_items for e in item["structured"].get("exceptions") or []))
    check_started = clock()
    code_failures = list(dict.fromkeys(grounding_failures + deterministic_checks(payload, draft, business_date)))
    base["timings_ms"]["code_check"] = int((clock() - check_started) * 1000)
    verification: dict[str, Any] = {"code_checks": code_failures or ["PASS"], "llm_check": None}
    if code_failures:
        return finish("ANSWER_HOLD", code_failures, verification=verification)
    try:
        verdict = call("verify", VERIFY_SYSTEM, {**model_payload, "draft_answer": {**draft, "citations": [alias.get(c, c) for c in draft["citations"]]}}, VERIFY_SCHEMA)
    except (LlmError, BudgetError) as error:
        return finish("ANSWER_HOLD", [error.code], verification=verification)
    verification["llm_check"] = verdict
    llm_failures = []
    if not verdict.get("intent_match"):
        llm_failures.append("LLM_INTENT_MISMATCH")
    if verdict.get("unsupported_claims"):
        llm_failures.append("LLM_UNSUPPORTED_CLAIM")
    if verdict.get("missing_conditions"):
        llm_failures.append("LLM_MISSING_CONDITION")
    if verdict.get("decision_made"):
        llm_failures.append("LLM_DECISION_MADE")
    if verdict.get("scope_mismatch"):
        llm_failures.append("LLM_SCOPE_MISMATCH")
    if verdict.get("uncovered_case_asserted"):
        llm_failures.append("LLM_UNCOVERED_CASE")
    if llm_failures:
        return finish("ANSWER_HOLD", llm_failures, verification=verification)
    return finish("ANSWERED", [], draft=draft, verification=verification)
