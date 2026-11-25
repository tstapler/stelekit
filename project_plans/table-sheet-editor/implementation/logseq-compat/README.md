# Logseq compatibility fixtures and owner checklist

Owner hand-off for Tasks 8.4b (untouched fixtures, W2) and 8.4a (copies edited by the app, W5).
The `0N-*.md` files are test inputs for `LogseqCompatFixtureTest` (Task 1.1c), not documentation; do not reformat them.
`09-crlf.md` has CRLF line endings on purpose.

## How to run

1. Copy the 9 fixture files into a scratch Logseq graph's `pages/` folder (never a real graph).
2. Open each page in Logseq (desktop and, if available, mobile) and fill in the table below.
3. For Task 8.4a, repeat with copies of each fixture after SteleKit has edited one data row, and record the edited-copy column.
4. Save the results to `results.md` in this folder and record confirmed or amended in ADR-007.

## Per-fixture checks

Every fixture: it renders as a table (not as plain text), and the edited row looks right in the edited copy (8.4a).

| Fixture | What to check in Logseq |
|---|---|
| 01-padded-cells | Renders as a table; padding spaces do not show. |
| 02-compact-delimiter | Renders as a table despite the `\|-\|` delimiter row. |
| 03-alignment | Left, center and right alignment are honoured; the default column is left. |
| 04-br-variants | All four variants (`<br>`, `<br/>`, `<BR>`, `<br />`) render as a line break inside the cell. |
| 05-formula-sum | `=SUM(B2:B5)` shows as literal text. |
| 06-ragged-rows | Renders as a table; record how short and long rows look. |
| 07-formula-star | `=B2*C2*D2` shows as literal text. If the `*` run is turned into italics, record exactly what is shown (SteleKit's flag-off inline-markdown path may do the same). |
| 08-escaped-pipes-empty-cells | `a \| b` shows as `a \| b` in one cell; empty cells stay empty; row 3 shows the literal text `literal \|` (record what Logseq shows). |
| 09-crlf | Renders as a table, same as the LF version. |

## Results

| Fixture | Untouched (8.4b): ok / note | Edited copy (8.4a): ok / note |
|---|---|---|
| 01-padded-cells | | |
| 02-compact-delimiter | | |
| 03-alignment | | |
| 04-br-variants | | |
| 05-formula-sum | | |
| 06-ragged-rows | | |
| 07-formula-star | | |
| 08-escaped-pipes-empty-cells | | |
| 09-crlf | | |
