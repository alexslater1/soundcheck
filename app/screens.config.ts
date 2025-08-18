import { defineConfig, devices } from '@playwright/test';

/**
 * Screenshots for docs/screens/web (and the README): `make screens`, against the running local
 * stack through the edge, like `make e2e`. Synthetic data only: two invented @example.com accounts
 * and the `make clips` clips; both accounts are deleted at the end and `make e2e`'s prune step hides
 * their posts. Phone-sized window, 2×, dark mode.
 */
export default defineConfig({
  testDir: './screens',
  timeout: 300_000,
  expect: { timeout: 20_000 },
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8088',
    ...devices['Desktop Chrome'],
    viewport: { width: 412, height: 915 },
    deviceScaleFactor: 2,
    colorScheme: 'dark',
  },
});
