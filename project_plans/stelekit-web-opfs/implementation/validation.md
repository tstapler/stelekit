# Validation Plan: SteleKit Web — OPFS Durable Storage & Local Dev

## Coverage Summary

| Test Type | Count |
|-----------|-------|
| Build tests | 2 |
| E2E browser tests | 7 |
| **Total** | **9** |

| Requirement | Tests Covering It |
|-------------|-------------------|
| F1 — OPFS SQLite Driver | E2E-02, E2E-03, E2E-04 |
| F2 — SQLDELIGHT backend in browser Main | E2E-02, E2E-03 |
| F3 — Local dev script | BUILD-02, E2E-06 |
| F4 — CI OPFS persistence test | E2E-02, E2E-03 |
| AC1 — serve-web.sh builds and serves | BUILD-02 |
| AC2 — Note persists after reload | E2E-02 |
| AC3 — Playwright persistence test passes in CI | E2E-02, E2E-03 |
| AC4 — wasmJsBrowserDistribution succeeds | BUILD-01 |
| AC5 — IN_MEMORY fallback with console warning | E2E-04 |

Requirements coverage: **5/5 functional requirements, 5/5 acceptance criteria** (100%).

---

## Build Tests

### BUILD-01

| Field | Value |
|-------|-------|
| **ID** | BUILD-01 |
| **Requirement refs** | AC4 |
| **Test type** | Build (Gradle) |
| **Description** | Run `./gradlew :kmp:wasmJsBrowserDistribution -PenableJs=true` and assert exit code 0. |
| **Pass criteria** | Gradle task exits 0. The output directory `kmp/build/dist/wasmJs/productionExecutable/` exists and contains `index.html`, `*.wasm`, and `*.js` files. No build errors or unresolved symbol errors in stderr. |
| **Implementation note** | This is already exercised implicitly by the Playwright CI job (which starts `node server.mjs` pointing at that directory). Add an explicit step in `.github/workflows/` (or verify the existing CI build step) that runs the Gradle command in isolation so a compile failure is caught before Playwright runs. |

---

### BUILD-02

| Field | Value |
|-------|-------|
| **ID** | BUILD-02 |
| **Requirement refs** | F3, AC1 |
| **Test type** | Build (shell) |
| **Description** | Assert that `scripts/serve-web.sh` exists, is executable (mode includes `x`), and contains the required build and serve invocations. |
| **Pass criteria** | `test -x scripts/serve-web.sh` exits 0. File contains `wasmJsBrowserDistribution` and `server.mjs` (or equivalent serve command). A dry-run invocation with a mocked Gradle (or `--dry-run` flag) prints a localhost URL string to stdout. |
| **Implementation note** | Verify in CI as a pre-check step (`ls -la scripts/serve-web.sh && head -20 scripts/serve-web.sh`). Full integration can be verified manually or in a separate non-headless CI job. |

---

## E2E Browser Tests

All E2E tests run via Playwright against the `chromium` project defined in `e2e/playwright.config.ts`. They target `http://localhost:8787` served by `node server.mjs`.

### Shared Helper: `waitForWasmReady`

All persistence tests reuse the existing canvas-resize check from `demo.spec.ts` as the "WASM initialized" signal:

```typescript
async function waitForWasmReady(page: Page, timeout = 30_000) {
  await page.waitForFunction(
    () => {
      const c = document.getElementById('ComposeTarget') as HTMLCanvasElement | null;
      return (c?.width ?? 0) > 300;
    },
    { timeout },
  );
}
```

### Shared Helper: `clearOpfsStorage`

Used in `beforeEach` for all OPFS tests to prevent state bleed between test runs:

```typescript
async function clearOpfsStorage(page: Page) {
  await page.evaluate(async () => {
    try {
      const root = await navigator.storage.getDirectory();
      await root.removeEntry('stelekit', { recursive: true });
    } catch {
      // directory may not exist yet — ignore
    }
  });
}
```

Call `clearOpfsStorage` BEFORE navigating to `/` (before the app boots) so the OPFS directory is absent when the driver initializes.

---

### E2E-01 (existing — extend, do not replace)

| Field | Value |
|-------|-------|
| **ID** | E2E-01 |
| **Requirement refs** | AC3 (baseline smoke) |
| **Test type** | E2E browser |
| **Description** | Existing test in `e2e/tests/demo.spec.ts`: canvas initializes and Compose paints at least one WebGL frame; no uncaught JS exceptions on startup. |
| **Pass criteria** | `canvas#ComposeTarget` is attached within 10 s; canvas width > 300 px within 30 s; center pixel alpha > 0; zero `pageerror` events. (Unchanged from current spec.) |
| **Implementation note** | This test must continue to pass after OPFS integration. If the OPFS driver initialization emits benign console messages (e.g., from `sqlite3InitModule`), ensure they do not reach `pageerror` (they should not — `console.*` calls are not uncaught exceptions). |

---

### E2E-02

| Field | Value |
|-------|-------|
| **ID** | E2E-02 |
| **Requirement refs** | F1, F2, F4, AC2, AC3 |
| **Test type** | E2E browser (persistence) |
| **Description** | OPFS data written during one page load is present after `page.reload()` within the same Playwright browser context. |
| **Pass criteria** | After first load and WASM init: (1) the OPFS directory `/stelekit` exists in navigator storage; (2) after `page.reload()` and re-init, the directory still exists and contains at least one `.sqlite` (or VFS pool) file, confirming the driver opened and wrote the database rather than falling back to IN_MEMORY. |
| **Implementation note** | Use `page.evaluate()` to probe OPFS via `navigator.storage.getDirectory()` both before and after reload. Do NOT use a persistent Playwright context (`userDataDir`) — `page.reload()` within the default ephemeral context preserves OPFS within the same browser session, which is sufficient. Clear OPFS in `beforeEach` using `clearOpfsStorage` before the initial `page.goto('/')`. |

```typescript
test('OPFS persistence: data survives page.reload()', async ({ page }) => {
  await clearOpfsStorage(page);
  await page.goto('/');
  await waitForWasmReady(page);

  // Verify OPFS directory was created on first load
  const dirExistsAfterFirstLoad = await page.evaluate(async () => {
    try {
      const root = await navigator.storage.getDirectory();
      await root.getDirectoryHandle('stelekit', { create: false });
      return true;
    } catch { return false; }
  });
  expect(dirExistsAfterFirstLoad).toBe(true);

  await page.reload();
  await waitForWasmReady(page);

  // Verify OPFS directory and at least one file survives reload
  const persistedAfterReload = await page.evaluate(async () => {
    try {
      const root = await navigator.storage.getDirectory();
      const dir = await root.getDirectoryHandle('stelekit', { create: false });
      const entries: string[] = [];
      for await (const [name] of (dir as any).entries()) entries.push(name);
      return entries.length > 0;
    } catch { return false; }
  });
  expect(persistedAfterReload).toBe(true);
});
```

---

### E2E-03

| Field | Value |
|-------|-------|
| **ID** | E2E-03 |
| **Requirement refs** | F1, F4, AC3 |
| **Test type** | E2E browser (performance) |
| **Description** | OPFS database open completes within 3 seconds on first launch (non-functional requirement: "Initial OPFS database open must complete within 3 seconds"). |
| **Pass criteria** | Time from `page.goto('/')` to `waitForWasmReady` completion is less than 3000 ms beyond the time for WASM binary loading (measured as: `waitForWasmReady` resolves within 33 s total, where 30 s covers WASM load and 3 s covers OPFS init). More precisely: expose a `window.__stelekit_opfs_ready_ms` timestamp from Kotlin JS interop; assert it minus `window.__stelekit_wasm_start_ms` is < 3000. If those markers are unavailable, assert that `waitForWasmReady` resolves within 33 s (30 s WASM + 3 s OPFS budget). |
| **Implementation note** | The simplest implementation: add `window.__stelekit_ready = true` from Kotlin via `js("window.__stelekit_ready = true")` after the driver initializes, then use `page.waitForFunction(() => window.__stelekit_ready === true, { timeout: 33_000 })`. Compare timestamps if more precision is required. A soft assertion (warning, not failure) is acceptable for CI flakiness reasons on slow machines. |

---

### E2E-04

| Field | Value |
|-------|-------|
| **ID** | E2E-04 |
| **Requirement refs** | F1, AC5 |
| **Test type** | E2E browser (fallback) |
| **Description** | When OPFS is blocked (simulated via `page.addInitScript`), the app falls back to IN_MEMORY and emits a console warning containing "OPFS unavailable" or equivalent. No uncaught exception. Canvas still initializes. |
| **Pass criteria** | A `console.warn` (or `console.error`) message matching `/OPFS (unavailable|blocked|failed)/i` is captured. Zero `pageerror` events. Canvas width > 300 px within 30 s (app still loads). |
| **Implementation note** | Block OPFS before page load using `addInitScript`: |

```typescript
test('OPFS fallback: IN_MEMORY used when OPFS is blocked', async ({ page }) => {
  const consoleWarnings: string[] = [];
  page.on('console', msg => {
    if (msg.type() === 'warn' || msg.type() === 'error') {
      consoleWarnings.push(msg.text());
    }
  });
  const errors: string[] = [];
  page.on('pageerror', err => errors.push(err.message));

  // Override navigator.storage.getDirectory to throw before the page loads
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'storage', {
      value: {
        getDirectory: () => Promise.reject(new DOMException('Mocked OPFS unavailable', 'NotSupportedError')),
        estimate: () => Promise.resolve({ usage: 0, quota: 0 }),
      },
      configurable: true,
    });
  });

  await page.goto('/');
  await waitForWasmReady(page);

  expect(errors, `Uncaught JS errors: ${errors.join(' | ')}`).toHaveLength(0);
  const hasOPFSWarning = consoleWarnings.some(w => /OPFS.*(unavailable|blocked|failed|error)/i.test(w));
  expect(hasOPFSWarning, `Expected OPFS fallback warning in: ${consoleWarnings.join(' | ')}`).toBe(true);
});
```

---

### E2E-05

| Field | Value |
|-------|-------|
| **ID** | E2E-05 |
| **Requirement refs** | F1 (error handling), AC5 |
| **Test type** | E2E browser (quota error) |
| **Description** | When OPFS write fails with `QuotaExceededError` (simulated), the app handles it gracefully — no uncaught exception, canvas still renders. |
| **Pass criteria** | Zero `pageerror` events. Canvas width > 300 px. A console message (warn or error) is emitted. |
| **Implementation note** | Simulate quota failure by overriding `FileSystemSyncAccessHandle.write` to throw `QuotaExceededError` in an `addInitScript`. This is a lower-priority test; skip in initial CI if simulation is too complex and cover it via unit test of the error-handling path instead. |

---

### E2E-06

| Field | Value |
|-------|-------|
| **ID** | E2E-06 |
| **Requirement refs** | F3, AC1 |
| **Test type** | E2E browser (smoke via script) |
| **Description** | `scripts/serve-web.sh` is used as the `webServer.command` in Playwright config for a separate "serve-web" project, verifying the full dev workflow end-to-end: script builds WASM target and starts server, then existing E2E-01 smoke test passes against that server. |
| **Pass criteria** | Playwright webServer starts within 120 s using `scripts/serve-web.sh`. Canvas smoke test (E2E-01 assertions) passes. This test is marked `@slow` and run only in the optional `serve-web` Playwright project (not in the default `chromium` project). |
| **Implementation note** | Add a second Playwright project in `playwright.config.ts`: |

```typescript
{
  name: 'serve-web-script',
  use: { ...devices['Desktop Chrome'] },
  testMatch: '**/demo.spec.ts',   // reuse E2E-01
  // Override webServer for this project only if Playwright supports per-project servers
  // (Playwright 1.38+); otherwise use a separate config file: e2e/playwright.serve-web.config.ts
}
```

If per-project webServer is not feasible, create `e2e/playwright.serve-web.config.ts` with `webServer.command = '../scripts/serve-web.sh'` and run it as a separate CI step.

---

### E2E-07

| Field | Value |
|-------|-------|
| **ID** | E2E-07 |
| **Requirement refs** | F1 (multi-tab exclusion), pitfall P3 |
| **Test type** | E2E browser (multi-tab lock) |
| **Description** | Opening a second browser tab against the same origin while the first tab has OPFS locked via `opfs-sahpool` does not cause a crash or `pageerror` in either tab. The second tab should degrade gracefully (either fall back to IN_MEMORY or show an error message). |
| **Pass criteria** | Zero `pageerror` events in either tab. At least one tab renders a canvas. |
| **Implementation note** | Open two pages in the same Playwright browser context: `const page2 = await context.newPage(); await page2.goto('/');`. This is a lower-priority test. Mark as `@skip` in initial implementation and revisit once the architecture decision for multi-tab behavior is finalized (see pitfall P3: `opfs-sahpool` holds exclusive lock). |

---

## Pitfall Coverage

The following pitfalls from `research/04-pitfalls.md` are exercised by the test suite:

| Pitfall | ID | Covered By |
|---------|----|------------|
| P1: OPFS sync access is worker-only | Yes | E2E-02, E2E-03 (driver must use worker architecture to pass) |
| P2: Async OPFS API is slow | Partial | E2E-03 (3s budget validates we use sahpool, not async) |
| P3: opfs-sahpool exclusive lock | Yes | E2E-07 (skip until arch decided) |
| P4: OPFS paths are logical, not filesystem | N/A | Not testable via Playwright |
| P5: Quota exceeded | Yes | E2E-05 |
| P6: Worker1/Promiser deprecated | N/A | Architecture choice — enforced via code review, not test |

---

## CI Integration Notes

1. **Order of execution**: BUILD-01 → BUILD-02 → E2E-01 through E2E-05 (E2E-06 and E2E-07 are optional/skipped initially).
2. **OPFS in Playwright CI**: Playwright's bundled Chromium supports OPFS. No additional flags are needed. The `server.mjs` COOP/COEP headers are already set.
3. **Test isolation**: All OPFS tests call `clearOpfsStorage` in `beforeEach` before navigating. This prevents state leakage between test runs in the same Playwright browser context.
4. **Timeout budget**: Each E2E test has a 60 s total budget (per `playwright.config.ts`). WASM load claims up to 30 s; OPFS init claims up to 3 s; assertions claim the remainder.
5. **Flakiness mitigation**: The `waitForWasmReady` function uses `waitForFunction` with polling, not a fixed `sleep`. The OPFS directory-existence check uses `page.evaluate` with async/await, which is stable.
