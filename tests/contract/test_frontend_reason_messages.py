"""TASK-017b: 화면의 사유 코드 설명(apps/frontend/src/lib/reasonMessages.json)이 AI 서비스 문구 표와 같은지 확인한다.

화면은 저장된 준비안의 사유 코드만 받으므로 같은 문구를 쓰려면 두 표가 일치해야 한다. AI 서비스 문구를 바꾸면 이 테스트가 실패한다.
"""
import ast
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def python_tables():
    tree = ast.parse((ROOT / "apps/ai-service/ai_service/messages.py").read_text(encoding="utf-8"))
    tables = {}
    for node in tree.body:
        if isinstance(node, (ast.Assign, ast.AnnAssign)):
            target = node.targets[0] if isinstance(node, ast.Assign) else node.target
            if isinstance(target, ast.Name) and target.id in ("CORE_REASON_MESSAGES", "SERVICE_REASON_MESSAGES"):
                tables[target.id] = ast.literal_eval(node.value)
    return {**tables["CORE_REASON_MESSAGES"], **tables["SERVICE_REASON_MESSAGES"]}


class FrontendReasonMessagesTest(unittest.TestCase):
    def test_frontend_table_matches_ai_service_messages(self):
        frontend = json.loads((ROOT / "apps/frontend/src/lib/reasonMessages.json").read_text(encoding="utf-8"))["messages"]
        self.assertEqual(python_tables(), frontend)


if __name__ == "__main__":
    unittest.main()
