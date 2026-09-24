import copy
import hashlib
import json
import os
import subprocess
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker, ValidationError


ROOT = Path(__file__).resolve().parents[2]
MANIFEST_ROOT = ROOT / "datasets/public/kb/manifests"
OBSERVATION_ROOT = ROOT / "datasets/public/kb/observations"
BASELINE_MANIFEST_DIR = MANIFEST_ROOT / "2026-09-21"
PRIVATE_ARTIFACT_ROOT = Path(
    os.environ.get("TRUSTAGENT_PRIVATE_ARTIFACT_ROOT", ROOT / ".private-artifacts")
)


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def validate_schema(instance: dict, schema_name: str) -> None:
    schema = load_json(ROOT / "contracts" / schema_name)
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(instance)


class BoundaryViolation(ValueError):
    pass


def validate_dataset_boundary(relative_path: Path, record: dict) -> None:
    path = relative_path.as_posix()
    if path.startswith("datasets/public/"):
        expected_class, expected_synthetic = "PUBLIC_KB", False
    elif path.startswith("datasets/synthetic/internal/"):
        expected_class, expected_synthetic = "SYNTHETIC_INTERNAL", True
    elif path.startswith("datasets/synthetic/work/"):
        expected_class, expected_synthetic = "SYNTHETIC_WORK", True
    elif path.startswith("datasets/derived/"):
        expected_class, expected_synthetic = "DERIVED", None
    else:
        raise BoundaryViolation(f"관리되지 않는 dataset 경로입니다: {path}")

    if record.get("dataset_class") != expected_class:
        raise BoundaryViolation(
            f"{path}: dataset_class는 {expected_class}이어야 합니다."
        )
    if expected_synthetic is not None and record.get("synthetic") is not expected_synthetic:
        raise BoundaryViolation(
            f"{path}: synthetic는 {expected_synthetic}이어야 합니다."
        )


class GitBaselineContractTest(unittest.TestCase):
    def test_git_baseline_commit_exists_and_contains_day_one_baseline(self) -> None:
        commit = subprocess.run(
            ["git", "rev-parse", "--verify", "HEAD^{commit}"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        self.assertRegex(commit, r"^[a-f0-9]{40}$")

        tracked = subprocess.run(
            ["git", "ls-tree", "-r", "--name-only", "HEAD"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
        self.assertIn("README.md", tracked)
        self.assertIn("docs/DAY_01_PLAN.md", tracked)
        self.assertIn("contracts/public-snapshot-manifest.schema.json", tracked)


class PublicSnapshotContractTest(unittest.TestCase):
    expected_markers = {
        "small-business-credit": "KB소상공인 신용대출",
        "boss-plus-overdraft": "KB사장님+ 마이너스통장",
        "kb-seller-loan": "KB셀러론",
    }

    def manifests(self) -> list[tuple[Path, dict]]:
        return [(path, load_json(path)) for path in sorted(MANIFEST_ROOT.rglob("*.json"))]

    def baseline_manifests(self) -> list[tuple[Path, dict]]:
        return [
            (path, load_json(path))
            for path in sorted(BASELINE_MANIFEST_DIR.glob("*.json"))
        ]

    def artifact_path(self, manifest: dict) -> Path:
        return PRIVATE_ARTIFACT_ROOT / manifest["snapshot_object_key"]

    def test_three_snapshot_manifests_are_registered(self) -> None:
        manifests = self.baseline_manifests()
        self.assertEqual(3, len(manifests))
        self.assertEqual(set(self.expected_markers), {item["product_key"] for _, item in manifests})

        for _, manifest in manifests:
            digest = manifest["snapshot_hash"].removeprefix("sha256:")
            self.assertEqual(
                f"public-kb/sha256/{digest}.html",
                manifest["snapshot_object_key"],
            )

    def test_manifests_pass_schema(self) -> None:
        for _, manifest in self.manifests():
            validate_schema(manifest, "public-snapshot-manifest.schema.json")

    def test_manual_acquisition_requires_a_note(self) -> None:
        observation = copy.deepcopy(
            load_json(next(OBSERVATION_ROOT.rglob("*.observation.json")))
        )
        observation["acquisition_method"] = "MANUAL_DOWNLOAD"
        observation.pop("acquisition_note", None)

        with self.assertRaises(ValidationError):
            validate_schema(observation, "public-observation.schema.json")

    def test_private_artifacts_match_manifest_hashes(self) -> None:
        manifests = self.manifests()
        missing = [
            self.artifact_path(manifest)
            for _, manifest in manifests
            if not self.artifact_path(manifest).is_file()
        ]
        if missing:
            if os.environ.get("TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS") == "1":
                self.fail(f"비공개 snapshot artifact가 없습니다: {missing}")
            self.skipTest("비공개 snapshot artifact가 제공되지 않았습니다.")

        for _, manifest in manifests:
            artifact = self.artifact_path(manifest)
            html = artifact.read_text(encoding="utf-8")
            self.assertIn(self.expected_markers[manifest["product_key"]], html)
            payload = artifact.read_bytes()
            actual_hash = "sha256:" + hashlib.sha256(payload).hexdigest()
            self.assertEqual(manifest["snapshot_hash"], actual_hash)
            self.assertEqual(manifest["byte_size"], len(payload))


class SyntheticFixtureContractTest(unittest.TestCase):
    notice_path = Path(
        "datasets/synthetic/internal/notices/seller-loan-checklist-v1.json"
    )
    company_path = Path("datasets/synthetic/work/companies/company-001.json")
    application_path = Path(
        "datasets/synthetic/work/applications/application-001.json"
    )

    def test_synthetic_fixtures_pass_their_schemas_and_references_resolve(self) -> None:
        notice = load_json(ROOT / self.notice_path)
        company = load_json(ROOT / self.company_path)
        application = load_json(ROOT / self.application_path)

        validate_schema(notice, "synthetic-internal-notice.schema.json")
        validate_schema(company, "synthetic-work-company.schema.json")
        validate_schema(application, "synthetic-work-application.schema.json")
        self.assertEqual(company["company_id"], application["company_id"])

        manifest_hashes = {
            load_json(path)["snapshot_hash"] for path in MANIFEST_ROOT.rglob("*.json")
        }
        self.assertIn(notice["references"][0]["snapshot_hash"], manifest_hashes)

    def test_all_dataset_json_files_obey_path_boundaries(self) -> None:
        for path in sorted((ROOT / "datasets").rglob("*.json")):
            relative_path = path.relative_to(ROOT)
            validate_dataset_boundary(relative_path, load_json(path))

    def test_public_record_in_synthetic_internal_path_is_rejected(self) -> None:
        notice = load_json(ROOT / self.notice_path)
        invalid = copy.deepcopy(notice)
        invalid["dataset_class"] = "PUBLIC_KB"
        invalid["synthetic"] = False

        with self.assertRaisesRegex(BoundaryViolation, "SYNTHETIC_INTERNAL"):
            validate_dataset_boundary(self.notice_path, invalid)

    def test_synthetic_work_record_in_public_path_is_rejected(self) -> None:
        company = load_json(ROOT / self.company_path)
        invalid_path = Path("datasets/public/kb/companies/company-001.json")

        with self.assertRaisesRegex(BoundaryViolation, "PUBLIC_KB"):
            validate_dataset_boundary(invalid_path, company)


if __name__ == "__main__":
    unittest.main()
