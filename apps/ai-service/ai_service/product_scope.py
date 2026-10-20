"""적용 범위 검사(코드). 설명이 인용 근거의 적용 상품보다 넓은 범위(다른 상품·상품군 전체)를 말하면 막는다.

상품 이름은 공개 상품 카탈로그(PUBLIC_KB, datasets/public/kb/catalog/product-catalog.json)의 product_key·display_name·source_marker만 쓴다.
'○○ 상품에 적용/해당' 꼴에서 ○○가 인용 근거의 적용 상품 이름이 아니면 범위 확대로 본다. 조건 문구('기업 고객의 기업여신 상담 건에 적용')는
'상품'이 아니므로 막지 않는다. '모든 대출', '기업여신 전체'처럼 전체를 가리키는 표현도 막는다.
"""
from __future__ import annotations

import json
import os
import re
from functools import lru_cache
from pathlib import Path
from typing import Iterable

CATALOG = Path("datasets/public/kb/catalog/product-catalog.json")
_PRODUCT_CLAIM = re.compile(r"([가-힣A-Za-z0-9\-' ]{1,24}?)\s*상품(?:군)?\s*(?:에|에는|에도|에만|의|으로|에게)?\s*(?:적용|해당)")
_ALL_SCOPE = re.compile(r"(모든|전체|전\s|모든\s*종류의)\s*(기업\s*)?(대출|여신|상품|고객|기업여신)|(대출|여신|상품|기업여신)\s*(전체|전반|일반)(에|에도|으로)")
_NOT_SCOPE_WORDS = {"해당", "이", "그", "이번", "본", "적용"}


@lru_cache(maxsize=1)
def _catalog(root: str) -> dict[str, list[str]]:
    path = Path(root) / CATALOG
    if not path.is_file():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    names: dict[str, list[str]] = {}
    for product in data.get("products") or []:
        key = product.get("product_key")
        if not key:
            continue
        aliases = {key, product.get("display_name") or "", product.get("source_marker") or ""}
        display = product.get("display_name") or ""
        if display.startswith("KB "):
            aliases.add(display[3:])
        names[key] = [alias for alias in aliases if alias]
    return names


def _root(root: str | None = None) -> str:
    return root or os.environ.get("TRUST_AGENT_REPOSITORY_ROOT", str(Path(__file__).resolve().parents[3]))


def catalog_display_name(product_key: str, root: str | None = None) -> str | None:
    """공개 상품 카탈로그의 표시 이름(예: KB 셀러론). 없으면 None."""
    path = Path(_root(root)) / CATALOG
    if not path.is_file():
        return None
    for product in json.loads(path.read_text(encoding="utf-8")).get("products") or []:
        if product.get("product_key") == product_key:
            return product.get("display_name") or None
    return None


def aliases(product_keys: Iterable[str], root: str | None = None) -> list[str]:
    catalog = _catalog(_root(root))
    found: list[str] = []
    for key in product_keys:
        found += catalog.get(key, [key])
    return found


def _compact(text: str) -> str:
    return re.sub(r"[\s'\"‘’“”]", "", text).lower()


def scope_violations(text: str, allowed_product_keys: Iterable[str], root: str | None = None) -> list[str]:
    """설명에서 적용 상품을 넓힌 구절을 돌려준다. 근거에 적용 상품이 없으면(체크리스트 규칙 등) 상품 이름 검사는 하지 않는다."""
    keys = list(allowed_product_keys)
    found = [match.group(0) for match in _ALL_SCOPE.finditer(text)
             if not re.search(r"(되지\s*않|아닙|아니|않습)", text[match.end():match.end() + 20])]
    if not keys:
        return found
    allowed = [_compact(alias) for alias in aliases(keys, root)]
    for match in _PRODUCT_CLAIM.finditer(text):
        # 부정문("다른 상품에는 적용되지 않습니다")은 범위 확대가 아니라 범위 한정이다
        if re.match(r"\s*(?:되지\s*않|하지\s*않|되는\s*것은\s*아니|대상이\s*아니|않)", text[match.end():match.end() + 12]):
            continue
        subject = _compact(match.group(1))
        if not subject or subject in _NOT_SCOPE_WORDS:
            continue
        if not any(alias and (alias in subject or subject.endswith(alias)) for alias in allowed):
            found.append(match.group(0).strip())
    return found
