import { expect, test } from "@playwright/test";
import {
  BUSINESS_DATE, USERS, createConsultation, expectNoHorizontalScroll, expectNoSeriousA11yViolations, login, logout, shot,
} from "./support";

const PREPAYMENT = "SIN-PREPAYMENT-FEE";
const SELLER = "SIN-SELLER-CHECKLIST";
const DECISION_BUTTON = /승인|거절|반려|부결|확정|한도|금리|신용등급/;

test.describe.configure({ mode: "serial" });

let firstConsultation = "";

test("AC-01 비로그인은 로그인 화면으로, API는 401 JSON", async ({ page, request }) => {
  await page.goto("/consultations");
  await expect(page).toHaveURL(/\/login$/);
  const api = await request.get("/api/v1/consultations");
  expect(api.status()).toBe(401);
  expect((await api.json()).code).toBe("UNAUTHENTICATED");
  await page.getByLabel("사용자 ID").fill(USERS.staff.id);
  await page.getByLabel("비밀번호").fill("wrong-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.getByText("사용자 ID 또는 비밀번호가 맞지 않습니다.")).toBeVisible();
  await expectNoSeriousA11yViolations(page, "login");
  await shot(page, "01-login");
});

test("AC-03·04 상담 건 생성 → 상세에서 질문 없이 적용 공문·Checklist·원문 근거가 보인다", async ({ page }) => {
  await login(page, USERS.staff);
  await expect(page).toHaveURL(/\/consultations$/);
  firstConsultation = await createConsultation(page);
  await expect(page.getByTestId("company-name")).not.toBeEmpty();
  const fee = page.getByTestId(`family-${PREPAYMENT}`);
  const seller = page.getByTestId(`family-${SELLER}`);
  await expect(fee.getByText("사용 가능")).toBeVisible();
  await expect(seller.getByText("사용 불가")).toBeVisible();
  await expect(seller.getByText("HUMAN_REVIEW_PENDING")).toBeVisible();

  // 화면 값 = Core 응답 값(브라우저 세션으로 같은 API를 직접 읽어 대조).
  const state = await (await page.request.get(`/api/v1/internal-policy/checklists/${PREPAYMENT}/applicable?businessDate=${BUSINESS_DATE}`)).json();
  for (const item of state.approvedChecklist.items) {
    await expect(fee.getByText(`${item.order + 1}. ${item.instruction}`)).toBeVisible();
  }
  await expect(fee.getByText(state.selectedNotice.title)).toBeVisible();
  // 변경 전후 값과 시행일이 카드 위쪽에 크게 보인다(값은 Core 구조화 값 그대로).
  const changed = state.approvedChecklist.items.find((item: { structuredChange: { after_value?: unknown; unit?: string } | null }) =>
    item.structuredChange && item.structuredChange.unit === "PERCENT");
  const highlights = fee.getByTestId("change-highlights");
  await expect(highlights.getByTestId("change-before")).toHaveText(`${changed.structuredChange.before_value}%`);
  await expect(highlights.getByTestId("change-after")).toHaveText(`${changed.structuredChange.after_value}%`);
  await expect(highlights.getByText(`${changed.structuredChange.effective_on} 시행`)).toBeVisible();
  // 기술 식별자는 기본 화면에 보이지 않고 상세 정보를 펼쳐야 보인다.
  await expect(fee.getByText(changed.ruleKey, { exact: true })).toBeHidden();
  await expect(fee.getByText(state.approvedChecklist.approvedChecklistVersionId)).toBeHidden();
  await fee.getByRole("button", { name: "상세 정보" }).first().click();
  await expect(fee.getByText(state.approvedChecklist.approvedChecklistVersionId)).toBeVisible();
  // 자금 용도는 잘리지 않고 전부 보인다.
  const consultation = await (await page.request.get(`/api/v1/consultations/${encodeURIComponent(firstConsultation)}`)).json();
  const purpose = page.getByTestId("purpose");
  await expect(purpose).toHaveText(consultation.application.purpose);
  expect(await purpose.evaluate((node) => node.scrollWidth <= node.clientWidth)).toBe(true);
  await fee.getByRole("button", { name: /원문 근거 보기/ }).first().click();
  const firstRule = state.rules.find((rule: { ruleVersionId: string }) => rule.ruleVersionId === state.approvedChecklist.items[0].sourceRuleVersionId);
  await expect(fee.locator("blockquote").filter({ hasText: firstRule.evidenceText })).toBeVisible();
  // 원문 근거 영역: 근거 공문·원문 발췌·적용 조건·직원 확인사항이 기본으로 보이고, 해시·규칙 ID는 기술 상세를 펼쳐야 보인다.
  const evidence = fee.getByTestId("evidence-panel").first();
  await expect(evidence.getByText(state.selectedNotice.title)).toBeVisible();
  await expect(evidence.getByRole("heading", { name: "공문 원문 발췌" })).toBeVisible();
  await expect(evidence.getByRole("heading", { name: "적용 조건" })).toBeVisible();
  await expect(evidence.getByRole("heading", { name: "예외" })).toBeVisible();
  await expect(evidence.getByRole("heading", { name: "직원 확인사항(승인 Checklist)" })).toBeVisible();
  await expect(evidence.getByText(firstRule.evidenceHash)).toBeHidden();
  await evidence.getByRole("button", { name: /기술 상세 정보/ }).click();
  await expect(evidence.getByText(firstRule.evidenceHash)).toBeVisible();
  await expect(evidence.getByText(state.approvedChecklist.decisionId)).toBeVisible();
  // 공문 규칙 원문 전체: 기존 적용 조회의 규칙 수와 같고, 문서 파일 링크는 없다.
  await fee.getByRole("button", { name: /공문 규칙 원문 전체 보기/ }).click();
  await expect(fee.getByTestId("notice-rules").locator("li")).toHaveCount(state.rules.length);
  await expect(fee.getByTestId("notice-rules").locator("a")).toHaveCount(0);
  await expect(fee.getByRole("button", { name: "근거 확인 기록" })).toBeDisabled();
  await expect(fee.getByText("AI 준비안을 만든 뒤 READY 공문군의 근거 확인을 기록할 수 있습니다.")).toBeVisible();
  await expect(page.getByText("합성 자료").first()).toBeVisible();
  await expectNoHorizontalScroll(page);
  await expectNoSeriousA11yViolations(page, "detail");
  await shot(page, "02-detail-before-preparation");
});

test("AC-05 준비안 생성: PARTIAL, 섹션별 READY/HOLD, 새로고침 뒤 유지", async ({ page }) => {
  await login(page, USERS.staff);
  await page.goto(`/consultations/${encodeURIComponent(firstConsultation)}?businessDate=${BUSINESS_DATE}`);
  const responses: string[] = [];
  page.on("response", async (response) => {
    if (response.url().includes("/api/")) responses.push(`${response.headers()["x-trustagent-grant"] ?? ""}${await response.text().catch(() => "")}`);
  });
  await page.getByRole("button", { name: /준비안 만들기/ }).click();
  await expect(page.getByTestId("preparation-status")).toHaveAttribute("data-status", "PARTIAL", { timeout: 30_000 });
  await expect(page.getByTestId("preparation-status")).toHaveText("일부 준비·추가 확인 필요");
  const panel = page.getByRole("complementary", { name: "AI 업무지원" });
  await expect(panel.getByTestId(`section-${PREPAYMENT}`).getByText(/중도상환수수료/)).toBeVisible();
  await expect(panel.getByTestId(`section-${PREPAYMENT}`).getByText("준비 완료", { exact: true })).toBeVisible();
  await expect(panel.getByTestId(`section-${SELLER}`).getByText("준비 보류", { exact: true })).toBeVisible();
  await expect(panel.getByText(/검토 대기 상태/)).toBeVisible();
  await expect(panel.getByText(/업무 시스템에 저장했습니다/)).toBeVisible();
  // AC-10: 브라우저가 받은 응답에 grant ID·서비스 토큰 없음.
  expect(responses.join("\n")).not.toMatch(/ai-grant:|Bearer |test-tool-|test-record-|test-inbound-/);
  await shot(page, "03-preparation-partial");
  await page.reload();
  await expect(page.getByTestId("preparation-status")).toHaveAttribute("data-status", "PARTIAL");
});

test("AC-07·08·09 READY 섹션 직원 확인: 전체 체크 뒤에만 기록, HOLD 섹션은 불가, 결정 버튼 없음", async ({ page }) => {
  await login(page, USERS.staff);
  await page.goto(`/consultations/${encodeURIComponent(firstConsultation)}?businessDate=${BUSINESS_DATE}`);
  const fee = page.getByTestId(`family-${PREPAYMENT}`);
  const seller = page.getByTestId(`family-${SELLER}`);
  await expect(seller.getByRole("button", { name: "근거 확인 기록" })).toHaveCount(0);
  const button = fee.getByRole("button", { name: "근거 확인 기록" });
  const boxes = fee.getByRole("checkbox");
  await expect(boxes.first()).toBeEnabled();
  await expect(seller.getByText(/쓸 수 없다고 판정/).first()).toBeVisible();
  const count = await boxes.count();
  expect(count).toBeGreaterThan(1);
  for (let index = 0; index < count - 1; index++) await boxes.nth(index).click();
  await expect(button).toBeDisabled();
  await boxes.nth(count - 1).focus();
  await page.keyboard.press("Space"); // 키보드로 마지막 항목 체크
  await expect(button).toBeEnabled();
  await expect(page.getByText(/대출 승인·거절이나 고객별 적용 승인, 상담 준비 완료가 아닙니다/).first()).toBeVisible();
  await button.click();
  const done = fee.getByTestId(`confirmation-${PREPAYMENT}`);
  await expect(done.getByText(/근거 확인 기록됨 · 확인자 SYN-STAFF-01\(직원\)/)).toBeVisible();
  await expect(done.getByRole("button", { name: "확인 대상 상세 정보" })).toBeVisible();
  await expect(page.getByRole("button", { name: DECISION_BUTTON })).toHaveCount(0);
  await shot(page, "04-confirmed");
  await page.reload();
  await expect(fee.getByTestId(`confirmation-${PREPAYMENT}`).getByText(/근거 확인 기록됨/)).toBeVisible();
  const history = page.getByTestId("confirmation-history");
  await expect(history.getByText("1건")).toBeVisible();
  await expect(history.getByText(/확인자 SYN-STAFF-01\(직원\)/)).toBeVisible();
});

test("AC-03(보완) 같은 신청의 다른 상담 건에는 준비안·확인이 연결되지 않는다", async ({ page }) => {
  await login(page, USERS.staff);
  await page.goto("/consultations");
  const second = await createConsultation(page);
  expect(second).not.toBe(firstConsultation);
  const panel = page.getByRole("complementary", { name: "AI 업무지원" });
  await expect(panel.getByText("이 상담 건에 저장된 준비안이 없습니다.")).toBeVisible();
  await expect(page.getByTestId(`confirmation-${PREPAYMENT}`).getByText(/근거 확인 기록됨/)).toHaveCount(0);
});

test("AC-03(권한) 다른 직원은 남의 상담 건을 열 수 없다", async ({ page }) => {
  await login(page, USERS.both);
  await page.goto(`/consultations/${encodeURIComponent(firstConsultation)}?businessDate=${BUSINESS_DATE}`);
  await expect(page.getByTestId("consultation-error")).toHaveText(/상담 건을 찾을 수 없습니다/);
});

test("AC-02 역할 겸임 계정의 역할 전환과 역할별 화면", async ({ page }) => {
  await login(page, USERS.both);
  await expect(page).toHaveURL(/\/consultations$/);
  await page.getByRole("group", { name: "활성 역할" }).getByRole("button", { name: /검수자/ }).click();
  await expect(page).toHaveURL(/\/reviews$/);
  await expect(page.getByTestId("review-read-only")).toBeVisible();
  await page.goto("/consultations");
  await expect(page.getByText(/직원 활성 역할에서 쓸 수 있습니다/)).toBeVisible();
  const forbidden = await page.request.get("/api/v1/consultations");
  expect(forbidden.status()).toBe(403);
  await page.getByRole("button", { name: /직원 역할로 전환/ }).click();
  await expect(page.getByRole("complementary", { name: "담당 상담 건" })).toBeVisible();
});

test("AC-10·11·12 검수자 읽기 전용, 번들에 비밀·고정 응답 없음, 1024px 레이아웃", async ({ page }) => {
  await login(page, USERS.reviewer);
  await expect(page).toHaveURL(/\/reviews$/);
  await expect(page.getByTestId("review-read-only")).toHaveText(/승인·수정·반려는 이 화면에서 할 수 없습니다/);
  await expect(page.getByRole("main").getByRole("button", { name: /승인|수정|반려|결정/ })).toHaveCount(0);
  await expect(page.getByRole("main").getByText(/^SIN-|^checklist-proposal:/)).toHaveCount(0); // 식별자는 상세 정보 안에만
  // 공문군 코드 대신 기존 데이터의 공문 제목(대상 공문과 같을 때)이 보인다.
  await expect(page.getByTestId("proposal-title").filter({ hasText: "셀러론 상담 준비 체크리스트 안내 v2" })).toBeVisible();
  await expectNoSeriousA11yViolations(page, "reviews");
  await shot(page, "05-reviews-read-only");
  const scripts = await page.locator("script[src]").evaluateAll((nodes) => nodes.map((node) => (node as HTMLScriptElement).src));
  for (const src of scripts) {
    const body = await (await page.request.get(src)).text();
    expect(body).not.toMatch(/ai-grant:|TRUST_AGENT_|Bearer [A-Za-z0-9]|test-tool-|consultation-preparation:sha256:[a-f0-9]{64}/);
  }
  await logout(page);
  await page.setViewportSize({ width: 1024, height: 768 });
  await login(page, USERS.staff);
  await page.goto(`/consultations/${encodeURIComponent(firstConsultation)}?businessDate=${BUSINESS_DATE}`);
  await expect(page.getByTestId(`family-${PREPAYMENT}`)).toBeVisible();
  await expectNoHorizontalScroll(page);
  await shot(page, "06-detail-1024");
});
