"""로컬 모델 provider(Ollama 형식). 가짜 Ollama HTTP 서버로 요청 형식·정형 출력 검사·실패 보류를 확인한다(실제 모델 품질 검증 아님)."""
from __future__ import annotations

import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

import tests.ai_service.support  # noqa: F401
from ai_service import llm, local_llm, rag
from ai_service.llm import LlmError

FEE = "policy-rule:sha256:" + "a" * 64
PAYLOAD = {"question": "수수료율은?", "business_date": "2026-10-06", "evidence": [{"rule_version_id": FEE, "notice_id": "N", "notice_title": None,
           "evidence_text": "중도상환수수료율 0.8퍼센트", "structured": {"after_value": "0.8", "conditions": ["기업 고객의 기업여신 상담 건"], "exceptions": []}}]}
GOOD = {"request_type": "RULE_QUESTION", "answerable": True,
        "statements": [{"text": "중도상환수수료율은 0.8퍼센트입니다.", "source": FEE, "quote": "중도상환수수료율 0.8퍼센트"}], "citations": [FEE], "needs_check": []}


class FakeOllama(BaseHTTPRequestHandler):
    reply: object = None
    status = 200
    requests: list = []

    def do_POST(self):  # noqa: N802
        length = int(self.headers["Content-Length"])
        FakeOllama.requests.append((self.path, json.loads(self.rfile.read(length))))
        body = FakeOllama.reply if isinstance(FakeOllama.reply, str) else json.dumps(FakeOllama.reply)
        self.send_response(FakeOllama.status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body.encode("utf-8"))

    def log_message(self, *args):
        pass


def envelope(content, done_reason="stop"):
    return {"model": "qwen3:14b-q4_K_M", "message": {"role": "assistant", "content": content if isinstance(content, str) else json.dumps(content, ensure_ascii=False)},
            "done": True, "done_reason": done_reason, "prompt_eval_count": 321, "eval_count": 45}


class LocalModelGatewayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), FakeOllama)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        FakeOllama.requests = []
        FakeOllama.status = 200

    def gateway(self, timeout=5.0):
        return local_llm.LocalModelGateway(self.url, "qwen3:14b-q4_K_M", timeout)

    def test_structured_request_and_usage(self):
        FakeOllama.reply = envelope(GOOD)
        result = self.gateway().call(rag.GENERATE_SYSTEM, PAYLOAD, rag.GENERATE_SCHEMA)
        self.assertEqual(GOOD, result.data)
        self.assertEqual((321, 45, 0.0, "ollama:qwen3:14b-q4_K_M"), (result.input_tokens, result.output_tokens, result.charged_usd, result.model))
        path, body = FakeOllama.requests[0]
        self.assertEqual("/api/chat", path)
        self.assertEqual(rag.GENERATE_SCHEMA, body["format"])
        self.assertFalse(body["stream"])
        self.assertFalse(body["think"])
        self.assertEqual(0, body["options"]["temperature"])
        self.assertEqual(PAYLOAD, json.loads(body["messages"][1]["content"]), "Bedrock과 같은 allowlist 자료만 보낸다")

    def test_think_block_is_removed_before_parsing(self):
        FakeOllama.reply = envelope("<think>생각</think>" + json.dumps(GOOD, ensure_ascii=False))
        self.assertEqual(GOOD, self.gateway().call(rag.GENERATE_SYSTEM, PAYLOAD, rag.GENERATE_SCHEMA).data)

    def test_failures_become_hold_codes(self):
        cases = [(envelope("not json"), 200, "LLM_RESPONSE_INVALID"), (envelope({k: v for k, v in GOOD.items() if k != "citations"}), 200, "LLM_FIELD_MISSING"),
                 (envelope({**GOOD, "request_type": "OTHER"}), 200, "LLM_FIELD_MISSING"), (envelope(GOOD, "length"), 200, "LLM_TRUNCATED"),
                 ({"error": "model not found"}, 404, "LLM_HTTP_404"), ("<html>", 200, "LLM_RESPONSE_INVALID")]
        for reply, status, code in cases:
            FakeOllama.reply, FakeOllama.status = reply, status
            with self.subTest(code=code), self.assertRaises(LlmError) as raised:
                self.gateway().call(rag.GENERATE_SYSTEM, PAYLOAD, rag.GENERATE_SCHEMA)
            self.assertEqual(code, raised.exception.code)

    def test_connection_dropped_without_response_is_unavailable(self):
        import socket as _socket
        listener = _socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        port = listener.getsockname()[1]

        def drop():
            connection, _ = listener.accept()
            connection.recv(65536)
            connection.close()
        threading.Thread(target=drop, daemon=True).start()
        try:
            with self.assertRaises(LlmError) as raised:
                local_llm.LocalModelGateway(f"http://127.0.0.1:{port}", "m", 5).call(rag.GENERATE_SYSTEM, PAYLOAD, rag.GENERATE_SCHEMA)
        finally:
            listener.close()
        self.assertEqual("LLM_UNAVAILABLE", raised.exception.code)

    def test_unreachable_port_is_unavailable(self):
        with self.assertRaises(LlmError) as raised:
            local_llm.LocalModelGateway("http://127.0.0.1:9", "m", 2).call(rag.GENERATE_SYSTEM, PAYLOAD, rag.GENERATE_SCHEMA)
        self.assertEqual("LLM_UNAVAILABLE", raised.exception.code)

    def test_only_loopback_urls_and_explicit_provider(self):
        for url in ("http://10.0.0.5:11434", "https://127.0.0.1:11434", "http://example.com:11434", "http://127.0.0.1"):
            with self.subTest(url=url), self.assertRaises(LlmError):
                local_llm.LocalModelGateway(url, "m")
        self.assertIsNone(local_llm.gateway_from_env({}))
        gateway = llm.gateway_for_service({"TRUST_AGENT_LLM_PROVIDER": "ollama", "TRUST_AGENT_OLLAMA_URL": self.url, "TRUST_AGENT_OLLAMA_MODEL": "qwen3:14b-q4_K_M"})
        self.assertIsInstance(gateway, local_llm.LocalModelGateway)
        with self.assertRaises(LlmError):
            llm.gateway_for_service({"TRUST_AGENT_LLM_PROVIDER": "openai"})
        with self.assertRaises(LlmError):
            llm.gateway_for_service({"TRUST_AGENT_LLM_PROVIDER": "ollama", "TRUST_AGENT_LLM_FAKE": "echo", "TRUST_AGENT_AI_DEPLOYMENT": "local"})

    def test_rag_holds_answer_when_local_model_fails(self):
        FakeOllama.reply = envelope({k: v for k, v in GOOD.items() if k != "statements"})
        search = {"status": "EVIDENCE", "hold_kind": None, "hold_reasons": [], "evidence": [{
            "rank": 1, "rule_version_id": FEE, "rule_key": "K", "score": "1", "instruction": "i", "evidence_required": True,
            "structured_change": {"after_value": "0.8", "conditions": ["기업 고객의 기업여신 상담 건"], "exceptions": []},
            "evidence": {"notice_id": "N", "evidence_text": "중도상환수수료율 0.8퍼센트", "json_pointer": "/rules/0", "evidence_hash": "sha256:x"}}]}
        result = rag.answer("수수료율은?", "2026-10-06", search, self.gateway())
        self.assertEqual(("ANSWER_HOLD", ["LLM_FIELD_MISSING"]), (result["status"], result["reasons"]))
        self.assertEqual("LLM_FIELD_MISSING", result["llm_call_log"][0]["code"])


if __name__ == "__main__":
    unittest.main()
