import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReviewsPage } from "./ReviewsPage";

describe("ReviewsPage", () => {
  it("is read-only, shows the notice title from the existing lookup, and offers no decision buttons", async () => {
    // 테스트 전용 응답(제품 코드에는 고정 응답이 없다). 호출마다 새 Response를 돌려준다.
    vi.stubGlobal("fetch", vi.fn().mockImplementation(async (url: string) => String(url).includes("/applicable")
      ? new Response(JSON.stringify({ selectedNotice: { noticeId: "SIN-SELLER-CHECKLIST-V2", version: 2, title: "[합성] 셀러론 상담 준비 체크리스트 안내 v2", effectiveFrom: "2026-10-01", effectiveTo: null } }), { status: 200 })
      : new Response(JSON.stringify({ proposals: [
          { proposalId: "proposal:abc", familyId: "SIN-SELLER-CHECKLIST", targetNoticeId: "SIN-SELLER-CHECKLIST-V2", itemCount: 3, createdAt: "2026-10-05T03:00:00Z", decision: null },
        ] }), { status: 200 })));
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><ReviewsPage /></QueryClientProvider>);
    expect(await screen.findByText("[합성] 셀러론 상담 준비 체크리스트 안내 v2")).toBeInTheDocument();
    expect(screen.getByText("검토 결정 없음")).toBeInTheDocument();
    expect(screen.getByTestId("review-read-only")).toHaveTextContent("승인·수정·반려는 이 화면에서 할 수 없습니다");
    // 결정 버튼은 없다. 남는 버튼은 식별자를 펼치는 '상세 정보'뿐이다.
    expect(screen.queryAllByRole("button", { name: /승인|수정|반려|결정/ })).toHaveLength(0);
    expect(screen.queryAllByRole("button").every((button) => button.textContent?.includes("상세 정보"))).toBe(true);
    vi.unstubAllGlobals();
  });
});
