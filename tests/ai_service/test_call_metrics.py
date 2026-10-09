"""TASK-021 AC-01~04: --metrics-file 계측은 준비안 출력을 바꾸지 않고, 호출 수·결과·소요를 계약대로 기록하며 비밀과 원문을 담지 않는다."""
import io
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

from tests.ai_service.support import PREPAYMENT, ROOT, SELLER, FakeTransport, load_json, make_root, ok, prepayment_usable, raise_timeout, seller_pending

from ai_service import config  # noqa: E402
from ai_service.cli import EXIT_RECORDED, main  # noqa: E402
from ai_service.metrics import METRICS_VERSION, TimingTransport, call_kind  # noqa: E402

METRICS_SCHEMA = Draft202012Validator(load_json(ROOT / "contracts/ai-call-metrics.schema.json"), format_checker=FormatChecker())
CLOCK = lambda: datetime(2026, 10, 6, 3, 0, 0, tzinfo=timezone.utc)  # noqa: E731
FIXED_RUN_ID = lambda: "consultation-preparation-run:" + "a" * 32  # noqa: E731


def environ_for(root):
    return {config.ENV_CORE_BASE_URL: "http://core.test", config.ENV_TOOL_TOKEN: "temporary-tool-token",
            config.ENV_RECORD_TOKEN: "temporary-record-token", config.ENV_REPOSITORY_ROOT: str(root)}


def partial_transport():
    return FakeTransport().usable(PREPAYMENT, prepayment_usable()).respond(SELLER, lambda: ok(seller_pending()))


class CallMetricsTest(unittest.TestCase):
    def setUp(self):
        self.root = make_root()
        self.metrics_dir = Path(tempfile.mkdtemp(prefix="ai-metrics-"))

    def run_cli(self, transport, metrics_file=None, business_date="2026-10-06"):
        args = ["prepare", "--application", "SW-APPLICATION-001", "--business-date", business_date, "--consultation-id", "m-1"]
        if metrics_file:
            args += ["--metrics-file", str(metrics_file)]
        out, err = io.StringIO(), io.StringIO()
        code = main(args, environ=environ_for(self.root), stdout=out, stderr=err, transport=transport, clock=CLOCK, run_id_factory=FIXED_RUN_ID)
        return code, out.getvalue(), err.getvalue()

    def test_metrics_counts_match_tool_calls_and_follow_contract(self):
        path = self.metrics_dir / "partial.json"
        code, out, err = self.run_cli(partial_transport(), path)
        self.assertEqual(EXIT_RECORDED, code, err)
        document = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual([], list(METRICS_SCHEMA.iter_errors(document)))
        self.assertEqual(METRICS_VERSION, document["metrics_version"])
        # 매핑 공문군 2개 → Tool 1 두 번, READY 섹션 항목 3개 → Tool 2 세 번, 기록 한 번
        self.assertEqual({"applicable_checklist": 2, "rule_evidence": 3, "record": 1, "total": 6}, document["counts"])
        self.assertEqual(["applicable_checklist", "rule_evidence", "rule_evidence", "rule_evidence", "applicable_checklist", "record"],
                         [call["kind"] for call in document["calls"]])
        self.assertEqual([PREPAYMENT, PREPAYMENT, PREPAYMENT, PREPAYMENT, SELLER, None], [call["family_id"] for call in document["calls"]])
        self.assertEqual([1, 2, 3, 4, 5, 6], [call["seq"] for call in document["calls"]])
        self.assertTrue(all(call["outcome"] == "OK" for call in document["calls"]))
        self.assertEqual([200, 200, 200, 200, 200, 201], [call["http_status"] for call in document["calls"]])
        self.assertEqual(document["call_elapsed_us_sum"], sum(call["elapsed_us"] for call in document["calls"]))
        self.assertGreaterEqual(document["total_elapsed_us"], document["call_elapsed_us_sum"])
        self.assertEqual(document["total_elapsed_us"] - document["call_elapsed_us_sum"], document["assembly_elapsed_us"])
        preparation = json.loads(out)
        self.assertEqual(preparation["preparation_id"], document["preparation_id"])
        self.assertEqual(preparation["run_id"], document["run_id"])
        self.assertEqual("PARTIAL", document["status"])
        self.assertEqual("RECORDED", document["record_status"])
        self.assertTrue(document["recorded"])
        self.assertEqual("m-1", document["consultation_id"])

    def test_metrics_file_contains_no_tokens_text_or_evidence(self):
        path = self.metrics_dir / "partial.json"
        self.run_cli(partial_transport(), path)
        raw = path.read_text(encoding="utf-8")
        self.assertNotIn("temporary-tool-token", raw)
        self.assertNotIn("temporary-record-token", raw)
        self.assertNotIn("Bearer", raw)
        checklist = prepayment_usable()["approvedChecklist"]["items"]
        for item in checklist:
            self.assertNotIn(item["instruction"], raw)
            self.assertNotIn(item["sourceRuleVersionId"], raw)
        self.assertNotIn("evidence_text", raw)
        self.assertNotIn("structured_change", raw)

    def test_failed_calls_are_recorded_and_preparation_is_unchanged(self):
        transport = partial_transport().respond(SELLER, raise_timeout)
        path = self.metrics_dir / "timeout.json"
        code, out, _ = self.run_cli(transport, path)
        self.assertEqual(EXIT_RECORDED, code)
        document = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual([], list(METRICS_SCHEMA.iter_errors(document)))
        seller_call = [call for call in document["calls"] if call["family_id"] == SELLER][0]
        self.assertEqual(("applicable_checklist", "TIMEOUT", None), (seller_call["kind"], seller_call["outcome"], seller_call["http_status"]))
        preparation = json.loads(out)
        self.assertEqual("HOLD", preparation["sections"][1]["status"])
        self.assertEqual(["CORE_TIMEOUT"], preparation["sections"][1]["blocking_reasons"])
        code_plain, out_plain, _ = self.run_cli(partial_transport().respond(SELLER, raise_timeout))
        self.assertEqual((code, out), (code_plain, out_plain), "계측 유무가 준비안 결과를 바꾸지 않는다")

    def test_output_and_exit_code_identical_with_and_without_metrics(self):
        code_plain, out_plain, err_plain = self.run_cli(partial_transport())
        code_metrics, out_metrics, err_metrics = self.run_cli(partial_transport(), self.metrics_dir / "same.json")
        self.assertEqual((code_plain, out_plain, err_plain), (code_metrics, out_metrics, err_metrics))
        self.assertFalse((self.metrics_dir / "missing.json").exists())

    def test_hold_scenario_has_no_rule_evidence_calls(self):
        transport = FakeTransport().respond(PREPAYMENT, lambda: ok({**prepayment_usable(), "usable": False, "blockingReasons": ["FIXTURE_CHECKLIST_NOT_APPROVED"], "approvedChecklist": None})) \
            .respond(SELLER, lambda: ok(seller_pending()))
        path = self.metrics_dir / "hold.json"
        self.run_cli(transport, path, business_date="2026-09-30")
        document = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual({"applicable_checklist": 2, "rule_evidence": 0, "record": 1, "total": 3}, document["counts"])
        self.assertEqual("HOLD", document["status"])

    def test_timing_transport_classifies_urls(self):
        self.assertEqual("applicable_checklist", call_kind("http://core.test/api/v1/tools/applicable_checklist"))
        self.assertEqual("rule_evidence", call_kind("http://core.test/api/v1/tools/rule_evidence"))
        self.assertEqual("record", call_kind("http://core.test/api/v1/consultation-preparations"))
        self.assertEqual("unknown", call_kind("http://core.test/other"))
        timing = TimingTransport(partial_transport())
        self.assertEqual([], timing.calls)
