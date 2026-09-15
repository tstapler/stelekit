# ADR-001: Extend the live `FormatAction`/`CommandManager`-bridge system; repoint the reachable slice of the orphaned `editor/` framework and delete the rest

**Status**: Accepted
**Date**: 2026-07-05
**Project**: rich-editing-experience

## Context

Two parallel command/formatting systems exist in the codebase (stack.md §3, features.md §2):

1. **Live system**: `FormatAction` enum + `applyFormatAction()` (`BlockEditor.kt:491`), dispatched from hardware shortcuts (`handleKeyEvent`) and `MobileBlockToolbar` buttons via `BlockStateManager.requestFormat()`/`formatEvents`. This is what actually runs on every platform today.
2. **Orphaned system**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/` — a much larger "enterprise" command framework (`CommandManager`, `CommandRegistry`, `CommandSystem`, `CommandTypes`, `EssentialCommands.kt` (776 lines), `SlashCommandHandler.kt`, `editor/RichTextEditor.kt`, `editor/components/RichTextEditor.kt`, `editor/performance/PerformanceOptimizedEditor.kt`) documenting an `EnhancedCommandPalette` and `/`-slash-command UX that does not exist in the live UI. Confirmed via repo-wide grep: `SlashCommandHandler`, both `RichTextEditor` composables, and `PerformanceOptimizedEditor` have zero call sites outside their own `businessTest` coverage.

Only one slice of system (2) is actually live: `StelekitViewModel` instantiates `CommandManager.create(...)` (`StelekitViewModel.kt:420`) purely to source the list of `EditorCommand`s bridged into the palette's `Command` list (`updateCommands()`, `StelekitViewModel.kt:2017`). Within that slice, `block.toggle-todo` is worse than fully-dead: it computes a `newContent` result and then discards it (`StelekitViewModel.executeCommand()`, line ~1973 never writes the result back) — so it appears in the command palette, labeled "Toggle Todo" with shortcut "Ctrl+Enter" (also present in both `I18n.kt` locales), and silently does nothing when selected. This is a worse failure mode than either fully wiring or fully deleting the feature, because it erodes user trust in the palette generally.

## Decision

1. **Do not resurrect** the orphaned framework's unreachable parts (`/`-slash-command UX, `EnhancedCommandPalette`, rich-text WYSIWYG editing). Nothing in requirements.md asks for slash-command syntax, and resurrecting a fully-built-but-never-wired subsystem would be uncontrolled scope creep against this project's Large-but-bounded appetite.
2. **Delete** the confirmed-zero-call-site files: `editor/commands/SlashCommandHandler.kt`, `editor/RichTextEditor.kt`, `editor/components/RichTextEditor.kt`, `editor/performance/PerformanceOptimizedEditor.kt`, plus their now-stale documentation (`editor/commands/README.md`, `editor/commands/UNDO_REDO_README.md`).
3. **Repoint** the one live, reachable slice: `StelekitViewModel`'s `CommandManager`-sourced palette-ID bridge is kept, but `block.toggle-todo`'s handling is changed to call `blockStateManager.requestTodoToggle()` — the real, working mutation path introduced by this project's Phase C — instead of computing and discarding a `CommandResult`. Every other `EditorCommand` in `EssentialCommands.kt` (text.bold/italic/code/strikethrough/highlight/link/heading) remains excluded from the palette by the existing `requiresSelection=true` filter in `CommandTypes.kt` (confirmed structural, not accidental) and is annotated with a comment clarifying that exclusion is by design, pointing to the real `format.*` palette entries added in Phase F.

**Note (corrected 2026-07-05)**: The TODO-toggle mechanism was corrected during architecture review to a dedicated `TodoState` sum type (`NONE`/`TODO`/`DOING`/`DONE`) + `applyTodoToggle`/`requestTodoToggle()`, deliberately **not** a `FormatAction.TOGGLE_TODO` case — see `architecture-review.md`'s Blockers 1/2 for the full rationale (the three-state cycle doesn't fit `FormatAction`'s binary `(prefix, suffix)` shape, and folding it into `FormatAction` would let heading/quote/etc. silently strip the todo marker or vice versa). See `plan.md`'s Pattern Decisions table ("TODO-toggle state modeling" row) for the complete before/after comparison.

This is sequenced last (Phase H) per architecture.md §5, so it does not conflict with Phases C/E/F's use of the same command-registration surface.

## Consequences

- **Positive**: Eliminates the single worst "wired-looking but silently non-functional" trap in the codebase. Removes ~5 files of confirmed-dead code (RichTextEditor ×2, SlashCommandHandler, PerformanceOptimizedEditor, plus stale docs) without touching anything with a live UI dependency.
- **Positive**: `StelekitViewModel`'s existing dependency on `CommandManager` for palette-ID sourcing is preserved — no regression risk to the palette's existing page/graph-level commands.
- **Negative / accepted risk**: `CommandRegistry`/`CommandSystem`/`CommandTypes`/`EssentialCommands.kt` remain partially in the codebase as a thinner, purpose-narrowed bridge rather than being removed entirely — a future maintainer could still misread them as a general-purpose command framework. Mitigated by the explicit "excluded from palette by design" comments added in Task H.2.2a.
- **Rollback**: Standard `git revert` of the Phase H PR(s); since Phase H is sequenced last and touches no file shared with Phases C-F's user-facing additions, reverting it cannot regress any Phase C-F feature.

## Alternatives Considered

- **Resurrect the full framework** (wire up `/`-commands, `EnhancedCommandPalette`) — rejected: not requested by requirements.md, would blow the Large-but-bounded appetite.
- **Delete the entire `editor/` package including the live `CommandManager` bridge** — rejected: `StelekitViewModel.kt:420` has a confirmed live dependency on it for palette command-ID sourcing; deleting it would require replacing that sourcing mechanism, a larger and unnecessary change with no clear benefit over repointing the one broken command.
