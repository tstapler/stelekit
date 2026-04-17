# Copy / Cut / Paste Blocks

## Epic Overview

### User Value
Users expect `Ctrl+C`, `Ctrl+X`, `Ctrl+V` to work on blocks the same way they work in every text editor and outliner they have ever used. Without clipboard support, users cannot reorganize notes by cutting from one location and pasting elsewhere — the single most common structural editing operation. This gap will be discovered within the first minute of use and erodes trust in the product.

### Success Metrics
- Copy/cut/paste round-trip preserves block tree hierarchy (parent-child ordering) with 100% fidelity for internal paste
- Pasting into an external app (Slack, browser text field) produces valid Markdown-indented bullet text
- `Ctrl+Shift+V` inserts a `((uuid))` block reference and the reference renders correctly
- Undo (`Ctrl+Z`) fully reverses a paste or cut; redo re-applies it
- No regressions to existing `BlockStateManager` selection state or `ExportService` export

### Scope

**In scope:**
- Copy block tree to clipboard (`Ctrl+C` on focused or selected blocks)
- Cut block tree from source and hold in clipboard (`Ctrl+X`)
- Paste as new blocks with new UUIDs below cursor (`Ctrl+V`)
- Paste external Markdown text from system clipboard as new blocks
- Paste as block reference for the first root block (`Ctrl+Shift+V`)
- Undo/redo for all three operations via existing `Command` + `UndoManager` infrastructure
- JVM (Desktop) platform target — primary; Android as secondary via Compose `ClipboardManager`

**Explicitly out of scope for this epic:**
- Pasting image files as asset attachments
- Cross-graph block reference resolution
- Mobile (iOS) clipboard
- Drag-and-drop as a clipboard operation (separate epic: multi-block-selection.md)

### Constraints
- Must not break `BlockStateManager` dirty-set optimistic updates
- Must route writes through the existing `Command` / `UndoManager` pipeline (no ad-hoc direct saves)
- `ClipboardProvider` extension must remain backward-compatible (additive interface change)
- In-process clipboard state lives in `BlockStateManager` — no new singletons

---

## Architecture Decisions

| ADR | File | Decision |
|-----|------|----------|
| ADR-001 | `project_plans/stelekit/decisions/ADR-001-block-clipboard-format.md` | In-process `BlockClipboard` in `BlockStateManager` + Markdown text to system clipboard |
| ADR-002 | `project_plans/stelekit/decisions/ADR-002-paste-uuid-strategy.md` | Standard paste regenerates UUIDs; reference paste appends `((uuid))` text |

---

## Story Breakdown

### Story 1: Clipboard Data Model and ClipboardProvider Extension [1 week]

**User value**: Establishes the data structures and platform abstractions needed by all subsequent stories. No user-visible behavior yet, but all later stories depend on this foundation.

**Acceptance Criteria:**
- `ClipboardBlock` value class compiles and is covered by unit tests
- `BlockClipboard` holds a list of `ClipboardBlock` and exposes `rootBlocks` / `allBlocks` helpers
- `ClipboardProvider` gains `readText(): String?`; both JVM and Android implementations updated
- `BlockStateManager` exposes `blockClipboard: StateFlow<BlockClipboard?>` and `setClipboard()` / `clearClipboard()` methods
- All new code passes `./gradlew jvmTest`

---

#### Task 1.1 — Define `ClipboardBlock` and `BlockClipboard` value objects [2h]

**Objective**: Create the in-process clipboard data model.

**Context Boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ClipboardProvider.kt` (18 lines — extend interface)
- New file: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/BlockClipboard.kt` (~50 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` (reference `Block` shape)
- Test: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/export/BlockClipboardTest.kt` (~60 lines)

**Prerequisites:**
- Understanding of `Block` data class fields (`uuid`, `parentUuid`, `level`, `position`, `content`)
- Understanding that `ClipboardProvider` is a `commonMain` interface with platform `actual` implementations

**Implementation Approach:**
1. In `BlockClipboard.kt`, define `data class ClipboardBlock(val block: Block, val relativeDepth: Int)` — `relativeDepth` is 0 for root blocks in the selection, +1 for each level of nesting below them
2. Define `data class BlockClipboard(val blocks: List<ClipboardBlock>)` with helpers: `val rootBlocks: List<ClipboardBlock>` (filter `relativeDepth == 0`), `val allBlocks: List<ClipboardBlock>` (all entries), `isEmpty(): Boolean`
3. Add `fun readText(): String?` to `ClipboardProvider` interface with `@OptIn`-free default returning `null` (allows existing implementations to remain valid until updated)
4. Write unit tests: empty clipboard, single-block clipboard, multi-root clipboard, nested-tree clipboard

**Validation Strategy:**
- Unit tests: `BlockClipboardTest` — verify `rootBlocks` filter, `isEmpty()`, depth calculation round-trip
- Success criteria: `./gradlew jvmTest --tests "*.BlockClipboardTest"` passes green

**INVEST Check:**
- Independent: no dependency on other tasks in this epic
- Negotiable: helper method names flexible
- Valuable: unblocks all other tasks
- Estimable: 2h — pure data model, no I/O
- Small: 2 new files, ~130 lines total
- Testable: pure functions, no coroutines

---

#### Task 1.2 — Extend `ClipboardProvider` platform implementations with `readText` [2h]

**Objective**: Implement `readText(): String?` on JVM and Android so paste of external Markdown text is possible.

**Context Boundary:**
- Primary: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/PlatformClipboardProvider.jvm.kt` (24 lines)
- Secondary: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/PlatformClipboardProvider.android.kt` (or equivalent `actual fun`)
- Reference: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ClipboardProvider.kt`

**Prerequisites:**
- Task 1.1 complete (`ClipboardProvider.readText()` interface method exists)
- Familiarity with Compose `ClipboardManager` API and `AnnotatedString`

**Implementation Approach:**
1. JVM: in `PlatformClipboardProvider.jvm.kt`, override `readText()` to return `clipboard.getText()?.text` (Compose `ClipboardManager.getText()`)
2. Android: same pattern with Compose `ClipboardManager`
3. iOS stub: return `null` (out of scope — platform declaration still required for compilation)
4. Update `rememberClipboardProvider` to return the updated anonymous object

**Validation Strategy:**
- Manual smoke test: copy text in browser → focus SteleKit → `Ctrl+V` (Story 3 must be done first for full test, but compilation alone validates this task)
- Success criteria: project compiles for all targets; `ClipboardProvider.readText()` has concrete implementations on all active targets

---

#### Task 1.3 — Add `blockClipboard` state to `BlockStateManager` [2h]

**Objective**: Wire the in-process clipboard into the single source of truth for block state.

**Context Boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt` (read first 160 lines for context)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/BlockClipboard.kt` (Task 1.1 output)
- Test: existing `BlockStateManager` test file (or new `BlockStateManagerClipboardTest.kt`)

**Prerequisites:**
- Task 1.1 complete (`BlockClipboard` type exists)
- Familiarity with `BlockStateManager`'s `MutableStateFlow` pattern and selection state

**Implementation Approach:**
1. Add `private val _blockClipboard = MutableStateFlow<BlockClipboard?>(null)` and `val blockClipboard: StateFlow<BlockClipboard?> = _blockClipboard.asStateFlow()` in `BlockStateManager`
2. Add `fun setClipboard(clipboard: BlockClipboard)` — sets `_blockClipboard.value = clipboard`
3. Add `fun clearClipboard()` — sets `_blockClipboard.value = null`
4. Add `fun getClipboard(): BlockClipboard?` — returns current value synchronously (for command use)
5. Write unit test: verify `setClipboard()` → `blockClipboard.value` reflects the new value; verify `clearClipboard()` resets to null

**Validation Strategy:**
- Unit test: `BlockStateManagerClipboardTest` — set/get/clear round-trip
- Success criteria: `./gradlew jvmTest` green; no regressions in existing `BlockStateManager` tests

---

### Story 2: Copy and Cut Commands [1 week]

**User value**: `Ctrl+C` copies the focused/selected block tree to the in-process clipboard and writes Markdown text to the system clipboard. `Ctrl+X` does the same and then deletes the source blocks.

**Acceptance Criteria:**
- `Ctrl+C` on focused block: `blockClipboard` holds the block + all its children; system clipboard contains indented Markdown
- `Ctrl+C` on multi-block selection (from `BlockStateManager.selectedBlockUuids`): all selected blocks and their subtrees are captured
- `Ctrl+X` copies identically then removes source blocks via `DeleteBlockCommand`
- `UndoManager` can undo a cut (restores deleted blocks)
- `BlockStateManager.selectedBlockUuids` is cleared after copy/cut

---

#### Task 2.1 — `BlockClipboardService.buildClipboard()` — serialize selected blocks [3h]

**Objective**: Extract the selected block tree from `BlockRepository` into a `BlockClipboard`.

**Context Boundary:**
- New file: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/BlockClipboardService.kt` (~120 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/blocks/BlockTreeOperations.kt` (`getSubtree()`)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/MarkdownExporter.kt` (for system clipboard Markdown text)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ClipboardProvider.kt`

**Prerequisites:**
- Task 1.1 complete
- Understanding of `BlockTreeOperations.getSubtree()` return type `List<BlockWithDepth>`
- Understanding that `MarkdownExporter.export(page, blocks, refs)` requires a `Page` object — a synthetic dummy page is acceptable for clipboard Markdown

**Implementation Approach:**
1. Create `class BlockClipboardService(private val blockTreeOps: BlockTreeOperations, private val markdownExporter: MarkdownExporter, private val clipboard: ClipboardProvider)`
2. Implement `suspend fun buildClipboard(selectedUuids: Set<String>): BlockClipboard`:
   - For each root UUID in `selectedUuids` (those with no `parentUuid` in the set), call `blockTreeOps.getSubtree(uuid)` to get `List<BlockWithDepth>`
   - Convert to `List<ClipboardBlock>` with `relativeDepth` = `BlockWithDepth.depth`
   - Return `BlockClipboard(blocks)`
3. Implement `fun toMarkdownText(clipboard: BlockClipboard): String`:
   - Collect `clipboard.allBlocks.map { it.block }` and render using `MarkdownExporter` with a synthetic `Page(name="clipboard", ...)`
   - Return the rendered Markdown string
4. Write unit tests: single block, block with children, multi-root selection

**Validation Strategy:**
- Unit tests cover: single-root clipboard, multi-root clipboard, nested 3-level tree, empty selection
- Success criteria: `./gradlew jvmTest --tests "*.BlockClipboardServiceTest"` green

---

#### Task 2.2 — `CopyBlockCommand` and keyboard handler wiring [2h]

**Objective**: Implement the `Ctrl+C` copy shortcut end-to-end.

**Context Boundary:**
- New file: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/ClipboardCommands.kt` (~80 lines)
- Primary edit: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (add `copySelectedBlocks()` fun)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/BlockClipboardService.kt` (Task 2.1)

**Prerequisites:**
- Tasks 1.1, 1.3, 2.1 complete
- Understanding of how `StelekitViewModel` delegates block ops to `BlockStateManager`

**Implementation Approach:**
1. In `ClipboardCommands.kt`, create `class CopyBlocksCommand(...)` implementing `Command<Unit>` — calls `BlockClipboardService.buildClipboard(selectedUuids)`, then `BlockStateManager.setClipboard(clipboard)`, then writes Markdown to system `ClipboardProvider`. `undo()` calls `BlockStateManager.clearClipboard()` (copy is non-destructive; undo just clears the in-process clipboard)
2. In `StelekitViewModel`, add `fun copySelectedBlocks()`: resolve `blockStateManager.selectedBlockUuids` (or `editingBlockUuid` if selection is empty), build the command, execute via coroutine scope
3. In the UI layer (PageView / JournalsView keyboard handler), wire `Ctrl+C` to call `viewModel.copySelectedBlocks()`
4. Note: `KeyboardEventHandler.handleKeyPress` already routes `key == "c" && CONTROL` to `textOperations.copy(blockId)` — this existing call handles in-block text selection copy; the new block-level copy fires when selection mode is active or when the block editor is not focused on a text range

**Validation Strategy:**
- Integration test: load a page with 3 blocks, select block 2, call `copySelectedBlocks()`, assert `blockStateManager.blockClipboard.value` is non-null with correct content
- Success criteria: test passes; Markdown appears in system clipboard when verified manually

---

#### Task 2.3 — `CutBlocksCommand` with undo support [3h]

**Objective**: Implement `Ctrl+X` — copy + delete source blocks, fully undoable.

**Context Boundary:**
- Primary edit: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/ClipboardCommands.kt` (Task 2.2 file, add `CutBlocksCommand`)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/BlockCommands.kt` (`DeleteBlockCommand` reference)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/UndoManager.kt`
- Test: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/command/ClipboardCommandsTest.kt`

**Prerequisites:**
- Tasks 1.3, 2.1, 2.2 complete
- Understanding of `DeleteBlockCommand` (captures hierarchy before delete for undo)
- Understanding of `UndoManager.push()` and `undo()` contract

**Implementation Approach:**
1. `CutBlocksCommand(blockUuids, blockClipboardService, blockStateManager, blockRepository)`:
   - `execute()`: call `buildClipboard(blockUuids)`, set clipboard, then delete each root block via `DeleteBlockCommand.execute()` (reuse existing delete logic — pass `siblingsToShiftUp` from repository query)
   - `undo()`: call `DeleteBlockCommand.undo()` for each deleted root block in reverse order to restore, then call `BlockStateManager.clearClipboard()`
2. `StelekitViewModel.cutSelectedBlocks()`: build the command, push to `UndoManager`, execute
3. Wire `Ctrl+X` in keyboard handler (same pattern as `Ctrl+C`)
4. Unit test: cut a block with 2 children, undo — verify all 3 blocks restored with original positions

**Validation Strategy:**
- Unit test: `CutBlocksCommandTest` — cut, verify deletion from repo; undo, verify restoration
- Integration test: cut block on page A, undo, verify block still on page A
- Success criteria: undo restores exact block tree; `./gradlew jvmTest` green

---

### Story 3: Paste Commands [1 week]

**User value**: `Ctrl+V` inserts the clipboard block tree as new siblings below the cursor block. `Ctrl+Shift+V` inserts a block reference. Pasting external Markdown text from the system clipboard creates new blocks.

**Acceptance Criteria:**
- `Ctrl+V` with in-process clipboard: pasted blocks appear below cursor with new UUIDs; undo removes them
- `Ctrl+V` with no in-process clipboard but system clipboard has plain text: text is split by newlines and inserted as new bullet blocks
- `Ctrl+Shift+V`: inserts `((source-uuid))` as inline content in a new block below cursor
- Pasted blocks appear in correct visual order immediately (StateFlow update)
- Undo of paste removes all pasted blocks

---

#### Task 3.1 — `BlockClipboardService.pasteAsNewBlocks()` [3h]

**Objective**: Implement the core paste logic — regenerate UUIDs, remap parent pointers, batch-insert below target block.

**Context Boundary:**
- Primary edit: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/BlockClipboardService.kt` (Task 2.1 file)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/util/UuidGenerator.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/BlockRepository.kt` (interface for `saveBlocks`)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/blocks/BlockTreeOperations.kt` (reference for UUID remapping pattern in `duplicateSubtree()`)
- Test: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/export/BlockClipboardServiceTest.kt`

**Prerequisites:**
- Tasks 1.1, 2.1 complete
- Understanding of `BlockTreeOperations.duplicateSubtree()` UUID remapping pattern (lines 208-253)
- Understanding of `Block.parentUuid`, `Block.position`, `Block.pageUuid`

**Implementation Approach:**
1. `suspend fun pasteAsNewBlocks(clipboard: BlockClipboard, targetPageUuid: String, afterBlockUuid: String?, blockRepository: BlockRepository): List<Block>`:
   - Build `oldUuid → newUuid` map via `UuidGenerator.generateV7()` for every block in clipboard
   - Determine insertion position: query siblings of `afterBlockUuid` and compute position offsets to insert the clipboard's root blocks starting after `afterBlockUuid.position`, shifting existing siblings down
   - Reconstruct each `ClipboardBlock.block` with `uuid = newUuid`, `pageUuid = targetPageUuid`, `parentUuid = remapped parent or null for roots`, `position = computed`, fresh `createdAt`/`updatedAt`
   - Call `blockRepository.saveBlocks(newBlocks)` in a single batch
   - Return `newBlocks` (caller needs UUIDs for undo)
2. Unit tests: paste 1 block, paste 3-deep tree, paste 2 root blocks side-by-side

**Validation Strategy:**
- Unit tests: verify UUID uniqueness, parent pointer integrity, position ordering, correct `pageUuid`
- Success criteria: `./gradlew jvmTest --tests "*.BlockClipboardServiceTest"` green

---

#### Task 3.2 — `PasteBlocksCommand` with undo [2h]

**Objective**: Wrap paste in the `Command` pattern for undo/redo support.

**Context Boundary:**
- Primary edit: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/ClipboardCommands.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/BlockClipboardService.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/BlockCommands.kt` (undo pattern reference)
- Test: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/command/ClipboardCommandsTest.kt`

**Prerequisites:**
- Tasks 2.2, 3.1 complete
- Understanding of `Command<T>.undo()` contract and how `DeleteBlockCommand.undo()` restores blocks

**Implementation Approach:**
1. `class PasteBlocksCommand(clipboard, targetPageUuid, afterBlockUuid, blockClipboardService, blockRepository)`:
   - `execute()`: call `pasteAsNewBlocks(...)`, store the returned `pastedBlockUuids: List<String>` for undo
   - `undo()`: for each pasted root block UUID, call `blockRepository.deleteBlock(uuid, deleteChildren = true)` in reverse insertion order; restore any position shifts to displaced siblings
2. `StelekitViewModel.pasteBlocks(afterBlockUuid)`: get clipboard from `BlockStateManager`, build `PasteBlocksCommand`, push to `UndoManager`, execute
3. Unit test: paste 3 blocks, undo — verify all 3 removed

**Validation Strategy:**
- Unit test: paste then undo — repository contains exactly the original blocks
- Success criteria: `./gradlew jvmTest` green; no blocks leak after undo

---

#### Task 3.3 — External Markdown paste (system clipboard → new blocks) [2h]

**Objective**: When the in-process clipboard is empty, `Ctrl+V` reads the system clipboard text and inserts each newline-split line as a new bullet block.

**Context Boundary:**
- Primary edit: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (update `pasteBlocks()`)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ClipboardProvider.kt` (`readText()` from Task 1.2)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/loader/MarkdownParser.kt` (interface)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/BlockCommands.kt` (`AddBlockCommand`)

**Prerequisites:**
- Tasks 1.2, 3.2 complete
- Understanding of `MarkdownParser.parseBlock()` and how `ParsedBlock` maps to `Block`
- Understanding of `AddBlockCommand` position shifting semantics

**Implementation Approach:**
1. In `StelekitViewModel.pasteBlocks()`: if `blockStateManager.getClipboard() == null`, call `clipboardProvider.readText()` to get system text
2. If text is non-null: split by `\n`, filter blank lines, create one `Block` per line with `AddBlockCommand` in sequence (or a single `AddMultipleBlocksCommand` — use a composite command that wraps `List<AddBlockCommand>`)
3. Each new block gets `UuidGenerator.generateV7()`, `pageUuid = currentPageUuid`, `parentUuid = null` (root level), `position` = sequential from `afterBlockUuid.position + 1`
4. Undo: composite command's `undo()` reverses all `AddBlockCommand` insertions in reverse order
5. If system clipboard text is also null, `pasteBlocks()` is a no-op

**Validation Strategy:**
- Unit test: mock `ClipboardProvider.readText()` returning `"line1\nline2\nline3"`, verify 3 new blocks created
- Success criteria: `./gradlew jvmTest` green; external paste creates correct block count

---

#### Task 3.4 — `Ctrl+Shift+V` paste as reference [1h]

**Objective**: Insert `((uuid))` block reference as content of a new block below the cursor.

**Context Boundary:**
- Primary edit: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (add `pasteAsReference()`)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/BlockCommands.kt` (`AddBlockCommand`)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/BlockClipboard.kt`

**Prerequisites:**
- Tasks 1.3, 3.2 complete
- Understanding that `((uuid))` is valid inline content that `InlineParser` renders as `BlockRefNode`

**Implementation Approach:**
1. `StelekitViewModel.pasteAsReference(afterBlockUuid: String)`:
   - Get `clipboard = blockStateManager.getClipboard()` — return if null
   - Take `clipboard.rootBlocks.first().block.uuid` as `sourceUuid`
   - Create a new `Block` with `content = "(($sourceUuid))"` via `AddBlockCommand`
   - Push and execute command via `UndoManager`
2. Wire `Ctrl+Shift+V` in keyboard handler (UI layer) to call `viewModel.pasteAsReference(currentBlockUuid)`
3. No dedicated unit test required — `AddBlockCommand` is already tested; a quick smoke test suffices

**Validation Strategy:**
- Manual smoke test: copy a block, `Ctrl+Shift+V` → verify `((uuid))` block appears and renders as block reference
- Success criteria: `AddBlockCommand` wraps the reference; `./gradlew jvmTest` green (no regressions)

---

### Story 4: Keyboard Shortcut Wiring and UI Feedback [3 days]

**User value**: The commands built in Stories 2-3 are reachable via standard keyboard shortcuts in the actual UI. Users see visual feedback (toast notification) on copy.

**Acceptance Criteria:**
- `Ctrl+C`, `Ctrl+X`, `Ctrl+V`, `Ctrl+Shift+V` trigger their respective ViewModel methods from `PageView` and `JournalsView`
- `Ctrl+C` shows a brief "Copied" toast notification
- `Ctrl+X` shows a "Cut" toast and the source block is visually removed immediately (optimistic state)
- `KeyboardShortcutsReference` component lists the new shortcuts

---

#### Task 4.1 — Wire shortcuts in `PageView` / `JournalsView` keyboard handler [2h]

**Objective**: Connect keyboard events to ViewModel clipboard methods.

**Context Boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/input/InputHandling.kt` (lines 356-375 — existing copy/cut/paste stubs)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt` (or wherever `KeyboardEventHandler` is instantiated)

**Prerequisites:**
- Tasks 2.2, 2.3, 3.2, 3.4 complete
- Understanding of how `KeyboardEventHandler.onExecuteCommand` callback dispatches to ViewModel

**Implementation Approach:**
1. In `InputHandling.kt`, the existing stubs for `Ctrl+C`, `Ctrl+X`, `Ctrl+V` currently delegate to `textOperations.copy/cut/paste(blockId)` — these handle in-block text selection. Add a guard: if `blockStateManager.isInSelectionMode` is true (multi-block selection), dispatch to the new block-level commands instead
2. Add `Ctrl+Shift+V` case: `key == "v" && CONTROL && SHIFT` → `onExecuteCommand("block.paste-as-reference")`
3. In `CommandManager` or equivalent dispatch table, map `"block.copy"`, `"block.cut"`, `"block.paste"`, `"block.paste-as-reference"` to ViewModel methods
4. Update `KeyboardShortcutsReference` composable in `InputHandling.kt` to list the four new shortcuts

**Validation Strategy:**
- Manual test: select 2 blocks with Shift+Click, press Ctrl+C, verify clipboard is populated; Ctrl+V below another block, verify 2 new blocks appear
- Success criteria: all four shortcuts work end-to-end; no regressions on existing text-level copy/paste

---

#### Task 4.2 — Toast notification for copy/cut [1h]

**Objective**: Show ephemeral "Copied" / "Cut" notifications using the existing `NotificationManager`.

**Context Boundary:**
- Primary edit: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (add `notificationManager.show(...)` calls in `copySelectedBlocks()` and `cutSelectedBlocks()`)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/NotificationManager.kt` (or `Notification` model)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` (`Notification` data class)

**Prerequisites:**
- Tasks 2.2, 2.3 complete
- Understanding of `NotificationManager.show()` or equivalent toast mechanism

**Implementation Approach:**
1. After `copySelectedBlocks()` succeeds: `notificationManager?.show(Notification(id=UUID, content="Copied", type=INFO, timeout=2000))`
2. After `cutSelectedBlocks()` succeeds: same with content="Cut"
3. Paste does not need a toast (visual insertion is sufficient feedback)

**Validation Strategy:**
- Manual test: copy a block, verify toast appears briefly and disappears
- Success criteria: notification appears; no test needed beyond compilation and smoke test

---

## Known Issues

### Bug 001: Position Conflict on Paste at End of Page [SEVERITY: Medium]

**Description**: When `afterBlockUuid` is the last block on a page, the new pasted blocks must be inserted after `max(position)`. If sibling position queries return a stale snapshot (BlockStateManager optimistic state vs. DB state), two blocks may receive the same position integer, causing non-deterministic rendering order.

**Mitigation:**
- `pasteAsNewBlocks()` must query fresh sibling positions from `BlockRepository` (not from `BlockStateManager.blocks` snapshot) immediately before computing new positions
- Use a single atomic `saveBlocks()` call to avoid intermediate states

**Files Likely Affected:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/BlockClipboardService.kt` — position computation
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/BlockRepository.kt` — must expose `getSiblings(parentUuid)` or similar

**Prevention Strategy:**
- Task 3.1 unit test must include a case where `afterBlockUuid` is the last block; assert no duplicate positions in inserted batch

**Related Tasks**: Task 3.1

---

### Bug 002: Cut-Paste Across Pages Leaves Dangling Backlinks [SEVERITY: Low]

**Description**: If a cut block had backlinks registered in `SearchRepository`, those backlinks are removed by `DeleteBlockCommand`. When pasted to page B with new UUIDs, new backlinks are registered for the new UUIDs. However, if undo is performed after the paste, the `PasteBlocksCommand.undo()` deletes the new-UUID blocks (and their backlinks), but `CutBlocksCommand.undo()` restores the original-UUID blocks. If undo is called only for paste (not for cut), the source blocks remain deleted with their original backlinks gone.

**Mitigation:**
- For v1, treat cut+paste as two separate undo steps (cut is one stack entry, paste is a second). Users must undo twice to fully reverse a cross-page cut+paste.
- Document this in `CutBlocksCommand.description` and `PasteBlocksCommand.description`.

**Files Likely Affected:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/ClipboardCommands.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/BacklinkRenamer.kt`

**Prevention Strategy:**
- Unit test: cut block with backlink, undo paste only, verify source blocks are still absent (expected v1 behavior); then undo cut, verify source blocks restored

**Related Tasks**: Tasks 2.3, 3.2

---

### Bug 003: External Markdown Paste Creates Flat Blocks Only [SEVERITY: Low]

**Description**: `ClipboardProvider.readText()` returns raw text. The external paste path in Task 3.3 splits by newline and creates flat blocks. If the user copies an indented Markdown list from an external app, the hierarchy (indent levels) is lost.

**Mitigation:**
- For v1, parse the text through `MarkdownParser.parse()` instead of naive newline split. The parser already handles indented bullets. The resulting `ParsedPage.blocks` preserves hierarchy.
- If parsing fails, fall back to line-by-line flat insertion.

**Files Likely Affected:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` — `pasteBlocks()` external text path
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/loader/MarkdownParser.kt`

**Prevention Strategy:**
- Task 3.3 should use `MarkdownParser.parse()` as primary path; the naive split as fallback

**Related Tasks**: Task 3.3

---

## Dependency Visualization

```
Story 1: Data Model Foundation
  Task 1.1 (ClipboardBlock/BlockClipboard) ──────────────────┐
  Task 1.2 (readText platform impls)        ──────────────────┤
  Task 1.3 (BSM clipboard state) ── depends on 1.1 ──────────┘

Story 2: Copy / Cut
  Task 2.1 (buildClipboard service) ── depends on 1.1 ────────┐
  Task 2.2 (CopyBlockCommand)       ── depends on 1.3, 2.1 ───┤
  Task 2.3 (CutBlocksCommand)       ── depends on 2.2 ─────────┘

Story 3: Paste
  Task 3.1 (pasteAsNewBlocks)       ── depends on 1.1, 2.1 ───┐
  Task 3.2 (PasteBlocksCommand)     ── depends on 2.2, 3.1 ───┤
  Task 3.3 (external MD paste)      ── depends on 1.2, 3.2 ───┤
  Task 3.4 (paste as reference)     ── depends on 1.3, 3.2 ───┘

Story 4: UI Wiring
  Task 4.1 (keyboard shortcuts)     ── depends on 2.2, 2.3, 3.2, 3.4
  Task 4.2 (toast notifications)    ── depends on 2.2, 2.3

Parallel execution opportunities:
  - 1.1, 1.2 can run in parallel (no dependency between them)
  - 2.1 can start as soon as 1.1 is done (does not need 1.2 or 1.3)
  - 2.2 and 2.3 are sequential (2.3 extends 2.2's file)
  - 3.1, 3.3, 3.4 can start in parallel once their respective dependencies are met
```

---

## Integration Checkpoints

**After Story 1**: Project compiles on all targets. `BlockStateManager` has `blockClipboard: StateFlow<BlockClipboard?>`. `ClipboardProvider.readText()` exists and returns null on all platforms. No user-visible change yet.

**After Story 2**: `Ctrl+C` and `Ctrl+X` are functional in the UI. `blockClipboard` state is populated after copy. System clipboard contains Markdown text. Cut removes source blocks. Undo restores them.

**After Story 3**: Full copy-cut-paste round-trip works within a single page and cross-page. External Markdown text paste creates new blocks. `Ctrl+Shift+V` inserts block references. All paste operations are undoable.

**Final (After Story 4)**: All four shortcuts are wired in both `PageView` and `JournalsView`. Toast notifications appear on copy/cut. `KeyboardShortcutsReference` panel is updated. Feature is complete and ready for QA.

---

## Context Preparation Guide

### Task 1.1
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` — understand `Block` fields
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ClipboardProvider.kt` — interface to extend
- Concept: Value Object pattern — `ClipboardBlock` has no identity, only data

### Task 1.3
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt` lines 1-160 — selection state pattern to replicate
- Concept: `MutableStateFlow` / `StateFlow` pair for read/write isolation

### Task 2.1
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/blocks/BlockTreeOperations.kt` lines 193-254 — `duplicateSubtree()` for UUID remapping reference
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/MarkdownExporter.kt` — for `toMarkdownText()` implementation
- Concept: Subtree traversal returns `List<BlockWithDepth>`; `depth` becomes `relativeDepth`

### Task 2.3
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/BlockCommands.kt` lines 34-82 — `DeleteBlockCommand` undo pattern
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/UndoManager.kt` — push/undo contract
- Concept: Capture full hierarchy before delete; restore in `undo()`

### Task 3.1
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/blocks/BlockTreeOperations.kt` lines 193-253 — `duplicateSubtree()` exact UUID remap algorithm to adapt
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/util/UuidGenerator.kt` — `generateV7()` signature
- Concept: Build `oldUuid → newUuid` map first, then reconstruct blocks in a second pass

### Task 3.3
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/loader/MarkdownParser.kt` — `parse()` returns `ParsedPage`
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/ParsedModels.kt` — `ParsedBlock` → `Block` mapping
- Concept: External text may contain indented Markdown; use `MarkdownParser.parse()` for fidelity

### Task 4.1
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/input/InputHandling.kt` lines 356-375 — existing cut/copy/paste stubs
- Concept: Two-mode dispatch — text-selection copy (existing) vs. block-selection copy (new); gate on `BlockStateManager.isInSelectionMode`

---

## Success Criteria

- All atomic tasks completed and validated per their individual success criteria
- `Ctrl+C` / `Ctrl+X` / `Ctrl+V` / `Ctrl+Shift+V` work in `PageView` and `JournalsView`
- Paste of external Markdown text inserts properly structured blocks
- Undo/redo of copy, cut, and paste operations behave correctly
- `./gradlew jvmTest` passes with no regressions
- New test coverage for `BlockClipboard`, `BlockClipboardService`, `CopyBlocksCommand`, `CutBlocksCommand`, `PasteBlocksCommand` reaches >80% line coverage
- Known issues documented with accepted-for-v1 mitigations applied
- `KeyboardShortcutsReference` UI component updated with the four new shortcuts
