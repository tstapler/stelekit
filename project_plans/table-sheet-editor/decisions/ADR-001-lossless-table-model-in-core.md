# ADR-001: Pure lossless table model in `core`, re-emitting untouched rows verbatim

**Status**: Proposed (accepted when Task 1.1c round-trip properties are green)
**Date**: 2026-11-24

## Context
`ui/components/TableModel.kt` is a lossy, parse-only projection: it trims cells, drops cells beyond the header count, discards blank lines, and reduces the delimiter row to an alignment enum. There is no serializer (research/stack.md section 2, pitfalls.md section 0). The requirement "a table the app never edited is byte-identical after load/save" cannot be met by `serialize(parse(raw))`. `parsing/TableExtraction.kt` and `projectbridge/TableExtraction` parse pipe tables too and disagree with `splitTableRow` on `\|`.

## Decision
- New package `dev.stapler.stelekit.table` (pure Kotlin: no `androidx`, `ui`, `db`, `git`, `merge`). Placed in module `core` (`kmp/modules_manifest.bzl`), not a new module: `core` already owns `parsing/*`, the dependency closure is unchanged, and `table/` is a new directory, so `scripts/kmp-modules-sync.py` reports it as an orphan and the files are placed by hand (Task 1.4a, in the same commit as the files; `ui/components/sheet/` in `ui_main` is placed the same way). Revisit a split only if build time shows it.
- `TableDoc` keeps, per source line, the original text plus a dirty flag, and keeps the verbatim prefix and suffix lines of the block (property lines, text before or after the table), the line-ending style, the leading/trailing-pipe style and the padding style detected from the first row. `TableSerializer` re-emits every untouched line byte for byte and re-renders only dirty rows. A structural edit re-renders every row it touches in the detected style (no column padding, so Unicode width never matters).
- Each `RowLine` also keeps the raw source text of every cell (`rawCells`). Cells hold unescaped text; the serializer escapes `|` as `\|` and a newline as `<br>` only for cells whose text changed; the other cells of a dirty row re-emit `rawCells`, so `<br/>`, `<BR>`, `<br />` and a literal `\\|` in untouched cells survive an edit of a neighbouring cell. `TableDoc.lineIndexOf(row)` is the one mapping from a table row (header = 1, delimiter not a row) to a source line. A cell ending in a backslash is rendered with a trailing space guard rather than merged with the closing pipe.
- Ragged rows: extra cells of an untouched row survive because the row is re-emitted verbatim; an edited ragged row keeps its extra cells and the sheet shows them as columns beyond the header (never silent loss).
- `TableData`/`parseTableContent` become a thin view over `TableDoc` so `TableModelTest`, `ParseTableContentTest` and `TableBlockLayoutTest` keep passing unchanged.
- We do not share the parser with `parsing/TableExtraction.kt` or `projectbridge/TableExtraction` (their disagreement on `\|` is the reason). The editor uses `splitTableRow` semantics.
- `TableEngineImportDirectionTest` (businessTest) fails if `table/**` imports `ui`, `db`, `git`, `merge` or `androidx`, and if package `table` imports `table.formula` (direction is `table.formula` -> `table`; `TableEditor` receives a `FormulaReferenceTransform` port, see ADR-003).
- Edit results are a sealed `TableEditOutcome` (`Applied(doc, EditSummary)` or `Refused(RefusalReason)`), carrying structured data only; user-facing strings are mapped in `ui_main` (`TableMessages`).

## Consequences
- Positive: byte identity is structural, not a property of a canonical form; small git diffs; the engine runs identically on JVM, Android, iOS, Wasm and is tested once in commonTest.
- Negative: `core` grows about 30 files in every module's closure; two parsers remain in the repo (documented, not merged).
- Risk: an unusual block shape (properties inside the table block) is unknown until Task 0.1a characterizes `BlockType.Table` content.

## Alternatives rejected
- Normalize the whole table on first edit: noisy diffs, and Logseq users hand-format tables.
- A new `table` Bazel module: more manifest and BUILD work for no measured compile benefit.
- Extend `TableData` with raw spans in `ui_main`: keeps the engine inside the UI module and blocks pure-layer import guards.
