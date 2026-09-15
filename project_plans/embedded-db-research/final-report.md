# Embedded DB Research — Final Report

**Date**: 2026-06-19  
**Scope**: Storage engines, FTS, WASM constraints, custom architecture, graph + text search  
**Sources**: `research/storage-engines.md`, `research/fts-options.md`,
`research/wasm-constraints.md`, `research/custom-layer-architecture.md`,
`research/graph-db-supplement.md`

---

## Executive Summary

After evaluating 15+ engines across five research streams, the answer to "what solution provides
robust graph + text search out of the box on all 4 platforms" is: **nothing available today**.

The closest candidates and why they fall short:

| Candidate | Why it falls short |
|---|---|
| **CozoDB** | Stalled (no release since Dec 2023), WASM is in-memory only, requires full Datalog rewrite |
| **SurrealDB** | No Kotlin/Native embedded path for iOS today; BSL-1.1 main engine |
| **Kuzu** | Archived October 2025 — discontinued |
| **redb + Tantivy custom** | WASM persistence unsolved, 18–27 weeks to build |

**Recommendation**: Stay on libsql. Add SQL-based graph query enhancements (weeks, not months).
Track SurrealDB as the 2027 reassessment candidate if graph traversal beyond 2-hop is required.

---

## 1. Scored Comparison Matrix

Scale: 3 = excellent / no action needed, 2 = gap but solvable, 1 = partial/blocked, 0 = hard fail

| Criterion (weight) | libsql (current) | CozoDB | SurrealDB | redb+Tantivy (custom A) | GlueSQL+redb (custom C) |
|---|:---:|:---:|:---:|:---:|:---:|
| **JVM embedded** (5) | 3 | 3 | 3 | 2 | 2 |
| **Android embedded** (5) | 3 | 3 | 3 | 2 | 2 |
| **iOS (K/N embedded)** (5) | 3 | 3 | 1 | 2 | 2 |
| **WASM persistent** (5) | 3 | 0 | 1 | 0 | 0 |
| **FTS / BM25 + highlight** (4) | 3 | 2 | 2 | 2 | 0 |
| **Graph traversal** (4) | 1 | 3 | 3 | 1 | 1 |
| **Vector search** (2) | 0 | 3 | 3 | 1 | 0 |
| **SQLDelight compat** (3) | 3 | 0 | 0 | 0 | 1 |
| **Maintenance status** (4) | 3 | 0 | 3 | 3 | 3 |
| **Solo migration cost** (3) | 3 | 0 | 1 | 0 | 1 |
| **License** (2) | 3 | 2 | 2 | 3 | 3 |
| **Memory < 1 GB** (3) | 3 | 3 | 2 | 3 | 3 |
| **Binary size (Android APK)** (2) | 2 | 2 | 2 | 3 | 3 |
| **Weighted total** | **138** | **78** | **98** | **72** | **67** |

Notes on scoring:
- libsql: vector search is 0 (not supported); graph is 1 (recursive CTEs handle 2-hop but not path algorithms)
- CozoDB: maintenance 0 due to 2.5-year release gap; WASM persistent 0 (in-memory only)
- SurrealDB: iOS 1 (requires custom cinterop build); WASM 1 (JS SDK exists but needs K/WASM bridge)
- Custom approaches: solo migration cost 0 = 18–27 weeks of solo work

---

## 2. Top-2 Recommendations

### Recommendation 1 (immediate): Stay on libsql + enhance SQL graph queries

**What to do**: Keep the `stelekit-libsql` branch as-is. Add targeted SQL enhancements for graph
traversal in `SteleDatabase.sq`.

**Graph traversal within SQL** — what's achievable:

| Query type | SQL approach | Estimated LOC | Performance |
|---|---|---|---|
| Backlinks (1-hop) | `SELECT FROM block_references WHERE target_page_name = ?` | Already exists | <1 ms |
| 2-hop connections | Recursive CTE or two-step JOIN | ~20 LOC per query | 5–20 ms at 50K refs |
| Transitive subtree | Recursive CTE on parent_block_uuid | ~25 LOC | 1–10 ms |
| "Pages linking to X AND Y" | Two subqueries + INTERSECT | ~30 LOC | 10–50 ms |
| N-hop neighborhood (N≤5) | Recursive CTE with depth limit | ~40 LOC | Scales quadratically |

What SQL **cannot** do efficiently:
- Shortest path between two pages — O(N) in recursive CTEs, exponential at scale
- PageRank — requires multiple iteration passes; feasible in Kotlin, not in SQL
- Community detection — not realistic in SQL

**Kotlin call site for graph queries** (what it looks like today, no engine change):

```kotlin
// Backlinks — already works
repository.getBacklinks(pageUuid).collect { ... }

// 2-hop — add a SQL query + repository method
repository.getTwoHopPages(pageUuid)
    .collect { pages -> /* show in "linked mentions" sidebar */ }

// Recursive subtree (already works via selectBlockHierarchy)
repository.getBlockSubtree(rootUuid).collect { ... }

// N-hop neighborhood — new SQL query + Kotlin batching loop
suspend fun getNeighborhood(pageUuid: PageUuid, hops: Int): List<Page> {
    var frontier = setOf(pageUuid)
    val visited = mutableSetOf(pageUuid)
    repeat(hops) {
        frontier = repository.getLinkedPages(frontier)
            .filterNot { it in visited }.toSet()
        visited += frontier
    }
    return visited.map { repository.getPage(it) }
}
```

**Estimated effort**: 2–4 weeks to add 2-hop queries, neighborhood expansion, and an optional
"connected pages" view in the UI. Zero platform risk. Zero migration cost.

**When this recommendation is wrong**: If you need shortest-path queries, PageRank-style ranking
of pages, or graph algorithms over the full reference graph — those cannot be done efficiently in
SQL and would require a graph engine layer.

---

### Recommendation 2 (2027): Evaluate SurrealDB for JVM + Android once iOS story matures

**Status**: Watch, not adopt. Target reassessment: Q1 2027.

**Why SurrealDB**:
- Actively maintained (v3.1.5, last release Jun 2026)
- Java SDK v2.1.1 (Jun 10, 2026) on Maven Central with explicit Android ARM64 + x86_64 support
- Embedded in-process mode (file-backed via `surrealkv://`)
- SurrealQL = SQL-like with native graph: `SELECT name FROM page:x->references->page`
- Built-in BM25 FTS, HNSW vector search — out of the box
- No Datalog learning curve

**What's blocked today**:
1. **iOS**: No published Kotlin/Native cinterop for SurrealDB. The Swift SDK connects remotely.
   To fix: build `libsurrealdb.a` for `aarch64-apple-ios` from Rust source, write a `.def`
   cinterop file, publish an `iosMain` `actual` implementation. Effort: ~2–3 weeks.
2. **Kotlin/WASM**: No native Kotlin/WASM binding. Would use the same JS worker bridge pattern
   as the current sqlite-wasm implementation. Effort: ~1–2 weeks.
3. **SQLDelight**: SurrealQL is not SQL — SQLDelight cannot generate SurrealQL queries.
   All 130 named queries would need to be rewritten as SurrealQL strings in hand-written
   repositories. Effort: ~6–8 weeks.
4. **Migration**: Converting the existing SQLite `.db` files to SurrealDB's surrealkv format.
   Effort: ~2 weeks.

**Total migration estimate**: ~12–15 weeks (one person).

**What changes at Kotlin call sites** (SurrealQL queries via a custom driver):

```kotlin
// Backlinks — native graph traversal
val backlinks: List<Page> = db.query(
    "SELECT name FROM page WHERE <-references<-block<-page.uuid = \$uuid",
    mapOf("uuid" to pageUuid)
).toPageList()

// 2-hop
val twoHop: List<Page> = db.query(
    "SELECT name FROM page WHERE <-references<-block<-page<-references<-block<-page.uuid = \$uuid",
    mapOf("uuid" to pageUuid)
).toPageList()

// FTS search
val results: List<Block> = db.query(
    "SELECT *, search::score(1) AS score FROM blocks " +
    "WHERE content @@ \$term ORDER BY score DESC LIMIT 20",
    mapOf("term" to searchQuery)
).toBlockList()

// Shortest path
val path: List<Page> = db.query(
    "SELECT ->references->(block->page)+ as path FROM page:\$start WHERE path[-1] = page:\$end",
    mapOf("start" to startUuid, "end" to endUuid)
).toPagePathList()
```

**Decision trigger to start this migration**: Either of —
- Graph query requirements grow beyond 2-hop (shortest path, PageRank-style ranking)
- SurrealDB publishes an official iOS Kotlin/Native cinterop package
- FTS requirements grow beyond FTS5's capability (typo tolerance, semantic search)

---

## 3. Custom Layer vs. libsql — Honest Assessment

The four research streams converge on the same answer: **do not build a custom layer now**.

### The math

| Option | Solo build time | Ongoing maintenance | What you gain |
|---|---|---|---|
| Custom Approach A (redb typed) | 18–27 weeks | Own it forever | ~100–150 MB less peak memory (estimated) |
| Custom Approach B (SQL-on-KV) | 20–31 weeks | Very high (SQL compat + engine) | Same as A |
| Custom Approach C (GlueSQL) | 10–14 weeks | High (dialect gap) | No FTS (hard gap) |
| SurrealDB migration | 12–15 weeks | Low (upstream) | Graph + FTS + vector out of the box |
| libsql enhancements | 2–4 weeks | Zero | 2-hop graph, improved FTS queries |

The ~100–150 MB memory saving from a custom layer is a projection, not a measured value.
libsql's current peak on an 8K-page graph is estimated at 150–300 MB — within the 1 GB budget.
No OOM crashes have been reported. The `custom-layer-architecture.md` research identifies this
correctly: "measure first."

### What libsql provides that is genuinely hard to replicate

1. **FTS5 with BM25, `highlight()`, porter tokenizer** — 15 years of battle-hardening
2. **`ON DELETE CASCADE`** — one SQL annotation replaces a cascade-delete Rust pass
3. **`INSERT OR REPLACE` / `INSERT OR IGNORE`** — used throughout `SteleDatabase.sq`
4. **`PRAGMA wal_checkpoint`** — clean post-import compaction in one call
5. **SQLDelight compile-time query validation** — catches query bugs before they ship
6. **`COLLATE NOCASE`** on page name index — one annotation vs a custom case-fold layer
7. **All four platforms already working** — zero maintenance cost

### Conclusion on custom layers

Build one only if a measured real problem appears that libsql cannot solve within budget.
The combination of "graph traversal beyond 2-hop" + "typo-tolerant FTS" + "vector search"
is the clearest justification — and SurrealDB is a better answer than building from scratch
because SurrealDB has already built those features in Rust and ships to JVM + Android today.

---

## 4. Decision Triggers

### Trigger A — Build graph layer (in SQL): satisfied by ≥1 user request for 2+ hop queries
- Add `selectTwoHopPagesByUuid`, `selectPageNeighborhood(uuid, hops)` to `SteleDatabase.sq`
- Implement in `BlockReferenceRepository`
- Timeline: 2–4 weeks; risk: zero

### Trigger B — Evaluate SurrealDB: ALL of the following
1. At least one user-reported or product-requested feature requires shortest-path queries
   OR PageRank-style ranking of pages
2. SurrealDB stable iOS embedding path exists (either official cinterop or community AAR
   targeting `aarch64-apple-ios`)
3. SurrealDB v3.x has remained stable for 6+ months without breaking API changes
4. The solo developer has 12–15 weeks available for the migration (no competing milestones)

### Trigger C — Custom Rust layer: ALL of the following (very high bar)
1. OOM crashes confirmed on Android devices at < 300 MB (measured, not estimated)
2. SQLite page cache tuning (`PRAGMA cache_size`) does not resolve the OOM
3. libsql upstream shows signs of abandonment or license change
4. The team has grown to ≥2 developers who can share the maintenance burden

### Trigger D — Tantivy FTS: only if FTS5 has a confirmed deficiency
- Missing: typo tolerance (FTS5 cannot do this)
- Missing: semantic/vector search (FTS5 cannot do this)
- Workaround for typo tolerance: normalize query terms before passing to FTS5 (`fts5vocab`)
- Workaround for vector: add a separate embedding index (sqlite-vec extension, already libsql-compatible)

---

## 5. Graph Query API — What It Would Look Like in Kotlin

### Option A: SQL recursive CTEs in libsql (immediate, no migration)

```kotlin
// In SteleDatabase.sq — add these queries
selectTwoHopPages:
WITH direct AS (
    SELECT DISTINCT r2.target_page_name
    FROM block_references r1
    JOIN block_references r2 ON r1.target_page_name = r2.source_block_uuid
    WHERE r1.source_block_uuid IN (
        SELECT uuid FROM blocks WHERE page_uuid = :pageUuid
    )
)
SELECT p.uuid, p.name FROM pages p
JOIN direct d ON p.name = d.target_page_name
WHERE p.uuid != :pageUuid;

selectNeighborhoodPages:
WITH RECURSIVE reach(page_name, depth) AS (
    SELECT r.target_page_name, 1
    FROM block_references r
    JOIN blocks b ON r.source_block_uuid = b.uuid
    WHERE b.page_uuid = :rootPageUuid
    UNION
    SELECT r.target_page_name, reach.depth + 1
    FROM block_references r
    JOIN blocks b ON r.source_block_uuid = b.uuid
    JOIN pages p ON b.page_uuid = p.uuid
    JOIN reach ON p.name = reach.page_name
    WHERE reach.depth < :maxHops
)
SELECT DISTINCT p.uuid, p.name FROM pages p
JOIN reach ON p.name = reach.page_name;
```

```kotlin
// Kotlin call site — repository interface stays the same
interface BlockReferenceRepository {
    fun getBacklinks(pageUuid: PageUuid): Flow<Either<DomainError, List<Page>>>
    fun getTwoHopPages(pageUuid: PageUuid): Flow<Either<DomainError, List<Page>>>
    fun getNeighborhood(pageUuid: PageUuid, maxHops: Int): Flow<Either<DomainError, List<Page>>>
}
```

### Option B: SurrealDB graph queries (if migrated)

```kotlin
// SurrealQL in a hand-written repository (no SQLDelight)
class SurrealBlockReferenceRepository(private val db: SurrealDriver) : BlockReferenceRepository {
    override fun getTwoHopPages(pageUuid: PageUuid) = flow {
        val result = db.query(
            """
            SELECT id, name FROM page
            WHERE ->references->(block<-page)->references->(block<-page).id = type::thing('page', $uuid)
            """.trimIndent(),
            mapOf("uuid" to pageUuid.value)
        )
        emit(result.toPageList().right())
    }.catchDbError()

    override fun getShortestPath(fromUuid: PageUuid, toUuid: PageUuid) = flow {
        // Shortest path — impossible in SQL, 1 query in SurrealQL
        val result = db.query(
            "SELECT ->references->{block<-page}+ as path FROM page:\$from WHERE path[-1] = page:\$to",
            mapOf("from" to fromUuid.value, "to" to toUuid.value)
        )
        emit(result.toPath().right())
    }.catchDbError()
}
```

---

## WASM Conclusion (consolidated)

**WASM target recommendation: retain sqlite-wasm regardless of what happens on native.**

Evidence:
- No Rust embedded database has a working OPFS persistence backend (confirmed across all evaluated)
- sqlite-wasm is already working in SteleKit with SAH Pool VFS + Worker architecture
- sqlite-wasm memory profile: 20–40 MB steady-state, within mobile browser budgets
- sqlite-wasm FTS5 is confirmed working (WasmBenchmarkTest in the codebase)
- Any custom Rust engine on WASM adds 2–4 weeks of JS bridge work + no persistence improvement
- The WASM Component Model (2025-2026) may eventually allow direct Kotlin/WASM → Rust wasm32
  linking without a JS bridge — check browser support before re-evaluating

---

## Storage Engine Shortlist (if building a custom native layer in the future)

From `storage-engines.md`:

1. **redb** — best cross-platform Rust KV store; WASI-tested; full ACID; ~300–500 KB binary;
   no mmap. **Use for JVM/Android/iOS if building custom.** WASM needs separate storage.
2. **LMDB (via lmdbjava)** — published Maven Android arm64 artifacts; zero JNI work;
   smallest binary (~60–120 KB). **Use for JVM/Android only** (WASM architectural blocker).
3. **SQLite minimal compile** — stays fully compatible with existing codebase;
   reduces Android binary 1.7 MB → ~350–500 KB; FTS5 omit trades size for loss of search.

## FTS Shortlist

From `fts-options.md`:

- **Tantivy** — production-grade, JVM/Android/iOS confirmed, 15.4K stars, MIT. Use for
  native platforms. WASM-blocked (rayon threads).
- **Custom BM25 (~700 LOC)** — pure Rust, all 4 platforms, WASM-compatible; no persistence
  out of the box. Use for WASM if FTS5 is insufficient and custom engine is adopted.
- **SQLite FTS5** — best current option, already working, battle-tested. Retain unless
  typo tolerance or vector search is specifically required.

---

## Research Files

| File | Contents |
|---|---|
| `research/storage-engines.md` | 11 engines: redb, LMDB, heed, sled, fjall, persy, SQLite minimal, nebari, native_db, surrealKV, agatedb |
| `research/fts-options.md` | Tantivy, Stork, milli, probly-search, bm25 crate, custom BM25 |
| `research/wasm-constraints.md` | Threading, OPFS, memory, Kotlin/WASM binary compatibility |
| `research/custom-layer-architecture.md` | Approach A (typed Rust), B (SQL-on-KV), C (GlueSQL); LOC estimates; ROI |
| `research/graph-db-supplement.md` | CozoDB, SurrealDB, Kuzu platform coverage and graph API |
