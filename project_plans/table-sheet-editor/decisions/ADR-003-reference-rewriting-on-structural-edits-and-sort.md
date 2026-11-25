# ADR-003: Reference behaviour on structural edits, move and sort

**Status**: Proposed (accepted when the structural-invariance property in Task 2.4b is green)
**Date**: 2026-11-24

## Context
Open question OQ-3: shift references automatically (Sheets) or leave literal. Pitfalls section 7: regex rewriting corrupts strings and function names; edits and rewrites must be one atomic step for undo.

## Decision
Rewrite references through the formula AST (`RefRewriter`), never by regex, and inside the same `TableEdit` application, so one edit yields one serialized string and one undo entry. The rewrite is a token splice: only reference spans whose address changes are replaced (deleted refs become the `#REF!` token); a formula with no changed reference is left byte-verbatim (`=sum( b2:b5 )`, `=007+1`, `1.50` survive) and does not dirty its `RowLine`. `RefRewriter` lives in `table.formula` and implements the `FormulaReferenceTransform` port declared in `table`, which `TableEditor` receives by constructor, so `table` never imports `table.formula`. Anchors are `before` everywhere (`InsertRows(before=N)`).

| Edit | Reference behaviour |
|---|---|
| Insert row/column | References at or after the insertion index shift by one (insert at a referenced row shifts it). A range spanning the insertion grows. |
| Delete row/column | References after shift down. A range loses the deleted rows (shrinks); a range whose rows are all deleted, and a single reference to a deleted row, become the literal token `#REF!` in the stored text (`=SUM(#REF!)`), which parses to a ref-error node and evaluates to `#REF!`. |
| Move row/column (reorder) | References follow the data (permutation map). A range whose endpoints swap order is normalized to min:max. |
| Sort rows (body only, header excluded) | By default the trailing totals rows are excluded. A trailing totals row is detected by dependency, not by `SUM` shape: a trailing body row with at least one formula whose references (single cells or range endpoints) include a cell in a different body row, all of those rows being above it; the run extends upward while each row qualifies, so `=B2+B3+B4`, `=AVERAGE(B2:B4)` and `=B2` all count and a row-local `=B5*C5` does not. Sort rewrites only single-cell references into the formula's own row and never rewrites ranges; a range that touches the own row and others but not the own cell stays literal, and a permutation that would put a moved formula's own cell inside its range is refused. Rows excluded this way stay at the bottom, so `=SUM(B2:B5)` in row 6 never lands inside its own range; with a multi-row selection only the selected body rows are sorted. If, after the permutation, a moved formula's range would contain its own new row, the sort is `Refused(SortWouldSelfReference)` and nothing is written. Formulas move with their row. References that point into the formula's own row follow the row (rewritten to the new row). All other references stay literal, as Sheets and Excel do. The edit result carries `EditSummary.Sorted(column, blankCount, rewrittenFormulaCount, crossRowFormulaCount)`; the announcement is "Sorted by Qty" plus ". N formulas reference other rows" and ". N blank cells sorted last" when those counts are above 0, and the sort menu shows "N formulas will be rewritten" before the user confirms. Iteration 4 (pre-mortem P2-3): a moved formula that is not in a detected totals row and holds a single-cell reference to another body row is not guessed at; the sort is `Refused(SortWouldChangeValues)`, and a property test asserts that every unmoved formula with order-insensitive operands keeps its computed value after an `Applied` sort. |
| Paste | References in pasted formula text are not rewritten (v1: literal). A paste from the in-app clipboard slot keeps formula text verbatim. |
| `$` absolute markers | Parsed and preserved on print. Structural edits shift absolute and relative references alike (Sheets); only copy/fill differs, and fill is out of scope. |

Sort rules: stable; sort key is the computed value; numbers before text, text compared case-insensitively; blanks last and errors last in both ascending and descending order (Sheets behaviour); ties keep prior order in both directions.

## Consequences
Predictable, testable semantics; the sort rule keeps the common "per-row total" pattern (`=B3*C3`) correct after a sort. A cross-row reference can silently point at new data after a sort; the announcement count is the only warning (a modal would be noise).

## Alternatives rejected
Leave all references literal (silently wrong after any insert); refuse sort when any formula references a moved row (blocks the common totals table); rewrite on sort as if refs were data pointers (breaks Excel/Sheets muscle memory).
