import { Bot, PanelRightClose, PanelRightOpen, RefreshCw } from "lucide-react";
import { preparationStatus, recordStatus, sectionStatus } from "@/lib/labels";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { ApiError } from "@/lib/api";
import { reasonText, TEXT } from "@/lib/messages";
import type { Preparation } from "@/lib/types";
import { cn, formatDateTime } from "@/lib/utils";
import { useRequestPreparation } from "./queries";
import { useNoticeTitle } from "./noticeTitle";
import type { PreparationSection } from "@/lib/types";

const STATUS_TEXT: Record<string, string> = {
  READY: "필수 공문군의 기준 자료가 모두 준비됐습니다",
  PARTIAL: "일부 공문군이 보류돼 추가 확인이 필요합니다",
  HOLD: "모든 공문군이 보류돼 준비안을 쓸 수 없습니다",
};

type Props = {
  consultationId: string;
  businessDate: string;
  preparation: Preparation | null | undefined;
  loading: boolean;
  open: boolean;
  onToggle: () => void;
};

/** 우측 AI 업무지원 패널. 준비안은 Core를 거쳐 AI 서비스가 만들고, 표시는 Core에 기록된 준비안을 다시 읽어서 한다. */
export function AiPanel({ consultationId, businessDate, preparation, loading, open, onToggle }: Props) {
  const request = useRequestPreparation(consultationId);
  if (!open) {
    return (
      <aside aria-label="AI 업무지원" className="flex flex-col items-center border-l border-line bg-surface py-4">
        <Button variant="ghost" size="icon" onClick={onToggle} aria-label="AI 업무지원 패널 열기" aria-expanded={false}>
          <PanelRightOpen />
        </Button>
      </aside>
    );
  }
  const failure = request.error instanceof ApiError ? request.error : null;
  const aiDown = failure && (failure.status === 502 || failure.status === 504);
  return (
    <aside aria-label="AI 업무지원" className="flex min-h-0 flex-col border-l border-line bg-surface">
      <div className="flex items-center justify-between px-5 pt-4 pb-3">
        <h2 className="flex items-center gap-2 text-base font-bold"><Bot className="size-5" /> AI 업무지원</h2>
        <Button variant="ghost" size="icon" onClick={onToggle} aria-label="AI 업무지원 패널 접기" aria-expanded>
          <PanelRightClose />
        </Button>
      </div>
      <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-5 pb-6">
        <section aria-label="상담 준비안" className="flex flex-col gap-3">
          <div className="flex items-center justify-between">
            <h3 className="text-[15px] font-bold">상담 준비안</h3>
            {preparation && <Badge tone={preparationStatus(preparation.status).tone} data-testid="preparation-status" data-status={preparation.status}>{preparationStatus(preparation.status).text}</Badge>}
          </div>
          {loading && <p className="text-sm text-ink-soft">저장된 준비안을 확인하는 중…</p>}
          {!loading && !preparation && <p className="text-sm text-ink-soft">이 상담 건에 저장된 준비안이 없습니다.</p>}
          {preparation && (
            <div className="rounded-xl bg-canvas p-4 text-sm">
              <p className="font-semibold">{STATUS_TEXT[preparation.status] ?? preparationStatus(preparation.status).text}</p>
              <p className="mt-1 text-ink-soft">업무일 {preparation.businessDate} · 기록 {formatDateTime(preparation.linkedAt)}</p>
              {preparation.businessDate !== businessDate && (
                <p className="mt-2 rounded-lg bg-hold-soft px-3 py-2 text-sm font-semibold text-hold" data-testid="preparation-date-mismatch">
                  화면 업무일({businessDate})과 다른 업무일의 준비안입니다. 이 업무일 기준으로 보려면 준비안을 다시 만드세요.
                </p>
              )}
              <ul className="mt-3 flex flex-col gap-2">
                {preparation.sections.map((section) => (
                  <SectionRow key={section.familyId} section={section} businessDate={preparation.businessDate} />
                ))}
              </ul>
              <p className="mt-3 text-sm leading-relaxed text-ink-soft">{TEXT.readyMeaning}</p>
            </div>
          )}
          <Button onClick={() => request.mutate(businessDate)} disabled={request.isPending} className={cn(preparation && "bg-surface border border-line hover:bg-muted")}
            variant={preparation ? "outline" : "primary"}>
            <RefreshCw className={cn(request.isPending && "animate-spin")} />
            {request.isPending ? "준비안을 만드는 중…" : preparation ? `준비안 다시 만들기(${businessDate})` : `준비안 만들기(${businessDate})`}
          </Button>
          {request.isSuccess && (
            <Alert tone={request.data.record?.recorded ? "ready" : "hold"}>
              {request.data.record?.recorded
                ? `준비안을 업무 시스템에 저장했습니다(${recordStatus(request.data.record.status).text}).`
                : `준비안을 만들었지만 업무 시스템에 저장되지 않았습니다(${recordStatus(request.data.record?.status).text}${request.data.record?.status ? `, 코드 ${request.data.record.status}` : ""}). 저장된 준비안은 바뀌지 않았습니다.`}
            </Alert>
          )}
          {aiDown && <Alert tone="hold" data-testid="ai-unavailable">{TEXT.aiUnavailable}</Alert>}
          {failure && !aiDown && <ErrorNotice error={failure} fallback="준비안을 만들지 못했습니다." />}
        </section>
        <Separator />
        <section aria-label="질문 응답" className="flex flex-col gap-2">
          <h3 className="text-[15px] font-bold">추가 질문</h3>
          <p className="rounded-xl bg-muted p-3 text-sm text-ink-soft">{TEXT.searchPending}</p>
        </section>
        <Separator />
        <p className="text-sm leading-relaxed text-ink-soft">{TEXT.humanDecision}</p>
      </div>
    </aside>
  );
}

function SectionRow({ section, businessDate }: { section: PreparationSection; businessDate: string }) {
  const title = useNoticeTitle(section.familyId, businessDate, section.selectedNoticeId);
  return (
    <li className="rounded-lg bg-surface p-3" data-testid={`section-${section.familyId}`}>
      <div className="flex items-start justify-between gap-2">
        <span className="text-sm leading-snug font-semibold">{title}</span>
        <Badge tone={sectionStatus(section.status).tone} data-status={section.status}>{sectionStatus(section.status).text}</Badge>
      </div>
      {section.blockingReasons.map((code) => (
        <p key={code} className="mt-1 text-sm leading-relaxed text-hold">{reasonText(code)}</p>
      ))}
    </li>
  );
}
