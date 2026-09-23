# Validation Plan: Block Reorder Persistence + Copy-Paste

**Phase**: SDD Phase 4 — Validation
**Date**: 2026-06-29
**Status**: READY FOR IMPLEMENTATION

---

## Summary

| Dimension | Count |
|---|---|
| Unit tests | 26 |
| Integration tests | 5 |
| Manual tests | 10 |
| **Total** | **41** |
| Requirements covered | 6/6 (R1, R2, R3, R4 + 2 regression success criteria) |
| Readiness gate | **PASS** |

---

## Requirement-to-Test Traceability

| Requirement | Test IDs |
|---|---|
| R1: Reorder persistence to disk | TC-R1-U01 – TC-R1-U06, TC-R1-I01, TC-R1-M01, TC-R1-M02 |
| R2: Valid fractional optimistic position | TC-R2-U01 – TC-R2-U04, TC-R2-M01 |
| R3: Block copy-paste in selection mode | TC-R3-U01 – TC-R3-U16, TC-R3-I01 – TC-R3-I03, TC-R3-M01 – TC-R3-M05 |
| R4: Bulk delete regression | TC-R4-U01 – TC-R4-U05, TC-R4-I01, TC-R4-M01 |
| SC-5: Drag-drop reorder regression (success criterion 6) | TC-DD-M01 |
| Adversarial BUGs 1–5 (patched in plan) | TC-R3-U09, TC-R3-U10, TC-R3-U11, TC-R3-U12, TC-R3-U13, TC-R3-U14, TC-R3-U15 |

---

## Epic 1: Reorder Persistence (R1)

**Target file**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerReorderTest.kt` (new)

### TC-R1-U01: moveBlockUp triggers queueDiskSave
- **Type**: Unit
- **Setup**: 3-block in-memory page, fake `GraphWriter` recording `queueDiskSave` calls
- **Action**: Call `moveBlockUp` on the middle block
- **Assert**: Fake GraphWriter records exactly one `queueDiskSave` call for the page UUID
- **Maps to plan**: Task 1.2

### TC-R1-U02: moveBlockDown triggers queueDiskSave
- **Type**: Unit
- **Setup**: 3-block in-memory page, fake `GraphWriter`
- **Action**: Call `moveBlockDown` on the middle block
- **Assert**: `queueDiskSave` called once for page UUID
- **Maps to plan**: Task 1.2

### TC-R1-U03: indentBlock triggers queueDiskSave
- **Type**: Unit
- **Setup**: 2-block page (parent + sibling), fake `GraphWriter`
- **Action**: Call `indentBlock` on the second block
- **Assert**: `queueDiskSave` called once for page UUID
- **Maps to plan**: Task 1.2

### TC-R1-U04: outdentBlock triggers queueDiskSave
- **Type**: Unit
- **Setup**: Page with nested block, fake `GraphWriter`
- **Action**: Call `outdentBlock` on the child block
- **Assert**: `queueDiskSave` called once for page UUID
- **Maps to plan**: Task 1.2

### TC-R1-U05: moveBlockUp reorders _blocks correctly
- **Type**: Unit
- **Setup**: 3 blocks at positions "a0", "a1", "a2"
- **Action**: `moveBlockUp` on block at "a1"
- **Assert**: In-memory `_blocks` list has the moved block before the block that was previously at "a0"
- **Maps to plan**: Task 1.2

### TC-R1-U06: moveBlockDown reorders _blocks correctly
- **Type**: Unit
- **Setup**: 3 blocks at positions "a0", "a1", "a2"
- **Action**: `moveBlockDown` on block at "a1"
- **Assert**: In-memory `_blocks` list has the moved block after the block at "a2"
- **Maps to plan**: Task 1.2

### TC-R1-I01: Reorder survives GraphLoader reload
- **Type**: Integration (businessTest — in-memory SQLite + real GraphWriter/GraphLoader)
- **Setup**: Load page with 3 blocks into SQLDELIGHT backend, call `moveBlockUp`
- **Action**: Flush the debounced `queueDiskSave`, then recreate `GraphLoader` and reload the page
- **Assert**: Reloaded block order in `_blocks` matches the reordered order (not original)
- **Maps to plan**: Task 1.2 (regression anchor)

### TC-R1-M01: Alt+Up/Alt+Down persists across page close
- **Type**: Manual
- **Procedure**:
  1. Open a page with 3+ blocks
  2. Focus a block, press Alt+Down to move it down one position
  3. Navigate away from the page (triggers save flush)
  4. Navigate back to the page
- **Expected**: Block is in the new position, not the original position
- **Maps to plan**: Task 1.1, R1 success criterion 1

### TC-R1-M02: Tab/Shift+Tab hierarchy persists across page close
- **Type**: Manual
- **Procedure**:
  1. Open a page with 2+ sibling blocks
  2. Focus the second block, press Tab to indent it (becomes child of first)
  3. Navigate away then back
- **Expected**: Indented block remains indented; no reversion to sibling level
- **Maps to plan**: Task 1.1, R1 success criterion 2

---

## Epic 2: Optimistic Block Position (R2)

**Target file**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerPositionTest.kt` (new)

### TC-R2-U01: addNewBlock produces in-range optimistic position
- **Type**: Unit
- **Setup**: Page with blocks at positions `"a0"` and `"a2"`, cursor on the `"a0"` block
- **Action**: Call `addNewBlock`
- **Assert**: The optimistic block's position satisfies `"a0" < position < "a2"` (lexicographic comparison, which FractionalIndexing guarantees is sort-correct)
- **Regression**: Confirms fix for string-concatenation bug (`"a0" + 1 = "a01"` which sorts after `"a0z"`)
- **Maps to plan**: Task 2.1, Task 2.3

### TC-R2-U02: addNewBlock at end of list produces valid position
- **Type**: Unit
- **Setup**: Page with blocks at positions `"a0"`, `"a1"` (no following sibling), cursor on `"a1"` block
- **Action**: Call `addNewBlock`
- **Assert**: Optimistic block's position satisfies `"a1" < position` and is a valid FractionalIndexing key (non-empty, alphanumeric)
- **Maps to plan**: Task 2.1, Task 2.3

### TC-R2-U03: splitBlock produces in-range optimistic position
- **Type**: Unit
- **Setup**: Page with blocks at positions `"a0"` and `"a2"`, splitting the `"a0"` block
- **Action**: Call `splitBlock` at a mid-content cursor position
- **Assert**: Optimistic new block's position satisfies `"a0" < position < "a2"`
- **Maps to plan**: Task 2.2, Task 2.3

### TC-R2-U04: splitBlock at end of list produces valid position
- **Type**: Unit
- **Setup**: Page with single block at position `"a0"`, splitting it
- **Action**: Call `splitBlock`
- **Assert**: Optimistic position satisfies `"a0" < position`
- **Maps to plan**: Task 2.2, Task 2.3

### TC-R2-M01: Enter key places new block without visual snap
- **Type**: Manual
- **Procedure**:
  1. Open a page with 3+ blocks
  2. Click to focus the first block
  3. Press Enter to create a new block
- **Expected**: New block appears immediately between first and second block with no visible repositioning "snap" after DB write returns
- **Maps to plan**: Task 2.1/2.2, R2 success criterion 3

---

## Epic 3: Block Copy-Paste (R3)

### Copy-path Unit Tests
**Target file**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/clipboard/CopySelectedBlocksTest.kt` (new)

#### TC-R3-U01: withBlocks factory creates correct clipboard
- **Type**: Unit
- **Action**: `BlockClipboard().withBlocks(listOf(block1, block2), COPY, "graph-uuid")`
- **Assert**: Clipboard has 2 entries; each entry has `operation = COPY` and `sourceGraphUuid = "graph-uuid"`
- **Maps to plan**: Task 3.1

#### TC-R3-U02: copySelectedBlocks flat selection populates clipboard
- **Type**: Unit
- **Setup**: Page with 3 flat blocks, blocks 1 and 3 selected
- **Action**: `copySelectedBlocks("graph-uuid")`
- **Assert**: `_blockClipboard.value` contains entries for blocks 1 and 3; block 2 not included
- **Maps to plan**: Task 3.3

#### TC-R3-U03: copySelectedBlocks includes full subtree of selected root
- **Type**: Unit
- **Setup**: Parent block with 2 children; parent is selected, children are not
- **Action**: `copySelectedBlocks("graph-uuid")`
- **Assert**: Clipboard contains parent block AND both children (3 entries total)
- **Maps to plan**: Task 3.3

#### TC-R3-U04: copySelectedBlocks subtree-dedup excludes children when ancestor selected
- **Type**: Unit
- **Setup**: Parent block with child block; both parent and child explicitly selected
- **Action**: `copySelectedBlocks("graph-uuid")`
- **Assert**: Clipboard contains only 2 blocks (parent + child), child is NOT duplicated
- **Maps to plan**: Task 3.3 (subtreeDedup)

#### TC-R3-U05: copySelectedBlocks empty selection is no-op
- **Type**: Unit
- **Setup**: No blocks selected
- **Action**: `copySelectedBlocks("graph-uuid")`
- **Assert**: `_blockClipboard.value` is unchanged (empty if it was empty)
- **Maps to plan**: Task 3.3

#### TC-R3-U06: copySelectedBlocks always uses operation=COPY
- **Type**: Unit (BUG 3 guard)
- **Setup**: Any non-empty selection
- **Action**: `copySelectedBlocks("graph-uuid")`
- **Assert**: All clipboard entries have `operation == ClipboardOperation.COPY`; no entry has `CUT`
- **Maps to plan**: Task 3.3 (BUG 3 fix — CUT removed from scope)

### Paste-path Unit Tests
**Target file**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/clipboard/PasteBlocksTest.kt` (new)

#### TC-R3-U07: pasteBlocks assigns new UUIDs to all pasted blocks
- **Type**: Unit
- **Setup**: Clipboard with 2 blocks (UUIDs "uuid-A", "uuid-B"), paste after a focused block
- **Action**: `pasteBlocks(focusedBlockUuid)`
- **Assert**: Blocks written to the fake repository have UUIDs different from "uuid-A" and "uuid-B"; no duplicates in new UUID set
- **Maps to plan**: Task 3.5

#### TC-R3-U08: pasteBlocks inserts root blocks after afterBlock in position order
- **Type**: Unit
- **Setup**: Page with blocks at "a0", "a2"; clipboard has 2 flat root blocks; paste after "a0"
- **Action**: `pasteBlocks(blockAtA0.uuid)`
- **Assert**: Both pasted blocks have positions in range `("a0", "a2")`; their positions are strictly increasing (first pasted < second pasted)
- **Maps to plan**: Task 3.4 (step 5), Task 3.5

#### TC-R3-U09: pasteBlocks adjusts level for deeply-nested clipboard blocks
- **Type**: Unit (BUG 2 fix guard)
- **Setup**: Clipboard with a block at level 3 and its child at level 4 (minClipLevel=3); paste after a block at level 1
- **Action**: `pasteBlocks(level1Block.uuid)`
- **Assert**: Pasted root block has level 1 (= 3 - 3 + 1); pasted child has level 2 (= 4 - 3 + 1)
- **Maps to plan**: Task 3.4 (step 8), Task 3.5 (BUG 2 fix)

#### TC-R3-U10: pasteBlocks root level equals afterBlock level
- **Type**: Unit (BUG 2 fix guard)
- **Setup**: Clipboard with a page-level block (level 0); paste after a block at level 2
- **Action**: `pasteBlocks(level2Block.uuid)`
- **Assert**: Pasted root block has level 2 (= 0 - 0 + 2)
- **Maps to plan**: Task 3.5 (BUG 2 fix)

#### TC-R3-U11: pasteBlocks repairs right-sibling leftUuid after insertion
- **Type**: Unit (BUG 1 fix guard)
- **Setup**: Page with chain A → B → C (B.leftUuid=A.uuid, C.leftUuid=B.uuid); clipboard with 1 block; paste after B
- **Action**: `pasteBlocks(blockB.uuid)`
- **Assert**: After paste, block C (existingRightSibling) has its `leftUuid` updated to the new UUID of the last pasted root block (not still pointing to B)
- **Maps to plan**: Task 3.5 (BUG 1 fix — existingRightSibling.copy(leftUuid = lastPastedRootNewUuid))

#### TC-R3-U12: pasteBlocks adds new UUIDs to pendingNewBlockUuids before write
- **Type**: Unit (BUG 4 fix guard)
- **Setup**: Spy on `pendingNewBlockUuids`; clipboard with 2 blocks; async write that suspends
- **Action**: Begin `pasteBlocks(...)`, inspect `pendingNewBlockUuids` while DB write is suspended
- **Assert**: All N new UUIDs are present in `pendingNewBlockUuids` while the write is in flight
- **Maps to plan**: Task 3.5 (BUG 4 fix — `pendingNewBlockUuids.update { it + newUuids }`)

#### TC-R3-U13: pasteBlocks removes new UUIDs from pendingNewBlockUuids after write
- **Type**: Unit (BUG 4 fix guard)
- **Action**: Complete `pasteBlocks(...)` successfully
- **Assert**: `pendingNewBlockUuids` does not contain any of the new pasted UUIDs after the method returns
- **Maps to plan**: Task 3.5 (BUG 4 fix — `pendingNewBlockUuids.update { it - newUuids }`)

#### TC-R3-U14: pasteBlocks aborts on saveBlocks failure — no disk save, no undo
- **Type**: Unit (BUG 5 fix guard)
- **Setup**: Fake `BlockRepository.saveBlocks` returns `Either.Left(WriteFailed("disk full"))`; spy on `GraphWriter.queueDiskSave` and `record()`
- **Action**: `pasteBlocks(focusedBlockUuid)`
- **Assert**: `queueDiskSave` NOT called; `record()` NOT called; function returns without updating `_blocks`
- **Maps to plan**: Task 3.5 (BUG 5 fix — `if (!success) return@launch`)

#### TC-R3-U15: pasteBlocks preserves internal parent/child tree
- **Type**: Unit
- **Setup**: Clipboard with a root block (uuid-P) and its two children (uuid-C1 parent=uuid-P, uuid-C2 parent=uuid-P)
- **Action**: `pasteBlocks(focusedBlockUuid)`
- **Assert**: In the DB writes, the two child blocks have `parentUuid` equal to the new remapped UUID of the root (not uuid-P, not null)
- **Maps to plan**: Task 3.4 (step 4), Task 3.5

#### TC-R3-U16: pasteBlocks calls queueDiskSave on success
- **Type**: Unit
- **Setup**: Fake GraphWriter spy; clipboard non-empty; saveBlocks succeeds
- **Action**: `pasteBlocks(focusedBlockUuid)`
- **Assert**: `queueDiskSave` called once for the page UUID
- **Maps to plan**: Task 3.5 (step 7)

### Paste Integration Tests
**Target file**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/clipboard/PasteBlocksIntegrationTest.kt` (new — SQLDELIGHT backend)

#### TC-R3-I01: Copy then paste — blocks present in DB with correct parent
- **Type**: Integration (in-memory SQLite)
- **Setup**: Create a page with 3 blocks in SQLDELIGHT backend; copy blocks 1 and 2
- **Action**: `pasteBlocks(block3.uuid)`
- **Assert**: DB contains 5 total blocks for the page; pasted blocks have `page_uuid` matching the page, their `parent_uuid` is null (same level as block3)
- **Maps to plan**: Tasks 3.3, 3.5, R3

#### TC-R3-I02: leftUuid chain integrity after paste — no duplicate left_uuid
- **Type**: Integration (in-memory SQLite — BUG 1 regression)
- **Setup**: Page A → B → C; clipboard with 1 block; paste after B
- **Action**: `pasteBlocks(blockB.uuid)`; then call `selectBlockByLeftUuid(blockB.uuid)` via repository
- **Assert**: Exactly one block is returned by `selectBlockByLeftUuid` — no `IllegalStateException` from multiple rows
- **Maps to plan**: Task 3.5 (BUG 1 fix)

#### TC-R3-I03: Level field in DB matches adjusted level after paste
- **Type**: Integration (in-memory SQLite — BUG 2 regression)
- **Setup**: Copy a block at level 2; paste after a block at level 0
- **Action**: `pasteBlocks(level0Block.uuid)`, query the pasted block from DB
- **Assert**: DB `level` column for the pasted block equals 0 (adjusted), not 2 (unadjusted)
- **Maps to plan**: Task 3.5 (BUG 2 fix)

### Copy-Paste Manual Tests

#### TC-R3-M01: Copy button appears in selection mode toolbar
- **Type**: Manual
- **Procedure**:
  1. Long-press a block to enter selection mode
  2. Observe the toolbar
- **Expected**: A "Copy" icon button (ContentCopy icon) is visible next to the Delete button
- **Maps to plan**: Task 3.7

#### TC-R3-M02: Ctrl+C in selection mode copies blocks
- **Type**: Manual (Desktop)
- **Procedure**:
  1. Enter selection mode (Ctrl+A or long-press)
  2. Select 2 blocks
  3. Press Ctrl+C
- **Expected**: "Copied 2 blocks" transient notification appears (or equivalent feedback)
- **Maps to plan**: Task 3.9, R3 success criterion

#### TC-R3-M03: Ctrl+V pastes blocks after focused block
- **Type**: Manual (Desktop)
- **Procedure**:
  1. Copy 2 blocks via Ctrl+C (after selection)
  2. Click on a different block (exit selection mode, establish focus)
  3. Press Ctrl+V
- **Expected**: The 2 copied blocks appear immediately after the focused block; original blocks are unchanged
- **Maps to plan**: Task 3.9, R3 success criterion 4

#### TC-R3-M04: Paste multi-level subtree preserves nesting
- **Type**: Manual
- **Procedure**:
  1. Create a parent block with 2 indented children
  2. Select the parent block
  3. Ctrl+C to copy (includes subtree)
  4. Focus a different block, Ctrl+V
- **Expected**: Pasted tree has parent + 2 children at the same relative indentation; children are visually indented under the pasted parent
- **Maps to plan**: Tasks 3.3 (subtree collect), 3.5 (UUID remapping), R3

#### TC-R3-M05: Paste multiple times from same clipboard
- **Type**: Manual
- **Procedure**:
  1. Copy 1 block
  2. Focus block A, Ctrl+V
  3. Focus block B (different from A), Ctrl+V
- **Expected**: Two separate paste operations each produce a copy; clipboard remains populated after the first paste; original block unchanged
- **Maps to plan**: Task 3.5 (COPY semantics — clipboard not cleared after paste)

---

## Epic 4: Bulk Delete Regression (R4)

**Target file**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerDeleteTest.kt` (new)

### TC-R4-U01: deleteSelectedBlocks removes selected blocks from DB
- **Type**: Unit
- **Setup**: 3-block page, blocks 1 and 3 selected
- **Action**: `deleteSelectedBlocks()`
- **Assert**: Fake repository receives a delete call for exactly blocks 1 and 3; block 2 is not deleted
- **Maps to plan**: Task 1.2 (R4 regression), R4

### TC-R4-U02: deleteSelectedBlocks removes subtrees
- **Type**: Unit
- **Setup**: Parent block with 2 children; parent is selected
- **Action**: `deleteSelectedBlocks()`
- **Assert**: Fake repository delete call includes parent and both children (3 total)
- **Maps to plan**: R4

### TC-R4-U03: deleteSelectedBlocks repairs linked-list chain after deletion
- **Type**: Unit
- **Setup**: Chain A → B → C; block B is selected for deletion
- **Action**: `deleteSelectedBlocks()`
- **Assert**: Block C's `leftUuid` is updated to A's UUID (chain repaired: A → C)
- **Maps to plan**: R4

### TC-R4-U04: deleteSelectedBlocks calls queueDiskSave
- **Type**: Unit
- **Setup**: Fake GraphWriter spy
- **Action**: `deleteSelectedBlocks()` with non-empty selection
- **Assert**: `queueDiskSave` called once for the page UUID
- **Maps to plan**: R4

### TC-R4-U05: deleteSelectedBlocks records undo entry
- **Type**: Unit
- **Setup**: Spy on `record(undo, redo)`
- **Action**: `deleteSelectedBlocks()` with non-empty selection
- **Assert**: `record()` called once; calling `undo()` from the recorded entry restores the deleted blocks in `_blocks`
- **Maps to plan**: R4

### TC-R4-I01: Delete then undo restores blocks in DB
- **Type**: Integration (in-memory SQLite)
- **Setup**: 3-block page with SQLDELIGHT backend; select all blocks
- **Action**: `deleteSelectedBlocks()`, wait for write, then call `undo()`
- **Assert**: Page block count returns to 3 after undo
- **Maps to plan**: R4

### TC-R4-M01: Ctrl+A + Delete removes all blocks
- **Type**: Manual
- **Procedure**:
  1. Open a page with 3+ blocks
  2. Press Ctrl+A to select all
  3. Press Delete (or tap Delete toolbar button)
- **Expected**: All blocks removed; page is empty (or shows empty state)
- **Maps to plan**: R4 success criterion 5

---

## Drag-Drop Regression

### TC-DD-M01: Drag-drop reorder persists across restart
- **Type**: Manual
- **Procedure**:
  1. Open a page with 3+ blocks
  2. Drag a block to a new position using the drag handle
  3. Navigate away (triggers save flush)
  4. Navigate back
- **Expected**: Drag-drop reorder is preserved; no reversion to original order
- **Maps to plan**: Out-of-scope for R1 fix (drag-drop already calls queueDiskSave); success criterion 6

---

## Adversarial Review Patch Confirmation

The adversarial review (`adversarial-review.md`) returned **BLOCKED** citing 5 bugs. All 5 are patched in `plan.md`:

| Bug | Severity | Patch Location in plan.md |
|---|---|---|
| BUG 1: leftUuid chain corruption after paste | CRITICAL | Task 3.5 — `existingRightSibling` captured pre-write; `saveBlocksUpdate(existingRightSibling.copy(leftUuid = lastPastedRootNewUuid))` called inside same actor block. Comment: `// BUG 1 fix` |
| BUG 2: Level not adjusted relative to insertion point | CRITICAL | Task 3.4 (step 8) — algorithm defined: `level = original.level - minClipLevel + insertionLevel`. Task 3.5 — `val minClipLevel = clipBlocks.minOf { it.level }` + `level = original.level - minClipLevel + insertionLevel`. Comment: `// BUG 2 fix` |
| BUG 3: CUT paste never deletes originals | CRITICAL | Task 3.3 — `ClipboardOperation.COPY` hardcoded; CUT branch absent. Task 3.5 — `// BUG 3 fix: CUT removed from scope; isCut branch deleted`. Comment in summary: "CUT removed from scope" |
| BUG 4: pendingNewBlockUuids not tracked | HIGH | Task 3.5 — `pendingNewBlockUuids.update { it + newUuids }` before write; `pendingNewBlockUuids.update { it - newUuids }` after write. Comment: `// BUG 4 fix` |
| BUG 5: saveBlocks error result silently discarded | HIGH | Task 3.5 — `var success = true`; both `writeActor` paths check `.onLeft { ...; success = false }`; `if (!success) return@launch`. Comment: `// BUG 5 fix` |

Medium-severity issues from adversarial review:
- **ISSUE 6** (`withBlocks` as instance method instead of companion object): Plan still defines it as an instance method. Not a correctness bug; causes a throwaway allocation at call site. Flag for cleanup in code review.
- **ISSUE 7** (ContentCopy icon Bazel dependency): Plan correctly adds the import. Bazel BUILD file verification is required before Task 3.7 coding begins — confirm `material-icons-extended` is declared as a dep on `//kmp:jvm_app` and `//kmp:android_app` targets. Not a blocker but a pre-T3.7 gate.

---

## Task Acceptance Criteria Audit

Every plan task has implementation-level acceptance criteria (before/after code diffs or explicit algorithmic contracts). Summary:

| Task | Criteria type | Status |
|---|---|---|
| T1.1 | Manual procedure with pass/fail criteria | Clear ✓ |
| T1.2 | Test file + assertion list | Clear ✓ |
| T2.1 | Before/after code diff; position range validated by TC-R2-U01 | Clear ✓ |
| T2.2 | Before/after code diff; position range validated by TC-R2-U03 | Clear ✓ |
| T2.3 | Test file + assertion (lexicographic range check) | Clear ✓ |
| T3.1 | Code snippet; exercised by TC-R3-U01 | Clear ✓ |
| T3.2 | Field declaration; exercised by TC-R3-U02 onwards | Clear ✓ |
| T3.3 | 8-step algorithm + full code; validated by TC-R3-U02 – TC-R3-U06 | Clear ✓ |
| T3.4 | 8-step conceptual contract | Clear ✓ |
| T3.5 | Full implementation; BUG fixes labelled with comments | Clear ✓ |
| T3.6 | Interface signatures listed | Clear ✓ |
| T3.7 | Before/after UI code diff; validated by TC-R3-M01 | Clear ✓ |
| T3.8 | Wire-up call shown | Clear ✓ |
| T3.9 | Keyboard handler branch shown with guard conditions | Clear ✓ |

---

## Open Questions Gate

| Flag | Resolution | Blocking? |
|---|---|---|
| Flag 1: R1 may already be fixed | Run Task 1.1 smoke test before E1 coding; E2/E3 unblocked regardless | No |
| Flag 2: graphUuid supply mechanism | Option C selected (empty string, tech debt noted for cross-graph paste) | No |
| Flag 3: saveBlocks write path opt-in | Verify `RestrictedDatabaseQueries` has `saveBlocks` stub before T3.5; codebase verification step | No |
| Flag 4: BlockSelectionPort not consumed by UI | Acknowledged; no cast or delegation issue | No |
| Issue 7: ContentCopy Bazel dep | Verify BUILD file before T3.7; low risk | No (pre-T3.7 gate only) |

No open questions remain that would block implementation.

---

## Readiness Gate Verdict

### Criterion 1: Every requirement has test coverage
- R1 (reorder persistence): 6 unit + 1 integration + 2 manual = **9 tests** ✓
- R2 (optimistic position): 4 unit + 1 manual = **5 tests** ✓
- R3 (copy-paste): 16 unit + 3 integration + 5 manual = **24 tests** ✓
- R4 (bulk delete regression): 5 unit + 1 integration + 1 manual = **7 tests** ✓
- SC-6 (drag-drop regression): 1 manual ✓

**Result: 6/6 requirements covered** ✓

### Criterion 2: Every task has clear acceptance criteria
All 14 tasks have implementation-level specifications (code diffs or algorithmic contracts) sufficient to determine when done. ✓

### Criterion 3: All 5 adversarial-review bugs patched
BUGs 1–5 all have explicit patches with inline comments in plan.md Task 3.5. ✓

### Criterion 4: No open questions blocking implementation
All flag decisions resolved. No unknowns require external input before coding begins. ✓

## VERDICT: PASS

Implementation may begin. Recommended execution order per plan.md:
- E2 (Tasks 2.1–2.3) immediately — independent, isolated, low-risk
- E1 T1.1 smoke test in parallel — gates T1.2 only
- E3 (Tasks 3.1–3.9) after E2 completes — medium risk, all correctness issues resolved in plan
