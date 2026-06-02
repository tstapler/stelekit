# Implementation Plan: Block Write Serialization Fix

**Epic**: Block Write Serialization Fix
**Status**: Draft
**Requirements**: `project_plans/block-write-race/requirements.md`

---

## Problem Summary

`BlockStateManager` has two async write paths that are not serialized relative to each other:

1. **Content writes** — `applyContentChange` → `writeActor.updateBlockContentOnly()` — queued through `DatabaseWriteActor` (FIFO serialized).
2. **Structural writes** — `splitBlock`, `addNewBlock`, `mergeBlock`, `handleBackspace` — call `blockRepository.splitBlock / mergeBlocks / deleteBlock` **directly**, bypassing the actor queue.

Because `splitBlock` and `mergeBlocks` read block content FROM THE DATABASE inside their SQL transactions (to compute split/merge text), a structural op that fires before its preceding content write drains from the actor reads stale content and corrupts the result.

The fix is to route all structural ops through the same `DatabaseWriteActor` queue, using the same actor-or-direct fallback pattern already established for content writes.

---

## Stories

### Story 1: Actor — Add Typed Structural-Op Methods to `DatabaseWriteActor`

**Goal**: Add `splitBlock`, `mergeBlocks`, and a structural `deleteBlock` wrapper to `DatabaseWriteActor` so callers can route structural ops through the actor's FIFO queue without losing type information.

**Files to change**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`
  — insert after line 394 (after the existing `deleteBlock` method, before `executeBatch`)

**What to add** (insert after line 394):

```kotlin
/**
 * Splits [blockUuid] at [cursorPosition] through the actor's serialized queue.
 * Guaranteed to execute after any pending [updateBlockContentOnly] for the same block.
 *
 * Returns the newly created [Block] on success, or a [DomainError] on failure.
 */
suspend fun splitBlock(
    blockUuid: String,
    cursorPosition: Int,
    newBlockUuid: String?,
): Either<DomainError, Block> {
    var newBlock: Block? = null
    val opResult = execute {
        blockRepository.splitBlock(blockUuid, cursorPosition, newBlockUuid)
            .onRight { newBlock = it }
            .map { }
    }
    return opResult.fold(
        ifLeft = { it.left() },
        ifRight = {
            newBlock?.right()
                ?: DomainError.DatabaseError.WriteFailed(
                    "splitBlock returned no block for $blockUuid"
                ).left()
        }
    )
}

/**
 * Merges [nextBlockUuid] into [blockUuid] through the actor's serialized queue.
 * Guaranteed to execute after any pending [updateBlockContentOnly] for either block.
 */
suspend fun mergeBlocks(
    blockUuid: String,
    nextBlockUuid: String,
    separator: String,
): Either<DomainError, Unit> =
    execute { blockRepository.mergeBlocks(blockUuid, nextBlockUuid, separator) }

/**
 * Deletes [blockUuid] through the actor's serialized queue.
 * Distinct from [deleteBlock] (which logs to op-logger); this variant is for
 * structural deletions triggered by backspace/merge where the block being removed
 * has no content to log.
 */
suspend fun deleteBlockStructural(blockUuid: String): Either<DomainError, Unit> =
    execute { blockRepository.deleteBlock(blockUuid) }
```

**Notes**:
- `DatabaseWriteActor` is already annotated `@OptIn(DirectRepositoryWrite::class)` at class level (line 54), so the new methods can call `blockRepository.splitBlock/mergeBlocks/deleteBlock` without per-call opt-in.
- All new methods use `Priority.HIGH` (the default for `execute {}`), matching the priority of existing `updateBlockContentOnly` calls, preserving correct FIFO ordering within the high-priority channel.
- `splitBlock` captures the `Block` result via a closure `var` because `WriteRequest.Execute` is typed `suspend () -> Either<DomainError, Unit>`. This is the established pattern in the codebase (Actor analysis, option 3).
- `deleteBlockStructural` is distinct from the existing `deleteBlock` wrapper (which pre-reads the block for op-logger). Structural deletes (backspace on empty block) do not need op-logging because there is no content to capture; using the lighter variant avoids an extra `getBlockByUuid` read inside the actor.

**Acceptance criteria**:
- `DatabaseWriteActor` compiles with the three new methods.
- Calling `actor.splitBlock("b1", 5, null)` from a test returns `Either<DomainError, Block>`.
- `./gradlew jvmTest --tests "*.DatabaseWriteActorTest"` passes without modification.

---

### Story 2: BlockStateManager — Route All Structural Ops Through Actor

**Goal**: Replace the four direct `blockRepository.splitBlock / mergeBlocks / deleteBlock` call sites in `BlockStateManager` with actor-routed equivalents, using the same `writeActor?.xxx() ?: blockRepository.xxx()` fallback pattern already used for content writes.

**Files to change**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`

#### Task 2a: Add structural write helpers (after line 76, alongside existing `writeBlock`/`writeContentOnly`)

Add private helper methods:

```kotlin
private suspend fun writeSplitBlock(
    blockUuid: String,
    cursorPosition: Int,
    newBlockUuid: String?,
): Either<DomainError, Block> =
    writeActor?.splitBlock(blockUuid, cursorPosition, newBlockUuid)
        ?: blockRepository.splitBlock(blockUuid, cursorPosition, newBlockUuid)

private suspend fun writeMergeBlocks(
    blockUuid: String,
    nextBlockUuid: String,
    separator: String,
): Either<DomainError, Unit> =
    writeActor?.mergeBlocks(blockUuid, nextBlockUuid, separator)
        ?: blockRepository.mergeBlocks(blockUuid, nextBlockUuid, separator)

private suspend fun writeDeleteBlockStructural(
    blockUuid: String,
): Either<DomainError, Unit> =
    writeActor?.deleteBlockStructural(blockUuid)
        ?: blockRepository.deleteBlock(blockUuid)
```

#### Task 2b: Fix `addNewBlock` (line 746)

Change:
```kotlin
blockRepository.splitBlock(currentBlockUuid, cursorPosition, expectedNewUuid).onRight { newBlock ->
```
To:
```kotlin
writeSplitBlock(currentBlockUuid, cursorPosition, expectedNewUuid).onRight { newBlock ->
```

#### Task 2c: Fix `splitBlock` (line 799)

Change:
```kotlin
blockRepository.splitBlock(blockUuid, clampedCursor, expectedNewUuid).onRight { newBlock ->
```
To:
```kotlin
writeSplitBlock(blockUuid, clampedCursor, expectedNewUuid).onRight { newBlock ->
```

#### Task 2d: Fix `mergeBlock` (line 946)

Change:
```kotlin
blockRepository.mergeBlocks(prevBlock.uuid, blockUuid, "").onRight {
```
To:
```kotlin
writeMergeBlocks(prevBlock.uuid, blockUuid, "").onRight {
```

#### Task 2e: Fix `handleBackspace` — three direct call sites (lines 986, 998, 1008, 1021)

There are four direct repository calls in `handleBackspace`:

- Line 986: `blockRepository.mergeBlocks(prevBlock.uuid, blockUuid, "")` → `writeMergeBlocks(prevBlock.uuid, blockUuid, "")`
- Line 998: `blockRepository.deleteBlock(blockUuid)` → `writeDeleteBlockStructural(blockUuid)`
- Line 1008: `blockRepository.mergeBlocks(parent.uuid, blockUuid, "")` → `writeMergeBlocks(parent.uuid, blockUuid, "")`
- Line 1021: `blockRepository.deleteBlock(blockUuid)` → `writeDeleteBlockStructural(blockUuid)`

**Notes on optimistic update ordering**: The existing code updates `_blocks` in-memory before the DB call (optimistic path). This ordering must be preserved after the fix: the optimistic `_blocks.update { }` still runs immediately (before awaiting the actor), and the rollback on `.onLeft` still reverts it. The only change is replacing the direct repository call with the actor-routed helper. The actor suspension replaces the direct repository suspension — the coroutine still suspends at the same logical point; it just suspends in the actor's queue rather than directly in the repository method.

**Acceptance criteria**:
- No `blockRepository.splitBlock`, `blockRepository.mergeBlocks`, or `blockRepository.deleteBlock` calls remain inside `addNewBlock`, `splitBlock`, `mergeBlock`, or `handleBackspace`.
- All existing `BlockStateManagerTest` tests pass without modification (`./gradlew jvmTest --tests "*.BlockStateManagerTest"`).
- When `writeActor` is null (test/in-memory path), the fallback to direct repository calls preserves existing in-memory test behavior.

---

### Story 3: Conflict Detection — Close the FR-4 Gap

**Goal**: When an external file change arrives while a structural op is in the actor queue (not yet committed to DB), the conflict detection must treat the page as dirty and trigger the conflict dialog.

**Root cause of the gap**: `StelekitViewModel.observeExternalFileChanges` has a three-tier protection check (line 1057): actively editing, dirty blocks in memory, pending disk write. None of these tiers cover the window where a structural op has been enqueued in the actor but has not yet completed its DB write.

#### Task 3a: Expose actor queue occupancy from `DatabaseWriteActor`

Add to `DatabaseWriteActor`:

```kotlin
// Counts requests that have been sent to the channel but whose DB operation has not
// yet completed. Incremented before send(), decremented in the actor's finally block.
// Declared as a field, not a channel property, so it covers the full enqueue→commit
// window (Channel.isEmpty only covers enqueue→dequeue, going false as soon as the
// actor dequeues the item — before the SQL transaction completes).
private val _activeOps = java.util.concurrent.atomic.AtomicInteger(0)

/**
 * Returns true if any write operation is currently pending or in-flight through the actor.
 * Covers both the channel-queue window (not yet dequeued) and the processing window
 * (dequeued but DB transaction not yet committed).
 *
 * Used by conflict detection: an external file change arriving while a split/merge is
 * in-flight must trigger the conflict dialog rather than silently overwriting local data.
 */
val hasPendingWrites: Boolean
    get() = _activeOps.get() > 0
```

Wire the counter in `execute {}` (the entry point for all typed wrappers):

```kotlin
suspend fun execute(priority: Priority = Priority.HIGH, op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> {
    _activeOps.incrementAndGet()               // count: enqueued
    val req = WriteRequest.Execute(op, priority)
    channelFor(priority).send(req)
    return try {
        req.deferred.await()
    } finally {
        _activeOps.decrementAndGet()           // count: committed or failed
    }
}
```

And add a matching decrement in the actor processing loop when an `Execute` request completes (inside `processRequest` for the `Execute` branch, in the `finally` after `request.deferred.complete`). This ensures the counter goes to zero only after the DB round-trip finishes.

**KMP note**: `java.util.concurrent.atomic.AtomicInteger` is JVM/Android only. For KMP, use `kotlinx.atomicfu.AtomicInt` from the `atomicfu` library (already a transitive dependency of `kotlinx.coroutines`). Replace with:

```kotlin
import kotlinx.atomicfu.atomic
private val _activeOps = atomic(0)
val hasPendingWrites: Boolean get() = _activeOps.value > 0
// increment: _activeOps.incrementAndGet()
// decrement: _activeOps.decrementAndGet()
```

**Correctness**: The counter covers the full enqueue→DB-commit window. `hasPendingWrites` returns `true` from `execute()` call through `req.deferred.await()` return, which happens only after the actor completes the DB operation and calls `request.deferred.complete(result)`. False negatives are now limited to the brief gap between the caller's `incrementAndGet` and the `send()` completing — negligible.

#### Task 3b: Thread `hasPendingWrites` into `BlockStateManager`

Add a public property to `BlockStateManager`:

```kotlin
/**
 * Returns true if the DatabaseWriteActor has pending writes (structural ops in queue).
 * Returns false when no actor is configured (in-memory/test path — no queue to check).
 */
val hasActorPendingWrites: Boolean
    get() = writeActor?.hasPendingWrites ?: false
```

#### Task 3c: Add a fourth tier to the conflict guard in `StelekitViewModel` (line 1057)

Change:
```kotlin
val shouldProtect = editingBlockUuid != null || pageHasDirtyBlocks || hasPendingDiskWrite
```
To:
```kotlin
val hasActorPending = blockStateManager?.hasActorPendingWrites ?: false
val shouldProtect = editingBlockUuid != null || pageHasDirtyBlocks || hasPendingDiskWrite || hasActorPending
```

**Acceptance criteria**:
- A manual test: navigate to a page, type content, immediately press Enter (queuing split in actor), then inject a simulated external file change before the actor drains — the conflict dialog appears.
- `./gradlew ciCheck` passes.
- The existing `StelekitViewModelTest` or equivalent tests that exercise `observeExternalFileChanges` still pass.

---

### Story 4: Tests — Race-Condition Tests

**Goal**: Demonstrate that the race cannot occur after the fix, covering split, addNewBlock, merge, and backspace paths (NFR-4).

**File to change**:
- `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerTest.kt`
  — add new test doubles and test cases after line 1675

#### Task 4a: Add `DelayedContentBlockRepository` test double

```kotlin
/**
 * Delays [updateBlockContentOnly] by [contentDelayMs] milliseconds.
 * Used to reproduce the race: a content write is queued in the actor but has not
 * yet committed to the repository when a structural op fires.
 *
 * This is distinct from [DelayedBlockRepository] which delays [splitBlock].
 */
@OptIn(DirectRepositoryWrite::class)
private class DelayedContentBlockRepository(
    private val delegate: InMemoryBlockRepository,
    private val contentDelayMs: Long = 500L,
) : BlockRepository by delegate {
    @DirectRepositoryWrite
    override suspend fun updateBlockContentOnly(
        blockUuid: String,
        content: String,
    ): Either<DomainError, Unit> {
        delay(contentDelayMs)
        return delegate.updateBlockContentOnly(blockUuid, content)
    }
}
```

#### Task 4b: Race test — `splitBlock_after_pending_content_write_uses_latest_content`

```kotlin
@Test
fun splitBlock_after_pending_content_write_uses_latest_content() = runTest {
    // Arrange: repo that delays content writes so the actor queue stays non-empty
    val innerRepo = InMemoryBlockRepository()
    val delayedRepo = DelayedContentBlockRepository(innerRepo, contentDelayMs = 500L)
    val pageRepo = InMemoryPageRepository()
    val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    val actor = DatabaseWriteActor(delayedRepo, pageRepo, scope = scope)
    // Load a page with one block containing "original"
    val page = Page(uuid = "page1", ...)
    val block = Block(uuid = "b1", pageUuid = "page1", content = "original", ...)
    innerRepo.saveBlock(block)
    val manager = BlockStateManager(delayedRepo, graphLoader, scope, writeActor = actor)
    manager.loadPage("page1")
    advanceUntilIdle()

    // Act: type "Hello World" (queues content write, delayed 500ms in repo)
    manager.applyContentChange("b1", "Hello World", 11)
    // Immediately hit Enter before content write drains (cursor at 5)
    manager.splitBlock("b1", 5)
    // Drain all pending coroutines
    advanceUntilIdle()

    // Assert: first block has "Hello" (first 5 chars), second block has "World"
    val blocks = manager.blocks.value["page1"]!!
    assertEquals(2, blocks.size)
    val first = blocks.find { it.uuid == "b1" }!!
    val second = blocks.find { it.uuid != "b1" }!!
    assertEquals("Hello", first.content)
    assertEquals("World", second.content)
}
```

**Verification**: Run this test WITHOUT the fix — it should fail (first block retains "original" or second block has wrong content). With the fix it must pass.

#### Task 4c: Race test — `addNewBlock_after_pending_content_write_preserves_typed_content`

Same structure as 4b but calls `manager.addNewBlock("b1")` instead of `manager.splitBlock("b1", 5)`. With the fix, the new block must appear and "b1" must retain the typed content committed to DB.

#### Task 4d: Race test — `mergeBlock_after_pending_content_write_uses_latest_content`

- Set up two blocks: "b1" with content "Hello", "b2" with content "original"
- Queue a content write: `manager.applyContentChange("b2", " World", 6)` (delayed 500ms)
- Before it drains: `manager.mergeBlock("b2")` (merges b2 into b1)
- After `advanceUntilIdle()`: assert merged content in b1 is "Hello World" (not "Hello original")

#### Task 4e: Race test — `handleBackspace_after_pending_content_write_merges_latest_content`

Same as 4d but calls `manager.handleBackspace("b2")` with cursor at position 0.

**Acceptance criteria**:
- All four race tests fail before the fix (demonstrating the bug) and pass after.
- Tests use `DelayedContentBlockRepository` + real `DatabaseWriteActor` + `UnconfinedTestDispatcher` — no mocking of actor internals.
- Tests are in `commonTest` source set (not `jvmTest`) so they run on all platforms.

---

### Story 5: Regression — Existing BlockStateManager Tests Must Pass Unmodified

**Goal**: Confirm no behavioral regression to the happy-path and optimistic-update tests.

**Verification steps**:
1. Run `./gradlew jvmTest --tests "*.BlockStateManagerTest"` — all ~40 existing tests must pass.
2. Run `./gradlew testDebugUnitTest` — Android unit tests must pass.
3. Run `./gradlew ciCheck` — full CI gate must pass.

**Files to change**: none. This story is verification-only.

**Key invariants to check**:
- `splitBlock_optimistically_updates_blocks_and_focus_before_db_write` (TC-04) still passes — the actor suspension must not block the optimistic `_blocks.update` that runs before the `writeSplitBlock` call. The optimistic update is above the `writeSplitBlock` call in the coroutine, so it executes immediately and the suspension only happens when the actor is awaited.
- `splitBlock_rolls_back_blocks_and_focus_on_db_failure` (TC-06) and `mergeBlock_rolls_back_focus_on_db_failure` (TC-07) still pass — rollback logic is in the `.onLeft` handler of the actor call result, which is unchanged.
- Tests that use `writeActor = null` (the majority) continue to work via the direct fallback path in `writeSplitBlock`, `writeMergeBlocks`, `writeDeleteBlockStructural`.

---

## Dependency Graph

```
Story 1 (Actor methods)
    └── Story 2 (BlockStateManager routing)
            └── Story 3 (Conflict detection)  [can be parallelized with Story 4]
            └── Story 4 (Race tests)           [depends on Story 2 for the fix to test]
Story 5 (Regression) runs after Story 2
```

Recommended order: Story 1 → Story 2 → (Story 3 + Story 4 in parallel) → Story 5 verification.

---

## Implementation Notes

### Why not the Flush/Barrier pattern?

The research explicitly rejected this (see `research/coroutine-patterns.md`, Option B): a `awaitPendingWrites()` barrier followed by a direct `blockRepository.splitBlock()` call has a TOCTOU race — a new content write can arrive between the barrier completing and the structural op executing. The only correct fix is routing the structural op through the same channel.

### Optimistic update ordering is preserved

The optimistic in-memory update (`_blocks.update { }`) runs before the `writeSplitBlock` suspension in the coroutine body. The actor routing changes only the DB-side suspension point, not the optimistic-first ordering visible to the UI. TC-04 and TC-05 will continue to pass because they test optimistic behavior before DB returns.

### `writeActor == null` fallback

`BlockStateManager` has a nullable `writeActor: DatabaseWriteActor?`. When null (all existing unit tests), `writeSplitBlock` etc. fall back to direct repository calls — preserving the existing test contract exactly. No test setup changes are required for any existing test.

### Actor destruction ordering

`DatabaseWriteActor.close()` cancels `actorScope` after closing the channels. Structural ops enqueued but not yet processed when `close()` is called will have their deferred completed with a `WriteFailed` error. `BlockStateManager`'s `.onLeft` handler will log the error and roll back the optimistic update — identical behavior to the existing handling of DB failures.

### `deleteBlockStructural` vs `deleteBlock`

The existing `DatabaseWriteActor.deleteBlock` reads the block from the repository to log it for undo purposes (lines 382–394). The structural delete used by `handleBackspace` (empty block at cursor position 0) has no need for op-logging: the block being deleted has empty content and no meaningful undo entry. Using a lighter `deleteBlockStructural` avoids an extra `getBlockByUuid` read inside the actor's serialized queue, reducing latency for backspace operations.

**Safety guarantee for the "empty content" assumption**: `handleBackspace` reads `currentBlock` from the in-memory `_blocks` state (which reflects the latest typed content, including optimistic updates not yet committed to DB). The two `deleteBlock` call sites in `handleBackspace` (lines 998 and 1021) are gated by `currentBlock.content.isEmpty()`. Because `_blocks` is updated optimistically on every keystroke, this check reflects the user's most recent intent. If the user typed something (non-empty), the check fails and the delete branch is NOT taken — the merge branch is taken instead. The race where a pending content write leaves the block non-empty in the actor queue does not affect this guard, because the guard reads from `_blocks` (live), not from the DB.

If future requirements add undo-logging for structural deletes, `deleteBlockStructural` should be updated to call `opLogger.logDelete`.

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Actor suspension delays optimistic focus | Low | Low | Optimistic update runs before `writeSplitBlock` call — no change to UI timing |
| False negative in `hasPendingWrites` | Low | Low | Resolved: AtomicInt counter covers full enqueue→DB-commit window, not just channel-queue window |
| `DelayedContentBlockRepository` flakiness in tests | Low | Medium | Use `UnconfinedTestDispatcher` + `advanceUntilIdle()` — deterministic scheduling |
| Regression in undo/redo for split/merge | Low | High | Undo record is created in `.onRight` handler, unchanged from current; Story 5 regression run covers this |
