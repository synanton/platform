import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  testMatch: "entity-graph.live.spec.ts",
  use: {
    baseURL: process.env.BASE_URL ?? "http://127.0.0.1:5173",
  },
  timeout: 360_000,
  retries: 0,
  reporter: [["list"]],
  webServer: {
    command: "npm run dev -- --host 127.0.0.1 --port 5173",
    cwd: "../../ui/syntology-admin",
    url: "http://127.0.0.1:5173",
    reuseExistingServer: true,
    timeout: 120_000,
    env: {
      ...process.env,
      VITE_RELIX_URL: process.env.VITE_RELIX_URL ?? "http://127.0.0.1:18084",
    },
  },
});
