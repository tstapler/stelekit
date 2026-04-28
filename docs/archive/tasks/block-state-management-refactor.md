# Block State Management Refactor

**Status**: Planning  
**Created**: 2026-04-11  
**Triggered by**: Race condition where edited content disappears due to reactive DB emissions overwriting optimistic UI state

---

## Epic Overview

### Problem Statement

Block state is managed in three uncoordinated locations:

1. **JournalsViewModel** maintains `_uiState.blocks: Map<String, List<Block>>` and has reactive `blockRepository.getBlocksForPage().collect` observers that overwrite UI state. `applyContentChange()` saves to the DB and updates the map directly, but the reactive collector re-emits and can clobber the optimistic update.

2. **PageView** uses `remember { mutableStateOf<List<Block>>(emptyList()) }` — a completely independent block state. It collects from `blockRepository.getBlocksForPage()` with the same race condition.

3. **LogseqViewModel** has `saveBlockContent()` with debounced DB writes via `DebounceManager` + disk writes via `GraphWriter.queueSave()`. It does NOT update any block UI state — it only queues persistence. It also duplicates all block operations (indent, outdent, move, split, merge, backspace, focus) that JournalsViewModel already has, but without undo/redo support.

The version-based merge in both JournalsViewModel and PageView (`if (local.version > incoming.version) keep local`) is a band-aid. It works only when the local version counter stays ahead, but breaks when:
- Multiple blocks are edited in rapid succession (version monotonicity depends on timing)
- Structural operations (indent, move) reset the block list from a fresh DB query
- The reactive collector fires between `saveBlock()` and the `_uiState.update{}` in `applyContentChange()`

### Root Cause

The architecture violates a fundamental principle: **there must be a single source of truth for any given piece of state**. Currently, the "truth" for a page's blocks lives in three competing locations, each with its own update cadence.

### Success Metrics

- A single `BlockStateManager` class owns block state for any active page, whether rendered by JournalsView or PageView.
- Content edits are optimistic: the UI state updates immediately; the DB write is async.
- Reactive DB emissions are treated as confirmations that merge with (not overwrite) local state.
- Undo/redo works on all pages, not just journals.
- `LogseqViewModel` no longer duplicates block operations from `JournalsViewModel`.
- The race condition (edit, reactive re-emit, content disappears) is structurally impossible.
- All existing tests in `JournalsViewModelTest.kt` and `JournalsViewModelEditorTest.kt` continue to pass.

---

## Architecture Decisions

### ADR-1: Centralized BlockStateManager vs. shared ViewModel base class

**Question**: How should block state be unified — through a standalone state manager injected into ViewModels, or through a shared base ViewModel class?

**Context**: JournalsViewModel and LogseqViewModel both contain block operations (indent, outdent, move, split, merge, backspace, add, focus navigation). JournalsViewModel adds undo/redo and a `blocks: Map<String, List<Block>>` state. LogseqViewModel has debounced `saveBlockContent()`. PageView bypasses both and manages its own local state.

**Decision**: Extract a standalone `BlockStateManager` class that owns:
- Per-page block state (`Map<String, List<Block>>`)
- Optimistic update logic (apply locally, persist async)
- Reactive DB observation with merge semantics
- Undo/redo stacks
- Editing focus state (editingBlockUuid, editingCursorIndex)

ViewModels delegate to `BlockStateManager` rather than inheriting from a base class.

**Rationale**: Composition over inheritance. A standalone manager can be:
- Shared between JournalsViewModel and a new PageViewModel (or injected into LogseqViewModel for PageView use)
- Unit tested in isolation without Compose or ViewModel infrastructure
- Reused in future contexts (e.g., a right-sidebar block editor)

**Rejected alternative**: Shared ViewModel base class. Rejected because JournalsViewModel manages multi-page state (paginated journals list) while PageView manages single-page state — the lifecycle semantics differ. A base class would force artificial abstraction over divergent page-loading strategies.

### ADR-2: Optimistic updates with DB confirmation merge

**Question**: How should the reactive `blockRepository.getBlocksForPage()` flow interact with locally-edited state?

**Context**: SQLDelight reactive queries re-emit whenever the underlying table changes. When `saveBlock()` writes to the DB, the reactive flow emits the new data. If the UI has already applied the change optimistically, the re-emission is redundant. If the UI hasn't applied it yet (e.g., from another editor or background sync), the emission carries new data.

**Decision**: Implement a "dirty set" pattern:
1. When the user edits a block, mark it as "dirty" in `BlockStateManager` with the local content and an incremented version.
2. When the reactive DB flow emits, merge: for each incoming block, if it's in the dirty set AND the local version is higher, keep the local version. If the incoming version is equal or higher, accept it and remove from dirty set.
3. When the DB write completes (after debounce), the next reactive emission will carry the written version, which will match or exceed the local version, clearing the dirty flag.

This is a refinement of the existing version-based merge, but centralized in one place with explicit dirty tracking rather than implicit version comparison scattered across two files.

**Rationale**: The dirty set makes the optimistic/confirmed distinction explicit. A block is either "being edited locally" (dirty) or "confirmed by DB" (clean). This is simpler to reason about than version arithmetic.

### ADR-3: PageView gets a PageViewModel

**Question**: Should PageView continue to be a stateless composable that manages blocks internally, or should it get a proper ViewModel?

**Context**: PageView currently receives `LogseqViewModel` and calls its block operations, but manages its own `blocks` state via `remember`. This means PageView's block state is lost on recomposition, and there's no undo/redo. LogseqViewModel duplicates all the block operations that JournalsViewModel has, but without undo/redo.

**Decision**: Create `PageViewModel` that delegates block operations to `BlockStateManager`. Remove the duplicated block operations from `LogseqViewModel`. PageView receives `PageViewModel` instead of `LogseqViewModel` for block operations.

**Rationale**: This gives PageView the same capabilities as JournalsView (undo/redo, optimistic updates, no race conditions) with zero code duplication. LogseqViewModel becomes thinner — it handles app-level concerns (graph loading, navigation, settings) and creates/provides PageViewModels.

---

## Implementation Stories

### Story 1: Extract BlockStateManager with optimistic update logic

**Priority**: Must-have  
**Depends on**: Nothing  

**Description**: Create `BlockStateManager` in `dev.stapler.stelekit.ui.state` package that consolidates the block state management currently split across JournalsViewModel, PageView, and LogseqViewModel.

**Acceptance Criteria**:
- `BlockStateManager` holds `StateFlow<Map<String, List<Block>>>` for per-page block state
- `BlockStateManager` holds editing focus state (`editingBlockUuid`, `editingCursorIndex`, `collapsedBlockUuids`)
- `observePage(pageUuid)` starts a reactive DB collection for a page with dirty-set merge logic
- `unobservePage(pageUuid)` cancels the collection and removes state
- `updateBlockContent(blockUuid, content)` applies optimistically (updates local state + increments version) and persists via `blockRepository.saveBlock()`
- Dirty set tracks which blocks have local edits not yet confirmed by DB
- Constructor takes `BlockRepository`, `GraphLoader`, `CoroutineScope`
- All methods are unit-testable without Compose

**Files to create**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/state/BlockStateManager.kt`

**Files to create (test)**:
- `kmp/src/commonTest/kotlin/com/logseq/kmp/ui/state/BlockStateManagerTest.kt`

**Known Issues**:

- **Race: observePage called before DB has blocks**: If `observePage()` is called for a page whose blocks haven't been loaded from disk yet, the initial emission will be empty. Mitigation: check `page.isContentLoaded` and trigger `graphLoader.loadFullPage()` before starting observation, matching the existing pattern in JournalsViewModel line 197-199.

- **Dirty set memory leak**: If a block is marked dirty but the DB write fails silently, the dirty entry persists forever. Mitigation: add a TTL or clear dirty entries when the page is unobserved.

---

### Story 2: Move undo/redo into BlockStateManager

**Priority**: Must-have  
**Depends on**: Story 1  

**Description**: Move the undo/redo stack from JournalsViewModel into BlockStateManager so all pages get undo/redo support.

**Acceptance Criteria**:
- `BlockStateManager` exposes `canUndo: StateFlow<Boolean>`, `canRedo: StateFlow<Boolean>`
- `record(undo, redo)` pushes to the undo stack (max 100 entries, matching current behavior)
- `undo()` and `redo()` execute the stored lambdas
- Content change, indent, outdent, move, split, merge, and backspace all record undo entries
- Undo/redo works identically to current JournalsViewModel behavior

**Files to modify**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/state/BlockStateManager.kt`

**Files to modify (test)**:
- `kmp/src/commonTest/kotlin/com/logseq/kmp/ui/state/BlockStateManagerTest.kt`

**Known Issues**:

- **Cross-page undo**: If the user edits journal page A, scrolls to journal page B, and presses Ctrl+Z, should it undo on page A or page B? Currently it undoes the most recent operation regardless of page. Decision: keep current behavior (global undo stack) for now, since it matches user expectations in an outliner.

---

### Story 3: Move block operations into BlockStateManager

**Priority**: Must-have  
**Depends on**: Story 2  

**Description**: Move all block manipulation operations (indent, outdent, moveUp, moveDown, splitBlock, mergeBlock, handleBackspace, addNewBlock, addBlockToPage, focusPrevious, focusNext) from JournalsViewModel and LogseqViewModel into BlockStateManager.

**Acceptance Criteria**:
- All block operations live in `BlockStateManager` with proper undo/redo recording
- `BlockStateManager.splitBlock()`, `mergeBlock()`, `handleBackspace()` match the JournalsViewModel implementations (which have correct undo recording via snapshots)
- `BlockStateManager.addNewBlock()` uses `blockRepository.splitBlock()` (JournalsViewModel pattern) not the manual sibling-shifting approach in LogseqViewModel
- Format events (`SharedFlow<FormatAction>`) are exposed from `BlockStateManager`
- `requestFormat(action)` method available

**Files to modify**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/state/BlockStateManager.kt`

**Files to modify (test)**:
- `kmp/src/commonTest/kotlin/com/logseq/kmp/ui/state/BlockStateManagerTest.kt`

**Known Issues**:

- **Two implementations of addNewBlock**: JournalsViewModel uses `blockRepository.splitBlock(currentBlockUuid, block.content.length)` while LogseqViewModel manually creates a new block and shifts siblings. These have different semantics — split at end-of-content vs. insert-after-with-shift. Mitigation: use the JournalsViewModel approach (splitBlock at content length) as the canonical implementation, since it's simpler and correctly handled by the repository's atomic operation.

- **Snapshot-based undo for structural ops is expensive**: `takePageSnapshot()` fetches all blocks from DB before and after each structural operation. For pages with hundreds of blocks this is wasteful. Acceptable for now; a future optimization could use command-based undo (record the inverse operation) instead of snapshot-based undo.

---

### Story 4: Wire JournalsViewModel to BlockStateManager

**Priority**: Must-have  
**Depends on**: Story 3  

**Description**: Refactor JournalsViewModel to delegate all block state and operations to BlockStateManager. Remove duplicated logic.

**Acceptance Criteria**:
- JournalsViewModel constructor takes `BlockStateManager` (or creates one internally)
- `_uiState.blocks` is removed from `JournalsUiState`; JournalsView reads blocks from `BlockStateManager.blocks`
- `observeBlocksForPages()` delegates to `blockStateManager.observePage()` / `unobservePage()`
- `updateBlockContent()`, `indentBlock()`, `outdentBlock()`, etc. delegate to `BlockStateManager`
- `applyContentChange()` private method is removed (logic now in `BlockStateManager.updateBlockContent()`)
- `_uiState` retains: `pages`, `isLoading`, `hasMore`, `loadingPageUuids` (page-level concerns)
- Editing state (`editingBlockUuid`, `editingCursorIndex`, `collapsedBlockUuids`) comes from `BlockStateManager`
- All existing tests in `JournalsViewModelTest.kt` pass (may need minor wiring changes)

**Files to modify**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/screens/JournalsViewModel.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/screens/JournalsView.kt`

**Files to modify (test)**:
- `kmp/src/commonTest/kotlin/com/logseq/kmp/ui/screens/JournalsViewModelTest.kt`
- `kmp/src/commonTest/kotlin/com/logseq/kmp/ui/screens/JournalsViewModelEditorTest.kt`

**Known Issues**:

- **JournalsUiState shape change breaks callers**: Removing `blocks` from `JournalsUiState` means JournalsView must collect from two sources: `journalsViewModel.uiState` for pages and `blockStateManager.blocks` for blocks. Mitigation: JournalsView already destructures `uiState` into local vals; replace `uiState.blocks[page.uuid]` with `blockStateManager.blocksForPage(page.uuid)`.

---

### Story 5: Create PageViewModel and wire PageView

**Priority**: Must-have  
**Depends on**: Story 3  

**Description**: Create `PageViewModel` that delegates block operations to `BlockStateManager`. Refactor PageView to use it instead of LogseqViewModel + local state.

**Acceptance Criteria**:
- `PageViewModel` created in `dev.stapler.stelekit.ui.screens` package
- Takes `BlockStateManager`, `GraphLoader`, `GraphWriter`, `CoroutineScope`
- Exposes block state for its page via `BlockStateManager`
- `saveBlockContent()` writes to DB + queues disk save via `GraphWriter` (currently in LogseqViewModel)
- PageView removes `var blocks by remember { mutableStateOf(...) }` local state
- PageView removes `LaunchedEffect(page.uuid) { blockRepository.getBlocksForPage().collect }` — observation is handled by BlockStateManager
- PageView gets undo/redo support via BlockStateManager
- MobileBlockToolbar in PageView gains format action support (currently only in JournalsView)

**Files to create**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/screens/PageViewModel.kt`

**Files to modify**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/screens/PageView.kt`

**Known Issues**:

- **PageViewModel lifecycle**: PageView is a composable navigated to by screen state. The PageViewModel must be created when navigating to a page and cleaned up (unobserve) when leaving. Mitigation: create PageViewModel in `App.kt` within the `Screen.PageView` branch, keyed on `page.uuid`, so it's recreated on page change and disposed on navigation away.

- **Disk write needs Page object**: `GraphWriter.queueSave()` takes a `Page` and full block list. Currently PageView receives `page: Page` as a parameter. PageViewModel needs access to this. Mitigation: pass `Page` to PageViewModel constructor or `observePage()`.

---

### Story 6: Remove duplicated block operations from LogseqViewModel

**Priority**: Must-have  
**Depends on**: Story 5  

**Description**: Remove all block manipulation methods from LogseqViewModel that are now handled by BlockStateManager through JournalsViewModel and PageViewModel.

**Acceptance Criteria**:
- Remove from LogseqViewModel: `indentBlock()`, `outdentBlock()`, `moveBlockUp()`, `moveBlockDown()`, `moveBlock()`, `addNewBlock()`, `addBlockToPage()`, `splitBlock()`, `mergeBlock()`, `handleBackspace()`, `focusPreviousBlock()`, `focusNextBlock()`, `saveBlockContent()`, `getBlockContent()`
- Remove `editingBlockId` and `editingCursorIndex` from `AppState` (now in BlockStateManager)
- Remove `pendingBlockContent` map and `debounceManager` from LogseqViewModel (debouncing moves to BlockStateManager or PageViewModel)
- LogseqViewModel retains: graph loading, navigation, settings, page operations (rename, delete, favorite), search, command palette
- `requestEditBlock()` on LogseqViewModel removed (delegated to BlockStateManager)

**Files to modify**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/LogseqViewModel.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/AppState.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/App.kt` (update wiring)

**Known Issues**:

- **Keyboard shortcuts for undo/redo**: In `App.kt` lines 241-247, Ctrl+Z and Ctrl+Y are wired to `journalsViewModel.undo()` and `journalsViewModel.redo()`. After refactor, these should route to `blockStateManager.undo()` and `blockStateManager.redo()` regardless of which screen is active. Mitigation: expose the shared `BlockStateManager` at the `GraphContent` level and wire shortcuts there.

---

### Story 7: Integrate disk persistence into BlockStateManager

**Priority**: Must-have  
**Depends on**: Story 6  

**Description**: Move the debounced disk-write logic (currently in LogseqViewModel.saveBlockContent) into BlockStateManager or a companion class, so that block edits on any page automatically queue disk saves.

**Acceptance Criteria**:
- When `updateBlockContent()` is called, after the DB write, a disk save is queued via `GraphWriter.queueSave()`
- Debouncing is per-block (matching current `DebounceManager` behavior) for DB writes
- Debouncing is per-page (matching current `GraphWriter.queueSave()` behavior) for disk writes
- `flush()` method available to force all pending saves (for app pause/shutdown, matching current `viewModel.savePendingChanges()`)
- GraphWriter receives the full block list for the page (required for markdown serialization)

**Files to modify**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/state/BlockStateManager.kt`

**Known Issues**:

- **Stale block list for disk write**: If `GraphWriter.queueSave()` is called with the block list at time T, but more edits happen before the debounced write fires at T+500ms, the written file may be stale. Current behavior already has this issue via `LogseqViewModel.saveBlockContent()` which fetches blocks from DB after debounce. Mitigation: at write time, read the current block list from BlockStateManager (which has the latest optimistic state) rather than from the DB.

---

### Story 8: Add FakeBlockStateManager for tests

**Priority**: Should-have  
**Depends on**: Story 3  

**Description**: Create a test fake of BlockStateManager that can be used by ViewModel tests without a real BlockRepository.

**Acceptance Criteria**:
- Extract a `BlockStateManager` interface or open class that can be faked
- `FakeBlockStateManager` provides in-memory block storage with immediate "DB confirmation"
- Existing `FakeBlockRepository` continues to work for lower-level tests
- ViewModel tests can verify block operations without SQLDelight

**Files to create**:
- `kmp/src/commonTest/kotlin/com/logseq/kmp/ui/state/FakeBlockStateManager.kt`

---

### Story 9: Integration test for the race condition

**Priority**: Should-have  
**Depends on**: Story 4  

**Description**: Write a targeted test that reproduces the original race condition (edit -> reactive re-emit -> content disappears) and verifies it no longer occurs.

**Acceptance Criteria**:
- Test uses `FakeBlockRepository` with a controllable emission delay
- Sequence: set up page with block, call `updateBlockContent("new text")`, trigger a DB re-emission with the old content, assert block state still shows "new text"
- Test verifies dirty-set merge correctly preserves local edits
- Test verifies that once DB catches up (emits with matching version), dirty flag is cleared

**Files to create**:
- `kmp/src/commonTest/kotlin/com/logseq/kmp/ui/state/BlockStateManagerRaceTest.kt`

---

## Implementation Sequence

```
Story 1: BlockStateManager core (state + observe + optimistic update)
    |
    v
Story 2: Undo/redo in BlockStateManager
    |
    v
Story 3: Block operations in BlockStateManager
    |
    +---> Story 4: Wire JournalsViewModel
    |         |
    +---> Story 5: Create PageViewModel + wire PageView
    |         |
    |         v
    |    Story 6: Remove duplicates from LogseqViewModel
    |         |
    |         v
    |    Story 7: Disk persistence integration
    |
    +---> Story 8: FakeBlockStateManager (can start after Story 3)
    |
    +---> Story 9: Race condition integration test (after Story 4)
```

Stories 4 and 5 can proceed in parallel after Story 3. Story 6 requires both 4 and 5 to be complete. Story 8 can start as soon as Story 3 defines the BlockStateManager API.

---

## Known Issues

### Concurrency: Dirty set vs. structural operations [SEVERITY: Medium]

**Description**: When a structural operation (indent, outdent, move) is performed, `BlockStateManager` takes a snapshot, delegates to `blockRepository.indentBlock()`, then fetches the new block list. The reactive DB emission will fire with the post-indent state. If a block was in the dirty set (being edited), the structural operation's DB write may carry stale content for that block.

**Mitigation**:
- Before performing structural operations, flush any dirty blocks to DB first
- Structural operations already use snapshot-based undo which captures the full state
- Add a `flushDirtyBlocks()` call at the start of indent/outdent/move/split/merge

**Files Likely Affected**:
- `BlockStateManager.kt` (all structural operation methods)

### Data integrity: Version counter overflow [SEVERITY: Low]

**Description**: The version counter is a `Long` (Block.version). If incremented on every keystroke, at 10 keystrokes/second it would take ~29 billion years to overflow. Not a real concern.

### Integration: GraphWriter block list freshness [SEVERITY: Medium]

**Description**: `GraphWriter.queueSave()` receives a `List<Block>` snapshot. With debounced writes, the snapshot may be stale by the time it's written to disk.

**Mitigation**:
- BlockStateManager provides a `getBlocksForPage(pageUuid): List<Block>` that returns the current optimistic state (dirty blocks included)
- Disk write lambda fetches blocks from BlockStateManager at write time, not at queue time
- This matches the current behavior where `LogseqViewModel.saveBlockContent()` fetches blocks from DB after debounce

### UX: Undo/redo scope when switching between journals and pages [SEVERITY: Low]

**Description**: With a shared `BlockStateManager`, undo/redo is global. If the user edits a journal, navigates to a page, edits there, and presses undo, it will undo the page edit. This is the same behavior as the current JournalsViewModel (which has a single undo stack across all visible journal pages).

**Mitigation**: Acceptable behavior for an outliner. If per-page undo is desired in the future, the undo stack can be partitioned by `pageUuid` as a separate enhancement.

---

## Migration Safety

Each story is designed to be independently deployable:

- **Story 1-3**: Pure additions. No existing code is modified. BlockStateManager is created alongside existing code.
- **Story 4**: JournalsViewModel delegates to BlockStateManager. If tests break, the old code is still in git. Feature-flag possible by keeping old `_uiState.blocks` as a fallback.
- **Story 5**: PageView gets new PageViewModel. Old LogseqViewModel methods still exist until Story 6.
- **Story 6**: Cleanup. Only safe to merge after Stories 4 and 5 are verified working.
- **Story 7**: Disk persistence wiring. Can be tested by verifying files on disk match expected content.

The key safety property: **Stories 1-3 don't modify any existing files**, so they can't break anything. Stories 4-7 modify existing files but each has a clear rollback path.
