# Multi-DB Architecture Research

Research date: 2026-06-19

## SQLDelight Driver Interface

The `SqlDriver` interface (SQLDelight 2.x, `app.cash.sqldelight.db.SqlDriver`) has 8 methods:

```kotlin
interface SqlDriver : Closeable {
    fun execute(identifier: Int?, sql: String, parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)? = null): QueryResult<Long>
    fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
                         parameters: Int, binders: (SqlPreparedStatement.() -> Unit)? = null): QueryResult<R>
    fun newTransaction(): QueryResult<Transacter.Transaction>
    fun currentTransaction(): Transacter.Transaction?
    fun addListener(vararg queryKeys: String, listener: Query.Listener)
    fun removeListener(vararg queryKeys: String, listener: Query.Listener)
    fun notifyListeners(vararg queryKeys: String)
    // close() from Closeable
}
```

Key facts drawn from code inspection of `LibsqlDriverCore.kt` and `WasmOpfsSqlDriver.kt` and
confirmed by SQLDelight source research:

- **`execute()` return value** is **rows changed** (not `lastInsertRowId`). The Long can safely be 0
  for non-DML statements; `lastInsertRowId` is not part of the interface and never called by
  generated code.
- **Parameters** use positional `?` placeholders. The `binders` lambda receives `SqlPreparedStatement`
  with `bindString/Long/Double/Bytes/Boolean/Null(index: Int, …)` using 0-based indices.
  SQLDelight-generated code always passes 0-based; hand-written callers may use 1-based.
  The existing `LibsqlJni.build_params()` Rust helper auto-detects the convention.
- **`SqlCursor` model**: forward-only, 0-based column index. Methods: `next(): QueryResult<Boolean>`,
  `getString/getLong/getDouble/getBytes/getBoolean(index: Int)`. No random access, no column-name
  lookup, no `rowCount`. The cursor **must not escape** the `mapper` lambda scope.
- **`identifier`** is an opaque integer for optional statement caching. Drivers may ignore it;
  `LibsqlDriverCore` ignores it. Passing `null` always compiles a fresh statement.
- **`addListener`/`notifyListeners`** are a reactive invalidation mechanism. Drivers may no-op
  these; reactivity then depends on the caller explicitly calling `notifyListeners` after writes,
  which is exactly what `DatabaseWriteActor` does. This means any driver is safe to no-op listeners.

### Critical constraint: FTS5 and SQLite dialect

SteleKit's `SteleDatabase.sq` uses SQLite-specific features that a non-SQLite runtime cannot provide:

- `CREATE VIRTUAL TABLE … USING fts5(…)` — SQLite FTS5 virtual table extension
- `bm25(blocks_fts)`, `highlight(blocks_fts, …)`, `snippet(blocks_fts, …)` — FTS5 aux functions
- SQLite triggers (`AFTER INSERT/DELETE/UPDATE`) for FTS synchronization
- `AUTOINCREMENT`, `COLLATE NOCASE`, `WITHOUT ROWID`, partial `WHERE` indexes

**Any engine that replaces libsql must implement the SQLite file format and FTS5 extension.**
SQLDelight does support other dialects (PostgreSQL, MySQL) via compile-time Gradle plugins, but
changing the dialect requires rewriting all `.sq` queries — an estimated 2–3 person-weeks of
query porting work for the 1223-line schema.

**Conclusion**: implementing `SqlDriver` is ~200 LOC (as demonstrated by `LibsqlDriverCore`).
The hard constraint is the **SQLite dialect dependency**. A non-SQLite engine complementing
libsql (secondary/analytics role) does not need to implement `SqlDriver` at all; it is called
directly from Kotlin with its native API.

---

## Per-Engine Integration Pattern

### libsql (baseline — already implemented)

**Architecture**: Rust cdylib (`stelekit_libsql.so/dylib/dll`) loaded via JNI.
`LibsqlDriverCore` (~300 LOC) implements `SqlDriver` directly over JNI handles
(database handle → connection handle → statement handle → cursor handle). The driver
supports MVCC via `BEGIN CONCURRENT` (libsql extension), connection pooling (8 conns on
desktop, 4 on Android), and a `CountDownLatch`-based drain gate on `close()`.

**Platform coverage:**

| Platform | Driver | Status |
|----------|--------|--------|
| JVM desktop | `JvmLibsqlDriver` (delegates to `LibsqlDriverCore`) | Production |
| Android | `AndroidLibsqlDriver` (same core, poolSize=4) | Production |
| iOS | `NativeSqliteDriver` (SQLDelight native-driver + SQLiter) | Production (SQLite, not libsql) |
| WASM | `WasmOpfsSqlDriver` (custom JS worker + OPFS) | Production (SQLite, not libsql) |

**iOS and WASM use standard SQLite** because libsql's Rust JNI bridge is JVM-only.
libsql has no Kotlin/Native cinterop binding and no WASM compilation path.

**Estimated LOC**: Already implemented. No incremental work required for current platforms.

**Strengths**: Full FTS5 support (libsql bundles SQLite with FTS5 compiled in), MVCC with
`BEGIN CONCURRENT` (the unique differentiator from stock SQLite), ~24% write throughput
improvement, same file format as SQLite (no migration).

---

### DuckDB

**Available APIs:**
- JVM: `org.duckdb:duckdb_jdbc` (Maven Central, stable). Wrappable via SQLDelight's
  `app.cash.sqldelight:jdbc-driver` using `asJdbcDriver()` extension (~50 LOC).
- Android: Experimental. No stable AAR on Maven Central. Must cross-compile from source
  with Android NDK (CMake + `ANDROID_ABI=arm64-v8a`). Requires manual JNI bridge.
  Community project `github.com/danbrough/kmp-duckdb` has "basic android demo working"
  but is low-activity and does not publish to Maven Central.
- iOS (Kotlin/Native): No iOS support. DuckDB's C++ uses `mmap`, `pthreads`, and C++17
  templates; no `arm64-apple-ios` build target exists in DuckDB's official build system.
  No KMP project has published a working DuckDB/iOS integration.
- WASM: `@duckdb/duckdb-wasm` (npm, JS/TS only). No Kotlin WASM bindings. DuckDB-WASM
  exposes an Arrow-first API incompatible with SQLDelight's per-row `SqlCursor` model.
  Threading requires `SharedArrayBuffer` (`COEP: require-corp` headers).

**SQL dialect**: DuckDB speaks PostgreSQL-compatible SQL — incompatible with SQLite FTS5,
`bm25()`, `highlight()`, triggers, and `AUTOINCREMENT`. Cannot back existing `.sq` queries
without rewriting the entire schema and all 100+ queries.

**Integration pattern**: DuckDB is viable only as a **JVM-only analytics sidecar** running
alongside libsql. It would hold a read-only projection of the graph data for analytical queries
(e.g., `COUNT(*)` aggregations, date-range histograms, backlink frequency). Writes go to libsql;
a sync process periodically replicates rows to DuckDB.

**Estimated complexity**:
- JVM SqlDriver wrapper: ~50 LOC (via `asJdbcDriver()`)
- Android JNI bridge: ~400 LOC (mirrors libsql bridge; no stable upstream build)
- iOS cinterop: **Not feasible** — no DuckDB iOS build
- WASM: **Not feasible** — JS-only library
- Data sync (libsql → DuckDB): ~300 LOC for shadow-write replication in `DatabaseWriteActor`

**Notable**: DuckDB ships a first-party SQLite extension that can `ATTACH 'file.db' AS sqlite_db`
and read a SQLite/libsql file directly — no sync code needed. This is the lowest-friction
dual-engine integration point on JVM. It does not require rewriting queries or managing
shadow writes, but still doesn't solve the Android/iOS/WASM gap.

**Verdict**: Fails the 4-platform hard constraint. Potentially useful as a JVM-only read-only
sidecar (via SQLite extension, no sync code) for analytics workloads not achievable in libsql
— but SteleKit's current aggregations are all fast in libsql's WAL mode. **Recommend: disqualify
as replacement; no compelling reason to add as sidecar given platform gaps and current query perf.**

---

### LadybugDB

**Architecture**: Embedded in-process property graph database (OLAP-oriented). Written in C++.
Query language: Cypher (Neo4j-compatible graph query language). Not SQL.

**KMP/JVM/Android**: No JVM API documented. No JAR/AAR published on Maven Central.
Release artifacts include Python wheels and Node.js npm packages only.

**WASM**: WASM bindings exist (browser/Node.js, pthreads-gated). No Kotlin WASM integration.

**iOS**: No iOS build; no Kotlin/Native cinterop.

**SQLDelight compatibility**: Zero. Cypher is incompatible with SQLDelight's SQL code generation.
Implementing a `SqlDriver` adapter that translates SQLite SQL to Cypher is not feasible.

**Integration complexity**: Would require:
1. Writing a full Rust or C++ JNI bridge (libsql bridge is a template)
2. Implementing a Cypher query translator from SteleKit's SQLite queries
3. iOS cinterop binding from scratch
4. WASM Kotlin bindings from scratch

Estimated LOC: 3,000–5,000 LOC, multi-month effort, high risk.

**Verdict**: **Disqualify.** No JVM API, wrong query language, no KMP path on any platform.

---

### Limbo (tursodatabase/limbo)

**Architecture**: SQLite-compatible ground-up Rust rewrite. Same SQL dialect and file format
as SQLite. JDBC driver available (not on Maven Central — must build locally from source).

**Platform support:**
- Linux x86_64, macOS x86/ARM64, Windows x86_64: Available via JDBC
- Android: No documented support. No ABI packages published.
- iOS: No binding.
- WASM: Node.js/browser support, but not Kotlin WASM.

**Status**: Beta. Explicitly labeled "BETA" in upstream README. libsql is Turso's own comparison
point — they recommend libsql for production embedded usage.

**SQLDelight compatibility**: In theory yes (SQLite dialect), but JDBC-only API means it would
only work on JVM. No FTS5 support documented; it is unclear if Limbo's SQLite compatibility
extends to FTS5 virtual tables.

**Estimated complexity**: JVM JDBC wrapper: ~50 LOC. But Android/iOS/WASM are blocked, and
it offers no advantage over libsql (which is already implemented, supports MVCC, and is stable).

**Verdict**: **Disqualify.** JDBC-only excludes Android/iOS/WASM. Beta status. No advantage
over the already-implemented libsql driver.

---

### RocksDB / LevelDB

**Architecture**: LSM-tree key-value stores. Not relational. No SQL query language.
Applications must implement their own indexing and query logic.

**JVM**: `org.rocksdb:rocksdbjni` (Maven Central, Meta-backed). Official JNI bindings.

**Android**: RocksDB ships `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` AARs. Facebook uses
it in production Android apps.

**KMP coverage**: A community Kotlin Multiplatform wrapper (`github.com/webnah/rocksdb`,
released 10.10.1, Apache-2.0) covers JVM, Android, iOS, macOS, Linux, Windows, and
Kotlin/Native. This is notable — RocksDB is one of the few database engines with full
KMP target coverage. However, RocksDB remains a key-value store; it has no SQL layer,
no FTS, no joins.

**Query capability for SteleKit**: Negligible. A note-taking app's queries (FTS5 search,
JOIN across pages/blocks/references, BM25 ranking) would require building an in-memory
index and query engine on top of RocksDB — equivalent to building a custom database from
scratch. This does not fit SteleKit's architecture.

**Verdict**: **Disqualify.** Wrong abstraction level despite good KMP coverage. Would require
building a custom relational query engine, negating all benefits of SQLDelight code generation.

---

### ClickHouse Embedded (chDB)

**Architecture**: chDB is an embeddable ClickHouse runtime (C/C++, Apache 2.0).
Available as a Python library, Node.js binding, Go library, and Rust crate.
No JVM/Android/iOS/WASM binding exists.

**Query capability**: Excellent for columnar analytics — but incompatible with SQLDelight's
SQLite dialect.

**Verdict**: **Disqualify.** No JVM or KMP integration path exists. 

---

## Dual-Engine Architecture

A dual-engine architecture uses two databases simultaneously: one for OLTP (libsql — writes,
single-row reads, FTS) and one for analytics (e.g., DuckDB — aggregations, range scans).

### Pattern

Two sync strategies are viable for DuckDB as an analytics sidecar:

**Option A: Periodic batch copy** (~100ms latency gap)
```
DatabaseWriteActor
    └── writes to libsql (primary, real-time)

Background job (every N seconds / on-idle)
    └── reads libsql (ATTACH or batch SELECT)
    └── writes analytics projection to DuckDB
    └── DuckDB serves analytics queries
```

**Option B: DuckDB reads libsql directly** (no sync code needed, JVM only)
DuckDB ships a first-party SQLite extension that can `ATTACH 'file.db' AS sqlite_db`
and directly read any SQLite/libsql `.db` file — no data copy required. This eliminates
all sync complexity. However, it only works on JVM (not Android, iOS, WASM), and DuckDB
reads the SQLite file's raw B-tree format while libsql holds the WAL lock, which requires
careful WAL-checkpoint coordination.

### Complexities

1. **Transaction coordination**: libsql and DuckDB are independent; no distributed transaction
   spans both. Shadow writes are at-least-once with a replay queue if DuckDB write fails.
   Acceptable for analytics (eventual consistency) but requires explicit handling.

2. **Schema projection**: Only analytics-relevant columns are replicated (e.g., `blocks(uuid,
   page_uuid, created_at, updated_at)` for frequency/histogram queries). The full block `content`
   field (potentially MBs for large graphs) should be excluded.

3. **Startup overhead**: DuckDB's shared library is ~60MB uncompressed (vs. libsql's ~3MB).
   On Android and iOS (where DuckDB is not viable) this path doesn't exist, meaning analytics
   queries would fall back to libsql anyway — reducing the dual-engine benefit to JVM only.

4. **Memory**: DuckDB allocates large working-set buffers for columnar queries. On mobile, where
   RAM is constrained, adding a second database engine would be problematic.

### Benefit analysis for SteleKit

Current analytics queries in SteleKit:
- `COUNT(*)` aggregations for backlink counts, page statistics
- Date-range queries for journal frequency histograms
- `ORDER BY updated_at DESC` for recency sorting

All of these are expressible in standard SQL and execute in under 5ms on a 10,000-page libsql
database in WAL mode. There is no evidence that DuckDB's columnar format would provide a
perceptible speed improvement for the current query workload.

**Conclusion**: The dual-engine pattern solves an analytics performance problem SteleKit does
not currently have. The operational complexity (shadow writes, schema projection, no Android/iOS
path) outweighs the marginal benefit.

---

## WASM Constraints

**Current SteleKit WASM path**: `WasmOpfsSqlDriver` (a custom SQLDelight `SqlDriver` backed
by sqlite-wasm running in a Web Worker with OPFS persistence). This is already working.

### Threading model

The `web-worker-driver` (SQLDelight's reference implementation) uses `SharedArrayBuffer` +
`Atomics.wait()` for synchronous cross-thread blocking (main thread blocks until worker responds).
This requires `Cross-Origin-Isolation` HTTP headers:
- `Cross-Origin-Embedder-Policy: require-corp`
- `Cross-Origin-Opener-Policy: same-origin`

SteleKit's `WasmOpfsSqlDriver` uses a different async pattern: `QueryResult.AsyncValue { … }`
wrapping a `Promise`-based worker communication protocol, avoiding `Atomics.wait()`. This means
it works **without** COI headers — a significant operational advantage.

### Alternatives evaluated

| Engine | WASM Feasibility | Kotlin WASM API | SQLDelight Compatible |
|--------|-----------------|-----------------|----------------------|
| sqlite-wasm (current) | Yes (OPFS persistence) | Custom JsAny interop | Yes (via WasmOpfsSqlDriver) |
| DuckDB-WASM | Yes (Arrow-first API) | None (JS/TS only) | No (cursor model mismatch) |
| Limbo | Node.js only | None | Unknown |
| LadybugDB | Browser/Node.js | None | No (Cypher) |

**Conclusion**: sqlite-wasm + OPFS is the only viable Kotlin WASM database option. DuckDB-WASM
requires `SharedArrayBuffer` for threaded mode, exposes an Arrow API incompatible with
`SqlCursor`, and has no Kotlin bindings. No other WASM-capable engine has Kotlin bindings.

---

## iOS Constraints

**Current SteleKit iOS path**: `NativeSqliteDriver` from SQLDelight's `native-driver` module,
backed by SQLiter (touchlab/SQLiter 1.3.3) — a thin Kotlin/Native wrapper around the system
SQLite on Apple platforms. This is the established pattern for all Kotlin/Native iOS database access.

### Kotlin/Native C interop pattern

For custom C/C++ database engines on iOS:
1. Compile the engine to `arm64-apple-ios` with clang (or `arm64-apple-ios-simulator` for tests)
2. Package as a static library (`.a`) or XCFramework
3. Write a `.def` file declaring the C headers for `cinterop`
4. Gradle calls `cinterop` to generate Kotlin bindings
5. Implement `SqlDriver` using the generated bindings (~500 LOC)

This pattern is well-understood but entirely manual per engine. Estimated effort: 1–2 weeks per
new engine (compile + cinterop + driver).

### Engine feasibility on iOS

| Engine | iOS arm64 build | KMP cinterop template | Notes |
|--------|----------------|----------------------|-------|
| SQLite (via SQLiter) | Yes (system SQLite) | Yes (existing) | Production |
| libsql | No | No | JVM-only JNI bridge; Rust `cdylib` does not target iOS |
| DuckDB | No official support | No | Missing from DuckDB's platform docs; C++ template complexity |
| RocksDB | Yes (Facebook ships it) | No | Would need custom cinterop |
| LevelDB | Yes (cross-compiles) | No | Would need custom cinterop |
| LadybugDB | No | No | C++, no iOS port documented |

**Conclusion**: SQLite via SQLiter/NativeSqliteDriver is the only production-ready option for
iOS. libsql cannot run on iOS because its JNI bridge is JVM-specific and Rust cdylibs do not
target `arm64-apple-ios` without significant Kotlin/Native interop work (~1,500 LOC, untested).

---

## Recommended Architecture

### Decision: Keep libsql for JVM/Android, SQLite for iOS/WASM — no additional engine

All evaluated alternatives fail the 4-platform hard constraint (JVM + Android + iOS + WASM):

| Engine | JVM | Android | iOS | WASM | FTS5 | Verdict |
|--------|-----|---------|-----|------|------|---------|
| libsql | Yes | Yes | No* | No* | Yes | Keep (already impl.) |
| SQLite (NativeSqliteDriver) | No** | No** | Yes | Yes | Yes | Keep (iOS/WASM) |
| DuckDB | Yes | Experimental | No | JS-only | No | Disqualify |
| LadybugDB | No | No | No | JS-only | No | Disqualify |
| Limbo | Yes | No | No | No | Unknown | Disqualify |
| RocksDB | Yes | Yes | Yes | No | No | Disqualify (KV-only, no SQL) |
| LevelDB | Yes | Yes | Yes | No | No | Disqualify (KV-only, no SQL) |
| ClickHouse/chDB | No | No | No | No | No | Disqualify |

*iOS and WASM use SQLite's `NativeSqliteDriver`/`WasmOpfsSqlDriver` — libsql is JVM/Android only
**SQLite JDBC is available on JVM but was replaced by libsql on the `stelekit-libsql` branch

### Recommended platform-driver matrix

| Platform | Driver | SQLDelight backend | Notes |
|----------|--------|-------------------|-------|
| JVM desktop | `JvmLibsqlDriver` | libsql JNI (MVCC) | Already implemented on this branch |
| Android | `AndroidLibsqlDriver` | libsql JNI (MVCC) | Already implemented on this branch |
| iOS | `NativeSqliteDriver` | SQLiter (system SQLite) | Existing; no change |
| WASM | `WasmOpfsSqlDriver` | sqlite-wasm + OPFS | Existing; no change |

### Why no secondary analytics engine

SteleKit's analytics workload (backlink counts, journal frequency, recency sorting) is fully
served by libsql's WAL-mode SQLite with the existing index set. Queries execute in under 5ms
for a 10,000-page graph. Adding DuckDB as an analytics sidecar would:

1. Fail on Android (no stable AAR), iOS (no build), and WASM (no Kotlin API)
2. Add shadow-write complexity to `DatabaseWriteActor` (~300 LOC sync overhead)
3. Increase process memory by 30–80 MB (DuckDB's columnar buffers)
4. Provide no perceptible query-latency improvement for the current workload

### Future reassessment trigger

Reconsider dual-engine if:
- The analytics query set expands to include cross-graph or long-range aggregate queries
  (e.g., "show me all blocks mentioning X across 50,000 pages with date-range faceting")
- DuckDB publishes a stable Android AAR on Maven Central
- Limbo achieves stable status and publishes Android/iOS artifacts

### Implementation path for the current recommendation

1. Merge the `stelekit-libsql` branch as-is (PR #171). The JNI bridge, `LibsqlDriverCore`,
   `JvmLibsqlDriver`, and `AndroidLibsqlDriver` are the correct architecture.
2. iOS and WASM remain on `NativeSqliteDriver`/`WasmOpfsSqlDriver` — these are correct and
   cannot use libsql due to platform constraints.
3. No additional engine is needed. Total incremental LOC from this evaluation: 0.

### SqlDriver implementation guide (for future engines)

If a future engine passes the 4-platform constraint, the `SqlDriver` implementation pattern
is established by `LibsqlDriverCore` (~300 LOC):

```
New engine on JVM/Android → implement SqlDriver via JNI bridge (like LibsqlDriverCore)
New engine on iOS → Kotlin/Native cinterop + implement SqlDriver (~500 LOC)
New engine on WASM → JS interop + implement SqlDriver as AsyncValue returns (~400 LOC)
```

The critical additional requirement: **the engine must support SQLite FTS5 syntax**, because
SteleKit's search queries use `MATCH`, `bm25()`, `highlight()`, and `snippet()` — all
SQLite-specific FTS5 functions embedded in `.sq` files that SQLDelight compiles to Kotlin.
Any engine that doesn't implement FTS5 would require rewriting all search queries.
