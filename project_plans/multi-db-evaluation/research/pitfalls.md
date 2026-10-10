# Multi-DB Evaluation — Pitfalls Research

## Risk Summary Table

| Engine           | Maturity Risk | Platform Risk | Perf Risk | Migration Complexity | Verdict        |
|------------------|--------------|---------------|-----------|----------------------|----------------|
| libsql (baseline)| Low          | Low           | Low       | None (already done)  | Viable         |
| LadybugDB        | Medium       | High          | Medium    | High                 | Disqualified   |
| Limbo            | High         | Medium        | Low       | Low                  | Caution        |
| DuckDB           | Low          | High          | High      | High                 | Disqualified   |
| ClickHouse/chDB  | Medium       | High          | High      | Very High            | Disqualified   |
| LevelDB/RocksDB  | Low (RocksDB)| Medium        | Medium    | Very High            | Disqualified   |

---

## libsql (Baseline)

**Maturity risk: Low | Platform risk: Low | Perf risk: Low | Migration complexity: None**

### BEGIN CONCURRENT in Local Mode

The most critical limitation for SteleKit: `BEGIN CONCURRENT` (MVCC) in libsql 0.9.x is
designed for the sqld server mode, where the write-ahead log is managed remotely. In
embedded local-file mode, libsql falls back to SQLite's standard WAL, which provides
single-writer semantics only. MVCC optimistic concurrency only activates when using the
network protocol (`libsql://` or HTTP connection strings), not `file://`. This means the
~24% write throughput gain measured on the current branch comes from WAL tuning and
batching, not true concurrent writes.

Practical impact for SteleKit: the `DatabaseWriteActor` already serializes all writes to
a single coroutine, so MVCC does not provide additional concurrency. The current
architecture is already correct for local-file libsql.

### JNI Bridge Maintenance

The libsql JNI layer consists of:
- A Rust crate (`libsql-sys`) that wraps the C API
- A generated C header via `cbindgen`
- Platform `.so` / `.dylib` / `.dll` artifacts built via CI

Maintenance burden: **medium-term concern for a solo developer.** Every libsql minor
release requires re-building and re-packaging native artifacts for all target ABIs
(arm64-v8a, x86\_64 for Android; x86\_64 and aarch64 for desktop). The current branch
already set up CI for this (see `.github/workflows/build-native-libs.yml`), which
reduces the recurring cost. The primary risk is a breaking change in the Rust C ABI
between versions; `cbindgen` outputs should be pinned to a specific libsql version. No
data corruption issues have been reported in the libsql issue tracker related to the JNI
layer.

### Schema Migration

libsql uses raw SQLite DDL. SteleKit already has `MigrationRunner` for this. No
libsql-specific migration mechanism exists; all migrations are hand-written SQL executed
at startup. This is a mature, low-risk pattern.

### Known Data Corruption Issues

No confirmed data corruption bugs in the libsql JNI layer as of mid-2026. The main
open issue of relevance is connection pool exhaustion under concurrent load (GitHub
issue #1195: `Internal connection timeout with low concurrency`), which manifests in
server mode under ~10 concurrent connections. In local-embedded mode this issue does
not apply — `PooledJdbcSqliteDriver` manages the connection pool at the JVM layer.

### Verdict: Viable

libsql local-embedded is production-stable, SQLite-compatible, and already integrated.
The "MVCC for local mode" story is marketing — it only applies to sqld. This is not a
problem in SteleKit's architecture because writes are already serialized.

---

## LadybugDB

**Maturity risk: Medium | Platform risk: High | Perf risk: Medium | Migration complexity: High**

### Maturity and Maintenance

LadybugDB was forked from Kuzu in 2025 when Kuzu went EOL. As of June 2026, the project
shows active development (v0.17.1 released June 2, 2026, ~6,072 commits, 58 open issues,
1.3k stars, 101 forks). However, it is a rebranded fork of a project that reached
end-of-life, and the contributor base is not yet established independently. The language
bindings supported are Python, Node.js, Rust, Go, Swift, Java, C/C++, and WASM — the
Java binding exists but targets JVM desktop only.

### Missing Android / iOS Support

No Android or iOS bindings are documented. The Java SDK is distributed as a standard JAR
with a native `.so` for Linux/macOS/Windows. To run on Android, the C++ engine would
need to be cross-compiled for ARM64 (arm64-v8a) and x86\_64 ABI targets using the
Android NDK — no pre-built artifacts exist. iOS support via Kotlin/Native cinterop would
require additional C header wrapping. This is a **disqualifying platform gap** for
SteleKit's mandatory 4-platform requirement.

### ACID Guarantees

LadybugDB (inherited from Kuzu) is a columnar property graph database using an
MVCC-based transaction system. ACID transactions are supported with snapshot isolation.
However, graph databases do not guarantee row-level atomicity in the same way relational
databases do — schema migrations that restructure graph topology require
export/import cycles (no online ALTER for relationship types).

### Schema Evolution

Schema migration is done via `EXPORT DATABASE` → modify schema → `IMPORT DATABASE` on
an empty database. There is no incremental migration mechanism (no equivalent of
`MigrationRunner`). For an app schema that evolves across releases, this approach
requires writing data-export scripts for every schema change — significantly higher
operational complexity than SQL `ALTER TABLE`.

### Data Model Mismatch

SteleKit's data model is relational (pages, blocks, references, properties as SQL
tables). Migrating to a graph model would require restructuring all queries from SQL
to Cypher (the query language LadybugDB inherits from Kuzu/Neo4j lineage). This is
a significant rewrite with no equivalent of SQLDelight code generation.

### Verdict: Disqualified

Disqualified on platform support alone (no Android, no iOS). Even if platforms were
supported, the schema migration complexity and data model divergence make it unsuitable
for a solo developer maintaining a 4-platform app.

---

## Limbo

**Maturity risk: High | Platform risk: Medium | Perf risk: Low | Migration complexity: Low**

### Production Readiness

Limbo (now internally called "Turso" in the repository) is explicitly in beta as of
mid-2026. The project README states: *"This software is in BETA. It may still contain
bugs and unexpected behavior."* The reliability target is "SQLite-level reliability" but
the developers themselves state that bar has not yet been met. It powers some production
applications (Turso Cloud, Kin AI, Spice.ai) but those are in server mode, not
embedded-local mode.

For a note-taking app where user data is irreplaceable, beta-status storage is
unacceptable without extensive regression testing infrastructure that exceeds what a
solo developer can maintain.

### SQLite Compatibility Gaps

Limbo maintains a `COMPAT.md` file tracking deviations from SQLite. Key known gaps as
of the research date:

- **Encryption at rest** is listed as experimental (not stable)
- **Multi-process WAL coordination** is still on the roadmap — multiple processes
  sharing one database file is not reliable
- **Full-text search** (FTS5) is experimental
- **Incremental computation / DBSP** — not yet available
- **Vector indexing** — on the roadmap, not shipped

For SteleKit, the FTS5 gap is particularly relevant because block content search is a
primary use case.

### WASM Maturity

Limbo has WASM bindings for JavaScript. However, these target the browser JS environment,
not Kotlin/WASM (Kotlin's WASM target uses the Component Model / WASI, not the JS
binding). A Kotlin WASM driver would need to be written from scratch using WASI or
C interop, which is not currently documented or supported.

### Migration Complexity

If Limbo were to mature, migration from libsql would be **low complexity** because
Limbo reads the SQLite file format and understands the same SQL dialect. The existing
`MigrationRunner` and `SteleDatabase.sq` files would be compatible. This is the main
advantage over other candidates.

### Verdict: Caution (not now, re-evaluate in 12 months)

Limbo is the most architecturally compatible candidate, but beta status and FTS5 gaps
block adoption today. Watch for a stable 1.0 release with FTS5 confirmation before
reconsidering.

---

## DuckDB

**Maturity risk: Low | Platform risk: High | Perf risk: High | Migration complexity: High**

### Android: Experimental, Not Production-Ready

DuckDB's Android support is explicitly documented as **experimental** — the official
docs state: *"DuckDB has experimental support for Android. Please use the latest main
branch of DuckDB instead of the stable versions."* This means:
- No stable releases support Android; you must build from the main branch
- ABI-specific builds (arm64-v8a, x86\_64) require NDK + Ninja + manual extension
  static linking
- Known build issues exist (undefined symbol `__android_log_write`)
- No pre-built Android artifacts in Maven Central or JitPack

This is a **disqualifying platform risk** for SteleKit.

### iOS: Threading Model Incompatibility

Safari on iOS disables SharedArrayBuffers, which DuckDB's multi-threaded execution
requires. The DuckDB team has confirmed they must compile DuckDB without threads
for iOS browser compatibility. For native iOS (Kotlin/Native), no official binding
exists — C interop would need to be developed from scratch. The columnar execution
engine is optimized for multi-core parallel scans and degrades significantly when
forced to single-threaded operation.

### Memory Usage

DuckDB's columnar buffer manager is optimized for analytical workloads — it loads
entire column segments (122,880 rows per row group by default) into memory during
query execution. Benchmarks show peak memory of ~2.3 GB vs SQLite's 480 MB for
equivalent data sizes. On Android devices, DuckDB memory behavior frequently triggers
OOM kills at the OS level. GitHub issue #8423 documents the engine requiring excessive
memory (60 GB+) when building indexes on large tables.

DuckDB does provide a memory limit setting (`SET memory_limit = '256MB'`) but some
operations bypass the buffer manager and ignore the limit, making it unreliable for
memory-constrained mobile devices.

### OLTP Write Performance

DuckDB is an OLAP engine. For transactional OLTP workloads (individual row INSERT/UPDATE
on `blocks` and `pages` tables), DuckDB is measurably slower than SQLite:
- Point lookups: SQLite outperforms DuckDB by ~20%
- Single-row inserts: DuckDB overhead is significant due to columnar write path

For SteleKit's workload (frequent single-block writes during editing, debounced at
500 ms), DuckDB's write path is a poor fit.

### Schema Migration

DuckDB has no built-in migration runner. Migrations require custom SQL scripts with
version tracking — similar to what `MigrationRunner` already implements for libsql.
However, DuckDB's DDL differs from SQLite in important ways (`VARCHAR` vs `TEXT`,
`HUGEINT`, different expression syntax), so the existing `SteleDatabase.sq` schema
would need significant adaptation.

### Verdict: Disqualified

Disqualified on platform support (experimental Android, no production iOS Kotlin
binding) and OLTP write performance. Not appropriate for a mobile-first OLTP workload.

---

## ClickHouse / chDB

**Maturity risk: Medium | Platform risk: High | Perf risk: High | Migration complexity: Very High**

### No Embedded Mobile Variant

ClickHouse server is a multi-process, network-served OLAP database — it requires a
running server daemon and cannot be embedded in an Android or iOS process. The
embedded variant (chDB) was acquired by ClickHouse in March 2024 and provides
Python, Node.js, Go, Rust, and C/C++ bindings. There are no JVM/Kotlin, Android,
or iOS bindings for chDB. The "ClickHouse on Android" blog post from ClickHouse is
an engineering demonstration using the full server binary — the APK includes a
~400 MB stripped binary, and memory-intensive queries are killed by Android's OOM
killer.

### Binary Size

chDB takes ~100 MB of disk on desktop. For mobile, even this reduced size is
prohibitive (typical Android APK size limits and iOS app store considerations
apply). The full ClickHouse binary is ~1.1 GB.

### Update / Delete Performance

ClickHouse is append-optimized. `UPDATE` and `DELETE` in ClickHouse are implemented
as asynchronous mutations — they do not provide row-level ACID semantics. Individual
block edits (SteleKit's primary write pattern) would either require `ReplacingMergeTree`
with deferred deduplication (eventual consistency) or frequent `ALTER TABLE UPDATE`
mutations (high latency, not suited for interactive editing).

### WASM

ClickHouse provides WASM support only for user-defined functions (UDFs) — not for
running the engine itself in WASM. There is no in-browser ClickHouse runtime.

### Verdict: Disqualified

Disqualified on all mobile platforms, binary size, and fundamental OLTP write model
mismatch. chDB does not have JVM/Android/iOS bindings. The data model (append-only
column store) is antithetical to SteleKit's interactive editing pattern.

---

## LevelDB / RocksDB

**Maturity risk: Low (RocksDB is production-proven) | Platform risk: Medium | Perf risk: Medium | Migration complexity: Very High**

### No SQL Layer

Neither LevelDB nor RocksDB provides SQL. Both are key-value stores. SteleKit's query
layer (SQLDelight, FTS5, relational joins across pages/blocks/references) would need to
be rebuilt from scratch on top of a custom key encoding scheme. This is a complete
rewrite of the data access layer — disqualifying on integration cost alone for a solo
developer.

### RocksDB JNI on Android

RocksDB provides a Java binding (`rocksdbjni`) that is usable on Android. Binary size
is significant: the `rocksdbjni` JAR includes native `.so` files for all Android ABIs,
adding ~8–15 MB per ABI to the APK. This is manageable but non-trivial.

### Memory: Confirmed Excessive Usage

Multiple confirmed GitHub issues document RocksDB exceeding configured memory limits
in JNI usage:
- Issue #280: RocksDB using more memory than configured options
- Issue #3216: RocksDB "massively exceeds memory limits" — potential memory leak
- Issue #9962: Native memory leak through JNI after upgrading to 7.0.4

These are native heap leaks that do not appear in Java heap profilers, making them
difficult to diagnose in a KMP application. The root cause in several cases is
unclosed native iterators or write batches that hold native memory past GC cycles.

### Compaction Pause Latency

RocksDB's LSM-tree compaction causes documented latency spikes in OLTP workloads.
During level-0 to level-1 compaction, write stalls occur as the compaction queue
fills. SILK (2019 Usenix ATC) documented this as a fundamental issue with background
I/O interference. On mobile devices where I/O bandwidth is limited, compaction pauses
are more severe and less predictable. An interactive editing app like SteleKit would
surface these as visible UI hangs.

### iOS and WASM

RocksDB has no official iOS Kotlin/Native binding and no WASM port. A C interop layer
would need to be written for each platform. RocksDB requires a threading model
(background compaction threads) that is incompatible with single-threaded WASM.

### ACID Transactions

RocksDB supports optimistic and pessimistic transactions but these operate at the
key-value level, not the SQL level. Simulating SQL transactions across multiple
key-value keys requires careful application-level sequencing — adding complexity
equivalent to building a mini-RDBMS.

### Verdict: Disqualified

Disqualified on missing SQL layer (would require complete data access rewrite),
JNI memory leak history, compaction pause risk, and absent iOS/WASM support. The
maintenance cost for a solo developer is prohibitive.

---

## Summary Findings

1. **libsql (baseline) is the strongest option for SteleKit today.** The MVCC
   `BEGIN CONCURRENT` feature does not activate in local-file mode (only in sqld server
   mode), but this is irrelevant because SteleKit's `DatabaseWriteActor` already
   serializes writes — single-writer SQLite semantics are sufficient and correct.
   The JNI maintenance burden is real but already mitigated by the CI pipeline on this
   branch.

2. **DuckDB, ClickHouse/chDB, LevelDB/RocksDB are disqualified by platform gaps.**
   None of them have production-ready Android + iOS + WASM support simultaneously.
   DuckDB's Android support is explicitly experimental; ClickHouse has no mobile
   embedding story; RocksDB lacks iOS/WASM support and has confirmed JNI memory leaks.

3. **Limbo is the only viable future candidate**, but it is in beta with confirmed FTS5
   gaps and no Kotlin/WASM driver. Re-evaluate when Limbo publishes a stable 1.0 with
   FTS5 confirmed. LadybugDB is disqualified by the Android/iOS platform gap and
   requires a full Cypher query layer rewrite.
