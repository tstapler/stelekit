'use strict';
// Run: node --test scripts/web-sqlite-startup.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit');
const source = name => fs.readFileSync(path.join(root, name), 'utf8');
function inlineJs(file, name) {
  const text = source(file).split(`fun ${name}(`)[1];
  assert.ok(text, `Missing ${name}`);
  const match = text.match(/js\(\s*"""([\s\S]*?)"""\s*\)/);
  assert.ok(match, `Missing inline JS in ${name}`);
  return match[1];
}
function harness(postError) {
  const listeners = new Map();
  const timers = new Set();
  let timer;
  let terminated = 0;
  const worker = {
    addEventListener(type, fn) { listeners.set(type, fn); },
    removeEventListener(type, fn) { if (listeners.get(type) === fn) listeners.delete(type); },
    terminate() { terminated++; },
    postMessage(message) { assert.equal(message.type, 'init'); if (postError) throw new Error(postError); },
  };
  const promise = vm.runInNewContext(inlineJs('db/SqliteWorkerInterop.kt', 'createWorkerReadyPromise'), {
    worker, dbPath: '/graph-test.sqlite3', mode: 'persistent',    setTimeout(fn, ms) { assert.ok(ms > 0 && ms <= 60000); timer = fn; timers.add(1); return 1; },
    clearTimeout(id) { timers.delete(id); },
  });
  return {
    promise,
    emit(type, event) { listeners.get(type)?.(event); },
    timeout() { assert.ok(timer, 'initialization must have a deadline'); timer(); },
    clean() { assert.equal(listeners.size, 0); assert.equal(timers.size, 0); },
    terminated: () => terminated,
  };
}
test('synchronous init post failure rejects and cleans up immediately', async () => {
  const h = harness('post failed');
  await assert.rejects(h.promise, /post failed/);
  h.clean();
  assert.equal(h.terminated(), 1);
});

test('ready succeeds, removes all startup listeners and timer, and retains worker', async () => {
  const h = harness();
  const ready = { type: 'ready', backend: 'opfs-sahpool' };
  h.emit('message', { data: ready });
  assert.equal(await h.promise, ready);
  h.clean();
  assert.equal(h.terminated(), 0);
});
for (const [name, type, event, message] of [
  ['init protocol error without request id', 'message', { data: { type: 'error', id: undefined, message: 'sqlite init failed' } }, /sqlite init failed/],
  ['module load error', 'error', { message: 'module fetch failed', preventDefault() {} }, /module fetch failed/],
  ['unreadable worker message', 'messageerror', {}, /message/i],
]) {
  test(`${name} rejects, cleans listeners and timer, and terminates worker`, async () => {
    const h = harness();
    const result = assert.rejects(h.promise, message);
    h.emit(type, event);
    // Old implementation never settles; the assertion also detects that synchronously.
    h.clean();
    await result;
    assert.equal(h.terminated(), 1);
  });
}
test('silent worker times out, cleans up, terminates and ignores late ready', async () => {
  const h = harness();
  const result = assert.rejects(h.promise, /timed out/i);
  h.timeout();
  await result;
  h.clean();
  h.emit('message', { data: { type: 'ready' } });
  assert.equal(h.terminated(), 1);
});
test('irrelevant and malformed messages do not settle initialization', async () => {
  const h = harness();
  h.emit('message', { data: null });
  h.emit('message', { data: { type: 'result', id: 0 } });
  h.emit('message', { data: { type: 'ready' } });
  await h.promise;
  h.clean();
});
test('boot failure wiring preserves stored graph pointers and offers retry, not demo', () => {
  const main = source('browser/Main.kt');
  assert.doesNotMatch(main, /useDemoFallback/);
  assert.doesNotMatch(main, /removeItem\("(?:graph_registry|cached_graph_path)"\)/);
  const failure = main.split('catch (e: GraphLockedElsewhereException)')[1].split('val graphManager')[0];
  assert.match(failure, /showBootError\(\)/);
  assert.doesNotMatch(failure, /GraphBackend.IN_MEMORY|DemoFileSystem\(\)/);
  assert.match(main, /Fatal startup error:[\s\S]*?showBootError\(\)/);
  assert.match(source('browser/BootRefusal.kt'), /fun showBootError\(/);
});
test('held graph lock can be released and reacquired after failed initialization', async () => {
  let held = false;
  let pending;
  const navigator = { locks: { request(_name, _options, callback) {
    if (held) return callback(null);
    held = true;
    pending = Promise.resolve(callback({ name: 'test' })).finally(() => { held = false; });
    return pending;
  } } };
  const acquire = () => vm.runInNewContext(inlineJs('platform/WebLock.kt', 'jsRequestLockHandleIfAvailable'), { navigator, name: 'test' });
  const handle = acquire();
  assert.ok(await handle.acquired);
  const busy = acquire();
  assert.equal(await busy.acquired, null);
  handle.release();
  await pending;
  const retry = await acquire();
  assert.ok(retry);
  retry.release();
  await pending;
  assert.equal(held, false);
});

test('failed driver creation closes worker and releases its releasable graph lock', () => {
  const factory = source('db/DriverFactory.js.kt');
  assert.match(factory, /WebLock.tryAcquireHeld\(/);
  assert.match(factory, /catch \(e: Throwable\)\s*\{[\s\S]*?driver\?\.terminate\(\)[\s\S]*?\.release\(\)[\s\S]*?throw e/);
  assert.match(factory, /check\(opened.actualBackend != "memory"\)/);
  assert.match(source('db/WasmOpfsSqlDriver.kt'), /internal fun terminate\(\)\s*=\s*terminateSqliteWorker\(worker\)/);
  assert.match(source('db/WasmOpfsSqlDriver.kt'), /override fun close\(\) \{\}/);
});
