# Findings: Pitfalls

**Dimension**: Known failure modes for KMP SQLite caching, WAL, and read concurrency
**Date**: 2026-04-24
**Status**: Training knowledge + pending web searches (code-verified where possible)

---

## Summary

Adding in-process caching, SQLite WAL tuning, and read concurrency to SteleKit's KMP + SQLDelight + coroutines stack introduces a cluster of failure modes that are mostly silent in development but surface as data-correctness bugs or ANRs in production. After reviewing `BlockCache.kt`, `CacheCore.kt`, `CachedBlockRepository.kt`, `PooledJdbcSqliteDriver.kt`, `DatabaseWriteActor.kt`, and `GraphLoader.kt`, the dominant risks are:

1. Cache-DB divergence after external file watcher events — the cache is not notified when `GraphLoader` triggers DB writes from filesystem events.
2. Read-modify-write races not covered by `DatabaseWriteActor` — the actor serializes writes but not read-modify-write sequences.
3. `LRUCache` has two concrete bugs: metrics counter never incremented, O(n) LRU eviction via `ConcurrentLinkedQueue`.
4. `BlockCache` hardcodes `Dispatchers.IO`, violating the `PlatformDispatcher.DB` rule — wrong on iOS.
5. SQLite WAL `SQLITE_BUSY` on Android background/multi-process scenarios.
6. iOS WAL path restrictions (iCloud-synced directories).
7. `StateFlow` → DB emission lag causing one-frame stale reads after writes.
8. Memory pressure on Android with large-wiki block caches.

---

## Options Surveyed

| Strategy | Description |
|----------|-------------|
| Write-through cache | Cache updated atomically with DB write inside `DatabaseWriteActor` |
| Cache invalidation on write | Cache entry removed on every write; next read re-loads from DB |
| Event-driven invalidation | File watcher emits events; cache subscribes and invalidates on those events |
| TTL-only staleness | Cache entries expire after a fixed time; no active invalidation |
| No caching for write paths | Cache is read-only; writes bypass it and rely on DB flow re-emission |
| WAL journal mode (current) | Already applied — `journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout=30000` |
| WAL checkpoint tuning | `wal_autocheckpoint=N` controls how large the WAL grows before checkpointing |
| Caffeine (JVM-only) | Correct O(1) LRU-variant cache for JVM, replaces custom `LRUCache` |

---

## Trade-off Matrix

| Failure Mode | Risk Severity | Detectability | Platform Specificity | Mitigation Available |
|-------------|---------------|---------------|---------------------|---------------------|
| Cache staleness after file watcher write | Data correctness — user sees wrong block content | Silent | JVM (file watcher is jvmMain) | Medium — wire `externalFileChanges` into cache invalidation |
| Read-modify-write TOCTOU | Data correctness — edits lost | Silent | All platforms | High — wrap RMW in `actor.execute { }` |
| `Dispatchers.IO` hardcoded in `BlockCache` | Crash on iOS; silent perf degradation Android | Loud on iOS; silent Android | iOS, Android | High — replace with `PlatformDispatcher.DB` |
| `LRUCache` O(n) eviction (`ConcurrentLinkedQueue.remove`) | Performance degradation at 10k entries | Silent | JVM | High — replace with Caffeine or `LinkedHashMap` + lock |
| `LRUCache` metrics counter bug | Incorrect hit-rate metrics → wrong tuning decisions | Silent | JVM | High — one-line fix in `LRUCache.get()` |
| SQLite WAL `SQLITE_BUSY` on Android background | App crash or data loss if background service holds write lock | Loud (exception) | Android | Medium — `busy_timeout=30000` already set; add `wal_checkpoint` on `onStop` |
| WAL file on iCloud-synced iOS path | WAL silently disabled or `SQLITE_CANTOPEN` | Loud on open; silent fallback | iOS | High — store DB in `Library/Application Support` |
| StateFlow/DB emission lag (one-frame stale read) | UI shows stale data for 1–2 render frames | Silent; transient; flaky tests | All platforms | Medium — use `advanceUntilIdle()` in tests; `turbine` for flow assertions |
| WAL checkpoint blocking during graph load | UI jank when autocheckpoint fires during bulk import | Observable jank | JVM, Android | Medium — explicit `wal_checkpoint(TRUNCATE)` after import |
| Memory pressure on Android (10k-block cache) | OOM on 256 MB heap devices with large wikis | Loud (OOM crash) | Android | High — `onTrimMemory` hook; reduce `maxBlocks` for Android |
| Benchmark flakiness (GC/JIT) | False cache hit-rate signals drive wrong config | Silent | JVM benchmark | Medium — JMH warmup=5, fork=3; filter outliers |

---

## Risk and Failure Modes

### 1. Cache Staleness After External File Watcher Write

**Mechanism**: `GraphLoader` watches for filesystem changes via `externalFileChanges: SharedFlow`. When an external editor saves a markdown file, `GraphLoader` re-parses it and writes via `DatabaseWriteActor.saveBlocks(...)` to `SqlDelightBlockRepository` (the delegate). `BlockCache` is never notified. `CachedBlockRepository.getBlockByUuid()` returns the old cached value until TTL expiry (default 5 minutes).

The path where `CachedBlockRepository.saveBlocks()` calls `cache.saveBlock(it)` is correct for direct saves. The gap is when `DatabaseWriteActor` bypasses `CachedBlockRepository` entirely and writes to the underlying `SqlDelightBlockRepository` directly, as happens during `GraphLoader`-triggered external-change reloads.

**Impact**: User edits in an external editor appear to do nothing in SteleKit UI for up to 5 minutes.

**Fix**: Subscribe `BlockCache.invalidateBlock()` (or `cache.clear()`) to `GraphLoader.externalFileChanges` in the scope that wires up the `CachedBlockRepository`. Or route all `GraphLoader` writes through `CachedBlockRepository` rather than the raw repository.

---

### 2. Read-Modify-Write (TOCTOU) Race

**Mechanism**: `DatabaseWriteActor` serializes writes but not read-modify-write sequences. Two concurrent callers that both (1) read block B from cache, (2) mutate content, (3) write block B via actor, will have the actor serialize their writes, but both writes are based on the same initial read. The later write silently overwrites the earlier one.

In SteleKit: the 500ms debounce in `BlockStateManager` mitigates this for typical typing. But external file watcher reloads concurrent with in-flight user edits are not debounced against each other. `DiffMerge.kt` handles disk conflicts, not in-process cache-vs-DB races.

**Impact**: High severity (silent data loss). `deferred.await()` returns `Result.success(Unit)` for both writes.

**Fix**: Wrap read-modify-write in a single `actor.execute { read; modify; write }` lambda so the entire sequence is serialized through the actor channel.

---

### 3. `Dispatchers.IO` Hardcoded in `BlockCache`

`BlockCache.kt` uses `.flowOn(Dispatchers.IO)` at multiple call sites, violating the `PlatformDispatcher.DB` abstraction. On iOS both `IO` and `Default` map to `Dispatchers.Default`, so this is currently benign there. If `BlockCache` were moved to `commonMain`, this would cause a compilation error — a good early signal.

**Fix**: Replace all `flowOn(Dispatchers.IO)` with `flowOn(PlatformDispatcher.DB)` in `BlockCache.kt`. Consider a detekt rule banning `Dispatchers.IO` in files that import SQLDelight types.

---

### 4. `LRUCache` Implementation Bugs

**Bug 1 — O(n) LRU eviction**: `updateLRU` calls `lruOrder.remove(key)` on a `ConcurrentLinkedQueue`. `ConcurrentLinkedQueue.remove(Object)` is O(n) — it walks the entire queue for every cache access. At `maxBlocks=10_000`, every `get()` or `put()` does a ~10,000-element scan. Under load this makes the cache slower than a direct DB query.

**Bug 2 — Hit counter never incremented**:
```kotlin
metrics.value = metrics.value.withBlockHit().let { metrics.value }
```
The `.let { metrics.value }` re-reads the old `StateFlow` value and assigns it back, discarding the `withBlockHit()` result. The hit counter is silently never incremented. All cache-tuning decisions based on `CacheMetrics.blockHitRate` are wrong.

**Bug 3 — Miss counted inconsistently**: In `getOrLoad`, the miss counter is incremented only when the loader returns a non-null value. A loader returning `null` (block not found in DB) increments neither hit nor miss.

**Fix**: On JVM replace `LRUCache` with Caffeine. Otherwise replace `ConcurrentLinkedQueue` with `LinkedHashMap` access-order mode guarded by `ReentrantReadWriteLock`. Fix the counter bug as a minimum if replacement is deferred.

---

### 5. SQLite WAL on Android — Background and Multi-Process Risks [TRAINING_ONLY]

**Multi-process**: If a ContentProvider or background service opens the SteleKit database from a second Android process, that process's SQLite may default to DELETE journal mode. A WAL-mode database opened by a DELETE-mode process can cause the WAL header to be ignored or corrupted.

**Background kill**: Android kills the process when backgrounded. If the WAL has not been checkpointed, the main `.db` file is stale. SQLite recovers correctly on next open via WAL replay, but only if the WAL and SHM files survived the kill. An abrupt kill during an in-progress checkpoint can leave a corrupted SHM file.

**Mitigation already present**: `busy_timeout=30000`. **Missing**: No `PRAGMA wal_checkpoint(TRUNCATE)` call on Android `onStop`/`onPause`.

---

### 6. SQLite WAL on iOS — Path Restrictions [TRAINING_ONLY]

**iCloud Drive paths**: Files in iCloud-synced containers use Apple's file coordination API, which conflicts with SQLite's WAL lock files (`.db-wal`, `.db-shm`). SQLite may either return `SQLITE_CANTOPEN` or silently fall back to DELETE journal mode.

**SteleKit risk assessment**: The `DriverFactory.jvm.kt` stores the DB in `Library/Application Support/SteleKit` which is correct. If the iOS `DriverFactory` follows the same convention, WAL on iOS is safe. The risk is if the DB is ever stored in an iCloud-synced container.

---

### 7. `StateFlow` vs. DB Emission Timing

After a write, `PooledJdbcSqliteDriver.notifyListeners()` is called synchronously on the writer's thread, which schedules a re-query on `PlatformDispatcher.DB`, which emits a new value to the `Flow`. Compose's `collectAsState()` picks this up on the next Main thread frame. A write followed by an immediate `state.value` read may see the pre-write value.

In production this is imperceptible (one frame, ~16ms). In tests it causes flakiness:
```kotlin
actor.saveBlock(block)
val result = viewModel.state.value.currentPage.blocks // may be stale
assertThat(result).contains(block) // flaky
```
**Fix in tests**: Use `turbine`'s `awaitItem()` or `advanceUntilIdle()` + `runCurrent()`.

---

### 8. Memory Pressure on Android

Default config: `maxBlocks=10_000, maxPages=1_000`. A realistic `Block` at 500–2,000 bytes means 5–20 MB for blocks. For a 50k-block wiki where users navigate 50 pages, this approaches the 20 MB range. Blocks with embedded images (base64-encoded content) can be 500 KB+, making the 10k limit dangerous.

No `onTrimMemory` hook currently exists in SteleKit.

---

### 9. Benchmark Flakiness

`metrics.value = metrics.value.withBlockHit()` allocates a new `CacheMetrics` data class copy on every cache access. At high throughput this creates significant GC pressure that affects benchmark timing. The O(n) LRU overhead distortion: benchmarks at small cache sizes (100–1,000 entries) will show linear overhead that is too small to detect, but at production scale (10,000 entries) the same configuration shows 100x worse performance. Small-scale benchmark results do not predict production behavior.

---

## Migration and Adoption Cost

| Change | Effort | Risk During Migration |
|--------|--------|----------------------|
| Wire `externalFileChanges` → cache invalidation | Low | Medium — must not double-invalidate during normal writes |
| Fix `Dispatchers.IO` → `PlatformDispatcher.DB` in `BlockCache` | Low — 5 call sites | Low |
| Fix `LRUCache` O(n) — use Caffeine (JVM) | Medium | Low — cache is write-through to DB |
| Fix metrics counter bug in `LRUCache.get()` | Low — one line | None |
| Add `wal_checkpoint(TRUNCATE)` on Android `onStop` | Medium | Medium — must not checkpoint during active actor writes |
| Add `onTrimMemory` → `cache.clearCache()` on Android | Medium | Low |
| Reduce `maxBlocks` to 2,000 for Android | Low — config change | Low |
| Route RMW sequences through `actor.execute { }` | Medium — audit all callers | High — must identify all TOCTOU sites |

---

## Operational Concerns

- **WAL file growth during bulk import**: With `synchronous=NORMAL` and no explicit checkpoint trigger, the WAL grows during 4,000-page imports. SQLite's default `wal_autocheckpoint=1000` (1,000 pages ≈ 4 MB) triggers periodic checkpoints that briefly block the writer.
- **WAL file orphaning during graph rename/migration**: `GraphManager.migrateWalShmFiles` renames `.db-wal` and `.db-shm` silently swallowing exceptions. If the rename happens while a connection holds the WAL file open, the WAL file is orphaned and SQLite fails on next open.
- **In-memory pool deadlock in tests**: `PooledJdbcSqliteDriver` uses `pool.take()` (blocking) for in-memory databases. Tests that parallelize with `async { }` and pool size of 1 can deadlock if a coroutine holds the connection while awaiting another coroutine that also needs the connection.

---

## Prior Art and Lessons Learned

- **Room's `InvalidationTracker`** (Android): Solves DB-write → observer notification coordination by maintaining per-table version counters. SQLDelight's synchronous `notifyListeners()` is simpler but has a one-frame emission-timing gap.
- **Caffeine (JVM)**: W-TinyLFU policy provides O(1) amortized eviction and correct statistics. Replacing `LRUCache` with Caffeine on JVM eliminates the O(n) eviction bug, the metrics counter bugs, and `ConcurrentLinkedQueue` thread contention.
- **Logseq**: Uses DataScript (an in-memory Datom store) as the authoritative source, syncing asynchronously to disk. Eliminates DB-cache staleness by making the in-memory store the single source of truth. SteleKit inverts this (DB is authoritative, cache accelerates reads), which requires more careful invalidation discipline.
- **SQLite WAL on iCloud**: Apple's documentation and WWDC sessions consistently warn against storing SQLite WAL databases in iCloud Drive containers. `Library/Application Support` is the correct path (excluded from iCloud by default on iOS).

---

## Open Questions

- [ ] Is `CachedBlockRepository` wired in production? `RepositoryFactoryImpl` determines whether it or `SqlDelightBlockRepository` is returned. If not wired, all cache-staleness risks are currently theoretical. — blocks decision on: priority of all cache fixes
- [ ] Does `GraphLoader.loadPage()` write through `CachedBlockRepository` or directly to `SqlDelightBlockRepository`? — blocks decision on: external file watcher cache invalidation path
- [ ] What is the iOS `DriverFactory` implementation? Whether WAL mode is correctly applied on iOS and whether the DB path is non-iCloud-synced determines iOS WAL risk severity.
- [ ] Is `onTrimMemory` hooked in the Android entry point? — blocks decision on: memory pressure safety valve priority
- [ ] Are benchmarks using JMH? `AhoCorasickBenchmark` and `MarkdownEngineBenchmark` exist; the harness type determines GC/JIT warm-up validity.

---

## Recommendation

**Immediate (before enabling cache in production):**

1. Fix `LRUCache.get()` metrics counter bug — one-line fix, zero correctness risk, required for any tuning decision to be valid.

2. Fix `LRUCache` O(n) eviction — at 10k entries, every cache access scans ~10k elements. On JVM replace with Caffeine. Otherwise replace `ConcurrentLinkedQueue` with `LinkedHashMap` access-order mode guarded by `ReentrantReadWriteLock`.

3. Replace `flowOn(Dispatchers.IO)` with `flowOn(PlatformDispatcher.DB)` in `BlockCache.kt` at all 5 call sites.

4. Wire cache invalidation to `GraphLoader.externalFileChanges` — subscribe in the scope that constructs `CachedBlockRepository`; on each emission call `cachedBlockRepo.invalidateBlock(uuid)` for the affected blocks or `clearCache()` for a full-page reload.

**Short-term:**

5. Add Android `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)` → `cache.clearCache()`.

6. Add `PRAGMA wal_checkpoint(TRUNCATE)` after bulk graph import and on Android `onStop`.

7. Audit all read-modify-write call sites and wrap each in an `actor.execute { }` lambda.

**Deferred:**

8. Confirm iOS `DriverFactory` stores DB in `Library/Application Support`, not an iCloud-synced directory.

---

## Pending Web Searches

1. `sqlite WAL mode android multi-process SQLITE_BUSY ContentProvider` — confirm whether a ContentProvider opening the same DB causes WAL mode conflicts
2. `sqlite WAL iCloud Drive iOS SQLITE_CANTOPEN journal_mode fallback` — confirm whether iCloud file coordination silently disables WAL
3. `sqldelight 2.3 notifyListeners synchronous flow emission timing race` — verify emission-timing behavior
4. `kotlin coroutines channel actor TOCTOU read-modify-write serialization pattern` — canonical patterns for serializing RMW through a coroutine actor
5. `caffeine cache kotlin multiplatform jvm-only dependency gradle kmp` — confirm Caffeine can be added as `jvmMain` dependency without affecting `commonMain`
6. `ConcurrentLinkedQueue remove O(n) OpenJDK performance` — verify O(n) complexity claim
7. `android ComponentCallbacks2 onTrimMemory TRIM_MEMORY_RUNNING_CRITICAL in-process cache eviction` — confirm recommended Android memory pressure response
8. `sqlite wal_autocheckpoint default pages behavior during bulk insert checkpoint blocking` — confirm default autocheckpoint threshold

---

## Web Search Results (2026-04-24)

### Critical: SQLite WAL data race bug (CVE-level, affects 3.7.0–3.51.2)

**Bug**: Present in all SQLite versions from 3.7.0 (2010) through 3.51.2 (2026-01-09). **Fixed in 3.51.3 (2026-03-13)**. Backports exist for 3.44.6 and 3.50.7. The race occurs when two connections simultaneously attempt a write commit that resets the WAL file AND a checkpoint. The checkpoint leaves the WAL-index header in an incorrect state, causing a later checkpoint to skip already-written transactions — resulting in **silent database corruption**.

**SteleKit impact**: `PooledJdbcSqliteDriver` uses the SQLite JDBC driver bundled with `sqlite-jdbc`. The version of this driver determines which SQLite is used. **Verify `sqlite-jdbc` is ≥ 3.51.3 or a version with the backport (3.44.6+, 3.50.7+).** This is the highest-severity pitfall found — it can silently corrupt user data. Source: [SQLite User Forum](https://sqlite.org/forum/info/2d74563d6c67bb0c), [sqlite.org WAL](https://sqlite.org/wal.html)

### ConcurrentLinkedQueue.remove(Object) confirmed O(n)

Confirmed by Java API documentation: `ConcurrentLinkedQueue` is based on a singly linked list; `remove(Object)` traverses the entire queue to find the element. At 10,000 entries this is a real throughput bottleneck. Source: [Oracle JDK 21 API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentLinkedQueue.html)

### SQLite WAL on iOS + iCloud

Apple QA1809 confirms: Core Data (and SQLite generally) changed default to WAL on iOS 7+. The `-wal` file contains unflushed transactions — if iCloud syncs only the main `.db` file without the `.db-wal` file, data is lost. For SteleKit: as long as the DB lives in `Library/Application Support` (iCloud-excluded by default), this is safe. If the DB is ever moved to `Documents` or an iCloud container, the WAL file must be bundled with the main file. Source: [Apple QA1809](https://developer.apple.com/library/archive/qa/qa1809/_index.html)

### SQLDelight `asFlow()` emission path

The `asFlow()` listener "dumps data into a channel; the flow waits on the channel to provide data, then emits on the dispatcher operating on the flow." This confirms emission is **asynchronous** — there is a channel hop between `notifyListeners()` call (synchronous on writer thread) and actual Flow emission (on `mapToList(dispatcher)`). The one-frame stale read window is real. Source: [SQLDelight Kotlin Slack](https://slack-chats.kotlinlang.org/t/10261870/)

### Android onTrimMemory pattern confirmed

`ComponentCallbacks2.onTrimMemory()` is the correct hook. Implement in `Application` class; respond to `TRIM_MEMORY_RUNNING_CRITICAL` and `TRIM_MEMORY_BACKGROUND` by calling `cache.clearCache()`. Source: [Android Developers](https://developer.android.com/topic/performance/memory)
