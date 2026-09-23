# Database Engine Feature Research — SteleKit Multi-DB Evaluation

Research date: 2026-06-19  
Use case: Kotlin Multiplatform outliner (Logseq-style). Data model: Pages, Blocks (hierarchical), References (backlinks), full-text search over block content.

---

## Feature Comparison Matrix

| Feature | libsql | LadybugDB | Limbo | DuckDB | ClickHouse/chDB | LevelDB/RocksDB | Neo4j/Memgraph |
|---|---|---|---|---|---|---|---|
| **FTS (built-in)** | ✅ Tantivy (v0.5+, exp.) + FTS5 compat | ✅ BM25 via extension | ✅ Planned (roadmap) | ✅ Extension (`duckdb_fts`) | ⚠️ Limited (via LIKE, no dedicated FTS) | ❌ None native | ❌ None native |
| **BM25 ranking** | ✅ Tantivy | ✅ | ❌ (FTS5 has bm25) | ✅ | ❌ | ❌ | ❌ |
| **Recursive/hierarchy** | ✅ Recursive CTEs | ✅ Native via Cypher patterns + variable-length paths | ✅ Recursive CTEs (SQLite compat) | ✅ Recursive CTEs + USING KEY (v1.3+) | ⚠️ Limited recursive CTE support | ❌ Must hand-roll | ✅ Native (graph traversal is first-class) |
| **OLTP point lookup** | ✅ Excellent (SQLite lineage) | ⚠️ Columnar; OLTP not primary target | ✅ Good (SQLite compat) | ⚠️ Poor (columnar, row-scan overhead) | ⚠️ Designed for append/analytics; updates added late | ⚠️ Fast for known keys, no SQL | N/A |
| **MVCC / snapshot isolation** | ✅ BEGIN CONCURRENT | ⚠️ Serializable ACID (not MVCC snapshots) | ✅ MVCC (in-progress impl.) | ✅ Snapshot isolation | ✅ MVCC (MergeTree variants) | ❌ Optimistic only | ✅ |
| **JVM binding** | ✅ Maven Central (`tech.turso.libsql:libsql`) | ✅ (`com.kuzudb:kuzu-java` — inherited from Kuzu) | ✅ (`bindings/java` via JNI) | ✅ (`org.duckdb:duckdb_jdbc`) | ❌ No JVM binding for chDB (Python only) | ✅ (`org.rocksdb:rocksdbjni`) | ✅ Neo4j Java driver; Memgraph via Bolt |
| **Android support** | ✅ `libsql-android` repo | ⚠️ Java binding exists; Android untested/unsupported in docs | ⚠️ JNI binding exists; Android not explicitly documented | ⚠️ Experimental via `kmp-duckdb` KMP project | ❌ No Android binding | ✅ (used widely on Android) | ❌ Not embeddable on Android |
| **iOS support** | ✅ (Turso iOS SDK) | ✅ Swift binding available | ⚠️ Dart binding (Flutter); no native Swift | ❌ No iOS embedding | ❌ | ❌ | ❌ |
| **WASM** | ✅ (via Limbo lineage) | ✅ Ladybug-Wasm | ✅ First-class WASM + OPFS | ✅ (WASM build available) | ❌ | ❌ | ❌ |
| **KMP / Kotlin wrapper** | ✅ Existing JNI driver in this repo | ❌ No KMP wrapper | ❌ No KMP wrapper | ⚠️ Community KMP project (`kmp-duckdb`) | ❌ | ❌ | ❌ |
| **Write throughput** | ✅ WAL + BEGIN CONCURRENT | ⚠️ Serializable transactions; single-writer on default | ✅ Async WAL | ⚠️ Write amplification in columnar | ⚠️ Append-optimised; mutations added mid-2025 | ✅ LSM-tree optimised | ✅ |
| **Single-file DB** | ✅ | ✅ | ✅ | ✅ | ❌ ClickHouse; ⚠️ chDB uses temp dirs | ❌ (directory) | ❌ (directory) |
| **Vector search** | ✅ Native | ✅ Extension | ✅ Planned | ✅ (`vss` extension) | ✅ | ❌ | ✅ |
| **SQLDelight compat** | ✅ (JDBC/SQLite dialect) | ❌ | ✅ (SQLite compat) | ❌ | ❌ | ❌ | ❌ |

---

## Engine Profiles

---

## libsql (baseline)

**Summary**: Open-source SQLite superset by Turso. Maintains 100% wire-level and file-format compatibility with SQLite while adding MVCC, a server mode, native FTS via Tantivy, vector search, and CDC.

### Full-Text Search
- **FTS5 compatibility**: libsql inherits SQLite's FTS5 virtual table mechanism and can load FTS5 as-is (work-in-progress in Turso v0.5).
- **Native Tantivy FTS** (experimental as of Jan 2026, Turso v0.5): Uses Apache Lucene-style `tantivy` Rust library. Stored inside the same B-Tree file (transactional; WAL-backed; crash-safe). SQL surface: `CREATE INDEX idx USING fts (col1, col2)`. Supports BM25 ranking via `fts_score()`, highlight via `fts_highlight()`, and tokenizers: `default`, `raw`, `simple`, `whitespace`, `ngram`. Merges are manual (`OPTIMIZE INDEX`) — segment count grows without it, so regular maintenance is needed for high-update workloads (blocks are updated on every edit). For SteleKit's block-heavy write pattern, `OPTIMIZE INDEX` should be run periodically.
- **Key caveat**: Tantivy FTS is experimental; FTS5 fallback is stable.

### Graph / Hierarchy
- Recursive CTEs supported (SQLite-compatible). Suitable for block-tree traversal (`WITH RECURSIVE children AS ...`).
- No native graph model; relationships are expressed via foreign keys and recursive SQL. Adequate for SteleKit's depth-bounded block trees (typically ≤10 levels).

### OLTP Fitness
- Excellent. Row-oriented B-tree storage with WAL. Optimized for mixed read/write with MVCC (BEGIN CONCURRENT). Single-row point lookups (`getBlockByUuid`, `getPageByName`) are fast with proper B-tree indexes.
- Android: `libsql-android` library available on GitHub releases. Current SteleKit branch uses this.

### Notable Missing Features
- Server-mode Tantivy FTS is the primary differentiator; the embedded version is still experimental.
- No graph traversal primitives beyond SQL recursive CTEs.

---

## LadybugDB

**Summary**: Embedded columnar property graph database. Community-driven fork of KuzuDB (archived Oct 2025). Uses Cypher query language, columnar + CSR storage, vectorized execution. Primarily designed for analytical graph workloads.

### Full-Text Search
- FTS available as a loadable extension. Uses BM25 (Okapi BM25) scoring.
- Created via `CALL CREATE_FTS_INDEX(table, index_name, [props], stemmer := 'english')`.
- Queried via `CALL QUERY_FTS_INDEX(table, index_name, query)`.
- FTS indexes are on node table STRING properties only. Configurable stemmers (English, French, etc.) and stopwords.
- Extension must be explicitly loaded — not bundled by default.
- **SteleKit fit**: Would require mapping Block content to a node table and using the Cypher FTS extension. Works, but involves extra schema complexity.

### Graph / Hierarchy
- **Native strength.** Cypher patterns (`MATCH (b:Block)-[:CHILD_OF*]->(p:Page)`) support variable-length path traversal natively. Backlinks and reference resolution are graph operations — this is the ideal data model for them.
- CSR (Compressed Sparse Row) adjacency list index makes multi-hop traversals fast.
- Block hierarchy, page references, and backlinks map cleanly to a property graph (Blocks as nodes, parent-child and reference edges).

### OLTP Fitness
- **Weak.** Columnar storage is optimized for analytical, join-heavy queries, not OLTP point lookups or frequent single-row updates. Every block edit is a point write — LadybugDB's mutation path is not optimised for this pattern.
- Serializable ACID transactions, but single-writer by default (per the concurrency docs).
- Java binding (`com.kuzudb:kuzu-java`, inherited from Kuzu) available on Maven Central. No explicit Android documentation; would require native library cross-compilation.

### Notable Missing Features
- No SQLDelight support (completely different query language/API).
- Android not documented or tested; iOS supported via Swift binding.
- High migration cost from current SQLite/SQLDelight schema.
- Best for a graph-first rewrite, not an incremental swap.

---

## Limbo (Turso)

**Summary**: In-process SQLite rewrite in Rust by Turso (also known as Turso Core). Designed for async I/O, MVCC, and WASM-native embedding. Actively developed; as of Dec 2025 index (commit 730836f3) still work-in-progress.

### Full-Text Search
- Roadmap includes FTS, vector search, CDC, and MVCC. FTS not yet shipping as of Dec 2025.
- When FTS ships, expected to use either FTS5 virtual-table compatibility or Tantivy (same team as libsql).
- Extension API (`turso_ext`) already supports virtual tables; FTS5 extensions can be loaded.

### Graph / Hierarchy
- Full recursive CTE support (SQLite compatible).
- Extension API allows custom virtual table extensions — could theoretically add graph traversal.
- No native graph model.

### OLTP Fitness
- Purpose-built for OLTP embedded use. MVCC for snapshot isolation. Asynchronous I/O from ground up (`io_uring` on Linux, standard Unix fallback).
- Java binding via JNI (`bindings/java`) in repo. Android not explicitly documented (same constraints as libsql-android: native cross-compile required).
- **SQLDelight compatible** — SQLite file format and SQL dialect match means SQLDelight can generate queries against Limbo.
- Still pre-production: missing features noted by authors include incomplete SQL coverage, some pragma support gaps. Not production-ready for complex apps yet.

### Notable Missing Features
- FTS not yet shipped (roadmap only).
- Java/Android binding is in the repo but not battle-tested.
- Being a complete Rust rewrite, native binary size may be larger than libsql on Android.

---

## DuckDB

**Summary**: Columnar OLAP embedded database. Extremely fast for analytical aggregations, bulk scans, and joins over large datasets. Available as JDBC driver and via a community KMP project.

### Full-Text Search
- `fts` extension available (`INSTALL fts; LOAD fts; CREATE FTS INDEX ON table(col)`).
- BM25 scoring. Supports stemming and stopwords.
- Benchmarks show DuckDB FTS is competitive with PostgreSQL FTS for 100M document indexing — though that's the batch/analytics scenario, not interactive note-taking.

### Graph / Hierarchy
- Recursive CTEs supported. DuckDB v1.3+ introduces `USING KEY` recursive CTEs that provide faster convergence for graph-style iterative queries (avoids accumulating all intermediate results).
- No native graph model; relationships are foreign keys + recursive SQL.

### OLTP Fitness
- **Poor for SteleKit's workload.** Columnar storage is optimised for scanning many rows, not point lookups or frequent single-cell updates. A 2025 study found DuckDB INSERT latency is ~4× that of purpose-built OLTP stores; UPDATE latency is ~8×.
- Block editing (debounced 500ms writes per block) is exactly the high-frequency single-row update pattern DuckDB handles poorly.
- Android: Community `kmp-duckdb` KMP project exists on GitHub but is experimental. The official `duckdb_jdbc` JDBC driver does not support Android (JVM only, not Dalvik/ART).
- A hybrid architecture (DuckDB for analytics/search + SQLite for OLTP) is sometimes recommended but adds significant complexity.

### Notable Missing Features
- Not suitable as a primary embedded store for a write-heavy interactive note-taking app.
- No SQLDelight support.
- No official Android or iOS embedding.
- Analytics bonus: excellent for batch graph-load benchmarking, export, or statistics overlays.

---

## ClickHouse / chDB

**Summary**: ClickHouse is a columnar OLAP database designed for analytics at scale. `chDB` is its in-process Python-only embedding. Neither is a realistic candidate for a KMP mobile note-taking app.

### Full-Text Search
- No dedicated FTS extension. String searches use `LIKE`, `match()` (regex), or `hasToken()` (token-level). No inverted index or BM25 ranking.
- As of 2025, `ngram` bloom filters exist for approximate substring search, but this is not true FTS with relevance scoring.

### Graph / Hierarchy
- Very limited recursive CTE support (open GitHub issue as of 2026). Hierarchical queries require `arrayJoin`, `dictGet`, or complex workarounds.
- Not suitable for block tree traversal in SteleKit.

### OLTP Fitness
- **Not suitable.** ClickHouse is append-optimised. Standard SQL `UPDATE` shipped in v25.7 (July 2025) via "patch parts" architecture and promoted to Beta in v25.8, but this is a columnar analytics store and UPDATE performance is still orders of magnitude slower than SQLite for single-row edits.
- `chDB` (embedded variant) is Python-only. No JVM, no Android, no iOS, no WASM (outside Python WASM environments).
- ReplacingMergeTree/CoalescingMergeTree can model "current value" but are eventually-consistent and not transactional at the row level.

### Notable Missing Features
- No JVM binding; no mobile support.
- No FTS with relevance ranking.
- Extremely high migration cost for zero analytics benefit in a note-taking app.

---

## LevelDB / RocksDB

**Summary**: Key-value stores (LSM-tree architecture). RocksDB is the production-hardened successor to LevelDB, widely used as a storage engine inside other databases (Cassandra, TiKV, etc.). Both are low-level; no SQL layer.

### Full-Text Search
- None native. FTS requires a separate library (e.g., Lucene, Tantivy, MeiliSearch) running alongside and maintaining its own inverted index.
- MeiliSearch was originally prototyped on RocksDB as its backend before switching to LMDB — RocksDB can serve as a KV backend for a search library, but this means building or embedding a full FTS engine separately.

### Graph / Hierarchy
- No native graph or SQL layer. Hierarchical data modeled via key encoding conventions (e.g., `block:{page_uuid}:{order_index}:{uuid}` keys). Requires careful key design and range scans.
- Recursive parent traversal requires iterative lookups — no query optimizer, no CTE.
- Multi-hop backlink resolution requires building and maintaining an adjacency structure in application code.

### OLTP Fitness
- Excellent write throughput (LSM-tree absorbs bursts). RocksDB is used in mobile apps (the Android SQLite WAL uses similar principles).
- JVM: `org.rocksdb:rocksdbjni` on Maven Central. RocksJava is mature; used in production at Facebook, LinkedIn, etc.
- Android: RocksJava works on Android (native `.so` bundled in the JNI jar). Not ideal for APK size (~8MB native libs per ABI).
- Point lookup performance is good for known keys; range scans are sequential (suitable for ordered block lists).
- **Missing**: No SQL, no query planner, no joins, no FTS, no recursive queries — all must be implemented in application code.

### Notable Missing Features
- Extremely high application-layer complexity. You build a database on top of a storage engine.
- No SQLDelight compatibility (no SQL dialect at all).
- FTS, hierarchy traversal, and backlink indexing all require significant custom engineering.

---

## Graph Databases: Neo4j Embedded / Memgraph

**Summary**: Native graph databases with Cypher query language. Strong for relationship-heavy queries. Both require a server process or embedded JVM runtime — neither is lightweight enough for a mobile-first KMP app.

### Full-Text Search
- Neither has built-in FTS. Neo4j has full-text indexes (Lucene-backed) in Enterprise edition. Community edition has limited FTS capability.
- Memgraph relies on exact-match or regex-based text predicates.

### Graph / Hierarchy
- **Excellent.** Native graph traversal is the core value proposition. `MATCH (b:Block)-[:PARENT*]->(root)` with variable-length path expressions is natively optimised.
- Backlink resolution, parent traversal, and multi-hop reference queries are all first-class operations.

### OLTP Fitness
- Neo4j Community Embedded: Java-based, runs on JVM. Viable for desktop JVM. Too heavy for Android (~100MB+ JVM footprint).
- Memgraph: C++, memory-first. No Android support. Requires a daemon process.
- No mobile embedding path for either. Neo4j Mobile for Android v0.1 was an experimental 2019 project with no continued development.

### Notable Missing Features
- No Android or iOS support (deal-breaker for KMP).
- No SQLDelight compatibility.
- Neo4j Community has no production FTS without Enterprise license.
- Bolt protocol overhead for embedded usage on desktop (not truly in-process on Android).

---

## Summary of Key Findings

1. **libsql is the strongest fit for SteleKit's KMP use case.** It is the only engine with proven Android + JVM + iOS support, SQLite/SQLDelight compatibility, FTS (Tantivy experimental + FTS5 stable), recursive CTEs for block tree traversal, MVCC, and a single-file database format. The current branch already has a working JNI driver.

2. **LadybugDB (Kuzu fork) is the best alternative if the data model were graph-first.** It natively handles backlinks, page references, and block hierarchies via Cypher, and has BM25 FTS and a Java binding — but its columnar-OLTP mismatch and lack of documented Android support make it a high-risk replacement for block editing workloads.

3. **DuckDB, ClickHouse/chDB, and LevelDB/RocksDB are not viable primary stores for SteleKit.** DuckDB and ClickHouse lack the OLTP performance required for interactive block editing; chDB has no JVM/mobile binding; LevelDB/RocksDB require rebuilding SQL, FTS, and hierarchy traversal from scratch. Limbo is a compelling future option (same SQLite API, async, MVCC, WASM-native) but is not production-ready yet.
