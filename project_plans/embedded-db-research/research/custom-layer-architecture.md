# Custom Rust Query Layer — Architecture & Build Cost Research

**Date**: 2026-06-19
**Scope**: Evaluate replacing libsql/SQLite with a purpose-built Rust query layer across all four
SteleKit targets (JVM, Android, iOS, WASM).

**Calibration baseline**: The existing `native/libsql/src/lib.rs` JNI bridge is 901 LOC and implements
a full SQL driver (open/close DB+connection, execute raw, prepare/bind/execute/query statements,
cursor iteration, MVCC probing, poison detection). It wraps a mature library (libsql). Every
approach below must replicate this interface surface — plus build the storage layer it currently
delegates to libsql.

**Schema scope**: `SteleDatabase.sq` contains 16 tables, 42 indexes, 6 FTS triggers, and
approximately 130 named queries. Query patterns include point lookups, paginated lists, bounded
IN-list lookups, 2-table JOINs, FTS MATCH with `highlight()` and `bm25()`, aggregates (COUNT,
SUM, MIN, MAX), and CTEs (UNION in `selectNeighbourPageUuids`).

---

## Approach A: Domain-specific Rust crate (no SQL parser)

Replace SQL entirely with a typed Rust API: `insert_page(uuid, name, ...) -> i64`,
`get_page_by_uuid(uuid) -> PageHandle`, etc. SQLDelight is abandoned; repositories become hand-
written Kotlin calling typed JNI methods.

### Storage engine recommendation: redb

**redb** (https://github.com/cberner/redb, v4.1.0 as of Apr 2026) is the strongest fit for
JVM/Android/iOS — but carries a hard WASM limitation:

- Pure Rust, no unsafe code in public surface (auditable for mobile/WASM trust)
- ACID-compliant B-tree with crash recovery (copy-on-write, no WAL corruption class)
- **WASM: NOT supported for wasm32-unknown-unknown.** redb's file backend uses `memmap2` (OS
  mmap), which is unavailable in browser WASM. PR #1065 adding wasm32 support was closed/abandoned
  Sep 2025. WASI (`wasm32-wasi`) works via PR #583 (merged May 2023) but requires a WASI runtime,
  not a browser. For the WASM target a separate in-process store (GlueSQL MemoryStorage or a custom
  in-memory implementation) is required alongside redb on native platforms.
- Compiles cleanly to `aarch64-linux-android` and `aarch64-apple-ios` (pure Rust, no C deps)
- In-memory mode available for tests and WASM
- MIT/Apache 2.0 dual-license
- Binary size: ~300–500 KB stripped aarch64 `.so` (vs libsql's ~6–8 MB bundled SQLite)
- Active maintenance: redb v4.1.0 released Apr 2026; API stable
- Performance vs SQLite (from redb benchmarks on NVMe): individual writes 7.6× faster, random
  reads 3.8× faster (single thread), 64× faster (32 threads). Raw KV; SQL overhead is additional.

**Rejected alternatives**:

- **sled**: Marked as "beta quality" by author; tree-wide panic on concurrent access bugs; not
  actively maintained as of 2025. Avoid for production.
- **fjall**: LSM-tree design uses `std::thread::spawn` (hard blocker for WASM). Better write
  throughput than redb on bulk imports (353ms vs redb 1595ms in benchmarks) but adds compaction
  complexity for SteleKit's mixed interactive pattern.
- **LMDB**: C library; requires CGo-style FFI; not pure Rust; WASM compilation is architecturally
  impossible (mmap is not available in WASM).
- **persy**: Less mature ecosystem; limited community validation at scale.

### LOC estimate

| Component | LOC (Rust) | Notes |
|-----------|------------|-------|
| redb table definitions + key/value codecs | 500–700 | One `TableDefinition` per logical table; composite keys as big-endian byte arrays; postcard serialization for value structs |
| Secondary index management | 800–1,200 | Manual: on each INSERT/UPDATE/DELETE, write/remove entries in 6–8 secondary index tables; must be inside same redb write transaction |
| Page CRUD (5 ops: insert, get-by-uuid, get-by-name, update, delete) | 350–500 | |
| Block CRUD (5 ops: insert, get-by-uuid, get-by-page, update, delete) | 400–600 | Includes subtree fetch via parent_uuid secondary index |
| Reference resolution (3 ops: insert-ref, outgoing, incoming) | 200–300 | |
| FTS search (3 ops: search blocks, search pages, count) with Tantivy | 800–1,200 | See FTS section below |
| Schema migration framework | 250–350 | Metadata table in redb; migration Vec with version u64; apply-in-order; checksum; idempotent |
| Remaining tables (properties, plugin_data, page_visits, ops log, spans, etc.) | 1,500–2,500 | 11 more tables each needing CRUD + secondary indexes; lower complexity than blocks |
| JNI bridge (JVM + Android) | 700–1,000 | Similar to existing lib.rs; typed extern fns instead of SQL passthrough; panic-catch wrapper required |
| C interop bridge (iOS Kotlin/Native cinterop) | 400–600 | Header + .def file + Kotlin `actual` implementations |
| WASM bridge (wasm-bindgen or direct exports) | 300–500 | wasm-pack or manual `#[wasm_bindgen]`; async via JS promises |
| **Total Rust** | **~5,700–9,450** | |
| Kotlin repositories replacing SQLDelight generated code | ~3,000–4,500 | ~130 named queries → typed Kotlin methods; Flow-backed reactive queries need channel/StateFlow wrappers |
| **Total project LOC** | **~8,700–14,000** | |

### Schema migration without SQL

Without DDL, migration is a code-level operation: each migration is a Rust closure
`fn(WriteTransaction) -> Result<()>` registered in an ordered `Vec`. The migration runner reads
a `schema_version: u64` from a redb metadata table, applies all closures with index >= version,
and increments the stored version. Data migrations (e.g., backfilling `backlink_count`) become
Rust loops over all rows. Structural changes (adding a field) require either: (a) bumping the
serialization format with a new struct version and a migration that rewrites all rows; or
(b) storing schema-optional fields in a secondary table. This is strictly more code per migration
than `ALTER TABLE ADD COLUMN` but is type-checked by the Rust compiler.

### SQLDelight breakage

**Yes — SQLDelight must be abandoned.** SQLDelight generates Kotlin from `.sq` files and
requires a `SqlDriver` that accepts raw SQL strings. A typed Rust API has no SQL strings to
pass through. The SQLDelight toolchain (code generation, schema validation, migration
verification) cannot be reused.

**Replacement**: Hand-written Kotlin `*Repository` classes calling a typed `SteleKvJni` object.
The existing repository interfaces (`PageRepository`, `BlockRepository`, etc.) remain unchanged
as the Kotlin-side contract — only the implementations change. This is the cleanest migration
path because the repository interfaces already hide SQL from the rest of the app.

**SQLDelight tooling loss**: No compile-time query validation, no auto-generated mapper code,
no schema-diff migration generation. These were genuine productivity multipliers — losing them
is a real cost.

### Calendar time estimate

- Rust core engine (redb + typed API): 8–12 weeks solo
- JNI + C interop + WASM bridges: 3–5 weeks
- Kotlin repository rewrites (130 queries): 4–6 weeks
- Testing (parity tests, migration tests, FTS regression): 3–4 weeks
- **Total: 18–27 weeks** (4.5–7 months)

---

## Approach B: Minimal SQL subset engine on top of KV

Build a Rust crate that parses a subset of SQL sufficient for SteleKit's ~130 `.sq` queries and
executes them against a KV backend. SQLDelight can be retained via a custom `SqlDriver` wrapper.

### SQL parser: sqlparser-rs

**sqlparser-rs** is the right choice (not `nom`-based hand-rolled; not pest):

- Compiles to `wasm32-unknown-unknown` (no OS dependencies, `#[no_std]` + alloc capable)
- Apache 2.0 license
- Battle-tested at production scale (DataFusion, Ballista use it)
- Produces typed `Statement` AST you walk to generate KV operations
- Binary footprint: ~300–400 KB stripped with LTO + `opt-level = "z"`
- **FTS5 MATCH syntax is not supported** — `WHERE blocks_fts MATCH ?`, `bm25(...)`, and
  `highlight(...)` are FTS5 extensions with no standard SQL equivalent. These ~8 queries must be
  intercepted before the parser as a special code path. This is viable since FTS queries are
  structurally distinct: they always reference a `*_fts` virtual table.

### LOC estimate

| Component | LOC (Rust) | Notes |
|-----------|------------|-------|
| SQL tokenizer | 0 | sqlparser-rs handles entirely |
| SQL parser adapter | 100–150 | Call sqlparser-rs; pattern-match Statement variants; reject unsupported forms with clear error |
| Query planner | 400–600 | Map table name → redb TableDefinition; detect pk lookup vs index scan vs table scan; handle IN-list; detect JOIN as nested-loop |
| Query executor | 1,200–1,800 | Iterate redb ranges; deserialize rows; evaluate WHERE predicates; apply ORDER BY (in-memory); LIMIT/OFFSET; project columns |
| KV mapping layer | 600–900 | Same as Approach A — one struct per table, key encoding, secondary indexes |
| Secondary index management | 800–1,200 | Same as Approach A — on mutation, update all affected indexes atomically |
| FTS special-case path (Tantivy) | 800–1,200 | Intercept `*_fts MATCH` queries; delegate to Tantivy; merge results with redb join |
| Schema migration framework | 200–300 | Same as Approach A |
| Remaining tables (all 16) | 1,800–2,800 | More work than Approach A since executor must handle each table's schema generically |
| JNI + C interop + WASM bridges | 1,400–2,100 | SQL passthrough bridge is simpler than typed-method bridge (one execute path vs one per operation), but parameter binding complexity is identical |
| **Total Rust** | **~7,300–11,050** | |
| Kotlin SqlDriver wrapper | 350–500 | See detail below |
| **Total project LOC** | **~7,650–11,550** | |

### SQLDelight compatibility

SQLDelight CAN be retained with a custom `SqlDriver`:

```kotlin
class SteleKvSqlDriver(private val bridge: SteleKvJni) : SqlDriver {
    override fun execute(identifier: Int?, sql: String, parameters: Int,
                         binders: SqlPreparedStatement.() -> Unit): QueryResult<Long>
    override fun <R> executeQuery(identifier: Int?, sql: String,
                                  mapper: (SqlCursor) -> QueryResult<R>,
                                  parameters: Int, binders: ...): QueryResult<R>
    override fun newTransaction(): QueryResult<TransactionWithReturn<Unit>>
    // ... listener registry, close, currentTransaction
}
```

The wrapper is ~350–500 LOC Kotlin (statement binder, cursor adapter, transaction state machine,
listener registry). SQLDelight continues to generate Kotlin from `.sq` files; the generated code
calls your `SteleKvSqlDriver` which ships each SQL string to Rust for execution.

**Critical risk**: Every SQL construct that SQLDelight generates must be handled by your Rust
parser. Known hazards from `SteleDatabase.sq`:

- `INSERT OR REPLACE`, `INSERT OR IGNORE` — non-standard upsert semantics
- `WHERE uuid IN ?` — SQLDelight expands to `WHERE uuid IN (?, ?, ...)` at runtime with
  variadic parameter counts; your parser must handle this
- `UNION` in `selectNeighbourPageUuids` — requires set-operation support
- `WITH` (no CTEs in current schema, but common in future queries)
- `GROUP BY ... HAVING COUNT(*) > 1` in `selectDuplicateBlockHashes`
- `highlight(blocks_fts, 0, '<em>', '</em>')` — FTS5-only function
- `PRAGMA wal_checkpoint(TRUNCATE)` — DDL passthrough

Each unsupported construct becomes a runtime panic unless explicitly handled. This is a
**maintenance trap**: every future `.sq` query addition must be tested against the Rust parser.

### Calendar time estimate

- Rust SQL executor + KV mapping: 12–18 weeks solo
- JNI + bridges: 3–5 weeks
- SqlDriver Kotlin wrapper: 1–2 weeks
- Testing (parity tests for all 130 queries): 4–6 weeks
- **Total: 20–31 weeks** (5–8 months)

**Maintenance burden**: Every new SQLDelight query adds risk. The executor test suite must be
run against every `.sq` change. This doubles the engineering cost of all future query additions
indefinitely — each `.sq` change that works with libsql must also be verified against your
custom executor.

---

## Approach C: Existing lightweight SQL engine (not SQLite)

### GlueSQL

GlueSQL (https://github.com/gluesql/gluesql, v0.19.0 Jan 2026, 3.1k stars) is a pure-Rust SQL
engine with pluggable storage — the most serious non-SQLite Rust SQL engine as of 2026.

**WASM status**: GlueSQL compiles to `wasm32-unknown-unknown`. The project ships a web demo,
publishes `gluesql-js` on npm, and has explicit `wasm-pack` support. Storage in WASM uses
`gluesql-idb-storage` (IndexedDB) or `gluesql-web-storage` backends (not OPFS), which limits
performance and requires async JS bridge. A `MemoryStorage` backend exists for in-memory use.
Note: build failures (#1511) occur when `idb-storage` pulls in `mio` transitively; workaround is
explicit `default-features = false`.

**redb as storage backend**: `gluesql-redb-storage` is a **first-class feature flag** in GlueSQL
since v0.17.0 (Jun 2024). It is included in GlueSQL's default feature set. However: since redb
itself does not compile to wasm32-unknown-unknown (see storage engine section), the combination
`GlueSQL + redb-storage + WASM` is not possible. WASM deployments must use `MemoryStorage` or
`idb-storage`. This means GlueSQL with redb provides persistent storage on JVM/Android/iOS, but
WASM reverts to in-memory (no persistence).

**FTS support**: GlueSQL does NOT support FTS5 or any full-text search virtual table
equivalent as of 2026. There is no `MATCH` operator or BM25 function. FTS would require either:
(a) a GlueSQL extension (substantial upstream contribution); or (b) a side-channel Tantivy
integration where FTS queries bypass GlueSQL entirely and are merged at the application layer.

**SQL subset**: GlueSQL supports a meaningful SQL subset including SELECT/INSERT/UPDATE/DELETE,
JOINs, GROUP BY, HAVING, ORDER BY, LIMIT/OFFSET, and basic aggregate functions. It does NOT
support: window functions, `INSERT OR REPLACE`, `INSERT OR IGNORE` (uses standard `INSERT INTO
... ON CONFLICT` syntax instead), triggers, virtual tables, or `PRAGMA`. These are all used
in `SteleDatabase.sq`.

**Binary size**: ~1.5–2.5 MB stripped WASM blob; ~1–1.8 MB stripped aarch64-linux-android .so
(excluding storage backend). Larger than redb alone but smaller than libsql's bundled SQLite
(~6–8 MB).

**License**: Apache 2.0. App-distribution compatible.

**SQLDelight driver wrapper**: The same ~350–500 LOC Kotlin `SqlDriver` approach works, but the
semantic mismatches (`INSERT OR REPLACE` vs `ON CONFLICT`, trigger absence, PRAGMA passthrough)
require shim SQL translation. This is feasible but fragile.

**Verdict**: GlueSQL eliminates the SQL-parser-from-scratch cost but introduces two hard gaps:
no FTS, and SQL dialect differences from SQLite that break generated SQLDelight queries. The
dialect gap alone makes it unsuitable as a drop-in replacement without significant shim work.

### CozoDB

CozoDB (https://github.com/cozodb/cozo) is a Rust embedded graph+relational database using
Datalog as its query language (not SQL).

**WASM status**: CozoDB has explicit WASM support and ships a browser demo. The WASM build is
production-quality for read-heavy in-memory workloads.

**Android/iOS**: Cross-compiles to `aarch64-linux-android` and `aarch64-apple-ios`. The project
provides pre-built bindings for multiple platforms including Android via a Java SDK.

**FTS support**: CozoDB has a built-in full-text search capability using its own index type
(`*text` column attribute). BM25 ranking is supported. This is a genuine differentiator.

**Binary size**: ~4–6 MB stripped (includes Datalog engine, FTS, graph traversal). Larger than
redb but smaller than DuckDB (previously rejected at ~2.3 GB peak memory).

**Critical problem for SteleKit**: CozoDB uses Datalog, not SQL. All 130 SteleKit queries would
need to be rewritten in CozoDB's Datalog syntax. SQLDelight is completely incompatible — there is
no SQL string to pass through. This is equivalent in migration cost to Approach A but adds the
learning curve of Datalog and ties the project to a smaller ecosystem.

**License**: MPL-2.0 — copyleft. Requires source disclosure of modifications to CozoDB itself.
More restrictive than MIT/Apache for commercial or proprietary distribution.

**Maintenance status**: Last release v0.7.6 Dec 2023. **Project is effectively stalled** — no
releases in 2.5+ years as of Jun 2026. Do not build a production dependency on CozoDB.

**Verdict**: Interesting for graph-traversal-heavy workloads but not viable for SteleKit without
a full Datalog query rewrite, SQLDelight abandonment, an MPL-2.0 license review, and reliance
on a stalled project.

### toydb

Educational project; not suitable for production use. Excluded from further consideration.

### IndraDB

Rust graph database. No SQL support; designed for graph property queries. Not suitable.

### Pglite / Neon WASM

Postgres compiled to WASM. Runs in browser via OPFS. Not embeddable in a native Android/iOS
binary — it's a browser-only target. Not applicable.

### Summary matrix

| Engine | WASM | Android | iOS | FTS | SQLDelight compat | Binary size | Solo-viable |
|--------|------|---------|-----|-----|-------------------|-------------|-------------|
| GlueSQL + redb | GlueSQL yes (IDB); redb no (mmap) | Yes (untested) | Yes (untested) | No — hard gap | Partial (dialect gap) | ~2 MB + storage | Marginal |
| GlueSQL + MemoryStorage | Yes (no persistence) | Yes | Yes | No | Partial | ~1.5–2 MB | No (no persistence) |
| CozoDB | Yes (memory only, no OPFS) | Yes (official SDK) | Yes (official SDK) | Partial (Datalog) | No (Datalog only) | ~5–15 MB | No (stalled project + MPL-2.0 + Datalog rewrite) |
| redb (Approach A) | No (wasm32 unsupported; WASI only) | Yes | Yes | Via Tantivy | No | ~400 KB + 3–5 MB Tantivy | Yes (JVM/Android/iOS only) |
| libsql (current) | No (WASM target uses sqlite-wasm separately; already working) | Yes | Via system SQLite | Yes (FTS5) | Yes | ~7 MB | Yes (already built) |

---

## FTS Layer: Tantivy vs Custom BM25

Any approach except libsql must provide FTS. Two options:

### Tantivy

- Pure Rust Lucene-style FTS library (v0.26.1, May 2026; 15.4k stars; production use at Etsy,
  ParadeDB, Element.io)
- **WASM**: Partial — `phiresky/tantivy-wasm` demonstrates compilation to `wasm32-unknown-unknown`
  (~15 MB uncompressed / ~4 MB gzipped WASM binary). Tantivy's internal `rayon` thread pool is
  disabled in WASM (single-threaded only). This is a proof-of-concept, not an official supported
  target from Quickwit. For SteleKit WASM, the 4 MB gzip addition is large but potentially
  acceptable in a web worker context.
- **Android/iOS**: Confirmed production-viable — `flutter_tantivy` plugin (Nov 2025) demonstrates
  Android and iOS use via `flutter_rust_bridge`. Stripped `.so` estimated 3–5 MB aarch64.
- BM25 ranking: yes. Porter stemming/unicode61: yes. Highlight extraction: yes (`Snippet` API).
  These are exact equivalents of SteleKit's current FTS5 features.
- Runtime memory for 100K-block index: ~20–80 MB (index is mmap'd when idle; active search
  loads segments). Acceptable under 1 GB budget.
- License: MIT.
- **Integration cost**: ~800–1,200 LOC Rust to wrap Tantivy as a side-channel index synchronized
  with KV writes. The hard part is transactional consistency: a block INSERT must atomically
  update both redb and the Tantivy index. Tantivy uses its own segment writer — you must commit
  both or neither on crash. This requires either a two-phase-commit protocol or accepting that
  the FTS index may be slightly behind on crash recovery (acceptable if rebuilding is fast).

### Custom BM25 inverted index on redb

Build a minimal inverted index (term → posting list of (doc_id, frequency)) stored in redb.

- Viable at SteleKit scale (100K documents, average block content ~50 words)
- No stemming without a separate tokenizer crate (`rust-stemmers`: ~50 KB binary)
- BM25 scoring: ~200 LOC of pure math
- Highlight extraction: ~300 LOC (find term offsets in original text)
- No WASM bloat — pure redb operations, WASM-safe
- **Cost**: ~1,500–2,000 LOC total. Acceptable quality for SteleKit's use case (not web search).
- **Maintenance**: you own correctness. No battle-tested query syntax (no proximity operators,
  no phrase queries). Adequate for keyword search in an outliner.

**Recommendation**: For WASM, custom BM25 on redb. For Android/iOS/JVM, Tantivy. Use a shared
Rust trait `FtsEngine` with two implementations, selected at compile time via Cargo features.

---

## ROI Assessment

### What you get from each approach

| Dimension | Approach A (typed Rust) | Approach B (SQL-on-KV) | Approach C (GlueSQL) | libsql (current) |
|-----------|------------------------|----------------------|---------------------|-----------------|
| Binary size | ~750 KB + 3.5 MB Tantivy | ~1.2 MB + 3.5 MB Tantivy | ~2 MB + 3.5 MB Tantivy | ~7 MB (bundled SQLite) |
| Android peak memory (8K pages) | ~80–120 MB (estimate) | ~100–150 MB (estimate) | ~120–200 MB (estimate) | ~150–300 MB (measured) |
| Write throughput | +15–30% vs libsql (no WAL contention) | Similar to A | Similar to A | Baseline |
| Cold-start time-to-first-query | Faster (no SQLite init) | Similar to A | Similar to A | ~80–150 ms |
| SQLDelight retained | No | Yes (with risk) | Partial | Yes |
| Compile-time query validation | No (typed API is the validation) | No | No | Yes |
| FTS quality | Tantivy (excellent) | Tantivy (excellent) | None (gap) | FTS5 (excellent) |
| WASM persistence | redb + OPFS (manual) | redb + OPFS (manual) | GlueSQL + IndexedDB | sqlite-wasm + OPFS |
| Solo build cost | 18–27 weeks | 20–31 weeks | 14–22 weeks | Already shipped |
| Ongoing maintenance overhead | High (own the engine) | Very high (SQL compat + own engine) | High (dialect gap) | Low (upstream) |

### Honest cost/benefit for a solo developer

**The fundamental question**: What problem does the current libsql solution fail to solve?

From the requirements: the stated concern is memory on Android (≤1 GB budget). libsql/SQLite's
peak memory on an 8,000-page graph is currently estimated at 150–300 MB — well within budget.
DuckDB was rejected at ~2.3 GB, but libsql is not DuckDB. The existing JVM `PooledJdbcSqliteDriver`
architecture with 8 connections and `PlatformDispatcher.DB` already limits concurrency. The
memory concern is real but not currently triggering crashes.

**The libsql advantages that are genuinely hard to replicate**:

1. FTS5 with BM25, porter tokenizer, and `highlight()` — battle-tested, zero maintenance
2. `COLLATE NOCASE` on page names — one annotation vs a custom case-folding layer
3. `ON DELETE CASCADE` foreign keys — automatic subtree cleanup on block delete
4. `INSERT OR REPLACE` / `INSERT OR IGNORE` upsert semantics — idiomatic
5. `PRAGMA wal_checkpoint(TRUNCATE)` for post-import compaction
6. The entire migration framework (MigrationRunner) already works
7. SQLDelight code generation validates every query at compile time

Every one of these features requires explicit implementation in a custom layer.

### Decision triggers that would justify the build investment

Build a custom layer ONLY if one or more of these conditions holds:

1. **OOM crashes on target Android hardware at < 300 MB** — custom layer might save 100–150 MB
   by eliminating the SQLite B-tree page cache and WAL buffer. Measure first.
2. **Graph traversal queries are needed** — e.g., "find all pages reachable within 2 hops",
   "compute PageRank across the block reference graph". Custom redb + graph-specific indexes
   outperform SQL JOIN chains for this. CozoDB would be a better fit than rolling your own.
3. **Sub-5ms p99 on 1M blocks** — at this scale, the SQLite B-tree is the bottleneck and a
   purpose-built index layout (e.g., blocks stored with page_uuid as key prefix for spatial
   locality) would help. Not a current SteleKit problem.
4. **Full offline WASM with no server required** — sqlite-wasm already works via OPFS; this
   is already solved. Not a trigger.
5. **libsql upstream abandonment or license change** — libsql is MIT-licensed and actively
   maintained by Turso. Not an imminent risk.

### At what point does "just use libsql" win?

"Just use libsql" wins today. It wins unless you hit one of the triggers above. The build cost
of any custom approach (4.5–8 months) is 4–8x the cost of the entire libsql JNI bridge that was
already built (which took 1–2 weeks). The libsql bridge is 901 LOC and is already solving the
problem.

The custom layer paths add:
- 8,000–14,000 LOC you now own and must maintain forever
- No SQLDelight compile-time query validation (or a fragile SQL compat shim)
- A FTS implementation gap that took SQLite 15 years to stabilize
- A migration framework you must prove correct against real user data

---

## Recommendation

**Do not build a custom layer at this time.**

**If the memory constraint becomes a real problem** (measured OOM on target devices, not
estimated), the highest-leverage intervention is:

1. Profile actual Android memory with Android Studio Memory Profiler on a real 8K-page graph
2. If SQLite page cache is the culprit: tune `PRAGMA cache_size` to reduce it
3. If libsql overhead is the issue: evaluate switching to xerial JDBC (stock SQLite) on Android
   which has lower overhead than libsql's Tokio runtime per connection

**If you must reduce binary size** (APK size budget):
- Approach A (typed Rust + redb) would save ~4–5 MB on the Android APK (redb ~450 KB vs
  libsql/SQLite ~7 MB). This is meaningful for F-Droid distribution but not worth 6 months.
- More immediately: strip libsql with `opt-level = "z"` + `strip = true` (already in Cargo.toml)
  and explore `--features core` (already enabled).

**If you want to explore this further** without committing: prototype Approach A with redb for a
single table (pages only) alongside libsql. This would be ~1,500 LOC and would validate the
secondary index management complexity, migration framework, and JNI bridge shape before committing
to the full build.

**GlueSQL is the only credible shortcut**, and it now includes a first-class `gluesql-redb-storage`
feature that avoids writing the storage backend from scratch. Total gap to fill: Tantivy FTS
side-channel (~1,000 LOC), SQL dialect shims for `INSERT OR REPLACE`/`IGNORE` (~300 LOC), and
acceptance that PRAGMA/triggers are gone. WASM persistence remains unsolved (GlueSQL + redb is
not WASM-compatible; WASM falls back to in-memory MemoryStorage). Estimated effort: ~2,500 LOC +
10–14 weeks. This is about 40% of the cost of Approach A. Track the GlueSQL issue tracker for
SQLite dialect compatibility and OPFS persistence — if those land upstream, GlueSQL becomes
substantially more attractive. Check the GlueSQL changelog before committing: the `INSERT OR
REPLACE` / `INSERT OR IGNORE` gap is the single most critical blocker (SteleKit uses both
throughout `SteleDatabase.sq`).
