# Implementation Plan: SteleKit Performance

**Status**: Ready for implementation
**Phase**: 5 — Implementation
**Feature branch**: stelekit-blockhound (bugs being fixed there); performance work follows

---

## Overview

The caching layer in SteleKit contains three correctness bugs that make all hit-rate data
unreliable, a data race on hierarchy timestamps, two dead-code paths with wrong dispatchers,
and no invalidation wiring between writes and the cache. These must be fixed before any
throughput work is meaningful. After the fixes, two targeted wiring changes (actor callback
invalidation and `stateIn` at the ViewModel layer) deliver the highest read-throughput gain
with the lowest risk. A third story adds observability so regressions are caught automatically.

Dependencies between stories are strict: Story 2 cannot be implemented correctly until Story 1
is complete. Story 3 can be implemented concurrently with Story 2 but benefits from it being
done first so the metrics reflect real behavior.

ADRs covering the three key architectural decisions are in
`project_plans/stelekit-performance/decisions/`.

---

## Story 1 — Safety and Bug Fixes

**Prerequisite for all other work. Nothing in Stories 2 or 3 is safe to ship until these
are resolved.**

All bugs were identified during research synthesis. The `jvmMain` cache layer
(`CacheCore.kt`, `BlockCache.kt`, `CachedBlockRepository.kt`, `PageCache.kt`) is a parallel
caching implementation that predates the `commonMain` `LruCache`. Its `LRUCache` class
(not to be confused with `LruCache` in `commonMain`) is the source of three of the eight bugs.

### Task 1.1 — Verify and upgrade `sqlite-jdbc`

**File**: `kmp/build.gradle.kts`

The JVM SQLite driver (`app.cash.sqldelight:sqlite-driver`) bundles `sqlite-jdbc` internally.
The research identified a WAL data race bug in SQLite versions 3.7.0–3.51.2 that can silently
corrupt databases when a concurrent write and WAL checkpoint race. This was fixed in SQLite
3.51.3 (released March 2026).

Action items:
- Check the bundled `sqlite-jdbc` version by inspecting the dependency resolution report:
  `./gradlew jvmMain:dependencies --configuration runtimeClasspath | grep sqlite`
- If the resolved version is below 3.51.3, add an explicit `sqlite-jdbc` override to force
  the patched version. The override goes in `jvmMain` dependencies:
  `implementation("org.xerial:sqlite-jdbc:3.51.3")` (or whichever ≥ 3.51.3 is available).
- Run `PooledJdbcSqliteDriverTest` after upgrade to confirm the pool still functions.

Note: The Android driver uses `com.github.requery:sqlite-android:3.49.0` which bundles its
own SQLite. That library must be upgraded to ≥ 3.49.2 or equivalent to pick up the same fix
if a patched version is available. This is a separate dependency from `sqlite-jdbc`.

### Task 1.2 — Fix `LRUCache.get()` metrics counter (jvmMain)

**File**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cache/CacheCore.kt`, line 147

The existing code:
```kotlin
metrics.value = metrics.value.withBlockHit().let { metrics.value }
```
The `.let { metrics.value }` discards the `withBlockHit()` result and assigns the old
`metrics.value` back to itself. The counter is never incremented.

Fix: replace with:
```kotlin
metrics.value = metrics.value.withBlockHit()
```

This is a one-line change. The same pattern may appear at other call sites in `CacheCore.kt`
— audit all `withBlockHit()`, `withPageHit()`, `withHierarchyHit()` usages.

### Task 1.3 — Audit and remove or fix `jvmMain` cache layer

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cache/` (all four files:
`CacheCore.kt`, `BlockCache.kt`, `CachedBlockRepository.kt`, `PageCache.kt`)

The `jvmMain` cache layer wraps `BlockRepository` and `PageRepository` with a second LRU
implementation. `RepositoryFactoryImpl.createBlockRepository()` instantiates
`SqlDelightBlockRepository` directly — `CachedBlockRepository` is not wired in production.

Verification steps:
1. Grep the entire `kmp/src/` tree for `CachedBlockRepository` and `CachedPageRepository`
   instantiation sites. If no production call site exists outside `jvmMain/cache/`, this is
   confirmed dead code.
2. Check `RepositoryFactoryImpl` for any reference to `BlockCache` or `PageCache`. Currently
   neither appears in `RepositoryFactory.kt`.
3. If confirmed dead: delete all four files. They duplicate the `commonMain` `LruCache` with
   weaker semantics and wrong dispatchers. No test coverage is lost (the benchmarks test
   `SqlDelightBlockRepository` directly).
4. If any production path uses them: fix `flowOn(Dispatchers.IO)` → `flowOn(PlatformDispatcher.DB)`
   at every call site before removing (see Task 1.4).

The `LRUCache` in `CacheCore.kt` has the O(n) eviction bug (Task 1.5) and the wrong dispatcher
bug (Task 1.4). Deleting these files eliminates both bugs at once.

### Task 1.4 — Fix `flowOn(Dispatchers.IO)` in jvmMain cache layer

**Files**: `BlockCache.kt` and `PageCache.kt` in `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cache/`

Every `flow { }.flowOn(Dispatchers.IO)` in `BlockCache.kt` (lines 72, 141, 174, 196, 229)
and `PageCache.kt` (lines 64, 91, 128, 145, 162, 179, 196) must be changed to
`flowOn(PlatformDispatcher.DB)`.

This is only necessary if Task 1.3 determines the code is live. If the files are deleted, skip
this task.

Rationale: `PlatformDispatcher.DB` is `Dispatchers.IO` on JVM but `Dispatchers.Default` on
iOS. Hardcoding `Dispatchers.IO` bypasses the abstraction and would cause incorrect behavior on
iOS where the native driver is not thread-safe in the same way.

### Task 1.5 — Fix O(n) LRU eviction in `CacheCore.LRUCache`

**File**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cache/CacheCore.kt`

`updateLRU()` calls `ConcurrentLinkedQueue.remove(key)` which is documented as O(n). At 10 000
entries this scans the entire queue on every cache access.

The `commonMain` `LruCache` already implements correct O(1) LRU using a `LinkedHashMap` with
insertion-order mode, manually moving accessed entries to the tail. The `jvmMain` `LRUCache`
should either be deleted (preferred, per Task 1.3) or restructured to eliminate the
`ConcurrentLinkedQueue` and use the same `LinkedHashMap` remove-and-reinsert pattern protected
by a `Mutex`.

If deleting, this task is complete as a side effect of Task 1.3.

### Task 1.6 — Fix `hierarchyCacheTimestamps` data race

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`,
line 855

```kotlin
private val hierarchyCacheTimestamps = mutableMapOf<String, Long>()
```

This plain `HashMap` is accessed from multiple coroutines on `PlatformDispatcher.DB` (which
maps to `Dispatchers.IO` on JVM — a thread pool). Concurrent access causes undefined behavior.

Fix: fold the timestamp into the `LruCache` value type. Replace the separate `LruCache<String,
List<BlockWithDepth>>` hierarchy cache with `LruCache<String, HierarchyCacheEntry>` where:

```kotlin
private data class HierarchyCacheEntry(
    val blocks: List<BlockWithDepth>,
    val cachedAtMs: Long,
)
```

Then `isHierarchyCacheExpired()` reads `entry.cachedAtMs` from within the `LruCache.get()`
lock instead of from the separate `mutableMapOf`. The `hierarchyCacheTimestamps` map is
removed entirely. The existing `Mutex` in `LruCache` covers the timestamp atomically.

Apply the same pattern to `ancestorsCache` if an equivalent TTL check is added in future.

---

## Story 2 — Cache Invalidation Wiring

**Depends on Story 1 being complete.**

After the bug fixes, the `commonMain` `LruCache` is correct but still not connected to writes.
A write through `DatabaseWriteActor` updates SQLDelight (which triggers `notifyListeners` for
reactive flows) but does not evict entries from `blockCache` or `hierarchyCache`. External file
changes bypass both.

### Task 2.1 — Add `WriteSideEffect` callback to `DatabaseWriteActor`

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`

Add a nullable side-effect callback that fires after each successful write, before the
`CompletableDeferred` is completed. The callback fires before `deferred.complete()` to prevent
a window where a reader observes the "write done" signal before the cache evicts the stale entry.

```kotlin
/** Called after a successful write, before the caller's deferred is completed. */
var onWriteSuccess: (suspend (WriteRequest) -> Unit)? = null
```

Wire it in `processRequest` and `flushBatch` at every `deferred.complete(Result.success(...))` site. Example pattern in `flushBatch`:

```kotlin
val result = blockRepository.saveBlocks(allBlocks)
if (result.isSuccess) {
    logSaveBlocks(allBlocks, existingByUuid)
    onWriteSuccess?.invoke(first)       // evict before completing
    batch.forEach { it.deferred.complete(Result.success(Unit)) }
}
```

The callback is a `suspend` lambda so it can call `suspend fun remove()` on `LruCache`.

### Task 2.2 — Wire actor callback to repository cache eviction

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt`
(inside `createRepositorySet`)

After the actor is constructed, wire the callback to evict entries from
`SqlDelightBlockRepository`'s caches. This requires `SqlDelightBlockRepository` to expose
eviction methods:

```kotlin
// Add to SqlDelightBlockRepository:
suspend fun evictBlock(uuid: String) { blockCache.remove(uuid) }
suspend fun evictHierarchyForPage(pageUuid: String) { hierarchyCache.invalidateAll() }
```

Then in `createRepositorySet`:

```kotlin
val sqlBlockRepo = blockRepo as? SqlDelightBlockRepository
if (sqlBlockRepo != null) {
    actor.onWriteSuccess = { request ->
        when (request) {
            is WriteRequest.SaveBlocks ->
                request.blocks.forEach { sqlBlockRepo.evictBlock(it.uuid) }
            is WriteRequest.SaveBlock ->
                sqlBlockRepo.evictBlock(request.block.uuid)
            is WriteRequest.DeleteBlocksForPage ->
                sqlBlockRepo.evictHierarchyForPage(request.pageUuid)
            else -> Unit
        }
    }
}
```

Note: `getBlocksForPage` flows via SQLDelight's `asFlow()` + `mapToList()`, which already
re-emits after writes through `notifyListeners`. The explicit eviction targets only the
`blockCache` (single-block lookups) and `hierarchyCache` (hierarchy traversal) — the reactive
flows for page-level lists need no manual invalidation.

### Task 2.3 — Wire external file changes to cache invalidation

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` (or wherever
the graph's `CoroutineScope` and `GraphLoader` are wired)

`GraphLoader.externalFileChanges` is a `SharedFlow` that emits when an external editor writes
a markdown file. Currently nothing invalidates the `blockCache` or `hierarchyCache` on these
events.

Wire a collector in the graph's coroutine scope:

```kotlin
scope.launch {
    graphLoader.externalFileChanges.collect { pageUuid ->
        // Reload the page through the write path (which triggers notifyListeners)
        // and explicitly evict LRU entries for the affected page.
        sqlBlockRepo?.evictHierarchyForPage(pageUuid)
        // The GraphLoader's existing reload logic handles re-importing the file;
        // this eviction ensures stale LRU entries don't shadow the fresh DB state.
    }
}
```

Confirm that `GraphLoader.loadPage()` writes through `DatabaseWriteActor` (not directly to
`SqlDelightBlockRepository`) so that SQLDelight's `notifyListeners` fires and reactive flows
see the new data.

### Task 2.4 — Apply `.conflate()` to `getBlocksForPage`

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`,
line 187

During bulk graph import, each `SaveBlocks` batch write triggers `notifyListeners`, which
re-executes the `selectBlocksByPageUuidUnpaginated` query. If the ViewModel has a collector,
this causes a query per batch. Add `.conflate()` to skip intermediate values when the collector
is behind:

```kotlin
override fun getBlocksForPage(pageUuid: String): Flow<Result<List<Block>>> =
    queries.selectBlocksByPageUuidUnpaginated(pageUuid)
        .asFlow()
        .mapToList(PlatformDispatcher.DB)
        .conflate()
        .map { list -> success(list.map { it.toBlockModel() }) }
```

This is consistent with how `getAllPages()` is handled elsewhere in the codebase.

### Task 2.5 — Add `stateIn(WhileSubscribed)` at ViewModel layer for `getBlocksForPage`

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
(or the composable where `getBlocksForPage` is first collected)

The hottest read path — loading a page's blocks — currently has N independent SQLDelight query
subscribers when multiple composables collect the same `getBlocksForPage` flow. Each subscriber
runs an independent DB query.

Apply `stateIn(SharingStarted.WhileSubscribed(5_000))` at the point where the flow enters the
ViewModel:

```kotlin
// In StelekitViewModel or a dedicated PageViewModel:
private val _blocksForPage: StateFlow<Result<List<Block>>> =
    blockRepository.getBlocksForPage(currentPageUuid)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = Result.success(emptyList())
        )

val blocksForPage: StateFlow<Result<List<Block>>> = _blocksForPage
```

The 5-second timeout means the query stays alive for 5 seconds after the last subscriber
disappears (e.g., during a brief navigation transition) before the upstream is cancelled. This
avoids redundant DB round-trips during back-navigation.

This is the primary throughput improvement: N Compose subscribers share one DB query instead
of each owning an independent one. SQLDelight's `notifyListeners` already handles re-emission
after writes — no manual invalidation is required for this flow.

### Task 2.6 — Wire `RequestCoalescer` into `SqlDelightPageRepository`

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightPageRepository.kt`

`RequestCoalescer` exists in `commonMain` but is not wired into any production repository.
Wire it for `getPageByName` and `getPageByUuid`, the two hottest page lookup paths:

```kotlin
private val coalescer = RequestCoalescer<String, Page?>()

override fun getPageByName(name: String): Flow<Result<Page?>> = flow {
    val page = coalescer.execute("name:$name") {
        queries.selectPageByName(name).executeAsOneOrNull()?.toModel()
    }
    emit(Result.success(page))
}.flowOn(PlatformDispatcher.DB)
```

This ensures that if multiple coroutines race to look up the same page name during graph load,
only one DB round-trip executes. The result is shared among all callers.

Note: `RequestCoalescer` is singleflight — it only deduplicates concurrent in-flight calls.
Once the call completes, the next call starts a fresh load. For persistent memoization, the
existing `blockCache` in `SqlDelightBlockRepository` is the right tool.

---

## Story 3 — Observability and Regression Gate

**Can be implemented concurrently with Story 2. Benefits from Story 1 being complete so
metrics reflect real cache behavior.**

### Task 3.1 — Route `commonMain` `LruCache` metrics to `HistogramWriter`

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/cache/LruCache.kt` and
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

Add hit/miss/eviction counters to `LruCache` and expose them so `HistogramWriter` can record
them as metrics.

`LruCache` changes:
- Add `private var hits = 0L`, `private var misses = 0L`, `private var evictions = 0L`
  fields (all under the existing `Mutex`).
- Increment `hits` in `get()` on success, `misses` in `get()` on null return.
- Increment `evictions` in `evict()` per removed entry.
- Expose `suspend fun snapshot(): CacheStats` that returns a value object with the three
  counters under the mutex.

`SqlDelightBlockRepository` changes:
- Expose a `suspend fun cacheStats(): CacheStats` that calls `blockCache.snapshot()`.

`RepositoryFactoryImpl` / `HistogramWriter` changes:
- In the existing 5-second drain loop, also call `cacheStats()` and record them as histograms
  with attribute `cache.name = "block"` / `"hierarchy"`.

### Task 3.2 — Add pool wait-time metric to `PooledJdbcSqliteDriver`

**File**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/db/PooledJdbcSqliteDriver.kt`

`pool.take()` on line 61 blocks when all 8 connections are in use. This wait time is invisible
in the current metrics. Add instrumentation:

```kotlin
// In getConnection():
val start = System.nanoTime()
val conn = pool.take()
val waitMs = (System.nanoTime() - start) / 1_000_000L
if (waitMs > 1L) {
    ringBuffer?.record(SerializedSpan(
        name = "db.pool_wait",
        durationMs = waitMs,
        attributes = mapOf("pool.size" to poolSize.toString()),
        statusCode = if (waitMs > 100L) "ERROR" else "OK",
        // ... traceId/spanId
    ))
}
```

Wire `ringBuffer` into `PooledJdbcSqliteDriver` (it is already available on `DatabaseWriteActor`;
pass it into the driver via `DriverFactory.jvm.kt` when constructing the driver, or set it after
construction in `RepositoryFactoryImpl`).

### Task 3.3 — Add Android `onTrimMemory` hook

**File**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/` (Application class or entry point)

Respond to system memory pressure by evicting caches. The Android documentation specifies
`TRIM_MEMORY_RUNNING_CRITICAL` (level 15) and `TRIM_MEMORY_BACKGROUND` (level 40) as the
appropriate levels for aggressive eviction.

```kotlin
// In Application.onTrimMemory or ComponentActivity.onTrimMemory:
override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
        graphManager.forEachRepositorySet { set ->
            (set.blockRepository as? SqlDelightBlockRepository)
                ?.let { scope.launch { it.clearAllCaches() } }
        }
    }
}
```

`SqlDelightBlockRepository.clearAllCaches()` calls `blockCache.invalidateAll()`,
`hierarchyCache.invalidateAll()`, `ancestorsCache.invalidateAll()`.

### Task 3.4 — Add `PRAGMA wal_checkpoint(TRUNCATE)` after bulk graph import

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`

After a full graph import completes (all pages and blocks written), the WAL file can grow
large. Issue a checkpoint to fold WAL frames back into the main database file, reducing the
WAL read overhead on subsequent cold starts:

```kotlin
// After graph import completes, inside DatabaseWriteActor.execute:
actor.execute(priority = Priority.LOW) {
    withContext(PlatformDispatcher.DB) {
        queries.pragmaWalCheckpointTruncate()
        Result.success(Unit)
    }
}
```

Add the pragma to `SteleDatabase.sq`:
```sql
pragmaWalCheckpointTruncate:
PRAGMA wal_checkpoint(TRUNCATE);
```

Do not call this during normal editing — only after bulk import. Checkpoint during editing
would block readers and is the WAL hazard described in the research.

### Task 3.5 — Write a benchmark asserting cache hit on second page navigation

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/RepositoryBenchmark.kt`
(or a new `CacheHitBenchmark.kt` in the same package)

Add a test that:
1. Loads a page (first navigation — cold miss).
2. Records the number of SQL statements executed (via `TimingDriverWrapper` span count or a
   statement counter on `PooledJdbcSqliteDriver`).
3. Navigates away (drops the `stateIn` subscription if within the 5-second window).
4. Navigates back to the same page within 5 seconds.
5. Asserts that the second navigation executes zero block-query SQL statements (because
   `stateIn(WhileSubscribed(5_000))` kept the upstream alive and the `blockCache` served the
   hierarchy query).

This benchmark gates Story 2's correctness: if the cache is not wired, the second navigation
will still execute SQL statements and the assertion will fail.

---

## Known Issues

### Bug: `hierarchyCacheTimestamps` data race (Story 1, Task 1.6) — Severity: High

`mutableMapOf<String, Long>()` in `SqlDelightBlockRepository` is accessed from multiple
`PlatformDispatcher.DB` coroutines (= `Dispatchers.IO` on JVM, a thread pool). This is a
read-then-write race on a non-thread-safe map. Observable as intermittent wrong TTL checks.

Mitigation: fold the timestamp into the `LruCache` value type (Task 1.6). The `LruCache`
`Mutex` covers the timestamp atomically.

Files affected: `SqlDelightBlockRepository.kt` (lines 855, 857–859)

### Bug: LRUCache metrics counter not incremented (Story 1, Task 1.2) — Severity: Medium

All hit-rate metrics for the `jvmMain` cache layer are permanently zero due to `.let {
metrics.value }` discarding the `withBlockHit()` result. Any cache tuning decision made
before this fix is based on incorrect data.

Files affected: `CacheCore.kt` line 147

### Bug: Cache not invalidated on write (Story 2, Task 2.2) — Severity: High

`DatabaseWriteActor` updates SQLDelight (triggering `notifyListeners`) but does not evict
`blockCache` or `hierarchyCache` entries. A reader that calls `getBlockHierarchy` between a
write commit and a `notifyListeners` re-query sees stale data from the LRU cache.

Files affected: `DatabaseWriteActor.kt`, `SqlDelightBlockRepository.kt`,
`RepositoryFactory.kt`

### Bug: External file changes bypass cache invalidation (Story 2, Task 2.3) — Severity: High

When an external editor saves a markdown file, `GraphLoader.externalFileChanges` emits, but
no code calls `blockCache.remove()` or `hierarchyCache.invalidateAll()`. The cached hierarchy
remains stale for up to the hierarchy TTL (2 minutes) even though the DB has been updated.

Files affected: `GraphLoader.kt` integration point, `GraphManager.kt`

### Bug: `flowOn(Dispatchers.IO)` in jvmMain cache (Story 1, Task 1.4) — Severity: Medium

Five call sites in `BlockCache.kt` and seven in `PageCache.kt` hardcode `Dispatchers.IO`.
On iOS, `PlatformDispatcher.DB` maps to `Dispatchers.Default` (the native driver is
single-threaded). This would cause incorrect behavior if the `jvmMain` cache layer were ever
used on iOS (currently it cannot be, since it is in `jvmMain`, but the bug illustrates why
the abstraction exists).

Files affected: `BlockCache.kt`, `PageCache.kt`

### Risk: sqlite-jdbc WAL data race (Story 1, Task 1.1) — Severity: Critical (data safety)

SQLite versions 3.7.0–3.51.2 have a TSAN-confirmed data race when a concurrent write and WAL
checkpoint occur. This can cause silent database corruption. The current sqlite-jdbc version
bundled in `app.cash.sqldelight:sqlite-driver` must be verified. If it bundles a version
before 3.51.3, an explicit override is required.

Files affected: `kmp/build.gradle.kts`

---

## Implementation Order

```
Task 1.1 (sqlite-jdbc version check + upgrade)
    |
Task 1.2 (LRUCache metrics counter fix)
    |
Task 1.3 (audit and delete jvmMain cache layer)
    |-- if live: Task 1.4 (fix flowOn) + Task 1.5 (fix O(n) eviction)
    |-- if dead: Tasks 1.4 and 1.5 are resolved by deletion
    |
Task 1.6 (hierarchyCacheTimestamps race fix)
    |
    +-------- Story 2 --------+
    |                         |
Task 2.1 (WriteSideEffect)    Task 3.1 (LruCache metrics)
Task 2.2 (actor -> eviction)  Task 3.2 (pool wait metric)
Task 2.3 (file watcher)       Task 3.3 (onTrimMemory)
Task 2.4 (conflate)
Task 2.5 (stateIn ViewModel)
Task 2.6 (RequestCoalescer)
    |
Task 3.4 (WAL checkpoint after import)
Task 3.5 (cache-hit benchmark)
```

---

## Acceptance Criteria

Story 1 is complete when:
- `sqlite-jdbc` version is confirmed ≥ 3.51.3 or an explicit override is in `build.gradle.kts`
- `LRUCache.get()` increments its hit counter correctly (verified by a unit test that reads
  back `getMetrics().blockHits`)
- `jvmMain` cache layer is deleted (preferred) or all `Dispatchers.IO` usages are replaced
  with `PlatformDispatcher.DB`
- `hierarchyCacheTimestamps` is removed; timestamp is embedded in `HierarchyCacheEntry`
- All existing `jvmTest` and `businessTest` pass

Story 2 is complete when:
- A write through `DatabaseWriteActor.saveBlocks()` causes `blockCache.get(blockUuid)` to
  return null on the next call (eviction happened)
- A `GraphLoader.externalFileChanges` emission causes the hierarchy cache to be cleared
- `getBlocksForPage` has `.conflate()` applied
- `StelekitViewModel` (or equivalent) exposes `blocksForPage` as a `StateFlow` using
  `stateIn(WhileSubscribed(5_000))`
- The cache-hit benchmark in Task 3.5 passes

Story 3 is complete when:
- `LruCache` hit/miss/eviction counts appear in the `HistogramWriter` output
- Pool wait-time spans appear in the ring buffer when pool contention occurs
- Android `onTrimMemory` is wired and tested with a manual memory pressure simulation
- `PRAGMA wal_checkpoint(TRUNCATE)` executes after a graph import test
