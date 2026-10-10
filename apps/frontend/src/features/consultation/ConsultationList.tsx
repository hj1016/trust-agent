import { useState } from "react";
import { consultationStatus } from "@/lib/labels";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Link } from "react-router";
import { Plus } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Alert } from "@/components/ui/alert";
import { cn, formatDateTime, formatWon } from "@/lib/utils";
import { useApplications, useConsultations, useCreateConsultation } from "./queries";
import { useNavigate } from "react-router";

export function ConsultationList({ selectedId }: { selectedId?: string }) {
  const consultations = useConsultations();
  const [filter, setFilter] = useState("");
  const items = (consultations.data ?? []).filter((item) => {
    const text = `${item.application.companyName} ${item.applicationId} ${item.consultationId}`.toLowerCase();
    return text.includes(filter.trim().toLowerCase());
  });
  return (
    <aside aria-label="담당 상담 건" className="flex min-h-0 flex-col border-r border-line bg-surface">
      <div className="flex items-center justify-between gap-2 px-4 pt-4 pb-3">
        <h2 className="text-base font-bold">담당 상담 건</h2>
        <NewConsultationDialog />
      </div>
      <div className="px-4 pb-3">
        <label htmlFor="consultation-filter" className="sr-only">목록 필터</label>
        <input id="consultation-filter" value={filter} onChange={(event) => setFilter(event.target.value)} placeholder="기업명·신청 ID로 거르기"
          className="h-10 w-full rounded-xl border border-line bg-canvas px-3 text-sm outline-none focus:border-brand-ink" />
      </div>
      <ul className="min-h-0 flex-1 overflow-y-auto px-2 pb-4" data-testid="consultation-list">
        {consultations.isPending && <li className="px-3 py-2 text-sm text-ink-soft">불러오는 중…</li>}
        {consultations.isError && <li className="px-3 py-2 text-sm text-danger">목록을 불러오지 못했습니다.</li>}
        {consultations.data && items.length === 0 && (
          <li className="px-3 py-6 text-center text-sm text-ink-soft">{consultations.data.length === 0 ? "담당 상담 건이 없습니다. 새 상담 건을 만드세요." : "조건에 맞는 상담 건이 없습니다."}</li>
        )}
        {items.map((item) => (
          <li key={item.consultationId}>
            <Link
              to={`/consultations/${encodeURIComponent(item.consultationId)}`}
              aria-current={item.consultationId === selectedId ? "page" : undefined}
              className={cn("mb-1 block rounded-xl px-3 py-3 hover:bg-muted", item.consultationId === selectedId && "bg-brand-soft hover:bg-brand-soft")}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="truncate text-[15px] font-semibold">{item.application.companyName}</span>
                <Badge tone={consultationStatus(item.status).tone} data-status={item.status}>{consultationStatus(item.status).text}</Badge>
              </div>
              <div className="mt-1 text-sm text-ink">신청 금액 {formatWon(item.application.requestedAmountKrw)}</div>
              <div className="mt-0.5 text-sm text-ink-soft">신청 {item.applicationId}</div>
              <div className="mt-0.5 text-sm text-ink-soft">상담 시작 {formatDateTime(item.createdAt)}</div>
            </Link>
          </li>
        ))}
      </ul>
    </aside>
  );
}

export function NewConsultationDialog({ label = "새 상담 건", large = false }: { label?: string; large?: boolean }) {
  const [open, setOpen] = useState(false);
  const applications = useApplications(open);
  const create = useCreateConsultation();
  const navigate = useNavigate();
  const choose = async (applicationId: string) => {
    const created = await create.mutateAsync(applicationId);
    setOpen(false);
    navigate(`/consultations/${encodeURIComponent(created.consultationId)}`);
  };
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size={large ? "md" : "sm"} variant={large ? "primary" : "dark"}><Plus /> {label}</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogTitle>새 상담 건</DialogTitle>
        <DialogDescription>업무 DB에 등록된 합성 신청을 골라 상담 건을 만듭니다. 상담 건은 만든 직원에게 배정됩니다.</DialogDescription>
        <div className="mt-4 flex max-h-[360px] flex-col gap-2 overflow-y-auto">
          {applications.isPending && <p className="text-sm text-ink-soft">신청 목록을 불러오는 중…</p>}
          {applications.isError && <Alert tone="danger">신청 목록을 불러오지 못했습니다.</Alert>}
          {applications.data?.map((application) => (
            <button key={application.applicationId} type="button" disabled={create.isPending} onClick={() => choose(application.applicationId)}
              className="rounded-xl border border-line p-4 text-left hover:border-brand-ink hover:bg-brand-soft disabled:opacity-50">
              <div className="flex items-center justify-between">
                <span className="font-semibold">{application.companyName}</span>
                <span className="text-sm text-ink-soft">{application.applicationId}</span>
              </div>
              <div className="mt-1 text-sm text-ink-soft">{application.productKey} · {formatWon(application.requestedAmountKrw)} · {application.purpose}</div>
            </button>
          ))}
        </div>
        {create.error && <ErrorNotice error={create.error} fallback="상담 건을 만들지 못했습니다." className="mt-3" />}
      </DialogContent>
    </Dialog>
  );
}
