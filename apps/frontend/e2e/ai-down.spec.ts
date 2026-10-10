import { expect, test } from "@playwright/test";
import { USERS, createConsultation, login, shot } from "./support";

// UiEndToEndRunner가 AI 서비스를 내린 뒤 이 파일만 실행한다.
test("AC-06 AI 서비스 중단: AI 준비안 없음 안내, Core checklist는 계속 보이고 가짜 준비안 없음", async ({ page }) => {
  test.skip(process.env.E2E_AI_DOWN !== "1", "AI 서비스 중단 단계에서만 실행");
  await login(page, USERS.staff);
  await createConsultation(page);
  await page.getByRole("button", { name: /준비안 만들기/ }).click();
  await expect(page.getByTestId("ai-unavailable")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("preparation-status")).toHaveCount(0);
  await expect(page.getByTestId("family-SIN-PREPAYMENT-FEE").getByText("사용 가능")).toBeVisible();
  await expect(page.getByTestId("family-SIN-PREPAYMENT-FEE").getByRole("checkbox").first()).toBeVisible();
  await shot(page, "07-ai-down");
});
