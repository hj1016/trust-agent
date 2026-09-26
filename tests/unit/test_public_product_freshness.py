import json
import unittest
from datetime import UTC, datetime, timedelta
from pathlib import Path

from scripts.public_product_freshness import (
    evaluate_confirmation_policy,
    evaluate_freshness,
)


NOW = datetime(2026, 9, 23, 12, 0, tzinfo=UTC)


class PublicProductFreshnessTest(unittest.TestCase):
    def test_shared_confirmation_policy_cases(self) -> None:
        fixture = json.loads(
            Path("contracts/fixtures/public-product-confirmation-policy-cases.json")
            .read_text(encoding="utf-8")
        )
        for case in fixture["cases"]:
            with self.subTest(case=case["name"]):
                result = evaluate_confirmation_policy(
                    evaluated_at=datetime.fromisoformat(case["evaluated_at"]),
                    as_of=datetime.fromisoformat(case["as_of"]),
                    max_confirmation_age=timedelta(
                        seconds=case["max_confirmation_age_seconds"]
                    ),
                    last_confirmed_at=(
                        datetime.fromisoformat(case["last_confirmed_at"])
                        if case["last_confirmed_at"]
                        else None
                    ),
                    latest_observation_at=(
                        datetime.fromisoformat(case["latest_observation_at"])
                        if case["latest_observation_at"]
                        else None
                    ),
                    has_newer_observation=case["has_newer_observation"],
                    latest_observation_extraction_status=case[
                        "latest_observation_extraction_status"
                    ],
                    latest_collection_status=case["latest_collection_status"],
                )
                expected = case["expected"]
                self.assertEqual(
                    expected["freshness_status"], result.freshness.freshness_status
                )
                self.assertEqual(
                    tuple(expected["blocking_reasons"]),
                    result.freshness.blocking_reasons,
                )
                self.assertEqual(
                    tuple(expected["warning_reasons"]),
                    result.freshness.warning_reasons,
                )
                self.assertEqual(expected["historical_query"], result.historical_query)
                self.assertEqual(
                    expected["public_evidence_confirmation_allowed"],
                    result.public_evidence_confirmation_allowed,
                )
                self.assertEqual(
                    tuple(expected["confirmation_blocking_reasons"]),
                    result.confirmation_blocking_reasons,
                )

    def test_unavailable_has_highest_priority(self) -> None:
        result = evaluate_freshness(
            now=NOW,
            max_confirmation_age=timedelta(days=1),
            last_confirmed_at=None,
            latest_observation_at=NOW,
            latest_observation_extraction_status="FAILED",
            latest_collection_status="FAILED",
        )
        self.assertEqual("UNAVAILABLE", result.freshness_status)
        self.assertIn("NO_CONFIRMED_TERMS", result.blocking_reasons)
        self.assertEqual(("LATEST_COLLECTION_FAILED",), result.warning_reasons)

    def test_future_as_of_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "FUTURE_AS_OF_NOT_ALLOWED"):
            evaluate_confirmation_policy(
                evaluated_at=NOW,
                as_of=NOW + timedelta(seconds=1),
                max_confirmation_age=timedelta(days=1),
                last_confirmed_at=NOW,
                latest_observation_at=NOW,
                has_newer_observation=False,
                latest_observation_extraction_status="SUCCEEDED",
                latest_collection_status="SUCCEEDED",
            )

    def test_extraction_failure_beats_stale(self) -> None:
        result = evaluate_freshness(
            now=NOW,
            max_confirmation_age=timedelta(days=1),
            last_confirmed_at=NOW - timedelta(days=10),
            latest_observation_at=NOW - timedelta(days=1),
            latest_observation_extraction_status="FAILED",
            latest_collection_status="SUCCEEDED",
        )
        self.assertEqual("UNCONFIRMED_AFTER_FAILURE", result.freshness_status)
        self.assertEqual(
            ("CONFIRMATION_AGE_EXCEEDED", "LATEST_EXTRACTION_FAILED"),
            result.blocking_reasons,
        )

    def test_new_observation_without_its_own_attempt_is_pending_after_prior_success(self) -> None:
        prior_success_confirmed_at = NOW - timedelta(days=10)
        result = evaluate_freshness(
            now=NOW,
            max_confirmation_age=timedelta(days=1),
            last_confirmed_at=prior_success_confirmed_at,
            latest_observation_at=NOW - timedelta(hours=1),
            latest_observation_extraction_status=None,
            latest_collection_status="SUCCEEDED",
        )
        self.assertEqual("PENDING_EXTRACTION", result.freshness_status)
        self.assertIn("LATEST_OBSERVATION_NOT_EXTRACTED", result.blocking_reasons)

    def test_stale_and_confirmed(self) -> None:
        stale = evaluate_freshness(
            now=NOW,
            max_confirmation_age=timedelta(days=1),
            last_confirmed_at=NOW - timedelta(days=2),
            latest_observation_at=NOW - timedelta(days=2),
            latest_observation_extraction_status="SUCCEEDED",
            latest_collection_status="FAILED",
        )
        self.assertEqual("STALE", stale.freshness_status)
        self.assertNotIn("LATEST_COLLECTION_FAILED", stale.blocking_reasons)
        self.assertEqual(("LATEST_COLLECTION_FAILED",), stale.warning_reasons)

        confirmed = evaluate_freshness(
            now=NOW,
            max_confirmation_age=timedelta(days=1),
            last_confirmed_at=NOW - timedelta(hours=1),
            latest_observation_at=NOW - timedelta(hours=1),
            latest_observation_extraction_status="SUCCEEDED",
            latest_collection_status="SUCCEEDED",
        )
        self.assertEqual("CONFIRMED", confirmed.freshness_status)
        self.assertEqual((), confirmed.blocking_reasons)
        self.assertEqual((), confirmed.warning_reasons)


if __name__ == "__main__":
    unittest.main()
