// Core 응답 형식(TASK-017b에서 쓰는 필드만). 값은 모두 Core가 정한다.

export type Role = "STAFF" | "REVIEWER";

export interface SessionSummary {
  principal: string;
  roles: Role[];
  activeRole: Role;
  workspaceId: string;
  synthetic: boolean;
}

export interface ApplicationSummary {
  applicationId: string;
  companyId: string;
  companyName: string;
  productKey: string;
  requestedAmountKrw: number;
  purpose: string;
  requestedAt: string;
  status: string;
}

export interface FamilyRef {
  familyId: string;
  required: boolean;
  order: number;
}

export interface Consultation {
  consultationId: string;
  applicationId: string;
  companyId: string;
  productKey: string;
  assignedUserId: string;
  status: string;
  workspaceId: string;
  createdAt: string;
  application: ApplicationSummary;
  families: FamilyRef[];
}

export interface StructuredChange {
  change_type?: string;
  field_key?: string;
  before_value?: unknown;
  after_value?: unknown;
  unit?: string;
  effective_on?: string;
  conditions?: string[];
  exceptions?: string[];
  [key: string]: unknown;
}

export interface ApplicableRule {
  ruleVersionId: string;
  order: number;
  ruleKey: string;
  instruction: string;
  evidenceRequired: boolean;
  structuredChange: StructuredChange | null;
  jsonPointer: string;
  evidenceText: string;
  evidenceHash: string;
}

export interface ApprovedItem {
  order: number;
  ruleKey: string;
  instruction: string;
  evidenceRequired: boolean;
  structuredChange: StructuredChange | null;
  sourceRuleVersionId: string;
}

export interface ApplicableState {
  familyId: string;
  synthetic: boolean;
  disclaimer: string;
  businessDate: string;
  evaluatedAt: string;
  internalChecklistUseAllowed: boolean;
  blockingReasons: string[];
  warningReasons: string[];
  selectedNotice: {
    noticeId: string;
    version: number;
    title: string;
    issuedOn?: string;
    effectiveFrom: string;
    effectiveTo: string | null;
  } | null;
  rules: ApplicableRule[];
  approvedChecklist: {
    approvedChecklistVersionId: string;
    decisionId: string;
    effectiveFrom: string;
    effectiveTo: string | null;
    items: ApprovedItem[];
  } | null;
}

export interface PreparationSection {
  familyId: string;
  required: boolean;
  status: "READY" | "HOLD";
  holdKind: string | null;
  blockingReasons: string[];
  selectedNoticeId: string | null;
  approvedChecklistVersionId: string | null;
  decisionId: string | null;
  itemRuleVersionIds: string[];
  itemEvidenceHashes: string[];
}

export interface Preparation {
  consultationId: string;
  preparationId: string;
  runId: string;
  status: "READY" | "PARTIAL" | "HOLD";
  preparationComplete: boolean;
  businessDate: string;
  recordedAt: string;
  linkedAt: string;
  sections: PreparationSection[];
}

export interface Confirmation {
  confirmationId: string;
  consultationId: string;
  preparationId: string;
  familyId: string;
  businessDate: string;
  selectedNoticeId: string;
  approvedChecklistVersionId: string;
  decisionId: string;
  confirmedRuleVersionIds: string[];
  confirmedEvidenceHashes: string[];
  confirmedBy: string;
  activeRole: string;
  recheckEvaluatedAt: string;
  confirmedAt: string;
}

/** AI 서비스 준비안 응답(Core가 그대로 전달). 화면은 상태와 기록 결과만 쓰고 저장본은 Core 조회로 다시 읽는다. */
export interface AiPreparationResponse {
  status?: string;
  headline?: string;
  record?: { recorded?: boolean; status?: string; code?: string };
}

export interface ProposalSummary {
  proposalId: string;
  familyId: string;
  targetNoticeId: string;
  itemCount: number;
  createdAt: string;
  decision: string | null;
}
