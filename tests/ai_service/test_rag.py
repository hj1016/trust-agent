"""RAG 흐름과 답변 검증(무과금, 예시 응답). 실제 모델 답변 품질은 이 테스트로 검증하지 않는다."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import tests.ai_service.support  # noqa: F401  (ai_service 경로 등록)
from ai_service.llm import Gateway, SenderResult
from ai_service.llm_budget import Ledger
from ai_service.rag import answer, deterministic_checks

FEE = "policy-rule:sha256:" + "a" * 64
COND = ["기업 고객의 기업여신 상담 건", "2026-10-01 이후 중도상환"]
EXC = ["별도 약정으로 수수료 면제가 확인된 경우"]
EVIDENCE = [{
    "rank": 1, "rule_version_id": FEE, "rule_key": "CHECK_PREPAYMENT_FEE_RATE", "score": "0.030000", "instruction": "i", "evidence_required": True,
    "structured_change": {"before_value": "1.2", "after_value": "0.8", "unit": "PERCENT", "effective_on": "2026-10-01",
                          "applicable_product_keys": ["kb-seller-loan"], "conditions": COND, "exceptions": EXC, "field_key": "f", "change_type": "c"},
    "evidence": {"notice_id": "SIN-PREPAYMENT-FEE-V2", "evidence_text": "기업여신 상담 시 변경된 중도상환수수료율 0.8퍼센트와 적용 조건을 원문 근거에서 확인한다.",
                 "json_pointer": "/rules/0", "evidence_hash": "sha256:x"},
}]
SEARCH_OK = {"status": "EVIDENCE", "hold_kind": None, "hold_reasons": [], "evidence": EVIDENCE}
GOOD = {"explanation": "kb-seller-loan 상품의 기업여신 중도상환수수료율은 2026-10-01부터 0.8퍼센트(변경 전 1.2퍼센트)입니다. 적용 조건과 면제 예외를 함께 확인해야 합니다.",
        "citations": [FEE], "conditions": COND, "exceptions": EXC, "needs_check": ["고객의 중도상환 예정일이 2026-10-01 이후인가요?"]}
PASS = {"intent_match": True, "unsupported_claims": [], "missing_conditions": [], "decision_made": False, "scope_mismatch": False, "uncovered_case_asserted": []}


class ScriptedSender:
    def __init__(self, *responses: dict) -> None:
        self.responses = list(responses)
        self.requests: list[dict] = []

    def __call__(self, request: dict) -> SenderResult:
        self.requests.append(request)
        response = self.responses.pop(0)
        if "explanation" in response and "draft_answer" not in json.loads(request["messages"][0]["content"]):
            response = as_statements(response)
        return SenderResult(json.dumps(response, ensure_ascii=False), 900, 250, "end_turn")


QUOTE = "변경된 중도상환수수료율 0.8퍼센트"


def as_statements(draft: dict, source: str = FEE, quote: str = QUOTE) -> dict:
    """설명 한 덩어리로 쓴 시험 응답을 생성 출력 형식(문장·근거·원문 인용구)으로 바꾼다. 문장마다 같은 근거 인용구를 단다."""
    sentences = [part.strip() + ("" if part.strip().endswith(".") else ".") for part in draft["explanation"].split(". ") if part.strip()]
    converted = {"request_type": "RULE_QUESTION", "answerable": True,
                 **{key: value for key, value in draft.items() if key not in ("explanation", "conditions", "exceptions")}}
    converted["statements"] = [{"text": sentence.rstrip(".") + "." if not sentence.endswith(".") else sentence, "source": source, "quote": quote}
                               for sentence in sentences]
    return converted


class RagTest(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = tempfile.TemporaryDirectory()
        self.ledger = Ledger(Path(self.dir.name) / "l.sqlite")
        self.ledger.register("A", 20, 1.0, 0.25, 1.25, "2099-01-01T00:00:00+00:00", "dev", "test")

    def tearDown(self) -> None:
        self.dir.cleanup()

    def gateway(self, sender) -> Gateway:
        return Gateway(self.ledger, sender, "global.anthropic.claude-haiku-5-5", "A", "dev", "test")

    def run_answer(self, *responses, search=SEARCH_OK, question="중도상환수수료 지금 얼마예요?", blocked=()):
        sender = ScriptedSender(*responses)
        return answer(question, "2026-10-06", search, self.gateway(sender), blocked), sender

    def test_version_comparison_dates_are_evidence_but_previous_application_is_blocked(self):
        comparison = {"kind": "CHANGED", "previousNoticeId": "N1", "previousTitle": "[합성] v1", "available": True, "unavailableReason": None,
                      "changes": [{"ruleKey": "CHECK_PREPAYMENT_FEE_RATE", "changeType": "MODIFIED", "beforeInstruction": "1.2퍼센트 확인", "afterInstruction": "0.8퍼센트 확인",
                                   "beforeStructuredChange": {"after_value": "1.2", "effective_on": "2026-09-15"}, "afterStructuredChange": {"after_value": "0.8"}}]}
        search = {**SEARCH_OK, "version_comparison": comparison}
        history = {"request_type": "RULE_QUESTION", "answerable": True, "citations": ["E1"], "needs_check": [], "statements": [
            {"text": "변경된 중도상환수수료율은 0.8퍼센트입니다.", "source": "E1", "quote": QUOTE},
            {"text": "변경 비교 자료에는 이전 시행일이 2026-09-15로 표시되어 있습니다.", "source": "VERSION_CHANGES", "quote": "2026-09-15"}]}
        sender = ScriptedSender(history, PASS)
        result = answer("요율이 언제 바뀌었나요?", "2026-10-06", search, self.gateway(sender))
        self.assertEqual("ANSWERED", result["status"], result.get("reasons"))
        self.assertTrue(json.loads(sender.requests[0]["messages"][0]["content"])["version_changes"]["available"])
        applied = {**history, "statements": [history["statements"][0],
                   {"text": "2026-09-15부터 9월 말까지 상환한 건은 이전 요율 1.2퍼센트가 적용됩니다.", "source": "VERSION_CHANGES", "quote": "2026-09-15"}]}
        held = answer("요율이 언제 바뀌었나요?", "2026-10-06", search, self.gateway(ScriptedSender(applied, PASS)))
        self.assertIn("VERSION_APPLICABILITY_UNSUPPORTED", held["reasons"])

    def test_model_sees_short_evidence_aliases_and_answer_keeps_real_ids(self):
        aliased = {**as_statements(GOOD, source="E1"), "citations": ["E1"]}
        result, sender = self.run_answer(aliased, PASS)
        self.assertEqual("ANSWERED", result["status"], result.get("reasons"))
        self.assertEqual([FEE], result["answer"]["citations"])
        sent = json.loads(sender.requests[0]["messages"][0]["content"])
        self.assertEqual("E1", sent["evidence"][0]["rule_version_id"])
        self.assertNotIn(FEE, sender.requests[0]["messages"][0]["content"], "긴 근거 ID는 모델에 보내지 않는다")
        self.assertEqual(["E1"], json.loads(sender.requests[1]["messages"][0]["content"])["draft_answer"]["citations"])
        typo, _ = self.run_answer({**as_statements(GOOD, source="E1"), "citations": ["E9"]}, PASS)
        self.assertIn("CITATION_NOT_IN_EVIDENCE", typo["reasons"], "없는 별칭은 그대로 막힌다")

    def test_verified_answer_uses_two_calls_and_reports_usage(self):
        result, sender = self.run_answer(GOOD, PASS)
        self.assertEqual("ANSWERED", result["status"])
        self.assertEqual(2, result["llm_calls"])
        self.assertEqual(1800, result["usage"]["input_tokens"])
        self.assertEqual(GOOD, result["answer"])
        verify_payload = json.loads(sender.requests[1]["messages"][0]["content"])
        self.assertIn("draft_answer", verify_payload)
        self.assertNotIn("consultation", sender.requests[0]["messages"][0]["content"])

    def test_holds_and_decision_requests_never_call_llm(self):
        for search, expected in (({"status": "EVIDENCE_HOLD", "hold_kind": "DECISION_REQUEST", "hold_reasons": ["DECISION_REQUEST_NOT_SUPPORTED"]}, "DECISION_DECLINED"),
                                 ({"status": "EVIDENCE_HOLD", "hold_kind": "NO_CANDIDATE", "hold_reasons": ["NO_RELEVANT_CANDIDATE"]}, "EVIDENCE_HOLD")):
            result, sender = self.run_answer(search=search)
            self.assertEqual(expected, result["status"])
            self.assertEqual([], sender.requests)
            self.assertIsNone(result["answer"])

    def test_identifier_in_question_is_held_before_llm(self):
        result, sender = self.run_answer(question="신청 금액 150,000,000원인 가상상사 주식회사도 0.8%인가요?", blocked=("가상상사 주식회사",))
        self.assertEqual(["QUESTION_CONTAINS_IDENTIFIER"], result["reasons"])
        self.assertEqual([], sender.requests)

    def test_code_checks_block_unverified_answers_before_llm_verification(self):
        cases = {
            "NUMBER_NOT_IN_EVIDENCE": {**GOOD, "explanation": GOOD["explanation"].replace("0.8퍼센트(", "0.7퍼센트(")},
            "DATE_NOT_IN_EVIDENCE": {**GOOD, "needs_check": ["중도상환 예정일이 2026-10-15 이후인가요?"]},
            "CITATION_NOT_IN_EVIDENCE": {**GOOD, "citations": ["policy-rule:sha256:" + "b" * 64]},
            "DECISION_ASSERTION": {**GOOD, "explanation": GOOD["explanation"] + " 이 고객에게 0.8퍼센트가 적용됩니다."},
        }
        for code, draft in cases.items():
            result, sender = self.run_answer(draft, PASS)
            self.assertEqual("ANSWER_HOLD", result["status"], code)
            self.assertIn(code, result["reasons"], code)
            self.assertIsNone(result["answer"], code)
            self.assertEqual(1, len(sender.requests), "코드 검사 실패면 검증 호출을 하지 않는다: " + code)

    def test_conditions_and_exceptions_are_filled_from_cited_evidence_not_from_the_model(self):
        altered = {**GOOD, "conditions": ["기업 고객"], "exceptions": []}
        result, sender = self.run_answer(altered, PASS)
        self.assertEqual("ANSWERED", result["status"])
        self.assertEqual((COND, EXC), (result["answer"]["conditions"], result["answer"]["exceptions"]), "확정 값은 근거의 구조화 값 그대로")
        self.assertEqual(COND, json.loads(sender.requests[1]["messages"][0]["content"])["draft_answer"]["conditions"], "검증 모델도 조립된 답변을 본다")
        self.assertIn("statements", result["_eval"]["draft"], "모델 원본은 평가 기록에 남는다")
        uncited, _ = self.run_answer({**GOOD, "citations": []}, PASS)
        self.assertIn("CITATION_MISSING", uncited["reasons"])

    def test_model_input_carries_product_display_name(self):
        _, sender = self.run_answer(GOOD, PASS)
        structured = json.loads(sender.requests[0]["messages"][0]["content"])["evidence"][0]["structured"]
        self.assertEqual(["KB 셀러론"], structured["applicable_product_names"])

    def test_llm_verifier_can_only_add_holds(self):
        for verdict, code in (({**PASS, "intent_match": False}, "LLM_INTENT_MISMATCH"), ({**PASS, "unsupported_claims": ["x"]}, "LLM_UNSUPPORTED_CLAIM"),
                              ({**PASS, "missing_conditions": ["y"]}, "LLM_MISSING_CONDITION"), ({**PASS, "decision_made": True}, "LLM_DECISION_MADE")):
            result, _ = self.run_answer(GOOD, verdict)
            self.assertEqual(("ANSWER_HOLD", [code]), (result["status"], result["reasons"]))

    def test_without_gateway_or_budget_answer_is_held_but_evidence_remains(self):
        result = answer("수수료율?", "2026-10-06", SEARCH_OK, None)
        self.assertEqual(["LLM_NOT_CONFIGURED"], result["reasons"])
        self.assertEqual(EVIDENCE, result["evidence"])
        unbudgeted = Gateway(self.ledger, ScriptedSender(GOOD), "m", "NONE", "dev", "test")
        held = answer("수수료율?", "2026-10-06", SEARCH_OK, unbudgeted)
        self.assertEqual(["LLM_BUDGET_NOT_APPROVED"], held["reasons"])

    def test_before_value_may_be_mentioned_as_previous_value(self):
        self.assertEqual([], deterministic_checks({"business_date": "2026-10-06", "evidence": [{
            "rule_version_id": FEE, "notice_id": "SIN-PREPAYMENT-FEE-V2", "evidence_text": EVIDENCE[0]["evidence"]["evidence_text"],
            "structured": {k: EVIDENCE[0]["structured_change"][k] for k in ("before_value", "after_value", "unit", "effective_on", "applicable_product_keys", "conditions", "exceptions")}}]}, GOOD))


    def test_generation_can_decline_decision_requests_and_hold_unanswerable_questions(self):
        decision, sender = self.run_answer({**GOOD, "request_type": "DECISION_REQUEST", "answerable": True})
        self.assertEqual(("DECISION_DECLINED", ["LLM_DECISION_REQUEST"]), (decision["status"], decision["reasons"]))
        self.assertIsNone(decision["answer"])
        self.assertEqual(1, len(sender.requests), "결정 요청 판정이면 검증 호출을 하지 않는다")
        unanswerable, sender = self.run_answer({**GOOD, "request_type": "RULE_QUESTION", "answerable": False, "explanation": "근거에 없음"})
        self.assertEqual(("ANSWER_HOLD", ["EVIDENCE_INSUFFICIENT"]), (unanswerable["status"], unanswerable["reasons"]))
        self.assertEqual(1, len(sender.requests))
        answered, sender = self.run_answer({**GOOD, "request_type": "RULE_QUESTION", "answerable": True}, PASS)
        self.assertEqual("ANSWERED", answered["status"])
        self.assertEqual(GOOD, answered["answer"], "요청 유형·답변 가능 여부는 직원 응답·검증 입력에 넣지 않는다")
        self.assertNotIn("request_type", json.loads(sender.requests[1]["messages"][0]["content"])["draft_answer"])

    def test_customer_specific_exemption_confirmation_is_blocked(self):
        draft = {**GOOD, "explanation": GOOD["explanation"] + " 이 고객은 수수료가 면제됩니다."}
        result, _ = self.run_answer(draft, PASS)
        self.assertIn("DECISION_ASSERTION", result["reasons"])
        conditional = {**GOOD, "explanation": GOOD["explanation"] + " 해당 고객이 별도 약정으로 면제가 확인된 경우 면제됩니다."}
        self.assertEqual("ANSWERED", self.run_answer(conditional, PASS)[0]["status"], "조건부 설명은 막지 않는다")

    def test_numbers_in_core_confirmed_notice_title_are_evidence(self):
        titled = [dict(EVIDENCE[0], notice_title="[합성] 셀러론 상담 준비 체크리스트 안내 v2")]
        draft = {**GOOD, "explanation": GOOD["explanation"] + " 근거는 셀러론 상담 준비 체크리스트 안내 v2입니다."}
        self.assertEqual("ANSWERED", self.run_answer(draft, PASS, search={**SEARCH_OK, "evidence": titled})[0]["status"])
        untitled, _ = self.run_answer(draft, PASS)
        self.assertIn("NUMBER_NOT_IN_EVIDENCE", untitled["reasons"], "제목이 근거에 없으면 그 숫자는 근거 밖이다")

    def test_business_date_must_not_be_presented_as_rule_date_in_any_notation(self):
        for text in (" 이 규정은 2026년 10월 6일에 적용됩니다.", " 이 규정은 2026-10-06부터 적용됩니다.", " 2026.10.6 기준입니다."):
            result, _ = self.run_answer({**GOOD, "explanation": GOOD["explanation"] + text}, PASS)
            self.assertIn("BUSINESS_DATE_AS_RULE_DATE", result["reasons"], text)
        korean_effective = {**GOOD, "explanation": GOOD["explanation"] + " 시행일은 2026년 10월 1일입니다."}
        self.assertEqual("ANSWERED", self.run_answer(korean_effective, PASS)[0]["status"])
        asked = {**GOOD, "needs_check": ["2026-10-06 기준으로 예정일이 지났나요?"]}
        self.assertEqual("ANSWERED", self.run_answer(asked, PASS)[0]["status"], "확인 질문의 업무일 언급은 허용")
        unknown = {**GOOD, "explanation": GOOD["explanation"] + " 2026년 11월 3일부터 바뀝니다."}
        self.assertIn("DATE_NOT_IN_EVIDENCE", self.run_answer(unknown, PASS)[0]["reasons"], "한글 날짜도 근거 밖 날짜 검사를 받는다")

    def test_date_rules_keep_evidence_dates_when_business_date_equals_effective_date(self):
        from ai_service.rag import deterministic_checks
        payload = {"question": "시행일 당일 상환도 0.8%인가요?", "business_date": "2026-10-01", "evidence": [{
            "rule_version_id": FEE, "notice_id": "SIN-PREPAYMENT-FEE-V2", "notice_title": None, "evidence_text": EVIDENCE[0]["evidence"]["evidence_text"],
            "structured": {k: EVIDENCE[0]["structured_change"][k] for k in ("before_value", "after_value", "unit", "effective_on", "applicable_product_keys", "conditions", "exceptions")}}]}
        same_day = {**GOOD, "explanation": "시행일은 2026년 10월 1일이며 이후 중도상환 건은 0.8퍼센트입니다. 조건과 예외를 확인합니다."}
        self.assertEqual([], deterministic_checks(payload, same_day, "2026-10-01"), "업무일과 시행일이 같아도 근거 날짜는 통과한다")
        other_day = {**payload, "business_date": "2026-10-06"}
        self.assertEqual([], deterministic_checks(other_day, same_day, "2026-10-06"))
        today_as_rule = {**GOOD, "explanation": GOOD["explanation"] + " 이 규정은 2026년 10월 6일에 적용됩니다."}
        self.assertIn("BUSINESS_DATE_AS_RULE_DATE", deterministic_checks(other_day, today_as_rule, "2026-10-06"))
        check_today = {**GOOD, "needs_check": ["오늘(2026-10-06) 기준 예정일이 지났나요?"]}
        self.assertEqual([], deterministic_checks(other_day, check_today, "2026-10-06"), "확인 질문의 업무일은 허용")
        asked = {**other_day, "question": "2026년 11월 2일 상환이면 0.8%인가요?"}
        echoed = {**GOOD, "explanation": GOOD["explanation"] + " 질문하신 2026-11-02 상환은 2026-10-01 이후입니다."}
        self.assertEqual([], deterministic_checks(asked, echoed, "2026-10-06"), "질문에 사용자가 쓴 날짜는 보존·허용")
        self.assertIn("DATE_NOT_IN_EVIDENCE", deterministic_checks(other_day, echoed, "2026-10-06"), "질문에 없던 날짜는 근거 밖")
        self.assertIn("NUMBER_NOT_IN_EVIDENCE", deterministic_checks(other_day, {**GOOD, "explanation": GOOD["explanation"] + " 10월 6일 기준입니다."}, "2026-10-06"),
                      "연도 없는 업무일 표기도 근거 밖 숫자로 막는다")

    def test_business_date_can_be_omitted_from_model_input_but_still_checked(self):
        import os
        os.environ["TRUST_AGENT_RAG_MODEL_BUSINESS_DATE"] = "omit"
        try:
            result, sender = self.run_answer({**GOOD, "explanation": GOOD["explanation"] + " 이 규정은 2026년 10월 6일에 적용됩니다."}, PASS)
        finally:
            os.environ.pop("TRUST_AGENT_RAG_MODEL_BUSINESS_DATE")
        sent = json.loads(sender.requests[0]["messages"][0]["content"])
        self.assertNotIn("business_date", sent)
        self.assertEqual("omit", result["model_business_date"])
        self.assertIn("BUSINESS_DATE_AS_RULE_DATE", result["reasons"], "모델이 몰라도 Core 업무일로 검사한다")
        included, sender = self.run_answer(GOOD, PASS)
        self.assertEqual("2026-10-06", json.loads(sender.requests[0]["messages"][0]["content"])["business_date"])

    def test_scope_broadening_is_blocked_by_code_and_verifier_but_correct_scope_passes(self):
        for text in (" 이 기준은 기업여신 상품에 적용됩니다.", " 모든 기업대출에 적용됩니다.", " KB 소상공인 신용대출 상품에도 적용됩니다."):
            result, _ = self.run_answer({**GOOD, "explanation": GOOD["explanation"] + text}, PASS)
            self.assertIn("SCOPE_BROADENED", result["reasons"], text)
        for text in (" KB 셀러론 상품에 적용됩니다.", " 셀러론 상품에 해당합니다.", " 기업 고객의 기업여신 상담 건에 적용됩니다."):
            self.assertEqual("ANSWERED", self.run_answer({**GOOD, "explanation": GOOD["explanation"] + text}, PASS)[0]["status"], text)
        held, _ = self.run_answer(GOOD, {**PASS, "scope_mismatch": True})
        self.assertEqual(("ANSWER_HOLD", ["LLM_SCOPE_MISMATCH"]), (held["status"], held["reasons"]))


    def test_uncovered_case_asserted_with_general_rule_is_held(self):
        held, _ = self.run_answer(GOOD, {**PASS, "uncovered_case_asserted": ["일부만 중도상환해도 같은 요율이 적용됩니다."]})
        self.assertEqual("ANSWER_HOLD", held["status"])
        self.assertIn("LLM_UNCOVERED_CASE", held["reasons"])

    def test_up_to_five_evidence_items_reach_the_model(self):
        many = [dict(EVIDENCE[0], rule_version_id="policy-rule:sha256:" + str(i) * 64) for i in range(1, 6)]
        result, sender = self.run_answer({**GOOD, "citations": [many[0]["rule_version_id"]]}, PASS, search={**SEARCH_OK, "evidence": many})
        self.assertEqual(5, len(json.loads(sender.requests[0]["messages"][0]["content"])["evidence"]))

    def test_call_log_timings_and_held_draft_are_recorded_for_evaluation_only(self):
        result, _ = self.run_answer(GOOD, PASS)
        self.assertEqual(["generate", "verify"], [log["stage"] for log in result["llm_call_log"]])
        self.assertTrue(all(log["ok"] and log["input_tokens"] == 900 for log in result["llm_call_log"]))
        self.assertTrue({"generate", "code_check", "verify", "rag_total"} <= set(result["timings_ms"]))
        bad = {**GOOD, "explanation": GOOD["explanation"] + " 최대 0.5%까지 감면됩니다."}
        held, _ = self.run_answer(bad, PASS)
        self.assertIsNone(held["answer"])
        self.assertEqual(as_statements(bad), held["_eval"]["draft"], "검증에서 막힌 초안(모델 원본)은 평가 전용 필드에만 남는다")
        self.assertEqual(["NUMBER_NOT_IN_EVIDENCE"], held["_eval"]["verification"]["code_checks"])

    def test_failed_call_is_logged_with_code(self):
        class Failing:
            def __call__(self, request):
                from ai_service.llm import LlmError
                raise LlmError("LLM_TIMEOUT", "timeout")
        result = answer("수수료율?", "2026-10-06", SEARCH_OK, self.gateway(Failing()))
        self.assertEqual(["LLM_TIMEOUT"], result["reasons"])
        self.assertEqual([{"stage": "generate", "ok": False, "code": "LLM_TIMEOUT"}],
                         [{k: log[k] for k in ("stage", "ok", "code")} for log in result["llm_call_log"]])
        self.assertEqual(0, result["llm_calls"], "성공한 호출만 llm_calls로 센다(실패 호출의 예약액은 원장이 확정한다)")

    def test_eval_trace_is_local_only_and_stripped_from_response(self):
        from ai_service import eval_trace
        from ai_service.config import SettingsError, check_startup
        self.assertIsNone(eval_trace.trace_dir({"TRUST_AGENT_EVAL_TRACE_DIR": self.dir.name}))
        self.assertIsNone(eval_trace.trace_dir({"TRUST_AGENT_EVAL_TRACE_DIR": self.dir.name, "TRUST_AGENT_AI_DEPLOYMENT": "production"}))
        local = eval_trace.trace_dir({"TRUST_AGENT_EVAL_TRACE_DIR": self.dir.name, "TRUST_AGENT_AI_DEPLOYMENT": "local"})
        self.assertEqual(Path(self.dir.name), local)
        with self.assertRaises(SettingsError):
            check_startup({"TRUST_AGENT_AI_INBOUND_TOKEN": "t", "TRUST_AGENT_EVAL_TRACE_DIR": self.dir.name})
        result, _ = self.run_answer({**GOOD, "explanation": GOOD["explanation"] + " 최대 0.5%까지 감면됩니다."}, PASS)
        entry = eval_trace.record("trace-1", "중도상환수수료 지금 얼마예요?", "SIN-PREPAYMENT-FEE", SEARCH_OK, result, 12)
        eval_trace.write(local, entry)
        line = json.loads((local / eval_trace.TRACE_FILE).read_text(encoding="utf-8").splitlines()[0])
        self.assertEqual("trace-1", line["trace_id"])
        self.assertNotIn("중도상환수수료 지금", json.dumps(line, ensure_ascii=False), "질문 원문은 남기지 않는다")
        self.assertIn("0.5%", json.dumps(line["draft"]["statements"], ensure_ascii=False))
        self.assertFalse(line["answer_released"])
        self.assertNotIn("_eval", eval_trace.strip(result))


if __name__ == "__main__":
    unittest.main()
