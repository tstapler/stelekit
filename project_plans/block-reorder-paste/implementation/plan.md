# Implementation Plan: Block Reorder Persistence + Copy-Paste

## Summary

3 epics, 14 tasks total.

| Epic | Requirement | Tasks | Risk |
|------|-------------|-------|------|
| E1: Disk persistence | R1 | 2 | LOW — code may already be fixed (see Flag 1) |
| E2: Fix optimistic position | R2 | 3 | LOW — isolated two-line fix with test |
| E3: Block copy-paste | R3 | 9 | MEDIUM — new state machine, UUID remapping |

---

## Epic 1: Fix Disk Persistence (R1)

### FLAG 1 (critical): R1 may already be fixed

`refreshBlocksForPage` (BlockStateManager.kt L940–953) already calls `queueDiskSave(pageUuid)` at **line 952**. All four reorder functions (`indentBlock` L955, `outdentBlock` L967, `moveBlockUp` L979, `moveBlockDown` L991) already call `refreshBlocksForPage`. The call chain is complete:

```
moveBlockUp → writeMoveBlockUp (SQLite) → refreshBlocksForPage → queueDiskSave ✓
```

The requirements.md description of the bug matches an earlier version of the code. **Before implementing E1, run the end-to-end verification test** (Alt+Up to reorder, kill app, reopen — order must survive). If the test passes, E1 reduces to Task 1.2 only.

### Task 1.1 — End-to-end smoke test (precondition gate)

**If the smoke test PASSES**, skip Task 1.2 entirely and move on.

Manual test procedure:
1. Open a page with 3+ blocks
2. Press Alt+Up / Alt+Down to reorder a block
3. Press Tab / Shift+Tab to indent/outdent a block
4. Close the page (navigate away)
5. Reopen the page
6. Confirm order and hierarchy match what was saved

Expected: order survives restart. If it does, R1 is already fixed.

### Task 1.2 — Regression test for reorder persistence

**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerReorderTest.kt` (new)

Write a test that:
- Creates a 3-block in-memory page
- Calls `moveBlockUp`, `moveBlockDown`, `indentBlock`, `outdentBlock`
- After each call, asserts that `queueDiskSave` was invoked (use a fake `GraphWriter` that records calls)
- Confirms the block ordering in `_blocks` matches the DB ordering after refresh

This test protects against future regressions if `refreshBlocksForPage` is ever refactored to not call `queueDiskSave`.

---

## Epic 2: Fix Optimistic Block Position (R2)

### Root cause (confirmed)

In both `addNewBlock` and `splitBlock`, the optimistic block is constructed with:

```kotlin
position = sourceBlock.position + 1,
```

Since `Block.position` is a `String`, this is Kotlin string concatenation, not arithmetic. If `sourceBlock.position` is `"a0"`, the result is `"a01"` — not a valid FractionalIndexing key. `BlockSorter.sort()` places `"a01"` after `"a0z"` (wrong), causing a visual flash until the DB write returns and the page re-queries.

### Task 2.1 — Fix `addNewBlock` optimistic position

**File**: `BlockStateManager.kt`
**Location**: `addNewBlock` function, lines 1003–1052. The optimistic block is constructed at lines 1015–1022.

Before (line 1018):
```kotlin
val optimisticNew = sourceBlock.copy(
    uuid = expectedNewBlockUuid,
    content = "",
    position = sourceBlock.position + 1,   // BUG: string concat
    leftUuid = currentBlockUuid.value,
    createdAt = now,
    updatedAt = now,
)
```

After:
```kotlin
// Compute next sibling position (same parentUuid, sorted by position, first one AFTER sourceBlock).
// This makes the optimistic key sort-correct immediately, with no post-DB-write snap.
val pageBlocksForPos = _blocks.value[pageUuidStr] ?: emptyList()
val nextSiblingPosition = pageBlocksForPos
    .filter { it.parentUuid == sourceBlock.parentUuid }
    .sortedBy { it.position }
    .firstOrNull { it.position > sourceBlock.position }
    ?.position
val optimisticPosition = dev.stapler.stelekit.util.FractionalIndexing
    .generateKeyBetween(sourceBlock.position, nextSiblingPosition)

val optimisticNew = sourceBlock.copy(
    uuid = expectedNewBlockUuid,
    content = "",
    position = optimisticPosition,
    leftUuid = currentBlockUuid.value,
    createdAt = now,
    updatedAt = now,
)
```

Insert the position-computation block immediately before the `val optimisticNew = ...` at line 1015. The `_blocks.value[pageUuidStr]` read is safe here because we are on the Default dispatcher inside `scope.launch` and `_blocks` is a `MutableStateFlow` (thread-safe).

### Task 2.2 — Fix `splitBlock` optimistic position

**File**: `BlockStateManager.kt`
**Location**: `splitBlock` function, lines 1054–1108. The optimistic block is constructed at lines 1066–1073.

Before (line 1069):
```kotlin
val optimisticNew = sourceBlock.copy(
    uuid = expectedNewBlockUuid,
    content = secondPart,
    position = sourceBlock.position + 1,   // BUG: string concat
    leftUuid = blockUuid.value,
    createdAt = now,
    updatedAt = now,
)
```

After:
```kotlin
val nextSiblingPositionForSplit = _blocks.value[pageUuid]
    ?.filter { it.parentUuid == sourceBlock.parentUuid }
    ?.sortedBy { it.position }
    ?.firstOrNull { it.position > sourceBlock.position }
    ?.position
val optimisticSplitPosition = dev.stapler.stelekit.util.FractionalIndexing
    .generateKeyBetween(sourceBlock.position, nextSiblingPositionForSplit)

val optimisticNew = sourceBlock.copy(
    uuid = expectedNewBlockUuid,
    content = secondPart,
    position = optimisticSplitPosition,
    leftUuid = blockUuid.value,
    createdAt = now,
    updatedAt = now,
)
```

Insert the position-computation block immediately before the `val optimisticNew = ...` at line 1066.

### Task 2.3 — Unit test for optimistic position

**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerPositionTest.kt` (new)

Test: given a page with blocks at positions `"a0"`, `"a2"`, calling `addNewBlock` on the `"a0"` block must produce an optimistic block whose position satisfies `"a0" < position < "a2"` (verify via `String` comparison). Same for `splitBlock`.

---

## Epic 3: Block Copy-Paste (R3)

> **Adversarial-review patches applied** (all 5 bugs from `adversarial-review.md` fixed in Tasks 3.3/3.4/3.5):
> - BUG 1: leftUuid chain repair after paste (right-sibling gets new leftUuid)
> - BUG 2: level field adjusted relative to insertion depth
> - BUG 3: CUT removed from scope (COPY only — avoids silent duplication)
> - BUG 4: pendingNewBlockUuids tracked around write
> - BUG 5: saveBlocks error no longer swallowed

### Architecture overview

New state added to `BlockStateManager`:
```
_blockClipboard: MutableStateFlow<BlockClipboard>
```

New methods:
- `copySelectedBlocks(graphUuid: String): Job` — in `BlockSelectionPort`
- `pasteBlocks(afterBlockUuid: BlockUuid): Job` — in `BlockStructurePort`

UI wiring:
- `MobileBlockToolbar`: new `onCopySelected` callback, Copy icon rendered in selection mode next to Delete
- `EditorToolbar`: wire `onCopySelected` to `blockStateManager.copySelectedBlocks(graphUuid)`
- `PageView.kt`: Ctrl+C shortcut when `isInSelectionMode`

### Task 3.1 — Extend `ClipboardModels.kt`

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/clipboard/ClipboardModels.kt`

Add `withBlocks` factory to `BlockClipboard`:

```kotlin
fun withBlocks(
    blocks: List<Block>,
    operation: ClipboardOperation,
    graphUuid: String,
): BlockClipboard = BlockClipboard(blocks.map { ClipboardBlock(it, operation, graphUuid) })
```

The existing `withBlock(single)` can stay for backward compatibility or be removed if no callers exist (grep shows it is currently unreferenced — safe to remove or leave).

### Task 3.2 — Add clipboard state to `BlockStateManager`

**File**: `BlockStateManager.kt`

Add field after the `selection` property block (around line 242):

```kotlin
// ---- Clipboard state ----
private val _blockClipboard = MutableStateFlow(BlockClipboard())
val blockClipboard: StateFlow<BlockClipboard> = _blockClipboard.asStateFlow()
```

No constructor change required for `graphUuid` — see Flag 2 below for the decision on how to supply it.

### Task 3.3 — Add `copySelectedBlocks()` to `BlockStateManager`

**File**: `BlockStateManager.kt`

Add method after `deleteSelectedBlocks()` (around line 269):

```kotlin
/**
 * Snapshot the selected blocks and their full subtrees (visual order, subtree-deduped)
 * into [_blockClipboard] for a later [pasteBlocks] call.
 *
 * Does NOT clear the selection — the user can paste multiple times.
 */
fun copySelectedBlocks(graphUuid: String): Job = scope.launch {
    val selected = selection.selectedBlockUuids.value
    if (selected.isEmpty()) return@launch

    val pageUuid = _blocks.value.entries
        .find { (_, blocks) -> blocks.any { it.uuid.value in selected } }
        ?.key ?: return@launch

    // Expand selection to include full subtrees of each selected block.
    // subtreeDedup removes blocks whose ancestor is also selected (avoids duplication);
    // then we re-expand to collect all descendants.
    val roots = subtreeDedup(selected, pageUuid)
    val allBlocks = _blocks.value[pageUuid] ?: emptyList()
    val childrenByParent = allBlocks.groupBy { it.parentUuid }

    fun collectSubtree(uuid: String): List<Block> {
        val block = allBlocks.find { it.uuid.value == uuid } ?: return emptyList()
        val children = childrenByParent[uuid] ?: emptyList()
        return listOf(block) + children.flatMap { collectSubtree(it.uuid.value) }
    }

    // Collect in visual order (roots appear in the visible list order)
    val visible = getVisibleBlocksForPage(pageUuid)
    val sortedRoots = visible.filter { it.uuid.value in roots }.map { it.uuid.value }
    val blocksToClip = sortedRoots.flatMap { collectSubtree(it) }

    // BUG 3 fix: always COPY; CUT is out of scope (avoids silent duplication)
    _blockClipboard.value = BlockClipboard().withBlocks(
        blocksToClip,
        dev.stapler.stelekit.clipboard.ClipboardOperation.COPY,
        graphUuid
    )
}
```

`getVisibleBlocksForPage` is already `private` in `BlockStateManager`; this method is in the same class, so it is accessible.

### Task 3.4 — UUID remapping algorithm for `pasteBlocks()`

**Conceptual contract** (implemented in Task 3.5):

Given clipboard blocks `C` (a list of `ClipboardBlock`):

1. **Build UUID map** — for every `ClipboardBlock`, map `block.uuid.value → UuidGenerator.generateV7()`. This is a one-pass pre-build so parent references can be resolved in one sweep.

2. **Identify root blocks** — a clipboard block is a "root" if its `block.parentUuid` is `null` OR its `block.parentUuid` is not in the set of all clipboard UUIDs. These are the blocks that anchor to the insertion point.

3. **Determine insertion parent** — the `afterBlock` (the focused block at paste time) supplies the parent context:
   - `insertionParentUuid = afterBlock.parentUuid` (paste at the same depth as `afterBlock`)

4. **Assign new parentUuid for each pasted block**:
   - If root block: `newParentUuid = insertionParentUuid`
   - If child block: `newParentUuid = uuidMap[oldParentUuid]` (the parent was also in the clipboard, now remapped)

5. **Assign positions**:
   - For root blocks (sequential): use a running `prevPos` starting from `afterBlock.position`. For each root:
     ```
     newPos = FractionalIndexing.generateKeyBetween(prevPos, nextSiblingOfAfterBlockPos)
     prevPos = newPos
     ```
     where `nextSiblingOfAfterBlockPos` is the position of the first sibling after `afterBlock` (same parentUuid, sorted, first with position > afterBlock.position).
   - For child blocks: use `FractionalIndexing.generateKeyBetween` with sequential keys. Process children grouped by their new parentUuid, sorted in their original visual order, generating `"a0"`, `"a1"`, ... (or between existing siblings if parent already has children — but for paste, the new parent is newly remapped, so no existing children conflict).

6. **Assign `leftUuid`**: compute from the new insert order (previous pasted sibling's new UUID, or `null` for the first block under a parent).

7. **Assign `pageUuid`**: the current page being edited.

8. **Level adjustment (BUG 2 fix)**: compute `minClipLevel = clipBlocks.minOf { it.level }` and `insertionLevel = afterBlock.level`. Each pasted block's level: `original.level - minClipLevel + insertionLevel`. Root blocks get exactly `insertionLevel`; children preserve their relative depth.

### Task 3.5 — Implement `pasteBlocks()` in `BlockStateManager`

**File**: `BlockStateManager.kt`

Add method after `copySelectedBlocks()`:

```kotlin
/**
 * Paste the clipboard blocks AFTER [afterBlockUuid] on the same page.
 * Each pasted block gets a new UUID v7. Internal parent/child relationships
 * within the clipboard are preserved via UUID remapping.
 * Triggers queueDiskSave. Always COPY — CUT is out of scope.
 */
@OptIn(DirectRepositoryWrite::class)
fun pasteBlocks(afterBlockUuid: BlockUuid): Job = scope.launch {
    val clip = _blockClipboard.value
    if (clip.isEmpty) return@launch

    val pageUuidStr = _blocks.value.entries
        .find { (_, blocks) -> blocks.any { it.uuid == afterBlockUuid } }
        ?.key ?: return@launch

    val afterBlock = _blocks.value[pageUuidStr]?.find { it.uuid == afterBlockUuid } ?: return@launch
    val allBlocks = _blocks.value[pageUuidStr] ?: emptyList()
    val before = takePageSnapshot(pageUuidStr)

    // 1. Build UUID remapping (old uuid string → new uuid string)
    val clipBlocks = clip.entries.map { it.block }
    val clipUuidSet = clipBlocks.map { it.uuid.value }.toSet()
    val uuidMap: Map<String, String> = clipBlocks.associate { b ->
        b.uuid.value to dev.stapler.stelekit.util.UuidGenerator.generateV7()
    }

    // 2. Identify root blocks
    val rootBlocks = clipBlocks.filter { b ->
        b.parentUuid == null || b.parentUuid !in clipUuidSet
    }

    // 3. Compute insertion context
    val siblings = allBlocks
        .filter { it.parentUuid == afterBlock.parentUuid }
        .sortedBy { it.position }
    val nextSiblingPos = siblings.firstOrNull { it.position > afterBlock.position }?.position
    val insertionParentUuid = afterBlock.parentUuid

    // BUG 1 fix: capture right-sibling before write so we can repair its leftUuid
    val existingRightSibling = siblings.firstOrNull { it.position > afterBlock.position }
    val lastPastedRootNewUuid = uuidMap[rootBlocks.lastOrNull()?.uuid?.value]

    // 4. Assign positions for root blocks sequentially
    var prevRootPos: String? = afterBlock.position
    val rootPositions: Map<String, String> = rootBlocks.associate { b ->
        val pos = dev.stapler.stelekit.util.FractionalIndexing
            .generateKeyBetween(prevRootPos, nextSiblingPos)
        prevRootPos = pos
        b.uuid.value to pos
    }

    // BUG 2 fix: level adjustment relative to insertion depth
    val minClipLevel = clipBlocks.minOf { it.level }
    val insertionLevel = afterBlock.level

    val now = kotlin.time.Clock.System.now()
    val pastedBlocks = mutableListOf<Block>()

    fun buildPasted(original: Block, newParentUuid: String?, position: String, prevLeftUuid: String?) {
        val newUuid = uuidMap[original.uuid.value] ?: return
        pastedBlocks.add(
            original.copy(
                uuid = dev.stapler.stelekit.model.BlockUuid(newUuid),
                pageUuid = afterBlock.pageUuid,
                parentUuid = newParentUuid,
                leftUuid = prevLeftUuid,
                position = position,
                level = original.level - minClipLevel + insertionLevel,  // BUG 2 fix
                createdAt = now,
                updatedAt = now,
                isLoaded = true,
            )
        )
        val children = clipBlocks
            .filter { it.parentUuid == original.uuid.value }
            .sortedBy { it.position }
        var prevChildPos: String? = null
        var prevChildLeftUuid: String? = null
        children.forEach { child ->
            val childPos = dev.stapler.stelekit.util.FractionalIndexing
                .generateKeyBetween(prevChildPos, null)
            prevChildPos = childPos
            buildPasted(child, newUuid, childPos, prevChildLeftUuid)
            prevChildLeftUuid = uuidMap[child.uuid.value]
        }
    }

    var prevRootLeftUuid: String? = afterBlockUuid.value
    rootBlocks.forEach { root ->
        val pos = rootPositions[root.uuid.value] ?: return@forEach
        buildPasted(root, insertionParentUuid, pos, prevRootLeftUuid)
        prevRootLeftUuid = uuidMap[root.uuid.value]
    }

    // BUG 4 fix: register new UUIDs as pending before write so merge doesn't drop them
    val newUuids = uuidMap.values.toSet()
    pendingNewBlockUuids.update { it + newUuids }

    // 6. Write to DB (BUG 5 fix: track success; BUG 1 fix: repair right-sibling leftUuid)
    var success = true
    writeActor?.execute {
        blockRepository.saveBlocks(pastedBlocks)
            .onLeft { logger.error("pasteBlocks: saveBlocks failed: $it"); success = false }
        if (success && existingRightSibling != null && lastPastedRootNewUuid != null) {
            blockRepository.saveBlocksUpdate(
                listOf(existingRightSibling.copy(leftUuid = lastPastedRootNewUuid))
            ).onLeft { logger.error("pasteBlocks: chain repair failed: $it"); success = false }
        }
        Unit.right()
    } ?: run {
        blockRepository.saveBlocks(pastedBlocks)
            .onLeft { logger.error("pasteBlocks: saveBlocks failed: $it"); success = false }
        if (success && existingRightSibling != null && lastPastedRootNewUuid != null) {
            blockRepository.saveBlocksUpdate(
                listOf(existingRightSibling.copy(leftUuid = lastPastedRootNewUuid))
            ).onLeft { logger.error("pasteBlocks: chain repair failed: $it"); success = false }
        }
    }

    // BUG 4 fix: remove pending after write completes
    pendingNewBlockUuids.update { it - newUuids }

    if (!success) return@launch  // BUG 5 fix: early exit on DB error

    // 7. Refresh and persist
    val refreshed = blockRepository.getBlocksForPage(afterBlock.pageUuid).first().getOrNull() ?: emptyList()
    _blocks.update { state -> state + (pageUuidStr to refreshed) }
    queueDiskSave(pageUuidStr)

    // 8. Record undo
    val after = takePageSnapshot(pageUuidStr)
    record(
        undo = { restorePageToSnapshot(pageUuidStr, before) },
        redo = { restorePageToSnapshot(pageUuidStr, after) }
    )
    // BUG 3 fix: CUT removed from scope; isCut branch deleted
}
```

Note: `blockRepository.saveBlocks` and `blockRepository.saveBlocksUpdate` are both confirmed in `SqlDelightBlockRepository.kt` (L270, L407). `@OptIn(DirectRepositoryWrite::class)` on the function covers both calls.

### Task 3.6 — Add methods to port interfaces

**File**: `BlockEditorPorts.kt`

Add to `BlockSelectionPort`:

```kotlin
val blockClipboard: StateFlow<BlockClipboard>   // exposed for UI to check isEmpty
fun copySelectedBlocks(graphUuid: String): Job
```

Add to `BlockStructurePort`:

```kotlin
fun pasteBlocks(afterBlockUuid: BlockUuid): Job
```

Add import at top of `BlockEditorPorts.kt`:
```kotlin
import dev.stapler.stelekit.clipboard.BlockClipboard
```

### Task 3.7 — Add Copy button to `MobileBlockToolbar`

**File**: `MobileBlockToolbar.kt`

Step A: Add `onCopySelected` parameter to the composable signature (after `onDeleteSelected`, around line 47):
```kotlin
onCopySelected: () -> Unit = {},
```

Step B: In the `isInSelectionMode` branch (lines 63–81), add a Copy icon button between the Delete button and the Close button:

Before (lines 73–80):
```kotlin
Row {
    IconButton(onClick = onDeleteSelected) {
        Icon(Icons.Default.Delete, contentDescription = "Delete selected")
    }
    IconButton(onClick = onClearSelection) {
        Icon(Icons.Default.Close, contentDescription = "Clear selection")
    }
}
```

After:
```kotlin
Row {
    IconButton(onClick = onCopySelected) {
        Icon(Icons.Default.ContentCopy, contentDescription = "Copy selected")
    }
    IconButton(onClick = onDeleteSelected) {
        Icon(Icons.Default.Delete, contentDescription = "Delete selected")
    }
    IconButton(onClick = onClearSelection) {
        Icon(Icons.Default.Close, contentDescription = "Clear selection")
    }
}
```

Add import: `import androidx.compose.material.icons.filled.ContentCopy`

### Task 3.8 — Wire Copy in `EditorToolbar.kt`

**File**: `EditorToolbar.kt`

The `graphUuid` must be available at the `EditorToolbar` call site. See Flag 2 for the decision.

Once `graphUuid: String` is available:

Add `onCopySelected` parameter thread-through in `MobileBlockToolbar(...)` call (around line 112):
```kotlin
onCopySelected = { blockStateManager.copySelectedBlocks(graphUuid) },
onDeleteSelected = { blockStateManager.deleteSelectedBlocks() },
```

If `pasteBlocks` is also triggered from the toolbar (not required by R3 — Ctrl+V is the primary paste trigger), wire it similarly. R3 spec only requires Ctrl+V keyboard, not a toolbar paste button.

### Task 3.9 — Wire Ctrl+C in `PageView.kt`

**File**: `PageView.kt`

In the `onKeyEvent` handler (starting at line 132), add a Ctrl+C branch inside the `when {}` block, before the `else -> false` at line 155:

```kotlin
event.key == Key.C && event.isCtrlPressed && isInSelectionMode && selectedBlockUuids.isNotEmpty() -> {
    blockStateManager.copySelectedBlocks(/* graphUuid — see Flag 2 */)
    true
}
event.key == Key.V && event.isCtrlPressed && !isInSelectionMode && editingBlockUuid != null -> {
    editingBlockUuid?.let { blockStateManager.pasteBlocks(it) }
    true
}
```

Note: the existing Ctrl+V handler (lines 126–131) intercepts image paste via `onPreviewKeyEvent` at a higher priority. The block-paste `Key.V` branch below uses `onKeyEvent` and fires only when `onPreviewKeyEvent` returns `false` (i.e., when `onPasteImage` is null OR when `editingBlockUuid` is null and it's a block-level paste). To avoid conflict, guard the block-paste branch with `!blockClipboard.isEmpty` or check that the system clipboard does not contain an image.

Add to the imports/state collection at the top of the composable:
```kotlin
val blockClipboard by blockStateManager.blockClipboard.collectAsState()
```

---

## Flag Decision Points

### Flag 1 — R1 may already be fixed (action required before coding)

**Current state**: `refreshBlocksForPage` (L940–952) already calls `queueDiskSave`. All four reorder functions call `refreshBlocksForPage`. The R1 fix described in `requirements.md` is already in the code.

**Decision**: Run the manual smoke test (Task 1.1) before any E1 coding. If it passes, E1 reduces to Task 1.2 (regression test only). If it fails, investigate why `queueDiskSave` is not reaching disk — the likeliest cause would be `graphWriter == null` or `graphPathProvider()` returning `""` in the test environment vs. production.

### Flag 2 — How to supply `graphUuid` to `copySelectedBlocks`

`ClipboardBlock.sourceGraphUuid` tracks which graph a block came from (future cross-graph paste guard). Three options:

**Option A (recommended)**: Add `private val graphUuidProvider: () -> String = { "" }` to `BlockStateManager` constructor. Wire it from `StelekitViewModel` the same way `graphPathProvider` is wired. Pass `graphUuidProvider()` in `copySelectedBlocks`. Cost: 1 constructor parameter + 1 call site change in ViewModel.

**Option B**: Add `graphUuid: String` as a parameter to `copySelectedBlocks(graphUuid: String)` and have the call sites (EditorToolbar, PageView) supply it from a local state variable. Cost: `graphUuid` must be accessible in both composables — requires threading from AppState.

**Option C**: Use an empty string `""` for now (cross-graph paste is out of scope). Add a TODO comment. Cost: zero wiring work; technical debt if cross-graph paste is added later.

Recommendation: **Option C** for this sprint (cross-graph paste is explicitly out of scope), escalate Option A when cross-graph is picked up.

### Flag 3 — `saveBlocks` write path in `pasteBlocks`

`blockRepository.saveBlocks(List<Block>)` is called with `@OptIn(DirectRepositoryWrite::class)` in `restorePageToSnapshot`. The same opt-in is needed in `pasteBlocks`. If the method is routed through `writeActor?.execute { blockRepository.saveBlocks(...) }`, the actor's `@OptIn` at class level (approved for `DatabaseWriteActor`) covers it. Verify that `saveBlocks` is declared on `RestrictedDatabaseQueries` before coding Task 3.5.

### Flag 4 — `BlockSelectionPort` interface not consumed directly by UI

`EditorToolbar` and `PageView` both accept `BlockStateManager` directly (not the port interface). Adding `blockClipboard` and `copySelectedBlocks`/`pasteBlocks` to the port interfaces is still correct for test-doubles and future interface segregation, but the UI wiring calls concrete `BlockStateManager` methods. No cast or delegation issue.

---

## Execution Order

```
E2/T2.1 → E2/T2.2 → E2/T2.3   (independent, fast)
E1/T1.1                         (smoke test — gate for T1.2)
E1/T1.2                         (regression test, after smoke)
E3/T3.1 → E3/T3.2 → E3/T3.3
       → E3/T3.4 (design review) → E3/T3.5
       → E3/T3.6
       → E3/T3.7 → E3/T3.8 → E3/T3.9
```

E2 and the E3 chain can start in parallel. E1 smoke test unblocks T1.2 but blocks nothing else.
