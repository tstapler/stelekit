# Architecture Research: rich-editing-experience (Agent 3)

**Prior analysis**: No existing codebase-hotspot or architecture-review output covers `BlockEditor.kt` / `MobileBlockToolbar.kt` / `EditorToolbar.kt` specifically (checked `project_plans/*/research/architecture.md` repo-wide). `tag-suggestion-trigger` made a small wiring change here but did not analyze the area. Treat this document as the first pass.

## 1. Is action-handling centralized or duplicated?

**Centralized for formatting actions — one function, two entry paths that converge.**

- `BlockEditor.kt:491` `applyFormatAction(action: FormatAction, textFieldValue, onTextFieldValueChange, onLocalVersionIncrement, onContentChange)` is the *only* place that mutates text for BOLD/ITALIC/STRIKETHROUGH/HIGHLIGHT/CODE/LINK/QUOTE/NUMBERED_LIST/HEADING. It handles both "wrap selection" and "toggle line-prefix" semantics.
- **Keyboard path**: `handleKeyEvent` (`BlockEditor.kt:219`) intercepts Ctrl/Cmd+B/I/S/H/E at line 304-328 and calls `applyFormatAction` directly, synchronously, on the local `textFieldValue`.
- **Toolbar path**: `MobileBlockToolbar` → `EditorToolbar.kt:74` `onFormat = { action -> blockStateManager.requestFormat(action) }` → `BlockStateManager.kt:500` `requestFormat()` emits into a `MutableSharedFlow<FormatAction>` (`_formatEvents`, `BlockStateManager.kt:497`) → `BlockItem.kt:220-231` has a `LaunchedEffect(isEditing, formatEvents)` that, **only while `isEditing == true` for that specific block**, collects the flow and calls the *same* `applyFormatAction` function against that block's local `textFieldValue`.

So there is exactly one business-logic function for format mutation, reached via two different plumbing mechanisms: a direct synchronous call (keyboard) vs. an event-bus round-trip through `BlockStateManager` (toolbar). Adding a new `FormatAction` case (e.g. an eleventh formatting style) requires touching `FormatAction` enum + `applyFormatAction`'s only-if-special-cased branches (currently just the line-prefix special case) — **1 place**, both paths pick it up for free once it's in the enum and the toolbar row.

**Not centralized for structural actions (indent/outdent/move/undo/redo/copy/cut/paste/delete-selected/attach-image/link-picker/suggest-tags).** These are NOT modeled as a discriminated action type at all — they're individual callback parameters threaded through three layers of composables (`MobileBlockToolbar` → `EditorToolbar` → `BlockStateManager` method calls) for the toolbar path, and separate direct calls to the same `onIndent`/`onOutdent`/`onMoveUp`/`onMoveDown` callback props inside `handleKeyEvent` (Tab/Shift+Tab at line 432-439, Alt+Up/Down at line 440-463) for the keyboard path. Both paths *do* bottom out in the same `BlockStateManager` methods (`indentBlock`, `outdentBlock`, etc.) — so there's no logic duplication — but there is **wiring duplication**: every new structural action needs a new named callback parameter added to `MobileBlockToolbar`'s signature, `EditorToolbar`'s signature, and (if keyboard-triggerable) a new `when` branch in `handleKeyEvent` plus a new parameter threaded through `BlockEditor`'s and `BlockItem`'s call chains. This is the pattern that will not scale gracefully to "more buttons, more shortcuts."

**Verdict**: `FormatAction` + `applyFormatAction` + the `requestFormat`/`formatEvents` SharedFlow is the right shape and should be the template extended for new actions, rather than continuing the parallel-callback-parameter pattern used for structural actions.

## 2. Shared abstraction between main editor and annotation editor toolbars?

**Recommendation: do not force a shared abstraction. Clear no.**

`MobileBlockToolbar` operates on `FormatAction` (an enum of markdown prefix/suffix pairs applied to a `TextFieldValue` — `BlockEditor.kt`/`JournalsViewModel.kt:26`). `AnnotationToolbar` (`ui/annotate/AnnotationToolbar.kt:59`) operates on `AnnotationTool` (SELECT/CALIBRATE/DISTANCE/AREA/ANGLE/LABEL/GRID_REF — geometric measurement tools on a 2D canvas) plus `MeasurementUnit`. These are not two flavors of the same concept — one is markdown text mutation, the other is drawing-tool selection over an image annotation surface. Their "undo/redo" buttons look superficially identical in both toolbars but are backed by **entirely separate stacks**: `BlockStateManager`'s text-edit undo/redo vs. the annotation screen's own `viewModel.canUndo`/`canRedo` for stroke/calibration history (`AnnotationEditorScreen.kt:160-163`, confirmed no shared `UndoStack` class). Forcing both under one `EditingAction` sealed class would require either (a) a lowest-common-denominator interface with no shared behavior beyond a `label`/`icon`, which buys nothing over just having two enums, or (b) leaky generics that make both call sites worse to read. This is the textbook "wrong abstraction is worse than duplication" case — two small, independently-evolving enums are the correct design. Do not build a shared "EditingAction" type across these two editors.

What *can* reasonably be shared, if desired, is much smaller: a generic `ToolbarButtonSpec(icon, label, shortcut, enabled, onClick)` UI-only data shape for rendering toolbar buttons/rows consistently — that's a presentation-layer commonality, not a domain/action commonality, and is optional polish rather than a requirement.

## 3. Command palette integration points

Found `CommandPalette.kt` (`ui/components/CommandPalette.kt`). Its shape:

- `CommandPalette(visible: Boolean, commands: List<Command>, onDismiss: () -> Unit)` — fuzzy-filters and renders `commands`, invokes `command.action()` on Enter/click.
- `Command` (`ui/AppState.kt:196`): `data class Command(val id: String, val label: String, val shortcut: String? = null, val action: () -> Unit)`.
- Commands are **not** static — they're rebuilt into `AppState.commands` (`AppState.kt:109`, `AppStateOptics.kt:27`) by `StelekitViewModel` (e.g. `StelekitViewModel.kt:2076-2109`, a `legacyCommands` list assembled from current graph/manifest/section state and pushed via `_uiState.update { it.copy(commands = legacyCommands) }`).
- Invocation: global `Cmd/Ctrl+Shift+P` shortcut in `App.kt:1763` (`onCommandPalette()`), rendered from `GraphDialogLayer.kt:118-121`.
- **Today's palette is entirely page/graph-level** (e.g. "Import text as new page", "New {section} journal for today", "Switch journal context") — it has no concept of "the currently-focused/editing block" and no entries for text formatting today.

**Integration shape for "Format > Bold" etc.** (touchpoints only, no code):
1. `StelekitViewModel` (or wherever `legacyCommands` is assembled) would need a reference to the currently-editing `BlockStateManager` to add entries like `Command(id="format.bold", label="Format: Bold", shortcut="Ctrl+B", action = { blockStateManager.requestFormat(FormatAction.BOLD) })`.
2. **Risk to flag explicitly**: `requestFormat` only has an effect because `BlockItem.kt:220`'s `LaunchedEffect(isEditing, formatEvents)` is *actively collecting* — and that collector is scoped to `isEditing == true` for one specific block. If the command palette is opened via `Cmd+Shift+P` while a block is mid-edit and focus is *not* lost, this works. But if opening the palette (a `Dialog`) causes the `BasicTextField` to lose focus/composition state such that `isEditing` flips false before the action fires, the emitted `FormatAction` is silently dropped (SharedFlow has no replay for a departed collector; `_formatEvents` uses `extraBufferCapacity = 1` but no replay cache — a late/absent collector will not see it). This needs to be verified empirically (does opening `CommandPalette`'s `Dialog` blur the block's focus?) before wiring command-palette format actions — otherwise "Format > Bold" from the palette may appear to do nothing.
3. For entries that don't depend on "which block is focused" (e.g. a hypothetical "Insert table at cursor" if it operates purely via the currently-tracked `editingBlockUuid`/`editingCursorIndex` StateFlows rather than the ephemeral `TextFieldValue`), the integration is more robust since those are `StateFlow`s readable independent of focus/composition timing.

## 4. Data flow — pure UI/interaction layer?

**Confirmed pure UI/interaction-layer, no new persistence required, with one thing to watch:**

- `FormatAction` inserts markdown syntax (`**`, `*`, `` ` ``, `^^`, `~~`, `> `, `1. `, `# `) directly into block content text, which flows through the existing `onContentChange(newText, newVersion)` → `BlockStateManager` debounce → `GraphWriter.saveBlock()` path already documented in project `CLAUDE.md`. No schema change, no new SQLDelight query, no new table.
- Tag suggestion (`onSuggestTags`) and link picker (`onLinkPicker`) already round-trip through existing search/tag infrastructure (`SuggestionBottomSheet.kt`, `TagChipRow.kt`, `SearchDialog`) — not touched by this project except at the wiring layer.
- **No exception found.** If a later idea like "TODO toggle" needs a checkbox/task-state concept beyond markdown text (e.g. `TODO`/`DONE` keyword prefix), that's still just a `FormatAction`-shaped text mutation (prefix insert/toggle), consistent with existing `QUOTE`/`NUMBERED_LIST`/`HEADING` line-prefix handling — no persistence change implied. Flag for Phase 3 planning only if research surfaces a genuinely new stored concept (e.g. a "favorite formatting" per-user preference) — nothing found in this pass that requires one.

## 5. Rollout risk (no feature-flag system exists)

Confirmed: only gating mechanism in the codebase is `DebugBuildConfig.isDebugBuild` (`GraphDialogLayer.kt:364`, `App.kt:1296`, `StelekitViewModel.kt:1932/1948`) — a compile-time debug/release switch, not a runtime/per-user flag. `TelemetryDatabase` is performance-stats only, confirmed not a flag store. Requirements' claim of "no feature-flag mechanism" is accurate.

**What makes this unusually risky:**
- Cross-cutting by construction (Complexity 4, "all editing surfaces × all platforms" per requirements) — a naive implementation touches `BlockEditor.kt`, `EditorToolbar.kt`, `MobileBlockToolbar.kt`, `BlockStateManager.kt`, `BlockItem.kt`, `CommandPalette`/`AppState`/`StelekitViewModel`, and `docs/journeys/*.md` in one pass, on 4 platforms (Desktop/Android/iOS/Web) that each render Compose UI slightly differently (mobile toolbar vs. desktop keyboard-first) — a single giant diff would be very hard to review or safely revert.
- No runtime flag means "ship it disabled, enable later" is not available; the only safe rollback for a bad change is `git revert`, which argues strongly for structuring the diff so each revert unit is small and independent.
- **Natural fault lines for incremental, independently revertable diffs** (this is a rollout-sequencing recommendation, not an architecture requirement):
  1. `FormatAction` enum extension + `applyFormatAction` new cases (pure function, easily unit-testable in isolation, zero UI risk).
  2. One `MobileBlockToolbar` row at a time (the toolbar already has 3 rows + a selection-mode variant + a left-handed layout variant — each row is a plausible PR boundary; note left-handed layout must be re-verified per row change since it's a mirrored variant, not a separate code path).
  3. Keyboard-shortcut additions in `handleKeyEvent` (desktop/hardware-keyboard users only — separable from mobile toolbar work since they touch different files and different user populations).
  4. Command-palette entries (additive to `AppState.commands` — can ship after toolbar/shortcut work lands and stabilizes, since it's the highest-risk integration point per finding #3 above).
  5. Annotation editor changes, if any, as a fully separate track — confirmed structurally unrelated to the main editor (finding #2), so it carries no shared-code risk with 1-4 and can proceed on its own timeline.
- Regression-test angle: existing `BlockEditorTest`-style tests (not enumerated here — check `businessTest`/`jvmTest` for `applyFormatAction`/`handleKeyEvent` coverage in Phase 4 validation) should be extended per new `FormatAction` case rather than written once at the end, so each incremental PR in the sequence above ships with its own tests and can be reviewed/reverted atomically.

## Files referenced
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt` (`handleKeyEvent` L219-484, `applyFormatAction` L491-558)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/EditorToolbar.kt` (L65-123 toolbar wiring)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt` (L34-58 signature, L60-282 rows)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt` (L220-231 formatEvents collector)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt` (L497-501 `requestFormat`/`formatEvents`)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsViewModel.kt` (L26-37 `FormatAction` enum, L66/162 delegation)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/CommandPalette.kt` (full file)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (L109, L196-201 `Command`)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (L2076-2109 command assembly)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationToolbar.kt` (full file header, L59-74 signature)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt` (L160-163 undo/redo, structurally separate viewModel)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/GraphDialogLayer.kt` (L118-121, L364), `App.kt` (L1296, L1763)
