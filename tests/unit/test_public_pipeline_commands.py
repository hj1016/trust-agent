import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from scripts import collect_public_kb_snapshots as collector
from scripts import extract_public_kb_product_facts as extractor


RUN_ID = "run:11111111111111111111111111111111"


class PublicPipelineCommandTest(unittest.TestCase):
    def test_collection_all_continues_after_one_product_failure(self) -> None:
        products = [
            collector.CatalogProduct(
                "small-business-credit", "first", "https://obank.kbstar.com/first", "first"
            ),
            collector.CatalogProduct(
                "kb-seller-loan", "second", "https://zloan.kbstar.com/second", "second"
            ),
        ]
        success = SimpleNamespace(
            status="CREATED",
            snapshot_hash="sha256:" + "a" * 64,
            observation_id="obs:kb-seller-loan:" + "b" * 32,
            collection_attempt_id="collect:kb-seller-loan:" + "c" * 32,
        )
        output = io.StringIO()
        with (
            mock.patch.object(collector, "load_catalog", return_value=products),
            mock.patch.object(
                collector,
                "collect_product",
                side_effect=[collector.CollectorError("failed", "TEST_FAILURE"), success],
            ) as collect,
            redirect_stdout(output),
        ):
            exit_code = collector.main(
                ["--all", "--run-id", RUN_ID, "--attempt-sequence", "3"]
            )

        self.assertEqual(1, exit_code)
        self.assertEqual(2, collect.call_count)
        self.assertEqual(
            [3, 3],
            [call.kwargs["attempt_sequence"] for call in collect.call_args_list],
        )
        summary = json.loads(output.getvalue())
        self.assertEqual(["FAILED", "SUCCEEDED"], [item["status"] for item in summary["results"]])
        self.assertNotIn("failed", summary["results"][0]["error_message"])

    def test_extraction_all_continues_after_one_observation_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            observation_root = root / "observations"
            observation_root.mkdir()
            observations = [
                {
                    "product_key": "small-business-credit",
                    "observation_id": "obs:small-business-credit:" + "1" * 32,
                    "observed_at": "2026-09-21T00:00:00Z",
                },
                {
                    "product_key": "kb-seller-loan",
                    "observation_id": "obs:kb-seller-loan:" + "2" * 32,
                    "observed_at": "2026-09-22T00:00:00Z",
                },
            ]
            for index, value in enumerate(observations):
                (observation_root / f"{index}.observation.json").write_text(
                    json.dumps(value), encoding="utf-8"
                )
            success = SimpleNamespace(
                status="CREATED",
                product_terms_version_id="ptv:kb-seller-loan:sha256:" + "a" * 64,
            )
            output = io.StringIO()
            with (
                mock.patch.object(
                    extractor,
                    "extract_observation",
                    side_effect=[
                        extractor.FactExtractionError("failed", "TEST_FAILURE"),
                        success,
                    ],
                ) as extract,
                mock.patch.object(extractor, "rebuild_change_detection", return_value=[]),
                redirect_stdout(output),
            ):
                exit_code = extractor.main(
                    [
                        "--all",
                        "--observation-root",
                        str(observation_root),
                        "--artifact-root",
                        str(root / "artifacts"),
                        "--terms-root",
                        str(root / "terms"),
                        "--evidence-root",
                        str(root / "evidence"),
                        "--quote-root",
                        str(root / "quotes"),
                        "--attempt-root",
                        str(root / "attempts"),
                        "--change-root",
                        str(root / "changes"),
                        "--run-id",
                        RUN_ID,
                    ]
                )

            self.assertEqual(1, exit_code)
            self.assertEqual(2, extract.call_count)
            summary = json.loads(output.getvalue())
            self.assertEqual(
                ["FAILED", "SUCCEEDED"],
                [item["status"] for item in summary["results"]],
            )
            self.assertNotIn("failed", summary["results"][0]["error_message"])


if __name__ == "__main__":
    unittest.main()
