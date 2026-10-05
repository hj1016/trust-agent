import copy
import json
import unittest
from datetime import date, datetime
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker, ValidationError


ROOT = Path(__file__).resolve().parents[2]
NOTICE_ROOT = ROOT / "datasets/synthetic/internal/notices"
RECEIPT_ROOT = ROOT / "datasets/synthetic/internal/receipts"
EXTRACTION_ROOT = ROOT / "datasets/derived/synthetic-internal/policy-extraction-attempts"


def load(path: Path):
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def validate(record, schema_name):
    schema = load(ROOT / "contracts" / schema_name)
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(record)


class DayFiveContractTest(unittest.TestCase):
    def test_notice_inventory_and_supersession_are_valid(self):
        notices = [load(path) for path in sorted(NOTICE_ROOT.glob("*.json"))]
        self.assertEqual(4, len(notices))
        for notice in notices:
            validate(notice, "synthetic-internal-notice.schema.json")
            self.assertIn("합성", notice["disclaimer"])
            if notice["effective_to"] is not None:
                self.assertLess(date.fromisoformat(notice["effective_from"]), date.fromisoformat(notice["effective_to"]))

        by_id = {notice["notice_id"]: notice for notice in notices}
        roots = [notice for notice in notices if notice["supersedes_notice_id"] is None]
        self.assertEqual(2, len(roots))
        self.assertEqual(len({notice["family_id"] for notice in notices}), len(roots))
        for notice in notices:
            predecessor = notice["supersedes_notice_id"]
            if predecessor is not None:
                self.assertIn(predecessor, by_id)
                self.assertEqual(by_id[predecessor]["family_id"], notice["family_id"])
                self.assertLess(by_id[predecessor]["version"], notice["version"])

    def test_receipts_and_extractions_resolve_and_follow_time(self):
        notices = {record["notice_id"] for record in map(load, NOTICE_ROOT.glob("*.json"))}
        receipts = [load(path) for path in sorted(RECEIPT_ROOT.glob("*.json"))]
        extractions = [load(path) for path in sorted(EXTRACTION_ROOT.glob("*.json"))]
        self.assertEqual(4, len(receipts))
        self.assertEqual(4, len(extractions))
        by_receipt = {}
        for receipt in receipts:
            validate(receipt, "synthetic-internal-notice-receipt.schema.json")
            self.assertIn(receipt["notice_id"], notices)
            by_receipt[receipt["receipt_id"]] = receipt
        for extraction in extractions:
            validate(extraction, "synthetic-internal-policy-extraction.schema.json")
            receipt = by_receipt[extraction["receipt_id"]]
            self.assertEqual(receipt["notice_id"], extraction["notice_id"])
            self.assertGreaterEqual(
                datetime.fromisoformat(extraction["attempted_at"].replace("Z", "+00:00")),
                datetime.fromisoformat(receipt["received_at"].replace("Z", "+00:00")),
            )

    def test_public_cross_check_requires_semantic_identity(self):
        notice = load(NOTICE_ROOT / "seller-loan-checklist-v1.json")
        invalid = copy.deepcopy(notice)
        del invalid["rules"][0]["public_cross_check"]["subject_type"]
        with self.assertRaises(ValidationError):
            validate(invalid, "synthetic-internal-notice.schema.json")

    def test_final_proposal_prepayment_fee_change_is_structured(self):
        notice = load(NOTICE_ROOT / "prepayment-fee-v2.json")
        change = notice["rules"][0]["structured_change"]
        self.assertEqual("prepayment_fee_rate_percent", change["field_key"])
        self.assertEqual("1.2", change["before_value"])
        self.assertEqual("0.8", change["after_value"])
        self.assertEqual("PERCENT", change["unit"])
        self.assertEqual(notice["effective_from"], change["effective_on"])

    def test_invalid_effective_interval_is_rejected_by_invariant(self):
        notice = load(NOTICE_ROOT / "seller-loan-checklist-v1.json")
        notice["effective_to"] = notice["effective_from"]
        self.assertFalse(date.fromisoformat(notice["effective_from"]) < date.fromisoformat(notice["effective_to"]))


if __name__ == "__main__":
    unittest.main()
