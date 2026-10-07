"""TASK-014 검색 골든셋 계약 테스트.

AC-01 파일 분리와 schema, AC-02 규칙 version ID 실재, AC-03 최종 평가용 필수 범주, AC-03a 초기 점검용 10건 일치,
AC-04 범주별 최소 수와 변형·중복 규칙, AC-07 합성 표시, AC-08 평가 고정 조건과 자료 fingerprint, AC-09 expected_fields 실재,
AC-10 안전성 파일 참조를 검증한다. 검색 구현과 관련성 보류 기준값은 이 테스트의 대상이 아니다(TASK-016).
"""
import copy
import hashlib
import json
import re
import unittest
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker, ValidationError


ROOT = Path(__file__).resolve().parents[2]
GOLDENSET_ROOT = ROOT / "datasets/synthetic/search-goldenset"
NOTICE_ROOT = ROOT / "datasets/synthetic/internal/notices"
SMOKE = GOLDENSET_ROOT / "prepayment-fee-smoke-v1.json"
EVAL = GOLDENSET_ROOT / "prepayment-fee-eval-v1.json"
SAFETY = GOLDENSET_ROOT / "prepayment-fee-safety-v1.json"
SCHEMA = "search-goldenset.schema.json"

PREPAYMENT = "SIN-PREPAYMENT-FEE"
SELLER = "SIN-SELLER-CHECKLIST"
HOLD_CATEGORIES = {"other_family", "unapproved", "decision_forbidden", "hold", "fixture_period"}
ALL_CATEGORIES = {"direct", "paraphrase", "numeric", "old_value_apply", "current_value"} | HOLD_CATEGORIES

# TASK-014 계획 "범주별 최소 수 합산" 표(확정, 결정자 사용자). 변형과 반대 사례는 범주 안에서 센다.
EVAL_CATEGORY_MINIMUM = {
    "direct": 2, "paraphrase": 4, "numeric": 4, "current_value": 3, "old_value_apply": 3,
    "other_family": 2, "unapproved": 2, "decision_forbidden": 2, "hold": 2, "fixture_period": 2,
}
EVAL_MIN_TOTAL, EVAL_MAX_TOTAL = 26, 30
EVAL_MIN_VARIANTS = 2

# TASK-014 계획의 초기 점검용 10건 표. 규칙은 (family, rule_key, notice_id)로 적고 테스트가 ID로 바꾼다.
FEE_V1 = (PREPAYMENT, "CHECK_PREPAYMENT_FEE_RATE", "SIN-PREPAYMENT-FEE-V1")
FEE_V2 = (PREPAYMENT, "CHECK_PREPAYMENT_FEE_RATE", "SIN-PREPAYMENT-FEE-V2")
SOURCE = (PREPAYMENT, "CHECK_NOTICE_SOURCE", "SIN-PREPAYMENT-FEE-V2")
CONTRACT = (PREPAYMENT, "CHECK_CUSTOMER_CONTRACT_DATE", "SIN-PREPAYMENT-FEE-V2")
LIMIT = (SELLER, "CHECK_CORPORATE_LIMIT_SOURCE", "SIN-SELLER-CHECKLIST-V2")
SETTLEMENT = (SELLER, "CHECK_SETTLEMENT_EVIDENCE", "SIN-SELLER-CHECKLIST-V2")
HUMAN = (SELLER, "CHECK_HUMAN_REVIEW", "SIN-SELLER-CHECKLIST-V2")
ALL_RULES = [FEE_V1, FEE_V2, SOURCE, CONTRACT, LIMIT, SETTLEMENT, HUMAN]
SELLER_RULES = [LIMIT, SETTLEMENT, HUMAN]
SMOKE_PLAN = {
    # id: (family, category, relevant(필수), supporting(보조), must_not, hold, human). 정답지 대조 보완 반영(S05 공문 출처 추가, S07 약정일 보조, S08 전체 7개 제외)
    "S01": (PREPAYMENT, "current_value", [FEE_V2], [], [FEE_V1], False, False),
    "S02": (PREPAYMENT, "numeric", [FEE_V2], [], [FEE_V1], False, False),
    "S03": (PREPAYMENT, "paraphrase", [FEE_V2], [], [], False, False),
    "S04": (PREPAYMENT, "direct", [CONTRACT], [], [], False, False),
    "S05": (PREPAYMENT, "numeric", [FEE_V2, CONTRACT, SOURCE], [], [FEE_V1], False, False),
    "S06": (PREPAYMENT, "direct", [SOURCE], [], [], False, False),
    "S07": (PREPAYMENT, "old_value_apply", [FEE_V2], [CONTRACT], [FEE_V1], False, True),
    "S08": (PREPAYMENT, "other_family", [], [], ALL_RULES, True, False),
    "S09": (SELLER, "unapproved", [], [], SELLER_RULES, True, False),
    "S10": (PREPAYMENT, "decision_forbidden", [], [], ALL_RULES, True, False),
}
EXCLUSION_CODES = {"OUT_OF_SCOPE", "UNAPPROVED", "OLD_VERSION", "IRRELEVANT", "NOT_IN_EFFECT"}


def load(path: Path):
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def validate(record, schema_name=SCHEMA):
    schema = load(ROOT / "contracts" / schema_name)
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(record)


def canonical_json(value):
    def reject_floating_point(item):
        if isinstance(item, float):
            raise ValueError("canonical JSON은 부동소수점을 허용하지 않습니다.")
        if isinstance(item, dict):
            for child in item.values():
                reject_floating_point(child)
        if isinstance(item, list):
            for child in item:
                reject_floating_point(child)

    reject_floating_point(value)
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256(value):
    return "sha256:" + hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def rule_index():
    """합성 공문에서 규칙 version ID를 다시 계산한다(SyntheticInternalImporter와 같은 canonical sha256)."""
    by_id = {}
    by_key = {}
    for path in sorted(NOTICE_ROOT.glob("*.json")):
        notice = load(path)
        for rule in notice["rules"]:
            rule_id = "policy-rule:" + sha256(rule)
            by_id.setdefault(rule_id, {"family_id": notice["family_id"], "rule_key": rule["rule_key"], "notices": [], "rule": rule})
            by_id[rule_id]["notices"].append(notice["notice_id"])
            by_key[(notice["family_id"], rule["rule_key"], notice["notice_id"])] = rule_id
    return by_id, by_key


def parse_instant(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00")).astimezone(timezone.utc)


def normalized(text):
    return re.sub(r"[\s.,?!%]", "", text)


class SearchGoldensetContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.smoke = load(SMOKE)
        cls.eval = load(EVAL)
        cls.safety = load(SAFETY)
        cls.rules_by_id, cls.rules_by_key = rule_index()

    # ---- AC-01 ----
    def test_files_are_separated_pass_schema_and_ids_do_not_overlap(self):
        validate(self.smoke)
        validate(self.eval)
        validate(self.safety)
        self.assertEqual("smoke", self.smoke["kind"])
        self.assertEqual("eval", self.eval["kind"])
        self.assertEqual("safety", self.safety["kind"])
        self.assertEqual(10, len(self.smoke["queries"]))
        self.assertGreaterEqual(len(self.eval["queries"]), EVAL_MIN_TOTAL)
        self.assertLessEqual(len(self.eval["queries"]), EVAL_MAX_TOTAL)
        smoke_ids = [q["query_id"] for q in self.smoke["queries"]]
        eval_ids = [q["query_id"] for q in self.eval["queries"]]
        self.assertEqual(len(smoke_ids), len(set(smoke_ids)))
        self.assertEqual(len(eval_ids), len(set(eval_ids)))
        self.assertEqual(set(), set(smoke_ids) & set(eval_ids))
        # 초기 점검용 질의 본문은 최종 평가용에 들어가지 않는다.
        smoke_texts = {normalized(q["query"]) for q in self.smoke["queries"]}
        for query in self.eval["queries"]:
            self.assertNotIn(normalized(query["query"]), smoke_texts, query["query_id"])

    # ---- AC-02 ----
    def test_every_rule_version_id_exists_in_synthetic_notices(self):
        for goldenset in (self.smoke, self.eval):
            for query in goldenset["queries"]:
                relevant, supporting, must_not = set(query["relevant"]), set(query["supporting"]), set(query["must_not"])
                for rule_id in relevant | supporting | must_not:
                    self.assertIn(rule_id, self.rules_by_id, f"{query['query_id']}: {rule_id}")
                # 필수·보조·제외는 서로 겹치지 않는다.
                self.assertEqual(set(), relevant & supporting, query["query_id"])
                self.assertEqual(set(), relevant & must_not, query["query_id"])
                self.assertEqual(set(), supporting & must_not, query["query_id"])
                # 필수·보조 근거는 요청 범위 안의 규칙이어야 한다.
                for rule_id in relevant | supporting:
                    self.assertEqual(query["family_id"], self.rules_by_id[rule_id]["family_id"], query["query_id"])
                # 보조 근거에는 선정 이유가 있고, 없으면 이유도 없다.
                self.assertEqual(bool(supporting), "supporting_reason" in query, query["query_id"])
                # 제외 이유의 합집합이 must_not과 같고 코드끼리 겹치지 않는다.
                reasons = query["exclusion_reasons"]
                self.assertTrue(set(reasons) <= EXCLUSION_CODES, query["query_id"])
                listed = [rule_id for ids in reasons.values() for rule_id in ids]
                self.assertEqual(len(listed), len(set(listed)), query["query_id"])
                self.assertEqual(must_not, set(listed), query["query_id"])
                for code, ids in reasons.items():
                    for rule_id in ids:
                        family = self.rules_by_id[rule_id]["family_id"]
                        if code == "OUT_OF_SCOPE":
                            self.assertNotEqual(query["family_id"], family, f"{query['query_id']}: {code}")
                        else:
                            self.assertEqual(query["family_id"], family, f"{query['query_id']}: {code} {rule_id}")
        # 계획 표의 앞 8자가 실제 계산값과 같다.
        expected_prefixes = {
            FEE_V1: "82f74fb1", FEE_V2: "0827aed5", SOURCE: "a72302da", CONTRACT: "6a0041a7",
            LIMIT: "205820d6", SETTLEMENT: "f6f8bc98", HUMAN: "daedcfc1",
        }
        for key, prefix in expected_prefixes.items():
            self.assertTrue(self.rules_by_key[key].startswith("policy-rule:sha256:" + prefix), key)

    # ---- AC-03 (최종 평가용에만) ----
    def test_eval_set_covers_required_exclusions_holds_and_intents(self):
        queries = self.eval["queries"]
        fee_v1 = self.rules_by_key[FEE_V1]
        fee_v2 = self.rules_by_key[FEE_V2]
        seller_ids = {self.rules_by_key[key] for key in SELLER_RULES}
        self.assertTrue(any(fee_v1 in q["must_not"] for q in queries), "구버전 수수료율이 must_not에 1건 이상")
        self.assertTrue(any(q["family_id"] == PREPAYMENT and seller_ids & set(q["must_not"]) for q in queries), "요청 범위 밖 공문군 제외 1건 이상")
        self.assertTrue(any(q["category"] == "unapproved" and q["family_id"] == SELLER and seller_ids <= set(q["must_not"]) for q in queries), "범위 안 미승인 규칙 제외 1건 이상")
        for category in HOLD_CATEGORIES:
            self.assertTrue(any(q["category"] == category and q["expected_evidence_hold"] for q in queries), category)
        for query in queries:
            if query["category"] == "old_value_apply":
                self.assertFalse(query["expected_evidence_hold"], query["query_id"])
                self.assertTrue(query["requires_human_decision"], query["query_id"])
                self.assertIn(fee_v2, query["relevant"], query["query_id"])
                self.assertIn(fee_v1, query["must_not"], query["query_id"])
            if query["category"] == "current_value":
                self.assertIn(fee_v2, query["relevant"], query["query_id"])
                self.assertNotIn(fee_v1, query["relevant"], query["query_id"])
            self.assertIn(query["category"], ALL_CATEGORIES, query["query_id"])
            # 역사적 비교 조회와 과거 knownAt 조회는 범위 밖이다.
            self.assertNotRegex(query["query"], r"전에는|예전에는.*지금은|knownAt|인지 시각", query["query_id"])
        # FIXTURE 기간 질의는 구버전 FIXTURE 근거도 제공하지 않는다.
        for query in queries:
            if query["category"] == "fixture_period":
                self.assertIn(fee_v1, query["must_not"], query["query_id"])

    # ---- AC-03a (초기 점검용 10건 유지) ----
    def test_smoke_set_matches_the_approved_plan_table(self):
        by_id = {q["query_id"]: q for q in self.smoke["queries"]}
        self.assertEqual(set(SMOKE_PLAN), set(by_id))
        for query_id, (family, category, relevant, supporting, must_not, hold, human) in SMOKE_PLAN.items():
            query = by_id[query_id]
            with self.subTest(query_id=query_id):
                self.assertEqual(family, query["family_id"])
                self.assertEqual(category, query["category"])
                self.assertEqual({self.rules_by_key[key] for key in relevant}, set(query["relevant"]))
                self.assertEqual({self.rules_by_key[key] for key in supporting}, set(query["supporting"]))
                self.assertEqual({self.rules_by_key[key] for key in must_not}, set(query["must_not"]))
                self.assertEqual(hold, query["expected_evidence_hold"])
                self.assertEqual(human, query["requires_human_decision"])
        self.assertEqual(8, len({q["category"] for q in self.smoke["queries"]}))
        self.assertEqual(set(), {"hold", "fixture_period"} & {q["category"] for q in self.smoke["queries"]})

    # ---- AC-04 ----
    def test_eval_set_meets_category_minimums_variants_and_no_plain_repeats(self):
        queries = self.eval["queries"]
        by_id = {q["query_id"]: q for q in queries}
        counts = Counter(q["category"] for q in queries)
        for category, minimum in EVAL_CATEGORY_MINIMUM.items():
            self.assertGreaterEqual(counts[category], minimum, category)
        self.assertEqual(sum(EVAL_CATEGORY_MINIMUM.values()), EVAL_MIN_TOTAL)
        # 반대 사례: 셀러론을 요청 범위로 둔 other_family 1건 이상(중도상환수수료 규칙이 제외돼야 함).
        prepayment_ids = {rule_id for rule_id, info in self.rules_by_id.items() if info["family_id"] == PREPAYMENT}
        self.assertTrue(any(q["category"] == "other_family" and q["family_id"] == SELLER and prepayment_ids <= set(q["must_not"]) for q in queries))
        # 의도적 변형: variant_of가 같은 파일의 질의를 가리키고 범주가 같으며 정답 구성이 같고 검증 목적이 있다.
        variants = [q for q in queries if "variant_of" in q]
        self.assertGreaterEqual(len(variants), EVAL_MIN_VARIANTS)
        for variant in variants:
            origin = by_id[variant["variant_of"]]
            self.assertNotIn("variant_of", origin, variant["query_id"])
            self.assertEqual(origin["category"], variant["category"], variant["query_id"])
            self.assertEqual(set(origin["relevant"]), set(variant["relevant"]), variant["query_id"])
            self.assertEqual(set(origin["supporting"]), set(variant["supporting"]), variant["query_id"])
            self.assertEqual(set(origin["must_not"]), set(variant["must_not"]), variant["query_id"])
            self.assertEqual(origin["expected_evidence_hold"], variant["expected_evidence_hold"], variant["query_id"])
            self.assertTrue(variant["variant_purpose"].strip(), variant["query_id"])
            # 띄어쓰기 변형은 정규화하면 원본과 같아지므로 원문 그대로 비교한다.
            self.assertNotEqual(origin["query"], variant["query"], variant["query_id"])
        # 단순 반복 금지: 변형이 아닌 질의끼리 질문 본문이 같으면 안 되고, 정답 구성이 같은 쌍은 서로 다른 이유를 적어야 한다.
        plain = [q for q in queries if "variant_of" not in q]
        texts = [normalized(q["query"]) for q in plain]
        self.assertEqual(len(texts), len(set(texts)))
        signature = lambda q: (q["category"], tuple(sorted(q["relevant"])), tuple(sorted(q["supporting"])), tuple(sorted(q["must_not"])), q["expected_evidence_hold"], q["requires_human_decision"])
        groups = {}
        for query in plain:
            groups.setdefault(signature(query), []).append(query)
        for members in groups.values():
            rationales = [m["rationale"] for m in members]
            self.assertEqual(len(rationales), len(set(rationales)), [m["query_id"] for m in members])

    # ---- AC-07 ----
    def test_goldensets_are_marked_synthetic_and_contain_no_real_person_or_customer_data(self):
        for record in (self.smoke, self.eval, self.safety):
            self.assertEqual("SYNTHETIC_WORK", record["dataset_class"])
            self.assertTrue(record["synthetic"])
            self.assertIn("합성", record["disclaimer"])
            self.assertIn("실제", record["disclaimer"])
        for goldenset in (self.smoke, self.eval):
            for query in goldenset["queries"]:
                text = query["query"] + " " + query["rationale"]
                self.assertNotRegex(text, r"\d{3}-\d{2}-\d{5}|\d{6}-\d{7}|@|SW-COMPANY|SW-APPLICATION", query["query_id"])

    # ---- AC-08 ----
    def test_evaluation_context_is_fixed_and_fingerprint_matches_current_data(self):
        for goldenset in (self.smoke, self.eval):
            context = goldenset["evaluation_context"]
            evaluated_at = parse_instant(context["evaluated_at"])
            self.assertEqual(date.fromisoformat(context["evaluated_business_date"]), date(2026, 10, 6))
            for query in goldenset["queries"]:
                self.assertLessEqual(date.fromisoformat(query["business_date"]), date.fromisoformat(context["evaluated_business_date"]), query["query_id"])
            approved = context["approval_state"]["approved"]
            self.assertEqual(["SIN-PREPAYMENT-FEE-V2"], [a["notice_id"] for a in approved])
            for approval in approved:
                self.assertLess(parse_instant(approval["decided_at"]), evaluated_at)
            self.assertEqual(["SIN-SELLER-CHECKLIST-V2"], [p["notice_id"] for p in context["approval_state"]["pending_review"]])
            self.assertEqual([], context["approval_state"]["withdrawn_notices"])
            self.assertEqual([], context["approval_state"]["rejected_proposals"])
            self.assertFalse(context["recheck_basis"]["known_at_supported"])
            files = context["dataset_fingerprint"]["files"]
            expected_files = sorted(
                [p.relative_to(ROOT).as_posix() for p in (ROOT / "datasets/synthetic/internal").rglob("*.json")]
                + [p.relative_to(ROOT).as_posix() for p in (ROOT / "datasets/derived/synthetic-internal").rglob("*.json")]
            )
            self.assertEqual(expected_files, sorted(files))
            for relative, digest in files.items():
                self.assertEqual(sha256(load(ROOT / relative)), digest, relative)
            fixture_ids = {load(p)["approved_checklist_version_id"] for p in (ROOT / "datasets/synthetic/internal/approved-checklists").glob("*.approved-checklist.json")}
            self.assertEqual(fixture_ids, set(context["fixture_checklists"]))
        self.assertEqual(self.smoke["evaluation_context"], self.eval["evaluation_context"])

    # ---- AC-09 ----
    def test_expected_fields_exist_in_the_rule_structured_change(self):
        for goldenset in (self.smoke, self.eval):
            for query in goldenset["queries"]:
                for expected in query["expected_fields"]:
                    with self.subTest(query_id=query["query_id"], field=expected["field"]):
                        self.assertIn(expected["rule_version_id"], query["relevant"])
                        change = self.rules_by_id[expected["rule_version_id"]]["rule"]["structured_change"]
                        self.assertIsNotNone(change, "문장만 있는 규칙에는 기대 필드를 둘 수 없다")
                        self.assertIn(expected["field"], change)
                        self.assertEqual(change[expected["field"]], expected["expected_value"])
                # 구조화 값이 없는 규칙만 정답인 질의는 기대 필드가 비어 있다.
                if query["relevant"] and all(self.rules_by_id[r]["rule"]["structured_change"] is None for r in query["relevant"]):
                    self.assertEqual([], query["expected_fields"], query["query_id"])

    # ---- AC-10 ----
    def test_safety_cases_reference_decision_forbidden_queries_without_copying_text(self):
        sources = {
            self.smoke["goldenset_id"] + "-" + self.smoke["goldenset_version"]: {q["query_id"]: q for q in self.smoke["queries"]},
            self.eval["goldenset_id"] + "-" + self.eval["goldenset_version"]: {q["query_id"]: q for q in self.eval["queries"]},
        }
        referenced = set()
        for case in self.safety["cases"]:
            self.assertNotIn("query", case)
            self.assertIn(case["source_goldenset"], sources, case["case_id"])
            query = sources[case["source_goldenset"]].get(case["source_query_id"])
            self.assertIsNotNone(query, case["case_id"])
            self.assertEqual("decision_forbidden", query["category"], case["case_id"])
            referenced.add((case["source_goldenset"], case["source_query_id"]))
        decision_queries = {(name, qid) for name, queries in sources.items() for qid, q in queries.items() if q["category"] == "decision_forbidden"}
        self.assertEqual(decision_queries, referenced)
        self.assertEqual("TASK-015", self.safety["pass_criteria_owner"])
        self.assertEqual("TASK-019", self.safety["llm_output_verification_owner"])
        self.assertNotIn("evaluation_context", self.safety)

    # ---- 거부 사례: 계약이 잘못된 골든셋을 막는다 ----
    def test_schema_rejects_hold_query_with_relevant_and_old_value_query_without_human_decision(self):
        broken = copy.deepcopy(self.eval)
        hold = next(q for q in broken["queries"] if q["category"] == "hold")
        hold["relevant"] = [self.rules_by_key[FEE_V2]]
        with self.assertRaises(ValidationError):
            validate(broken)
        broken = copy.deepcopy(self.eval)
        old = next(q for q in broken["queries"] if q["category"] == "old_value_apply")
        old["requires_human_decision"] = False
        with self.assertRaises(ValidationError):
            validate(broken)
        broken = copy.deepcopy(self.eval)
        broken["queries"][0]["variant_of"] = "E99"
        with self.assertRaises(ValidationError):
            validate(broken)
        # 보조 근거만 있고 선정 이유가 없으면 거부
        broken = copy.deepcopy(self.eval)
        supported = next(q for q in broken["queries"] if q["supporting"])
        del supported["supporting_reason"]
        with self.assertRaises(ValidationError):
            validate(broken)
        # 보류 질의에 보조 근거가 있으면 거부
        broken = copy.deepcopy(self.eval)
        hold = next(q for q in broken["queries"] if q["category"] == "hold")
        hold["supporting"] = [self.rules_by_key[SOURCE]]
        hold["supporting_reason"] = "잘못된 예"
        with self.assertRaises(ValidationError):
            validate(broken)
        # 알 수 없는 제외 이유 코드는 거부
        broken = copy.deepcopy(self.eval)
        broken["queries"][0]["exclusion_reasons"]["UNKNOWN"] = []
        with self.assertRaises(ValidationError):
            validate(broken)
        broken = copy.deepcopy(self.safety)
        broken["pass_criteria_owner"] = "TASK-014"
        with self.assertRaises(ValidationError):
            validate(broken)


if __name__ == "__main__":
    unittest.main()
