# Requirements: Block Write Race Fix

## Problem Statement

When editing blocks in the journal (or any editor surface) at high speed, pressing Enter to
create a new block frequently results in the new block disappearing, the previous block's
content being restored to a stale version, or content merging incorrectly. This happens >50%
of the time during fast editing.

## Root Cause (Diagnosed)

`BlockStateManager` has two async write paths that are **not serialized** relative to each
other:

1. **Content writes** — `applyContentChange` → `writeActor.updateBlockContentOnly()`
   — queued through `DatabaseWriteActor`, which serializes them.

2. **Structural writes** — `splitBlock`, `addNewBlock`, `mergeBlock`, `handleBackspace`
   — call `blockRepository.splitBlock / mergeBlocks / deleteBlock` **directly**, bypassing
   the actor queue.

**Race sequence (fast typing + Enter):**

```
t=0  User types. applyContentChange → actor.updateBlockContentOnly("Hello World") [queued]
t=1  User hits Enter. splitBlock() launches a new coroutine.
t=2  splitBlock reads _blocks.value → sees "Hello World" in memory, cursor=5.
t=3  splitBlock calls blockRepository.splitBlock(uuid, 5, newUuid) DIRECTLY.
       DB still has old content → split operates on stale data.
t=4  Actor dequeues content update → writes "Hello World" to DB for original block.
       This OVERWRITES the structural split that splitBlock just performed.
t=5  Reactive DB emission fires. mergeBlocks() may or may not keep the dirty version.
       Result: new block disappears, content reverts, or merges wrong.
```

The same pattern applies to `addNewBlock`, `mergeBlock`, and `handleBackspace`.

## Functional Requirements

### FR-1: Structural operations must serialize after pending content writes

All structural operations on a block must execute after any pending `DatabaseWriteActor`
content-write for the same block has committed. Specifically:

- `splitBlock` must wait for pending content writes to `blockUuid` before calling
  `blockRepository.splitBlock`.
- `addNewBlock` must wait for pending content writes to `currentBlockUuid` before calling
  `blockRepository.splitBlock`.
- `mergeBlock` must wait for pending content writes to both the current and previous block.
- `handleBackspace` must wait for pending content writes to both blocks involved in the merge
  or delete.

### FR-2: Structural operations must route through DatabaseWriteActor

Structural block operations (split, merge, delete) must be dispatched via
`DatabaseWriteActor.execute {}` so that the actor's serialization guarantee applies. This
prevents any future content writes from racing with structural writes.

### FR-3: In-memory state must reflect completed structural operation

After a structural operation completes (DB round-trip confirmed), `_blocks` must reflect the
correct post-op state — not a merge of stale DB content and the optimistic pre-op snapshot.
The current optimistic-then-confirm flow is acceptable; only the ordering constraint (FR-1,
FR-2) needs to be enforced.

### FR-4: External file changes must not overwrite blocks with unconfirmed writes

When `GraphLoader.externalFileChanges` fires while a structural operation is in-flight
(actor queue not empty), the conflict detection must treat the page as dirty. The existing
`hasPendingDiskWrite` check is insufficient because structural ops may not queue a disk write
until their DB write completes.

Acceptance: an external file change arriving while a split/merge is in the actor queue must
trigger the existing conflict dialog, not silently overwrite.

### FR-5: All block editor surfaces are in scope

Journal, PageView, and any future view that uses `BlockStateManager` must benefit from this
fix. No surface-specific workarounds.

### FR-6: Backspace/delete/merge path is also fixed

`handleBackspace` and `mergeBlock` exhibit the same race (structural op bypasses actor).
Both must be fixed with the same serialization approach as FR-1/FR-2.

## Non-Functional Requirements

### NFR-1: Correctness SLA — final committed state wins

It is acceptable to lose in-progress (in-memory, unwritten) edits when an external conflict
occurs (disk file changed). The priority is that the **final committed DB state** is always
correct and consistent. No silent data loss without user notification.

### NFR-2: Full concurrency safety

The fix must be safe under:
- Fast keystroke sequences (debounce windows overlap)
- External file watcher events concurrent with DB writes
- Graph-loading bulk transactions concurrent with user edits (already handled by actor
  priority queue; must remain intact)

### NFR-3: No regression to existing write performance

The actor already batches and coalesces `SaveBlocks` requests. The serialization added by
FR-2 must not introduce additional round-trips or artificial delays beyond what is
necessary to ensure ordering. The debounce window (currently 300ms for disk, immediate for
DB via actor) must not increase.

### NFR-4: Tests must cover the race window explicitly

Tests must demonstrate that the race cannot occur after the fix. Specifically:
- A test that schedules a content write and a structural op concurrently and asserts the
  structural op wins the final DB state.
- A test that verifies block content after a split initiated while a content write is in-flight.
- A test for merge/backspace with the same concurrency scenario.

## Out of Scope

- Operational transforms / CRDT for multi-user simultaneous editing
- Increasing or decreasing the debounce window as a standalone change
- Any UI changes — this is a write-path correctness fix only

## Success Criteria

1. Fast typing followed immediately by Enter never drops the new block or reverts content
   (manual test: type 10 characters, immediately Enter — new block always persists).
2. All existing `BlockStateManager` tests pass without modification.
3. New race-condition tests pass (see NFR-4).
4. `./gradlew ciCheck` passes.
