# Research: Features — Comparable JNI SQLite Drivers and BEGIN CONCURRENT

_Research focus for libsql-mvcc feature_

---

## 1. sqlite-jdbc JNI Bridge and Native Library Loading

### JNI Bridge Architecture

sqlite-jdbc (xerial/sqlite-jdbc) implements its JNI bridge through a class called `SQLiteJDBCLoader`
(in `org.sqlite.SQLiteJDBCLoader`). The native library is bundled inside the JAR as a classpath
resource. On startup, the loader:

1. Detects OS and architecture via `System.getProperty("os.name")` and `os.arch`.
2. Computes a resource path of the form:
   ```
   /org/sqlite/native/<OS>/<arch>/libsqlitejdbc.<ext>
   ```
   For example: `/org/sqlite/native/Linux/x86_64/libsqlitejdbc.so`
   Recognized OS keys: `Linux`, `Mac`, `Windows`, `Android`, `FreeBSD`, etc.
   Recognized arch keys: `x86_64`, `aarch64`, `arm`, `ppc64`, etc.

3. Extracts the library to a temp directory (by default Java's temp dir via
   `System.getProperty("java.io.tmpdir")`). The extraction path can be overridden via
   `System.setProperty("org.sqlite.tmpdir", "/custom/path")`.

4. Calls `System.load(extractedPath)` (not `System.loadLibrary`) so it uses the absolute path
   to the extracted file rather than the `java.library.path` search.

5. Falls back to `System.loadLibrary("sqlitejdbc")` if the classpath resource is not found
   (i.e., the library was placed on `java.library.path` externally).

### Key Implementation Details

- The extracted filename includes the sqlite-jdbc version and a UUID suffix to avoid conflicts
  across concurrent JVMs: e.g., `sqlitejdbc-3.x.y_<uuid>.so`.
- The `NativeDB` class holds the actual `native` method declarations that the Rust JNI bridge
  must match by method signature.
- `SQLiteJDBCLoader.initialize()` is idempotent — safe to call multiple times; protected by a
  static flag.
- Architecture can be overridden via `-Dorg.sqlite.osinfo.architecture=arm` when auto-detection
  is wrong (e.g., JDK 8 on Apple M1 reports `x86_64`).
- Additional overrides: `-Dorg.sqlite.lib.path=/path/to/folder` + `-Dorg.sqlite.lib.name=libname.so`
  allow pointing to a pre-extracted library, skipping JAR extraction entirely.

### Relevance for JvmLibsqlDriver

The `JvmLibsqlDriver` should follow the same pattern:
- Bundle `libstelekit_libsql.so` (Linux), `.dylib` (macOS), `.dll` (Windows) as classpath
  resources under a stable path like `dev/stapler/stelekit/native/<OS>/<arch>/`.
- Extract to `System.getProperty("java.io.tmpdir")` or `org.sqlite.tmpdir`-equivalent property.
- Call `System.load(absolutePath)`.
- For Android, place the `.so` under `src/androidMain/jniLibs/<ABI>/` — Android's class loader
  handles extraction automatically without any Java-side code.

---

## 2. BEGIN CONCURRENT in libsql — MVCC Mechanics and SQLITE_BUSY_SNAPSHOT

### What BEGIN CONCURRENT Is

`BEGIN CONCURRENT` is a libsql extension that implements optimistic MVCC on top of SQLite WAL.
Unlike `BEGIN IMMEDIATE` (which takes a reserved/pending write lock immediately) or
`BEGIN EXCLUSIVE` (which locks immediately for the entire transaction), `BEGIN CONCURRENT`
defers lock acquisition until `COMMIT`:

1. The transaction reads proceed against a snapshot of the WAL at the time `BEGIN CONCURRENT`
   is issued — readers see a consistent point-in-time view.
2. Writes accumulate in memory without contending for the global write lock.
3. At `COMMIT`, libsql acquires the write lock and checks for conflicts with any concurrent
   committed transactions. If no conflict is detected, the commit proceeds.

### Two Implementations: Upstream SQLite vs Turso MVCC

There are two related but distinct implementations:

**Upstream SQLite `BEGIN CONCURRENT`** (`sqlite/sqlite` begin-concurrent branch):
- Conflict detection is **B-tree page–level** (4 KB page granularity).
- Two transactions conflict if they read or wrote the same page, even if different rows.
- Higher false-conflict rate for tables with many rows per page.

**Turso MVCC** (`PRAGMA journal_mode = 'mvcc'` + `BEGIN CONCURRENT`):
- Conflict detection is **row-level** (only true write-write conflicts).
- Inspired by Hekaton/MVSQLITE design; much lower false-conflict rate.
- As of libsql v0.5.0+: moved from tech-preview to beta. Unique index and integer PK
  conflicts are properly detected at commit time.
- **Requires** `PRAGMA journal_mode = 'mvcc'` — not the same as standard WAL.
- Status: some restrictions on index operations; verify against the pinned libsql version.

For SteleKit's data model — one page's blocks occupy disjoint UUID-sorted row ranges, typically
on separate B-tree leaf pages — actual conflicts should be near zero under either implementation.

### SQLITE_BUSY_SNAPSHOT

`SQLITE_BUSY_SNAPSHOT` (extended error code 517 = `SQLITE_BUSY | (3<<8)`) is returned at
`COMMIT` time (not at `BEGIN`) when:

- Another transaction has already committed changes that overlap (page-level or row-level,
  depending on implementation) the current transaction's read or write set.
- The current transaction's snapshot is now stale and it **cannot** safely commit.

This is distinct from ordinary `SQLITE_BUSY` (code 5), which means a writer lock is held by
someone else (a timing issue that `busy_timeout` or a brief sleep can resolve).
`SQLITE_BUSY_SNAPSHOT` means the transaction must be **fully retried** from the beginning:

1. `ROLLBACK` the failed transaction (mandatory — the snapshot is discarded).
2. Re-issue `BEGIN CONCURRENT`.
3. Re-execute all reads and writes.
4. Re-attempt `COMMIT`.

Sleeping does not help — the snapshot is fundamentally stale regardless of wait time.

### Recommended Retry Pattern

No official backoff specification exists in the libsql docs. Standard practice:

- **Immediate rollback and retry** for low-contention workloads (SteleKit's expected case).
- Optional **exponential backoff** (start 1–5 ms, cap at ~100 ms) for high-contention scenarios.
- **Bounded retry count** (3–5 attempts) before surfacing a `DomainError.DatabaseError`.

For SteleKit's `DatabaseWriteActor`, retry logic belongs _outside_ the driver in the
actor's execute-loop (post-driver milestone per requirements). The driver must surface the error
distinctly — e.g., a typed `LibsqlBusySnapshot` exception — so the actor can distinguish it
from other errors.

Example Rust retry pattern:
```rust
loop {
    match conn.execute_batch("BEGIN CONCURRENT; /* writes */; COMMIT") {
        Ok(_) => break,
        Err(e) if is_busy_snapshot(&e) => { conn.execute("ROLLBACK").ok(); continue; }
        Err(e) => return Err(e),
    }
}
```

### Rust Crate for Local Embedding

The crate is `libsql` on crates.io (published by Turso). For local-only use:

```toml
[dependencies]
libsql = { version = "0.x", default-features = false, features = ["core"] }
tokio = { version = "1", features = ["rt", "rt-multi-thread"] }
```

- `core` feature: embedded local database + embedded replica support, no HTTP/cloud sync.
- `replication` feature: HTTP sync from remote — **not needed** for SteleKit's local-only goal.
- `libsql-sys` is the raw FFI bindings layer; `libsql` builds on top of it.
- `libsql::Database::open(path)` opens a local file database; `Database::open_in_memory()`
  for in-memory.
- Transaction behavior: `conn.transaction_with_behavior(TransactionBehavior::Concurrent)` or
  equivalent execute of `BEGIN CONCURRENT`.

---

## 3. Existing KMP / Android Projects Using libsql Locally

### Current Landscape (as of mid-2026)

No KMP project has been found that uses libsql as a local embedded database (non-cloud) via a
SQLDelight `SqlDriver` interface. The libsql ecosystem is heavily oriented toward Turso's cloud
sync offering. SteleKit would be pioneering this pattern.

**Official Android SDK**: `tursodatabase/libsql-android` (Maven: `tech.turso.libsql:libsql:0.1.2`).

- Provides Java/Kotlin JNI bindings for Android — **in technical preview**.
- Supports local embedded mode: `Libsql.open(path = "./local.db")` with no URL/auth.
- Exposes a Kotlin API but is **not** a SQLDelight `SqlDriver` implementation.
- Android-only (not a KMP artifact); no `commonMain`/`iosMain`/`jvmMain` split.
- Does not implement the `SqlDriver` interface — a custom JNI bridge is still required.

**Turso GitHub issue #6069** explicitly tracks the KMP SDK gap. As of 2026, there is no official
KMP libsql driver.

**Known performance issue (#1458)**: libsql local mode is ~3–5× slower than rusqlite in
microbenchmarks due to Tokio async overhead per statement. For SteleKit this is largely mitigated
by `DatabaseWriteActor` (all writes already batched in transactions), but microbenchmarks that
issue many tiny single-statement transactions may understate libsql's real-world benefit.

### Implications

1. **Novel JNI infrastructure**: SteleKit's `JvmLibsqlDriver` and `AndroidLibsqlDriver` are
   novel — no prior art for libsql+SQLDelight+JNI. `libsql-android` is the closest reference
   but targets a different API surface.
2. **Tokio + JNI thread model**: The `block_on` approach per JNI call requires a global Tokio
   runtime initialized before the first call. The requirements' approach (2-worker shared
   runtime) is correct. Avoid `tokio::spawn` inside JNI functions — use `rt.block_on(async {})`.
3. **AAB/APK `.so` packaging**: Android requires `.so` under `jniLibs/<ABI>/`. AGP extracts
   these automatically at install time; Bazel's `android_binary` does the same.
4. **libsql-sys build complexity**: `libsql-sys` compiles libsql's C sources via `build.rs`,
   requiring `cmake` + a C++ toolchain. `rules_rust`'s `crates_repository` handles this, but
   native build deps must be declared in `MODULE.bazel`.

### Closest Prior Art

- **`tursodatabase/libsql-android`**: JNI bridge for Android — different Kotlin API, no
  SQLDelight, but shows the JNI handle model and build structure.
- **`flutter_rust_bridge`** / `rusqlite` on Android: several Flutter/Rust projects ship Rust
  JNI `.so` files on Android. The JNI calling convention is identical to what libsql needs.
- **`sqlite-android`** (requery/sqlite-android): bundles a custom SQLite build as an Android
  AAR; analogous resource packaging pattern.

---

## 4. SQLDelight 2.x JdbcDriver Transaction Contract

### SqlDriver Interface (SQLDelight 2.x)

`SqlDriver` (in `app.cash.sqldelight:runtime`) requires:

```kotlin
interface SqlDriver : Closeable {
    fun execute(identifier: Int?, sql: String, parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)? = null): QueryResult<Long>
    fun <RowType : Any> executeQuery(identifier: Int?, sql: String,
                mapper: (SqlCursor) -> QueryResult<RowType>, parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)? = null): QueryResult<RowType>
    fun newTransaction(): QueryResult<Transacter.Transaction>
    fun endTransaction(successful: Boolean): QueryResult<Unit>
    fun addListener(vararg queryKeys: String, listener: Query.Listener)
    fun removeListener(vararg queryKeys: String, listener: Query.Listener)
    fun notifyListeners(vararg queryKeys: String)
}
```

Note: `currentTransaction()` is not in the interface but is expected by some generated code.
All methods return `QueryResult<T>` — use `QueryResult.Value(x)` for synchronous drivers
(not `AsyncValue`) since `block_on` in JNI calls provides the necessary synchronization.

### JdbcDriver Transaction Implementation (ThreadLocal Model)

`JdbcDriver` (abstract base in `app.cash.sqldelight:sqlite-driver`) manages transactions via a
**ThreadLocal** `ConnectionManager`. Key behavior:

- `newTransaction()`: acquires a connection, sets `autoCommit = false`, pushes a `Transaction`
  onto the ThreadLocal stack. Nested calls push a `SAVEPOINT sp_N` (not a second `BEGIN`).
- `endTransaction(successful = true)`: calls `connection.commit()` (or `RELEASE SAVEPOINT sp_N`
  for nested), clears ThreadLocal, returns connection to pool, calls `notifyListeners()`.
- `endTransaction(successful = false)`: calls `connection.rollback()` (or
  `ROLLBACK TO SAVEPOINT sp_N` for nested), clears ThreadLocal, **does not** call
  `notifyListeners()`.
- The connection is **pinned** to the calling thread for the transaction duration via ThreadLocal.

### Listener / Invalidation Contract

- `notifyListeners(vararg queryKeys)` is called by **SQLDelight-generated query code** after
  every write `execute()` call (with the affected table names as keys). The driver does not
  need to infer when to notify — the generated code drives it.
- On non-transactional paths: `notifyListeners` fires immediately after `execute()`.
- On transactional paths: SQLDelight generates an `afterCommit { notifyListeners(...) }` block
  that fires after `endTransaction(successful=true)`.
- The driver's listener registry must be thread-safe: use
  `ConcurrentHashMap<String, CopyOnWriteArrayList<Query.Listener>>` (same as `PooledJdbcSqliteDriver`).

### Subtleties for JvmLibsqlDriver

1. **ThreadLocal for connection pinning**: libsql's `Connection` is `Send + Sync` in Rust, but
   the driver must still track which pooled connection is active for the current transaction.
   Use a `ThreadLocal<TransactionState?>` mirroring `JdbcDriver`'s pattern — Kotlin coroutines
   on `Dispatchers.Default` are typically confined to a single thread per continuation.

2. **Nested transactions / savepoints**: SQLDelight generates `SAVEPOINT` for nested
   `transaction { }` calls. `MigrationRunner.applyAll` uses nested transactions — the JNI
   bridge must support `SAVEPOINT sp_N`, `RELEASE SAVEPOINT sp_N`, `ROLLBACK TO SAVEPOINT sp_N`
   as plain SQL via `execute()`. Do **not** issue `BEGIN CONCURRENT` for nested calls.

3. **`BEGIN CONCURRENT` only at top level**: Issue `BEGIN CONCURRENT` only for
   `newTransaction()` calls when no transaction is currently active on the connection. Nested
   calls use `SAVEPOINT` only. libsql supports SAVEPOINT within a CONCURRENT transaction.

4. **`identifier` parameter**: The `identifier` in `execute`/`executeQuery` is a prepared
   statement cache key. A custom driver can use it for statement caching or ignore it; it must
   accept any value (including `null`) without crashing.

5. **`SQLITE_BUSY_SNAPSHOT` surfacing**: The JNI layer must propagate this as a distinct
   exception type (e.g., `LibsqlBusySnapshotException`) so upper-layer retry logic can
   distinguish it from schema errors or ordinary busy timeouts.

---

## 5. Existing SteleKit Benchmark Infrastructure

### Write-Performance Benchmarks

SteleKit has a comprehensive benchmark suite in `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/`:

#### `BlockInsertBenchmarkTest` (primary write latency benchmark)

The **key existing benchmark** for the libsql comparison. It measures wall-clock latency from
`BlockStateManager.addBlockToPage()` call start to `Job.join()` return — i.e., DB write committed.

Two test cases:
- **TC-09** (`blockInsertLatency_syntheticGraph_shimmedSafFileSystem`): P99 ≤ 200ms with a
  `LatencyShimFileSystem` injecting 50ms write / 10ms exists / 30ms read delays (Android SAF simulation).
- **TC-10** (`blockInsertLatency_noShim_jvmBaseline`): P99 ≤ 50ms with a `FakeFileSystem`
  (pure DB write cost, no I/O noise).

Both use 5 warm-up inserts + 100 measured inserts, reporting P50/P95/P99 to JSON in
`build/reports/benchmark-insert[.json/-noshim.json]`.

**Extension point for libsql**: Add a third variant `blockInsertLatency_libsql_noShim` that
creates a `JvmLibsqlDriver` instead of `PooledJdbcSqliteDriver` and measures the same metric.
Add a concurrent variant that launches ≥ 4 coroutines inserting simultaneously and measures
P99 throughput.

#### `GraphLoadTimingTest`

End-to-end load timing + write-latency-under-load benchmarks. Supports:
- System property `STELEKIT_BENCH_CONFIG` (TINY/SMALL/MEDIUM/LARGE/MESH)
- JFR profiling via `./gradlew :kmp:jvmTestProfile`
- Real-graph testing via `-DSTELEKIT_GRAPH_PATH`

Includes a write-actor contention test that fires block inserts while graph loading is in progress.

#### `UserSessionBenchmarkTest`

Interleaved read+write user-session simulation (`navigate_page`, `type_block`, `search`,
`rename_page`, `delete_block`, etc.). Reports P50/P95/P99/max per action category to
`benchmark-session.json`. Requires a real graph path.

#### `UnifiedBenchmarkRunner`

Machine-readable JSON output for baseline comparison. `WriteConcurrency` scenario exists as a
placeholder with sentinel values (-1.0). **This is the registration point for the libsql
concurrent-write benchmark** — implement `runWriteConcurrencyMetrics` with real numbers.

#### `SyntheticGraphGenerator`

Generates synthetic Logseq/Stelekit graphs on disk. Presets: TINY (50 pages), SMALL (200),
MEDIUM (500), LARGE (2000), XLARGE (7978+2930), MESH (worst-case link density). Deterministic
with configurable seed.

#### `SyntheticGraphDbBuilder`

Populates an already-open `SteleDatabase` with synthetic pages/blocks bypassing disk I/O and
markdown parsing. Used for pure-DB latency benchmarks. **Ideal for the libsql concurrent-write
benchmark** — pre-populate the database with `SyntheticGraphDbBuilder.populate()` then fire
concurrent `execute` calls.

### Benchmark Output Infrastructure

- JSON output directory: `System.getProperty("benchmark.output.dir")` → default `build/reports/`
- Pattern: `writeBenchmarkJson(name, mapOf(...))` — manual JSON serialization (no Kotlinx.serialization).
- CI integration: `./gradlew :kmp:jvmTestProfile` with async-profiler for flamegraphs.
- Benchmark results referenced in commit messages (`chore(bench): benchmark summary <sha>`).

### Proposed New Benchmark: `LibsqlConcurrentWriteBenchmarkTest`

Based on the existing patterns, the new benchmark should:

1. Create a `JvmLibsqlDriver`-backed `RepositoryFactoryImpl`.
2. Pre-populate with `SyntheticGraphDbBuilder.populate(db, pageCount = 500, blocksPerPage = 10)`.
3. Launch ≥ 4 coroutines (`Dispatchers.Default`), each calling `actor.saveBlock(block)` in a
   tight loop for N iterations.
4. Measure per-write latency (wall-clock) and aggregate P50/P95/P99/max.
5. Run the same workload against `PooledJdbcSqliteDriver` for comparison.
6. Assert ≥ 10% P99 improvement or ≥ 10% throughput improvement (per requirements success
   criteria SC-3).
7. Output to `build/reports/benchmark-libsql-concurrent.json`.

The `LatencyShimFileSystem` is not needed for this benchmark (pure DB write path).

---

## Summary

- **sqlite-jdbc extraction pattern**: `SQLiteJDBCLoader` extracts from classpath resource
  `/org/sqlite/native/<OS>/<arch>/lib*.so` to Java temp dir, calls `System.load(absolutePath)`.
  SteleKit's `JvmLibsqlDriver` should follow the identical pattern with its own resource path.

- **BEGIN CONCURRENT conflict semantics**: page-level (not row-level) B-tree conflict detection;
  `SQLITE_BUSY_SNAPSHOT` (error 517) returned at COMMIT requires full transaction retry from
  scratch with exponential backoff. For SteleKit's isolated-page data model, actual conflict
  rate under concurrent editing should be near zero.

- **Benchmark integration**: The `BlockInsertBenchmarkTest` (TC-09/TC-10) and
  `UnifiedBenchmarkRunner` are the natural extension points. Add a
  `LibsqlConcurrentWriteBenchmarkTest` using `SyntheticGraphDbBuilder` for prepopulation and
  measure concurrent P99 write latency vs. `PooledJdbcSqliteDriver` baseline.
