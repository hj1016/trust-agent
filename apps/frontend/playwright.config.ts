import { defineConfig, devices } from "@playwright/test";

// 실제 Core + PostgreSQL + AI 서비스로 실행한다(UiEndToEndRunner가 띄우고 E2E_BASE_URL을 넘긴다). 목 서버는 없다.
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: [["list"], ["json", { outputFile: process.env.E2E_REPORT ?? "test-results/e2e-report.json" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL,
    locale: "ko-KR",
    timezoneId: "Asia/Seoul",
    trace: "off",
    ...devices["Desktop Chrome"],
    viewport: { width: 1280, height: 800 },
  },
});
