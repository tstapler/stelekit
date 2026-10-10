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

---

## ✅ Status (post-review additions)

E1 + E2 + E3 are complete. The following epics address findings from the algorithmic/domain-design review.

---

## Epic 4: API Cleanup (graphUuid phantom + CUT orphan)

### Task 4.1 — Remove `graphUuid: String` param from `copySelectedBlocks`

The parameter is always called with `""` from all call sites. `sourceGraphUuid` is stored in `ClipboardBlock` for future cross-graph paste detection, but the caller-supplied value adds no value today and leaks an infrastructure concern into the port interface.

**Files**:

1. `BlockEditorPorts.kt:57` — change signature:
   ```kotlin
   // Before
   fun copySelectedBlocks(graphUuid: String): Job
   // After
   fun copySelectedBlocks(): Job
   ```

2. `BlockStateManager.kt:285` — change signature and replace `graphUuid` usage:
   ```kotlin
   // Before
   override fun copySelectedBlocks(graphUuid: String): Job = scope.launch {
       ...
       _blockClipboard.value = BlockClipboard().withBlocks(blocksToClip, ClipboardOperation.COPY, graphUuid)
   // After
   override fun copySelectedBlocks(): Job = scope.launch {
       ...
       _blockClipboard.value = BlockClipboard().withBlocks(blocksToClip, ClipboardOperation.COPY, "")
   ```

3. `EditorToolbar.kt:112` — change call:
   ```kotlin
   // Before
   onCopySelected = { blockStateManager.copySelectedBlocks("") },
   // After
   onCopySelected = { blockStateManager.copySelectedBlocks() },
   ```

4. `PageView.kt:150` — change call:
   ```kotlin
   // Before
   blockStateManager.copySelectedBlocks("")
   // After
   blockStateManager.copySelectedBlocks()
   ```

### Task 4.2 — Remove `ClipboardOperation.CUT` and `isCut`

`ClipboardOperation.CUT` is never set in production code. Keeping it creates a phantom case in any future `when` branch. Remove it now; Epic 7 re-introduces it properly when CUT is implemented.

**File**: `ClipboardModels.kt`

```kotlin
// Before
enum class ClipboardOperation { CUT, COPY }
...
val isCut: Boolean get() = entries.firstOrNull()?.operation == ClipboardOperation.CUT

// After
enum class ClipboardOperation { COPY }
// remove isCut entirely — no callers reference it
```

---

## Epic 5: Extract `BlockTreeAlgorithms`

Extract the pure tree-traversal algorithms from `BlockStateManager` into a testable object. This improves:
- Unit testability (algorithms take `List<Block>`, return `List<Block>` — no coroutines or state)
- Benchmarkability (can feed large synthetic trees without instantiating BlockStateManager)
- Reusability (Epic 7 CUT reuses `collectSubtree`)

### Task 5.1 — Create `clipboard/BlockTreeAlgorithms.kt`

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/clipboard/BlockTreeAlgorithms.kt`

```kotlin
package dev.stapler.stelekit.clipboard

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.util.FractionalIndexing
import dev.stapler.stelekit.util.UuidGenerator
import kotlin.time.Instant

object BlockTreeAlgorithms {

    /** Pre-index blocks by UUID for O(1) lookups (replaces allBlocks.find in recursive calls). */
    fun indexByUuid(blocks: List<Block>): Map<String, Block> =
        blocks.associateBy { it.uuid.value }

    /** Pre-index blocks by parentUuid for O(1) child lookups. */
    fun indexChildren(blocks: List<Block>): Map<String?, List<Block>> =
        blocks.groupBy { it.parentUuid }

    /**
     * Collect a block and all its descendants in BFS order.
     * [byUuid] and [childrenByParent] are pre-built indexes — callers must provide them
     * to avoid repeated O(n) scans across recursive calls.
     */
    fun collectSubtree(
        rootUuid: String,
        byUuid: Map<String, Block>,
        childrenByParent: Map<String?, List<Block>>,
    ): List<Block> {
        val root = byUuid[rootUuid] ?: return emptyList()
        val children = childrenByParent[rootUuid] ?: emptyList()
        return listOf(root) + children.flatMap { collectSubtree(it.uuid.value, byUuid, childrenByParent) }
    }

    /**
     * Find the minimal set of clipboard roots — blocks whose ancestor is NOT also in the selection.
     * Input [uuids] is the raw selection; [childrenByParent] is the page-wide child index.
     */
    fun findRoots(uuids: Set<String>, childrenByParent: Map<String?, List<Block>>): Set<String> {
        // A block is a root if none of its ancestors are in the selection set.
        // Walk up via parentUuid; stop at null (top of tree).
        fun hasSelectedAncestor(block: Block, byUuid: Map<String, Block>): Boolean {
            var current = block.parentUuid?.let { byUuid[it] }
            while (current != null) {
                if (current.uuid.value in uuids) return true
                current = current.parentUuid?.let { byUuid[it] }
            }
            return false
        }
        // Build byUuid from childrenByParent values
        val byUuid = childrenByParent.values.flatten().associateBy { it.uuid.value }
        return uuids.filter { uuid ->
            val block = byUuid[uuid] ?: return@filter false
            !hasSelectedAncestor(block, byUuid)
        }.toSet()
    }

    /**
     * Build the list of pasted blocks with new UUIDs, remapped parent/left references,
     * adjusted positions, and level normalization.
     *
     * @param clipBlocks the clipboard blocks (COPY of original blocks)
     * @param rootBlocks subset of clipBlocks that are paste roots (no parent in clipboard)
     * @param uuidMap old-uuid → new-uuid remapping (pre-built by caller)
     * @param afterBlock the destination block (pasted items go after this)
     * @param insertionParentUuid parentUuid for root pasted blocks
     * @param nextSiblingPos position of the first sibling after afterBlock (null = afterBlock is last)
     * @param now timestamp for createdAt/updatedAt
     */
    fun buildPastedTree(
        clipBlocks: List<Block>,
        rootBlocks: List<Block>,
        uuidMap: Map<String, String>,
        afterBlock: Block,
        insertionParentUuid: String?,
        nextSiblingPos: String?,
        now: Instant,
    ): List<Block> {
        val clipChildrenByParent = clipBlocks.groupBy { it.parentUuid }
        val minClipLevel = clipBlocks.minOf { it.level }
        val insertionLevel = afterBlock.level

        val result = mutableListOf<Block>()

        fun build(original: Block, newParentUuid: String?, position: String, prevLeftUuid: String?) {
            val newUuid = uuidMap[original.uuid.value] ?: return
            result.add(
                original.copy(
                    uuid = dev.stapler.stelekit.model.BlockUuid(newUuid),
                    pageUuid = afterBlock.pageUuid,
                    parentUuid = newParentUuid,
                    leftUuid = prevLeftUuid,
                    position = position,
                    level = original.level - minClipLevel + insertionLevel,
                    createdAt = now,
                    updatedAt = now,
                    isLoaded = true,
                )
            )
            val children = (clipChildrenByParent[original.uuid.value] ?: emptyList())
                .sortedBy { it.position }
            var prevChildPos: String? = null
            var prevChildLeftUuid: String? = null
            children.forEach { child ->
                val childPos = FractionalIndexing.generateKeyBetween(prevChildPos, null)
                prevChildPos = childPos
                build(child, newUuid, childPos, prevChildLeftUuid)
                prevChildLeftUuid = uuidMap[child.uuid.value]
            }
        }

        var prevRootPos: String? = afterBlock.position
        var prevRootLeftUuid: String? = afterBlock.uuid.value
        rootBlocks.forEach { root ->
            val pos = FractionalIndexing.generateKeyBetween(prevRootPos, nextSiblingPos)
            prevRootPos = pos
            build(root, insertionParentUuid, pos, prevRootLeftUuid)
            prevRootLeftUuid = uuidMap[root.uuid.value]
        }

        return result
    }
}
```

### Task 5.2 — Refactor `BlockStateManager` to use `BlockTreeAlgorithms`

**File**: `BlockStateManager.kt`

In `copySelectedBlocks()` (~L297–308):
```kotlin
// Before
val childrenByParent = allBlocks.groupBy { it.parentUuid }
val blockByUuid = allBlocks.associateBy { it.uuid.value }
fun collectSubtree(uuid: String): List<Block> { ... }
val sortedRoots = BlockSorter.sort(allBlocks).filter { it.uuid.value in roots }.map { it.uuid.value }
val blocksToClip = sortedRoots.flatMap { collectSubtree(it) }

// After
val byUuid = BlockTreeAlgorithms.indexByUuid(allBlocks)
val childrenByParent = BlockTreeAlgorithms.indexChildren(allBlocks)
val sortedRoots = BlockSorter.sort(allBlocks).filter { it.uuid.value in roots }.map { it.uuid.value }
val blocksToClip = sortedRoots.flatMap {
    BlockTreeAlgorithms.collectSubtree(it, byUuid, childrenByParent)
}
```

In `pasteBlocks()` (~L374–406), replace the inline `buildPasted` local function and the code that calls it with:
```kotlin
val pastedBlocks = BlockTreeAlgorithms.buildPastedTree(
    clipBlocks = clipBlocks,
    rootBlocks = rootBlocks,
    uuidMap = uuidMap,
    afterBlock = afterBlock,
    insertionParentUuid = insertionParentUuid,
    nextSiblingPos = nextSiblingPos,
    now = now,
)
```

Add import at top of `BlockStateManager.kt`:
```kotlin
import dev.stapler.stelekit.clipboard.BlockTreeAlgorithms
```

---

## Epic 6: Copy/Paste Tests

### Task 6.1 — `BlockTreeAlgorithmsTest.kt` (unit tests for pure algorithms)

**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/clipboard/BlockTreeAlgorithmsTest.kt`

Test cases:
1. `collectSubtree_single_block_no_children` — single block returns [block]
2. `collectSubtree_two_level_tree` — root + 2 children returns [root, c1, c2]
3. `collectSubtree_deep_tree` — 3-level tree returns 7 blocks in BFS order
4. `collectSubtree_unknown_uuid_returns_empty` — non-existent UUID → empty list
5. `buildPastedTree_single_block` — single block paste: new UUID, correct position between afterBlock and nextSibling
6. `buildPastedTree_uuid_uniqueness` — 10-block paste: all 10 new UUIDs are distinct and differ from originals
7. `buildPastedTree_level_normalization` — nested clipboard (min level 2) pasted at level 0 → levels 0, 1
8. `buildPastedTree_chain_repair_left_uuid` — pasted root's leftUuid equals afterBlock.uuid; second root's leftUuid equals first root's new UUID

### Task 6.2 — `BlockStateManagerCopyPasteTest.kt` (integration tests through BSM)

**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerCopyPasteTest.kt`

Test cases:
1. `copySelectedBlocks_empty_selection_is_noop` — clipboard stays empty
2. `copySelectedBlocks_single_block` — clipboard contains 1 block with new UUID remapping ready
3. `copySelectedBlocks_subtree_dedup` — select parent + child → clipboard has parent+child but NOT duplicated
4. `pasteBlocks_single_block_inserts_correctly` — block appears in _blocks after paste
5. `pasteBlocks_right_sibling_chain_repair` — existing right sibling's leftUuid updated to last pasted root
6. `pasteBlocks_nested_hierarchy_preserved` — paste a 2-level subtree; children have correct parentUuid
7. `pasteBlocks_uuid_uniqueness` — all pasted UUIDs differ from clipboard UUIDs

---

## Epic 7: CUT Implementation (Ctrl+X)

### Design: two-phase approach

- **Phase 1 (Ctrl+X)**: `cutSelectedBlocks()` — snapshots blocks into clipboard with `ClipboardOperation.CUT`. No visual removal yet. UI greys out cut blocks via `blockClipboard.isCut`.
- **Phase 2 (Ctrl+V)**: `pasteBlocks()` detects `isCut`, inserts new blocks AND deletes originals in a single atomic transaction (Epic 8). Escape or a new copy/cut clears the clipboard.

### Task 7.0 — Re-add `ClipboardOperation.CUT` to enum

Epic 4 removed CUT because it was orphaned. Epic 7 re-introduces it properly.

**File**: `ClipboardModels.kt`
```kotlin
enum class ClipboardOperation { COPY, CUT }
val isCut: Boolean get() = entries.firstOrNull()?.operation == ClipboardOperation.CUT
```

### Task 7.1 — Add `cutSelectedBlocks()` to `BlockStateManager`

Add after `copySelectedBlocks()`:
```kotlin
fun cutSelectedBlocks(): Job = scope.launch {
    val selected = selection.selectedBlockUuids.value
    if (selected.isEmpty()) return@launch

    val pageUuid = _blocks.value.entries
        .find { (_, blocks) -> blocks.any { it.uuid.value in selected } }
        ?.key ?: return@launch

    val roots = subtreeDedup(selected, pageUuid)
    val allBlocks = _blocks.value[pageUuid] ?: emptyList()
    val byUuid = BlockTreeAlgorithms.indexByUuid(allBlocks)
    val childrenByParent = BlockTreeAlgorithms.indexChildren(allBlocks)
    val sortedRoots = BlockSorter.sort(allBlocks).filter { it.uuid.value in roots }.map { it.uuid.value }
    val blocksToClip = sortedRoots.flatMap { BlockTreeAlgorithms.collectSubtree(it, byUuid, childrenByParent) }

    _blockClipboard.value = BlockClipboard().withBlocks(blocksToClip, ClipboardOperation.CUT, "")
}
```

Add `fun cutSelectedBlocks(): Job` to `BlockSelectionPort` interface in `BlockEditorPorts.kt`.

### Task 7.2 — Extend `pasteBlocks()` to handle CUT

After writing the pasted blocks (Epic 8 atomic tx), add:
```kotlin
if (clip.isCut) {
    val cutUuids = clipBlocks.map { it.uuid }
    blockRepository.deleteBulk(cutUuids, deleteChildren = false)
        .onLeft { logger.error("pasteBlocks CUT: delete originals failed: $it") }
    // Update in-memory state: remove cut blocks from source page
    _blocks.update { state ->
        val cutUuidSet = cutUuids.map { it.value }.toSet()
        state.mapValues { (_, blocks) -> blocks.filter { it.uuid.value !in cutUuidSet } }
    }
    _blockClipboard.value = BlockClipboard()  // clear clipboard after successful CUT
}
```

Note: `deleteBulk` already performs chain repair for the deleted blocks.

### Task 7.3 — Ctrl+X keyboard binding in `PageView.kt`

In the `onKeyEvent` handler, add alongside the Ctrl+C branch:
```kotlin
event.key == Key.X && event.isCtrlPressed && isInSelectionMode && selectedBlockUuids.isNotEmpty() -> {
    blockStateManager.cutSelectedBlocks()
    true
}
```

### Task 7.4 — Visual indicator for cut blocks (grey overlay)

In the block rendering composable (wherever blocks are rendered), check `blockClipboard.isCut`:
```kotlin
val blockClipboard by blockStateManager.blockClipboard.collectAsState()
val isCutBlock = blockClipboard.isCut && blockUuid in blockClipboard.entries.map { it.block.uuid.value }
// Apply alpha = 0.4f or strikethrough style to isCutBlock blocks
```

---

## Epic 8: Atomic Paste Transaction

### Task 8.1 — Add `saveBlocksAtomicWithChainRepair` to `BlockWriteRepository`

**File**: `BlockWriteRepository.kt`

```kotlin
/**
 * Insert [toInsert] and update [chainRepair] in a single SQLite transaction.
 * Used by paste to atomically insert new blocks and repair the right-sibling's leftUuid.
 * Default impl uses two separate transactions (correct but non-atomic — safe for now).
 */
@DirectRepositoryWrite
suspend fun saveBlocksAtomicWithChainRepair(
    toInsert: List<Block>,
    chainRepair: List<Block>,
): Either<DomainError, Unit> {
    val result = saveBlocks(toInsert)
    if (result.isLeft()) return result
    return if (chainRepair.isEmpty()) Unit.right() else saveBlocksUpdate(chainRepair)
}
```

### Task 8.2 — Override in `SqlDelightBlockRepository`

**File**: `SqlDelightBlockRepository.kt`

```kotlin
override suspend fun saveBlocksAtomicWithChainRepair(
    toInsert: List<Block>,
    chainRepair: List<Block>,
): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
    if (toInsert.isEmpty() && chainRepair.isEmpty()) return@withContext Unit.right()
    try {
        ftsAutomergeOff()
        queries.transaction {
            toInsert.forEach { block -> insertBlockRow(block) }
            chainRepair.forEach { block ->
                queries.updateBlockFull(
                    block.pageUuid.value, block.parentUuid, block.leftUuid,
                    block.content, block.level.toLong(), block.position,
                    block.updatedAt.toEpochMilliseconds(),
                    block.properties.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { null },
                    block.contentHash ?: ContentHasher.sha256ForContent(block.content),
                    block.blockType.toDiscriminatorString(),
                    block.uuid.value,
                )
            }
        }
        ftsAutomergeDefault()
        Unit.right()
    } catch (e: CancellationException) {
        runCatching { ftsAutomergeDefault() }
        throw e
    } catch (e: Exception) {
        runCatching { ftsAutomergeDefault() }
        DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
    }
}
```

### Task 8.3 — Refactor `pasteBlocks()` to use atomic method

**File**: `BlockStateManager.kt` in `pasteBlocks()`

Replace the two-call write block (~lines 413–431):
```kotlin
// Before: two separate transactions (non-atomic)
writeActor?.execute {
    blockRepository.saveBlocks(pastedBlocks)
        .onLeft { ... }
    if (success && existingRightSibling != null && lastPastedRootNewUuid != null) {
        blockRepository.saveBlocksUpdate(...)
            .onLeft { ... }
    }
    Unit.right()
}

// After: single atomic transaction
val chainRepair = if (existingRightSibling != null && lastPastedRootNewUuid != null)
    listOf(existingRightSibling.copy(leftUuid = lastPastedRootNewUuid))
else emptyList()
writeActor?.execute {
    blockRepository.saveBlocksAtomicWithChainRepair(pastedBlocks, chainRepair)
        .onLeft { logger.error("pasteBlocks: atomic write failed: $it"); success = false }
    Unit.right()
} ?: run {
    blockRepository.saveBlocksAtomicWithChainRepair(pastedBlocks, chainRepair)
        .onLeft { logger.error("pasteBlocks: atomic write failed: $it"); success = false }
}
```

---

## Updated Execution Order

```
Wave 1 (parallel):
  Agent A: E4 + E5 + E8  (cleanup + extraction + atomic tx — all touch BlockStateManager.kt)
  Agent B: E6            (new test files only — no conflicts with A)

Wave 2 (after Wave 1):
  Agent C: E7            (CUT — depends on E5's BlockTreeAlgorithms + E8's atomic tx)
```
