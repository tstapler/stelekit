import { defineConfig, devices } from '@playwright/test';

const DEMO_DIST =
  process.env.DEMO_DIST ??
  '../kmp/build/dist/wasmJs/productionExecutable';

export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  reporter: process.env.CI ? 'github' : 'list',

  webServer: {
    command: 'node server.mjs',
    url: 'http://localhost:8787',
    reuseExistingServer: !process.env.CI,
    env: { DEMO_DIST, PORT: '8787' },
  },

  use: {
    baseURL: 'http://localhost:8787',
    trace: 'on-first-retry',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
