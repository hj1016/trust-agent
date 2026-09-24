from __future__ import annotations

import argparse
import hashlib
import json
import os
import ssl
import sys
import tempfile
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Callable
from urllib.parse import urlparse

import certifi
from jsonschema import Draft202012Validator, FormatChecker, ValidationError


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOG = ROOT / "datasets/public/kb/catalog/product-catalog.json"
DEFAULT_CATALOG_SCHEMA = ROOT / "contracts/public-product-catalog.schema.json"
DEFAULT_MANIFEST_ROOT = ROOT / "datasets/public/kb/manifests"
DEFAULT_OBSERVATION_ROOT = ROOT / "datasets/public/kb/observations"
DEFAULT_COLLECTION_ATTEMPT_ROOT = (
    ROOT / "datasets/derived/public-kb/collection-attempts"
)
DEFAULT_ARTIFACT_ROOT = ROOT / ".private-artifacts"
DEFAULT_SCHEMA = ROOT / "contracts/public-snapshot-manifest.schema.json"
DEFAULT_OBSERVATION_SCHEMA = ROOT / "contracts/public-observation.schema.json"
DEFAULT_COLLECTION_ATTEMPT_SCHEMA = (
    ROOT / "contracts/public-collection-attempt.schema.json"
)
DEFAULT_TIMEOUT_SECONDS = 15.0
DEFAULT_MAX_BYTES = 5 * 1024 * 1024
USER_AGENT = "TrustAgent-MVP/0.2 public-snapshot-collector"


class CollectorError(RuntimeError):
    """Raised when a collection attempt cannot satisfy its contract."""

    def __init__(self, message: str, code: str = "COLLECTION_FAILED") -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class CatalogProduct:
    product_key: str
    display_name: str
    source_url: str
    source_marker: str


@dataclass(frozen=True)
class FetchResponse:
    payload: bytes
    content_type: str
    final_url: str


@dataclass(frozen=True)
class CollectionResult:
    product_key: str
    status: str
    snapshot_hash: str
    artifact_path: Path
    manifest_path: Path
    observation_path: Path
    collection_attempt_path: Path
    observation_id: str
    collection_attempt_id: str


Fetcher = Callable[[str], FetchResponse]


class AllowedRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_source_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _load_json(path: Path) -> dict:
    try:
        with path.open(encoding="utf-8") as source:
            return json.load(source)
    except (OSError, json.JSONDecodeError) as error:
        raise CollectorError(
            f"JSON 파일을 읽을 수 없습니다: {path}", "INVALID_JSON"
        ) from error


def _json_bytes(value: dict) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _utc_text(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise CollectorError(
            "date-time은 timezone-aware 값이어야 합니다.", "INVALID_TIMESTAMP"
        )
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _new_run_id() -> str:
    return f"run:{uuid.uuid4().hex}"


def _validate_run_id(run_id: str) -> uuid.UUID:
    if not run_id.startswith("run:"):
        raise CollectorError("run ID 형식이 올바르지 않습니다.", "INVALID_RUN_ID")
    try:
        value = uuid.UUID(hex=run_id.removeprefix("run:"))
    except ValueError as error:
        raise CollectorError(
            "run ID 형식이 올바르지 않습니다.", "INVALID_RUN_ID"
        ) from error
    if value.hex != run_id.removeprefix("run:"):
        raise CollectorError("run ID 형식이 올바르지 않습니다.", "INVALID_RUN_ID")
    return value


def _event_ids(run_id: str, product_key: str, attempt_sequence: int) -> tuple[str, str]:
    if attempt_sequence < 1:
        raise CollectorError(
            "attempt sequence는 1 이상이어야 합니다.", "INVALID_ATTEMPT_SEQUENCE"
        )
    namespace = _validate_run_id(run_id)
    attempt_uuid = uuid.uuid5(namespace, f"collection:{product_key}:{attempt_sequence}")
    observation_uuid = uuid.uuid5(attempt_uuid, "observation")
    return (
        f"collect:{product_key}:{attempt_uuid.hex}",
        f"obs:{product_key}:{observation_uuid.hex}",
    )


def load_catalog(
    path: Path, schema_path: Path = DEFAULT_CATALOG_SCHEMA
) -> list[CatalogProduct]:
    catalog = _load_json(path)
    _validate_json_schema(catalog, schema_path, "catalog")
    products = catalog.get("products")
    if not isinstance(products, list) or not products:
        raise CollectorError("catalog에 products가 필요합니다.", "INVALID_CATALOG")

    result = []
    seen_keys = set()
    for item in products:
        try:
            product = CatalogProduct(
                product_key=item["product_key"],
                display_name=item["display_name"],
                source_url=item["source_url"],
                source_marker=item["source_marker"],
            )
        except (KeyError, TypeError) as error:
            raise CollectorError(
                "catalog product 필드가 올바르지 않습니다.", "INVALID_CATALOG"
            ) from error
        if product.product_key in seen_keys:
            raise CollectorError(
                f"중복 product_key입니다: {product.product_key}", "INVALID_CATALOG"
            )
        validate_source_url(product.source_url)
        seen_keys.add(product.product_key)
        result.append(product)
    return result


def validate_source_url(url: str) -> None:
    parsed = urlparse(url)
    hostname = (parsed.hostname or "").lower()
    allowed_host = hostname == "kbstar.com" or hostname.endswith(".kbstar.com")
    if (
        parsed.scheme != "https"
        or not allowed_host
        or parsed.username is not None
        or parsed.password is not None
        or parsed.port not in (None, 443)
    ):
        raise CollectorError(
            f"허용되지 않은 source URL입니다: {url}", "SOURCE_URL_REJECTED"
        )


def fetch_http(
    url: str,
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
    max_bytes: int = DEFAULT_MAX_BYTES,
) -> FetchResponse:
    validate_source_url(url)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    tls_context = ssl.create_default_context(cafile=certifi.where())
    opener = urllib.request.build_opener(
        AllowedRedirectHandler(),
        urllib.request.HTTPSHandler(context=tls_context),
    )
    try:
        with opener.open(request, timeout=timeout_seconds) as response:
            final_url = response.geturl()
            validate_source_url(final_url)
            content_type = response.headers.get_content_type().lower()
            payload = response.read(max_bytes + 1)
    except (OSError, urllib.error.URLError, ValueError) as error:
        raise CollectorError(
            f"공개 페이지 수집에 실패했습니다: {url}", "HTTP_FETCH_FAILED"
        ) from error

    if len(payload) > max_bytes:
        raise CollectorError(
            f"응답이 최대 크기 {max_bytes} bytes를 초과했습니다.",
            "RESPONSE_TOO_LARGE",
        )
    return FetchResponse(payload=payload, content_type=content_type, final_url=final_url)


def _validate_html(response: FetchResponse, product: CatalogProduct) -> None:
    if response.content_type != "text/html":
        raise CollectorError(
            f"HTML 응답이 아닙니다: {response.content_type}", "NON_HTML_RESPONSE"
        )
    if not response.payload:
        raise CollectorError("빈 HTML 응답입니다.", "EMPTY_RESPONSE")
    if len(response.payload) > DEFAULT_MAX_BYTES:
        raise CollectorError(
            f"응답이 최대 크기 {DEFAULT_MAX_BYTES} bytes를 초과했습니다.",
            "RESPONSE_TOO_LARGE",
        )
    try:
        html = response.payload.decode("utf-8")
    except UnicodeDecodeError as error:
        raise CollectorError(
            "HTML 응답이 UTF-8이 아닙니다.", "INVALID_ENCODING"
        ) from error
    lowered = html.lower()
    if "<html" not in lowered and "<!doctype html" not in lowered:
        raise CollectorError("HTML 문서 식별자가 없습니다.", "INVALID_HTML")
    if product.source_marker not in html:
        raise CollectorError(
            f"상품 식별 문자열이 없습니다: {product.source_marker}",
            "PRODUCT_MARKER_MISSING",
        )


def _validate_json_schema(instance: dict, schema_path: Path, label: str) -> None:
    schema = _load_json(schema_path)
    try:
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(instance)
    except ValidationError as error:
        raise CollectorError(
            f"{label} schema 검증에 실패했습니다: {error.message}",
            "SCHEMA_VALIDATION_FAILED",
        ) from error


def _write_immutable(path: Path, payload: bytes) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.read_bytes() != payload:
            raise CollectorError(
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
            if path.read_bytes() != payload:
                raise CollectorError(
                    f"기존 immutable 파일의 내용이 다릅니다: {path}",
                    "IMMUTABLE_CONFLICT",
                )
            return False
        return True
    finally:
        temporary_path.unlink(missing_ok=True)


def _find_existing_manifest(
    manifest_root: Path, product_key: str, snapshot_hash: str
) -> tuple[Path, dict] | None:
    if not manifest_root.exists():
        return None
    for path in sorted(manifest_root.rglob("*.manifest.json")):
        manifest = _load_json(path)
        if (
            manifest.get("product_key") == product_key
            and manifest.get("snapshot_hash") == snapshot_hash
        ):
            return path, manifest
    return None


def _relative_path(path: Path, repository_root: Path) -> str:
    try:
        return path.relative_to(repository_root).as_posix()
    except ValueError as error:
        raise CollectorError(
            "공개 dataset 파일은 repository 내부에 있어야 합니다.",
            "PATH_OUTSIDE_REPOSITORY",
        ) from error


def _event_path(root: Path, occurred_at: datetime, product_key: str, event_id: str, suffix: str) -> Path:
    token = event_id.rsplit(":", 1)[-1]
    return root / occurred_at.astimezone(UTC).date().isoformat() / f"{product_key}--{token}.{suffix}.json"


def _find_event_path(root: Path, event_id: str, suffix: str) -> Path | None:
    if not root.exists():
        return None
    token = event_id.rsplit(":", 1)[-1]
    matches = list(root.rglob(f"*--{token}.{suffix}.json"))
    if len(matches) > 1:
        raise CollectorError(
            f"중복 event ID 파일입니다: {event_id}", "DUPLICATE_EVENT_ID"
        )
    return matches[0] if matches else None


def _attempt_record(
    *,
    attempt_id: str,
    run_id: str,
    product_key: str,
    attempt_sequence: int,
    attempted_at: str,
    status: str,
    observation_id: str | None,
    error_code: str | None,
    error_message: str | None,
) -> dict:
    return {
        "dataset_class": "DERIVED",
        "collection_attempt_id": attempt_id,
        "collection_run_id": run_id,
        "product_key": product_key,
        "attempt_sequence": attempt_sequence,
        "attempted_at": attempted_at,
        "status": status,
        "observation_id": observation_id,
        "error_code": error_code,
        "error_message": error_message,
    }


def _audit_error_message(error_code: str) -> str:
    return f"공개 상품 수집 실패 ({error_code})"


def collect_product(
    product: CatalogProduct,
    fetcher: Fetcher,
    artifact_root: Path,
    manifest_root: Path,
    schema_path: Path,
    collected_at: datetime,
    acquisition_method: str = "HTTP_DOWNLOAD",
    acquisition_note: str | None = None,
    *,
    observation_root: Path | None = None,
    collection_attempt_root: Path | None = None,
    observation_schema: Path = DEFAULT_OBSERVATION_SCHEMA,
    collection_attempt_schema: Path = DEFAULT_COLLECTION_ATTEMPT_SCHEMA,
    repository_root: Path = ROOT,
    run_id: str | None = None,
    attempt_sequence: int = 1,
) -> CollectionResult:
    observed_text = _utc_text(collected_at)
    observed_utc = collected_at.astimezone(UTC)
    if acquisition_method == "MANUAL_DOWNLOAD" and not acquisition_note:
        raise CollectorError(
            "수동 취득에는 acquisition_note가 필요합니다.", "ACQUISITION_NOTE_REQUIRED"
        )
    if acquisition_method == "HTTP_DOWNLOAD" and acquisition_note:
        raise CollectorError(
            "HTTP 취득에는 acquisition_note를 사용할 수 없습니다.",
            "ACQUISITION_NOTE_NOT_ALLOWED",
        )

    actual_run_id = run_id or _new_run_id()
    attempt_id, observation_id = _event_ids(
        actual_run_id, product.product_key, attempt_sequence
    )
    observation_root = observation_root or manifest_root.parent / "observations"
    collection_attempt_root = (
        collection_attempt_root
        or repository_root / "datasets/derived/public-kb/collection-attempts"
    )
    observation_path = _event_path(
        observation_root, observed_utc, product.product_key, observation_id, "observation"
    )
    attempt_path = _event_path(
        collection_attempt_root,
        observed_utc,
        product.product_key,
        attempt_id,
        "collection-attempt",
    )

    existing_attempt_path = _find_event_path(
        collection_attempt_root, attempt_id, "collection-attempt"
    )
    existing_observation_path = _find_event_path(
        observation_root, observation_id, "observation"
    )
    if existing_attempt_path:
        attempt_path = existing_attempt_path
        attempt = _load_json(attempt_path)
        _validate_json_schema(attempt, collection_attempt_schema, "collection attempt")
        if attempt["status"] == "FAILED":
            raise CollectorError(attempt["error_message"], attempt["error_code"])
        if not existing_observation_path:
            raise CollectorError(
                "성공 attempt의 Observation이 없습니다.", "OBSERVATION_MISSING"
            )
        observation_path = existing_observation_path
        observation = _load_json(observation_path)
        manifest_path = repository_root / observation["snapshot_manifest_path"]
        manifest = _load_json(manifest_path)
        artifact_path = artifact_root / manifest["snapshot_object_key"]
        return CollectionResult(
            product.product_key,
            "REPLAYED",
            observation["snapshot_hash"],
            artifact_path,
            manifest_path,
            observation_path,
            attempt_path,
            observation_id,
            attempt_id,
        )

    if existing_observation_path:
        observation_path = existing_observation_path
        observation = _load_json(observation_path)
        success = _attempt_record(
            attempt_id=attempt_id,
            run_id=actual_run_id,
            product_key=product.product_key,
            attempt_sequence=attempt_sequence,
            attempted_at=observed_text,
            status="SUCCEEDED",
            observation_id=observation_id,
            error_code=None,
            error_message=None,
        )
        _validate_json_schema(success, collection_attempt_schema, "collection attempt")
        _write_immutable(attempt_path, _json_bytes(success))
        manifest_path = repository_root / observation["snapshot_manifest_path"]
        manifest = _load_json(manifest_path)
        return CollectionResult(
            product.product_key,
            "REPLAYED",
            observation["snapshot_hash"],
            artifact_root / manifest["snapshot_object_key"],
            manifest_path,
            observation_path,
            attempt_path,
            observation_id,
            attempt_id,
        )

    try:
        response = fetcher(product.source_url)
        validate_source_url(response.final_url)
        _validate_html(response, product)

        digest = hashlib.sha256(response.payload).hexdigest()
        snapshot_hash = f"sha256:{digest}"
        object_key = f"public-kb/sha256/{digest}.html"
        artifact_path = artifact_root / object_key
        existing = _find_existing_manifest(
            manifest_root, product.product_key, snapshot_hash
        )

        if existing:
            manifest_path, manifest = existing
            _validate_json_schema(manifest, schema_path, "snapshot")
            expected = {
                "source_url": product.source_url,
                "snapshot_object_key": object_key,
                "byte_size": len(response.payload),
            }
            for field, expected_value in expected.items():
                if manifest.get(field) != expected_value:
                    raise CollectorError(
                        f"기존 snapshot의 {field}가 수집 결과와 다릅니다: {manifest_path}",
                        "SNAPSHOT_CONFLICT",
                    )
            artifact_created = _write_immutable(artifact_path, response.payload)
            snapshot_status = "ARTIFACT_RESTORED" if artifact_created else "DEDUPLICATED"
        else:
            manifest = {
                "dataset_class": "PUBLIC_KB",
                "product_key": product.product_key,
                "source_url": product.source_url,
                "snapshot_storage": "LOCAL_PRIVATE",
                "snapshot_object_key": object_key,
                "content_type": "text/html",
                "byte_size": len(response.payload),
                "snapshot_hash": snapshot_hash,
                "synthetic": False,
            }
            _validate_json_schema(manifest, schema_path, "snapshot")
            manifest_path = (
                manifest_root
                / observed_utc.date().isoformat()
                / f"{product.product_key}--{digest}.manifest.json"
            )
            _write_immutable(artifact_path, response.payload)
            _write_immutable(manifest_path, _json_bytes(manifest))
            snapshot_status = "CREATED"

        observation = {
            "dataset_class": "PUBLIC_KB",
            "synthetic": False,
            "observation_id": observation_id,
            "collection_attempt_id": attempt_id,
            "collection_run_id": actual_run_id,
            "product_key": product.product_key,
            "source_url": product.source_url,
            "final_url": response.final_url,
            "observed_at": observed_text,
            "acquisition_method": acquisition_method,
            "snapshot_manifest_path": _relative_path(manifest_path, repository_root),
            "snapshot_hash": snapshot_hash,
        }
        if acquisition_note:
            observation["acquisition_note"] = acquisition_note
        _validate_json_schema(observation, observation_schema, "observation")
        _write_immutable(observation_path, _json_bytes(observation))

        success = _attempt_record(
            attempt_id=attempt_id,
            run_id=actual_run_id,
            product_key=product.product_key,
            attempt_sequence=attempt_sequence,
            attempted_at=observed_text,
            status="SUCCEEDED",
            observation_id=observation_id,
            error_code=None,
            error_message=None,
        )
        _validate_json_schema(success, collection_attempt_schema, "collection attempt")
        _write_immutable(attempt_path, _json_bytes(success))
    except CollectorError as error:
        failure = _attempt_record(
            attempt_id=attempt_id,
            run_id=actual_run_id,
            product_key=product.product_key,
            attempt_sequence=attempt_sequence,
            attempted_at=observed_text,
            status="FAILED",
            observation_id=None,
            error_code=error.code,
            error_message=_audit_error_message(error.code),
        )
        _validate_json_schema(failure, collection_attempt_schema, "collection attempt")
        _write_immutable(attempt_path, _json_bytes(failure))
        raise
    except OSError as error:
        failure = _attempt_record(
            attempt_id=attempt_id,
            run_id=actual_run_id,
            product_key=product.product_key,
            attempt_sequence=attempt_sequence,
            attempted_at=observed_text,
            status="FAILED",
            observation_id=None,
            error_code="STORAGE_FAILED",
            error_message="수집 결과 저장에 실패했습니다.",
        )
        _validate_json_schema(failure, collection_attempt_schema, "collection attempt")
        _write_immutable(attempt_path, _json_bytes(failure))
        raise CollectorError(
            "수집 결과 저장에 실패했습니다.", "STORAGE_FAILED"
        ) from error

    return CollectionResult(
        product.product_key,
        snapshot_status,
        snapshot_hash,
        artifact_path,
        manifest_path,
        observation_path,
        attempt_path,
        observation_id,
        attempt_id,
    )


def _manual_fetcher(path: Path, source_url: str) -> Fetcher:
    payload = path.read_bytes()

    def fetcher(_: str) -> FetchResponse:
        return FetchResponse(payload=payload, content_type="text/html", final_url=source_url)

    return fetcher


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="KB 공개 상품 snapshot 수집기")
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--all", action="store_true", help="catalog의 모든 상품 수집")
    target.add_argument("--product-key", help="수집할 product_key")
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--catalog-schema", type=Path, default=DEFAULT_CATALOG_SCHEMA)
    parser.add_argument("--manifest-root", type=Path, default=DEFAULT_MANIFEST_ROOT)
    parser.add_argument("--observation-root", type=Path, default=DEFAULT_OBSERVATION_ROOT)
    parser.add_argument(
        "--collection-attempt-root", type=Path, default=DEFAULT_COLLECTION_ATTEMPT_ROOT
    )
    parser.add_argument("--artifact-root", type=Path, default=DEFAULT_ARTIFACT_ROOT)
    parser.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA)
    parser.add_argument("--input-file", type=Path, help="수동 취득한 raw HTML")
    parser.add_argument("--acquisition-note", help="수동 취득 사유")
    parser.add_argument("--run-id", help="재실행 멱등성을 위한 run ID")
    parser.add_argument(
        "--attempt-sequence",
        type=int,
        default=1,
        help="선택한 각 상품 안에서 사용할 attempt sequence (기본값: 1)",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        products = load_catalog(args.catalog, args.catalog_schema)
        by_key = {product.product_key: product for product in products}
        if args.all:
            selected = products
        elif args.product_key not in by_key:
            raise CollectorError(
                f"catalog에 없는 product_key입니다: {args.product_key}",
                "UNKNOWN_PRODUCT_KEY",
            )
        else:
            selected = [by_key[args.product_key]]

        if args.input_file:
            if args.all:
                raise CollectorError(
                    "수동 입력은 product-key 한 건에만 사용할 수 있습니다.",
                    "INVALID_ARGUMENT",
                )
            if not args.acquisition_note:
                raise CollectorError(
                    "수동 입력에는 acquisition-note가 필요합니다.",
                    "ACQUISITION_NOTE_REQUIRED",
                )
            fetcher = _manual_fetcher(args.input_file, selected[0].source_url)
            acquisition_method = "MANUAL_DOWNLOAD"
        else:
            if args.acquisition_note:
                raise CollectorError(
                    "acquisition-note는 input-file과 함께 사용해야 합니다.",
                    "ACQUISITION_NOTE_NOT_ALLOWED",
                )
            fetcher = fetch_http
            acquisition_method = "HTTP_DOWNLOAD"

        run_id = args.run_id or _new_run_id()
        _validate_run_id(run_id)
        if args.attempt_sequence < 1:
            raise CollectorError(
                "attempt sequence는 1 이상이어야 합니다.",
                "INVALID_ATTEMPT_SEQUENCE",
            )
    except (CollectorError, OSError) as error:
        print(
            _audit_error_message(getattr(error, "code", "COLLECTION_FAILED")),
            file=sys.stderr,
        )
        return 2

    results = []
    failed = False
    for product in selected:
        attempted_at = datetime.now(UTC)
        try:
            result = collect_product(
                product=product,
                fetcher=fetcher,
                artifact_root=args.artifact_root,
                manifest_root=args.manifest_root,
                schema_path=args.schema,
                collected_at=attempted_at,
                acquisition_method=acquisition_method,
                acquisition_note=args.acquisition_note,
                observation_root=args.observation_root,
                collection_attempt_root=args.collection_attempt_root,
                run_id=run_id,
                attempt_sequence=args.attempt_sequence,
            )
            results.append(
                {
                    "product_key": product.product_key,
                    "status": "SUCCEEDED",
                    "snapshot_status": result.status,
                    "snapshot_hash": result.snapshot_hash,
                    "observation_id": result.observation_id,
                    "collection_attempt_id": result.collection_attempt_id,
                }
            )
        except (CollectorError, OSError) as error:
            failed = True
            results.append(
                {
                    "product_key": product.product_key,
                    "status": "FAILED",
                    "error_code": getattr(error, "code", "COLLECTION_FAILED"),
                    "error_message": _audit_error_message(
                        getattr(error, "code", "COLLECTION_FAILED")
                    ),
                }
            )

    print(json.dumps({"run_id": run_id, "results": results}, ensure_ascii=False))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
