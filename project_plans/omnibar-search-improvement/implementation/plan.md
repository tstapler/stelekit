# Implementation Plan: Omnibar Search Improvement

## Summary

This plan extends SteleKit's FTS5/BM25 search to add visit-recency ranking, exact-title-match
guarantees, FTS index health tooling, and a comprehensive performance-testing suite. The
`stelekit-better-search` branch already ported the scoring constants, AND-first/OR-fallback
semantics, `bm25Score` fields, `RankedSearchHit`, OTel spans, and 37 integration tests onto a
now-stale base (pre-`main`). Epic 1 re-applies that work cleanly onto the current branch; Epics
2–5 add the net-new features required by the requirements.

---

## Prior Work (stelekit-better-search)

The `origin/stelekit-better-search` branch (commit `22091fe55`) added the following on top of
an older base that has since diverged from `main`:

| Item | Status in `main` |
|------|-----------------|
| `FtsQueryBuilder.build()` — AND semantics, wildcard on every token | Already in `main` |
| `FtsQueryBuilder.buildOr()` — OR fallback | Already in `main` |
| `PAGE_BOOST = 5.0`, `GRAPH_BOOST = 3.0`, `RECENCY_HALFLIFE_DAYS = 14.0` constants | Already in `main` |
| `bm25Score` field on `SearchedPage` / `SearchedBlock` | Already in `main` |
| `RankedSearchHit` sealed class | Already in `main` |
| `buildRankedList()` + `recencyMultiplier()` + `graphMultiplier()` | Already in `main` |
| `selectNeighbourPageUuids` SQL query | Already in `main` |
| `bm25_score` column in FTS SQL queries | Already in `main` |
| OTel spans in `SqlDelightSearchRepository` | Already in `main` |
| 37 integration tests in `SearchRepositoryIntegrationTests` | Already in `main` |

**Assessment**: The diff between `stelekit-better-search` and `main` shows that `main` already
contains essentially all of the `stelekit-better-search` changes. There is no cherry-pick work
needed. Epic 1 is therefore limited to confirming this, fixing two trigger bugs identified in
research, and adding the missing index on `block_references(from_block_uuid)`.

---

## Epics

---

### Epic 1: Baseline Correctness Fixes

Confirm `main` has all `stelekit-better-search` improvements, then fix two known
correctness bugs from research (`pages_ai` trigger and missing reference index).

#### Tasks

**T1.1 — Verify stelekit-better-search parity on `main`**
- Run `git diff origin/stelekit-better-search...HEAD -- kmp/src/commonMain/` and confirm
  all scoring logic, `RankedSearchHit`, `bm25Score` fields, and SQL columns are present.
- Run existing `SearchRepositoryIntegrationTests` (`./gradlew jvmTest --tests
  "*.SearchRepositoryIntegrationTests"`) and confirm all pass.
- File: no code changes expected; this is a verification step.

**T1.2 — Fix `pages_ai` trigger: replace `last_insert_rowid()` with `new.rowid`**
- File: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`
- Change:
  ```sql
  -- Before
  CREATE TRIGGER pages_ai AFTER INSERT ON pages BEGIN
      INSERT INTO pages_fts(rowid, name) VALUES (last_insert_rowid(), new.name);
  END;

  -- After
  CREATE TRIGGER pages_ai AFTER INSERT ON pages BEGIN
      INSERT INTO pages_fts(rowid, name) VALUES (new.rowid, new.name);
  END;
  ```
- This requires a schema migration (new migration class in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/`).
- Migration: `DROP TRIGGER IF EXISTS pages_ai; CREATE TRIGGER pages_ai ...` (new rowid form).
- Register the migration in `MigrationRunner`.

**T1.3 — Add index on `block_references(from_block_uuid)`**
- The `selectNeighbourPageUuids` query joins `block_references` on `from_block_uuid`. The
  research confirms this is currently unindexed on this join direction (only
  `idx_references_from` on `from_block_uuid` exists — verify it actually covers this query).
- File: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`
- Verify `idx_references_from ON block_references(from_block_uuid)` is present. If it is,
  this task is a no-op verification. If missing, add it and register migration.

---

### Epic 2: Visit-Recency Tracking

Add a `page_visits` table, record navigation events, and incorporate a visit-recency
multiplier into `buildRankedList`. Addresses G1, AC1, AC2, AC8.

#### Tasks

**T2.1 — Add `page_visits` table to schema (Option B: upserted summary row)**
- File: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`
- Add after existing `CREATE TABLE` blocks:
  ```sql
  CREATE TABLE IF NOT EXISTS page_visits (
      page_uuid       TEXT NOT NULL PRIMARY KEY,
      visit_count     INTEGER NOT NULL DEFAULT 0,
      last_visited_at INTEGER NOT NULL   -- epoch milliseconds
  );
  ```
- Add named queries:
  ```sql
  upsertPageVisit:
  INSERT INTO page_visits (page_uuid, visit_count, last_visited_at)
  VALUES (?, 1, ?)
  ON CONFLICT(page_uuid) DO UPDATE SET
      visit_count     = visit_count + 1,
      last_visited_at = excluded.last_visited_at;

  selectPageVisitsByUuids:
  SELECT page_uuid, last_visited_at
  FROM page_visits
  WHERE page_uuid IN ?;
  ```
- Note: `IN ?` uses SQLDelight's `Collection<String>` parameter form — verify this compiles
  on all targets. If not, use `selectPageVisitByUuid` (single-row) and batch-call from Kotlin,
  or use a raw SQL fragment.

**T2.2 — Add `RestrictedDatabaseQueries` stub for `upsertPageVisit`**
- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`
- Add in the `// ── Maintenance ───` section (or create a new `// ── Visit tracking ──` section):
  ```kotlin
  @DirectSqlWrite
  fun upsertPageVisit(page_uuid: String, last_visited_at: Long): QueryResult<Long> =
      queries.upsertPageVisit(page_uuid, last_visited_at)
  ```

**T2.3 — Add schema migration for `page_visits` table**
- Create migration class (e.g. `AddPageVisitsMigration`) in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/`.
- Migration body: `CREATE TABLE IF NOT EXISTS page_visits (...)` (same DDL as T2.1 — safe to
  run even if the table was created at schema creation time on new installs).
- Register in `MigrationRunner` with a new migration ID.

**T2.4 — Add `recordPageVisit` to `SearchRepository` interface**
- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/GraphRepository.kt`
- Add to `SearchRepository` interface:
  ```kotlin
  /**
   * Records a navigation to [pageUuid]. Fire-and-forget from the ViewModel.
   * Must execute on [PlatformDispatcher.DB].
   */
  @DirectRepositoryWrite
  suspend fun recordPageVisit(pageUuid: String): Either<DomainError, Unit>
  ```

**T2.5 — Implement `recordPageVisit` in `SqlDelightSearchRepository`**
- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightSearchRepository.kt`
- Implementation:
  ```kotlin
  override suspend fun recordPageVisit(pageUuid: String): Either<DomainError, Unit> =
      withContext(PlatformDispatcher.DB) {
          try {
              queries.upsertPageVisit(pageUuid, HistogramWriter.epochMs())
              Unit.right()
          } catch (e: Exception) {
              DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
          }
      }
  ```
- Note: `queries` is the read-only `SteleDatabaseQueries`; the write must go through
  `DatabaseWriteActor`. Inject `writeActor: DatabaseWriteActor?` into the constructor
  (already nullable in `RepositorySet`) and route through it:
  ```kotlin
  writeActor?.execute {
      @OptIn(DirectSqlWrite::class)
      restricted.upsertPageVisit(pageUuid, HistogramWriter.epochMs())
  } ?: run { /* fallback: direct write for test DBs without actor */ }
  ```
  Alternatively, since visit writes are low-stakes (lost write = no boost, not data loss),
  use a direct write from `withContext(PlatformDispatcher.DB)` with `@OptIn(DirectSqlWrite::class)`
  on a private helper annotated `@DirectSqlWrite`. Choose the simpler path that matches existing
  patterns in the codebase.

**T2.6 — Call `recordPageVisit` from `StelekitViewModel.navigateTo()`**
- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
- In `navigateTo()`, after the `_uiState.update { }` block, add a fire-and-forget launch:
  ```kotlin
  if (screen is Screen.PageView) {
      viewModelScope.launch {
          searchRepository?.recordPageVisit(screen.page.uuid)
      }
  }
  ```
- The ViewModel already has `viewModelScope` (verify: it must be a `CoroutineScope` owned by
  the ViewModel, not `rememberCoroutineScope`). `searchRepository` must be wired in via
  `RepositorySet`; verify it is accessible from the ViewModel.

**T2.7 — Add `visitRecencyMultiplier` and integrate into `buildRankedList`**
- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightSearchRepository.kt`
- Add constant:
  ```kotlin
  const val VISIT_HALFLIFE_DAYS = 3.0
  ```
- Add function:
  ```kotlin
  private fun visitRecencyMultiplier(lastVisitedMs: Long, nowMs: Long): Double {
      if (lastVisitedMs <= 0L) return 1.0
      val daysSince = (nowMs - lastVisitedMs).coerceAtLeast(0L) / 86_400_000.0
      return 1.0 + exp(-daysSince / VISIT_HALFLIFE_DAYS)
  }
  ```
- Modify `buildRankedList` signature and body to accept a visit map:
  ```kotlin
  private fun buildRankedList(
      pages: List<SearchedPage>,
      blocks: List<SearchedBlock>,
      neighbourPageUuids: Set<String>,
      visitMap: Map<String, Long>,   // pageUuid -> last_visited_at epoch ms (0 if never)
      nowMs: Long,
  ): List<RankedSearchHit> {
      val pageHits = pages.map { sp ->
          val bm25 = abs(sp.bm25Score)
          val lastVisited = visitMap[sp.page.uuid] ?: 0L
          val score = bm25 * PAGE_BOOST *
              recencyMultiplier(sp.page.updatedAt.toEpochMilliseconds(), nowMs) *
              graphMultiplier(sp.page.uuid, neighbourPageUuids) *
              visitRecencyMultiplier(lastVisited, nowMs)
          RankedSearchHit.PageHit(sp.page, sp.snippet, score)
      }
      val blockHits = blocks.map { sb ->
          val bm25 = abs(sb.bm25Score)
          val lastVisited = visitMap[sb.block.pageUuid] ?: 0L
          val score = bm25 *
              recencyMultiplier(sb.block.updatedAt.toEpochMilliseconds(), nowMs) *
              graphMultiplier(sb.block.pageUuid, neighbourPageUuids) *
              visitRecencyMultiplier(lastVisited, nowMs)
          RankedSearchHit.BlockHit(sb.block, sb.snippet, score)
      }
      return (pageHits + blockHits).sortedByDescending { it.score }
  }
  ```
- Collect visit data before calling `buildRankedList` (inside `searchWithFilters`, after
  `neighbourPageUuids` is computed):
  ```kotlin
  val allResultUuids = searchedPages.map { it.page.uuid } +
      searchedBlocks.map { it.block.pageUuid }
  val visitMap: Map<String, Long> = if (allResultUuids.isEmpty()) emptyMap()
      else runCatching {
          queries.selectPageVisitsByUuids(allResultUuids.toSet())
              .executeAsList()
              .associate { it.page_uuid to it.last_visited_at }
      }.getOrDefault(emptyMap())
  val ranked = buildRankedList(searchedPages, searchedBlocks, neighbourPageUuids, visitMap, nowMs)
  ```
  This is a single batch query (not N+1), safe on all platforms.

**T2.8 — Update `InstrumentedSearchRepository` to record `ranking.visit_boost` span attribute**
- File:
  `kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/performance/InstrumentedSearchRepository.kt`
- Currently a thin pass-through. Instrument `searchWithFilters` to measure duration and record
  an OTel span with at minimum:
  ```
  ranking.visit_boost = "true"   // indicates visit signal was applied
  result.ranked.count = N
  duration.ms = X
  ```
- Pattern to follow: `SqlDelightSearchRepository.searchBlocksByContent` span emission via
  `SpanEmitter`. Use the same `SpanEmitter` approach (already in `InstrumentedSearchRepository`
  via the `Tracer` injection, or adopt `SpanEmitter` for consistency).

**T2.9 — Add `page_visits` stub to `InMemorySearchRepository` (test doubles)**
- Find the in-memory `SearchRepository` implementation used in tests
  (`InMemorySearchRepository` or similar).
- Add a no-op `recordPageVisit` that stores to a `MutableMap<String, Long>`.
- File: wherever the test double lives (search for `InMemorySearchRepository` or
  `class.*SearchRepository.*Memory`).

---

### Epic 3: Exact-Title-Match Guarantee

A page whose name exactly matches the query string (case-insensitive) must appear as the
first result. Addresses G2, AC3.

#### Tasks

**T3.1 — Add exact-title-match post-processing in `buildRankedList`**
- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightSearchRepository.kt`
- After sorting by score, promote any page whose `page.name.equals(rawQuery, ignoreCase = true)`
  to position 0:
  ```kotlin
  private fun promoteExactTitleMatch(
      ranked: List<RankedSearchHit>,
      rawQuery: String,
  ): List<RankedSearchHit> {
      val exactIdx = ranked.indexOfFirst { hit ->
          hit is RankedSearchHit.PageHit &&
              hit.page.name.trim().equals(rawQuery.trim(), ignoreCase = true)
      }
      if (exactIdx <= 0) return ranked   // 0 = already first, -1 = not found
      val exactHit = ranked[exactIdx]
      return listOf(exactHit) + ranked.toMutableList().also { it.removeAt(exactIdx) }
  }
  ```
- Call `promoteExactTitleMatch(ranked, rawQuery)` before emitting the result in
  `searchWithFilters`.

**T3.2 — Pass `rawQuery` through to `buildRankedList` or keep promotion as a separate step**
- Architectural choice: keep `buildRankedList` focused on scoring; apply `promoteExactTitleMatch`
  as a post-step in `searchWithFilters`. This keeps the scoring function pure (no string
  comparison) and easier to test independently.
- No change to `buildRankedList` signature needed for this approach.

**T3.3 — Add exact-title-match tests to `SearchRepositoryIntegrationTests`**
- File:
  `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SearchRepositoryIntegrationTests.kt`
- Test cases:
  - Exact match: insert pages "Taxes", "Tax Returns 2025", "Tax Planning"; query "taxes";
    verify `ranked[0]` is the "Taxes" page.
  - Case-insensitive: query "TAXES" → same result.
  - No exact match: query "tax"; verify "Taxes" is NOT necessarily first (BM25 rules).
  - Exact match with trailing space: query "Taxes " (trimmed) → still promotes.

---

### Epic 4: FTS Index Health (`rebuildFts`)

Implement the `rebuildFts()` repair function and optional `integrityCheckFts()` for
debug/settings. Addresses G3, AC5.

#### Tasks

**T4.1 — Add `rebuildFts` and `integrityCheckFts` SQL commands**
- File: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`
- SQLDelight does not support FTS5 maintenance commands via named queries (they are not SELECT
  or DML in the standard sense). Use a raw SQL execution approach.
- Add raw maintenance queries:
  ```sql
  rebuildBlocksFts:
  INSERT INTO blocks_fts(blocks_fts) VALUES('rebuild');

  rebuildPagesFts:
  INSERT INTO pages_fts(pages_fts) VALUES('rebuild');

  integrityCheckBlocksFts:
  INSERT INTO blocks_fts(blocks_fts) VALUES('integrity-check');

  integrityCheckPagesFts:
  INSERT INTO pages_fts(pages_fts) VALUES('integrity-check');
  ```
- These are INSERT statements so they must be in `RestrictedDatabaseQueries` with `@DirectSqlWrite`.

**T4.2 — Implement `rebuildFts()` in `SqlDelightSearchRepository`**
- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightSearchRepository.kt`
- Add to `SearchRepository` interface (in `GraphRepository.kt`):
  ```kotlin
  suspend fun rebuildFts(): Either<DomainError, Unit>
  suspend fun integrityCheckFts(): Either<DomainError, Unit>
  ```
- Implementation in `SqlDelightSearchRepository`:
  ```kotlin
  override suspend fun rebuildFts(): Either<DomainError, Unit> =
      withContext(PlatformDispatcher.DB) {
          try {
              writeActor?.execute {
                  @OptIn(DirectSqlWrite::class)
                  restricted.rebuildBlocksFts()
                  @OptIn(DirectSqlWrite::class)
                  restricted.rebuildPagesFts()
              }
              Unit.right()
          } catch (e: Exception) {
              DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
          }
      }
  ```
- `integrityCheckFts()` calls the two `integrity-check` commands inside a try/catch;
  returns `Either.Left(DomainError.DatabaseError.WriteFailed("FTS integrity check failed: …"))`
  if the SQLite error is thrown.

**T4.3 — Add `RestrictedDatabaseQueries` stubs for FTS maintenance commands**
- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`
- Add in the `// ── Maintenance ───` section:
  ```kotlin
  @DirectSqlWrite
  fun rebuildBlocksFts(): QueryResult<Long> = queries.rebuildBlocksFts()

  @DirectSqlWrite
  fun rebuildPagesFts(): QueryResult<Long> = queries.rebuildPagesFts()

  @DirectSqlWrite
  fun integrityCheckBlocksFts(): QueryResult<Long> = queries.integrityCheckBlocksFts()

  @DirectSqlWrite
  fun integrityCheckPagesFts(): QueryResult<Long> = queries.integrityCheckPagesFts()
  ```

**T4.4 — Wire `rebuildFts()` into ViewModel/Settings**
- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
- Add `fun triggerFtsRebuild()` that calls `searchRepository.rebuildFts()` in a
  `viewModelScope.launch` and updates a status message on completion.
- Expose a debug action in the Performance/Debug settings screen (find the relevant composable
  in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/`).

**T4.5 — Integration test: `rebuildFts` on 10k-page in-memory graph**
- File:
  `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SearchRepositoryIntegrationTests.kt`
- Test: insert 10k pages via repository, call `rebuildFts()`, assert it returns `Either.Right`.
- Use `SyntheticGraphGenerator.XLARGE` graph setup (already has 7978 + 2930 journal pages).
  Loading via `GraphLoader` may be too slow for a unit test; prefer direct repository inserts
  for speed.

---

### Epic 5: Performance Test Suite

Two-layer testing: integration latency assertions in `jvmTest` and JMH microbenchmarks via
the existing `kotlinx-benchmark` setup. Addresses G4, G5, AC6, AC7.

#### Tasks

**T5.1 — Create `SyntheticGraphDbBuilder` helper for in-DB graph setup (no disk I/O)**
- File:
  `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/SyntheticGraphDbBuilder.kt`
- Purpose: insert N pages + M blocks directly via `PageRepository` / `BlockRepository` into an
  in-memory SQLite DB. Avoids `GraphLoader`'s markdown parsing overhead in benchmark setup.
- Method: `fun populate(pageRepo, blockRepo, config: SyntheticGraphGenerator.Config)`.
- Use deterministic UUIDs (sequential hex, as in `SearchRepositoryIntegrationTests`) for
  reproducibility.
- Rationale: The existing `SyntheticGraphGenerator` writes to disk; using it in benchmarks adds
  disk I/O noise. A DB-direct builder produces cleaner latency measurements.

**T5.2 — Add jvmTest latency assertions: `SearchLatencyTest`**
- File:
  `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SearchLatencyTest.kt`
- Uses `SyntheticGraphDbBuilder` to populate three graph sizes: 100, 1000, 10000 pages with
  ~10 blocks each.
- For each size, run 100 search queries with representative terms, measure wall-clock latency,
  compute p99.
- Assertions (from AC6):
  - At 10k pages: p99 < 200ms.
  - Cold-start FTS query (first query after DB open): < 500ms.
- Implementation sketch:
  ```kotlin
  @Test
  fun searchLatency_10kPages_p99Under200ms() = runBlocking {
      val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
      SteleDatabase.Schema.create(driver)
      val db = SteleDatabase(driver)
      val repo = SqlDelightSearchRepository(db)
      SyntheticGraphDbBuilder.populate(db, pageCount = 10_000, blocksPerPage = 10)

      val queries = listOf("programming", "notes", "project", "meeting", "tax")
      val latencies = mutableListOf<Long>()
      repeat(100) { i ->
          val q = queries[i % queries.size]
          val start = System.currentTimeMillis()
          repo.searchWithFilters(SearchRequest(query = q, limit = 50)).first()
          latencies.add(System.currentTimeMillis() - start)
      }
      latencies.sort()
      val p99 = latencies[(latencies.size * 0.99).toInt()]
      assertTrue(p99 < 200, "p99 latency $p99 ms exceeded 200 ms at 10k pages")
  }
  ```
- Run in CI via standard `./gradlew jvmTest`.

**T5.3 — Create `SearchBenchmark.kt` (JMH via kotlinx-benchmark)**
- File:
  `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/benchmarks/SearchBenchmark.kt`
- Pattern: follows `AhoCorasickBenchmark.kt` in the same package.
- `@State(Scope.Benchmark)` class with `@Setup(Level.Trial)` that populates 2000-page DB
  (LARGE config) — warm baseline. Add XLARGE (10k) variant via separate `@State` class or
  parameterised with a system property.
- Benchmark methods (all `@BenchmarkMode(Mode.SampleTime)`, `@OutputTimeUnit(MILLISECONDS)`):
  ```
  searchWithFilters_singleToken         — "programming"
  searchWithFilters_multiToken          — "meeting notes project"
  searchWithFilters_phraseQuery         — '"meeting notes" programming'
  searchPagesByTitle_singleToken        — "programming"
  buildRankedList_50results             — ranking step in isolation (pre-build 50 results)
  ftsQueryBuilder_complexQuery          — FtsQueryBuilder.build() for complex input
  ```
- Use `runBlocking` in `@Benchmark` methods (correct for synchronous JMH measurement).
- No new dependencies required: `kotlinx-benchmark` already configured in `kmp/build.gradle.kts`.
- Run: `./gradlew :kmp:jvmBenchmark`. Output: `kmp/build/reports/benchmarks/main/jvm.json`.

**T5.4 — Verify `kotlinx-benchmark` `allopen` plugin is applied**
- File: `kmp/build.gradle.kts`
- `kotlinx-benchmark` requires `allopen` Kotlin plugin so benchmark classes are not final.
  Confirm `"kotlin.plugin.allopen"` and `allOpen { annotation("org.openjdk.jmh.annotations.State") }`
  are present. If missing, add them (no ADR needed — this is a build tooling fix, not a new
  external dependency).

**T5.5 — N+1 query elimination: `selectNeighbourPageUuids` on dense graphs**
- Profile `selectNeighbourPageUuids` on a dense hub-page graph (MESH config from
  `SyntheticGraphGenerator`).
- If it is slow (> 50ms on MESH), consider:
  1. Caching the neighbour set in a `LinkedHashMap<String, Set<String>>` with a small capacity
     (e.g. 10 entries) keyed by `pageUuid`. Cache is invalidated on navigation. This is a
     pure Kotlin change in `SqlDelightSearchRepository`, no SQL changes.
  2. Alternatively, push the graph-distance boost into SQL as an outer join, eliminating the
     separate `selectNeighbourPageUuids` call entirely.
- Decision: start with the in-memory cache (option 1) as the lower-risk change. Record decision
  in T5.5 code comment.

**T5.6 — Add CI job for benchmark (no regression gate)**
- File: `.github/workflows/` (find the existing CI workflow).
- Add a step that runs `./gradlew :kmp:jvmBenchmark` and uploads
  `kmp/build/reports/benchmarks/` as a CI artifact.
- Mark `continue-on-error: false` — benchmarks must complete successfully (AC7).

---

## Technical Decisions

### Decision 1: `page_visits` uses Option B (upserted summary row)

One row per page, `visit_count` + `last_visited_at`. Avoids N+1 reads during ranking (single
`IN` query for all result UUIDs). Write amplification is 1 upsert per navigation at most, capped
by the 500ms ViewModel debounce on block saves (same pattern). The table has at most `pageCount`
rows — 10k pages × 50 bytes = ~500 KB, negligible.

### Decision 2: Multiplicative visit signal, not additive

Adding `visitRecencyMultiplier` as a ×-factor (max ×2.0 for today, ×1.0 for old content) is
consistent with the existing `recencyMultiplier` and `graphMultiplier` pattern. The three
multipliers together cap at ×12.0 (visited today + edited today + graph neighbour), which is
intentional: it strongly promotes content the user is actively engaging with.

### Decision 3: Exact-title-match as post-sort promotion, not score inflation

Inflating the BM25 score for exact matches (e.g. multiplying by 1000) would corrupt the
scoring signal for tests. A post-sort promotion step is cleaner, testable, and explicit.
It also means the exact-match page still appears first even if its BM25 score is 0.0 (e.g.
query matches page name but the page has no indexed blocks).

### Decision 4: `rebuildFts` routes writes through `DatabaseWriteActor`

FTS rebuild is a write-like operation (INSERT with special command string). It must be serialised
by `DatabaseWriteActor` to prevent races with concurrent block saves. The rebuild is O(N) in
row count and may take several seconds on large graphs; callers must invoke it from a
non-blocking context (ViewModel `viewModelScope.launch`).

### Decision 5: JMH benchmarks stay in `jvmMain` (not a separate source set)

Consistent with `AhoCorasickBenchmark.kt`. The benchmark code ships in the production JVM jar
but is not invoked at runtime. A separate `jvmBenchmark` source set is a future cleanup if
the benchmark count grows.

### Decision 6: `recordPageVisit` write path

For visit writes, use `DatabaseWriteActor` (preferred per CLAUDE.md). If `SqlDelightSearchRepository`
does not currently hold a reference to `writeActor`, add an optional `writeActor:
DatabaseWriteActor?` constructor parameter (matching the pattern in `RepositorySet`). For
in-memory test instances where `writeActor` is null, fall back to a direct write with
`@OptIn(DirectSqlWrite::class)` so tests remain simple.

---

## SQL Schema Changes

### New table: `page_visits`

```sql
CREATE TABLE IF NOT EXISTS page_visits (
    page_uuid       TEXT NOT NULL PRIMARY KEY,
    visit_count     INTEGER NOT NULL DEFAULT 0,
    last_visited_at INTEGER NOT NULL   -- epoch milliseconds (Unix)
);
```

No index beyond the implicit `PRIMARY KEY` index is needed — all reads use a primary-key lookup
or an `IN` clause which SQLite optimises via the PK index.

### New named queries

```sql
upsertPageVisit:
INSERT INTO page_visits (page_uuid, visit_count, last_visited_at)
VALUES (?, 1, ?)
ON CONFLICT(page_uuid) DO UPDATE SET
    visit_count     = visit_count + 1,
    last_visited_at = excluded.last_visited_at;

selectPageVisitsByUuids:
SELECT page_uuid, last_visited_at
FROM page_visits
WHERE page_uuid IN ?;
```

### New FTS maintenance queries

```sql
rebuildBlocksFts:
INSERT INTO blocks_fts(blocks_fts) VALUES('rebuild');

rebuildPagesFts:
INSERT INTO pages_fts(pages_fts) VALUES('rebuild');

integrityCheckBlocksFts:
INSERT INTO blocks_fts(blocks_fts) VALUES('integrity-check');

integrityCheckPagesFts:
INSERT INTO pages_fts(pages_fts) VALUES('integrity-check');
```

### Trigger fix (migration)

```sql
-- Drop and recreate pages_ai to use new.rowid instead of last_insert_rowid()
DROP TRIGGER IF EXISTS pages_ai;
CREATE TRIGGER pages_ai AFTER INSERT ON pages BEGIN
    INSERT INTO pages_fts(rowid, name) VALUES (new.rowid, new.name);
END;
```

---

## Acceptance Criteria Mapping

| AC | Requirement | Implementation |
|----|-------------|----------------|
| AC1 | Navigating to a page records a visit timestamp | T2.6: `navigateTo()` calls `recordPageVisit()` |
| AC2 | Page visited < 1h ago ranks above equally-BM25-scored never-visited page | T2.7: `visitRecencyMultiplier` with 3-day half-life; test in T2.9 |
| AC3 | Exact title match is always first result | T3.1: `promoteExactTitleMatch()` post-sort; test in T3.3 |
| AC4 | All existing jvmTest search tests pass | T1.1: verify; no changes break existing tests |
| AC5 | `rebuildFts()` runs without error on 10k-page graph | T4.5: integration test |
| AC6 | jvmTest p99 < 200ms at 10k pages | T5.2: `SearchLatencyTest` |
| AC7 | JMH benchmark task runs and produces output | T5.3: `SearchBenchmark.kt`; T5.6: CI step |
| AC8 | `InstrumentedSearchRepository` records `ranking.visit_boost` span attribute | T2.8 |
