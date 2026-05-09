# Agent 3 — Architecture Research: SqlDriver, FileSystem, and Integration Design

## 1. The `FileSystem` Interface — What OPFS Must Implement

The common `FileSystem` interface (at `kmp/src/commonMain/.../platform/FileSystem.kt`) defines:

```kotlin
interface FileSystem {
    fun getDefaultGraphPath(): String
    fun expandTilde(path: String): String
    fun readFile(path: String): String?
    fun writeFile(path: String, content: String): Boolean
    fun listFiles(path: String): List<String>
    fun listDirectories(path: String): List<String>
    fun fileExists(path: String): Boolean
    fun directoryExists(path: String): Boolean
    fun createDirectory(path: String): Boolean
    fun deleteFile(path: String): Boolean
    fun pickDirectory(): String?
    suspend fun pickDirectoryAsync(): String? = pickDirectory()
    fun getLastModifiedTime(path: String): Long?
    fun listFilesWithModTimes(path: String): List<Pair<String, Long>>
    fun hasStoragePermission(): Boolean = true
    fun getLibraryDisplayName(): String? = null
    fun displayNameForPath(path: String): String
    fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
    fun stopExternalChangeDetection() {}
    fun renameFile(from: String, to: String): Boolean = false
    fun getDownloadsPath(): String
    suspend fun pickSaveFileAsync(suggestedName: String, mimeType: String): String? = null
    fun updateShadow(path: String, content: String) {}
    fun invalidateShadow(path: String) {}
    suspend fun syncShadow(graphPath: String) {}
}
```

The current `PlatformFileSystem.kt` (wasmJsMain) is a stub — all methods return null/empty/false.

### Methods Requiring OPFS Implementation

For the OPFS-backed FileSystem (needed for `GraphLoader`/`GraphWriter` to work with markdown files):

| Method | OPFS Implementation Strategy |
|---|---|
| `readFile(path)` | OPFS async: `getFileHandle` → `getFile()` → `text()` |
| `writeFile(path, content)` | OPFS async: `getFileHandle({create:true})` → `createWritable()` → write → close |
| `listFiles(path)` | OPFS async: `getDirectoryHandle` → `for await (entry of dir)` |
| `listDirectories(path)` | Same as listFiles, filter to directories |
| `fileExists(path)` | Try `getFileHandle`, catch `NotFoundError` |
| `directoryExists(path)` | Try `getDirectoryHandle`, catch `NotFoundError` |
| `createDirectory(path)` | `getDirectoryHandle(name, {create:true})` |
| `deleteFile(path)` | `dir.removeEntry(name)` |
| `getLastModifiedTime(path)` | `getFileHandle` → `getFile()` → `file.lastModified` |
| `getDefaultGraphPath()` | Return `/stelekit/graph` (OPFS path) |
| `pickDirectory()` | Return null (not supported in browser without File System Access API) |

**CRITICAL CONSTRAINT**: All OPFS APIs are async (`Promise`-returning). The `FileSystem` interface uses synchronous signatures (`fun readFile(): String?`). This is an **impedance mismatch**.

### Resolving the Async Impedance Mismatch

Options:

**Option A — Suspend functions in the FileSystem interface** (breaking change to all platforms):
Add `suspend` variants alongside sync ones. The interface already has `suspend fun pickDirectoryAsync()`. Pattern: add `suspend fun readFileAsync()` etc. Callers in `GraphLoader` use the suspend variant on browser, sync variant on JVM/Android.

**Option B — All-in-memory pre-load** (OPFS → memory cache):
On browser startup, eagerly load all OPFS files into an in-memory map (the `DemoFileSystem` pattern). `GraphLoader` reads from memory; writes go to OPFS asynchronously in background. This matches how the existing `DemoFileSystem` works and avoids interface changes.

**Option C — `runBlocking` equivalent** (NOT viable in WASM):
Kotlin/WASM is single-threaded. There is no `runBlocking` — blocking the coroutine dispatcher would deadlock the browser.

**Option D — Worker-only file I/O**:
Run the entire FileSystem implementation inside the SQLite worker, so sync access handles can be used for file reads too. This couples SQLite and FileSystem in one worker, which is complex but avoids async issues.

**Recommended: Option B** for Phase B implementation. Pre-load the OPFS file tree into a `MutableMap<String, String>` at startup (in a `suspend fun init()` called before `GraphManager.addGraph()`), then use the in-memory cache for sync reads. Writes go to OPFS asynchronously. This is consistent with `DemoFileSystem` design and keeps the `FileSystem` interface unchanged.

---

## 2. JVM `DriverFactory` — What a Real Implementation Looks Like

From `kmp/src/jvmMain/.../db/DriverFactory.jvm.kt`:

```kotlin
actual class DriverFactory actual constructor() {
    actual fun createDriver(jdbcUrl: String): SqlDriver {
        // 1. Ensure parent directory exists
        // 2. Create connection pool with WAL + busy_timeout
        val driver = PooledJdbcSqliteDriver(jdbcUrl, props, poolSize = 8)
        // 3. Create schema (idempotent)
        SteleDatabase.Schema.create(driver)
        // 4. Run migrations
        MigrationRunner.applyAll(driver)
        return driver
    }
    actual fun getDatabaseUrl(graphId: String): String = "jdbc:sqlite:${dir}/stelekit-graph-$graphId.db"
    actual fun getDatabaseDirectory(): String = jvmDatabaseDirectory()
}
```

The expected `SqlDriver` contract for SteleKit:
1. Created by `DriverFactory.createDriver(jdbcUrl)` — the `jdbcUrl` is purely a naming convention; in WASM the path is the OPFS path not a JDBC string.
2. Must support `SteleDatabase.Schema.create(driver)` — this calls `execute()` with `CREATE TABLE IF NOT EXISTS` DDL.
3. Must support `MigrationRunner.applyAll(driver)` — series of `execute()` calls with ALTER TABLE / INSERT.
4. Must support `SteleDatabaseQueries` — all the generated SELECT/INSERT/UPDATE/DELETE operations.

---

## 3. SQLDelight `SqlDriver` Interface

The `app.cash.sqldelight:runtime` interface (simplified):

```kotlin
interface SqlDriver : Closeable {
    fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)? = null
    ): QueryResult<Long>

    fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)? = null
    ): QueryResult<R>

    fun newTransaction(): QueryResult<Transacter.Transaction>
    fun endTransaction(successful: Boolean): QueryResult<Unit>
    fun currentTransaction(): Transacter.Transaction?

    fun addListener(vararg queryKeys: String, listener: Query.Listener)
    fun removeListener(vararg queryKeys: String, listener: Query.Listener)
    fun notifyListeners(vararg queryKeys: String)

    override fun close()
}
```

Key: `QueryResult<T>` has two subtypes:
- `QueryResult.Value<T>` — synchronous result
- `QueryResult.AsyncValue<T>` — async result (returns a `suspend fun await(): T`)

The web-worker-driver returns `AsyncValue` for all operations. For a custom Kotlin/WASM driver talking to an OPFS-backed SQLite worker, all operations must also return `AsyncValue` (wrapping Promise-based message-passing to the worker).

### Schema.create() Compatibility

`SteleDatabase.Schema` is generated by SQLDelight. If the Gradle config has `generateAsync = false` (current state), `Schema.create()` expects synchronous `SqlDriver.execute()` (returning `QueryResult.Value`). If `generateAsync = true`, it expects `AsyncValue`.

**CRITICAL**: The current SteleKit build does not set `generateAsync = true` in `sqldelight {}`. Changing this would regenerate ALL query code to use suspend functions, affecting JVM and Android too (they'd need async-capable drivers or shims). This is a significant change.

**Recommended approach**: Keep `generateAsync = false`. Implement the WASM `SqlDriver` to execute SQL **synchronously** from the Kotlin perspective by using a **synchronous wrapper** approach:

Since `opfs-sahpool` in a worker provides `db.exec()` synchronously (the worker itself is async from the main thread's perspective, but internally sync), the architecture becomes:

```
Kotlin/WASM main (coroutine suspend) ←→ JS Worker (sync SQLite via opfs-sahpool)
```

The Kotlin side uses `Promise.await()` to suspend the coroutine until the worker responds. The `SqlDriver` returns `QueryResult.Value(...)` after awaiting the worker response. The SQLDelight-generated code does not need to change.

---

## 4. Should the WASM SQLite Driver Live Entirely in Kotlin or Use a JS Adapter?

### Option A: Full Kotlin/WASM `SqlDriver` with JS Interop

```kotlin
class OpfsSqlDriver(private val worker: JsAny) : SqlDriver {
    override fun execute(identifier: Int?, sql: String, ...): QueryResult<Long> {
        // This is the key problem: SqlDriver.execute() is NOT suspend
        // We cannot call Promise.await() from a non-suspend function
        // → This approach requires QueryResult.AsyncValue
    }
}
```

Problem: `SqlDriver.execute()` is synchronous by interface contract (returns `QueryResult<Long>` not `suspend`). Returning `AsyncValue` requires `generateAsync = true` in SQLDelight config, which changes generated code project-wide.

### Option B: JS Adapter Pattern (Recommended for Phase B)

Use a **JS-side SQLite wrapper** that handles the async-to-sync conversion internally, exposing a **synchronous-looking interface** to Kotlin:

Since `opfs-sahpool` + worker provides a synchronous SQLite interface from within the worker, and Kotlin can suspend while waiting for the worker response, the implementation looks like:

```kotlin
// In wasmJsMain — Kotlin/WASM driver
class WasmOpfsSqlDriver : SqlDriver {
    private var initialized = false

    // Called once, before creating the driver, in a suspend context
    suspend fun initialize(graphPath: String) {
        // postMessage to worker: { type: "open", path: graphPath }
        // await worker response via Promise
        initialized = true
    }

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: ...): QueryResult<Long> {
        // Cannot suspend here — must use runBlocking equivalent
        // On WASM there is no runBlocking → returns AsyncValue
        return QueryResult.AsyncValue {
            // This suspend lambda runs in a coroutine
            sendToWorkerAndAwait("exec", sql, ...)
        }
    }
}
```

This requires `generateAsync = true`. See above.

### Option C: Pre-load + In-memory SQLite (Hybrid, avoids async SqlDriver entirely)

1. On startup, open the OPFS file, read all bytes into a Kotlin `ByteArray`.
2. Pass that byte array to an **in-memory SQLite instance** (using a Kotlin/WASM WASM SQLite, e.g., sql.js equivalent).
3. All SQL operations run synchronously against the in-memory instance.
4. On writes: serialize the SQLite file back to OPFS asynchronously.

This avoids the async SqlDriver problem entirely. The existing `IN_MEMORY` backend continues to work synchronously; OPFS is just the persistence layer for loading and saving the full DB file.

**This is viable** and conceptually simpler, but has a write-durability concern (crash between in-memory write and OPFS flush).

---

## 5. Cleanest Architecture Recommendation

Given the constraints (no `generateAsync`, single-threaded WASM, no runBlocking):

### Recommended: Dedicated SQLite Worker + AsyncValue SqlDriver + generateAsync=true (scoped to wasmJs)

SQLDelight supports per-target async generation. The `generateAsync` flag applies globally, but you can use `expect`/`actual` to provide different `SqlDriver` implementations per platform without changing generated code if you use a shim.

**Alternative clean path**: Use a **blocking JS interop trick** specific to wasmJs:

In Kotlin/WASM, you can call JS synchronous functions. If the SQLite worker is replaced with a **synchronous JS wrapper** that internally uses `Atomics.wait()` to block until the worker responds, then Kotlin can call it synchronously. However, `Atomics.wait()` is blocked on the main thread in browsers (it would freeze the UI).

**Final recommendation**: The cleanest architecture for Phase B is:

1. Implement a dedicated JS worker (`sqlite-stelekit-worker.js`) using `opfs-sahpool`.
2. Expose a JS module with async functions (`openDb`, `execSql`, `execQuery`, `beginTransaction`, `commit`, `rollback`).
3. From Kotlin/WASM, use `external` declarations + `Promise.await()` to call these async functions.
4. Set `generateAsync = true` in the SQLDelight gradle config — this is the correct long-term direction per SQLDelight maintainers for browser targets.
5. Use `awaitAsList()` / `awaitAsOne()` in repository code on the wasmJs platform — or use `expect`/`actual` to keep synchronous calls on JVM/Android.

The risk: `generateAsync = true` changes ALL generated query code to suspend functions. The JVM sqlite-driver and Android driver both support `QueryResult.Value` (synchronous), which is compatible with async drivers when called from a coroutine scope. This change is additive and safe.

---

## 6. Database Directory Structure in `commonMain/db/`

Key files:
- `DriverFactory.kt` — `expect class DriverFactory()` with `init(context)`, `createDriver(jdbcUrl)`, `getDatabaseUrl(graphId)`, `getDatabaseDirectory()`
- `GraphManager.kt` — calls `DriverFactory.createDriver(getDatabaseUrl(graphId))` for each graph
- `MigrationRunner.kt` — applies SQL migrations; called in `DriverFactory.jvm.kt` after schema creation
- `DatabaseWriteActor.kt` — serializes all writes via a single coroutine actor
- `RestrictedDatabaseQueries.kt` — write-gating layer (`@DirectSqlWrite`)

The wasmJs `DriverFactory.js.kt` needs to:
1. Accept `graphId` via `getDatabaseUrl(graphId)` (currently returns a fake JDBC URL).
2. Return an actual `SqlDriver` from `createDriver()`.
3. The returned `SqlDriver` must work with `SteleDatabase.Schema.create()` and `MigrationRunner.applyAll()`.

For the OPFS path: the "URL" is just an OPFS path like `/stelekit/graph-<graphId>.sqlite3`. The JDBC URL prefix is meaningless in WASM — strip it or ignore it.

---

## Summary

- `PlatformFileSystem.kt` in wasmJsMain needs an OPFS-backed implementation with async pre-loading pattern to bridge the sync `FileSystem` interface.
- The cleanest `SqlDriver` path requires `generateAsync = true` in SQLDelight gradle config, enabling async queries project-wide (safe for JVM/Android, correct for WASM).
- The `DriverFactory.js.kt` should strip the JDBC prefix and use the remainder as an OPFS path.
- MigrationRunner must work with the async driver — verify it only calls `execute()` not synchronous result patterns.
- Architecture: Kotlin/WASM → JS external declarations → JS async functions → Web Worker → opfs-sahpool VFS → OPFS filesystem.
