# Architecture Pitfalls: Search Improvement in KMP/SQLite

## Research scope
Common failure modes when extending search in SteleKit's architecture:
SQLDelight + external-content FTS5 + `DatabaseWriteActor` + KMP coroutine dispatch +
multi-platform ranking code.

---

## 1. N+1 Query Problems in FTS + Metadata Join Patterns

### Current N+1 in `searchWithFilters`
`SqlDelightSearchRepository.searchWithFilters` calls `selectNeighbourPageUuids` once per
search request (not per result), so it is not technically an N+1. However:

```kotlin
// Line 268 — one query for all neighbour UUIDs of the *current page*
val neighbourPageUuids = searchRequest.pageUuid
    ?.let { queries.selectNeighbourPageUuids(it).executeAsList().toSet() }
    ?: emptySet()
```

The `selectNeighbourPageUuids` query does a 3-table JOIN with `block_references`, `blocks`
(twice), with a UNION. On a dense graph (thousands of block references per page, e.g. MOC
pages), this query can be slow — especially since there is no index on `block_references`
by `from_block_uuid`.

**Risk with visit tracking**: Adding visit data will introduce a new lookup per search.
The naive approach — `SELECT last_visited_at FROM page_visits WHERE page_uuid = ?` inside
`buildRankedList` — is a textbook N+1: one query per result row (up to 50 queries per search).

**Fix**: Query visit data for all result UUIDs in a single batched query:
```sql
SELECT page_uuid, last_visited_at FROM page_visits
WHERE page_uuid IN (?, ?, ?, ...);  -- one query for all result UUIDs
```
Then materialise into a `Map<String, Long>` before the ranking loop. SQLDelight supports
`IN` clauses via `selectIn(uuids)` or raw SQL fragments.

### Ghost result N+1
If FTS5 returns a ghost row (stale index, deleted block), the JOIN to `blocks b ON b.id = bm.rowid`
produces no row (INNER JOIN), so the ghost row is silently dropped. This is correct behaviour —
the current query is safe. However, it means the result count after the JOIN may be fewer than
LIMIT. If top-N search must guarantee exactly LIMIT results, a larger FTS LIMIT + post-filter
is required. The current code does not guarantee this.

---

## 2. Thread Safety: SQLDelight + Coroutine Dispatcher Mismatches

### Established rule (from CLAUDE.md)
All FTS reads use `.flowOn(PlatformDispatcher.DB)` and all writes use
`withContext(PlatformDispatcher.DB)`. This is correctly applied in `SqlDelightSearchRepository`.

### Pitfall: `selectNeighbourPageUuids` outside `flowOn`
In `searchWithFilters`, the `selectNeighbourPageUuids` call is inside the `flow { }` block
which ends with `.flowOn(PlatformDispatcher.DB)`. This is correct — the entire flow body
executes on DB dispatcher. No issue here.

### Pitfall: visit recording from wrong dispatcher
The proposed `recordPageVisit` call will originate from `StelekitViewModel.navigateTo()`
which runs on `Dispatchers.Default` (or `Main`). If `recordPageVisit` is a `suspend fun`
in `SearchRepository`, it must wrap its SQL with `withContext(PlatformDispatcher.DB)`.
Failure to do so is silent on JVM (Dispatchers.IO and DB are both thread pools) but can
cause threading errors on iOS Native (which requires a single-threaded DB access pattern).

### Pitfall: SQLDelight transaction + coroutine suspend
SQLDelight's transaction handling relies on thread-locals. If a `suspend fun` inside a
`transaction { }` block is resumed on a different thread (e.g. after a `delay()`), the
transaction context is lost. Rule: never `suspend` inside a `transaction { }` block.
The current `DatabaseWriteActor` serialises writes without suspending inside transactions,
so this is safe — but any new visit-tracking write must follow the same pattern.

### iOS/Native constraint
SQLite Native driver (iOS) requires all DB access on a single thread (typically the main
thread or a dedicated DB thread). `PlatformDispatcher.DB` maps to `Dispatchers.Default`
on iOS, which is GCD-managed. Multiple concurrent reads on iOS will serialise through
SQLite's native driver. This means read performance on iOS is inherently more constrained
than JVM. No FTS5 queries should be issued from the main thread without `withContext`.

---

## 3. Ranking Correctness: Ghost Results from External-Content FTS5

### The ghost result problem
External-content FTS5 stores only the index, not the content. When a block is deleted:
1. The `blocks_ad` trigger fires and issues the `'delete'` command to FTS5.
2. If the trigger is missing or fails silently (e.g. dropped during schema migration),
   the FTS index retains the token entries for the deleted row.
3. On the next search, FTS5 returns the deleted `rowid`.
4. The JOIN `JOIN blocks b ON b.id = bm.rowid` finds no matching row (block deleted).
5. Because the JOIN is INNER, the ghost row is dropped silently.

**Result**: Ghost results are suppressed by the INNER JOIN. However:
- The ghost row consumes one LIMIT slot, potentially excluding a valid result.
- `bm25()` computes IDF scores based on the ghost row's token presence, slightly
  skewing BM25 for rare terms.
- `SELECT COUNT(*) FROM blocks_fts WHERE blocks_fts MATCH :query` (used in
  `searchBlocksCountFts`) will overcount because ghost rows are not joined away.

**Detection**: `INSERT INTO blocks_fts(blocks_fts) VALUES('integrity-check')` raises an
SQL error if the FTS index is inconsistent with the content table. This should be wired into
a debug/settings screen.

**Repair**: `INSERT INTO blocks_fts(blocks_fts) VALUES('rebuild')` discards the entire FTS
index and rebuilds from the current content of `blocks`. This is the `rebuildFts()` function
required by G3/AC5.

### Migration risk
If a schema migration drops and recreates the `blocks` table without also dropping and
recreating `blocks_fts`, the FTS rowids will be misaligned (pointing to old rowids in the
new autoincrement sequence). This can cause queries to return wrong blocks. Migration scripts
must always drop FTS virtual tables before dropping their content tables and recreate them
after.

---

## 4. Visit Tracking Write Amplification

### Risk assessment
With Option B (upserted summary row per page):
- Every navigation event = 1 `INSERT OR REPLACE` on `page_visits`.
- At 20 navigations/day × 365 days = 7,300 writes/year. Trivial for SQLite.
- All writes go through `DatabaseWriteActor`. The actor processes one write at a time,
  serialised. A visit-record write will queue behind any in-flight block save.
- Worst case: user navigates rapidly while a bulk FTS rebuild is running. The write actor
  queue depth increases but this is self-correcting once the rebuild finishes.

**Recommendation**: No debounce needed at the DB level. A 500 ms debounce in the ViewModel
is sufficient to coalesce rapid back-forward navigation into a single write. This is already
the pattern for block saves (500 ms debounce in `BlockStateManager`).

### Scaling concern
If `page_visits` grows without pruning:
- Each upsert rewrites only the existing row (1 row per page UUID) — no unbounded growth.
- The table has at most `pageCount` rows (one per unique page). For 10k pages, this is 10k
  rows × ~50 bytes = ~500 KB. Not a concern.

---

## 5. KMP Constraint: Ranking Code on iOS and WASM

### Kotlin stdlib availability across targets
All ranking math in `buildRankedList` uses:
- `kotlin.math.abs` — available on all targets (commonMain).
- `kotlin.math.exp` — available on all targets (commonMain since Kotlin 1.2).
- `kotlin.collections.sortedByDescending` — available on all targets.
- `kotlin.time.Instant.toEpochMilliseconds()` — available on all targets.

No ranking logic requires JVM-specific APIs. The entire `buildRankedList` function and any
additions (visitRecencyMultiplier, visitFrequency) compile correctly for iOS/WASM.

### SQLDelight availability on WASM
SQLDelight WASM support was experimental as of late 2024. SteleKit enables JS/WASM via
`gradle.properties` `enableJs=true`. The `SearchRepository` interface and its implementations
sit in `commonMain`, but `SqlDelightSearchRepository` is backed by a JDBC driver on JVM —
the WASM/JS target uses a different driver (SqlJs or IndexedDB). Visit tracking writes must
be routed through the same `DatabaseWriteActor` pattern on all platforms to avoid
platform-specific write paths.

### Flow vs suspend on iOS
On Kotlin/Native (iOS), `Flow` collection across threads requires careful coroutine scope
management. The existing pattern (`flow { }.flowOn(PlatformDispatcher.DB)`) is correct for
iOS because it pins the flow's upstream to a single DB thread. Adding visit-tracking as
a `suspend fun` (not a `Flow`) is safer than making it flow-based, since suspend functions
do not require cross-thread flow collection.

---

## 6. Summary: Highest-Priority Pitfalls to Guard Against

| Pitfall | Severity | Fix |
|---------|----------|-----|
| Visit lookup as N+1 inside ranking loop | High | Batch `selectPageVisitsByUuids` before ranking |
| Visit write on wrong dispatcher (iOS crash) | High | `withContext(PlatformDispatcher.DB)` in all write paths |
| `selectNeighbourPageUuids` slow on dense graphs | Medium | Add index on `block_references(from_block_uuid)` |
| Ghost results skewing LIMIT | Low | `rebuildFts()` + integrity-check in settings |
| `pages_ai` trigger using `last_insert_rowid()` | Low | Change to `new.rowid` |
| Migration dropping content table without FTS rebuild | Medium | Document in migration runbook; add assertion in MigrationRunner |
| `rememberCoroutineScope` passed into visit recorder | High | VisitRepository must own its own scope or be called via existing actor |
