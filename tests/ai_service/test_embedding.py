"""로컬 임베딩(e5-small) 고정 조건과 입력 형식. 실제 모델 추론 검사는 TRUST_AGENT_EMBEDDING_MODEL_DIR가 있을 때만 실행한다."""
import json
import os
import tempfile
import unittest
from pathlib import Path

import tests.ai_service.support  # noqa: F401  (ai_service 경로 등록)
from ai_service import embedding


class EmbeddingManifestTest(unittest.TestCase):
    def test_manifest_pins_model_revision_dimension_and_prefixes(self):
        info = embedding.manifest()
        self.assertEqual("intfloat/multilingual-e5-small", info["model_id"])
        self.assertRegex(info["revision"], r"^[0-9a-f]{40}$")
        self.assertEqual(384, info["dimension"])
        self.assertEqual({"query": "query: ", "passage": "passage: "}, info["prefixes"])
        self.assertEqual("MIT", info["license"])
        for name, meta in info["files"].items():
            self.assertRegex(meta["sha256"], r"^[0-9a-f]{64}$", name)
        self.assertEqual(f"intfloat/multilingual-e5-small@{info['revision'][:12]}", embedding.model_tag(info))

    def test_requirements_pin_runtime_versions_from_manifest(self):
        requirements = (Path(embedding.__file__).resolve().parent.parent / "requirements-ai.txt").read_text(encoding="utf-8")
        for package, version in embedding.manifest()["runtime"].items():
            self.assertIn(f"{package}=={version}", requirements)

    def test_text_validation(self):
        self.assertEqual(["a"], embedding.validate_texts(["a"]))
        for bad in ([], ["   "], ["x" * (embedding.MAX_CHARS + 1)], ["a"] * (embedding.MAX_TEXTS + 1), "text", [1]):
            with self.assertRaises(ValueError):
                embedding.validate_texts(bad)

    def test_unconfigured_model_dir_is_unavailable(self):
        saved = embedding._EMBEDDER
        embedding._EMBEDDER = None
        try:
            with self.assertRaises(embedding.EmbeddingUnavailable) as raised:
                embedding.get_embedder({})
            self.assertEqual("EMBEDDING_NOT_CONFIGURED", raised.exception.code)
        finally:
            embedding._EMBEDDER = saved

    def test_tampered_model_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in embedding.manifest()["files"]:
                (root / name).parent.mkdir(parents=True, exist_ok=True)
                (root / name).write_bytes(b"not the model")
            with self.assertRaises(embedding.EmbeddingUnavailable) as raised:
                embedding.E5Embedder(root, embedding.manifest())
            self.assertEqual("EMBEDDING_MODEL_MISMATCH", raised.exception.code)


@unittest.skipUnless(os.environ.get(embedding.ENV_MODEL_DIR), "모델 디렉터리가 없어 실제 추론 검사는 건너뜀")
class EmbeddingInferenceTest(unittest.TestCase):
    """smoke·tuning 자료 문장만 쓴다(최종 평가 문항으로 동작을 확인하지 않는다)."""

    @classmethod
    def setUpClass(cls):
        cls.embedder = embedding.get_embedder()

    def test_vectors_are_normalized_with_fixed_dimension(self):
        vectors = self.embedder.encode(["중도상환수수료율이 바뀌었다는데 지금 얼마예요?"], "query")
        self.assertEqual(384, len(vectors[0]))
        self.assertAlmostEqual(1.0, sum(x * x for x in vectors[0]), places=4)

    def test_query_and_passage_prefixes_change_the_vector(self):
        text = "수수료를 안 받는 예외가 있나요?"
        query = self.embedder.encode([text], "query")[0]
        passage = self.embedder.encode([text], "passage")[0]
        self.assertLess(sum(a * b for a, b in zip(query, passage)), 0.9999)

    def test_same_input_is_deterministic(self):
        first = self.embedder.encode(["고객 약정일은 왜 확인해야 하나요?"], "query")[0]
        second = self.embedder.encode(["고객 약정일은 왜 확인해야 하나요?"], "query")[0]
        self.assertEqual(first, second)
