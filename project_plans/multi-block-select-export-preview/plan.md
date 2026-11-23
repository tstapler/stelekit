# Implementation Plan: Multi-Block Text Selection & Export Preview

**Project:** `multi-block-select-export-preview`
**Date:** 2026-11-20

---

## Phasing

The two features are independent and differ significantly in scope/risk:

| Phase | Feature | Effort | Risk |
|-------|---------|--------|------|
| A | Export Preview Dialog | Medium | Low |
| B | Bullet-List Export Format | Small | Very Low |
| C | Cross-Block Drag Selection | Large | High |

Ship Phase A and B first; Phase C is gated behind a feature flag and can follow independently.

---

## Phase A — Export Preview Dialog

### A.1 — Refactor `ExportService` to separate serialize from write

**Files:** `export/ExportService.kt`

Add a `suspend fun buildExportString(page, blocks, formatId, stripTrackingParams): Either<DomainError, String>`
that runs the same pipeline as `exportToClipboard` but returns the string instead of writing
the clipboard. `exportToClipboard` becomes a thin wrapper that calls this, then writes.

This keeps the preview and the copy action in sync by construction.

### A.2 — Add `ExportPreviewState` to `AppState`

**Files:** `ui/AppState.kt`

Add fields:
```kotlin
val sharePreviewVisible: Boolean = false
val sharePreviewText: String? = null           // null = loading or not yet computed
val sharePreviewIsLoading: Boolean = false
```

### A.3 — Wire preview computation in `ShareExportCoordinator`

**Files:** `ui/ShareExportCoordinator.kt`

Add `fun showPreview()` and `fun hidePreview()`. `showPreview()` launches a coroutine on
`PlatformDispatcher.DB` using `buildExportString` (from A.1), sets `sharePreviewIsLoading =
true` while loading, and writes `sharePreviewText` when done.
Re-trigger whenever `shareFormat` or `shareScope` changes while the preview is visible
(via `uiState.map { ... }.distinctUntilChanged().collect { ... }` on the coordinator's scope).

### A.4 — Add `SharePreviewPane` composable

**Files:** `ui/components/SharePreviewPane.kt` (new)

A `@Composable` that renders:
- A `CircularProgressIndicator` while `sharePreviewIsLoading`
- A `SelectionContainer { BasicText(...) }` for the preview text (selectable, monospace font)
- A "Copy" `FilledTonalButton` that calls `viewModel.exportPage()` or `viewModel.exportSelectedBlocks()`

### A.5 — Integrate into `ShareContent`

**Files:** `ui/components/ShareDialog.kt`

Add a "Preview" `OutlinedButton` next to the existing "Copy to Clipboard" button. When tapped,
it sets `sharePreviewVisible = true` and the pane slides in below the format picker (or as a
bottom sheet on mobile, checking `isMobile`). Closing the preview sets `sharePreviewVisible =
false` and clears `sharePreviewText`.

---

## Phase B — Bullet List Export Format

### B.1 — Add `BulletListExporter`

**Files:** `export/BulletListExporter.kt` (new)

`PageExporter` implementation with `formatId = "bullet-list"` and `displayName = "Bullet List"`.
Renders every block as `• <content>` (stripping indentation/hierarchy), with inline nodes
rendered via the same `PlainTextExporter.renderPlainText` logic (extract to a shared
`InlineTextRenderer` or make `renderPlainText` internal in a shared location).

### B.2 — Register in `ExportService` factory / DI

**Files:** `ui/GraphContentGraphIoSetup.kt` (or wherever `ExportService` is constructed)

Add `BulletListExporter()` to the `exporters` list. No schema changes required.

### B.3 — Add `bullet-list` chip to `ShareContent`

**Files:** `ui/components/ShareDialog.kt`

Add the new format chip alongside Markdown / Plain Text / HTML / JSON.

---

## Phase C — Cross-Block Drag Selection (feature-flagged)

### C.1 — Add feature flag

**Files:** `model/FeatureFlag.kt` (or `platform/Settings.kt` key constant)

Add `Settings` key `feature.multi_block_drag_select` (Boolean, default `false`).
Desktop builds default to `true` after the feature is stabilized.

### C.2 — Expose `TextLayoutResult` per block

**Files:** `ui/components/BlockViewer.kt`, `ui/components/BlockItem.kt`

Extend `BlockItem`'s `onSelectionChange: ((range: IntRange?) -> Unit)?` callback (already
present) to also carry a `TextLayoutResult`. Or add a separate
`onTextLayout: ((TextLayoutResult) -> Unit)?` callback. `BlockList` aggregates these into
`textLayoutResults: Map<String, TextLayoutResult>` (UUID → result), updated in a
`remember { mutableStateMapOf<String, TextLayoutResult>() }`.

### C.3 — Cross-block drag gesture at `BlockList` level

**Files:** `ui/components/BlockList.kt`

Add a `Modifier.pointerInput(Unit) { detectDragGestures(...) }` to the outer `Box` wrapping
the `Column` of blocks. On drag start:
1. Determine which block was hit by Y coordinate (using existing `blockBounds` map).
2. Determine character offset within that block by calling `TextLayoutResult.getOffsetForPosition(localPos)`.
3. Record `dragSelectStart: BlockCharOffset?`.

On each drag update, record `dragSelectEnd: BlockCharOffset?` and emit
`CrossBlockSelection(startBlockUuid, startCharOffset, endBlockUuid, endCharOffset)` via a
`MutableStateFlow` on `BlockList`'s caller (or via a `CompositionLocal`).

### C.4 — Render selection highlight overlay

**Files:** `ui/components/BlockItem.kt`, `ui/components/BlockViewer.kt`

For fully-selected blocks, render a full-width `Box` with `MaterialTheme.colorScheme.primary.copy(alpha=0.2f)`.
For partial (start/end) blocks, render a highlight `Box` whose left/right bounds come from
`TextLayoutResult.getBoundingBox(range)`.

### C.5 — Copy handler for cross-block selection

**Files:** `ui/components/BlockList.kt` (key event handler already present)

When `Ctrl/Cmd+C` is pressed and `crossBlockSelection != null`, call a new
`onCopyCrossBlockSelection: (CrossBlockSelection) -> Unit` callback. The ViewModel resolves
the block UUIDs in document order, extracts the substring from each block's content (applying
`InlineParser` to strip markup), joins with `\n`, and writes to clipboard.

### C.6 — Clear selection on single click

The existing `onBlockClick` handler in `BlockList` already fires on tap; add logic to clear
`crossBlockSelection` state when a click event is detected without drag.

---

## Test Plan

| Test | Location | Covers |
|------|----------|--------|
| `ExportServicePreviewTest` | `businessTest` | `buildExportString` returns same output as `exportToClipboard` for all formats |
| `BulletListExporterTest` | `businessTest` | Renders flat list from nested blocks, inline node rendering |
| `SharePreviewPaneTest` | `jvmTest` (Compose) | Loading state, text appears after async computation, Copy button triggers export |
| `CrossBlockSelectionModelTest` | `commonTest` | `computeCrossBlockSelection` pure function: char range → block order → extracted text |
| `CrossBlockDragSelectTest` | `jvmTest` | Drag gesture selects expected blocks, Ctrl+C copies expected text, single click clears |

---

## Adversarial Review

**Concern 1 — Phase C complexity creep**: The drag selection mechanism touches pointer
input, `TextLayoutResult`, custom rendering, and clipboard — all tricky to get right across
Desktop and Android. If the pointer and text layout APIs differ materially between JVM and
Android targets, this could double the effort. Mitigation: restrict Phase C to Desktop-only
(JVM target) initially and add the feature flag per AC-1.6.

**Concern 2 — `TextLayoutResult` state management**: `TextLayoutResult` instances are
large objects holding full glyph/line info. Storing them in a `SnapshotStateMap` for every
visible block while the user is not dragging wastes memory. Mitigation: only store them
transiently during an active drag gesture; outside a drag the map can be cleared.

**Concern 3 — Preview dialog on mobile**: A preview text area in a `BottomSheet` on Android
has poor ergonomics (small area, keyboard pushes it up). Mitigation: on mobile, the preview
should be a full-screen dialog rather than a sheet, or be deferred to a later iteration.

**Concern 4 — `buildExportString` for `JournalRange` scope**: The journal range may cover
hundreds of pages; computing it for a preview could be very slow. Mitigation: cap the preview
at the first 50 pages with a "… N more pages" note, or disable the Preview button for
`JournalRange` scope.
