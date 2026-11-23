# Research: Multi-Block Text Selection & Export Preview

**Project:** `multi-block-select-export-preview`
**Date:** 2026-11-20

---

## Existing Infrastructure

### Export Layer (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/`)

| File | Role |
|------|------|
| `PageExporter.kt` | Interface: `formatId`, `displayName`, `export(page, blocks, resolvedRefs)` |
| `ExportService.kt` | Orchestrates: bulk-resolves block refs, dispatches to exporter, writes clipboard |
| `PlainTextExporter.kt` | Renders blocks as indented plain text with page title |
| `MarkdownExporter.kt` | Renders blocks as Markdown |
| `HtmlExporter.kt` | Renders blocks as HTML |
| `JsonExporter.kt` | Serializes to `ExportRoot` JSON |
| `ShareExportSettings.kt` | Persists `strip_tracking_params` preference |
| `ShareProvider.kt` | Platform-agnostic share sheet provider |
| `ClipboardProvider.kt` | Abstraction over `writeText`/`writeHtml` |

The `ExportService.exportToClipboard(page, blocks, formatId, stripTrackingParams)` method is the
single entry point for all clipboard exports. It already runs on `PlatformDispatcher.DB`.
A preview variant that returns the `String` without writing the clipboard needs only a small
wrapper (or refactoring to separate "serialize" from "write to clipboard").

### Share / Export UI (`ui/ShareExportCoordinator`, `ui/components/ShareDialog.kt`)

`ShareContent` is the modal dialog body. It already:
- Lets the user pick `ShareScope` (CurrentPage / SelectedBlocks / PageAndLinks / JournalRange)
- Renders format chips (Markdown, Plain Text, HTML, JSON)
- Has a "Copy to Clipboard" primary action and "Export to Drive" secondary action

The coordinator (`ShareExportCoordinator`) owns the export logic, reads
`blockStateManager.selectedBlockUuids`, and resolves scopes including `JournalRange`.

### Block Selection Model (`ui/state/BlockStateManager`, `ui/components/BlockList.kt`)

`BlockList` already carries:
- `selectedBlockUuids: Set<String>` — full-block selection set
- `isInSelectionMode: Boolean`
- `onToggleSelect`, `onEnterSelectionMode`, `onShiftClick`, `onShiftArrowUp/Down`
- `onBlockSelectionChange: ((blockUuid: String, range: IntRange?) -> Unit)?` — text range
  selection within a single block (passed into `BlockItem` as `onSelectionChange`)

The per-block text range is passed down but nothing currently aggregates multi-block ranges.

### Block Rendering (`ui/components/BlockItem.kt`, `BlockViewer.kt`, `BlockEditor.kt`)

Each block is independently rendered. In view mode, `BlockViewer` renders inline nodes as
`AnnotatedString` via `MarkdownEngine`; in edit mode, `BlockEditor` uses `BasicTextField`.
Compose's `SelectionContainer` could wrap the whole `BlockList` to give native OS-level text
selection, but only for view-mode blocks — edit-mode blocks use `BasicTextField` which manages
its own selection, and mixing the two contexts crashes or mis-selects.

A pure-Compose cross-component drag-select requires tracking pointer events at the `BlockList`
level (via `Modifier.pointerInput`), computing which block and character offset the pointer
is over (using `TextLayoutResult` and `onGloballyPositioned` for block positions), and
rendering a custom selection highlight overlay — analogous to how IDE editors implement
multi-cursor selection.

### Feature Flags (`model/FeatureFlag.kt` or similar)

No dedicated `FeatureFlag` enum was found in the initial survey. The project uses a boolean
`Settings` key pattern (e.g. `ShareExportSettings.KEY_STRIP_TRACKING_PARAMS`) or Gradle
property flags. A new flag should follow the same pattern.

---

## Comparable Prior Art in This Codebase

### Drag-and-Drop Reorder (`project_plans/drag-drop-reorder/`)

The existing drag-to-reorder implementation (`BlockList.kt` lines 40–80) already:
- Tracks pointer position at the `BlockList` level via `Modifier.pointerInput`
- Maintains `blockBounds: Map<String, Pair<Float, Float>>` (top/bottom y per block)
  populated by `onGloballyPositioned` on each `BlockItem`
- Runs `computeDropTarget` (a pure function) to resolve the nearest block from Y coordinate

The same infrastructure (pointer tracking + bounds map) is a solid foundation for
cross-block drag-select: X position maps to character offset within the block's
`TextLayoutResult`, Y position maps to the block via `blockBounds`.

### Rich Editing Experience (`project_plans/rich-editing-experience/`)

This project may contain prior art for sub-block text selection handling; not fully surveyed.

---

## Technical Constraints

### Compose Text Selection Boundaries

`SelectionContainer` in Compose wraps child composables and handles pointer-based text
selection, but it cannot span across multiple independent `BasicTextField` instances — each
edit-mode field owns its own `TextInputSession` and selection state. The practical approach
for cross-block selection is:

1. **View-mode only**: Only blocks currently in view mode (not being edited) participate in
   the cross-block selection. If the user is in the middle of editing a block, the standard
   `BasicTextField` selection applies to that block; the cross-block drag does not activate.
2. **Custom overlay**: Render a transparent selection overlay on top of the block list at
   the `BlockList` level, tracking pointer events and deriving character offsets from each
   block's cached `TextLayoutResult`.
3. **Copy on demand**: When copy is triggered, build the string from the selected character
   ranges in document order.

### `TextLayoutResult` Availability

`TextLayoutResult` is available via `BasicText`/`Text`'s `onTextLayout` callback. `BlockViewer`
already renders inline nodes as `AnnotatedString` via `Text`; hooking `onTextLayout` there and
passing the result back to `BlockList` (via callback or `CompositionLocal`) is feasible without
major restructuring.

### Preview Performance

`ExportService.exportToClipboard` already runs on `PlatformDispatcher.DB` and resolves block
refs in bulk. A preview variant should reuse this bulk-resolution logic and return the string
without writing the clipboard. For a 500-block page, Markdown/PlainText serialization is
CPU-bound and should complete in <200ms; HTML may be slightly slower. The preview should
compute asynchronously via `LaunchedEffect` keyed on (scope, format, selectedBlockUuids).

---

## Risk Register

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Pointer tracking + TextLayoutResult aggregation is high effort (2–4 weeks) | High | Feature-flag off by default; ship Export Preview first (lower risk) |
| `SelectionContainer` and cross-block drag conflict on some platforms | Medium | Restrict to view-mode-only; test on Desktop JVM first |
| Preview for large pages (>2000 blocks) causes UI jank | Low | Async computation with loading indicator; limit preview to first 500 blocks with "... truncated" note |
| "List format" mode (mentioned in backlog) is undefined | Medium | Treat as a new `BulletListExporter` (plain text, no indentation, all blocks as `•` bullets); define in plan |
