"""로컬 임베딩(multilingual-e5-small, ONNX). 외부 API를 부르지 않는다(PLAN-002 제안 4).

모델 ID·revision·차원·풀링은 manifest로 고정하고, 모델 파일은 sha256으로 검증한 뒤에만 쓴다.
E5 입력 형식: 질의는 "query: ", 근거 문장은 "passage: "를 앞에 붙인다. 평균 풀링 뒤 L2 정규화(코사인 = 내적).
"""
from __future__ import annotations

import hashlib
import json
import os
import threading
from pathlib import Path
from typing import Optional, Sequence

MANIFEST_PATH = Path(__file__).resolve().parent.parent / "models" / "multilingual-e5-small.manifest.json"
ENV_MODEL_DIR = "TRUST_AGENT_EMBEDDING_MODEL_DIR"
KINDS = ("query", "passage")
MAX_TEXTS = 64
MAX_CHARS = 2000


class EmbeddingUnavailable(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def manifest() -> dict:
    with MANIFEST_PATH.open(encoding="utf-8") as source:
        return json.load(source)


def model_tag(info: Optional[dict] = None) -> str:
    """색인 문서와 질의가 같은 모델로 만든 벡터인지 대조하는 표식(모델 ID@revision 앞 12자)."""
    info = info or manifest()
    return f"{info['model_id']}@{info['revision'][:12]}"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


class E5Embedder:
    def __init__(self, model_dir: Path, info: dict) -> None:
        try:
            import numpy  # noqa: F401
            import onnxruntime
            from tokenizers import Tokenizer
        except ImportError as error:  # pragma: no cover - 의존성 누락
            raise EmbeddingUnavailable("EMBEDDING_RUNTIME_MISSING", "임베딩 실행 패키지가 설치되지 않았습니다.") from error
        for name, expected in info["files"].items():
            path = model_dir / name
            if not path.is_file() or path.stat().st_size != expected["size"] or _sha256(path) != expected["sha256"]:
                raise EmbeddingUnavailable("EMBEDDING_MODEL_MISMATCH", f"모델 파일 검증 실패: {name}")
        self.info = info
        self.tag = model_tag(info)
        self.dimension = int(info["dimension"])
        self._session = onnxruntime.InferenceSession(str(model_dir / "onnx" / "model.onnx"), providers=["CPUExecutionProvider"])
        self._input_names = {item.name for item in self._session.get_inputs()}
        self._tokenizer = Tokenizer.from_file(str(model_dir / "onnx" / "tokenizer.json"))
        self._tokenizer.enable_truncation(int(info["max_tokens"]))
        self._tokenizer.enable_padding()
        self._lock = threading.Lock()

    def encode(self, texts: Sequence[str], kind: str) -> list[list[float]]:
        import numpy as np
        if kind not in KINDS:
            raise ValueError("kind는 query 또는 passage")
        prefix = self.info["prefixes"][kind]
        with self._lock:
            encoded = self._tokenizer.encode_batch([prefix + text for text in texts])
        ids = np.array([item.ids for item in encoded], dtype=np.int64)
        mask = np.array([item.attention_mask for item in encoded], dtype=np.int64)
        feed = {"input_ids": ids, "attention_mask": mask}
        if "token_type_ids" in self._input_names:
            feed["token_type_ids"] = np.zeros_like(ids)
        hidden = self._session.run(None, feed)[0]
        weights = mask[..., None].astype(np.float32)
        pooled = (hidden * weights).sum(axis=1) / np.clip(weights.sum(axis=1), 1e-9, None)
        pooled = pooled / np.clip(np.linalg.norm(pooled, axis=1, keepdims=True), 1e-12, None)
        if pooled.shape[1] != self.dimension:
            raise EmbeddingUnavailable("EMBEDDING_DIMENSION_MISMATCH", "임베딩 차원이 manifest와 다릅니다.")
        return [[float(x) for x in row] for row in pooled]


_EMBEDDER: Optional[E5Embedder] = None
_EMBEDDER_LOCK = threading.Lock()


def get_embedder(environ: Optional[dict] = None) -> E5Embedder:
    """처음 한 번 모델을 검증·적재한다. 모델 디렉터리가 없으면 EmbeddingUnavailable."""
    global _EMBEDDER
    if _EMBEDDER is not None:
        return _EMBEDDER
    env = os.environ if environ is None else environ
    raw = (env.get(ENV_MODEL_DIR) or "").strip()
    if not raw:
        raise EmbeddingUnavailable("EMBEDDING_NOT_CONFIGURED", f"{ENV_MODEL_DIR}가 설정되지 않았습니다.")
    with _EMBEDDER_LOCK:
        if _EMBEDDER is None:
            _EMBEDDER = E5Embedder(Path(raw), manifest())
    return _EMBEDDER


def validate_texts(texts: object) -> list[str]:
    if not isinstance(texts, list) or not texts or len(texts) > MAX_TEXTS:
        raise ValueError(f"texts는 1~{MAX_TEXTS}개 문자열 배열")
    if any(not isinstance(text, str) or not text.strip() or len(text) > MAX_CHARS for text in texts):
        raise ValueError(f"각 문장은 1~{MAX_CHARS}자")
    return texts
