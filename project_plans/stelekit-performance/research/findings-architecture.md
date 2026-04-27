# Findings: Architecture

**Dimension**: Read Concurrency, Cache Invalidation, and Mobile SQLite Tuning in KMP + SQLDelight
**Date**: 2026-04-24
**Status**: Complete (training knowledge; web searches pending — see final section)

---

## Summary

SteleKit already has a solid foundation: WAL mode is on for both JVM and Android, an 8-connection
pool exists on JVM, `DatabaseWriteActor` serializes writes, and a weight-bounded `LruCache` with
`Mutex` guards exist in `commonMain`. The current architecture is sound; the gaps are:

1. Cache invalidation on write is **not wired** into `DatabaseWriteActor`. The `LruCache` in
   `SqlDelightBlockRepository` / `SqlDelightPageRepository` is populated on read and invalidated
   on write *only within the same repository instance*. Cross-cutting invalidation (e.g., a write
   actor save that should bust the hierarchy cache) does not happen automatically.
2. `SqlDelightBlockRepository.getBlockHierarchy` is a one-shot cold flow, not a reactive hot
   flow. Every new subscriber re-runs the BFS query. With `stateIn(WhileSubscribed)` applied at
   the ViewModel layer, the DB is only queried once per active subscriber window.
3. The JVM pool size (8) and Android `busy_timeout` (10 s vs JVM's 30 s) are not tuned against
   measured contention data. WAL enables concurrent reads, but whether 8 is the sweet spot depends
   on benchmark data that does not yet exist.
4. The `PageNameIndex` uses `stateIn(SharingStarted.WhileSubscribed)` correctly; the pattern is
   not yet applied uniformly to the heavier block-load flows.

The six patterns below are evaluated against the current codebase state.

---

## Options Surveyed

### Option 1 — Repository-Layer In-Memory Cache with `StateFlow`

**Concept**: The repository owns a `MutableStateFlow<Map<pageUuid, List<Block>>>`. On write, the
actor invalidates or updates the map. Downstream composables collect the `StateFlow` directly;
no DB query runs unless the key is absent.

**Fit with current code**: `LruCache` + `Mutex` already exists in `SqlDelightBlockRepository`.
The missing piece is making eviction reactive: instead of returning `null` on miss and leaving
the caller to re-query, the cache should emit a new value into a `StateFlow` whenever the
`DatabaseWriteActor` completes a write. This requires the actor to call back into the repository
(or an event bus) after each successful write.

**Implementation sketch**:
```kotlin
// In DatabaseWriteActor, after processRequest succeeds:
onWriteComplete?.invoke(WriteEvent(pageUuid, blocksAffected))

// In SqlDelightBlockRepository:
private val _pageBlocks = MutableStateFlow<Map<String, List<Block>>>(emptyMap())

fun getBlocksForPage(pageUuid: String): Flow<Result<List<Block>>> =
    _pageBlocks.map { map ->
        map[pageUuid]?.let { Result.success(it) }
            ?: loadAndCache(pageUuid)
    }

// Actor calls: repository.invalidate(pageUuid) after each write
```

**Correctness caveat**: The actor must complete the write *before* calling `invalidate`, and the
`StateFlow` update must be visible to all coroutines collecting it before the next read. Because
`MutableStateFlow.value` assignments are immediately visible on all threads (backed by `@Volatile`),
this is safe as long as the actor calls `invalidate` synchronously in the same coroutine that did
the write, before completing the `CompletableDeferred`.

---

### Option 2 — SQLDelight `shareIn` / `stateIn` with `WhileSubscribed`

**Concept**: Wrap the upstream SQLDelight `.asFlow().mapToList(DB)` with `.stateIn(scope,
SharingStarted.WhileSubscribed(5_000), emptyList())`. While at least one subscriber is active, the
DB query runs exactly once; all new collectors share the same emission. The 5-second stop timeout
keeps the query alive during brief navigation transitions.

**Fit with current code**: `SqlDelightPageRepository.getAllPages()` already uses `.asFlow()`. The
`PageNameIndex` already applies `stateIn(WhileSubscribed)`. The pattern is not applied in
`SqlDelightBlockRepository.getBlocksForPage()`, which is the hottest path during page load.

**Key property**: SQLDelight's `notifyListeners` (called by `PooledJdbcSqliteDriver` after each
write) re-emits the upstream `asFlow()`, which propagates through `stateIn`. This is automatic
cache invalidation at zero cost — no manual invalidation hook needed.

**Risk**: `WhileSubscribed` means if all composables navigating away causes a 5-second window, the
next navigation triggers a fresh DB query. This is acceptable — it is exactly the cost of
"eventually consistent with DB."

**Recommendation fit**: This is the **lowest-complexity, highest-correctness** pattern for list
queries that are already expressed as SQLDelight `asFlow()`. Apply to `getBlocksForPage` and
`getAllPages` ViewModel layers.

---

### Option 3 — Read-Through Cache with Coroutine `Mutex`

**Concept**: Serialize cache reads and writes with a `Mutex`. On miss, hold the mutex while
loading from DB; put the result in the cache; release.

**Fit with current code**: This is exactly what `LruCache` in `commonMain` implements today.
The `Mutex` is already there. The `RequestCoalescer` (`singleflight` pattern) in
`SqlDelightPageRepository` extends this with deduplication of concurrent identical lookups.

**Gaps identified**:
- `blockCache` and `hierarchyCache` in `SqlDelightBlockRepository` are `suspend fun` backed by
  `Mutex`, but invalidation after writes is not connected to the actor. A write to blocks does not
  call `blockCache.invalidateAll()` or `hierarchyCache.remove(pageUuid)`.
- The `hierarchyCache` uses a manual `hierarchyCacheTimestamps` map for TTL tracking, which is a
  separate (non-Mutex-protected) `mutableMapOf`. This is a correctness risk under concurrent
  coroutine access.

**Action**: Wire the actor's `processRequest` success path to call `repository.onWrite(pageUuid)`,
which calls `blockCache.remove(uuid)` and `hierarchyCache.remove(pageUuid)`. This adds ~1 µs to
each write and eliminates stale hierarchy reads.

---

### Option 4 — SQLite WAL Mode + Connection Pool Tuning

**Current state**: WAL is already enabled on JVM (via `Properties` in `DriverFactory.jvm.kt`)
and on Android (via `PRAGMA journal_mode=WAL` in `DriverFactory.android.kt`). Pool size is 8 on
JVM, 1 for in-memory. `busy_timeout` is 30 s on JVM, 10 s on Android.

**WAL mode semantics in the current design**:
- WAL allows N concurrent readers + 1 writer without blocking reads.
- `DatabaseWriteActor` ensures at most one writer, so WAL's reader-writer non-blocking guarantee
  is fully utilized.
- The 8-connection pool on JVM bounds connection creation; all 8 connections can read
  simultaneously while the actor holds one for writing.

**Tuning opportunities**:
- `cache_size`: default is -2000 (2 MB page cache). For a notes app with many small pages and
  blocks, increasing to -8000 (8 MB) may reduce I/O on JVM. [TRAINING_ONLY — verify]
- `mmap_size`: Memory-mapping the database file reduces syscall overhead on JVM/Linux. Setting
  `mmap_size=268435456` (256 MB) moves reads from `read()` syscalls to page-fault-driven memory
  access. Benchmark required. [TRAINING_ONLY — verify]
- `wal_autocheckpoint`: Default is 1000 pages. For a mostly-read workload, raising this to 4000
  reduces checkpoint frequency but increases WAL file size. [TRAINING_ONLY — verify]
- **Android pool**: `AndroidSqliteDriver` + `RequerySQLiteOpenHelperFactory` does not expose a
  pool size parameter. The current `busy_timeout` of 10 s is adequate for write serialization
  via actor; could be lowered to 5 s if actor queuing is working correctly.
- **iOS**: `PlatformDispatcher.DB` maps to `Dispatchers.Default` (GCD handles threading). No JDBC
  pool. The native SQLite driver is single-connection; WAL still benefits concurrent reads from
  multiple GCD threads. [TRAINING_ONLY — verify native driver behavior]

---

### Option 5 — Separate Read Replica

**Concept**: Open a second `SqlDriver` instance pointing to the same WAL-mode database file.
Reads use the replica driver; writes use the primary driver via `DatabaseWriteActor`. WAL mode
allows multiple connections to the same file, so readers on the replica see a consistent snapshot
from the last WAL checkpoint.

**Fit with SteleKit**: This would require `RepositoryFactoryImpl` to instantiate two drivers per
graph and wire repositories accordingly — significant architectural change. The benefit over the
current 8-connection pool is unclear: the pool already allows 8 concurrent readers, which exceeds
the typical number of concurrently active coroutines in a single-user UI.

**Correctness risk (critical)**: In SQLite WAL mode, a reader on connection A sees the state as of
the moment it opened its read transaction. If the write actor commits on connection B, a reader
that opened before the write will not see it until it starts a new transaction. The
`notifyListeners` mechanism fires `queryResultsChanged()` immediately after a write commits — but
if the replica connection has an open read transaction, it will re-query and still see the
pre-write snapshot until it releases and re-acquires a read lock.

**Verdict**: Not recommended. The existing 8-connection pool already provides parallelism. The
replica pattern adds complexity with no meaningful throughput gain for a single-user app, and
introduces a correctness hazard around snapshot visibility.

---

### Option 6 — `Channel`-Based Read Fan-Out

**Concept**: A single coroutine reads from the DB and pushes results into a `SharedFlow` (or
`BroadcastChannel`, deprecated since Kotlin 1.5). All UI subscribers collect the shared hot flow.

**Fit with current code**: This is effectively what SQLDelight's `.asFlow().shareIn(...)` already
does. The `asFlow()` extension registers a `Query.Listener`; when `notifyListeners` fires, it
re-emits. The `shareIn` operator multicasts to all subscribers. Implementing a custom
`Channel`-based fan-out would duplicate SQLDelight's reactive pipeline with no benefit.

**Verdict**: Not recommended as a standalone pattern. Subsumed by Option 2 (`stateIn` /
`shareIn`) which is cleaner and already idiomatic in the codebase
(`PageNameIndex`, `AllPagesViewModel`).

---

## Trade-off Matrix

| Axis | Option 1 (StateFlow cache) | Option 2 (stateIn) | Option 3 (Mutex cache) | Option 4 (WAL tuning) | Option 5 (Read replica) | Option 6 (Channel fan-out) |
|---|---|---|---|---|---|---|
| **Consistency** | Write-invalidated; stale between write and invalidation | DB-driven; always fresh after `notifyListeners` | TTL + manual invalidation; can go stale | Always fresh (DB truth) | Snapshot-bounded; may lag | Same as Option 2 |
| **Read throughput** | Cache hit = zero DB calls | One DB call per active subscriber window | Cache hit = zero DB calls | 8 concurrent reads (pool) + mmap | Two pools; marginal gain | Same DB query rate as Option 2 |
| **Cache invalidation complexity** | High — actor must callback repository | Zero — SQLDelight reactive pipeline handles it | Medium — TTL + manual hooks needed | N/A | High — snapshot staleness hazard | Zero (same as Option 2) |
| **Code complexity** | Medium — new callback interface on actor | Low — one operator added per repository method | Low — already implemented; close gaps | Low — PRAGMA values only | High — dual driver lifecycle | Low — already subsumed |
| **Correctness under concurrent writes** | Safe if invalidation is synchronous in actor | Safe — SQLDelight listener fires post-commit | Safe — Mutex serializes; TTL prevents perpetual stale | Safe — WAL native | Hazardous — snapshot lag | Safe |
| **KMP compatibility** | Full (commonMain) | Full (commonMain) | Full (already in commonMain) | Partial — JDBC only on JVM; Android uses PRAGMA | Partial | Full |
| **Migration cost** | Medium — actor interface change | Low | Low — gap-fill only | Low | High | None |

---

## Risk and Failure Modes

### Risk 1: Cache invalidation miss after actor write

**Pattern affected**: Options 1 and 3.

**Scenario**: `DatabaseWriteActor.processRequest` completes `deferred` before calling the
repository's invalidation callback. A racing read collector receives the "write done" signal (via
`CompletableDeferred.await()`) and calls `getBlocksForPage`, hitting the stale cache entry before
the invalidation fires.

**Mitigation**: Invalidate the cache *inside* the actor's processing coroutine, before completing
`deferred`. The actor already runs all writes sequentially; adding `repo.invalidate(pageUuid)` at
the top of the success path is safe.

### Risk 2: SQLDelight `asFlow` notification storm during bulk import

**Pattern affected**: Option 2.

**Scenario**: `GraphLoader` saves 4,000 blocks in a loop through the actor (low-priority queue).
Each `saveBlocks` commit fires `notifyListeners`, which re-emits every registered `asFlow()`. With
20 active subscribers on `getAllPages()`, a 4,000-page import fires 80,000 recompose events.

**Current mitigation**: `SqlDelightPageRepository.getAllPages()` already applies `.conflate()` to
drop intermediate invalidations. Verify `getBlocksForPage` also has `.conflate()`.

**Additional mitigation**: Apply `.conflate()` before `.stateIn()` on any `asFlow()` that can
receive burst invalidations during import.

### Risk 3: `hierarchyCacheTimestamps` race condition

**Pattern affected**: Option 3.

**Scenario**: `hierarchyCacheTimestamps` in `SqlDelightBlockRepository` is a `mutableMapOf<String,
Long>()` — a `LinkedHashMap` — accessed from multiple coroutines on `PlatformDispatcher.DB`
without a lock. Two coroutines calling `getBlockHierarchy` concurrently for different pages can
race on the timestamp write.

**Mitigation**: Fold the timestamp into the `LruCache` value itself (a `data class
TimestampedHierarchy(val blocks: ..., val cachedAt: Long)`) so the `Mutex` in `LruCache` covers
both the data and its timestamp.

### Risk 4: WAL file growth on Android without checkpoint pressure

**Pattern affected**: Option 4.

**Scenario**: SQLite's default `wal_autocheckpoint` is 1000 pages. On Android, if `busy_timeout`
prevents the checkpointer from acquiring the write lock during heavy read concurrency, the WAL
file can grow unboundedly. For a notes app, this is unlikely in practice but possible after a
large import.

**Mitigation**: Keep `wal_autocheckpoint` at default (1000). Monitor WAL file size in
`PerfExporter`. Optionally call `PRAGMA wal_checkpoint(PASSIVE)` after the actor drains the
low-priority queue post-import. [TRAINING_ONLY — verify SQLite behavior on Android]

### Risk 5: `stateIn(SharingStarted.Eagerly)` scope leak

**Pattern affected**: Option 2, current `AllPagesViewModel`.

**Scenario**: `AllPagesViewModel` uses `SharingStarted.Eagerly` — the DB query starts immediately
and never stops, even if no subscriber is listening. For the `pages` StateFlow this is fine
(the ViewModel owns its scope). If applied at the repository layer with a long-lived scope, it
keeps a DB connection busy indefinitely.

**Mitigation**: Use `SharingStarted.WhileSubscribed(5_000)` at the repository layer; reserve
`Eagerly` for ViewModel-layer aggregations that are always needed while the screen is visible.

---

## Migration and Adoption Cost

| Change | Scope | Effort | Risk |
|---|---|---|---|
| Wire actor invalidation to `LruCache` (Options 1+3 gap-fill) | `DatabaseWriteActor`, block and page repositories | S (2–4 h) | Low — additive change, no interface break |
| Apply `.stateIn(WhileSubscribed)` at ViewModel layer for block flows | `StelekitViewModel`, one or two screen VMs | S (1–2 h) | Low |
| Apply `.conflate()` to `getBlocksForPage` | `SqlDelightBlockRepository` | XS (<30 min) | Low |
| Fix `hierarchyCacheTimestamps` race (fold into `LruCache`) | `SqlDelightBlockRepository` | S (1 h) | Low |
| Tune WAL PRAGMAs (`cache_size`, `mmap_size`) on JVM | `DriverFactory.jvm.kt` | XS (<30 min) | Low — requires benchmark validation |
| Implement full `StateFlow` map cache (Option 1 full) | `SqlDelightBlockRepository`, actor callback interface | M (1–2 days) | Medium — new contracts, needs thorough testing |
| Separate read replica (Option 5) | `RepositoryFactoryImpl`, all repositories | L (3–5 days) | High |

---

## Operational Concerns

### Cache warming after graph switch

When `GraphManager.switchGraph` is called, the old factory is closed and all caches are dropped
(they are instance-level in the repositories). The first load after a switch will be cold for all
caches. For a single-user app this is acceptable — cold load happens once per switch and is
amortized over the session.

### Memory pressure on mobile

`RepoCacheConfig.fromPlatform()` sizes the cache at 10% of JVM max heap. On Android with a 192 MB
heap limit, 10% = 19.2 MB. The `LruCache` weight-based eviction prevents unbounded growth, but
the older `CacheCore.kt` in `jvmMain` has separate `ConcurrentHashMap`-backed storage with TTL
eviction that is not weight-bounded. **If `CacheCore.kt` is still on the production path, it is a
memory risk.** Verify and remove if unused.

### WAL checkpoint and battery life on Android

WAL checkpoints write dirty WAL pages back to the main database file. On Android, unnecessary
checkpoints burn I/O and battery. The current actor design — one writer at a time — minimizes
write amplification. No additional tuning is needed unless profiling reveals the WAL file growing
past 10 MB during normal use.

### iOS native driver

On iOS, `PlatformDispatcher.DB = Dispatchers.Default`. The native SQLite driver used by SQLDelight
on iOS is effectively single-connection; WAL is still beneficial but concurrent reads from multiple
GCD threads share one connection through SQLDelight's thread-safety wrapper. The `LruCache` in
`commonMain` is the primary read-concurrency mechanism on iOS — optimizing cache hit rate matters
more than raw SQL concurrency on this platform. [TRAINING_ONLY — verify native driver threading]

---

## Prior Art and Lessons Learned

### SQLDelight reactive flow pattern (official)

SQLDelight's `asFlow()` + `mapToList()` is the canonical reactive read pattern. The library fires
`queryResultsChanged()` on every `notifyListeners` call after a write, which re-executes the
query. This is fundamentally a "always go to DB" pattern — the library optimizes query execution
but not result caching. Layering `stateIn(WhileSubscribed)` above it gives one-query-per-window
semantics at the ViewModel layer, which is the intended usage.

### Logseq (Electron/DataScript)

Logseq's DataScript backend uses an in-memory datom store; reads are always in-memory. The
structural analogy to SteleKit's Option 1 is: Logseq keeps the entire graph in RAM. For SteleKit,
this is impractical for large graphs but achievable per-page via the `StateFlow` map cache.

### Room (Android Jetpack)

Room's `@Query` + `Flow` return type uses `invalidationTracker` to fire observer callbacks when
a table changes — the same mechanism as SQLDelight's `notifyListeners`. Room does not add an
application-level cache; it expects ViewModels to use `stateIn`/`liveData`. The Room team's
recommended pattern matches Option 2 exactly.

### Realm (mobile object database)

Realm uses object-level change notifications rather than table-level. Writes propagate
automatically to all live query subscriptions. The analogy to SteleKit is: if every `saveBlocks`
call on the actor triggered a fine-grained listener keyed to `pageUuid`, reads from that page's
`StateFlow` would update immediately. This is achievable via Option 1 (actor callback +
`MutableStateFlow`), but adds per-page subscription bookkeeping.

### Caffeine / Guava `LoadingCache` (JVM)

`Caffeine` provides an extremely fast JVM cache with write-invalidation support but is JVM-only
and cannot live in `commonMain`. SteleKit's `LruCache` in `commonMain` is the correct
multiplatform equivalent. Caffeine would only be appropriate as a `jvmMain` override if benchmarks
show `Mutex` contention at the cache layer is a bottleneck.
[TRAINING_ONLY — Caffeine not currently a dependency]

---

## Open Questions

1. **Is `CacheCore.kt` (jvmMain) still on the live code path?** It contains a separate `LRUCache`
   with `ConcurrentHashMap`. If active alongside the `commonMain` `LruCache`, there are two
   parallel caches with different eviction strategies. Grep needed.

2. **What is the actual JDBC pool contention rate?** `TimingDriverWrapper` wraps the driver for
   query timing. Does it also measure connection acquisition time (`pool.take()` / `pool.poll()`)?
   If not, pool exhaustion during bulk import is invisible. A `HistogramWriter` metric for pool
   wait time would answer this.

3. **What does `platformCacheBytes()` return on Android in practice?** On Android with a 192 MB
   heap limit, 10% = 19.2 MB. Is this available or is the process already memory-constrained?
   ADB `dumpsys meminfo` during a page-load test would answer this.

4. **Does `.conflate()` on `getAllPages()` eliminate O(N²) notifications during bulk import?**
   There is no test asserting that fewer than N DB queries execute for N-page imports. A
   `TimingDriverWrapper` query count assertion in an integration test would verify this.

5. **What is `wal_autocheckpoint` currently set to?** Neither `DriverFactory.jvm.kt` nor
   `DriverFactory.android.kt` sets this PRAGMA. The SQLite default (1000 pages) applies.
   Confirm this is intentional.

6. **Is `stateIn(SharingStarted.Eagerly)` in `AllPagesViewModel` acceptable?** It keeps the full
   page list query alive indefinitely. For 10,000 pages, this is a large result set held in memory.
   Switching to `WhileSubscribed(5_000)` would release it when the screen is not visible.

---

## Recommendation

Apply changes in this priority order:

**Priority 1 — Immediate, low risk**:
- Fix `hierarchyCacheTimestamps` race: fold timestamp into the `LruCache` entry type so the
  existing `Mutex` covers it.
- Apply `.conflate()` to `getBlocksForPage` in `SqlDelightBlockRepository` (consistent with
  `getAllPages`).
- Verify `CacheCore.kt` is not on the production path; if unused, delete it.

**Priority 2 — Short-term, medium risk**:
- Wire cache invalidation from `DatabaseWriteActor` into repository caches. Add a `WriteSideEffect`
  interface (or a simple lambda parameter) that repositories register; actor calls it after each
  successful write. Repository implementations call `blockCache.remove(uuid)` and
  `hierarchyCache.remove(pageUuid)`.
- Apply `.stateIn(SharingStarted.WhileSubscribed(5_000))` in ViewModel layers above
  `getBlocksForPage` and `getAllPages` so multiple screen collectors share one DB query.
  Do not apply `stateIn` at the repository layer itself.

**Priority 3 — Benchmark first**:
- Tune JVM `cache_size` from 2 MB to 8 MB and add `mmap_size=268435456` in `DriverFactory.jvm.kt`.
  Benchmark before and after with `GraphLoadTimingTest`. Add pool wait-time metric to
  `PooledJdbcSqliteDriver` before benchmarking.
- If pool contention exceeds 5% of query latency, increase `poolSize` from 8 to 16. If near zero,
  keep at 8.

**Do not implement**:
- Option 5 (separate read replica): complexity not justified; snapshot-lag hazard is real.
- Option 6 (custom Channel fan-out): fully subsumed by Option 2.
- Full `StateFlow` map cache (Option 1 in full): the `stateIn`-at-ViewModel approach (Option 2)
  achieves the same observable result with far less invalidation logic to maintain.

---

## Pending Web Searches

The following queries were not executed because web search was unavailable. Run these and update
any `[TRAINING_ONLY]` claims above.

1. `sqldelight 2.3.x asFlow notifyListeners concurrency guarantee` — confirm whether
   `notifyListeners` is called synchronously on the write thread or dispatched asynchronously;
   this affects the timing of cache invalidation in Option 2.

2. `kotlin coroutines stateIn WhileSubscribed performance best practices 2024` — confirm the
   recommended stop timeout value and whether `Eagerly` vs `WhileSubscribed` has measurable
   overhead differences.

3. `SQLite WAL mmap_size JDBC Android performance` — confirm whether `mmap_size` PRAGMA applies
   to Android's SQLite or is silently ignored.

4. `SQLite WAL wal_autocheckpoint mobile default behavior` — confirm the default page count and
   whether raising it improves read throughput in WAL mode.

5. `RequerySQLiteOpenHelperFactory Android concurrent reads WAL` — confirm whether Requery's
   factory enables multi-connection WAL reads or serializes all access to a single connection.

6. `SQLDelight native iOS driver WAL concurrent readers` — confirm threading model of the iOS
   native SQLite driver and whether WAL reader concurrency applies.

7. `Caffeine cache KMP multiplatform alternative coroutines` — check whether any KMP-compatible
   Caffeine equivalent (`store5`, `multiplatform-settings`, etc.) offers coroutine-native
   write-invalidation that would replace the `LruCache` + Mutex pattern.

8. `site:github.com/cashapp/sqldelight shareIn stateIn example` — find official or community
   examples of `stateIn(WhileSubscribed)` applied to SQLDelight flows in production KMP apps.
