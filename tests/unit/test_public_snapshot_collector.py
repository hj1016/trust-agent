import json
import tempfile
import unittest
from datetime import UTC, datetime, timedelta
from pathlib import Path
from unittest import mock

from scripts import collect_public_kb_snapshots as collector


ROOT = Path(__file__).resolve().parents[2]
NOW = datetime(2026, 9, 22, 1, 2, 3, tzinfo=UTC)
RUN_ID = "run:11111111111111111111111111111111"
PRODUCT = collector.CatalogProduct(
    product_key="kb-seller-loan",
    display_name="KB 셀러론",
    source_url="https://zloan.kbstar.com/quics?page=C108424",
    source_marker="KB셀러론",
)


def response(payload: bytes) -> collector.FetchResponse:
    return collector.FetchResponse(
        payload=payload, content_type="text/html", final_url=PRODUCT.source_url
    )


class PublicSnapshotCollectorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary_directory.name)
        self.artifacts = self.repository / ".private-artifacts"
        self.manifests = self.repository / "datasets/public/kb/manifests"
        self.observations = self.repository / "datasets/public/kb/observations"
        self.attempts = self.repository / "datasets/derived/public-kb/collection-attempts"

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def collect(
        self,
        payload: bytes,
        *,
        sequence: int = 1,
        collected_at: datetime = NOW,
        fetcher=None,
        **kwargs,
    ) -> collector.CollectionResult:
        return collector.collect_product(
            product=PRODUCT,
            fetcher=fetcher or (lambda _: response(payload)),
            artifact_root=self.artifacts,
            manifest_root=self.manifests,
            schema_path=collector.DEFAULT_SCHEMA,
            collected_at=collected_at,
            observation_root=self.observations,
            collection_attempt_root=self.attempts,
            repository_root=self.repository,
            run_id=RUN_ID,
            attempt_sequence=sequence,
            **kwargs,
        )

    def test_same_snapshot_creates_one_snapshot_and_two_observations(self) -> None:
        payload = "<!doctype html><html>KB셀러론</html>".encode()
        first = self.collect(payload, sequence=1)
        second = self.collect(payload, sequence=2, collected_at=NOW + timedelta(days=1))
        self.assertEqual("CREATED", first.status)
        self.assertEqual("DEDUPLICATED", second.status)
        self.assertEqual(1, len(list(self.artifacts.rglob("*.html"))))
        self.assertEqual(1, len(list(self.manifests.rglob("*.manifest.json"))))
        self.assertEqual(2, len(list(self.observations.rglob("*.observation.json"))))
        self.assertEqual(2, len(list(self.attempts.rglob("*.collection-attempt.json"))))

    def test_a_b_a_preserves_three_observations_and_two_snapshots(self) -> None:
        first = "<!doctype html><html>KB셀러론 A</html>".encode()
        second = "<!doctype html><html>KB셀러론 B</html>".encode()
        self.collect(first, sequence=1)
        self.collect(second, sequence=2, collected_at=NOW + timedelta(days=1))
        self.collect(first, sequence=3, collected_at=NOW + timedelta(days=2))
        self.assertEqual(2, len(list(self.artifacts.rglob("*.html"))))
        self.assertEqual(2, len(list(self.manifests.rglob("*.manifest.json"))))
        self.assertEqual(3, len(list(self.observations.rglob("*.observation.json"))))

    def test_same_attempt_replay_does_not_fetch_again(self) -> None:
        payload = "<!doctype html><html>KB셀러론</html>".encode()
        first = self.collect(payload)
        fetcher = mock.Mock(side_effect=AssertionError("HTTP 요청이 재실행됐습니다."))
        replay = self.collect(
            payload,
            collected_at=NOW + timedelta(days=1),
            fetcher=fetcher,
        )
        self.assertEqual("REPLAYED", replay.status)
        self.assertEqual(first.observation_id, replay.observation_id)
        fetcher.assert_not_called()

    def test_failure_is_persisted_and_other_event_files_are_not_created(self) -> None:
        def fail(_: str) -> collector.FetchResponse:
            raise collector.CollectorError("network failed", "HTTP_FETCH_FAILED")

        with self.assertRaisesRegex(collector.CollectorError, "network failed"):
            self.collect(b"", fetcher=fail)
        attempts = list(self.attempts.rglob("*.collection-attempt.json"))
        self.assertEqual(1, len(attempts))
        value = json.loads(attempts[0].read_text())
        self.assertEqual("FAILED", value["status"])
        self.assertEqual("HTTP_FETCH_FAILED", value["error_code"])
        self.assertNotIn("network failed", value["error_message"])
        self.assertEqual([], list(self.observations.rglob("*.json")))

    def test_manual_collection_requires_note_and_http_rejects_note(self) -> None:
        payload = "<!doctype html><html>KB셀러론</html>".encode()
        with self.assertRaisesRegex(collector.CollectorError, "acquisition_note"):
            self.collect(payload, acquisition_method="MANUAL_DOWNLOAD")
        with self.assertRaisesRegex(collector.CollectorError, "사용할 수 없습니다"):
            self.collect(payload, acquisition_note="manual only")

    def test_non_html_and_missing_marker_fail_closed(self) -> None:
        with self.assertRaisesRegex(collector.CollectorError, "HTML 응답"):
            self.collect(
                b"{}",
                fetcher=lambda _: collector.FetchResponse(
                    b"{}", "application/json", PRODUCT.source_url
                ),
            )
        with self.assertRaisesRegex(collector.CollectorError, "상품 식별 문자열"):
            self.collect(b"<!doctype html><html>wrong</html>", sequence=2)

    def test_manifest_failure_does_not_delete_content_addressed_artifact(self) -> None:
        payload = "<!doctype html><html>KB셀러론</html>".encode()
        original_write = collector._write_immutable

        def fail_manifest(path: Path, content: bytes) -> bool:
            if path.name.endswith(".manifest.json"):
                raise collector.CollectorError("manifest failed", "MANIFEST_WRITE_FAILED")
            return original_write(path, content)

        with (
            mock.patch.object(collector, "_write_immutable", side_effect=fail_manifest),
            self.assertRaisesRegex(collector.CollectorError, "manifest failed"),
        ):
            self.collect(payload)

        artifacts = list(self.artifacts.rglob("*.html"))
        self.assertEqual(1, len(artifacts))
        self.assertEqual(payload, artifacts[0].read_bytes())
        self.assertEqual([], list(self.manifests.rglob("*.manifest.json")))

    def test_source_url_allowlist_rejects_spoofed_hosts_and_http(self) -> None:
        for url in (
            "http://zloan.kbstar.com/quics?page=C108424",
            "https://kbstar.com.example.com/quics?page=C108424",
            "https://kbstar.com@evil.example/quics?page=C108424",
        ):
            with self.subTest(url=url), self.assertRaises(collector.CollectorError):
                collector.validate_source_url(url)

    def test_catalog_and_redirect_validation(self) -> None:
        products = collector.load_catalog(
            ROOT / "datasets/public/kb/catalog/product-catalog.json"
        )
        self.assertEqual(3, len(products))
        handler = collector.AllowedRedirectHandler()
        with self.assertRaises(collector.CollectorError):
            handler.redirect_request(
                mock.Mock(), mock.Mock(), 302, "Found", {}, "https://evil.example"
            )


if __name__ == "__main__":
    unittest.main()
