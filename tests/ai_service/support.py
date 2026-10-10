"""AI 서비스 테스트 공통 도구: 가짜 Core 전송, 임시 저장소 루트, 설정."""
from __future__ import annotations

import hashlib
import json
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Callable, Optional

ROOT = Path(__file__).resolve().parents[2]
AI_SERVICE_DIR = ROOT / "apps/ai-service"
if str(AI_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(AI_SERVICE_DIR))

from ai_service.config import Settings  # noqa: E402
from ai_service.core_client import TransportResponse, TransportTimeout, TransportUnavailable  # noqa: E402

PREPAYMENT = "SIN-PREPAYMENT-FEE"
SELLER = "SIN-SELLER-CHECKLIST"
TOOL_FIXTURE = ROOT / "contracts/fixtures/tool-applicable-checklist.expected.json"


def load_json(path: Path):
    with path.open(encoding="utf-8") as source:
        return json.load(source, parse_float=str)


def prepayment_usable() -> dict:
    return load_json(TOOL_FIXTURE)


def seller_pending() -> dict:
    return {
        "familyId": SELLER,
        "datasetClass": "SYNTHETIC_INTERNAL",
        "synthetic": True,
        "disclaimer": "프로젝트 시연을 위해 생성한 합성 공문이며 실제 KB 내부자료가 아닙니다.",
        "businessDate": "2026-10-06",
        "evaluatedAt": "2026-10-06T03:00:00Z",
        "selectedNotice": {"noticeId": "SIN-SELLER-CHECKLIST-V2", "version": 2, "title": "[합성] 셀러론 상담 준비 체크리스트 안내 v2",
                           "effectiveFrom": "2026-10-01", "effectiveTo": None},
        "usable": False,
        "blockingReasons": ["HUMAN_REVIEW_PENDING"],
        "warningReasons": [],
        "approvedChecklist": None,
    }


def evidence_for(item: dict, family_id: str = PREPAYMENT) -> dict:
    text = item["instruction"]
    return {
        "familyId": family_id,
        "noticeId": "SIN-PREPAYMENT-FEE-V2",
        "effectiveFrom": "2026-10-01",
        "ruleVersionId": item["sourceRuleVersionId"],
        "ruleKey": item["ruleKey"],
        "evidenceText": text,
        "jsonPointer": "/rules/" + str(item["order"]),
        "evidenceHash": "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest(),
        "disclaimer": "프로젝트 시연을 위해 생성한 합성 공문이며 실제 KB 내부자료가 아닙니다.",
    }


def problem(status: int, code: str) -> TransportResponse:
    return TransportResponse(status, json.dumps({"status": status, "title": "x", "code": code, "detail": "d", "traceId": "t"}).encode("utf-8"))


def ok(body) -> TransportResponse:
    return TransportResponse(200, json.dumps(body, ensure_ascii=False).encode("utf-8"))


class FakeTransport:
    """URL별 응답을 미리 정해 두는 가짜 Core. 호출 기록을 남긴다."""

    def __init__(self) -> None:
        self.checklist: dict[str, Callable[[], TransportResponse]] = {}
        self.evidence: dict[str, Callable[[], TransportResponse]] = {}
        self.record: Callable[[dict], TransportResponse] = lambda body: TransportResponse(
            201, json.dumps({"preparationId": body["preparation_id"], "runId": body["run_id"], "status": "RECORDED",
                             "recordedAt": "2026-10-06T03:00:05Z"}).encode("utf-8"))
        self.calls: list[tuple[str, dict, str]] = []
        self.headers_seen: list[dict] = []

    def usable(self, family_id: str, checklist: dict) -> "FakeTransport":
        self.checklist[family_id] = lambda: ok(checklist)
        if checklist.get("approvedChecklist"):
            for item in checklist["approvedChecklist"]["items"]:
                self.evidence[item["sourceRuleVersionId"]] = (lambda i=item: ok(evidence_for(i, family_id)))
        return self

    def respond(self, family_id: str, response: Callable[[], TransportResponse]) -> "FakeTransport":
        self.checklist[family_id] = response
        return self

    def send(self, method, url, headers, body, timeout):
        payload = json.loads(body.decode("utf-8")) if body else {}
        token = headers.get("Authorization", "")
        self.calls.append((url, payload, token))
        self.headers_seen.append(dict(headers))
        if url.endswith("/api/v1/tools/applicable_checklist"):
            handler = self.checklist.get(payload["familyId"])
            if handler is None:
                return problem(404, "POLICY_FAMILY_NOT_FOUND")
            return handler()
        if url.endswith("/api/v1/tools/rule_evidence"):
            handler = self.evidence.get(payload["ruleVersionId"])
            if handler is None:
                return problem(403, "EVIDENCE_NOT_AVAILABLE")
            return handler()
        if url.endswith("/api/v1/consultation-preparations"):
            return self.record(payload)
        raise AssertionError("알 수 없는 URL: " + url)

    def tool_calls(self, suffix: str) -> list[dict]:
        return [payload for url, payload, _ in self.calls if url.endswith(suffix)]


def raise_timeout():
    raise TransportTimeout("timeout")


def raise_unavailable():
    raise TransportUnavailable("refused")


def make_root(mapping: Optional[dict] = None) -> Path:
    """임시 저장소 루트: 계약과 합성 신청·기업 자료를 복사하고 매핑은 바꿔 끼울 수 있다."""
    root = Path(tempfile.mkdtemp(prefix="ai-service-test-"))
    (root / "contracts").mkdir()
    for name in ("tool-applicable-checklist.schema.json", "tool-rule-evidence.schema.json", "consultation-family-mapping.schema.json"):
        shutil.copy(ROOT / "contracts" / name, root / "contracts" / name)
    work = root / "datasets/synthetic/work"
    shutil.copytree(ROOT / "datasets/synthetic/work", work)
    if mapping is not None:
        (work / "consultation-family-mapping.json").write_text(json.dumps(mapping, ensure_ascii=False, indent=2), encoding="utf-8")
    return root


def mapping_with(families: list[dict], product_key: str = "kb-seller-loan") -> dict:
    base = load_json(ROOT / "datasets/synthetic/work/consultation-family-mapping.json")
    base["products"] = [{"product_key": product_key, "families": families}]
    return base


def settings_for(root: Path, record_token: Optional[str] = "temporary-record-token") -> Settings:
    return Settings(core_base_url="http://core.test", tool_token="temporary-tool-token", record_token=record_token,
                    timeout_seconds=5.0, repository_root=root)
