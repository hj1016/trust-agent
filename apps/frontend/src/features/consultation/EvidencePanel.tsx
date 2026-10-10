import { ChevronDown, FileText, Quote } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { TechnicalDetails } from "@/components/TechnicalDetails";
import type { ApplicableRule, ApplicableState, ApprovedItem } from "@/lib/types";

/**
 * 항목 하나의 원문 근거(은행원용). 기본으로 보이는 것: 근거 공문(제목·차수·발행일·시행일·사용 상태), 공문 원문 발췌,
 * 적용 조건과 예외, 직원 확인사항. 규칙 ID·JSON Pointer·근거 해시·승인 결정 ID는 감사·무결성 확인용으로 그대로 두되 접어 둔다.
 * 원문 발췌(공문 규칙 문장)와 직원 확인사항(승인 Checklist 지시)은 출처가 다르므로 나눠 보인다. 값은 모두 Core 응답 그대로다.
 */
export function EvidencePanel({ item, rule, state }: { item: ApprovedItem; rule: ApplicableRule; state: ApplicableState }) {
  const notice = state.selectedNotice;
  const change = item.structuredChange;
  const conditions = Array.isArray(change?.conditions) ? change.conditions : [];
  const exceptions = Array.isArray(change?.exceptions) ? change.exceptions : [];
  return (
    <Collapsible>
      <CollapsibleTrigger className="group mt-2 inline-flex items-center gap-1 rounded-lg px-1 py-1 text-[15px] font-semibold text-info hover:underline">
        <FileText className="size-4" /> 원문 근거 보기 <ChevronDown className="size-4 transition-transform group-data-[state=open]:rotate-180" />
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="mt-2 flex flex-col gap-3 rounded-xl border border-line bg-surface p-4" data-testid="evidence-panel">
          {notice && (
            <section aria-label="근거 공문">
              <h4 className="text-sm font-semibold text-ink-soft">근거 공문</h4>
              <p className="mt-0.5 text-[15px] font-semibold text-ink">{notice.title}</p>
              <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-ink-soft">
                <span>{notice.version}차</span>
                {notice.issuedOn && <span>발행 {notice.issuedOn}</span>}
                <span>시행 {notice.effectiveFrom}{notice.effectiveTo ? ` ~ ${notice.effectiveTo}` : "부터"}</span>
                {state.internalChecklistUseAllowed ? <Badge tone="ready">업무일 {state.businessDate} 사용 가능</Badge> : <Badge tone="hold">업무일 {state.businessDate} 사용 불가</Badge>}
              </div>
            </section>
          )}
          <section aria-label="공문 원문 발췌">
            <h4 className="flex items-center gap-1 text-sm font-semibold text-ink-soft"><Quote className="size-4" /> 공문 원문 발췌</h4>
            <blockquote className="mt-1 rounded-lg border-l-4 border-brand bg-brand-soft/50 px-4 py-3 text-[15px] leading-relaxed text-ink">{rule.evidenceText}</blockquote>
          </section>
          {(conditions.length > 0 || exceptions.length > 0) && (
            <section aria-label="적용 조건과 예외" className="grid gap-2 sm:grid-cols-2">
              {conditions.length > 0 && (
                <div>
                  <h4 className="text-sm font-semibold text-ink-soft">적용 조건</h4>
                  <ul className="mt-1 list-disc pl-5 text-[15px] text-ink">{conditions.map((text) => <li key={text}>{text}</li>)}</ul>
                </div>
              )}
              {exceptions.length > 0 && (
                <div>
                  <h4 className="text-sm font-semibold text-ink-soft">예외</h4>
                  <ul className="mt-1 list-disc pl-5 text-[15px] text-ink">{exceptions.map((text) => <li key={text}>{text}</li>)}</ul>
                </div>
              )}
            </section>
          )}
          <section aria-label="직원 확인사항">
            <h4 className="text-sm font-semibold text-ink-soft">직원 확인사항(승인 Checklist)</h4>
            <p className="mt-1 text-[15px] leading-relaxed text-ink">{item.instruction}</p>
            {item.instruction === rule.evidenceText && (
              <p className="mt-1 text-sm text-ink-soft">이 항목의 확인 지시는 공문 원문 문장과 같습니다.</p>
            )}
          </section>
          <TechnicalDetails label="기술 상세 정보(감사·무결성 확인용)" rows={[
            ["규칙 ID", rule.ruleVersionId],
            ["규칙 키", item.ruleKey],
            ["JSON Pointer", rule.jsonPointer],
            ["근거 해시", rule.evidenceHash],
            ["승인 checklist", state.approvedChecklist?.approvedChecklistVersionId],
            ["승인 결정 ID", state.approvedChecklist?.decisionId],
            ["공문 ID", notice?.noticeId],
          ]} />
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

/**
 * 공문에 실린 규칙 원문 전체(Core가 적재한 공문 추출 결과, 기존 적용 조회 응답의 rules). 원본 문서 파일을 여는 기능은 없다.
 */
export function NoticeRulesPanel({ state }: { state: ApplicableState }) {
  if (!state.selectedNotice || state.rules.length === 0) return null;
  const rules = [...state.rules].sort((a, b) => a.order - b.order);
  return (
    <Collapsible>
      <CollapsibleTrigger className="group inline-flex items-center gap-1 rounded-lg px-1 py-1 text-[15px] font-semibold text-info hover:underline">
        <FileText className="size-4" /> 공문 규칙 원문 전체 보기({rules.length}개) <ChevronDown className="size-4 transition-transform group-data-[state=open]:rotate-180" />
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="mt-2 rounded-xl border border-line bg-surface p-4" data-testid="notice-rules">
          <p className="text-sm text-ink-soft">{state.selectedNotice.title}에 실린 규칙 문장입니다(업무 시스템에 적재된 공문 추출 결과). 원본 문서 파일 열람 기능은 없습니다.</p>
          <ol className="mt-2 flex flex-col gap-2">
            {rules.map((rule) => (
              <li key={rule.ruleVersionId} className="rounded-lg bg-canvas/70 px-3 py-2 text-[15px] leading-relaxed">
                <span className="mr-1 font-semibold">{rule.order + 1}.</span>{rule.evidenceText}
              </li>
            ))}
          </ol>
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}
