/**
 * 상태·역할·오류 코드의 한국어 표시(표시 계층 전용). DB·API·내부 코드는 바꾸지 않는다.
 * 원칙: 의미가 확인된 값만 매핑한다. 모르는 값은 성공·승인으로 해석하지 않고 '확인 필요'와 원래 코드를 함께 보인다.
 * 근거: consultation.status CHECK(OPEN만), consultation_preparation.status(READY/PARTIAL/HOLD, READY는 결정 완료가 아님),
 * human_review_decision.decision CHECK(APPROVE/MODIFY/REJECT, MODIFY는 새 revision만 만듦), AI 서비스 기록 결과,
 * 합성 신청 계약의 status const DRAFT(의미 문서 없음 → '작성 중'으로 표시하고 코드를 함께 남김).
 */
export type Tone = "neutral" | "ready" | "hold" | "partial" | "danger" | "info" | "brand";
export type Label = { text: string; tone: Tone; code: string };

function lookup(table: Record<string, Omit<Label, "code">>, code: string | null | undefined, empty: Omit<Label, "code">): Label {
  if (code === null || code === undefined || code === "") return { ...empty, code: "" };
  const hit = table[code];
  return hit ? { ...hit, code } : { text: `확인 필요(${code})`, tone: "neutral", code };
}

const CONSULTATION_STATUS = { OPEN: { text: "진행 중", tone: "info" } } as const satisfies Record<string, Omit<Label, "code">>;
export const consultationStatus = (code: string) => lookup(CONSULTATION_STATUS, code, { text: "상태 없음", tone: "neutral" });

const APPLICATION_STATUS = { DRAFT: { text: "작성 중", tone: "neutral" } } as const satisfies Record<string, Omit<Label, "code">>;
export const applicationStatus = (code: string) => lookup(APPLICATION_STATUS, code, { text: "상태 없음", tone: "neutral" });

const PREPARATION_STATUS = {
  READY: { text: "기준 자료 준비 완료", tone: "ready" },
  PARTIAL: { text: "일부 준비·추가 확인 필요", tone: "partial" },
  HOLD: { text: "준비 보류", tone: "hold" },
} as const satisfies Record<string, Omit<Label, "code">>;
export const preparationStatus = (code: string) => lookup(PREPARATION_STATUS, code, { text: "준비안 없음", tone: "neutral" });

const SECTION_STATUS = {
  READY: { text: "준비 완료", tone: "ready" },
  HOLD: { text: "준비 보류", tone: "hold" },
} as const satisfies Record<string, Omit<Label, "code">>;
export const sectionStatus = (code: string) => lookup(SECTION_STATUS, code, { text: "상태 없음", tone: "neutral" });

const REVIEW_DECISION = {
  APPROVE: { text: "승인 완료", tone: "ready" },
  MODIFY: { text: "수정 후 재검토", tone: "partial" },
  REJECT: { text: "반려", tone: "danger" },
} as const satisfies Record<string, Omit<Label, "code">>;
export const reviewDecision = (code: string | null) => lookup(REVIEW_DECISION, code, { text: "검토 결정 없음", tone: "hold" });

const ROLE = { STAFF: { text: "직원", tone: "info" }, REVIEWER: { text: "검수자", tone: "info" } } as const satisfies Record<string, Omit<Label, "code">>;
export const roleLabel = (code: string) => lookup(ROLE, code, { text: "역할 없음", tone: "neutral" });

const RECORD_STATUS = {
  RECORDED: { text: "새로 저장됨", tone: "ready" },
  ALREADY_RECORDED: { text: "같은 내용이 이미 기록돼 있음", tone: "ready" },
  NOT_ATTEMPTED: { text: "기록하지 않음", tone: "hold" },
  FAILED: { text: "기록 실패", tone: "danger" },
  REJECTED: { text: "업무 시스템이 저장을 거부함", tone: "danger" },
} as const satisfies Record<string, Omit<Label, "code">>;
export const recordStatus = (code: string | undefined) => lookup(RECORD_STATUS, code, { text: "기록 결과 없음", tone: "hold" });

/** 화면에서 볼 수 있는 오류 코드의 한국어 안내. 원래 코드와 서버 설명은 함께 보인다(원인을 숨기지 않는다). */
const ERROR_TEXT: Record<string, string> = {
  UNAUTHENTICATED: "로그인이 필요합니다. 다시 로그인하세요.",
  LOGIN_FAILED: "사용자 ID 또는 비밀번호가 맞지 않습니다.",
  FORBIDDEN: "현재 활성 역할로는 할 수 없는 작업입니다.",
  CSRF_REJECTED: "보안 확인 값이 만료됐습니다. 화면을 새로고침한 뒤 다시 시도하세요.",
  ROLE_NOT_HELD: "이 계정이 가진 역할이 아닙니다.",
  ROLE_NOT_ACTIVE: "직원 역할로 전환한 뒤 기록할 수 있습니다.",
  CONSULTATION_NOT_FOUND: "상담 건을 찾을 수 없습니다. 담당 상담 건만 열 수 있습니다.",
  APPLICATION_NOT_REGISTERED: "등록되지 않은 신청입니다.",
  PRODUCT_NOT_MAPPED: "이 상품에 연결된 공문군이 없습니다.",
  INVALID_BUSINESS_DATE: "업무일 형식이 올바르지 않습니다.",
  INVALID_REQUEST: "요청 내용이 올바르지 않습니다.",
  PREPARATION_NOT_FOUND: "이 상담 건에 기록된 준비안이 없습니다. 준비안을 먼저 만드세요.",
  SECTION_NOT_IN_PREPARATION: "준비안에 없는 공문군입니다.",
  SECTION_ON_HOLD: "보류된 공문군은 근거 확인을 기록할 수 없습니다. 수기 Checklist로 확인하세요.",
  CONFIRMATION_INCOMPLETE: "모든 항목의 근거를 확인해야 기록할 수 있습니다.",
  CONFIRMATION_MISMATCH: "준비안 항목과 맞지 않는 확인 요청입니다. 화면을 새로고침하세요.",
  ALREADY_CONFIRMED: "이 준비안의 이 공문군은 이미 확인 기록이 있습니다.",
  PREPARATION_STALE: "준비안 이후 승인 상태가 바뀌었습니다. 준비안을 다시 만드세요.",
  AI_SERVICE_UNAVAILABLE: "AI 서비스에 연결하지 못했습니다.",
  AI_SERVICE_TIMEOUT: "AI 서비스 응답이 시간 안에 오지 않았습니다.",
  GRANT_ISSUE_FAILED: "요청 승인 정보를 저장하지 못해 AI 요청을 보내지 않았습니다. 잠시 뒤 다시 시도하세요.",
  SECURITY_EVENT_WRITE_FAILED: "보안 기록을 남기지 못해 요청을 완료하지 않았습니다. 잠시 뒤 다시 시도하세요.",
};

export function errorGuide(code: string): string {
  return ERROR_TEXT[code] ?? (code.startsWith("HTTP_5") ? "서버 처리 중 문제가 생겼습니다. 잠시 뒤 다시 시도하세요." : "요청을 처리하지 못했습니다.");
}
