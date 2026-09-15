# Requirements: Push-Based Block State Architecture

## Problem Statement

SteleKit's current block observation model uses SQLDelight reactive queries (`asFlow()`) to drive UI updates. SQLDelight uses **table-level** change notifications: any write to the `blocks` table wakes every active `getBlocksForPage(pageUuid)` subscriber, regardless of which `page_uuid` changed.

In the journal view with N visible pages, a single block write during background indexing triggers N re-executions of `SELECT * FROM blocks WHERE page_uuid = ?`. A 100 ms `blockObserveDebounceMs` was added as a workaround. This is architecturally mismatched: the database is a derived index (source of truth is markdown on disk), and `DatabaseWriteActor` already holds the block data it just wrote — re-querying the DB is redundant.

## Goals

1. Replace reactive pull (write → DB notification → all subscribers re-query) with push (write → actor emits block list → only the affected page's observer updates).
2. Ship in phases that deliver correctness and type safety at each step, converging on the full push model.
3. Remove the `blockObserveDebounceMs` workaround and the N standing reactive flow subscriptions.
4. Maintain or improve correctness for external file changes (GraphLoader path).
5. Establish a measurable performance benchmark for journal view latency during background indexing.

## Non-Goals

- Rewriting the block repository interface or SQLDelight schema.
- Changing how search results or page lists are observed (those are low-frequency and correctly use reactive flows).
- Supporting concurrent multi-graph simultaneous display (out of scope).

## Phased Delivery

### Phase 1 — Invalidation signal (ADR-012 intermediate step)

Add `blockInvalidations: SharedFlow<Set<PageUuid>>` to `DatabaseWriteActor`. All write paths emit their affected page UUIDs after a successful write. `BlockStateManager.observePage` subscribes to this signal and executes a one-shot `getBlocksForPage(pageUuid).first()` only when its specific page UUID (or the wildcard sentinel) appears.

Deliverables:
- `DatabaseWriteActor` emits page UUID sets
- `BlockStateManager` replaces standing reactive flow with invalidation-driven one-shot queries
- `blockObserveDebounceMs` parameter removed
- Reactive `getBlocksForPage` subscriptions removed from `observePage`
- Test: with 5 journal pages open and only page A written, `getBlocksForPage` is called ≤1 time for page A and 0 times for pages B–E

### Phase 2 — Full push payload (hot edit path)

Extend `DatabaseWriteActor` to emit `BlocksWritten(pageUuid: PageUuid, blocks: List<Block>)` for in-app writes that already have the full block list available (single block edits, split, merge, delete). `BlockStateManager` consumes the pushed list directly — zero DB re-query for in-app edits.

Deliverables:
- `DatabaseWriteActor` emits `BlocksWritten` for hot-path writes
- `BlockStateManager` applies pushed block lists to `_blocks: MutableStateFlow` directly
- External file changes (GraphLoader path) continue using the one-shot re-query (Phase 1 path)
- Test: an in-app block edit triggers zero calls to `getBlocksForPage` (state updated from push)
- Performance benchmark: journal view re-render time during large graph background indexing

### Phase 3 — Push for bulk import (optional follow-up)

Evaluate whether bulk import paths (`saveBlocks`, `saveBlocksDiff`) should also emit block lists. Given that bulk import writes hundreds of blocks per page, the push payload at that scale may be larger than beneficial. This phase is deferred until Phase 2 metrics inform the decision.

## Acceptance Criteria

| ID | Criterion | Phase |
|----|-----------|-------|
| AC-1 | With N journal pages visible, a write to page A triggers at most 1 DB read for page A and 0 reads for other pages | 1 |
| AC-2 | `blockObserveDebounceMs` parameter is deleted from `BlockStateManager` and `App.kt` | 1 |
| AC-3 | External file change to any page reflects in the UI within 2 seconds | 1 |
| AC-4 | All existing `BlockStateManager` tests are updated to be compatible with the invalidation-based observation model and continue to pass. No test may be deleted — each that relied on reactive re-emission must be converted to the invalidation-signal or optimistic-update path. The total test count must not decrease. | 1 |
| AC-5 | An in-app block edit (typing) triggers zero calls to `getBlocksForPage` (state updated from push) | 2 |
| AC-6 | Journal view block list re-render latency during large graph background indexing is measurably reduced vs. baseline (debounce workaround) | 2 |
| AC-7 | Graph switch (`switchGraph`) correctly clears pushed state and does not deliver stale blocks to the incoming graph's observers | 1+2 |

## Constraints

- Kotlin Multiplatform: changes must compile for JVM, Android, iOS. No platform-specific APIs in shared code.
- `DatabaseWriteActor` is shared infrastructure — changes must not break the telemetry write actor or any non-block actor usage.
- `InMemoryBlockRepository` (used in tests without a write actor) must remain functional without an invalidation signal.
- The `BlockRepository` interface (`getBlocksForPage: Flow<Either<DomainError, List<Block>>>`) must not change — downstream callers use `.first()` for one-shot reads.
- Type safety: the push payload type must be defined in `commonMain` with no raw `Any` casts. Sealed class preferred.

## Key Files

| File | Role |
|------|------|
| `db/DatabaseWriteActor.kt` | Add `blockInvalidations` + `blocksPushed` signals |
| `ui/state/BlockStateManager.kt` | Replace `observePage` reactive subscription |
| `ui/App.kt` | Wire actor signals to BlockStateManager |
| `repository/SqlDelightBlockRepository.kt` | Emit page UUIDs from write paths |
| `kmp/TESTING_README.md` | Test infrastructure reference |

## Reference

- ADR-012: `project_plans/stelekit/decisions/ADR-012-reactive-invalidation-model.md`
- SDD implementation plan: `project_plans/deferred-perf/reactive-invalidation/plan.md`
