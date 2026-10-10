# Research: Block Reorder, Bulk Delete & Copy-Paste — Pitfalls

## Pre-implementation Verification: Bug 1 May Already Be Fixed

**Current code (BlockStateManager.kt, lines 940–952):**
```kotlin
private suspend fun refreshBlocksForPage(blockUuid: BlockUuid) {
    val pageUuid = ...
    val pageBlocks = blockRepository.getBlocksForPage(PageUuid(pageUuid)).first().getOrNull() ?: return
    _blocks.update { ... }
    queueDiskSave(pageUuid)   // ← already present
}
```

All four reorder operations (`moveBlockUp`, `moveBlockDown`, `indentBlock`, `outdentBlock`) delegate to `refreshBlocksForPage`, which already calls `queueDiskSave`. Verify this in the live codebase before implementing Bug 1's fix — the patch may already be present and the requirements doc may have been written against an older snapshot. Applying a redundant fix would double-queue disk saves.

---

## Section 1: Known Failure Modes — Fractional Index + Linked-List Ordering

### 1.1 String-concatenation bug is confirmed at two sites

`addNewBlock` (line 1018) and `splitBlock` (line 1069) both do:
```kotlin
position = sourceBlock.position + 1  // Kotlin: String + Int → String concatenation → "a01"
```
Correct fix: find the next sibling (same `parentUuid`, sorted by `position`, first after `sourceBlock`) then call:
```kotlin
FractionalIndexing.generateKeyBetween(sourceBlock.position, nextSiblingPosition)
```
`nextSiblingPosition` is `null` when `sourceBlock` is the last sibling (append case), which `generateKeyBetween` handles correctly by calling `increment()`.

### 1.2 UUID remapping is mandatory for subtree paste

For a three-level clipboard subtree A → B → C (A is root, B child of A, C child of B):
- A gets new UUID A', B gets B', C gets C'
- B.parentUuid must be set to A' (not the original A)
- C.parentUuid must be set to B' (not the original B)
- leftUuid pointers within the clipboard subtree must also be remapped to new UUIDs

Build a `Map<String, String>` (originalUuid → newUuid) before writing any blocks. Without this, the pasted subtree references source UUIDs, and if the source blocks are later deleted (CUT), `parentUuid` becomes a dangling reference that corrupts tree traversal.

### 1.3 leftUuid chain repair is a multi-row update

After inserting N pasted blocks after focus block F:
1. The last pasted block P_n must have `leftUuid = P_{n-1}.uuid`
2. The block that was previously the right-sibling of F must have its `leftUuid` updated to `P_n.uuid`

The repository's `moveBlock` handles this incrementally for drag-drop. For paste, the safest approach is to re-query the full page after the write transaction settles (same pattern as `deleteSelectedBlocks`), rather than trying to patch `leftUuid` in-memory optimistically.

### 1.4 Fractional key generation for N consecutive inserts

The `moveSelectedBlocks` method already shows the correct pattern for inserting multiple blocks into a gap:
```kotlin
var prevMovePosition: String? = afterPosition
toMove.forEach { uuid ->
    val newPos = FractionalIndexing.generateKeyBetween(prevMovePosition, nextSiblingPosition)
    prevMovePosition = newPos
    writeMoveBlock(...)
}
```
Copy this pattern for paste: advance `prevMovePosition` after each block. The concern is key length growth when inserting into a very tight gap (e.g., between "a0" and "a1"), but `FractionalIndexing.midpoint` handles this recursively and correctness is maintained — only key-string length grows, which is not a correctness failure for this feature scope.

### 1.5 pageUuid must be reset on pasted blocks

`ClipboardBlock` stores blocks with their original `pageUuid`. On paste, every pasted block must have `pageUuid` set to the target page's UUID. Even for same-page paste (current scope), this is a required assignment since the model validates UUID format and downstream markdown serialization reads `block.pageUuid`.

### 1.6 Level adjustment arithmetic

Requirements say "level adjusted relative to insertion point". The canonical algorithm:
1. Find `minLevel = clipboard.entries.minOf { it.block.level }`
2. Find `targetLevel = focusedBlock.level` (the block the cursor is on when paste fires)
3. Each pasted block gets `level = block.level - minLevel + targetLevel` (same-level paste) or `+ targetLevel + 1` (paste as children)

The requirements specify "inserts after the currently focused block," implying same-level paste (option 1). Misimplementing as "+1" would silently indent all pasted content one level too deep.

### 1.7 CUT semantics: delete-on-paste, not delete-on-cut

Logseq, Roam, and Notion all implement CUT as: mark blocks in clipboard, delete originals **on paste** (not when Ctrl+X is pressed). This allows the user to cancel a CUT by pressing Escape without losing content. Implement as:
- `isCut` flag on `BlockClipboard`
- After successful paste: call `deleteSelectedBlocks` on the original UUIDs (stored in clipboard alongside the block data), then clear clipboard

Failure mode if delete-on-cut is used instead: user presses Ctrl+X, app crashes before paste → data loss. The current `ClipboardBlock.operation` field already encodes this intent correctly.

---

## Section 2: Architecture — How Logseq/Roam/Notion Handle Copy-Paste

### 2.1 Logseq (most relevant — same data model)

Logseq stores blocks as an EDN graph with `:block/uuid`, `:block/parent`, `:block/left`, `:block/order`. Copy-paste:
- **Copy**: Serializes selected block trees to an in-memory clipboard. Does NOT write to system clipboard for block-level operations (only exports text for Ctrl+C on text selection within a block). Block clipboard is app-internal state.
- **UUID regeneration**: On paste, new UUIDs are generated for all blocks. Internal `:block/parent` and `:block/left` references are remapped via a translation map before writing.
- **Order**: Pasted blocks are inserted by computing positions relative to the target insertion point using the same fractional/order mechanism.
- **Subtree deduplication**: If a parent and a child are both selected, only the parent is copied (the child is included implicitly as part of the parent's subtree). This matches the existing `subtreeDedup()` call in `deleteSelectedBlocks`.

### 2.2 Roam Research

- Block references (`((uuid))`) in content are **preserved as-is** on copy, not remapped. This is correct because `((uuid))` in content references the original block by intent (they are references, not copies).
- SteleKit does not implement block references yet, so this case is out of scope — but keep it in mind: when block references land, pasted content that contains `[[page-name]]` or `((block-uuid))` should NOT have UUIDs in the content string remapped.

### 2.3 Notion

- Block-level clipboard is serialized as JSON on paste and can be pasted cross-document.
- Notion's approach of remapping all IDs and adjusting levels is the pattern to follow.
- Key difference: Notion paste always inserts as a sibling at the same level (not as children), which matches the R3 requirements.

---

## Section 3: Stack-Specific Pitfalls (KMP + Compose + StateFlow + SQLDelight + Arrow)

### 3.1 ClipboardBlock model is single-block only — needs `withBlocks()`

Current `BlockClipboard.withBlock()` creates a single-element list and replaces previous contents. For multi-block copy:
- Add `fun withBlocks(blocks: List<Block>, operation: ClipboardOperation, graphUuid: String): BlockClipboard`
- Store the blocks in DFS pre-order (parent before children) so the paste loop can rebuild parent-child relationships by walking the list in order
- The test at `BlockClipboardTest.withBlock_replacesPreviousEntry()` asserts single-block replace semantics — `withBlocks` must have the same replace-not-append behavior

### 3.2 blockClipboard must live on BlockStateManager, not BlockSelectionManager

`BlockSelectionManager` is cleared when selection mode exits. Clipboard state must survive page navigation and selection-mode exit. Hold `_blockClipboard = MutableStateFlow(BlockClipboard())` directly on `BlockStateManager`, exposed as `StateFlow<BlockClipboard>` on the interface. Do not route through `BlockSelectionManager`.

### 3.3 pendingNewBlockUuids tracking for multi-block optimistic insert

The existing `pendingNewBlockUuids: MutableStateFlow<Set<String>>` prevents reactive re-emissions from dropping optimistically inserted blocks before the DB write completes. For paste (N blocks), all N new UUIDs must be added atomically before the first write and removed atomically after all writes complete (or on rollback). Use:
```kotlin
pendingNewBlockUuids.update { it + newUuids }
// ... all writes ...
pendingNewBlockUuids.update { it - newUuids }
```
Removing them one-by-one mid-paste would allow the reactive DB emission after the first block's write to prematurely remove blocks 2..N from the in-memory state.

### 3.4 Arrow Either for multi-block save — use a fold, not chained onRight

For N blocks, `blockRepository.saveBlock(block)` returns `Either<DomainError, Block>`. Chaining `.onRight { ... }` N times is error-prone. Use:
```kotlin
val results = newBlocks.map { block -> writeBlock(block) }
val firstError = results.filterIsInstance<Either.Left<DomainError>>().firstOrNull()
if (firstError != null) {
    // roll back all optimistic inserts
} else {
    // proceed
}
```
Or use Arrow's `either { }` DSL with `bind()` to short-circuit on the first failure. The `either { }` builder is on the classpath.

### 3.5 Undo entry for paste must use page snapshots

Paste is a structural operation (adds blocks to DB). Use the same snapshot pattern as `deleteSelectedBlocks`:
```kotlin
val before = takePageSnapshot(pageUuid)
// ... write all blocks ...
val after = takePageSnapshot(pageUuid)
record(
    undo = { restorePageToSnapshot(pageUuid, before) },
    redo = { restorePageToSnapshot(pageUuid, after) }
)
```
Do NOT try to undo paste by deleting individual UUIDs — `restorePageToSnapshot` handles linked-list repair atomically.

### 3.6 queueDiskSave is mandatory after paste

Paste adds blocks to SQLite via `DatabaseWriteActor`. Without `queueDiskSave(pageUuid)`, the paste survives only for the current session. On restart, `GraphLoader` re-reads the markdown file (which doesn't contain the pasted blocks) and overwrites the DB. This is the same class of bug as Bug 1. Call `queueDiskSave(pageUuid)` after all writes succeed.

### 3.7 Keyboard shortcut routing — selection mode vs text edit mode

Ctrl+C in selection mode must NOT intercept when the user is typing (text edit mode). The wiring in `PageView.kt` must check `isInSelectionMode.value` before routing Ctrl+C to block copy, otherwise it will block the native text-copy shortcut. Pattern from existing keyboard handling:
```kotlin
if (isInSelectionMode.value && KeyboardShortcuts.isCopy(event)) {
    blockManager.copySelectedBlocks(pageUuid)
    true  // consumed
} else {
    false  // not consumed — let Compose/system handle
}
```

### 3.8 MobileBlockToolbar — Copy button placement

The toolbar shows Delete and other actions in selection mode. The Copy button must appear next to Delete (not inside a submenu) so it's one tap away. Review `MobileBlockToolbar.kt` for the existing row layout. Adding a second icon button with the same `enabled = selectedBlockUuids.isNotEmpty()` guard is sufficient — no new state needed.
