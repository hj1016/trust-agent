from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

from bs4 import BeautifulSoup, Tag
from jsonschema import Draft202012Validator, FormatChecker, ValidationError


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST_ROOT = ROOT / "datasets/public/kb/manifests"
DEFAULT_ARTIFACT_ROOT = ROOT / ".private-artifacts"
DEFAULT_OUTPUT_ROOT = ROOT / "datasets/public/kb/product-versions"
DEFAULT_MANIFEST_SCHEMA = ROOT / "contracts/public-snapshot-manifest.schema.json"
DEFAULT_VERSION_SCHEMA = ROOT / "contracts/public-product-version.schema.json"
DEFAULT_FACT_SCHEMA = ROOT / "contracts/public-product-fact.schema.json"
PARSER_VERSION = "public-kb-html-v1"


class FactExtractionError(RuntimeError):
    """Raised when a fact cannot be extracted without guessing."""


@dataclass(frozen=True)
class LocatedText:
    text: str
    strategy: str
    selector: str
    label: str


@dataclass(frozen=True)
class ExtractionResult:
    product_key: str
    status: str
    product_version_id: str
    fact_set_hash: str
    output_path: Path


def _load_json(path: Path) -> dict:
    try:
        with path.open(encoding="utf-8") as source:
            return json.load(source)
    except (OSError, json.JSONDecodeError) as error:
        raise FactExtractionError(f"JSON 파일을 읽을 수 없습니다: {path}") from error


def _validate_schema(instance: dict, schema_path: Path, label: str) -> None:
    schema = _load_json(schema_path)
    try:
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(instance)
    except ValidationError as error:
        raise FactExtractionError(f"{label} schema 검증에 실패했습니다: {error.message}") from error


def _normalize_text(value: str) -> str:
    return " ".join(value.split())


def _unique(items: list, description: str):
    if len(items) != 1:
        raise FactExtractionError(
            f"{description} 후보는 정확히 한 건이어야 합니다: {len(items)}건"
        )
    return items[0]


def _attribute_text(
    soup: BeautifulSoup, selector: str, attribute: str, label: str
) -> LocatedText:
    candidates = [element for element in soup.select(selector) if element.get(attribute)]
    element = _unique(candidates, label)
    return LocatedText(
        text=_normalize_text(element[attribute]),
        strategy="CSS_ATTRIBUTE",
        selector=selector,
        label=label,
    )


def _labeled_paragraph(soup: BeautifulSoup, label: str) -> LocatedText:
    headings = [
        element
        for element in soup.select("p.tit3")
        if _normalize_text(element.get_text(" ", strip=True)) == label
    ]
    heading = _unique(headings, f"section label {label}")
    sibling = heading.find_next_sibling()
    while sibling is not None:
        if isinstance(sibling, Tag) and sibling.name == "p" and "tit3" in sibling.get("class", []):
            break
        if isinstance(sibling, Tag) and sibling.name == "p" and "p-text" in sibling.get("class", []):
            return LocatedText(
                text=_normalize_text(sibling.get_text(" ", strip=True)),
                strategy="LABELED_ADJACENT_TEXT",
                selector="p.tit3 + p.p-text",
                label=label,
            )
        sibling = sibling.find_next_sibling()
    raise FactExtractionError(f"section 본문을 찾을 수 없습니다: {label}")


def _labeled_span(soup: BeautifulSoup, label: str) -> LocatedText:
    labels = [
        element
        for element in soup.select("span.info_tit")
        if _normalize_text(element.get_text(" ", strip=True)) == label
    ]
    heading = _unique(labels, f"info label {label}")
    sibling = heading.find_next_sibling("span")
    if sibling is None or "info_txt" not in sibling.get("class", []):
        raise FactExtractionError(f"info 본문을 찾을 수 없습니다: {label}")
    return LocatedText(
        text=_normalize_text(sibling.get_text(" ", strip=True)),
        strategy="LABELED_ADJACENT_TEXT",
        selector="span.info_tit + span.info_txt",
        label=label,
    )


def _unique_text_match(
    soup: BeautifulSoup, selector: str, pattern: re.Pattern[str], label: str
) -> LocatedText:
    matches = []
    for element in soup.select(selector):
        text = _normalize_text(element.get_text(" ", strip=True))
        if pattern.fullmatch(text):
            matches.append((element, text))
    _, text = _unique(matches, label)
    return LocatedText(
        text=text,
        strategy="CSS_TEXT_MATCH",
        selector=selector,
        label=label,
    )


def _evidence_hash(text: str) -> str:
    return "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest()


def _make_fact(
    product_key: str,
    fact_key: str,
    subject_type: str,
    value_type: str,
    value: str | int,
    unit: str,
    located: LocatedText,
    manifest: dict,
) -> dict:
    return {
        "fact_id": f"fact:{product_key}:{fact_key}",
        "fact_key": fact_key,
        "subject_type": subject_type,
        "value_type": value_type,
        "value": value,
        "unit": unit,
        "source_locator": {
            "strategy": located.strategy,
            "selector": located.selector,
            "label": located.label,
            "evidence_text": located.text,
            "evidence_hash": _evidence_hash(located.text),
            "snapshot_hash": manifest["snapshot_hash"],
            "source_url": manifest["source_url"],
        },
    }


def _parse_eok_amount(text: str, pattern: re.Pattern[str], label: str) -> int:
    values = {int(value) for value in pattern.findall(text)}
    if len(values) != 1:
        raise FactExtractionError(
            f"{label} 억원 값은 정확히 한 종류여야 합니다: {sorted(values)}"
        )
    return values.pop() * 100_000_000


def _normalize_sale_status(located: LocatedText) -> str:
    compact = re.sub(r"[\s-]", "", located.text)
    if compact != "판매중":
        raise FactExtractionError(f"지원하지 않는 판매 상태입니다: {located.text}")
    return "SELLING"


def _common_product_name(
    soup: BeautifulSoup, product_key: str
) -> LocatedText:
    selector = (
        'input[name="브랜드상품명"]'
        if product_key == "boss-plus-overdraft"
        else 'input[name="상세웹상품명"]'
    )
    return _attribute_text(soup, selector, "value", "상품명")


def _parse_small_business_or_overdraft(
    soup: BeautifulSoup, manifest: dict
) -> list[dict]:
    product_key = manifest["product_key"]
    product_name = _common_product_name(soup, product_key)
    eligibility = _labeled_paragraph(soup, "가입대상")
    limit = _labeled_paragraph(soup, "대출한도")
    repayment = _labeled_paragraph(soup, "대출기간과 상환방법")
    sale_status = _labeled_paragraph(soup, "상품판매여부")
    rate = _unique_text_match(
        soup,
        "span.txt",
        re.compile(r"최저\s*연\s*.+~\s*최고\s*연\s*.+"),
        "광고 금리 문구",
    )
    rate_date = _unique_text_match(
        soup,
        "span",
        re.compile(r"\(\d{4}\.\d{2}\.\d{2},\s*신용등급\s*1등급\s*기준\)"),
        "광고 금리 기준일",
    )
    date_match = re.search(r"(\d{4})\.(\d{2})\.(\d{2})", rate_date.text)
    if date_match is None:
        raise FactExtractionError("광고 금리 기준일을 정규화할 수 없습니다.")
    normalized_date = "-".join(date_match.groups())
    max_limit = _parse_eok_amount(
        limit.text, re.compile(r"최대\s*(\d+)억원"), "개인사업자 최대한도"
    )

    return [
        _make_fact(product_key, "product_name", "PRODUCT", "TEXT", product_name.text, "TEXT", product_name, manifest),
        _make_fact(product_key, "applicant_eligibility_text", "PRODUCT", "TEXT", eligibility.text, "TEXT", eligibility, manifest),
        _make_fact(product_key, "max_limit_individual_krw", "SOLE_PROPRIETOR", "INTEGER", max_limit, "KRW", limit, manifest),
        _make_fact(product_key, "advertised_rate_text", "PRODUCT", "TEXT", rate.text, "TEXT", rate, manifest),
        _make_fact(product_key, "advertised_rate_reference_date", "PRODUCT", "DATE", normalized_date, "DATE", rate_date, manifest),
        _make_fact(product_key, "repayment_method_text", "PRODUCT", "TEXT", repayment.text, "TEXT", repayment, manifest),
        _make_fact(product_key, "sale_status", "PRODUCT", "STATUS", _normalize_sale_status(sale_status), "STATUS", sale_status, manifest),
    ]


def _parse_seller_loan(soup: BeautifulSoup, manifest: dict) -> list[dict]:
    product_key = manifest["product_key"]
    product_name = _common_product_name(soup, product_key)
    eligibility = _labeled_paragraph(soup, "대출대상")
    limit = _labeled_paragraph(soup, "대출한도")
    repayment = _labeled_paragraph(soup, "원리금상환 방법")
    sale_status = _labeled_paragraph(soup, "판매종료 여부")
    rate = _labeled_span(soup, "적용금리")
    individual_limit = _parse_eok_amount(
        limit.text,
        re.compile(r"개인사업자\s*:?\s*(?:Min\([^)]*?,\s*)?(\d+)억원"),
        "개인사업자 최대한도",
    )
    corporate_limit = _parse_eok_amount(
        limit.text,
        re.compile(r"법인사업자\s*:?\s*(?:Min\([^)]*?,\s*)?(\d+)억원"),
        "법인사업자 최대한도",
    )

    return [
        _make_fact(product_key, "product_name", "PRODUCT", "TEXT", product_name.text, "TEXT", product_name, manifest),
        _make_fact(product_key, "applicant_eligibility_text", "PRODUCT", "TEXT", eligibility.text, "TEXT", eligibility, manifest),
        _make_fact(product_key, "max_limit_individual_krw", "SOLE_PROPRIETOR", "INTEGER", individual_limit, "KRW", limit, manifest),
        _make_fact(product_key, "max_limit_corporate_krw", "CORPORATION", "INTEGER", corporate_limit, "KRW", limit, manifest),
        _make_fact(product_key, "advertised_rate_text", "PRODUCT", "TEXT", rate.text, "TEXT", rate, manifest),
        _make_fact(product_key, "repayment_method_text", "PRODUCT", "TEXT", repayment.text, "TEXT", repayment, manifest),
        _make_fact(product_key, "sale_status", "PRODUCT", "STATUS", _normalize_sale_status(sale_status), "STATUS", sale_status, manifest),
    ]


def parse_facts(payload: bytes, manifest: dict) -> list[dict]:
    try:
        html = payload.decode("utf-8")
    except UnicodeDecodeError as error:
        raise FactExtractionError("Snapshot이 UTF-8이 아닙니다.") from error
    soup = BeautifulSoup(html, "html.parser")
    product_key = manifest["product_key"]
    if product_key in {"small-business-credit", "boss-plus-overdraft"}:
        facts = _parse_small_business_or_overdraft(soup, manifest)
    elif product_key == "kb-seller-loan":
        facts = _parse_seller_loan(soup, manifest)
    else:
        raise FactExtractionError(f"지원하지 않는 product_key입니다: {product_key}")
    return sorted(facts, key=lambda fact: fact["fact_key"])


def _canonical_fact_set(facts: list[dict]) -> bytes:
    semantic_facts = [
        {
            "fact_key": fact["fact_key"],
            "subject_type": fact["subject_type"],
            "value_type": fact["value_type"],
            "value": fact["value"],
            "unit": fact["unit"],
        }
        for fact in facts
    ]
    return json.dumps(
        semantic_facts, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def _write_immutable(path: Path, payload: bytes) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        existing = _load_json(path)
        incoming = json.loads(payload.decode("utf-8"))
        existing_semantic = _canonical_fact_set(existing["facts"])
        incoming_semantic = _canonical_fact_set(incoming["facts"])
        if existing_semantic != incoming_semantic:
            raise FactExtractionError(f"기존 product version의 fact set이 다릅니다: {path}")
        return False

    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as target:
            target.write(payload)
            target.flush()
            os.fsync(target.fileno())
        try:
            os.link(temporary_path, path)
        except FileExistsError:
            return _write_immutable(path, payload)
        return True
    finally:
        temporary_path.unlink(missing_ok=True)


def extract_manifest(
    manifest_path: Path,
    artifact_root: Path,
    output_root: Path,
    manifest_schema: Path = DEFAULT_MANIFEST_SCHEMA,
    version_schema: Path = DEFAULT_VERSION_SCHEMA,
    fact_schema: Path = DEFAULT_FACT_SCHEMA,
    repository_root: Path = ROOT,
) -> ExtractionResult:
    manifest = _load_json(manifest_path)
    _validate_schema(manifest, manifest_schema, "manifest")
    artifact_path = artifact_root / manifest["snapshot_object_key"]
    if not artifact_path.is_file():
        raise FactExtractionError(f"비공개 snapshot artifact가 없습니다: {artifact_path}")
    payload = artifact_path.read_bytes()
    actual_hash = "sha256:" + hashlib.sha256(payload).hexdigest()
    if actual_hash != manifest["snapshot_hash"] or len(payload) != manifest["byte_size"]:
        raise FactExtractionError(f"Snapshot 무결성 검증에 실패했습니다: {artifact_path}")

    facts = parse_facts(payload, manifest)
    for fact in facts:
        _validate_schema(fact, fact_schema, f"fact {fact['fact_key']}")
    fact_set_hash = "sha256:" + hashlib.sha256(_canonical_fact_set(facts)).hexdigest()
    product_version_id = f"pv:{manifest['product_key']}:{fact_set_hash}"
    try:
        relative_manifest_path = manifest_path.relative_to(repository_root).as_posix()
    except ValueError as error:
        raise FactExtractionError("manifest는 repository 내부에 있어야 합니다.") from error
    version = {
        "dataset_class": "PUBLIC_KB",
        "synthetic": False,
        "product_version_id": product_version_id,
        "product_key": manifest["product_key"],
        "fact_set_hash": fact_set_hash,
        "observed_at": manifest["collected_at"],
        "effective_from": manifest.get("effective_from"),
        "effective_to": manifest.get("effective_to"),
        "source_manifest_path": relative_manifest_path,
        "source_snapshot_hash": manifest["snapshot_hash"],
        "source_url": manifest["source_url"],
        "parser_version": PARSER_VERSION,
        "facts": facts,
    }
    _validate_schema(version, version_schema, "product version")

    digest = fact_set_hash.removeprefix("sha256:")
    output_path = output_root / manifest["product_key"] / f"{digest}.json"
    output_payload = (
        json.dumps(version, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")
    created = _write_immutable(output_path, output_payload)
    return ExtractionResult(
        product_key=manifest["product_key"],
        status="CREATED" if created else "DEDUPLICATED",
        product_version_id=product_version_id,
        fact_set_hash=fact_set_hash,
        output_path=output_path,
    )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="KB 공개 상품 product fact 추출기")
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--all", action="store_true", help="모든 manifest 처리")
    target.add_argument("--manifest", type=Path, help="처리할 manifest")
    parser.add_argument("--manifest-root", type=Path, default=DEFAULT_MANIFEST_ROOT)
    parser.add_argument("--artifact-root", type=Path, default=DEFAULT_ARTIFACT_ROOT)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        manifest_paths = (
            sorted(args.manifest_root.rglob("*.json")) if args.all else [args.manifest]
        )
        manifests_with_time = [(_load_json(path)["collected_at"], path) for path in manifest_paths]
        for _, manifest_path in sorted(manifests_with_time):
            result = extract_manifest(
                manifest_path=manifest_path,
                artifact_root=args.artifact_root,
                output_root=args.output_root,
            )
            print(
                json.dumps(
                    {
                        "product_key": result.product_key,
                        "status": result.status,
                        "product_version_id": result.product_version_id,
                        "fact_set_hash": result.fact_set_hash,
                        "output_path": str(result.output_path),
                    },
                    ensure_ascii=False,
                )
            )
    except (FactExtractionError, KeyError, OSError) as error:
        print(f"추출 실패: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
