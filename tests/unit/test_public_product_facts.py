import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts import extract_public_kb_product_facts as extractor


ROOT = Path(__file__).resolve().parents[2]


def seller_html(corporate_limit: int = 20, chrome: str = "") -> bytes:
    return f"""<!doctype html>
<html><body>
{chrome}
<input name="상세웹상품명" value="KB셀러론">
<span class="info_tit">적용금리</span><span class="info_txt">연 3.85% (쿠팡셀러 기준)</span>
<p class="tit3">대출대상</p><p class="p-text">제휴마켓 입점 사업자</p>
<p class="tit3">대출한도</p><p class="p-text">
개인사업자: Min(정산금, 5억원) 법인사업자: Min(정산금, {corporate_limit}억원)
개인사업자: 5억원 법인사업자: {corporate_limit}억원
</p>
<p class="tit3">원리금상환 방법</p><p class="p-text">일시상환방식</p>
<p class="tit3">판매종료 여부</p><p class="p-text">- 판매중</p>
</body></html>""".encode("utf-8")


def manifest_for(payload: bytes, collected_at: str = "2026-09-23T00:00:00Z") -> dict:
    digest = hashlib.sha256(payload).hexdigest()
    return {
        "dataset_class": "PUBLIC_KB",
        "product_key": "kb-seller-loan",
        "source_url": "https://zloan.kbstar.com/quics?page=C108424",
        "collected_at": collected_at,
        "acquisition_method": "HTTP_DOWNLOAD",
        "snapshot_storage": "LOCAL_PRIVATE",
        "snapshot_object_key": f"public-kb/sha256/{digest}.html",
        "content_type": "text/html",
        "byte_size": len(payload),
        "published_or_reviewed_at": None,
        "effective_from": None,
        "effective_to": None,
        "snapshot_hash": f"sha256:{digest}",
        "parser_version": "raw-html-v1",
        "synthetic": False,
    }


def facts_by_key(facts: list[dict]) -> dict[str, dict]:
    return {fact["fact_key"]: fact for fact in facts}


class PublicProductFactsTest(unittest.TestCase):
    def test_seller_loan_limits_are_normalized_to_krw(self) -> None:
        payload = seller_html()
        facts = facts_by_key(extractor.parse_facts(payload, manifest_for(payload)))

        self.assertEqual(500_000_000, facts["max_limit_individual_krw"]["value"])
        self.assertEqual(2_000_000_000, facts["max_limit_corporate_krw"]["value"])
        self.assertEqual("KRW", facts["max_limit_corporate_krw"]["unit"])

    def test_source_locator_preserves_snapshot_and_evidence_hash(self) -> None:
        payload = seller_html()
        manifest = manifest_for(payload)
        facts = facts_by_key(extractor.parse_facts(payload, manifest))
        locator = facts["max_limit_corporate_krw"]["source_locator"]

        self.assertEqual(manifest["snapshot_hash"], locator["snapshot_hash"])
        self.assertEqual(manifest["source_url"], locator["source_url"])
        expected_hash = "sha256:" + hashlib.sha256(
            locator["evidence_text"].encode("utf-8")
        ).hexdigest()
        self.assertEqual(expected_hash, locator["evidence_hash"])

    def test_chrome_only_change_keeps_same_fact_set_hash(self) -> None:
        first_payload = seller_html(chrome="<nav>old menu</nav>")
        second_payload = seller_html(chrome="<nav>new menu</nav>")
        first = extractor.parse_facts(first_payload, manifest_for(first_payload))
        second = extractor.parse_facts(second_payload, manifest_for(second_payload))

        first_hash = hashlib.sha256(extractor._canonical_fact_set(first)).hexdigest()
        second_hash = hashlib.sha256(extractor._canonical_fact_set(second)).hexdigest()

        self.assertNotEqual(
            manifest_for(first_payload)["snapshot_hash"],
            manifest_for(second_payload)["snapshot_hash"],
        )
        self.assertEqual(first_hash, second_hash)

    def test_duplicate_label_is_rejected_instead_of_guessing(self) -> None:
        payload = seller_html().replace(
            b'<p class="tit3">\xeb\x8c\x80\xec\xb6\x9c\xeb\x8c\x80\xec\x83\x81</p>',
            b'<p class="tit3">\xeb\x8c\x80\xec\xb6\x9c\xeb\x8c\x80\xec\x83\x81</p><p class="p-text">duplicate</p><p class="tit3">\xeb\x8c\x80\xec\xb6\x9c\xeb\x8c\x80\xec\x83\x81</p>',
        )

        with self.assertRaisesRegex(extractor.FactExtractionError, "정확히 한 건"):
            extractor.parse_facts(payload, manifest_for(payload))

    def test_two_hundred_million_source_does_not_become_two_billion(self) -> None:
        payload = seller_html(corporate_limit=2)
        facts = facts_by_key(extractor.parse_facts(payload, manifest_for(payload)))

        self.assertEqual(200_000_000, facts["max_limit_corporate_krw"]["value"])
        self.assertNotEqual(2_000_000_000, facts["max_limit_corporate_krw"]["value"])

    def test_extract_manifest_deduplicates_equal_fact_sets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            artifacts = repository / ".private-artifacts"
            manifests = repository / "datasets/public/kb/manifests/2026-09-23"
            outputs = repository / "datasets/public/kb/product-versions"

            results = []
            for index, chrome in enumerate(("<nav>one</nav>", "<nav>two</nav>"), start=1):
                payload = seller_html(chrome=chrome)
                manifest = manifest_for(payload, f"2026-09-23T00:00:0{index}Z")
                artifact = artifacts / manifest["snapshot_object_key"]
                artifact.parent.mkdir(parents=True, exist_ok=True)
                artifact.write_bytes(payload)
                manifest_path = manifests / f"snapshot-{index}.json"
                manifest_path.parent.mkdir(parents=True, exist_ok=True)
                manifest_path.write_text(
                    json.dumps(manifest, ensure_ascii=False), encoding="utf-8"
                )
                results.append(
                    extractor.extract_manifest(
                        manifest_path=manifest_path,
                        artifact_root=artifacts,
                        output_root=outputs,
                        repository_root=repository,
                    )
                )

            self.assertEqual("CREATED", results[0].status)
            self.assertEqual("DEDUPLICATED", results[1].status)
            self.assertEqual(results[0].fact_set_hash, results[1].fact_set_hash)
            self.assertEqual(1, len(list(outputs.rglob("*.json"))))

    def test_effective_dates_remain_null_when_source_does_not_provide_them(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            artifacts = repository / ".private-artifacts"
            manifest_path = (
                repository
                / "datasets/public/kb/manifests/2026-09-23/snapshot.json"
            )
            outputs = repository / "datasets/public/kb/product-versions"
            payload = seller_html()
            manifest = manifest_for(payload)
            artifact = artifacts / manifest["snapshot_object_key"]
            artifact.parent.mkdir(parents=True, exist_ok=True)
            artifact.write_bytes(payload)
            manifest_path.parent.mkdir(parents=True, exist_ok=True)
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            result = extractor.extract_manifest(
                manifest_path=manifest_path,
                artifact_root=artifacts,
                output_root=outputs,
                repository_root=repository,
            )
            version = json.loads(result.output_path.read_text(encoding="utf-8"))

            self.assertIsNone(version["effective_from"])
            self.assertIsNone(version["effective_to"])


if __name__ == "__main__":
    unittest.main()
