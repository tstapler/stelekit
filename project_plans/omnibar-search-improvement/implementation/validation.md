# Validation Plan: Omnibar Search Improvement

## Test Coverage Summary

- Unit tests: 7
- Integration tests: 13
- Benchmark methods: 6
- Acceptance criteria covered: 8/8

---

## Test Cases

### TC-1: Visit timestamp recorded on navigation
**Type**: integration
**Requirement**: AC1
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SearchRepositoryIntegrationTests.kt`

**Setup**: In-memory SQLite DB via `DriverFactory().createDriver("jdbc:sqlite::memory:")`. One page inserted via `pageRepo.savePage(...)`.

**Action**: Call `repository.recordPageVisit(pageUuid)`.

**Assert**:
- Returns `Either.Right(Unit)`.
- Query `selectPageVisitsByUuids(setOf(pageUuid))` returns a row with `last_visited_at > 0`.
- `visit_count == 1`.

---

### TC-2: Repeated navigation increments visit count
**Type**: integration
**Requirement**: AC1
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SearchRepositoryIntegrationTests.kt`

**Setup**: Same as TC-1.

**Action**: Call `repository.recordPageVisit(pageUuid)` three times with slightly different timestamps.

**Assert**:
- `selectPageVisitsByUuids` returns exactly one row for `pageUuid`.
- `visit_count == 3`.
- `last_visited_at` equals the timestamp of the third call (not the first).

---

### TC-3: Visit-recency boost ranks recently-visited page above equally-scored unvisited page
**Type**: integration
**Requirement**: AC2
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SearchRepositoryIntegrationTests.kt`

**Setup**:
- Insert two pages with identical names ("Kotlin Development") and identical content blocks ("kotlin development notes").
- Record a visit for `visitedPageUuid` with `last_visited_at = nowMs` (< 1 hour ago).
- Leave `neverVisitedPageUuid` with no entry in `page_visits`.

**Action**: `repository.searchWithFilters(SearchRequest(query = "kotlin development", limit = 10)).first()`.

**Assert**:
- `ranked` is not empty.
- The `RankedSearchHit.PageHit` for `visitedPageUuid` appears before the `RankedSearchHit.PageHit` for `neverVisitedPageUuid`.
- `visitedHit.score > neverVisitedHit.score`.

---

### TC-4: `visitRecencyMultiplier` returns 2.0 for a visit at time=now
**Type**: unit
**Requirement**: AC2
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/VisitRecencyMultiplierTest.kt`

**Setup**: Instantiate or call `visitRecencyMultiplier` directly (make it `internal` or test via a test-friend helper).

**Action**: Call `visitRecencyMultiplier(lastVisitedMs = nowMs, nowMs = nowMs)`.

**Assert**: Result is within `[1.99, 2.01]` (i.e., `1.0 + exp(0) = 2.0`).

---

### TC-5: `visitRecencyMultiplier` returns 1.0 for `lastVisitedMs = 0`
**Type**: unit
**Requirement**: AC2
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/VisitRecencyMultiplierTest.kt`

**Action**: Call `visitRecencyMultiplier(lastVisitedMs = 0L, nowMs = nowMs)`.

**Assert**: Result is exactly `1.0`.

---

### TC-6: `visitRecencyMultiplier` decays toward 1.0 after many days
**Type**: unit
**Requirement**: AC2
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/VisitRecencyMultiplierTest.kt`

**Action**: Call `visitRecencyMultiplier(lastVisitedMs = nowMs - 30.days.inWholeMilliseconds, nowMs = nowMs)`.

**Assert**: Result is in `(1.0, 1.1)` — close to 1.0 but still above it (30 days >> 3-day half-life).

---

### TC-7: Exact title match is first result regardless of BM25
**Type**: integration
**Requirement**: AC3
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SearchRepositoryIntegrationTests.kt`

**Setup**:
- Insert page "Taxes" (uuid: `generateUuid(700)`) with no body blocks.
- Insert page "Tax Returns 2025" (uuid: `generateUuid(701)`) with a long block content containing "taxes taxes taxes taxes taxes" (higher BM25 signal in body).
- Insert page "Tax Planning" (uuid: `generateUuid(702)`) with a similar content block.

**Action**: `repository.searchWithFilters(SearchRequest(query = "Taxes", limit = 10)).first()`.

**Assert**:
- `ranked[0]` is a `RankedSearchHit.PageHit`.
- `(ranked[0] as RankedSearchHit.PageHit).page.uuid == generateUuid(700)`.

---

### TC-8: Exact title match is case-insensitive
**Type**: integration
**Requirement**: AC3
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SearchRepositoryIntegrationTests.kt`

**Setup**: Same as TC-7.

**Action**: `repository.searchWithFilters(SearchRequest(query = "TAXES", limit = 10)).first()`.

**Assert**: `ranked[0]` is the "Taxes" page hit (uuid `generateUuid(700)`).

---

### TC-9: No exact match — BM25 order is not overridden
**Type**: integration
**Requirement**: AC3
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SearchRepositoryIntegrationTests.kt`

**Setup**: Same pages as TC-7.

**Action**: `repository.searchWithFilters(SearchRequest(query = "tax", limit = 10)).first()`.

**Assert**: `ranked[0]` is NOT required to be the "Taxes" page (no exact match on "tax" vs "Taxes"). The test verifies that `promoteExactTitleMatch` does NOT fire when `query != page.name` (case-insensitive).

---

### TC-10: `promoteExactTitleMatch` with leading/trailing whitespace
**Type**: unit
**Requirement**: AC3
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/ExactTitleMatchTest.kt`

**Setup**: Build a synthetic `ranked` list where the "Taxes" `PageHit` is at index 2 (behind two higher-BM25 block hits).

**Action**: Call `promoteExactTitleMatch(ranked, rawQuery = "  Taxes  ")`.

**Assert**: Returned list has "Taxes" `PageHit` at index 0. All other hits are present and their relative order is preserved.

---

### TC-11: All existing `SearchRepositoryIntegrationTests` pass unchanged
**Type**: integration
**Requirement**: AC4
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SearchRepositoryIntegrationTests.kt`

**Setup**: Standard in-memory driver (same as existing `@BeforeTest setup()`).

**Action**: Run `./gradlew jvmTest --tests "*.SearchRepositoryIntegrationTests"`.

**Assert**: All 7 existing test methods pass:
- `testSearchBlocksByContent`
- `testSearchBlocksByContentCaseInsensitive`
- `testSearchPagesByTitle`
- `testFindBlocksReferencing`
- `testSearchWithFilters`
- `testAndSemanticsMultiTermOnlyMatchesBothTerms`
- `testFieldBoostingPageRanksAboveBlock`
- `testOrFallbackFiresWhenAndReturnsEmpty`
- `testGraphDistanceBoostLinkedPageRanksHigher`
- `testRecencyBoostRecentBlockRanksHigher`

No modifications to those test methods are permitted.

---

### TC-12: `rebuildFts()` succeeds on 10k-page graph
**Type**: integration
**Requirement**: AC5
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/FtsRebuildTest.kt`

**Setup**:
```kotlin
val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
SteleDatabase.Schema.create(driver)
val db = SteleDatabase(driver)
val restricted = RestrictedDatabaseQueries(db.steleDatabase)
val repo = SqlDelightSearchRepository(db, writeActor = null)
SyntheticGraphDbBuilder.populate(db, pageCount = 10_000, blocksPerPage = 10)
```

**Action**: `val result = repo.rebuildFts()`.

**Assert**:
- `result.isRight()` is true.
- No exception is thrown.
- A subsequent `repo.searchWithFilters(SearchRequest(query = "programming", limit = 5)).first()` returns a non-empty `ranked` list.

---

### TC-13: `integrityCheckFts()` returns `Right` on a healthy index
**Type**: integration
**Requirement**: AC5
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/FtsRebuildTest.kt`

**Setup**: Same as TC-12, populated with 1000 pages.

**Action**: `val result = repo.integrityCheckFts()`.

**Assert**: `result.isRight()` is true.

---

### TC-14: p99 search latency < 200ms at 10k pages
**Type**: integration (latency assertion)
**Requirement**: AC6
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SearchLatencyTest.kt`

**Setup**:
```kotlin
val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
val db = SteleDatabase(driver)
SyntheticGraphDbBuilder.populate(db, pageCount = 10_000, blocksPerPage = 10)
val repo = SqlDelightSearchRepository(db)
val queryTerms = listOf("programming", "notes", "project", "meeting", "tax",
                         "philosophy", "economics", "history", "design", "learning")
```

**Action**: Warm up with 5 queries (excluded from measurement). Then run 100 queries round-robin over `queryTerms`, recording `System.currentTimeMillis()` delta for each.

**Assert**:
```kotlin
latencies.sort()
val p99 = latencies[(latencies.size * 0.99).toInt()]
assertTrue(p99 < 200, "p99 latency ${p99}ms exceeded 200ms at 10k pages")
```

---

### TC-15: Cold-start FTS query latency < 500ms at 10k pages
**Type**: integration (latency assertion)
**Requirement**: AC6
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SearchLatencyTest.kt`

**Setup**: Same DB population as TC-14, but measured on the very first `searchWithFilters` call after DB setup (no prior queries).

**Action**: Record `System.currentTimeMillis()` before and after the single cold query.

**Assert**: `coldStartMs < 500`.

---

### TC-16: `SearchBenchmark` JMH task runs and produces output file
**Type**: benchmark
**Requirement**: AC7
**Location**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/benchmarks/SearchBenchmark.kt`

**Action**: `./gradlew :kmp:jvmBenchmark`.

**Assert**:
- Task exits with code 0 (no exceptions in benchmark setup or execution).
- File `kmp/build/reports/benchmarks/main/jvm.json` exists and is non-empty.
- JSON contains entries for all six benchmark method names: `searchWithFilters_singleToken`, `searchWithFilters_multiToken`, `searchWithFilters_phraseQuery`, `searchPagesByTitle_singleToken`, `buildRankedList_50results`, `ftsQueryBuilder_complexQuery`.

---

### TC-17: `SearchBenchmark.searchWithFilters_singleToken` benchmark method executes without error
**Type**: benchmark
**Requirement**: AC7
**Location**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/benchmarks/SearchBenchmark.kt`

**Setup**: `@Setup(Level.Trial)` populates a 2000-page in-memory DB using `SyntheticGraphDbBuilder` with `SyntheticGraphGenerator.LARGE` config.

**Action**: JMH invokes `@Benchmark fun searchWithFilters_singleToken()` using `runBlocking { repo.searchWithFilters(SearchRequest(query = "programming", limit = 50)).first() }`.

**Assert**: No exception thrown. JMH records a finite `SampleTime` in milliseconds.

---

### TC-18: `SearchBenchmark.buildRankedList_50results` benchmark isolates ranking step
**Type**: benchmark
**Requirement**: AC7
**Location**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/benchmarks/SearchBenchmark.kt`

**Setup**: Pre-fetch 50 `SearchedPage` + `SearchedBlock` results during `@Setup`. Pre-fetch `neighbourPageUuids` and `visitMap`.

**Action**: JMH invokes `@Benchmark fun buildRankedList_50results()` calling `buildRankedList(pages50, blocks50, neighbours, visitMap, nowMs)` directly (package-private or `internal` accessor).

**Assert**: Returns a `List<RankedSearchHit>` of size 50 without exception. JMH records throughput.

---

### TC-19: `InstrumentedSearchRepository` records `ranking.visit_boost` span attribute
**Type**: integration
**Requirement**: AC8
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/performance/InstrumentedSearchRepositoryTest.kt`

**Setup**:
```kotlin
val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
val db = SteleDatabase(driver)
val innerRepo = SqlDelightSearchRepository(db)
val spanExporter = InMemorySpanExporter.create()
val tracerProvider = SdkTracerProvider.builder()
    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
    .build()
val tracer = tracerProvider.get("test")
val repo = InstrumentedSearchRepository(innerRepo, tracer)
// Insert one page and one block
```

**Action**: `repo.searchWithFilters(SearchRequest(query = "test", limit = 10)).first()`.

**Assert**:
- `spanExporter.finishedSpanItems` contains at least one span whose name matches `"search"` or `"searchWithFilters"`.
- That span has an attribute `ranking.visit_boost = "true"`.
- That span has attribute `result.ranked.count` with a non-negative integer value.
- That span has attribute `duration.ms` with a non-negative long value.

---

### TC-20: `InMemorySearchRepository.recordPageVisit` stores visit without error
**Type**: unit
**Requirement**: AC1, AC8 (test double completeness)
**Location**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/repository/InMemorySearchRepositoryTest.kt`

**Setup**: Instantiate `InMemorySearchRepository()` (no constructor args).

**Action**: Call `repository.recordPageVisit("page-uuid-1")` twice.

**Assert**:
- Both calls return `Either.Right(Unit)`.
- Internal `visitMap["page-uuid-1"]` is non-null and `> 0` (or whatever the stub's internal tracking exposes via a test-friend getter).

---

## Requirement-to-Test Traceability Matrix

| Requirement | Description | Test Case(s) | Status |
|-------------|-------------|-------------|--------|
| AC1 | Navigating to a page records a visit timestamp | TC-1, TC-2, TC-20 | Covered |
| AC2 | Page visited < 1h ago ranks above equally-scored never-visited page | TC-3, TC-4, TC-5, TC-6 | Covered |
| AC3 | Exact title match is always the first result | TC-7, TC-8, TC-9, TC-10 | Covered |
| AC4 | All existing jvmTest search tests pass without modification | TC-11 | Covered |
| AC5 | `rebuildFts()` runs without error on 10k-page graph | TC-12, TC-13 | Covered |
| AC6 | jvmTest p99 < 200ms at 10k pages | TC-14, TC-15 | Covered |
| AC7 | JMH benchmark task runs and produces output | TC-16, TC-17, TC-18 | Covered |
| AC8 | `InstrumentedSearchRepository` records `ranking.visit_boost` span attribute | TC-19 | Covered |

---

## Test Infrastructure Needed

### New test helpers

**`SyntheticGraphDbBuilder`** (new file)
- Location: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/SyntheticGraphDbBuilder.kt`
- Purpose: Insert N pages + M blocks directly via `pageRepo.savePages()` / `blockRepo.saveBlocks()` into an already-open `SteleDatabase`, bypassing disk I/O and markdown parsing. Used by TC-12 through TC-19.
- Key method: `fun populate(db: SteleDatabase, pageCount: Int, blocksPerPage: Int, seed: Long = 42L)`.
- Uses deterministic UUIDs in the same `generateUuid(index)` pattern as `SearchRepositoryIntegrationTests` (sequential hex-padded).

### New test files

| File | Test cases |
|------|-----------|
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/VisitRecencyMultiplierTest.kt` | TC-4, TC-5, TC-6 |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/ExactTitleMatchTest.kt` | TC-10 |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/FtsRebuildTest.kt` | TC-12, TC-13 |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SearchLatencyTest.kt` | TC-14, TC-15 |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/performance/InstrumentedSearchRepositoryTest.kt` | TC-19 |
| `kmp/src/commonTest/kotlin/dev/stapler/stelekit/repository/InMemorySearchRepositoryTest.kt` | TC-20 |

### Additions to `SearchRepositoryIntegrationTests.kt`

New test methods added to the existing class:
- `testRecordPageVisitStoresTimestamp` (TC-1)
- `testRecordPageVisitIncrementsCount` (TC-2)
- `testVisitBoostRanksVisitedPageFirst` (TC-3)
- `testExactTitleMatchIsFirstResult` (TC-7)
- `testExactTitleMatchCaseInsensitive` (TC-8)
- `testNoExactMatchDoesNotForceOrder` (TC-9)

### JMH benchmark file

- Location: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/benchmarks/SearchBenchmark.kt`
- Pattern: mirrors `AhoCorasickBenchmark.kt` — `@State(Scope.Benchmark)`, `@BenchmarkMode(Mode.SampleTime)`, `@OutputTimeUnit(MILLISECONDS)`.
- `@Setup(Level.Trial)` populates an in-memory `SteleDatabase` via `SyntheticGraphDbBuilder` with 2000 pages (LARGE config) as the default. XLARGE (10k) variant enabled via `-PbenchmarkSize=xlarge` system property.
- Six `@Benchmark` methods: `searchWithFilters_singleToken`, `searchWithFilters_multiToken`, `searchWithFilters_phraseQuery`, `searchPagesByTitle_singleToken`, `buildRankedList_50results`, `ftsQueryBuilder_complexQuery`.

### `InstrumentedSearchRepository` instrumentation requirement

TC-19 requires `InstrumentedSearchRepository` to actually emit a span for `searchWithFilters`. The current implementation is a pass-through with no span emission. The `@Test` will fail until T2.8 in the implementation plan is complete. This is intentional — the test drives the implementation.

The OTel test dependency (`opentelemetry-sdk-testing`) is likely already on the classpath via other instrumented tests; verify in `kmp/build.gradle.kts` before adding it.

---

## Risk Areas

### Flakiness risks

**TC-14 / TC-15 — Latency assertions on CI**
The p99 < 200ms target at 10k pages may be environment-sensitive. SQLite in-memory on a shared CI runner (GitHub Actions) can be slower than local JVM due to CPU contention. Mitigation:
- Use a warm-up phase (5+ queries excluded from measurement) before collecting latencies.
- If CI consistently fails at 200ms, the threshold may need raising to 400ms with a comment noting the CI constraint. Do not lower to pass — investigate the query plan first.
- Mark the test `@Tag("latency")` so it can be excluded from fast feedback loops if needed.

**TC-12 — 10k-page `rebuildFts` test duration**
FTS5 rebuild on 10k pages in SQLite in-memory is O(N) but fast; expect < 5 seconds. If it exceeds the JVM test timeout, use 5k pages as an alternative. Record the trade-off in a comment.

### Assertion difficulty

**TC-3 — Visit boost ranking correctness**
Two pages with truly identical content and identical FTS scores are hard to guarantee in practice because FTS5 BM25 scores can vary slightly based on rowid ordering and internal segment structure. Mitigation: give both pages identical page names AND identical block content using the exact same string, insert them in a single transaction, and assert `visitedHit.score >= neverVisitedHit.score * 1.5` (the visit multiplier at t=0 adds ×2.0 vs ×1.0, a 2× score ratio, so 1.5× threshold gives slack for minor BM25 variance).

**TC-19 — OTel span attribute assertions**
`InstrumentedSearchRepository` must complete its span before the assertion. Use `spanExporter.finishedSpanItems` only after `Flow.first()` returns (the span should be closed at that point). If spans are emitted asynchronously, use `awaitExact(n)` or a short `Thread.sleep(50)` as a last resort.

**TC-10 — `promoteExactTitleMatch` unit test**
`promoteExactTitleMatch` is a private function in `SqlDelightSearchRepository`. It must be made `internal` (or package-private via a test file in the same package) to be callable from a unit test. Alternatively, test it indirectly through `searchWithFilters` (which TC-7 and TC-8 already do). The unit test (TC-10) exists to cover the whitespace-trimming edge case which is hard to set up via a full integration test.

### Schema dependency

TC-1, TC-2, TC-3, TC-12, TC-13, TC-14, TC-15, and TC-19 all depend on the `page_visits` table (T2.1) existing in the schema. These tests will fail to compile or throw at runtime until the migration is applied. The `DriverFactory().createDriver("jdbc:sqlite::memory:")` call runs `SteleDatabase.Schema.create(driver)` which includes the new table — but this only works after the `.sq` file is updated. Sequence dependency: T2.1 (schema) must be merged before any of these tests can run green.
