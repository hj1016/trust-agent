"""TASK-015 AC-01~05, 08(서비스 쪽), 13, 17: 규칙 조립 준비안과 보류, 상태, ID, 기록 결과."""
import json
import unittest
from datetime import datetime, timezone

from jsonschema import Draft202012Validator, FormatChecker

from tests.ai_service.support import (
    PREPAYMENT, ROOT, SELLER, FakeTransport, load_json, make_root, mapping_with, prepayment_usable, problem,
    raise_timeout, raise_unavailable, seller_pending, settings_for, ok,
)

from ai_service.assembler import InputError, prepare  # noqa: E402
from ai_service.ids import hash_subject, preparation_id  # noqa: E402

OUTPUT_SCHEMA = Draft202012Validator(load_json(ROOT / "contracts/consultation-preparation.schema.json"), format_checker=FormatChecker())
RECORD_SCHEMA = Draft202012Validator(load_json(ROOT / "contracts/consultation-preparation-record.schema.json"), format_checker=FormatChecker())
CLOCK = lambda: datetime(2026, 10, 6, 3, 0, 0, tzinfo=timezone.utc)  # noqa: E731


def partial_transport() -> FakeTransport:
    return FakeTransport().usable(PREPAYMENT, prepayment_usable()).respond(SELLER, lambda: ok(seller_pending()))


def ready_transport() -> FakeTransport:
    seller = dict(seller_pending())
    seller.update({"usable": True, "blockingReasons": [], "approvedChecklist": {
        "approvedChecklistVersionId": "approved-checklist:" + "4" * 32, "origin": "HUMAN_REVIEW",
        "decisionId": "review-decision:" + "4" * 32, "effectiveFrom": "2026-10-01", "effectiveTo": None,
        "items": [{"order": 0, "ruleKey": "CHECK_SETTLEMENT_EVIDENCE", "instruction": "매출 정산 내역 확인 여부를 상담 준비 체크리스트에 기록한다.",
                   "evidenceRequired": True, "structuredChange": None,
                   "sourceRuleVersionId": "policy-rule:sha256:" + "f" * 64}]}})
    return FakeTransport().usable(PREPAYMENT, prepayment_usable()).usable(SELLER, seller)


class PreparationAssemblyTest(unittest.TestCase):
    def setUp(self):
        self.root = make_root()
        self.settings = settings_for(self.root)

    def run_prepare(self, transport, **kwargs):
        return prepare("SW-APPLICATION-001", "2026-10-06", kwargs.pop("consultation_id", None),
                       settings=kwargs.pop("settings", self.settings), transport=transport, clock=CLOCK, **kwargs)

    # ---- AC-01, AC-02: READY 섹션과 CORE_DECISION 보류, PARTIAL ----
    def test_partial_preparation_has_ready_section_with_evidence_and_core_decision_hold(self):
        transport = partial_transport()
        output = self.run_prepare(transport)
        self.assertEqual([], list(OUTPUT_SCHEMA.iter_errors(output)))
        self.assertEqual("PARTIAL", output["status"])
        self.assertFalse(output["preparation_complete"])
        self.assertIn("끝나지 않았습니다", output["headline"])
        self.assertEqual([SELLER], [check["family_id"] for check in output["remaining_checks"]])
        self.assertEqual([], output["optional_holds"])

        ready = output["sections"][0]
        expected = prepayment_usable()
        self.assertEqual("READY", ready["status"])
        self.assertIsNone(ready["hold_kind"])
        self.assertEqual(expected["approvedChecklist"]["approvedChecklistVersionId"], ready["approved_checklist"]["version_id"])
        self.assertEqual(expected["approvedChecklist"]["decisionId"], ready["approved_checklist"]["decision_id"])
        self.assertEqual(3, len(ready["items"]))
        self.assertEqual([0, 1, 2], [item["order"] for item in ready["items"]])
        for item, source in zip(ready["items"], expected["approvedChecklist"]["items"]):
            self.assertEqual(source["sourceRuleVersionId"], item["source_rule_version_id"])
            self.assertEqual(source["instruction"], item["instruction"])
            self.assertEqual(source["structuredChange"], item["structured_change"])
            self.assertEqual(item["evidence"]["evidence_text"], source["instruction"])
            self.assertTrue(item["evidence"]["evidence_hash"].startswith("sha256:"))
        self.assertEqual("2026-10-05T05:00:00Z", ready["evaluated_at"])
        self.assertTrue(ready["tool_response_hash"].startswith("sha256:"))
        self.assertEqual("SIN-PREPAYMENT-FEE-V2", ready["selected_notice"]["notice_id"])

        hold = output["sections"][1]
        self.assertEqual("HOLD", hold["status"])
        self.assertEqual("CORE_DECISION", hold["hold_kind"])
        self.assertEqual("CORE_REPORTED", hold["hold_claim_basis"])
        self.assertEqual(["HUMAN_REVIEW_PENDING"], hold["blocking_reasons"])
        self.assertEqual([], hold["items"])
        self.assertIsNone(hold["approved_checklist"])
        self.assertIn("검토 대기", hold["hold_message"])
        self.assertIn("/api/v1/internal-policy/checklists/" + SELLER + "/applicable", hold["manual_checklist_notice"])
        self.assertEqual("2026-10-06T03:00:00Z", hold["evaluated_at"])
        self.assertEqual("RECORDED", output["record"]["status"])
        self.assertTrue(output["record"]["recorded"])
        self.assertIn("사용 허가가 아니", output["notices"]["usage_notice"])
        self.assertIn("담당자가 판단", output["notices"]["human_decision_notice"])
        # 보류 섹션 기록 본문도 Core에 그대로 전달됐다.
        record_calls = transport.tool_calls("/api/v1/consultation-preparations")
        self.assertEqual(1, len(record_calls))
        self.assertEqual([], list(RECORD_SCHEMA.iter_errors(record_calls[0])))
        self.assertEqual("CORE_REPORTED", record_calls[0]["sections"][1]["hold_claim_basis"])
        self.assertNotIn("evidence_text", json.dumps(record_calls[0], ensure_ascii=False))

    def test_all_required_ready_gives_ready_with_completion_notice(self):
        output = self.run_prepare(ready_transport())
        self.assertEqual([], list(OUTPUT_SCHEMA.iter_errors(output)))
        self.assertEqual("READY", output["status"])
        self.assertTrue(output["preparation_complete"])
        self.assertIn("상담이나 대출 결정의 완료가 아닙니다", output["headline"])
        self.assertEqual([], output["remaining_checks"])

    # ---- AC-03: 모든 필수 공문군 사용 불가 → HOLD ----
    def test_all_required_hold_gives_hold_without_guessed_items(self):
        fixture_period = dict(prepayment_usable())
        fixture_period.update({"usable": False, "blockingReasons": ["FIXTURE_CHECKLIST_NOT_APPROVED"], "approvedChecklist": None,
                               "businessDate": "2026-09-30"})
        transport = FakeTransport().respond(PREPAYMENT, lambda: ok(fixture_period)).respond(SELLER, lambda: ok(seller_pending()))
        output = self.run_prepare(transport)
        self.assertEqual("HOLD", output["status"])
        self.assertEqual(2, len(output["remaining_checks"]))
        self.assertTrue(all(section["items"] == [] for section in output["sections"]))
        self.assertEqual("RECORDED", output["record"]["status"])
        self.assertTrue(output["record"]["recorded"])

    # ---- AC-04: Tool 2 한 항목 실패 → 섹션 전체 HOLD ----
    def test_single_evidence_failure_holds_whole_section(self):
        transport = partial_transport()
        items = prepayment_usable()["approvedChecklist"]["items"]
        transport.evidence[items[1]["sourceRuleVersionId"]] = lambda: problem(403, "EVIDENCE_NOT_AVAILABLE")
        output = self.run_prepare(transport)
        section = output["sections"][0]
        self.assertEqual("HOLD", section["status"])
        self.assertEqual("UNVERIFIED", section["hold_kind"])
        self.assertEqual("SERVICE_REPORTED", section["hold_claim_basis"])
        self.assertEqual(["EVIDENCE_UNAVAILABLE"], section["blocking_reasons"])
        self.assertEqual([], section["items"])
        self.assertEqual("HOLD", output["status"])

    # ---- AC-05: 통신·인증·응답 오류 → UNVERIFIED, 추측 없음 ----
    def test_unverified_holds_for_auth_server_timeout_and_invalid_responses(self):
        cases = {
            "TOOL_AUTH_FAILED": lambda: problem(401, "UNAUTHENTICATED"),
            "CORE_UNAVAILABLE": lambda: problem(503, "INTERNAL_ERROR"),
            "CORE_TIMEOUT": raise_timeout,
            "TOOL_RESPONSE_INVALID": lambda: ok({"familyId": PREPAYMENT, "usable": True}),
        }
        for code, response in cases.items():
            with self.subTest(code=code):
                transport = FakeTransport().respond(PREPAYMENT, response).respond(SELLER, lambda: ok(seller_pending()))
                output = self.run_prepare(transport)
                section = output["sections"][0]
                self.assertEqual(("HOLD", "UNVERIFIED", [code]), (section["status"], section["hold_kind"], section["blocking_reasons"]))
                self.assertEqual([], section["items"])
                self.assertIn("Core의 판정이 아닙니다", section["hold_message"])
                self.assertEqual([], list(OUTPUT_SCHEMA.iter_errors(output)))
        transport = FakeTransport().respond(PREPAYMENT, raise_unavailable).respond(SELLER, lambda: ok(seller_pending()))
        self.assertEqual(["CORE_UNAVAILABLE"], self.run_prepare(transport)["sections"][0]["blocking_reasons"])

    def test_family_not_found_is_a_core_decision(self):
        transport = FakeTransport().respond(PREPAYMENT, lambda: problem(404, "POLICY_FAMILY_NOT_FOUND")).respond(SELLER, lambda: ok(seller_pending()))
        section = self.run_prepare(transport)["sections"][0]
        self.assertEqual(("HOLD", "CORE_DECISION", ["POLICY_FAMILY_NOT_FOUND"]), (section["status"], section["hold_kind"], section["blocking_reasons"]))

    # ---- 매핑: 선택 공문군, 없는 상품, 필수 없음 ----
    def test_optional_family_hold_does_not_change_overall_status(self):
        root = make_root(mapping_with([
            {"family_id": PREPAYMENT, "required": True, "order": 0},
            {"family_id": SELLER, "required": False, "order": 1},
        ]))
        output = self.run_prepare(partial_transport(), settings=settings_for(root))
        self.assertEqual("READY", output["status"])
        self.assertTrue(output["preparation_complete"])
        self.assertEqual([], output["remaining_checks"])
        self.assertEqual([SELLER], [hold["family_id"] for hold in output["optional_holds"]])
        self.assertEqual([], list(OUTPUT_SCHEMA.iter_errors(output)))

    def test_product_not_in_mapping_is_an_input_error(self):
        root = make_root(mapping_with([{"family_id": PREPAYMENT, "required": True, "order": 0}], product_key="other-product"))
        with self.assertRaises(InputError) as raised:
            self.run_prepare(partial_transport(), settings=settings_for(root))
        self.assertEqual("PRODUCT_NOT_MAPPED", raised.exception.code)

    def test_mapping_without_required_family_never_becomes_ready(self):
        root = make_root(mapping_with([
            {"family_id": PREPAYMENT, "required": False, "order": 0},
            {"family_id": SELLER, "required": False, "order": 1},
        ]))
        output = self.run_prepare(ready_transport(), settings=settings_for(root))
        self.assertEqual("HOLD", output["status"])
        self.assertFalse(output["preparation_complete"])
        self.assertIn("NO_REQUIRED_FAMILY_CONFIGURED", output["headline"])
        self.assertIn("끝나지 않았습니다", output["headline"])
        self.assertEqual([], output["remaining_checks"])
        self.assertEqual([], list(OUTPUT_SCHEMA.iter_errors(output)))

    def test_unknown_application_is_an_input_error(self):
        with self.assertRaises(InputError) as raised:
            prepare("SW-APPLICATION-999", "2026-10-06", settings=self.settings, transport=partial_transport(), clock=CLOCK)
        self.assertEqual("APPLICATION_NOT_FOUND", raised.exception.code)
        with self.assertRaises(InputError) as raised:
            prepare("SW-APPLICATION-001", "2026/10/06", settings=self.settings, transport=partial_transport(), clock=CLOCK)
        self.assertEqual("INVALID_BUSINESS_DATE", raised.exception.code)

    # ---- AC-13, 제안 2: ID와 재실행 ----
    def test_preparation_id_ignores_run_and_time_but_tracks_section_state(self):
        first = self.run_prepare(partial_transport(), consultation_id="demo-1")
        later_clock = lambda: datetime(2026, 10, 6, 4, 0, 0, tzinfo=timezone.utc)  # noqa: E731
        shifted = dict(prepayment_usable())
        shifted["evaluatedAt"] = "2026-10-06T04:00:00Z"
        transport = FakeTransport().usable(PREPAYMENT, shifted).respond(SELLER, lambda: ok(seller_pending()))
        second = prepare("SW-APPLICATION-001", "2026-10-06", "demo-2", settings=self.settings, transport=transport, clock=later_clock)
        self.assertEqual(first["preparation_id"], second["preparation_id"])
        self.assertNotEqual(first["run_id"], second["run_id"])
        self.assertNotEqual(first["sections"][0]["tool_response_hash"], second["sections"][0]["tool_response_hash"])
        changed = self.run_prepare(ready_transport())
        self.assertNotEqual(first["preparation_id"], changed["preparation_id"])

    def test_rerun_calls_core_tools_again_even_when_already_recorded(self):
        transport = partial_transport()
        transport.record = lambda body: ok({"preparationId": body["preparation_id"], "status": "ALREADY_RECORDED", "recordedAt": "2026-10-06T02:00:00Z"})
        self.run_prepare(transport)
        first_calls = len(transport.tool_calls("/api/v1/tools/applicable_checklist"))
        output = self.run_prepare(transport)
        self.assertEqual(first_calls * 2, len(transport.tool_calls("/api/v1/tools/applicable_checklist")))
        self.assertEqual("ALREADY_RECORDED", output["record"]["status"])
        self.assertIn("이미 Core에 기록", output["notices"]["usage_notice"])

    def test_hash_subject_excludes_run_consultation_and_evaluated_at(self):
        transport = partial_transport()
        self.run_prepare(transport, consultation_id="trace-1")
        body = transport.tool_calls("/api/v1/consultation-preparations")[0]
        subject = hash_subject(body)
        self.assertNotIn("run_id", subject)
        self.assertNotIn("consultation_id", subject)
        self.assertNotIn("preparation_id", subject)
        self.assertTrue(all("evaluated_at" not in section and "tool_response_hash" not in section for section in subject["sections"]))
        self.assertEqual(body["preparation_id"], preparation_id(body))

    # ---- 기록 결과 매핑 ----
    def test_record_outcomes_map_to_status_and_usage_notice(self):
        cases = [
            (lambda body: problem(422, "PREPARATION_NOT_USABLE"), "REJECTED", "PREPARATION_NOT_USABLE"),
            (lambda body: problem(409, "PREPARATION_STALE"), "REJECTED", "PREPARATION_STALE"),
            (lambda body: problem(400, "PREPARATION_ID_MISMATCH"), "REJECTED", "PREPARATION_ID_MISMATCH"),
            (lambda body: problem(401, "UNAUTHENTICATED"), "REJECTED", "UNAUTHENTICATED"),
            (lambda body: problem(500, "RECORD_WRITE_FAILED"), "FAILED", "CORE_UNAVAILABLE"),
            (lambda body: raise_timeout(), "FAILED", "CORE_TIMEOUT"),
        ]
        for record, status, code in cases:
            with self.subTest(code=code):
                transport = partial_transport()
                transport.record = record
                output = self.run_prepare(transport)
                self.assertEqual((status, code), (output["record"]["status"], output["record"]["error_code"]))
                self.assertFalse(output["record"]["recorded"])
                self.assertIn("사용하지 말고 다시 실행", output["notices"]["usage_notice"])
                self.assertEqual([], list(OUTPUT_SCHEMA.iter_errors(output)))

    def test_missing_record_token_skips_recording_without_calling_core(self):
        transport = partial_transport()
        output = self.run_prepare(transport, settings=settings_for(self.root, record_token=None))
        self.assertEqual(("NOT_ATTEMPTED", "RECORD_TOKEN_MISSING"), (output["record"]["status"], output["record"]["error_code"]))
        self.assertFalse(output["record"]["recorded"])
        self.assertEqual([], transport.tool_calls("/api/v1/consultation-preparations"))
        self.assertIn("사용하지 말고", output["notices"]["usage_notice"])

    def test_record_request_uses_record_token_and_tool_requests_use_tool_token(self):
        transport = partial_transport()
        self.run_prepare(transport)
        tool_tokens = {token for url, _, token in transport.calls if "/api/v1/tools/" in url}
        record_tokens = {token for url, _, token in transport.calls if url.endswith("/api/v1/consultation-preparations")}
        self.assertEqual({"Bearer temporary-tool-token"}, tool_tokens)
        self.assertEqual({"Bearer temporary-record-token"}, record_tokens)
        self.assertEqual("2026-10-06", transport.tool_calls("/api/v1/tools/applicable_checklist")[0]["businessDate"])


if __name__ == "__main__":
    unittest.main()
