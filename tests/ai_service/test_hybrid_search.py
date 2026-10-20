"""하이브리드 검색(BM25 + 로컬 e5 kNN, RRF 순위 결합). 실제 모델 없이 가짜 임베딩과 가짜 ES로 흐름·보류·계약을 확인한다(품질 평가 아님)."""
from __future__ import annotations

import json
import unittest
from datetime import datetime, timezone

from jsonschema import Draft202012Validator, FormatChecker

from tests.ai_service.support import PREPAYMENT, ROOT, FakeTransport, make_root, prepayment_usable, settings_for
from ai_service import config, embedding
from ai_service.search import RelevanceHold, SearchSettings, cosine_from_es, fuse, search

CLOCK = lambda: datetime(2026, 10, 6, 3, 0, tzinfo=timezone.utc)  # noqa: E731
MODEL = "intfloat/multilingual-e5-small@614241f622f5"


class SplitEs:
    """BM25 질의와 kNN 질의에 서로 다른 결과를 돌려주는 가짜 ES."""

    def __init__(self, bm25=None, knn=None) -> None:
        self.bm25 = bm25 or []
        self.knn = knn or []
        self.requests: list[dict] = []

    def search(self, alias, body):
        self.requests.append(body)
        hits = self.knn if "knn" in body else self.bm25
        return {"hits": {"hits": [{"_id": rid, "_score": score} for rid, score in hits]}}


def settings(mode="hybrid", gate="either", min_cosine="-1", hold=None, diagnostics=True) -> SearchSettings:
    return SearchSettings(index_alias="alias", hold=hold or RelevanceHold(), diagnostics=diagnostics, mode=mode, gate=gate, vector_min_cosine=min_cosine)


def fake_embed(query: str):
    return MODEL, [1.0] + [0.0] * 383


def es_cos(cos: float) -> float:
    return (1.0 + cos) / 2.0


class FuseTest(unittest.TestCase):
    def test_rrf_orders_by_both_ranks_and_constant_k(self):
        rows = fuse([("a", 9.0), ("b", 5.0)], [("b", es_cos(0.9)), ("c", es_cos(0.8))], settings())
        self.assertEqual(["b", "a", "c"], [row["rule_version_id"] for row in rows])
        b = next(row for row in rows if row["rule_version_id"] == "b")
        self.assertAlmostEqual(1 / 62 + 1 / 61, b["rrf"])
        self.assertAlmostEqual(0.9, b["cosine"])

    def test_relevance_gate_uses_hold_and_cosine_not_rrf(self):
        hold = RelevanceHold("absolute", "6", "0", "t")
        bm25 = [("a", 9.0), ("b", 5.0)]
        vector = [("b", es_cos(0.9)), ("c", es_cos(0.5))]
        either = {r["rule_version_id"]: r["keep"] for r in fuse(bm25, vector, settings(gate="either", min_cosine="0.85", hold=hold))}
        both = {r["rule_version_id"]: r["keep"] for r in fuse(bm25, vector, settings(gate="both", min_cosine="0.85", hold=hold))}
        self.assertEqual({"a": True, "b": True, "c": False}, either)
        self.assertEqual({"a": False, "b": False, "c": False}, both)

    def test_vector_mode_uses_cosine_only(self):
        rows = fuse([("a", 9.0)], [("b", es_cos(0.7)), ("c", es_cos(0.2))], settings(mode="vector", min_cosine="0.5"))
        self.assertEqual(["b", "c", "a"], [row["rule_version_id"] for row in rows])
        self.assertEqual([True, False, False], [row["keep"] for row in rows])

    def test_cosine_conversion(self):
        self.assertAlmostEqual(0.0, cosine_from_es(0.5))
        self.assertAlmostEqual(-1.0, cosine_from_es(0.0))


class HybridSearchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = make_root()
        self.app_settings = settings_for(self.root, record_token=None)
        self.checklist = prepayment_usable()
        self.ids = [item["sourceRuleVersionId"] for item in self.checklist["approvedChecklist"]["items"]]
        with (ROOT / "contracts/ai-search-response.schema.json").open(encoding="utf-8") as source:
            self.response_schema = Draft202012Validator(json.load(source), format_checker=FormatChecker())

    def run_search(self, es, search_settings, embed=fake_embed, transport=None):
        transport = transport or FakeTransport().usable(PREPAYMENT, self.checklist)
        return search("중도상환수수료 예외", PREPAYMENT, "2026-10-06", "consult-1", settings=self.app_settings, search_settings=search_settings,
                      es=es, transport=transport, clock=CLOCK, embed_query=embed), transport

    def test_hybrid_queries_bm25_and_knn_with_same_scope_and_model_filter(self):
        es = SplitEs(bm25=[(self.ids[0], 4.0)], knn=[(self.ids[1], es_cos(0.9)), (self.ids[0], es_cos(0.8))])
        result, _ = self.run_search(es, settings())
        self.assertEqual("search-hybrid-v1", result["search_version"])
        self.assertEqual("EVIDENCE", result["status"])
        self.assertEqual(2, len(es.requests))
        knn = next(body for body in es.requests if "knn" in body)
        filters = knn["knn"]["filter"]["bool"]["filter"]
        self.assertIn({"term": {"embedding_model": MODEL}}, filters)
        self.assertIn({"term": {"family_id": PREPAYMENT}}, filters)
        self.assertIn({"ids": {"values": sorted(self.ids)}}, filters)
        self.assertEqual(False, knn["_source"])
        self.assertEqual([], [e.message for e in self.response_schema.iter_errors(result)])
        self.assertEqual("hybrid", result["relevance_hold"]["mode"])
        self.assertIn("vector_cosine", result["diagnostics"]["raw_candidates"][0])

    def test_embedding_unavailable_is_hold_without_knn(self):
        def broken(_query):
            raise embedding.EmbeddingUnavailable("EMBEDDING_NOT_CONFIGURED", "x")
        es = SplitEs(bm25=[(self.ids[0], 4.0)])
        result, _ = self.run_search(es, settings(), embed=broken)
        self.assertEqual(("EVIDENCE_HOLD", "UNVERIFIED", ["EMBEDDING_UNAVAILABLE"]), (result["status"], result["hold_kind"], result["hold_reasons"]))
        self.assertFalse(any("knn" in body for body in es.requests))

    def test_missing_vectors_for_allowed_docs_is_index_mismatch_hold(self):
        result, _ = self.run_search(SplitEs(bm25=[(self.ids[0], 4.0)], knn=[]), settings())
        self.assertEqual(["EMBEDDING_INDEX_MISMATCH"], result["hold_reasons"])
        self.assertEqual([], result["evidence"])

    def test_vector_threshold_hold_returns_no_candidate(self):
        es = SplitEs(knn=[(self.ids[0], es_cos(0.3))])
        result, transport = self.run_search(es, settings(mode="vector", min_cosine="0.8"))
        self.assertEqual(("EVIDENCE_HOLD", ["NO_RELEVANT_CANDIDATE"]), (result["status"], result["hold_reasons"]))
        self.assertEqual([], transport.tool_calls("/api/v1/tools/rule_evidence"))

    def test_unusable_family_never_embeds_or_searches(self):
        calls = []
        def counting(query):
            calls.append(query)
            return fake_embed(query)
        transport = FakeTransport().respond(PREPAYMENT, lambda: FakeTransport.ok_response({**self.checklist, "usable": False, "blockingReasons": ["HUMAN_REVIEW_PENDING"], "approvedChecklist": None}))
        es = SplitEs(bm25=[(self.ids[0], 4.0)], knn=[(self.ids[0], es_cos(0.9))])
        result, _ = self.run_search(es, settings(), embed=counting, transport=transport)
        self.assertEqual("EVIDENCE_HOLD", result["status"])
        self.assertEqual([], es.requests)
        self.assertEqual([], calls)


class HybridSettingsTest(unittest.TestCase):
    def test_env_parsing(self):
        parsed = config.load_search_settings({config.ENV_SEARCH_MODE: "hybrid", config.ENV_SEARCH_HYBRID_GATE: "both", config.ENV_SEARCH_VECTOR_MIN_COSINE: "0.82"})
        self.assertEqual(("hybrid", "both", "0.82", 60), (parsed.mode, parsed.gate, parsed.vector_min_cosine, parsed.rrf_k))
        self.assertEqual("bm25", config.load_search_settings({}).mode)
        for env in ({config.ENV_SEARCH_MODE: "rerank"}, {config.ENV_SEARCH_HYBRID_GATE: "any"}, {config.ENV_SEARCH_VECTOR_MIN_COSINE: "2"}):
            with self.assertRaises(config.SettingsError):
                config.load_search_settings(env)
