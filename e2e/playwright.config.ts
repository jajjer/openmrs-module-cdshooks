import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright configuration for the cdshooks E2E tests.
 *
 * Targets the isolated cdshooks-dev stack on port 8081. To run:
 *
 *   cd e2e
 *   npm install
 *   npx playwright install chromium
 *   npx playwright test
 */
export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:8081",
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    ignoreHTTPSErrors: true,
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
