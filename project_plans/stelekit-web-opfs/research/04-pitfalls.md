# Agent 4 — Pitfalls Research: OPFS + SQLite + Kotlin/WASM

## 1. Known Pitfalls of OPFS + SQLite in the Browser

### Pitfall 1: OPFS is Worker-Only for High-Performance Access

`FileSystemSyncAccessHandle` (the fast, synchronous OPFS API) is **only available inside Web Workers**. Calling `createSyncAccessHandle()` from the main browser thread throws a `DOMException`. This means:

- SQLite's OPFS VFS (`opfs` or `opfs-sahpool`) **must run in a Web Worker**.
- All SQL operations from the main thread must go through message-passing to the worker.
- This adds latency per query (serialization + postMessage + deserialization).

**Impact for SteleKit**: The `DriverFactory.js.kt` cannot directly instantiate SQLite and call it synchronously from the Kotlin/WASM main thread. A dedicated JS worker is required as an intermediary.

### Pitfall 2: OPFS Async API Is Available on Main Thread But Is Slow

The async OPFS API (`getFile()`, `createWritable()`) IS available on the main thread, but these are Promise-based and significantly slower than sync access handles. Using the async API for a SQLite VFS would require custom VFS implementation and is not what `@sqlite.org/sqlite-wasm` supports out of the box.

### Pitfall 3: Exclusive Lock with opfs-sahpool

The `opfs-sahpool` VFS holds **exclusive access** to all pre-allocated file handles for the duration of its installation. This means:

- Only ONE tab can use `opfs-sahpool` against the same OPFS directory at a time.
- Opening a second tab attempting to use the same VFS configuration will fail or behave unexpectedly.
- For SteleKit this is acceptable (single-tab assumption per the architecture), but must be documented.
- The `opfs` VFS supports multi-tab concurrency but requires SAB.

### Pitfall 4: OPFS File Paths Are Not Real Filesystem Paths

OPFS paths like `/stelekit/graph-abc.sqlite3` are **logical paths within the VFS**, not actual filesystem paths. Users cannot browse to them in Finder/Explorer. The browser exposes OPFS files only via the API (or via DevTools → Application → Storage → Origin Private File System in Chrome 116+).

### Pitfall 5: Quota Limits

- OPFS is subject to browser storage quotas (typically 60% of available disk space for the origin).
- If quota is exceeded, `createSyncAccessHandle()` or write operations throw `QuotaExceededError`.
- For SteleKit (a note-taking app with text data), hitting the quota would require hundreds of MB of notes — low risk but should be handled gracefully.

### Pitfall 6: Worker1/Promiser Deprecation

The `sqlite3Worker1Promiser` API from `@sqlite.org/sqlite-wasm` was **deprecated on 2026-04-15**. Many online examples still use it. Use `sqlite3InitModule` + direct module API instead.

---

## 2. Does Kotlin/WASM Support Web Workers?

### Current State (May 2026)

**Short answer: Not directly from Kotlin/WASM code.**

Kotlin/WASM runs entirely on the main browser thread. There is currently no native Kotlin API to spawn a `new Worker(...)` from Kotlin/WASM code directly. The threading situation:

- `Dispatchers.Default` and `Dispatchers.IO` on wasmJs both run on the **main thread** (backed by `setTimeout`/event loop).
- There is no `Dispatchers.IO` offload to a thread pool — all coroutines run cooperatively on the single main thread event loop.
- True multithreading in WASM requires the WASM threads proposal (SharedArrayBuffer + `wasm-threads`), which is still experimental and not enabled by default in Kotlin/WASM.

### How to Spawn a Web Worker from Kotlin/WASM

Use JS interop to call `new Worker()` from the JS side:

```kotlin
// Kotlin/WASM external declaration
external fun createWorker(scriptPath: String): JsAny

// JS snippet
fun createWorker(path: String): JsAny = js("new Worker(path, { type: 'module' })")
```

Alternatively, place the worker creation in a JS helper module imported via `@JsModule`.

### Worker Communication from Kotlin/WASM

```kotlin
external fun postMessageToWorker(worker: JsAny, message: JsAny): Unit
// js("worker.postMessage(message)")

// Receiving: set worker.onmessage callback
external fun setWorkerOnMessage(worker: JsAny, callback: JsAny): Unit
// js("worker.onmessage = callback")
```

Since Kotlin/WASM cannot `Atomics.wait()` on the main thread (would freeze the browser), all worker communication is inherently async via Promise + `await()` in Kotlin coroutines.

### The Async VFS and WASM's Single Thread

The `opfs` (async proxy) VFS works differently: SQLite WASM internally spawns a **second proxy worker** via `new Worker('./sqlite3-opfs-async-proxy.js')`. This second worker uses `FileSystemSyncAccessHandle` synchronously, then communicates results back via SharedArrayBuffer + Atomics to the primary worker where SQLite runs. Since Kotlin/WASM runs on the main thread (not in a worker), using the `opfs` VFS directly from Kotlin/WASM's perspective means the SQLite module itself would need to be in a worker — which is exactly the architecture recommended.

**The recommended architecture**: Kotlin/WASM (main thread) ↔ (postMessage/Promise) ↔ SQLite Worker (JS, contains `@sqlite.org/sqlite-wasm` + opfs-sahpool). The Kotlin code never runs inside the worker.

---

## 3. Private Browsing / Incognito Mode Behavior

### Browser-Specific Behavior

| Browser | OPFS in Private Browsing |
|---|---|
| **Chrome/Chromium** | OPFS is available but storage quota is severely limited (~100 MB, non-persistent) |
| **Firefox** | OPFS is **completely disabled** in private browsing mode |
| **Safari** | OPFS is **completely disabled** in private browsing / incognito mode |

### Implication for the Fallback Strategy

The requirements spec states: "If OPFS is unavailable (e.g., private browsing with restricted storage), fall back to IN_MEMORY with a console warning."

This fallback is **feasible** and is the correct approach. The detection pattern:

```js
// In the SQLite worker, during initialization:
async function tryOpenOpfs(dbPath) {
  try {
    const root = await navigator.storage.getDirectory();
    // If this throws, OPFS is unavailable
    return true;
  } catch (e) {
    return false;
  }
}

// Or attempt installOpfsSAHPoolVfs and catch:
try {
  poolUtil = await sqlite3.installOpfsSAHPoolVfs({ ... });
  db = new poolUtil.OpfsSAHPoolDb(dbPath);
  postMessage({ type: 'ready', backend: 'opfs-sahpool' });
} catch (e) {
  // Fall back to in-memory
  db = new sqlite3.oo1.DB(':memory:');
  postMessage({ type: 'ready', backend: 'memory', warning: 'OPFS unavailable: ' + e.message });
}
```

On the Kotlin side, the `DriverFactory` reads the `backend` field from the worker's ready message and configures `GraphBackend` accordingly.

### NOTE: `IN_MEMORY` SqlDriver Fallback

If the whole SQLite worker approach fails, the simplest fallback is to NOT use the worker at all and return to `GraphBackend.IN_MEMORY` (the current behavior). The `DriverFactory.createDriver()` could throw `UnsupportedOperationException` for the `IN_MEMORY` case and let `GraphManager` handle it — which is what the current code already does.

---

## 4. Known Issues with `@sqlite.org/sqlite-wasm` and Kotlin/WASM Interop

### Memory Model Differences

- Kotlin/WASM uses WasmGC (garbage-collected WASM) — its memory is managed by the GC, not a linear memory buffer.
- `@sqlite.org/sqlite-wasm` runs in standard WASM linear memory (Emscripten-compiled).
- These two WASM modules **cannot share memory directly**. They run in separate WASM instances.
- **This is actually fine** for the worker pattern: Kotlin/WASM is on the main thread; SQLite WASM is in a worker. They communicate via serialized messages (`postMessage`), not shared memory.
- No pointer-passing between Kotlin/WASM and SQLite WASM is needed or safe.

### Pointer Passing: Not Applicable

Since the two WASM modules are in separate contexts (main thread vs worker), there is no pointer-passing issue. All data exchange is via `postMessage` with serializable values (strings, numbers, arrays).

### JsAny Type Constraints

Kotlin/WASM interop requires all JS-boundary types to be `JsAny` or primitives. The result of `postMessage` responses are received as `JsAny` and must be cast manually. SQLite query results (rows as objects) must be serialized in the worker and deserialized in Kotlin using `JsAny` accessors.

### ES Module Requirement

Kotlin/WASM supports ES modules only. `@sqlite.org/sqlite-wasm` supports ES module import. The worker script must also use `type: 'module'`:

```js
new Worker('./sqlite-worker.js', { type: 'module' })
```

Standard workers (non-module) cannot use ES module `import`. This is a compatibility concern: older browsers that support WASM but not module workers would fail. However, all browsers that support OPFS (Chrome 86+, Firefox 111+, Safari 16.4+) also support module workers — this is not a practical concern.

### Webpack / Bundle Issues

- `@sqlite.org/sqlite-wasm` ships with its own `sqlite3.wasm` binary. Webpack must copy this file to the output directory as a static asset, not inline it.
- The package uses a dynamic `new URL('./sqlite3.wasm', import.meta.url)` pattern to locate the WASM binary relative to the JS bundle. This pattern requires webpack 5's asset modules to work correctly.
- If served from a CDN, the WASM binary is fetched separately — this bypasses the webpack issue but adds a network dependency.
- **Recommendation**: Use the CDN approach for Phase B simplicity, with a pinned version and integrity hash. Switch to bundled for production.

---

## 5. Browser Support Cutoffs for OPFS Async VFS

### OPFS Async VFS (`opfs` — requires SAB + Atomics)

| Browser | Minimum Version | Notes |
|---|---|---|
| Chrome | 86+ (OPFS) / 113+ (stable SAB everywhere) | Full support since Chrome 113 |
| Firefox | 111+ | OPFS support added; SAB requires COOP/COEP |
| Safari | 17+ | SAB available since Safari 15.2 with COOP/COEP; full OPFS since Safari 16.4; the `opfs` VFS (sub-worker bug) fixed in Safari 17 |

### OPFS SAHPool VFS (`opfs-sahpool` — NO SAB required)

| Browser | Minimum Version | Notes |
|---|---|---|
| Chrome | 109+ | `createSyncAccessHandle` API; stable since Chrome 109 |
| Firefox | 111+ | `createSyncAccessHandle` in workers |
| Safari | 16.4+ | Works including Safari 16.4 (no need for Safari 17+) |

**SteleKit targets Chrome 119+, Firefox 120+, Safari 18.2+** (per `index.html`). All targets support `opfs-sahpool` without issues.

**`Atomics.waitAsync()`** (needed by `opfs` VFS): Available Chrome 87+, Firefox 116+, Safari 16.4+. Since `opfs-sahpool` doesn't use it, this is moot for the recommended VFS.

---

## 6. COOP/COEP Headers and OPFS Interaction

### Current State in SteleKit

`server.mjs` already sets:
```
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp
Cross-Origin-Resource-Policy: same-origin
```

These headers:
1. Enable `SharedArrayBuffer` — available for `opfs` VFS if needed.
2. Enable `crossOriginIsolated = true` — required for SAB.
3. Do NOT directly affect OPFS availability — OPFS works with or without these headers.

### Consequence for opfs-sahpool

Since `opfs-sahpool` doesn't require SAB, the COOP/COEP headers are **not needed for OPFS itself**. They're already set for the `coi-serviceworker.min.js` (which ensures SAB for the existing WASM build). Keeping them doesn't break anything.

### CDN Resources and COEP

If `@sqlite.org/sqlite-wasm` WASM binary is loaded from a CDN, the CDN response must include:
```
Cross-Origin-Resource-Policy: cross-origin
```
OR the Kotlin WASM app uses `credentialless` for COEP instead of `require-corp`.

**Risk**: Loading from CDN without proper CORP headers will fail under `require-corp` COEP. Fix: either bundle the WASM binary locally or use a CDN that sets CORP headers (unpkg.com does; esm.run/jsDelivr may not — verify at implementation time).

---

## 7. Licensing

- `@sqlite.org/sqlite-wasm` is based on SQLite itself.
- SQLite is **public domain** — no copyright, no license, no restrictions.
- The `@sqlite.org/sqlite-wasm` npm package is published by the SQLite project under the same public domain dedication.
- **No licensing issues** for use in SteleKit (Elastic License 2.0 for SteleKit code).

---

## Summary

- OPFS sync access is **worker-only** — architecture must use a Web Worker for SQLite. This is the #1 pitfall.
- Kotlin/WASM cannot spawn Web Workers via native Kotlin API — use `js("new Worker(...)")` interop.
- All coroutines on Kotlin/WASM run on the main thread — no Dispatchers.IO thread pool.
- Private browsing: Firefox and Safari completely disable OPFS; Chrome limits to ~100MB. Fallback to IN_MEMORY is feasible and required.
- No memory model conflicts between Kotlin/WASM (WasmGC) and SQLite WASM (linear memory) because they run in separate threads/contexts with message-passing.
- `opfs-sahpool` works on Safari 16.4+ (better than `opfs`'s Safari 17+ requirement).
- COOP/COEP headers already set in SteleKit — SAB available. But `opfs-sahpool` doesn't need it.
- CDN delivery of `sqlite3.wasm` requires CORP header from CDN, or bundle locally.
- SQLite is public domain — no licensing concern.
