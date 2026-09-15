# Reactive Invalidation Model — Implementation Plan

**Goal:** Eliminate table-level SQLDelight fan-out so that a write to page A does not wake
observers for pages B, C, D, E when multiple journal pages are visible.

---

## 1. Requirements and Success Criteria

### Done means:

1. With N journal pages open simultaneously, a block write targeting page A causes exactly 1
   DB re-read (for page A), not N re-reads.
2. The 100 ms `blockObserveDebounceMs` is fully removed in production. No workaround debounce
   replaces it.
3. First-load cold start (page not yet in `_blocks`) is unchanged: `observePage` still triggers
   an initial pull via `getBlocksForPage(...).first()`.
4. Writes routed through `WriteRequest.Execute` (arbitrary lambdas — block UUID unknown)
   trigger a broad invalidation that re-fetches all **currently active** observers. This is a
   conservative safe fallback, not a regression.
5. `BlockRepository.getBlocksForPage` remains a `Flow` in the interface (no breaking change
   across all callers). The standing reactive subscription inside `observePage` is replaced with
   a pull-on-signal pattern, but the method signature stays intact.
6. The existing `MigrationRunner`, `GraphLoader.indexRemainingPages`, and
   `DatabaseWriteActor.processDeleteBlocksForPage` callers of `getBlocksForPage(...).first()` are
   unaffected.

### Measurable success criteria:

- `observePage_should_notRefetch_when_differentPageIsInvalidated` passes: invalidating page B
  does not increment a re-fetch counter for page A's observer.
- `N_journalPages_visible_backgroundIndexing_should_not_fan_out` passes: N=5 observed pages,
  background indexing writes 100 blocks for page 1 only → `getBlocksForPage` invocation count
  for pages 2–5 = 0.
- `blockObserveDebounceMs_removal_should_not_affect_existing_behavior` passes: after removing the
  parameter, user edits still land in `_blocks` correctly.

---

## 2. Design Decisions and Risks

### 2a. How to propagate page UUIDs from actor to BlockStateManager

**Decision: expose a `SharedFlow<Set<PageUuid>>` as a property on `DatabaseWriteActor`.**

A property flow is simpler than a callback (`onBlocksWritten: (suspend (Set<PageUuid>) -> Unit)?`)
because:
- `BlockStateManager` can subscribe to it in `init` or `observePage` without needing the actor
  to hold a reference back to `BlockStateManager`.
- A `SharedFlow` with `replay=0` and `extraBufferCapacity=64, onBufferOverflow=DROP_OLDEST`
  ensures the actor never suspends on the emit (it remains fire-and-forget from the actor's
  perspective).
- Multiple future consumers (e.g. a search index invalidation subscriber) can subscribe
  independently with no coordination.

Proposed declaration in `DatabaseWriteActor`:

```kotlin
// Internal mutable flow; BlockStateManager (or any consumer) collects it.
private val _blockInvalidations = MutableSharedFlow<Set<PageUuid>>(
    replay = 0,
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
val blockInvalidations: SharedFlow<Set<PageUuid>> = _blockInvalidations.asSharedFlow()
```

**Buffer overflow tradeoff:** `DROP_OLDEST` means that if 65+ invalidation signals pile up before
`BlockStateManager` processes them, the oldest are silently dropped. The practical risk is low
because each invalidation is a tiny `Set<PageUuid>` and the consumer loop is lightweight. The
worst case is a stale page: the user sees stale data until the next write to that page re-triggers
a refresh. A conservative mitigation is to ensure that `unobservePage`'s keepalive window (5 s)
guarantees another pull on re-observe. Additionally, the `WriteRequest.Execute` wildcard
(Section 2b) provides a backstop for unknown writes.

**Alternative considered and rejected: `Channel.CONFLATED`**. A conflated channel retains only
the most recent element, which would be unsafe: it could coalesce page A's invalidation signal
with page B's signal so that page A's observer never sees its own signal. A `SharedFlow` with
`DROP_OLDEST` preserves all recent signals unless the buffer is genuinely exhausted.

### 2b. WriteRequest.Execute: broad invalidation sentinel

`WriteRequest.Execute` wraps an arbitrary `suspend () -> Either<DomainError, Unit>` lambda. The
actor cannot inspect it to extract page UUIDs.

**Decision: emit `WILDCARD_INVALIDATION = setOf(WILDCARD_PAGE_UUID)` where
`WILDCARD_PAGE_UUID = PageUuid("*")`.**

`BlockStateManager.invalidatePages` checks: if `WILDCARD_PAGE_UUID in pageUuids`, re-fetch all
pages in `observationJobs.keys`.

This is conservative (every active observer re-fetches on any `Execute` write) but preserves
correctness. The background indexing hot path uses `SaveBlocks`/`SaveBlocksDiff` (not `Execute`),
so the common case is unaffected. Operations that currently use `Execute` (single-block edits,
structural ops) are user-initiated, so one broad invalidation per user action is acceptable.

Operations using `Execute` today (`deleteBlock`, `splitBlock`, `mergeBlocks`, `updateBlockContentOnly`,
`moveBlock`, `indentBlock`, `outdentBlock`, `deleteBlockStructural`): these all affect at most one
page. A future improvement (Phase 4) could extract the page UUID from the block before the write
and pass it as an argument to `execute(priority, pageUuid?, op)`, eliminating the wildcard for
the common case. This is deferred — the wildcard is safe now.

### 2c. getBlocksForPage: stay as Flow in the interface

**Decision: keep `getBlocksForPage` as `Flow<Either<DomainError, List<Block>>>` in
`BlockReadRepository`.**

Rationale:
- Several callers (e.g. `processDeleteBlocksForPage`, `logDeletesForChunk`, `refreshBlocksForPage`,
  `deleteSelectedBlocks`, `moveSelectedBlocks`, `mergeBlock`, `handleBackspace`, `takePageSnapshot`,
  `loadPageContent`, `savePageNow`) call `.first()` on the flow for one-shot reads. These callers
  are unaffected.
- The `observePage` standing subscription is replaced by pull-on-signal (see Section 4).
  Internally `observePage` still calls `.first()` to get the current state on demand.
- Changing the return type to `suspend fun` would break 15+ call sites and require a new method
  name to avoid clashing. Not worth it for this change.

**There is no need to add a new `suspend fun getBlocksForPageOnce` method**: `.first()` on the
existing `Flow` already provides that semantics.

### 2d. Backward compatibility: tests with blockObserveDebounceMs

Tests that construct `BlockStateManager` directly (in `BlockStateManagerTest.kt`,
`JournalsViewModelTest.kt`, `JournalsViewModelEditorTest.kt`) currently pass the implicit default
`blockObserveDebounceMs = 0L`. Once `blockObserveDebounceMs` is removed:
- Tests that inject an `InMemoryBlockRepository` (no SQLDelight table-level subscription):
  these will work fine because `InMemoryBlockRepository` already emits on every write through a
  `MutableSharedFlow`, not a SQLDelight watcher.
- Tests that inject a real `SqlDelightBlockRepository` with the new invalidation model: they will
  need to wire `actor.blockInvalidations` into the `BlockStateManager`. This is handled by
  updating the test helpers in Section 5.

The `blockObserveDebounceMs` parameter should be **deprecated first** (mark it with
`@Deprecated("Use invalidation-based model; parameter has no effect")`) and removed in Phase 3.

### 2e. Risk: dropped invalidation signals

If the `blockInvalidations` buffer fills (> 64 pending signals) and a write completes, the
`DROP_OLDEST` policy silently drops the oldest signal. The affected page's observer will not
re-fetch until the next write to that page.

**Mitigations:**
1. Buffer of 64 is generous: in practice < 5 concurrent invalidations are expected even during
   heavy indexing, since the actor serializes writes.
2. `unobservePage`'s 5-second keepalive means re-observing the page always triggers a fresh pull.
3. Phase 2 changes `observePage`'s initial pull to always happen on first attach, regardless of
   cache. If a signal was dropped, the user navigating away and back will recover.
4. As a future improvement, record a histogram counter when `DROP_OLDEST` fires (requires
   wrapping `MutableSharedFlow` in a custom class or using a `Channel` variant with a
   `onUndeliveredElement` callback). Not required for Phase 1.

### 2f. Can the debounce be fully removed?

Yes. The debounce was added because the standing `asFlow()` subscription fires on every write to
the `blocks` table. Once `observePage` uses pull-on-signal instead:
- The actor emits one invalidation signal per write batch.
- `invalidatePages` launches one coroutine per affected page, calling `.first()` synchronously
  (on `PlatformDispatcher.DB`).
- There is no reactive SQLDelight subscription left to debounce.

The 100 ms debounce should be **fully removed** once Phase 2 is complete. It provides no benefit
in the pull-on-signal model and only delays UI updates.

---

## 3. Migration/Rollout Strategy

### Phase 1 — Add `blockInvalidations` to actor, wire in production (no behavior change yet)

Add `_blockInvalidations` + `blockInvalidations` to `DatabaseWriteActor`. Emit the correct
`Set<PageUuid>` in each `processRequest` arm. Wire `actor.blockInvalidations` in `App.kt`'s
`BlockStateManager` construction as a constructor parameter, but do NOT change `observePage` yet.
Ship this. CI green. No user-visible change.

### Phase 2 — Change observePage to pull-on-invalidation

Replace the standing `asFlow()` subscription in `observePage` with:
1. An initial pull (`getBlocksForPage(...).first()`).
2. A long-lived `collect` on `actor.blockInvalidations`.

Remove `blockObserveDebounceMs` usage from the `observePage` body (keep the parameter as
`@Deprecated` for one release).

Update `App.kt`: pass `writeActor` as `invalidationSource` to `BlockStateManager`.
Update tests: wire the `blockInvalidations` flow in test scenarios that need reactive updates.

### Phase 3 — Remove blockObserveDebounceMs

Remove the `blockObserveDebounceMs` constructor parameter entirely. Update all call sites
(App.kt: `blockObserveDebounceMs = 100L` removed; tests: no references since the default was 0).

---

## 4. Test Plan

All new tests live in
`kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockInvalidationTest.kt`
(new file, `commonTest` source set — no real DB needed, uses `InMemoryBlockRepository` and
`UnconfinedTestDispatcher`).

### Unit tests

#### `observePage_should_refetch_when_itsOwnPageIsInvalidated`
- Setup: one observed page A.
- Action: emit `setOf(pageUuidA)` on `blockInvalidations`.
- Assert: `blockRepository.getBlocksForPage` called once for A after the signal.

#### `observePage_should_notRefetch_when_differentPageIsInvalidated`
- Setup: observe page A.
- Action: emit `setOf(pageUuidB)`.
- Assert: `blockRepository.getBlocksForPage` NOT called for A (counter stays at 0 after initial
  pull).

#### `observePage_wildcard_should_refetch_all_active_pages`
- Setup: observe pages A and B.
- Action: emit `setOf(WILDCARD_PAGE_UUID)`.
- Assert: `getBlocksForPage` called for both A and B.

#### `observePage_should_not_refetch_after_unobservePage_keepalive_expires`
- Setup: observe A, then unobserve A, advance clock past 5 s.
- Action: emit `setOf(pageUuidA)`.
- Assert: `getBlocksForPage` NOT called (observation job cancelled).

#### `actor_saveBlocks_should_emit_correct_page_uuids`
- Setup: write blocks for pages A and B in one `SaveBlocks` batch.
- Assert: `blockInvalidations` emits exactly `setOf(pageUuidA, pageUuidB)`.

#### `actor_saveBlocksDiff_should_emit_correct_page_uuids`
- Setup: `SaveBlocksDiff` with inserts for page A, updates for page B.
- Assert: `blockInvalidations` emits `setOf(pageUuidA, pageUuidB)`.

#### `actor_deleteBlocksForPage_should_emit_single_page_uuid`
- Setup: `DeleteBlocksForPage(pageUuidA)`.
- Assert: `blockInvalidations` emits `setOf(pageUuidA)`.

#### `actor_deleteBlocksForPages_should_emit_all_page_uuids`
- Setup: `DeleteBlocksForPages(listOf(pageUuidA, pageUuidB))`.
- Assert: `blockInvalidations` emits a superset of `{pageUuidA, pageUuidB}`.

#### `actor_execute_should_emit_wildcard`
- Setup: `Execute { Unit.right() }`.
- Assert: `blockInvalidations` emits `setOf(WILDCARD_PAGE_UUID)`.

### Integration tests

#### `N_journalPages_visible_backgroundIndexing_should_not_fan_out`
Location: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/state/BlockInvalidationIntegrationTest.kt`

- Setup: 5 pages observed; use a counting `BlockRepository` wrapper that increments a counter per
  `getBlocksForPage` invocation.
- Action: emit `setOf(pageUuidA)` (simulating a background indexing write to page A).
- Assert: total `getBlocksForPage` calls = 1 (only page A), not 5.

#### `blockObserveDebounceMs_removal_should_not_regress_edit_updates`
- Setup: observe a page, update a block's content via `BlockStateManager.updateBlockContent`.
- Assert: `_blocks` state reflects the updated content without needing any debounce delay.
  (Verifies that the pull-on-signal path carries updates to the UI correctly.)

### Regression tests (to confirm existing tests remain green)

- All existing `BlockStateManagerTest` tests must pass without modification (they use
  `InMemoryBlockRepository` which pushes updates via its own `MutableSharedFlow`, not SQLDelight
  table watchers — the pull-on-signal path is compatible).
- `UpgradeResilienceTest` TC-UPGRADE-001 (covers closed-DB guard) — no change, `.first()` on
  the existing `Flow` still propagates through `catchDbError()`.

---

## 5. File-by-File Change List

### 5a. `DatabaseWriteActor.kt`
`/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`

**Add imports (at top of file):**
```kotlin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
```

**After line 69 (`@Volatile var onWriteSuccess`)**, add:
```kotlin
// Fine-grained block invalidation signal. Emits the set of PageUuids whose blocks were
// just written. Subscribers (BlockStateManager) re-fetch only the affected pages.
// WILDCARD_PAGE_UUID ("*") means "all active pages" — emitted by WriteRequest.Execute
// which wraps an arbitrary lambda and cannot declare which pages it affects.
private val _blockInvalidations = MutableSharedFlow<Set<PageUuid>>(
    replay = 0,
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
val blockInvalidations: SharedFlow<Set<PageUuid>> = _blockInvalidations.asSharedFlow()

companion object {
    val WILDCARD_PAGE_UUID = PageUuid("*")
}
```

Note: the existing `companion object` at line 665 already contains `PAGE_DELETE_CHUNK`. Add
`WILDCARD_PAGE_UUID` to it instead of creating a new one.

**In `processRequest` (line 216), add emit after each `onWriteSuccess` call:**

`SavePage` / `SavePages` (lines 219–226): pages table only — **no emit** to `_blockInvalidations`.

`DeleteBlocksForPage` (line 248, after `onWriteSuccess?.invoke(request)`):
```kotlin
_blockInvalidations.tryEmit(setOf(request.pageUuid))
```

`flushBatch` (around line 367–396, after each `onWriteSuccess?.invoke(...)` for SaveBlocks):

In the single-item path (line 367–370):
```kotlin
val affectedPages = batch[0].blocks.mapTo(mutableSetOf()) { it.pageUuid }
_blockInvalidations.tryEmit(affectedPages)
```

In the combined-transaction path (line 376–381, batch success):
```kotlin
val affectedPages = allBlocks.mapTo(mutableSetOf()) { it.pageUuid }
_blockInvalidations.tryEmit(affectedPages)
```

In the retry-individual path (line 390–397, per-request success):
```kotlin
val affectedPages = req.blocks.mapTo(mutableSetOf()) { it.pageUuid }
_blockInvalidations.tryEmit(affectedPages)
```

`flushDiffBatch` (around line 425–451):

Combined success (line 430–434):
```kotlin
val affectedPages = (allInserts + allUpdates).mapTo(mutableSetOf()) { it.pageUuid }
_blockInvalidations.tryEmit(affectedPages)
```

Retry-individual path (line 441–447):
```kotlin
val affectedPages = (req.toInsert + req.toUpdate).mapTo(mutableSetOf()) { it.pageUuid }
_blockInvalidations.tryEmit(affectedPages)
```

`processDeleteBlocksForPages` (line 277–296):

After the op-logger chunk path's final `onWriteSuccess?.invoke(request)` (line 290):
```kotlin
_blockInvalidations.tryEmit(request.pageUuids.toSet())
```

After the non-op-logger path's `onWriteSuccess?.invoke(request)` (line 294):
```kotlin
_blockInvalidations.tryEmit(request.pageUuids.toSet())
```

`processExecute` (lines 252–259): after `request.deferred.complete(request.op())`, add:
```kotlin
// Execute wraps an arbitrary lambda — emit wildcard so all active observers refresh.
_blockInvalidations.tryEmit(setOf(WILDCARD_PAGE_UUID))
```

Note: emit unconditionally (not just on success) because the lambda may have partially committed
before failing, and a false negative (no refresh when one was needed) is worse than a spurious
refresh.

### 5b. `BlockStateManager.kt`
`/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`

**Constructor change (line 57–73):**
- Add `private val invalidationSource: SharedFlow<Set<PageUuid>>? = null` parameter.
- Deprecate `private val blockObserveDebounceMs: Long = 0L` with
  `@Deprecated("Invalidation-based model; parameter has no effect after Phase 2")`.

**Add imports:**
```kotlin
import kotlinx.coroutines.flow.SharedFlow
import dev.stapler.stelekit.db.DatabaseWriteActor
```

**Add `invalidatePages` method (new public method):**
```kotlin
/**
 * Re-fetches blocks for any page in [pageUuids] that is currently observed.
 * If [pageUuids] contains [DatabaseWriteActor.WILDCARD_PAGE_UUID], re-fetches all
 * active observers (conservative fallback for WriteRequest.Execute writes).
 */
fun invalidatePages(pageUuids: Set<PageUuid>) {
    val wildcard = DatabaseWriteActor.WILDCARD_PAGE_UUID in pageUuids
    val targets = if (wildcard) {
        _activePageUuids.value.map { PageUuid(it) }.toSet()
    } else {
        pageUuids.filter { it.value in _activePageUuids.value }.toSet()
    }
    for (pageUuid in targets) {
        scope.launch { pullBlocksForPage(pageUuid) }
    }
}

/** One-shot pull of blocks for [pageUuid], applies dirty-set merge into [_blocks]. */
private suspend fun pullBlocksForPage(pageUuid: PageUuid) {
    val result = blockRepository.getBlocksForPage(pageUuid).first()
    val incomingBlocks = result.getOrNull() ?: emptyList()
    val pageUuidStr = pageUuid.value
    _blocks.update { current ->
        val localBlocks = current[pageUuidStr] ?: emptyList()
        val merged = mergeBlocks(localBlocks, incomingBlocks)
        current + (pageUuidStr to merged)
    }
}
```

**Replace `observePage` (lines 356–387):**

Replace the standing `pageFlow.collect` block with:

```kotlin
fun observePage(pageUuid: PageUuid, isContentLoaded: Boolean = true) {
    val pageUuidStr = pageUuid.value
    _activePageUuids.update { it + pageUuidStr }
    pendingUnobserve.remove(pageUuidStr)?.cancel()
    if (pageUuidStr in observationJobs) return

    observationJobs[pageUuidStr] = scope.launch {
        val alreadyCached = _blocks.value[pageUuidStr]?.isNotEmpty() == true
        if (!isContentLoaded && !alreadyCached) {
            _loadingPageUuids.update { it + pageUuidStr }
            try {
                graphLoader.loadFullPage(pageUuidStr)
            } finally {
                _loadingPageUuids.update { it - pageUuidStr }
            }
        }

        // Initial pull — populates _blocks before the invalidation subscription is active.
        pullBlocksForPage(pageUuid)

        // Subscribe to fine-grained invalidation signals from the write actor.
        // When this page's UUID appears in the signal, re-fetch from DB.
        invalidationSource?.collect { pageUuids ->
            val isWildcard = DatabaseWriteActor.WILDCARD_PAGE_UUID in pageUuids
            if (isWildcard || pageUuid in pageUuids) {
                pullBlocksForPage(pageUuid)
            }
        }
        // If no invalidationSource is wired (in-memory/test path), the job ends here.
        // InMemoryBlockRepository pushes updates via its own MutableSharedFlow, so the
        // initial pull + optimistic updates in _blocks are sufficient for tests.
    }
}
```

Note: for tests that use `InMemoryBlockRepository` and expect reactive updates, the repository's
internal `MutableSharedFlow` is not connected here. Those tests rely on the optimistic update path
in `_blocks` (direct `_blocks.update` calls in write methods) rather than the reactive observation.
This is already the case today — tests do not call `observePage` and then rely on reactive emission.
Verify in `BlockStateManagerTest` that all test scenarios are still green.

**Remove or deprecate `blockObserveDebounceMs` usage** from the `observePage` body (the debounce
call is replaced by the pull-on-signal pattern above).

### 5c. `RepositoryFactory.kt`
`/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt`

**`wireCacheCallbacks` (lines 373–393):** no change required. The existing `onWriteSuccess`
callback handles LRU cache eviction. The new `_blockInvalidations` flow is independent.

No other changes to `RepositoryFactory.kt` are needed: `RepositorySet` already exposes
`writeActor`, and `App.kt` is the wiring site.

### 5d. `App.kt`
`/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

**`BlockStateManager` construction (lines 583–593):**

Add `invalidationSource` parameter:
```kotlin
val blockStateManager = remember {
    dev.stapler.stelekit.ui.state.BlockStateManager(
        blockRepository = repos.blockRepository,
        graphLoader = graphLoader,
        graphWriter = graphWriter,
        pageRepository = repos.pageRepository,
        graphPathProvider = { viewModelRef?.uiState?.value?.currentGraphPath ?: "" },
        histogramWriter = repos.histogramWriter,
        invalidationSource = repos.writeActor?.blockInvalidations,
        // blockObserveDebounceMs removed — replaced by invalidation-based model
    )
}
```

Remove `blockObserveDebounceMs = 100L`.

### 5e. New test file: `BlockInvalidationTest.kt`
`/home/tstapler/Programming/stelekit/kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockInvalidationTest.kt`

New file in `commonTest`. Contains all unit tests from Section 4.

```kotlin
package dev.stapler.stelekit.ui.state

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.model.PageUuid
// ... InMemoryBlockRepository, fake GraphLoader, UnconfinedTestDispatcher imports

class BlockInvalidationTest {
    // Tests listed in Section 4 (unit tests only)
    // Integration tests with counting repository wrapper
}
```

### 5f. New test file: `BlockInvalidationIntegrationTest.kt`
`/home/tstapler/Programming/stelekit/kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/state/BlockInvalidationIntegrationTest.kt`

New file in `businessTest`. Contains integration tests from Section 4. Uses the existing
`SqlDelightBlockRepository` wired with an in-memory SQLite driver (same pattern as other
`businessTest` files).

---

## 6. Hard Question Answers

### What happens to WriteRequest.Execute requests that modify blocks but don't declare which pages?
Emit `setOf(WILDCARD_PAGE_UUID)` unconditionally from `processExecute`. `BlockStateManager`
interprets the wildcard as "re-fetch all active observers." This is safe: the cost is one
`getBlocksForPage` per active observer per user-initiated structural operation, which is identical
to the current behavior (every `Execute` write already wakes all SQLDelight table watchers). Net
change: zero regression on this path.

### What if the SharedFlow buffer is full when a write completes?
`DROP_OLDEST` policy silently drops the oldest pending signal. The affected page observer will
see stale data until the next write targeting that page (or until unobserve/re-observe). The
buffer is sized to 64 — in practice the actor serializes writes so the maximum burst rate is
bounded by the single-writer throughput. A sustained burst of 64+ distinct writes before
`BlockStateManager` can process them is extremely unlikely in production. If it occurs, the user
experiences a brief stale-data window, not a crash or data loss.

### Should getBlocksForPage remain a Flow or become a one-shot suspend fun?
Remain a `Flow`. The interface already supports `.first()` semantics for one-shot reads at every
call site. Changing the return type would break 15+ call sites and the `BlockReadRepository`
interface — a large, disproportionate refactor for this feature. The pull-on-signal pattern in
`observePage` already uses `.first()` internally.

### Can the debounce be fully removed or only reduced?
Fully removed. The debounce existed only to collapse the N table-level wakeups per write burst.
Once `observePage` uses pull-on-signal, there is no standing reactive subscription to debounce.
The new path fires at most once per actor write (one signal, one re-fetch per affected page).
There is no burst collapse needed.

---

## 7. Wiring Location Confirmation

The `DatabaseWriteActor` and `BlockStateManager` are **not** wired together in `GraphManager`.
`GraphManager` creates `RepositorySet` (which contains `writeActor`) but does not instantiate
`BlockStateManager`.

`BlockStateManager` is constructed in **`App.kt` at line 583** inside a `remember { }` block,
inside the `GraphContent` composable. The `repos.writeActor` reference is available at that point
(it comes from `_activeRepositorySet`). The `blockInvalidations` flow is exposed as a property on
the actor and passed as the `invalidationSource` constructor parameter.

---

## 8. Out of Scope

- Extracting the page UUID from `WriteRequest.Execute` calls (deferred as Phase 4 / future work).
- Adding a buffer-overflow histogram metric (deferred).
- Changing `BlockReadRepository.getBlocksForPage` to a `suspend fun` (rejected — breaking change).
- Changing the `InMemoryBlockRepository` to emit on the `blockInvalidations` channel (not needed;
  tests rely on optimistic `_blocks.update` paths).
