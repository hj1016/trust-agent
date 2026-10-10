import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";

// 개발 서버는 Core(기본 8080)로 API·로그인을 넘겨 같은 출처 세션 쿠키를 쓴다. 배포 형태는 Core가 dist를 정적 자원으로 제공한다.
const core = process.env.TRUST_AGENT_CORE_URL ?? "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": path.resolve(__dirname, "src") } },
  server: {
    proxy: {
      "/api": core,
      "/login": { target: core, bypass: (req) => (req.method === "GET" ? "/index.html" : undefined) },
      "/logout": core,
    },
  },
  build: { outDir: "dist", sourcemap: false },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
