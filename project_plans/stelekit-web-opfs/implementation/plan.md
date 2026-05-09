# Implementation Plan: SteleKit Web — OPFS Durable Storage & Local Dev

**Project**: stelekit-web-opfs
**Phase**: 3 — Architecture + Task Breakdown
**Date**: 2026-05-07

---

## Summary

3 epics, 3 stories, 13 tasks.

Replace the browser's hard-coded `IN_MEMORY` + `DemoFileSystem` stack with a real OPFS-backed SQLite driver and file system. The only practical path is:

1. `@sqlite.org/sqlite-wasm` running inside a **JS Web Worker** (OPFS sync access is browser-main-thread-blocked).
2. Kotlin/WASM talking to that worker via postMessage wrapped as JS `Promise`s.
3. A custom `SqlDriver` returning `QueryResult.AsyncValue`, gated by `generateAsync = true` in the SQLDelight Gradle config.

No `app.cash.sqldelight:web-worker-driver-wasm-js` artifact exists at 2.3.2; a custom driver is mandatory.

---

## Risk Register

| ID | Risk | Likelihood | Impact | Mitigation |
|----|------|-----------|--------|------------|
| R1 | `generateAsync = true` regenerates ALL query code to suspend functions — may break JVM/Android tests | Medium | High | JVM `sqlite-driver` and Android `android-driver` both support synchronous `QueryResult.Value`, which is compatible with async callers in coroutine context. Run full `ciCheck` immediately after enabling. If tests break, use `expect/actual` shim to keep JVM/Android sync. |
| R2 | Worker module path wrong in built dist (webpack outputs file at different path than `new Worker('./sqlite-stelekit-worker.js')` resolves) | High | High | Must verify the worker script path after first successful webpack build. Check `kmp/build/dist/wasmJs/productionExecutable/` for actual output filenames. |
| R3 | CDN delivery of `sqlite3.wasm` blocked by COEP `require-corp` if CDN does not set CORP header | Medium | High | Bundle `@sqlite.org/sqlite-wasm` locally via npm; prefer bundled over CDN for all CI/production use. |
| R4 | `opfs-sahpool` exclusive-lock prevents multi-tab use | Low | Low | Document single-tab assumption. Acceptable per requirements. |
| R5 | Private browsing (Firefox/Safari) disables OPFS entirely — driver init throws | Low | Medium | Handled by fallback in worker init: catch error, post `{ backend: 'memory' }`, Kotlin keeps `IN_MEMORY`. |

---

## Epic 1: Local Dev Script

**Goal**: Single command builds wasmJs and starts the local preview server.

### Story 1.1 — `serve-web.sh`

**Acceptance**: `./scripts/serve-web.sh` builds the WASM distribution and starts `node e2e/server.mjs`.

#### Task 1.1.1 — Create `scripts/serve-web.sh`

**File**: `scripts/serve-web.sh` (new)
**Dependencies**: none

Steps:
1. `chmod +x` the script (set in the file header).
2. Build the wasmJs distribution:
   ```bash
   ./gradlew :kmp:wasmJsBrowserDistribution -PenableJs=true
   ```
3. Start the server pointing at the output directory:
   ```bash
   node e2e/server.mjs
   ```
4. Print the local URL (`http://localhost:8787`) to stdout.

Script skeleton:
```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "Building wasmJs distribution..."
./gradlew :kmp:wasmJsBrowserDistribution -PenableJs=true

echo ""
echo "Starting local server at http://localhost:8787"
node e2e/server.mjs
```

**Notes**: No port configuration needed — `server.mjs` already defaults to 8787. Must work on macOS and Linux (no GNU-specific flags). Script lives alongside existing `scripts/benchmark-local.sh`.

---

## Epic 2: OPFS SQLite Driver

**Goal**: `DriverFactory.js.kt` returns a working `SqlDriver` backed by `@sqlite.org/sqlite-wasm` using the `opfs-sahpool` VFS. Fallback to `IN_MEMORY` if OPFS is unavailable.

### Story 2.1 — JS Layer (Worker + npm dependency)

Tasks 2.1.1 and 2.1.2 are **parallel** (no dependency between them).

#### Task 2.1.1 — Add `@sqlite.org/sqlite-wasm` npm dependency

**File**: `kmp/build.gradle.kts`
**Dependencies**: none

In the `wasmJsMain` source-set dependencies block (currently has a `// Phase B` comment at line ~154):

```kotlin
val wasmJsMain by getting {
    dependencies {
        implementation(npm("@sqlite.org/sqlite-wasm", "3.46.1"))
    }
}
```

**Notes**: Use `npm()` Gradle function, not the `"npm:..."` string syntax. Version 3.46.1 is the version used in research; pin explicitly. The package ships `sqlite3.wasm`; webpack 5 must copy it as a static asset. Verify the `sqlite3.wasm` is present in `kmp/build/dist/wasmJs/productionExecutable/` after first build.

#### Task 2.1.2 — Create `sqlite-stelekit-worker.js`

**File**: `kmp/src/wasmJsMain/resources/sqlite-stelekit-worker.js` (new)
**Dependencies**: none (parallel with 2.1.1)

This is a pure JS Web Worker file. It must:

1. Import `sqlite3InitModule` from `@sqlite.org/sqlite-wasm` (ES module import — worker must be launched with `{ type: 'module' }`).
2. On `type: 'init'` message: call `sqlite3InitModule`, then `installOpfsSAHPoolVfs` with `clearOnInit: false`. Open `OpfsSAHPoolDb` at the given `dbPath`. Post `{ type: 'ready', backend: 'opfs-sahpool' }` on success, or fall back to `:memory:` and post `{ type: 'ready', backend: 'memory', warning: '...' }` on failure.
3. On `type: 'exec'` message: run `db.exec({ sql, bind, rowMode: 'object', ... })`, collect rows, post `{ type: 'result', id, rows }`.
4. On `type: 'query'` message: same as exec but used for SELECT paths.
5. On `type: 'transaction-begin'` / `type: 'transaction-end'` / `type: 'transaction-rollback'`: delegate to `db.transaction()` or manual BEGIN/COMMIT/ROLLBACK.
6. On `type: 'execute-long'` message (for DDL/migrations returning row count): run exec, post `{ type: 'long-result', id, value }`.

Message protocol (Kotlin ↔ Worker):

```
Kotlin → Worker:
  { type: 'init', dbPath: '/stelekit/graph-<graphId>.sqlite3' }
  { type: 'exec', id: number, sql: string, bind: any[] }
  { type: 'query', id: number, sql: string, bind: any[] }
  { type: 'transaction-begin', id: number }
  { type: 'transaction-end', id: number, successful: boolean }
  { type: 'execute-long', id: number, sql: string, bind: any[] }

Worker → Kotlin:
  { type: 'ready', backend: 'opfs-sahpool' | 'memory', warning?: string }
  { type: 'result', id: number, rows: object[] }
  { type: 'long-result', id: number, value: number }
  { type: 'error', id: number, message: string }
```

Worker initialization code (reference):
```js
import sqlite3InitModule from '@sqlite.org/sqlite-wasm';

let db = null;
const pending = new Map(); // id → { resolve, reject }

async function init(dbPath) {
  const sqlite3 = await sqlite3InitModule({ print: console.log, printErr: console.error });
  try {
    const poolUtil = await sqlite3.installOpfsSAHPoolVfs({
      name: 'opfs-sahpool',
      directory: '/stelekit',
      initialCapacity: 6,
      clearOnInit: false,
    });
    db = new poolUtil.OpfsSAHPoolDb(dbPath);
    self.postMessage({ type: 'ready', backend: 'opfs-sahpool' });
  } catch (e) {
    console.warn('[SteleKit] OPFS unavailable, falling back to in-memory:', e.message);
    db = new sqlite3.oo1.DB(':memory:');
    self.postMessage({ type: 'ready', backend: 'memory', warning: e.message });
  }
}

self.onmessage = async (e) => {
  const { type, id, sql, bind, dbPath, successful } = e.data;
  try {
    if (type === 'init') { await init(dbPath); return; }
    if (type === 'exec' || type === 'query') {
      const rows = [];
      db.exec({ sql, bind: bind ?? [], rowMode: 'object', callback: r => rows.push(r) });
      self.postMessage({ type: 'result', id, rows });
    } else if (type === 'execute-long') {
      db.exec(sql, { bind: bind ?? [] });
      self.postMessage({ type: 'long-result', id, value: db.changes() });
    } else if (type === 'transaction-begin') {
      db.exec('BEGIN');
      self.postMessage({ type: 'result', id, rows: [] });
    } else if (type === 'transaction-end') {
      db.exec(successful ? 'COMMIT' : 'ROLLBACK');
      self.postMessage({ type: 'result', id, rows: [] });
    }
  } catch (e) {
    self.postMessage({ type: 'error', id, message: e.message });
  }
};
```

**Notes**: `resources/` files in `wasmJsMain` are copied to the webpack output directory by the Kotlin Gradle plugin. Verify the worker appears in `kmp/build/dist/wasmJs/productionExecutable/` (R2 risk). The `id` field is a monotonically increasing integer managed by the Kotlin side; it maps responses back to pending Kotlin coroutines.

---

### Story 2.2 — Kotlin Layer (External Declarations + SqlDriver)

Tasks in order: 2.2.1 → 2.2.2 → 2.2.3

#### Task 2.2.1 — Enable `generateAsync = true` in SQLDelight config

**File**: `kmp/build.gradle.kts` (line ~806)
**Dependencies**: none (should be done before implementing the driver, and immediately followed by running `./gradlew jvmTest testDebugUnitTest` to catch regressions)

Change:
```kotlin
sqldelight {
    databases {
        create("SteleDatabase") {
            packageName.set("dev.stapler.stelekit.db")
        }
    }
}
```

To:
```kotlin
sqldelight {
    databases {
        create("SteleDatabase") {
            packageName.set("dev.stapler.stelekit.db")
            generateAsync.set(true)
        }
    }
}
```

**Risk R1**: This regenerates ALL generated query functions to return suspend-capable `QueryResult`. The JVM `sqlite-driver` and Android `android-driver` already support this (they return `QueryResult.Value` which is compatible when called from a coroutine). Run `./gradlew ciCheck` immediately after this change. If any callers use `executeAsOne()` or `executeAsList()` (synchronous extensions), they must be replaced with `awaitAsOne()` / `awaitAsList()` on all platforms, or use `expect/actual` to keep synchronous on non-browser targets.

**Migration note**: SQLDelight's `awaitAsList()` / `awaitAsOne()` are suspend extension functions from `coroutines-extensions` — already on the classpath. The generated `Queries` classes remain the same; only `executeAsOne()` signatures change to require a coroutine context.

#### Task 2.2.2 — Create Kotlin external declarations for worker protocol

**File**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/SqliteWorkerInterop.kt` (new)
**Dependencies**: Task 2.1.1, Task 2.1.2

Declare the JS interop functions needed to create a worker and exchange messages:

```kotlin
package dev.stapler.stelekit.db

import kotlin.js.Promise

// Spawn a module-type Web Worker
internal fun createSqliteWorker(scriptPath: String): JsAny =
    js("new Worker(scriptPath, { type: 'module' })")

// Send a message to the worker
internal fun workerPostMessage(worker: JsAny, message: JsAny): Unit =
    js("worker.postMessage(message)")

// Register the onmessage callback
internal fun workerOnMessage(worker: JsAny, handler: (JsAny) -> Unit): Unit =
    js("worker.onmessage = function(e) { handler(e.data) }")

// Build a plain JS object literal for the message payload
internal fun buildInitMessage(dbPath: String): JsAny =
    js("({ type: 'init', dbPath: dbPath })")

internal fun buildExecMessage(id: Int, sql: String, bindJson: String): JsAny =
    js("({ type: 'exec', id: id, sql: sql, bind: JSON.parse(bindJson) })")

internal fun buildQueryMessage(id: Int, sql: String, bindJson: String): JsAny =
    js("({ type: 'query', id: id, sql: sql, bind: JSON.parse(bindJson) })")

internal fun buildTransactionBeginMessage(id: Int): JsAny =
    js("({ type: 'transaction-begin', id: id })")

internal fun buildTransactionEndMessage(id: Int, successful: Boolean): JsAny =
    js("({ type: 'transaction-end', id: id, successful: successful })")

internal fun buildExecuteLongMessage(id: Int, sql: String, bindJson: String): JsAny =
    js("({ type: 'execute-long', id: id, sql: sql, bind: JSON.parse(bindJson) })")

// Read fields from a response JsAny (worker postMessage data)
internal fun getMessageType(msg: JsAny): String = js("msg.type")
internal fun getMessageId(msg: JsAny): Int = js("msg.id")
internal fun getMessageRows(msg: JsAny): JsAny = js("msg.rows")
internal fun getMessageValue(msg: JsAny): Long = js("BigInt(msg.value)")
internal fun getMessageError(msg: JsAny): String = js("msg.message")
internal fun getMessageBackend(msg: JsAny): String = js("msg.backend")
internal fun getMessageWarning(msg: JsAny): String? = js("msg.warning || null")
internal fun rowsToJsonString(rows: JsAny): String = js("JSON.stringify(rows)")

// Create a Promise that wraps worker response for a given message id
internal fun createWorkerResponsePromise(worker: JsAny, id: Int): Promise<JsAny> =
    js("""new Promise(function(resolve, reject) {
        function handler(e) {
            var data = e.data;
            if ((data.type === 'result' || data.type === 'long-result' || data.type === 'error') && data.id === id) {
                worker.removeEventListener('message', handler);
                if (data.type === 'error') { reject(new Error(data.message)); }
                else { resolve(data); }
            }
        }
        worker.addEventListener('message', handler);
    })""")
```

**Notes**: Kotlin/WASM requires all JS-boundary values to be `JsAny`. Parameter binding values must be JSON-serialized in Kotlin and parsed in JS (avoids complex `JsAny` array construction). Row results are JSON-stringified in JS and parsed in Kotlin using `kotlinx.serialization`. The `Promise<JsAny>` type is imported from `kotlin.js`.

#### Task 2.2.3 — Implement `WasmOpfsSqlDriver`

**File**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/WasmOpfsSqlDriver.kt` (new)
**Dependencies**: Task 2.2.2

Implement `app.cash.sqldelight.db.SqlDriver`. Key design decisions:

- All operations return `QueryResult.AsyncValue { ... }` (requires `generateAsync = true` from 2.2.1).
- A `suspend fun init(dbPath: String)` must be called before `createDriver()` returns — `DriverFactory` is responsible for calling `init` before handing the driver to `GraphManager`.
- An internal `AtomicInteger` provides monotonically increasing message IDs.
- The worker is created once per driver instance. OPFS `opfs-sahpool` holds the db open for the driver lifetime.
- `addListener` / `removeListener` / `notifyListeners` maintain a `MutableMap<String, MutableSet<Query.Listener>>` — same pattern as the in-memory driver.

```kotlin
package dev.stapler.stelekit.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class WasmOpfsSqlDriver(private val workerScriptPath: String) : SqlDriver {

    private val worker: JsAny = createSqliteWorker(workerScriptPath)
    private var nextId = 0
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()
    var actualBackend: String = "unknown"
        private set

    // Must be called once before using the driver
    suspend fun init(dbPath: String) {
        val readyPromise: Promise<JsAny> = js("""new Promise(function(resolve) {
            worker.addEventListener('message', function onReady(e) {
                if (e.data.type === 'ready') {
                    worker.removeEventListener('message', onReady);
                    resolve(e.data);
                }
            });
        })""")
        workerPostMessage(worker, buildInitMessage(dbPath))
        val readyMsg = readyPromise.await()
        actualBackend = getMessageBackend(readyMsg)
        val warning = getMessageWarning(readyMsg)
        if (warning != null) {
            console.warn("[SteleKit] SQLite worker fallback: $warning")
        }
    }

    private fun nextMsgId(): Int = nextId++

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<Long> = QueryResult.AsyncValue {
        val id = nextMsgId()
        val bindValues = if (binders != null) {
            val collector = JsBindCollector()
            binders(collector)
            collector.toJsonString()
        } else "[]"
        val promise = createWorkerResponsePromise(worker, id)
        workerPostMessage(worker, buildExecuteLongMessage(id, sql, bindValues))
        val resp = promise.await()
        getMessageValue(resp)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<R> = QueryResult.AsyncValue {
        val id = nextMsgId()
        val bindValues = if (binders != null) {
            val collector = JsBindCollector()
            binders(collector)
            collector.toJsonString()
        } else "[]"
        val promise = createWorkerResponsePromise(worker, id)
        workerPostMessage(worker, buildQueryMessage(id, sql, bindValues))
        val resp = promise.await()
        val rowsJson = rowsToJsonString(getMessageRows(resp))
        val rows = Json.decodeFromString<JsonArray>(rowsJson)
        val cursor = JsonArraySqlCursor(rows)
        mapper(cursor).await()
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> = QueryResult.AsyncValue {
        val id = nextMsgId()
        val promise = createWorkerResponsePromise(worker, id)
        workerPostMessage(worker, buildTransactionBeginMessage(id))
        promise.await()
        WasmTransaction(this)
    }

    override fun endTransaction(successful: Boolean): QueryResult<Unit> = QueryResult.AsyncValue {
        val id = nextMsgId()
        val promise = createWorkerResponsePromise(worker, id)
        workerPostMessage(worker, buildTransactionEndMessage(id, successful))
        promise.await()
        Unit
    }

    override fun currentTransaction(): Transacter.Transaction? = null

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { key -> listeners.getOrPut(key) { mutableSetOf() }.add(listener) }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { key -> listeners[key]?.remove(listener) }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        queryKeys.forEach { key -> listeners[key]?.forEach { it.queryResultsChanged() } }
    }

    override fun close() {
        // Worker termination is optional; browser GC will handle it
    }
}
```

Supporting classes needed in the same package or file:

- `JsBindCollector : SqlPreparedStatement` — collects bound values into a JSON-serializable list.
- `JsonArraySqlCursor : SqlCursor` — wraps a `JsonArray` of `JsonObject` rows, implementing `getString`, `getLong`, `getDouble`, `getBytes`, `getBoolean` by column index (requires knowing column names — use ordered index from SQLDelight's generated queries which always use positional columns).
- `WasmTransaction : Transacter.Transaction` — delegates `commit()`/`rollback()` back to the driver.

**Important**: `JsonArraySqlCursor` must handle SQLDelight's positional column binding. The worker returns rows as `{ columnName: value }` objects. The cursor needs a column-name-to-index mapping derived from the first row's key order. This is the most fiddly implementation detail — test thoroughly with the migration queries.

---

### Story 2.3 — Integration (DriverFactory + FileSystem + Main.kt)

Tasks in order: 2.3.1 → 2.3.2 → 2.3.3 (parallel) → 2.3.4

#### Task 2.3.1 — Wire `DriverFactory.js.kt` to try OPFS then fall back

**File**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/DriverFactory.js.kt`
**Dependencies**: Task 2.2.3

Replace the `UnsupportedOperationException` stub:

```kotlin
package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver

actual class DriverFactory actual constructor() {
    actual fun init(context: Any) {}

    actual fun createDriver(jdbcUrl: String): SqlDriver {
        // Caller must use createDriverAsync() on wasmJs — this path should not be reached
        throw UnsupportedOperationException("Use createDriverAsync() on wasmJs")
    }

    // OPFS path extracted from the fake JDBC URL: "jdbc:sqlite:stelekit-graph-<graphId>"
    actual fun getDatabaseUrl(graphId: String): String = "jdbc:sqlite:stelekit-graph-$graphId"
    actual fun getDatabaseDirectory(): String = "/stelekit"

    // Async entry point — called from browser/Main.kt before GraphManager.addGraph()
    suspend fun createDriverAsync(graphId: String): SqlDriver {
        val opfsPath = "/stelekit/graph-${graphId}.sqlite3"
        val driver = WasmOpfsSqlDriver(workerScriptPath = "./sqlite-stelekit-worker.js")
        driver.init(opfsPath)
        // Create schema (idempotent) and run migrations
        SteleDatabase.Schema.create(driver).await()
        MigrationRunner.applyAll(driver)
        return driver
    }
}

actual val defaultDatabaseUrl: String
    get() = "jdbc:sqlite:stelekit"
```

**Notes**: The JDBC URL prefix is meaningless in WASM — the graphId is extracted directly. `createDriverAsync` is a new non-`actual` function specific to the wasmJs implementation; it does not need an `expect` declaration.

**Worker script path**: The literal `"./sqlite-stelekit-worker.js"` assumes the worker file is served at the same path as `index.html`. Verify this matches the actual webpack output path after the first build (R2 risk).

**MigrationRunner**: Verify `MigrationRunner.applyAll(driver)` works with an async driver. If it calls `driver.execute(...)` synchronously (expecting `QueryResult.Value`), it will need to be wrapped in a coroutine or refactored to use `.await()`. Inspect `MigrationRunner.kt` at implementation time.

#### Task 2.3.2 — Implement OPFS-backed `PlatformFileSystem`

**File**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`
**Dependencies**: none (parallel with 2.3.1 after 2.2.3 completes, but can be worked independently)

Replace the stub with an **Option B pre-load implementation** (in-memory cache, async OPFS write-through):

```kotlin
package dev.stapler.stelekit.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

actual class PlatformFileSystem actual constructor() : FileSystem {
    private val homeDir = "/stelekit"
    private val cache = mutableMapOf<String, String>()   // path → content
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Call once at startup in a suspend context before passing to GraphManager
    suspend fun preload(graphPath: String) {
        // List files in OPFS at graphPath and load them into cache
        // Uses JS interop calls to navigator.storage.getDirectory()
        // See OpfsFileSystemInterop.kt for JS interop helpers
        loadDirectory(graphPath)
    }

    // Synchronous reads from cache (GraphLoader calls these)
    actual override fun readFile(path: String): String? = cache[path]
    actual override fun fileExists(path: String): Boolean = cache.containsKey(path)
    actual override fun listFiles(path: String): List<String> =
        cache.keys.filter { it.startsWith(path) && !it.removePrefix(path).drop(1).contains('/') }
    actual override fun listDirectories(path: String): List<String> = emptyList()  // deferred

    // Sync write: update cache immediately, write to OPFS asynchronously
    actual override fun writeFile(path: String, content: String): Boolean {
        cache[path] = content
        scope.launch { opfsWriteFile(path, content) }
        return true
    }

    actual override fun getDefaultGraphPath(): String = homeDir
    actual override fun expandTilde(path: String): String =
        if (path.startsWith("~")) path.replaceFirst("~", homeDir) else path
    actual override fun directoryExists(path: String): Boolean = true   // optimistic
    actual override fun createDirectory(path: String): Boolean = true   // OPFS dirs created lazily
    actual override fun deleteFile(path: String): Boolean {
        cache.remove(path)
        scope.launch { opfsDeleteFile(path) }
        return true
    }
    actual override fun pickDirectory(): String? = null
    actual override suspend fun pickDirectoryAsync(): String? = null
    actual override fun getLastModifiedTime(path: String): Long? = null
    actual override fun displayNameForPath(path: String): String = path.substringAfterLast('/')
    actual override fun getDownloadsPath(): String = homeDir
}
```

A companion file `OpfsFileSystemInterop.kt` (same package) provides:
- `suspend fun loadDirectory(path: String)` — uses `js("navigator.storage.getDirectory()")` wrapped as `Promise<JsAny>` to walk the OPFS directory tree and populate the cache.
- `suspend fun opfsWriteFile(path: String, content: String)` — async OPFS write via `createWritable()`.
- `suspend fun opfsDeleteFile(path: String)` — OPFS `removeEntry()`.

**Notes**: The cache pre-load happens once at startup in `browser/Main.kt` before `GraphManager.addGraph()`. Writes are fire-and-forget to the background scope (acceptable durability for a note-taking app — crashes between cache update and OPFS flush lose at most one debounce window of edits, consistent with the existing JVM behavior).

#### Task 2.3.3 — Update `browser/Main.kt` to use SQLDELIGHT backend

**File**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`
**Dependencies**: Task 2.3.1 (createDriverAsync), Task 2.3.2 (PlatformFileSystem.preload)

Replace the `CanvasBasedWindow` body. The key challenge: `CanvasBasedWindow` takes a `@Composable` lambda — suspend calls are not allowed there. The OPFS initialization must happen before the Composable runs.

Approach: initialize OPFS in a coroutine at the top of `main()`, then start `CanvasBasedWindow` after the driver is ready:

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Initialize OPFS outside of the Composable tree
    val scope = MainScope()   // or CoroutineScope(Dispatchers.Default)
    val graphId = "default"

    scope.launch {
        val fileSystem = PlatformFileSystem()
        fileSystem.preload("/stelekit/$graphId")

        val driverFactory = DriverFactory()
        val driver = try {
            driverFactory.createDriverAsync(graphId)
        } catch (e: Exception) {
            console.warn("[SteleKit] OPFS driver init failed, using IN_MEMORY: ${e.message}")
            null
        }

        val backend = if (driver != null) GraphBackend.SQLDELIGHT else GraphBackend.IN_MEMORY
        val graphManager = GraphManager(
            platformSettings = PlatformSettings(),
            driverFactory = driverFactory,
            fileSystem = fileSystem,
            defaultBackend = backend,
        )

        val graphPath = "/stelekit/$graphId"
        graphManager.addGraph(graphPath)

        // Signal to Playwright that the app is ready (for e2e tests)
        js("window.__stelekit_ready = true")

        CanvasBasedWindow(canvasElementId = "ComposeTarget") {
            StelekitApp(
                fileSystem = fileSystem,
                graphPath = graphPath,
                graphManager = graphManager,
            )
        }
    }
}
```

**Notes**: `GraphManager.addGraph()` is currently called inside the Composable via navigation. Check if it can be called outside — if `GraphManager` requires the Compose lifecycle, this pattern needs adjustment. `window.__stelekit_ready = true` is used by Playwright tests to know initialization is complete. `DemoFileSystem` is no longer used as the primary file system (can be kept as a fallback if OPFS init fails).

#### Task 2.3.4 — Verify Webpack copies `sqlite3.wasm` and worker file

**File**: build verification (no code change, but documents what to check)
**Dependencies**: Tasks 2.1.1, 2.1.2

After `./gradlew :kmp:wasmJsBrowserDistribution -PenableJs=true`, verify:

1. `kmp/build/dist/wasmJs/productionExecutable/sqlite3.wasm` — the SQLite binary (from npm package).
2. `kmp/build/dist/wasmJs/productionExecutable/sqlite-stelekit-worker.js` — the worker script.

If either is missing:
- For `sqlite3.wasm`: may need a webpack copy plugin config. Check if `@sqlite.org/sqlite-wasm` package uses `import.meta.url` asset references (webpack 5 handles these automatically as asset modules).
- For the worker script: verify `resources/` files are copied. The Kotlin Gradle plugin should copy `wasmJsMain/resources/` to the output directory — if not, add an explicit `Copy` Gradle task.

If the worker path resolves differently (e.g., `composeResources/sqlite-stelekit-worker.js`), update the `workerScriptPath` literal in `DriverFactory.js.kt`.

---

## Epic 3: E2E Persistence Tests

**Goal**: Playwright test suite verifies OPFS persistence and test isolation.

### Story 3.1 — Persistence test and isolation

**Dependencies**: Epic 2 complete

#### Task 3.1.1 — Add OPFS persistence test to `demo.spec.ts`

**File**: `e2e/tests/demo.spec.ts`
**Dependencies**: Epic 2 complete (OPFS driver working)

Add a new test after the existing canvas-paint test:

```typescript
test('SteleKit OPFS: data persists across page reload', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', err => errors.push(err.message));

  await page.goto('/');

  // Wait for WASM init + OPFS driver ready
  await page.waitForFunction(
    () => (window as any).__stelekit_ready === true,
    { timeout: 30_000 },
  );

  // Reload — OPFS data survives within the same Playwright browser context
  await page.reload();

  await page.waitForFunction(
    () => (window as any).__stelekit_ready === true,
    { timeout: 30_000 },
  );

  // Assert OPFS directory exists (proves the driver wrote to OPFS, not just memory)
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
```

**Notes**: This test uses `page.reload()` within the same browser context — OPFS data survives within the same context (Playwright does not clear origin storage between `goto`/`reload` by default). The `__stelekit_ready` flag is set in `browser/Main.kt` (Task 2.3.3) after the OPFS driver is initialized.

#### Task 3.1.2 — Add OPFS storage clearing in `beforeEach` for test isolation

**File**: `e2e/tests/demo.spec.ts`
**Dependencies**: Task 3.1.1

Add a `beforeEach` hook that clears the OPFS `stelekit` directory before each test run to prevent test-order dependencies:

```typescript
test.beforeEach(async ({ page }) => {
  await page.goto('/');
  // Clear OPFS stelekit directory for isolation
  await page.evaluate(async () => {
    try {
      const root = await navigator.storage.getDirectory();
      await root.removeEntry('stelekit', { recursive: true });
    } catch {
      // Directory may not exist on first run — ignore
    }
  });
});
```

**Notes**: This must run after `page.goto('/')` because OPFS is only accessible from an origin context (requires the page to be loaded). Placing it before the persistence test means the persistence test starts with a clean slate and verifies that the app creates the OPFS directory from scratch — which is the correct behavior for first launch.

---

## Sequencing Summary

```
Epic 1 (no blockers, can be done any time):
  1.1.1 — scripts/serve-web.sh

Epic 2:
  Parallel: 2.1.1 (npm dep), 2.1.2 (sqlite-stelekit-worker.js)
  Then 2.2.1 (generateAsync=true) — IMMEDIATELY run ciCheck
  Then 2.2.2 (external declarations) — needs 2.1.1, 2.1.2 done
  Then 2.2.3 (WasmOpfsSqlDriver) — needs 2.2.2
  Then 2.3.1 (DriverFactory) — needs 2.2.3
       2.3.2 (PlatformFileSystem) — needs 2.2.3 (can parallel with 2.3.1)
       2.3.3 (browser/Main.kt) — needs 2.3.1 and 2.3.2
  Then 2.3.4 (build verification) — needs 2.3.3

Epic 3:
  3.1.1 (persistence test) — needs Epic 2
  3.1.2 (beforeEach isolation) — needs 3.1.1
```

---

## File Map

| File | Status | Task |
|------|--------|------|
| `scripts/serve-web.sh` | New | 1.1.1 |
| `kmp/build.gradle.kts` | Modify (npm dep + generateAsync) | 2.1.1, 2.2.1 |
| `kmp/src/wasmJsMain/resources/sqlite-stelekit-worker.js` | New | 2.1.2 |
| `kmp/src/wasmJsMain/kotlin/.../db/SqliteWorkerInterop.kt` | New | 2.2.2 |
| `kmp/src/wasmJsMain/kotlin/.../db/WasmOpfsSqlDriver.kt` | New | 2.2.3 |
| `kmp/src/wasmJsMain/kotlin/.../db/DriverFactory.js.kt` | Modify | 2.3.1 |
| `kmp/src/wasmJsMain/kotlin/.../platform/PlatformFileSystem.kt` | Modify | 2.3.2 |
| `kmp/src/wasmJsMain/kotlin/.../platform/OpfsFileSystemInterop.kt` | New | 2.3.2 |
| `kmp/src/wasmJsMain/kotlin/.../browser/Main.kt` | Modify | 2.3.3 |
| `e2e/tests/demo.spec.ts` | Modify | 3.1.1, 3.1.2 |

Kotlin package path abbreviation: `dev.stapler.stelekit`

---

## Technology Choices

| Decision | Choice | Rationale |
|----------|--------|-----------|
| OPFS VFS | `opfs-sahpool` | 3-4x faster than `opfs`; no SAB required; single-tab is sufficient; supports Safari 16.4+ (target is 18.2+) |
| SQLite API | Direct `sqlite3InitModule` | `Worker1/Promiser` API deprecated 2026-04-15; direct API is the upstream recommendation |
| npm package | `@sqlite.org/sqlite-wasm@3.46.1` (bundled) | CDN introduces COEP/CORP risk with `require-corp`; local bundle is safer |
| SqlDriver approach | Custom `WasmOpfsSqlDriver` with `QueryResult.AsyncValue` | No `web-worker-driver-wasm-js` at 2.3.2; 2.1.0 version mismatch risk is too high |
| `generateAsync` | `true` (global) | Correct long-term direction; JVM/Android drivers are compatible with async callers |
| FileSystem bridge | Option B (in-memory pre-load cache) | Avoids breaking the `FileSystem` sync interface; consistent with `DemoFileSystem` pattern; simpler than suspend variants across all platforms |
| Worker spawning | `js("new Worker(path, { type: 'module' })")` | No native Kotlin/WASM Web Worker API exists; ES module workers required for `import` in worker |
| Playwright OPFS clearing | `page.evaluate` + `removeEntry` in `beforeEach` | Must clear within origin context; Playwright's standard context isolation does not clear OPFS |
