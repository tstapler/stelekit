# UX Design: table-sheet-editor

**Date**: 2026-11-24
**Inputs**: `requirements.md`, `research/ux.md`, `implementation/plan.md`, ADR-005 (selection and single gesture owner), ADR-006 (Dialog host, flags, read-only), ADR-007 (session-only view state), ADR-009 (shortcuts).
**Status**: Design for implementation (iteration 7, triad repair 3, resolves the UX gaps on held conflicts, undo scope, field-level undo, notices, draft survival, sheet Esc and the point-mode escape; iteration 6 resolved the blockers on undo, Back and the touch inline flow). Wireframes are ASCII and show layout and interaction model, not pixels. Phone render and device checks (plan Epic 8) validate them; nothing here is verified on a device yet.

## 0. Design rules (apply to every surface)

1. **Two modes, one selection model.** Inline is "a document with editable cells": a tap on cell text edits it. The sheet is "a spreadsheet": a tap selects, a second tap, Enter or F2 edits. Both use the same `GridSelection` and `GridMode` (`Navigate` or `Edit`).
2. **The file is the source of truth.** Formulas are literal cell text. Edit mode and the edit bar always show the literal text; display mode shows the computed value with an `fx` marker.
3. **Nothing is drag-only, keyboard-only or hover-only.** Every drag (range handles, column resize, row/column reorder) has a menu or strip action, and every shortcut has a menu, strip or TalkBack custom action (WCAG 2.5.7).
4. **Every state has an exit.** "Edit as markdown" (inline strip, sheet top bar, overflow menu) is reachable in every state, including error and read-only. Esc, Back and the close button always leave the current layer (section 4).
5. **No silent data loss.** A pending draft is committed on close, on flag flip and when the cell leaves composition. A draft is never discarded by an external change (conflict dialog instead).
6. **Disabled with a reason, not hidden** (the sheet's `readOnly` host parameter; no active-graph read-only flag exists today, ADR-006).
7. **Touch targets** are at least 48dp hit area, sized with `LocalDensity` (dp, never raw px). Visual size may be smaller (handles draw 12dp).
8. **Flags**: with `table_inline_edit_enabled` and `table_sheet_enabled` both off, the table renders and behaves as today (no strip, no Maximize button); the four flag combinations are defined by the matrix in plan.md Story 4.1 and the S1 table below.
9. **View state is session-only** (ADR-007): widths, freeze and the sort indicator are never written. Alignment (`:---:`) and sort order are data and are undoable.
10. **One touch rule for the inline strip (iteration 6 replaces the iteration-5 wording).** On touch the strip is a window-anchored bottom bar: it docks to the bottom of the visible area (on top of the IME when the IME is up), at the same position in `Navigate` and in `Edit`, and never follows the active cell. It appears and disappears with no slide or fade, so it never moves under a finger. Its actions keep one fixed relative order in both modes (S3). Whether it shows during `Edit` is decided once, when the edit starts, and stays for the whole edit (`TableStripPolicy`, section 3.2): touch, TalkBack or Switch Access entry shows it; a mouse or hardware-key entry does not; a hardware key pressed mid-edit never changes it. The placement is decided with it (touch entry: window-anchored; hardware entry: directly below the table) and does not move when the pointer type changes mid-edit. The one exception is a held conflict, which shows the strip whatever the profile (rule 15). In `Edit` its first two controls are **Cancel edit** and **Done**, Cancel on the left and Done on the right (the Android dialog convention, observed in study hypothesis H-C). The first slot has a fixed width (the wider of its two labels) and never changes label while the user types: it reads **Close** in `Navigate` and **Cancel edit** in `Edit`, and is always present. With an unchanged draft Cancel edit just leaves `Edit` (nothing to discard, no notice); with a changed draft it restores the original and posts the Undo notice. The two use different icons (Close: a hide chevron; Cancel edit: a revert arrow) and different words, so neither is read as the other. **Edit as markdown** is pinned in the strip's fixed trailing slot as a button labelled **Markdown** (accessibility label "Edit as markdown"), outside the horizontally scrolling part, so it is one tap away at 360dp. Every other strip action first commits the draft exactly as "tap another cell" does (one undo step, a no-op if unchanged) and then acts in `Navigate`. UX-05, UX-06, UX-57..UX-59, UX-72..UX-74, UX-89 and UX-91 test it.
11. **Time-limited messages are extendable** (WCAG 2.2.1): a message that carries an action (Undo, Retry, Keep mine...) stays until the user acts or dismisses it; an informational message auto-dismisses after 10 s and the timer pauses while it has pointer hover or keyboard/screen-reader focus. See S11, S14, S18.
12. **One Back ladder (iteration 6).** Android Back and Esc follow the single ladder of section 3.3: the first matching rung handles the key and no other handler runs. Back never closes the sheet while a banner, menu, IME, draft or range is open, and never discards a draft.
13. **One stacking order (iteration 6).** Bottom to top: IME, strip, notice, hint chip. One transient layer shows above the strip at a time, and a notice beats a hint chip (the hint waits). The active cell is scrolled above the whole stack. When the stack plus the active row cannot fit (landscape phone), hint chips are suppressed and the notice shrinks to one line with its action. The Maximize button is not drawn while the strip is visible, because the strip's Maximize action replaces it. UX-78 tests it. Notices follow the queue rule of section 3.10 (UX-87).
14. **Undo and Redo are controls, not only keys (iteration 6).** The sheet top bar, the sheet context strip and the inline strip each carry Undo and Redo (UX-70), because a phone has no Ctrl+Z. Their scope and text are fixed by rule 16.
15. **A held conflict is visible in every profile (iteration 7).** When `TableSyncPolicy` returns `ConflictKeepDraft`, inline commits are held and the strip is shown in `Navigate` and in `Edit` for every entry profile (the one exception to the sticky rule of 10). A `Hardware` edit shows the reduced strip **Resolve**, **Cancel edit**, **Markdown** directly below the table, takes no focus and announces once "Your edit to B3 is not saved. Resolve it first". While the conflict is held every key or control that would commit (Enter, Tab, Shift+Enter, the soft keyboard action key, Done, Next column, a tap on another cell, a tap outside, and every strip action except Resolve, Cancel edit and Markdown) commits nothing, moves nothing, keeps the draft and re-shows the banner (Back ladder rung 1) with the same announcement. Cancel edit still discards (with its Undo notice) and Markdown follows the S14 `RecoveredDraft` path. UX-84 tests it.
16. **Undo is table-scoped and field-first (iteration 7).** (a) In `Edit`, Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y and the strip and edit-bar Undo and Redo act on the cell's own draft history (`CellDraft`), never on the document and never commit; Undo is enabled only while that history has a step, otherwise it is dimmed and shows "Nothing more to undo in this cell". Table-level Undo needs `Navigate` (press Done first). (b) In `Navigate`, inline and sheet Undo and Redo both act only on this table's own content-changing commits (`TableUndoScope`, plan Task 3.2c). The inline scope starts at depth 0 when the strip appears and resets to 0 when it hides or when a foreign entry reaches the page stack; the sheet scope starts at 0 on every open, so a reopen never undoes an earlier open's commits. Neither can undo another block; commits made before the scope began are undone with the page's own Ctrl+Z. (c) The button text names the step ("Undo: edit B3", "Undo: paste", "Redo: sort"), identical in the sheet top bar, the context strip, the range strip and the inline strip, and is also the accessibility label. UX-85 and UX-86 test it.

## 1. Surface inventory

| # | Surface | Interactive? | Treatment |
|---|---|---|---|
| S1 | Inline table at rest (display, Maximize button, formula and error cells) | Yes | Full |
| S2 | Inline cell edit | Yes | Full |
| S3 | Inline selection and action strip | Yes | Full |
| S4 | Maximized sheet, phone (<600dp) | Yes | Full |
| S5 | Maximized sheet, tablet / foldable (600-839dp and >=840dp) | Yes | Full |
| S6 | Maximized sheet, desktop and web | Yes | Full |
| S7 | Edit bar (formula bar) | Yes | Full |
| S8 | Column header menu | Yes | Full |
| S9 | Row gutter menu | Yes | Full |
| S10 | Touch range mode (handles) and status line | Yes | Full |
| S11 | Paste feedback, notices and web paste fallback | Yes | Full |
| S12 | Formula and cell error states | Yes (detail on hover, keyboard focus, select-cell, or the Why? action) | Full |
| S13 | Read-only / browse mode | Yes | Full |
| S14 | External change and conflict | Yes | Full |
| S15 | Sheet overflow menu and shortcuts panel | Yes | Full |
| S16 | Unparseable or oversized table fallback | Yes | Full |
| S17 | Settings toggles (Tables section) | Yes | Condensed |
| S18 | Screen-reader announcements (live region) | No | Condensed |

Total: 18 surfaces (16 full treatment, 2 condensed).

## 2. Vocabulary used in wireframes

`[ ]` = active cell outline (never fill-only; 3:1 contrast, WCAG 1.4.11). `fx` = formula marker. `o` = range handle. `(…)` = icon button, 48dp. `=== ` = frozen/sticky area.

---

## S1. Inline table at rest

```
 ...paragraph...
 +--------+--------+-----------+ 
 | Name   | Qty    | Price     |  header row, bold (existing style)
 +--------+--------+-----------+
 | Apple  | 3      | 1.20      |
 | Pear   | 5      | fx 10     |  formula cell: value + fx marker
 | Fig    | 2      | #DIV/0!   |  error cell: text + error semantics
 +--------+--------+-----------+  --> horizontal scroll if columns exceed width
                         (Maximize)  48dp, bottom-right; whenever the sheet flag is on (also with inline edit off); not drawn while the touch strip is visible (the strip's Maximize replaces it, design rule 13)
```

Behaviour:
- Wrapping, link taps and tallest-cell row height are unchanged (regression anchor, `TableBlockLayoutTest`).
- Columns min 96dp, max 240dp, existing `TableColumnLayout`; horizontal scroll inside the block.
- The Maximize button is one action to maximize. Also bound to Ctrl/Cmd+Shift+Enter when the table has grid focus (inline edit on); the chord is never bound with inline edit off or the sheet flag off.
- Non-editing semantics are static `table` (not `grid`); the sheet is the `grid`.

Flow:

| User does | System responds |
|---|---|
| Taps a link in a cell | Opens the link (existing; precedence 1 in ADR-005) |
| Taps other cell text (inline flag on) | Enters S2 on that cell |
| Taps cell text (inline flag off) | Today's behaviour: block opens as raw markdown |
| Long-press a cell (touch) | Existing block selection (unchanged, ADR-005 precedence 3); the strip (S3) is reached from an active cell instead |
| The page is in block selection mode and the user taps a cell | The table is inert (ADR-005 precedence 0): the tap toggles the block's selection as today, no cell edits, no strip, Ctrl+C is the block copy. Entering block selection from a cell (long-press) first commits any draft and hides the strip |
| Activates Maximize | `LocalOpenTableSheetAction(blockUuid)` opens the sheet (S4-S6) |
| Scrolls horizontally with touch drag | Scrolls the table; never starts a range |

Errors and empty states:

| State | Shown | Exit |
|---|---|---|
| Formula error | Cell shows `#DIV/0!` etc., see S12 | Tap cell to edit and fix; Edit as markdown |
| Table with only a header row | Header plus one empty-looking row hint is not added; table renders as is | Tap header cell to edit; sheet "Insert row" |
| Sheet flag off (inline on) | Strip without Maximize; no button, no chord | Enable in S17 |
| Inline flag off, sheet flag on | Tap opens raw markdown (today); Maximize button is the only entry to the sheet; no strip, no chord | Enable in S17 |
| Sheet action null (no shell provides it) | No Maximize button (no dead control) | n/a |

---

## S2. Inline cell edit

```
 +--------+------------+-----------+
 | Name   | Qty        | Price     |
 +--------+------------+-----------+
 | Apple  |[ 3|       ]| 1.20      |   one live BasicTextField (active cell only)
 | Pear   | 5          | fx 10     |   others stay rendered cells
 +--------+------------+-----------+
```

On phones the IME opens; the active cell scrolls above the IME (WCAG 2.4.11). Edit text is raw (`[[link]]` and `=B2*2` shown literally).

Flow:

| Key / gesture | Result |
|---|---|
| Tap cell text | Cell enters `Edit`, caret at the tap position, IME opens, the strip appears above the IME (touch rule, design rule 10) |
| Typing | Updates the hoisted `CellDraft`; nothing written until commit |
| Enter | Commit, move down (inline: the next cell enters `Edit`; sheet: it is selected in `Navigate`, see note). On the last body row it commits and stays on the same cell in `Navigate` (no row is appended; Insert row is explicit), the IME hides and "End of table" is announced once |
| Shift+Enter | Commit, move up |
| Tab / Shift+Tab (touch: strip **Next column**) | Commit, move right / left, wrapping across rows; at the last cell, Tab commits and leaves the grid. Touch has Next column (Tab) but no Shift+Tab; Back and Cancel are the way out |
| Soft keyboard action key (touch) | `ImeAction.Next` with Enter semantics: commit and move down, exactly as Enter. It never inserts a line break and never hides the IME on its own, except when it commits on the last body row (see Enter) |
| Alt+Enter (touch: strip **Line break**) | Inserts a line break at the caret, written as `<br>` on commit. The soft keyboard's Enter key never inserts one (see the IME action key row) |
| Esc (hardware keyboard) or strip **Cancel edit** (touch) | Restore the original text if the draft differs, hide the IME, return to `Navigate` on the same cell. The slot is always present in `Edit` (rule 10); when the draft differs, "Discarded your edit" is posted with an Undo action that restores the draft into the cell (the same mechanism as Take theirs), and when it is unchanged the slot only leaves `Edit` and posts nothing |
| Strip **Done** (touch) | Commit the draft (no-op if unchanged), hide the IME, return to `Navigate` on the same cell; this is the touch way into `Navigate` |
| Strip **Select row / Select column / Select table / Extend... / Copy / Maximize / Markdown** (touch) | Commit the draft first (one undo step), then run the action in `Navigate`; the strip is part of the grid's focus scope, so tapping it is never a "tap outside" |
| Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y; strip or edit-bar **Undo / Redo** | Field-scoped (rule 16a): revert or redo one step of this cell's draft history; never commits and never touches the document; Undo is dimmed with "Nothing more to undo in this cell" when the history is empty. Table-level Undo needs `Navigate` (press Done first), where it is named "Undo: edit B3" |
| Android Back (touch) | Section 3.3 ladder: IME visible hides the IME and stays in `Edit`; IME hidden is the same as Done (commits, never closes the table, never discards) |
| Tap outside the table and strip | Commit, leave grid focus (rendered view), strip hides |
| Tap another cell | Commit, edit that cell, unless the point-mode rule of S7 applies (a formula draft whose caret follows `=`, an operator, `(` or `,`): then the tap inserts that cell's reference and the draft stays in `Edit` |
| Edit as markdown (strip or Shift+F2) | Commit draft, then open raw block editor |
| Arrow keys | Move the caret inside the cell (never the grid) |

Note: after Enter or Tab the next cell enters `Edit` inline (spreadsheet flow); in the sheet it becomes the selected cell in `Navigate` (S4); on touch the sheet's IME hides at that point and a tap on the selected cell starts the next edit (UX-89).

Commit writes through `SetCell` then `TableSerializer` then `onContentChange`, one undo step per commit. Typed `|` is stored escaped as `\|`; a notice is not needed for this case because the cell still shows `|`.

Errors and edge cases:

| Situation | Behaviour | Exit |
|---|---|---|
| IME composition active | Enter, Tab, arrows and Esc go to the IME; the grid consumes nothing until composition ends | Finish composition |
| Typed text is a bad formula (`=SUM(`) | Commit succeeds (text is literal), cell then shows `#PARSE!` (S12) | Re-edit |
| Cell text contains newline pasted into the field | Stored as `<br>`; a one-line notice "Line break stored as <br>" | Dismiss or undo |
| Block changes on disk while editing | S14: draft kept, conflict choices; inline the strip shows a **Resolve** chip in every profile (rule 15) and commits are held, so Enter, Tab, Done and taps elsewhere re-show the banner instead of committing | Keep mine / Take theirs / Edit as markdown / Back (dismiss, draft kept, Resolve chip stays) |
| Graph read-only | Cell does not enter `Edit`; S13 reason shown | See S13 |
| Draft recycled out of composition (sheet only) | Draft is hoisted above the lazy row: text, selection and focus request survive, nothing is committed on dispose; commits happen only on Enter/Tab/arrow/blur, close, maximize, Edit as markdown and `onStop` (ADR-004) | n/a |
| Flag flipped off mid-edit | Draft committed first, then the table returns to today's behaviour | n/a |
| Rotation, fold, resize, app to background, process death | Section 3.11: a configuration change keeps the uncommitted draft; backgrounding commits it once | n/a |

---

## S3. Inline selection and action strip

Entered by: tap on a cell to make it active (first tap edits, and on touch the strip is already visible during that edit; Esc, or the strip's Done or Cancel edit on touch, returns to selected-not-editing), mouse drag after slop, Shift+click, Shift+arrows, or "Select row / column / table" from the strip. Touch inline has no drag range (ADR-005 precedence 5).

Touch walk-through for UX-05 (3 taps to copy a row): tap a cell (`Edit`, IME and strip appear) -> tap **Select row** (draft committed, IME hides, row selected) -> tap **Copy** (TSV on the clipboard, "Copied 1 x 3 cells").

```
 +--------+--------+-----------+
 | Name   | Qty    | Price     |
 +--------+--------+-----------+
 | Apple  |[ 3    ]| 1.20      |
 | Pear   | 5      | fx 10     |
 +--------+--------+-----------+
   Navigate: [ Close | Select row | Select column | Select table | Extend... | Copy | Undo: <step> | Redo: <step> | Maximize ] [ Markdown ]
   Edit (touch entry): [ Cancel edit | Done | Next column | Line break | Select row | Select column | Select table | Extend... | Copy | Undo | Redo | Maximize ] [ Markdown ]
   The first slot is one fixed width: Close in Navigate, Cancel edit in Edit, always present (rule 10). In Edit, Undo and Redo are field-scoped (rule 16a). [ Markdown ] is pinned outside the scrolling part. An error cell adds [ Why? ] before Select row; a held conflict adds a [ Resolve ] chip at the start (rule 15).
   (relative order is the same in both modes; the middle scrolls horizontally, 48dp targets; docked to the bottom of the visible area, on top of the IME when it is up)
```

Placement (touch or TalkBack or Switch Access entry): the strip is window-anchored (design rule 10), so it does not move when the IME opens or closes except by the IME's own inset; the active cell is scrolled above the strip first (WCAG 2.4.11). With a mouse or hardware-key entry there is no IME inset to track, so the strip sits directly below the table. Landscape phones: on Android the cell field sets `IME_FLAG_NO_EXTRACT_UI` and `IME_FLAG_NO_FULLSCREEN` (Compose mechanism INFERRED, verified in Task 8.2a) so the IME never takes the whole screen; if the cell and the strip still cannot both fit, the strip collapses to **Cancel edit** or **Close**, **Done**, the pinned **Markdown** button and a "More" button that opens the remaining actions as a menu (a rung-2 layer of the Back ladder). Placement follows the entry profile and never moves when the pointer type changes mid-edit: a mouse click during a touch edit leaves the strip docked, and a tap during a hardware edit neither shows nor moves it (UX-91).

Flow:

| User does | System responds |
|---|---|
| Strip: Select row / Select column / Select table | Selection becomes whole row, column or table (outline, not fill-only) |
| Strip: Extend... | Opens the one Extend picker (right, down, to row end, to column end, to table end), the same picker as the sheet (S10). Each choice grows the range from the anchor and the picker can be used again. There is no "tap extends" mode inline. In formula point mode the same picker extends the reference being built (S7) |
| Strip: Undo / Redo | `Navigate`: undo or redo one of this table's own commits (rule 16b, `TableUndoScope`), text "Undo: edit B3"; never another block. Dimmed with "Nothing more to undo in this table" / "Nothing more to redo in this table" when the scope has nothing; the control stays focusable and activating it shows that notice. `Edit`: field-scoped (rule 16a), never commits |
| Strip: Close (`Navigate`) | Collapses a range first; otherwise leaves grid focus and hides the strip (Back ladder rung 6) |
| Strip: Why? (active cell is an error cell) | Shows the S12 detail as one notice with Edit and Dismiss; stays until acted on |
| Strip: Next column / Line break (touch `Edit`) | Tab and Alt+Enter equivalents (S2) |
| Strip: Copy | TSV of values to the system clipboard, formulas to the in-app slot; announces "Copied 2 x 3 cells" |
| Mouse drag from cell A to B (after slop) | Range `A:B`; touch is excluded (a touch drag scrolls) |
| Shift+click | Range from active cell |
| Ctrl/Cmd+C in `Navigate` | Same as Copy; consumed before `PageView` block-copy |
| Esc | Range collapses to the active cell; second Esc leaves grid focus and hides the strip |
| Strip: Done or Cancel edit (touch, during `Edit`) | See S2; both end in `Navigate` on the same cell with the strip still visible. Cancel edit is always present; it posts the Undo notice only when the draft differs |
| Tap outside | Leaves grid, strip hides |
| Strip: Maximize | Opens the sheet with the same selection |

Errors and edge cases:

| Situation | Behaviour | Exit |
|---|---|---|
| Clipboard write fails (web permission) | Toast "Couldn't copy. Select the text and copy manually" plus "Show as text" opening a dialog with the TSV in a selectable field | Dismiss |
| Read-only graph | Copy, Row, Col, Table, Extend stay enabled; edit actions disabled with reason (S13) | n/a |
| Empty selection result | Copy of blank cells yields empty cells in TSV; announcement says "Copied 1 x 1 cell, empty" | n/a |
| Strip and IME both present | Not an overlap: the strip sits on top of the IME (design rule 10); the active cell is scrolled above the strip, and above a notice when one shows (design rule 13) | Done, Cancel, Close or Back |
| Copy pressed during `Edit` (touch) | The draft commits first, then the active cell is copied as a 1 x 1 range; the platform's own text-selection toolbar still copies selected text inside the field | n/a |

---

## S4. Maximized sheet, phone (<600dp): full-screen Dialog

```
+--------------------------------+
| (x) Page / Table (undo)(redo)(...) |  top bar: close, title, Undo, Redo (48dp, dimmed when empty), overflow (S15)
+--------------------------------+
| fx  [ =SUM(B2:B5)         ](v) |  edit bar (S7), sits above the IME when it is up
+----+---------+--------+--------+
|    |    A    |   B    |   C    |  === sticky column letters (tap = header menu, S8)
+----+---------+--------+--------+
| 1  | Name    | Qty    | Price  |  === frozen header row (default on)
+----+---------+--------+--------+
| 2  | Apple   |[ 3    ]| 1.20   |  selected cell, outline
| 3  | Pear    | 5      | fx 10  |  row number = tap for row menu (S9)
| .. |         |        |        |  lazy rows
+----+---------+--------+--------+
| Count 2  Sum 8  Average 4      |  status line, only when a range of numbers is selected
| Undo Redo Copy Paste Cut Row+ ..|  context strip, scrolls horizontally; Undo and Redo lead
+--------------------------------+
```

Behaviour:
- Opens in one action from S1; focus lands on the previously active cell, else the first body cell (`A2`).
- Tap selects (`Navigate`); tap on the selected cell, Enter or F2 edits; typing a printable character replaces and edits.
- Editing happens in the edit bar and in the cell together (one `CellDraft`); on phones the edit bar is the primary field because the IME covers 40-50% of the screen.
- Column letters and the row-number gutter are sticky; the first column can be frozen from the header menu (S8).
- Back and Esc follow the ladder of section 3.3: Android Back closes the sheet only from `Navigate` on a single cell with no banner, menu, IME, draft or range open. Esc never closes the sheet: it collapses a range and is otherwise not handled, so a stray key cannot lose the user's place (UX-93). The close button (x, "Close table"), Back and Ctrl/Cmd+Shift+Enter (the inline chord toggles) always close (pending draft committed, `BlockStateManager.flush()` runs before dismissal).
- Undo and Redo (UX-70) sit in the top bar and lead the context strip, 48dp with text labels that name the step ("Undo: paste", "Redo: edit B3"; plain "Undo" and "Redo" when dimmed). They call the same depth-gated path as Ctrl+Z/Y (Story 5.1): dimmed when `sheetUndoDepth` or `sheetRedoDepth` is 0, but still focusable, and activating a dimmed one shows "Nothing more to undo in this table" (or "redo") as a polite notice so TalkBack and Switch Access users get feedback. In `Edit` they are field-scoped (rule 16a) and never commit; in `Navigate` they undo this open's own commits only, and the depth starts at 0 on every open, so edits made before the sheet opened, or in an earlier open, are undone from the page after closing.
- Rows are never fixed height; wrapped text grows the row. No row animation.

Flow:

| User does | System responds |
|---|---|
| Tap cell | Selected, edit bar shows literal text |
| Tap selected cell / Enter / F2 | Edit, caret at end, IME opens, edit bar focused or cell focused (last-used wins) |
| Tab / Enter | Commit, move right / down, stays `Navigate` after move; on touch the IME hides (the edit bar keeps showing the next cell's literal text) and a tap on the selected cell edits it; Enter on the last body row commits and stays (UX-89) |
| Long-press cell | Range mode with handles (S10) |
| Tap column letter / row number | Selects the whole column / row and opens the matching menu (S8/S9) |
| Tap (x) | Commit draft, flush, close |
| Back / Esc | One rung of the section 3.3 ladder per press; Esc never closes the sheet, Back, (x) and Ctrl/Cmd+Shift+Enter do |
| Tap Undo / Redo | One depth-gated undo or redo; "Undone" / "Redone" announced; dimmed button shows the "Nothing more" notice |
| Overflow | S15 |

Errors:

| Situation | Behaviour | Exit |
|---|---|---|
| IME hides active cell | Scroll active cell above the IME and above the edit bar | n/a |
| Rotation, fold, resize | Selection, active cell, scroll offsets and the uncommitted draft are kept in the in-memory state holder; nothing is committed | n/a |
| Process death | Section 3.11: the draft was committed once at `onStop`, so the file already has it; selection and scroll are not restored (the reopen is a fresh open) | n/a |
| Commit fails (write refused) | Snackbar "Couldn't save the change" with Retry; draft stays | Retry, Edit as markdown, close (draft preserved in block editor) |
| Edit on a header-row structural op refused | Notice per S8/S9 | n/a |

---

## S5. Maximized sheet, tablet and foldable

Same components as S4; differences:

```
>=840dp: Dialog ~90% of window; page stays visible dimmed around it
+------------------------------------------------------------+
| (x) Page / Table                          Count 2 Sum 8 ... (...)|  top bar, status line inline
| fx [ =SUM(B2:B5)                                         ](v)|  edit bar inline at top (not above IME)
+----+-------+-------+-------+-------+-------+-------+--------+
|    |  A    |  B    |  C    |  D    |  E    |  F    |   G    |
| ...                                                            |
+----+-------+-------+-------+-------+-------+-------+--------+
| Copy Paste Cut Row+ Col+ Sort ...                               |
+------------------------------------------------------------+
```

- 600-839dp: full-screen dialog with the inline top-bar edit bar; 840dp and above: about 90% window. Breakpoints use `LocalWindowSizeClass` only, never device type.
- Fold or unfold, window resize and rotation preserve selection, active cell, scroll offsets, view state and in-progress edit text.
- Tabletop posture (hinge horizontal): sheet in the top half, keyboard below; the active cell must not sit under the hinge (INFERRED API, verify on `Pixel_Fold`).
- Touch handles, hardware keyboard and pointer all work at once.

Errors: window shrinks below 600dp while open: layout switches to S4 without closing; if a draft exists it moves into the phone edit bar.

---

## S6. Maximized sheet, desktop (JVM) and web

```
+--------------------------------------------------------------+
| (x) Page / Table                                       (...) |
| fx [ =SUM(B2:B5)                                      ] (v)  |
+----+-------+-------+-------+-------+-------+-----------------+
|    |   A   |   B   |   C   |   D   |  (chevron on hover)     |  header: hover chevron, right-click, double-click edge = auto-fit
+----+-------+-------+-------+-------+-------------------------+
| 1  | Name  | Qty   | Price |       |
| 2  | Apple |[ 3   ]| 1.20  |       |
+----+-------+-------+-------+-------+-------------------------+
| Count 2   Sum 8   Average 4                     Updated from disk |  status line
+--------------------------------------------------------------+
```

- Dialog about 90% of the window above 840dp, full-screen below.
- Mouse: click selects, double-click edits, drag selects a range, Shift+click extends, right-click opens the cell/row/column context menu, drag a header edge to resize (cursor changes), double-click an edge to auto-fit.
- Keyboard: ADR-009 table, reproduced for users in the sheet's shortcuts panel (S15). Cmd maps to Ctrl on macOS.
- Dialog key reach: undo/redo (Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y) are bound in the sheet because app-window shortcuts may not reach a Dialog window (plan Task 5.1a verifies). The sheet's Ctrl+Z undoes only the sheet's own edits; when none remain it shows "Nothing more to undo in this table" and never touches other blocks (ADR-004). The top-bar Undo and Redo buttons call the same depth-gated path, so a window that never receives the keys still has undo.
- Web: Tab traversal is already trapped at `wasmJsMain/.../browser/Main.kt:125`; paste uses the `paste` event path (S11).
- Hover shows the 48dp-equivalent expand affordance inline (S1) with a tooltip "Maximize (Ctrl+Shift+Enter)".

Errors: the same as S4. Web clipboard denied: S11.

---

## S7. Edit bar (formula bar)

```
 phone:   fx [ =SUM(B2:B5)            ] (...) (Cancel edit) (v)   above the IME; (...) = Insert line break, Next column, Stop inserting references
 >=600dp: fx [ =SUM(B2:B5)                          ]   inline in top area; Enter commits
```

- Shows the literal text of the active cell; the cell itself shows the value when not editing.
- Mirrors the draft in the cell while typing (single `CellDraft`).
- (v) commits; **Cancel edit** (a revert-arrow icon, the same as Esc) is always present in `Edit` and restores the original only if the draft differs. It is a different control from the top bar's **Close table** (x), with a different icon and label. Both 48dp on touch. Undo and Redo in the edit bar's (...) menu are field-scoped during `Edit` (rule 16a).
- Formula point mode (iteration 7 adds the indicator, the escape and the literal-text rule). A tap on another cell inserts its reference instead of committing only when the draft starts with `=`, the draft up to the caret is a valid formula prefix under the formula tokenizer, and the caret follows `=`, an operator (`+ - * / ^ & < > <= >= <>`), `(` or `,` (whitespace ignored). Text that merely starts with `=`, such as `=5 apples`, `= total` or `==`, fails the prefix check, so it never arms point mode and a tap commits and moves as in S2. Any other draft, or a caret elsewhere, makes the tap commit and move as in S2. While point mode is armed the edit bar (sheet) or the strip (inline) shows the text indicator "Tap a cell to insert its reference" (after an insert: "Inserted B2. Extend... or type to continue"), and its **Stop inserting** control turns point mode off for the rest of this edit, so a tap commits and moves; typing any character, moving the caret or Done also disarms it, and Done, Enter and Tab always commit. UX-90 tests it. After an insert, another tap replaces that inserted reference until the user types another character. The non-drag way to build a range: after the tap inserts `B2`, strip or context-strip **Extend...** extends the inserted reference (down by one, to column end...), so `=SUM(` plus tap `B2` plus Extend down 3 yields `=SUM(B2:B5`; a handle drag does the same on the sheet. Focus stays in the field. One pure `PointModeRule` decides (plan Task 3.1d); UX-20 and UX-75 test it.
- Multi-line cells: shown in one field with `<br>` as visible text, not a line break. Alt+Enter inserts `<br>`; on touch the phone edit bar's (...) menu has **Insert line break** (Alt+Enter) and **Next column** (Tab), and the soft keyboard's action key is Enter (commit, move down). Neither touch path needs a hardware key.

Flow and errors:

| Situation | Behaviour | Exit |
|---|---|---|
| Typed formula has a syntax error | Bar shows a one-line message below: "Unexpected end of formula. Supported: SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, IF, ROUND, ABS" (text, not just color); commit still allowed | Fix text or cancel |
| Active cell is an error cell | Bar shows the literal source; message line shows the reason (S12) | Edit |
| Read-only | Bar is read-only text with "Read only" state | Copy text from the bar |
| Selection is a range | Bar shows the active (anchor) cell's text | n/a |
| IME composition | Enter and Esc go to the IME until composition ends | n/a |

---

## S8. Column header menu

Opened by: tap column letter (touch), click chevron or right-click (desktop/web), Shift+F10 or Menu key on a header, TalkBack custom actions.

```
Phone: bottom sheet                      Desktop/tablet: popup menu
+-------------------------------+        +--------------------------+
| Column B                      |        | Sort rows A-Z (changes file)  |
| Sort rows A-Z (changes file)  |        | Sort rows Z-A (changes file)  |
| Sort rows Z-A (changes file)  |        | Align left | center | right  |
| Align  [left][center][right]  |        | Insert column left            |
| Insert column left            |        | Insert column right           |
| Insert column right           |        | Move column left              |
| Move column left              |       | Move column right             |
| Move column right             |       | Delete column                 |
| Delete column                 |       | Width: Narrow Medium Wide Auto-fit |
| Width  Narrow Medium Wide Auto-fit |      | Freeze first column (toggle)  |
| Freeze first column            |      +--------------------------+
| (Cancel)                      |
+-------------------------------+
```

Rules:
- Sort is labelled "(changes file)", is one undo step, never touches the header row, and sorts numerically by computed value for numeric columns. Announces "Sorted by Name", plus ". N formulas reference other rows" and ". N blank cells sorted last" when those counts are above 0 (all from `EditSummary.Sorted`, ADR-003). The sort indicator is session-only and clears when row order changes by any other edit (ADR-007).
- Alignment writes only that column's delimiter cell (`---:`), one undo step.
- Width presets are the non-drag alternative to header-edge dragging; no content write.
- Delete, insert and move rewrite formula references in the same undo step (ADR-003).
- Disabled items show a reason line, e.g. "This graph is read-only".

Errors:

| Situation | Behaviour | Exit |
|---|---|---|
| Delete the last column | Refused: "A table needs at least one column" | Menu stays open |
| Delete column referenced by formulas | Allowed; dependent cells show `#REF!` after; notice "2 formulas now show #REF!" with Undo | Undo |
| Sort with formulas | Rows sort by value; references follow data (ADR-003); announced | Undo |
| Sort a table whose last row is a totals row (`=SUM(B2:B5)`) | Totals row is excluded and stays at the bottom; the menu helper text says "Totals row stays at the bottom"; with a multi-row selection the item reads "Sort selected rows" | Undo |
| Sort would put a total inside its own range | Refused, nothing changes: "Sort would put a total inside its own range" | Sort selected rows / Edit as markdown |
| Sort in a table with a blank key column | Blank rows sort last in both directions; notice "Sorted by Qty. 3 blank cells sorted last" (`EditSummary.Sorted.blankCount`) | Undo |
| Menu open and block changes externally | Menu closes, grid refreshes per S14 | n/a |

Exit: tap outside, Back, Esc, or the Cancel item closes the menu with no change.

---

## S9. Row gutter menu

Opened by: tap row number (touch), right-click or hover chevron (desktop), Shift+F10/Menu key.

```
+-------------------------------+
| Row 3                         |
| Insert row above              |
| Insert row below              |
| Move row up                   |
| Move row down                 |
| Duplicate row                 |
| Delete row                    |
| (Cancel)                      |
+-------------------------------+
```

- Each action is one undo step; moving or inserting rewrites formula references in the same step.
- The header row (row 1) menu offers only: Insert row below, Freeze header (toggle). Delete and Move are not offered, shown disabled with the reason "The header row can't be moved or deleted".
- Selecting a row via the gutter selects the full row (outline across all cells).

Errors:

| Situation | Behaviour | Exit |
|---|---|---|
| Delete the only body row | Allowed; the table keeps its header and delimiter | Undo |
| Move first/last row further | Item disabled, reason "Already at the top" / "Already at the bottom" | n/a |
| Row has a draft mid-edit | Draft is committed first | n/a |

Exit as S8.

---

## S10. Touch range mode and status line

```
 long-press cell A2        --> range mode
 +----+-------+--------+
 | 2  |o[ Apple| 3      |      o = anchor handle (12dp visual, 48dp hit)
 | 3  |  Pear  | 5     ]o|     o = focus handle
 +----+-------+--------+
 Count 2  Sum 8  Average 4
 [ Undo: <step> | Redo: <step> | Copy | Cut | Clear | Paste | Select row | Select column | Select all | Extend... ]  (same leading Undo and Redo as the context strip, S4)
```

Flow:

| User does | System responds |
|---|---|
| Long-press a cell | Range mode: single cell selected, two handles, strip changes to range actions; haptic tick where available |
| Drag a handle | Resizes the rectangle; grid auto-scrolls near edges |
| Strip: Extend... | Opens the one Extend picker "Extend: right, down, to row end, to column end, to table end" (non-drag path; the same picker inline and, in formula point mode, for the reference being built) |
| Tap row number / column letter | Select whole row / column |
| Tap one cell | Collapses to that cell |
| Strip: Copy, Cut, Clear, Paste | See S11; Cut and Clear are one undo step |
| Back / Esc | Section 3.3 ladder: collapses the range (rung 5); the next press continues down the ladder (inline: Back or Esc closes the strip; sheet: Back closes the sheet, Esc does nothing) |

Status line:
- Shows "Count N  Sum S  Average A" only when the range contains at least one number. Non-numeric cells are ignored; formulas count by computed value; error cells are ignored and the line adds "1 error" when any.
- It is a polite live region updated at most once per 500ms during a drag.

Errors:

| Situation | Behaviour | Exit |
|---|---|---|
| Handle dragged outside table | Clamped to table bounds | n/a |
| Selection of > 5k cells | Copy still works; the status line caps work off the main thread; no UI freeze | n/a |
| Handles overlap at row 1 sticky area | Handles remain tappable above the sticky header; grid scrolls the active corner into view | n/a |

---

## S11. Paste feedback, notices and web paste fallback

Paste rules (ADR-008, plan Task 5.4b): a single copied cell fills the whole selection; otherwise the clipboard pastes whole from the selection's top-left, growing the table. In-app clipboard slot preserves formula text when TSV equals the slot. Every notice below follows the queue rule of section 3.10.

```
After paste:  +--------------------------------------------+
              | Pasted 8 x 5, added 7 rows, 3 columns [Undo]|   snackbar, stays until acted on or dismissed (design rule 11), also live region
              +--------------------------------------------+
Transformed:  | Pasted 4 x 3. 2 cells changed: "|" escaped, line break stored as <br>  [Undo] |
Rejected:     | Paste is too large (limit 10,000 cells). Nothing changed.  [Dismiss]            |
```

Web paste fallback (clipboard permission denied or no `paste` event arrives):

```
+-----------------------------------------+
| Paste here                              |
| [ multiline field, focused             ]|
| Paste the cells here, then press Insert |
| [Cancel]                      [Insert]  |
+-----------------------------------------+
```

Flow:

| User does | System responds |
|---|---|
| Ctrl/Cmd+V or strip Paste | Parse via the `TableClipboard` port; one commit; snackbar summary; selection becomes the pasted range |
| Undo (snackbar or Ctrl+Z) | Restores the exact previous string |
| Web, `paste` event | Used first; `navigator.clipboard.readText()` is not required |
| Web, no event / denied | Fallback box above; focus moves to its field |

Errors:

| Situation | Behaviour | Exit |
|---|---|---|
| Clipboard empty or not text | Notice "Nothing to paste" | Dismiss |
| Paste over the cap (10,000 cells) | Rejected whole, nothing changes | Dismiss |
| Paste on a read-only graph | Paste disabled with reason (S13) | n/a |
| Parse throws | Caught at the boundary: "Couldn't read the clipboard as a table", logged via `Logger`, nothing changed | Dismiss, paste as text into one cell |
| Paste into a formula range | Pasted text lands literally; references are not auto-adjusted unless the slot matched | Undo |

---

## S12. Formula and cell error states

| Error | Cell shows | Detail text (tooltip on desktop/web, keyboard focus, selecting the cell in the sheet, the Why? action inline; `error()` semantics) |
|---|---|---|
| Parse | `#PARSE!` | "Unexpected end of formula" (with position) |
| Bad reference | `#REF!` | "Reference B9 is outside the table" or "Referenced row or column was deleted" |
| Cycle | `#CYCLE!` on every cell in the loop | "Circular reference B2 -> C2 -> B2" |
| Divide by zero | `#DIV/0!` | "Division by zero" |
| Wrong type | `#VALUE!` | "A2 is text, expected a number" |
| Unsupported name | `#NAME?` | "VLOOKUP isn't supported. Supported: SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, IF, ROUND, ABS" |
| Complexity cap | `#LIMIT!` | "Formula is too large or nested too deeply" |

Rules:
- Errors are text plus a tint and an icon, never color only (WCAG 3.3.1, 1.4.1).
- The source text is untouched and shown in the edit bar; tapping the cell edits it. Because formulas are literal, Logseq shows the raw text and no data is lost.
- Formula errors never throw; they log through `Logger` once per distinct cause per session.
- Touch has no long-press path to the detail (long-press is block select inline and range mode in the sheet). Sheet: selecting the error cell shows the reason in the edit bar's message line and the status line. Inline: when the active cell is an error cell the strip shows **Why?**, which shows the detail as one notice with Edit and Dismiss (design rule 11: stays until acted on).
- Exit for every error: edit the cell, undo, or Edit as markdown.

---

## S13. Read-only / browse mode

Trigger: the host's `readOnly` parameter is true. No active-graph read-only flag exists today (verified by search), so this state is reachable only in tests until one is added; the layout below is kept so it is ready.

```
+--------------------------------+
| (x)  Page / Table      Read only (...) |   top bar chip "Read only"
| fx  [ =SUM(B2:B5)       ] (disabled)   |
| ...grid: select + copy work...         |
| Copy  [Paste] [Cut] [Row+] [Sort]      |   disabled ones greyed, "This graph is read-only" on focus/long-press
+--------------------------------+
```

- Allowed: select, range select, copy, view formulas, open links, Edit as markdown (opens the raw viewer, which itself is read-only).
- Disabled with reason: cell edit, paste, cut, clear, structure ops, sort, alignment, resize presets stay enabled (view-only, session).
- Tapping a cell enters selection only; typing does nothing visible but shows the one-line reason "This graph is read-only" (not a silent swallow).
- Inline: tap selects instead of editing; strip shows Copy, Select row, Select column, Select table, Maximize, Markdown. Touch (the trigger is still the host's `readOnly` parameter, so reachable in tests only): a tap on a cell selects it in `Navigate` without opening the IME, shows the strip, and shows the reason line "This graph is read-only" once; long-press keeps its normal meaning (block select inline, range mode in the sheet).
- Semantics: `disabled()` plus `stateDescription = "Read only"`; `aria-readonly` equivalent on the grid.

Exit: all layers close as usual; nothing is written.

---

## S14. External change and conflict

Uses `TableSyncPolicy` (`Echo`, `Stale`, `Adopt`, `Rebuild`, `ConflictKeepDraft`, ADR-004). The banner below is new sheet UI (`TableConflictBanner`); the page-level `DiskConflict` dialog has no per-block flow and is not reused. Undo and redo are not external changes: they `Adopt` silently.

No draft pending:

```
 grid refreshes in place, selection kept by (row, col), clamped if rows were removed
 status line / toast: "Updated from disk"   (polite live region)
```

Draft or uncommitted edit pending:

```
+--------------------------------------------------+
| This table changed on disk                       |
| Your edit to B3 ("42") hasn't been saved.        |
| [Keep mine]  [Take theirs]  [Edit as markdown]   |
+--------------------------------------------------+
```

| Choice | Result |
|---|---|
| Keep mine | The draft commits onto the new content (if the cell still exists), one undo step |
| Take theirs | Draft discarded explicitly; grid rebuilt; announces "Discarded your edit" with an Undo notice that stays until acted on or dismissed (design rule 11); Undo restores the draft text into the cell |
| Edit as markdown | Closes the sheet and opens the raw editor with the on-disk content. The draft is stored as a `RecoveredDraft` (session memory, keyed by block and cell) and shown as a persistent notice "Unsaved edit to B3: 42  [Copy] [Apply to B3] [Discard]" on the block until one of the three is chosen; it never auto-expires |
| Back or Esc (dismiss) | Closes the banner only. The sheet stays open, the draft stays in its cell, nothing is written, and the status line shows a persistent "Conflict: your edit to B3 is not saved [Resolve]" chip that reopens the banner. Further commits are held while the conflict is unresolved |
| Close sheet while conflicted | Never drops or silently overwrites: the draft becomes a `RecoveredDraft` (as in Edit as markdown) and the sheet closes without writing |

Inline (iterations 6 and 7): a held draft is never silent, in any entry profile (design rule 15). When `TableSyncPolicy` returns `ConflictKeepDraft` for an inline table, the strip shows a **Resolve** chip ("Your edit to B3 is not saved") at its start in both modes, and a mouse or hardware-key `Edit` shows the reduced strip (Resolve, Cancel edit, Markdown) below the table with no focus change and one polite announcement. Further inline commits are held: Enter, Tab, Done, Next column and taps elsewhere commit nothing and re-show the banner. Resolve opens the same banner (`TableConflictBanner`) above the strip with Keep mine, Take theirs and Edit as markdown. Back or Esc dismisses the banner only (Back ladder rung 1); the chip stays and the draft stays in its cell. Taking theirs or Edit as markdown follows the sheet rows above (`RecoveredDraft`).

Errors and edge cases:

| Situation | Behaviour | Exit |
|---|---|---|
| Row or column count differs after the change | Block-level conflict only; no cell-level merge in v1 (same dialog, without "Keep mine" if the cell is gone) | Take theirs / Edit as markdown |
| Echo of own write | Ignored; no flicker, no toast | n/a |
| Block deleted, no longer a table, unparsable, page unloaded or graph switched while the sheet is open | Sheet flushes and closes; one toast ("This table was removed" / "This block is no longer a table"); nothing is ever written to a missing block | n/a |
| Banner dismissed (Back/Esc) | Dismiss means dismiss: no choice is assumed, nothing is written, the draft and the Resolve chip remain (iteration 5; this replaces "Back = Edit as markdown", which broke the Back-dismisses mental model) | Resolve chip, or Keep mine / Take theirs / Edit as markdown at any time |
| Process killed while a `RecoveredDraft` or a conflicted draft exists | The text is lost (session memory only, no file written behind `GraphWriter`); a held conflict draft is not committed at `onStop` (section 3.11), so this is the one draft that process death can still lose; stated here as a known limit, hypothesis to test in 8.7a whether it matters | Copy it out of the notice early |

---

## S15. Sheet overflow menu and shortcuts panel

```
(...) -> +--------------------------------------+
         | Edit as markdown        Shift+F2     |
         | Copy as formulas                     |
         | Keyboard shortcuts                   |
         | Freeze header row         [on]       |
         | Freeze first column       [off]      |
         | Column widths reset when you close the sheet |   info line, not an action
         +--------------------------------------+

Keyboard shortcuts panel (dialog or sheet, built from TableShortcutMap):
  Navigation  Arrows, Tab, Enter, Home/End, Ctrl+Home/End, PgUp/PgDn, Ctrl+Arrow
  Selection   Shift+Arrows, Ctrl+Space, Shift+Space, Ctrl+A
  Edit        F2, Enter, Esc, Alt+Enter, Delete, Ctrl+Z, Ctrl+Shift+Z/Y
  Clipboard   Ctrl+C / X / V
  Structure   Ctrl+Shift+=, Ctrl+-, Alt+Shift+Arrows
  Sheet       Ctrl+Shift+Enter (maximize inline, close in the sheet), Shift+F2 (edit as markdown), Shift+F10 (menu)
```

- "Copy as formulas" copies cells with `=...` literals as TSV (the default copy puts values).
- Undo and Redo are top-bar buttons. When the bar wraps at narrow width (320 CSS px) they are the last items to move and become the first two overflow items, still labelled and still dimmed with the same notice.
- The panel lists keys for the current platform (Cmd on macOS) and has a close button and Esc exit.
- Shortcuts that conflict with existing app bindings are not offered (ADR-009: no Ctrl+Shift+E, no Ctrl+Alt+M, no Ctrl+D/R).

Errors: panel render failure falls back to a plain list; no error state is expected otherwise. Exit: Close, Esc, Back.

---

## S16. Unparseable or oversized table fallback

```
Unparseable (no delimiter row, malformed):
 +----------------------------------------------+
 | rendered fallback (today's behaviour)         |
 | This table can't be edited as a grid: missing |
 | delimiter row.                [Edit as markdown] |
 +----------------------------------------------+

Large (>5,000 cells) inline:
 first N rows rendered ... "Show all rows (Maximize)"  [Maximize]
```

- Ragged rows that can be padded virtually open normally; untouched rows are re-emitted byte-identically.
- Unparseable tables never enter grid edit; the only action is Edit as markdown (always works).
- Oversized tables virtualize rows in the sheet; inline shows a capped row window with an explicit "Maximize" action and counts exposed via `collectionInfo`.

Exit: Edit as markdown; for large tables, the sheet or scrolling.

---

## S17. Settings toggles (condensed)

Location: Settings, Editor, Tables section.

```
Tables
  [ ] Edit table cells in place          (table_inline_edit_enabled, default off)
  [ ] Spreadsheet view                    (table_sheet_enabled, default off)
  Note under each: "Raw markdown editing is always available."
```

Acceptance criteria:
- Both default off; with both off the table matches today's rendering and tap behaviour.
- Toggling takes effect without restart; toggling off while a cell is editing commits the draft first.
- Switch labels have text, not icon-only, and are keyboard reachable.
- The sheet toggle off removes the Maximize button and the Ctrl+Shift+Enter binding; the inline toggle off removes the strip and the chord and leaves tap = raw markdown (flag matrix, plan.md Story 4.1).

## S18. Screen-reader announcements (condensed)

Polite live region, one message per user action:

```
"Pasted 4 x 3 cells"            "Sorted by Name[. N formulas reference other rows][. N blank cells sorted last]"           "Undone"
"Copied 2 x 3 cells"            "Updated from disk"        "Inserted column to the right of B"
"Circular reference B2 -> C2 -> B2" (on commit of the causing edit)
```

Acceptance criteria:
- One announcement per undoable action; undo/redo announce the generic "Undone" / "Redone" (the undo manager keeps no entry labels, ADR-004).
- No announcement while the user is typing in a cell.
- Announcements are text, localisable strings, not log lines.
- Status-line updates during a drag are debounced to at most one per 500ms.
- Messages that carry an action are not time-limited (design rule 11); informational ones auto-dismiss after 10 s with the timer paused on hover or focus.

---

## 3. Cross-cutting interaction model

### 3.1 Gesture precedence (ADR-005; implemented once in `TableGestureOwner`)

0. While the page is in block selection mode the table is inert: a tap on a cell toggles block selection, nothing edits, no strip shows (ADR-005 precedence 0).
1. Tap on a link in a non-editing cell opens it.
2. Tap on other cell text (inline flag on) edits that cell; in the sheet it selects.
3. Long-press: inline = existing block select; sheet = range mode with handles.
4. Mouse drag or Shift+click after slop = range select (mouse only inline).
5. Touch drag inline = horizontal scroll, never a range.
6. Header and gutter taps select whole columns and rows and open menus (sheet only).

### 3.2 State machine (one per table)

```
Rendered --tap cell--> Edit(cell) --Esc | strip Cancel edit (touch)--> Navigate(cell)  (inline: back to Rendered when focus leaves)
Edit(cell) --strip Done (touch) | Back with IME hidden--> Navigate(cell)   (draft committed)
Edit(cell) --strip Select/Extend/Copy/Maximize/Edit as markdown (touch)--> commit draft, then action in Navigate
Navigate --Enter/F2/type | tap the active cell--> Edit
Edit --Enter/Tab--> Navigate or Edit(next)   (inline: Edit(next); sheet: Navigate(next))
Navigate --Esc (range)--> Navigate(single) --Esc--> Rendered (inline) / not handled (sheet; Back, (x) or Ctrl/Cmd+Shift+Enter close it)
Any --Edit as markdown--> raw block editor   (draft committed first)
```

Strip visibility (iterations 6 and 7): `TableStripPolicy(mode, entryProfile, conflictHeld)`, where `entryProfile` is fixed when `Edit` starts. `Touch` means a touch pointer, or TalkBack or Switch Access is enabled (Android `AccessibilityManager`); `Hardware` means a mouse click or hardware-key entry with no touch or accessibility service active. `Navigate`: visible for every profile once a cell is active. `Edit`: visible if and only if the profile is `Touch`, for the whole edit. A Bluetooth or hardware key pressed during a `Touch` edit does not hide the strip; a tap during a `Hardware` edit does not show it until the next edit. Web and Desktop screen readers are not detected and get `Hardware` (the strip is not their accessible path; Edit as markdown and the shortcuts are, section 3.7). `conflictHeld` overrides the profile: with `ConflictKeepDraft` unresolved the strip is visible in every mode (a `Hardware` edit gets the reduced strip, design rule 15). On Web the profile comes from the pointer type of the event that started the edit (`touch` and `pen` are `Touch`, `mouse` is `Hardware`; INFERRED that Compose's `PointerType` carries it on Wasm, checked at 390 px in Task 8.3a). Placement is part of the same fixed decision (design rule 10). A pure function with a table test (plan Tasks 4.4a and 4.5e).

### 3.3 Back and Esc ladder (one ladder, iteration 6)

The first matching rung (top to bottom) handles Back or Esc; no other handler runs. `TableBackLadder(surface, state)` is one pure function (plan Task 3.1d); the Compose `BackHandler` (Android) and `onPreviewKeyEvent` (Esc) are thin wrappers, and the inline `BackHandler` is enabled only while a cell is active so that with nothing open Back belongs to the platform.

| Rung | State (checked in this order) | Back (Android) | Esc (keyboard) |
|---|---|---|---|
| 1 | Conflict banner open (sheet or inline); also re-shown by any held commit key (rule 15) | Dismiss the banner only; draft kept; Resolve chip stays | same |
| 2 | A menu or panel is open: column or row menu, overflow, shortcuts panel, Extend picker, More menu, error-detail notice or popup, paste fallback box | Close it; focus returns to its invoker | same |
| 3 | The soft keyboard is visible | Hide the IME; mode unchanged (`Edit` stays `Edit`) | not applicable (Esc continues to rung 4) |
| 4 | `Edit` with the IME hidden | **Done**: commit (no-op if unchanged), then `Navigate` on the same cell; never closes the sheet, never discards | **Cancel**: restore the original, then `Navigate` |
| 5 | `Navigate` with a range (more than one cell, or touch range mode with handles) | Collapse to the active cell | same |
| 6 | Inline `Navigate`, single cell, strip visible | Close the strip: leave grid focus; the next Back is the platform's | same |
| 6' | Sheet `Navigate`, single cell | Commit any draft, flush, close the sheet | not handled (never closes the sheet; closing is Back, (x) or Ctrl/Cmd+Shift+Enter, UX-93) |

Pointer exits that are not Back: tap outside the table and strip commits and leaves grid focus (inline); the paste fallback box has Cancel; menus have a Cancel item; the shortcuts panel has Close; the sheet has (x) and the Ctrl/Cmd+Shift+Enter chord. Each of the twelve rung-and-surface combinations has its own test (plan Tasks 4.4e and 5.3d, UX-71); UX-47 checks that focus is never trapped.

### 3.4 Keyboard map

ADR-009 is the single source. Notable bindings: Maximize Ctrl/Cmd+Shift+Enter, Edit as markdown Shift+F2, menu Shift+F10. In `Navigate` mode the grid consumes Ctrl+A/C/X/V and Esc before `PageView` and `BlockList` handlers; in `Edit` mode it consumes only Enter, Tab, Esc and Ctrl+Z/Ctrl+Shift+Z/Ctrl+Y (field-scoped, rule 16a), and never while `composition != null`. Ctrl/Cmd+Shift+Enter maximizes an inline table and closes the open sheet.

### 3.5 Visual and responsive rules

- Selection and focus are outlines of at least 2dp with 3:1 contrast against both the cell and neighbours, plus the `selected` semantic; never a fill alone.
- Formula cells: `fx` marker and a tint; edit bar uses monospace.
- Minimum column 96dp, maximum 240dp; no fixed row height; large font (2.0) wraps instead of clipping; 360dp is the narrowest supported width.
- Dark mode and light mode use theme tokens only; error text contrast is at least 4.5:1.

### 3.6 Accessibility semantics (summary; plan Epic 6)

- Sheet is a `grid`: `collectionInfo` with full row and column counts (not the lazy window), `collectionItemInfo` per cell, header cells flagged as headings, `selected`, `error()`, `stateDescription` for formulas ("Formula =SUM(B2:B5), value 42"), "Sorted ascending", "Read only" (when the host is `readOnly`).
- TalkBack custom actions on cells and headers: Edit cell, Extend selection right/down/to row end/to column end, Select row, Select column, Select all, Move column left/right, Move row up/down, Insert row/column, Resize column (presets), "Sort rows A-Z (changes file)" / "Sort rows Z-A (changes file)", Copy, Paste.
- Roving focus: only the active cell is a tab stop; Tab enters the grid once and leaves once at the end.
- Web: if the canvas grid is not exposed to the browser accessibility tree, Edit as markdown (plain text field) is the accessible fallback and stays one action away.

### 3.6b Inline table semantics (iteration 6)

- The inline table keeps `table` semantics with `collectionInfo` and `collectionItemInfo` (full counts). With the inline flag on, every cell has an `onClick` labelled "Edit cell" and these custom actions, which are the TalkBack and Switch Access path to everything the strip does: Edit cell, Select row, Select column, Select table, Extend selection (right, down, to row end, to column end, to table end), Copy, Undo, Redo, Maximize (sheet flag on), Edit as markdown, Insert line break and Next column (while editing), and Resolve (while a conflict is held).
- Focus entry: TalkBack focus on a cell reads "Row N, column X, <text>, double-tap to edit". Activating it enters `Edit` and moves focus into the cell's text field with one polite announcement "Editing B3, <text>" (none while typing, UX-54). Done, Cancel, Close and Back return focus to the same cell node.
- The strip is its own traversal group placed immediately after the table in reading order; its first stop is Close or Cancel edit, then Done. Every strip control is a focusable button with a text label (UX-80).
- A hardware Tab enters the table as one stop and leaves once, as in the sheet. Web and Desktop screen readers are UNVERIFIED and gated (3.7, UX-81).

### 3.7 Assistive technology, WCAG and locale coverage (iteration 5)

Screen readers (each row states what is verified and what is not):

| Platform | Reader | Position | Where checked |
|---|---|---|---|
| Android | TalkBack | Supported, verified (custom actions, `collectionInfo`) | Task 8.2a |
| Web (Wasm) | NVDA (Windows) and VoiceOver (macOS Safari/Chrome) through the browser accessibility tree | Supported only if the canvas grid is exposed; otherwise "Edit as markdown" plain text field is the supported path (UX-56). Owner-gated, UNVERIFIED until Task 6.1e | Tasks 6.1c, 6.1e |
| Desktop JVM | NVDA, Narrator (Windows), Orca (Linux), VoiceOver (macOS) through Compose Desktop's accessibility bridge | Best effort, UNVERIFIED (the bridge's grid support is not documented in this repo); "Edit as markdown" is the fallback and stays one action away | Task 6.1e |
| iOS | VoiceOver | Explicit non-goal for this plan: iOS is best effort with no Bazel path and no verification reach (requirements.md Users); the shared semantics are provided but not claimed to work. Reported as UNVERIFIED in the final report (M-07) | n/a |
| Any | JAWS | Not tested (licence and no test machine); NVDA is the proxy for Windows browsers. Stated as a non-goal, not a claim of support | n/a |

**Acceptance gate (iteration 6, UX-81):** `table_sheet_enabled` does not become default-on for Web or Desktop until Task 6.1e has recorded that platform's row as verified, or as fails-with-fallback or unverified with the owner's written acceptance that "Edit as markdown" is the supported path there. A blank row blocks the flip. Android requires the TalkBack row verified in Task 8.2a, including the inline custom actions of 3.6b.

WCAG items:
- **1.4.13 Content on hover or focus** (error detail, tooltips): detail appears on pointer hover, on keyboard focus of the cell (shown in the status line and the edit bar, no popup needed) and on long-press; a hover popup is dismissible with Esc, hoverable (the pointer can move onto it) and persistent until dismissed or focus moves.
- **2.2.1 Timing adjustable**: design rule 11 (action-bearing messages persistent; informational ones 10 s, pausable).
- **1.4.10 Reflow and 1.4.12 Text spacing**: on web at 320 CSS px width (400% zoom) the sheet's top bar wraps into the overflow menu with no page-level horizontal scroll; the grid itself scrolls in two dimensions (data table exception). With user text spacing (line height 1.5, paragraph 2x, letter 0.12em, word 0.16em) rows grow and nothing clips (rows have no fixed height).
- **Forced colors and high contrast**: selection, focus, error and `fx` markers use outline width and glyphs, never fill alone, so they survive a palette swap. Wasm canvas content is not recolored by the browser, so the app reads `matchMedia('(forced-colors: active)')` (INFERRED mechanism) and switches to the high-contrast theme tokens; Android uses its high-contrast text setting; Windows High Contrast on Desktop JVM is UNVERIFIED. One `TableVisualPrefs` value (`reducedMotion`, `highContrast`) is provided via a `CompositionLocal`.
- **Reduced motion**: with the preference on (Android animator scale 0, web `prefers-reduced-motion`), scroll-into-view jumps instead of animating and notices appear without fade or slide.
- **RTL**: under a right-to-left layout direction column 1 is drawn at the right, Left/Right arrows and Home/End mirror, Tab advances visually right to left, and the sticky row-number gutter moves to the right. File order, TSV order and `CellAddress` stay in logical (left-to-right) column order. The menu items "Align left" and "Align right" stay physical because the GFM markers `:---` and `---:` are physical. Cell text direction follows its content.
- **Localisation**: all user-visible strings sit in `TableMessages` (single seam). v1 ships English only; translating them is an explicit non-goal now, with the seam making a later resource migration mechanical.

### 3.8 Loading and in-progress states (iteration 5)

- **Sheet open**: if the table has more than 1,000 cells, or parsing and the first calc take longer than 100 ms, the dialog shows its top bar immediately with an indeterminate "Opening table..." indicator in place of the grid; Close and Edit as markdown stay enabled. Below that threshold nothing flashes.
- **In-flight commit**: editing stays enabled; the status line shows "Saving..." only if a commit is still pending after 300 ms, and clears on completion (polite live region announces only a failure, UX-45). Closing the sheet with a commit in flight waits for `flush()` (Story 5.1), showing "Saving..." on the closing dialog.
- **Large paste or sort**: the same 300 ms rule, then "Applying..." in the status line; the result notice replaces it.

### 3.9 Discoverability (iteration 5)

Both flags ship off, so nothing is found by accident. Hints are one-time, non-modal, dismissible, stored per install in `Settings` (key `table_hints_seen`), and re-showable from the shortcuts panel ("Show tips again"):
- **First-run note**: enabling either flag in Settings shows a one-line note under the toggle ("Tap a cell to edit it. Raw markdown editing is always available.") and a dismissible tip on the first table opened afterwards.
- **H1** first inline edit on touch: a tip chip in the transient slot above the strip "Select row, then Copy, copies a row". It follows design rule 13: never while a notice is showing, never in landscape, and dismissed by the first strip action.
- **H2** first sheet open on touch: "Long-press a cell to select a range, or use Extend... in the strip".
- **H3** first sheet open with a keyboard: "Keyboard shortcuts are in the menu (top right)".
Hints never move focus, are announced once politely and are never required to complete a task. A hint that is deferred because a notice is showing waits for the first moment with no notice and an idle strip (2 s); after 3 deferrals in one session it is dropped for that session and is not marked seen, so it can still appear next session and can never starve an action.

### 3.10 Notice queue, merge rule and height (iteration 7)

One notice is visible at a time (one `NoticeQueue`, plan Task 3.2c, rendered by `TableNoticeHost` in the sheet and `TableOverlayStack` inline). Maximum height: two lines, one line in landscape, and never more than 25% of the space above the IME; longer text wraps then ellipsizes with the action still visible and the full text as the accessibility label.

| Kind | Examples | Rule |
|---|---|---|
| Informational | "Updated from disk", "Copied 2 x 3 cells", "End of table" | A new one replaces a visible informational one (latest wins). While an action-bearing notice is visible it is not queued: it goes to the live region and the status line only. Auto-dismisses after 10 s (timer paused on hover and focus) |
| Action-bearing | Undo (paste, delete, sort, Discarded your edit), Retry, Why? detail, Keep mine | Never dropped and never replaced by a different notice. It queues first-in first-out behind the visible one, which shows a focusable "1 more" control that advances the queue. A commit-failure Retry jumps to the front and the preempted notice stays queued. A second Why? detail replaces the first (same kind). Undo-bearing notices past a queue length of 5 retire the oldest into the Undo control: its step stays on the undo stack and stays reachable as "Undo: <step>" and by Ctrl+Z, so no undo is lost |
| Pinned | `RecoveredDraft` ("Unsaved edit to B3: 42  [Copy] [Apply to B3] [Discard]"), the held-conflict Resolve chip | Not part of the queue: the `RecoveredDraft` notice has its own one-line slot on the block, the Resolve chip lives in the strip. Neither is ever dropped or auto-dismissed |

UX-87 tests the rule.

### 3.11 Draft survival (iteration 7; reconciles S4, S14 and ADR-004)

| Event | Uncommitted draft | Selection, scroll |
|---|---|---|
| Rotation, fold or unfold, window resize (configuration change) | Kept in the in-memory state holder, not committed, with caret and composition-free text; `Esc` or Cancel edit still discards it afterwards | Kept |
| App goes to the background, tab hidden or window closed (`onStop`, not a configuration change: Android `isChangingConfigurations` is false, INFERRED mechanism; Web `visibilitychange` to hidden; Desktop window close) | Committed once with any composition cleared, one undo step, a no-op if unchanged. A draft held by a conflict is not committed: it becomes the session `RecoveredDraft` | Kept in memory |
| Process death after `onStop` | Nothing to restore, because the committed text is already in the file | Not restored; the reopen is a fresh open |
| Process killed with no `onStop` (crash, kill while foreground) | Text typed since the last commit is lost; stated limit, checked by M-05 step 10 | Not restored |

This replaces the earlier S4 claim that a draft is restored after process death. UX-88 tests it.

---

## 4. UX acceptance criteria

Each criterion is testable by a human, on the named platform(s). IDs are for the validation phase.

### Inline editing (S1-S3)

- **UX-01** With the inline flag on, tapping a non-link cell opens that cell for editing in 1 tap with the caret in the cell; the block does not switch to raw markdown.
- **UX-02** With the inline flag on, tapping a link inside a non-editing cell opens the link and does not start editing (no regression of #721).
- **UX-03** Pressing Esc in an edited cell restores the original text; pressing Enter or Tab commits it. Neither writes a partial string (verify the file after each).
- **UX-04** After editing a cell and tapping outside the table, the rendered table shows the new value, and one Ctrl+Z restores the previous file content exactly.
- **UX-05** A touch drag over an inline table scrolls horizontally and never creates a range; the user can still copy a row in at most 3 taps (tap cell [enters `Edit`, IME and strip appear], Select row [draft committed], Copy).
- **UX-06** The action strip never covers the active cell, docks to the bottom of the visible area (on top of the IME when it is up) at the same position in `Navigate` and `Edit`, and is shown and hidden with no animation; it is visible in both modes on touch; every strip button, including Cancel, Close and Done, measures at least 48dp on a 360dp-wide phone.
- **UX-07** With both flags off, the table renders and responds to taps exactly as before (screenshot test `TableBlockScreenshotTest` unchanged; no strip, no Maximize button).
- **UX-08** "Edit as markdown" is reachable in at most 1 action from the strip (the pinned Markdown button is never scrolled off) and returns the current draft intact (nothing typed is lost).
- **UX-09** A 120-character cell, a wiki link cell and font scale 2.0 all wrap without clipping, on phone width and the web build.

### Maximized sheet (S4-S7)

- **UX-10** From any inline table, the sheet opens in exactly 1 action (Maximize button, or Ctrl+Shift+Enter when inline edit is on; with inline edit off the Maximize button is still shown whenever the sheet flag is on).
- **UX-11** At 360dp the sheet is full screen, sticky column letters and row numbers stay aligned with cells when scrolled right and down, and the header row stays visible.
- **UX-12** At 840dp and above the sheet is a dialog of about 90% of the window width; between 600 and 839dp it is full screen with an inline edit bar.
- **UX-13** On a phone, with the IME open, the edit bar and the active cell are both visible (neither covered by the IME).
- **UX-14** Folding or unfolding `Pixel_Fold`, rotating, or resizing the window keeps selection, scroll offset, view state and a half-typed cell edit.
- **UX-15** Closing the sheet by (x), by Back from `Navigate` on a single cell (section 3.3), or by Ctrl/Cmd+Shift+Enter, commits any pending draft and the page shows the new value without reopening; no draft is lost. Esc does not close the sheet (UX-93).
- **UX-16** A user can complete "fix one cell and close" from the inline table within a step budget per input type, counting one navigation sequence or chord as one step: touch at most 5 steps (open the sheet, tap the cell, tap it again to edit, type and press the soft keyboard action key, which also hides the IME, close); pointer at most 4 (Maximize, double-click the cell, type and Enter, close); keyboard at most 4 (Ctrl+Shift+Enter, arrow to the cell, type and Enter, Ctrl+Shift+Enter to close). Each budget has its own test.
- **UX-17** Entering text into a 200 x 12 table scrolls and edits without visible jank on a mid-range phone (no dropped-frame stutter by eye; frame-time probe if available).
- **UX-18** With keyboard only, a user can open the sheet, move with arrows, edit a cell, copy a range, and close, without a pointer.
- **UX-19** The edit bar shows the literal `=SUM(B2:B5)` while the cell shows the value; formula cells carry an `fx` marker that distinguishes `5` from `=2+3` without relying on color.
- **UX-20** While a formula is being edited and the point-mode rule allows it (caret after `=`, an operator, `(` or `,`), tapping a cell inserts its reference (`=SUM(` plus tap `B2` then Extend down 3 yields `=SUM(B2:B5`) and focus stays in the field; with any other caret position a tap commits and moves instead (indicator, escape and literal `=` text: UX-90).

### Selection, clipboard and paste (S3, S10, S11)

- **UX-21** On the sheet, a long-press enters range mode with two handles; a handle drag changes the range; the strip's "Extend..." picker achieves the same without dragging.
- **UX-22** Handles have a 48dp hit area (draw 12dp); dragging a handle by touch inside 48dp of its center works.
- **UX-23** Copying a range then pasting into Google Sheets or Excel yields one cell per grid cell (TSV); copying a formula range pastes values externally and restores formulas when pasted back in the same app.
- **UX-24** Pasting an 8 x 5 block into a 3 x 3 table grows the table, shows "Pasted 8 x 5, added 7 rows, 3 columns" with Undo, and one Ctrl+Z restores the original.
- **UX-25** Pasting one copied cell onto a 3-cell selection fills all three.
- **UX-26** A paste larger than 10,000 cells is rejected with the stated message and changes nothing.
- **UX-27** Cells containing `|` or a newline paste as `\|` and `<br>`, and the user sees a notice that content was transformed.
- **UX-28** On web, if clipboard read is denied, a "Paste here" box appears with focus in it; Cancel returns to the grid with no change.
- **UX-29** The status line shows "Count 2  Sum 8  Average 4" for cells `3`, `5`, `x`, and is absent when the range has no numbers.

### Structure, sort, resize, alignment (S8, S9)

- **UX-30** Tapping a column letter opens a menu with sort, align, insert left/right, move left/right, delete, width presets and freeze; tapping a row number opens insert above/below, move up/down, duplicate, delete.
- **UX-31** Every structural command is 1 undo step (including formula reference rewrites), restoring the exact prior file text.
- **UX-32** The sort items read "Sort rows A-Z (changes file)" and "Sort rows Z-A (changes file)" (the same strings as the TalkBack custom actions); after sort, the header row is unchanged, the live region says "Sorted by <column>" (plus the formula and blank-cell suffixes of S18 when their counts are above 0), and one undo restores the order.
- **UX-33** Align right writes `---:` for only that column's delimiter cell; no other line changes; undo restores it.
- **UX-34** Column width can be set both by dragging the header edge and by the Narrow, Medium, Wide, Auto-fit presets; neither writes to the file (file unchanged after resize).
- **UX-35** Closing and reopening the sheet resets widths to auto-fit, and the overflow menu says "Column widths reset when you close the sheet".
- **UX-36** Deleting the last column is refused with "A table needs at least one column", and the header row cannot be deleted or moved (disabled with reason).
- **UX-37** Deleting a column that formulas reference shows `#REF!` in those cells, a notice with count, and an Undo.

### Errors, read-only, conflicts (S12-S16)

- **UX-38** Each formula error shows its code (`#PARSE!`, `#REF!`, `#CYCLE!`, `#DIV/0!`, `#VALUE!`, `#NAME?`, `#LIMIT!`) plus a plain-language detail text on hover, keyboard or screen-reader focus, selecting the cell (sheet edit bar) or the Why? action (inline strip); color is not the only signal and long-press is not required.
- **UX-39** A cycle names the path ("Circular reference B2 -> C2 -> B2") and every cell in the loop shows `#CYCLE!`.
- **UX-40** No formula error crashes or blocks editing; the source text can always be opened for edit and fixed in 1 action.
- **UX-41** When the sheet host is read-only (no trigger exists yet, ADR-006), select and copy work, and every edit control is disabled with the visible reason "This graph is read-only"; typing on a cell shows that reason rather than doing nothing. On touch a tap on a cell selects it (no IME) and shows the strip with the reason line; long-press keeps its normal meaning.
- **UX-42** When the file changes on disk with no draft, the grid refreshes, keeps the selection by (row, col) and shows "Updated from disk".
- **UX-43** When the file changes on disk during an edit, the sheet's own banner offers Keep mine, Take theirs and Edit as markdown; the draft is never silently discarded, and Back does not drop the draft.
- **UX-44** An unparseable table shows today's rendered fallback with the reason and an "Edit as markdown" action; the grid editor is not entered.
- **UX-45** A commit failure shows "Couldn't save the change" with Retry; the draft remains until retry succeeds or the user opens markdown.
- **UX-46** Toggling a flag off while a cell is being edited commits the draft first and returns to today's behaviour with no lost text.

### Exit paths and no dead ends

- **UX-47** For every rung of the section 3.3 ladder on both surfaces (conflict banner, menus and panels, IME, `Edit` with the IME hidden, range, inline strip, sheet), the documented exit works with keyboard, pointer and Android Back, and none leaves focus trapped (WCAG 2.1.2). Esc in sheet `Navigate` on a single cell is a no-op and not a trap: (x), Back and Ctrl/Cmd+Shift+Enter close.
- **UX-48** "Edit as markdown" is reachable from the inline strip, the sheet top-bar overflow, and Shift+F2, in every state including error, read-only and conflict.
- **UX-49** Every error and notice in this document offers at least one next action (Undo, Retry, Dismiss, Edit, Keep mine / Take theirs / Edit as markdown); no error is a dead end.

### Accessibility

- **UX-50** All controls reachable by Tab or arrows and activatable by Enter/Space with a visible focus ring (outline contrast at least 3:1, WCAG 1.4.11); Tab enters the grid once and leaves once.
- **UX-51** Text and error colors meet at least 4.5:1 contrast in light and dark modes; selection and focus outlines meet at least 3:1.
- **UX-52** TalkBack announces "row N, column X" and grid size ("12 columns, 200 rows") using full counts; formula cells read the value first and the formula as state; error cells read the error message.
- **UX-53** Every drag (resize, reorder, range handles) and every shortcut has a non-drag, non-keyboard-only alternative in a menu, the strip or a TalkBack custom action (WCAG 2.5.7).
- **UX-54** Paste, copy, sort, undo and external-update results are announced once through the polite live region; no announcement fires while typing.
- **UX-55** All interactive targets on touch are at least 48dp (visual size may be smaller) and the layout is usable at 360dp width and font scale 2.0 without horizontal page scroll (the table itself scrolls).
- **UX-56** On the web build, the grid is checked against the browser accessibility tree; if it is not exposed, "Edit as markdown" is a plain text field reachable by keyboard.

### Iteration 5 additions (touch rule, assistive technology, states, discoverability, conflict)

- **UX-57** On a touch entry, with a cell in `Edit` and the IME open, the strip is visible, docked to the bottom of the visible area directly on top of the IME, its first two controls are Cancel edit and Done (in that order), and the active cell is still visible above it. The decision is made once when the edit starts: a hardware key pressed mid-edit does not hide the strip, and a mouse or hardware-key entry shows no strip during `Edit` (it shows in `Navigate`), except while a held conflict needs it (UX-84).
- **UX-58** Done commits the draft (a no-op if unchanged) and lands in `Navigate` on the same cell; Cancel edit (always present) restores the original text and lands in `Navigate`, posting the Undo notice only when the draft differed; Android Back with the IME hidden behaves as Done and never discards a draft; Back with the IME visible only hides it. Select row, Select column, Select table, Extend, Copy, Maximize and Markdown each commit the draft exactly once, then act; Undo and Redo in `Edit` are field-scoped and commit nothing (UX-86).
- **UX-59** From the inline table at rest on a phone, "copy a row" takes exactly 3 taps (cell, Select row, Copy), and "fix one cell and keep working" needs no hardware key at any step.
- **UX-60** A screen-reader position exists for every platform in section 3.7 (supported and verified, supported and unverified, or explicit non-goal with the reason), and the final report states it.
- **UX-61** Error detail is reachable by keyboard focus (status line and edit bar), by hover (dismissible with Esc and hoverable), by selecting the cell in the sheet, and by the Why? action inline; no detail is hover-only or long-press-only (WCAG 1.4.13).
- **UX-62** Every message that offers an action stays until the user acts or dismisses it; informational messages auto-dismiss after 10 s and the timer pauses on hover and on focus (WCAG 2.2.1). "Discarded your edit" and paste Undo are reachable for as long as the notice shows and by Ctrl+Z.
- **UX-63** At 320 CSS px width on web there is no page-level horizontal scroll (the grid scrolls), and with the WCAG text-spacing overrides applied no cell text clips (WCAG 1.4.10, 1.4.12).
- **UX-64** With forced colors or high contrast on (web, Android), selection, focus, error and `fx` markers remain distinguishable without relying on fill color; with reduced motion on, no scroll or notice animation runs.
- **UX-65** Under a right-to-left layout direction, column 1 is at the right, Left/Right arrows and Home/End mirror, and copy/TSV order is unchanged.
- **UX-66** Opening a table over 1,000 cells shows the dialog chrome and an "Opening table..." indicator at once, with Close enabled; a commit pending over 300 ms shows "Saving..."; neither flashes for fast cases.
- **UX-67** Enabling a flag shows the one-line note and the one-time tip on the first table; each hint shows at most once, never takes focus, and "Show tips again" re-enables them.
- **UX-68** In the conflict banner Back or Esc dismisses it only: the sheet stays open, the draft stays in its cell, nothing is written, and a persistent Resolve chip reopens the banner.
- **UX-69** Choosing Edit as markdown, or closing the sheet, while a conflict is unresolved never loses the draft: a persistent `RecoveredDraft` notice offers Copy, Apply to cell and Discard until one is chosen.

### Iteration 6 additions (triad repair 2: undo, Back ladder, touch inline flow, inline accessibility)

- **UX-70** Undo and Redo are controls on every touch surface: the sheet top bar, the sheet context strip and the inline strip (and the first two overflow items at 320 CSS px). The sheet range strip (S10) leads with them too. Each is 48dp with a text label that names the step ("Undo: edit B3"), is dimmed (not removed) when the table's own `TableUndoScope` depth (sheet and inline alike) has nothing, stays focusable, and activating a dimmed one shows "Nothing more to undo in this table" or the redo equivalent. An enabled press changes exactly one undo entry of this table and announces "Undone" or "Redone"; scope and labels are UX-85.
- **UX-71** The section 3.3 ladder is the only Back and Esc behaviour: for each rung (conflict banner, menu or panel, IME visible, `Edit` with the IME hidden, range, inline strip, sheet) on each surface, one press performs exactly that rung's action, nothing else. In particular Back in sheet `Edit` with the IME hidden is Done and does not close the sheet, and Back in inline `Navigate` collapses a range, then closes the strip, and only then reaches the platform. Esc in sheet `Navigate` on a single cell is not handled and never closes the sheet (UX-93).
- **UX-72** The touch strip has the same dock position and the same relative action order in `Navigate` and `Edit`, appears and disappears without animation, and in landscape the cell field never lets the IME take the whole screen (extract UI off); if cell and strip cannot both fit it collapses to Cancel edit or Close, Done, the pinned Markdown button and More.
- **UX-73** Strip visibility during `Edit` is decided once at entry and does not change for that edit: touch, TalkBack and Switch Access entries show it, mouse and hardware-key entries do not, a Bluetooth keyboard key pressed mid-edit changes nothing, and neither does the placement (UX-91).
- **UX-74** The strip's first slot has a fixed width and is always present: **Close** in `Navigate` (collapses a range or hides the strip) and **Cancel edit** in `Edit`, left of **Done**, with different icons and different words, and its label never changes while the user types. Cancel edit restores the original and posts "Discarded your edit" with an Undo that restores the draft only when the draft differs; with an unchanged draft it just leaves `Edit`. The phone edit bar's Cancel edit follows the same rule and is distinct from the top bar's Close table.
- **UX-75** There is one Extend mode inline: the Extend... picker (no "tap extends"). A formula reference can be built without dragging (tap a cell in point mode, then Extend...), and point mode applies only after `=`, an operator, `(` or `,`; any other tap commits (armed state, indicator and escape: UX-90).
- **UX-76** Error detail has a touch path that is not long-press: selecting the error cell shows the reason in the sheet's edit bar and status line, and the inline strip shows Why? for an error cell.
- **UX-77** While the page is in block selection mode a tap on a table cell toggles block selection and does not edit, and entering block selection from a cell commits any draft and hides the strip.
- **UX-78** Notice, strip, IME and hint chip stack in the order of design rule 13: a notice beats a hint chip, the active cell stays visible above the whole stack, hints are suppressed in landscape, and the Maximize button is not drawn while the strip is visible.
- **UX-79** A held inline draft is never silent: the strip shows a Resolve chip while `ConflictKeepDraft` is unresolved (in every entry profile, UX-84), commits are held, Resolve opens the banner, and Back or Esc dismisses it with the chip and draft kept.
- **UX-80** The inline table exposes `table` semantics with an "Edit cell" action, the custom actions of section 3.6b, focus entry on activation with one "Editing B3, <text>" announcement, and the strip as its own traversal group after the table.
- **UX-81** Web and Desktop screen-reader positions (section 3.7) are an acceptance gate: the sheet flag does not flip to default-on for a platform whose row is blank.
- **UX-82** Alt+Enter and Tab have touch equivalents: the soft keyboard action key is Enter (commit, move down), the strip and the phone edit bar offer **Insert line break** (Alt+Enter) and **Next column** (Tab), and no touch path needs a hardware key.
- **UX-83** Phone vertical budget (360 x 780dp, IME about 40 to 50% of the height): on the sheet, top bar plus edit bar plus context strip plus column header leave the active row and at least one neighbouring row fully visible above the IME; on the inline table, strip plus one notice leave the active row and one more row visible. The render tests assert the measured bounds, not only a PNG.

### Iteration 7 additions (triad repair 3: held conflicts, undo scope, field undo, notices, draft survival, sheet Esc, point mode)

- **UX-84** A held conflict is visible and holds every commit path in every profile (design rule 15). With `ConflictKeepDraft` set during a mouse or hardware-key `Edit`, the reduced strip (Resolve, Cancel edit, Markdown) appears below the table without taking focus and one polite message is announced. Enter, Tab, Shift+Enter, the soft keyboard action key, Done, Next column, a tap on another cell, a tap outside and every strip action except Resolve, Cancel edit and Markdown commit nothing, move nothing, keep the draft and re-show the banner. Back or Esc dismisses the banner only (strip and draft stay); a following Esc is Cancel edit with its Undo notice. No key sequence loses the draft.
- **UX-85** Undo and Redo in `Navigate` are scoped to this table's own content-changing commits and never reach another block: inline depth starts at 0 when the strip appears and resets when it hides or when a foreign entry reaches the page stack (Undo then dims with the notice); the sheet depth starts at 0 on every open. The text names the step ("Undo: edit B3", "Redo: paste"), identically on the sheet top bar, context strip, range strip and inline strip, and is the accessibility label.
- **UX-86** In `Edit`, Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y and the strip and edit-bar Undo and Redo revert or redo one step of the cell's draft only: they never commit and never touch the document. They are dimmed with "Nothing more to undo in this cell" when the draft history is empty. After Done, Undo names the commit ("Undo: edit B3") and reverts it.
- **UX-87** Notices obey the queue rule of section 3.10: one visible, at most two lines (one in landscape) and at most 25% of the space above the IME; informational notices replace each other and are not queued behind an action-bearing one; action-bearing notices queue first-in first-out with a "1 more" control and none is dropped; Retry jumps the queue; `RecoveredDraft` and the Resolve chip are pinned outside it; undo-bearing notices past 5 retire into the Undo control without losing the step. A hint deferred by a notice shows at the next idle moment and is dropped for the session after 3 deferrals without being marked seen.
- **UX-88** A configuration change (rotation, fold, resize) keeps the uncommitted draft; backgrounding the app or hiding the tab commits it once (one undo step, a no-op if unchanged), except a conflict-held draft, which becomes the session `RecoveredDraft`; after process death the committed text is in the file and the selection is not restored.
- **UX-89** Enter on the last body row commits and stays on the same cell in `Navigate` (no row is appended) and announces "End of table" once. On touch in the sheet, Enter or the soft keyboard action key commits, moves down, hides the IME and leaves the edit bar showing the next cell's literal text; a tap on the selected cell edits it. The fixed first strip slot never changes label or width while the user types, and Close table, Close and Cancel edit have different icons and words.
- **UX-90** While formula point mode is armed an indicator "Tap a cell to insert its reference" shows (after an insert: "Inserted B2. Extend... or type to continue") with a **Stop inserting** control that makes taps commit and move for the rest of the edit; typing, moving the caret or Done also disarms it, and Done, Enter and Tab always commit. Draft text that is not a valid formula prefix (`=5 apples`, `= total`, `==`) never arms point mode.
- **UX-91** The strip's placement is decided once with its visibility: a mouse click during a touch edit leaves it docked and a tap during a hardware edit neither shows nor moves it. **Markdown** (Edit as markdown) is pinned in the fixed trailing slot and is never scrolled off at 360dp. On Web, a `touch` or `pen` pointer type starts a `Touch` edit and `mouse` a `Hardware` one, and the strip fits without clipping at 390 px.
- **UX-92** The 3-tap copy-row flow (cell, Select row, Copy) shows the IME once and hides it once; Select row hides the IME with no animation and the table's scroll offset afterwards differs from before the first tap by at most the IME inset (no jump), and the active row never leaves the screen. Checked on `Pixel_Fold` (M-05 step 19).
- **UX-93** Esc in sheet `Navigate` with a single cell is not handled and never closes the sheet; Back, the (x) "Close table" button and Ctrl/Cmd+Shift+Enter close it (pending draft committed, `flush()` first). Esc still collapses a range, cancels an edit and closes menus. No state traps focus, because those three closers always work.

Total UX acceptance criteria: 93 (UX-01 to UX-93).

## 5. Verification hooks (for plan Epic 8)

- Phone render: `TablePhoneRenderTest` (360dp, native graphics) screenshots for S1, S2 with IME inset, S4 at rest and with a selected range, S8 open, 120-char cell, font scale 2.0. The images are opened and reviewed per image; wireframes in this file are not evidence of layout. Each render test also asserts the vertical budget of UX-83 by measured node bounds (chrome height versus the space above the IME) and the strip dock position of UX-72, plus landscape (780 x 360dp) with the IME inset, and an inline `Edit` frame with strip, notice and hint chip stacked.
- Emulator: handle hit areas, IME avoidance, fold/unfold, TalkBack actions on `Pixel_Fold`, the 3-tap copy-row IME count and table jump (UX-92), backgrounding mid-edit (UX-88).
- Web: 390px and desktop widths, paste event path and fallback, accessibility tree check, the strip at 390 px with a `touch` and then a `mouse` pointer (UX-91).
- Keyboard: every row in `TableShortcutMap` has a test; the shortcuts panel is generated from the same list.

## 5b. UX success metrics and lightweight validation (iteration 5)

The design rests on hypotheses; these metrics and one small study test them. Targets are proposals (INFERRED, owner to confirm) and are compared against the Phase-0 baseline of plan Task 0.7a.

| ID | Metric | How measured | Proposed target |
|---|---|---|---|
| UXM-1 | Steps and seconds to change one cell, phone and desktop | Same stopwatch protocol as the baseline, flags on | At most half the baseline steps and time |
| UXM-2 | Task completion on 5 scripted tasks (fix a cell, copy a row, paste a Sheets TSV, add a column, sort) | 3-5 person study, Task 8.7a | Every task completed unaided by most participants; no step where 2 or more participants stall |
| UXM-3 | Fallback-to-raw rate | Local `Logger("TableUsage")` lines: share of table edits that go through Edit as markdown while the flags are on | Below 20% after two weeks of owner use |
| UXM-4 | Accidental-edit proxy | Share of inline commits followed by Undo within 5 s (same log) | Below 10% |
| UXM-5 | Hypothesis checks | Study observations | H-A inline tap=edit versus sheet tap=select split does not confuse; H-B conflict Back-dismisses is understood; H-C touch strip with Cancel left of Done (Android convention) and the Close control is found without prompting, and nobody reaches for a missing Esc or Undo key; H-D the table-scoped Undo that names its step ("Undo: edit B3") is understood and nobody expects it to reach another block |

Study (Task 8.7a, owner-gated): 3-5 participants (the owner plus 2-4 people who use notes on a phone), 20 minutes each, think-aloud, one phone and one desktop task set, observations recorded as notes. With this few people the output is a list of observed stalls and fixes, not statistics. A stall seen by 2 or more participants is fixed before any default flips.

---

## 6. Design items (resolved in iteration 4, plus one open check)

- Decided: Enter after a commit moves to the next cell down (Sheets behaviour); inline, that cell enters `Edit` (S2), in the sheet it is selected in `Navigate`.
- Decided (iteration 5, replaces the earlier "strip only when not editing"): on touch the strip shows whenever a cell is active, including during `Edit`, with Done and Cancel leading; strip actions commit first. This is design rule 10, reworded in iteration 6 (window-anchored, sticky, Close or Cancel edit in a fixed slot, then Done).
- Decided (iteration 5): conflict banner Back/Esc dismisses; draft kept; non-lossy `RecoveredDraft` path (S14).
- Open check (not a scope decision): hinge-avoidance API availability on the target module (INFERRED, verified in Task 8.2a; `[beyond-req]`).
- Decided (iteration 6): Undo and Redo are controls on the sheet top bar, the context strip and the inline strip (UX-70). One Back ladder (3.3), with Back in sheet `Edit` and IME hidden = Done. The touch strip is a window-anchored bottom bar with a fixed order, sticky for the whole edit, with Close and Cancel edit sharing one fixed-width slot (iteration 7 replaced the earlier Cancel-only-when-the-draft-differs rule; the Undo notice appears only when the draft differs) and Cancel left of Done. One Extend picker inline; point mode only after `=`, an operator, `(` or `,`; error detail via select-cell or Why?; the table is inert during block selection; stacking order notice over hint; inline Resolve chip; inline accessibility semantics; Web and Desktop screen-reader gate.
- Decided (iteration 7), one decision each: a held conflict shows the reduced strip in every profile and holds every commit key (rule 15); inline and sheet Undo are both scoped to the table's own commits with the step named in the label, the inline scope resets when the strip hides or a foreign entry arrives, the sheet scope on every open (rule 16b, c); Undo in `Edit` is field-scoped and never commits (rule 16a); notices follow one queue (3.10); a configuration change keeps the draft and backgrounding commits it, a conflict-held draft excepted (3.11); the first strip slot is fixed width and always present (Close in `Navigate`, Cancel edit in `Edit`) with distinct icons and words, and Markdown is pinned; Esc never closes the sheet, Back, (x) and Ctrl/Cmd+Shift+Enter do; Enter on the last row commits and stays; the sheet's IME hides after Enter on touch; point mode needs a valid formula prefix, shows an indicator and has Stop inserting; the strip placement is fixed per edit and Web touch is detected by pointer type; hints are dropped for the session after 3 deferrals; the read-only touch tap selects with a reason line.
