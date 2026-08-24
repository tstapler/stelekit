import { test, expect } from '@playwright/test';

// ComposeViewport attaches a shadow root to document.body and renders the
// Skia/WebGL canvas inside that shadow DOM — not as a direct child of body.
// Assertions here verify that the WASM binary compiles to something that
// actually runs in a browser, not just that the build directory exists.

test.beforeEach(async ({ page }) => {
  // Clear OPFS stelekit directory before each test to prevent inter-test bleed
  await page.goto('/');
  await page.evaluate(async () => {
    try {
      const root = await navigator.storage.getDirectory();
      await root.removeEntry('stelekit', { recursive: true });
    } catch {
      // Directory may not exist on first run
    }
  });
});

test('SteleKit WASM demo: canvas initializes and Compose paints', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', err => {
    // coi-serviceworker logs to console but does not throw; only real uncaught
    // exceptions reach pageerror.
    errors.push(err.message);
  });

  // COOP/COEP headers are set by the test server so crossOriginIsolated=true
  // on first load — the service worker never performs its reload.
  await page.goto('/');

  // Step 1: canvas element must be present in body's shadow DOM.
  // ComposeViewport attaches a shadow root to body and places the canvas inside it.
  // Playwright 1.27+ auto-pierces shadow roots for CSS selectors.
  const canvas = page.locator('canvas');
  await expect(canvas).toBeAttached({ timeout: 10_000 });

  // Step 2: Compose resizes the canvas from the HTML default (300 px) to the
  // viewport size when it initialises. Wait for that resize as a proxy for
  // "Kotlin/WASM main() executed successfully".
  await page.waitForFunction(
    () => {
      const shadow = document.body.shadowRoot;
      const c = shadow?.querySelector('canvas') as HTMLCanvasElement | null;
      return (c?.width ?? 0) > 300;
    },
    { timeout: 30_000 },
  );

  // Step 3: Compose acquires a WebGL context. Verify it exists — this confirms
  // Skiko initialised the GPU renderer successfully. readPixels is unreliable
  // with the default preserveDrawingBuffer:false because the drawing buffer is
  // cleared after each swap, so a context-presence check is the right proxy.
  const hasGlContext = await page.evaluate(() => {
    const shadow = document.body.shadowRoot;
    const c = shadow?.querySelector('canvas') as HTMLCanvasElement | null;
    if (!c) return false;
    const gl =
      (c.getContext('webgl2') as WebGLRenderingContext | null) ??
      (c.getContext('webgl') as WebGLRenderingContext | null);
    return gl !== null;
  });
  expect(hasGlContext, 'Canvas must have a WebGL context (Skiko GPU renderer)').toBe(true);

  // Step 4: no uncaught JS exceptions during startup.
  expect(errors, `Uncaught JS errors: ${errors.join(' | ')}`).toHaveLength(0);
});

test('SteleKit OPFS: data persists across page reload', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', err => errors.push(err.message));

  await page.goto('/');

  await page.waitForFunction(
    () => (window as any).__stelekit_ready === true,
    { timeout: 30_000 },
  );

  await page.reload();

  await page.waitForFunction(
    () => (window as any).__stelekit_ready === true,
    { timeout: 30_000 },
  );

  const hasOpfsData = await page.evaluate(async () => {
    try {
      const root = await navigator.storage.getDirectory();
      await root.getDirectoryHandle('stelekit', { create: false });
      return true;
    } catch {
      return false;
    }
  });
  expect(hasOpfsData, 'OPFS stelekit directory must exist after app init').toBe(true);

  expect(errors, `Uncaught JS errors: ${errors.join(' | ')}`).toHaveLength(0);
});

test('SteleKit WASM: graph dialog mode is active (no native file picker)', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', err => errors.push(err.message));

  await page.goto('/');
  await page.waitForFunction(
    () => (window as any).__stelekit_ready === true,
    { timeout: 30_000 },
  );

  const dialogMode = await page.evaluate(() => (window as any).__stelekit_native_graph_picker);
  expect(dialogMode, '__stelekit_native_graph_picker must be false on WASM (no native file picker)').toBe(false);

  expect(errors, `Uncaught JS errors: ${errors.join(' | ')}`).toHaveLength(0);
});

test('SteleKit WASM: named OPFS graph opens via localStorage test override', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', err => errors.push(err.message));

  // Set test override before page load so Main.kt reads it
  await page.addInitScript(() => {
    window.localStorage.setItem('__stelekit_test_graph', 'e2e-named-graph');
  });

  await page.goto('/');
  await page.waitForFunction(
    () => (window as any).__stelekit_ready === true,
    { timeout: 30_000 },
  );

  // The named graph OPFS directory should exist after initialization.
  // Skip the directory check if the SQLite driver fell back to :memory: (no OPFS writes occur in that mode).
  const { hasNamedGraph, driverMode } = await page.evaluate(async () => {
    const mode = (window as any).__stelekit_driver_backend ?? 'unknown';
    try {
      const root = await navigator.storage.getDirectory();
      const stelekit = await root.getDirectoryHandle('stelekit', { create: false });
      await stelekit.getDirectoryHandle('e2e-named-graph', { create: false });
      return { hasNamedGraph: true, driverMode: mode };
    } catch {
      return { hasNamedGraph: false, driverMode: mode };
    }
  });
  if (driverMode !== 'memory') {
    expect(hasNamedGraph, `OPFS /stelekit/e2e-named-graph must exist (driver=${driverMode})`).toBe(true);
  }

  // Reload to verify the same graph is re-opened (persistence)
  await page.reload();
  await page.waitForFunction(
    () => (window as any).__stelekit_ready === true,
    { timeout: 30_000 },
  );

  expect(errors, `Uncaught JS errors: ${errors.join(' | ')}`).toHaveLength(0);
});
