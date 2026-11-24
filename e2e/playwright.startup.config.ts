import { defineConfig } from '@playwright/test';
import config from './playwright.config';

// This regression owns a headerless server and must not depend on a built WASM app.
export default defineConfig({
  ...config,
  testMatch: ['startup-cache.spec.ts', 'startup-sqlite.spec.ts'],
  webServer: undefined,
});
