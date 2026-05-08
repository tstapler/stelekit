# Agent 1 — Stack Research: OPFS SQLite for Kotlin/WASM

## 1. `@sqlite.org/sqlite-wasm` and OPFS VFS Options

### Package Identity
- npm package: `@sqlite.org/sqlite-wasm`
- GitHub: https://github.com/sqlite/sqlite-wasm
- This is the official SQLite project's WASM build, not a third-party wrapper.

### Available OPFS VFS Implementations

The official package exposes three OPFS-capable VFS implementations:

| VFS Name | Requires SAB? | Multi-tab? | Performance | Notes |
|---|---|---|---|---|
| `opfs` | **Yes** (SharedArrayBuffer + Atomics) | Yes (single write, concurrent reads) | Good | Uses `sqlite3-opfs-async-proxy.js`; requires COOP/COEP |
| `opfs-sahpool` (OPFSSAHPool) | **No** | No (exclusive lock) | **Best** (3–4× faster than `opfs`) | Works without SAB; released in SQLite 3.43.0 |
| `opfs-wl` | Yes | Yes | Good | Write-lock variant |

### Which VFS to Use for SteleKit

**Recommendation: `opfs-sahpool` (OPFSSAHPool VFS)**

Reasoning:
- The requirements file notes that COOP/COEP headers ARE already set (server.mjs sets them), so SAB IS available. However:
- `opfs-sahpool` is 3–4× faster and is the recommended default for single-tab applications.
- SteleKit is inherently single-tab (one open graph at a time); multi-tab concurrency is not a requirement.
- `opfs-sahpool` does NOT require SAB at all, which simplifies the fallback story.
- The Worker1/Promiser API was **deprecated on 2026-04-15** — do not build on it for new code.

**CRITICAL NOTE**: Both `opfs` and `opfs-sahpool` require SQLite to run inside a **Web Worker**. The synchronous `FileSystemSyncAccessHandle` API is blocked on the main thread. This is the single most important architectural constraint.

### opfs-sahpool Initialization (JS-side)

```js
import sqlite3InitModule from '@sqlite.org/sqlite-wasm';

// Must run inside a Web Worker
const sqlite3 = await sqlite3InitModule({ print: console.log, printErr: console.error });
const poolUtil = await sqlite3.installOpfsSAHPoolVfs({
  name: 'opfs-sahpool',     // VFS name
  directory: '/stelekit',   // OPFS subdirectory
  initialCapacity: 6,       // pre-allocated file handles
  clearOnInit: false,       // preserve data across page loads
});
const db = new poolUtil.OpfsSAHPoolDb('/stelekit/mydb.sqlite3');
db.exec('CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT)');
```

The `opfs` (async proxy) VFS initialization is similar but requires `SharedArrayBuffer` and spawns a second internal proxy worker.

---

## 2. SQLDelight 2.3.2 Kotlin/WASM Driver

### Official `app.cash.sqldelight` Artifacts

| Artifact | Latest on Maven Central | Notes |
|---|---|---|
| `web-worker-driver` | 2.1.0 | For Kotlin/JS browser targets |
| `web-worker-driver-wasm-js` | 2.1.0 | For Kotlin/WASM browser targets |
| `web-worker-driver-js` | 2.1.0 | JS-only variant |

**CRITICAL FINDING**: As of research date (May 2026), `app.cash.sqldelight:web-worker-driver-wasm-js` exists on Maven Central but its **latest release is 2.1.0, not 2.3.2**. SteleKit's project uses SQLDelight 2.3.2 throughout for JVM/Android. There is a **version mismatch**: the official wasm-js web-worker-driver tops out at 2.1.0.

There is also a community fork `me.gulya.sqldelight:web-worker-driver-wasm-js:2.1.0-wasm` that targets WASM.

### What the web-worker-driver Does

- Communicates with a SQLite implementation running inside a Web Worker via message passing.
- The driver is **asynchronous**: `execute()`, `executeQuery()`, `newTransaction()`, `endTransaction()` all return `QueryResult` (not a plain value).
- Requires setting `generateAsync = true` in the SQLDelight Gradle config.
- The `awaitAsList()` / `awaitAsOne()` suspend extension functions replace synchronous equivalents.
- SQLDelight provides a `sqlite.worker.js` script that wraps sql.js — this is the default worker implementation. You can provide a custom worker that wraps `@sqlite.org/sqlite-wasm` + opfs-sahpool instead.

### Alternative: Custom SqlDriver in Kotlin

Since 2.3.2 has no matching `web-worker-driver-wasm-js`, the implementation team has three options:

1. **Use `web-worker-driver-wasm-js:2.1.0`** — version mismatch with 2.3.2 runtime; may or may not be binary-compatible. Risk: high.
2. **Implement a custom `SqlDriver`** in Kotlin/WASM with JS interop calls to `@sqlite.org/sqlite-wasm` running in a dedicated JS Worker — all communication is async via Promises/callbacks. This is the most correct approach for 2.3.2.
3. **Vendor/copy the web-worker-driver source** and update it to work with 2.3.2. Labor-intensive but avoids version mismatch.

**Prior art**: `dellisd/sqldelight-sqlite-wasm` (GitHub) experiments with exactly this: a SqlDriver for Kotlin/JS backed by `@sqlite.org/sqlite-wasm`. It targets Kotlin/JS (not WASM), but the JS interop patterns are applicable.

---

## 3. Kotlin/WASM JS Interop Mechanisms

### How to Call npm Packages from Kotlin/WASM

Kotlin/WASM uses **ES modules only** (no CommonJS). The interop path is:

1. **Declare `external` types and functions** with `@JsModule` annotation:

```kotlin
@file:JsModule("@sqlite.org/sqlite-wasm")

package dev.stapler.stelekit.db

external fun sqlite3InitModule(config: JsAny): JsAny  // returns Promise<Sqlite3>
```

2. **Call JS Promises** via `Promise<T>.await()` (from `kotlinx.coroutines`):

```kotlin
import kotlinx.coroutines.await

val sqlite3: JsAny = sqlite3InitModule(config).unsafeCast<Promise<JsAny>>().await()
```

3. **Type restrictions**: Kotlin/WASM interop signatures must use `JsAny` or its subtypes at the boundaries. You cannot pass Kotlin data classes directly — you need to marshal to/from `JsAny`.

4. **`@JsExport`**: marks Kotlin functions callable from JS side (used for the worker message handler).

5. **`= js("...")` snippets**: small inline JS expressions can be embedded directly.

### Key Difference vs Kotlin/JS

- Kotlin/WASM uses `JsAny` as the universal JS value type; Kotlin/JS uses `dynamic`.
- `@JsNonModule` is NOT supported in WASM (ES modules only).
- `external` declarations must be at file/top-level or inside `external` classes.

---

## 4. How npm Packages Are Bundled in Kotlin/WASM

### Gradle Configuration

```kotlin
// build.gradle.kts
val wasmJsMain by getting {
    dependencies {
        implementation(npm("@sqlite.org/sqlite-wasm", "3.46.1"))
    }
}
```

The `npm()` Gradle function (not string `"npm:"` syntax) is the correct approach. The Kotlin Gradle plugin uses **webpack 5** to bundle everything, including npm dependencies, into the output JS bundle.

### Worker Script Challenge

The `@sqlite.org/sqlite-wasm` package includes a WASM binary (`sqlite3.wasm`) that must be served separately (not inlined). The webpack config needs to emit the `.wasm` file as a static asset, not inline it. This typically requires a webpack copy plugin or the package's own configuration.

Alternatively, `@sqlite.org/sqlite-wasm` can be loaded from CDN with an integrity hash inside a dedicated worker script, avoiding the webpack bundling problem.

---

## 5. Prior Art: "sqldelight wasm" and "kotlin wasm opfs sqlite"

- **`dellisd/sqldelight-sqlite-wasm`** (GitHub): Kotlin/JS (not WASM) prototype using `@sqlite.org/sqlite-wasm` + SQLDelight's `web-worker-driver`. Worker file is in `src/jsMain/resources/sqlite.worker.js`.
- **`powersync.com` blog (November 2025)**: Comprehensive state-of-the-art review of all OPFS VFS options, confirms opfs-sahpool as the recommended choice for single-tab performance.
- **Kotlin Slack thread**: Developers report difficulty adding wasmJs to existing KMP projects with SQLDelight; the missing 2.3.x web-worker-driver is a known pain point.
- **No production Kotlin/WASM + OPFS + SQLDelight example found** in the public ecosystem as of May 2026. This is a greenfield integration.

---

## Summary

- Use `opfs-sahpool` VFS — no SAB required, 3–4× faster, single-tab sufficient.
- `app.cash.sqldelight:web-worker-driver-wasm-js` tops out at 2.1.0 — version mismatch with 2.3.2.
- Most likely path: implement a **custom async `SqlDriver`** in Kotlin/WASM using JS interop, with `@sqlite.org/sqlite-wasm` running in a dedicated JS Web Worker (required — OPFS sync API is blocked on main thread).
- npm dependency added via `implementation(npm("@sqlite.org/sqlite-wasm", "3.x.x"))` in `wasmJsMain` source set.
- Worker1/Promiser API is deprecated; use direct module API (`sqlite3InitModule` + `installOpfsSAHPoolVfs`).
