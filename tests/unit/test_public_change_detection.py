import json
import tempfile
import unittest
from pathlib import Path

from scripts import extract_public_kb_product_facts as extractor


PRODUCT = "small-business-credit"


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def event_values(token: str, observed_at: str, terms_token: str, rate: str, date: str):
    observation_id = f"obs:{PRODUCT}:{token * 32}"
    observation = {
        "observation_id": observation_id,
        "product_key": PRODUCT,
        "observed_at": observed_at,
    }
    evidence = {
        "observation_id": observation_id,
        "product_key": PRODUCT,
        "product_terms_version_id": f"ptv:{PRODUCT}:sha256:{terms_token * 64}",
    }
    quote = {
        "observation_id": observation_id,
        "product_key": PRODUCT,
        "advertised_rate_text": rate,
        "advertised_rate_reference_date": date,
    }
    return observation, evidence, quote


class PublicChangeDetectionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.observations = self.root / "observations"
        self.evidence = self.root / "evidence"
        self.quotes = self.root / "quotes"
        self.changes = self.root / "changes"

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def add_event(
        self, token: str, observed_at: str, terms_token: str, rate: str, date: str
    ) -> str:
        observation, evidence, quote = event_values(
            token, observed_at, terms_token, rate, date
        )
        observation_id = observation["observation_id"]
        write_json(
            self.observations / f"{token}.observation.json", observation
        )
        write_json(
            self.evidence / f"{token}.version-evidence.json", evidence
        )
        write_json(self.quotes / f"{token}.rate-quote.json", quote)
        return observation_id

    def active_results(self) -> dict[str, dict]:
        return extractor._active_change_results(self.changes)

    def test_terms_and_rate_can_change_in_the_same_observation(self) -> None:
        self.add_event("1", "2026-09-21T00:00:00Z", "a", "연 3.0%", "2026-09-21")
        second = self.add_event(
            "2", "2026-09-22T00:00:00Z", "b", "연 4.0%", "2026-09-22"
        )

        extractor.rebuild_change_detection(
            observation_root=self.observations,
            evidence_root=self.evidence,
            quote_root=self.quotes,
            change_root=self.changes,
        )

        self.assertEqual(
            ["PRODUCT_TERMS_CHANGED", "RATE_QUOTE_CHANGED"],
            self.active_results()[second]["classifications"],
        )

    def test_rate_text_change_does_not_create_a_terms_change(self) -> None:
        self.add_event("1", "2026-09-21T00:00:00Z", "a", "연 3.0%", "2026-09-21")
        second = self.add_event(
            "2", "2026-09-22T00:00:00Z", "a", "연 4.0%", "2026-09-22"
        )
        extractor.rebuild_change_detection(
            observation_root=self.observations,
            evidence_root=self.evidence,
            quote_root=self.quotes,
            change_root=self.changes,
        )
        self.assertEqual(
            ["RATE_QUOTE_CHANGED"],
            self.active_results()[second]["classifications"],
        )

    def test_incomplete_observation_is_skipped_without_stopping_later_results(self) -> None:
        incomplete, incomplete_evidence, _ = event_values(
            "1", "2026-09-21T00:00:00Z", "a", "연 3.0%", "2026-09-21"
        )
        write_json(self.observations / "1.observation.json", incomplete)
        write_json(self.evidence / "1.version-evidence.json", incomplete_evidence)
        complete = self.add_event(
            "2", "2026-09-22T00:00:00Z", "a", "연 3.0%", "2026-09-22"
        )

        created = extractor.rebuild_change_detection(
            observation_root=self.observations,
            evidence_root=self.evidence,
            quote_root=self.quotes,
            change_root=self.changes,
        )

        self.assertEqual(1, len(created))
        self.assertEqual(["BASELINE_ESTABLISHED"], created[0]["classifications"])
        self.assertEqual(complete, created[0]["observation_id"])
        self.assertNotIn(incomplete["observation_id"], self.active_results())

    def test_backfill_supersedes_results_using_observation_time(self) -> None:
        first = self.add_event(
            "2", "2026-09-22T00:00:00Z", "a", "연 3.0%", "2026-09-22"
        )
        second = self.add_event(
            "3", "2026-09-23T00:00:00Z", "a", "연 3.0%", "2026-09-23"
        )
        extractor.rebuild_change_detection(
            observation_root=self.observations,
            evidence_root=self.evidence,
            quote_root=self.quotes,
            change_root=self.changes,
        )
        original_first_result = self.active_results()[first][
            "change_detection_result_id"
        ]

        backfill = self.add_event(
            "1", "2026-09-21T00:00:00.500000Z", "a", "연 3.0%", "2026-09-21"
        )
        extractor.rebuild_change_detection(
            observation_root=self.observations,
            evidence_root=self.evidence,
            quote_root=self.quotes,
            change_root=self.changes,
        )
        active = self.active_results()

        self.assertEqual(["BASELINE_ESTABLISHED"], active[backfill]["classifications"])
        self.assertEqual(backfill, active[first]["previous_observation_id"])
        self.assertEqual(original_first_result, active[first]["supersedes_result_id"])
        self.assertEqual(first, active[second]["previous_observation_id"])


if __name__ == "__main__":
    unittest.main()
