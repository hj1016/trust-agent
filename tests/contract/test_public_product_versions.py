import hashlib
import json
import os
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker, ValidationError

from scripts import extract_public_kb_product_facts as extractor
from scripts import migrate_public_kb_pipeline_v2 as migration


ROOT = Path(__file__).resolve().parents[2]
PUBLIC_ROOT = ROOT / "datasets/public/kb"
DERIVED_ROOT = ROOT / "datasets/derived/public-kb"
PRIVATE_ARTIFACT_ROOT = Path(
    os.environ.get("TRUSTAGENT_PRIVATE_ARTIFACT_ROOT", ROOT / ".private-artifacts")
)


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def canonical(value: dict) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


class PublicProductPipelineContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.terms = [
            load_json(path)
            for path in sorted((PUBLIC_ROOT / "product-terms-versions").rglob("*.json"))
        ]
        cls.observations = [
            load_json(path)
            for path in sorted((PUBLIC_ROOT / "observations").rglob("*.observation.json"))
        ]
        cls.evidence = [
            load_json(path)
            for path in sorted((PUBLIC_ROOT / "version-evidence").rglob("*.version-evidence.json"))
        ]
        cls.quotes = [
            load_json(path)
            for path in sorted((PUBLIC_ROOT / "rate-quotes").rglob("*.rate-quote.json"))
        ]
        cls.collection_attempts = [
            load_json(path)
            for path in sorted((DERIVED_ROOT / "collection-attempts").rglob("*.json"))
        ]
        cls.extraction_attempts = [
            load_json(path)
            for path in sorted((DERIVED_ROOT / "extraction-attempts").rglob("*.json"))
        ]
        cls.changes = [
            load_json(path)
            for path in sorted((DERIVED_ROOT / "change-detection-results").rglob("*.json"))
        ]

    def validate_all(self, values: list[dict], schema_name: str) -> None:
        schema = load_json(ROOT / "contracts" / schema_name)
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        for value in values:
            validator.validate(value)

    def test_committed_baseline_inventory(self) -> None:
        self.assertEqual(
            {
                "small-business-credit": 1,
                "boss-plus-overdraft": 1,
                "kb-seller-loan": 1,
            },
            dict(Counter(item["product_key"] for item in self.terms)),
        )
        self.assertEqual(5, len(self.observations))
        self.assertEqual(5, len(self.evidence))
        self.assertEqual(5, len(self.quotes))
        self.assertEqual(5, len(self.collection_attempts))
        self.assertEqual(5, len(self.extraction_attempts))
        self.assertEqual(5, len(self.changes))

    def test_all_new_records_pass_their_schemas(self) -> None:
        self.validate_all(self.terms, "public-product-terms-version.schema.json")
        term_schema = load_json(ROOT / "contracts/public-product-term-fact.schema.json")
        term_validator = Draft202012Validator(term_schema, format_checker=FormatChecker())
        for version in self.terms:
            for fact in version["facts"]:
                term_validator.validate(fact)
        self.validate_all(self.observations, "public-observation.schema.json")
        self.validate_all(self.evidence, "public-version-evidence.schema.json")
        self.validate_all(self.quotes, "public-rate-quote.schema.json")
        self.validate_all(
            self.collection_attempts, "public-collection-attempt.schema.json"
        )
        self.validate_all(
            self.extraction_attempts, "public-extraction-attempt.schema.json"
        )
        self.validate_all(
            self.changes, "public-change-detection-result.schema.json"
        )

    def test_terms_hashes_are_unique_and_reproducible(self) -> None:
        seen = set()
        for version in self.terms:
            actual = "sha256:" + hashlib.sha256(
                extractor._canonical_terms(version["facts"])
            ).hexdigest()
            self.assertEqual(actual, version["terms_hash"])
            self.assertEqual(
                f"ptv:{version['product_key']}:{actual}",
                version["product_terms_version_id"],
            )
            key = (version["product_key"], version["terms_hash"])
            self.assertNotIn(key, seen)
            seen.add(key)
            fact_keys = {fact["fact_key"] for fact in version["facts"]}
            self.assertNotIn("advertised_rate_text", fact_keys)
            self.assertNotIn("advertised_rate_reference_date", fact_keys)

    def test_observation_relationships_are_complete(self) -> None:
        observations = {item["observation_id"]: item for item in self.observations}
        terms = {item["product_terms_version_id"] for item in self.terms}
        self.assertEqual(set(observations), {item["observation_id"] for item in self.evidence})
        self.assertEqual(set(observations), {item["observation_id"] for item in self.quotes})
        self.assertEqual(
            set(observations),
            {item["observation_id"] for item in self.collection_attempts},
        )
        self.assertEqual(
            set(observations),
            {item["observation_id"] for item in self.extraction_attempts},
        )
        for evidence in self.evidence:
            self.assertIn(evidence["product_terms_version_id"], terms)
            observation = observations[evidence["observation_id"]]
            self.assertEqual(observation["snapshot_hash"], evidence["snapshot_hash"])

    def test_seller_loan_corporate_limit_is_two_billion_krw(self) -> None:
        version = next(
            item for item in self.terms if item["product_key"] == "kb-seller-loan"
        )
        fact = next(
            item for item in version["facts"] if item["fact_key"] == "max_limit_corporate_krw"
        )
        self.assertEqual(2_000_000_000, fact["value"])
        evidence = next(
            item for item in self.evidence if item["product_key"] == "kb-seller-loan"
        )
        fact_evidence = next(
            item for item in evidence["fact_evidence"] if item["fact_id"] == fact["fact_id"]
        )
        self.assertTrue(
            any("20억원" in locator["evidence_text"] for locator in fact_evidence["locators"])
        )

    def test_rate_date_refresh_is_not_a_terms_change(self) -> None:
        classifications = Counter(
            classification
            for result in self.changes
            for classification in result["classifications"]
        )
        self.assertEqual(3, classifications["BASELINE_ESTABLISHED"])
        self.assertEqual(2, classifications["QUOTE_REFRESHED"])
        self.assertEqual(0, classifications["PRODUCT_TERMS_CHANGED"])

    def test_date_time_format_is_actually_enforced(self) -> None:
        schema = load_json(ROOT / "contracts/public-observation.schema.json")
        invalid = dict(self.observations[0])
        invalid["observed_at"] = "not-a-timestamp"
        with self.assertRaises(ValidationError):
            Draft202012Validator(schema, format_checker=FormatChecker()).validate(invalid)
        invalid["observed_at"] = "2026-09-23T00:00:00"
        with self.assertRaises(ValidationError):
            Draft202012Validator(schema, format_checker=FormatChecker()).validate(invalid)

    def test_private_artifacts_reextract_to_committed_golden_files(self) -> None:
        missing = []
        for observation in self.observations:
            snapshot = load_json(ROOT / observation["snapshot_manifest_path"])
            artifact = PRIVATE_ARTIFACT_ROOT / snapshot["snapshot_object_key"]
            if not artifact.is_file():
                missing.append(artifact)
        if missing:
            if os.environ.get("TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS") == "1":
                self.fail(f"비공개 snapshot artifact가 없습니다: {missing}")
            self.skipTest("비공개 snapshot artifact가 제공되지 않았습니다.")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sequences: dict[str, int] = {}
            for observation_path in sorted(
                (PUBLIC_ROOT / "observations").rglob("*.observation.json"),
                key=lambda path: (
                    extractor._parse_instant(load_json(path)["observed_at"]),
                    load_json(path)["observation_id"],
                ),
            ):
                observation = load_json(observation_path)
                key = observation["product_key"]
                sequences[key] = sequences.get(key, 0) + 1
                extractor.extract_observation(
                    observation_path,
                    artifact_root=PRIVATE_ARTIFACT_ROOT,
                    terms_root=root / "datasets/public/kb/product-terms-versions",
                    evidence_root=root / "datasets/public/kb/version-evidence",
                    quote_root=root / "datasets/public/kb/rate-quotes",
                    attempt_root=root / "datasets/derived/public-kb/extraction-attempts",
                    repository_root=ROOT,
                    extraction_run_id=migration.EXTRACTION_RUN_ID,
                    attempt_sequence=sequences[key],
                    attempted_at=extractor._parse_instant(observation["observed_at"]),
                )
            extractor.rebuild_change_detection(
                observation_root=PUBLIC_ROOT / "observations",
                evidence_root=root / "datasets/public/kb/version-evidence",
                quote_root=root / "datasets/public/kb/rate-quotes",
                change_root=root / "datasets/derived/public-kb/change-detection-results",
            )
            comparisons = [
                (PUBLIC_ROOT / "product-terms-versions", root / "datasets/public/kb/product-terms-versions"),
                (PUBLIC_ROOT / "version-evidence", root / "datasets/public/kb/version-evidence"),
                (PUBLIC_ROOT / "rate-quotes", root / "datasets/public/kb/rate-quotes"),
                (DERIVED_ROOT / "extraction-attempts", root / "datasets/derived/public-kb/extraction-attempts"),
                (DERIVED_ROOT / "change-detection-results", root / "datasets/derived/public-kb/change-detection-results"),
            ]
            for expected_root, actual_root in comparisons:
                expected = {
                    path.relative_to(expected_root): path.read_bytes()
                    for path in expected_root.rglob("*.json")
                }
                actual = {
                    path.relative_to(actual_root): path.read_bytes()
                    for path in actual_root.rglob("*.json")
                }
                self.assertEqual(expected, actual)

    def test_private_migration_is_deterministic_with_fixed_run_ids(self) -> None:
        snapshot_by_path = {
            path.relative_to(ROOT).as_posix(): load_json(path)
            for path in (PUBLIC_ROOT / "manifests").rglob("*.manifest.json")
        }
        if any(
            not (PRIVATE_ARTIFACT_ROOT / value["snapshot_object_key"]).is_file()
            for value in snapshot_by_path.values()
        ):
            if os.environ.get("TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS") == "1":
                self.fail("deterministic migration에 필요한 private artifact가 없습니다.")
            self.skipTest("비공개 snapshot artifact가 제공되지 않았습니다.")

        attempts = {
            item["collection_attempt_id"]: item for item in self.collection_attempts
        }

        def prepare_and_migrate(root: Path) -> dict:
            for observation in self.observations:
                snapshot = snapshot_by_path[observation["snapshot_manifest_path"]]
                attempt = attempts[observation["collection_attempt_id"]]
                legacy = {
                    **snapshot,
                    "collected_at": observation["observed_at"],
                    "acquisition_method": observation["acquisition_method"],
                    "published_or_reviewed_at": None,
                    "effective_from": None,
                    "effective_to": None,
                    "parser_version": "raw-html-v1",
                }
                if observation.get("acquisition_note"):
                    legacy["acquisition_note"] = observation["acquisition_note"]
                self.assertEqual(attempt["attempted_at"], observation["observed_at"])
                path = root / observation["snapshot_manifest_path"]
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(
                    json.dumps(legacy, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8",
                )
            return migration.migrate(
                repository_root=root,
                manifest_root=root / "datasets/public/kb/manifests",
                artifact_root=PRIVATE_ARTIFACT_ROOT,
                observation_root=root / "datasets/public/kb/observations",
                collection_attempt_root=root / "datasets/derived/public-kb/collection-attempts",
                terms_root=root / "datasets/public/kb/product-terms-versions",
                evidence_root=root / "datasets/public/kb/version-evidence",
                quote_root=root / "datasets/public/kb/rate-quotes",
                extraction_attempt_root=root / "datasets/derived/public-kb/extraction-attempts",
                change_root=root / "datasets/derived/public-kb/change-detection-results",
                legacy_version_root=root / "datasets/public/kb/product-versions",
            )

        with tempfile.TemporaryDirectory() as first_dir, tempfile.TemporaryDirectory() as second_dir:
            first_root, second_root = Path(first_dir), Path(second_dir)
            first_report = prepare_and_migrate(first_root)
            second_report = prepare_and_migrate(second_root)
            self.assertEqual(canonical(first_report), canonical(second_report))
            first_files = {
                path.relative_to(first_root): path.read_bytes()
                for path in first_root.rglob("*.json")
            }
            second_files = {
                path.relative_to(second_root): path.read_bytes()
                for path in second_root.rglob("*.json")
            }
            self.assertEqual(first_files, second_files)
            committed_roots = [
                PUBLIC_ROOT / "manifests",
                PUBLIC_ROOT / "observations",
                PUBLIC_ROOT / "product-terms-versions",
                PUBLIC_ROOT / "version-evidence",
                PUBLIC_ROOT / "rate-quotes",
                DERIVED_ROOT / "collection-attempts",
                DERIVED_ROOT / "extraction-attempts",
                DERIVED_ROOT / "change-detection-results",
            ]
            committed_files = {
                path.relative_to(ROOT): path.read_bytes()
                for committed_root in committed_roots
                for path in committed_root.rglob("*.json")
            }
            self.assertEqual(committed_files, first_files)
            self.assertTrue(first_report["observations_are_lower_bound"])


if __name__ == "__main__":
    unittest.main()
