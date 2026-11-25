# ADR-005: Custom range selection and a single gesture owner per table

**Status**: Proposed (accepted when Tasks 0.4a, 4.2b and 5.4d are green)
**Date**: 2026-11-24

## Context
Commit `dc94048f63` (#240): two `detectTapGestures` recognizers on one pointer stream raced, each with its own long-press timer. Today `TableBlock.kt` has one `combinedClickable` for the whole table plus `WikiLinkText`'s own link recognizer. Platform `SelectionContainer` cannot select across a custom `Layout` of `WikiLinkText` cells and competes with block long-press-select. `BlockList.kt` and `PageView.kt` own block-level Ctrl+C/Ctrl+A/Esc when `isInSelectionMode`.

## Decision
- **Selection**: `GridSelection(anchor, focus)` rectangular model in the pure `table` package, shared by the inline and maximized views. It has its own TSV copy path (`TabularText`). Platform text selection is used only inside the single editing cell's `BasicTextField`.
- **One gesture owner**: a single `pointerInput` on the table (Task 4.2a) does one hit test (pointer position to cell, then link hit test inside that cell). No nested clickables. Precedence, defined in one place:
  1. Tap on a link in a non-editing cell: open it (shipped, #721).
  2. Tap on other cell text (flag on): edit that cell.
  3. Long-press on a non-editing cell: the existing block select (unchanged).
  4. Mouse drag or Shift+click after slop: range select (`PointerType.Mouse` only inline).
  5. Touch inline: no drag range; a non-drag action strip on the active cell offers Select row / Select column / Select table / Extend / Copy / Maximize.
  6. Maximized: long-press a cell enters range mode with two handles (48dp hit area, 12dp visual); header and row-gutter taps select whole columns and rows; keyboard Shift+arrows extend.
- Horizontal scroll stays the default for touch drags; a range drag starts only from a handle or after slop plus long-press (CMP-4279 note at `TableBlock.kt:48`).
- While a cell is in edit mode the grid does not handle Ctrl+C/V/X/A (the text field does). In navigation mode the table's `onPreviewKeyEvent` consumes them before `PageView`'s handlers (`PageView.kt:249-269`), which only act in block selection mode.
- Hit areas use dp via `LocalDensity` (`pxPerDp` convention), never raw pixels.

## Consequences
Positive: no recognizer race by construction; copy behaviour is testable without a platform. Negative: no free platform selection handles or magnifier across cells; inline touch range select needs the action strip.

## Alternatives rejected
`SelectionContainer` over the grid (rabbit hole, #240 class of bug); per-cell `clickable` (re-creates the race).

## Owner confirmation (iteration 4, pre-mortem P1-2)
Before Task 4.4a starts, the owner confirms the wording of the flag-on touch behaviour (tap edits the cell, long-press selects the block, the strip selects ranges, a touch drag never starts a range). Iteration 5 adds the strip rule to the wording: on touch the strip is visible during `Edit` (leading Cancel edit and Done, docked above the IME) and every other strip action commits the draft first (ux.md design rule 10). Iteration 7 adds three rules to the same wording: a held conflict shows the reduced strip (Resolve, Cancel edit, Markdown) for every entry profile and holds every commit key (ux.md rule 15); the strip's first slot is fixed width and always present, Close in `Navigate` and Cancel edit in `Edit`; Esc never closes the maximized sheet (Back, the close button and Ctrl/Cmd+Shift+Enter do). Status: PENDING. Touch drag delegates to platform text selection only inside the editing cell (the #240 lesson), asserted by `touchDrag_should_StartNoGridSelection_When_FlagOn`.
