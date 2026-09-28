# Adversarial Review: Block Reorder Persistence + Copy-Paste Implementation Plan

**Verdict: BLOCKED**

Three correctness bugs would ship corrupted data. Two are silent data-integrity failures; one triggers a runtime exception.

---

## Critical Bugs (must fix before implementation)

### BUG 1 — leftUuid chain corruption after paste (runtime exception + linked-list fork)

**Severity: CRITICAL — runtime crash on next structural op**

After `saveBlocks(pastedBlocks)` inserts N new root blocks between `afterBlock` and `existingRightSibling`:

- `pastedBlock1.leftUuid = afterBlock.uuid.value` (set by plan — correct)
- `existingRightSibling.leftUuid = afterBlock.uuid.value` (NOT updated — still the pre-paste value)

Result: two rows in the DB share `left_uuid = afterBlock.uuid`. The schema has no UNIQUE constraint on `left_uuid`, so SQLite silently accepts both rows. The index `idx_blocks_left_uuid` is a plain index, not a unique one.

Multiple callers of `selectBlockByLeftUuid` use `.executeAsOneOrNull()` (lines 604, 616, 644, 706 of `SqlDelightBlockRepository.kt`). When two rows match, `executeAsOneOrNull()` throws:
```
IllegalStateException: Query returned more than one row
```

Concretely: after pasting, calling `indentBlock` on `afterBlock` hits `selectBlockByLeftUuid(block.uuid).executeAsOneOrNull()` at line 706 and crashes. Same for `outdentBlock` (line 616), and `moveBlock` (line 644).

**The pitfalls doc section 1.3 explicitly calls this out and the plan ignores it.**

The fix is a second repository call inside the same actor execute block, updating the right-sibling after inserting the pasted blocks:
```kotlin
// Inside writeActor?.execute { ... }:
// After saveBlocks(pastedBlocks):
val nextSibling = allBlocks.firstOrNull {
    it.parentUuid == afterBlock.parentUuid && it.position > afterBlock.position &&
    siblings.sortedBy { s -> s.position }.first { s -> s.position > afterBlock.position } == it
}
if (nextSibling != null) {
    val lastPastedRootUuid = uuidMap[rootBlocks.last().uuid.value]
    blockRepository.saveBlocksUpdate(listOf(nextSibling.copy(leftUuid = lastPastedRootUuid)))
}
```

Or more simply: find `existingRightSibling` (the sibling with `leftUuid == afterBlockUuid.value`) and call `saveBlocksUpdate([it.copy(leftUuid = lastPastedRootBlock.newUuid)])` in the same transaction.

---

### BUG 2 — Level not adjusted relative to insertion point

**Severity: CRITICAL — silent serialization corruption**

Task 3.4, step 8 explicitly states: "All other fields (content, properties, blockType, **level**, isLoaded) are copied from the original."

This directly contradicts pitfalls doc section 1.6:
> Requirements say "level adjusted relative to insertion point". The canonical algorithm:
> 1. Find `minLevel = clipboard.entries.minOf { it.block.level }`
> 2. Find `targetLevel = focusedBlock.level`
> 3. Each pasted block gets `level = block.level - minLevel + targetLevel`

If clipboard blocks at levels 2, 3 (copied from a deeply nested context) are pasted at a page-level block (level 0), the plan will write rows with `level = 2` and `level = 3` into the DB even though their `parentUuid` places them at level 0 and 1. The `level` field is written directly to the markdown file by `GraphWriter`, causing incorrect indentation on disk. When Logseq or another client reads that file, the hierarchy is wrong.

The `Block.init` only requires `level >= 0`, so no validation catches this.

**Fix**: compute `minClipLevel` before `buildPasted`, then in `buildPasted` set:
```kotlin
level = original.level - minClipLevel + afterBlock.level,
```

For child blocks, the same adjustment applies since their level difference from their parent is preserved by the formula.

---

### BUG 3 — CUT paste never deletes the original blocks

**Severity: CRITICAL — silent data duplication**

Task 3.5 step 9:
```kotlin
if (clip.isCut) _blockClipboard.value = BlockClipboard()
```

The clipboard is cleared but the original blocks are never deleted from the DB.

Pitfalls doc section 1.7 is explicit:
> After successful paste: call `deleteSelectedBlocks` on the original UUIDs (stored in clipboard alongside the block data), then clear clipboard.

The original block UUIDs ARE available in `clip.entries.map { it.block.uuid }`. The plan has no call to `writeDeleteBulk` or `deleteSelectedBlocks` on them.

If CUT is genuinely out of scope for R3, the plan must:
1. State this explicitly in a flag decision
2. Remove the `if (clip.isCut)` branch entirely — dead code with wrong semantics is worse than no code
3. Remove `CUT` from `ClipboardOperation` or add a TODO comment noting the delete step is unimplemented

As written, CUT behaves identically to COPY (no deletion). Any clipboard entry with `operation = CUT` silently duplicates content.

---

## High-Severity Bugs

### BUG 4 — pendingNewBlockUuids not tracked during paste

**Severity: HIGH — pasted blocks invisible until re-query completes**

The plan's `pasteBlocks` does no optimistic in-memory insert. The N pasted blocks only appear in the UI after:
1. `writeActor?.execute { saveBlocks(...) }` suspends and completes
2. `getBlocksForPage(...)` suspends and returns

During this window (potentially 50–200ms on a large graph), if an invalidation fires on `observePage.collectLatest` (e.g., because another block's content was saved), it will re-query and overwrite `_blocks[pageUuid]` with a list that does NOT include the pasted blocks — even though they are already in SQLite. The pasted blocks would vanish from the screen momentarily or permanently if the explicit refresh races with the invalidation.

Pitfalls doc section 3.3 requires atomically adding all N new UUIDs to `pendingNewBlockUuids` before the write and removing them after. Without this, `mergeBlocks()` can't protect the pending blocks from being dropped by concurrent invalidation emissions.

**Fix**: before the write, insert the pasted blocks optimistically into `_blocks` and add all new UUIDs to `pendingNewBlockUuids`. After the refresh, remove them. Pattern from `addNewBlock` (lines 1023–1043).

---

### BUG 5 — `saveBlocks` error result silently discarded

**Severity: HIGH — false undo entry and spurious disk save on write failure**

```kotlin
writeActor?.execute { blockRepository.saveBlocks(pastedBlocks) }
    ?: blockRepository.saveBlocks(pastedBlocks)
```

The `Either<DomainError, Unit>` return is not captured. If `saveBlocks` returns `Either.Left`, execution continues to:
```kotlin
val refreshed = blockRepository.getBlocksForPage(afterBlock.pageUuid).first().getOrNull() ?: emptyList()
_blocks.update { state -> state + (pageUuid to refreshed) }
queueDiskSave(pageUuid)
val after = takePageSnapshot(pageUuid)
record(undo = { ... }, redo = { ... })
```

A `queueDiskSave` fires for a paste that never happened. An undo entry is recorded whose `redo` closure will try to restore to a "state after paste" snapshot that is identical to "state before paste" — invisible to the user but pollutes the undo stack.

**Fix**: capture and check the result:
```kotlin
val result = writeActor?.execute { blockRepository.saveBlocks(pastedBlocks) }
    ?: blockRepository.saveBlocks(pastedBlocks)
result.onLeft { err ->
    logger.error("pasteBlocks: saveBlocks failed: $err")
    return@launch
}
```

---

## Medium-Severity Issues

### ISSUE 6 — `withBlocks` should be a companion object function

In Task 3.1, `withBlocks` is defined as an instance method on `BlockClipboard`:
```kotlin
fun withBlocks(blocks: List<Block>, ...): BlockClipboard = BlockClipboard(blocks.map { ... })
```

The `this` receiver is never used. The call site in Task 3.3 calls it as `BlockClipboard().withBlocks(...)`, allocating a throwaway empty instance. Should be declared in a `companion object`, matching the pattern of factory functions. Not a correctness bug but is an API smell that will confuse future callers who wonder why there's a no-arg constructor call before `withBlocks`.

---

### ISSUE 7 — `Icons.Default.ContentCopy` not in existing import set

`MobileBlockToolbar.kt` imports from:
```
androidx.compose.material.icons.filled.ArrowDownward
androidx.compose.material.icons.filled.ArrowUpward
androidx.compose.material.icons.filled.Add
androidx.compose.material.icons.filled.AttachFile
...
```

`ContentCopy` is not in the existing filled icons import block. The plan correctly specifies `import androidx.compose.material.icons.filled.ContentCopy` must be added. Low risk since it is explicit in the plan, but the Bazel `BUILD` file may also need updating if the icon set is declared as a dep target. Verify that `material-icons-extended` or equivalent is already on the `kmp:jvm_app` target's deps before coding Task 3.7.

---

## Verified Correct (questions answered)

**Q1 (leftUuid chain repair)**: `saveBlocks` uses `insertBlock` (INSERT OR REPLACE) which only writes new rows. It does NOT call `updateBlockLeftUuid` for existing rows. Chain is broken. See BUG 1.

**Q2 (pendingNewBlockUuids)**: Not tracked. See BUG 4.

**Q3 (level field)**: Not adjusted. Plan explicitly copies it unchanged. See BUG 2.

**Q4 (afterBlock.pageUuid vs pageUuid string)**: Consistent. `afterBlock` is found via `_blocks.value[pageUuid]`, so `afterBlock.pageUuid.value == pageUuid` by construction. No mismatch.

**Q5 (saveBlocks UUID collision)**: UUIDs are generated via `UuidGenerator.generateV7()` (timestamp + random). Collision probability negligible. Not a practical concern.

**Q6 (CUT delete)**: Missing. See BUG 3.

**Q7 (leftUuid right-sibling)**: Confirmed broken. `selectBlockByLeftUuid` at line 706 uses `.executeAsOneOrNull()`. With two rows matching, this throws `IllegalStateException`. See BUG 1.

**Q8 (DropZone.ABOVE paste position)**: Not a conflict. Drag-drop uses `moveSelectedBlocks`; Ctrl+V paste uses `pasteBlocks`. The paste target is always "after the focused editing block," which is unambiguous. Acceptable.

**Q9 (addNewBlock pageUuidStr consistency)**: Consistent. `pageUuidStr = sourceBlock.pageUuid.value` in `addNewBlock`; `pageUuid = getPageUuidForBlock(blockUuid)` in `splitBlock`. Both are used correctly to key into `_blocks`. Task 2.1 and 2.2 fixes are internally consistent.

**Q10 (ContentCopy icon)**: Available in Material Icons Extended as `Icons.Default.ContentCopy` (maps to `androidx.compose.material.icons.filled.ContentCopy`). Import is correct. Dependency risk is low since `material-icons-extended` is typically already declared, but should be verified in the Bazel BUILD file.

---

## Pre-implementation Gate

Before any code is written for Epic 3:

1. **Fix BUG 1**: Add right-sibling `leftUuid` repair inside the same actor `execute` block as `saveBlocks`. Use `blockRepository.saveBlocksUpdate(listOf(existingRightSibling.copy(leftUuid = lastPastedRootBlock.uuid)))`.

2. **Fix BUG 2**: Compute `minClipLevel` before `buildPasted`. Apply `level = original.level - minClipLevel + afterBlock.level` in every `pastedBlocks.add(original.copy(...))` call.

3. **Fix BUG 3**: Either implement CUT delete (call `writeDeleteBulk` on `clip.entries.map { it.block.uuid }` after paste succeeds) or explicitly remove the `if (clip.isCut)` branch from the plan and `CUT` from scope.

4. **Fix BUG 4**: Add optimistic insert with `pendingNewBlockUuids` tracking before the DB write.

5. **Fix BUG 5**: Capture the `saveBlocks` result and `return@launch` on `Either.Left`.

The Epic 2 (optimistic position) fixes are clean and can proceed immediately. Epic 1 (disk persistence) verification can proceed immediately. Epic 3 is blocked until the above are resolved.
