# Rich Table Editor — Backlog Item

**Status**: Backlog (not started). Needs design (SDD) before implementation.

## Problem

Pipe tables (`| a | b |`) are parsed (`TableBlockNode`, `BlockType.Table`) and there is a `TableBlock` composable (`ui/components/TableBlock.kt`, with `parseTableContent`), but tables are not a good editing experience. The goal is a rich, grid-style table editor that exports back to clean Markdown.

## Scope

- Render a pipe table as a real table in view mode (header row, aligned columns, wrapped cells, horizontal scroll when it does not fit; works on a phone width).
- Edit mode is a grid: edit a cell in place, Tab/Shift+Tab/Enter/arrow navigation, add/remove/reorder rows and columns, set per-column alignment (`:---`, `:---:`, `---:`), and paste a block of cells from a spreadsheet.
- Inline formatting, links and `[[wiki links]]` inside cells keep working (a cell is an inline-Markdown context; escape `|` as `\|`, and a newline as `<br>`).
- Export: the editor serialises to Markdown with normalised (or consistently padded) column widths and a valid delimiter row. Unedited tables must round-trip byte-identical (no gratuitous reformatting of tables the user did not touch).
- A raw-Markdown edit path stays available (toggle between "table" and "source").
- Undo/redo integrates with the existing block undo. Large tables must not make typing laggy.

## Acceptance criteria

1. Property tests: for generated tables (including empty cells, escaped pipes, alignment rows, ragged rows), parse then serialise then parse is stable, and an untouched table re-serialises byte-identical.
2. Editor operations (insert/delete/move row or column, alignment change, paste cells) are unit-tested on a pure table model; the Compose layer is thin.
3. Robolectric UI test for cell editing and keyboard navigation (`androidUnitTest`); one Android device test on the `Pixel_Fold` AVD for the narrow-width layout.
4. Round-trip through `GraphWriter`/`GraphLoader` leaves non-table content in the page unchanged.
5. Decision recorded in an ADR if the table model is shared with the home-sketch measurement-table extractor (`projectbridge/TableExtraction`), which also parses pipe tables.

## Pointers

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TableBlock.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt` (`TableBlockNode`)
- `docs/tasks/parser-gap-analysis.md` (tables row)
