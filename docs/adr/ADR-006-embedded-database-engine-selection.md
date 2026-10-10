# ADR-006: Embedded Database Engine Selection

## Status
Accepted

## Context

SteleKit is a Kotlin Multiplatform (KMP) app targeting four platforms simultaneously: JVM/Desktop,
Android, iOS, and Kotlin/WASM. This is a hard constraint — any embedded database engine must
provide production-ready artifacts for all four targets from a single codebase. There is no
optional platform; each is a shipping target.

The `stelekit-libsql` branch (PR #171) replaces the `xerial/sqlite-jdbc` JDBC driver with a
custom libsql JNI bridge (`LibsqlDriverCore`, `JvmLibsqlDriver`, `AndroidLibsqlDriver`). The
branch also introduces a CI workflow that builds native `.so`/`.dylib` artifacts for each ABI via
cross-compilation.

As part of merging PR #171, a formal evaluation of seven embedded database engines was conducted
to confirm that libsql is the correct choice and to document under what conditions alternatives
should be reconsidered.

### SteleKit's schema constraints

`SteleDatabase.sq` makes extensive use of SQLite FTS5:

- Two FTS5 virtual tables: `blocks_fts`, `pages_fts`
- Six FTS5 triggers (after-insert, after-update, after-delete for each table)
- Six named queries using `MATCH`, `bm25()`, and `highlight()` (`snippet()` is not used)

Any replacement engine must support this dialect verbatim, or a multi-week schema rewrite would
be required before the engine could be evaluated on its own merits.

## Decision

Retain **libsql** as the sole embedded database engine across all four platforms, via the driver
implementation already present in PR #171:

| Platform | Driver | Engine |
|---|---|---|
| JVM Desktop | `JvmLibsqlDriver` → `LibsqlDriverCore` → JNI | libsql embedded (WAL mode) |
| Android | `AndroidLibsqlDriver` → `LibsqlDriverCore` → JNI | libsql embedded (WAL mode) |
| iOS | `NativeSqliteDriver` (SQLDelight built-in) + SQLiter | System SQLite |
| WASM/Web | `WasmOpfsSqlDriver` (sqlite-wasm + OPFS) | sqlite-wasm |

## Rationale

### 1. The four-platform hard constraint disqualifies all evaluated alternatives immediately

No alternative engine evaluated provides production-ready artifacts for JVM, Android, iOS, and
Kotlin/WASM simultaneously. This single criterion is sufficient to reject all six alternatives
before any feature comparison is performed.

### 2. FTS5 dialect compatibility is non-negotiable

Two FTS5 virtual tables, six triggers, and six named queries using `bm25()`, `highlight()`, and
`MATCH` are already in production in `SteleDatabase.sq`. (`snippet()` is not used.) libsql inherits the full SQLite FTS5
implementation. All other candidates either lack FTS entirely (LevelDB/RocksDB), provide only
approximate string matching (ClickHouse), offer FTS as a non-standard extension with a different
query API (LadybugDB), or have FTS listed as experimental/roadmap (Limbo). Migrating this query
surface to a different dialect would require a multi-week rewrite with no user-visible benefit.

### 3. `BEGIN CONCURRENT` MVCC is detected at runtime — and single-writer WAL is sufficient

The driver calls `LibsqlJni.isDatabaseMvccEnabled(dbHandle)` at startup and branches between
`BEGIN CONCURRENT` (MVCC) and `BEGIN IMMEDIATE` (single-writer WAL) accordingly. As of libsql
0.9, MVCC only activates in sqld server mode (remote WAL over `libsql://`/HTTP); in embedded
local-file mode `isDatabaseMvccEnabled` returns false, and the driver falls back to `BEGIN
IMMEDIATE`. This is intentional and not a deficiency: `DatabaseWriteActor` already serializes
all writes to a single coroutine, so single-writer WAL is architecturally sufficient and correct
for SteleKit. If a future libsql release enables local-mode MVCC, the runtime branch will pick
it up automatically with no code change required.

### 4. The ~24% P50 write throughput improvement is real and preserved

The benchmark improvement measured on PR #171 comes from WAL tuning and write batching, not from
MVCC. The improvement is real (not an artifact of the MVCC claim) and is fully preserved in
local-file mode.

## Alternatives Rejected

### LadybugDB

A columnar property graph database (community fork of KuzuDB) with a Cypher query language. The
Java binding (`com.kuzudb:kuzu-java`) targets JVM desktop only — no documented Android support,
no iOS Kotlin/Native path, no Kotlin/WASM. Even if platform support existed, replacing
SQLDelight's SQL code generation with Cypher queries and migrating the `SteleDatabase.sq` schema
to a node/edge graph model would require a multi-month rewrite, with no OLTP write performance
benefit for SteleKit's debounced single-block edit pattern. Disqualified on platform support and
migration cost.

### Limbo (Turso)

An in-process SQLite rewrite in Rust by Turso, designed for async I/O and WASM-native embedding.
Architecturally the most compatible future candidate: it reads the SQLite file format, speaks the
SQLite SQL dialect, and has a JDBC binding in its repository. However, as of mid-2026 it is
explicitly in beta, FTS5 is listed as experimental in `COMPAT.md`, there are no published
Android or iOS artifacts, and the Kotlin/WASM driver does not exist. Beta-status storage is
unacceptable for a note-taking app where user data is irreplaceable. Disqualified now;
**reassess when Limbo publishes a stable 1.0 with FTS5 confirmed and Android/iOS/WASM artifacts
on a public artifact registry.**

### DuckDB

A columnar OLAP database. No production iOS binding exists (no arm64-apple-ios build). Android
support is explicitly labeled "experimental" by the DuckDB team with no stable AAR on Maven
Central; builds require the `main` branch and manual NDK setup. OLTP write latency is 4–8×
higher than SQLite for single-row INSERT/UPDATE — exactly the workload pattern for SteleKit's
debounced 500 ms block edits. Memory peaks at ~2.3 GB for moderate data sizes vs SQLite's
480 MB, triggering OOM kills on Android. Disqualified on platform support, write performance,
and memory profile. May be reconsidered as a **JVM-only analytics sidecar** if cross-graph
aggregation queries over 50,000+ pages become a bottleneck and a stable Android AAR appears
(tracked as Epic 3, deferred indefinitely).

### ClickHouse / chDB

ClickHouse is a server-process OLAP database with no embedded mobile variant. `chDB` (the
in-process embedding) has bindings only for Python, Go, Rust, and C/C++ — no JVM, no Android,
no iOS, no Kotlin/WASM. The standard `UPDATE` operation uses asynchronous columnar mutations
(not row-level ACID), making it fundamentally incompatible with interactive block editing.
Disqualified before any feature comparison.

### LevelDB / RocksDB

Key-value stores with no SQL layer. Adopting either would require implementing full-text search,
relational joins, recursive block hierarchy traversal, and BM25 ranking in application code —
equivalent to writing a new database engine on top of a storage engine. RocksDB has a JNI
binding for JVM/Android, but no iOS Kotlin/Native path and no WASM port. RocksDB also has
confirmed JNI native memory leaks (GitHub issues #9962 and #3216) that do not appear in Java
heap profilers, making diagnosis difficult in a KMP application. Disqualified on missing SQL
layer, platform gaps, and JNI memory leak history.

## Consequences

- PR #171 (`stelekit-libsql` branch) proceeds to merge as-is.
- The existing `MigrationRunner`, `DatabaseWriteActor`, `SteleDatabase.sq` schema, and all
  SQLDelight-generated queries require no changes.
- The CI workflow introduced in PR #171 (`build-native-libs.yml`) must be run on every libsql
  minor release to rebuild and repackage JNI artifacts for all target ABIs. This is the primary
  ongoing maintenance cost.
- DuckDB is documented as a potential JVM-only analytics sidecar for future cross-graph
  aggregation workloads (Epic 3). It is not adopted now; no DuckDB code is introduced.
- Limbo (Turso) should be reassessed when: (a) it publishes a stable 1.0 release with explicit
  FTS5 stability confirmation in `COMPAT.md`, and (b) Android (`arm64-v8a`, `x86_64`) and iOS
  Kotlin/Native artifacts are published to a public artifact registry. Migration effort at that
  point is estimated at approximately one week: replace `LibsqlJni.kt` with a Turso JDBC wrapper
  (~50 LOC via `asJdbcDriver()`); the schema, migrations, and SQLDelight queries are unchanged.
