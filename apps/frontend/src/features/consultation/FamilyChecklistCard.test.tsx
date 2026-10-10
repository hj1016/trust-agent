import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { FamilyChecklistCard } from "./FamilyChecklistCard";
import type { ApplicableState, Preparation } from "@/lib/types";

// 테스트 전용 Core 응답 모양(실제 값은 E2E에서 실제 Core로 확인한다).
const rule = (n: number) => `policy-rule:sha256:${String(n).repeat(64)}`;
const state: ApplicableState = {
  familyId: "SIN-PREPAYMENT-FEE", synthetic: true, disclaimer: "합성", businessDate: "2026-10-06", evaluatedAt: "2026-10-06T03:00:00Z",
  internalChecklistUseAllowed: true, blockingReasons: [], warningReasons: [],
  selectedNotice: { noticeId: "SIN-PREPAYMENT-FEE-V2", version: 2, title: "[합성] 공문", effectiveFrom: "2026-10-01", effectiveTo: null },
  rules: [1, 2].map((n) => ({ ruleVersionId: rule(n), order: n - 1, ruleKey: `K${n}`, instruction: `지시 ${n}`, evidenceRequired: true, structuredChange: null,
    jsonPointer: `/rules/${n - 1}`, evidenceText: `원문 ${n}`, evidenceHash: `sha256:${"a".repeat(64)}` })),
  approvedChecklist: { approvedChecklistVersionId: "approved-checklist:1", decisionId: "review-decision:1", effectiveFrom: "2026-10-01", effectiveTo: null,
    items: [1, 2].map((n) => ({ order: n - 1, ruleKey: `K${n}`, instruction: `지시 ${n}`, evidenceRequired: true, structuredChange: null, sourceRuleVersionId: rule(n) })) },
};
const preparation = (status: "READY" | "HOLD"): Preparation => ({
  consultationId: "c1", preparationId: "consultation-preparation:sha256:" + "b".repeat(64), runId: "r", status: status === "READY" ? "READY" : "HOLD",
  preparationComplete: status === "READY", businessDate: "2026-10-06", recordedAt: "", linkedAt: "",
  sections: [{ familyId: "SIN-PREPAYMENT-FEE", required: true, status, holdKind: status === "HOLD" ? "CORE_DECISION" : null, blockingReasons: [],
    selectedNoticeId: "SIN-PREPAYMENT-FEE-V2", approvedChecklistVersionId: status === "READY" ? "approved-checklist:1" : null,
    decisionId: status === "READY" ? "review-decision:1" : null, itemRuleVersionIds: status === "READY" ? [rule(1), rule(2)] : [], itemEvidenceHashes: [] }],
});

function renderCard(prep: Preparation | null, fetchMock = vi.fn()) {
  fetchMock.mockImplementation(async (url: string) =>
    url.includes("/applicable") ? new Response(JSON.stringify(state), { status: 200 }) : new Response(JSON.stringify({}), { status: 201 }));
  vi.stubGlobal("fetch", fetchMock);
  render(
    <QueryClientProvider client={new QueryClient()}>
      <FamilyChecklistCard consultationId="c1" family={{ familyId: "SIN-PREPAYMENT-FEE", required: true, order: 0 }} businessDate="2026-10-06"
        preparation={prep} confirmations={[]} />
    </QueryClientProvider>,
  );
  return fetchMock;
}

describe("FamilyChecklistCard", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shows Core checklist and evidence before any question, and blocks confirmation without a preparation", async () => {
    renderCard(null);
    expect(await screen.findByText("1. 지시 1")).toBeInTheDocument();
    expect(screen.getByText("AI 준비안을 만든 뒤 READY 공문군의 근거 확인을 기록할 수 있습니다.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "근거 확인 기록" })).toBeDisabled();
  });

  it("does not allow confirming a HOLD section", async () => {
    renderCard(preparation("HOLD"));
    expect(await screen.findByText(/보류\(HOLD\)라 확인 기록 대상이 아닙니다/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "근거 확인 기록" })).toBeDisabled();
  });

  it("enables confirmation only after every item is checked and sends the preparation's rule versions", async () => {
    const user = userEvent.setup();
    const fetchMock = renderCard(preparation("READY"));
    const button = await screen.findByRole("button", { name: "근거 확인 기록" });
    const boxes = within(screen.getByTestId("family-SIN-PREPAYMENT-FEE")).getAllByRole("checkbox");
    await user.click(boxes[0]);
    expect(button).toBeDisabled();
    await user.click(boxes[1]);
    expect(button).toBeEnabled();
    await user.click(button);
    const post = fetchMock.mock.calls.find(([url]) => String(url).endsWith("/confirmations"));
    expect(JSON.parse(post![1].body)).toEqual({ preparationId: preparation("READY").preparationId, familyId: "SIN-PREPAYMENT-FEE", ruleVersionIds: [rule(1), rule(2)] });
  });
});
