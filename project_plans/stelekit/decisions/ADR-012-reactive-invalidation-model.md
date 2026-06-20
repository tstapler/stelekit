# ADR-012: Reactive Invalidation Model for Block Observation

**Status**: Proposed
**Date**: 2026-06-18
**Feature**: Performance — Block Observation / Read Fanout

## Context

`BlockStateManager.observePage(pageUuid)` subscribes to `blockRepository.getBlocksForPage(pageUuid)`, which is a SQLDelight reactive `asFlow()` backed by `selectBlocksByPageUuidUnpaginated`. SQLDelight's reactive queries use **table-level** change notifications: any write to the `blocks` table wakes every active subscriber regardless of which `page_uuid` changed.

In the journal view (N most-recent daily pages rendered simultaneously), this means a write to any block — including writes to completely unrelated pages during background indexing — triggers N re-executions of `SELECT * FROM blocks WHERE page_uuid = ?`. During Phase 3 background indexing (50-row chunks, 80 chunks for a large page), each chunk commit fires N reads competing with the ongoing write on Android's single SQLite connection.

A 100 ms `blockObserveDebounceMs` parameter was added to `BlockStateManager` as a mitigation. It drops all-but-the-last notification per page per 100 ms window. This reduces N×writes re-reads to N×(1 per 100 ms window) but does not eliminate the redundant reads.

The root cause is that the notification system has less information than it needs: it knows "some row in `blocks` changed" but not "which `page_uuid` changed". `DatabaseWriteActor`, by contrast, already knows exactly which pages are affected by every write it processes.

### Why the current model is architecturally mismatched

SQLite reactive flows are designed for apps where the database is the canonical source of truth and the UI is a live projection of it. SteleKit's actual source of truth is markdown files on disk; SQLite is a derived index. Every block write round-trips through: **write → DB → notification → re-query → render**. The actor already has the block data it just wrote; re-querying it from the DB is redundant work.

The long-term direction is a **push model** where the actor emits new block state directly, bypassing the re-query entirely. This ADR captures the intermediate step: replacing table-level notifications with page-UUID-scoped invalidation. The push model is a natural follow-on once the invalidation signal exists.

## Decision

Replace the N standing `getBlocksForPage` reactive flows in `BlockStateManager` with a page-UUID-scoped invalidation signal:

### 1. Add `blockInvalidations` to `DatabaseWriteActor`

```kotlin
// DatabaseWriteActor.kt
val blockInvalidations: SharedFlow<Set<PageUuid>> = _blockInvalidations.asSharedFlow()
private val _blockInvalidations = MutableSharedFlow<Set<PageUuid>>(
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
```

Each write arm in `processRequest` emits the affected page UUIDs after a successful write. `WriteRequest.Execute` (arbitrary actor lambdas) emits a `WILDCARD_PAGE_UUID` sentinel that triggers all active observers to re-fetch, preserving correctness for paths that don't yet carry explicit page UUID context.

### 2. Rewrite `BlockStateManager.observePage`

Replace the standing reactive flow subscription:

```kotlin
// Before
blockRepository.getBlocksForPage(pageUuid).collect { result -> … }

// After
// Initial load
pullBlocksForPage(pageUuid)

// Invalidation-driven refresh
invalidationSource
    .filter { it.contains(pageUuid) || it.contains(WILDCARD_PAGE_UUID) }
    .collect { pullBlocksForPage(pageUuid) }
```

`pullBlocksForPage` executes `blockRepository.getBlocksForPage(pageUuid).first()` — a one-shot query, not a standing subscription.

### 3. Wire the invalidation source

`BlockStateManager` receives `invalidationSource: SharedFlow<Set<PageUuid>>?` as a constructor parameter (nullable for backwards compatibility with tests that use `InMemoryBlockRepository` without an actor).

At the `App.kt` construction site (line ~583), pass `repos.writeActor?.blockInvalidations`.

### 4. Three-phase rollout

To minimise regression risk the change ships in three phases:

| Phase | Change | Risk |
|-------|--------|------|
| 1 | Add `blockInvalidations` to actor; emit after every write. No `BlockStateManager` change. | None — signal is unused |
| 2 | Replace standing subscription in `observePage`; add `invalidationSource` parameter; remove `blockObserveDebounceMs`. | Medium — needs staging |
| 3 | Remove `blockObserveDebounceMs` from `BlockStateManager` constructor; delete the parameter from `App.kt`. | Low — cleanup only |

### 5. External file changes

`GraphLoader.externalFileChanges` already emits per-page signals and routes them through `BlockStateManager` separately. These continue to work unchanged — they call `loadPage` which writes through the actor, which then emits the invalidation.

## Rationale

- **Eliminates broadcast fan-out**: during background indexing, only the page currently being written triggers a re-read instead of all N visible pages.
- **Actor is the natural chokepoint**: all block writes already pass through `DatabaseWriteActor`. Attaching page-UUID emission there gives 100% coverage with no per-write-path instrumentation outside the actor.
- **Makes debounce unnecessary**: page-scoped invalidation means a burst of writes to page A triggers exactly one re-read for page A observers, not N re-reads for all page observers. The 100 ms debounce was compensating for the broadcast; with scoped signals it is no longer needed.
- **Foundation for the push model**: once `blockInvalidations` exists, the actor can be extended to emit `BlocksUpdated(pageUuid, List<Block>)` alongside the invalidation signal. `BlockStateManager` can consume the pushed list directly, eliminating the re-query entirely for in-app edits. The invalidation model is a stepping stone toward that goal, not the final destination.
- **No interface breakage**: `BlockReadRepository.getBlocksForPage` stays as `Flow<Either<DomainError, List<Block>>>`. All callers that use `.first()` for one-shot reads are unaffected.

## Consequences

**Positive**

- Journal view block reads during background indexing drop from O(N×writes) to O(1×writes).
- `blockObserveDebounceMs` parameter and its test coverage gap are removed.
- `conflate()` on reactive `getBlocksForPage` flows reverts to a pure safety net rather than a required load-shedding mechanism.
- Phase 1 (actor signal addition) is zero-risk and can ship immediately.

**Negative / Trade-offs**

- One round-trip gap: a one-shot query fires after each invalidation signal rather than the write itself carrying the new state. On Android with WAL mode, concurrent reads are allowed and this gap is sub-millisecond in practice.
- `WriteRequest.Execute` (arbitrary lambdas) emits `WILDCARD_PAGE_UUID`, which is conservative — it wakes all observers. Callers that know their affected pages should use typed `WriteRequest` variants instead. Over time the wildcard should shrink to zero callers.
- Implementation touches `DatabaseWriteActor`, `BlockStateManager`, and `App.kt`. All three require careful testing across graph-switch and closed-DB edge cases.

## Follow-on: push model

Once this ADR is implemented, the natural next step is to have the actor emit block lists directly alongside the invalidation signal:

```kotlin
data class BlocksWritten(val pageUuid: PageUuid, val blocks: List<Block>)
val blocksPushed: SharedFlow<BlocksWritten>
```

`BlockStateManager` would consume `blocksPushed` first (zero re-query), falling back to the invalidation-triggered re-query only for writes that don't carry explicit block content (bulk imports, external file changes). This eliminates the re-query entirely for the hot user-edit path and is documented separately as the push model evolution.

## Related

- Supersedes the `blockObserveDebounceMs = 100L` workaround added in `perf(android): reduce editing latency`
- Complements ADR-011 (wikilink reference index): that ADR fixed write-path O(N) costs; this ADR fixes read-path O(N×writes) costs
- Implementation plan: `project_plans/deferred-perf/reactive-invalidation/plan.md`
