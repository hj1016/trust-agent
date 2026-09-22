import json
import tempfile
import unittest
from datetime import UTC, datetime
from pathlib import Path
from unittest import mock

from scripts import collect_public_kb_snapshots as collector


ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "contracts/public-snapshot-manifest.schema.json"
NOW = datetime(2026, 9, 22, 1, 2, 3, tzinfo=UTC)
PRODUCT = collector.CatalogProduct(
    product_key="kb-seller-loan",
    display_name="KB 셀러론",
    source_url="https://zloan.kbstar.com/quics?page=C108424",
    source_marker="KB셀러론",
)


def response(payload: bytes) -> collector.FetchResponse:
    return collector.FetchResponse(
        payload=payload,
        content_type="text/html",
        final_url=PRODUCT.source_url,
    )


class PublicSnapshotCollectorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.artifacts = self.root / "artifacts"
        self.manifests = self.root / "manifests"

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def collect(self, payload: bytes, **kwargs) -> collector.CollectionResult:
        return collector.collect_product(
            product=PRODUCT,
            fetcher=lambda _: response(payload),
            artifact_root=self.artifacts,
            manifest_root=self.manifests,
            schema_path=SCHEMA,
            collected_at=NOW,
            **kwargs,
        )

    def test_first_collection_creates_artifact_and_valid_manifest(self) -> None:
        payload = b"<!doctype html><html>KB\xec\x85\x80\xeb\x9f\xac\xeb\xa1\xa0</html>"

        result = self.collect(payload)

        self.assertEqual("CREATED", result.status)
        self.assertEqual(payload, result.artifact_path.read_bytes())
        manifest = json.loads(result.manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(result.snapshot_hash, manifest["snapshot_hash"])
        self.assertEqual(len(payload), manifest["byte_size"])
        self.assertEqual("HTTP_DOWNLOAD", manifest["acquisition_method"])

    def test_repeated_content_is_deduplicated(self) -> None:
        payload = b"<!doctype html><html>KB\xec\x85\x80\xeb\x9f\xac\xeb\xa1\xa0</html>"
        first = self.collect(payload)

        second = self.collect(payload)

        self.assertEqual("DEDUPLICATED", second.status)
        self.assertEqual(first.manifest_path, second.manifest_path)
        self.assertEqual(1, len(list(self.artifacts.rglob("*.html"))))
        self.assertEqual(1, len(list(self.manifests.rglob("*.json"))))

    def test_changed_content_creates_new_artifact_and_manifest(self) -> None:
        first = b"<!doctype html><html>KB\xec\x85\x80\xeb\x9f\xac\xeb\xa1\xa0 v1</html>"
        second = b"<!doctype html><html>KB\xec\x85\x80\xeb\x9f\xac\xeb\xa1\xa0 v2</html>"
        self.collect(first)

        result = self.collect(second)

        self.assertEqual("CREATED", result.status)
        self.assertEqual(2, len(list(self.artifacts.rglob("*.html"))))
        self.assertEqual(2, len(list(self.manifests.rglob("*.json"))))

    def test_non_html_response_is_rejected_without_writes(self) -> None:
        def fetcher(_: str) -> collector.FetchResponse:
            return collector.FetchResponse(
                payload=b"{}", content_type="application/json", final_url=PRODUCT.source_url
            )

        with self.assertRaisesRegex(collector.CollectorError, "HTML 응답"):
            collector.collect_product(
                product=PRODUCT,
                fetcher=fetcher,
                artifact_root=self.artifacts,
                manifest_root=self.manifests,
                schema_path=SCHEMA,
                collected_at=NOW,
            )

        self.assertFalse(self.artifacts.exists())
        self.assertFalse(self.manifests.exists())

    def test_missing_product_marker_is_rejected_without_writes(self) -> None:
        with self.assertRaisesRegex(collector.CollectorError, "상품 식별 문자열"):
            self.collect(b"<!doctype html><html>wrong page</html>")

        self.assertFalse(self.artifacts.exists())
        self.assertFalse(self.manifests.exists())

    def test_manual_collection_requires_a_note(self) -> None:
        with self.assertRaisesRegex(collector.CollectorError, "acquisition_note"):
            self.collect(
                b"<!doctype html><html>KB\xec\x85\x80\xeb\x9f\xac\xeb\xa1\xa0</html>",
                acquisition_method="MANUAL_DOWNLOAD",
            )

    def test_manual_collection_records_its_note(self) -> None:
        result = self.collect(
            b"<!doctype html><html>KB\xec\x85\x80\xeb\x9f\xac\xeb\xa1\xa0</html>",
            acquisition_method="MANUAL_DOWNLOAD",
            acquisition_note="자동 수집 장애 중 공식 페이지에서 직접 저장",
        )

        manifest = json.loads(result.manifest_path.read_text(encoding="utf-8"))
        self.assertEqual("MANUAL_DOWNLOAD", manifest["acquisition_method"])
        self.assertEqual(
            "자동 수집 장애 중 공식 페이지에서 직접 저장",
            manifest["acquisition_note"],
        )

    def test_oversized_response_is_rejected_without_writes(self) -> None:
        payload = b"<html>KB\xec\x85\x80\xeb\x9f\xac\xeb\xa1\xa0" + b"x" * 100 + b"</html>"

        with mock.patch.object(collector, "DEFAULT_MAX_BYTES", 32):
            with self.assertRaisesRegex(collector.CollectorError, "최대 크기"):
                self.collect(payload)

        self.assertFalse(self.artifacts.exists())
        self.assertFalse(self.manifests.exists())

    def test_fetch_failure_preserves_existing_files(self) -> None:
        payload = b"<!doctype html><html>KB\xec\x85\x80\xeb\x9f\xac\xeb\xa1\xa0</html>"
        first = self.collect(payload)

        def fail(_: str) -> collector.FetchResponse:
            raise collector.CollectorError("network failed")

        with self.assertRaisesRegex(collector.CollectorError, "network failed"):
            collector.collect_product(
                product=PRODUCT,
                fetcher=fail,
                artifact_root=self.artifacts,
                manifest_root=self.manifests,
                schema_path=SCHEMA,
                collected_at=NOW,
            )

        self.assertTrue(first.artifact_path.is_file())
        self.assertTrue(first.manifest_path.is_file())
        self.assertEqual(1, len(list(self.artifacts.rglob("*.html"))))
        self.assertEqual(1, len(list(self.manifests.rglob("*.json"))))

    def test_manifest_write_failure_removes_new_artifact(self) -> None:
        payload = b"<!doctype html><html>KB\xec\x85\x80\xeb\x9f\xac\xeb\xa1\xa0</html>"
        original_write = collector._write_immutable
        calls = 0

        def fail_manifest(path: Path, content: bytes) -> bool:
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("manifest write failed")
            return original_write(path, content)

        with mock.patch.object(collector, "_write_immutable", side_effect=fail_manifest):
            with self.assertRaisesRegex(OSError, "manifest write failed"):
                self.collect(payload)

        self.assertEqual([], list(self.artifacts.rglob("*.html")))
        self.assertEqual([], list(self.manifests.rglob("*.json")))

    def test_source_url_allowlist_rejects_spoofed_hosts_and_http(self) -> None:
        invalid_urls = [
            "http://zloan.kbstar.com/quics?page=C108424",
            "https://kbstar.com.example.com/quics?page=C108424",
            "https://kbstar.com@evil.example/quics?page=C108424",
        ]
        for url in invalid_urls:
            with self.subTest(url=url):
                with self.assertRaises(collector.CollectorError):
                    collector.validate_source_url(url)

    def test_redirect_handler_rejects_disallowed_host_before_following(self) -> None:
        handler = collector.AllowedRedirectHandler()

        with self.assertRaises(collector.CollectorError):
            handler.redirect_request(
                req=mock.Mock(),
                fp=mock.Mock(),
                code=302,
                msg="Found",
                headers={},
                newurl="https://evil.example/snapshot",
            )

    def test_repository_catalog_passes_schema_and_url_validation(self) -> None:
        products = collector.load_catalog(
            ROOT / "datasets/public/kb/catalog/product-catalog.json"
        )

        self.assertEqual(3, len(products))
        self.assertEqual(3, len({product.product_key for product in products}))

    def test_existing_manifest_must_match_content_addressed_object_key(self) -> None:
        payload = b"<!doctype html><html>KB\xec\x85\x80\xeb\x9f\xac\xeb\xa1\xa0</html>"
        first = self.collect(payload)
        manifest = json.loads(first.manifest_path.read_text(encoding="utf-8"))
        manifest["snapshot_object_key"] = "public-kb/sha256/" + "0" * 64 + ".html"
        first.manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False), encoding="utf-8"
        )

        with self.assertRaisesRegex(collector.CollectorError, "snapshot_object_key"):
            self.collect(payload)


if __name__ == "__main__":
    unittest.main()
