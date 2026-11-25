# Architecture research: table-sheet-editor

Paths are under `kmp/src/commonMain/kotlin/dev/stapler/stelekit/` unless noted. Line numbers are from this worktree (HEAD 071698a27b). Labels: VERIFIED = opened or run; INFERRED = reasoned, not run.

## 1. Data flow today (VERIFIED)

Block content string -> UI:
1. `ui/components/BlockItem.kt:498` dispatches `is BlockType.Table -> TableBlock(content = block.content, onStartEditing, ...)`. A tap calls `onStartEditing`, which opens the raw-markdown text field for the whole block.
2. `ui/components/TableBlock.kt` calls `remember(content) { parseTableContent(content) }`, then lays out a shared-column grid (`TableGrid`, `TableCell`, `TableColumnLayout.kt`) and renders cells through `WikiLinkText`. The outer `Box` uses `combinedClickable`, so one click handler owns the whole table. This is the seam to replace for per-cell edit.
3. `ui/components/TableModel.kt` holds `TableData` (headers/alignments/rows/explicitAlignments), `splitTableRow` (handles `\|`, trims cells), `parseTableContent`. There is no serializer and no edit operations. It is a read-only projection and lossy by design: cell trimming discards padding, and the delimiter row is reduced to alignments.

UI -> disk:
4. `BlockStateManager.updateBlockContent(blockUuid, newContent, newVersion)` (`ui/state/BlockStateManager.kt:1064`) calls `applyBlockContentUpdate`, which records an undo entry and calls `applyContentChange`. That marks the block dirty, writes the DB through `writeContentOnly` (`DatabaseWriteActor`), updates the local `_blocks` map, and calls `queueDiskSave`.
5. `queueDiskSave` (`:1153`) debounces `graphWriter.queueSave(page, blocks, graphPath)` (`db/GraphWriter.kt`). That is the only sanctioned write path (requirements constraint; CLAUDE.md "never write markdown behind GraphWriter").
6. Load side: `parsing/BlockParser.kt` and `parsing/TableExtraction.kt` classify a table block. `src/commonTest/.../parser/TableRoundTripTest.kt` already asserts that compact delimiter rows and cell padding survive parse -> block content. So the markdown -> block-content -> markdown leg is byte-preserving. The new work only has to keep the block-content <-> cell-grid leg lossless.

Consequence: a table edit is just `updateBlockContent(uuid, TableSerializer.serialize(newModel), version)`. No new persistence path is needed.

## 2. Where a TableModel edit should live

Recommendation: a pure, Compose-free `sheet` package in `commonMain` with three layers. It does not live in `ui/components`.

```
table/                       (pure Kotlin, no androidx, no db/ui/git imports)
  TableDoc.kt         immutable: rows of Cell(raw: String), delimiter row spec, raw-line fidelity info
  TableParser.kt      content String -> TableDoc   (reuses splitTableRow semantics)
  TableSerializer.kt  TableDoc -> String
  TableEdit.kt        sealed edit ops: SetCell, InsertRow/Col, DeleteRow/Col, MoveRow/Col, Sort, SetAlignment, PasteRange
  TableClipboard.kt   TSV/CSV in and out (Sheets/Excel quoting)
sheet/formula/               (pure)
  FormulaParser, FormulaAst, DependencyGraph, Evaluator, RefRewriter
```

Round-trip guarantee design (the hard part, INFERRED from TableModel.kt):
- `TableDoc` keeps each source line's original text plus a per-cell "dirty" flag. `serialize` re-emits the original line verbatim for every untouched row and re-renders only touched rows. This gives "a table the app never edited is byte-identical" without needing a perfect canonical form.
- Cells store raw text with escapes already removed. The serializer re-escapes `|` as `\|` and a newline as `<br>`. `splitTableRow` currently trims, so the parser must retain the original padding on the line (a row-level raw string) rather than re-deriving it.
- Structural edits (insert/delete/move column) change every row, so they re-render those rows. They should pad using the table's existing padding style, detected from the first row, so diffs stay small.
- Property tests (commonTest, kotest `Arb`/`checkAll` in `runTest` per CLAUDE.md): (a) `serialize(parse(s)) == s` for generated untouched tables, including empty cells, `\|`, ragged rows, and compact and padded delimiters; (b) `parse(serialize(edit(doc)))` equals `edit(doc)`; (c) untouched cells are unchanged by any edit op.
- Reuse decision: `projectbridge/TableExtraction` and `parsing/TableExtraction.kt` parse pipe tables too (backlog `docs/tasks/rich-table-editor.md` acceptance #5 asks for an ADR if shared). Recommend not sharing in this project. Put the new parser beside `splitTableRow`, and have `TableData`/`parseTableContent` become a thin view over `TableDoc` so the existing renderer and `TableModelTest` keep passing. Record the non-sharing in an ADR.

## 3. Undo/redo composition (VERIFIED)

`BlockUndoManager` (`ui/state/BlockUndoManager.kt:16`) is a closure stack: `record(undo: suspend () -> Unit, redo)`, max 100, redo cleared on new record. `BlockStateManager.record(...)` at `:602` exposes it. `applyBlockContentUpdate` already records a (old content, new content) pair per call.

Recommendation: one table edit = one `updateBlockContent` call = one undo entry, with whole-string snapshots. Reasons:
- It reuses the existing stack, so Ctrl+Z inside the maximized sheet and outside it are the same stack, and there is no second history to reconcile with block undo.
- Cell edits are committed on cell blur or Enter, not per keystroke, so a typed cell is one entry. Per-keystroke in-cell text undo stays with the platform text field while the cell is focused (same as today's block editor).
- Memory: a 200x12 table is a few tens of KB per snapshot, times 100 entries is acceptable. Bound it only if a measured case disagrees (INFERRED, not measured).
- Sort and structural edits are single entries, which answers the open question on sort needing undo.
- Risk: `applyBlockContentUpdate` early-returns when `oldContent == newContent` and drops no-op edits, which is correct. A sheet session that wants "group N edits into one undo" would need a `beginGroup/endGroup` on `BlockUndoManager`. That is not needed for v1; do not add it speculatively.

## 4. Formula layering (INFERRED design; closed grammar per Security constraint)

Layers, each independently testable in commonTest:
1. Parse: `=` prefix on raw cell text -> AST. Closed grammar: numbers, cell refs `A1`/`$A$1`, ranges `A1:B5`, `+ - * / ^`, unary minus, comparison, parentheses, and functions from a fixed allow-list (SUM, AVERAGE, MIN, MAX, COUNT, IF to start). No identifiers outside the allow-list, so there is no execution surface. Parse errors become a `CellError.Parse` value, not an exception.
2. Dependency graph: built from AST refs, stored as precedents (cell -> cells it reads) and dependents (reverse). Ranges are stored as rectangles, not expanded cells, so `SUM(A1:A5000)` is O(1) to register.
3. Incremental eval: on a cell change, compute the transitive dependents set, topologically order it, and recompute only those. Cache values by cell. This meets "no O(cells^2) per keystroke".
4. Cycle detection: Kahn's algorithm (or DFS colouring) over the dirty subgraph. Cells in a cycle get `CellError.Cycle`. Keep the cycle result out of the cache so an edit that breaks the cycle clears it.
5. Reference rewriting on structural edits: a pure function `RefRewriter.apply(ast, TableEdit)` shifts refs on insert/delete/move (Sheets behaviour; a ref to a deleted row/col becomes `#REF!`). It rewrites the cell text of every formula cell, so it composes inside the same single `TableEdit` -> one serialized string -> one undo entry. Sort rewrites refs only where Sheets does, which is the open question OQ-3 for the planner; default to "formulas move with their row, refs are left literal" and state it plainly.
6. Display: formula values are computed at render time and never persisted (requirement: literal `=SUM(B2:B5)` text, no cache). Number parsing is locale-independent (`.` decimal) in the engine; format on display only.

Because values are derived, the maximized sheet and the inline table both call `Evaluator` over the same `TableDoc`; there is no stored value to diverge.

## 5. Maximized view per platform (INFERRED; one existing precedent verified)

Precedent: the repo already uses Compose `Dialog` (`ui/components/CapturePreviewDialog.kt:42`, `SearchDialog.kt:102`) and an expect/actual composable (`CameraViewfinderDialog.kt:7`), and a `LocalOpenSketchAction` composition-local at `ui/components/AnnotateImageAction.kt:11` for "open a full editor from a block", used at `BlockItem.kt:526`.

Recommendation:
- A single `commonMain` `TableSheetScreen(blockUuid, ...)` hosted in a full-screen `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))` on all four targets, entered via a new `LocalOpenTableSheetAction` mirroring `LocalOpenSketchAction`. One code path means one test surface.
- Android: same dialog, which gives system Back dismissal for free; IME `adjustResize` needs an emulator pass (blocker already listed in requirements).
- Desktop: the dialog works, but a resizable panel or in-place expansion may feel better; defer unless the dialog proves cramped.
- Web (Wasm): Dialog is supported by Compose Web; the risk is clipboard and IME (already in requirements), not presentation.
- iOS: best effort, same dialog.
- Why not a route or in-place: a route would add navigation state and back-stack handling for a transient editor; in-place expansion fights `LazyColumn` item height and the existing `horizontalScroll` TODO (CMP-4279 at `TableBlock.kt:48`). Dialog avoids both. Lazy row composition inside the sheet uses its own `LazyColumn`.
- The sheet edits a local `TableDoc` and commits through `updateBlockContent` (debounced or on-edit); closing the dialog loses nothing because every edit already went through the block write path.

## 6. Manifest and layering constraints (VERIFIED)

- `kmp/modules_manifest.bzl` is generated by `scripts/kmp-modules-sync.py`; `ModuleManifestGuardTest` (`src/businessTest/.../audit/ModuleManifestGuardTest.kt`) requires every JVM source file to be in exactly one module, the graph to be acyclic, and no file to import a declaration outside its module's dependency closure.
- Today `TableBlock.kt`, `TableColumnLayout.kt`, `TableModel.kt`, `TablePreferences.kt`, and `BlockItem.kt` all sit in `ui_main` (manifest lines 1377-1380, 1305). `ui_main` deps: `sketch_runtime, transfer, ui_found` (224 srcs). `core` already owns `parsing/*` including `TableExtraction.kt` (line 31); `model` deps `core`.
- Placement options for the pure layer: (a) `core`, beside `parsing/`, no new module and no dependency risk, but `core` is the foundation module and adding ~10 files grows every module's compile closure; (b) a new `table` module with `deps: ["core"]`, which `ui_main` depends on. A new module needs a `modules.bzl`/BUILD entry, so choose it only if compile-isolation matters; the default is (a)'s smaller diff. INFERRED recommendation: package `dev.stapler.stelekit.table` in `core` first; split to its own module only if build time shows it. The planner should confirm by running `scripts/kmp-modules-sync.py --check`.
- Composable sheet UI goes in `ui_main` next to `TableBlock.kt` (it needs `WikiLinkText`, theme, composition locals).
- Import direction: the repo uses per-package import-direction tests (e.g. `JobsScheduleImportDirectionTest`, `SketchSolverImportDirectionTest`, `SketchV2EngineImportDirectionTest` in `src/businessTest`). Add `TableEngineImportDirectionTest`: `table/**` and `sheet/formula/**` must not import `ui`, `db`, `git`, `merge`, `androidx`. This is the structural guard that keeps the pure layer pure, and it follows the CLAUDE.md pattern for new layering rules.
- New test classes must be registered (`AllBusinessTests`/`AllJvmTests`/`AllAndroidUnitTests`; commonTest needs `kmp/src/commonTest/kotlin/BUILD.bazel`; `scripts/kmp-module-tests-sync.py`), per requirements. Pure tests go in `commonTest`; Robolectric cell-edit tests in `androidUnitTest`; Roborazzi only if pixels matter.
- Persisting column width/freeze/sort: any block property would be written by `LogseqPageSerializer` as a `key::` line and shown in Logseq; recommendation is session-only in-memory view state (a `TableViewState` held by the sheet), which also matches "no hidden state". Alignment is the exception because GFM `:---:` is the file format.

## 7. Hotspot evidence for BlockItem / TableBlock (VERIFIED, git only)

Ran `git log --since=6.months --oneline -- <file> | wc -l` (the `--name-only | uniq -c` form returns a path count per commit, so oneline count is the commit count):

| File | Lines | Commits, 6 months |
|---|---|---|
| `ui/components/BlockItem.kt` | 693 | 23 |
| `ui/state/BlockStateManager.kt` | n/a (large; undo and write path) | 32 |
| `ui/components/TableBlock.kt` | 269 | 5 |
| `ui/state/BlockUndoManager.kt` | ~70 | 0 |

Recent `BlockItem.kt` commits are cross-feature (tables x3, sketch x3, annotate, mermaid, queries, lasso-select, link insertion), so it is a dispatcher everyone touches. I did not run the full `code-hotspot-analysis` complexity x churn scoring; the above is churn plus size only. The `BlockItem` function begins at `:42`; its `when (BlockType)` branch at `:498` is the only coupling point the sheet needs.

### Tech-debt disposition for BlockItem: Isolate via seam

Not Refactor-first: the table work needs only the one `is BlockType.Table` branch (`:498`); refactoring a 23-commit dispatcher first would add risk and merge conflicts for no table benefit. Not plain Extend-as-is: the tap contract today is "tap = open raw editor" and it is wired through `onStartEditing`, so adding inline cell edit by threading more parameters through `BlockItem` would deepen the hotspot. Concretely:
- Add one seam: `TableBlock` gains its own edit state and takes `onContentChange: (String) -> Unit` and `onOpenSheet: () -> Unit` (the latter from a composition local like `LocalOpenSketchAction`), so `BlockItem` grows by a couple of lines, not by cell logic.
- Keep `onStartEditing` as the raw-markdown escape hatch (required by Risk Control: raw edit always reachable).
- All new logic lives in the new `table` package and a new `ui/components/sheet/` composable set; `BlockItem.kt` is touched in exactly one branch.
- Gate by a settings toggle (Risk Control) read inside `TableBlock`, so the flag-off path is the current behaviour.

## 8. Open items for planning (not decided here)
- OQ-3 formula behaviour under sort (default stated in section 4).
- Whether `table/` goes in `core` or a new module (section 6).
- Selection model: a custom range selection with its own copy path is recommended over platform text selection, because platform selection containers across many `WikiLinkText` cells are the known rabbit hole (#240). Not researched further here.
- Not examined: Wasm clipboard APIs, IME inside cells, third-party KMP grid libraries (the other research tracks).
