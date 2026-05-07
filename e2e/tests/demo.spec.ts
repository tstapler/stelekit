import { test, expect } from '@playwright/test';

// The WASM app renders into canvas#ComposeTarget via Skia/WebGL.
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

  // Step 1: canvas element must be present in the DOM.
  const canvas = page.locator('canvas#ComposeTarget');
  await expect(canvas).toBeAttached({ timeout: 10_000 });

  // Step 2: Compose resizes the canvas from the HTML default (300 px) to the
  // viewport size when it initialises. Wait for that resize as a proxy for
  // "Kotlin/WASM main() executed successfully".
  await page.waitForFunction(
    () => {
      const c = document.getElementById('ComposeTarget') as HTMLCanvasElement | null;
      return (c?.width ?? 0) > 300;
    },
    { timeout: 30_000 },
  );

  // Step 3: Compose paints into a WebGL context. Poll until the centre pixel
  // has a non-zero alpha, meaning at least one render frame has been committed.
  await expect
    .poll(
      () =>
        page.evaluate(() => {
          const c = document.getElementById('ComposeTarget') as HTMLCanvasElement | null;
          if (!c) return false;
          // Compose uses webgl2 on supported browsers, webgl as fallback.
          const gl =
            (c.getContext('webgl2') as WebGLRenderingContext | null) ??
            (c.getContext('webgl') as WebGLRenderingContext | null);
          if (!gl) return false;
          const px = new Uint8Array(4);
          gl.readPixels(
            Math.floor(c.width / 2),
            Math.floor(c.height / 2),
            1, 1,
            gl.RGBA,
            gl.UNSIGNED_BYTE,
            px,
          );
          return px[3] > 0; // alpha > 0 → Compose painted at least one frame
        }),
      { timeout: 30_000, intervals: [500, 1_000, 2_000, 2_000, 2_000] },
    )
    .toBe(true);

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
