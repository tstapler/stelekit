# Validation Plan: Block Write Serialization Fix

**Project**: block-write-race
**Plan**: `project_plans/block-write-race/implementation/plan.md`
**Requirements**: `project_plans/block-write-race/requirements.md`
**Adversarial review**: `project_plans/block-write-race/implementation/adversarial-review.md`
**Gate verdict**: PASS

---

## 1. Requirements Coverage Matrix

Every functional and non-functional requirement must have at least one test case. The table
below maps requirements to test cases defined in Section 2.

| Requirement | Test IDs | Count |
|---|---|---|
| FR-1: Structural ops serialize after pending content writes | TC-R1a, TC-R1b, TC-R1c, TC-R1d | 4 |
| FR-2: Structural ops route through DatabaseWriteActor | TC-R2a, TC-R2b | 2 |
| FR-3: In-memory state reflects completed structural op | TC-R3a, TC-R3b | 2 |
| FR-4: External file changes blocked while actor queue non-empty | TC-R4a | 1 |
| FR-5: All block editor surfaces covered (no surface workarounds) | TC-R5a | 1 |
| FR-6: Backspace/delete/merge path fixed | TC-R6a, TC-R6b | 2 |
| NFR-1: Final committed DB state wins on external conflict | TC-N1a | 1 |
| NFR-2: Full concurrency safety | TC-N2a, TC-N2b | 2 |
| NFR-3: No regression to write performance | TC-N3a | 1 |
| NFR-4: Race window covered explicitly by tests | TC-N4a, TC-N4b, TC-N4c, TC-N4d | 4 |

**Coverage**: 10/10 requirements covered. Fraction: **10/10 = 100%**.

---

## 2. Full Test Suite

Tests are grouped by type: unit (isolated component), integration (actor + repository
collaboration), and race (concurrency scenario with `DelayedContentBlockRepository`).

---

### 2.1 Unit Tests — `DatabaseWriteActorTest`

Source set: `jvmTest` (existing file, new cases added).

#### TC-R2a: `splitBlock_returns_new_block_via_actor`

**Type**: Unit
**Requirement**: FR-2
**Story**: Story 1

Setup: `DatabaseWriteActor` with an `InMemoryBlockRepository` containing block "b1" on
page "p1". Call `actor.splitBlock("b1", 5, null)`.

Assert:
- Result is `Either.Right<Block>`.
- Returned block has `pageUuid == "p1"`.
- `InMemoryBlockRepository` now has 2 blocks for page "p1".

#### TC-R2b: `mergeBlocks_via_actor_serializes_after_queued_content_write`

**Type**: Unit
**Requirement**: FR-2
**Story**: Story 1

Setup: actor + `DelayedContentBlockRepository(contentDelayMs=200L)` + two blocks "b1"
("Hello"), "b2" (" World"). Queue `actor.updateBlockContentOnly("b2", " World")` (delayed).
Immediately queue `actor.mergeBlocks("b1", "b2", "")`.

Assert after `advanceUntilIdle()`:
- Merged content in "b1" is `"Hello World"` (content write drained before merge executed).
- "b2" no longer exists in the repository.

#### TC-R2c: `deleteBlockStructural_removes_block_via_actor`

**Type**: Unit
**Requirement**: FR-2, FR-6
**Story**: Story 1

Setup: actor + `InMemoryBlockRepository` with empty block "b1". Call
`actor.deleteBlockStructural("b1")`.

Assert:
- Result is `Either.Right<Unit>`.
- "b1" is not present in the repository.

---

### 2.2 Unit Tests — `BlockStateManagerTest`

Source set: `commonTest` (existing file, new cases added).

#### TC-R5a: `blockStateManager_with_null_actor_falls_back_to_direct_repository`

**Type**: Unit
**Requirement**: FR-5, FR-2
**Story**: Story 2

Setup: `BlockStateManager(writeActor = null)` with `InMemoryBlockRepository`.
Call `manager.splitBlock("b1", 3)`.

Assert:
- `_blocks` updated correctly in-memory.
- Repository has 2 blocks.
- No exception thrown; no actor required.

#### TC-R3a: `splitBlock_memory_state_reflects_structural_op_after_completion`

**Type**: Unit
**Requirement**: FR-3
**Story**: Story 2

Setup: `BlockStateManager` (null actor), block "b1" with content "Hello World".
Call `manager.splitBlock("b1", 5)`. `advanceUntilIdle()`.

Assert:
- `manager.blocks.value["page1"]` has exactly 2 entries.
- Block at position 0 has content `"Hello"`.
- Block at position 1 has content `" World"`.
- Focus moved to new block (position 1) at cursor 0.

#### TC-R3b: `mergeBlock_memory_state_reflects_merged_content_after_completion`

**Type**: Unit
**Requirement**: FR-3, FR-6
**Story**: Story 2

Setup: `BlockStateManager` (null actor), two consecutive blocks "b1" ("Hello") and
"b2" (" World"). Call `manager.mergeBlock("b2")`. `advanceUntilIdle()`.

Assert:
- `manager.blocks.value["page1"]` has exactly 1 entry.
- Merged block has content `"Hello World"`.
- Focus is on "b1" at cursor position 5 (end of "Hello").

#### TC-R6a: `handleBackspace_routes_merge_through_actor`

**Type**: Unit
**Requirement**: FR-6
**Story**: Story 2

Setup: `BlockStateManager` with real actor + `InMemoryBlockRepository`. Two blocks "b1"
("Hello"), "b2" (" World", cursor at 0). Call `manager.handleBackspace("b2")`.
`advanceUntilIdle()`.

Assert:
- Repository has 1 block.
- Merged content is `"Hello World"`.
- Merge went through actor (verify via `actor.hasPendingWrites` was `true` before
  `advanceUntilIdle()` completed).

#### TC-R6b: `handleBackspace_routes_delete_empty_block_through_actor`

**Type**: Unit
**Requirement**: FR-6
**Story**: Story 2

Setup: `BlockStateManager` with real actor + `InMemoryBlockRepository`. Two blocks "b1"
("Hello"), "b2" (empty, cursor at 0). Call `manager.handleBackspace("b2")`.
`advanceUntilIdle()`.

Assert:
- Repository has 1 block (only "b1").
- "b2" is deleted.
- Block "b1" content unchanged: `"Hello"`.

---

### 2.3 Integration Tests — Conflict Detection

Source set: `jvmTest` (existing `StelekitViewModelTest` or new
`ConflictDetectionTest`).

#### TC-R4a: `external_file_change_while_structural_op_in_actor_triggers_conflict_dialog`

**Type**: Integration
**Requirement**: FR-4
**Story**: Story 3

Setup:
- `StelekitViewModel` with a real `DatabaseWriteActor` + `DelayedContentBlockRepository`
  (structural op delayed by 300ms to keep `hasPendingWrites == true` long enough).
- Load a page. User triggers `splitBlock` (routes through actor, now in-flight).
- Before actor drains, emit a simulated `externalFileChange` event on the same page path.

Assert:
- `observeExternalFileChanges` emits a conflict event / the conflict dialog state is set.
- The page content is NOT silently replaced.

#### TC-N1a: `external_conflict_does_not_silently_overwrite_pending_db_write`

**Type**: Integration
**Requirement**: NFR-1
**Story**: Story 3

Setup: same as TC-R4a. After conflict is detected:
- User dismisses conflict dialog (picks "keep local").

Assert:
- Repository content matches the actor-committed state, not the external file content.
- No data loss occurs without user notification.

#### TC-N2a: `concurrent_content_writes_and_structural_op_do_not_corrupt_state`

**Type**: Integration
**Requirement**: NFR-2
**Story**: Story 4

Setup: `BlockStateManager` with real actor + `DelayedContentBlockRepository(200ms)`.
Block "b1" with content "original".

Sequence (all launched concurrently without `advanceUntilIdle` between):
1. `applyContentChange("b1", "Hello World", 11)` — queues delayed content write.
2. `splitBlock("b1", 5)` — queues structural op behind content write.

Assert after `advanceUntilIdle()`:
- No exception thrown.
- Block "b1" content is `"Hello"` (structural split used updated content).
- New block content is `" World"`.
- `_blocks` is consistent with repository state.

#### TC-N2b: `fast_keystroke_sequence_does_not_drop_blocks`

**Type**: Integration
**Requirement**: NFR-2
**Story**: Story 4 / Story 5

Setup: `BlockStateManager` + real actor + `InMemoryBlockRepository`.
Simulate 10 rapid `applyContentChange` calls followed immediately by `addNewBlock`.

Assert after `advanceUntilIdle()`:
- Two blocks exist (original + new block from Enter).
- New block persists — not dropped.
- Original block content matches last typed character sequence.

#### TC-N3a: `actor_does_not_introduce_extra_round_trips_on_split`

**Type**: Unit
**Requirement**: NFR-3
**Story**: Story 1 / Story 2

Setup: `InMemoryBlockRepository` instrumented to count `splitBlock` invocations.
Call `manager.splitBlock("b1", 5)` once via `BlockStateManager` with real actor.
`advanceUntilIdle()`.

Assert:
- `blockRepository.splitBlock` is called exactly once (no retry or double-write).
- `actor.hasPendingWrites` returns `false` after `advanceUntilIdle()`.

---

### 2.4 Race Tests — `BlockStateManagerTest` (new section)

Source set: `commonTest`. These four tests map directly to NFR-4 and Story 4.

All four tests share the same test double:

```
DelayedContentBlockRepository
  - wraps InMemoryBlockRepository
  - overrides updateBlockContentOnly: delays by contentDelayMs before calling delegate
  - all other methods delegate directly
```

The delay keeps the actor's content-write in-flight long enough for the structural op
to be dispatched concurrently, reproducing the original race window. With the fix,
the actor serializes them: content write drains first, structural op executes second.

---

#### TC-N4a: `splitBlock_after_pending_content_write_uses_latest_content`

**Test ID**: TC-N4a
**Type**: Race
**Requirement**: NFR-4, FR-1, FR-2
**Story**: Story 4, Task 4b

**`DelayedContentBlockRepository` behavior**:
- `updateBlockContentOnly` sleeps for 500ms (via `delay(500L)`) before calling
  `delegate.updateBlockContentOnly`. This holds the actor busy with the content write
  for 500 virtual ms, during which the split op is queued but cannot execute.

**Scenario**:
1. Set up `BlockStateManager` with real actor + `DelayedContentBlockRepository(500ms)`.
2. Load page "p1" with single block "b1", initial DB content `"original"`.
3. Call `manager.applyContentChange("b1", "Hello World", 11)`.
   - This enqueues `actor.updateBlockContentOnly("b1", "Hello World")` — delayed 500ms.
4. Immediately call `manager.splitBlock("b1", 5)`.
   - This enqueues `actor.splitBlock("b1", 5, newUuid)` behind the content write.
5. Call `advanceUntilIdle()` to drain all virtual time.

**What happens without the fix**:
- `splitBlock` bypasses the actor, calls `blockRepository.splitBlock("b1", 5)` directly.
- DB still has `"original"`, so split produces `"origi"` / `"nal"`.
- When the delayed content write drains, it writes `"Hello World"` to "b1", overwriting
  the structural split result. New block may disappear on next reactive emission.

**What happens with the fix**:
- `splitBlock` is enqueued behind `updateBlockContentOnly` in the actor.
- Content write executes first: `"b1"` DB content becomes `"Hello World"`.
- Split executes second, reading `"Hello World"` from DB: produces `"Hello"` / `" World"`.

**Asserts** (after `advanceUntilIdle()`):
- `manager.blocks.value["p1"]` has exactly 2 blocks.
- Block "b1" has content `"Hello"`.
- New block (uuid != "b1") has content `" World"`.
- Repository reflects the same 2-block state.

**Regression signal**: Run without the fix — test must FAIL (original or wrong split
content). With fix — test must PASS.

---

#### TC-N4b: `addNewBlock_after_pending_content_write_preserves_typed_content`

**Test ID**: TC-N4b
**Type**: Race
**Requirement**: NFR-4, FR-1, FR-2
**Story**: Story 4, Task 4c

**`DelayedContentBlockRepository` behavior**:
- Same as TC-N4a: `updateBlockContentOnly` delayed 500ms.

**Scenario**:
1. Set up `BlockStateManager` with real actor + `DelayedContentBlockRepository(500ms)`.
2. Load page "p1" with single block "b1", initial DB content `"original"`.
3. Call `manager.applyContentChange("b1", "Hello World", 11)`.
   - Enqueues delayed content write for "b1".
4. Immediately call `manager.addNewBlock("b1")` (cursor at end, Enter key).
   - This calls `writeSplitBlock("b1", 11, newUuid)` via actor — queued behind content write.
5. Call `advanceUntilIdle()`.

**What happens without the fix**:
- `addNewBlock` calls `blockRepository.splitBlock("b1", 11)` directly.
- DB has `"original"` (10 chars); cursor position 11 > length, clamped to 10.
- Split produces `"original"` / `""` (or fails with index error).
- Content write then overwrites "b1" with `"Hello World"`, leaving new block but with
  wrong content attribution.

**What happens with the fix**:
- Content write drains first: "b1" becomes `"Hello World"` in DB.
- `splitBlock("b1", 11)` executes: `"Hello World"` / `""` (new empty block at end).
- New block persists.

**Asserts** (after `advanceUntilIdle()`):
- `manager.blocks.value["p1"]` has exactly 2 blocks.
- Block "b1" has content `"Hello World"`.
- New block has empty content `""`.
- New block exists in repository.

**Regression signal**: Run without fix — new block disappears or "b1" reverts to
`"original"`. With fix — PASS.

---

#### TC-N4c: `mergeBlock_after_pending_content_write_uses_latest_content`

**Test ID**: TC-N4c
**Type**: Race
**Requirement**: NFR-4, FR-1, FR-6
**Story**: Story 4, Task 4d

**`DelayedContentBlockRepository` behavior**:
- `updateBlockContentOnly` delayed 500ms, operating on "b2"'s content write.

**Scenario**:
1. Set up `BlockStateManager` with real actor + `DelayedContentBlockRepository(500ms)`.
2. Load page "p1" with two sequential blocks:
   - "b1": content `"Hello"`.
   - "b2": content `"original"` (DB state).
3. Call `manager.applyContentChange("b2", " World", 6)`.
   - Enqueues `actor.updateBlockContentOnly("b2", " World")` — delayed 500ms.
4. Immediately call `manager.mergeBlock("b2")` (cursor at position 0 of "b2").
   - Enqueues `actor.mergeBlocks("b1", "b2", "")` behind the content write.
5. `advanceUntilIdle()`.

**What happens without the fix**:
- `mergeBlock` calls `blockRepository.mergeBlocks("b1", "b2", "")` directly.
- "b2" DB content is still `"original"`.
- Merge produces `"Hello" + "original"` = `"Hellooriginal"`.
- Content write then fires but "b2" is already deleted — write may fail silently or write
  to a deleted block UUID (orphan write).

**What happens with the fix**:
- Content write for "b2" drains first: DB "b2" content becomes `" World"`.
- `mergeBlocks("b1", "b2", "")` executes: `"Hello"` + `" World"` = `"Hello World"`.
- "b2" deleted from DB.

**Asserts** (after `advanceUntilIdle()`):
- `manager.blocks.value["p1"]` has exactly 1 block.
- Remaining block "b1" has content `"Hello World"`.
- "b2" does not exist in repository.

**Regression signal**: Without fix — merged content is `"Hellooriginal"`. With fix — PASS.

---

#### TC-N4d: `handleBackspace_after_pending_content_write_merges_latest_content`

**Test ID**: TC-N4d
**Type**: Race
**Requirement**: NFR-4, FR-1, FR-6
**Story**: Story 4, Task 4e

**`DelayedContentBlockRepository` behavior**:
- Same as TC-N4c: `updateBlockContentOnly` delayed 500ms, applied to "b2".

**Scenario**:
1. Set up `BlockStateManager` with real actor + `DelayedContentBlockRepository(500ms)`.
2. Load page "p1" with two sequential blocks:
   - "b1": content `"Hello"`.
   - "b2": content `"original"` in DB; live in-memory content after typing is `" World"`.
3. Set cursor on "b2" at position 0.
4. Call `manager.applyContentChange("b2", " World", 6)`.
   - Enqueues delayed content write for "b2".
5. Immediately call `manager.handleBackspace("b2")` with cursor at position 0.
   - "b2" is non-empty in `_blocks` (live: `" World"`), so the merge branch is taken.
   - Enqueues `actor.mergeBlocks("b1", "b2", "")` behind the content write.
6. `advanceUntilIdle()`.

**What happens without the fix**:
- `handleBackspace` calls `blockRepository.mergeBlocks("b1", "b2", "")` directly.
- "b2" DB content is still `"original"`.
- Merge produces `"Hello" + "original"` = `"Hellooriginal"`.

**What happens with the fix**:
- Content write for "b2" drains first: DB "b2" becomes `" World"`.
- `mergeBlocks("b1", "b2", "")` executes on updated content: result is `"Hello World"`.

**Asserts** (after `advanceUntilIdle()`):
- `manager.blocks.value["p1"]` has exactly 1 block.
- Remaining block "b1" has content `"Hello World"`.
- "b2" does not exist in repository.

**Regression signal**: Without fix — merged content is `"Hellooriginal"`. With fix — PASS.

---

## 3. Test Suite Summary

| Type | Test IDs | Count |
|---|---|---|
| Unit | TC-R2a, TC-R2b, TC-R2c, TC-R5a, TC-R3a, TC-R3b, TC-R6a, TC-R6b, TC-N3a | 9 |
| Integration | TC-R4a, TC-N1a, TC-N2a, TC-N2b | 4 |
| Race | TC-N4a, TC-N4b, TC-N4c, TC-N4d | 4 |
| **Total** | | **17** |

Existing passing tests are covered by Story 5 regression verification (not new tests —
the ~40 existing `BlockStateManagerTest` cases must pass unmodified).

---

## 4. `DelayedContentBlockRepository` Specification

Used by all four race tests (TC-N4a–TC-N4d). Full behavioral contract:

```
class DelayedContentBlockRepository(
    delegate: InMemoryBlockRepository,
    contentDelayMs: Long = 500L
) : BlockRepository by delegate {

    override suspend fun updateBlockContentOnly(
        blockUuid: String,
        content: String,
    ): Either<DomainError, Unit> {
        delay(contentDelayMs)          // holds actor busy; structural op waits in queue
        return delegate.updateBlockContentOnly(blockUuid, content)
    }
    // All other methods: delegate directly (no delay on splitBlock, mergeBlocks, etc.)
}
```

Key design decisions:
- Only `updateBlockContentOnly` is delayed — this precisely simulates a slow content
  write holding the actor's FIFO queue, while structural ops (splitBlock, mergeBlocks)
  run at normal speed once they dequeue.
- `splitBlock` and `mergeBlocks` on the delegate are NOT delayed — the race is between
  a slow content write and an immediately-ready structural op, not a slow structural op.
- `UnconfinedTestDispatcher` + `advanceUntilIdle()` gives fully deterministic scheduling;
  no real wall-clock delays, no test flakiness.
- Uses `@OptIn(DirectRepositoryWrite::class)` on the override because
  `updateBlockContentOnly` is annotated `@DirectSqlWrite` on the real repository.

---

## 5. Implementation Readiness Gate

### Gate Criterion 1: Requirements Coverage

Every FR and NFR has at least one test case.

| Requirement | Covered | Test(s) |
|---|---|---|
| FR-1 | YES | TC-N4a, TC-N4b, TC-N4c, TC-N4d |
| FR-2 | YES | TC-R2a, TC-R2b, TC-R2c, TC-R5a |
| FR-3 | YES | TC-R3a, TC-R3b |
| FR-4 | YES | TC-R4a |
| FR-5 | YES | TC-R5a |
| FR-6 | YES | TC-R6a, TC-R6b, TC-N4c, TC-N4d |
| NFR-1 | YES | TC-N1a |
| NFR-2 | YES | TC-N2a, TC-N2b |
| NFR-3 | YES | TC-N3a |
| NFR-4 | YES | TC-N4a, TC-N4b, TC-N4c, TC-N4d |

**Result**: PASS — 10/10 requirements covered.

---

### Gate Criterion 2: Plan Completeness

Every story must have defined acceptance criteria.

| Story | Acceptance Criteria Present |
|---|---|
| Story 1 (Actor methods) | YES — 3 criteria defined: compiles, returns `Either<DomainError, Block>`, existing tests pass |
| Story 2 (BlockStateManager routing) | YES — 3 criteria: no direct calls remain, existing tests pass, null-actor fallback works |
| Story 3 (Conflict detection) | YES — 3 criteria: manual conflict test, `ciCheck` passes, existing `StelekitViewModelTest` passes |
| Story 4 (Race tests) | YES — 3 criteria: tests fail before fix / pass after, use `DelayedContentBlockRepository` + real actor, in `commonTest` |
| Story 5 (Regression) | YES — 3 criteria: ~40 existing `BlockStateManagerTest` tests pass, Android unit tests pass, `ciCheck` passes |

**Result**: PASS — all 5 stories have acceptance criteria.

---

### Gate Criterion 3: Adversarial Review Verdict

The adversarial review verdict must be CLEAN or CONCERNS (not BLOCKED).

Adversarial review verdict: **CONCERNS**

All 3 findings reviewed:
- Finding 1 (LOW): INVALID after verification — no action required.
- Finding 2 (MEDIUM): VALID — plan patched (AtomicInt counter replaces `Channel.isEmpty`
  check). Patch applied in Story 3, Task 3a.
- Finding 3 (LOW): VALID but mitigated by existing `isEmpty()` guard in `handleBackspace`.
  No unresolved risk.

The adversarial document itself notes: "After the Story 3 patch: CLEAN for implementation."
The overall document verdict is CONCERNS (pre-patch), effectively CLEAN after patch.

**Result**: PASS — verdict is CONCERNS with all items resolved; no BLOCKED findings.

---

### Gate Criterion 4: No Unresolved BLOCKED Findings

Scanning adversarial-review.md:
- Finding 1: Status = INVALID. Not BLOCKED.
- Finding 2: Status = VALID. Patch applied in plan. Not BLOCKED.
- Finding 3: Status = VALID but mitigated. Not BLOCKED.

Zero findings with unresolved BLOCKED status.

**Result**: PASS — 0 unresolved BLOCKED findings.

---

## 6. Overall Gate Verdict

| Criterion | Result |
|---|---|
| Requirements coverage (10/10) | PASS |
| Plan completeness (5/5 stories) | PASS |
| Adversarial verdict (CONCERNS, all resolved) | PASS |
| No unresolved BLOCKED findings | PASS |

**GATE VERDICT: PASS**

The implementation plan is ready to proceed to Phase 5 (implementation). Begin with a
fresh session; load requirements.md, plan.md, and this validation.md. Implement in story
order: Story 1 → Story 2 → (Story 3 + Story 4 in parallel) → Story 5 verification.
