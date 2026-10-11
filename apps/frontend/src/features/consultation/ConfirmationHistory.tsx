import { History } from "lucide-react";
import { roleLabel } from "@/lib/labels";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { TEXT } from "@/lib/messages";
import type { Confirmation } from "@/lib/types";
import { formatDateTime } from "@/lib/utils";
import { TechnicalDetails } from "@/components/TechnicalDetails";
import { useNoticeTitle } from "./noticeTitle";

/** 이 상담 건의 직원 근거 확인 이력(Core 기록 그대로, 최신이 위). 확인은 근거를 읽었다는 기록이며 승인·준비 완료가 아니다. */
export function ConfirmationHistory({ confirmations, loading }: { confirmations: Confirmation[] | undefined; loading: boolean }) {
  const rows = [...(confirmations ?? [])].sort((a, b) => b.confirmedAt.localeCompare(a.confirmedAt));
  return (
    <Card data-testid="confirmation-history">
      <CardHeader>
        <CardTitle className="flex items-center gap-2"><History className="size-5" /> 직원 근거 확인 이력</CardTitle>
        <Badge tone="neutral">{rows.length}건</Badge>
      </CardHeader>
      <CardContent>
        <p className="mb-3 text-sm text-ink-soft">{TEXT.confirmationMeaning}</p>
        {loading && <p className="text-sm text-ink-soft">불러오는 중…</p>}
        {!loading && rows.length === 0 && <p className="text-sm text-ink-soft">아직 기록된 직원 확인이 없습니다.</p>}
        {rows.length > 0 && (
          <ol className="flex flex-col gap-2">
            {rows.map((row) => <HistoryRow key={row.confirmationId} row={row} />)}
          </ol>
        )}
      </CardContent>
    </Card>
  );
}

function HistoryRow({ row }: { row: Confirmation }) {
  const title = useNoticeTitle(row.familyId, row.businessDate, row.selectedNoticeId);
  return (
    <li className="rounded-xl border border-line bg-canvas/60 px-4 py-3 text-[15px]">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-semibold">{title}</span>
        <span className="text-ink-soft">{formatDateTime(row.confirmedAt)}</span>
      </div>
      <p className="mt-1 text-ink-soft">확인자 {row.confirmedBy}({roleLabel(row.activeRole).text}) · 업무일 {row.businessDate} · 항목 {row.confirmedRuleVersionIds.length}개</p>
      <TechnicalDetails rows={[
        ["공문군", row.familyId],
        ["확인 대상 공문", row.selectedNoticeId],
        ["승인 checklist", row.approvedChecklistVersionId],
        ["검수 결정", row.decisionId],
        ["준비안", row.preparationId],
        ["확인 기록 ID", row.confirmationId],
      ]} />
    </li>
  );
}
