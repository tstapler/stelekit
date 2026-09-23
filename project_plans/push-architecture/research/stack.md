# Research: Kotlin Coroutines / SharedFlow Patterns for Push-Based State in KMP

## 1. SharedFlow vs StateFlow for the Invalidation Bus

**Use `SharedFlow`, not `StateFlow`.**

`StateFlow` holds a single current value and only emits when the value changes. That model is wrong for an event bus: two consecutive writes to the same page (e.g. split + merge during a bulk import) would produce two identical `Set<PageUuid>` emissions that `StateFlow` would de-duplicate, silently dropping the second invalidation. `SharedFlow` has no de-duplication by default and can buffer events independently of consumption.

The specific mismatch: `DatabaseWriteActor` uses `onWriteSuccess` today. The new signal needs to fire once per successful write, even if the same page UUID was just emitted. `SharedFlow` semantics match "fire-and-forget events" exactly; `StateFlow` semantics match "current truth snapshot."

For the Phase 1 invalidation signal declare:
```kotlin
private val _blockInvalidations = MutableSharedFlow<Set<PageUuid>>(extraBufferCapacity = 64)
val blockInvalidations: SharedFlow<Set<PageUuid>> = _blockInvalidations.asSharedFlow()
```

For the Phase 2 push payload, the `BlocksWritten` sealed subclass should also be emitted via `SharedFlow` since it is a one-shot event with a payload, not a persistent state.

## 2. Buffer Size and Overflow Strategy

**`extraBufferCapacity = 64`, `onBufferOverflow = DROP_OLDEST`.**

Rationale:
- The actor is single-threaded (one coroutine); `BlockStateManager` observers run on `Dispatchers.Default`. During bulk import the actor can emit dozens of invalidation signals per second while the observers are suspended doing one-shot DB re-queries. Without a buffer, `emit()` would suspend the actor, blocking the write loop.
- `extraBufferCapacity = 64` covers a full `indexRemainingPages` batch cycle (batch size = 100 pages, but coalescing in `processSaveBlocks` reduces the number of emissions). 64 is well within heap budget: a `Set<PageUuid>` with O(10) elements is ~1 KB.
- `DROP_OLDEST` (not `DROP_LATEST` or `SUSPEND`) is correct here because a stale invalidation is harmless — the page will be invalidated again on the next write. Dropping the latest would suppress the most recent change; suspending the actor would reintroduce the head-of-line blocking the bus is meant to eliminate.

For the Phase 2 `BlocksWritten` payload, the same buffer policy applies. Bulk import paths that emit hundreds of blocks per page should be excluded from the push payload (they use the Phase 1 invalidation + one-shot re-query path) so the buffer is never loaded with large block lists.

```kotlin
private val _blocksPushed = MutableSharedFlow<BlockUpdateEvent>(
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
```

## 3. Safe SharedFlow Sharing Across KMP Scopes

**The flow lives on the actor scope; each subscriber scope cancels its own `collect` independently.**

`DatabaseWriteActor` owns its `actorScope` (a `SupervisorJob + PlatformDispatcher.Default` scope that outlives Compose recomposition — already established). The `SharedFlow` object is allocated once inside the actor. Subscribers (`BlockStateManager.observePage`) launch a `scope.launch { actor.blockInvalidations.collect { ... } }` inside their own scope.

Key properties:
- A subscriber's scope cancellation (e.g. `BlockStateManager.unobservePage` after the 5-second keepalive) stops only that subscriber's `collect`. The `SharedFlow` itself and other subscribers are unaffected — no `BroadcastChannel` teardown, no race on `close()`.
- Graph switch: `BlockStateManager.close()` calls `scope.cancel()`, which cancels all child `collect` jobs. The actor's `SharedFlow` is unaffected and ready for the next graph's `BlockStateManager`. No stale-page delivery is possible because the collecting job is already cancelled before the new graph's `BlockStateManager` is created.
- iOS/WASM: `SharedFlow` is pure Kotlin, no JVM-specific code. `PlatformDispatcher.Default` on iOS maps to `Dispatchers.Default` (GCD-backed). Safe across all KMP targets.
- Do not call `resetReplayCache()` or expose `MutableSharedFlow` directly — use `.asSharedFlow()` to restrict emission to the actor.

## 4. Sealed Class Event Type in commonMain

Define the sealed class in `commonMain` with no platform dependencies:

```kotlin
// db/BlockUpdateEvent.kt (commonMain)
sealed class BlockUpdateEvent {
    /** Phase 1: one or more pages were written; subscribers should re-query their page if present. */
    data class PagesInvalidated(val pageUuids: Set<PageUuid>) : BlockUpdateEvent()

    /** Phase 2: an in-app write already has the full block list; no DB re-query needed. */
    data class BlocksWritten(val pageUuid: PageUuid, val blocks: List<Block>) : BlockUpdateEvent()
}
```

Notes:
- `data class` rather than `object` for `PagesInvalidated` makes unit testing straightforward (structural equality on the `Set`).
- Both `PageUuid` and `Block` are already value-class-safe `data class` types defined in `commonMain/model/Models.kt`.
- No `@JvmField`, `@ObjCName`, or platform annotations needed — Kotlin sealed classes serialize correctly across all KMP targets.
- A single `SharedFlow<BlockUpdateEvent>` can carry both event types, simplifying the actor API surface. Alternatively, two separate flows (`blockInvalidations: SharedFlow<Set<PageUuid>>` and `blocksPushed: SharedFlow<BlockUpdateEvent.BlocksWritten>`) allow Phase 1 and Phase 2 to be wired independently without a type switch.

## 5. collectLatest vs collect on the Observer Side

**`collectLatest` for the invalidation-driven re-query; `collect` for the pushed block list.**

`collectLatest` cancels the previous block (the in-flight `getBlocksForPage().first()`) when a new emission arrives before the query completes. This is exactly right for Phase 1: if page A is invalidated three times in rapid succession, only the last one-shot query should complete; earlier queries are stale by the time they'd finish. `collectLatest` implements this de-bouncing automatically at zero allocation cost.

```kotlin
// BlockStateManager.observePage (Phase 1)
scope.launch {
    actor.blockInvalidations.collectLatest { pageUuids ->
        if (pageUuidStr !in pageUuids && WILDCARD !in pageUuids) return@collectLatest
        val result = blockRepository.getBlocksForPage(pageUuid).first()
        _blocks.update { ... }
    }
}
```

For Phase 2 (`BlocksWritten`), use `collect` (not `collectLatest`) because the push payload is the authoritative block list. Cancelling it mid-apply would leave `_blocks` in a partial state. The payload is O(1) to apply (a single `_blocks.update { ... }` call), so there is no meaningful work to cancel.

The guard against stale state for rapid edits (e.g. the user typing) is already handled by the `_dirtyBlocks` dirty-set merge in `BlockStateManager.mergeBlocks`. Even if two `BlocksWritten` events arrive for the same page, the second overwrites the first in `_blocks`, and dirty blocks from in-progress edits are preserved by the merge logic.

## Implementation Notes for This Codebase

- **`onWriteSuccess` hook**: The current `@Volatile var onWriteSuccess: (suspend (WriteRequest) -> Unit)?` is a nullable hook injected post-construction (used by telemetry). The new `SharedFlow` emission should be added directly inside `flushBatch`, `processDeleteBlocksForPage`, `processSaveBlocksDiff`, and `processExecute` after a successful write — co-located with the existing `onWriteSuccess?.invoke(request)` call, not replacing it. Both can fire.
- **InMemoryBlockRepository path**: `BlockStateManager` is constructed without a `DatabaseWriteActor` in tests. The `actor?.blockInvalidations` subscription is null-guarded and the existing reactive `getBlocksForPage` flow acts as fallback (Phase 1 constraint: `InMemoryBlockRepository` remains functional). Only wire the SharedFlow subscription when `writeActor != null`.
- **`blockObserveDebounceMs` removal**: Once `observePage` switches from the standing reactive flow to the invalidation-driven one-shot pattern, the debounce parameter has no role. Remove it from `BlockStateManager` constructor and `App.kt` after Phase 1 lands and tests confirm AC-1.
- **Wildcard sentinel**: For `DeleteBlocksForPages` (which does not know the block list per page before deletion), emit a wildcard sentinel (`PageUuid("*")` or a dedicated `object WildcardPageUuid`) so observers that can't match a specific UUID still receive the signal. `BlockStateManager` already tracks `_activePageUuids`; it can re-query only active pages on wildcard, not all known pages.
