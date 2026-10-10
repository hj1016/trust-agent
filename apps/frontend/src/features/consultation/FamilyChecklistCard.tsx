import { useMemo, useState } from "react";
import { roleLabel } from "@/lib/labels";
import { ErrorNotice } from "@/components/ErrorNotice";
import { ShieldCheck } from "lucide-react";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { reasonText, TEXT } from "@/lib/messages";
import type { ApplicableState, Confirmation, FamilyRef, Preparation } from "@/lib/types";
import { formatDateTime } from "@/lib/utils";
import { useApplicable, useConfirm } from "./queries";
import { describeChange } from "./structured";
import { ChangeHighlights } from "./ChangeHighlights";
import { EvidencePanel, NoticeRulesPanel } from "./EvidencePanel";
import { TechnicalDetails } from "@/components/TechnicalDetails";

type Props = {
  consultationId: string;
  family: FamilyRef;
  businessDate: string;
  preparation: Preparation | null | undefined;
  confirmations: Confirmation[] | undefined;
};

/** 공문군 하나: Core 적용 공문·승인 checklist·원문 근거(질문 없이 먼저 보임)와 READY 섹션의 직원 확인. */
export function FamilyChecklistCard({ consultationId, family, businessDate, preparation, confirmations }: Props) {
  const applicable = useApplicable(family.familyId, businessDate);
  const state = applicable.data;
  return (
    <Card data-testid={`family-${family.familyId}`}>
      <CardHeader>
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <CardTitle>{state?.selectedNotice?.title ?? (applicable.isPending ? "공문 정보를 불러오는 중…" : family.familyId)}</CardTitle>
            {family.required && <Badge tone="info">필수 확인</Badge>}
            {state && (state.internalChecklistUseAllowed ? <Badge tone="ready">사용 가능</Badge> : <Badge tone="hold">사용 불가</Badge>)}
          </div>
          {state?.selectedNotice && (
            <p className="mt-1 text-[15px] text-ink-soft">
              공문 {state.selectedNotice.version}차 · 시행 {state.selectedNotice.effectiveFrom}{state.selectedNotice.effectiveTo ? ` ~ ${state.selectedNotice.effectiveTo}` : "부터"}
            </p>
          )}
          <div className="mt-1">
            <TechnicalDetails rows={[
              ["공문군", family.familyId],
              ["공문 ID", state?.selectedNotice?.noticeId],
              ["승인 checklist", state?.approvedChecklist?.approvedChecklistVersionId],
              ["검수 결정", state?.approvedChecklist?.decisionId],
            ]} />
          </div>
        </div>
      </CardHeader>
      <CardContent>
        {applicable.isPending && <p className="text-sm text-ink-soft">적용 Checklist를 불러오는 중…</p>}
        {applicable.isError && (
          <ErrorNotice error={applicable.error} fallback="적용 Checklist를 불러오지 못했습니다." />
        )}
        {state && !state.internalChecklistUseAllowed && <Unusable state={state} />}
        {state?.internalChecklistUseAllowed && state.approvedChecklist && (
          <ChecklistWithConfirmation consultationId={consultationId} familyId={family.familyId} state={state} businessDate={businessDate}
            preparation={preparation} confirmations={confirmations} />
        )}
      </CardContent>
    </Card>
  );
}

function Unusable({ state }: { state: ApplicableState }) {
  return (
    <Alert tone="hold">
      <p className="font-semibold">업무 시스템이 이 업무일에는 승인 Checklist를 쓸 수 없다고 판정했습니다.</p>
      <ul className="mt-1 list-disc pl-5">
        {state.blockingReasons.map((code) => (
          <li key={code}>{reasonText(code)} <span className="font-mono text-[13px]">({code})</span></li>
        ))}
      </ul>
      <p className="mt-2">{TEXT.manualChecklist}</p>
    </Alert>
  );
}

function ChecklistWithConfirmation({ consultationId, familyId, state, businessDate, preparation, confirmations }: {
  consultationId: string; familyId: string; state: ApplicableState; businessDate: string;
  preparation: Preparation | null | undefined; confirmations: Confirmation[] | undefined;
}) {
  const checklist = state.approvedChecklist!;
  const rulesById = useMemo(() => new Map(state.rules.map((rule) => [rule.ruleVersionId, rule])), [state.rules]);
  const section = preparation?.sections.find((candidate) => candidate.familyId === familyId);
  const confirmation = confirmations?.find((row) => row.preparationId === preparation?.preparationId && row.familyId === familyId);
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const confirm = useConfirm(consultationId);

  const blocker = confirmation ? null
    : !preparation ? "AI 준비안을 만든 뒤 READY 공문군의 근거 확인을 기록할 수 있습니다."
    : !section ? "최신 준비안에 이 공문군이 없습니다."
    : section.status !== "READY" ? "준비안에서 이 공문군이 보류(HOLD)라 확인 기록 대상이 아닙니다."
    : preparation.businessDate !== businessDate ? `준비안 업무일(${preparation.businessDate})과 화면 업무일(${businessDate})이 달라 확인할 수 없습니다.`
    : section.approvedChecklistVersionId !== checklist.approvedChecklistVersionId ? "준비안 이후 승인 checklist가 바뀌었습니다. 준비안을 다시 만드세요."
    : null;
  const canCheck = !blocker && !confirmation;
  const allChecked = checklist.items.every((item) => checked.has(item.sourceRuleVersionId));

  const submit = () => {
    if (!preparation || !section) return;
    confirm.mutate({ preparationId: preparation.preparationId, familyId, ruleVersionIds: section.itemRuleVersionIds.filter((id) => checked.has(id)) });
  };

  return (
    <div className="flex flex-col gap-3">
      <ChangeHighlights items={checklist.items} effectiveFrom={state.selectedNotice?.effectiveFrom} />
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="mt-1 text-[15px] font-bold text-ink">직원 확인사항 {checklist.items.length}개</h3>
        <NoticeRulesPanel state={state} />
      </div>
      <ol className="flex flex-col gap-3">
        {checklist.items.map((item) => {
          const rule = rulesById.get(item.sourceRuleVersionId);
          const id = `${familyId}-${item.order}`;
          const isChecked = Boolean(confirmation) || checked.has(item.sourceRuleVersionId);
          return (
            <li key={item.sourceRuleVersionId} className="rounded-xl border border-line bg-canvas/60 p-4">
              <div className="flex items-start gap-3">
                <Checkbox id={id} aria-label={`근거 확인: ${item.instruction}`} checked={isChecked} disabled={!canCheck}
                  onCheckedChange={(value) => setChecked((previous) => {
                    const next = new Set(previous);
                    if (value === true) next.add(item.sourceRuleVersionId); else next.delete(item.sourceRuleVersionId);
                    return next;
                  })} />
                <div className="min-w-0 flex-1">
                  <label htmlFor={id} className="text-[15px] leading-relaxed font-semibold text-ink">{item.order + 1}. {item.instruction}</label>
                  {item.evidenceRequired && <div className="mt-1"><Badge tone="partial">근거 확인 필수</Badge></div>}
                  {itemLines(item).length > 0 && (
                    <ul className="mt-2 flex flex-col gap-1 text-[15px] text-ink">
                      {itemLines(item).map((line) => <li key={line}>{line}</li>)}
                    </ul>
                  )}
                  {rule && <EvidencePanel item={item} rule={rule} state={state} />}
                </div>
              </div>
            </li>
          );
        })}
      </ol>
      <div className="rounded-xl border border-dashed border-line p-4" data-testid={`confirmation-${familyId}`}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2 text-[15px] font-semibold"><ShieldCheck className="size-5 text-ready" /> 직원 근거 확인</div>
          {!confirmation && (
            <Button onClick={submit} disabled={!canCheck || !allChecked || confirm.isPending}>
              {confirm.isPending ? "기록 중…" : "근거 확인 기록"}
            </Button>
          )}
        </div>
        <p className="mt-2 text-sm leading-relaxed text-ink-soft">{TEXT.confirmationMeaning}</p>
        {blocker && <p className="mt-2 text-sm text-hold">{blocker}</p>}
        {canCheck && !allChecked && <p className="mt-2 text-sm text-ink-soft">모든 항목의 근거를 확인해야 기록할 수 있습니다.</p>}
        {confirm.error && (
          <ErrorNotice error={confirm.error} fallback="확인을 기록하지 못했습니다." className="mt-2" />
        )}
        {confirmation && (
          <div className="mt-2">
            <Alert tone="ready" className="text-[15px]">
              근거 확인 기록됨 · 확인자 {confirmation.confirmedBy}({roleLabel(confirmation.activeRole).text}) · {formatDateTime(confirmation.confirmedAt)} · 항목 {confirmation.confirmedRuleVersionIds.length}개
            </Alert>
            <TechnicalDetails label="확인 대상 상세 정보" rows={[
              ["확인 대상 공문", confirmation.selectedNoticeId],
              ["승인 checklist", confirmation.approvedChecklistVersionId],
              ["검수 결정", confirmation.decisionId],
              ["준비안", confirmation.preparationId],
            ]} />
          </div>
        )}
      </div>
    </div>
  );
}

/** 항목 아래 줄: 변경 값은 위쪽 변경 요약에 크게 있으므로 빼고, 시행일(값이 없는 항목)·조건·예외만 보인다. */
function itemLines(item: { structuredChange: import("@/lib/types").StructuredChange | null }): string[] {
  const numeric = item.structuredChange && item.structuredChange.unit !== "BOOLEAN" && item.structuredChange.after_value != null;
  // 조건·예외는 원문 근거 영역에 있으므로 여기서는 값이 없는 항목의 시행일만 보인다.
  return describeChange(item.structuredChange).filter((line) => line.startsWith("시행일") && !numeric);
}
