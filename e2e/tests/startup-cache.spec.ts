import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

function createHeaderlessServer() {
  const resources = join(__dirname, '../../kmp/src/wasmJsMain/resources');
  const scripts = new Map(['stelekit-service-worker.js', 'looks-like-app-shell.js'].map(name =>
    [name, readFileSync(join(resources, name), 'utf8')],
  ));
  const server = createServer((req, res) => {
    const script = scripts.get((req.url ?? '').split('/').pop() ?? '');
    res.setHeader('Content-Type', script ? 'application/javascript' : 'text/html');
    res.end(script ?? '<!doctype html><script src="kmp.js"></script>');
  });
  return server;
}

// A headerless origin exercises the GitHub Pages service-worker path, unlike server.mjs.
test('cached worker startup and offline reload retain cross-origin isolation', async ({ page, context }) => {
  const server = createHeaderlessServer();
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const address = server.address();
    if (!address || typeof address === 'string') throw new Error('Missing test server port');
    await page.goto(`http://127.0.0.1:${address.port}/app/`);
    await page.evaluate(async () => {
      await navigator.serviceWorker.register('./stelekit-service-worker.js');
      await navigator.serviceWorker.ready;
      if (navigator.serviceWorker.controller) return;
      await new Promise<void>(resolve => navigator.serviceWorker.addEventListener('controllerchange', () => resolve(), { once: true }));
    });
    await page.reload();
    expect(await page.evaluate(() => crossOriginIsolated)).toBe(true);
    await page.evaluate(async () => {
      const cache = await caches.open('stelekit-app-shell-v1');
      await cache.put('./sqlite-stelekit-worker.js', new Response(
        'postMessage({ready:true,isolated:crossOriginIsolated})',
        { headers: { 'Content-Type': 'application/javascript' } },
      ));
    });
    await context.setOffline(true);
    const result = await page.evaluate(() => new Promise((resolve, reject) => {
      const worker = new Worker('./sqlite-stelekit-worker.js', { type: 'module' });
      const timer = setTimeout(() => { worker.terminate(); reject(new Error('Worker startup timed out')); }, 10_000);
      worker.onmessage = event => { clearTimeout(timer); worker.terminate(); resolve(event.data); };
      worker.onerror = () => { clearTimeout(timer); worker.terminate(); reject(new Error('Cached worker blocked')); };
    }));
    expect(result).toEqual({ ready: true, isolated: true });
    await page.reload({ waitUntil: 'domcontentloaded' });
    expect(await page.evaluate(() => crossOriginIsolated)).toBe(true);
  } finally {
    await context.setOffline(false);
    await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
});
