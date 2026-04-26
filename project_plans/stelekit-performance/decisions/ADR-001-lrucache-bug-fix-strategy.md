# ADR-001: LruCache O(n) Eviction Fix — LinkedHashMap Access-Order vs. Redundant Queue Removal

**Status**: Accepted
**Date**: 2026-04-24
**Deciders**: Tyler Stapler
**Feature**: SteleKit Performance — Story 1 (Safety and Bug Fixes)

---

## Context

`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cache/CacheCore.kt` contains a class called
`LRUCache<K, V>` (note: distinct from `LruCache<K, V>` in `commonMain`). This class maintains
two parallel data structures:

- `ConcurrentHashMap<K, CacheEntry<V>>` — the actual key-value store
- `ConcurrentLinkedQueue<K>` — a queue tracking LRU access order

The `updateLRU(key)` method calls `ConcurrentLinkedQueue.remove(key)` before re-adding the
key to the tail. Per the Java API documentation, `ConcurrentLinkedQueue.remove(Object)` is O(n)
— it traverses the queue linearly from head to tail until the element is found. At 10 000
cached entries, every cache access performs a 10 000-element linear scan.

The `commonMain` `LruCache<K, V>` (in `LruCache.kt`) already solves this correctly using
a `LinkedHashMap` in insertion-order mode. On each `get()`, the accessed entry is removed
and reinserted, moving it to the tail in O(1) amortized time (HashMap remove + put). Eviction
removes from the head via the iterator. All mutations are guarded by a `Mutex`, making it safe
for concurrent coroutine access.

The question is: should the `jvmMain` `LRUCache` be fixed in place, or should the whole
`jvmMain` cache layer be deleted and replaced by the `commonMain` `LruCache`?

Three options were considered:

| Option | Summary | Risk |
|--------|---------|------|
| A: Fix `LRUCache` in place | Replace `ConcurrentLinkedQueue` with `LinkedHashMap` access-order mode inside `LRUCache`; keep the `jvmMain` cache layer | Medium — must keep two cache implementations in sync |
| B: Delete the `jvmMain` cache layer | Confirm the layer is dead code; delete `CacheCore.kt`, `BlockCache.kt`, `CachedBlockRepository.kt`, `PageCache.kt` | Low — removes the duplication entirely |
| C: Replace with Caffeine | Add `com.github.ben-manes.caffeine:caffeine` to `jvmMain`; use W-TinyLFU | Medium — adds dependency; only beneficial if Mutex contention is measured |

---

## Decision

**Choose Option B: delete the jvmMain cache layer, provided it is confirmed dead code.**

`RepositoryFactoryImpl.createBlockRepository()` instantiates `SqlDelightBlockRepository`
directly. No call site in production code wraps it with `CachedBlockRepository` or `BlockCache`.
Grepping the full `kmp/src/` tree for `CachedBlockRepository`, `BlockCache`, and `PageCache`
instantiation sites confirms no production wire-up exists.

If the audit in Task 1.3 confirms the layer is unwired, all four files are deleted. The
`commonMain` `LruCache` already used by `SqlDelightBlockRepository` is the surviving
implementation. It is correct (O(1) LRU via `LinkedHashMap`), Mutex-guarded, and KMP-compatible.

If the audit unexpectedly finds a live production path, fall back to Option A: replace
`updateLRU` with the `LinkedHashMap` remove-and-reinsert pattern, guarded by a `Mutex`
(replacing the current `ConcurrentHashMap + ConcurrentLinkedQueue` pair, which has no shared
lock and is therefore not atomic under concurrent access).

Option C (Caffeine) is deferred. The `commonMain` `LruCache` is correct and sufficient for a
single-user desktop application. Caffeine's W-TinyLFU advantage over plain LRU is measurable
only under skewed access patterns at high concurrency — neither condition applies here. If JVM
profiling after Story 1 shows the `Mutex` is a hotspot, Caffeine can be introduced as a
`jvmMain`-only `expect/actual` seam without API changes.

---

## Consequences

**Positive**:
- Eliminates the O(n) eviction bug entirely by removing the implementation that has it.
- Eliminates the `Dispatchers.IO` violations in `BlockCache` and `PageCache` (same files).
- Eliminates the metrics-counter bug in `CacheCore.LRUCache.get()`.
- Reduces codebase size: four files (~750 lines) removed with no production behavior change.
- One canonical `LruCache` implementation in `commonMain`; no platform-specific cache divergence.

**Negative / Trade-offs**:
- Requires careful audit before deletion. If a live path exists (e.g., a test-only wire-up
  that bridges to production through a flag), the delete would break that path.
- If Caffeine is needed later, an `expect/actual` seam must be introduced. This is straightforward
  but adds boilerplate.

**Rejected alternatives**:
- In-place `LRUCache` fix: The class has three independent bugs (O(n) eviction, wrong
  dispatcher, metrics counter). Fixing all three on an unwired class is wasted effort. Deletion
  is cleaner.
- Caffeine now: No profiling evidence of Mutex contention. Premature optimization.

---

## Implementation Notes

- Task: Story 1, Task 1.3
- Audit command: `grep -rn "CachedBlockRepository\|CachedPageRepository\|BlockCache\|PageCache" kmp/src/`
  (exclude the four files themselves)
- Files to delete if confirmed dead:
  - `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cache/CacheCore.kt`
  - `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cache/BlockCache.kt`
  - `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cache/CachedBlockRepository.kt`
  - `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cache/PageCache.kt`
- Surviving implementation: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/cache/LruCache.kt`
- The `commonMain` `LruCache` uses `LinkedHashMap` insertion-order with remove-and-reinsert
  on `get()` — this is O(1) amortized. No changes required to the surviving file.
