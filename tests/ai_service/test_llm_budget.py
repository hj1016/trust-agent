"""외부 LLM 호출 통제(무과금). 가짜 전송기만 쓰며 네트워크·Bedrock을 부르지 않는다. 예시 응답 테스트이며 실제 모델 품질 검증이 아니다."""
from __future__ import annotations

import multiprocessing
import json
import tempfile
import unittest
from pathlib import Path

import tests.ai_service.support  # noqa: F401  (ai_service 경로 등록)
from ai_service.llm import Gateway, LlmError, SenderResult, build_payload, identifier_kinds
from ai_service.llm_budget import BudgetError, Ledger

SCHEMA = {"type": "object", "properties": {"answer": {"type": "string"}}, "required": ["answer"], "additionalProperties": False}
EVIDENCE = [{"rule_version_id": "policy-rule:sha256:" + "a" * 64, "evidence": {"notice_id": "SIN-PREPAYMENT-FEE-V2", "evidence_text": "원문", "json_pointer": "/rules/0",
             "evidence_hash": "sha256:x"}, "structured_change": {"after_value": "0.8", "unit": "PERCENT", "field_key": "x", "change_type": "y"},
             "score": "1.000000", "rule_key": "R", "instruction": "i", "evidence_required": True}]
FUTURE = "2099-01-01T00:00:00+00:00"


def ok_sender(calls):
    def send(request):
        calls.append(request)
        return SenderResult('{"answer": "예시"}', 1000, 200, "end_turn")
    return send


def _reserve_in_child(path: str, queue) -> None:
    ledger = Ledger(Path(path))
    try:
        ledger.reserve("A", "smoke", "m", "h", 100, 100, "child")
        queue.put("OK")
    except BudgetError as error:
        queue.put(error.code)


class LedgerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = tempfile.TemporaryDirectory()
        self.path = Path(self.dir.name) / "ledger.sqlite"
        self.ledger = Ledger(self.path)

    def tearDown(self) -> None:
        self.dir.cleanup()

    def gateway(self, sender, approval="A", purpose="smoke") -> Gateway:
        return Gateway(self.ledger, sender, "global.anthropic.claude-haiku-5-5", approval, purpose, "test")

    def payload(self):
        return build_payload("수수료를 안 받는 예외가 있나요?", "2026-10-06", EVIDENCE)

    def test_no_registered_budget_means_zero_calls(self):
        calls = []
        with self.assertRaises(BudgetError) as raised:
            self.gateway(ok_sender(calls)).call("sys", self.payload(), SCHEMA)
        self.assertEqual("LLM_BUDGET_NOT_APPROVED", raised.exception.code)
        self.assertEqual([], calls)

    def test_call_count_cap_blocks_before_http(self):
        self.ledger.register("A", 2, 1.0, 0.25, 1.25, FUTURE, "smoke", "test")
        calls = []
        gateway = self.gateway(ok_sender(calls))
        gateway.call("sys", self.payload(), SCHEMA)
        gateway.call("sys", self.payload(), SCHEMA)
        with self.assertRaises(BudgetError) as raised:
            gateway.call("sys", self.payload(), SCHEMA)
        self.assertEqual("LLM_BUDGET_EXCEEDED", raised.exception.code)
        self.assertEqual(2, len(calls))

    def test_amount_cap_uses_reservation_of_max_output(self):
        # 최대 출력 1500 토큰 × $1.25/MTok = $0.001875만으로 상한 $0.001을 넘는다 → 호출 전 차단
        self.ledger.register("A", 10, 0.001, 0.25, 1.25, FUTURE, "smoke", "test")
        calls = []
        with self.assertRaises(BudgetError):
            self.gateway(ok_sender(calls)).call("sys", self.payload(), SCHEMA)
        self.assertEqual([], calls)

    def test_settlement_uses_usage_and_failures_keep_reservation(self):
        self.ledger.register("A", 10, 1.0, 0.25, 1.25, FUTURE, "smoke", "test")
        result = self.gateway(ok_sender([])).call("sys", self.payload(), SCHEMA)
        self.assertAlmostEqual((1000 * 0.25 + 200 * 1.25) / 1_000_000, result.charged_usd, places=9)
        for code in ("LLM_RATE_LIMITED", "LLM_TIMEOUT"):
            def failing(_request, code=code):
                raise LlmError(code, "x")
            with self.assertRaises(LlmError):
                self.gateway(failing).call("sys", self.payload(), SCHEMA)
        summary = self.ledger.summary("A")
        self.assertEqual({"OK": 1, "LLM_RATE_LIMITED": 1, "LLM_TIMEOUT": 1}, summary["by_status"])
        self.assertGreater(summary["committed_usd"], result.charged_usd * 2)  # 실패 2건은 예약액(최대 출력 포함) 그대로 확정

    def test_missing_usage_and_invalid_json_are_charged_conservatively(self):
        self.ledger.register("A", 10, 1.0, 0.25, 1.25, FUTURE, "smoke", "test")
        with self.assertRaises(LlmError) as raised:
            self.gateway(lambda _r: SenderResult("not json", None, None, "end_turn")).call("sys", self.payload(), SCHEMA)
        self.assertEqual("LLM_RESPONSE_INVALID", raised.exception.code)
        self.assertEqual({"USAGE_MISSING": 1}, self.ledger.summary("A")["by_status"])
        with self.assertRaises(LlmError) as raised:
            self.gateway(lambda _r: SenderResult('{"answer"', 10, 1500, "max_tokens")).call("sys", self.payload(), SCHEMA)
        self.assertEqual("LLM_TRUNCATED", raised.exception.code)

    def test_budget_survives_restart_and_is_shared(self):
        self.ledger.register("A", 1, 1.0, 0.25, 1.25, FUTURE, "smoke", "test")
        Ledger(self.path).reserve("A", "smoke", "m", "h", 10, 10, "worktree-1")
        with self.assertRaises(BudgetError):
            Ledger(self.path).reserve("A", "smoke", "m", "h", 10, 10, "worktree-2")

    def test_concurrent_reservations_from_two_processes_respect_cap(self):
        self.ledger.register("A", 1, 1.0, 0.25, 1.25, FUTURE, "smoke", "test")
        context = multiprocessing.get_context("spawn")
        queue = context.Queue()
        processes = [context.Process(target=_reserve_in_child, args=(str(self.path), queue)) for _ in range(2)]
        for process in processes:
            process.start()
        for process in processes:
            process.join(30)
        results = sorted(queue.get(timeout=5) for _ in processes)
        self.assertEqual(["LLM_BUDGET_EXCEEDED", "OK"], results)

    def test_single_flight_rejects_parallel_call(self):
        with self.ledger.single_flight():
            other = Ledger(self.path)
            child = multiprocessing.get_context("spawn")
            # 같은 프로세스 안 두 번째 잠금은 flock 특성상 허용될 수 있어, 다른 프로세스에서 확인한다
            queue = child.Queue()
            process = child.Process(target=_try_lock, args=(str(self.path), queue))
            process.start()
            process.join(30)
            self.assertEqual("LLM_BUSY", queue.get(timeout=5))
            del other

    def test_expired_or_wrong_purpose_budget(self):
        self.ledger.register("OLD", 5, 1.0, 0.25, 1.25, "2000-01-01T00:00:00+00:00", "smoke", "test")
        self.ledger.register("EVAL", 5, 1.0, 0.25, 1.25, FUTURE, "eval", "test")
        with self.assertRaises(BudgetError) as raised:
            self.gateway(ok_sender([]), approval="OLD").call("sys", self.payload(), SCHEMA)
        self.assertEqual("LLM_BUDGET_EXPIRED", raised.exception.code)
        with self.assertRaises(BudgetError) as raised:
            self.gateway(ok_sender([]), approval="EVAL", purpose="smoke").call("sys", self.payload(), SCHEMA)
        self.assertEqual("LLM_BUDGET_PURPOSE_MISMATCH", raised.exception.code)


def _try_lock(path: str, queue) -> None:
    try:
        with Ledger(Path(path)).single_flight():
            queue.put("OK")
    except BudgetError as error:
        queue.put(error.code)


class PayloadTest(unittest.TestCase):
    def test_payload_contains_only_allowlisted_fields(self):
        payload = build_payload("수수료를 안 받는 예외가 있나요?", "2026-10-06", EVIDENCE)
        self.assertEqual({"question", "business_date", "evidence", "version_changes"}, set(payload))
        self.assertEqual((False, "COMPARISON_NOT_PROVIDED", []), (payload["version_changes"]["available"], payload["version_changes"]["unavailable_reason"],
                                                                 payload["version_changes"]["changes"]), "비교 자료가 없으면 비교 불가로 보낸다(추측 금지)")
        self.assertEqual({"rule_version_id", "notice_id", "notice_title", "evidence_text", "structured"}, set(payload["evidence"][0]))
        self.assertIsNone(payload["evidence"][0]["notice_title"], "검색 근거에 제목이 없으면 비워 두고 모델이 추측하게 하지 않는다")
        titled = build_payload("수수료?", "2026-10-06", [{**EVIDENCE[0], "notice_title": "[합성] 공문 제목"}])
        self.assertEqual("[합성] 공문 제목", titled["evidence"][0]["notice_title"])
        self.assertEqual({"after_value", "unit"}, set(payload["evidence"][0]["structured"]))  # field_key·change_type·점수·해시 제외
        text = str(payload)
        for forbidden in ("score", "json_pointer", "evidence_hash", "instruction", "consult", "grant"):
            self.assertNotIn(forbidden, text)

    def test_identifier_detection(self):
        self.assertEqual([], identifier_kinds("중도상환수수료율 0.8%는 언제부터 적용되나요?"))
        self.assertIn("AMOUNT", identifier_kinds("신청 금액 150,000,000원 고객은 해당되나요?"))
        self.assertIn("KOREAN_AMOUNT", identifier_kinds("한도 이십억 고객"))
        self.assertIn("BUSINESS_NUMBER", identifier_kinds("123-45-67890 법인"))
        self.assertIn("REGISTERED_IDENTIFIER", identifier_kinds("가상상사 주식회사 건 문의", ("가상상사 주식회사",)))


if __name__ == "__main__":
    unittest.main()


class FakeGatewayGuardTest(unittest.TestCase):
    def test_fake_echo_only_in_explicit_local_deployment(self):
        from ai_service.llm import FakeEchoGateway, gateway_for_service
        with self.assertRaises(LlmError):
            gateway_for_service({"TRUST_AGENT_LLM_FAKE": "echo"})
        with self.assertRaises(LlmError):
            gateway_for_service({"TRUST_AGENT_LLM_FAKE": "echo", "TRUST_AGENT_AI_DEPLOYMENT": "production"})
        self.assertIsInstance(gateway_for_service({"TRUST_AGENT_LLM_FAKE": "echo", "TRUST_AGENT_AI_DEPLOYMENT": "local"}), FakeEchoGateway)

    def test_without_fake_or_budget_settings_service_runs_without_llm(self):
        from ai_service.llm import gateway_for_service
        self.assertIsNone(gateway_for_service({}))


class CloudRequestShapeTest(unittest.TestCase):
    """클라우드 요청 형식(실제 호출 없음): Claude는 강제 도구 호출 + 사고 끔, Converse는 JSON Schema(strict) + 추론 none. 받은 JSON은 스키마로 재검사."""

    def setUp(self) -> None:
        self.dir = tempfile.TemporaryDirectory()
        self.ledger = Ledger(Path(self.dir.name) / "ledger.sqlite")
        self.ledger.register("A", 5, 1.0, 0.10, 0.50, FUTURE, "smoke", "test")

    def tearDown(self) -> None:
        self.dir.cleanup()

    def test_claude_request_uses_forced_tool_and_disables_thinking(self):
        calls = []
        Gateway(self.ledger, ok_sender(calls), "global.anthropic.claude-haiku-5-5", "A", "smoke", "test").call(
            "sys", build_payload("예외?", "2026-10-06", EVIDENCE), SCHEMA)
        request = calls[0]
        self.assertEqual({"type": "tool", "name": "submit_answer"}, request["tool_choice"])
        self.assertEqual(SCHEMA, request["tools"][0]["input_schema"])
        self.assertEqual({"type": "disabled"}, request["thinking"])
        self.assertNotIn("output_config", request, "Bedrock Haiku 5.5는 JSON Schema 정형 출력 미지원")

    def test_response_that_breaks_schema_is_rejected(self):
        def bad(request):
            return SenderResult('{"other": 1}', 100, 10, "end_turn")
        with self.assertRaises(Exception) as raised:
            Gateway(self.ledger, bad, "m", "A", "smoke", "test").call("sys", build_payload("예외?", "2026-10-06", EVIDENCE), SCHEMA)
        self.assertEqual("LLM_FIELD_MISSING", raised.exception.code)

    def test_converse_request_shape(self):
        from ai_service.llm import converse_request
        request = {"model": "global.openai.gpt-6-luna", "max_tokens": 1500, "system": "sys", "messages": [{"role": "user", "content": "{}"}],
                   "tools": [{"name": "submit_answer", "input_schema": SCHEMA}], "tool_choice": {"type": "tool", "name": "submit_answer"},
                   "thinking": {"type": "disabled"}, "temperature": 0}
        converse = converse_request(request)
        self.assertEqual("global.openai.gpt-6-luna", converse["modelId"])
        self.assertEqual(SCHEMA, json.loads(converse["outputConfig"]["textFormat"]["structure"]["jsonSchema"]["schema"]))
        self.assertTrue(converse["additionalModelRequestFields"]["text"]["format"]["strict"])
        self.assertEqual("none", converse["additionalModelRequestFields"]["reasoning_effort"])
        self.assertEqual(1500, converse["inferenceConfig"]["maxTokens"])
