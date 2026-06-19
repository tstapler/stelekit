# Embedded DB Research — Requirements

## Context

SteleKit is a Kotlin Multiplatform outliner app (Logseq migration) with four shipping targets:
JVM/Desktop, Android, iOS (Kotlin/Native), and WASM/Web. The current database is libsql
(SQLite fork) on JVM/Android, with SQLite (system) on iOS and sqlite-wasm on WASM.

This research explores whether a purpose-built Rust storage engine — bridged to KMP via JNI
(JVM/Android) and C interop (iOS) and compiled to WASM (Web) — could outperform or out-fit
libsql on memory-constrained mobile devices while covering all four platforms.

The team (one developer) is willing to build a custom Rust query layer on top of an existing
storage engine. This is a research exercise, not a committed implementation.

## Memory Constraint

**≤1 GB native memory budget.** Primary concern is Android, where the OOM killer becomes
active as processes approach the device's per-app native memory limit. DuckDB was previously
rejected at ~2.3 GB peak. Any candidate must run comfortably below 1 GB peak on an 8,000-page,
~100,000-block SteleKit graph.

## Data Model

SteleKit's data model that the engine must serve:

| Entity | Size (8k-page graph) | Access pattern |
|--------|----------------------|----------------|
| Pages | ~8,000 rows | Point lookup by name/uuid; paginated list; journal queries by date |
| Blocks | ~100,000 rows | Subtree fetch (recursive parent→child); point lookup by uuid; bulk insert during import |
| Block references | ~50,000 rows | Backlink resolution: find all blocks referencing a page name |
| FTS index | ~100,000 entries | `bm25()` ranked search across block content; page name search; highlight extraction |
| Properties | ~20,000 key-value pairs | Keyed lookup per block/page |

Write pattern: debounced single-block updates (500ms), bulk import bursts (1,000–10,000 inserts).
Read pattern: interactive single-row lookups + paginated lists + FTS queries.

## Research Questions

### 1. Storage Engine Candidates
Which Rust or C embedded storage engines are:
- Lightweight (binary < 5 MB, runtime memory < 200 MB for the data model above)?
- ACID-compliant (crash recovery, durable writes)?
- Compilable for: `aarch64-linux-android`, `aarch64-apple-ios`, `wasm32-unknown-unknown`?
- Suitable as a foundation for a SQL/FTS query layer?

Primary candidates to evaluate:
- **redb** — pure-Rust B-tree KV store (no unsafe, WASM-capable)
- **LMDB** — C mmap KV store (industry-proven, very low memory, used in OpenLDAP/Mozilla)
- **sled** — Rust B-tree KV store (async-native)
- **fjall** — Rust LSM-tree KV store (write-optimized)
- **persy** — Rust embedded database with indexing
- **SQLite compiled with minimal flags** — custom compile stripping unused features

### 2. FTS Layer Options
Can a Rust FTS library be layered on top of any storage engine to replace SQLite FTS5?
- **Tantivy** — Rust full-text search (Lucene-style); WASM support?; mobile binary size?
- **stork** — WASM-native FTS; feature set?
- **sonic** — Rust FTS server (embedded mode?)
- **Custom inverted index** — build minimal BM25 on top of a KV store (complexity estimate)

### 3. WASM Viability
Which engines actually compile to `wasm32-unknown-unknown` or `wasm32-wasi`?
- Threading: WASM is single-threaded by default (SharedArrayBuffer restrictions in browsers)
- Persistence: Only OPFS (Origin Private File System) for durable storage
- Memory: WASM heap is limited (typically 256 MB–2 GB depending on browser/device)

### 4. Custom Architecture Assessment
If we build "Rust KV store + custom SQL-like layer + Tantivy FTS":
- What is the realistic LOC estimate for the query layer?
- What SQLDelight compatibility mode would we need (or would we replace SQLDelight)?
- What migration path from the current `SteleDatabase.sq` schema exists?
- What maintenance burden does this impose vs. riding on a maintained engine?

### 5. Comparison vs. libsql (baseline)
For any promising candidate, estimate:
- Expected write throughput improvement (vs. libsql's ~24% over xerial JDBC)
- Expected read latency for the common patterns
- Binary size on Android APK
- Cold-start time-to-first-query

## Platform Constraint

**Hard**: all four platforms simultaneously:
- `aarch64-linux-android` + `x86_64-linux-android` (JNI `.so`)
- `aarch64-apple-ios` + `x86_64-apple-ios-sim` (C interop `.a` / `.xcframework`)
- `wasm32-unknown-unknown` or `wasm32-wasi` (compiled to `.wasm`)
- JVM `linux-x86_64` + `macos-aarch64` + `windows-x86_64` (JNI `.so`/`.dylib`/`.dll`)

An engine that doesn't compile for all of these is still worth researching if the gap is
"6 months of upstream work away" vs. "architectural impossibility."

## Out of Scope

- Client-server databases
- JVM-only solutions
- Engines requiring OS-level services (systemd, DBus, etc.)
- GPL/AGPL licensed engines incompatible with app distribution

## Graph Search Requirements (NEW — highest priority)

SteleKit's outliner model is fundamentally a **property graph**: pages are nodes, block
references are directed edges, block hierarchies are tree edges. The current SQLite schema
models this relationally with foreign keys and recursive CTEs — workable but not expressive.

The ideal solution provides **out-of-the-box** graph query capability:

| Query type | Current approach | Desired |
|---|---|---|
| Backlinks | `SELECT ... FROM block_references WHERE target_page = ?` | Native graph traversal |
| 2-hop connections | Multi-join + UNION, expensive | Single graph query |
| Transitive subtree | Recursive CTE | Native hierarchy traversal |
| "Pages linking to X AND Y" | Two subqueries + INTERSECT | Native set intersection on edges |
| Path between pages | Not supported today | Shortest-path query |
| Subgraph around a page | Not supported | N-hop neighborhood |

A graph-native engine should handle all of these with a single query language (Cypher,
Datalog, Gremlin, or similar) rather than recursive SQL.

### Priority graph engines to evaluate

- **CozoDB** (https://github.com/cozodb/cozo) — Rust, Datalog + relational + graph, embedded,
  claims WASM support, Apache 2.0. Has JNI bindings. Most promising.
- **SurrealDB** (https://github.com/surrealdb/surrealdb) — Rust, multi-model (graph + relational
  + document), embedded mode (`Db::new()`), WASM claimed. Has FTS. BSL 1.1 → Apache 2.0 after 4 years.
- **IndraDB** (https://github.com/indradb/indradb) — Rust property graph DB, Apache 2.0. Mobile?
- **GrDB** — graph-relational hybrid (any Rust option here?)
- **Kuzu** (https://github.com/kuzudb/kuzu) — C++ columnar graph DB (LadybugDB is based on this);
  Cypher; WASM build exists; Java binding exists. Mobile gap was the blocker before — is there a
  lightweight embedded mode that fits the 1 GB budget?

## Text Search Requirements (NEW — expanded scope)

Beyond SQLite FTS5's BM25, the ideal solution provides:

| Feature | Current (FTS5) | Desired |
|---|---|---|
| BM25 ranking | Yes | Yes (must-have) |
| Highlighted snippets | `highlight()` | Yes |
| Typo tolerance / fuzzy match | No | Strongly preferred |
| Phrase proximity search | Partial | Full phrase search |
| Multi-field search | Separate FTS tables | Unified index |
| Incremental indexing | Triggers | Transactional updates |
| Semantic / vector search | No | Nice-to-have (future) |

A good text search layer should require no schema change when new block fields are added.

## Deliverable

A structured research report covering:
1. A scored comparison matrix: candidate × criterion (including graph + FTS dimensions)
2. A **top-2 recommendation** with build plan sketch, explicitly addressing graph traversal
3. An honest assessment of whether building a custom layer beats adopting libsql long-term
4. Specific "decision triggers" that would justify starting an implementation
5. For the top candidate: estimate of what graph query API would look like in Kotlin call sites
