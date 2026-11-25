# ADR-007: Column width, freeze and sort indicator are session-only; alignment is data

**Status**: Proposed (confirmed by the Logseq fixture pass in Task 8.4a)
**Date**: 2026-11-24

## Context
Open question 1. GFM has no place for width, freeze or sort order. Any block property is written by `LogseqPageSerializer` as a `key::` line and shown in Logseq (research/architecture.md section 6); the owner wants "no hidden state".

## Decision
- `TableViewState` (column widths in dp, frozen header and first-column flags, current sort indicator) lives in memory for the sheet session. It is never written to the file, never a block property, and not persisted in `Settings` in v1.
- Defaults: frozen header on in the sheet; auto-fit widths on open (min 96dp, max 240dp, existing `TableColumnLayout` rules); width presets Narrow / Medium / Wide / Auto-fit in the header menu. The sheet says once, in the overflow menu, "Column widths reset when you close the sheet".
- Alignment is the one exception because `:---`, `:---:`, `---:` is GFM: `SetAlignment` rewrites only that column's delimiter cell.
- Sort is a data change (row order in the file), labelled "Sort rows A-Z (changes file)" / "Sort rows Z-A (changes file)", one undo entry. The sort indicator is session-only and clears when row order changes by any other edit.
- Resizing never triggers a content write.
- Formula text with `*` runs (`=B2*C2*D2`) can be italicized by an inline-markdown pass (SteleKit's flag-off path, possibly Logseq); the Task 0.2a fixture covers it and the result is recorded here after Task 8.4a.

## Consequences
Zero Logseq risk and no extra serializer surface. Users from Sheets lose widths between sessions; a later opt-in persistence (Settings keyed by block uuid, not in the file) stays possible without a format change.

## Alternatives rejected
Block property (visible in Logseq, hidden state in the file); HTML comment sidecar (other apps may render or strip it).
