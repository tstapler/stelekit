# ADR-002: Cache Invalidation via DatabaseWriteActor Callback vs. Alternatives

**Status**: Accepted
**Date**: 2026-04-24
**Deciders**: Tyler Stapler
**Feature**: SteleKit Performance — Story 2 (Cache Invalidation Wiring)

---

## Context

After Story 1 bug fixes, the `commonMain` `LruCache` instances in `SqlDelightBlockRepository`
(`blockCache`, `hierarchyCache`, `ancestorsCache`) are correct but not invalidated when a write
occurs. `DatabaseWriteActor` serializes all writes and already has a `CompletableDeferred` per
request. The question is how to attach cache eviction to the successful write path.

There is a correctness window: if a reader calls `blockCache.get(uuid)` between the moment a
write commits to SQLDelight and the moment `notifyListeners` re-emits, and the `LruCache` has a
cached copy of the old value, the reader sees stale data. This window exists today and grows
longer as the hierarchy TTL (2 minutes) keeps stale entries alive.

Four options were considered:

| Option | Summary | Key trade-off |
|--------|---------|---------------|
| A: Actor callback (`onWriteSuccess` lambda) | Add a nullable `suspend` lambda to `DatabaseWriteActor`; fire it on success before `deferred.complete()` | Zero new types; wired once in `RepositoryFactoryImpl`; `suspend` lambda calls `LruCache.remove()` safely |
| B: Repository observer pattern | Add a `List<CacheInvalidationListener>` to `SqlDelightBlockRepository`; write actor calls listeners after each write | More interface surface; requires listener registration plumbing |
| C: TTL-only, no invalidation | Keep the existing 2-minute hierarchy TTL; accept a staleness window | Simple; always wrong for fast write-then-read sequences |
| D: Reactive flow from write actor | Emit a `SharedFlow<WriteEvent>` from the actor; repositories collect and invalidate | Fully decoupled; adds a flow collection lifetime to manage; more moving parts |

---

## Decision

**Choose Option A: add an `onWriteSuccess: (suspend (WriteRequest) -> Unit)?` property to
`DatabaseWriteActor`.**

The actor already owns the canonical write lifecycle. A single callback point after each
successful write — fired before `deferred.complete()` — gives the cache eviction code exactly
the ordering guarantee it needs: the cache is evicted before the caller's `await()` returns,
which means no caller can observe the "write done" signal and then read a stale cache entry.

The callback is a nullable property (not a constructor parameter) so that:
- Existing construction sites (tests, in-memory backends) require no changes.
- The callback is wired in `RepositoryFactoryImpl.createRepositorySet()` only when the backend
  is `SQLDELIGHT`, where the `LruCache` caches exist.

The callback receives the full `WriteRequest` so the eviction code can be specific: a
`SaveBlocks` request evicts only the affected block UUIDs; a `DeleteBlocksForPage` request
clears the hierarchy cache for that page. This avoids full-cache invalidations on every write,
which would defeat the purpose of caching during bulk imports.

Invalidation ordering is: evict → `deferred.complete()`. This is the only safe ordering.
Reversing it would allow a reader to observe the write completion signal, re-read from the LRU
cache, and get the stale pre-write entry.

---

## Consequences

**Positive**:
- One callback point covers all write types (SaveBlocks, SaveBlock, DeleteBlocksForPage, etc.).
- No new interface types; the actor API grows by one nullable property.
- Eviction is targeted (per-UUID for single blocks; full hierarchy cache for structural changes).
- `suspend` lambda allows calling `LruCache.remove()` (which uses `Mutex.withLock`).
- Tests that do not wire the callback see no behavior change (property remains null).

**Negative / Trade-offs**:
- The callback runs inside the actor's coroutine — if it suspends for a non-trivial duration
  (e.g., `LruCache.invalidateAll()` under heavy contention), it adds latency to the write path.
  In practice, `LruCache.remove()` acquires the Mutex for nanoseconds; this is negligible.
- The callback does not know about batch coalescing: a coalesced batch of 50 `SaveBlocks`
  requests results in 50 individual `evictBlock()` calls. This is still correct and cheaper
  than a full cache clear.
- `onWriteSuccess` is mutable state on the actor. It is written once during construction and
  read on every write — this is safe only if it is set before the first write. Document that it
  must be set immediately after `DatabaseWriteActor` construction.

**Rejected alternatives**:
- Observer list (Option B): more interface surface for no behavioral advantage. The callback
  lambda is equivalent and simpler.
- TTL-only (Option C): creates a correctness hazard. A user who saves a block and immediately
  navigates away and back within 2 minutes sees the old content if the hierarchy cache is not
  invalidated. This is the exact staleness bug the research identified.
- SharedFlow from actor (Option D): fully decoupled but requires each repository to launch a
  collector coroutine, manage its lifetime, and handle backpressure. The simpler callback
  achieves the same result.

---

## Implementation Notes

- Task: Story 2, Tasks 2.1 and 2.2
- Actor property to add:
  ```kotlin
  // DatabaseWriteActor.kt — set once after construction, before first write
  var onWriteSuccess: (suspend (WriteRequest) -> Unit)? = null
  ```
- Eviction order in `processRequest` and `flushBatch`:
  ```
  1. Execute repository write (saveBlocks / savePage / deleteBlocksForPage)
  2. onWriteSuccess?.invoke(request)   // evict from LruCache
  3. deferred.complete(Result.success(Unit))  // unblock caller
  ```
- Methods to add to `SqlDelightBlockRepository`:
  ```kotlin
  suspend fun evictBlock(uuid: String) = blockCache.remove(uuid)
  suspend fun evictHierarchyForPage(pageUuid: String) {
      hierarchyCache.invalidateAll()
      ancestorsCache.invalidateAll()
  }
  ```
- Wire-up location: `RepositoryFactoryImpl.createRepositorySet()`, after actor construction,
  before `RepositorySet` is returned.
- External file change invalidation (Task 2.3) uses the same `evictHierarchyForPage` method
  but is wired separately via `GraphLoader.externalFileChanges`.
