# Agent 2 — Features Research: OPFS API, sqlite-wasm, and Playwright

## 1. OPFS API Surface

### Core APIs Needed

The Origin Private File System (OPFS) API exposes two paths to file access:

**Asynchronous (main thread + worker):**
```js
// Get the root directory handle
const root = await navigator.storage.getDirectory();

// Create/get a subdirectory
const dir = await root.getDirectoryHandle('stelekit', { create: true });

// Create/get a file handle
const fileHandle = await dir.getFileHandle('mydb.sqlite3', { create: true });

// Open a writable stream
const writable = await fileHandle.createWritable();
await writable.write(buffer);
await writable.close();
```

**Synchronous (Web Worker only — high performance):**
```js
// Must be in a Web Worker
const fileHandle = await dir.getFileHandle('mydb.sqlite3', { create: true });
const syncHandle = await fileHandle.createSyncAccessHandle();

// Synchronous read/write — no await needed
syncHandle.write(buffer, { at: offset });
syncHandle.read(readBuffer, { at: offset });
syncHandle.flush();
syncHandle.close();
```

### Key Constraint
`createSyncAccessHandle()` is **only available inside Web Workers**. The main browser thread can only use the async API. This is why all SQLite-over-OPFS implementations run SQLite inside a worker.

### Full Function Set Needed by the VFS

For `opfs-sahpool` (OPFSSAHPool), the SQLite WASM runtime handles OPFS internally — you only need to call `installOpfsSAHPoolVfs()`. You do NOT need to call `navigator.storage.getDirectory()` manually; the VFS abstraction handles it.

Required browser APIs (called internally by SQLite WASM):
- `navigator.storage.getDirectory()` — get OPFS root
- `FileSystemDirectoryHandle.getDirectoryHandle(name, {create})` — create subdirectory
- `FileSystemDirectoryHandle.getFileHandle(name, {create})` — open file
- `FileSystemFileHandle.createSyncAccessHandle()` — open sync access (worker-only)
- `FileSystemSyncAccessHandle.read()` / `.write()` / `.truncate()` / `.flush()` / `.close()`

---

## 2. Which OPFS VFS Works in a Web Worker (for SteleKit)

### The Two Main Choices

**`opfs` (async proxy VFS):**
- Works inside a dedicated worker that SQLite spawns internally (the `sqlite3-opfs-async-proxy.js`).
- Requires `SharedArrayBuffer` to coordinate between the WASM sync I/O calls and the async OPFS API.
- When SAB is available (COOP/COEP headers set), this is reliable.
- `server.mjs` in SteleKit already sets COOP/COEP → SAB IS available in SteleKit's context.

**`opfs-sahpool` (OPFSSAHPool VFS) — RECOMMENDED:**
- Runs entirely within a single worker.
- Uses `createSyncAccessHandle()` directly — no SAB needed.
- 3–4× faster I/O than the async proxy VFS.
- The VFS pre-allocates a pool of sync access handles (configurable via `initialCapacity`).
- Does NOT support multi-tab access to the same database file simultaneously.

### Worker Architecture for opfs-sahpool

```
Main Thread (Kotlin/WASM)
    │
    │  postMessage(sql, params)
    ▼
sqlite-worker.js (Web Worker)
    │
    │  sqlite3InitModule() → installOpfsSAHPoolVfs()
    │  → OpfsSAHPoolDb('/stelekit/graph-abc.sqlite3')
    │  → db.exec(sql)
    │
    │  postMessage(result)
    ▼
Main Thread (receives result, resolves Promise)
```

The `@sqlite.org/sqlite-wasm` package already handles threading internally for the `opfs` VFS. For `opfs-sahpool`, the developer controls the worker.

---

## 3. Initialization Sequence for opfs-sahpool

```js
// sqlite-worker.js (runs in a Web Worker)
import sqlite3InitModule from '@sqlite.org/sqlite-wasm';

let db = null;
let poolUtil = null;

async function init(dbPath) {
  const sqlite3 = await sqlite3InitModule({
    print: (...args) => console.log(...args),
    printErr: (...args) => console.error(...args),
  });

  // Install the SAHPool VFS into this worker's sqlite3 instance
  poolUtil = await sqlite3.installOpfsSAHPoolVfs({
    name: 'opfs-sahpool',
    directory: '/stelekit',      // OPFS sub-directory
    initialCapacity: 6,          // number of pre-allocated sync access handles
    clearOnInit: false,          // do NOT wipe on restart — essential for persistence!
  });

  // Open (or create) the database
  // Constructor throws if DB cannot be opened; wrap in try/catch for fallback.
  db = new poolUtil.OpfsSAHPoolDb(dbPath);  // e.g. '/stelekit/graph-default.sqlite3'
  
  self.postMessage({ type: 'ready' });
}

self.onmessage = async (e) => {
  const { type, sql, bind, dbPath } = e.data;
  if (type === 'init') {
    await init(dbPath);
  } else if (type === 'exec') {
    const results = [];
    db.exec({ sql, bind, rowMode: 'object', callback: (row) => results.push(row) });
    self.postMessage({ type: 'result', results });
  }
};
```

Key initialization parameters:
- `clearOnInit: false` — do NOT clear on init (this is the persistence toggle).
- `directory` — OPFS subdirectory (isolated per origin, writable).
- `initialCapacity` — number of pre-allocated file handles (minimum: number of concurrent databases + 2).

---

## 4. SharedArrayBuffer and OPFSSAHPool

| VFS | SAB Required | Why |
|---|---|---|
| `opfs` | **Yes** | Proxy worker uses SAB+Atomics to convert async OPFS calls to synchronous SQLite VFS calls |
| `opfs-sahpool` | **No** | Uses `createSyncAccessHandle()` directly inside the worker — no async→sync bridging needed |
| `opfs-wl` | **Yes** | Write-lock variant of `opfs` |

**For SteleKit**: since COOP/COEP headers are already set, both options work. But `opfs-sahpool` is preferred for performance and simpler architecture (no second proxy worker).

**Atomics.waitAsync()**: As of 2025, this is available in all major browsers (required by the `opfs` VFS). The `opfs-sahpool` VFS does not use it.

---

## 5. `@sqlite.org/sqlite-wasm` JS API Reference

### Module Import

```js
// In a worker (ES module worker)
import sqlite3InitModule from '@sqlite.org/sqlite-wasm';
// OR via CDN (with integrity hash for security):
import sqlite3InitModule from 'https://esm.run/@sqlite.org/sqlite-wasm@3.46.1-build2';
```

### Initialization

```js
const sqlite3 = await sqlite3InitModule({ print, printErr });
// sqlite3.version.libVersion → '3.46.1'
// sqlite3.capi — low-level C API
// sqlite3.oo1 — high-level OO API (use this)
// sqlite3.installOpfsSAHPoolVfs — async function for SAH pool
```

### OO1 Database API (after VFS install)

```js
// After installOpfsSAHPoolVfs():
const db = new poolUtil.OpfsSAHPoolDb('/path/file.sqlite3');

// Execute SQL (synchronous, because we're in a worker with sync handles)
db.exec({
  sql: 'SELECT * FROM pages WHERE uuid = ?',
  bind: [uuid],
  rowMode: 'object',       // returns {column: value} objects
  callback: (row) => { /* process row */ }
});

// One-shot exec with result array:
const rows = db.exec('SELECT * FROM pages', { returnValue: 'resultRows', rowMode: 'object' });

// Prepared statement:
const stmt = db.prepare('INSERT INTO blocks VALUES (?, ?, ?)');
stmt.bind([uuid, content, parentId]).stepReset().finalize();

// Transaction:
db.transaction(() => {
  db.exec('INSERT INTO ...');
  db.exec('UPDATE ...');
});

// Close:
db.close();
```

### IMPORTANT: The Worker1/Promiser API is Deprecated (as of 2026-04-15)

Do NOT use `sqlite3Worker1Promiser()`. The upstream SQLite project has deprecated this API as "too fragile, too imperformant, and too limited for non-toy software." Use direct module initialization instead.

---

## 6. How to Test OPFS Persistence with Playwright

### Key Challenge

OPFS is an origin-scoped, persistent filesystem. Unlike `localStorage`, Playwright's standard `browser.newContext()` does NOT automatically preserve OPFS data between test runs unless using a persistent context or the same profile directory.

### Approach 1: `page.reload()` Within a Single Test (Simplest for SteleKit)

```typescript
test('OPFS persistence survives page reload', async ({ page }) => {
  await page.goto('/');
  
  // Wait for app to initialize
  await page.waitForFunction(() => window.__stelekit_ready === true, { timeout: 30_000 });
  
  // Write a note via UI interaction or JS evaluation
  // (The actual mechanism depends on how SteleKit exposes state)
  
  // Reload the page — OPFS data survives within the same browser context
  await page.reload();
  
  // Wait for re-initialization
  await page.waitForFunction(() => window.__stelekit_ready === true, { timeout: 30_000 });
  
  // Assert the data is still present
  // ...
});
```

OPFS data **does** survive `page.reload()` within the same Playwright browser context (same origin, same profile).

### Approach 2: Persistent Context (Cross-test persistence)

```typescript
// playwright.config.ts
use: {
  // Use a persistent context to preserve OPFS across test files
  // Note: requires browser.launchPersistentContext() not browser.newContext()
  userDataDir: '/tmp/playwright-stelekit-profile',
}
```

### Approach 3: Clear OPFS Between Tests (Isolation)

```typescript
// In beforeEach: clear OPFS so tests don't bleed into each other
await page.evaluate(async () => {
  const root = await navigator.storage.getDirectory();
  // Remove the stelekit directory
  await root.removeEntry('stelekit', { recursive: true });
});
```

### Recommended Pattern for SteleKit's Persistence Test

```typescript
test('SteleKit OPFS: data persists across page reload', async ({ page }) => {
  // First load
  await page.goto('/');
  await waitForWasmReady(page);  // reuse existing helper

  // The app bootstraps an empty graph and shows today's journal — verify it loaded
  // ... existing canvas paint assertions from demo.spec.ts ...

  // Trigger a data write: either via UI or by injecting JS that calls the worker
  // (exact mechanism TBD based on how SteleKit exposes its graph state)

  // Reload — OPFS persists within same context
  await page.reload();
  await waitForWasmReady(page);

  // Assert the same page/graph is present (not a fresh demo)
  // Use page.evaluate() to inspect OPFS via navigator.storage.getDirectory()
  // or verify via visual assertion / app state
  const hasData = await page.evaluate(async () => {
    try {
      const root = await navigator.storage.getDirectory();
      const dir = await root.getDirectoryHandle('stelekit', { create: false });
      return true;  // directory exists → data was written
    } catch {
      return false;  // directory doesn't exist → OPFS write failed
    }
  });
  expect(hasData).toBe(true);
});
```

### `clearOnInit` in Tests

When writing Playwright tests that specifically verify fresh-start behavior, pass `clearOnInit: true` to `installOpfsSAHPoolVfs()` in a test-only build, or expose a JS function that clears OPFS before calling reload.

---

## Summary

- `opfs-sahpool` VFS is recommended — no SAB required, fastest performance, best for single-tab.
- OPFS requires a Web Worker — `createSyncAccessHandle()` is blocked on the main thread.
- Initialization: `sqlite3InitModule()` → `installOpfsSAHPoolVfs({ clearOnInit: false })` → `new poolUtil.OpfsSAHPoolDb(path)`.
- Worker1/Promiser API is **deprecated** — use direct module API.
- Playwright persistence tests: use `page.reload()` within one test (same browser context preserves OPFS); use `page.evaluate()` to verify OPFS directory existence.
- `clearOnInit: false` is essential — setting it to `true` would wipe all data on every load.
