# Research Synthesis: SteleKit Performance

**Date**: 2026-04-24
**Inputs**: findings-stack.md, findings-features.md, findings-architecture.md, findings-pitfalls.md

---

## Decision Required

How to improve read throughput, reduce DB round-trips, and establish a performance regression gate for SteleKit — a KMP notes app using SQLDelight + coroutines — without sacrificing cache correctness or adding disproportionate complexity.

---

## Context

SteleKit already has a non-trivial caching foundation: a weight-bounded `LruCache<K,V>` in `commonMain`, a `RequestCoalescer` (singleflight), a `PooledJdbcSqliteDriver` with 8 connections + WAL mode on JVM, and `DatabaseWriteActor` for write serialization. However:

- Three concrete bugs make the existing cache untrustworthy: O(n) LRU eviction, a metrics counter that is never incremented, and `Dispatchers.IO` hardcoded where `PlatformDispatcher.DB` is required.
- Cache invalidation is not wired into `DatabaseWriteActor` — writes do not update or evict the cache.
- External file-watcher writes (`GraphLoader.externalFileChanges`) bypass the cache entirely.
- `getBlocksForPage` — the hottest read path — has no `shareIn`/`stateIn` multicasting, so N Compose subscribers each open an independent DB listener.
- A SQLite WAL data race bug (present in versions 3.7.0–3.51.2, fixed in 3.51.3) can silently corrupt databases when concurrent write and checkpoint operations race. The current `sqlite-jdbc` version must be verified.

---

## Options Considered

| Option | Summary | Key Trade-off |
|--------|---------|---------------|
| Fix existing cache bugs only | Correct the O(n) eviction, metrics counter, and `Dispatchers.IO` violations | Low risk, low throughput gain; prerequisite for all other work |
| `stateIn(WhileSubscribed)` at ViewModel layer | Multicast SQLDelight reactive flows so N subscribers share one DB query | Near-zero risk; highest correctness; no invalidation logic needed |
| Actor → cache invalidation callback | Wire `DatabaseWriteActor` success path to evict/update repository caches | Medium risk; eliminates stale reads after writes; required for Option 3 |
| Caffeine on `jvmMain` as cache backend | Replace custom `LRUCache` with Caffeine (W-TinyLFU, O(1), correct stats) | JVM-only; medium migration effort; resolves both perf bugs at once |
| Lightweight in-memory backlink index | Build `HashMap<blockUuid, Set<referencedUuid>>` populated at startup | Medium cost; makes backlink resolution O(1); modeled on Obsidian MetadataCache |
| FTS5 virtual table for search | Add SQLite FTS5 to replace LIKE scans | Medium cost; no additional infrastructure; Bear demonstrates iOS viability |
| Full in-memory graph (Logseq style) | Load entire graph into RAM at startup | Catastrophic on mobile — 8s+ cold start, OOM above 300 MB graph |
| Separate read-replica SQLite connection | Second driver for reads, first for writes | Snapshot-lag correctness hazard; complexity not justified for single-user app |
| SQLite WAL driver upgrade | Upgrade `sqlite-jdbc` to ≥ 3.51.3 | Critical safety fix; zero functional change; must be done regardless |

---

## Dominant Trade-off

**Correctness of cache state vs. read throughput.**

Aggressive caching that is not invalidated correctly is silent data corruption — the user sees stale block content with no error. Aggressive caching that is correctly invalidated gives free read throughput. The `stateIn(WhileSubscribed)` + reactive pipeline approach (Option 2) resolves this tension by delegating invalidation to SQLDelight's own `notifyListeners` mechanism, which fires automatically after every write commit. All other cache layers (LRU for single-block lookups, hierarchy) require explicit invalidation callbacks from the write actor.

A secondary tension: **platform diversity vs. JVM-specific optimization**. Caffeine is compelling on JVM but unusable in `commonMain`. The existing `LruCache` in `commonMain` is the correct shared mechanism; Caffeine belongs only in `jvmMain` if profiling confirms Mutex contention is measurable.

---

## Recommendation

**Choose: Fix bugs first, then adopt `stateIn(WhileSubscribed)` as the primary caching mechanism, with targeted actor invalidation callbacks for secondary caches.**

### Tier 1 — Do immediately (bugs, not features)

1. **Upgrade `sqlite-jdbc` to ≥ 3.51.3** to eliminate the WAL data race bug (silent corruption risk; present since SQLite 3.7.0, fixed March 2026). This is a safety requirement independent of all other work.

2. **Fix `LRUCache.get()` metrics counter** — the `.let { metrics.value }` expression discards the `withBlockHit()` result. One-line fix. Without this, no cache tuning decision is valid.

3. **Fix `LRUCache` O(n) eviction** — replace `ConcurrentLinkedQueue.remove(key)` (confirmed O(n) by Java API docs) with `LinkedHashMap` in access-order mode guarded by the existing `Mutex`. The existing `LruCache` already uses a `LinkedHashMap` internally; the `ConcurrentLinkedQueue` is a redundant second data structure that is the source of the bug.

4. **Replace `flowOn(Dispatchers.IO)` with `flowOn(PlatformDispatcher.DB)`** in `BlockCache.kt` (5 call sites). Confirmed `Dispatchers.IO` is wrong on iOS and bypasses the platform dispatcher abstraction.

5. **Delete or wire `CacheCore.kt` / `CachedBlockRepository`** in `jvmMain`. This duplicates the `commonMain` cache with weaker semantics. If unwired from production, delete it. If wired, the O(n) `LRUCache` and wrong dispatcher must be fixed before production traffic hits it.

### Tier 2 — Short-term architecture (highest ROI)

6. **Apply `.stateIn(SharingStarted.WhileSubscribed(5_000))` at the ViewModel layer** for `getBlocksForPage` and `getAllPages`. Community-confirmed best practice (5000ms stop timeout). Eliminates N-subscriber DB query fan-out. SQLDelight's `notifyListeners` re-emits automatically on every write — zero manual invalidation required. This is the primary throughput improvement.

7. **Wire `DatabaseWriteActor` success path to invalidate repository caches** via a `WriteSideEffect` lambda or small callback interface. For the hierarchy and ancestors caches, which have TTL-based eviction today, the actor callback replaces TTL with write-event-driven invalidation. Invalidation must occur *before* `CompletableDeferred.complete()` to avoid a race where a reader observes the "write done" signal before the cache evicts the old entry.

8. **Fix `hierarchyCacheTimestamps` data race** — fold the timestamp into the `LruCache` value type so the existing `Mutex` covers it.

9. **Wire `GraphLoader.externalFileChanges` → cache invalidation** — subscribe in the scope that constructs the repository; call `invalidateBlock(uuid)` or `clearPageBlocks(pageUuid)` on each external change event. Without this, external editor saves are invisible in the UI for up to 5 minutes.

10. **Add Android `onTrimMemory` hook** in the Application class — respond to `TRIM_MEMORY_RUNNING_CRITICAL` and `TRIM_MEMORY_BACKGROUND` by calling cache eviction. Confirmed correct pattern per Android Developers documentation.

### Tier 3 — After profiling evidence

11. **Add Caffeine to `jvmMain`** as a `jvmMain`-only dependency (`com.github.ben-manes.caffeine:caffeine:3.2.3`). Confirmed compatible with KMP `jvmMain` source set. Use only if JVM profiling shows `LruCache` Mutex contention at production scale. For a single-user desktop app, Tier 1 bug fixes likely suffice.

12. **Add FTS5 virtual table for full-text search** — eliminates LIKE scans; Bear demonstrates sub-100ms mobile search without a separate search engine. Defer until search is a measured bottleneck.

13. **Tune JVM SQLite PRAGMAs** (`cache_size = -8000`, `mmap_size = 268435456`) in `DriverFactory.jvm.kt`. Add pool wait-time metric to `PooledJdbcSqliteDriver` before benchmarking so tuning is data-driven.

---

## Accept These Costs

- `stateIn(WhileSubscribed)` adds a 5-second query keep-alive window on last-subscriber departure. This is a minor DB resource use during brief navigation transitions — acceptable for a notes app.
- Actor invalidation callbacks add ~1 µs per write. Negligible compared to the write itself.
- Caffeine (if adopted on JVM) adds a `jvmMain` `expect/actual` seam and a ~600 KB JAR. iOS/Android still use the `commonMain` `LruCache`.
- Upgrading `sqlite-jdbc` may require testing the new WAL behavior under the existing `PooledJdbcSqliteDriver` connection pool.

---

## Reject These Alternatives

- **Full in-memory graph**: Logseq's history shows this causes 8s+ cold starts and OOM crashes on mobile for graphs above 300 MB. SteleKit's page-granular loading via `GraphLoader` is already the correct pattern.
- **Separate read-replica SQLite connection**: Snapshot-lag correctness hazard. SQLDelight's `asFlow()` already multicasts; a second connection adds complexity with zero benefit for a single-user app.
- **Custom Channel-based fan-out**: Subsumed entirely by `stateIn`/`shareIn`. SQLDelight's `Query.Listener` is already the correct abstraction.
- **TTL-only staleness with no write invalidation**: Already proven insufficient — external file watcher writes cause a 5-minute stale window.

---

## Open Questions Before Committing

- [ ] **What version of `sqlite-jdbc` is currently in `build.gradle.kts`?** — If < 3.51.3 (and no backport to 3.44.6/3.50.7), the WAL data race bug is active. This must be checked and fixed before any WAL-dependent feature work.
- [ ] **Is `CachedBlockRepository` wired in production (`RepositoryFactoryImpl`)?** — Determines whether Tier 1 cache bug fixes have immediate user-facing impact or are hypothetical cleanup.
- [ ] **Does `GraphLoader.loadPage()` write through `CachedBlockRepository` or directly to `SqlDelightBlockRepository`?** — Determines the scope of the external-change staleness problem.
- [ ] **What is the iOS `DriverFactory` DB path?** — Must confirm it is `Library/Application Support`, not a Documents or iCloud container, to keep WAL safe on iOS.

If the `sqlite-jdbc` version check reveals a vulnerable version, fix that before all other work — it is a data safety issue.

---

## Sources

- [SQLite WAL documentation](https://sqlite.org/wal.html)
- [SQLite WAL data race bug (TSAN report)](https://sqlite.org/forum/info/2d74563d6c67bb0c)
- [SQLDelight asFlow / notifyListeners Kotlin Slack](https://slack-chats.kotlinlang.org/t/10261870/)
- [SQLDelight coroutines documentation](https://sqldelight.github.io/sqldelight/2.0.2/android_sqlite/coroutines/)
- [Caffeine GitHub (ben-manes/caffeine)](https://github.com/ben-manes/caffeine)
- [Apple QA1809 — WAL mode and iCloud](https://developer.apple.com/library/archive/qa/qa1809/_index.html)
- [Android ComponentCallbacks2.onTrimMemory](https://developer.android.com/topic/performance/memory)
- [Java ConcurrentLinkedQueue API (O(n) remove)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentLinkedQueue.html)
- [Logseq DB version migration discussion](https://discuss.logseq.com/t/why-the-database-version-and-how-its-going/26744)
- [How SQLite scales read concurrency (WAL internals)](https://fly.io/blog/sqlite-internals-wal/)

---

## Links to Findings Files

- [Stack](findings-stack.md) — caching library options and SQLite driver tuning
- [Features](findings-features.md) — Logseq, Obsidian, Bear, Roam, AppFlowy architecture survey
- [Architecture](findings-architecture.md) — read concurrency patterns and cache invalidation design
- [Pitfalls](findings-pitfalls.md) — failure modes, WAL bugs, race conditions, benchmark flakiness
