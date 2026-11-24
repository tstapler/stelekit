# Research: feature landscape — table-sheet-editor

Confidence labels: VERIFIED = read in this repo; INDUSTRY = from my knowledge of the products, not re-fetched this session (web tools unavailable); INFERRED = reasoning.

## 1. In-repo analogues (VERIFIED unless noted)

| Area | What exists | Implication |
|---|---|---|
| Parser | `ui/components/TableModel.kt`: `splitTableRow`, `parseTableContent`, `parseAlignmentCell`, `isNumericCell`. **No serializer exists.** | Serializer + round trip is net-new; the "byte-identical untouched" requirement means keep the original raw string and only re-serialize edited tables (or serialize per-row). |
| Lossiness today | `parseTableContent` drops body cells beyond header count, trims every cell, discards blank lines, returns an empty `TableData` for <2 lines, and normalizes the delimiter row to `TableColumnAlignment`. | Fine for display; unacceptable as an edit model. Need a lossless model (raw cell text, original delimiter row, leading/trailing pipe style, padding) or a "dirty row" strategy. |
| Escapes | `splitTableRow` turns `\|` into a literal `|` on parse; serializer must re-escape every `|` in a cell. A line ending in `\|` is handled. Pipes inside backtick code spans still split in GFM unless escaped; parser matches that. | Serializer must escape `|` in all cells, including those pasted from TSV. |
| Block undo | `ui/state/BlockUndoManager.kt`: closure-pair stack (`record(undo, redo)`), cap 100, redo cleared on record, scoped to `BlockStateManager`. | Cell edits can record one entry per committed cell edit/structural op, with closures that write the whole block content. Typing inside a cell must coalesce to one entry on commit, not one per keystroke. |
| Block editing | `BlockStateManager`: `editingBlockUuid`, `editingCursorIndex`, `editingSelectionRange`, 300 ms disk debounce (`DebounceManager`), `writeContentOnly`, pending-content-write counters, `BlockSelectionManager` for block-level multi-select. | Selection here is block-granular; a cell/range selection is a different model. Inline cell edit must not trigger "editing block" raw mode, and external-change (`DiskConflict`) while a cell is mid-edit needs a rule. |
| Sketch editor | `ui/sketch/v2/KeyboardActions.kt` has `UndoAnnouncer` ("Undid: <label>", "nothing to undo"), `ui/annotate/UndoHistory.kt` is a snapshot stack (`push(before)`, cap 50). Sketch uses `ToolContext.pxPerDp` / `hitTolerancePx` for touch targets. | Reuse: labeled undo announcements for accessibility; dp-based hit areas for resize handles/cell edges. Snapshot undo (whole `TableData`) is simpler than closures for sort/paste/structural edits. |
| Renderer | `TableBlock.kt` (269 lines): wrap, `WikiLinkText`, `LocalUriHandler` link taps, tallest-cell row height. Tests `TableBlockLayoutTest`, `TableBlockScreenshotTest`. | Regression anchors from requirements.md. |

## 2. Industry landscape (INDUSTRY)

- **Google Sheets / Excel**: arrow/Tab/Enter/Shift variants, F2 or typing to enter edit, Esc cancels edit, Enter commits and moves down, Tab commits and moves right; range select by drag/Shift+arrows, Ctrl+A; fill handle; paste as TSV (with quoted multi-line cells); formulas `=` prefix, case-insensitive functions, relative references shift on insert/delete/move, `#REF!` when a referenced cell is deleted, `#DIV/0!`, `#NAME?`, `#VALUE!`; Sheets flags circular refs as `#REF!` ("Circular dependency detected"), Excel warns and returns 0. Sort is stable in Sheets; sort keeps header via "data has header row"; sort does NOT rewrite formula references' meaning in Excel except relative refs within the same row. Locale decides decimal and argument separators (`;` vs `,`).
- **Notion tables/databases**: cell edit in place, typed columns, sort/filter as view state saved per view (not in data), formulas hidden state-free (property formulas). Row drag handles, column resize persisted.
- **Obsidian Advanced Tables plugin**: stays within GFM text; Tab/Enter navigation, auto-format (pads columns with spaces), alignment via delimiter row, sort via command, "formulas" in a trailing `<!-- TBLFM -->`-style comment (separate from cells), moves rows/cols via commands. Lesson: formatted padding churns diffs; and its formulas live out-of-band.
- **Logseq**: renders pipe tables via mldoc (basic GFM); no cell edit; clicking opens raw block editing. Does not understand formulas. Unknown/extra markup in cells (HTML, `<br>`) renders as text/HTML inconsistently. Block properties are the only metadata channel (`key:: value` lines), which Logseq tolerates; arbitrary extra block properties on a table block are preserved but invisible (INFERRED about the table-block case; verify by opening a fixture in Logseq before choosing where to persist view state).
- **Typora**: WYSIWYG table with insert/delete/align via toolbar, Tab/Enter nav, writes tidy GFM, supports `<br>` for line breaks, no formulas.
- Common ground: keyboard nav and copy/paste TSV are table stakes; formulas are rare in markdown tools and live out-of-band when present. The owner's choice (literal `=SUM(...)` in the cell, no cache) matches the "no hidden state" principle but means other apps show the raw formula text.

No KMP spreadsheet-grid library fits (INFERRED; Compose Multiplatform has `LazyColumn`/`LazyRow` only, no maintained grid with editing); a custom grid is consistent with the project's custom-renderer preference. Confirm with a dependency search in the architecture phase.

## 3. Edge cases and failure modes the design must handle

### Parsing / serialization
1. **Ragged rows**: short rows pad on display; long rows currently drop extra cells (data loss on a write). Serializer must either preserve the extra raw text for untouched rows or refuse to rewrite them. Add a property test for "untouched row survives".
2. **Escaped pipes**: `a\|b` round-trips; cell ending in backslash before the closing pipe; pasted text containing `|`; pipe inside code spans.
3. **Multi-line cells**: GFM has no newlines in cells; `<br>` (also `<br/>`, `<br />`) is the convention. Editing a cell with Shift+Enter inserts `<br>`; paste of a TSV cell containing a newline converts to `<br>`; display converts `<br>` to a line break; editing shows raw `<br>` or newline (decide). Raw newline must never reach the serialized file (breaks the table). *(Superseded by ADR-009: Shift+Enter commits and moves up; Alt+Enter inserts `<br>`.)*
4. **Empty table / header-only table / one column**: `parseTableContent` returns zero columns for <2 lines; header-only (header + delimiter) has zero body rows: the sheet must still allow add row. Delete-last-column, delete-last-row, delete-header-row rules (GFM requires header).
5. **Duplicate or empty headers**: identity by index, never by header text; formula references by letter/number, so duplicates are harmless, but sort-by-header-name UI must use column index. Empty header cells are valid GFM.
6. **Rich cell content**: `[[links]]`, `#tags`, `**bold**`, inline code, images, `((block refs))`, HTML. Edit mode shows raw text; display mode renders. A `]]` or `|` typed in a cell must not break parse. Autocomplete for `[[` inside a cell is a user expectation but a scope question.
7. **Whitespace and style**: leading/trailing pipes optional, cell padding (`|  a  |`), alignment-only delimiter variants (`:-:`, `---:`), CRLF line endings, tabs, trailing blank lines, blocks with content before/after the table in the same block (child lines, properties, `id::` lines). Untouched-table byte identity forces preserving all of these; touched tables may normalize but should keep the original style (padding on/off) to keep git diffs small.
8. **Unicode**: CJK/emoji widths do not matter for GFM (no padding alignment) but matter for any "pretty-print" option; avoid column-padding output.
9. **Huge paste**: 10k-row TSV: cap/confirm, parse off the main thread, chunked write, one undo entry, never recompute formulas per pasted cell. Web clipboard needs a user gesture and permission (`navigator.clipboard.readText`); fall back to a paste event handler.
10. **Concurrent change**: file reloaded from disk (`externalFileChanges` / `DiskConflict`) while a cell is being edited or the sheet is open.

### Formulas (closed set: arithmetic + SUM/AVERAGE/MIN/MAX/COUNT/IF) *(Superseded by ADR-002: adds COUNTA, ROUND, ABS.)*
11. Errors displayed per cell, never crash: `#REF!` (out-of-range or deleted reference), `#CYCLE!` (circular; every cell in the cycle, not just one), `#DIV/0!`, `#VALUE!` (text in arithmetic), `#NAME?` (unknown function), `#PARSE!` (syntax). Error propagates to dependents. Empty range: `AVERAGE` -> `#DIV/0!`, `SUM` -> 0, `MIN/MAX` -> 0 (Sheets) — pick one and test.
12. Formula text starting with `=` in an ordinary cell (e.g. a note "=== heading ===" or `=` alone): must be classified as formula only if it parses; otherwise show as text or `#PARSE!`? Decide, and make "leading apostrophe/escape" or a settings toggle available.
13. **Locale**: `1,234.56` vs `1.234,56`; `isNumericCell` already accepts `,` thousands and `.` decimal only. Formula argument separator `,` vs `;`. Recommendation: parse numbers with a fixed invariant locale in storage, because the file is portable between machines; display may localize but never write localized text.
14. **Reference rewriting** on insert/delete/move/sort: shift on row/column insert and delete; deleted target becomes `#REF!` literal in the stored text; range endpoints shrink; absolute `$B$2` markers if supported. Undo must restore the original formula text exactly (snapshot undo handles this).
15. **Sort stability with formulas**: sort must be stable (ties keep prior order), sort by computed value but move the formula text; relative refs that point within the same row stay semantically right after the row moves, refs to other rows do not (Excel behavior). Decide: rewrite or leave literal (open question). Header row excluded; blank cells sort last; mixed number/text compare rule (numbers before text); sort by a formula column uses computed values; cycle/errored cells sort last deterministically.
16. Incremental recompute: dependency graph rebuilt only for changed cells; a single edit must not be O(cells^2); 200x12 = 2,400 cells is the stated target, so even a naive full pass per commit is acceptable if done once per commit, not per keystroke.
17. Totals-row pattern: `=SUM(B2:B5)` in a body row inside the range's own column is a `#CYCLE!` if the range includes the formula cell; note common mistake and the message.
18. Security: no `eval`, no network/file functions; bounded recursion depth and range size (a `=SUM(A1:ZZZ99999)` must not allocate); bounded formula length.

### Interaction / platform
19. Tap vs selection vs link-tap race (history: #240): in a non-editing cell, tap on a link opens it, tap elsewhere starts edit; long-press / drag selects. Needs one gesture arbiter shared by inline and sheet.
20. IME composition in a cell (Android, Wasm): commit on blur must not truncate composing text; Tab/Enter during composition belong to the IME.
21. Focus when the grid scrolls: the focused cell can be recycled by lazy composition; edit state must live above the lazy item, not inside it.
22. Selection copy: range -> TSV (with quoting for tab/newline/quote cells) plus the plain-text rendering; single cell copy copies raw text, not rendered text; copy of a formula cell: raw formula vs value (Sheets copies value in the text clipboard, formula internally). Pick and document.
23. Paste: TSV from Sheets/Excel (quoted multi-line cells, `\r\n`), CSV, plain text into a single cell, paste larger than the table (auto-expand vs clip), paste into a selected range of different shape.
24. Column resize and freeze are view state; persisting them is the open question. Safe default: session-only, with optional persistence in a block property if a Logseq fixture confirms it is preserved and invisible.
25. Touch: resize handles and cell edges sized in dp via `pxPerDp`; a 44 dp minimum target; the phone width (about 360 dp) makes a 12-column table require horizontal scroll and a frozen first column, not just a frozen header.
26. Accessibility: grid semantics (row/column index announcements, "cell B3, value 12, formula =A1+B2"), keyboard-only reachability of every command, `UndoAnnouncer`-style undo messages.
27. Feature flag: raw-markdown edit must stay reachable in every state; flipping the flag off mid-edit must not lose the draft.

## 4. Users' unstated needs
- Trust: "my file is not reformatted when I only look". Show a diff-minimal write: only rewritten if a cell changed.
- Escape hatch: one-action "edit as markdown" from both views; errors never hide the stored text.
- Formula visibility: a cell shows the value but the formula must be inspectable (formula bar or edit mode shows `=SUM(...)`), and copy gives a sane result when pasted into another app.
- Totals and quick sums: selecting a numeric range should show SUM/AVERAGE/COUNT in a status line (Sheets does), cheaper to build than a formula and a likely first use.
- Paste from the web and from Sheets "just works", including headers and multi-line cells.
- Round trip through Logseq: the same file renders and edits there; formulas appear as plain text there; no hidden metadata that Logseq would corrupt.
- Undo that is predictable: Ctrl+Z after a sort restores order; one undo per user action; undo in the sheet does not undo unrelated blocks.
- Sort that does not destroy hand-ordered tables unexpectedly: sort is an undoable data change with a clear label; offer "sort view only" later if view state is persisted.
- Stable keyboard muscle memory: Enter down, Tab right, Esc cancels, F2 edits, Ctrl+C/V/X, Ctrl+Z/Y, Shift+arrows, Ctrl+Home/End.
- Numbers behave like numbers: right-align (already via `numericColumns`), do not auto-convert `007` or dates, leave leading zeros untouched.
- Large-table comfort: sticky header and first column, row numbers/column letters visible so formulas are writable.

## 5. Decisions to carry into the design (recommendations, INFERRED)
- Lossless model: keep raw per-row text plus parsed cells; only re-serialize rows whose cells changed (guarantees byte identity and small diffs).
- Undo: snapshot-based per user action routed through `BlockUndoManager.record`, label for announcements; coalesce typing per cell edit.
- Selection: custom cell/range selection with its own TSV copy path; platform text selection only inside the active editing cell.
- Formulas: stored text only; pure `commonMain` evaluator with a dependency graph, bounded sizes, explicit error type, invariant-locale parse.
- View state (width, freeze, sort): session-only by default; decide persistence after a Logseq fixture test.
