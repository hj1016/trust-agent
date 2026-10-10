import reasons from "./reasonMessages.json";

const table: Record<string, string> = reasons.messages;

/** Core·AI 서비스 사유 코드의 한국어 설명. 모르는 코드는 코드 그대로 보인다(설명을 지어내지 않는다). */
export function reasonText(code: string): string {
  return table[code] ?? `설명이 등록되지 않은 사유 코드입니다: ${code}`;
}

/** 화면 고정 문구. */
export const TEXT = {
  synthetic: "합성 자료",
  syntheticNotice: "프로젝트 시연용 합성 자료입니다. 실제 고객·은행 내부자료가 아닙니다.",
  humanDecision: "이 화면은 확인할 규정과 근거를 보여 줍니다. 대출 승인·거절, 금리·한도 확정, 신용등급 결정은 하지 않으며 담당자와 결재 절차가 정합니다.",
  confirmationMeaning: "이 기록은 이 공문군의 항목별 근거를 읽었다는 직원 확인입니다. 대출 승인·거절이나 고객별 적용 승인, 상담 준비 완료가 아닙니다.",
  readyMeaning: "‘기준 자료 준비 완료’는 필수 공문군의 승인 Checklist와 근거를 갖췄다는 뜻이며 상담·대출 결정의 완료가 아닙니다.",
  manualChecklist: "보류된 공문군은 가운데의 적용 Checklist로 수기 확인하세요.",
  aiUnavailable: "AI 준비안 없음: AI 서비스에 연결하지 못했습니다. 가운데의 적용 Checklist로 수기 확인하세요.",
  searchPending: "근거 검색과 질문 응답은 아직 연결되지 않았습니다(후속 작업).",
  roleOverlap: "이 계정은 직원·검수자 역할을 함께 가진 시연용 계정입니다. 실제 금융기관의 직무 분리와 다릅니다.",
  reviewReadOnly: "읽기 전용: 이 화면에서는 변경안과 기록된 검수 상태만 볼 수 있습니다. 승인·수정·반려는 이 화면에서 할 수 없습니다.",
} as const;
