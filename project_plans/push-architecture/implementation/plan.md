# Implementation Plan: Push-Based Block State Architecture

## Summary

Replace SteleKit's table-level SQLDelight reactive subscriptions in `BlockStateManager` with a page-UUID-scoped invalidation signal emitted by `DatabaseWriteActor`, then extend that signal to carry the full block list for hot-path edits. This eliminates the N-fan-out problem where a single block write during background indexing triggers N re-queries across all visible journal pages, and removes the `blockObserveDebounceMs` workaround.

---

## Epic 1: Foundation — `BlockUpdateEvent` sealed class and `blockInvalidations` SharedFlow

No behavior change. The signal is wired but no subscriber yet. Entire phase is additive and safe to ship independently.

### Story 1.1: Define `BlockUpdateEvent` sealed class

**Goal**: Establish the typed event hierarchy in `commonMain` that carries both Phase 1 and Phase 2 payloads, including the `WriteSource` discriminator.

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/BlockUpdateEvent.kt` (new file)

**Tasks**:
- [ ] Create `BlockUpdateEvent.kt` in the `db` package with the following structure:

```kotlin
package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.PageUuid

/**
 * Event emitted by [DatabaseWriteActor] after a successful block write.
 * Phase 1 uses [PagesInvalidated]; Phase 2 extends with [BlocksWritten].
 */
sealed class BlockUpdateEvent {

    /**
     * One or more pages were written. Subscribers should re-query their page if its UUID
     * appears here, or if the set contains [WILDCARD_PAGE_UUID].
     */
    data class PagesInvalidated(val pageUuids: Set<PageUuid>) : BlockUpdateEvent()

    /**
     * An in-app write already holds the full block list for a page — no DB re-query needed.
     * [source] discriminates between user edits, bulk imports, and external file changes,
     * following Logseq's outlinerOp metadata precedent.
     */
    data class BlocksWritten(
        val pageUuid: PageUuid,
        val blocks: List<Block>,
        val source: WriteSource = WriteSource.UserEdit,
    ) : BlockUpdateEvent()
}

/** Discriminates the origin of a block write for smarter UI decisions. */
sealed class WriteSource {
    /** User typed, split, merged, or deleted a block interactively. */
    object UserEdit : WriteSource()
    /** Background indexing or graph import (GraphLoader path). */
    object BulkImport : WriteSource()
    /** External file change detected on disk (watcher path). */
    object ExternalFileChange : WriteSource()
}
```

- [ ] Verify `PageUuid` and `Block` are already imported from `model/Models.kt` (no new deps needed).

**Tests required**: None — pure data model, covered transitively by Epic 2 unit tests.

**Done when**: File compiles on all KMP targets (JVM, Android, iOS); no platform annotations required.

---

### Story 1.2: Add `blockInvalidations` SharedFlow to `DatabaseWriteActor`

**Goal**: Expose a `SharedFlow<Set<PageUuid>>` on the actor and emit the correct page UUID sets from every typed write arm, plus a wildcard sentinel from `processExecute`.

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`

**Tasks**:
- [ ] Add imports at the top of `DatabaseWriteActor.kt`:
  ```kotlin
  import kotlinx.coroutines.channels.BufferOverflow
  import kotlinx.coroutines.flow.MutableSharedFlow
  import kotlinx.coroutines.flow.SharedFlow
  import kotlinx.coroutines.flow.asSharedFlow
  ```
- [ ] Add `WILDCARD_PAGE_UUID` to the existing `companion object` (alongside `PAGE_DELETE_CHUNK`):
  ```kotlin
  val WILDCARD_PAGE_UUID = PageUuid("*")
  ```
- [ ] Declare the private mutable flow and public read-only view immediately after the existing `@Volatile var onWriteSuccess` field:
  ```kotlin
  private val _blockInvalidations = MutableSharedFlow<Set<PageUuid>>(
      replay = 0,
      extraBufferCapacity = 64,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  val blockInvalidations: SharedFlow<Set<PageUuid>> = _blockInvalidations.asSharedFlow()
  ```
- [ ] In `processRequest`, after each `onWriteSuccess?.invoke(request)` call, add `tryEmit`:
  - `DeleteBlocksForPage`: `_blockInvalidations.tryEmit(setOf(request.pageUuid))`
  - `processDeleteBlocksForPages` (both the op-logger and non-op-logger branches): `_blockInvalidations.tryEmit(request.pageUuids.toSet())`
- [ ] In `processExecute`, **BEFORE** `request.deferred.complete(request.op())`, add:
  ```kotlin
  _blockInvalidations.tryEmit(setOf(WILDCARD_PAGE_UUID))
  ```
  Rationale: `processExecute` has NO `onWriteSuccess` call and no success/failure inspection — the emit must be unconditional and must precede `deferred.complete` so subscribers receive the signal before the caller's `await()` returns. Do NOT place this after `deferred.complete` and do NOT follow the "after onWriteSuccess" pattern used in other arms — `processExecute` has no such call.
- [ ] In `flushBatch` (SaveBlocks coalescing path):
  - Single-item path: `_blockInvalidations.tryEmit(batch[0].blocks.mapTo(mutableSetOf()) { it.pageUuid })`
  - Combined-transaction success: `_blockInvalidations.tryEmit(allBlocks.mapTo(mutableSetOf()) { it.pageUuid })`
  - Retry-individual success (per-request loop): `_blockInvalidations.tryEmit(req.blocks.mapTo(mutableSetOf()) { it.pageUuid })`
- [ ] In `flushDiffBatch` (SaveBlocksDiff coalescing path):
  - Combined success: `_blockInvalidations.tryEmit((allInserts + allUpdates).mapTo(mutableSetOf()) { it.pageUuid })`
  - Retry-individual success: `_blockInvalidations.tryEmit((req.toInsert + req.toUpdate).mapTo(mutableSetOf()) { it.pageUuid })`
- [ ] `SavePage` / `SavePages` arms: **no emit** — page-table writes do not affect block subscribers.

**Tests required**:
- `actor_saveBlocks_should_emit_correct_page_uuids` — `SaveBlocks` for pages A and B emits `setOf(A, B)`.
- `actor_saveBlocksDiff_should_emit_correct_page_uuids` — inserts for A, updates for B → `setOf(A, B)`.
- `actor_deleteBlocksForPage_should_emit_single_page_uuid` — `setOf(pageUuidA)`.
- `actor_deleteBlocksForPages_should_emit_all_page_uuids` — superset of `{A, B}`.
- `actor_execute_should_emit_wildcard` — `setOf(WILDCARD_PAGE_UUID)`.
- `actor_savePage_should_not_emit_block_invalidation` — `SavePage` produces zero emissions.

**Done when**: All actor emission unit tests pass; CI green; no `BlockStateManager` change yet; debounce workaround still present.

---

## Epic 2: Phase 1 — Invalidation-Scoped Observation

Replace the standing reactive `asFlow()` subscription in `observePage` with an invalidation-driven one-shot pull. Remove `blockObserveDebounceMs`. Promote the five hot-path `Execute` wrappers to typed `WriteRequest` subclasses so the wildcard sentinel is eliminated for user-edit writes.

### Story 2.1: Promote hot-path Execute wrappers to typed WriteRequest subclasses

**Goal**: Eliminate wildcard sentinel emissions from the five high-frequency `Execute` paths — `saveBlock`, `updateBlockContentOnly`, `updateBlockPropertiesOnly`, `deleteBlock`, `splitBlock`, `mergeBlocks`, `deleteBlockStructural` — by giving each a typed `WriteRequest` that carries the relevant `pageUuid`.

**Background**: The pitfalls research (pitfall #5) confirms that with N journal pages, wildcard emission on every keystroke would fire N one-shot queries per character typed — equal to or worse than the pre-invalidation model. Promoting these paths is required before Phase 1 can ship a performance win.

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/WriteRequest.kt` (if it is a separate file; verify location)

**Tasks**:
- [ ] Audit `WriteRequest.kt` for its current sealed class hierarchy. Confirm the existing arms: `SavePage`, `SavePages`, `SaveBlocks`, `SaveBlocksDiff`, `DeleteBlocksForPage`, `DeleteBlocksForPages`, `Execute`.
- [ ] **Audit all `blockRepository.getBlocksForPage().first()` call sites in `BlockStateManager` outside `observePage`**. Current list identified by adversarial review: `refreshBlocksForPage` (line ~803), `deleteSelectedBlocks` (line ~252), `moveSelectedBlocks` (line ~314), `restorePageToSnapshot` (line ~768), `mergeBlock` (line ~1084), `handleBackspace` (line ~1118). For each: document whether a wildcard `PagesInvalidated` from `processExecute` will also fire for the same page during the same operation, creating a double-pull. Decision: accept the double-pull for structural ops (low frequency) and annotate with a comment, OR promote structural ops to typed `WriteRequest` subclasses with explicit `pageUuid` to suppress the wildcard. Document the chosen approach before Story 2.2 lands.
- [ ] Add new typed subclasses to `WriteRequest`:
  ```kotlin
  data class WriteBlockContent(
      val blockUuid: BlockUuid,
      val pageUuid: PageUuid,
      val content: String,
      val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
  ) : WriteRequest()

  data class WriteBlock(
      val block: Block,
      val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
  ) : WriteRequest()

  data class DeleteBlock(
      val blockUuid: BlockUuid,
      val pageUuid: PageUuid,
      val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
  ) : WriteRequest()

  data class SplitBlock(
      val blockUuid: BlockUuid,
      val pageUuid: PageUuid,
      val cursorPosition: Int,
      val newBlockUuid: BlockUuid,
      val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
  ) : WriteRequest()

  data class MergeBlocks(
      val blockUuid: BlockUuid,
      val pageUuid: PageUuid,
      val nextBlockUuid: BlockUuid,
      val separator: String,
      val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
  ) : WriteRequest()

  data class DeleteBlockStructural(
      val blockUuid: BlockUuid,
      val pageUuid: PageUuid,
      val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
  ) : WriteRequest()

  data class WriteBlockProperties(
      val blockUuid: BlockUuid,
      val pageUuid: PageUuid,
      val properties: Map<String, String>,
      val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
  ) : WriteRequest()
  ```
- [ ] Add processing arms in `processRequest` for each new subclass. Each arm calls the existing repository method (same logic as the current `execute` lambda) and then emits `setOf(request.pageUuid)` to `_blockInvalidations`.
- [ ] Update the `DatabaseWriteActor` public convenience methods (`saveBlock`, `updateBlockContentOnly`, `updateBlockPropertiesOnly`, `deleteBlock`, `splitBlock`, `mergeBlocks`, `deleteBlockStructural`) to enqueue typed requests instead of `Execute` lambdas.
- [ ] Confirm `onWriteSuccess` is still called (chained alongside emit) for cache eviction compatibility in `RepositoryFactory.wireCacheCallbacks` — that callback only matches on typed arms it knows about; unrecognized arms fall to the `else -> Unit` branch, which is safe.

**Flagged decision**: The `pageUuid` for `updateBlockContentOnly`, `deleteBlock`, `splitBlock`, `mergeBlocks`, and `deleteBlockStructural` is not currently available to the actor's public API — only the `blockUuid` is passed. Two options:
  - **Option A** (preferred): require callers (`BlockStateManager`) to pass both `blockUuid` and `pageUuid`. `BlockStateManager` already knows the current page UUID from context.
  - **Option B**: actor resolves `blockUuid → pageUuid` via a DB lookup before enqueuing. Adds one DB read per write.
  Option A is zero-cost and correct; `BlockStateManager` always has the page UUID in scope for these operations.

**Tests required**:
- `writeBlockContent_should_emit_pageUuid` — typed request emits `setOf(pageUuid)`, not wildcard.
- `deleteBlock_typed_should_emit_pageUuid`.
- `splitBlock_typed_should_emit_pageUuid`.
- `mergeBlocks_typed_should_emit_pageUuid`.
- Existing `BlockStateManager` structural-op tests must remain green (the public API surface does not change).

**Done when**: `updateBlockContentOnly`, `saveBlock`, `splitBlock`, `mergeBlocks`, `deleteBlock`, `deleteBlockStructural`, `updateBlockPropertiesOnly` each enqueue typed requests; `processExecute` wildcard is still emitted only for genuinely opaque lambdas (structural ops, search rebuilds, git config writes).

---

### Story 2.2: Add `invalidationSource` parameter to `BlockStateManager` and rewrite `observePage`

**Goal**: Replace the standing `blockRepository.getBlocksForPage(pageUuid).collect { }` subscription with an initial one-shot pull plus an invalidation-driven refresh loop. Remove `blockObserveDebounceMs` from the constructor.

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`

**Tasks**:
- [ ] Add constructor parameter `private val invalidationSource: SharedFlow<Set<PageUuid>>? = null`.
- [ ] Mark `blockObserveDebounceMs` with `@Deprecated("Replaced by invalidation-based model; no effect after Phase 1", ReplaceWith(""))`. Do not remove yet — preserves binary compatibility for the Phase 3 cleanup.
- [ ] Add private `suspend fun pullBlocksForPage(pageUuid: PageUuid)`:
  ```kotlin
  private suspend fun pullBlocksForPage(pageUuid: PageUuid) {
      val result = blockRepository.getBlocksForPage(pageUuid).first()
      val incomingBlocks = result.getOrNull() ?: emptyList()
      val pageUuidStr = pageUuid.value
      _blocks.update { current ->
          val localBlocks = current[pageUuidStr] ?: emptyList()
          current + (pageUuidStr to mergeBlocks(localBlocks, incomingBlocks))
      }
  }
  ```
  This must call the existing `mergeBlocks` function verbatim — the dirty-set merge of local edits with incoming DB versions is the correctness heart of block observation. Do not inline or simplify.
- [ ] Replace the `observePage` subscription body. The new body:
  1. Lazy disk load preamble — unchanged from current code.
  2. Call `pullBlocksForPage(pageUuid)` unconditionally (initial pull, runs before subscription starts to avoid the startup race described in pitfalls research section 1).
  3. Subscribe to `invalidationSource` with `collectLatest`:
     ```kotlin
     invalidationSource?.collectLatest { pageUuids ->
         val isWildcard = DatabaseWriteActor.WILDCARD_PAGE_UUID in pageUuids
         if (isWildcard || pageUuid in pageUuids) {
             pullBlocksForPage(pageUuid)
         }
     }
     ```
     Use `collectLatest` (not `collect`) so that rapid invalidations for the same page cancel any in-flight one-shot re-query, applying only the last signal. This is the zero-allocation debounce described in stack research section 5.
  4. If `invalidationSource` is null (test path with `InMemoryBlockRepository`), the coroutine ends after the initial pull. Tests rely on the optimistic `_blocks.update` paths for subsequent updates — this is already the case today.
- [ ] Remove the `debounce(blockObserveDebounceMs)` call from `observePage` body.

**Tests required**:
- `observePage_should_refetch_when_itsOwnPageIsInvalidated` — emit `setOf(pageUuidA)`; assert `getBlocksForPage` called once for A after signal.
- `observePage_should_notRefetch_when_differentPageIsInvalidated` — emit `setOf(pageUuidB)`; assert zero calls for A after initial pull.
- `observePage_wildcard_should_refetch_all_active_pages` — emit `setOf(WILDCARD_PAGE_UUID)` with A and B observed; assert both re-queried.
- `observePage_should_not_refetch_after_unobservePage_keepalive_expires` — unobserve A, advance clock past 5 s, emit invalidation for A; assert zero calls.
- `observePage_collectLatest_should_cancel_inflight_query_on_rapid_invalidation` — emit three signals for page A in rapid succession; assert at most one completed query.
- `observePage_initialPull_should_not_require_invalidation_signal` — observe page with no signal emitted; assert `_blocks` populated from initial pull.
- `observePage_mergeBlocks_should_preserve_dirty_local_edits_on_invalidation` — set a locally-dirty block in `_blocks`, then emit invalidation; assert dirty content is not overwritten by incoming DB version.

**Done when**: `observePage` no longer holds a standing `asFlow()` SQLDelight subscription; `blockObserveDebounceMs` is deprecated but present; all tests above pass.

---

### Story 2.3: Wire `invalidationSource` in `App.kt` and remove `blockObserveDebounceMs` usage

**Goal**: Pass `repos.writeActor?.blockInvalidations` to `BlockStateManager` at the Compose construction site and remove the 100 ms debounce argument.

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

**Tasks**:
- [ ] Locate the `BlockStateManager` construction inside `remember { }` (currently line ~583). Add **TWO** parameters:
  ```kotlin
  writeActor = repos.writeActor,
  invalidationSource = repos.writeActor?.blockInvalidations,
  ```
  **Important**: adding `writeActor` is a behavior change — it routes all BSM user-edit writes through the actor queue instead of calling `blockRepository` directly. Verify that the integration test suite covers the BSM+actor combination before merging.
- [ ] Remove `blockObserveDebounceMs = 100L` argument (parameter is now `@Deprecated` with no effect — will be deleted in Story 2.5).
- [ ] Verify the construction site is inside `key(activeGraphId) { }` — this is required for AC-7 (graph switch safety). The old BSM's scope is cancelled by `close()` before the new one subscribes to the new actor's flow. Confirm via code inspection; add a comment if the `key {}` relationship is not obvious.

**Tests required**: AC-7 covered by the graph-switch integration test in Story 4.2. No new unit tests needed here beyond visual inspection.

**Done when**: `App.kt` compiles without the debounce argument; desktop and Android smoke-test runs are clean.

---

### Story 2.4: Audit and convert false-negative `BlockStateManager` tests

**Goal**: Identify all existing `BlockStateManager` tests that assert reactive re-emission after a direct `blockRepository.saveBlock()` call (which will silently stop working after the standing subscription is removed), and convert them to use either the invalidation signal or the optimistic-update path.

**Background**: Pitfalls research section 6 confirms these are false negatives — tests that will stop asserting what they claim after Phase 1. AC-4 as written ("all existing tests pass without modification") is not fully achievable for tests that relied on reactive re-emission. The plan accepts updating these tests as required.

**Files**:
- `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerTest.kt` (or wherever BSM tests live)
- Any other test files that construct `BlockStateManager` and call `blockRepository` directly

**Tasks**:
- [ ] Grep for `blockRepository.save`, `blockRepository.insert`, `repository.save` inside BSM test files. For each hit: determine if the test then asserts `blocksForPage()` content changed.
- [ ] For each such test, choose the conversion path:
  - **Option A** (preferred): inject a `MutableSharedFlow<Set<PageUuid>>` as `invalidationSource` in the test; after the direct repository write, emit the page UUID on the flow; then assert the BSM state. This keeps the test as a unit test.
  - **Option B**: convert to an integration test in `businessTest` with a real `DatabaseWriteActor` + `SqlDelightBlockRepository` pair. Use this when the test is more about the actor-BSM wiring than the BSM merge logic.
- [ ] Document which tests were converted and why in a comment block at the top of the test file.

**Tests required**: All existing BSM tests must remain green after conversion. The count of tests must not decrease — conversion is a 1:1 replacement, not a deletion.

**Done when**: No BSM test relies on reactive SQLDelight re-emission; all tests pass; conversion decisions documented.

---

### Story 2.5: Delete `blockObserveDebounceMs` parameter (satisfies AC-2)

**Goal**: Complete the AC-2 requirement by removing the now-unused parameter from `BlockStateManager`.

**Prerequisite**: Story 2.3 merged (the only call site removed from `App.kt`).

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`

**Tasks**:
- [ ] Delete `private val blockObserveDebounceMs: Long = 0L` from the `BlockStateManager` constructor.
- [ ] Delete the `@Deprecated` annotation added in Story 2.2.
- [ ] Grep for `blockObserveDebounceMs` across all source sets to confirm zero remaining references.
- [ ] Update `requirements.md` AC-2 status to "satisfied."

**Tests required**: All existing BSM tests must still pass — this is a pure deletion with no behavior change (the parameter was already a no-op after Story 2.2).

**Done when**: `blockObserveDebounceMs` does not appear anywhere in the codebase; AC-2 is satisfied.

---

## Epic 3: Phase 2 — Full Push Payload for Hot-Path Writes

The typed `WriteRequest` subclasses introduced in Story 2.1 already carry `pageUuid`. Extend their processing arms to also compute and emit the full post-write block list, enabling `BlockStateManager` to apply it directly with zero DB re-query.

### Story 3.1: Emit `BlocksWritten` from typed hot-path write arms

**Goal**: After a successful `WriteBlockContent`, `WriteBlock`, `DeleteBlock`, `SplitBlock`, `MergeBlocks`, `DeleteBlockStructural` write, fetch the resulting block list from the DB (inside the actor, on `PlatformDispatcher.DB`) and emit `BlockUpdateEvent.BlocksWritten` on a second `SharedFlow`.

**Background**: The actor already holds `blockRepository` and can call `getBlocksForPage(pageUuid).first()` inside `withContext(PlatformDispatcher.DB)` immediately after the write commits. This is one re-query per user edit (as opposed to the current N re-queries from reactive subscriptions), and in Phase 2 it is the data source for the push payload rather than a BSM-triggered re-query.

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`

**Tasks**:
- [ ] Add a second `SharedFlow` for push payloads:
  ```kotlin
  private val _blocksPushed = MutableSharedFlow<BlockUpdateEvent.BlocksWritten>(
      replay = 0,
      extraBufferCapacity = 64,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  val blocksPushed: SharedFlow<BlockUpdateEvent.BlocksWritten> = _blocksPushed.asSharedFlow()
  ```
- [ ] In each typed hot-path arm's success branch, after committing the write, call:
  ```kotlin
  val blocks = blockReadRepository.getBlocksForPage(request.pageUuid).first().getOrNull() ?: emptyList()
  _blocksPushed.tryEmit(BlockUpdateEvent.BlocksWritten(request.pageUuid, blocks, WriteSource.UserEdit))
  // Still emit invalidation for any subscriber not yet on Phase 2 path:
  _blockInvalidations.tryEmit(setOf(request.pageUuid))
  ```
  Keep the `_blockInvalidations` emission as a fallback for subscribers not yet consuming `blocksPushed`.
- [ ] Confirm `blockReadRepository: BlockReadRepository` is accessible inside the actor. It is already present as a constructor parameter (`blockRepository: BlockReadRepository` — verify exact field name).
- [ ] Bulk import paths (`SaveBlocks`, `SaveBlocksDiff`) must NOT emit `BlocksWritten` — these can affect hundreds of blocks per page and the push payload would be disproportionately large. They continue emitting only `PagesInvalidated` (Phase 1 path). Annotate this decision with a comment in code.
- [ ] External file changes flow through `GraphLoader` → `writeActor.saveBlocks()` → `SaveBlocks` arm, so they also stay on the Phase 1 invalidation path. This satisfies the requirements.md constraint that "external file changes continue using the one-shot re-query."

**Flagged decision**: Whether to inject `blockReadRepository` directly or re-use the existing `blockRepository` field (if it already exposes `getBlocksForPage`). Verify the actor's current constructor signature to confirm which interface is available.

**Tests required**:
- `writeBlockContent_should_emit_BlocksWritten_with_correct_blocks` — after `WriteBlockContent`, `blocksPushed` emits `BlocksWritten` for the correct page with the updated block list.
- `saveBlocks_should_NOT_emit_BlocksWritten` — bulk import path emits only `PagesInvalidated`.
- `externalFileChange_via_saveBlocks_should_NOT_emit_BlocksWritten` — confirms external changes stay on Phase 1 path.

**Done when**: Actor emits `BlocksWritten` for all five hot-path typed write arms; bulk and external paths unchanged.

---

### Story 3.2: `BlockStateManager` consumes `blocksPushed` — zero re-query for in-app edits

**Goal**: Subscribe to `blocksPushed` in `BlockStateManager.observePage` and apply the pushed block list directly to `_blocks` using the existing `mergeBlocks` logic — bypassing `getBlocksForPage` entirely for in-app edits.

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

**Tasks**:
- [ ] Add constructor parameter `private val pushSource: SharedFlow<BlockUpdateEvent.BlocksWritten>? = null`.
- [ ] In `observePage`, launch a second concurrent coroutine (alongside the invalidation collector):
  ```kotlin
  // Phase 2: direct push path for in-app edits (zero DB re-query)
  pushSource?.collect { event ->
      if (event.pageUuid.value != pageUuidStr) return@collect
      _blocks.update { current ->
          val localBlocks = current[pageUuidStr] ?: emptyList()
          current + (pageUuidStr to mergeBlocks(localBlocks, event.blocks))
      }
  }
  ```
  Use `collect` (not `collectLatest`) here — the block list is O(1) to apply and cancelling mid-apply would leave `_blocks` in partial state. Rapid edits are already serialized by the actor's write queue.
- [ ] In `App.kt`, at the `BlockStateManager` construction site, add `pushSource = repos.writeActor?.blocksPushed`.
- [ ] When a `BlocksWritten` event arrives for a page, the invalidation subscriber for the same page will also fire (since `_blockInvalidations.tryEmit(setOf(pageUuid))` is still emitted alongside). Prevent a double-update by checking whether the push already applied the state: the simplest guard is that `collect` on `blocksPushed` runs before `collectLatest` on `blockInvalidations` within the same page's observation coroutine — but since they are in separate child coroutines, ordering is not guaranteed. Add a version counter or timestamp check, OR suppress the invalidation emit for typed hot-path arms when `blocksPushed` is emitted. Preferred: suppress the `_blockInvalidations` emit for arms that emit `blocksPushed` (the push is the authoritative signal; the invalidation fallback is for paths without a push payload).

**Flagged decision**: Double-update suppression strategy. Two options:
  - **Option A** (preferred): In the typed arm's success branch, emit `blocksPushed` only, not `_blockInvalidations`. The invalidation fallback is no longer needed once the push path is active for that arm.
  - **Option B**: Keep both emissions but add a `lastPushTimestamp: AtomicLong` to the observation context. The invalidation handler skips if a push already landed within N ms. This is fragile on slow devices.
  Option A is cleaner and eliminates the double-query structurally.

**Tests required** (AC-5):
- `inApp_blockEdit_should_trigger_zero_getBlocksForPage_calls` — type into a block; assert `getBlocksForPage` call count = 0 for the page (state updated from `blocksPushed` only).
- `pushSource_should_preserve_dirty_local_edits` — push arrives while user has an unsaved edit; assert dirty content is merged, not overwritten.
- `pushSource_null_should_fall_back_to_invalidation_path` — `pushSource = null` (test path); assert existing Phase 1 behavior unchanged.

**Done when**: AC-5 passes; `getBlocksForPage` call count for hot-path edits = 0.

---

### Story 3.3: Performance benchmark — journal view latency during background indexing

**Goal**: Establish a measurable before/after for AC-6 using the existing benchmark infrastructure.

**Files**:
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/` (benchmark location — verify exact path from `scripts/benchmark-local.sh`)
- `scripts/benchmark-local.sh`

**Tasks**:
- [ ] Identify the existing benchmark class that measures journal-view re-render latency or block query frequency. Check `kmp/build.gradle.kts` for the `jvmTestProfile` task and its entry point.
- [ ] Add or extend a benchmark scenario: 5 journal pages open, background indexing of a 500-page graph. Measure: (a) number of `getBlocksForPage` calls, (b) total wall-clock time for the indexing run from the UI thread's perspective.
- [ ] Run the benchmark on the `main` branch (pre-push) to record the baseline.
- [ ] Run again after Epic 2 lands (Phase 1 only) and after Epic 3 lands (Phase 2). Record all three data points.
- [ ] Commit the baseline result to `benchmarks/history/` using the existing naming convention.

**Tests required**: No pass/fail test — this is a data-collection task. The benchmark must not regress the existing benchmark suite.

**Done when**: Before/after data exists for AC-6; benchmark committed to history.

---

## Epic 4: Test Coverage — New Unit Tests and Integration Tests

### Story 4.1: New unit tests for actor emission correctness

**Goal**: Cover all actor emission arms defined in Stories 1.2 and 3.1 with targeted unit tests using `UnconfinedTestDispatcher`.

**Files**:
- `kmp/src/commonTest/kotlin/dev/stapler/stelekit/db/BlockInvalidationActorTest.kt` (new file)

**Tasks**:
- [ ] Create test file with tests listed in Story 1.2 "Tests required" section (actor emission tests).
- [ ] Add `actor_execute_should_emit_wildcard_even_on_failure` — `Execute` lambda returns `Left(error)`; assert wildcard still emitted.
- [ ] Add `actor_coalesced_saveBlocks_should_emit_union_of_page_uuids` — two `SaveBlocks` batches coalesced in `flushBatch`; assert emitted set is union of both batches' page UUIDs.
- [ ] Use `MutableSharedFlow.tryEmit` assertion via a `testScope.launch { flow.take(1).first() }` collect pattern. Use `runTest` with `UnconfinedTestDispatcher`.

**Tests required**: See task list above.

**Done when**: All actor unit tests pass; file committed to `commonTest`.

---

### Story 4.2: Integration tests — graph switch safety (AC-7)

**Goal**: Verify that a graph switch does not deliver stale block invalidations or push payloads from the old graph's actor to the new graph's `BlockStateManager`.

**Files**:
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphSwitchInvalidationTest.kt` (new file)

**Tasks**:
- [ ] Create test that:
  1. Constructs `GraphManager` with two in-memory graphs (A and B) using the existing `businessTest` driver factory pattern.
  2. Opens a page on graph A, observes it in a `BlockStateManager`.
  3. Triggers a write to graph A's actor (emits invalidation).
  4. Calls `switchGraph(B)` — this creates a new `RepositorySet` and a new actor.
  5. Emits another invalidation on the old actor (simulating in-flight write during teardown).
  6. Asserts: the new graph's `BlockStateManager` receives zero emissions from the old actor.
  7. Asserts: the old BSM's observation job is cancelled (check `observationJobs.isEmpty()` after `close()`).
- [ ] Add test for the timing window where old actor completes a write after `switchGraph` starts: old actor emits `_blockInvalidations.tryEmit(...)` — this succeeds (no subscribers) and is silently dropped. Assert no exception.

**Tests required**: See above.

**Done when**: Both graph-switch scenarios pass; AC-7 green.

---

### Story 4.3: Integration test — N-page fan-out reduction (AC-1)

**Goal**: Demonstrate that with 5 journal pages open and only page A written, `getBlocksForPage` is called exactly once (for page A) and zero times for pages B–E.

**Files**:
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/state/BlockInvalidationIntegrationTest.kt` (new file)

**Tasks**:
- [ ] Create a `CountingBlockRepository` decorator that wraps `SqlDelightBlockRepository` and increments an atomic counter per `getBlocksForPage` call per page UUID.
- [ ] Test setup: 5 pages (A–E) observed in `BlockStateManager`; initial pulls counted separately and reset; then emit `setOf(pageUuidA)` on `blockInvalidations`.
- [ ] Assert: counter for A = 1, counters for B–E = 0.
- [ ] Add variant: emit `setOf(WILDCARD_PAGE_UUID)`; assert all 5 counters = 1 (wildcard semantics correct).
- [ ] Add variant: emit `setOf(pageUuidA, pageUuidB)`; assert A = 1, B = 1, C/D/E = 0 (set membership correct).

**Tests required**: See above.

**Done when**: All three variants pass; AC-1 green.

---

### Story 4.4: External file change regression (AC-3)

**Goal**: Confirm that an external file change (disk write detected by `GraphLoader.externalFileChanges`) still reflects in the UI within 2 seconds via the Phase 1 invalidation path.

**Files**:
- Existing external file change integration tests (locate via grep for `externalFileChanges` in test dirs)

**Tasks**:
- [ ] Identify existing tests that cover `GraphLoader.externalFileChanges → BlockStateManager` update path.
- [ ] Verify they still pass after Phase 1 changes (external changes flow through `saveBlocks` → `SaveBlocks` arm → `PagesInvalidated` emission → BSM one-shot pull).
- [ ] If no test exists for the full path, add one in `businessTest`: modify a markdown file on disk; wait for the file watcher to detect it; assert `_blocks` for the page is updated within 2 s.

**Tests required**: At least one integration test covering the external file → UI update path.

**Done when**: AC-3 green; external file change path confirmed working through new invalidation model.

---

## Flagged Decisions

The following decisions were not resolved in requirements or research and require explicit choices before implementation:

1. **`pageUuid` source for hot-path typed requests (Story 2.1, Option A vs B)**: Does `BlockStateManager` pass `pageUuid` alongside `blockUuid` when calling `updateBlockContentOnly`, `deleteBlock`, `splitBlock`, `mergeBlocks`, `deleteBlockStructural`? Option A (caller passes both) is recommended — `BlockStateManager` always has the page UUID in context. Confirm that there are no call sites outside `BlockStateManager` that use these actor methods without a page UUID available.

2. **Double-update suppression for Phase 2 (Story 3.2, Option A vs B)**: When `blocksPushed` is emitted for a typed hot-path write, should `_blockInvalidations` also emit for the same page, or be suppressed? Option A (suppress invalidation when push payload is emitted) is recommended — cleaner, eliminates the duplicate query structurally. Requires confirming that no other subscriber relies on `blockInvalidations` for these exact write paths.

3. **`blockReadRepository` access inside `DatabaseWriteActor` for Phase 2 (Story 3.1)**: Verify whether `blockRepository: BlockReadRepository` is already a constructor field on `DatabaseWriteActor` (it likely is, for `processDeleteBlocksForPage`). If not, determine the injection pattern (constructor vs. setter like `onWriteSuccess`).

4. **Phase 3 scope**: Requirements.md defers bulk import push payload until Phase 2 metrics are available. This plan does not include Phase 3 stories. The decision to include or exclude Phase 3 depends on the benchmark data from Story 3.3. No action required before Phase 2 ships.

5. **`WriteRequest.Execute` wildcard for structural ops post-Phase-2**: After typed requests replace the five hot-path arms, `Execute` is only used for structural ops (indent, outdent, move) and non-block writes (search, git config). The wildcard sentinel is acceptable for these low-frequency paths. Confirm with a count of `writeActor.execute()` call sites in `BlockStateManager` (expected: ~6 structural ops + 2 non-block paths). If count is higher, evaluate additional typed request promotion in a follow-on task.

---

## Risks

### Risk 1: `mergeBlocks` dirty-set logic divergence

**Description**: `pullBlocksForPage` in `BlockStateManager` and the new `pushSource` handler in Story 3.2 must both call the existing `mergeBlocks` function with the same argument order — `(localBlocks = current[pageUuidStr], incomingBlocks = DB/pushed list)`. A copy-paste error or argument transposition would silently overwrite locally-dirty edits with stale DB content, causing data loss for text the user is currently typing.

**Mitigation**: Extract `pullBlocksForPage` as a named private suspend function (as shown in Story 2.2) and reuse it in both the invalidation handler and the push handler — never inline the merge logic twice. Add `observePage_mergeBlocks_should_preserve_dirty_local_edits_on_invalidation` and `pushSource_should_preserve_dirty_local_edits` as mandatory tests (Stories 2.2 and 3.2). These tests must set a dirty block, trigger the respective path, and assert the dirty content is preserved.

### Risk 2: Startup race — initial pull missing before invalidation subscription

**Description**: If a future refactor removes the `pullBlocksForPage(pageUuid)` call that runs before the `invalidationSource?.collectLatest { }` loop in `observePage`, any invalidation emitted before the subscription starts will be missed, and the page's `_blocks` will stay at its initial state (empty or stale) until the next write. This is the race described in pitfalls research section 1. The `replay = 0` SharedFlow configuration means missed events are permanently lost.

**Mitigation**: The initial pull is in `observePage` immediately before the subscription loop (Story 2.2). Add a comment: "DO NOT remove this pull — it is the guard against missed emissions before subscription." Add `observePage_initialPull_should_not_require_invalidation_signal` to the required test suite. The test asserts that `_blocks` is non-empty after `observePage` with no signal emitted — it will fail immediately if the initial pull is accidentally removed.

### Risk 3: Graph switch timing window delivers stale invalidations

**Description**: The pitfalls research (section 2) describes a window between `_activeRepositorySet.value = null` and the new actor's first emission where the old actor may complete in-flight writes and emit to `_blockInvalidations`. If `BlockStateManager.close()` is delayed relative to the old actor's emission, stale invalidations could reach the old BSM instance, triggering a `getBlocksForPage` call against the now-closed DB driver.

**Mitigation**: The existing `catchDbError()` guard in `SqlDelightBlockRepository.getBlocksForPage` converts closed-DB exceptions to `Either.Left`, so the query fails gracefully rather than crashing. The `key(activeGraphId)` pattern in `App.kt` disposes the old BSM synchronously with Compose recomposition, which fires `close()` and cancels its scope before the new BSM is constructed. Story 4.2 adds an explicit integration test for this window. Additionally, confirm in code review that `actor.close()` is called before or simultaneously with BSM disposal — if it is called after, add a `@Volatile isShuttingDown` flag to suppress post-close emissions.

---

## Epic/Story/Task Summary

| Epic | Stories | Approximate Tasks |
|------|---------|-------------------|
| Epic 1: Foundation | 2 | 14 |
| Epic 2: Phase 1 — Invalidation | 5 | 24 |
| Epic 3: Phase 2 — Full Push | 3 | 14 |
| Epic 4: Test Coverage | 4 | 16 |
| **Total** | **14** | **~68** |

Flagged decisions requiring user choice: **5** (listed above).

## Adversarial Review Patches Applied

The following patches were applied from the adversarial review (verdict: BLOCKED → patched):

| Issue | Patch Applied |
|-------|---------------|
| CRIT-1: `writeActor` absent from BSM App.kt construction | Story 2.3 updated to add both `writeActor` and `invalidationSource` |
| CRIT-2: AC-2 deprecate-vs-delete contradiction | Story 2.5 added to delete parameter; AC-2 satisfied in Phase 1 |
| CRIT-3: 6 uncovered `getBlocksForPage` call sites in BSM | Story 2.1 expanded with audit task; double-pull decision documented |
| CRIT-4: `processExecute` emit location wrong (no `onWriteSuccess` anchor) | Story 1.2 `processExecute` instruction corrected to BEFORE `deferred.complete` |
