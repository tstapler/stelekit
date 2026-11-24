import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createServer } from 'node:http';

const root = join(__dirname, '../../kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit');
function inline(file: string, name: string): string {
  const source = readFileSync(join(root, file), 'utf8').split(`fun ${name}(`)[1];
  const match = source?.match(/js\(\s*"""([\s\S]*?)"""\s*\)/);
  if (!match) throw new Error(`Missing inline JavaScript: ${name}`);
  return match[1];
}
const readyJs = inline('db/SqliteWorkerInterop.kt', 'createWorkerReadyPromise');
const renderJs = inline('browser/BootRefusal.kt', 'jsRenderBootRefusal');
const server = createServer((_req, res) => res.end('<!doctype html><div id="loading">Loading</div>'));
let url: string;
test.beforeAll(async () => {
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Missing port');
  url = `http://127.0.0.1:${address.port}`;
});
test.afterAll(async () => { await new Promise<void>(resolve => server.close(() => resolve())); });

for (const [name, script, expected] of [
  ['worker init protocol error', "onmessage = () => postMessage({type:'error',id:undefined,message:'sqlite init failed'})", 'sqlite init failed'],
  ['module evaluation error', "throw new Error('module failed')", 'module failed'],
  ['silent worker deadline', 'onmessage = () => {}', 'timed out'],
  ['ready success', "onmessage = () => postMessage({type:'ready',backend:'opfs-sahpool'})", 'opfs-sahpool'],
]) {
  test(name, async ({ page }) => {
    await page.goto(url);
    const result = await page.evaluate(async ({ js, script }) => {
      const blob = URL.createObjectURL(new Blob([script], { type: 'application/javascript' }));
      const worker = new Worker(blob, { type: 'module' });
      let terminated = false;
      const terminate = worker.terminate.bind(worker);
      worker.terminate = () => { terminated = true; terminate(); };
      let deadline = 0;
      try {
        const start = new Function('worker', 'dbPath', 'mode', 'setTimeout', `return (${js});`);
        const timer = (fn: TimerHandler, ms: number) => {
          deadline = ms;
          return setTimeout(fn, script === 'onmessage = () => {}' ? 100 : ms);
        };
        try {
          const ready = await start(worker, '/test.sqlite3', 'persistent', timer);
          return { value: ready.backend, terminated, deadline };
        } catch (error) {
          return { value: String(error), terminated, deadline };
        }
      } finally {
        terminate();
        URL.revokeObjectURL(blob);
      }
    }, { js: readyJs, script });
    expect(result.value).toContain(expected);
    expect(result.terminated).toBe(name !== 'ready success');
    expect(result.deadline).toBe(30000);
  });
}

test('boot error DOM preserves saved graphs and reload retries without temporary mode', async ({ page }) => {
  await page.goto(url);
  const render = async () => page.evaluate(js => {
    localStorage.setItem('graph_registry', 'saved-registry');
    localStorage.setItem('cached_graph_path', '/my-graph');
    new Function('heading', 'message', 'waiting', 'action', 'actionName', 'secondary', 'secondaryName', 'target', 'showWaiting', js)(
      'SteleKit couldn’t start', 'Your saved graphs have not been removed.', '', 'Reload', 'Retry startup', '', '', '', false,
    );
  }, renderJs);
  await render();
  await expect(page.getByRole('alert')).toContainText('saved graphs have not been removed');
  await expect(page.getByRole('link')).toHaveCount(0);
  await page.getByRole('button', { name: 'Retry startup' }).click();
  await expect(page.locator('#loading')).toHaveText('Loading');
  expect(await page.evaluate(() => [localStorage.getItem('graph_registry'), localStorage.getItem('cached_graph_path')]))
    .toEqual(['saved-registry', '/my-graph']);
});
