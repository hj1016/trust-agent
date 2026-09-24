import unittest
from datetime import UTC, datetime, timedelta

from scripts.public_product_freshness import evaluate_freshness


NOW = datetime(2026, 9, 23, 12, 0, tzinfo=UTC)


class PublicProductFreshnessTest(unittest.TestCase):
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
