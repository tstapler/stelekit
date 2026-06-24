# Adversarial Review: Push-Based Block Architecture Plan

**Verdict**: BLOCKED
**Date**: 2026-06-18

---

## Critical Issues (BLOCKED — must fix before implementation)

### CRIT-1: `writeActor` is not currently passed to `BlockStateManager` — the wiring Story 2.3 assumes doesn't exist

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`, lines 583–593

The `BlockStateManager` construction in `GraphContent` does not include `writeActor`:

```kotlin
val blockStateManager = remember {
    dev.stapler.stelekit.ui.state.BlockStateManager(
        blockRepository = repos.blockRepository,
        graphLoader = graphLoader,
        graphWriter = graphWriter,
        pageRepository = repos.pageRepository,
        graphPathProvider = { viewModelRef?.uiState?.value?.currentGraphPath ?: "" },
        histogramWriter = repos.histogramWriter,
        blockObserveDebounceMs = 100L,
        // writeActor is ABSENT here
    )
}
```

`repos.writeActor` is passed to `StelekitViewModel` at line 626, but never to `BlockStateManager`. Story 2.3 instructs: "Add `invalidationSource = repos.writeActor?.blockInvalidations`" as if this is a one-line addition, but the prerequisite (`writeActor` itself as a BSM constructor parameter) is also missing from the current code.

**Impact**: Story 2.3 as written silently skips the most important wiring step. Without `writeActor` in BSM, `writeActor?.updateBlockContentOnly`, `writeActor?.splitBlock`, etc. inside `BlockStateManager` all fall back to direct `blockRepository` calls. The optimistic debounce path never goes through the actor. The invalidation signal is wired to a `null` actor. Phase 1 appears to compile and run but delivers zero improvement.

**Required patch**: Story 2.3 must add TWO constructor parameters at the `App.kt` construction site, not one:
- `writeActor = repos.writeActor`
- `invalidationSource = repos.writeActor?.blockInvalidations`

And must note this is a **behavior change**: adding `writeActor` to BSM switches all user-edit writes from direct `blockRepository` calls to the actor queue. This interaction is not currently tested for BSM in `GraphContent` (the existing BSM unit tests pass `writeActor = null`).

---

### CRIT-2: `AC-2` contradiction — plan deprecates `blockObserveDebounceMs` in Phase 1 but requirements require deletion

Requirements AC-2: "`blockObserveDebounceMs` parameter is **deleted** from `BlockStateManager` and `App.kt`"

Story 2.2 instructs: "Mark `blockObserveDebounceMs` with `@Deprecated(...)`. Do not remove yet — preserves binary compatibility for the Phase 3 cleanup."

Story 2.3 instructs: "Remove `blockObserveDebounceMs = 100L` argument" from `App.kt`.

These are in direct contradiction. Deprecating the parameter (Story 2.2) while removing it from the call site (Story 2.3) means the parameter stays on the class but has no callers — the binary exists but is dead. AC-2 says delete. The plan says keep-but-deprecate until a Phase 3 that has no stories. If Phase 3 never ships, AC-2 is permanently unmet.

**Impact**: AC-2 is a Phase 1 acceptance criterion. The plan's Phase 1 does not satisfy it.

**Required patch**: Either (a) update AC-2 to read "deprecated and unused" to match the plan's intent, or (b) add a Story 2.5 (Epic 2, Phase 1) that removes the parameter entirely after Story 2.2 removes the only call site in Story 2.3. The parameter cannot be safely removed before its call site is gone, so sequencing is: 2.2 (deprecate) → 2.3 (remove call site) → 2.5 (delete parameter). Since there are no other known callers, deletion is safe within Phase 1.

---

### CRIT-3: `refreshBlocksForPage` and `deleteSelectedBlocks`/`moveSelectedBlocks` are uncovered write paths that bypass the push model entirely

**File**: `BlockStateManager.kt`

The plan identifies five hot-path `execute` wrappers to promote (Story 2.1). It misses six additional direct `blockRepository.getBlocksForPage().first()` call sites in `BlockStateManager` that perform post-write refresh pulls outside of the `observePage` subscription:

- `refreshBlocksForPage` (line 803) — called after `indentBlock`, `outdentBlock`, `moveBlockUp`, `moveBlockDown`
- `deleteSelectedBlocks` (line 252) — direct `blockRepository.getBlocksForPage` after `deleteBulk`
- `moveSelectedBlocks` (line 314) — direct `blockRepository.getBlocksForPage` after `moveBlock`
- `restorePageToSnapshot` (line 768) — direct `blockRepository.getBlocksForPage` before and after
- `mergeBlock` (line 1084) — direct `blockRepository.getBlocksForPage`
- `handleBackspace` (line 1118) — direct `blockRepository.getBlocksForPage`

These are not `observePage`-driven pulls. They are explicit, synchronous, one-shot re-fetches performed by the structural-op handlers themselves. After Phase 2, when the `observePage` standing subscription is removed, these paths will be the only mechanism that refreshes `_blocks` for structural operations. They are not on the wildcard sentinel path either — they bypass the actor's invalidation flow entirely.

**Impact**: These paths will still work after Phase 1/2 because they directly update `_blocks` from the DB. But they now create a dual-update situation in Phase 2: for structural ops, `refreshBlocksForPage` fires a `getBlocksForPage` pull, AND the actor emits a wildcard `PagesInvalidated` via `processExecute`, triggering `pullBlocksForPage` in the `observePage` loop. This is two re-queries per structural operation, not one. The plan does not account for this interaction at all.

Additionally, `refreshBlocksForPage` is called from BSM's own `scope.launch` coroutines, which means it runs on `Dispatchers.Default` (the BSM scope), not `PlatformDispatcher.DB`. This is a pre-existing bug — but it becomes newly relevant because Story 3.1 adds `blockReadRepository.getBlocksForPage().first()` calls inside the actor on `PlatformDispatcher.DB`, and these two code paths now coexist with different dispatcher assumptions.

**Required patch**: Story 2.1 should include an audit of all `blockRepository.getBlocksForPage().first()` calls in BSM outside `observePage`, document their interaction with the new invalidation model, and decide whether to suppress the wildcard invalidation for structural ops (by promoting them to typed requests that carry `pageUuid`) or accept the double-pull.

---

### CRIT-4: `processExecute` emits wildcard unconditionally including on `onWriteSuccess` absence — plan gets the semantics wrong

**File**: `DatabaseWriteActor.kt`, lines 252–259

The actual `processExecute` implementation is:

```kotlin
private suspend fun processExecute(request: WriteRequest.Execute) {
    val waitMs = HistogramWriter.epochMs() - request.enqueueMs
    histogramWriter?.record("db.write_queue_depth", _activeOps.value.toLong())
    if (waitMs > 10L) { recordQueueWaitSpan(request, waitMs) }
    request.deferred.complete(request.op())
    // No onWriteSuccess call here
}
```

`processExecute` does NOT call `onWriteSuccess`. Story 1.2 instructs: "In `processRequest`, after each `onWriteSuccess?.invoke(request)` call, add `tryEmit`." For `processExecute`, there is no `onWriteSuccess` call, so this instruction is structurally incorrect — there is no `onWriteSuccess?.invoke(request)` call to "add after."

Story 1.2 further instructs: "`processExecute`: `_blockInvalidations.tryEmit(setOf(WILDCARD_PAGE_UUID))` — emit unconditionally (not just on success) because a partial commit must not silently skip the refresh signal." But `processExecute` calls `request.deferred.complete(request.op())` without inspecting the result. There is no "on success" path to emit from — the `Either` result goes directly to the deferred.

**Impact**: The implementation note "emit unconditionally" happens to be correct behavior, but the rationale is confused: the plan assumes there is a `result.isRight()` success gate that is being bypassed, when in fact there is no success gate at all in `processExecute`. The emit should fire regardless of the `Either` value returned by `op()`. A naive implementer following "after each `onWriteSuccess?.invoke(request)` call" will add the emit call in the wrong location (not in `processExecute` at all) or skip it because there is no `onWriteSuccess` to add after.

**Required patch**: Story 1.2 must be rewritten for `processExecute` to say: "Before `request.deferred.complete(request.op())`, add `_blockInvalidations.tryEmit(setOf(WILDCARD_PAGE_UUID))`." The emit must happen regardless of success or failure, since the `Execute` lambda's result is opaque.

---

## Concerns (should address before implementation)

### CONCERN-1: `flushDiffBatch` single-item failure path skips `onWriteSuccess` — invalidation will not emit for that arm

**File**: `DatabaseWriteActor.kt`, `flushDiffBatch`, lines 425–451

When `batch.size == 1` and the combined result fails, the code falls to:

```kotlin
} else {
    batch[0].deferred.complete(batchResult)
}
```

There is no retry loop for the single-item failure case in `flushDiffBatch` (unlike `flushBatch` which always retries individually). `onWriteSuccess` is never called on failure, so no invalidation emits. This is a pre-existing issue but the plan's Story 1.2 instruction to add emit "after `onWriteSuccess?.invoke`" will silently skip this path. The test `actor_saveBlocksDiff_should_emit_correct_page_uuids` in Story 1.2 tests the success path only. No test covers the `SaveBlocksDiff` single-item failure path.

**Impact**: If a `SaveBlocksDiff` for a single request fails, the subscriber does not receive an invalidation, and `_blocks` stays stale. For user edits this means the UI silently shows the pre-write state. Low severity in practice (write failures are rare), but the test gap is real.

---

### CONCERN-2: Phase 2's `blocksPushed` approach creates a subscription ordering race in `observePage`

Story 3.2 proposes launching two concurrent child coroutines inside `observePage`'s `scope.launch` block: one for `invalidationSource?.collectLatest { }` and one for `pushSource?.collect { }`. The plan acknowledges that "ordering is not guaranteed" between these coroutines and recommends Option A (suppress `_blockInvalidations` for push-path arms) to prevent double-updates.

However, Option A introduces a new race: if `blocksPushed.collect` is not yet subscribed when the actor emits (because the second `launch` hasn't started yet on `Dispatchers.Default`), the push event is dropped with `replay=0`. Unlike the invalidation flow which has `extraBufferCapacity=64`, the plan specifies identical `extraBufferCapacity=64` for `blocksPushed`. If both flows have `replay=0` and a push fires before the second `launch` starts, the update is silently lost with no fallback from `blockInvalidations` (since Option A suppresses it).

The startup race documented in pitfalls section 1 applies here too, but for `blocksPushed`. The plan's risk section addresses the `blockInvalidations` startup race but not the `blocksPushed` startup race.

**Mitigation needed**: Either keep `_blockInvalidations` emitting for all arms (and accept the double-query, guarded by a version-check in BSM), or ensure the `pushSource` subscription starts before the `invalidationSource` subscription within `observePage` and add a comment explaining the ordering dependency. A simpler approach: if Option A is chosen, keep a single `collect` that first checks `blocksPushed`, then falls back to `blockInvalidations` — i.e., merge the two flows rather than launching two concurrent coroutines.

---

### CONCERN-3: `mergeBlock` and `handleBackspace` read from `blockRepository` directly, not from `_blocks`, creating a stale-read hazard

**File**: `BlockStateManager.kt`, lines 1080–1182

`mergeBlock` (line 1080) calls `blockRepository.getBlockByUuid(blockUuid).first()` and then immediately `blockRepository.getBlocksForPage(currentBlock.pageUuid).first()` (line 1084). This bypasses the local `_blocks` optimistic state. If the user typed content into a block that hasn't been confirmed to the DB yet (dirty block), these reads return the pre-edit DB version. The `prevBlock` content used to compute the merge target cursor position (`prevBlock.content.length`) could be stale.

This is a pre-existing bug, not introduced by the push architecture. However, the push architecture migration is the right time to enforce the invariant "structural ops must read from `_blocks`, not from `blockRepository`" since the plan is already auditing all read paths. The plan has no story covering this and the audit in Story 2.4 is scoped only to test files.

---

### CONCERN-4: AC-4 is unachievable as written — the plan acknowledges this but does not update the requirement

Requirements AC-4: "All existing `BlockStateManager` tests pass without modification to test setup."

Story 2.4 explicitly states: "AC-4 as written ('all existing tests pass without modification to test setup') is not fully achievable for tests that relied on reactive re-emission. The plan accepts updating these tests as required."

This is a requirements contradiction that should be resolved in requirements.md, not buried in a story's background note. As written, AC-4 will fail at Phase 1 completion if any existing tests rely on reactive re-emission (which the pitfalls research confirms they do). The implementation team will be blocked on sign-off because AC-4 says "without modification" and the plan says "modification required."

**Required patch**: Update `requirements.md` AC-4 to: "All existing `BlockStateManager` tests are updated to be compatible with the invalidation-based model and pass; no test may be deleted — each must be converted to an equivalent assertion via the invalidation or optimistic-update path."

---

### CONCERN-5: Story 3.1 assumes `blockRepository: BlockRepository` in the actor already has `getBlocksForPage` — it does, but the interface must be verified

The plan's Flagged Decision #3 says "Verify whether `blockRepository: BlockReadRepository` is already a constructor field." The actual constructor is:

```kotlin
class DatabaseWriteActor(
    private val blockRepository: BlockRepository,
    private val pageRepository: PageRepository,
    ...
)
```

`BlockRepository` (not `BlockReadRepository`) is the actual type. The plan uses "BlockReadRepository" throughout Story 3.1's implementation notes, which is either a different interface or an alias. If `BlockRepository` extends `BlockReadRepository`, this is fine. If `BlockReadRepository` does not exist, the Story 3.1 code snippets will not compile as written. This must be confirmed before Story 3.1 begins.

---

### CONCERN-6: The benchmark story (Story 3.3) has no pass/fail criterion and no CI gate — it cannot satisfy AC-6

AC-6: "Journal view block list re-render latency during large graph background indexing is measurably reduced vs. baseline."

Story 3.3 explicitly states: "No pass/fail test — this is a data-collection task." There is no test that enforces AC-6 will stay green after future changes. "Measurably reduced" has no threshold. A 0.1% reduction technically satisfies the criterion. Future regressions are not caught.

**Impact**: AC-6 is unmeasurable and unenforceable. The benchmark is a one-time observation, not a regression gate.

**Recommended patch**: Story 3.3 should produce a JVM test assertion: "during a simulated 500-block background import with 5 observed pages, `getBlocksForPage` call count for non-imported pages is 0 after Phase 2." This is already covered by Story 4.3's `CountingBlockRepository`, so Story 3.3 can be scoped to running the existing benchmark infrastructure and recording before/after timing numbers. The latency threshold should be defined as "> 20% reduction in re-render events measured by `getBlocksForPage` call count" — not a time-based threshold (too fragile in CI).

---

## Observations (informational)

### OBS-1: `saveBlocksDiff` convenience method does not use `sendAndAwait` — pre-existing asymmetry

`saveBlocksDiff` at line 510 calls `channelFor(priority).send(req)` and `req.deferred.await()` directly, bypassing `sendAndAwait` and therefore not incrementing `_activeOps`. This means `hasPendingWrites` underreports during `SaveBlocksDiff` operations. This is a pre-existing issue not introduced by the push architecture, but Story 1.2 will add `tryEmit` calls after the `flushDiffBatch` path — a reviewer should note this asymmetry during Story 1.2's code review.

### OBS-2: `loadPageContent` (line 1237) performs two consecutive `getBlocksForPage` calls and updates `_blocks` directly — this path is neither on the invalidation nor the push path and will continue working but is undocumented

`loadPageContent` does its own `_blocks.update` outside `observePage`. After Phase 1, this creates a third code path that updates blocks: (1) `observePage` initial pull, (2) invalidation-driven pull, (3) `loadPageContent` direct update. The plan makes no mention of this path. It will not break, but it adds complexity to the block state lifecycle that should be acknowledged.

### OBS-3: The plan does not specify where `BlockUpdateEvent.kt` lives relative to `WriteRequest`

`WriteRequest` is a nested sealed class inside `DatabaseWriteActor`. Story 1.1 creates `BlockUpdateEvent` as a top-level file in the `db` package. The plan should note that `WILDCARD_PAGE_UUID` (which Story 1.2 adds to `DatabaseWriteActor.companion`) is referenced in `BlockUpdateEvent.kt`'s usage sites via `DatabaseWriteActor.WILDCARD_PAGE_UUID`. If `BlockUpdateEvent` is in a separate file but references `DatabaseWriteActor`, a circular dependency check is needed. In this case it is not circular (event file does not import the actor), but it is worth noting the reference direction for reviewers.

### OBS-4: `WriteSource` is defined as a sealed class with `object` children — this should be a sealed interface or enum

`object UserEdit : WriteSource()` is valid but is a heavier pattern than `sealed interface` with `object` implementations. More importantly, `object` subclasses of sealed classes cannot be used in exhaustive `when` expressions on all Kotlin targets without a `else` branch in some compiler versions. This is a cosmetic issue, but `sealed interface` is the idiomatic modern Kotlin pattern for discriminated unions with no state.

---

## Recommended Plan Patches

### Patch 1 — Story 2.3: Add `writeActor` to BSM construction (CRIT-1)

Replace the Story 2.3 task "Add `invalidationSource = repos.writeActor?.blockInvalidations`" with:

```
- [ ] At the BlockStateManager construction site in App.kt (line ~583), add TWO parameters:
      writeActor = repos.writeActor,
      invalidationSource = repos.writeActor?.blockInvalidations
  Note: Adding `writeActor` switches BSM user-edit writes from direct repository calls to the
  actor queue. This is the intended behavior but is a behavior change. Verify the existing
  integration test suite covers the BSM+actor combination before merging.
```

### Patch 2 — Story 2.2 / requirements AC-2: Resolve deprecation-vs-deletion contradiction (CRIT-2)

Add Story 2.5 to Epic 2:

```
### Story 2.5: Delete `blockObserveDebounceMs` parameter (satisfies AC-2)

Prerequisite: Story 2.3 merged (call site removed).

- [ ] Delete `private val blockObserveDebounceMs: Long = 0L` from BlockStateManager constructor.
- [ ] Delete the `@Deprecated` annotation added in Story 2.2.
- [ ] Verify no remaining callers (grep for blockObserveDebounceMs across all source sets).
- [ ] Update requirements.md AC-2 to confirm it is now satisfied.
```

Also update requirements.md AC-4 to match what the plan actually does.

### Patch 3 — Story 2.1: Expand audit to include `refreshBlocksForPage` and bulk-structural paths (CRIT-3)

Add to Story 2.1 tasks:

```
- [ ] Audit all blockRepository.getBlocksForPage().first() call sites in BlockStateManager outside
  of observePage. Current list: refreshBlocksForPage (line 803), deleteSelectedBlocks (line 252),
  moveSelectedBlocks (line 314), restorePageToSnapshot (line 768), mergeBlock (line 1084),
  handleBackspace (line 1118). For each: document whether a wildcard invalidation from processExecute
  will also fire for the same page, creating a double-pull. Decision: accept double-pull for structural
  ops (low frequency) and document with a comment, OR promote structural ops to typed requests.
```

### Patch 4 — Story 1.2: Fix `processExecute` emit location (CRIT-4)

Replace the Story 1.2 instruction for `processExecute` with:

```
- [ ] In `processExecute`, BEFORE `request.deferred.complete(request.op())`, add:
      _blockInvalidations.tryEmit(setOf(WILDCARD_PAGE_UUID))
  Rationale: processExecute has no onWriteSuccess call and no success/failure inspection —
  the emit must be unconditional and must precede deferred completion so subscribers
  receive the signal before the caller's await() returns.
```

### Patch 5 — Requirements: Update AC-4 (CONCERN-4)

In `requirements.md`, replace AC-4 with:

> AC-4: All existing `BlockStateManager` tests are updated to be compatible with the invalidation-based observation model and continue to pass. No test may be deleted — each that relied on reactive re-emission must be converted to the invalidation-signal path or the optimistic-update path. The total test count must not decrease.
