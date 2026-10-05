import hashlib
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[2]
FIXTURE_ROOT = ROOT / "datasets/synthetic/internal/approved-checklists"
NOTICE_ROOT = ROOT / "datasets/synthetic/internal/notices"
EXPECTED_PROPOSAL = ROOT / "contracts/fixtures/prepayment-fee-v2-proposal.expected.json"


def load(path: Path):
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def validate(record, schema_name):
    schema = load(ROOT / "contracts" / schema_name)
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(record)


def canonical_json(value):
    def reject_floating_point(item):
        if isinstance(item, float):
            raise ValueError("canonical JSON은 부동소수점을 허용하지 않습니다.")
        if isinstance(item, dict):
            for child in item.values():
                reject_floating_point(child)
        if isinstance(item, list):
            for child in item:
                reject_floating_point(child)

    reject_floating_point(value)
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256(value):
    return "sha256:" + hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def content(rule):
    return {
        "rule_key": rule["rule_key"],
        "instruction": rule["instruction"],
        "evidence_required": rule["evidence_required"],
        "structured_change": rule["structured_change"],
    }


class ChecklistProposalContractTest(unittest.TestCase):
    def test_fixture_checklist_is_marked_as_fixture_and_matches_schema(self):
        versions = [load(path) for path in sorted(FIXTURE_ROOT.glob("*.approved-checklist.json"))]
        schedules = [load(path) for path in sorted(FIXTURE_ROOT.glob("*.schedule.json"))]
        self.assertEqual(1, len(versions))
        self.assertEqual(1, len(schedules))
        for version in versions:
            validate(version, "synthetic-approved-checklist-fixture.schema.json")
            self.assertEqual("FIXTURE", version["origin"])
            self.assertIn("합성", version["disclaimer"])
            self.assertIn("실제 인간 승인 기록이 아닙니다", version["disclaimer"])
        for schedule in schedules:
            validate(schedule, "synthetic-approved-checklist-schedule-fixture.schema.json")
            self.assertEqual("FIXTURE", schedule["origin"])
            version_ids = {version["approved_checklist_version_id"] for version in versions}
            for entry in schedule["entries"]:
                self.assertIn(entry["approved_checklist_version_id"], version_ids)

    def test_fixture_items_mirror_the_v1_notice_rules_and_reference_their_rule_versions(self):
        version = load(FIXTURE_ROOT / "prepayment-fee-v1.approved-checklist.json")
        notice = load(NOTICE_ROOT / "prepayment-fee-v1.json")
        self.assertEqual(notice["notice_id"], version["notice_id"])
        self.assertEqual(notice["family_id"], version["family_id"])
        self.assertEqual([rule["rule_key"] for rule in notice["rules"]], [item["rule_key"] for item in version["items"]])
        for item, rule in zip(version["items"], notice["rules"]):
            self.assertEqual(content(rule), {key: item[key] for key in content(rule)})
            self.assertEqual("policy-rule:" + sha256(rule), item["source_rule_version_id"])

    def test_expected_proposal_matches_schema_and_recomputes_from_notices(self):
        expected = load(EXPECTED_PROPOSAL)
        validate(expected, "checklist-change-proposal.schema.json")
        v1 = {rule["rule_key"]: content(rule) for rule in load(NOTICE_ROOT / "prepayment-fee-v1.json")["rules"]}
        v2 = {rule["rule_key"]: content(rule) for rule in load(NOTICE_ROOT / "prepayment-fee-v2.json")["rules"]}

        self.assertEqual(sha256([v1[key] for key in sorted(v1)]), expected["before_hash"])
        self.assertEqual(sha256([v2[key] for key in sorted(v2)]), expected["after_hash"])

        identity_items = []
        for item in expected["items"]:
            key = item["rule_key"]
            self.assertEqual(v1.get(key), item["before_json"])
            self.assertEqual(v2.get(key), item["after_json"])
            if item["before_json"] is not None:
                self.assertEqual(sha256(item["before_json"]), item["before_hash"])
            if item["after_json"] is not None:
                self.assertEqual(sha256(item["after_json"]), item["after_hash"])
            identity_items.append({"rule_key": key, "change_type": item["change_type"], "before": item["before_json"], "after": item["after_json"]})
        identity = {
            "base_checklist_version_id": expected["base_checklist_version_id"],
            "target_notice_id": expected["target_notice_id"],
            "generator_version": expected["generator_version"],
            "items": identity_items,
        }
        self.assertEqual("checklist-proposal:" + sha256(identity), expected["proposal_id"])

        self.assertEqual(["CHECK_CUSTOMER_CONTRACT_DATE", "CHECK_PREPAYMENT_FEE_RATE"], [item["rule_key"] for item in expected["items"]])
        self.assertEqual(["ADD", "MODIFY"], [item["change_type"] for item in expected["items"]])
        modify = expected["items"][1]
        self.assertEqual("1.2", modify["before_json"]["structured_change"]["after_value"])
        self.assertEqual("0.8", modify["after_json"]["structured_change"]["after_value"])
        self.assertIsInstance(modify["after_json"]["structured_change"]["after_value"], str)
        self.assertNotIn("CHECK_NOTICE_SOURCE", [item["rule_key"] for item in expected["items"]])


if __name__ == "__main__":
    unittest.main()
