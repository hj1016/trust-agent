import hashlib
import json
import tempfile
import unittest
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

from scripts import extract_public_kb_product_facts as extractor


def observation(product_key: str, payload: bytes) -> dict:
    digest = hashlib.sha256(payload).hexdigest()
    return {
        "product_key": product_key,
        "source_url": "https://obank.kbstar.com/quics?page=test",
        "snapshot_hash": f"sha256:{digest}",
    }


def seller_html(corporate_limit: int = 20, sale_status: str = "- 판매중", chrome: str = "") -> bytes:
    return f"""<!doctype html><html><body>
{chrome}<input name="상세웹상품명" value="KB셀러론">
<span class="info_tit">적용금리</span><span class="info_txt">연 3.85% (쿠팡셀러 기준)</span>
<p class="tit3">대출대상</p><p class="p-text">제휴마켓 입점 사업자</p>
<p class="tit3">대출한도</p><p class="p-text">개인사업자: Min(정산금, 5억원) 법인사업자: Min(정산금, {corporate_limit}억원) 개인사업자: 5억원 법인사업자: {corporate_limit}억원</p>
<p class="tit3">원리금상환 방법</p><p class="p-text">일시상환방식</p>
<p class="tit3">판매종료 여부</p><p class="p-text">{sale_status}</p>
</body></html>""".encode("utf-8")


def small_business_html(rate_date: str = "2026.09.21", chrome: str = "") -> bytes:
    return f"""<!doctype html><html><body>
{chrome}<input name="상세웹상품명" value="KB소상공인 신용대출">
<span class="txt">최저 연 3.72% ~ 최고 연 5.73%</span>
<span>({rate_date}, 신용등급 1등급 기준)</span>
<p class="tit3">가입대상</p><p class="p-text">개인사업자 고객</p>
<p class="tit3">대출한도</p><p class="p-text">최대 2억원</p>
<p class="tit3">대출기간과 상환방법</p><p class="p-text">만기일시상환</p>
<p class="tit3">상품판매여부</p><p class="p-text">판매중</p>
</body></html>""".encode("utf-8")


def facts_by_key(parsed: extractor.ParsedProduct) -> dict[str, dict]:
    return {fact["fact_key"]: fact for fact in parsed.facts}


def write_extraction_fixture(root: Path, payload: bytes) -> Path:
    digest = hashlib.sha256(payload).hexdigest()
    artifact = root / ".private-artifacts" / f"public-kb/sha256/{digest}.html"
    artifact.parent.mkdir(parents=True)
    artifact.write_bytes(payload)
    manifest_path = (
        root / "datasets/public/kb/manifests/2026-09-23/kb-seller-loan.manifest.json"
    )
    manifest_path.parent.mkdir(parents=True)
    manifest = {
        "dataset_class": "PUBLIC_KB",
        "product_key": "kb-seller-loan",
        "source_url": "https://zloan.kbstar.com/quics?page=C108424",
        "snapshot_storage": "LOCAL_PRIVATE",
        "snapshot_object_key": f"public-kb/sha256/{digest}.html",
        "content_type": "text/html",
        "byte_size": len(payload),
        "snapshot_hash": f"sha256:{digest}",
        "synthetic": False,
    }
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    observation_path = (
        root / "datasets/public/kb/observations/2026-09-23/test.observation.json"
    )
    observation_path.parent.mkdir(parents=True)
    observation_value = {
        "dataset_class": "PUBLIC_KB",
        "synthetic": False,
        "observation_id": "obs:kb-seller-loan:11111111111111111111111111111111",
        "collection_attempt_id": "collect:kb-seller-loan:22222222222222222222222222222222",
        "collection_run_id": "run:33333333333333333333333333333333",
        "product_key": "kb-seller-loan",
        "source_url": "https://zloan.kbstar.com/quics?page=C108424",
        "final_url": "https://zloan.kbstar.com/quics?page=C108424",
        "observed_at": "2026-09-23T00:00:00Z",
        "acquisition_method": "HTTP_DOWNLOAD",
        "snapshot_manifest_path": manifest_path.relative_to(root).as_posix(),
        "snapshot_hash": f"sha256:{digest}",
    }
    observation_path.write_text(json.dumps(observation_value), encoding="utf-8")
    return observation_path


class PublicProductFactsTest(unittest.TestCase):
    def test_seller_loan_limits_and_quote_are_separated(self) -> None:
        payload = seller_html()
        parsed = extractor.parse_product(payload, observation("kb-seller-loan", payload))
        facts = facts_by_key(parsed)
        self.assertEqual(500_000_000, facts["max_limit_individual_krw"]["value"])
        self.assertEqual(2_000_000_000, facts["max_limit_corporate_krw"]["value"])
        self.assertNotIn("advertised_rate_text", facts)
        self.assertEqual("연 3.85% (쿠팡셀러 기준)", parsed.advertised_rate_text)
        self.assertIsNone(parsed.advertised_rate_reference_date)

    def test_rate_reference_date_does_not_change_terms_hash(self) -> None:
        first_payload = small_business_html("2026.09.21")
        second_payload = small_business_html("2026.09.22")
        first = extractor.parse_product(
            first_payload, observation("small-business-credit", first_payload)
        )
        second = extractor.parse_product(
            second_payload, observation("small-business-credit", second_payload)
        )
        self.assertEqual(
            hashlib.sha256(extractor._canonical_terms(first.facts)).hexdigest(),
            hashlib.sha256(extractor._canonical_terms(second.facts)).hexdigest(),
        )
        self.assertNotEqual(
            first.advertised_rate_reference_date,
            second.advertised_rate_reference_date,
        )

    def test_individual_limit_preserves_composite_evidence(self) -> None:
        payload = small_business_html()
        parsed = extractor.parse_product(
            payload, observation("small-business-credit", payload)
        )
        evidence = next(
            item
            for item in parsed.fact_evidence
            if item["fact_id"].endswith("max_limit_individual_krw")
        )
        self.assertEqual(["가입대상", "대출한도"], [item["label"] for item in evidence["locators"]])

    def test_chrome_only_change_keeps_terms_hash(self) -> None:
        first_payload = seller_html(chrome="<nav>old</nav>")
        second_payload = seller_html(chrome="<nav>new</nav>")
        first = extractor.parse_product(
            first_payload, observation("kb-seller-loan", first_payload)
        )
        second = extractor.parse_product(
            second_payload, observation("kb-seller-loan", second_payload)
        )
        self.assertEqual(
            extractor._canonical_terms(first.facts),
            extractor._canonical_terms(second.facts),
        )

    def test_sale_discontinued_is_a_known_terms_state(self) -> None:
        payload = seller_html(sale_status="판매종료")
        parsed = extractor.parse_product(payload, observation("kb-seller-loan", payload))
        self.assertEqual("DISCONTINUED", facts_by_key(parsed)["sale_status"]["value"])

    def test_unknown_sale_status_fails_closed(self) -> None:
        payload = seller_html(sale_status="신규가입중단")
        with self.assertRaisesRegex(extractor.FactExtractionError, "지원하지 않는 판매 상태"):
            extractor.parse_product(payload, observation("kb-seller-loan", payload))

    def test_two_hundred_million_does_not_become_two_billion(self) -> None:
        payload = seller_html(corporate_limit=2)
        parsed = extractor.parse_product(payload, observation("kb-seller-loan", payload))
        value = facts_by_key(parsed)["max_limit_corporate_krw"]["value"]
        self.assertEqual(200_000_000, value)
        self.assertNotEqual(2_000_000_000, value)

    def test_fractional_and_whole_seconds_sort_by_instant(self) -> None:
        values = [
            "2026-09-22T14:00:36.992221Z",
            "2026-09-22T14:00:36Z",
        ]
        ordered = sorted(values, key=extractor._parse_instant)
        self.assertEqual(
            ["2026-09-22T14:00:36Z", "2026-09-22T14:00:36.992221Z"],
            ordered,
        )

    def test_offset_timestamp_is_normalized_to_utc_z(self) -> None:
        value = datetime(2026, 9, 23, 9, 0, tzinfo=timezone(timedelta(hours=9)))
        self.assertEqual("2026-09-23T00:00:00Z", extractor._utc_text(value))

    def test_unknown_sale_status_persists_failed_extraction_attempt(self) -> None:
        payload = seller_html(sale_status="신규가입중단")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            observation_path = write_extraction_fixture(root, payload)
            attempt_root = root / "datasets/derived/public-kb/extraction-attempts"

            with self.assertRaises(extractor.FactExtractionError):
                extractor.extract_observation(
                    observation_path,
                    artifact_root=root / ".private-artifacts",
                    terms_root=root / "datasets/public/kb/product-terms-versions",
                    evidence_root=root / "datasets/public/kb/version-evidence",
                    quote_root=root / "datasets/public/kb/rate-quotes",
                    attempt_root=attempt_root,
                    repository_root=root,
                    extraction_run_id="run:44444444444444444444444444444444",
                    attempted_at=datetime(2026, 9, 23, tzinfo=UTC),
                )

            attempts = list(attempt_root.rglob("*.extraction-attempt.json"))
            self.assertEqual(1, len(attempts))
            attempt = json.loads(attempts[0].read_text())
            self.assertEqual("FAILED", attempt["status"])
            self.assertEqual("MEASURED", attempt["attempted_at_source"])
            self.assertEqual("UNKNOWN_SALE_STATUS", attempt["error_code"])
            self.assertNotIn(str(root), attempt["error_message"])
            self.assertEqual([], list((root / "datasets/public/kb/product-terms-versions").rglob("*.json")))

    def test_parser_retry_preserves_failed_and_successful_attempts(self) -> None:
        payload = seller_html()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            observation_path = write_extraction_fixture(root, payload)
            roots = {
                "artifact_root": root / ".private-artifacts",
                "terms_root": root / "datasets/public/kb/product-terms-versions",
                "evidence_root": root / "datasets/public/kb/version-evidence",
                "quote_root": root / "datasets/public/kb/rate-quotes",
                "attempt_root": root / "datasets/derived/public-kb/extraction-attempts",
                "repository_root": root,
            }
            with mock.patch.object(
                extractor,
                "parse_product",
                side_effect=extractor.FactExtractionError(
                    "old parser failed", "PARSER_STRUCTURE_CHANGED"
                ),
            ):
                with self.assertRaises(extractor.FactExtractionError):
                    extractor.extract_observation(
                        observation_path,
                        extraction_run_id="run:44444444444444444444444444444444",
                        attempted_at=datetime(2026, 9, 23, tzinfo=UTC),
                        parser_version="public-kb-html-v2",
                        **roots,
                    )

            result = extractor.extract_observation(
                observation_path,
                extraction_run_id="run:55555555555555555555555555555555",
                attempted_at=datetime(2026, 9, 23, 1, tzinfo=UTC),
                parser_version="public-kb-html-v3",
                **roots,
            )
            attempts = [
                json.loads(path.read_text())
                for path in roots["attempt_root"].rglob("*.extraction-attempt.json")
            ]
            self.assertEqual({"FAILED", "SUCCEEDED"}, {item["status"] for item in attempts})
            self.assertEqual(
                {"MEASURED"}, {item["attempted_at_source"] for item in attempts}
            )
            self.assertEqual(
                {"public-kb-html-v2", "public-kb-html-v3"},
                {item["parser_version"] for item in attempts},
            )
            self.assertEqual("CREATED", result.status)

    def test_partial_output_storage_error_persists_failed_attempt(self) -> None:
        payload = seller_html()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            observation_path = write_extraction_fixture(root, payload)
            attempt_root = root / "datasets/derived/public-kb/extraction-attempts"
            original_write = extractor._write_immutable

            def fail_quote(path: Path, content: bytes) -> bool:
                if path.name.endswith(".rate-quote.json"):
                    raise OSError("private path must not be persisted")
                return original_write(path, content)

            with (
                mock.patch.object(extractor, "_write_immutable", side_effect=fail_quote),
                self.assertRaisesRegex(
                    extractor.FactExtractionError, "추출 결과 저장에 실패"
                ),
            ):
                extractor.extract_observation(
                    observation_path,
                    artifact_root=root / ".private-artifacts",
                    terms_root=root / "datasets/public/kb/product-terms-versions",
                    evidence_root=root / "datasets/public/kb/version-evidence",
                    quote_root=root / "datasets/public/kb/rate-quotes",
                    attempt_root=attempt_root,
                    repository_root=root,
                    extraction_run_id="run:66666666666666666666666666666666",
                    attempted_at=datetime(2026, 9, 23, tzinfo=UTC),
                )

            attempt_path = next(attempt_root.rglob("*.extraction-attempt.json"))
            attempt = json.loads(attempt_path.read_text())
            self.assertEqual("FAILED", attempt["status"])
            self.assertEqual("STORAGE_FAILED", attempt["error_code"])
            self.assertNotIn("private path", attempt["error_message"])


if __name__ == "__main__":
    unittest.main()
