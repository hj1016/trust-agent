import hashlib
import json
import unittest
from collections import Counter
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[2]
VERSION_ROOT = ROOT / "datasets/public/kb/product-versions"
VERSION_SCHEMA = ROOT / "contracts/public-product-version.schema.json"
FACT_SCHEMA = ROOT / "contracts/public-product-fact.schema.json"


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def canonical_fact_set(facts: list[dict]) -> bytes:
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


class PublicProductVersionContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.version_schema = load_json(VERSION_SCHEMA)
        cls.fact_schema = load_json(FACT_SCHEMA)
        cls.versions = [load_json(path) for path in sorted(VERSION_ROOT.rglob("*.json"))]

    def test_expected_product_versions_exist(self) -> None:
        counts = Counter(version["product_key"] for version in self.versions)

        self.assertEqual(
            {
                "small-business-credit": 2,
                "boss-plus-overdraft": 2,
                "kb-seller-loan": 1,
            },
            dict(counts),
        )

    def test_versions_and_facts_pass_schema(self) -> None:
        version_validator = Draft202012Validator(
            self.version_schema, format_checker=FormatChecker()
        )
        fact_validator = Draft202012Validator(
            self.fact_schema, format_checker=FormatChecker()
        )
        for version in self.versions:
            version_validator.validate(version)
            for fact in version["facts"]:
                fact_validator.validate(fact)

    def test_fact_set_hash_and_product_version_id_are_reproducible(self) -> None:
        for version in self.versions:
            actual_hash = "sha256:" + hashlib.sha256(
                canonical_fact_set(version["facts"])
            ).hexdigest()
            self.assertEqual(actual_hash, version["fact_set_hash"])
            self.assertEqual(
                f"pv:{version['product_key']}:{actual_hash}",
                version["product_version_id"],
            )

    def test_each_fact_preserves_valid_source_evidence(self) -> None:
        for version in self.versions:
            manifest_path = ROOT / version["source_manifest_path"]
            self.assertTrue(manifest_path.is_file())
            manifest = load_json(manifest_path)
            self.assertEqual(
                manifest["snapshot_hash"], version["source_snapshot_hash"]
            )
            self.assertEqual(manifest["source_url"], version["source_url"])

            fact_keys = [fact["fact_key"] for fact in version["facts"]]
            self.assertEqual(len(fact_keys), len(set(fact_keys)))
            for fact in version["facts"]:
                locator = fact["source_locator"]
                self.assertEqual(version["source_snapshot_hash"], locator["snapshot_hash"])
                self.assertEqual(version["source_url"], locator["source_url"])
                evidence_hash = "sha256:" + hashlib.sha256(
                    locator["evidence_text"].encode("utf-8")
                ).hexdigest()
                self.assertEqual(evidence_hash, locator["evidence_hash"])

    def test_seller_loan_corporate_limit_is_two_billion_krw(self) -> None:
        version = next(
            version
            for version in self.versions
            if version["product_key"] == "kb-seller-loan"
        )
        fact = next(
            fact
            for fact in version["facts"]
            if fact["fact_key"] == "max_limit_corporate_krw"
        )

        self.assertEqual(2_000_000_000, fact["value"])
        self.assertEqual("KRW", fact["unit"])
        self.assertIn("법인사업자", fact["source_locator"]["evidence_text"])
        self.assertIn("20억원", fact["source_locator"]["evidence_text"])

    def test_effective_dates_are_not_inferred_from_collection_time(self) -> None:
        for version in self.versions:
            self.assertIsNone(version["effective_from"])
            self.assertIsNone(version["effective_to"])


if __name__ == "__main__":
    unittest.main()
