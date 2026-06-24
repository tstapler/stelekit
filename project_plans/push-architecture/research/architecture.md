# Research: DatabaseWriteActor Extension Design

## 1. All Write Request Types in `DatabaseWriteActor`

`processRequest` dispatches on seven arms:

| Arm | Type | Page UUIDs affected |
|-----|------|---------------------|
| `SavePage` | `WriteRequest.SavePage` | none (page write, not block) |
| `SavePages` | `WriteRequest.SavePages` | none (page write, not block) |
| `SaveBlocks` | `WriteRequest.SaveBlocks` | **all distinct `block.pageUuid` values** in `request.blocks`; coalesced batches span multiple requests, so a single flush may affect multiple pages |
| `SaveBlocksDiff` | `WriteRequest.SaveBlocksDiff` | **all distinct page UUIDs** across `request.toInsert + request.toUpdate` |
| `DeleteBlocksForPage` | `WriteRequest.DeleteBlocksForPage` | **`request.pageUuid`** — exactly one, typed |
| `DeleteBlocksForPages` | `WriteRequest.DeleteBlocksForPages` | **`request.pageUuids`** — typed list |
| `Execute` | `WriteRequest.Execute` | **structurally unavailable** — arbitrary lambda; page UUID not present in the request |

### Coalescing detail for SaveBlocks
`processSaveBlocks` drains consecutive same-priority `SaveBlocks` from the channel into a `batch: MutableList<WriteRequest.SaveBlocks>`. `flushBatch` then calls `blockRepository.saveBlocks(allBlocks)` for the merged set. All individual `onWriteSuccess` hooks are called per-request only on success. On partial failure, each request is retried individually. This means:

- For Phase 1 invalidation: the affected page UUIDs for a coalesced `SaveBlocks` flush are `batch.flatMap { req -> req.blocks.map { it.pageUuid } }.toSet()`.
- The same coalescing applies to `SaveBlocksDiff` in `flushDiffBatch`.

### Key observation: `Execute` path is opaque
The following `DatabaseWriteActor` public methods all route through `execute { ... }` and therefore produce only a generic `WriteRequest.Execute` in the queue:
- `saveBlock(block: Block)` — page UUID is *inside the lambda*, not on the request
- `updateBlockContentOnly(blockUuid, content)` — block UUID only, page UUID requires an additional DB lookup
- `updateBlockPropertiesOnly(blockUuid, properties)` — same
- `deleteBlock(blockUuid)` — same
- `splitBlock(blockUuid, cursorPosition, newBlockUuid)` — same
- `mergeBlocks(blockUuid, nextBlockUuid, separator)` — same
- `deleteBlockStructural(blockUuid)` — same
- `restorePageToSnapshot` calls — page UUID is captured in the closure but not on the request

---

## 2. Write Paths: Typed Page UUID vs. Opaque Execute

### Typed page UUID available on the request

| Path | Type | How to extract page UUID(s) |
|------|------|----------------------------|
| `saveBlocks(blocks)` | `SaveBlocks` | `request.blocks.map { it.pageUuid }.toSet()` |
| `saveBlocksDiff(toInsert, toUpdate)` | `SaveBlocksDiff` | `(request.toInsert + request.toUpdate).map { it.pageUuid }.toSet()` |
| `deleteBlocksForPage(pageUuid)` | `DeleteBlocksForPage` | `setOf(request.pageUuid)` |
| `deleteBlocksForPages(pageUuids)` | `DeleteBlocksForPages` | `request.pageUuids.toSet()` |

### Opaque Execute — page UUID not structurally available

| Actor method | Description | Page UUID extraction |
|---|---|---|
| `saveBlock(block)` | `execute { blockRepository.saveBlock(block) }` | Block arg is captured in closure but not on `WriteRequest.Execute`; could be added as a typed request |
| `updateBlockContentOnly(blockUuid, content)` | `execute { ... }` | No page UUID; blockUuid only; requires DB lookup to resolve |
| `updateBlockPropertiesOnly(blockUuid, props)` | `execute { ... }` | Same as above |
| `deleteBlock(blockUuid)` | `execute { ... }` (with op-logger pre-read) | No page UUID |
| `splitBlock(...)` | `execute { ... }` | No page UUID |
| `mergeBlocks(...)` | `execute { ... }` | No page UUID |
| `deleteBlockStructural(blockUuid)` | `execute { ... }` | No page UUID |
| `execute { ... }` (generic, from `BlockStateManager`) | Arbitrary: `moveBlock`, `indentBlock`, `outdentBlock`, `deleteBulk`, `restorePageToSnapshot` | Completely opaque |

### Implication for Phase 1

For the invalidation-signal approach (emit `Set<PageUuid>` after each write), the typed arms can emit page UUIDs directly from the request. The `Execute` arm cannot. The cleanest path is:

1. For `saveBlock`, `updateBlockContentOnly`, `splitBlock`, `mergeBlocks`, `deleteBlock`, `deleteBlockStructural`: promote each to a dedicated typed `WriteRequest` subclass carrying the relevant page UUID.
2. For the generic `execute { }` path (structural ops called from `BlockStateManager`): either (a) add a `pageUuids: Set<PageUuid>?` parameter to `WriteRequest.Execute` as an optional annotation, or (b) add a wildcard sentinel emission after every `Execute` request that forces all active observers to re-query.

Option (b) is the safe fallback — it degrades to today's behavior for opaque lambdas but keeps the optimization for typed requests. Option (a) requires callers to declare page UUIDs at the call site. Both are KMP-compatible.

---

## 3. `onWriteSuccess` — Current State and Extension Potential

### Current use

`onWriteSuccess` is a `@Volatile` nullable lambda wired in `RepositoryFactory.wireCacheCallbacks` (line 375 of `RepositoryFactory.kt`):

```kotlin
actor.onWriteSuccess = { request ->
    when (request) {
        is WriteRequest.SaveBlocks ->
            request.blocks.forEach { sqlBlockRepo.evictBlock(it.uuid.value) }
        is WriteRequest.SaveBlocksDiff -> { ... evict inserts + updates ... }
        is WriteRequest.DeleteBlocksForPage ->
            sqlBlockRepo.evictHierarchyForPage(request.pageUuid.value)
        is WriteRequest.DeleteBlocksForPages ->
            request.pageUuids.forEach { sqlBlockRepo.evictHierarchyForPage(it.value) }
        else -> Unit   // Execute: no eviction, TTL expiry only
    }
}
```

**It is actively used in production** to invalidate `SqlDelightBlockRepository`'s in-memory block and hierarchy LRU caches. This is set exactly once (in `wireCacheCallbacks`), so there is no current multi-subscriber design.

### Can it carry `Set<PageUuid>`?

`onWriteSuccess: (suspend (WriteRequest) -> Unit)?` is a single slot — only one callback is registered. Adding page UUID emission alongside cache eviction requires either:

1. **Chaining inside the existing callback**: the wiring in `RepositoryFactory` handles both cache eviction AND emits to a `MutableSharedFlow<Set<PageUuid>>` in sequence. This is the path of least resistance — no API change to `DatabaseWriteActor`.
2. **Replacing with a proper hook mechanism**: change `onWriteSuccess` to a `SharedFlow<WriteRequest>` on the actor, then let `wireCacheCallbacks` and the new invalidation wiring each collect independently. This is cleaner but requires an API change to `DatabaseWriteActor`.

The constraint from requirements: `DatabaseWriteActor` is shared infrastructure — the callback slot is already used for cache eviction and must not be broken. Option 1 (extend the existing callback) is the lower-risk path.

A `blockInvalidations: MutableSharedFlow<Set<PageUuid>>` could live either:
- On `DatabaseWriteActor` (actor owns the flow, emits in `onWriteSuccess`) — clear ownership, but adds coupling between the actor and block domain
- On `RepositorySet` (wired externally, emitting in the `onWriteSuccess` closure registered in `wireCacheCallbacks`) — no actor API change needed

---

## 4. `BlockStateManager.observePage` — Full Subscribe Loop

The subscribe loop (lines 363–387 of `BlockStateManager.kt`):

```kotlin
observationJobs[pageUuidStr] = scope.launch {
    // 1. Trigger lazy disk load if page is not yet content-loaded and not already in cache
    val alreadyCached = _blocks.value[pageUuidStr]?.isNotEmpty() == true
    if (!isContentLoaded && !alreadyCached) {
        _loadingPageUuids.update { it + pageUuidStr }
        try {
            graphLoader.loadFullPage(pageUuidStr)
        } finally {
            _loadingPageUuids.update { it - pageUuidStr }
        }
    }

    // 2. Standing reactive subscription to the DB
    val pageFlow = blockRepository.getBlocksForPage(pageUuid)
    val debouncedFlow = if (blockObserveDebounceMs > 0) pageFlow.debounce(blockObserveDebounceMs) else pageFlow
    debouncedFlow.collect { result ->
        val incomingBlocks = result.getOrNull() ?: emptyList()
        _blocks.update { current ->                              // ← _blocks.update call #1
            val localBlocks = current[pageUuidStr] ?: emptyList()
            val merged = mergeBlocks(localBlocks, incomingBlocks)
            current + (pageUuidStr to merged)
        }
    }
}
```

### `_blocks.update` calls to replicate in push model

The `_blocks.update` inside the `debouncedFlow.collect` is the only `_blocks` write in `observePage`. It must be replicated in the push subscription handler. The `mergeBlocks` function (lines 423–442) is a pure function that must be reused as-is — it handles dirty-set semantics (keeping locally-edited blocks over incoming DB versions) and pending new blocks (optimistically inserted blocks not yet in DB).

Other `_blocks.update { }` calls elsewhere in `BlockStateManager` are NOT part of `observePage` — they are point-in-time updates in structural operations (`addBlockToPage`, `splitBlock`, `deleteSelectedBlocks`, `refreshBlocksForPage`, `moveSelectedBlocks`, `loadPageContent`, `restorePageToSnapshot`). These must remain as-is — they perform their own DB queries and optimistic local mutations.

### Under the push model:

The `debouncedFlow.collect` block would be replaced by:
```kotlin
// Phase 1: subscribe to invalidation signal
invalidationSignal.collect { invalidatedPageUuids ->
    if (pageUuidStr !in invalidatedPageUuids && WILDCARD_SENTINEL !in invalidatedPageUuids) return@collect
    val result = blockRepository.getBlocksForPage(pageUuid).first()
    val incomingBlocks = result.getOrNull() ?: emptyList()
    _blocks.update { current ->
        val localBlocks = current[pageUuidStr] ?: emptyList()
        current + (pageUuidStr to mergeBlocks(localBlocks, incomingBlocks))
    }
}
```

The lazy disk load preamble is unchanged. The debounce is removed entirely.

---

## 5. Graph Switch (`switchGraph`) Effect on Active Subscriptions

### Current mechanism

`GraphManager.switchGraph` (lines 335–435 of `GraphManager.kt`):

1. Cancels the previous graph's `CoroutineScope` via `activeGraphJobs.remove(previousId)?.cancel()` (line 349). This is the *graph-level* scope, not `BlockStateManager`'s scope.
2. Nulls `_activeRepositorySet` BEFORE closing the driver (line 359), so Compose collectors that observe `activeRepositorySet` see null and stop querying before the DB is torn down.
3. Calls `currentFactory?.close()` which closes the SQLite driver.
4. Creates a new `CoroutineScope` for the incoming graph (line 364).

**`BlockStateManager`'s own scope** (`CoroutineScope(SupervisorJob() + Dispatchers.Default)`) is **not cancelled by `switchGraph`**. The graph switch happens at the `GraphManager` level; `BlockStateManager` is a Compose-managed object that outlives individual graphs and is replaced when `key(activeGraphId)` forces a Compose recomposition.

In practice, the Compose `key(activeGraphId)` block in `App.kt` disposes the old `BlockStateManager` (calling `close()` which cancels its scope) and creates a fresh one. This means:

- Old `observePage` subscriptions are cancelled when `BlockStateManager.close()` is called as part of the Compose keyed recomposition.
- The `catchDbError()` guard in `SqlDelightBlockRepository.getBlocksForPage` converts closed-DB exceptions to `Either.Left` if a subscription somehow survives into the teardown window.

### Impact on push model

Under the push model, `BlockStateManager` would subscribe to a `blockInvalidations: SharedFlow<Set<PageUuid>>` on the actor or `RepositorySet`. On graph switch:

1. The old actor is closed (`actor.close()` calls `highPriority.close()` and `lowPriority.close()`).
2. `BlockStateManager.close()` cancels its scope, ending the invalidation collector.
3. A new `RepositorySet` (with new actor and new `blockInvalidations` flow) is created for the incoming graph.

The risk: if `BlockStateManager.close()` is called *after* the new actor is registered in `_activeRepositorySet`, the stale BSM could briefly receive invalidations from the new actor. However, since `key(activeGraphId)` disposes/creates composables in the same recomposition frame, this is a transient race that the dirty-set merge logic already handles (stale blocks from a prior page UUID simply won't match the new page UUIDs being observed).

**AC-7 requirement**: graph switch must clear pushed state and not deliver stale blocks to the incoming graph's observers. This is guaranteed structurally: `BlockStateManager.close()` cancels the invalidation collector, and a fresh `BlockStateManager` starts with an empty `_blocks` map and fresh subscriptions.

---

## 6. `DatabaseWriteActor` and `HistogramWriter` Relationship

### Current wiring

`HistogramWriter` is referenced in `DatabaseWriteActor` in two ways:

1. **Static usage for timestamps**: `HistogramWriter.epochMs()` is called as a static/companion method to capture enqueue times on `SaveBlocks.enqueueMs` and `Execute.enqueueMs`. This has no instance dependency.
2. **Instance field**: `@Volatile var histogramWriter: HistogramWriter? = null` — set after construction by whoever creates the actor. It is used only in `processExecute`:
   ```kotlin
   histogramWriter?.record("db.write_queue_depth", _activeOps.value.toLong())
   ```
   This records the queue depth metric at the moment an `Execute` request is dequeued for processing.

### Would adding push signals affect histogram tracking?

Adding a `MutableSharedFlow<Set<PageUuid>> blockInvalidations` field to `DatabaseWriteActor` and emitting from `onWriteSuccess` (or from `processRequest` directly) would NOT affect `HistogramWriter` at all:

- The histogram records queue depth and queue wait times — these are unaffected by emitting to a `SharedFlow`.
- `SharedFlow.emit` in Kotlin Coroutines is a fast non-blocking operation for replay=0 flows (the standard `MutableSharedFlow()` default) — no contention with the actor's serialized write loop.
- The only concern would be if `blockInvalidations` used a large `replay` buffer that held block lists (as in Phase 2 `BlocksWritten`) and created GC pressure. For Phase 1 (just `Set<PageUuid>`), this is negligible.

The `ringBuffer: RingBufferSpanExporter` (separate from `HistogramWriter`) records span events for queue wait times. Adding push emission does not change span recording — the emit occurs after `request.deferred.complete(result)` in the current `onWriteSuccess` model, which is already after all timing is recorded.

---

## Summary

**Key finding 1: The Execute-path opacity is the critical design constraint.**
Seven of the most important hot-path writes (`saveBlock`, `updateBlockContentOnly`, `splitBlock`, `mergeBlocks`, `deleteBlock`, `deleteBlockStructural`, and all structural ops via generic `execute {}`) flow through `WriteRequest.Execute`, which carries no page UUID. To emit accurate invalidations for these paths, they must either be promoted to typed request subclasses (preferred for Phase 2 full-push), or the generic `Execute` request must accept an optional `pageUuids` annotation (simpler for Phase 1).

**Key finding 2: `onWriteSuccess` is already in use for cache eviction and must be extended, not replaced.**
The existing `wireCacheCallbacks` in `RepositoryFactory` wires `onWriteSuccess` to evict `SqlDelightBlockRepository`'s LRU caches. Any push signal emission must be chained inside this same callback (or `onWriteSuccess` must be promoted to a `SharedFlow<WriteRequest>` for multi-subscriber support). The single-slot design is the current constraint; a `SharedFlow` replacement is the clean path but requires an API change.

**Key finding 3: `BlockStateManager.observePage`'s `_blocks.update` inside `debouncedFlow.collect` is the single site to replace, and `mergeBlocks` must be reused as-is.**
The dirty-set merge logic in `mergeBlocks` handles pending new blocks and locally-edited blocks — it is the correctness heart of the observation model. The push model must call it with the same arguments (`localBlocks = current[pageUuidStr]`, `incomingBlocks = pushed or queried list`). Graph switch safety is already structurally guaranteed by Compose's `key(activeGraphId)` disposal of the old `BlockStateManager`.
