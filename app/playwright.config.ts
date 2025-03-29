import { defineConfig, devices } from '@playwright/test';

/**
 * Web E2E: journeys against the app served through the Caddy edge (http://localhost:8088),
 * which proxies `/` to the Expo web server and `/api/*` to the API. Start `make up`, `make api` and
 * `make app` (or the static export) first; `make e2e` wraps this.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  retries: 0,
  // One journey at a time: they share one machine, one IP (the auth rate limit) and one transcode worker.
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8088',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
