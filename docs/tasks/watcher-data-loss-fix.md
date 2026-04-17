# Watcher Data-Loss Fix

## Epic Overview

**User value**: A user's journal content must never be silently destroyed by an external file change (sync daemon, git, Logseq desktop, Dropbox, etc.) touching a file while the app is running. The three bugs documented here form a chain: Bug 1 creates false positive detections, Bug 2 ensures those false positives wipe data, and Bug 3 ensures the wiper runs even when the user recently edited the page.

**Production incident reference**: 16:55 blank-overwrite — user edited today's journal, clicked away, a sync tool touched the file, the watcher fired, `suppress()` was never called because `editingBlockId` was null, `parseAndSavePage` ran with blank content, blocks were deleted.

---

## Bug Inventory

| ID | File | Method | Severity | Status |
|----|------|--------|----------|--------|
| B1 | `FileRegistry.kt` | `scanDirectory` | High | Confirmed by `FileRegistryTest` |
| B2 | `GraphLoader.kt` | `parseAndSavePage` | Critical | Confirmed by `ExternalChangeConflictTest` |
| B3 | `StelekitViewModel.kt` | `observeExternalFileChanges` | Critical | Confirmed by `ExternalChangeConflictTest` |

---

## Task 1 — Fix `scanDirectory` to initialise content hashes (Bug B1)

### Context

`scanDirectory` (called during `loadGraph` via `loadJournalsImmediate` and `loadPages`) populates `modTimes` for every `.md` file it finds but never writes to `contentHashes`. When the file watcher later calls `detectChanges`, the content-hash guard at line 100:

```kotlin
if (contentHashes[filePath] == newHash)
```

compares against `null` for any file that was not processed through `detectChanges` first. `null != newHash` is always true, so every mtime change — even a no-op `touch` by a sync tool — is treated as a genuine external modification and triggers `parseAndSavePage`.

### Acceptance criteria

The following test must pass with the assertion meaning inverted (currently the test asserts the broken behavior; after the fix it must assert the correct behavior):

```
FileRegistryTest: BUG - scanDirectory without prior detectChanges leaves contentHash uninitialised
ExternalChangeConflictTest: BUG - mtime bump after loadGraph triggers re-parse even with identical content
```

Both tests will need their assertion comment updated to reflect fixed behavior (see "Test updates" below).

### Implementation

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/FileRegistry.kt`

In `scanDirectory`, after recording `modTimes[filePath] = modTime`, read the file content and store the hash:

```kotlin
val content = fileSystem.readFile(filePath)
if (content != null) contentHashes[filePath] = content.hashCode()
```

This mirrors exactly what `detectChanges` does for new files (lines 92-94). The additional `readFile` call per file at startup is acceptable: `scanDirectory` is called once per graph load for journals and pages directories, and the files must already be read shortly after for parsing anyway.

**Note**: `scanDirectory` currently does not have a `content` variable in scope. The map lambda maps `fileName -> FileEntry`. The read must happen inside that lambda, before or after the `modTime` assignment. The `FileEntry` does not carry content (it is metadata-only by design), so the hash is stored only in `contentHashes` and not returned.

### Test updates required

`FileRegistryTest: BUG - scanDirectory without prior detectChanges leaves contentHash uninitialised`

Change the assertion from `assertEquals(1, changes.changedFiles.size, "BUG confirmed...")` to `assertEquals(0, changes.changedFiles.size, "mtime-only change after scanDirectory must NOT trigger re-parse once contentHash is initialised")`.

`ExternalChangeConflictTest: BUG - mtime bump after loadGraph triggers re-parse even with identical content`

Change the assertion from `assertEquals(1, ...)` to `assertEquals(0, ..., "After fix: mtime-only bump after loadGraph must not trigger re-parse")`.

### Risk

Low. `readFile` is called in the same `loadGraph` path anyway (files are parsed immediately after scanning). The only behavioral change is storing the hash upfront. No write paths are affected.

---

## Task 2 — Guard `parseAndSavePage` against blank-content overwrites (Bug B2)

### Context

At line 850-865 of `GraphLoader.kt`, the existing safety guard already handles one case:

```kotlin
val fileHasContent = content.trim().isNotEmpty()
if (blocksToSave.isEmpty() && fileHasContent) {
    // parser failure: skip
} else {
    writeActor.deleteBlocksForPage(pageUuid)
    ...
}
```

This correctly suppresses a parse error (non-empty file yields zero blocks). However when `content` is empty (`""`), `fileHasContent` is `false`, so the `else` branch runs unconditionally: `deleteBlocksForPage` fires and wipes in-memory state.

### Acceptance criteria

```
ExternalChangeConflictTest: FIX NEEDED - parseAndSavePage with blank content should not destroy existing blocks
```

This test must flip from documenting broken behavior to passing with the fix. The test pre-populates two blocks ("Important meeting notes", "Action items"), calls `parseAndSavePage(filePath, "", ParseMode.FULL)`, and asserts that the blocks survive.

### Implementation

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`

Extend the safety guard at the `mode == ParseMode.FULL` branch (around line 850) to also check whether existing in-memory blocks would be destroyed by an incoming empty parse:

```kotlin
if (mode == ParseMode.FULL) {
    val fileHasContent = content.trim().isNotEmpty()
    val existingBlockCount = existingBlocks.size  // already fetched above at line 828

    if (blocksToSave.isEmpty() && fileHasContent) {
        // Case 1 (existing): parser yielded no blocks for non-empty file — likely parse error
        logger.error(
            "Parser returned no blocks for non-empty file '$filePath' " +
            "(${content.length} chars) — skipping block update to prevent data loss"
        )
    } else if (blocksToSave.isEmpty() && !fileHasContent && existingBlockCount > 0) {
        // Case 2 (new): incoming content is blank but we have live blocks — treat as
        // suspicious external write, do not destroy in-memory state
        logger.warn(
            "Blank-file parse for '$filePath' would destroy $existingBlockCount existing block(s) " +
            "— skipping destructive write. Emitting WriteError for user notification."
        )
        _writeErrors.tryEmit(WriteError(filePath, existingBlockCount,
            IllegalStateException("Blank external overwrite suppressed to prevent data loss")))
    } else {
        writeActor.deleteBlocksForPage(pageUuid)
        if (blocksToSave.isNotEmpty()) {
            writeActor.saveBlocks(blocksToSave).onFailure { e ->
                logger.error("saveBlocks failed for $filePath (${blocksToSave.size} blocks)", e)
                _writeErrors.tryEmit(WriteError(filePath, blocksToSave.size, e))
            }
        }
    }
}
```

The `existingBlockCount` variable is derived from `existingBlocks` which is already fetched at line 828. No additional repository call is needed.

**Behavioral contract after the fix**:

| Incoming content | Parsed blocks | Existing blocks | Action |
|-----------------|---------------|-----------------|--------|
| non-empty | > 0 | any | Normal: delete + save |
| non-empty | 0 | any | Skip (parser failure guard, existing behavior) |
| empty | 0 | 0 | Normal: delete (no-op) + no blocks to save |
| empty | 0 | > 0 | NEW: skip + emit WriteError |

The third row (empty file, no existing blocks) is the new-journal creation path. It must continue to work so that creating a fresh `.md` file results in an empty page.

### Test updates required

`ExternalChangeConflictTest: FIX NEEDED - parseAndSavePage with blank content should not destroy existing blocks`

The test body does not need to change. The `assertTrue(blocks.any { it.content == "Important meeting notes" }, "FIX NEEDED: ...")` assertion will now pass. Update the test name to remove "FIX NEEDED" prefix and update the failure message to describe the expected invariant rather than the bug.

`ExternalChangeConflictTest: KNOWN BUG - external blank overwrite silently replaces in-memory content when not editing`

This integration-level test creates a `GraphLoader` without a subscriber calling `suppress()`, then calls `parseAndSavePage` with blank content. After Bug B2 is fixed, `assertFalse(hasContentAfter)` will flip to false (blocks are preserved), so the test assertion will fail. Update the test to assert `assertTrue(hasContentAfter || blocksAfterBlankParse.isNotEmpty())` and rename it to reflect the correct post-fix behavior.

### Risk

Medium. The guard must not block legitimate empty-file cases (new journal, explicit user delete-all). The invariant `existingBlockCount > 0` is the key discriminant: if there are no existing blocks, an empty file is not a destructive event. A new integration test covering the "create fresh journal file" path should be added to the test suite to prevent regression.

---

## Task 3 — Extend `observeExternalFileChanges` to protect non-editing page state (Bug B3)

### Context

`observeExternalFileChanges` in `StelekitViewModel` (line 791-822) has a hard exit if `editingBlockId` is null:

```kotlin
val editingBlockUuid = state.editingBlockId ?: return@collect
```

This means protection only activates while the cursor is inside a block. The moment the user clicks away (clearing `editingBlockId`), any watcher event for the current page flows straight to `parseAndSavePage` without generating a conflict dialog. For the 5-second watcher polling interval, this is a wide window.

Additionally, because `GraphWriter` calls `markFileWrittenByUs` only after a save completes, the debounce delay (300ms in `BlockStateManager.diskWriteDebounce`) means there is a period where the file has been written but `markWrittenByUs` has not yet been called. This is a narrower race but it is real.

### Acceptance criteria

The following behaviors must be verifiable by test:

1. When the current screen is `PageView` for the changed file AND `blockStateManager` has any dirty blocks for that page (i.e., `dirtyBlocks` is non-empty for the page's blocks), `suppress()` is called and a `DiskConflict` is raised — even when `editingBlockId` is null.

2. When the current screen is `PageView` but the page has no dirty blocks and the user is not editing, the external change flows through normally (watcher should be allowed to reload clean pages).

### Implementation

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

Replace the `editingBlockId ?: return@collect` early-exit with a two-tier check:

```kotlin
private fun observeExternalFileChanges() {
    scope.launch {
        graphLoader.externalFileChanges.collect { event ->
            val state = _uiState.value
            val currentPage = (state.currentScreen as? Screen.PageView)?.page
                ?: return@collect
            if (currentPage.filePath != event.filePath) return@collect

            val editingBlockUuid = state.editingBlockId
            val pageHasDirtyBlocks = blockStateManager
                ?.blocks?.value?.get(currentPage.uuid)
                ?.any { block ->
                    blockStateManager.dirtyBlocks.containsKey(block.uuid)
                } ?: false

            // Protect if: actively editing a block on this page OR page has unsaved changes
            val shouldProtect = editingBlockUuid != null || pageHasDirtyBlocks
            if (!shouldProtect) return@collect

            event.suppress()

            val localContent = if (editingBlockUuid != null) {
                blockStateManager
                    ?.blocks?.value?.get(currentPage.uuid)
                    ?.find { it.uuid == editingBlockUuid }?.content
                    ?: blockRepository.getBlockByUuid(editingBlockUuid).first().getOrNull()?.content
                    ?: ""
            } else {
                // No focused block — use the page's first dirty block content as the conflict anchor
                blockStateManager
                    ?.blocks?.value?.get(currentPage.uuid)
                    ?.firstOrNull { blockStateManager.dirtyBlocks.containsKey(it.uuid) }
                    ?.content ?: ""
            }

            _uiState.update { it.copy(
                diskConflict = DiskConflict(
                    pageUuid = currentPage.uuid,
                    pageName = currentPage.name,
                    filePath = event.filePath,
                    editingBlockUuid = editingBlockUuid ?: "",
                    localContent = localContent,
                    diskContent = event.content
                )
            )}
        }
    }
}
```

**Required `BlockStateManager` change**: `dirtyBlocks` is currently `private`. It must be exposed as a read-only view:

```kotlin
// BlockStateManager.kt — add alongside existing state flows
val dirtyBlockUuids: Set<String> get() = dirtyBlocks.keys.toSet()
```

The ViewModel then queries `blockStateManager.dirtyBlockUuids` rather than accessing the internal map directly.

**`DiskConflict` model change**: `editingBlockUuid` is currently non-nullable in the data class. When protection is triggered with no focused block, a sentinel empty string is a pragmatic choice that avoids a larger model change. The conflict resolution methods (`keepLocalChanges`, `manualResolve`, `saveAsNewBlock`) must be audited to ensure they do not crash when `editingBlockUuid` is empty:

- `keepLocalChanges`: does not use `editingBlockUuid` — safe.
- `acceptDiskVersion`: does not use `editingBlockUuid` — safe.
- `saveAsNewBlock`: does not use `editingBlockUuid` — safe.
- `manualResolve`: calls `blockRepository.getBlockByUuid(conflict.editingBlockUuid)` — will return a `Result.failure` for an empty UUID. Add an early return: `if (conflict.editingBlockUuid.isBlank()) { keepLocalChanges(); return }`.

### Test updates required

New tests to add to `ExternalChangeConflictTest` (or a new `ViewModelExternalChangeTest`):

1. **"external change on viewed page with dirty blocks but no editing block triggers conflict"** — set up a `StelekitViewModel` with a `BlockStateManager` that has dirty blocks, clear `editingBlockId`, fire an external change event, assert `diskConflict` is set.

2. **"external change on viewed page with no dirty blocks and not editing is allowed through"** — same setup but no dirty blocks, assert `diskConflict` is null and `parseAndSavePage` ran.

3. **"manualResolve with blank editingBlockUuid falls back to keepLocalChanges"** — verify the guard added to `manualResolve`.

### Risk

High. This is a ViewModel change with UI implications. The `DiskConflict` dialog UI (not analyzed here) may need to handle the case where `editingBlockUuid` is blank — e.g., the dialog might show a block-level diff that makes no sense if no block is focused. The dialog should be tested manually after the fix. Additionally, the `pageHasDirtyBlocks` check must be efficient: it iterates the block list for the current page once per watcher event (every 5 seconds), which is acceptable.

---

## Dependency Graph

```
[B1: scanDirectory content hash]
         |
         | (reduces false positives entering the pipeline)
         v
[B2: parseAndSavePage blank guard]   [B3: observeExternalFileChanges scope]
         |                                         |
         +------- both independently fix ----------+
                  the data-loss chain
```

B1 is independent and should be implemented first. It reduces the surface area for B2 and B3 (fewer false watcher events). B2 and B3 address orthogonal layers (storage vs. UI) and can be implemented in parallel after B1, though B3's test for dirty-block detection implicitly depends on B2 not wiping blocks before the test can observe them.

**Recommended sequence**: B1 → B2 (in parallel with) B3, then run the full test suite before merging.

---

## Testing Strategy

### Existing tests that serve as acceptance criteria

| Test | File | Current behavior | Expected after fix |
|------|------|------------------|--------------------|
| `BUG - scanDirectory without prior detectChanges leaves contentHash uninitialised` | `FileRegistryTest` | Asserts `changedFiles.size == 1` (bug present) | Assert `changedFiles.size == 0` (fixed) |
| `BUG - mtime bump after loadGraph triggers re-parse even with identical content` | `ExternalChangeConflictTest` | Asserts `changedFiles.size == 1` (bug present) | Assert `changedFiles.size == 0` (fixed) |
| `FIX NEEDED - parseAndSavePage with blank content should not destroy existing blocks` | `ExternalChangeConflictTest` | Fails (blocks are destroyed) | Passes (blocks preserved) |
| `KNOWN BUG - external blank overwrite silently replaces in-memory content when not editing` | `ExternalChangeConflictTest` | `assertFalse(hasContentAfter)` passes (bug confirmed) | Assertion flips — test must be updated |

### Additional tests to write

**For B1 (FileRegistry)**

- `scanDirectory followed by detectChanges with changed content IS reported` — verifies that initialising the hash in `scanDirectory` does not suppress genuine changes.
- `scanDirectory on empty file does not crash` — edge case: `readFile` returns `null` or `""`.

**For B2 (GraphLoader)**

- `parseAndSavePage with empty content on page with zero existing blocks does not emit WriteError` — verifies the new-journal creation path is unaffected.
- `parseAndSavePage with empty content on page with existing blocks emits WriteError and preserves blocks` — the core fix invariant.
- `parseAndSavePage with whitespace-only content on page with existing blocks is treated as blank` — `"   \n"` should trigger the guard (`content.trim().isEmpty()`).

**For B3 (ViewModel)**

- `observeExternalFileChanges protects page with dirty blocks even when editingBlockId is null`
- `observeExternalFileChanges allows reload of clean page not being edited`
- `manualResolve with blank editingBlockUuid delegates to keepLocalChanges`

---

## Known Risks and Edge Cases

### Race: watcher fires between `GraphWriter.write` and `markFileWrittenByUs`

`GraphWriter` writes the file, then the coroutine resumes and calls `markWrittenByUs`. If the watcher fires in the gap (5-second polling interval makes this unlikely but not impossible), Bug B1's fix makes this safer because the hash stored at scan time matches the pre-save content — meaning a mtime-only change after the user's save would still be caught. The true fix for this race is to call `markWrittenByUs` before writing (pre-register the expected hash), but that is outside the scope of this epic.

### New journal file creation (empty file path)

The `parseAndSavePage` guard added in B2 checks `existingBlockCount > 0`. For a brand new journal created by the app with no prior blocks, `existingBlockCount == 0` so the destructive path still runs — which is correct. No regression risk here.

### Android vs Desktop

`FileRegistry` is in `commonMain`. The fix applies to both platforms. On Android, the Storage Access Framework path goes through the same `FileSystem` abstraction, so no platform-specific changes are needed.

### `dirtyBlocks` concurrency

`dirtyBlocks` in `BlockStateManager` is a `mutableMapOf` (not thread-safe). Access from `observeExternalFileChanges` is on the `scope` coroutine dispatcher. All `BlockStateManager` mutations also run on the same scope. As long as the ViewModel scope is single-threaded (the default `Main` dispatcher on all platforms), this is safe. If `BlockStateManager` is ever moved to a multi-threaded dispatcher, `dirtyBlocks` must become a `ConcurrentHashMap`.

### `DiskConflict.editingBlockUuid` as blank sentinel

The blank sentinel avoids a nullable type change in `DiskConflict`. If the model is later refactored (e.g., to carry a list of affected block UUIDs rather than a single one), the sentinel should be replaced with `null` and all callers updated. Document this in the data class KDoc.

### Hash collision in `contentHashes`

`String.hashCode()` is a 32-bit hash. Two different file contents could theoretically produce the same hash, causing a genuine external change to be suppressed. The probability for typical journal file sizes is negligible, but it exists. This is a pre-existing limitation of the design, not introduced by this fix.

---

## Success Criteria

1. All four tests listed in the "Acceptance criteria" rows of the Testing Strategy table pass with assertions reflecting correct (fixed) behavior.
2. `./gradlew jvmTest` runs green with no regressions.
3. Manual smoke test: open today's journal, type content, click away, externally `touch` the file — no data loss, no false conflict dialog.
4. Manual smoke test: open today's journal, type content, click away, externally blank the file — conflict dialog appears, "Keep my changes" preserves the typed content.
5. Manual smoke test: open a page that has never been edited in this session, externally modify it — page reloads automatically (no false positive protection).
