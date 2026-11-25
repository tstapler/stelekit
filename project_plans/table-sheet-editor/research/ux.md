# UX Research: table-sheet-editor

**Date**: 2026-11-24
**Scope**: interaction patterns, mental models, accessibility, error/edge states, jobs-to-be-done, per-form-factor layouts, shortcut table.
**Evidence labels**: VERIFIED = a source was opened/returned by search this session (URL given). KNOWN = product behaviour from background knowledge, not re-checked this session (treat as INFERRED; confirm by trying the product before copying). Search results were snippets only; I did not open full pages.

## 0. Takeaways that should drive the design

1. **Two modes, one selection model.** Inline = "document with editable cells" (tap edits). Maximized = "spreadsheet" (tap selects, second tap/Enter/F2 edits). The split exists in every mature product: Notion tables and Airtable tap-to-open vs. Sheets/Excel select-then-edit. Requirements chose inline tap-to-edit; keep that, but the maximized sheet should follow the spreadsheet select-first model because range ops need a "selected, not editing" state.
2. **Never rely on drag alone.** WCAG 2.2 SC 2.5.7 (Dragging Movements, AA) requires a single-pointer non-drag alternative; SC 2.5.8 (Target Size Minimum, AA) requires 24x24 CSS px targets or spacing ([What's New in WCAG 2.2](https://w3.org/WAI/standards-guidelines/wcag/new-in-22/), [Understanding 2.5.8](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum)). Range-select handles, fill handle, column resize and row/column reorder each need a menu/keyboard path.
3. **The hard constraint is the file, not the grid.** Cell edits are text edits to a GFM table. Users must always see what will be written (formulas stay literal, `\|` escaping), and failure modes (paste overflow, external edit) must resolve toward "no data lost, raw markdown still reachable."

## 1. Comparable patterns

### 1.1 Interaction flows by product

| Flow | Google Sheets | Excel | Notion (table/database) | Airtable | Apple Numbers (iPad/iPhone) |
|---|---|---|---|---|---|
| Tap/click on cell | Select (single click). Second click, Enter or F2 edits. KNOWN | Same; F2 edits, typing replaces content. KNOWN | Click edits text cell in place (simple tables) or selects (databases). KNOWN | Click selects; Enter/Space/second click edits; typing replaces. KNOWN | Tap selects; second tap/double tap edits; on phone, a formula/text bar appears over the keyboard. KNOWN |
| Range select | Drag, Shift+click, Shift+arrows, click header for row/column. KNOWN | Same; Ctrl+Space / Shift+Space for column/row. KNOWN | Drag across cells to multi-select (databases); weaker. KNOWN | Drag or Shift+click; fill handle on selection. KNOWN | Drag the circular selection handles at the selection corners, or tap header for row/column. KNOWN |
| Fill / drag | Blue square handle bottom-right; double-click fills down. KNOWN | Same fill handle; Ctrl+D / Ctrl+R. KNOWN | Limited. KNOWN | Drag handle; paste into selection. KNOWN | Explicit "Autofill Cells" action, then drag the yellow border; overwrites existing data; Cmd+Backslash with keyboard. VERIFIED ([Apple Numbers iPad: autofill](https://support.apple.com/guide/numbers-ipad/tan0e1129cf6/ipados), [older step wording](https://help.apple.com/numbers/ipad/2.5/en.lproj/tan90d4d2a40.html)) |
| Maximize / expand | "Full screen" via View menu; mobile app is already full screen. KNOWN | Full window by design. | "Open as page" / full-width table toggle. KNOWN | Expand record (modal), grid is the page. KNOWN | Table is a canvas object; zoom/pinch. |
| Column resize | Drag header border; double-click border auto-fits. KNOWN | Same. | Drag header border; width is a view property. KNOWN | Drag header border. KNOWN | Select column, drag the handle at its header edge. KNOWN |
| Header menu | Chevron/right-click: sort A-Z, freeze, insert, delete, resize, hide. KNOWN | Right-click and ribbon. | Header click menu: sort, filter, insert left/right, duplicate, delete, wrap. KNOWN | Header dropdown: edit field, sort, filter, hide, insert. KNOWN | Header chevron: sort, insert/delete, resize, header options. KNOWN |

### 1.2 What to copy, what to skip

- **Select-first, then edit (Sheets/Excel/Numbers).** Required for range operations. Typing on a selected cell replaces content; Enter/F2 enters edit with caret at end; Esc cancels; Enter commits and moves down; Tab commits and moves right. In inline mode, tap-to-edit goes straight to edit, but arrow/Tab navigation between cells should still work from the keyboard.
- **Touch selection handles (Numbers).** Two corner handles on a selected range, a floating action strip (copy, paste, fill, clear) near the selection, row/column header taps select whole rows/columns. Requires a non-drag path (see 3.4).
- **Explicit fill mode on touch (Numbers' "Autofill Cells" pattern).** Better for touch than a 12dp handle; mirrors WCAG 2.5.7. Fill is not in the required scope list; if later added, use the Numbers model on touch and the handle plus Ctrl+D/R on desktop.
- **Header menu as the single home for column operations** (Notion/Airtable/Sheets): sort, alignment, insert left/right, delete, move left/right, auto-fit. It doubles as the accessible alternative to drag-resize and drag-reorder.
- **Freeze row** is a view toggle in Sheets/Excel; in SteleKit the header row is already visually distinct, so "frozen header" means "sticky while the sheet scrolls vertically", on by default in maximized.
- **Do not copy:** Notion's per-view property persistence, Airtable's typed fields, Excel's ribbon density. Markdown cells are untyped text.

## 2. Mental models

| Model | Who holds it | Implication |
|---|---|---|
| "A table in my notes" (document) | Logseq/Notion users | Inline: the block is prose-adjacent; editing must not feel like opening an app. Tap edits one cell; leaving the block returns to rendered view. Never navigate away from the page for a one-cell fix. |
| "A spreadsheet" | Sheets/Excel users | Maximized: grid, selection rectangle, formula bar, Tab/Enter flow, clipboard to/from Sheets. Expect `=SUM(B2:B5)`. |
| "A markdown file" | Owner, Logseq interop | Raw markdown is the source of truth. Provide a visible "Edit as markdown" escape (requirement: raw edit always reachable), and show formulas literally when a cell is in edit mode. |

**Where the models collide** (and the design answer):
- *Formula text vs. value.* Display = computed value; edit = literal `=...`. Spreadsheet users expect this; markdown users see the literal in the file. Show a small `fx` marker or monospace tint on formula cells so "5" is distinguishable from "=2+3".
- *Sort rewrites the file.* Spreadsheet users treat sort as a view op; here it mutates row order. Label it "Sort rows (changes file)" or equivalent and make it one undo step.
- *Column width / freeze are not persisted* (open question). Mental-model-safe default: session-only view state, never written to the file. Users from Sheets will expect widths to stick; surface this once ("Column widths reset when you close the sheet") only if persistence is decided against. Decision belongs to the plan; UX recommendation is session-only plus auto-fit on open.
- *Rich cell content.* Cells can hold `[[links]]`, inline code, `<br>`. Edit mode shows raw text; display mode renders. Spreadsheet users won't be surprised by this because Sheets does the same for formulas and hyperlinks.

## 3. Accessibility

### 3.1 WCAG 2.2 criteria that bite here

| SC | Level | Applies to | Design response |
|---|---|---|---|
| 1.4.10 Reflow / 1.4.4 Resize Text | AA | 360dp width, 200% font | Table scrolls horizontally inside its container; text wrap (already shipped) holds up. Test the largest font setting (CLAUDE.md UI checklist). |
| 1.4.11 Non-text contrast | AA | selection outline, focus ring, handles | 3:1 against adjacent colors; selection must not be color-fill-only. |
| 2.1.1 Keyboard / 2.1.2 No Keyboard Trap | A | grid | Every op reachable by keyboard; Esc (or Tab at grid edge when not editing) leaves the grid. |
| 2.4.7 / 2.4.11 Focus Visible / Not Obscured | AA | active cell | Scroll active cell into view; floating action strip and IME must not cover it (2.4.11 is new in 2.2). |
| 2.5.7 Dragging Movements | AA | resize, reorder, fill, range handles | Menu/keyboard alternative for every drag. VERIFIED ([What's New in 2.2](https://w3.org/WAI/standards-guidelines/wcag/new-in-22/)). |
| 2.5.8 Target Size (Minimum) | AA | handles, header menu buttons | 24x24 CSS px minimum; use 48dp for primary touch targets (Android guidance) sized via `pxPerDp`, per project rule. VERIFIED ([Understanding 2.5.8](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum)). |
| 3.3.1/3.3.3 Error Identification/Suggestion | A/AA | formula errors | Error text, not just red color; say what is wrong ("Circular reference B2 -> C2 -> B2"). |
| 4.1.2 Name, Role, Value | A | all controls | Compose semantics below. |
| 4.1.3 Status Messages | AA | paste results, sort done, error | Live-region/announce on completion ("Pasted 4 x 3 cells", "Truncated 2 columns"). |

### 3.2 ARIA grid pattern (web/Wasm reference model)

The W3C APG grid pattern covers data grids with cell-to-cell navigation, optional selection, and separate rules for editing inside a cell. It lists a "Sortable Data Grid With Editable Cells" and an "Advanced Data Grid" with spreadsheet-like cell/row selection as examples. It is a Working Group Note, not a standard ([APG grid pattern](https://www.w3.org/WAI/ARIA/apg/patterns/grid), [grid examples](https://www.w3.org/WAI/ARIA/apg/example-index/grid/dataGrids.html)). Only the pattern's outline was returned by search, so the key table below is KNOWN from APG, to be spot-checked against the page before implementation.

Model to adopt:
- **Roving focus**: exactly one cell is the tab stop (`tabindex=0`), others `-1` (or `aria-activedescendant` on the grid). Tab enters the grid once and leaves it once; arrows move inside.
- **Two states per cell**: *navigation mode* (arrows move, typing may begin edit) and *edit mode* (arrows move the caret; Esc returns to navigation mode, Enter/Tab commits). APG calls the second one "editing and navigating inside a cell".
- **Roles**: `grid` > `row` > `columnheader`/`gridcell`; `aria-rowcount`/`aria-colcount` and `aria-rowindex`/`aria-colindex` when rows are virtualized; `aria-selected` on selected cells; `aria-readonly` on cells/grid when the graph is read-only; `aria-sort` on the sorted column header; `aria-invalid` on formula-error cells.
- **Caveat**: Adrian Roselli argues ARIA grid is often over-applied to static tables ([ARIA Grid As an Anti-Pattern](https://adrianroselli.com/2020/07/aria-grid-as-an-anti-pattern.html)). Applies here in reverse: the inline *read* view is a static `table` semantics; only the editable/maximized sheet is a `grid`.

### 3.3 Compose semantics equivalents (Android TalkBack, Desktop, Wasm)

| Need | Compose semantics | Note |
|---|---|---|
| Grid size announcement ("3 of 12 columns") | `Modifier.semantics { collectionInfo = CollectionInfo(rowCount, columnCount) }` on the container | VERIFIED existence ([Compose semantics](https://developer.android.com/jetpack/compose/semantics)). Use the full counts, not the lazy-composed window. |
| Cell position ("row 4, column B") | `collectionItemInfo = CollectionItemInfo(rowIndex, rowSpan, columnIndex, columnSpan)` per cell; `isHeading()` / header flag for header cells | Community sources disagree on span values; spans should be 1 for non-merged cells. Check the reference docs. |
| Cell content + formula state | `contentDescription` or merged text; for formula cells: `stateDescription = "Formula =SUM(B2:B5), value 42"` | Read value first, formula as state. |
| Selected | `selected = true` | Plus a non-color outline. |
| Edit vs. read | `Role.Button`/`onClick(label = "Edit cell")`; editing uses `EditableText`/text-field semantics | Provide `onClick` label so TalkBack says "double tap to edit cell". |
| Read-only graph | `disabled()` on edit actions; `stateDescription = "Read only"` | Do not just swallow taps silently. |
| Error | `error("Circular reference")` | Compose has an `error` semantics property. |
| Sort state | `stateDescription = "Sorted ascending"` on header | Compose has no `aria-sort` equivalent. |
| Non-drag alternatives | `customActions = listOf(CustomAccessibilityAction("Move column right"){...}, "Resize column", "Insert row below", ...)` on header/cell | This is the TalkBack way to satisfy 2.5.7; it appears under the TalkBack actions menu. |
| Announce results | `liveRegion = LiveRegionMode.Polite` on a status node | For paste/sort summaries. |
| Focus traversal | `focusable()` + `Modifier.onKeyEvent` + `FocusRequester` per active cell; use `focusProperties { canFocus }` so only the active cell is a tab stop | Compose Desktop/Wasm have hardware keyboards; Android with a keyboard needs the same. |

Wasm note: Compose Multiplatform for web exposes semantics to the DOM accessibility tree to a limited degree; confirm with the browser's accessibility tree on the web build (INFERRED, not checked this session). If the canvas-rendered grid is not reachable by screen readers, the "Edit as markdown" escape is the accessible fallback and must remain a plain text field.

### 3.4 TalkBack and touch specifics

- Explore-by-touch: each cell is a swipe-through node; with 12 columns x 200 rows, linear swipe is painful. Provide header-level jump actions ("Go to column", row/col jump) via custom actions, and expose rows as grouped nodes (row = container with `collectionItemInfo`) so TalkBack's navigation by container works.
- Range select by TalkBack: no drag. Provide actions on a selected cell: "Extend selection right/down/to row end/to column end", "Select row", "Select column", "Select all" (also surfaced in the cell's overflow menu). Copy/paste via the same strip.
- Large font/display scaling: row height grows with wrapped text; no fixed row heights (regression anchor in requirements).
- Switch Access / external keyboard: same key map as 6.

## 4. Layouts

### 4.1 Phone (360dp wide, ~780dp tall)

```
INLINE (in page, scrolls with page)          MAXIMIZED (full-screen sheet)
+------------------------------+            +------------------------------+
| ...paragraph text...         |            | [x]  Table title        [...]|  top bar: close, overflow
| +--------+--------+--------+ |            | fx [ =SUM(B2:B5)        ] [v]|  formula/edit bar (above kb)
| | Name   | Qty    | Price  | | <- h-scroll| +----+-------+------+------+ |
| +--------+--------+--------+ |            | |    |  A    |  B   |  C   | |  frozen header + row numbers
| | Apple  | 3      | 1.20   | |            | +----+-------+------+------+ |
| | Pear   | 5      | =B3*2  |→|            | | 1  | Name  | Qty  | Price| |  frozen
| +--------+--------+--------+ |            | | 2  | Apple |[ 3 ]| 1.20 | |  selected cell outline
| [expand icon]                |            | | 3  | Pear  | 5    | 10   | |
| ...                          |            | +----+-------+------+------+ |
+------------------------------+            | action strip: Copy Paste Row+ Col+ Sort ... |
                                            +------------------------------+
```

- Inline: horizontal scroll inside the block when columns exceed width (min col width ~96dp, max ~240dp, wrap). A persistent expand button (48dp) at the block's bottom-right corner or in the block's overflow; one action to maximize (requirement). Tap on cell text edits that cell with the IME open; a link in a non-editing cell opens on tap (shipped, must not regress); long-press starts text selection/range select (resolve tap vs. selection race, see #240).
- Maximized: full-screen dialog (Android/iOS) or route; top bar with close; **edit bar above keyboard** (like Sheets/Numbers) because in-cell editing at 360dp is cramped and the IME covers 40-50% of the screen. Active cell must scroll above the IME (SC 2.4.11). Row-number gutter and header are sticky. Action strip is context-sensitive (cell vs. range vs. row/column selected), min 48dp targets, scrolls horizontally.
- Header menu: tap the column letter -> bottom sheet (sort asc/desc, align left/center/right, insert left/right, move left/right, delete, auto-fit). Row gutter tap -> row menu (insert above/below, move up/down, delete, duplicate).
- Range select on touch: long-press a cell enters range mode with two 48dp-hit-area handles (visual 12dp), strip shows Copy/Cut/Clear/Fill; also "Select row/column" via headers. Non-drag alternative via strip: "Extend selection..." picker or TalkBack custom actions.
- Column resize on touch: drag the 48dp-wide header edge; alternative: header menu "Column width" with presets (Narrow/Medium/Wide/Auto-fit).

### 4.2 Tablet / foldable (Pixel_Fold: ~600-840dp wide unfolded, posture changes)

- Unfolded or >=600dp: maximized sheet shows more columns without scroll; formula bar becomes an inline top bar (not above IME, since the IME no longer dominates), row gutter and header the same. Keep touch handles and also accept pointer/keyboard.
- Inline mode keeps the phone approach but fewer wraps; expand opens a dialog sized ~90% of the window (not full-bleed) on >=840dp, so the page remains visible context.
- Foldable posture: on fold/unfold, preserve selection, active cell, scroll offset and any in-progress edit text across the configuration change (state held in the model, not in composable `remember` only). Tabletop posture (hinge horizontal): keyboard on the lower half, sheet on top half; avoid placing the active cell behind the hinge region (use `WindowInfoTracker` fold features, INFERRED API availability on the target module).
- Window resize (desktop-mode Android/ChromeOS): same responsive breakpoints; no layout based on device type, only `WindowSizeClass`.

### 4.3 Desktop (JVM) and Web

- Inline: hover shows a faint "expand" button and column-edge resize cursor; click edits; Tab/Shift+Tab/Enter/arrows move between cells while editing (spreadsheet flow); Esc cancels.
- Maximized: a large dialog (or in-place expanded block, open question) with menu-less layout: formula bar on top, column letters and row numbers, right-click context menu (insert/delete/sort/align), status bar showing selection stats (count, sum, average for numeric ranges: optional, low cost, high spreadsheet-familiarity win).
- Clipboard: Ctrl/Cmd+C/V/X in TSV (plus `text/html` table if cheaply available); Web requires user gesture and the async Clipboard API permission, so the `paste` event path must be preferred over `navigator.clipboard.readText()` (INFERRED; verify on the wasm build). Fall back to "Paste" button that opens a paste target textarea if the permission is denied.
- Mouse range select: click + drag, Shift+click, Shift+arrows; column resize by dragging the header edge with double-click auto-fit.

## 5. Keyboard and shortcut table

Cmd = Ctrl on non-Mac. "Nav" = grid focused, not editing. "Edit" = cell in edit mode. Keys marked K are KNOWN from Sheets/Excel/APG and should be spot-checked.

| Key | Nav mode | Edit mode |
|---|---|---|
| Arrows | Move active cell | Move caret in cell text |
| Tab / Shift+Tab | Move right/left (wraps to next/prev row; leaves grid at the last/first cell only in maximized if no wrap preferred) | Commit, move right/left |
| Enter | Start edit (K) | Commit, move down |
| Shift+Enter | Move up (K) | Commit, move up; Alt+Enter inserts line break (`<br>`) |
| F2 | Start edit, caret at end | Toggle caret mode (optional) |
| Esc | Clear range to single cell; if single cell, leave grid focus to previous control | Cancel edit, restore original text |
| Printable char | Start edit with that char replacing content (K) | Types |
| Delete / Backspace | Clear selected cells | Normal text |
| Home / End | First / last cell in row (K, APG) | Caret line start/end |
| Ctrl+Home / Ctrl+End | First cell / last used cell (K, APG) | n/a |
| PgUp / PgDn | Move one page of rows (K, APG) | n/a |
| Ctrl+Arrow | Jump to edge of data region (K) | Word-wise caret |
| Shift+Arrows | Extend range | Extend text selection |
| Ctrl+Space / Shift+Space | Select column / row (K) | n/a |
| Ctrl+A | Select all cells | Select cell text |
| Ctrl+C / X / V | Copy / cut / paste range as TSV (+ plain text) | Normal text clipboard |
| Ctrl+D / Ctrl+R | Fill down / right from first row/column of range (K) | n/a |
| Ctrl+Z / Ctrl+Shift+Z (Ctrl+Y) | Undo / redo table edit | Undo within cell, then table |
| Ctrl+Shift+= / Ctrl+- | Insert / delete row or column (matches selection kind) (K) | n/a |
| Alt+Shift+Arrows | Move selected row/column (non-drag reorder) | n/a |
| Ctrl+Alt+M (proposal) | Toggle maximize | Same *(Rejected in ADR-009: AltGr-sensitive and browser-adjacent; Maximize is Ctrl+Shift+Enter.)* |
| Shift+F10 / Menu key | Context menu (row/column/cell) | n/a |
| Ctrl+Shift+E (proposal) | Edit as raw markdown | Same *(Rejected in ADR-009: collides with the export chord; Edit as markdown is Shift+F2.)* |

Avoid conflicts: confirm none of the proposals collide with existing SteleKit shortcuts (command palette, sidebar, search) before assigning; this table is a starting set, not a decision.

## 6. Error and edge states

| State | Trigger | Behaviour (recommended) | A11y |
|---|---|---|---|
| Formula syntax error | `=SUM(` | Cell shows `#ERROR` (or `#PARSE!`) with tooltip/long-press detail; source text unchanged; edit mode retains the text. | `error()` semantics, announce on commit. |
| Bad reference | `=A99` outside table, or reference deleted | `#REF!`. On structural edits, rewrite references (Sheets) or mark `#REF!` when target removed. Decision pending (open question 3); UX preference: shift references automatically. | Same. |
| Cycle | `A1 = B1+1`, `B1 = A1` | `#CYCLE!` on all cells in the cycle; message names the cycle path. | Same. |
| Divide by zero / type | `=1/0`, `=A2+"x"` | `#DIV/0!`, `#VALUE!` | Same. |
| Unsupported function | `=VLOOKUP(...)` | `#NAME?` with tooltip listing supported functions. Because formulas are literal text and Logseq will just show them, no data lost. | Same. |
| Paste larger than table | TSV 8x5 into a 3x3 selection at row 3 | Default: grow the table (add rows/columns) to fit; show a one-line confirmation with Undo ("Pasted 8 x 5, added 5 rows, 2 columns"). If it would exceed a size cap (e.g. 10k cells), reject with message and no change. A selection-size mismatch pastes the clipboard whole from the top-left of the selection (Sheets behaviour K), except a single copied cell fills the whole selection. | Announce summary. |
| Paste wider than 1 row but cells contain tabs/newlines/pipes | Cell contains `|` or newline | Escape `|` as `\|`; newline -> `<br>`. Show a notice when content was transformed. | Announce. |
| Clipboard blocked (Web) | Permission denied | Inline hint plus "Paste here" fallback box. | Focus moves to the fallback field. |
| External edit while open | File changes on disk (`GraphLoader.externalFileChanges` -> `DiskConflict`) | If no pending edit: silently refresh grid, keep selection by (row, col) if still in range, toast "Updated from disk". If a cell is mid-edit or uncommitted edits exist: show the existing DiskConflict flow with choices "Keep mine / Take theirs / Open as markdown". Never silently discard the in-progress cell text. | Announce via live region. |
| Structural conflict | Row/column count differs after external change | Fall back to block-level conflict dialog; do not attempt a cell-level merge in v1. | Same. |
| Read-only graph / off-graph target | Writer not available | Grid opens in browse mode: selection, copy, formula view allowed; edit, paste, sort, structure ops disabled and marked with a reason ("This graph is read-only"). Per CLAUDE.md pattern: disabled with a reason, not hidden. | `disabled()`, `stateDescription = "Read only"`. |
| Table is not parseable as GFM (no delimiter row, ragged) | Hand-edited markdown | Do not enter grid edit; show rendered fallback and "Edit as markdown". Ragged rows: pad virtually, preserve original text for untouched rows (round-trip requirement). | Explain why. |
| Very large table | >5k cells | Maximized sheet virtualizes rows; inline shows first N rows with "Show all (open sheet)" (a11y: counts exposed via `collectionInfo`). | Same. |
| Undo after sort / structural op | any | One undo step per user action (sort, insert, paste); formula rewrites included in the same step. | Announce "Undid sort". |
| IME composition | CJK / predictive text mid-edit | Commit only on composition end; arrow/Enter during composition must go to the IME. | N/A |
| Cell contains `[[link]]` | tap in non-edit mode | Opens link (shipped). In edit mode, shows raw `[[...]]`. |  |

Error cells never throw; they log through `Logger` (observability requirement) and show text.

## 7. Jobs to be done

| # | When... | I want to... | So I can... | Primary surface |
|---|---|---|---|---|
| J1 | I spot a typo or stale value in a table in my note | Fix one cell in place without leaving the page | Keep reading flow | Inline tap-to-edit |
| J2 | I want to reuse a value or table | Copy a cell or range (and get TSV for Sheets/Excel) | Move data in and out | Inline long-press, maximized Ctrl+C |
| J3 | I paste data from a spreadsheet, web page or CSV | Turn it into a markdown table without hand-editing pipes | Capture structured data quickly | Maximized paste, or inline paste into empty/selected cell |
| J4 | A table is bigger than the screen | See and edit it comfortably, keep headers visible | Work on real data on a phone | Maximized sheet |
| J5 | I'm tracking numbers (budget, reading list, workout log) | Total or average a column with `=SUM` | Avoid external spreadsheet for small sums | Maximized formulas |
| J6 | I need rows in a different order | Sort or reorder rows/columns | Clean up lists | Header menu, keyboard |
| J7 | A table needs to read well | Align columns, resize widths | Legibility | Header menu (alignment is written to file; width is not) |
| J8 | I'm on a graph that I can't write to, or it changed elsewhere | Understand why edits are blocked or what changed | Trust the data | Error/edge states |
| J9 | Something goes wrong or I want exact control | Fall back to raw markdown | Not be trapped by the editor | "Edit as markdown" always available |
| J10 | I use only keyboard or a screen reader | Do all of the above without pointing or dragging | Equal access | Roving focus, custom actions |

## 8. Recommendations for the plan phase

1. Selection model: custom range selection owned by the table state (rectangular `anchor`/`focus`), shared by inline and maximized; copy path produces TSV itself. Do not rely on platform text selection across many cells (rabbit hole in requirements).
2. Ship the non-drag alternatives (header menu width presets, move row/column actions, extend-selection actions) in the same milestone as the drag gestures, since 2.5.7 is AA.
3. Maximized presentation: full-screen dialog <600dp, large dialog >=600dp, in-place expanded block as a later enhancement; decide in the plan, test on `Pixel_Fold`.
4. Keep column width/freeze/sort order session-only unless the Logseq-compat research (separate file) shows a safe block property.
5. Verify touch behavior on the emulator (hit areas, handles, IME avoidance) and render at phone size via Robolectric+Roborazzi, as the project's UI checklist requires; this research cannot verify those.

## Sources

- [WAI-ARIA APG: Grid pattern](https://www.w3.org/WAI/ARIA/apg/patterns/grid)
- [APG grid examples (sortable editable, advanced spreadsheet-like)](https://www.w3.org/WAI/ARIA/apg/example-index/grid/dataGrids.html)
- [Adrian Roselli: ARIA Grid As an Anti-Pattern](https://adrianroselli.com/2020/07/aria-grid-as-an-anti-pattern.html)
- [MDN: ARIA grid role](https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Roles/grid_role)
- [W3C: What's New in WCAG 2.2](https://w3.org/WAI/standards-guidelines/wcag/new-in-22/)
- [W3C: Understanding SC 2.5.8 Target Size (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum)
- [Android: Compose semantics](https://developer.android.com/jetpack/compose/semantics)
- [Apple: Numbers for iPad, autofill cells](https://support.apple.com/guide/numbers-ipad/tan0e1129cf6/ipados)
- [Apple: Numbers for iPad (older fill steps)](https://help.apple.com/numbers/ipad/2.5/en.lproj/tan90d4d2a40.html)
