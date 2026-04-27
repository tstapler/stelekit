# Findings: Stack

**Dimension**: KMP-compatible caching and SQLite driver/pool options
**Date**: 2026-04-24
**Status**: Training knowledge + pending web searches

---

## Summary

SteleKit already has a meaningful caching foundation: `SqlDelightBlockRepository` embeds a `LruCache<String, Block>` (weight-bounded, Mutex-guarded, pure KMP) for single-block lookups, a count-capped `LruCache` for hierarchy and ancestors, and a `RequestCoalescer` (single-flight deduplication). The JVM driver is a custom `PooledJdbcSqliteDriver` with a fixed pool of 8 connections, WAL mode, `synchronous=NORMAL`, and `busy_timeout=30000`. A parallel `jvmMain`-only `CachedBlockRepository`/`BlockCache` wrapper using `ConcurrentHashMap` + TTL also exists but is unwired from the production code path.

The question is therefore not "should we add caching?" but "which gaps remain and which additional options are worth adding?"

**Identified gaps:**

1. `blockCache` in `SqlDelightBlockRepository` is populated on read but never updated on write-back — a `saveBlock` through the actor does not update the in-process cache.
2. `hierarchyCache` uses a flat 120 s TTL with no write invalidation when a block inside the hierarchy changes.
3. `getBlocksForPage` (hot path for every page render) is a live SQLDelight `asFlow()` with no in-process cache layer.
4. `CachedBlockRepository`/`BlockCache` in `jvmMain` duplicates the common-layer cache logic, hardcodes `Dispatchers.IO` instead of `PlatformDispatcher.DB`, and has an unsafe non-blocking `LRUCache.get()` with an O(n) `ConcurrentLinkedQueue` LRU promotion.
5. WAL mode is already enabled on JVM. No explicit PRAGMA verification on iOS/Android.
6. No `shareIn`/`stateIn` multicasting is applied to any hot `Flow` query, so duplicate subscribers each open a separate DB listener.

---

## Options Surveyed

**Option 1 — Existing `LruCache<K,V>` with `Mutex` (pure KMP, already in codebase)**
`dev.stapler.stelekit.cache.LruCache` in `commonMain`. Weight-bounded, suspend-based, backed by `LinkedHashMap` in access-order mode. Zero additional dependencies.

**Option 2 — Caffeine (JVM-only, expect/actual wrapper)**
Caffeine is the fastest JVM cache library. Uses W-TinyLFU eviction (higher hit rate than LRU for skewed access patterns), supports async loading, per-entry weight, and time-based expiry. Not available in KMP `commonMain` — requires a `jvmMain` expect/actual seam. [TRAINING_ONLY — verify current Caffeine KMP state]

**Option 3 — AndroidX `LruCache` (Android-only)**
`androidx.collection.LruCache<K,V>`. Synchronized, count-bounded LRU. Android-only; would require expect/actual wrapper. Adds an AndroidX dependency.

**Option 4 — Plain `LinkedHashMap` with size cap**
Already what the existing `LruCache` implements, minus weight-based eviction. Strictly a regression versus Option 1.

**Option 5 — SQLDelight `asFlow()` with `shareIn` multicasting**
SQLDelight reactive flows re-execute the SQL query on each new subscriber unless multicast with `shareIn(scope, SharingStarted.WhileSubscribed())`. Works across all KMP targets. No new dependencies.

**Option 6 — SQLite WAL mode via JDBC PRAGMA (JVM)**
Already applied in `DriverFactory.jvm.kt`. Pool of 8 connections all inherit WAL settings. No action needed.

**Option 7 — `RequestCoalescer` (single-flight, pure KMP, already in codebase)**
Implements Go-style `singleflight`. Already in `commonMain` but not wired into any production repository. Wiring into `SqlDelightPageRepository.getPageByName`/`getPageByUuid` would eliminate duplicate DB round-trips on simultaneous subscription.

---

## Trade-off Matrix

| Option | KMP scope | Eviction policy | Thread safety | Memory overhead | Integration complexity | SQLDelight Flow compat |
|--------|-----------|-----------------|---------------|-----------------|----------------------|----------------------|
| 1. Existing `LruCache` (Mutex + LinkedHashMap) | commonMain | Weight-based LRU | Mutex (suspend) | Low | Already in place | Yes — populate on miss, invalidate on write |
| 2. Caffeine (expect/actual) | jvmMain only | W-TinyLFU, time-expiry | Lock-free CAS | ~600 KB jar | Medium — expect/actual seam | Cache-aside or write-through layer needed |
| 3. AndroidX `LruCache` | androidMain only | Count-bounded LRU | `synchronized` | Low | Same complexity as Option 2; weaker eviction | Cache-aside only |
| 4. Plain `LinkedHashMap` | commonMain | Count-bounded LRU | External lock | Low | Regression vs. Option 1 | — |
| 5. `shareIn` multicasting | commonMain | N/A (no eviction) | Flow operators | Per-active-query overhead | Low — wrap in ViewModel | Native; queries run once per distinct key |
| 6. WAL (JDBC PRAGMA) | jvmMain | N/A | OS-level | None | Already done | Transparent |
| 7. `RequestCoalescer` | commonMain | N/A (single-flight) | Mutex (suspend) | Minimal | Low — wire into repos | Complementary; eliminates duplicate inflight loads |

---

## Risk and Failure Modes

**Option 1 (extend existing LruCache):** Stale reads if `saveBlock` through `DatabaseWriteActor` does not invalidate the cache. Current code only populates on reads; writes do not update it. A user saving a block and navigating back could see the old cached version until TTL expires. `invalidateAll()` on indent/outdent wipes the entire block cache, causing a cold start.

**Option 2 (Caffeine expect/actual):** Divergent eviction configs per platform make performance testing harder. Caffeine's `AsyncLoadingCache` uses `CompletableFuture`, which requires careful coroutine bridging. [TRAINING_ONLY — verify Caffeine 3.x + KMP coroutines integration]

**Option 5 (`shareIn` multicasting):** `SharingStarted.WhileSubscribed()` cancels the upstream on last subscriber. Two Composables subscribing in rapid succession may each get independent cold flows. Mitigated by `stopTimeoutMillis = 5_000`. A graph switch that cancels the scope loses the last-emitted `StateFlow` value.

**Option 7 (`RequestCoalescer`):** If the loader throws, all waiters receive the same exception — suppresses per-caller retry logic. Does not prevent repeated loads across distinct time windows.

**WAL mode (already applied):** With 8 persistent connections, WAL checkpointing relies on SQLite's automatic 1000-page threshold. Heavy write-load import sessions could grow the WAL file significantly. An explicit `PRAGMA wal_checkpoint(TRUNCATE)` after bulk graph import would help. [TRAINING_ONLY — verify SQLite JDBC WAL checkpoint behavior with persistent connections]

---

## Migration and Adoption Cost

| Change | Effort | Risk |
|--------|--------|------|
| Wire `saveBlock` write-back into `blockCache` in `SqlDelightBlockRepository` | Low | Low |
| Targeted cache invalidation by page UUID (replace `invalidateAll()`) | Low | Low |
| Wire `RequestCoalescer` into `getPageByName`/`getPageByUuid` | Low | Low |
| Add `shareIn` to `getBlocksForPage` in ViewModel layer | Medium | Medium |
| Add hit/miss counters to `LruCache` routed to `HistogramWriter` | Low | Low |
| Caffeine expect/actual on JVM | High | Medium |
| Remove dead `jvmMain` `CachedBlockRepository`/`BlockCache` | Low-Medium | Low |

---

## Operational Concerns

- **Memory pressure on mobile:** `platformCacheBytes()` returns 8 MB fixed on iOS and 10% of max heap on JVM (capped 32 MB). If a graph has 50k blocks at ~500 bytes average, the full block dataset is ~25 MB — exceeding the iOS budget. The cache should remain a hot-set (recent/open pages), not a full graph index.
- **Cache observability:** The common-layer `LruCache` has no metrics. Adding weight and hit-rate telemetry and routing through `HistogramWriter` would close this gap.
- **Graph switch cache leaks:** When `GraphManager.switchGraph()` is called, repositories are recreated and the old `SqlDelightBlockRepository` and its `blockCache` are abandoned to GC. Safe — no cross-graph state leaks — but means every graph switch cold-starts the cache.

---

## Prior Art and Lessons Learned

- **Logseq (ClojureScript):** Uses a datom-indexed in-memory database (DataScript) for all reads; SQLite is only a persistence layer. The lesson: a full in-memory index gives the best read performance at the cost of startup time and memory.
- **SQLite WAL + connection pool:** The existing `PooledJdbcSqliteDriver` correctly applies WAL at connection-creation time and pools 8 connections, consistent with SQLite documentation recommending WAL for concurrent read workloads.
- **Caffeine in Android apps:** Several large Android projects (Room cache helpers, Coil) use Caffeine on JVM but fall back to simpler caches on Android due to ProGuard/R8 surface and a separate JAR requirement. The expect/actual boundary adds maintenance burden without proportional gain unless profiling confirms the JVM block cache Mutex is a bottleneck.
- **`shareIn` in Jetpack Compose:** Community practice recommends lifting `stateIn`/`shareIn` to the ViewModel layer rather than the repository layer to avoid scope coupling. In SteleKit, `StelekitViewModel` is the natural owner.

---

## Open Questions

- [ ] Is `CachedBlockRepository`/`BlockCache` in `jvmMain` intentionally unused or was it meant to be wired into the production path? It has weaker semantics than the common-layer `LruCache`. — blocks decision on: whether to delete or fix it
- [ ] Does `getBlocksForPage` show up as a hot path? It is a live `asFlow().mapToList()` with no in-process cache — every subscriber adds a DB listener. — blocks decision on: `shareIn` priority
- [ ] What is the p95 `db.queue_wait` for `Execute` requests observed by `DatabaseWriteActor`? — blocks decision on: whether write serialization is a bottleneck before caching matters
- [ ] Should `hierarchyCache` TTL (120 s) be replaced with write-triggered invalidation? — blocks decision on: staleness budget

---

## Recommendation

**Tier 1 — High value, low risk, no new dependencies:**

1. Wire `saveBlock`/`saveBlocks` write-back into `blockCache` in `SqlDelightBlockRepository`. After a successful write, call `blockCache.put(block.uuid, block)`. Keeps the cache coherent without a DB re-read.

2. Replace `hierarchyCache.invalidateAll()` with page-scoped invalidation in indent/outdent/move methods. Track `pageUuid -> Set<rootUuid>` in a secondary index so only the affected page's hierarchies are evicted.

3. Wire `RequestCoalescer` into `SqlDelightPageRepository.getPageByName` and `getPageByUuid`. Already in `commonMain` and tested. Eliminates duplicate DB round-trips during simultaneous subscriber subscriptions.

**Tier 2 — Medium value, evaluate after profiling:**

4. Apply `.stateIn(SharingStarted.WhileSubscribed(5_000))` to `getBlocksForPage` in `StelekitViewModel` for pages with multiple active subscribers.

5. Add `LruCache` hit/miss counters routed to `HistogramWriter` so cache effectiveness is observable.

**Tier 3 — Defer until profiling evidence:**

6. Caffeine expect/actual: only justified if JVM profiling shows the `LruCache` Mutex is a contention bottleneck under real single-user desktop load.

7. AndroidX `LruCache`: same — defer until Android profiling.

8. Remove `jvmMain` `CachedBlockRepository`/`BlockCache`: dead code with weaker semantics. Remove to reduce confusion, but this is not a performance change.

---

## Pending Web Searches

1. `"caffeine" "kotlin multiplatform" site:github.com OR site:mvnrepository.com` — confirm whether a KMP-compatible Caffeine wrapper or port exists
2. `"app.cash.sqldelight" "shareIn" OR "stateIn" site:github.com` — find patterns for multicasting SQLDelight reactive flows
3. `"SQLite WAL checkpoint" "JDBC" "persistent connections"` — confirm WAL checkpoint behavior with long-lived JDBC connections
4. `"androidx.collection.LruCache" kotlin multiplatform` — confirm whether AndroidX collection-ktx LruCache is available for KMP targets
5. `"kotlin coroutines" "caffeine" "AsyncLoadingCache"` — find prior art bridging Caffeine async cache with Kotlin coroutines
6. `site:cashapp.github.io/sqldelight shareIn OR stateIn` — official SQLDelight docs on reactive flow sharing
