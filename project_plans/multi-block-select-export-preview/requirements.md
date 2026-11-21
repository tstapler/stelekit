# Requirements: Multi-Block Text Selection & Export Preview

**Project:** `multi-block-select-export-preview`
**Date:** 2026-11-20
**Source:** Backlog item b5fc3864-3e06-422e-8a82-56e4a0002ce4

---

## Problem Statement

SteleKit currently lacks two related features that Logseq offers:

1. **Cross-block text selection**: Users cannot click and drag to select text that spans
   multiple blocks. Each block is an independent `BasicTextField`; Compose's native text
   selection doesn't cross component boundaries. Users wanting to copy multi-block text must
   manually select each block or use the existing block-level selection mode (checkbox/shift-click),
   which selects entire blocks but not arbitrary sub-ranges within them.

2. **Export preview window**: The current Share/Export dialog exports immediately to the clipboard
   or Drive without showing the user what the output looks like. Users cannot see or further
   select content from the formatted output before committing to an export format.

---

## Goals

- Let users drag across block boundaries to select a continuous span of text, then copy it.
- Provide an export preview dialog that renders the formatted output for the selected format,
  lets the user pick among export formats, and allows selecting/copying from the preview text.

---

## Non-Goals (this iteration)

- Cross-page selection (selection is scoped to the currently open page).
- Mobile touch-drag selection (mobile gets the existing block-selection mode; this is primarily
  a desktop/pointer enhancement).
- Saving export presets or export templates.
- Exporting directly to file from the preview (clipboard-copy remains the primary action;
  Drive upload is out of scope for this feature).

---

## Acceptance Criteria

### Feature 1 — Cross-Block Drag Selection

**AC-1.1** On desktop (pointer input), the user can initiate a drag starting from within any
rendered block's text, drag past block boundaries, and release to select a contiguous span
covering text in one or more blocks (read-mode / viewer mode).

**AC-1.2** Selected blocks (fully covered) render with a selection highlight consistent with
the app's existing selection color; partial-start and partial-end blocks show the highlight
only over the selected substring.

**AC-1.3** Pressing Cmd/Ctrl+C (or the standard copy shortcut) while a cross-block selection
is active copies the selected text as plain text in document order (depth-first, ordered by
block position, with newlines between blocks).

**AC-1.4** A single click anywhere in the content area clears the cross-block drag selection.

**AC-1.5** The cross-block selection gesture does not interfere with entering a single block's
edit mode: clicking without dragging (or double-clicking to select a word) enters edit mode in
the clicked block as before.

**AC-1.6** The feature is guarded by a `FeatureFlag.MULTI_BLOCK_DRAG_SELECTION` flag (off by
default) until validated on all targets; enabled by default on Desktop after stabilization.

### Feature 2 — Export Preview Dialog

**AC-2.1** The existing Share/Export dialog gains a "Preview" action (button or tab) that opens
a preview pane showing the rendered output for the currently selected export format and scope.

**AC-2.2** The preview pane updates in real time as the user switches between formats (Markdown,
Plain Text, HTML, JSON).

**AC-2.3** The user can select text within the preview pane and copy it via standard OS shortcuts
(the preview is a selectable, read-only text area).

**AC-2.4** The preview correctly reflects the current scope (CurrentPage, SelectedBlocks,
PageAndLinks, JournalRange) — the same blocks that would be exported to the clipboard are
shown in the preview.

**AC-2.5** For the HTML format the preview renders the HTML source as plain text (the raw HTML
string), not a live web view, to avoid sandboxing/security concerns on all platforms.

**AC-2.6** The preview pane is scrollable and handles large pages (>500 blocks) without
freezing the UI by computing the preview text on a background coroutine (DB dispatcher) and
showing a loading indicator while computing.

**AC-2.7** A "Copy" button in the preview pane triggers the same clipboard write as the
existing export flow.

---

## Open Questions

- **Q1**: Should cross-block drag selection also work in journal view (which renders multiple
  pages in a lazy column)? Initial scope: single-page view only.
- **Q2**: Should the preview pane be a bottom sheet on mobile or a full-width inline expansion
  of the existing dialog?
- **Q3**: Should "list format" (mentioned in the backlog description) be a new `PageExporter`
  variant (e.g. numbered or bulleted list stripping indentation) or just the existing Plain Text
  format which already preserves indentation via two-space indent?
