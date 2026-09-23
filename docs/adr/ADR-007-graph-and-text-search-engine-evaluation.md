# ADR-007: Graph and Text Search Engine Evaluation

## Status

Accepted (current decision: libsql + recursive CTEs)
Deferred (future paths: SurrealDB adoption, fork-and-port)

## Context

Following the libsql JNI driver implementation (ADR-006, PR #171), the team evaluated whether
a purpose-built embedded database could provide richer graph traversal and full-text search
capabilities than SQLite/libsql — ideally out of the box — while remaining within a ≤1 GB
native memory budget across all four KMP platforms (JVM, Android, iOS, WASM).

### What "graph and text search" means for SteleKit

SteleKit's data model is a property graph:

| Element | Representation | Count (8k-page graph) |
|---|---|---|
| Pages | Nodes | ~8,000 |
| Blocks | Nodes | ~100,000 |
| Block references | Directed edges (block → page) | ~50,000 |
| Block hierarchy | Tree edges (parent → child) | ~100,000 |
| Properties | Node/edge attributes | ~20,000 |

Current SQL schema uses recursive CTEs for hierarchy traversal and JOIN chains for backlink
resolution — correct but not expressive for multi-hop queries or path finding. The ideal engine
provides graph traversal natively via a query language (Cypher, Datalog, SurrealQL, etc.).

For text search, the current FTS5 baseline provides BM25 ranking and `highlight()`. The
expanded requirement is: typo tolerance, phrase proximity, unified multi-field index, and
incremental indexing — all without schema changes when new block fields are added.

### WASM constraint

Kotlin/WASM compiles to **WasmGC** bytecode — a distinct WebAssembly proposal using managed
heap objects. Rust databases compile to **wasm32 linear memory**. These are binary-incompatible:
Kotlin cannot directly link a Rust `.wasm` module. Every cross-boundary call must traverse a
JavaScript interop layer (WasmGC → JS → wasm32), which is the same architecture already used
by `WasmOpfsSqlDriver` and is functional but requires a custom OPFS persistence adapter on the
Rust side (~500–1,000 LOC). No existing Rust embedded database ships a production-ready OPFS
backend.

## Engines Evaluated

### Round 1 (ADR-006) — 7 engines, all disqualified on platform coverage

LadybugDB, Limbo/Turso, DuckDB, ClickHouse/chDB, LevelDB/RocksDB, Realm — all lacked at least
one of: Android, iOS, or WASM platform support. See ADR-006 for per-engine rationale.

### Round 2 — Graph + FTS focused, 13 additional engines

**Graph-native databases**

| Engine | Graph | FTS | Android | iOS | WASM | Verdict |
|---|---|---|---|---|---|---|
| **CozoDB** | Datalog, `ShortestPathDijkstra` | Tantivy-based BM25 | AAR v0.7.2 | Swift package | Memory-only, no persistence | **Abandoned Dec 2023** (18+ months, no commits, pre-1.0) |
| **Kuzu** | Cypher, `*0..30`, path finding | Built-in | API 21+ confirmed | iOS 14+ confirmed | npm WASM build | **Archived Oct 2025** (no successor announced) |
| **SurrealDB** | `->` traversal, **no recursion** | BM25 + highlight | Unconfirmed embedded mode | No Kotlin/Native cinterop | Claimed but undocumented | Active (v3.1.5, Jun 2026); two disqualifying gaps |
| **IndraDB** | Property graph | None | Unknown | Unknown | Unknown | Experimental, incomplete SQL |
| **Oxigraph** | RDF/SPARQL | None | Unknown | Unknown | Yes (wasm32) | Semantic-web focus, wrong model |

**Lightweight storage engines** (would require custom query/FTS layer on top)

| Engine | Android | iOS | WASM | Binary (arm64) | ACID | Verdict |
|---|---|---|---|---|---|---|
| **redb** | Yes (no prebuilt artifacts) | Yes | Partial (no OPFS backend) | ~300–500 KB | Yes | Best pure-Rust option; WASM gap |
| **LMDB / heed** | Yes (Maven artifacts ready) | Yes | No (mmap) | ~60–120 KB | Yes | Smallest binary; WASM impossible |
| **sled** | Blocked (C zstd dep) | Blocked | No | — | Partial | Stalled rewrite; eliminated |
| **fjall** | Unverified (`File::lock` risk) | Unknown | No | ~2.2 MB | Yes | Too large; no mobile CI |
| **nebari** | Unknown | Unknown | Unknown | — | Yes | No commits since Oct 2023 |
| **Minimal SQLite** | Yes (production) | Yes (production) | Yes (sqlite-wasm) | ~350–500 KB | Yes | Zero migration risk; already in use |

**FTS libraries** (for custom layer pairing with a KV store)

| Library | Android | iOS | WASM | BM25 | Highlight | Verdict |
|---|---|---|---|---|---|---|
| **Tantivy** | Yes (via FFI) | Yes (via FFI) | **Hard blocker** (Rayon + mmap + crossbeam) | Yes | `SnippetGenerator` | Native targets only |
| **Custom BM25** (~700 LOC) | Yes | Yes | Yes | Yes | Custom | Viable WASM FTS fallback |
| **Stork** | No | No | Yes (WASM-native) | No (TF-IDF) | Partial | Abandoned Jan 2023 |
| **milli** | No | No | No (LMDB dep) | Yes | Yes | Archived Apr 2023 (merged into Meilisearch) |

## Decision

**Retain libsql as the primary database engine.** No evaluated engine provides native graph
queries, robust FTS, and production-ready artifacts for all four platforms simultaneously.

**Add recursive CTE queries to `SteleDatabase.sq`** to close the graph traversal gap without
adopting a new engine. SQLite recursive CTEs cover:

- Transitive subtrees (all descendant blocks of a page)
- 2-hop backlink expansion (pages linking to pages that link to X)
- N-hop neighborhood (all pages within N reference hops)
- Ancestor path lookup

What recursive CTEs cannot express well: shortest-path queries, PageRank-style ranking,
arbitrary pattern matching across the graph (subgraph isomorphism). These are not current
SteleKit requirements.

**Retain sqlite-wasm for the WASM target** on all future paths. The WasmGC/wasm32
incompatibility means any Rust engine requires a custom OPFS adapter + JS bridge layer
to reach Kotlin/WASM — worthwhile investment only when a capability gap exists that
sqlite-wasm genuinely cannot fill.

## Consequences

- PR #171 merges as-is.
- Graph query work proceeds via `SteleDatabase.sq` recursive CTEs — no new engine, no new
  platform targets, no new CI pipelines.
- The evaluation is complete; re-opening it requires one of the specific triggers below.

## Future Paths

### Path A — SurrealDB adoption (estimated 12–15 weeks when triggered)

SurrealDB is the only actively maintained engine with JVM embedded + native graph model +
BM25 FTS + vector search. Two gaps currently disqualify it:

1. **No recursive traversal**: `->` operator is fixed-depth only. Arbitrary-depth block
   hierarchies require application-level BFS or denormalized ancestor arrays — unacceptable
   for SteleKit's block tree model.
2. **No iOS Kotlin/Native cinterop**: no published `.xcframework` or cinterop header set.

**Trigger**: Adopt SurrealDB when both gaps close:
- `->*` wildcard (or equivalent unbounded depth) ships in a stable SurrealDB release, AND
- A published iOS Kotlin/Native cinterop path appears (upstream or custom).

Migration effort at that point: replace `LibsqlDriverCore` with a SurrealDB embedded adapter,
rewrite `SteleDatabase.sq` queries in SurrealQL (~50 queries, ~2 weeks). Schema migration via
`MigrationRunner` equivalent.

---

### Path B — Fork a graph engine and add WASM support (estimated 6–10 weeks)

The most interesting near-term research project: fork an abandoned-but-capable graph engine
and contribute the WASM persistence layer it currently lacks.

**Best fork candidate: CozoDB**

CozoDB (https://github.com/cozodb/cozo) was abandoned in December 2023 at version 0.7.2,
but represents the most complete design:
- Datalog query language with recursive rules (handles transitive hierarchies natively)
- Built-in graph algorithms: `ShortestPathBFS`, `ShortestPathDijkstra`, `BreadthFirstSearch`
- Tantivy-based BM25 FTS already integrated
- Android AAR and iOS Swift package already exist (v0.7.2)
- Apache 2.0 license — forkable with no license risk

The single missing piece: WASM persistence. The published WASM build is memory-only.

**Work required to fork and ship**:

| Component | Effort | Notes |
|---|---|---|
| Fork repo, update dependencies (Tantivy, tokio, etc.) | 1–2 weeks | Catch up on 18 months of ecosystem drift |
| OPFS `StorageBackend` for CozoDB's RocksDB storage layer | 2–3 weeks | Replace RocksDB with redb or custom OPFS-backed store for WASM; native targets keep RocksDB |
| `wasm-bindgen` JS bridge | 1 week | Expose `open`, `run`, `close` to JS |
| Kotlin `@JsModule` declarations + Worker setup | 1 week | Mirror `WasmOpfsSqlDriver` pattern |
| SQLDelight driver adapter (or replace SQLDelight) | 2–3 weeks | CozoDB speaks Datalog, not SQL — requires a new query API in Kotlin, bypassing SQLDelight |

**Total**: 7–10 weeks. The main cost is the SQLDelight replacement: CozoDB's Datalog API
cannot be wrapped behind `SqlDriver` — it would require a new repository layer API alongside
or replacing the current SQL-backed one.

**Alternative fork: Kuzu** (archived Oct 2025)

Kuzu had the best KMP story before archival: Cypher, Java bundled-natives JAR, Android API 21+,
iOS 14+, WASM via npm. The archival reason is unknown — it may have been absorbed into a
commercial product. Worth monitoring for a community fork. If an active fork emerges, it is
preferable to CozoDB for SteleKit because Cypher's `MATCH` syntax is closer to SQL and the
WASM target already had a working build (would need OPFS adapter only).

**Trigger for Path B**: A concrete user-visible requirement that libsql + recursive CTEs
cannot express — e.g., "find the shortest path of page references between these two pages"
or "rank pages by PageRank centrality." Not currently in the roadmap.

---

### Path C — WASM bridge as a standalone contribution (estimated 2–4 weeks)

Independent of which engine wins on native targets: contributing an OPFS persistence backend
to an existing Rust KV engine (most likely `redb`) would unblock all Rust database engines
for Kotlin/WASM. This is a public-good contribution that benefits the broader Rust/WASM
ecosystem and reduces SteleKit's lock-in to sqlite-wasm.

See `project_plans/embedded-db-research/research/wasm-constraints.md` for the full bridge
architecture and component breakdown.

**Trigger**: Begin when a native Rust engine has been validated on JVM/Android/iOS and the
team wants consistent storage semantics across all four platforms.

## Research Artifacts

Full research written to `project_plans/embedded-db-research/` (committed alongside this ADR):

| File | Contents |
|---|---|
| `requirements.md` | Evaluation scope, data model, platform constraints |
| `research/storage-engines.md` | redb, LMDB, sled, fjall, nebari, minimal SQLite — cross-platform matrix |
| `research/fts-options.md` | Tantivy, custom BM25, stork, milli — WASM/mobile compilability |
| `research/wasm-constraints.md` | WasmGC/wasm32 gap, OPFS constraints, WASM bridge architecture |
| `research/custom-layer-architecture.md` | 3 build approaches, LOC estimates, ROI assessment |
| `research/graph-and-text-search.md` | CozoDB, SurrealDB, Kuzu deep-dives + query language comparison |
| `research/graph-db-supplement.md` | Scored matrix, Kotlin graph API examples |
| `research/limbo-watchlist.md` | Turso/Limbo adoption blockers and reassessment checklist |
| `final-report.md` | Top-2 recommendations, decision triggers, full scoring matrix |
