# Graph + Multi-Model Database Research Supplement

_Research date: 2026-06-19. Covers three candidates from the requirements.md §Graph Search
Requirements: CozoDB, SurrealDB, Kuzu. Supplements `storage-engines.md` and
`custom-layer-architecture.md`._

---

## CozoDB

**GitHub**: https://github.com/cozodb/cozo  
**License**: MPL-2.0 (file-level copyleft — modifications to CozoDB source files must be open-sourced)  
**Stars**: ~4,100  
**Latest release**: v0.7.6 (December 11, 2023)  
**Language**: Rust

### Platform coverage

CozoDB is the only evaluated engine that ships **official pre-built artifacts for all four
required targets simultaneously**:

| Platform | Artifact | Coordinate |
|---|---|---|
| JVM Desktop | Maven Central | `io.github.cozodb:cozo_java:0.7.6` |
| Android | Maven Central | `io.github.cozodb:cozo_android:0.7.2` (ARM64, ARMv7, x86_64, x86) |
| iOS | Swift Package / CocoaPod | `cozo-lib-swift` (iOS ARM64, simulators, Mac ARM64/x86_64) |
| Browser WASM | npm | `cozo-lib-wasm` |

Android storage backends available: M (in-memory) and Q (SQLite). RocksDB is not available
on Android. WASM storage backend: M (in-memory) only — no OPFS or persistent storage on WASM.

### Query language: Datalog (CozoScript)

CozoDB uses Datalog, not SQL. This is a declarative logic programming language — queries are
written as rules rather than SELECT statements. Example:

```cozo
# Backlinks: find all pages referencing 'target_page'
?[page_name] :=
    *block_references[source_block_uuid, target_page_name],
    target_page_name = "target_page",
    *pages[source_block_uuid, page_name, ...]

# 2-hop connections
?[page_a, page_b] :=
    *block_references[_, a_name],
    *block_references[a_name, b_name],
    page_a = a_name, page_b = b_name
```

SQLDelight is **completely incompatible** with CozoDB — SQLDelight generates SQL strings and
requires a `SqlDriver` accepting SQL. CozoDB has no SQL interface. All 130 named queries in
`SteleDatabase.sq` would need to be rewritten in Datalog.

### Graph capabilities

CozoDB provides native graph algorithms via its `algo` module:
- Shortest path (Dijkstra, Yen's K-shortest)
- PageRank
- Connected components, betweenness centrality
- Breadth/depth-first traversal
- HNSW vector search

Two-hop and multi-hop traversal is natural in Datalog (recursive rules).

### Full-text search

FTS was added in v0.7 using Tantivy as the underlying library. BM25 ranking and basic search
are supported. The API is via a `*text` column type and `~` operator.

### Memory profile

Official claim: 50 MB peak for 1.6M rows (OLTP benchmark). The in-memory backend (M) holds
everything in Rust heap — no separate page cache. The SQLite backend (Q) on Android delegates
to SQLite's page cache.

### Critical concern: maintenance status

**Last release: December 11, 2023 — 2.5 years ago as of June 2026.** The GitHub repository
has no commits in 2024, 2025, or 2026 beyond maintenance noise. Open issues include unanswered
questions about Android compatibility and WASM persistence. The project appears stalled.

**Verdict**: Architecturally the closest off-the-shelf solution for graph + FTS on all 4
platforms. Disqualified in practice by:
1. 2.5-year release gap — do not build a production note-taking app on a stalled engine
2. Datalog query language requires full query rewrite — equivalent cost to Approach A
3. MPL-2.0 license requires open-sourcing any CozoDB file modifications
4. WASM is in-memory only (no persistence)

**When to revisit**: If the project resumes with a 2026 release and WASM OPFS persistence,
re-evaluate as a graph query engine for the JVM/Android/iOS targets with sqlite-wasm retained
for WASM. At that point the per-platform split architecture makes CozoDB viable as a data layer
replacing the SQL graph queries.

---

## SurrealDB

**GitHub**: https://github.com/surrealdb/surrealdb  
**License**: BSL-1.1 (→ Apache 2.0 after 4 years); Java SDK is Apache-2.0  
**Stars**: ~32,400  
**Latest release**: v3.1.5 (as of Jun 2026, actively maintained)  
**Language**: Rust

### Platform coverage

| Platform | Artifact | Status |
|---|---|---|
| JVM Desktop | Maven Central `com.surrealdb:surrealdb:2.1.1` (Jun 10, 2026) | **Confirmed** — JNI embedded mode |
| Android | Same artifact — explicitly lists ARM64, x86_64 architectures | **Confirmed** — JNI embedded mode |
| iOS (Kotlin/Native) | Swift SDK (`surrealdb.swift`) — remote connection only | **Gap** — no K/N embedded path |
| Kotlin/WASM | JavaScript SDK (`surrealdb.js` + WASM engine) — JS interop only | **Gap** — no Kotlin/WASM native binding |

Android architectures explicitly listed in the Java SDK README: Linux ARM aarch64, Linux x86_64,
Windows x86_64, macOS ARM aarch64, macOS x86_64, Android ARM aarch64, Android x86_64.

### Query language: SurrealQL

SurrealQL is SQL-like with graph extensions:

```surql
-- Backlinks: pages linking to a target
SELECT page_name FROM blocks WHERE ->references->pages.name = 'target_page';

-- Create a graph edge
RELATE block:uuid->references->page:uuid;

-- 2-hop traversal
SELECT name FROM page:start->references->block->references->page;

-- Full-text search (FTS)
SELECT * FROM blocks WHERE content @@ 'search term';
```

Graph edges are first-class via `RELATE` and `->` traversal syntax. No Datalog learning curve.

### Features

- Graph traversal: native (RELATE + `->`)
- Full-text search: built-in (BM25, `@@` operator)
- Vector search: built-in (HNSW, `<|>` operator)
- Time-series: built-in
- Documents + relational: unified
- ACID transactions: yes
- MVCC: yes

### Embedded mode

```java
try (final Surreal driver = new Surreal()) {
    driver.connect("memory");           // in-process in-memory
    driver.connect("surrealkv://path"); // in-process file-backed
    driver.connect("ws://localhost:8000"); // remote connection
}
```

The Java SDK uses JNI to call native Rust code in-process. No server process required.

### iOS gap — no Kotlin/Native embedded path

The Swift SDK connects to a SurrealDB process via WebSocket or HTTP — it is not an embedded
in-process library for iOS apps. For SteleKit's iOS target (Kotlin/Native), there is no
published cinterop `.a` that embeds SurrealDB in-process.

**Workaround paths** (all require work):
1. Build `libsurrealdb.a` for `aarch64-apple-ios` from Rust source + write Kotlin/Native cinterop
   `.def` file. Feasible but ~2–3 weeks and requires iOS cross-compilation setup.
2. Retain system SQLite on iOS (same as current architecture). SurrealDB only on JVM/Android.
3. Run a local SurrealDB process on the device and connect via localhost. Non-starter for mobile.

### WASM gap — JS SDK, not Kotlin/WASM

SurrealDB publishes a WASM build via the JavaScript SDK (`@surrealdb/wasm`), but this is a
JS/TypeScript library, not a Kotlin/WASM-importable module. Kotlin/WASM uses WasmGC and can
interop with JS via `@JsModule` — the same pattern as the current sqlite-wasm worker bridge.
This is viable but requires JS glue code (~200–400 LOC), the same as any other Rust WASM engine.

### License nuance

The main `surrealdb` Rust repo is **BSL-1.1** (Business Source License) — this restricts
production use above a threshold without a commercial license for 4 years from each release,
after which it converts to Apache 2.0. However, each year's release converts 4 years later.
The Java SDK (`surrealdb.java`) is separately Apache-2.0.

For SteleKit (a personal note-taking app, not a database service), the BSL-1.1 restriction
("you may not use the Licensed Work to provide a SurrealDB service to third parties" — the exact
restriction is about competing as a managed database service) does not apply. App distribution
that embeds SurrealDB is permitted.

### Verdict

The most actively maintained multi-model engine with confirmed JVM+Android embedded support and
a SQL-like query language. The iOS gap is a real blocker for SteleKit's 4-platform requirement —
it could be solved with ~2–3 weeks of Rust cinterop work, but that work is not off-the-shelf.

---

## Kuzu

**GitHub**: https://github.com/kuzudb/kuzu  
**License**: MIT  
**Stars**: ~4,000  
**Status**: **ARCHIVED October 10, 2025**

The kuzudb/kuzu repository was archived on October 10, 2025. The project GitHub banner reads
"Kuzu is working on something new!" — they are rebuilding under a different name/direction.
The last release (v0.11.3) was also October 10, 2025.

**This project is discontinued. Do not adopt.** No security patches, no bug fixes, no future
releases. The archive is read-only.

Note: LadybugDB was previously identified as a community fork of KuzuDB. LadybugDB's upstream
is now an archived project, which amplifies the risk of adopting LadybugDB.

---

## Comparison Matrix: Graph + FTS Engines

| Criterion | libsql (current) | CozoDB | SurrealDB | Custom (redb+Tantivy) |
|---|---|---|---|---|
| **JVM embedded** | Yes | Yes (Maven) | Yes (Maven) | Yes (build req.) |
| **Android embedded** | Yes | Yes (Maven) | Yes (Maven) | Yes (build req.) |
| **iOS (K/N embedded)** | Yes (system SQLite) | Yes (Swift pkg) | **Gap** (build req.) | Yes (build req.) |
| **WASM persistent** | Yes (sqlite-wasm) | **No** (memory only) | **Gap** (JS only) | **No** (not solved) |
| **Graph traversal** | Recursive CTE (limited) | Datalog (native) | SurrealQL `->` (native) | Build yourself |
| **FTS / BM25** | Yes (FTS5) | Yes (Tantivy-based) | Yes (built-in) | Tantivy (JVM/Android/iOS) |
| **Vector / HNSW** | No | Yes (built-in) | Yes (built-in) | Tantivy (partial) |
| **SQLDelight compat** | Yes (native) | **No** (Datalog) | **No** (SurrealQL) | No (Approach A) / Yes (B) |
| **Maintenance status** | Active (Turso) | **Stalled** (Dec 2023) | Active (Jun 2026) | You own it |
| **License** | MIT | MPL-2.0 | BSL-1.1 / Apache (SDK) | Per component |
| **Migration cost** | Zero (current) | Full rewrite (Datalog) | ~12 wks (iOS gap extra) | 18–27 wks |
| **Solo risk** | Low | Very high (stalled) | Medium (iOS gap) | High |

---

## Key Finding

No off-the-shelf engine delivers graph + FTS + all 4 platforms simultaneously today.

- **CozoDB** technically covers all 4 platforms but is stalled (2.5 years without a release)
  and requires a full Datalog query rewrite.
- **SurrealDB** is actively maintained and covers JVM + Android + "WASM via JS" but has a real
  iOS (Kotlin/Native embedded) gap requiring custom cinterop work.
- **Kuzu** is archived — disqualified.
- **libsql** covers all 4 platforms today, has excellent FTS5, but has no native graph query
  language (recursive CTEs handle 2-hop but not shortest path or PageRank).

The path to "robust graph + text search" that covers all 4 platforms either requires:
(a) staying on libsql and building graph queries in SQL (recursive CTEs + materialized
    block_references indexes), and accepting the ceiling of what SQL graph queries can do; or
(b) adopting SurrealDB on JVM+Android (where it ships today) and solving the iOS gap (~2–3 wks
    Rust cinterop), while retaining sqlite-wasm for WASM; or
(c) building a custom layer (Approach A/B, 18–27 weeks) with full graph index control.
