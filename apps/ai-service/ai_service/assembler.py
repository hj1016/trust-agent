"""규칙 조립 상담 준비안과 보류(TASK-015 핵심 흐름).

신청 건 → 승인된 공문군 매핑 → 공문군마다 Core Tool 1(적용 checklist)과 Tool 2(항목 근거) → 섹션 READY/HOLD →
전체 상태(필수 공문군 기준) → Core 기록 경로. LLM 없음. 추측 준비안 없음(fail-closed).
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Callable, Optional
from zoneinfo import ZoneInfo

from jsonschema import Draft202012Validator, FormatChecker

from . import messages
from .config import Settings
from .core_client import CoreClient, CoreResponse, HttpTransport, TransportTimeout, TransportUnavailable
from .ids import ASSEMBLER_VERSION, new_run_id, preparation_id, sha256_of

BUSINESS_TIMEZONE = ZoneInfo("Asia/Seoul")
MAPPING_RELATIVE_PATH = Path("datasets/synthetic/work/consultation-family-mapping.json")
APPLICATION_DIRECTORY = Path("datasets/synthetic/work/applications")
COMPANY_DIRECTORY = Path("datasets/synthetic/work/companies")
TOOL_SCHEMA_PATH = Path("contracts/tool-applicable-checklist.schema.json")
EVIDENCE_SCHEMA_PATH = Path("contracts/tool-rule-evidence.schema.json")
MAPPING_SCHEMA_PATH = Path("contracts/consultation-family-mapping.schema.json")
DISCLAIMER = ("프로젝트 시연을 위해 생성한 합성 상담 준비안이며 실제 상담 기록, 고객 정보, 은행 내부자료가 아닙니다. "
              "항목과 근거는 Core의 승인 checklist와 합성 공문 원문에서 왔고 LLM이 생성한 문장은 없습니다.")

HOLD_CLAIM_BASIS = {"CORE_DECISION": "CORE_REPORTED", "UNVERIFIED": "SERVICE_REPORTED"}


class InputError(ValueError):
    """사용자 입력 오류(신청 없음, 매핑 없음, 날짜 형식 등). CLI 종료 코드 2."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass
class _Section:
    family_id: str
    required: bool
    status: str
    hold_kind: Optional[str] = None
    evaluated_at: Optional[str] = None
    selected_notice: Optional[dict] = None
    approved_checklist: Optional[dict] = None
    items: list = None  # type: ignore[assignment]
    blocking_reasons: list = None  # type: ignore[assignment]
    warning_reasons: list = None  # type: ignore[assignment]
    tool_response_hash: Optional[str] = None

    def __post_init__(self) -> None:
        self.items = self.items or []
        self.blocking_reasons = self.blocking_reasons or []
        self.warning_reasons = self.warning_reasons or []


def prepare(application_id: str, business_date: Optional[str] = None, consultation_id: Optional[str] = None, *,
            settings: Settings, transport: Optional[HttpTransport] = None, clock: Optional[Callable[[], datetime]] = None,
            run_id_factory: Optional[Callable[[], str]] = None) -> dict:
    """준비안을 조립하고 Core에 기록한 뒤 출력 계약 형태의 dict를 돌려준다. 재실행은 항상 Core를 다시 본다."""
    now = (clock or (lambda: datetime.now(timezone.utc)))()
    effective_business_date = _business_date(business_date, now)
    if consultation_id is not None and (not consultation_id.strip() or len(consultation_id) > 64):
        raise InputError("INVALID_CONSULTATION_ID", "상담 ID는 1~64자여야 합니다.")
    root = settings.repository_root
    application = _load_application(root, application_id)
    company = _load_company(root, application["company_id"])
    mapping, mapping_hash = _load_mapping(root)
    families = _families_for(mapping, application["product_key"])

    client = CoreClient(settings.core_base_url, settings.tool_token, settings.record_token, settings.timeout_seconds, transport)
    tool_validator = _validator(root / TOOL_SCHEMA_PATH)
    evidence_validator = _validator(root / EVIDENCE_SCHEMA_PATH)

    sections = [
        _build_section(client, family, effective_business_date, consultation_id, tool_validator, evidence_validator)
        for family in families
    ]
    required_sections = [section for section in sections if section.required]
    no_required_family = not required_sections
    status = _overall_status(required_sections, no_required_family)
    run_id = (run_id_factory or new_run_id)()

    record_body = _record_body(
        run_id, mapping_hash, application, effective_business_date, consultation_id, status, sections)
    record_body["preparation_id"] = preparation_id(record_body)
    record = _record(client, record_body)

    hold_sections = [section for section in sections if section.status == "HOLD"]
    required_holds = [section for section in hold_sections if section.required]
    optional_holds = [section for section in hold_sections if not section.required]
    ready_count = sum(1 for section in sections if section.status == "READY")
    output = {
        "preparation_id": record_body["preparation_id"],
        "run_id": run_id,
        "assembler_version": ASSEMBLER_VERSION,
        "messages_hash": messages.messages_hash(),
        "family_mapping_hash": mapping_hash,
        "dataset_class": "SYNTHETIC_WORK",
        "synthetic": True,
        "disclaimer": DISCLAIMER,
        "application": {
            "application_id": application["application_id"],
            "company_id": application["company_id"],
            "legal_name": company["legal_name"],
            "product_key": application["product_key"],
            "requested_amount_krw": application["requested_amount_krw"],
            "purpose": application["purpose"],
            "source_hash": sha256_of(application),
        },
        "business_date": effective_business_date,
        "generated_at": now.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "status": status,
        "preparation_complete": status == "READY",
        "headline": messages.headline(
            status, total=len(sections), required=len(required_sections), ready=ready_count,
            hold=len(required_holds), hold_families=[section.family_id for section in required_holds],
            no_required_family=no_required_family),
        "remaining_checks": [_hold_summary(section) for section in required_holds],
        "optional_holds": [_hold_summary(section) for section in optional_holds],
        "sections": [_section_output(section) for section in sections],
        "notices": {
            "human_decision_notice": messages.NOTICES["human_decision_notice"],
            "source_notice": messages.NOTICES["source_notice"],
            "usage_notice": messages.usage_notice(record["status"]),
        },
        "record": record,
    }
    if consultation_id:
        output["consultation_id"] = consultation_id
    return output


# ---- 입력 ----

def _business_date(value: Optional[str], now: datetime) -> str:
    if value is None or not value.strip():
        return now.astimezone(BUSINESS_TIMEZONE).date().isoformat()
    try:
        return date.fromisoformat(value.strip()).isoformat()
    except ValueError as error:
        raise InputError("INVALID_BUSINESS_DATE", "업무일은 YYYY-MM-DD 형식이어야 합니다.") from error


def _load_json(path: Path) -> Any:
    try:
        with path.open(encoding="utf-8") as source:
            return json.load(source, parse_float=str)
    except FileNotFoundError as error:
        raise InputError("FILE_NOT_FOUND", f"파일이 없습니다: {path}") from error
    except ValueError as error:
        raise InputError("FILE_INVALID", f"JSON을 읽을 수 없습니다: {path}") from error


def _load_application(root: Path, application_id: str) -> dict:
    if not application_id or "/" in application_id or ".." in application_id:
        raise InputError("APPLICATION_NOT_FOUND", "신청 ID가 올바르지 않습니다.")
    path = root / APPLICATION_DIRECTORY / f"{application_id.lower().replace('sw-application-', 'application-')}.json"
    if not path.is_file():
        raise InputError("APPLICATION_NOT_FOUND", f"합성 신청 자료가 없습니다: {application_id}")
    application = _load_json(path)
    if application.get("dataset_class") != "SYNTHETIC_WORK" or application.get("synthetic") is not True \
            or application.get("application_id") != application_id:
        raise InputError("APPLICATION_INVALID", f"합성 신청 자료가 계약과 다릅니다: {application_id}")
    return application


def _load_company(root: Path, company_id: str) -> dict:
    path = root / COMPANY_DIRECTORY / f"{company_id.lower().replace('sw-company-', 'company-')}.json"
    if not path.is_file():
        raise InputError("COMPANY_NOT_FOUND", f"합성 기업 자료가 없습니다: {company_id}")
    company = _load_json(path)
    if company.get("dataset_class") != "SYNTHETIC_WORK" or company.get("company_id") != company_id:
        raise InputError("COMPANY_INVALID", f"합성 기업 자료가 계약과 다릅니다: {company_id}")
    return company


def _load_mapping(root: Path) -> tuple[dict, str]:
    path = root / MAPPING_RELATIVE_PATH
    if not path.is_file():
        raise InputError("MAPPING_NOT_FOUND", f"공문군 매핑 파일이 없습니다: {path}")
    mapping = _load_json(path)
    errors = sorted(_validator(root / MAPPING_SCHEMA_PATH).iter_errors(mapping), key=lambda error: list(error.path))
    if errors:
        raise InputError("MAPPING_INVALID", "공문군 매핑 파일이 계약과 다릅니다: " + errors[0].message)
    return mapping, sha256_of(mapping)


def _families_for(mapping: dict, product_key: str) -> list[dict]:
    for product in mapping["products"]:
        if product["product_key"] == product_key:
            return sorted(product["families"], key=lambda family: family["order"])
    raise InputError("PRODUCT_NOT_MAPPED", f"승인된 공문군 매핑에 없는 상품입니다: {product_key}")


def _validator(schema_path: Path) -> Draft202012Validator:
    with schema_path.open(encoding="utf-8") as source:
        return Draft202012Validator(json.load(source), format_checker=FormatChecker())


# ---- 섹션 조립 ----

def _build_section(client: CoreClient, family: dict, business_date: str, consultation_id: Optional[str],
                   tool_validator: Draft202012Validator, evidence_validator: Draft202012Validator) -> _Section:
    family_id = family["family_id"]
    required = bool(family["required"])
    try:
        response = client.applicable_checklist(family_id, business_date, consultation_id)
    except TransportTimeout:
        return _Section(family_id, required, "HOLD", hold_kind="UNVERIFIED", blocking_reasons=["CORE_TIMEOUT"])
    except TransportUnavailable:
        return _Section(family_id, required, "HOLD", hold_kind="UNVERIFIED", blocking_reasons=["CORE_UNAVAILABLE"])

    outcome = _classify_tool_response(response, tool_validator)
    if outcome != "OK":
        kind, code = outcome
        return _Section(family_id, required, "HOLD", hold_kind=kind, blocking_reasons=[code])

    checklist = response.json
    response_hash = sha256_of(checklist)
    selected = _selected_notice(checklist.get("selectedNotice"))
    evaluated_at = checklist.get("evaluatedAt")
    warnings = list(checklist.get("warningReasons") or [])
    if not checklist["usable"]:
        reasons = list(checklist.get("blockingReasons") or []) or ["USABLE_FALSE_WITHOUT_REASON"]
        return _Section(family_id, required, "HOLD", hold_kind="CORE_DECISION", evaluated_at=evaluated_at,
                        selected_notice=selected, blocking_reasons=reasons, warning_reasons=warnings,
                        tool_response_hash=response_hash)

    approved = checklist["approvedChecklist"]
    items = []
    for item in approved["items"]:
        evidence = _fetch_evidence(client, family_id, item["sourceRuleVersionId"], consultation_id, evidence_validator)
        if evidence is None:
            # 근거 없는 항목은 제공하지 않는다. 하나라도 실패하면 섹션 전체를 보류한다(부분 제공 없음).
            return _Section(family_id, required, "HOLD", hold_kind="UNVERIFIED", evaluated_at=evaluated_at,
                            selected_notice=selected, blocking_reasons=["EVIDENCE_UNAVAILABLE"],
                            warning_reasons=warnings, tool_response_hash=response_hash)
        items.append({
            "order": item["order"],
            "rule_key": item["ruleKey"],
            "instruction": item["instruction"],
            "evidence_required": item["evidenceRequired"],
            "structured_change": item["structuredChange"],
            "source_rule_version_id": item["sourceRuleVersionId"],
            "evidence": evidence,
        })
    return _Section(
        family_id, required, "READY", evaluated_at=evaluated_at, selected_notice=selected,
        approved_checklist={
            "version_id": approved["approvedChecklistVersionId"],
            "decision_id": approved["decisionId"],
            "effective_from": approved["effectiveFrom"],
            "effective_to": approved["effectiveTo"],
        },
        items=items, warning_reasons=warnings, tool_response_hash=response_hash)


def _classify_tool_response(response: CoreResponse, validator: Draft202012Validator):
    """200이 아니면 보류 종류와 사유 코드를, 200이고 계약에 맞으면 'OK'를 돌려준다."""
    if response.status in (401, 403):
        return ("UNVERIFIED", "TOOL_AUTH_FAILED")
    if response.status == 404 and response.problem_code == "POLICY_FAMILY_NOT_FOUND":
        # Core가 직접 "그런 공문군이 없다"고 판정한 것이다.
        return ("CORE_DECISION", "POLICY_FAMILY_NOT_FOUND")
    if response.status >= 500:
        return ("UNVERIFIED", "CORE_UNAVAILABLE")
    if response.status != 200 or not isinstance(response.json, dict):
        return ("UNVERIFIED", "TOOL_RESPONSE_INVALID")
    if next(validator.iter_errors(response.json), None) is not None:
        return ("UNVERIFIED", "TOOL_RESPONSE_INVALID")
    return "OK"


def _fetch_evidence(client: CoreClient, family_id: str, rule_version_id: str, consultation_id: Optional[str],
                    validator: Draft202012Validator) -> Optional[dict]:
    try:
        response = client.rule_evidence(family_id, rule_version_id, consultation_id)
    except (TransportTimeout, TransportUnavailable):
        return None
    if response.status != 200 or not isinstance(response.json, dict):
        return None
    if next(validator.iter_errors(response.json), None) is not None:
        return None
    if response.json["ruleVersionId"] != rule_version_id:
        return None
    return {
        "notice_id": response.json["noticeId"],
        "evidence_text": response.json["evidenceText"],
        "json_pointer": response.json["jsonPointer"],
        "evidence_hash": response.json["evidenceHash"],
    }


def _selected_notice(node: Optional[dict]) -> Optional[dict]:
    if not node:
        return None
    return {
        "notice_id": node["noticeId"],
        "version": node["version"],
        "title": node["title"],
        "effective_from": node["effectiveFrom"],
        "effective_to": node["effectiveTo"],
    }


def _overall_status(required_sections: list[_Section], no_required_family: bool) -> str:
    if no_required_family:
        return "HOLD"
    ready = sum(1 for section in required_sections if section.status == "READY")
    if ready == len(required_sections):
        return "READY"
    return "PARTIAL" if ready > 0 else "HOLD"


# ---- 출력과 기록 ----

def _record_body(run_id: str, mapping_hash: str, application: dict, business_date: str,
                 consultation_id: Optional[str], status: str, sections: list[_Section]) -> dict:
    body = {
        "run_id": run_id,
        "assembler_version": ASSEMBLER_VERSION,
        "messages_hash": messages.messages_hash(),
        "family_mapping_hash": mapping_hash,
        "application": {
            "application_id": application["application_id"],
            "company_id": application["company_id"],
            "product_key": application["product_key"],
            "source_hash": sha256_of(application),
        },
        "business_date": business_date,
        "status": status,
        "preparation_complete": status == "READY",
        "sections": [{
            "family_id": section.family_id,
            "required": section.required,
            "status": section.status,
            "hold_kind": section.hold_kind,
            "hold_claim_basis": HOLD_CLAIM_BASIS.get(section.hold_kind) if section.hold_kind else None,
            "evaluated_at": section.evaluated_at,
            "selected_notice_id": section.selected_notice["notice_id"] if section.selected_notice else None,
            "approved_checklist_version_id": section.approved_checklist["version_id"] if section.approved_checklist else None,
            "decision_id": section.approved_checklist["decision_id"] if section.approved_checklist else None,
            "item_rule_version_ids": [item["source_rule_version_id"] for item in section.items],
            "item_evidence_hashes": [item["evidence"]["evidence_hash"] for item in section.items],
            "blocking_reasons": list(section.blocking_reasons),
            "tool_response_hash": section.tool_response_hash,
        } for section in sections],
    }
    if consultation_id:
        body["consultation_id"] = consultation_id
    return body


def _record(client: CoreClient, record_body: dict) -> dict:
    """기록 결과. recorded는 RECORDED·ALREADY_RECORDED일 때만 true이며 그 밖은 모두 '기록되지 않음'이다."""
    result = _record_outcome(client, record_body)
    result = {"recorded": result["status"] in ("RECORDED", "ALREADY_RECORDED"), **result}
    return result


def _record_outcome(client: CoreClient, record_body: dict) -> dict:
    if not client.record_token_configured:
        return {"status": "NOT_ATTEMPTED", "error_code": "RECORD_TOKEN_MISSING",
                "error_message": "기록 토큰이 설정되지 않아 Core에 기록하지 않았습니다."}
    try:
        response = client.record_preparation(record_body)
    except TransportTimeout:
        return {"status": "FAILED", "error_code": "CORE_TIMEOUT", "error_message": messages.SERVICE_REASON_MESSAGES["CORE_TIMEOUT"]}
    except TransportUnavailable:
        return {"status": "FAILED", "error_code": "CORE_UNAVAILABLE", "error_message": messages.SERVICE_REASON_MESSAGES["CORE_UNAVAILABLE"]}
    body = response.json if isinstance(response.json, dict) else {}
    if response.status in (200, 201):
        result = {"status": "RECORDED" if response.status == 201 else "ALREADY_RECORDED"}
        core_id = body.get("preparationId") or body.get("preparation_id")
        recorded_at = body.get("recordedAt") or body.get("recorded_at")
        if isinstance(core_id, str):
            result["core_preparation_id"] = core_id
        if isinstance(recorded_at, str):
            result["recorded_at"] = recorded_at
        return result
    if response.status >= 500:
        return {"status": "FAILED", "error_code": "CORE_UNAVAILABLE",
                "error_message": "Core 기록 경로가 오류로 응답했습니다(" + str(response.status) + ")."}
    code = response.problem_code or ("UNAUTHENTICATED" if response.status == 401 else "RECORD_REJECTED")
    detail = body.get("detail") if isinstance(body.get("detail"), str) else "Core가 기록을 거부했습니다."
    return {"status": "REJECTED", "error_code": code, "error_message": detail}


def _hold_summary(section: _Section) -> dict:
    return {
        "family_id": section.family_id,
        "required": section.required,
        "hold_kind": section.hold_kind,
        "blocking_reasons": list(section.blocking_reasons),
        "hold_message": messages.hold_message(section.hold_kind, section.blocking_reasons),
        "manual_checklist_notice": messages.manual_checklist_notice(section.family_id),
    }


def _section_output(section: _Section) -> dict:
    hold = section.status == "HOLD"
    return {
        "family_id": section.family_id,
        "required": section.required,
        "status": section.status,
        "hold_kind": section.hold_kind,
        "hold_claim_basis": HOLD_CLAIM_BASIS.get(section.hold_kind) if section.hold_kind else None,
        "evaluated_at": section.evaluated_at,
        "selected_notice": section.selected_notice,
        "approved_checklist": section.approved_checklist,
        "items": list(section.items),
        "blocking_reasons": list(section.blocking_reasons),
        "warning_reasons": list(section.warning_reasons),
        "hold_message": messages.hold_message(section.hold_kind, section.blocking_reasons) if hold else None,
        "manual_checklist_notice": messages.manual_checklist_notice(section.family_id) if hold else None,
        "tool_response_hash": section.tool_response_hash,
    }
