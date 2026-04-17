# Task Management UI — Implementation Plan

## Epic Overview

### User Value
Logseq users organize their work inside their knowledge graph. Every captured TODO, scheduled meeting note, and deadline lives as a block — not in a separate app. Without task management, SteleKit is unusable as a daily driver for anyone who relies on Logseq's `TODO`/`DOING`/`DONE` workflow, scheduled dates, or the daily agenda view. This feature restores parity with Logseq's most-used productivity surface.

### Success Metrics
- A Logseq graph containing task blocks imports and displays status markers without data loss.
- Clicking a task marker cycles the status in ≤100 ms (optimistic update visible immediately).
- The agenda section on a journal page lists all blocks scheduled or due today within 500 ms of page open.
- Priority markers (`[#A]`–`[#D]`) render inline and survive a round-trip save to disk.
- Zero regressions on existing `BlockItem`, `BlockGutter`, and `GraphWriter` tests.

### Scope
**In scope:**
- `TaskStatus` enum: `TODO`, `DOING`, `DONE`, `CANCELED`, `LATER`, `NOW`
- `TaskPriority` enum: `A` (P1) through `D` (P4)
- `TaskStatusParser` — extract status + priority from `Block.content` prefix
- Inline task marker UI in `BlockGutter` (click-to-cycle, keyboard shortcut `t t`)
- `CycleTaskStatusCommand` — undo/redo-safe status mutation
- `SCHEDULED` / `DEADLINE` date display in block viewer (already parsed, needs rendering)
- Agenda section on journal pages — blocks matching today's date from `scheduled`/`deadline`
- `AgendaRepository` interface + in-memory implementation

**Explicitly out of scope (won't have in this feature):**
- Recurring tasks / repeat schedules
- Agenda calendar view (grid/calendar layout)
- Task filtering sidebar or dedicated Tasks screen
- Drag-and-drop between agenda entries
- Mobile date-picker for SCHEDULED/DEADLINE editing (keyboard entry only)

### Constraints
- Must not introduce a schema migration: status stored as content prefix (see ADR-001).
- `Block.content` validation in `Models.kt` must continue to pass for task-prefixed content.
- All new UI components must be `@Composable` and live in `commonMain`.
- No new third-party dependencies.

---

## Architecture Decisions

| # | File | One-line decision |
|---|------|-------------------|
| ADR-001 | `project_plans/stelekit/decisions/ADR-001-task-status-as-content-prefix.md` | Store task status as a prefix keyword in `Block.content` for Logseq file-format compatibility. |
| ADR-002 | `project_plans/stelekit/decisions/ADR-002-agenda-query-strategy.md` | Use in-memory property filter for agenda queries at MVP; add indexed columns in a follow-up migration if performance degrades. |

---

## Story Breakdown

### Story 1 — Task Domain Model & Parser [1 week]
**User value:** Task blocks can be parsed from existing Logseq Markdown files and their status/priority is available to all UI layers without ad-hoc string matching scattered across the codebase.

**Acceptance criteria:**
- `TaskStatus` and `TaskPriority` enums exist in `commonMain/model/`.
- `TaskStatusParser.parse(content)` correctly identifies all six status keywords and four priority levels.
- Parser strips the prefix from the display content (same pattern as `TimestampParser`).
- Round-trip: serializing back to Markdown produces the original prefix string.
- `CycleTaskStatusCommand` cycles `null → TODO → DOING → DONE → CANCELED → null` and integrates with `UndoManager`.
- Unit tests cover all status transitions and edge cases (no prefix, mixed case, priority-only).

---

#### Task 1.1 — `TaskStatus`, `TaskPriority`, and `TaskStatusParser` [2h]

**Objective:** Create the value objects and parser that extract task metadata from block content.

**Context boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/TaskStatusParser.kt` (new, ~80 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/TimestampParser.kt` (reference pattern)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/ParsedModels.kt` (extend `ParsedBlock`)
- Test: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parser/TaskStatusParserTest.kt` (new, ~120 lines)

**Prerequisites:**
- Understanding of `TimestampParser` pattern (prefix regex → extract → strip from content).
- `ParsedBlock` data class fields (add `taskStatus: TaskStatus?` and `taskPriority: TaskPriority?`).

**Implementation approach:**
1. Add `TaskStatus` enum to `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/TaskModels.kt` (new file):
   ```kotlin
   enum class TaskStatus(val keyword: String) {
       TODO("TODO"), DOING("DOING"), DONE("DONE"),
       CANCELED("CANCELED"), LATER("LATER"), NOW("NOW");
       companion object {
           fun fromKeyword(s: String) = entries.firstOrNull { it.keyword == s.uppercase() }
       }
   }
   enum class TaskPriority(val label: String) {
       A("A"), B("B"), C("C"), D("D");
       companion object { fun fromLabel(s: String) = entries.firstOrNull { it.label == s.uppercase() } }
   }
   data class TaskStatusResult(val content: String, val status: TaskStatus?, val priority: TaskPriority?)
   ```
2. Implement `TaskStatusParser.kt` with regex: `^(TODO|DOING|DONE|CANCELED|LATER|NOW)(\s+\[#([ABCD])\])?\s+`.
3. Extend `ParsedBlock` with `taskStatus: TaskStatus? = null` and `taskPriority: TaskPriority? = null`.
4. Call `TaskStatusParser.parse()` in `MarkdownParser.convertBlock()` alongside the existing `TimestampParser.parse()` call. Use the stripped content as the stored `ParsedBlock.content`.
5. Extend `GraphLoader`'s block-to-`Block` mapping to store `taskStatus` and `taskPriority` in `Block.properties` (keys `"task_status"` and `"task_priority"`).

**Validation strategy:**
- Unit tests: `TODO Buy milk` → status=TODO, content=`Buy milk`; `DONE [#A] Report` → status=DONE, priority=A, content=`Report`; bare content with no prefix → status=null.
- Integration: load a Logseq `.md` file containing task blocks, assert `Block.properties["task_status"]` is non-null.

**INVEST check:**
- Independent: no dependency on Story 2 or 3.
- Negotiable: regex vs manual parser — either works.
- Valuable: unblocks all other stories.
- Estimable: 2 h with high confidence.
- Small: single concern — parsing.
- Testable: pure functions, trivially unit-tested.

---

#### Task 1.2 — `CycleTaskStatusCommand` [2h]

**Objective:** Implement an undo/redo-safe Command that cycles a block's task status through the canonical sequence.

**Context boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/TaskCommands.kt` (new, ~60 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/BlockCommands.kt` (reference pattern)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/Command.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/TaskStatusParser.kt` (from Task 1.1)
- Test: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/command/TaskCommandsTest.kt` (new)

**Prerequisites:**
- Task 1.1 complete (`TaskStatus`, `TaskStatusParser`).
- Understanding of `UpdateBlockContentCommand` as the canonical pattern for content mutation.

**Implementation approach:**
1. Define cycle order: `null → TODO → DOING → DONE → CANCELED → null` (wrap around).
2. `CycleTaskStatusCommand` delegates to the repository: on `execute()`, read block, compute next status, rewrite content prefix, call `repository.saveBlock(block.copy(content = newContent))`. On `undo()`, restore original content.
3. Add a `setTaskStatus(block, status)` helper in `TaskStatusParser` companion that rebuilds the content string with the correct prefix (or strips it for `null`).
4. Register in `BlockStateManager` as `fun cycleTaskStatus(blockUuid)` — creates and executes the command via `UndoManager`.

**Validation strategy:**
- Unit test: `TODO Buy milk` → `cycleTaskStatus` → `DOING Buy milk` → undo → `TODO Buy milk`.
- Integration: verify `GraphWriter` serialises the updated content to disk with the correct prefix.

**INVEST check:**
- Independent from UI stories (no Compose dependency).
- Estimable: 2 h.
- Testable: pure command execution with in-memory repository.

---

### Story 2 — Inline Task Marker UI [1 week]
**User value:** Users can see and toggle task status directly in the block list without entering edit mode, mirroring Logseq's checkbox-style markers.

**Acceptance criteria:**
- Blocks with a task status display a colored marker chip/icon left of the bullet.
- Clicking the marker cycles the status (calls `CycleTaskStatusCommand`).
- Keyboard shortcut `t t` cycles status on the focused block.
- `SCHEDULED` / `DEADLINE` dates render as small chips below the block content in view mode.
- Priority badge (A/B/C/D) renders inline when present.
- `BlockViewer` does not show the raw `TODO`/`DONE` prefix — it is stripped before rendering (content already stripped by parser).

**Acceptance criteria (visual):**
- `TODO` → gray outlined checkbox icon.
- `DOING` → blue filled icon (in-progress indicator).
- `DONE` → green checkmark.
- `CANCELED` → gray strikethrough icon.
- `LATER`/`NOW` → purple clock icon.

---

#### Task 2.1 — `TaskStatusChip` composable [2h]

**Objective:** Create the inline UI chip that displays task status and captures click-to-cycle.

**Context boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TaskStatusChip.kt` (new, ~80 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/TaskModels.kt` (from Task 1.1)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Color.kt`
- Test: screenshot test via Roborazzi for each status variant

**Prerequisites:**
- Task 1.1 complete (TaskStatus enum, TaskModels.kt).

**Implementation approach:**
1. `@Composable fun TaskStatusChip(status: TaskStatus, onCycle: () -> Unit, modifier: Modifier)`.
2. Use `FilterChip` or `SuggestionChip` from Material3, with a leading icon per status.
3. Icon mapping: `TODO` → `Icons.Outlined.CheckBoxOutlineBlank`, `DOING` → `Icons.Filled.Autorenew`, `DONE` → `Icons.Filled.CheckBox`, `CANCELED` → `Icons.Filled.Cancel`, `LATER`/`NOW` → `Icons.Filled.Schedule`.
4. Color per status via `StelekitTheme` semantic tokens (add `taskTodo`, `taskDone`, `taskCanceled` color slots to `Color.kt`).
5. No internal state — parent owns status; `onCycle` callback.

**Validation strategy:**
- Compose Preview or Roborazzi screenshot test for each status.
- Accessibility: `contentDescription = "${status.keyword} — tap to change"`.

---

#### Task 2.2 — Wire `TaskStatusChip` into `BlockGutter` and keyboard shortcut [2h]

**Objective:** Mount the chip in the block row and wire the `t t` keyboard shortcut.

**Context boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockGutter.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TaskStatusChip.kt` (Task 2.1)

**Prerequisites:**
- Task 2.1 complete.
- Task 1.2 complete (`cycleTaskStatus` on `BlockStateManager`).

**Implementation approach:**
1. Add `taskStatus: TaskStatus?` and `onCycleTaskStatus: () -> Unit` parameters to `BlockGutter`.
2. When `taskStatus != null`, render `TaskStatusChip` in place of (or before) the bullet dot. When null, render the existing bullet.
3. In `BlockItem`, extract `taskStatus` from `block.properties["task_status"]` and pass down.
4. Keyboard shortcut: in `BlockEditor`'s key-event handler (in `InputHandling.kt`), detect two consecutive `t` keypresses within 500 ms and call `onCycleTaskStatus`. Use a `remember { mutableLongStateOf(0L) }` timestamp for the double-press detection.
5. Propagate `onCycleTaskStatus` lambda from `BlockList` → `BlockItem` → `BlockGutter`, sourcing from `BlockStateManager.cycleTaskStatus(blockUuid)`.

**Validation strategy:**
- Manual: open a page with `TODO` blocks, click chip → status cycles, disk file updates.
- Keyboard: focus a TODO block, press `t t` → cycles.
- Undo: `Ctrl+Z` after cycling restores previous status.

---

#### Task 2.3 — `ScheduledDeadlineChip` inline date display [1h]

**Objective:** Render SCHEDULED/DEADLINE dates as small info chips below block content in view mode.

**Context boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ScheduledDeadlineChip.kt` (new, ~50 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockViewer.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` (`Block.properties`)

**Prerequisites:**
- `Block.properties["scheduled"]` and `Block.properties["deadline"]` populated (already done by `TimestampParser` in `MarkdownParser`).

**Implementation approach:**
1. `@Composable fun ScheduledDeadlineChip(label: String, date: String, isOverdue: Boolean)` — renders a small `AssistChip` with a calendar icon.
2. In `BlockViewer`, after rendering the block content, check `block.properties["scheduled"]` and `block.properties["deadline"]`. If present, render chips below the content row.
3. Parse date string to `LocalDate` using `kotlinx.datetime` for overdue detection (date < today → `isOverdue = true` → red tint).

**Validation strategy:**
- Blocks with `SCHEDULED: <2026-01-01 Mon>` property show a chip reading "Scheduled: 2026-01-01".
- Overdue date renders with error color.

---

### Story 3 — Journal Agenda Section [1 week]
**User value:** The journal page for today automatically surfaces all blocks scheduled or due today, giving users a single view of their commitments without manually searching.

**Acceptance criteria:**
- A collapsible "Agenda" section appears at the top of each journal page.
- It lists all blocks from any page where `scheduled` or `deadline` property matches the journal date.
- Each agenda entry shows the block content, its source page name as a link, and its task status chip.
- Clicking a block navigates to its source page.
- The section is hidden when no scheduled/deadline blocks exist for that date.
- Load time for the agenda section ≤ 500 ms on a graph with 1000 pages.

---

#### Task 3.1 — `AgendaRepository` interface + in-memory implementation [3h]

**Objective:** Create the data-access layer that queries scheduled/deadline blocks for a given date.

**Context boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/AgendaRepository.kt` (new, ~60 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`
- Test: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/repository/AgendaRepositoryTest.kt`

**Prerequisites:**
- `Block.properties["scheduled"]` and `Block.properties["deadline"]` stored correctly (from Task 1.1 wiring in `GraphLoader`).

**Implementation approach:**
1. Define interface:
   ```kotlin
   interface AgendaRepository {
       fun getAgendaForDate(date: LocalDate): Flow<Result<List<AgendaEntry>>>
   }
   data class AgendaEntry(val block: Block, val sourcePage: Page, val type: AgendaType)
   enum class AgendaType { SCHEDULED, DEADLINE }
   ```
2. `InMemoryAgendaRepository` implementation: iterate all pages via `PageRepository.getAllPages()`, for each page load blocks, filter where `block.properties["scheduled"]` or `block.properties["deadline"]` contains `date.toString()`.
3. Add `AgendaRepository` to `RepositoryFactory` (both `IN_MEMORY` and `SQLDELIGHT` variants return the in-memory implementation for now).
4. Expose `getAgendaForDate` in `JournalService`.

**Validation strategy:**
- Unit test with in-memory repos: create 3 blocks across 2 pages with today's scheduled date, 2 with different dates — assert `getAgendaForDate(today)` returns exactly 3.
- Performance test: 1000 pages × 5 blocks each → assert result in < 500 ms.

---

#### Task 3.2 — `AgendaSection` composable [3h]

**Objective:** Render the collapsible agenda panel at the top of journal pages.

**Context boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/AgendaSection.kt` (new, ~120 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/AgendaRepository.kt` (Task 3.1)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TaskStatusChip.kt` (Task 2.1)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsView.kt`

**Prerequisites:**
- Tasks 2.1, 3.1 complete.

**Implementation approach:**
1. `@Composable fun AgendaSection(entries: List<AgendaEntry>, onEntryClick: (Page) -> Unit, modifier: Modifier)`:
   - `ElevatedCard` with collapsible `AnimatedVisibility`.
   - Header row: calendar icon, "Agenda" label, entry count badge, expand/collapse chevron.
   - Each entry row: `TaskStatusChip` (if status present), block content text (single line, ellipsis), source page name as `TextButton`.
   - Group by `AgendaType`: SCHEDULED entries first, then DEADLINE.
2. Wire into `JournalsView`: add `AgendaViewModel` (or inline in `JournalsViewModel`) that calls `journalService.getAgendaForDate(page.journalDate)` and exposes as `StateFlow<List<AgendaEntry>>`.
3. Pass `onEntryClick = { page -> onLinkClick(page.name) }` so navigation uses the existing link-click pipeline.

**Validation strategy:**
- Visual: journal page for today shows agenda section when scheduled blocks exist.
- Hidden when `entries.isEmpty()`.
- Clicking an entry navigates to the correct page.

---

#### Task 3.3 — Wire `AgendaSection` into `JournalsViewModel` [2h]

**Objective:** Load agenda data reactively and pass it to `AgendaSection` without blocking the journal scroll list.

**Context boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsViewModel.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsView.kt`

**Prerequisites:**
- Task 3.1 (`AgendaRepository`), Task 3.2 (`AgendaSection`) complete.

**Implementation approach:**
1. Add `private val _agendaEntries = MutableStateFlow<List<AgendaEntry>>(emptyList())` to `JournalsViewModel`.
2. In `JournalsViewModel.init`, launch a coroutine: collect `journalService.getAgendaForDate(today)` and emit into `_agendaEntries`.
3. In `JournalsView`, collect `viewModel.agendaEntries` and render `AgendaSection` at the top of the `LazyColumn` (before journal entries), wrapped in `item { ... }`.
4. `AgendaSection` renders only when `agendaEntries.isNotEmpty()`.

**Validation strategy:**
- Create a block with `SCHEDULED: <today>` in a non-journal page, open journals, verify it appears in the agenda.
- Collapsing and re-expanding agenda section preserves state across recompositions.

---

## Known Issues

### BUG-001: Status Prefix Round-Trip Corruption [SEVERITY: Medium]
**Description:** When `TaskStatusParser` strips the prefix for storage and `GraphWriter` serializes the block back to disk, it must re-prepend the status keyword. If `GraphWriter`'s `blockToMarkdown` method does not know about task status, it writes the stripped content without the prefix, silently corrupting the file.

**Files likely affected:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt` — block serialization
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/TaskStatusParser.kt` — prefix stripping

**Mitigation:**
- The `GraphWriter` serializes `Block.content` verbatim. If we store the status prefix as part of `Block.content` (ADR-001), no round-trip bug exists — the prefix is in the string.
- Confirm: `TaskStatusParser` in `MarkdownParser.convertBlock()` strips the prefix for `ParsedBlock.content`, but `Block.content` in the DB must retain the prefix.
- Solution: store the **original prefixed content** in `Block.content`; the UI strips the prefix only at render time using `TaskStatusParser.parse().content`.

**Prevention:**
- Add a round-trip integration test: parse `.md` → save → re-parse → assert content matches original.
- Ensure `GraphLoader` stores the raw content string (with prefix) in `Block.content`.

### BUG-002: Double-Press `t t` Shortcut Fires in Text Editor [SEVERITY: Low]
**Description:** Detecting consecutive `t` keypresses in `BlockEditor` could accidentally cycle status when the user is typing text containing the letter `t` twice quickly.

**Files likely affected:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/input/InputHandling.kt`

**Mitigation:**
- Only detect `t t` when the block is **not** in active edit mode (i.e., when `isEditing = false` or cursor is at position 0 before any text).
- Use a 300 ms timeout: second `t` must arrive within 300 ms of the first, and both must be bare `t` with no modifiers.
- Alternative: require an explicit modifier (e.g., `Ctrl+Enter` to cycle) — more discoverable, less ambiguous.

**Prevention:**
- Unit test: simulate `t` + `t` with 100 ms gap while isEditing=false → cycles status. Simulate `t` + `t` while isEditing=true → no status change.

### BUG-003: Agenda Repository N+1 Query on Large Graphs [SEVERITY: Medium]
**Description:** The in-memory `AgendaRepository` loads blocks for every page in the graph to find scheduled entries, which is O(pages × DB round-trips) — an N+1 pattern that degrades on large graphs.

**Files likely affected:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/AgendaRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

**Mitigation:**
- For MVP: add a performance test; if > 500 ms on 1000 pages, batch the query using `getBlocksForPages(pageUuids: List<String>)`.
- Future fix: add `scheduled_date` and `deadline_date` indexed columns via `MigrationRunner`, then use a single SQL query.

**Prevention:**
- Log `AgendaRepository` query time in debug mode.
- Enforce 500 ms SLA in the integration test.

### BUG-004: `Block.content` Validation Rejects Status Prefixes [SEVERITY: Low]
**Description:** `Validation.validateContent()` in `Models.kt` checks for control characters. Task status prefixes are plain ASCII and should pass, but the validator should be explicitly tested with task-prefixed strings.

**Files likely affected:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`

**Mitigation:**
- Add explicit test: `Block(content = "TODO Buy groceries", ...)` — assert no validation exception.
- Test all six status keywords as content prefixes.

---

## Dependency Visualization

```
Story 1 (Domain & Parser)
  Task 1.1 ─── TaskStatus enum, TaskStatusParser
      │
  Task 1.2 ─── CycleTaskStatusCommand (depends on 1.1)
      │
      ├──────────────────────┐
Story 2 (Inline UI)         Story 3 (Agenda)
  Task 2.1 ─── TaskStatusChip (depends on 1.1)
      │                        Task 3.1 ─── AgendaRepository (depends on 1.1)
  Task 2.2 ─── BlockGutter wire (depends on 2.1, 1.2)     │
      │                        Task 3.2 ─── AgendaSection (depends on 2.1, 3.1)
  Task 2.3 ─── ScheduledDeadlineChip (depends on 1.1)      │
                               Task 3.3 ─── Wire into JournalsViewModel (depends on 3.1, 3.2)

Legend: ─── = sequential dependency
        Independent tasks within a story can run in parallel
```

**Parallel opportunities:**
- Tasks 2.3 and 3.1 can run in parallel once 1.1 is done.
- Tasks 3.2 and 2.2 can run in parallel once their prerequisites are met.

---

## Integration Checkpoints

**After Story 1:**
- Open a Logseq graph with task blocks → `Block.properties["task_status"]` is populated.
- `CycleTaskStatusCommand` executes and undoes correctly in isolation (verified via unit tests and manual `jvmTest`).

**After Story 2:**
- Open any page with `TODO` blocks → colored chips appear in the gutter.
- Click a chip → status changes, disk file updates within debounce window (500 ms).
- `t t` shortcut cycles status on the focused block.
- `SCHEDULED` date chips visible on blocks with that property.

**Final (after Story 3):**
- Today's journal page shows an "Agenda" section listing all blocks scheduled for today from any page.
- Agenda entries link back to their source page.
- Full end-to-end test: create task in journal → schedule it → verify it appears in today's agenda.
- Zero regressions in existing screenshot tests.

---

## Context Preparation Guide

### Task 1.1 — Files to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/TimestampParser.kt` — reference for prefix-extraction pattern
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/ParsedModels.kt` — `ParsedBlock` to extend
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt` (lines 1–110) — where to call the new parser
- `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parser/TimestampParserTest.kt` — test pattern to follow

**Concepts:** `TimestampParser` pattern, `ParsedBlock` fields, `MarkdownParser.convertBlock()` pipeline.

### Task 1.2 — Files to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/BlockCommands.kt` — `UpdateBlockContentCommand` pattern
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/Command.kt` — interface
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt` (lines 1–80) — where to add `cycleTaskStatus`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/TaskStatusParser.kt` (Task 1.1 output)

### Task 2.1 — Files to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Color.kt` — theme color tokens
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/TaskModels.kt` (Task 1.1 output)
- Any existing chip usage in the codebase (`FilterBar.kt` or `AutocompleteMenu.kt`) for Material3 chip patterns.

### Task 2.2 — Files to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockGutter.kt` (full file, ~127 lines)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt` (lines 44–110, parameter list)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/input/InputHandling.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TaskStatusChip.kt` (Task 2.1 output)

### Task 3.1 — Files to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt` — where to add `getAgendaForDate`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt` — where to register `AgendaRepository`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` — `Block.properties` structure

### Task 3.2 — Files to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsView.kt` (full file)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/AgendaRepository.kt` (Task 3.1 output)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TaskStatusChip.kt` (Task 2.1 output)

### Task 3.3 — Files to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsViewModel.kt` (full file)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/AgendaSection.kt` (Task 3.2 output)

---

## Success Criteria

- [ ] All atomic tasks completed and validated per their individual success conditions.
- [ ] `TaskStatusParser` unit tests pass: all six status keywords, four priority levels, round-trip fidelity.
- [ ] `CycleTaskStatusCommand` integrates with `UndoManager` — undo restores prior content.
- [ ] `BlockGutter` renders task chips without visual regression on non-task blocks.
- [ ] `AgendaSection` appears on journal pages with correct entries; hidden when empty.
- [ ] No regressions in existing `jvmTest` or screenshot test suite.
- [ ] BUG-001 round-trip test passes: parse `.md` → save → re-parse → content identical.
- [ ] Agenda query SLA: ≤ 500 ms on 1000-page graph.
- [ ] Code review approved; all new files have KDoc on public API.
