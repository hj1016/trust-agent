import { useState } from "react";
import { applicationStatus, consultationStatus } from "@/lib/labels";
import { useParams, useSearchParams } from "react-router";
import { CalendarDays } from "lucide-react";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { ApiError, request } from "@/lib/api";
import { useQuery } from "@tanstack/react-query";
import { TechnicalDetails } from "@/components/TechnicalDetails";
import { TEXT } from "@/lib/messages";
import type { Consultation } from "@/lib/types";
import { cn, formatDateTime, formatWon, seoulToday } from "@/lib/utils";
import { AiPanel } from "@/features/consultation/AiPanel";
import { ConsultationList, NewConsultationDialog } from "@/features/consultation/ConsultationList";
import { ConfirmationHistory } from "@/features/consultation/ConfirmationHistory";
import { FamilyChecklistCard } from "@/features/consultation/FamilyChecklistCard";
import { useConfirmations, useConsultation, usePreparation } from "@/features/consultation/queries";

export function ConsultationsPage() {
  const { consultationId } = useParams();
  const [panelOpen, setPanelOpen] = useState(true);
  return (
    <div className={cn("grid h-full min-h-0", panelOpen ? "grid-cols-[260px_minmax(0,1fr)_320px] xl:grid-cols-[300px_minmax(0,1fr)_380px]" : "grid-cols-[260px_minmax(0,1fr)_64px] xl:grid-cols-[300px_minmax(0,1fr)_64px]")}>
      <ConsultationList selectedId={consultationId} />
      {consultationId ? (
        <ConsultationDetail key={consultationId} consultationId={consultationId} panelOpen={panelOpen} onTogglePanel={() => setPanelOpen((value) => !value)} />
      ) : (
        <>
          <section className="grid place-items-center p-10 text-center text-ink-soft">
            <div>
              <p className="text-lg font-semibold text-ink">상담 건을 선택하세요</p>
              <p className="mt-2 text-sm">상담 건을 열면 적용 공문과 확인할 항목이 먼저 보입니다.</p>
              <div className="mt-5"><NewConsultationDialog label="새 상담 건 만들기" large /></div>
            </div>
          </section>
          <aside className="border-l border-line bg-surface" aria-hidden />
        </>
      )}
    </div>
  );
}

function ConsultationDetail({ consultationId, panelOpen, onTogglePanel }: { consultationId: string; panelOpen: boolean; onTogglePanel: () => void }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const businessDate = searchParams.get("businessDate") ?? seoulToday();
  const consultation = useConsultation(consultationId);
  const preparation = usePreparation(consultationId);
  const confirmations = useConfirmations(consultationId);

  if (consultation.isError) {
    const notFound = consultation.error instanceof ApiError && consultation.error.status === 404;
    return (
      <>
        <section className="p-8">
          <Alert tone={notFound ? "neutral" : "danger"} data-testid="consultation-error">
            {notFound ? "상담 건을 찾을 수 없습니다. 담당 상담 건만 열 수 있습니다." : "상담 건을 불러오지 못했습니다."}
          </Alert>
        </section>
        <aside className="border-l border-line bg-surface" aria-hidden />
      </>
    );
  }
  return (
    <>
      <section aria-label="상담 상세" className="min-h-0 overflow-y-auto">
        <div className="mx-auto flex max-w-[960px] flex-col gap-5 p-6">
          {consultation.data ? <Summary consultation={consultation.data} businessDate={businessDate}
            onDate={(value) => setSearchParams((previous) => { const next = new URLSearchParams(previous); next.set("businessDate", value); return next; })} />
            : <p className="text-ink-soft">상담 건을 불러오는 중…</p>}
          <Alert tone="info">{TEXT.humanDecision}</Alert>
          {consultation.data?.families.map((family) => (
            <FamilyChecklistCard key={`${family.familyId}-${businessDate}`} consultationId={consultationId} family={family} businessDate={businessDate}
              preparation={preparation.data} confirmations={confirmations.data} />
          ))}
          {consultation.data && <ConfirmationHistory confirmations={confirmations.data} loading={confirmations.isPending} />}
        </div>
      </section>
      <AiPanel consultationId={consultationId} businessDate={businessDate} preparation={preparation.data} loading={preparation.isPending}
        open={panelOpen} onToggle={onTogglePanel} />
    </>
  );
}

function Summary({ consultation, businessDate, onDate }: { consultation: Consultation; businessDate: string; onDate: (value: string) => void }) {
  const application = consultation.application;
  const product = useProductName(application.productKey);
  return (
    <Card>
      <CardContent className="pt-5">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-[22px] font-extrabold" data-testid="company-name">{application.companyName}</h1>
              <Badge tone="brand">{TEXT.synthetic}</Badge>
            </div>
            <p className="mt-1 text-[15px] text-ink-soft">{product ?? application.productKey} 상담 · 신청 {formatDateTime(application.requestedAt)}</p>
          </div>
          <label className="flex items-center gap-2 rounded-xl border border-line bg-canvas px-3 py-2 text-[15px] font-semibold">
            <CalendarDays className="size-4" /> 업무일
            <input type="date" value={businessDate} onChange={(event) => event.target.value && onDate(event.target.value)}
              aria-label="업무일" className="bg-transparent outline-none" />
          </label>
        </div>
        <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 text-[15px] 2xl:grid-cols-4">
          <Field term="상품" value={product ?? application.productKey} />
          <Field term="신청 금액" value={formatWon(application.requestedAmountKrw)} />
          <Field term="담당" value={consultation.assignedUserId} />
          <Field term="상담 상태" value={consultationStatus(consultation.status).text} />
          <div className="col-span-full min-w-0">
            <dt className="text-sm text-ink-soft">자금 용도</dt>
            <dd className="mt-0.5 font-semibold leading-relaxed break-keep" data-testid="purpose">{application.purpose}</dd>
          </div>
        </dl>
        <div className="mt-3">
          <TechnicalDetails rows={[
            ["기업 ID", application.companyId],
            ["신청 ID", application.applicationId],
            ["상담 건 ID", consultation.consultationId],
            ["상품 키", application.productKey],
            ["신청 상태", `${applicationStatus(application.status).text} (${application.status})`],
          ]} />
        </div>
      </CardContent>
    </Card>
  );
}

/** 상품 표시명: 공개 상품 관측 상태(기존 API)의 displayName. 못 읽으면 상품 키를 그대로 보인다. */
function useProductName(productKey: string): string | undefined {
  const query = useQuery({
    queryKey: ["product-name", productKey],
    queryFn: async () => (await request<{ displayName?: string }>(`/api/v1/public-products/${encodeURIComponent(productKey)}/observed-state`)).displayName,
    staleTime: 5 * 60_000,
  });
  if (query.isPending) return "상품 정보를 불러오는 중…";
  return query.data ?? undefined;
}

function Field({ term, value, mono }: { term: string; value: string; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-sm text-ink-soft">{term}</dt>
      <dd className={cn("mt-0.5 font-semibold break-keep", mono && "font-mono text-[13px] break-all")}>{value}</dd>
    </div>
  );
}
