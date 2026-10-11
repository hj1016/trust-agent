import { expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import path from "node:path";

export const BUSINESS_DATE = process.env.E2E_BUSINESS_DATE ?? "2026-10-06";
export const USERS = {
  staff: { id: "SYN-STAFF-01", password: process.env.E2E_STAFF_PASSWORD ?? "" },
  reviewer: { id: "SYN-REVIEWER-01", password: process.env.E2E_REVIEWER_PASSWORD ?? "" },
  both: { id: "SYN-STAFF-REVIEWER-01", password: process.env.E2E_BOTH_PASSWORD ?? "" },
};
const evidenceDir = process.env.E2E_EVIDENCE_DIR ?? "test-results/evidence";

export async function login(page: Page, user: { id: string; password: string }) {
  await page.goto("/login");
  await page.getByLabel("사용자 ID").fill(user.id);
  await page.getByLabel("비밀번호").fill(user.password);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.getByTestId("principal")).toHaveText(user.id);
}

export async function logout(page: Page) {
  await page.getByRole("button", { name: "로그아웃" }).click();
  await expect(page).toHaveURL(/\/login$/);
}

export async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(evidenceDir, `${name}.png`), fullPage: false });
}

/** 접근성 검사: serious·critical 위반 0. */
export async function expectNoSeriousA11yViolations(page: Page, label: string) {
  // @axe-core/playwright의 Page 타입이 설치된 Playwright보다 오래돼 타입만 맞춘다(실행 객체는 같다).
  const results = await new AxeBuilder({ page: page as unknown as ConstructorParameters<typeof AxeBuilder>[0]["page"] }).analyze();
  const serious = results.violations.filter((violation) => violation.impact === "serious" || violation.impact === "critical");
  expect(serious.map((violation) => `${label}: ${violation.id} ${violation.help} (${violation.nodes.length}) ` +
    violation.nodes.slice(0, 8).map((node) => `${node.target.join(" ")} ${(node.any[0]?.message ?? "").slice(0, 120)}`).join(" | "))).toEqual([]);
}

export async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
}

export async function createConsultation(page: Page): Promise<string> {
  await page.getByRole("complementary", { name: "담당 상담 건" }).getByRole("button", { name: "새 상담 건" }).click();
  await page.getByRole("dialog").getByRole("button", { name: /SW-APPLICATION-001/ }).click();
  await expect(page).toHaveURL(/\/consultations\/consultation/);
  const id = decodeURIComponent(page.url().split("/consultations/")[1].split("?")[0]);
  await page.goto(`/consultations/${encodeURIComponent(id)}?businessDate=${BUSINESS_DATE}`);
  return id;
}
