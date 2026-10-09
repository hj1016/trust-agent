"""TASK-021 AC-06: 요약 스크립트의 백분위 계산, 예열 제외, 입력 거부."""
import json
import tempfile
import unittest
from pathlib import Path

from scripts.summarize_ai_call_metrics import SummaryError, load_scenarios, main, percentile, render_markdown, summarize


def document(total_us, calls, status="PARTIAL", record_status="RECORDED", index=0):
    counts = {kind: sum(1 for c in calls if c[0] == kind) for kind in ("applicable_checklist", "rule_evidence", "record")}
    counts["total"] = len(calls)
    call_sum = sum(c[1] for c in calls)
    return {
        "metrics_version": "ai-call-metrics-v1",
        "run_id": "consultation-preparation-run:" + f"{index:032x}",
        "preparation_id": "consultation-preparation:sha256:" + "0" * 64,
        "application_id": "SW-APPLICATION-001", "business_date": "2026-10-06", "status": status, "record_status": record_status,
        "recorded": True, "started_at": "2026-10-06T03:00:00.000000Z", "total_elapsed_us": total_us, "call_elapsed_us_sum": call_sum,
        "assembly_elapsed_us": total_us - call_sum, "counts": counts,
        "calls": [{"seq": i + 1, "kind": kind, "family_id": None, "outcome": "OK", "http_status": 200, "elapsed_us": us} for i, (kind, us) in enumerate(calls)],
    }


class PercentileTest(unittest.TestCase):
    def test_nearest_rank(self):
        values = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100]
        self.assertEqual(50, percentile(values, 50))
        self.assertEqual(100, percentile(values, 95))
        self.assertEqual(30, percentile([30], 95))
        with self.assertRaises(SummaryError):
            percentile([], 50)


class SummarizeTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="metrics-summary-"))

    def write(self, scenario, docs):
        folder = self.root / scenario
        folder.mkdir(parents=True)
        for i, doc in enumerate(docs):
            (folder / f"{i:03d}.metrics.json").write_text(json.dumps(doc), encoding="utf-8")

    def test_warmup_excluded_and_percentiles_per_kind(self):
        docs = [document(1000 * (i + 1), [("applicable_checklist", 100 * (i + 1)), ("rule_evidence", 10), ("record", 5)], index=i) for i in range(5)]
        self.write("partial", docs)
        summaries = {name: summarize(d, warmup=2) for name, d in load_scenarios(self.root).items()}
        s = summaries["partial"]
        self.assertEqual((5, 2, 3), (s["runs_total"], s["warmup_excluded"], s["runs_measured"]))
        self.assertEqual(4000, s["total"]["p50_us"])
        self.assertEqual(5000, s["total"]["p95_us"])
        self.assertEqual(400, s["per_kind"]["applicable_checklist"]["p50_us"])
        self.assertEqual(1, s["per_kind"]["applicable_checklist"]["count_per_run_min"])
        self.assertEqual({"RECORDED": 3}, s["record_statuses"])
        self.assertIn("partial", render_markdown(summaries))
        self.assertIn("4.0 / 5.0", render_markdown(summaries))

    def test_rejects_empty_dir_version_mismatch_and_full_warmup(self):
        with self.assertRaises(SummaryError):
            load_scenarios(self.root / "nothing")
        (self.root / "empty").mkdir()
        with self.assertRaises(SummaryError):
            load_scenarios(self.root)
        bad = document(100, [("record", 5)])
        bad["metrics_version"] = "other"
        self.write("bad", [bad])
        with self.assertRaises(SummaryError):
            load_scenarios(self.root)
        self.assertEqual(2, main(["--input-dir", str(self.root)]))
        with self.assertRaises(SummaryError):
            summarize([document(100, [("record", 5)])], warmup=1)
