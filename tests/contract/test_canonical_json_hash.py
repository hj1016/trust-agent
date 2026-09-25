import hashlib
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def canonical_json(value: object) -> str:
    def reject_floating_point(item: object) -> None:
        if isinstance(item, float):
            raise ValueError("FLOATING_POINT_NOT_ALLOWED")
        if isinstance(item, dict):
            for child in item.values():
                reject_floating_point(child)
        elif isinstance(item, list):
            for child in item:
                reject_floating_point(child)

    reject_floating_point(value)
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


class CanonicalJsonHashContractTest(unittest.TestCase):
    def test_python_matches_shared_canonical_json_hash_cases(self) -> None:
        fixture = json.loads(
            (ROOT / "contracts/fixtures/canonical-json-hash-cases.json").read_text(
                encoding="utf-8"
            )
        )

        for case in fixture["cases"]:
            with self.subTest(case=case["name"]):
                canonical = canonical_json(case["input"])
                digest = "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()
                self.assertEqual(case["expected_canonical_json"], canonical)
                self.assertEqual(case["expected_sha256"], digest)

        for case in fixture["rejected_cases"]:
            with self.subTest(case=case["name"]):
                with self.assertRaisesRegex(ValueError, case["reason"]):
                    canonical_json(case["input"])


if __name__ == "__main__":
    unittest.main()
