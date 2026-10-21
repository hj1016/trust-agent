"""문장 단위 근거 대조: 인용구 실재, 근거 없는 결과·순서·전체 범위·이유, 근거 없는 변경 이력. 유형별로 막을 사례와 통과할 대조 사례."""
from __future__ import annotations

import unittest

import tests.ai_service.support  # noqa: F401
from ai_service import grounding, llm, product_scope

FEE = "policy-rule:sha256:" + "a" * 64
DATE = "policy-rule:sha256:" + "b" * 64
SETTLE = "policy-rule:sha256:" + "c" * 64
EVIDENCE = [
    {"rule_version_id": FEE, "notice_id": "N2", "notice_title": "[합성] 기업여신 중도상환수수료 기준 변경 안내 v2",
     "evidence_text": "기업여신 상담 시 변경된 중도상환수수료율 0.8퍼센트와 적용 조건을 원문 근거에서 확인한다.",
     "structured": {"after_value": "0.8", "before_value": "1.2", "applicable_product_keys": ["kb-seller-loan"], "applicable_product_names": ["KB 셀러론"],
                    "conditions": ["기업 고객의 기업여신 상담 건"], "exceptions": ["별도 약정으로 수수료 면제가 확인된 경우"]}},
    {"rule_version_id": DATE, "notice_id": "N2", "notice_title": None,
     "evidence_text": "변경 기준 적용 여부를 판단하기 위해 고객 약정일과 중도상환 예정일을 확인한다.", "structured": {}},
    {"rule_version_id": SETTLE, "notice_id": "S2", "notice_title": None,
     "evidence_text": "매출 정산 내역 확인 여부를 상담 준비 체크리스트에 기록한다.", "structured": {}},
]
NAMES = {FEE: ["KB 셀러론"]}


def st(text, source=SETTLE, quote="매출 정산 내역 확인 여부를 상담 준비 체크리스트에 기록한다"):
    return {"text": text, "source": source, "quote": quote}


def check(*statements, version=None):
    return grounding.check_statements(statements, EVIDENCE, version, NAMES)


class GroundingTest(unittest.TestCase):
    def test_quote_must_exist_in_the_cited_source(self):
        self.assertEqual([], check(st("정산 내역 확인 여부는 상담 준비 체크리스트에 기록합니다.")))
        self.assertIn("UNGROUNDED_STATEMENT", check(st("정산 자료는 3년 보관합니다.", quote="정산 자료는 3년 보관")))
        self.assertIn("UNGROUNDED_STATEMENT", check(st("정산 내역을 기록합니다.", source="policy-rule:sha256:" + "f" * 64, quote="정산 내역은 5년 보관한다")),
                      "입력에 없는 근거 ID와 원문에 없는 인용구")
        self.assertEqual([], check(st("KB 셀러론 상품에 적용됩니다.", source=FEE, quote="KB 셀러론")), "상품 이름도 인용 가능한 근거 값")

    def test_structured_field_names_in_quote_are_normalized_but_values_must_exist(self):
        self.assertEqual([], check(st("별도 약정으로 면제가 확인된 경우는 예외입니다.", source=FEE, quote='exceptions: ["별도 약정으로 수수료 면제가 확인된 경우"]')))
        self.assertEqual([], check(st("적용 상품은 KB 셀러론입니다.", source=FEE, quote='"applicable_product_names": ["KB 셀러론"]')))
        self.assertEqual([], check(st("변경 전 1.2, 변경 후 0.8입니다.", source=FEE, quote='after_value: "0.8", before_value: "1.2"')))
        self.assertIn("UNGROUNDED_STATEMENT", check(st("2027년부터 적용됩니다.", source=FEE, quote='effective_on: "2027-01-01"')), "필드 이름은 걷어도 값은 근거에 있어야 한다")
        self.assertIn("UNGROUNDED_STATEMENT", check(st("두 값입니다.", source=FEE, quote='after_value: "0.8", before_value: "1.5"')), "값 조각 하나라도 없으면 막는다")
        self.assertEqual([], check(st("0.8퍼센트, 기업 고객 상담 건입니다.", source=FEE, quote="0.8, 기업 고객의 기업여신 상담 건")))
        self.assertIn("UNGROUNDED_STATEMENT", check(st("0.8퍼센트, 개인 고객입니다.", source=FEE, quote="0.8, 개인 고객 상담 건")))

    def test_quote_from_another_provided_input_is_grounded_but_invented_quote_is_not(self):
        self.assertEqual([], check(st("정산 내역 확인 여부를 기록합니다.", source=FEE)), "출처 표기만 다른 실제 원문 조각")
        self.assertIn("UNGROUNDED_STATEMENT", check(st("이 절차는 기존에 없었던 것으로 추가되었습니다.", source=grounding.VERSION_SOURCE,
                                                        quote="이 절차는 기존에 없었던 것으로 추가되었습니다")))

    def test_invented_consequence_order_scope_and_reason_are_blocked(self):
        for text in ("정산 내역을 기록하지 않으면 처리가 불가능합니다.", "자동 검증이 완료된 후 기록합니다.",
                     "이 기록 의무는 모든 상황에 적용됩니다.", "책임을 명확히 하기 위함입니다.", "특정 상품군에 한정된 조건은 없습니다."):
            with self.subTest(text=text):
                self.assertIn("UNSUPPORTED_CLAIM_TYPE", check(st(text)))

    def test_contrast_cases_with_support_or_negation_pass(self):
        supported_reason = st("변경 기준 적용 여부를 판단하기 위해 약정일과 예정일을 확인합니다.", source=DATE,
                              quote="변경 기준 적용 여부를 판단하기 위해 고객 약정일과 중도상환 예정일을 확인한다")
        self.assertEqual([], check(supported_reason), "원문에 있는 목적 표현은 허용")
        self.assertEqual([], check(st("이 기준이 모든 상황에 적용되는 것은 아닙니다.")), "부정문은 범위 확대가 아니다")
        self.assertEqual([], check(st("정산 내역 확인 여부를 체크리스트에 기록합니다.")))

    def test_version_claims_need_core_comparison(self):
        added = st("정산 내역 확인 항목이 새로 추가되었습니다.")
        self.assertIn("VERSION_CLAIM_UNSUPPORTED", check(added), "비교 자료 없이 추가됐다고 말할 수 없다")
        comparison = {"available": True, "changes": [{"rule_key": "CHECK_SETTLEMENT_EVIDENCE", "change_type": "ADDED", "before_rule_text": None,
                                                      "after_rule_text": "매출 정산 내역 확인 여부를 상담 준비 체크리스트에 기록한다.", "before": None, "after": None}]}
        self.assertEqual([], check(added, version=comparison))
        removed = st("담당자 검토 항목은 삭제되었습니다.")
        self.assertIn("VERSION_CLAIM_UNSUPPORTED", check(removed, version=comparison), "추가만 있는 비교로 삭제를 말할 수 없다")
        version_statement = {"text": "이전 공문 대비 정산 확인 항목이 추가되었습니다.", "source": grounding.VERSION_SOURCE, "quote": "CHECK_SETTLEMENT_EVIDENCE ADDED"}
        self.assertEqual([], check(version_statement, version=comparison))
        unavailable = {**comparison, "available": False}
        self.assertIn("VERSION_CLAIM_UNSUPPORTED", check(added, version=unavailable))

    def test_previous_rule_applicability_from_version_history_is_blocked(self):
        comparison = {"available": True, "previous_notice_title": "[합성] v1", "changes": [
            {"rule_key": "CHECK_PREPAYMENT_FEE_RATE", "change_type": "MODIFIED", "before_rule_text": "1.2퍼센트 확인", "after_rule_text": "0.8퍼센트 확인",
             "before": {"before_value": "1.2", "effective_on": "2026-09-15"}, "after": {"after_value": "0.8"}}]}
        applied = {"text": "10월 1일 이전 상환 건은 이전 요율 1.2%가 적용됩니다.", "source": grounding.VERSION_SOURCE, "quote": "before_value: \"1.2\""}
        self.assertIn("VERSION_APPLICABILITY_UNSUPPORTED", check(applied, version=comparison))
        history = {"text": "이전 공문 대비 요율이 1.2에서 0.8로 바뀌었습니다.", "source": grounding.VERSION_SOURCE, "quote": "before_value: \"1.2\""}
        self.assertNotIn("VERSION_APPLICABILITY_UNSUPPORTED", check(history, version=comparison), "변경 사실 설명은 허용")
        current = {"text": "변경된 수수료율은 2026-10-01부터 적용됩니다.", "source": grounding.VERSION_SOURCE, "quote": "after_value: \"0.8\""}
        self.assertNotIn("VERSION_APPLICABILITY_UNSUPPORTED", check(current, version=comparison), "현재 값의 시행 설명은 허용")

    def test_reason_with_same_meaning_ending_and_multiple_sources(self):
        reason = st("변경 기준 적용 여부를 판단하기 위해서는 약정일과 예정일을 확인해야 합니다.", source=DATE,
                    quote="변경 기준 적용 여부를 판단하기 위해 고객 약정일과 중도상환 예정일을 확인한다")
        self.assertEqual([], check(reason), "원문 '위해'와 같은 뜻의 '위해서'")
        both = st("약정일을 확인하고 정산 내역을 기록합니다.", source=f"{DATE}, {SETTLE}",
                  quote="고객 약정일과 중도상환 예정일을 확인한다. 매출 정산 내역 확인 여부를 상담 준비 체크리스트에 기록한다")
        self.assertNotIn("UNGROUNDED_STATEMENT", check(both), "여러 출처는 합쳐 대조")

    def test_not_confirmable_statement_without_source(self):
        ok = {"text": "요청하신 v3의 내용은 현재 제공된 승인 근거로 확인할 수 없습니다.", "source": grounding.NONE_SOURCE, "quote": ""}
        self.assertEqual([], check(ok))
        with_value = {"text": "v3 요율은 확인할 수 없지만 0.8%로 보입니다.", "source": grounding.NONE_SOURCE, "quote": ""}
        self.assertIn("UNGROUNDED_STATEMENT", check(with_value), "출처 없는 문장에 수치를 넣으면 막는다")
        claim = {"text": "다른 상품에도 적용됩니다.", "source": grounding.NONE_SOURCE, "quote": ""}
        self.assertIn("UNGROUNDED_STATEMENT", check(claim), "확인 불가 표현 없는 출처 없는 문장")

    def test_version_changes_payload_from_core_comparison(self):
        core = {"kind": "CHANGED", "previousNoticeId": "N1", "previousTitle": "[합성] v1", "available": True, "unavailableReason": None,
                "changes": [{"ruleKey": "CHECK_PREPAYMENT_FEE_RATE", "changeType": "MODIFIED", "beforeInstruction": "1.2퍼센트 확인", "afterInstruction": "0.8퍼센트 확인",
                             "beforeStructuredChange": {"after_value": "1.2", "field_key": "x"}, "afterStructuredChange": {"after_value": "0.8"}}]}
        payload = llm.version_changes_payload(core)
        self.assertTrue(payload["available"])
        self.assertEqual({"after_value": "1.2"}, payload["changes"][0]["before"], "허용 필드만 옮긴다")
        self.assertFalse(llm.version_changes_payload({**core, "kind": "NEW", "available": True})["available"], "이전 version이 없으면 비교 불가")

    def test_scope_check_does_not_flag_negated_scope(self):
        self.assertEqual([], product_scope.scope_violations("KB 셀러론 상품에만 적용되며, 다른 기업대출 상품에는 적용되지 않습니다.", ["kb-seller-loan"]))
        self.assertTrue(product_scope.scope_violations("다른 기업대출 상품에도 적용됩니다.", ["kb-seller-loan"]))
        self.assertEqual([], product_scope.scope_violations("모든 기업대출에 적용되는 것은 아닙니다.", ["kb-seller-loan"]))


if __name__ == "__main__":
    unittest.main()
