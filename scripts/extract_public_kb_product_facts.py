from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from bs4 import BeautifulSoup, Tag
from jsonschema import Draft202012Validator, FormatChecker, ValidationError


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OBSERVATION_ROOT = ROOT / "datasets/public/kb/observations"
DEFAULT_ARTIFACT_ROOT = ROOT / ".private-artifacts"
DEFAULT_TERMS_ROOT = ROOT / "datasets/public/kb/product-terms-versions"
DEFAULT_EVIDENCE_ROOT = ROOT / "datasets/public/kb/version-evidence"
DEFAULT_QUOTE_ROOT = ROOT / "datasets/public/kb/rate-quotes"
DEFAULT_EXTRACTION_ATTEMPT_ROOT = ROOT / "datasets/derived/public-kb/extraction-attempts"
DEFAULT_CHANGE_ROOT = ROOT / "datasets/derived/public-kb/change-detection-results"
DEFAULT_OBSERVATION_SCHEMA = ROOT / "contracts/public-observation.schema.json"
DEFAULT_SNAPSHOT_SCHEMA = ROOT / "contracts/public-snapshot-manifest.schema.json"
DEFAULT_TERM_SCHEMA = ROOT / "contracts/public-product-term-fact.schema.json"
DEFAULT_TERMS_SCHEMA = ROOT / "contracts/public-product-terms-version.schema.json"
DEFAULT_EVIDENCE_SCHEMA = ROOT / "contracts/public-version-evidence.schema.json"
DEFAULT_QUOTE_SCHEMA = ROOT / "contracts/public-rate-quote.schema.json"
DEFAULT_ATTEMPT_SCHEMA = ROOT / "contracts/public-extraction-attempt.schema.json"
DEFAULT_CHANGE_SCHEMA = ROOT / "contracts/public-change-detection-result.schema.json"
PARSER_VERSION = "public-kb-html-v2"
CHANGE_POLICY_VERSION = "public-kb-change-v1"


class FactExtractionError(RuntimeError):
    """Raised when facts cannot be extracted without guessing."""

    def __init__(self, message: str, code: str = "EXTRACTION_FAILED") -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class LocatedText:
    text: str
    strategy: str
    selector: str
    label: str


@dataclass(frozen=True)
class ParsedProduct:
    facts: list[dict]
    fact_evidence: list[dict]
    advertised_rate_text: str
    advertised_rate_reference_date: str | None
    rate_locator: dict
    rate_date_locator: dict | None


@dataclass(frozen=True)
class ExtractionResult:
    product_key: str
    status: str
    product_terms_version_id: str
    terms_hash: str
    version_evidence_id: str
    rate_quote_id: str
    extraction_attempt_id: str


def _load_json(path: Path) -> dict:
    try:
        with path.open(encoding="utf-8") as source:
            return json.load(source)
    except (OSError, json.JSONDecodeError) as error:
        raise FactExtractionError(
            f"JSON 파일을 읽을 수 없습니다: {path}", "INVALID_JSON"
        ) from error


def _json_bytes(value: dict) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _validate_schema(instance: dict, schema_path: Path, label: str) -> None:
    schema = _load_json(schema_path)
    try:
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(instance)
    except ValidationError as error:
        raise FactExtractionError(
            f"{label} schema 검증에 실패했습니다: {error.message}",
            "SCHEMA_VALIDATION_FAILED",
        ) from error


def _parse_instant(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise FactExtractionError(
            f"date-time을 해석할 수 없습니다: {value}", "INVALID_TIMESTAMP"
        ) from error
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise FactExtractionError(
            f"timezone 없는 date-time입니다: {value}", "INVALID_TIMESTAMP"
        )
    return parsed.astimezone(UTC)


def _utc_text(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise FactExtractionError(
            "date-time은 timezone-aware 값이어야 합니다.", "INVALID_TIMESTAMP"
        )
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _normalize_text(value: str) -> str:
    return " ".join(value.split())


def _unique(items: list, description: str):
    if len(items) != 1:
        raise FactExtractionError(
            f"{description} 후보는 정확히 한 건이어야 합니다: {len(items)}건",
            "AMBIGUOUS_SOURCE",
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
    raise FactExtractionError(
        f"section 본문을 찾을 수 없습니다: {label}", "SOURCE_NOT_FOUND"
    )


def _labeled_span(soup: BeautifulSoup, label: str) -> LocatedText:
    labels = [
        element
        for element in soup.select("span.info_tit")
        if _normalize_text(element.get_text(" ", strip=True)) == label
    ]
    heading = _unique(labels, f"info label {label}")
    sibling = heading.find_next_sibling("span")
    if sibling is None or "info_txt" not in sibling.get("class", []):
        raise FactExtractionError(
            f"info 본문을 찾을 수 없습니다: {label}", "SOURCE_NOT_FOUND"
        )
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
    return LocatedText(text=text, strategy="CSS_TEXT_MATCH", selector=selector, label=label)


def _evidence_hash(text: str) -> str:
    return "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest()


def _locator(located: LocatedText, observation: dict) -> dict:
    return {
        "strategy": located.strategy,
        "selector": located.selector,
        "label": located.label,
        "evidence_text": located.text,
        "evidence_hash": _evidence_hash(located.text),
        "snapshot_hash": observation["snapshot_hash"],
        "source_url": observation["source_url"],
    }


def _term(
    product_key: str,
    fact_key: str,
    subject_type: str,
    value_type: str,
    value: str | int,
    unit: str,
    locators: list[dict],
) -> tuple[dict, dict]:
    fact_id = f"fact:{product_key}:{fact_key}"
    fact = {
        "fact_id": fact_id,
        "fact_key": fact_key,
        "subject_type": subject_type,
        "value_type": value_type,
        "value": value,
        "unit": unit,
    }
    return fact, {"fact_id": fact_id, "locators": locators}


def _parse_eok_amount(text: str, pattern: re.Pattern[str], label: str) -> int:
    values = {int(value) for value in pattern.findall(text)}
    if len(values) != 1:
        raise FactExtractionError(
            f"{label} 억원 값은 정확히 한 종류여야 합니다: {sorted(values)}",
            "AMBIGUOUS_AMOUNT",
        )
    return values.pop() * 100_000_000


def _normalize_sale_status(located: LocatedText) -> str:
    compact = re.sub(r"[\s-]", "", located.text)
    if compact == "판매중":
        return "SELLING"
    if compact == "판매종료":
        return "DISCONTINUED"
    raise FactExtractionError(
        f"지원하지 않는 판매 상태입니다: {located.text}", "UNKNOWN_SALE_STATUS"
    )


def _common_product_name(soup: BeautifulSoup, product_key: str) -> LocatedText:
    selector = (
        'input[name="브랜드상품명"]'
        if product_key == "boss-plus-overdraft"
        else 'input[name="상세웹상품명"]'
    )
    return _attribute_text(soup, selector, "value", "상품명")


def _parse_small_business_or_overdraft(
    soup: BeautifulSoup, observation: dict
) -> ParsedProduct:
    product_key = observation["product_key"]
    product_name = _common_product_name(soup, product_key)
    eligibility = _labeled_paragraph(soup, "가입대상")
    limit = _labeled_paragraph(soup, "대출한도")
    repayment = _labeled_paragraph(soup, "대출기간과 상환방법")
    sale_status = _labeled_paragraph(soup, "상품판매여부")
    rate = _unique_text_match(
        soup, "span.txt", re.compile(r"최저\s*연\s*.+~\s*최고\s*연\s*.+"), "광고 금리 문구"
    )
    rate_date = _unique_text_match(
        soup,
        "span",
        re.compile(r"\(\d{4}\.\d{2}\.\d{2},\s*신용등급\s*1등급\s*기준\)"),
        "광고 금리 기준일",
    )
    date_match = re.search(r"(\d{4})\.(\d{2})\.(\d{2})", rate_date.text)
    if date_match is None:
        raise FactExtractionError(
            "광고 금리 기준일을 정규화할 수 없습니다.", "INVALID_RATE_DATE"
        )
    normalized_date = "-".join(date_match.groups())
    max_limit = _parse_eok_amount(
        limit.text, re.compile(r"최대\s*(\d+)억원"), "개인사업자 최대한도"
    )

    located_terms = [
        _term(product_key, "product_name", "PRODUCT", "TEXT", product_name.text, "TEXT", [_locator(product_name, observation)]),
        _term(product_key, "applicant_eligibility_text", "PRODUCT", "TEXT", eligibility.text, "TEXT", [_locator(eligibility, observation)]),
        _term(product_key, "max_limit_individual_krw", "SOLE_PROPRIETOR", "INTEGER", max_limit, "KRW", [_locator(eligibility, observation), _locator(limit, observation)]),
        _term(product_key, "repayment_method_text", "PRODUCT", "TEXT", repayment.text, "TEXT", [_locator(repayment, observation)]),
        _term(product_key, "sale_status", "PRODUCT", "STATUS", _normalize_sale_status(sale_status), "STATUS", [_locator(sale_status, observation)]),
    ]
    return ParsedProduct(
        facts=sorted([item[0] for item in located_terms], key=lambda item: item["fact_key"]),
        fact_evidence=sorted([item[1] for item in located_terms], key=lambda item: item["fact_id"]),
        advertised_rate_text=rate.text,
        advertised_rate_reference_date=normalized_date,
        rate_locator=_locator(rate, observation),
        rate_date_locator=_locator(rate_date, observation),
    )


def _parse_seller_loan(soup: BeautifulSoup, observation: dict) -> ParsedProduct:
    product_key = observation["product_key"]
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
    located_terms = [
        _term(product_key, "product_name", "PRODUCT", "TEXT", product_name.text, "TEXT", [_locator(product_name, observation)]),
        _term(product_key, "applicant_eligibility_text", "PRODUCT", "TEXT", eligibility.text, "TEXT", [_locator(eligibility, observation)]),
        _term(product_key, "max_limit_individual_krw", "SOLE_PROPRIETOR", "INTEGER", individual_limit, "KRW", [_locator(limit, observation)]),
        _term(product_key, "max_limit_corporate_krw", "CORPORATION", "INTEGER", corporate_limit, "KRW", [_locator(limit, observation)]),
        _term(product_key, "repayment_method_text", "PRODUCT", "TEXT", repayment.text, "TEXT", [_locator(repayment, observation)]),
        _term(product_key, "sale_status", "PRODUCT", "STATUS", _normalize_sale_status(sale_status), "STATUS", [_locator(sale_status, observation)]),
    ]
    return ParsedProduct(
        facts=sorted([item[0] for item in located_terms], key=lambda item: item["fact_key"]),
        fact_evidence=sorted([item[1] for item in located_terms], key=lambda item: item["fact_id"]),
        advertised_rate_text=rate.text,
        advertised_rate_reference_date=None,
        rate_locator=_locator(rate, observation),
        rate_date_locator=None,
    )


def parse_product(payload: bytes, observation: dict) -> ParsedProduct:
    try:
        html = payload.decode("utf-8")
    except UnicodeDecodeError as error:
        raise FactExtractionError(
            "Snapshot이 UTF-8이 아닙니다.", "INVALID_ENCODING"
        ) from error
    soup = BeautifulSoup(html, "html.parser")
    product_key = observation["product_key"]
    if product_key in {"small-business-credit", "boss-plus-overdraft"}:
        return _parse_small_business_or_overdraft(soup, observation)
    if product_key == "kb-seller-loan":
        return _parse_seller_loan(soup, observation)
    raise FactExtractionError(
        f"지원하지 않는 product_key입니다: {product_key}", "UNKNOWN_PRODUCT_KEY"
    )


def _canonical_terms(facts: list[dict]) -> bytes:
    return json.dumps(
        facts, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def _write_immutable(path: Path, payload: bytes) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.read_bytes() != payload:
            raise FactExtractionError(
                f"기존 immutable 파일의 내용이 다릅니다: {path}",
                "IMMUTABLE_CONFLICT",
            )
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


def _run_uuid(run_id: str) -> uuid.UUID:
    if not run_id.startswith("run:"):
        raise FactExtractionError("run ID 형식이 올바르지 않습니다.", "INVALID_RUN_ID")
    try:
        return uuid.UUID(hex=run_id.removeprefix("run:"))
    except ValueError as error:
        raise FactExtractionError(
            "run ID 형식이 올바르지 않습니다.", "INVALID_RUN_ID"
        ) from error


def _observation_uuid(observation_id: str) -> uuid.UUID:
    try:
        return uuid.UUID(hex=observation_id.rsplit(":", 1)[-1])
    except ValueError as error:
        raise FactExtractionError(
            "observation ID 형식이 올바르지 않습니다.", "INVALID_OBSERVATION_ID"
        ) from error


def _event_path(root: Path, occurred_at: datetime, product_key: str, event_id: str, suffix: str) -> Path:
    token = event_id.rsplit(":", 1)[-1]
    return root / occurred_at.date().isoformat() / f"{product_key}--{token}.{suffix}.json"


def _find_event_path(root: Path, event_id: str, suffix: str) -> Path | None:
    if not root.exists():
        return None
    token = event_id.rsplit(":", 1)[-1]
    matches = list(root.rglob(f"*--{token}.{suffix}.json"))
    if len(matches) > 1:
        raise FactExtractionError(
            f"중복 event ID 파일입니다: {event_id}", "DUPLICATE_EVENT_ID"
        )
    return matches[0] if matches else None


def _relative(path: Path, repository_root: Path) -> str:
    try:
        return path.relative_to(repository_root).as_posix()
    except ValueError as error:
        raise FactExtractionError(
            "참조 파일은 repository 내부에 있어야 합니다.", "PATH_OUTSIDE_REPOSITORY"
        ) from error


def _audit_error_message(error_code: str) -> str:
    return f"공개 상품 추출 실패 ({error_code})"


def extract_observation(
    observation_path: Path,
    *,
    artifact_root: Path = DEFAULT_ARTIFACT_ROOT,
    terms_root: Path = DEFAULT_TERMS_ROOT,
    evidence_root: Path = DEFAULT_EVIDENCE_ROOT,
    quote_root: Path = DEFAULT_QUOTE_ROOT,
    attempt_root: Path = DEFAULT_EXTRACTION_ATTEMPT_ROOT,
    repository_root: Path = ROOT,
    extraction_run_id: str,
    attempt_sequence: int = 1,
    attempted_at: datetime | None = None,
    attempted_at_source: str = "MEASURED",
    parser_version: str = PARSER_VERSION,
) -> ExtractionResult:
    observation = _load_json(observation_path)
    _validate_schema(observation, DEFAULT_OBSERVATION_SCHEMA, "observation")
    product_key = observation["product_key"]
    attempted = attempted_at or datetime.now(UTC)
    attempted_text = _utc_text(attempted)
    attempt_uuid = uuid.uuid5(
        _run_uuid(extraction_run_id),
        f"extraction:{observation['observation_id']}:{attempt_sequence}",
    )
    attempt_id = f"extract:{product_key}:{attempt_uuid.hex}"
    attempt_path = _event_path(
        attempt_root, attempted.astimezone(UTC), product_key, attempt_id, "extraction-attempt"
    )
    existing_attempt_path = _find_event_path(
        attempt_root, attempt_id, "extraction-attempt"
    )
    if existing_attempt_path:
        attempt_path = existing_attempt_path
        record = _load_json(attempt_path)
        if record["status"] == "FAILED":
            raise FactExtractionError(record["error_message"], record["error_code"])
        terms_hash = record["product_terms_version_id"].rsplit(":", 1)[-1]
        return ExtractionResult(
            product_key,
            "REPLAYED",
            record["product_terms_version_id"],
            f"sha256:{terms_hash}",
            record["version_evidence_id"],
            record["rate_quote_id"],
            attempt_id,
        )

    try:
        snapshot_path = repository_root / observation["snapshot_manifest_path"]
        snapshot = _load_json(snapshot_path)
        _validate_schema(snapshot, DEFAULT_SNAPSHOT_SCHEMA, "snapshot")
        if snapshot["snapshot_hash"] != observation["snapshot_hash"]:
            raise FactExtractionError(
                "Observation과 snapshot hash가 다릅니다.", "SNAPSHOT_REFERENCE_MISMATCH"
            )
        artifact_path = artifact_root / snapshot["snapshot_object_key"]
        if not artifact_path.is_file():
            raise FactExtractionError(
                f"비공개 snapshot artifact가 없습니다: {artifact_path}",
                "ARTIFACT_MISSING",
            )
        payload = artifact_path.read_bytes()
        actual_hash = "sha256:" + hashlib.sha256(payload).hexdigest()
        if actual_hash != snapshot["snapshot_hash"] or len(payload) != snapshot["byte_size"]:
            raise FactExtractionError(
                f"Snapshot 무결성 검증에 실패했습니다: {artifact_path}",
                "ARTIFACT_INTEGRITY_FAILED",
            )

        parsed = parse_product(payload, observation)
        for fact in parsed.facts:
            _validate_schema(fact, DEFAULT_TERM_SCHEMA, f"term fact {fact['fact_key']}")
        terms_hash = "sha256:" + hashlib.sha256(_canonical_terms(parsed.facts)).hexdigest()
        terms_id = f"ptv:{product_key}:{terms_hash}"
        terms = {
            "dataset_class": "PUBLIC_KB",
            "synthetic": False,
            "product_terms_version_id": terms_id,
            "product_key": product_key,
            "terms_hash": terms_hash,
            "effective_from": None,
            "effective_to": None,
            "facts": parsed.facts,
        }
        _validate_schema(terms, DEFAULT_TERMS_SCHEMA, "product terms version")
        terms_path = terms_root / product_key / f"{terms_hash.removeprefix('sha256:')}.json"
        terms_created = _write_immutable(terms_path, _json_bytes(terms))

        obs_uuid = _observation_uuid(observation["observation_id"])
        evidence_uuid = uuid.uuid5(obs_uuid, f"evidence:{parser_version}")
        quote_uuid = uuid.uuid5(obs_uuid, f"quote:{parser_version}")
        evidence_id = f"evidence:{product_key}:{evidence_uuid.hex}"
        quote_id = f"quote:{product_key}:{quote_uuid.hex}"
        observed_at = _parse_instant(observation["observed_at"])
        evidence = {
            "dataset_class": "PUBLIC_KB",
            "synthetic": False,
            "version_evidence_id": evidence_id,
            "observation_id": observation["observation_id"],
            "product_terms_version_id": terms_id,
            "product_key": product_key,
            "snapshot_hash": observation["snapshot_hash"],
            "parser_version": parser_version,
            "fact_evidence": parsed.fact_evidence,
        }
        quote = {
            "dataset_class": "PUBLIC_KB",
            "synthetic": False,
            "rate_quote_id": quote_id,
            "observation_id": observation["observation_id"],
            "product_key": product_key,
            "advertised_rate_text": parsed.advertised_rate_text,
            "advertised_rate_reference_date": parsed.advertised_rate_reference_date,
            "text_locator": parsed.rate_locator,
            "reference_date_locator": parsed.rate_date_locator,
        }
        _validate_schema(evidence, DEFAULT_EVIDENCE_SCHEMA, "version evidence")
        _validate_schema(quote, DEFAULT_QUOTE_SCHEMA, "rate quote")
        evidence_path = _event_path(
            evidence_root, observed_at, product_key, evidence_id, "version-evidence"
        )
        quote_path = _event_path(quote_root, observed_at, product_key, quote_id, "rate-quote")
        _write_immutable(evidence_path, _json_bytes(evidence))
        _write_immutable(quote_path, _json_bytes(quote))

        success = {
            "dataset_class": "DERIVED",
            "extraction_attempt_id": attempt_id,
            "extraction_run_id": extraction_run_id,
            "observation_id": observation["observation_id"],
            "product_key": product_key,
            "attempt_sequence": attempt_sequence,
            "attempted_at": attempted_text,
            "attempted_at_source": attempted_at_source,
            "parser_version": parser_version,
            "status": "SUCCEEDED",
            "product_terms_version_id": terms_id,
            "version_evidence_id": evidence_id,
            "rate_quote_id": quote_id,
            "error_code": None,
            "error_message": None,
        }
        _validate_schema(success, DEFAULT_ATTEMPT_SCHEMA, "extraction attempt")
        _write_immutable(attempt_path, _json_bytes(success))
    except (FactExtractionError, OSError) as error:
        error_code = getattr(error, "code", "STORAGE_FAILED")
        failure = {
            "dataset_class": "DERIVED",
            "extraction_attempt_id": attempt_id,
            "extraction_run_id": extraction_run_id,
            "observation_id": observation["observation_id"],
            "product_key": product_key,
            "attempt_sequence": attempt_sequence,
            "attempted_at": attempted_text,
            "attempted_at_source": attempted_at_source,
            "parser_version": parser_version,
            "status": "FAILED",
            "product_terms_version_id": None,
            "version_evidence_id": None,
            "rate_quote_id": None,
            "error_code": error_code,
            "error_message": _audit_error_message(error_code),
        }
        _validate_schema(failure, DEFAULT_ATTEMPT_SCHEMA, "extraction attempt")
        _write_immutable(attempt_path, _json_bytes(failure))
        if isinstance(error, FactExtractionError):
            raise
        raise FactExtractionError(
            "추출 결과 저장에 실패했습니다.", error_code
        ) from error

    return ExtractionResult(
        product_key,
        "CREATED" if terms_created else "TERMS_REUSED",
        terms_id,
        terms_hash,
        evidence_id,
        quote_id,
        attempt_id,
    )


def _active_change_results(change_root: Path) -> dict[str, dict]:
    records = [_load_json(path) for path in change_root.rglob("*.json")] if change_root.exists() else []
    by_id = {item["change_detection_result_id"]: item for item in records}
    superseded = {item["supersedes_result_id"] for item in records if item["supersedes_result_id"]}
    return {item["observation_id"]: item for key, item in by_id.items() if key not in superseded}


def rebuild_change_detection(
    *,
    observation_root: Path,
    evidence_root: Path,
    quote_root: Path,
    change_root: Path,
) -> list[dict]:
    observations = {
        item["observation_id"]: item
        for item in (_load_json(path) for path in observation_root.rglob("*.observation.json"))
    }
    evidence_by_observation = {
        item["observation_id"]: item
        for item in (_load_json(path) for path in evidence_root.rglob("*.version-evidence.json"))
    }
    quote_by_observation = {
        item["observation_id"]: item
        for item in (_load_json(path) for path in quote_root.rglob("*.rate-quote.json"))
    }
    complete_observation_ids = (
        observations.keys()
        & evidence_by_observation.keys()
        & quote_by_observation.keys()
    )
    active = _active_change_results(change_root)
    created = []
    products = sorted(
        {
            evidence_by_observation[observation_id]["product_key"]
            for observation_id in complete_observation_ids
        }
    )
    for product_key in products:
        timeline = [
            observations[observation_id]
            for observation_id in complete_observation_ids
            if evidence_by_observation[observation_id]["product_key"] == product_key
        ]
        timeline.sort(key=lambda item: (_parse_instant(item["observed_at"]), item["observation_id"]))
        previous = None
        for observation in timeline:
            observation_id = observation["observation_id"]
            evidence = evidence_by_observation[observation_id]
            quote = quote_by_observation[observation_id]
            if previous is None:
                classifications = ["BASELINE_ESTABLISHED"]
                previous_id = None
            else:
                previous_id = previous["observation_id"]
                previous_evidence = evidence_by_observation[previous_id]
                previous_quote = quote_by_observation[previous_id]
                classifications = []
                if evidence["product_terms_version_id"] != previous_evidence["product_terms_version_id"]:
                    classifications.append("PRODUCT_TERMS_CHANGED")
                if quote["advertised_rate_text"] != previous_quote["advertised_rate_text"]:
                    classifications.append("RATE_QUOTE_CHANGED")
                elif quote["advertised_rate_reference_date"] != previous_quote["advertised_rate_reference_date"]:
                    classifications.append("QUOTE_REFRESHED")
                if not classifications:
                    classifications.append("NO_SEMANTIC_CHANGE")

            prior_result = active.get(observation_id)
            if prior_result and prior_result["previous_observation_id"] == previous_id and prior_result["classifications"] == classifications:
                previous = observation
                continue
            body = {
                "product_key": product_key,
                "observation_id": observation_id,
                "previous_observation_id": previous_id,
                "evaluated_at": observation["observed_at"],
                "policy_version": CHANGE_POLICY_VERSION,
                "classifications": classifications,
                "supersedes_result_id": prior_result["change_detection_result_id"] if prior_result else None,
            }
            digest = hashlib.sha256(
                json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
            ).hexdigest()
            result = {
                "dataset_class": "DERIVED",
                "change_detection_result_id": f"change:{product_key}:{digest}",
                **body,
            }
            _validate_schema(result, DEFAULT_CHANGE_SCHEMA, "change detection result")
            path = _event_path(
                change_root,
                _parse_instant(observation["observed_at"]),
                product_key,
                result["change_detection_result_id"],
                "change-detection",
            )
            _write_immutable(path, _json_bytes(result))
            active[observation_id] = result
            created.append(result)
            previous = observation
    return created


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="KB 공개 상품 terms와 quote 추출기")
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--all", action="store_true", help="모든 Observation 처리")
    target.add_argument("--observation", type=Path, help="처리할 Observation")
    parser.add_argument("--observation-root", type=Path, default=DEFAULT_OBSERVATION_ROOT)
    parser.add_argument("--artifact-root", type=Path, default=DEFAULT_ARTIFACT_ROOT)
    parser.add_argument("--terms-root", type=Path, default=DEFAULT_TERMS_ROOT)
    parser.add_argument("--evidence-root", type=Path, default=DEFAULT_EVIDENCE_ROOT)
    parser.add_argument("--quote-root", type=Path, default=DEFAULT_QUOTE_ROOT)
    parser.add_argument("--attempt-root", type=Path, default=DEFAULT_EXTRACTION_ATTEMPT_ROOT)
    parser.add_argument("--change-root", type=Path, default=DEFAULT_CHANGE_ROOT)
    parser.add_argument("--run-id", help="재실행 멱등성을 위한 extraction run ID")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    run_id = args.run_id or f"run:{uuid.uuid4().hex}"
    try:
        _run_uuid(run_id)
        paths = (
            list(args.observation_root.rglob("*.observation.json"))
            if args.all
            else [args.observation]
        )
        loaded = [(_load_json(path), path) for path in paths]
        loaded.sort(
            key=lambda item: (
                _parse_instant(item[0]["observed_at"]),
                item[0]["observation_id"],
            )
        )
    except (FactExtractionError, OSError) as error:
        print(
            _audit_error_message(getattr(error, "code", "EXTRACTION_FAILED")),
            file=sys.stderr,
        )
        return 2

    sequences: dict[str, int] = {}
    results = []
    failed = False
    for observation, path in loaded:
        product_key = observation["product_key"]
        sequences[product_key] = sequences.get(product_key, 0) + 1
        try:
            result = extract_observation(
                path,
                artifact_root=args.artifact_root,
                terms_root=args.terms_root,
                evidence_root=args.evidence_root,
                quote_root=args.quote_root,
                attempt_root=args.attempt_root,
                extraction_run_id=run_id,
                attempt_sequence=sequences[product_key],
            )
            results.append(
                {
                    "product_key": product_key,
                    "observation_id": observation["observation_id"],
                    "status": "SUCCEEDED",
                    "terms_status": result.status,
                    "product_terms_version_id": result.product_terms_version_id,
                }
            )
        except (FactExtractionError, OSError) as error:
            failed = True
            results.append(
                {
                    "product_key": product_key,
                    "observation_id": observation["observation_id"],
                    "status": "FAILED",
                    "error_code": getattr(error, "code", "EXTRACTION_FAILED"),
                    "error_message": _audit_error_message(
                        getattr(error, "code", "EXTRACTION_FAILED")
                    ),
                }
            )

    try:
        rebuild_change_detection(
            observation_root=args.observation_root,
            evidence_root=args.evidence_root,
            quote_root=args.quote_root,
            change_root=args.change_root,
        )
    except (FactExtractionError, OSError) as error:
        print(
            _audit_error_message(getattr(error, "code", "CHANGE_DETECTION_FAILED")),
            file=sys.stderr,
        )
        return 2

    print(json.dumps({"run_id": run_id, "results": results}, ensure_ascii=False))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
