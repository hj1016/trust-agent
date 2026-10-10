"""결정 요청 판별(TASK-016 후속, 최소 규칙). AI는 대출 승인·거절, 금리·한도 확정, 신용등급 결정 주체가 아니다(CLAUDE.md).

검색 점수만으로는 "이 건을 승인해 달라" 같은 요청과 정상 규정 문의를 가르기 어렵다. 결정 요청은 근거를 찾을 질문이 아니므로
검색 전에 근거 보류(DECISION_REQUEST)로 답한다. 단순 키워드 차단을 피하려고 다음 두 조건을 함께 요구한다.
  1) 결정 동사(승인·거절·부결·가결·반려·확정·실행·증액·감액) 바로 뒤에 요청·허락·통보 어미가 붙는다
     (해 주세요, 부탁, 할게요, 해도 되나요, 해야 하나요, 하는 게 맞나요, 처리, 가능하다고 등).
     "승인된", "승인 여부", "승인 전에", "승인 절차"처럼 어미가 붙지 않은 명사·관형 표현은 해당하지 않는다.
  2) 같은 문장에 결정 대상(대출·여신·한도·금리·신용등급·등급·심사·신청·차주·건)이 있다.
판별은 규칙 기반이며 의도 분류 모델이 아니다. 놓치는 표현(예: "신용등급을 B로 정해서")은 관련성 보류가 맡는다.
"""
from __future__ import annotations

import re
from typing import Optional

VERSION = "decision-guard-v1"

_VERB = r"(승인|거절|부결|가결|반려|확정|실행|증액|감액)"
_SUFFIX = (r"(?:\s*(?:으로|로|을|를))?\s*"
           r"(?:해\s*주|해주|해\s*줘|해\s*달|부탁|할게|할께|하겠|해도\s*(?:되|될|돼|괜찮|문제)|해야\s*(?:하|할|되|돼|합)|"
           r"하는\s*게\s*맞|하면\s*(?:되|될|돼)|처리|가능하다고|시켜)")
_REQUEST = re.compile(_VERB + _SUFFIX)
_OBJECT = re.compile(r"(대출|여신|한도|금리|신용등급|등급|심사|신청|차주|건)")


def detect(query: str) -> Optional[str]:
    """결정 요청이면 일치한 구절을, 아니면 None을 돌려준다."""
    match = _REQUEST.search(query)
    if match is None or _OBJECT.search(query) is None:
        return None
    return match.group(0)
