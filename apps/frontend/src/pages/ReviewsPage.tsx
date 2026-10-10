import { Eye } from "lucide-react";
import { reviewDecision } from "@/lib/labels";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { TEXT } from "@/lib/messages";
import { formatDateTime, seoulToday } from "@/lib/utils";
import { TechnicalDetails } from "@/components/TechnicalDetails";
import { useNoticeTitle } from "@/features/consultation/noticeTitle";
import type { ProposalSummary } from "@/lib/types";
import { useProposals } from "@/features/consultation/queries";

/** 검수자 화면(읽기 전용). 승인·수정·반려 경로는 TASK-027이며 이 화면에는 그런 버튼이 없다. */
export function ReviewsPage() {
  const proposals = useProposals();
  return (
    <div className="mx-auto flex max-w-[1080px] flex-col gap-5 p-6">
      <Alert tone="hold" data-testid="review-read-only"><Eye className="mr-1 inline size-4" />{TEXT.reviewReadOnly}</Alert>
      <Card>
        <CardHeader><CardTitle>변경안 검수 현황</CardTitle><Badge tone="neutral">읽기 전용</Badge></CardHeader>
        <CardContent>
          {proposals.isPending && <p className="text-sm text-ink-soft">불러오는 중…</p>}
          {proposals.isError && <Alert tone="danger">목록을 불러오지 못했습니다.</Alert>}
          {proposals.data && proposals.data.length === 0 && <p className="text-sm text-ink-soft">변경안이 없습니다.</p>}
          {proposals.data && proposals.data.length > 0 && (
            <table className="w-full text-left text-sm">
              <thead className="text-sm text-ink-soft">
                <tr><th className="py-2">대상 공문</th><th>항목 수</th><th>생성</th><th>기록된 검토 결정</th></tr>
              </thead>
              <tbody>
                {proposals.data.map((proposal) => <ProposalRow key={proposal.proposalId} proposal={proposal} />)}
              </tbody>
            </table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function ProposalRow({ proposal }: { proposal: ProposalSummary }) {
  // 사람이 읽는 이름은 기존 적용 조회의 공문 제목(대상 공문과 같을 때만). 다르면 코드를 그대로 보인다.
  const title = useNoticeTitle(proposal.familyId, seoulToday(), proposal.targetNoticeId);
  return (
    <tr className="border-t border-line align-top">
      <td className="py-3 pr-4">
        <div className="font-semibold" data-testid="proposal-title">{title}</div>
        <TechnicalDetails rows={[["대상 공문 ID", proposal.targetNoticeId], ["공문군", proposal.familyId], ["변경안 ID", proposal.proposalId]]} />
      </td>
      <td className="py-3">{proposal.itemCount}</td>
      <td className="py-3">{formatDateTime(proposal.createdAt)}</td>
      <td className="py-3"><Badge tone={reviewDecision(proposal.decision).tone} data-status={proposal.decision ?? ""}>{reviewDecision(proposal.decision).text}</Badge></td>
    </tr>
  );
}
