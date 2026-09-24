from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
import uuid
from collections import defaultdict
from datetime import UTC
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import collect_public_kb_snapshots as collector
from scripts import extract_public_kb_product_facts as extractor


COLLECTION_RUN_ID = f"run:{uuid.uuid5(uuid.NAMESPACE_URL, 'trustagent:day3-hardening:collection:v1').hex}"
EXTRACTION_RUN_ID = f"run:{uuid.uuid5(uuid.NAMESPACE_URL, 'trustagent:day3-hardening:extraction:v1').hex}"


def _replace_json(path: Path, value: dict) -> None:
    payload = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as target:
            target.write(payload)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def _legacy_versions(version_root: Path, repository_root: Path) -> dict[str, str]:
    result = {}
    if not version_root.exists():
        return result
    for path in version_root.rglob("*.json"):
        value = collector._load_json(path)
        manifest_path = value.get("source_manifest_path")
        if manifest_path:
            result[manifest_path] = value["product_version_id"]
    return result


def _preflight_legacy_inputs(
    *,
    legacy: list[tuple],
    artifact_root: Path,
) -> None:
    """모든 private 입력을 검증한 뒤에만 일회성 migration 쓰기를 허용합니다."""
    for _, manifest_path, manifest in legacy:
        artifact = artifact_root / manifest["snapshot_object_key"]
        if not artifact.is_file():
            raise collector.CollectorError(
                f"migration artifact가 없습니다: {artifact}", "ARTIFACT_MISSING"
            )
        payload = artifact.read_bytes()
        actual_hash = "sha256:" + hashlib.sha256(payload).hexdigest()
        if actual_hash != manifest["snapshot_hash"]:
            raise collector.CollectorError(
                f"migration artifact hash가 manifest와 다릅니다: {manifest_path}",
                "ARTIFACT_HASH_MISMATCH",
            )
        if len(payload) != manifest["byte_size"]:
            raise collector.CollectorError(
                f"migration artifact byte size가 manifest와 다릅니다: {manifest_path}",
                "ARTIFACT_SIZE_MISMATCH",
            )
        extractor.parse_product(
            payload,
            {
                "product_key": manifest["product_key"],
                "source_url": manifest["source_url"],
                "snapshot_hash": manifest["snapshot_hash"],
            },
        )


def migrate(
    *,
    repository_root: Path,
    manifest_root: Path,
    artifact_root: Path,
    observation_root: Path,
    collection_attempt_root: Path,
    terms_root: Path,
    evidence_root: Path,
    quote_root: Path,
    extraction_attempt_root: Path,
    change_root: Path,
    legacy_version_root: Path,
) -> dict:
    manifest_paths = sorted(manifest_root.rglob("*.manifest.json"))
    legacy = []
    for path in manifest_paths:
        value = collector._load_json(path)
        if "collected_at" not in value or "acquisition_method" not in value:
            raise collector.CollectorError(
                f"legacy manifest가 아닙니다: {path}", "MIGRATION_INPUT_REQUIRED"
            )
        legacy.append((extractor._parse_instant(value["collected_at"]), path, value))
    legacy.sort(key=lambda item: (item[0], item[2]["product_key"], item[2]["snapshot_hash"]))
    _preflight_legacy_inputs(legacy=legacy, artifact_root=artifact_root)

    old_ids = _legacy_versions(legacy_version_root, repository_root)
    sequences: dict[str, int] = defaultdict(int)
    observation_paths: list[Path] = []
    migration_rows = []

    for observed_at, manifest_path, manifest in legacy:
        product_key = manifest["product_key"]
        sequences[product_key] += 1
        sequence = sequences[product_key]
        attempt_id, observation_id = collector._event_ids(
            COLLECTION_RUN_ID, product_key, sequence
        )
        observed_text = extractor._utc_text(observed_at)
        relative_manifest = manifest_path.relative_to(repository_root).as_posix()
        observation = {
            "dataset_class": "PUBLIC_KB",
            "synthetic": False,
            "observation_id": observation_id,
            "collection_attempt_id": attempt_id,
            "collection_run_id": COLLECTION_RUN_ID,
            "product_key": product_key,
            "source_url": manifest["source_url"],
            "final_url": None,
            "observed_at": observed_text,
            "acquisition_method": manifest["acquisition_method"],
            "snapshot_manifest_path": relative_manifest,
            "snapshot_hash": manifest["snapshot_hash"],
        }
        if manifest.get("acquisition_note"):
            observation["acquisition_note"] = manifest["acquisition_note"]
        collector._validate_json_schema(
            observation, collector.DEFAULT_OBSERVATION_SCHEMA, "observation"
        )
        observation_path = collector._event_path(
            observation_root, observed_at, product_key, observation_id, "observation"
        )
        collector._write_immutable(observation_path, collector._json_bytes(observation))

        attempt = collector._attempt_record(
            attempt_id=attempt_id,
            run_id=COLLECTION_RUN_ID,
            product_key=product_key,
            attempt_sequence=sequence,
            attempted_at=observed_text,
            status="SUCCEEDED",
            observation_id=observation_id,
            error_code=None,
            error_message=None,
        )
        collector._validate_json_schema(
            attempt, collector.DEFAULT_COLLECTION_ATTEMPT_SCHEMA, "collection attempt"
        )
        attempt_path = collector._event_path(
            collection_attempt_root,
            observed_at,
            product_key,
            attempt_id,
            "collection-attempt",
        )
        collector._write_immutable(attempt_path, collector._json_bytes(attempt))

        snapshot = {
            "dataset_class": "PUBLIC_KB",
            "product_key": product_key,
            "source_url": manifest["source_url"],
            "snapshot_storage": manifest["snapshot_storage"],
            "snapshot_object_key": manifest["snapshot_object_key"],
            "content_type": manifest["content_type"],
            "byte_size": manifest["byte_size"],
            "snapshot_hash": manifest["snapshot_hash"],
            "synthetic": False,
        }
        collector._validate_json_schema(snapshot, collector.DEFAULT_SCHEMA, "snapshot")
        _replace_json(manifest_path, snapshot)
        observation_paths.append(observation_path)
        migration_rows.append(
            {
                "product_key": product_key,
                "snapshot_hash": snapshot["snapshot_hash"],
                "manifest_path": relative_manifest,
                "observation_id": observation_id,
                "collection_attempt_id": attempt_id,
                "legacy_product_version_id": old_ids.get(relative_manifest),
            }
        )

    extraction_sequences: dict[str, int] = defaultdict(int)
    results = {}
    for observation_path in observation_paths:
        observation = collector._load_json(observation_path)
        product_key = observation["product_key"]
        extraction_sequences[product_key] += 1
        result = extractor.extract_observation(
            observation_path,
            artifact_root=artifact_root,
            terms_root=terms_root,
            evidence_root=evidence_root,
            quote_root=quote_root,
            attempt_root=extraction_attempt_root,
            repository_root=repository_root,
            extraction_run_id=EXTRACTION_RUN_ID,
            attempt_sequence=extraction_sequences[product_key],
            attempted_at=extractor._parse_instant(observation["observed_at"]),
        )
        results[observation["observation_id"]] = result

    changes = extractor.rebuild_change_detection(
        observation_root=observation_root,
        evidence_root=evidence_root,
        quote_root=quote_root,
        change_root=change_root,
    )
    for row in migration_rows:
        result = results[row["observation_id"]]
        row.update(
            {
                "product_terms_version_id": result.product_terms_version_id,
                "version_evidence_id": result.version_evidence_id,
                "rate_quote_id": result.rate_quote_id,
                "extraction_attempt_id": result.extraction_attempt_id,
            }
        )
    return {
        "collection_run_id": COLLECTION_RUN_ID,
        "extraction_run_id": EXTRACTION_RUN_ID,
        "observations_are_lower_bound": True,
        "migrations": migration_rows,
        "change_detection_results_created": len(changes),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Day 3 공개 상품 pipeline v2 migration")
    parser.add_argument("--repository-root", type=Path, default=ROOT)
    parser.add_argument("--artifact-root", type=Path, default=ROOT / ".private-artifacts")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.repository_root
    report = migrate(
        repository_root=root,
        manifest_root=root / "datasets/public/kb/manifests",
        artifact_root=args.artifact_root,
        observation_root=root / "datasets/public/kb/observations",
        collection_attempt_root=root / "datasets/derived/public-kb/collection-attempts",
        terms_root=root / "datasets/public/kb/product-terms-versions",
        evidence_root=root / "datasets/public/kb/version-evidence",
        quote_root=root / "datasets/public/kb/rate-quotes",
        extraction_attempt_root=root / "datasets/derived/public-kb/extraction-attempts",
        change_root=root / "datasets/derived/public-kb/change-detection-results",
        legacy_version_root=root / "datasets/public/kb/product-versions",
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
