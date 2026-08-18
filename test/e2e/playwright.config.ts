import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  use: {
    baseURL: process.env.BASE_URL ?? "http://localhost:8080",
  },
  timeout: 30_000,
  retries: 0,
  reporter: [["list"], ["html", { open: "never" }]],
});
