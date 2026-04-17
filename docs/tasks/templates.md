# Feature Plan: Templates

> **Status**: Planned | **Priority**: Launch-Critical | **Estimate**: ~2 weeks

---

## Epic Overview

### User Value

Logseq users rely heavily on templates for recurring workflows — daily notes structure, meeting
agendas, project kickoffs, and weekly reviews. Without templates, migrating users must manually
re-type repeated block structures every session, which is a critical friction point and a known
reason users abandon alternative note-taking apps. Implementing templates closes the single
largest parity gap for existing Logseq users.

### Success Metrics

- A user can mark any block (with children) as a template via the `template::` property
- A user can invoke `/template` in the editor and select from a fuzzy-search dialog
- The inserted block tree replaces the trigger line at cursor, fully rendered in the editor
- Dynamic variables (`<% today %>`, `<% time %>`, `<% current page %>`, NLD dates) expand at
  insert time
- Zero regressions to existing block insertion or slash-command behaviour
- Template picker dialog appears within 200ms of typing `/template`
- Template discovery (querying all blocks with `template::` property) completes in < 500ms on
  a graph with 10,000+ blocks

### Scope

**In scope (Must)**
- Template discovery via `PropertyRepository.getBlocksWithPropertyKey("template")`
- Template insertion: deep-clone block tree at cursor with new UUIDs
- Dynamic variable expansion: `<% today %>`, `<% time %>`, `<% current page %>`
- `/template` slash command entry point
- `TemplatePickerDialog` — keyboard-navigable fuzzy-search UI
- NLD date expressions: `<% next monday %>`, `<% last friday %>`, etc. (subset of NLP tokens
  Logseq supports)

**Out of scope (Won't)**
- Template editing UI (users edit the template block directly on its page)
- Template namespacing / organisation beyond the `template` property value
- Template parameters / user-input prompts (would need a separate feature)
- Remote/shared template library

### Constraints

- KMP shared code — no platform-specific date libraries; use `kotlinx-datetime`
- NLD date parsing must be pure Kotlin (no JS or JVM-only libs)
- Block insertion must go through `BlockStateManager` to stay within the existing dirty-block
  architecture and avoid SQLITE_BUSY races
- New slash command must be added to `SlashCommandHandler.mapSlashToCommandId` and
  `mapCommandIdToSlash` for autocomplete symmetry

---

## Architecture Decisions

| # | File | Decision |
|---|------|----------|
| ADR-001 | `project_plans/stelekit/decisions/ADR-001-template-variable-engine.md` | Pure-Kotlin regex-based variable expander over a third-party NLP lib — keeps KMP targets clean and avoids a 2MB+ dependency for a small token set |
| ADR-002 | `project_plans/stelekit/decisions/ADR-002-template-clone-strategy.md` | Deep-clone with new UUIDs at insert time (not stored clones) — matches Logseq semantics where templates are live block trees, not serialised snapshots |

---

## Story Breakdown

### Story 1: Template Discovery Service [~3 days]

**User value**: Blocks tagged with `template:: my-name` are discoverable as named templates
everywhere in the app.

**Acceptance criteria**
- `TemplateRepository` queries `PropertyRepository.getBlocksWithPropertyKey("template")` and
  returns a list of `TemplateDescriptor` (uuid, name, previewText)
- Results are sorted alphabetically by template name
- Blocks without a non-blank `template` property value are excluded
- A `TemplateRepository` integration test passes using the in-memory backend

---

#### Task 1.1 — TemplateDescriptor model + TemplateRepository interface [1h, Micro]

**Objective**: Define the domain model and repository contract for template discovery.

**Context boundary**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/TemplateRepository.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/GraphRepository.kt`

**Prerequisites**
- Understand `PropertyRepository.getBlocksWithPropertyKey` contract
- Understand `Block.properties: Map<String, String>` structure

**Implementation approach**
1. Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/` package
2. Define `data class TemplateDescriptor(val blockUuid: String, val name: String, val previewText: String)`
3. Define `interface TemplateRepository { fun getAllTemplates(): Flow<Result<List<TemplateDescriptor>>> }`

**Validation strategy**
- Unit test: `TemplateDescriptor` is a plain value object — compile-time check is sufficient
- The interface contract will be tested via Task 1.2 implementation

**INVEST check**
- Independent: no prior tasks required
- Negotiable: field names / data class structure is flexible
- Valuable: establishes the contract all other tasks depend on
- Estimable: 1 hour — pure model definition
- Small: single concern
- Testable: compile + contract review

---

#### Task 1.2 — SqlDelightTemplateRepository implementation [2h, Small]

**Objective**: Implement template discovery by querying blocks that have the `template` property.

**Context boundary**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/SqlDelightTemplateRepository.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/TemplateRepository.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/GraphRepository.kt` (`PropertyRepository`)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt` (pattern reference)

**Prerequisites**
- Task 1.1 complete
- `PropertyRepository.getBlocksWithPropertyKey(key)` returns `Flow<Result<List<Block>>>`

**Implementation approach**
1. Implement `SqlDelightTemplateRepository(private val propertyRepository: PropertyRepository, private val blockRepository: BlockRepository) : TemplateRepository`
2. In `getAllTemplates()`:
   - Call `propertyRepository.getBlocksWithPropertyKey("template")`
   - For each block, extract `block.properties["template"]` as the name; skip blank values
   - Build `TemplateDescriptor(blockUuid = block.uuid, name = templateName, previewText = block.content.take(80))`
   - Sort by name alphabetically
3. Return as `Flow<Result<List<TemplateDescriptor>>>`

**Validation strategy**
- Unit test: seed in-memory repository with 3 blocks (2 with `template::`, 1 without), call
  `getAllTemplates()`, assert only 2 results with correct names and preview text
- Unit test: block with blank template value (`template:: ` with whitespace) is excluded

**INVEST check**
- Independent: depends only on 1.1 (interface)
- Valuable: core query that the picker and slash command depend on
- Estimable: 2 hours
- Testable: fully testable with `InMemoryRepositories`

---

### Story 2: Template Variable Expansion Engine [~2 days]

**User value**: When a template is inserted, dynamic placeholders like `<% today %>` expand to
their current values so users don't have to manually update dates.

**Acceptance criteria**
- `<% today %>` expands to current date in `yyyy-MM-dd` format (journal-link compatible)
- `<% time %>` expands to current time in `HH:mm` format
- `<% current page %>` expands to the name of the page where insertion is triggered
- NLD expressions `<% next monday %>`, `<% last friday %>`, `<% 2 days from now %>` expand
  to the computed `LocalDate` in `yyyy-MM-dd`
- Unknown tokens are left as-is (no data loss)
- `TemplateVariableExpander` has 100% unit test coverage on the expansion logic

---

#### Task 2.1 — TemplateVariableExpander: core token engine [2h, Small]

**Objective**: Pure Kotlin function that replaces `<% token %>` patterns in a string.

**Context boundary**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/TemplateVariableExpander.kt` (new)
- Supporting: none (pure logic, uses `kotlinx-datetime` only)

**Prerequisites**
- Understand `kotlinx.datetime.Clock.System.now()` and `LocalDate` / `LocalTime` APIs

**Implementation approach**
1. Define `object TemplateVariableExpander`
2. Entry point: `fun expand(content: String, context: TemplateContext): String`
3. `data class TemplateContext(val currentPageName: String, val clock: Clock = Clock.System)`
4. Use regex `<%\s*(.+?)\s*%>` to find all tokens
5. Dispatch token string to `resolveToken(token: String, context: TemplateContext): String?`:
   - `"today"` → `LocalDate.now(context.clock, TimeZone.currentSystemDefault()).toString()`
   - `"time"` → format `LocalTime.now(context.clock, TimeZone.currentSystemDefault())` as `HH:mm`
   - `"current page"` → `context.currentPageName`
   - anything else → delegate to `NldDateResolver.tryResolve(token)` — returns `null` if unknown
6. If resolution is `null`, leave the original `<% token %>` string unchanged

**Validation strategy**
- Unit tests covering: today, time, current page, unknown token passthrough
- NLD tests are in Task 2.2

**INVEST check**
- Independent: standalone pure function
- Testable: fully deterministic with an injected `Clock`

---

#### Task 2.2 — NldDateResolver: natural language date subset [2h, Small]

**Objective**: Resolve a small fixed set of NLD date tokens Logseq supports.

**Context boundary**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/NldDateResolver.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/TemplateVariableExpander.kt`

**Prerequisites**
- Task 2.1 complete

**Implementation approach**
1. `object NldDateResolver { fun tryResolve(token: String, clock: Clock, tz: TimeZone): String? }`
2. Normalise token to lowercase, trim
3. Pattern-match using a `when` expression on common tokens:
   - `"yesterday"`, `"tomorrow"`, `"next <weekday>"`, `"last <weekday>"`, `"N days from now"`,
     `"N days ago"`, `"N weeks from now"`, `"N weeks ago"`
4. Use `LocalDate.now(clock, tz).plus(N, DateTimeUnit.DAY)` for arithmetic
5. Weekday resolution: compute `DayOfWeek` offset from today
6. Return formatted `LocalDate.toString()` or `null` if token doesn't match any pattern

**Validation strategy**
- Unit tests: `"next monday"` from a Thursday returns the correct date
- Unit tests: `"3 days from now"` from a known date
- Unit tests: `"unknown token"` returns null
- Inject a fixed `Clock` to avoid flakiness

---

### Story 3: Template Insertion Command [~3 days]

**User value**: When a user selects a template from the picker, its full block tree (with children)
is cloned and inserted at the cursor, with all variables expanded.

**Acceptance criteria**
- `InsertTemplateCommand` is undoable (implements `Command<Unit>`)
- The inserted blocks have fresh UUIDs — they are independent copies, not references
- The template's root block replaces the current (trigger) block's content; children are inserted
  as siblings beneath it
- All `<% ... %>` variables are expanded before saving to the repository
- Undo removes all inserted blocks and restores the original trigger block content

---

#### Task 3.1 — BlockTreeCloner utility [2h, Small]

**Objective**: Deep-clone a block tree with new UUIDs, preserving parent/child structure.

**Context boundary**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/BlockTreeCloner.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` (`Block`)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/util/UuidGenerator.kt`

**Prerequisites**
- Understand `Block` fields: `uuid`, `parentUuid`, `leftUuid`, `position`, `pageUuid`

**Implementation approach**
1. `object BlockTreeCloner`
2. `fun clone(blocks: List<Block>, targetPageUuid: String, targetParentUuid: String?, positionOffset: Int, expander: (String) -> String): List<Block>`
   - `blocks` is the ordered flat list from `getBlockHierarchy` (root first, BFS order)
   - Build a `oldUuid → newUuid` mapping for every block in the list
   - For each block:
     - New UUID via `UuidGenerator.generate()`
     - Remap `parentUuid` and `leftUuid` via the mapping
     - Apply `expander` to `content`
     - Set `pageUuid = targetPageUuid`
     - Offset root-level `position` by `positionOffset`
   - Return new flat list in the same BFS order

**Validation strategy**
- Unit test: clone a 3-block tree (root + 2 children); assert all UUIDs are fresh, parent
  references are internally consistent, content expander was applied
- Unit test: clone preserves level/depth information

---

#### Task 3.2 — InsertTemplateCommand [3h, Medium]

**Objective**: Undoable command that fetches a template's block tree, clones it, and saves to the
repository.

**Context boundary**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/InsertTemplateCommand.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/BlockCommands.kt` (pattern)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/BlockTreeCloner.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/TemplateVariableExpander.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/GraphRepository.kt`

**Prerequisites**
- Tasks 2.1, 2.2, 3.1 complete
- `Command<T>` interface from `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/Command.kt`

**Implementation approach**
1. `class InsertTemplateCommand(repository, templateBlockUuid, triggerBlockUuid, targetPageUuid, context: TemplateContext) : Command<Unit>`
2. `execute()`:
   a. Fetch template block hierarchy via `repository.getBlockHierarchy(templateBlockUuid).first()`
   b. Strip the `template::` property from the root block's content/properties in the clone
   c. Create expander lambda: `{ content -> TemplateVariableExpander.expand(content, context) }`
   d. Clone with `BlockTreeCloner.clone(...)`, targeting `targetPageUuid` and `triggerBlock.parentUuid`
   e. Save cloned blocks: `repository.saveBlocks(clonedBlocks)`
   f. Update trigger block content to match template root block content (or delete trigger block
      if it was a blank placeholder)
3. `undo()`:
   - Delete all inserted block UUIDs
   - Restore trigger block to its original content

**Validation strategy**
- Integration test with in-memory repository: call `execute()`, verify cloned blocks exist with
  correct content and expanded variables; call `undo()`, verify inserted blocks are gone and
  trigger block is restored
- Test: `template::` property does NOT appear in the inserted blocks

---

### Story 4: Template Picker UI + Slash Command Integration [~3 days]

**User value**: Users can type `/template` in any block and see a searchable list of all their
templates, then select one to insert.

**Acceptance criteria**
- `/template` appears in slash command autocomplete suggestions
- Triggering `/template` opens `TemplatePickerDialog`
- The dialog shows a fuzzy-filtered list of template names with preview text
- Keyboard (arrow keys, Enter, Escape) and mouse/touch navigation work
- Selecting a template closes the dialog and calls `InsertTemplateCommand`
- The dialog is accessible: focus trapped, labelled for screen readers
- Empty state ("No templates found") shown when graph has no templates

---

#### Task 4.1 — Register `/template` slash command [1h, Micro]

**Objective**: Add `template` to the slash command registry so it appears in autocomplete and
triggers the picker.

**Context boundary**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/SlashCommandHandler.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`

**Prerequisites**
- Understand `SlashCommandHandler.mapSlashToCommandId` / `mapCommandIdToSlash` pattern
- Understand how `AppState` carries dialog-visible flags

**Implementation approach**
1. Add `"template" -> "template.insert"` to `mapSlashToCommandId`
2. Add `"template.insert" -> "template"` to `mapCommandIdToSlash`
3. Add a `templatePickerVisible: Boolean = false` flag to `AppState`
4. Register `"template.insert"` command in `EssentialCommands` or a new `TemplateCommands`
   object; on execute, set `templatePickerVisible = true` in the view model

**Validation strategy**
- Manual smoke test: type `/template` in editor, verify it appears in autocomplete
- Unit test: `mapSlashToCommandId("template")` returns `"template.insert"`

---

#### Task 4.2 — TemplatePickerDialog Composable [3h, Medium]

**Objective**: Reusable search dialog for templates, modelled after `SearchDialog`.

**Context boundary**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TemplatePickerDialog.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SearchDialog.kt` (pattern)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/TemplateRepository.kt`

**Prerequisites**
- Tasks 1.1, 1.2 complete
- Familiarity with `SearchDialog` structure and keyboard handling

**Implementation approach**
1. Create `@Composable fun TemplatePickerDialog(visible: Boolean, onDismiss: () -> Unit, onSelect: (TemplateDescriptor) -> Unit, viewModel: TemplatePickerViewModel)`
2. `TemplatePickerViewModel(templateRepository: TemplateRepository)`:
   - `uiState: StateFlow<TemplatePickerState>`
   - `TemplatePickerState(query: String, templates: List<TemplateDescriptor>, isLoading: Boolean)`
   - Client-side fuzzy filter on `name` field (templates list is small; no DB round trip needed)
3. Dialog layout (copy `SearchDialog` structure):
   - `TextField` with focus request
   - `LazyColumn` of template items showing `name` (bold) + `previewText` (secondary)
   - Empty-state `Text("No templates found")` when list is empty
4. Keyboard: Arrow Up/Down navigate, Enter selects, Escape dismisses

**Validation strategy**
- Screenshot test (Roborazzi): dialog with 3 mock templates rendered
- Unit test: `TemplatePickerViewModel` filters correctly on query change
- Manual test: keyboard navigation works on desktop

---

#### Task 4.3 — Wire dialog into StelekitViewModel + BlockStateManager [2h, Small]

**Objective**: Connect `TemplatePickerDialog` selection to `InsertTemplateCommand` execution via
the existing ViewModel orchestration layer.

**Context boundary**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/InsertTemplateCommand.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`

**Prerequisites**
- Tasks 3.2, 4.1, 4.2 complete

**Implementation approach**
1. Add `fun onTemplateSelected(descriptor: TemplateDescriptor, triggerBlockUuid: String)` to
   `StelekitViewModel`
2. Inside: build `TemplateContext(currentPageName = currentPage.name)`, construct
   `InsertTemplateCommand`, execute via `writeActor` (or direct if writeActor is null)
3. On success: hide picker via `_appState.update { it.copy(templatePickerVisible = false) }`
4. On failure: emit error notification via `notificationManager`
5. Pass `onTemplateSelected` lambda through to `TemplatePickerDialog` in `App.kt`
6. Show `TemplatePickerDialog` in `App.kt` when `appState.templatePickerVisible`

**Validation strategy**
- Integration test: mock `TemplateRepository` returning 1 template; call
  `onTemplateSelected`, verify `InsertTemplateCommand.execute()` was invoked and
  `templatePickerVisible` becomes false
- Null-safety test: no crash when `currentPage` is null (picker should not be openable without
  an active page)

---

## Known Issues

### Bug 001: `template::` property leaks into inserted block content [SEVERITY: High]

**Description**: When the template root block is cloned, its `content` string may contain the
`template:: my-name` property line as plain text (depending on how the parser stores properties).
If this line is not stripped before insertion, users will see the property in their inserted block,
creating noise and potentially breaking the block's semantic type.

**Files likely affected**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/BlockTreeCloner.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/InsertTemplateCommand.kt`

**Mitigation**
- In `InsertTemplateCommand.execute()`, before cloning, remove any line matching
  `^template::.*$` (case-insensitive) from the root block's content
- Also remove the `"template"` key from `clonedRoot.properties`
- Add a specific unit test asserting this removal

**Prevention**
- The `BlockTreeCloner.clone()` signature should accept an explicit `stripProperties: Set<String>`
  parameter so that this stripping is explicit and testable

---

### Bug 002: NLD date resolver locale ambiguity [SEVERITY: Medium]

**Description**: `kotlinx-datetime`'s `TimeZone.currentSystemDefault()` may behave differently
across JVM, Android, and iOS if the device timezone is not set. "Today" could resolve to a
different calendar date than the user expects.

**Files likely affected**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/NldDateResolver.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/TemplateVariableExpander.kt`

**Mitigation**
- Inject `TimeZone` into `TemplateContext` rather than calling `TimeZone.currentSystemDefault()`
  inside the expander
- Platform entry points (desktop `Main.kt`, Android entry) pass the platform timezone at
  `TemplateContext` construction time

**Prevention**
- `TemplateContext` should require a non-default `TimeZone` parameter

---

### Bug 003: UUID collision risk in large batch inserts [SEVERITY: Low]

**Description**: `UuidGenerator.generate()` is called once per cloned block in a tight loop.
On platforms with a weak random source (early Android) there is a non-zero (though vanishingly
small) chance of UUID collision against existing blocks, causing a SQL constraint violation.

**Files likely affected**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/BlockTreeCloner.kt`

**Mitigation**
- Wrap `repository.saveBlocks(clonedBlocks)` in a `try-catch`; on `SQLiteConstraintException`,
  regenerate UUIDs and retry once
- Alternatively, prefix cloned UUIDs with a timestamp to reduce collision space

---

### Bug 004: Blank trigger block not cleaned up after insertion [SEVERITY: Medium]

**Description**: When the user types `/template` in a fresh empty block, that empty block remains
in the page after template insertion unless explicitly handled. The result is an orphan empty block
above the inserted template content.

**Files likely affected**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/InsertTemplateCommand.kt`

**Mitigation**
- In `InsertTemplateCommand.execute()`, check if `triggerBlock.content.trim()` equals `/template`
  (or is blank after stripping the slash command text); if so, delete the trigger block and insert
  the template root in its position
- `undo()` must then also re-create the blank trigger block

---

## Dependency Visualization

```
Story 1: Template Discovery
  Task 1.1 (model + interface)
    └─> Task 1.2 (SqlDelight impl)
           │
           └──────────────────────────┐
                                      ▼
Story 2: Variable Expansion        Story 3: Insertion Command
  Task 2.1 (core expander)           Task 3.1 (BlockTreeCloner)
    └─> Task 2.2 (NLD dates)            │
                │                       │
                └───────────────────────┤
                                        ▼
                                  Task 3.2 (InsertTemplateCommand)
                                        │
                          ┌─────────────┘
                          ▼
Story 4: UI + Slash Command Integration
  Task 4.1 (register slash cmd)
  Task 4.2 (TemplatePickerDialog)  ← depends on Story 1 (TemplateRepository)
    └─> Task 4.3 (wire into ViewModel)  ← depends on Tasks 3.2, 4.1, 4.2
```

**Parallel opportunities**:
- Stories 1 and 2 are fully independent and can be worked in parallel
- Task 3.1 (BlockTreeCloner) can start once Story 1 interface (Task 1.1) is done
- Task 4.2 (UI) can start as soon as Task 1.1 (TemplateDescriptor model) is done

---

## Integration Checkpoints

**After Story 1**: Run the integration test with the in-memory backend — `getAllTemplates()` returns
correct descriptors for a seeded graph. No UI yet, but the data layer is verified.

**After Story 2**: Unit test suite for `TemplateVariableExpander` and `NldDateResolver` fully
green. Manually verify `TemplateVariableExpander.expand("<% today %> - <% current page %>", ctx)`
returns a correctly formatted string.

**After Story 3**: Integration test for `InsertTemplateCommand` passes (execute + undo cycle).
Manually verify in a running app that calling the command directly inserts a cloned block tree.

**After Story 4 (Complete feature)**: End-to-end manual test:
1. Open a page, add a block `Meeting Notes` with children `- Attendees`, `- Action Items`
2. Add property `template:: meeting` to the root block
3. Navigate to a new page, type `/template`, select "meeting"
4. Assert the block tree is inserted with correct structure, `template::` stripped, and
   `<% today %>` (if present) expanded
5. Cmd+Z — assert undo removes inserted blocks

---

## Context Preparation Guide

### Task 1.1 — TemplateDescriptor model

Files to load:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` — `Block`, `Property`, `Validation` patterns
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/GraphRepository.kt` — `PropertyRepository` interface

Concepts to understand:
- `Block.properties: Map<String, String>` stores parsed properties inline
- `Flow<Result<T>>` is the standard async return type throughout the codebase

### Task 1.2 — SqlDelightTemplateRepository

Files to load:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/TemplateRepository.kt` (Task 1.1 output)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/GraphRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryRepositories.kt` (test backend)

Concepts to understand:
- `PropertyRepository.getBlocksWithPropertyKey(key)` returns blocks that have that key set
- `Flow.map {}` transforms results reactively

### Task 2.1 — TemplateVariableExpander

Files to load: none (pure logic)

Concepts to understand:
- `kotlinx.datetime.Clock`, `LocalDate`, `LocalTime`, `TimeZone`
- Regex `Regex("<%\\s*(.+?)\\s*%>")` token extraction pattern

### Task 3.1 — BlockTreeCloner

Files to load:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` — `Block` field semantics
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/util/UuidGenerator.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/data/repositories/IBlockRepository.kt` — `BlockWithDepth`

Concepts to understand:
- `Block.parentUuid` / `Block.leftUuid` form a linked-list structure; both must be remapped
- `position` is a sibling-order integer; only root-level blocks of the cloned tree need offset

### Task 3.2 — InsertTemplateCommand

Files to load:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/BlockCommands.kt` — undo/redo pattern
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/Command.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/BlockTreeCloner.kt` (Task 3.1 output)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/TemplateVariableExpander.kt`

Concepts to understand:
- `Command<Unit>` has `execute()` and `undo()` — both must be atomic and reversible
- `repository.getBlockHierarchy(uuid).first()` is the synchronous way to fetch a block tree

### Task 4.1 — Register slash command

Files to load:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/SlashCommandHandler.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/EssentialCommands.kt`

Concepts to understand:
- The two-way mapping `mapSlashToCommandId` / `mapCommandIdToSlash` must be kept symmetric
- `AppState` boolean flags drive dialog visibility in `App.kt`

### Task 4.2 — TemplatePickerDialog

Files to load:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SearchDialog.kt` — structural reference
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/TemplateRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/SearchViewModel.kt` — ViewModel pattern

Concepts to understand:
- `SearchDialog` already handles keyboard navigation, `FocusRequester`, and `LazyColumn` scroll
  sync — copy this pattern exactly
- `TemplatePickerViewModel` should use client-side filter (not DB) since template count is small

### Task 4.3 — Wire into ViewModel

Files to load:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (first 120 lines)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/template/InsertTemplateCommand.kt`

Concepts to understand:
- `writeActor` is the preferred path for DB writes in ViewModel; fall back to direct write if null
- `notificationManager.show(...)` emits user-visible error/success toasts

---

## Success Criteria

- [ ] All 8 atomic tasks completed and individually validated
- [ ] `TemplateRepository` integration test passes on in-memory backend
- [ ] `TemplateVariableExpander` unit tests achieve 100% branch coverage on the expansion logic
- [ ] `InsertTemplateCommand` execute + undo cycle passes integration test
- [ ] `TemplatePickerDialog` screenshot test passes (Roborazzi)
- [ ] `TemplatePickerViewModel` fuzzy filter unit test passes
- [ ] `/template` slash command appears in autocomplete
- [ ] End-to-end manual test: create template, insert via `/template`, verify structure and variable expansion, undo
- [ ] `template::` property does NOT appear in inserted blocks
- [ ] No regressions on existing slash command tests
- [ ] Template picker opens within 200ms (manual observation)
- [ ] Template discovery completes within 500ms on a graph with 10,000+ blocks (manual benchmark)
