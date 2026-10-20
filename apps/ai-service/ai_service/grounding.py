"""문장 단위 근거 대조(코드). 생성 모델은 설명을 문장 목록으로 내고 문장마다 근거(source)와 원문 인용구(quote)를 붙인다.

1) UNGROUNDED_STATEMENT: 인용구가 그 근거의 원문(evidence_text)·지시문·구조화 값·공문 제목·상품 이름, 또는 version_changes(이전·현재 규칙)
   어디에도 없거나, source가 입력에 없는 근거면 막는다. 모델이 근거를 '주장'만 하는 것이 아니라 실제 원문 조각을 대야 한다.
2) UNSUPPORTED_CLAIM_TYPE: 원문에 없는 처리 결과("하지 않으면 처리 불가"), 순서("검증 완료 후"), 전체 범위("모든 상황에 적용"),
   이유("~하기 위함")를 말하는 문장은 그 표현이 근거 원문에 없으면 막는다. 특정 문장이 아니라 표현 유형으로 검사한다.
   부정문("모든 상황에 적용되는 것은 아닙니다")은 범위 확대가 아니다.
4) VERSION_APPLICABILITY_UNSUPPORTED: 변경 이력을 출처로 '이전 기준이 적용된다·안 된다'를 말하면 막는다(적용 여부는 현재 승인 근거로만 말한다).
3) VERSION_CLAIM_UNSUPPORTED: '추가·신설·삭제·폐지·변경' 같은 변경 이력 주장은 Core가 준 version_changes에 그 종류의 변경이 있어야 한다.
   비교 자료가 없으면 변경 이력을 말할 수 없다(현재 버전만으로 이전 상태를 추측하지 않는다).
"""
from __future__ import annotations

import re
from typing import Iterable, Optional

VERSION_SOURCE = "VERSION_CHANGES"
# 근거로 확인할 수 없다는 사실만 말하는 문장의 출처 표기. 확인 불가 표현이 있어야 하고 수치·적용 단정이 없어야 한다
NONE_SOURCE = "NONE"
_NOT_CONFIRMABLE = re.compile(r"(확인할\s*수\s*없|확인되지\s*않|확정할\s*수\s*없|별도로\s*확인|따로\s*확인|확인해야|근거에\s*없|다루지\s*않)")

_CONSEQUENCE = re.compile(r"(않으면|안\s*하면|누락하면|빠뜨리면|없으면|하지\s*않을\s*경우|미기재\s*시)(?:(?!\.\s|。).){0,30}?"
                          r"(불가|불가능|안\s*됩니다|되지\s*않|제재|반려|거절|처리(?:(?!\.\s|。).){0,8}(?:않|못)|문제가\s*(?:됩|생))")
_ORDER = re.compile(r"(완료|끝난|끝낸|마친|확인한|검증한|기록한)\s*(?:된\s*)?(후|뒤|다음)|먼저\s|순서(?:대로|로|는)")
_UNIVERSAL = re.compile(r"(모든|어떤|어느)\s*(상황|경우|상담|고객|건|상품|대출|업무)(?:에|에서|에도|이든|이나)?(?!(?:(?!\.\s|。).){0,12}(?:아닙|않습|아니))"
                        r"|한정(?:된|하는)?\s*(?:조건|대상)(?:은|이)?\s*없")
_REASON = re.compile(r"(위해서|위함|위한\s*것|때문에|때문입니다|목적(?:은|으로|이))")
# 변경 이력(version_changes)은 이전·현재 규칙의 차이를 보여 줄 뿐 이전 규칙이 지금 적용된다는 근거가 아니다
_APPLICABILITY = re.compile(r"적용(?:됩|된다|돼|되며|되고|되지|되었|받|하면\s*됩)")
_PREVIOUS = re.compile(r"(이전|기존|종전|변경\s*전|과거|예전)")
# 근거가 이유·목적을 말하면 같은 뜻의 다른 어미("위해서", "위함")도 근거가 있는 것으로 본다
_REASON_STEM = {"위해서": "위해", "위함": "위해", "위한것": "위한"}
_ADDED = re.compile(r"(추가|신설|새로\s*(?:생|도입|들어)|도입)")
_REMOVED = re.compile(r"(삭제|폐지|없어졌|제외되었|빠졌)")
_CHANGED = re.compile(r"(변경되었|바뀌었|바뀐|개정|인하|인상|조정되었)")


def compact(text: str) -> str:
    return re.sub(r"[\s'\"‘’“”「」『』.,·()\[\]]", "", (text or "")).lower()


_FIELD_KEY = re.compile(r"[\"']?\b[a-z][a-z_]*[a-z]\b[\"']?\s*:")


def quote_fragments(quote: str) -> list[str]:
    """인용구에서 구조화 값의 필드 이름(`exceptions:`·`"effective_on":`)과 목록 기호를 걷어 내고 값 조각만 남긴다.
    값 조각은 하나도 빠짐없이 근거에 있어야 한다(형식만 정규화하고 대조 기준은 낮추지 않는다)."""
    if not _FIELD_KEY.search(quote or ""):
        return [quote] if compact(quote) else []
    body = _FIELD_KEY.sub("|", quote)
    parts = re.split(r"\||[\"']\s*,\s*[\"']|[\[\]{}]", body)
    return [part for part in parts if compact(part)]


def _version_text(version_changes: Optional[dict]) -> str:
    if not version_changes:
        return ""
    parts = [version_changes.get("previous_notice_title") or ""]
    for change in version_changes.get("changes") or []:
        parts += [change.get("rule_key") or "", change.get("change_type") or "", change.get("before_rule_text") or "",
                  change.get("after_rule_text") or "", str(change.get("before") or ""), str(change.get("after") or "")]
    return " ".join(parts)


def source_texts(evidence: list[dict], product_names: dict[str, list[str]]) -> dict[str, str]:
    """근거 ID → 인용구를 찾을 원문 묶음(원문·구조화 값·공문 제목·상품 이름)."""
    texts: dict[str, str] = {}
    for item in evidence:
        structured = item.get("structured") or {}
        values = []
        for value in structured.values():
            values += [str(v) for v in value] if isinstance(value, list) else [str(value)]
        texts[item["rule_version_id"]] = " ".join([item.get("evidence_text") or "", item.get("notice_title") or "", *values,
                                                   *product_names.get(item["rule_version_id"], [])])
    return texts


def check_statements(statements: Iterable[dict], evidence: list[dict], version_changes: Optional[dict],
                     product_names: Optional[dict[str, list[str]]] = None) -> list[str]:
    failures: list[str] = []
    texts = source_texts(evidence, product_names or {})
    version_text = _version_text(version_changes)
    all_evidence = compact(" ".join(texts.values()) + " " + version_text)
    kinds = {change.get("change_type") for change in (version_changes or {}).get("changes") or []} if (version_changes or {}).get("available") else set()
    for statement in statements:
        text = statement.get("text") or ""
        quote = statement.get("quote") or ""
        source = statement.get("source") or ""
        if source == NONE_SOURCE:
            if not _NOT_CONFIRMABLE.search(text) or re.search(r"\d+(?:\.\d+)?\s*(?:%|퍼센트|억|만|원)", text) or _APPLICABILITY.search(text):
                failures.append("UNGROUNDED_STATEMENT")
            continue
        # 출처를 여러 개 적으면("E2, E3" → 원래 ID 여러 개) 그 근거들을 합쳐 대조한다
        sources = [part.strip() for part in source.split(",") if part.strip()] or [source]
        parts = [version_text if part == VERSION_SOURCE else texts.get(part) for part in sources]
        haystack = None if any(part is None for part in parts) else " ".join(parts)
        fragments = quote_fragments(quote)
        # 인용구는 표시한 근거에서 찾고, 없으면 같은 요청의 다른 승인 입력(근거·version_changes)에서 찾는다(출처 표기 혼동만 허용).
        # 어디에도 없는 인용구(지어낸 원문)는 막는다. 인용 근거 일치는 citations 검사가 따로 본다
        def found(parts):
            return bool(parts) and (haystack is not None and all(compact(f) in compact(haystack) for f in parts)
                                    or all(compact(f) in all_evidence for f in parts))
        # 값 나열("0.8, 2026-10-01 이후 중도상환")은 쉼표 조각마다 근거에 있어야 한다
        if not found(fragments) and not found([f for part in fragments for f in part.split(",") if compact(f)]):
            failures.append("UNGROUNDED_STATEMENT")
        grounded = compact(" ".join(fragments)) + " " + all_evidence
        for pattern in (_CONSEQUENCE, _ORDER, _UNIVERSAL, _REASON):
            match = pattern.search(text)
            token = compact(match.group(0)) if match else ""
            # 같은 뜻의 다른 어미는 그 문장이 인용한 원문 조각에 있을 때만 허용한다
            if match and token not in grounded and _REASON_STEM.get(token, "\0") not in compact(" ".join(fragments)):
                failures.append("UNSUPPORTED_CLAIM_TYPE")
                break
        # 변경 이력을 출처로 '이전 상태(이전·기존·변경 전, 또는 변경 전 값)가 적용된다'고 말하는 문장만 막는다. 현재 값의 시행 설명은 허용
        before_values = {str((change.get("before") or {}).get("before_value") or (change.get("before") or {}).get("after_value") or "")
                         for change in (version_changes or {}).get("changes") or []} - {"", "None"}
        if source == VERSION_SOURCE and _APPLICABILITY.search(text) and (_PREVIOUS.search(text) or any(v in text for v in before_values)):
            failures.append("VERSION_APPLICABILITY_UNSUPPORTED")
        for pattern, kind in ((_ADDED, "ADDED"), (_REMOVED, "REMOVED"), (_CHANGED, "MODIFIED")):
            match = pattern.search(text)
            if match and kind not in kinds and compact(match.group(0)) not in compact(" ".join(fragments)):
                failures.append("VERSION_CLAIM_UNSUPPORTED")
                break
    return list(dict.fromkeys(failures))
