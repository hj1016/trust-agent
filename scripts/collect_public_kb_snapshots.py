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
DEFAULT_ARTIFACT_ROOT = ROOT / ".private-artifacts"
DEFAULT_SCHEMA = ROOT / "contracts/public-snapshot-manifest.schema.json"
DEFAULT_TIMEOUT_SECONDS = 15.0
DEFAULT_MAX_BYTES = 5 * 1024 * 1024
USER_AGENT = "TrustAgent-MVP/0.1 public-snapshot-collector"


class CollectorError(RuntimeError):
    """Raised when a snapshot cannot be collected without violating its contract."""


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
        raise CollectorError(f"JSON 파일을 읽을 수 없습니다: {path}") from error


def load_catalog(
    path: Path, schema_path: Path = DEFAULT_CATALOG_SCHEMA
) -> list[CatalogProduct]:
    catalog = _load_json(path)
    _validate_json_schema(catalog, schema_path, "catalog")

    products = catalog.get("products")
    if not isinstance(products, list) or not products:
        raise CollectorError("catalog에 products가 필요합니다.")

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
            raise CollectorError("catalog product 필드가 올바르지 않습니다.") from error
        if product.product_key in seen_keys:
            raise CollectorError(f"중복 product_key입니다: {product.product_key}")
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
        raise CollectorError(f"허용되지 않은 source URL입니다: {url}")


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
        raise CollectorError(f"공개 페이지 수집에 실패했습니다: {url}") from error

    if len(payload) > max_bytes:
        raise CollectorError(f"응답이 최대 크기 {max_bytes} bytes를 초과했습니다.")
    return FetchResponse(payload=payload, content_type=content_type, final_url=final_url)


def _validate_html(response: FetchResponse, product: CatalogProduct) -> None:
    if response.content_type != "text/html":
        raise CollectorError(f"HTML 응답이 아닙니다: {response.content_type}")
    if not response.payload:
        raise CollectorError("빈 HTML 응답입니다.")
    if len(response.payload) > DEFAULT_MAX_BYTES:
        raise CollectorError(f"응답이 최대 크기 {DEFAULT_MAX_BYTES} bytes를 초과했습니다.")
    try:
        html = response.payload.decode("utf-8")
    except UnicodeDecodeError as error:
        raise CollectorError("HTML 응답이 UTF-8이 아닙니다.") from error
    lowered = html.lower()
    if "<html" not in lowered and "<!doctype html" not in lowered:
        raise CollectorError("HTML 문서 식별자가 없습니다.")
    if product.source_marker not in html:
        raise CollectorError(
            f"상품 식별 문자열이 없습니다: {product.source_marker}"
        )


def _validate_json_schema(instance: dict, schema_path: Path, label: str) -> None:
    schema = _load_json(schema_path)
    try:
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(instance)
    except ValidationError as error:
        raise CollectorError(f"{label} schema 검증에 실패했습니다: {error.message}") from error


def _validate_manifest(manifest: dict, schema_path: Path) -> None:
    _validate_json_schema(manifest, schema_path, "manifest")


def _write_immutable(path: Path, payload: bytes) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.read_bytes() != payload:
            raise CollectorError(f"기존 immutable 파일의 내용이 다릅니다: {path}")
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
                raise CollectorError(f"기존 immutable 파일의 내용이 다릅니다: {path}")
            return False
        return True
    finally:
        temporary_path.unlink(missing_ok=True)


def _find_existing_manifest(
    manifest_root: Path, product_key: str, snapshot_hash: str
) -> tuple[Path, dict] | None:
    if not manifest_root.exists():
        return None
    for path in sorted(manifest_root.rglob("*.json")):
        manifest = _load_json(path)
        if (
            manifest.get("product_key") == product_key
            and manifest.get("snapshot_hash") == snapshot_hash
        ):
            return path, manifest
    return None


def collect_product(
    product: CatalogProduct,
    fetcher: Fetcher,
    artifact_root: Path,
    manifest_root: Path,
    schema_path: Path,
    collected_at: datetime,
    acquisition_method: str = "HTTP_DOWNLOAD",
    acquisition_note: str | None = None,
) -> CollectionResult:
    if collected_at.tzinfo is None or collected_at.utcoffset() is None:
        raise CollectorError("collected_at은 timezone-aware datetime이어야 합니다.")
    if acquisition_method == "MANUAL_DOWNLOAD" and not acquisition_note:
        raise CollectorError("수동 취득에는 acquisition_note가 필요합니다.")

    response = fetcher(product.source_url)
    validate_source_url(response.final_url)
    _validate_html(response, product)

    digest = hashlib.sha256(response.payload).hexdigest()
    snapshot_hash = f"sha256:{digest}"
    object_key = f"public-kb/sha256/{digest}.html"
    artifact_path = artifact_root / object_key
    existing = _find_existing_manifest(manifest_root, product.product_key, snapshot_hash)

    if existing:
        manifest_path, manifest = existing
        _validate_manifest(manifest, schema_path)
        expected = {
            "source_url": product.source_url,
            "snapshot_object_key": object_key,
            "byte_size": len(response.payload),
        }
        for field, expected_value in expected.items():
            if manifest.get(field) != expected_value:
                raise CollectorError(
                    f"기존 manifest의 {field}가 수집 결과와 다릅니다: {manifest_path}"
                )
        artifact_created = _write_immutable(artifact_path, response.payload)
        status = "ARTIFACT_RESTORED" if artifact_created else "DEDUPLICATED"
        return CollectionResult(
            product_key=product.product_key,
            status=status,
            snapshot_hash=snapshot_hash,
            artifact_path=artifact_path,
            manifest_path=manifest_path,
        )

    collected_utc = collected_at.astimezone(UTC)
    manifest = {
        "dataset_class": "PUBLIC_KB",
        "product_key": product.product_key,
        "source_url": product.source_url,
        "collected_at": collected_utc.isoformat().replace("+00:00", "Z"),
        "acquisition_method": acquisition_method,
        "snapshot_storage": "LOCAL_PRIVATE",
        "snapshot_object_key": object_key,
        "content_type": "text/html",
        "byte_size": len(response.payload),
        "published_or_reviewed_at": None,
        "effective_from": None,
        "effective_to": None,
        "snapshot_hash": snapshot_hash,
        "parser_version": "raw-html-v1",
        "synthetic": False,
    }
    if acquisition_note:
        manifest["acquisition_note"] = acquisition_note
    _validate_manifest(manifest, schema_path)

    manifest_directory = manifest_root / collected_utc.date().isoformat()
    manifest_path = manifest_directory / f"{product.product_key}--{digest}.manifest.json"
    manifest_payload = (
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")

    artifact_created = False
    try:
        artifact_created = _write_immutable(artifact_path, response.payload)
        _write_immutable(manifest_path, manifest_payload)
    except Exception:
        if artifact_created:
            artifact_path.unlink(missing_ok=True)
        raise

    return CollectionResult(
        product_key=product.product_key,
        status="CREATED",
        snapshot_hash=snapshot_hash,
        artifact_path=artifact_path,
        manifest_path=manifest_path,
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
    parser.add_argument("--artifact-root", type=Path, default=DEFAULT_ARTIFACT_ROOT)
    parser.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA)
    parser.add_argument("--input-file", type=Path, help="수동 취득한 raw HTML")
    parser.add_argument("--acquisition-note", help="수동 취득 사유")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        products = load_catalog(args.catalog, args.catalog_schema)
        by_key = {product.product_key: product for product in products}
        selected = products if args.all else [by_key[args.product_key]]

        if args.input_file:
            if args.all:
                raise CollectorError("수동 입력은 product-key 한 건에만 사용할 수 있습니다.")
            if not args.acquisition_note:
                raise CollectorError("수동 입력에는 acquisition-note가 필요합니다.")
            fetcher = _manual_fetcher(args.input_file, selected[0].source_url)
            acquisition_method = "MANUAL_DOWNLOAD"
        else:
            fetcher = fetch_http
            acquisition_method = "HTTP_DOWNLOAD"

        for product in selected:
            result = collect_product(
                product=product,
                fetcher=fetcher,
                artifact_root=args.artifact_root,
                manifest_root=args.manifest_root,
                schema_path=args.schema,
                collected_at=datetime.now(UTC),
                acquisition_method=acquisition_method,
                acquisition_note=args.acquisition_note,
            )
            print(
                json.dumps(
                    {
                        "product_key": result.product_key,
                        "status": result.status,
                        "snapshot_hash": result.snapshot_hash,
                        "artifact_path": str(result.artifact_path),
                        "manifest_path": str(result.manifest_path),
                    },
                    ensure_ascii=False,
                )
            )
    except (CollectorError, KeyError, OSError) as error:
        print(f"수집 실패: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
