# Implementation Plan: table-sheet-editor

**Feature**: Inline per-cell editing and a maximized spreadsheet-style sheet (grid navigation, range select, TSV copy/paste, structural ops, resize, alignment, sort, freeze, formulas) over SteleKit's GFM pipe tables, with a byte-identical round trip.
**Date**: 2026-11-24
**Status**: Ready for implementation (repaired against architecture-review.md, adversarial-review.md, pre-mortem.md and consistency-review.md; see repair-log.md)
**ADRs**: ADR-001 lossless model in core; ADR-002 formula engine; ADR-003 reference rewriting and sort; ADR-004 undo and write path; ADR-005 selection and gesture owner; ADR-006 maximize via Dialog and BlockItem seam; ADR-007 session-only view state; ADR-008 clipboard port and copy semantics; ADR-009 shortcut map (all under `project_plans/table-sheet-editor/decisions/`)

Paths are under `kmp/src/<sourceSet>/kotlin/dev/stapler/stelekit/` unless a task says otherwise (`COMMON` = `commonMain`, `CTEST` = `kmp/src/commonTest/kotlin/dev/stapler/stelekit/`, `ATEST` = `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/`).

---

## Step 0.5 - Alternatives considered (CREATIVE pass)

| Approach | Strength | Weakness |
|---|---|---|
| A. Extend `TableData`/`TableBlock` in `ui_main` with edit ops and a serializer in place | Smallest diff, no new package | Engine stays entangled with Compose and `ui_main`; cannot be import-guarded; lossy `TableData` remains the model |
| B. Pure `table` package in `core` (lossless `TableDoc` + serializer + edit ops + formula engine) with thin Compose layers in `ui_main` | Byte identity is structural; engine tested once in commonTest on four targets; import-direction guard possible | More files and a hand-placed manifest entry |
| C. Treat the table as a miniature document: edit a markdown AST node via an existing editor model | Reuses outliner machinery | The outliner has no cell concept; round trip would still need a serializer; hides the formula and selection models in generic code |

**Chosen: B** (ADR-001). A and C rejected as recorded in Pattern Decisions.

---

## Domain Glossary
*(Exact names are used in code, tests and comments.)*

| Term | Definition | Notes |
|---|---|---|
| `TableDoc` | Immutable lossless model of one table block: verbatim prefix/suffix lines, `RowLine`s, `DelimiterRow`, style info | Package `table`; the only edit model |
| `RowLine` | One source line: original text, `rawCells: List<String>` (source text per cell, escapes and `<br>` variants as written), `List<CellText>` and a `dirty` flag | Untouched rows are re-emitted verbatim; in a dirty row only cells whose `CellText` changed are re-escaped, the rest re-emit `rawCells` byte-for-byte (`<br/>`, `<BR>`, `\|` survive) |
| `CellText` | Unescaped raw text of one cell (a formula is just `CellText` starting with `=`) | Value class over String |
| `DelimiterRow` | Original delimiter line plus per-column `ColumnAlign` | Only changed columns are regenerated |
| `ColumnAlign` | Enum `None / Left / Center / Right` | Maps to `TableColumnAlignment` in `ui_main` |
| `CellAddress` | `(row, col)`, 1-based, row 1 = header row; prints as `B2` | Value class; `A1` = first header cell. A row number is a table row, never a source-line number: the delimiter line is not a row. `TableDoc.lineIndexOf(row)` is the single mapping to a source line (it skips the delimiter) and has its own test. Anchor convention everywhere is `before`: `InsertRows(before=N)` inserts so the new row becomes row N |
| `CellRange` | Normalized rectangle of two `CellAddress` | Registered as a rectangle, never expanded |
| `RowKey` | Stable session id for a row, preserved through `TableEdit` | Lazy-list `key`; never the row index |
| `TableEdit` | Sealed interface: `SetCell`, `ClearRange`, `InsertRows/Cols(before, count)`, `DeleteRows/Cols`, `MoveRows/Cols`, `SetAlignment`, `SortRows(col, ascending, rows: RowRange?)`, `PasteRange` | One edit = one commit |
| `TableEditOutcome` | Sealed: `Applied(doc: TableDoc, summary: EditSummary)` or `Refused(reason: RefusalReason)` | `EditSummary` and `RefusalReason` are enums/data (kind, counts, column), no user-facing strings in `table`. `EditSummary` fields (so UX strings have a source): `Sorted(column, blankCount, rewrittenFormulaCount, crossRowFormulaCount)`, `Pasted(rows, cols, addedRows, addedCols, transformedCells)`, structural deletes carry `formulasBroken` (formula cells that newly contain `#REF!`); `RefusalReason` includes `HeaderRow`, `LastColumn`, `SortWouldSelfReference`, `SortWouldChangeValues`, `PasteTooLarge`; `ui/components/sheet/TableMessages.kt` maps them to announcements and notices. A caller commits only on `Applied` |
| `TableParser` / `TableSerializer` | `String -> TableDoc` / `TableDoc -> String` | Serializer re-emits untouched `RowLine`s verbatim |
| `TabularText` | TSV/CSV codec (RFC-4180-style, tab or comma delimiter) | Pure, property-tested |
| `FormulaShaped` | Raw text starting with `=` followed by an expression-start token: number, ref, function name, `(`, unary sign, quote, or the literal error token `#REF!` | Else the cell is literal text (ADR-002). Text containing `#REF!` after a delete (`=#REF!*2`) is still formula-shaped and re-parses to a `RefError` node |
| `Expr` | Sealed formula AST: `Num, Str, Ref, Range, Bin, Neg, Pct, Call, RefError` | Rewritten by `RefRewriter`, never regex |
| `CellValue` | Sealed: `Num(Double)`, `Text(String)`, `Blank`, `Err(CellError)` | Display only, never persisted |
| `CellError` | Sealed: `Ref, Cycle, DivZero, Value, Name, Parse, Limit` (`Limit` = per-formula read budget, ADR-002) | Prints `#REF!`, `#CYCLE!`, `#DIV/0!`, `#VALUE!`, `#NAME?`, `#PARSE!`, `#LIMIT!` |
| `TableCalc` | Facade over a `TableDoc`: parses formulas, keeps `DependencyGraph`, evaluates incrementally, exposes `evaluationCount` | `Throwable`-guarded boundary |
| `DependencyGraph` | Precedents/dependents over cells and range rectangles | Kahn order over the dirty subgraph |
| `RefRewriter` | Pure transform in `table.formula` applying a `TableEdit` to formula refs by token splice (ADR-003); implements `FormulaReferenceTransform` | Only reference spans that change are re-printed; a formula with no changed reference is returned as `null` (untouched, byte-identical, `RowLine` not dirtied by it). Includes sort own-row rule |
| `GridSelection` | Rectangular `anchor`/`focus` over `CellAddress`; whole row/column/table variants | Shared by inline and sheet |
| `GridMode` | `Navigate` or `Edit` | Esc leaves `Edit` first |
| `GridNavigator` | Pure function `(selection, key command, bounds) -> selection` | Tab/Enter/arrows/Home/End/PgUp/Ctrl+Arrow |
| `CellDraft` | Hoisted plain state (text, selection range, `composing: Boolean`) for the editing cell, keyed by `CellAddress`; no Compose type | Lives above the lazy item in `SheetState`; commits only on explicit triggers (Enter/Tab/arrow/blur to another cell, close, maximize, Edit as markdown, `onStop`), never on dispose |
| `TableViewState` | Session-only widths (dp), freeze flags, sort indicator | Never written (ADR-007) |
| `TableSyncPolicy` | Classifies each observed block `content` (a `String`; it takes no version parameter, so no version space can leak in) as `Echo`, `Stale`, `Adopt` (undo/redo of a known state), `Rebuild` or `ConflictKeepDraft` using an `OwnCommitLedger` | ADR-004. Observed flow: the sheet host collects the repository's `getBlockByUuid` (DB-row version space) and drops `version` at the host boundary |
| `OwnCommitLedger` | Content-keyed record (hash plus length, never the string) of every state this session loaded, committed, adopted or observed, each with a `seq` the ledger itself assigns from one monotonic counter, a state (`InFlight` / `Acked`), the `currentSeq` of the displayed doc, and 128 entries max (undo stack is 100, `BlockUndoManager(maxHistory = 100)`, plus redo and in-flight) | No DB version and no UI version is ever stored or compared. Evicted states read as external (`Rebuild`, or `ConflictKeepDraft` with a draft), which loses nothing because every rebuild uses the observed content. A hash collision only turns `Rebuild` into `Adopt`; both rebuild from the observed content |
| `TableGestureOwner` | The single `pointerInput` per table doing cell then link hit testing | ADR-005 |
| `TableClipboard` | Port: `copy(tsv, html?)`, `paste()` | ADR-008 |
| `TableClipboardSlot` | In-app raw-cell clipboard that preserves formula text | Used when pasted TSV equals slot TSV |
| `SelectionStats` | COUNT / SUM / AVERAGE of the numeric cells in a `GridSelection` | Shown in the sheet status line |
| `TableEditPrefs` | The two flags `inlineEditEnabled`, `sheetEnabled` carried by `TableDisplayPrefs` | Default false |
| `LocalOpenTableSheetAction` | `((BlockUuid) -> Unit)?` composition local, default null | Mirrors `LocalOpenSketchAction` |
| `TableSheetHost` | Shell-owned Dialog host for the open block | ADR-006 |
| `TableShortcutMap` | Pure list of key chord to command, shared by handler, tests and the shortcuts panel | ADR-009 |
| `FormulaReferenceTransform` | `fun interface` in `table` (`apply(cell: CellText, edit: TableEdit): CellText?`, null = unchanged) that `TableEditor` receives; `table.formula.RefRewriter` implements it | Breaks the `table` <-> `table.formula` cycle: `table.formula` depends on `table`, never the reverse |
| `TableCommitter` | One class in `ui/components/sheet/` that serializes, skips a commit whose string equals the current content, registers the commit in the `OwnCommitLedger` (new `seq`, `InFlight`) before issuing it, counts the sheet's own undo entries (`sheetUndoDepth`), and calls `LocalTableCommitAction` with a version lower bound of 0 | The only code that commits for the sheet host. It mints no version: when `restoreEditFocus = false` the manager stores `max(passedVersion, current.version + 1)` itself (Task 0.5a), in the UI space |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|---|---|---|---|---|
| Table model | Immutable value model with persistent edits (Domain Model, small) | PoEAA Fowler | Transaction Script over strings | Round-trip and undo need an inspectable structure |
| Block write path | Existing Unit-of-Work-like commit via `BlockStateManager.updateBlockContent` | PoEAA | New sheet persistence / second debounce | ADR-004: one stack, one writer |
| Edit operations | Command objects (`TableEdit` sealed) applied by one pure `TableEditor` | GoF Command | Per-op mutator methods on `TableDoc` | Undo label, announcements and ref rewriting hang off one value |
| Formula evaluation | Interpreter over a sealed AST (GoF Interpreter), iterative evaluator | GoF | `eval`-style or third-party engine | Closed set is the security boundary (ADR-002) |
| Function set | Table-driven registry (allow-list) | Strategy | `when` scattered in evaluator | One place to audit |
| Clipboard | Port and Adapter (`TableClipboard`) | GoF Adapter / Hexagonal | Direct `ClipboardManager` calls | Wasm differs; `LocalClipboard` migration is one file |
| BlockItem integration | Anti-corruption seam (composition local plus 3 params) | PoEAA Gateway | Thread cell logic through `BlockItem` | Hotspot, 23 commits in 6 months |
| Maximize | Composition-local action plus shell host | Existing repo precedent | Route, in-place expansion | ADR-006 |
| Selection and navigation | Pure state machine functions, no Compose | Type-driven design | Logic inside composables | Testable in commonTest, shared inline and sheet |
| Cell identity | `CellAddress`, `RowKey`, `CellText` value classes | Type-driven design | Raw `Int`/`String` | Prevent row/column swaps and index-as-identity bugs |
| Errors | `CellValue.Err(CellError)` sealed values, no exceptions | Parse-don't-validate | Throwing parser | Wasm/Android crash rules; display as cell error |
| Mode | `GridMode` enum + sealed `GridCommand` | Type-driven design | Boolean flags | Exhaustive `when` for IME/Enter rules |
| Flags | Two booleans on existing `TableDisplayPrefs` | Existing pattern | New settings class | `TablePreferencesTest` pattern already exists |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|---|---|---|---|
| `ui/components/BlockItem.kt` (693 lines, 23 commits/6 months, cross-feature dispatcher) | Hotspot: every feature adds a branch; `onContentChange: (String, Long)` with `++localVersion` | **Isolate via seam** (Task 4.3c) | Only the `is BlockType.Table` branch (`:498`) is touched; the seam adapts `(String) -> Unit` to `onContentChange(s, ++localVersion)` exactly as the other branches do (`:534`, `:598`); new logic lives in `table/` and `ui/components/sheet/`; refactoring the dispatcher first adds risk with no table benefit |
| `ui/components/TableBlock.kt` (269 lines, one `combinedClickable`, CMP-4279 TODO) | Single click owner for the whole table; lossy parse | **Refactor-first** inside the feature (Tasks 1.1d, 4.2a) | The gesture owner and the `TableDoc`-backed view are prerequisites for cell edit; flag-off path stays identical |
| `ui/components/TableModel.kt` | Lossy parse-only model, no serializer | **Refactor-first** (Task 1.1d) | Becomes a thin view over `TableDoc` |
| `parsing/TableExtraction.kt`, `projectbridge/TableExtraction` | Parsers disagree with `splitTableRow` on `\|` | **Extend as-is** (untouched, not shared) | Stable, other consumers; ADR-001 records the non-sharing |
| `ui/state/BlockUndoManager.kt` | No grouping API, no entry label | **Extend as-is** (untouched) | Snapshot undo needs neither; announcements say "Undone"/"Redone" without a label (ADR-004) |
| `ui/state/BlockStateManager.kt` (`applyBlockContentUpdate`, ~32 commits, hotspot) | The undo/redo closures it records call `requestEditBlock(...)` (`:1089-1097`), flipping the block into raw edit mode on every table undo; undo also re-applies `oldVersion`, which the `pageBlocks[idx].version <= version` guard (`:1128-1130`) rejects after a higher-version edit | **Isolate via seam** (Task 0.5a) | One default-valued parameter `restoreEditFocus: Boolean = true` declared on `BlockEditingPort.updateBlockContent` (`ui/state/BlockEditorPorts.kt:19`) and threaded through `BlockStateManager.updateBlockContent`/`applyBlockContentUpdate`; table commits pass `false`; with `false` the manager stores `max(passedVersion, current.version + 1)` and the undo/redo closures re-apply with a fresh `block.version + 1`. No table logic enters the manager; text-block behaviour is byte-for-byte unchanged by default. Proven first by the failing test in Task 0.5a |

---

## Migration Plan
None. No `.sq` change, no new table, `MigrationRunner` and `kmp/src/generated/sqldelight*` untouched. The file format is unchanged (formulas are cell text; untouched tables serialize byte-identically).

## Observability Plan
- **Logs**: `Logger("TableSheet")` (never `android.util.Log`) for parse failure (once per block per session), serialize guard hits, paste rejection (size cap), `TableSyncPolicy` external-change decisions, evaluator `Throwable` guard hits, formula errors by kind at debug level. Error cells show text in the UI; nothing throws.
- **Metrics**: the existing harness (`benchmark/BenchmarkTypes.kt` `BenchmarkResult`, run by `UnifiedBenchmarkRunner` in `jvmTest`) is non-Compose, so Task 8.5a adds a JVM scenario `table-commit-200x12` that reports `commit_ms`, `evaluation_count` and `serialized_dirty_lines` as `BenchmarkResult.metrics` and is compared by the harness's existing baseline mechanism (relative, not an absolute gate). A Compose scroll probe (`table-sheet-scroll-edit`) is recorded as "not supported by the harness". No other metrics.
- **Usage tally (iteration 5)**: `Logger("TableUsage")` writes one line per table edit with `via=inline|sheet|raw` (no cell text), local only. It is the input to the adoption and fallback-to-raw outcome metrics (requirements.md Outcome Metrics) and is read by grep from the persisted logs in Task 8.7a.
- **Alerts**: none.

## Risk Control
- **Feature flags** (Settings, default off): `table_inline_edit_enabled`, `table_sheet_enabled`. Flag off equals today's behaviour (`TableBlockLayoutTest` unchanged, enforced by Task 4.3c).
- **Delivery**: three PRs, each green with flags off (see Dependency Visualization). Raw-markdown edit stays reachable in every flag state.
- **Rollback**: turn the flags off; no format change, so no data migration. Source rollback is a standard revert.
- **Staged rollout**: merge with both flags off; owner enables on desktop then on device; flipping a default to on is a separate follow-up PR after the Pixel_Fold and Logseq passes (wall-clock blockers below). Raw-markdown edit stays reachable in every state.

## Unresolved Questions
- [ ] Exact shape of `BlockType.Table` block content (property lines, bullet prefix, CRLF) - blocks Task 1.1a - resolved by Task 0.1a (agent).
- [ ] Wasm paste route and IME behaviour inside a Compose canvas Dialog - blocks Tasks 5.3a edit-mode on Wasm and 7.1b - resolved by Task 0.3a (agent; owner confirms real CJK IME in Task 8.3a).
- [ ] Whether a `Dialog` window receives `onGraphKeyEvent` (ADR-004 assumes not) - blocks Task 5.1a key wiring - resolved inside 5.1a with a test (agent).
- [x] Do a per-block DiskConflict flow and an active-graph read-only flag exist? RESOLVED (grep of `commonMain`): `DiskConflict` (`ui/AppState.kt:276`) is page-level app state with an optional `editingBlockUuid`, shown by the page-level dialog layer; it has no keep-mine/take-theirs-for-one-block API and does not fire for a block that is not `editingBlockUuid`. No active-graph read-only flag exists (`TargetWriterCapabilities` is the cross-graph write convention for off-graph targets). Decisions: the sheet owns a small conflict banner (new UI, sized in Task 5.1b: Keep mine / Take theirs / Edit as markdown) instead of reusing `DiskConflict`; the host takes a `readOnly: Boolean = false` parameter that nothing sets today (unit-tested, wired when a real flag appears), and the read-only story criterion is reworded to that. ADR-004 and ADR-006 amended.
- [ ] Whether Logseq preserves/renders `<br>`, formula text and rewritten delimiter rows as expected - blocks the default-on flip, not implementation - owner opens fixtures from Task 0.2a (owner: Tyler).
- [ ] Whether `TableClipboard`/`WasmPasteEventBridge` count as a `WiringAudit.SEAMS` interface - blocks Task 7.1c - resolved by reading `WiringAudit.SEAMS` (agent).
- [x] Block `version` semantics. RESOLVED by reading `BlockStateManager.kt:1064-1135` and `BlockItem.kt:105,165,250`: `applyContentChange` only replaces the in-memory block when `stored.version <= version`; inline commits use `++localVersion` and `BlockItem` resyncs when `block.version > localVersion`; `insertTextAtCursor` mints `block.version + 1` under `contentMutationMutex` (`:935`). Contract: inline commits keep `BlockItem`'s `(String, Long)` callback; the sheet host commits through one `TableCommitter` minting `max(observed block.version, lastMinted) + 1`; only one of the two editors is live at a time (inline freezes while the sheet is open), so versions never collide; undo/redo of a table commit re-applies with a fresh `block.version + 1` (Task 0.5a). Test: interleaved inline, sheet, inline commits yield strictly increasing versions (Task 5.1c). Round 2 correction (R2-B1): the commit versions are UI-space, but what the sheet observes from `getBlockByUuid` is the DB-row counter (`SteleDatabase.sq:316-317`, `version = version + 1`; `BlockStateManager.kt:777-783` says it cannot be matched against the UI version), so the sync policy never looks at a version at all (ADR-004). The manager mints the stored UI version for table commits, so a stale inline `++localVersion` cannot be rejected by the `<=` guard.
- [ ] Does undo of a table block currently update the in-memory content? The version guard suggests it does not (read, not run) - Task 0.5a runs it failing-first and fixes it in the same commit.

---

## Open Questions Resolved
| Question | Resolution | Where |
|---|---|---|
| Function set and number rule | `SUM AVERAGE MIN MAX COUNT COUNTA IF ROUND ABS`; invariant `.`-decimal grammar, strict `,` thousands, `%`; `FormulaShaped` rule; header is row 1 | ADR-002 |
| Reference behaviour on structural edits and sort | AST rewrite on insert/delete/move; sort: own-row refs follow the row, others literal; paste literal | ADR-003 |
| Module placement of the pure engine | `core`, package `dev.stapler.stelekit.table` and `.table.formula`; hand-placed in `kmp/modules_manifest.bzl` (new directory is an orphan for `kmp-modules-sync.py`) | ADR-001, Task 1.4a |
| Shortcut conflicts | Grep-based table; `Ctrl+Shift+E` (export) and `Ctrl+Alt+M` rejected; `Ctrl+Shift+Enter` maximize, `Shift+F2` edit as markdown | ADR-009 |
| Cell undo vs block undo | One commit = one block undo entry; undo/redo stay in the table (no raw-editor flip, Task 0.5a); the sheet's Ctrl+Z reaches only the sheet's own entries | ADR-004 |
| Selection model | Custom `GridSelection` and single gesture owner | ADR-005 |
| Maximize presentation | One commonMain Dialog on all targets | ADR-006 |
| Where view state lives | Session-only; alignment is data | ADR-007 |
| Copy of formula cells | Value to system clipboard, raw to in-app slot, "Copy as formulas" item | ADR-008 |

---

## Dependency Visualization

```
Phase 0  (wave 1, parallel)
  0.1a char+baseline --+--> 1.1a TableDoc/Parser
  0.1b web baseline    |
  0.2a Logseq fixtures-+--------------------------------------------------------> 8.4a (owner)
  0.3a Wasm spike ----------------------------------> 7.1b, 5.3a(Wasm), 8.3a
  0.4a Android spike -------------------------------> 4.2a, 5.2b, 8.2a
  0.5a failing-first undo test + BlockStateManager/BlockEditingPort param (one task, one commit) --> 4.3a, 4.3c, 5.1a, 5.1b
  0.5a --> 0.6a emission trace (W2, agent-only; real manager + SQLDelight feed recorded to a fixture) --> 3.2b (scripted cases cite the fixture)
  0.2a --> 8.4b (owner opens untouched fixtures, W2) ; 1.1c + 1.2a --> 8.4a (edited copies, W5)

Phase 1 pure engine
  1.4a manifest/core (end of W1) ---> 1.1a --> 1.1b --> 1.1c
                          1.1b --> 1.1d (renderer view)          1.4b guard
                          1.1b --> 1.2a --> 1.2b --> 1.2c
                          1.3a --> 1.3b
  2.1a --> 2.1b           2.1a --> 2.2a --> 2.2b --> 2.2c
  1.2a + 2.1b --> 2.3a ; 2.3a + 1.2b --> 2.3b --> 2.4a/2.4b --> 2.4c   (2.2c needs 2.2b + 1.2a)
  3.1c needs 3.1a + 2.2c + 1.3a
  3.1a (needs 1.1a) --> 3.1b ; 3.2a (needs 1.1a) + 0.6a --> 3.2b

Phase 2 inline
  4.1a flags -----------------------------+
  1.1d + 0.4a --> 4.2a --> 4.2b           |
  2.2c + 3.2b + 4.2a + 0.5a --> 4.3a --> 4.3c (BlockItem seam; TableBlock.kt serialized) ; 4.3a + 4.3c --> 4.3b
  3.1a + 1.3a + 7.1a + 4.3c --> 4.4a --> 4.4b
  4.3a + 4.1a --> 4.5a --> 4.5b (2.2c) ; 4.5a --> 4.5c (unparseable fallback, capped row window; TableBlock.kt #7, W11)
  4.2a + 4.3a + 4.1a --> 8.2b early device smoke (W7, owner machine; a fail blocks the W8 gesture/grid tasks 4.4a and 5.1a)

Phase 3 sheet
  4.3c + 4.1a + 0.5a --> 5.1a (creates TableMessages) --> 5.1b (+ 3.2b) --> 5.1c ; 5.1b --> 5.1d (notice host with actions, commit-failure path)
  5.2b --> 8.2c second device smoke (W11, owner machine; a fail blocks W12 and later gesture/grid tasks)
  5.1a + 2.2c --> 5.2a --> 5.2b --> 5.2c
  5.2b + 3.1b --> 5.3a --> 5.3b ; 5.3a --> 5.3c
  5.2b + 3.1a + 1.3a + 7.1a --> 5.4a --> 5.4b --> 5.4c --> 5.4d
  1.2a + 5.2b --> 5.5a --> 5.5b ; 5.2b --> 5.6a --> 5.6b ; 1.2b + 5.5a --> 5.7a
  2.2c + 5.2b --> 5.8a ; 5.8a + 5.3b --> 5.8b
  5.2b + 5.4a + 5.5a + 5.6a --> 6.1a --> 6.1b (also 5.3c, 5.5b, 5.7a, 5.8a), 6.1c (+7.1b)
  0.3a --> 7.1b ; 1.3a --> 7.1a (defines TableClipboard port) ; 7.1a + 7.1b + 5.4b --> 7.1c

Iteration 5 additions
  (none) --> 0.7a (W1, owner-gated; feeds 8.7a) ; 3.1b + 5.8a + 6.1a --> 6.1d --> 6.1e (+ 6.1c) ; 4.5c + 5.4a --> 4.5d
  5.1b + 5.1d + 5.2b --> 5.9a ; 8.1a + 4.5d + 5.9a + 6.1d + 0.7a --> 8.7a

Iteration 6 additions (triad repair 2)
  3.1a --> 3.1d (pure TableBackLadder + PointModeRule, W4)
  4.4a + 4.5a + 3.1d + 0.5a --> 4.4c (strip completion, TableOverlayStack; TableActionStrip.kt, W10) --> 4.4e (inline Back ladder; TableActionStrip.kt, W11)
  4.4c + 4.4e + 4.5d + 5.1b + 5.1d --> 4.5e (inline a11y, block-selection wiring, Resolve chip, sticky policy plumbing; TableBlock.kt #9, W13)
  5.1b + 5.1d + 5.4a --> 5.1e (sheet Undo/Redo: top bar + context strip; TableSheetScreen.kt, W12)
  3.1d + 5.1b + 5.3b + 5.3a + 5.4a --> 5.3d (sheet Back ladder, IME action key, Insert line break; new SheetBackHandler.kt, W13)
  5.8b + 3.1d + 4.4c + 4.3a --> 5.8c (point-mode wiring in sheet and inline CellEditor, non-drag references, W14)
  8.1a also needs 4.4e, 4.5e, 5.1e, 5.3d ; 6.1d also needs 5.1e

Iteration 7 additions (triad repair 3)
  3.2a --> 3.2c (pure TableUndoScope + NoticeQueue, W4) --> 4.4c (inline scoped Undo, notice queue), 5.1b (TableCommitter scope), 5.1d (NoticeQueue host), 5.1e (labels)
  2.1a --> 3.1d (PointModeRule valid-prefix check; 2.1a is W1)
  Everything else in iteration 7 amends existing tasks (3.1b, 3.2a, 4.4a, 4.4c, 4.5e, 5.1b, 5.1d, 5.1e, 5.3d, 5.8c, 8.2a, 8.3a); no wave moved

Phase 4 verify (after Phase 3)
  8.1a (needs 5.4c-d, 5.7a, 5.8b, 6.1a, 4.4e, 4.5e, 5.1e, 5.3d) --> 8.1b ; 8.5a ; 8.6b ; 8.6a (last)
  8.2a (Pixel_Fold, owner machine) ;  8.3a (web)
  Owner Logseq checks are earlier now: 8.4b (untouched fixtures, W2) and 8.4a (app-edited copies, W5); 8.2b/8.2c are the early device smokes
```

Serialization constraints (shared files): `TableBlock.kt` is edited by 4.2a (W5), 4.3a (W6), 4.3c (W7), 4.4a (W8), 4.5a (W9), 4.5b (W10), 4.5c (W11), 4.5d (W12), 4.5e (W13): exactly one task per wave, in that order. `TableActionStrip.kt` is edited by 4.4a (W8), 4.5a (W9), 4.4c (W10) and 4.4e (W11), one task per wave. `TableSheetScreen.kt` is edited by 5.1a (W8), 5.1e (W12), 5.9a (W13) and 6.1d (W14); 5.3d (W13) adds only a new `SheetBackHandler.kt` and a one-line call site the integrator merges. `BlockItem.kt` only in 4.3c. `BlockStateManager.kt` and `ui/state/BlockEditorPorts.kt` only in 0.5a. `TableMessages.kt` is created by 5.1a (W8) and extended only by later tasks in distinct waves (5.1d W10, 5.4b W12, 5.7a W12, 4.5d W12, 5.9a W13 add disjoint entries, so the integrator merges them without conflict; 5.4b and 5.7a edit different `TableMessages` sections and are listed so). `SheetStructureTest.kt` is owned by 5.5b, `SheetSortTest.kt` by 5.7a. `TableEditor.kt` is a thin facade; op families live in `StructuralOps.kt` (1.2a), `SortOp.kt` and `PasteOp.kt` (1.2b), with 2.3b touching them only to pass the reference transform (W6, after both). `kmp/modules_manifest.bzl`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`, `AllAndroidUnitTests.kt` and `kmp/src/androidUnitTest/kotlin/BUILD.bazel` are registry files: tasks list the test classes they add in their report, and the wave integrator runs `scripts/kmp-modules-sync.py` and `scripts/kmp-module-tests-sync.py` once per wave and re-runs `scripts/check-bazel-test-listing.sh`. Generated manifests are never hand-edited, with one exception: the one-time placement of a brand-new directory that `kmp-modules-sync.py` reports as an orphan (`table/`, `table/formula/` in W1 by Task 1.4a, `ui/components/sheet/` in W3 by the wave integrator when Task 7.1a adds its first file, and any other new directory under `commonMain` the same way). The placement lands in the same commit as the files, and no wave is pushed without a clean `scripts/kmp-modules-sync.py --check` and `ModuleManifestGuardTest`.

**Delivery in three PRs (pre-mortem P2-4).** Waves are the sequencing; PRs are the merge units, each merge-ready with both flags off and each run through the full gate list of Task 8.6a (the integrator runs it at every PR boundary, not only in W16): **PR-A** engine = Epics 0-3 plus Task 4.1a, 1.4a/b, 0.5a, 0.6a (no UI behaviour change; closes when 2.4c lands, W8); **PR-B** inline = Epic 4 minus 4.1a, plus 7.1a and the first device smoke 8.2b; **PR-C** sheet = Epics 5-6, 7.1b/c and the Epic 8 verification tasks (8.2c, 8.3a, 8.4a-b, 8.5a, 8.6). A late finding therefore reworks at most the open PR, and PR-A can merge while the UI work is still in review.

---

## Phase 0: De-risk (spikes, baselines, guards)

### Epic 0: Spikes and baselines
**Goal**: Answer the repo-unanswerable questions and capture the regression anchors before building.

#### Story 0.1: Baselines and content shape
**As an** implementer, **I want** the current table content shape and look recorded, **so that** the lossless model and the no-regression claim rest on evidence.
**Acceptance Criteria**:
- The shape of `BlockType.Table` block content is characterized by a test.
  - *Given* a block whose content is `- | a | b |\n|---|---|\n| 1 | 2 |\nid:: 6f2c` (a `TableDoc` candidate with a bullet prefix and a property line), *When* the characterization test reads `block.content` via `BlockParser`, *Then* the test records exactly which lines belong to the content and the expected prefix/suffix verbatim lines for `TableParser`.
- A phone-size and web baseline of the current table exists as PNGs.
  - *Given* the `TableBlockLayoutTest` fixture table with a 60-character cell, *When* rendered at `w360dp-h780dp-xxhdpi` and on the wasm build at 390 px and 1280 px, *Then* the PNGs are opened and described in the task report (wrapping intact, link tap still opens).
**Files**: `ATEST/ui/components/TableBaselineRenderTest.kt` (new, Roborazzi, removed or kept as the regression anchor in 8.1a), `CTEST/parsing/TableBlockContentShapeTest.kt` (new)

##### Task 0.1a: Characterize Table block content and capture phone baseline (S)
- First read the existing table path in `parsing/BlockParser.kt` (`:215`, `:503`) and `parsing/TableExtraction.kt` so the test only characterizes what the code does not already state. Then write `TableBlockContentShapeTest` over both; cover bullet prefix, `id::` property lines, trailing text after the last row (#724), CRLF, blank lines inside the block.
- Write `TableBaselineRenderTest` using the `PhoneMenuEntryTest` recipe (`@RunWith(RobolectricTestRunner::class)`, `@GraphicsMode(GraphicsMode.Mode.NATIVE)`, `@Config(sdk=[29], qualifiers="w360dp-h780dp-xxhdpi")`); record PNGs with `-Proborazzi.test.record=true` and open them.
- Tests: commonTest (shape) and androidUnitTest (render). Registration: `scripts/kmp-module-tests-sync.py` for the commonTest class; register the Robolectric class in `AllAndroidUnitTests.kt` and `kmp/src/androidUnitTest/kotlin/BUILD.bazel` per the `PhoneMenuEntryTest` precedent.
- Files: the two above. Output feeds Unresolved Question 1.

##### Task 0.1b: Web baseline of the current table (XS)
- `./gradlew :kmp:wasmJsBrowserDevelopmentRun -PenableJs=true`, open with the Chrome tools, screenshot a long-cell table at 390 px and desktop width; note link tap. Findings into the task report only (no file).
- Files: none.

#### Story 0.2: Logseq compatibility fixtures
**As the** owner, **I want** fixture pages covering untouched and edited tables, **so that** I can confirm Logseq still reads them.
**Acceptance Criteria**:
- A fixture pack plus SteleKit-side expectations exist.
  - *Given* fixtures with padded cells, compact `|-|` delimiter, `:---:` alignment, `<br>` cells (including `<br/>` and `<BR>` variants), an `=SUM(B2:B5)` cell and a ragged row, *When* `TableParser`/`TableSerializer` runs on each (after 1.1b exists) with no edit and then one `SetCell` edit, *Then* the untouched output equals the input and the edited output differs only in the edited row.
**Files**: `project_plans/table-sheet-editor/implementation/logseq-compat/*.md` (fixture pages, new), `CTEST/table/LogseqCompatFixtureTest.kt` (new, written in 1.1c)

##### Task 0.2a: Author the fixture pack and owner checklist (S)
- Create 9 fixture pages (one table each, including one whose cells are `=B2*C2*D2`: SteleKit's flag-off inline-markdown path and Logseq may italicize the `*` run, record the result in ADR-007) and a checklist of what the owner checks in Logseq (renders as a table; `*` in a formula is not turned into italics or, if it is, the result is recorded; edited row looks right; `<br>` renders as a line break; formula shows as text; alignment honoured). The `.md` fixtures are test inputs, not documentation. Hand off the checklist in Task 8.4a.
- Files: `project_plans/table-sheet-editor/implementation/logseq-compat/` fixtures.

#### Story 0.3: Wasm clipboard and IME spike
**As an** implementer, **I want** the Wasm paste route proven, **so that** the clipboard port has one known-good Wasm path.
**Acceptance Criteria**:
- The route is chosen with evidence.
  - *Given* a throwaway wasm build with a focused canvas and a document `paste` listener, *When* the owner-machine Chrome pastes a 4x3 Sheets TSV with a quoted multi-line cell via Ctrl+V and via the browser context-menu Paste, *Then* the listener receives `clipboardData` text equal to the TSV in both cases and `navigator.clipboard.readText` outcome (granted, prompt or denied) is recorded.
- IME inside a Compose text field in a Dialog on Wasm is characterized.
  - *Given* a Dialog containing one `BasicTextField` on the wasm build, *When* a composition is started (an IME or dead-key sequence such as `´` then `e`), *Then* the report states whether `composition` is set, whether Enter and Tab reach the IME, and whether focus survives scrolling a `LazyColumn` hosting the field.
**Files**: scratch branch only; findings recorded into ADR-008 and this plan's Unresolved Questions

##### Task 0.3a: Run the Wasm spike (M)
- Throwaway code on a scratch branch (`wasmJsMain/.../browser/` pattern, as `Main.kt:115-125` does for key handling): a `paste` listener, a `copy` via `ClipboardProvider`-style write, a Dialog with a `BasicTextField`.
- Browser: Chrome via the Chrome tools; Firefox/Safari noted as UNVERIFIED if unavailable.
- Update ADR-008 status and the Unresolved Questions with results. Delete the scratch code.

- **Go/no-go rule (written before the spike runs):** if the spike shows IME composition not reaching a Dialog `BasicTextField`, or the DOM `paste` event unavailable, then the Web target ships view-only plus "Edit as markdown" and the plan drops the Wasm edit-mode work (the Wasm part of 5.3a and Task 7.1b) now, rather than adapting later; record the decision in ADR-008 and in this plan's Unresolved Questions. Otherwise Wasm editing proceeds as planned.

##### Task 0.3b: Record spike findings (XS)
- Edit ADR-008 and this plan's Unresolved Questions checkboxes; no new files.

#### Story 0.4: Android gesture, IME and recycling spike
**As an** implementer, **I want** the touch/IME/lazy-recycle behaviour known on `Pixel_Fold`, **so that** the inline gesture owner and the hoisted edit state are designed against facts.
**Acceptance Criteria**:
- The gesture and IME facts are recorded.
  - *Given* a scratch screen with a `LazyColumn` of rows each hosting cells and one `BasicTextField` for the active cell on the `Pixel_Fold` AVD, *When* the active cell is edited and scrolled 30 rows off-screen and back, *Then* the report states whether the draft, composition and soft keyboard survive with the draft hoisted above the item, and whether `adjustResize` plus `imePadding` keeps the cell visible.
**Files**: scratch only (`~/Android/Sdk/emulator/emulator -avd Pixel_Fold -no-window -no-audio -no-snapshot`; `export ANDROID_HOME=$HOME/Android/Sdk`; `adb emu kill` after)

##### Task 0.4a: Run the Android spike (M)
- **Go/no-go rule (written before the spike runs):** if the hoisted draft does not preserve composition, focus and the soft keyboard across `LazyColumn` recycling, then the sheet renders the editing row outside the `LazyColumn` (a pinned editor above the grid) instead, and Tasks 5.2a/5.2b are re-scoped to that design before they start; record the decision in ADR-005/ADR-006.
- One emulator, one Gradle run at a time, `timeout 30m`, `TMPDIR=~/.cache/claude-tmp`. Findings into ADR-005/ADR-006 status lines and the Unresolved Questions. Wall-clock blocker: emulator pass.

#### Story 0.5: Undo and commit contract
**As an** implementer, **I want** the current undo behaviour of a Table block proven (red) and fixed (green) in one commit, **so that** "Ctrl+Z restores the previous value" is true in the grid and does not flip the block into the raw editor.
**Acceptance Criteria**:
- Red first, green in the same commit (Task 0.5a): the test is written and run against the unmodified manager, its failing output is pasted into the task report, then the fix is applied and the test re-run green; nothing red is ever committed or pushed.
  - *Given* a `BlockStateManager` over in-memory repositories (the harness `BlockStateManagerTest` uses) holding a `BlockType.Table` block with content `C0` at version 1, *When* `updateBlockContent(uuid, C1, 2)` then `undo()` run to completion, *Then* the test asserts `blocks` content equals `C0` and `editingBlockUuid` is null; run before any change it is expected to fail on one or both asserts, and the failing output is pasted into the task report (the version guard and `requestEditBlock` are read, not yet run).
  - *Given* the same block, *When* `redo()` runs, *Then* content equals `C1` and `editingBlockUuid` is null.
- The fix is a default-valued parameter, nothing else.
  - *Given* `updateBlockContent(uuid, content, version, restoreEditFocus = false)`, *When* undone or redone, *Then* the recorded closures do not call `requestEditBlock`, re-apply with `block.version + 1`, and the stored version is `max(passedVersion, current.version + 1)`, so a table commit made with a stale lower bound (for example an inline `++localVersion` before `BlockItem`'s `LaunchedEffect(block.version)` resyncs) is still applied in memory (test: undo, then an immediate commit with a lower-bound version of 1, then assert the grid content is the new one); the existing `BlockStateManagerTest` and every text-block caller (which use the default `true`) behave exactly as before.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockEditorPorts.kt`, `CTEST/ui/state/BlockStateManagerTableUndoTest.kt` and `CTEST/ui/state/BlockStateManagerDefaultPathTest.kt` (new, characterization pack; location per the existing `BlockStateManagerTest`; register both via `scripts/kmp-module-tests-sync.py`)

##### Task 0.5a: Failing-first undo test plus the `restoreEditFocus` fix, one commit (M)
- Order inside the task: (1) add `BlockStateManagerTableUndoTest` per the criteria and run it on the unmodified manager; paste the failing output (or, if it passes, record that the version guard is not exposed and drop the version part of step 2); (2) add `restoreEditFocus: Boolean = true` to `BlockEditingPort.updateBlockContent` in `BlockEditorPorts.kt:19` (the default lives on the interface; the override in `BlockStateManager.kt:~1064` cannot redeclare it) and to the private `applyBlockContentUpdate`; when false the manager stores `max(passedVersion, current.version + 1)` and the undo/redo closures skip `requestEditBlock` and re-apply with `(findBlockOrNull(uuid)?.version ?: oldVersion) + 1`. VERIFIED callers: `BlockEditingPort` is implemented only by `BlockStateManager`; `JournalsViewModel.kt:153` calls positionally with three arguments, so the defaulted parameter is source-compatible; (3) re-run: the new test is green and the whole `BlockStateManagerTest` is unchanged. One commit contains test and fix, so no wave is ever red. This is the only task that edits `BlockStateManager.kt` and `BlockEditorPorts.kt`. Also adds one additive, read-only `val undoRecordCount: StateFlow<Long>` on `BlockStateManager` (incremented in `record`, no `BlockUndoManager` change) so the sheet can detect foreign undo entries and derive its depth from the manager side (Story 5.1, R3-C3/C4); iteration 6 also adds read-only `canUndo`/`canRedo: StateFlow<Boolean>` (true while the page-wide stack has an entry to undo or redo; INFERRED that `BlockUndoManager` needs no change, confirmed in this task) so the inline strip can dim Undo and Redo (UX-70); test: it rises by exactly 1 per content-changing commit and by 0 for an unchanged-content commit. Report the diff size; if it exceeds that parameter, the counter, the version line and the two closure edits, stop and record why, and split `undoRecordCount` into its own commit. Pre-mortem P3-5: before any change, write and run a default-path characterization pack (`BlockStateManagerDefaultPathTest`: undo and redo of a text-block edit, an edit made after undo, the version guard rejecting a lower-version edit) so the shared hotspot is proven unchanged for `restoreEditFocus = true`; run it before and after the change and paste both outputs in the report.

#### Story 0.6: Record what the real feed emits
**As an** implementer, **I want** the real `BlockStateManager` and SQLDelight feed emissions recorded for the hard interleavings, **so that** the sync policy's scripted cases (Task 3.2b) are built from what is emitted rather than from what is assumed (pre-mortem P1-1).
**Acceptance Criteria**:
- A committed fixture holds the ordered trace.
  - *Given* a real `BlockStateManager` over the SQLDelight repository on in-memory SQLite (the harness later reused by Task 5.1c), *When* each of these runs: two quick commits `S1`,`S2`; one commit, undo, then an immediate re-commit; redo; one foreign write by another writer, *Then* the task logs, per scenario, every `getBlockByUuid` emission (content only; the DB version is recorded as a side column and is never an input to a decision) and the manager's block content after each step, as an ordered list in `project_plans/table-sheet-editor/implementation/emission-trace.md` and in the task report.
- Observed behaviour overrides assumption.
  - *Given* the trace, *When* an emission order or a missing/duplicated emission contradicts the classification rules of Story 3.2, *Then* the task reports the contradiction and Story 3.2's rules are amended before Task 3.2b starts.
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerEmissionTraceTest.kt` (new; `jvmTest-reason: real SQLDelight driver`, no `androidx.compose.ui.test` import; register in `AllJvmTests.kt`), `project_plans/table-sheet-editor/implementation/emission-trace.md` (fixture, new)

##### Task 0.6a: Record the real emission trace (S, agent-only, W2)
- Depends on 0.5a (uses the `restoreEditFocus = false` path). Build the in-memory SQLDelight harness once (`TableSheetRealFeedHarness`, jvmTest helper) so Task 5.1c reuses it instead of rebuilding. No assertions on timing: the test records and prints the order; a smoke assertion only checks that every scenario produced at least one emission. Blocks Task 3.2b.

#### Story 0.7: Measured baseline and product assumptions (iteration 5)
**As the** owner, **I want** the current table cost and the product assumptions measured before outcome claims are made, **so that** "faster" and "wanted" are checkable.
**Acceptance Criteria**:
- Table count and size are recorded.
  - *Given* the owner's graph root, *When* the delimiter-row command in requirements.md Baseline is run, *Then* the table and file counts are recorded in the task report (the command is run once per graph; a graph with zero tables is itself the finding).
- Cost of a cell edit today is recorded.
  - *Given* a 5 x 4 table and the raw-markdown editor, *When* the owner changes one cell 5 times on a phone and 5 times on desktop with a stopwatch, *Then* the median steps (taps and keystrokes) and seconds per platform are recorded; these are the baselines for UXM-1 and the adoption targets.
- Persona and platform ranking are confirmed or corrected.
  - *Given* requirements.md "Users / Consumers", *When* the owner answers five questions (primary persona and job, platform ranking, which of edit-cell / paste-range / sort / formulas they would use weekly, how many tables they edit per week, whether a literal `=SUM()` showing in Logseq is acceptable), *Then* the answers are written into requirements.md and ADR-005's touch-wording confirmation (Story 4.2) is made against that ranking.
**Files**: `project_plans/table-sheet-editor/requirements.md` (answers), task report

##### Task 0.7a: Baseline measurement and product-assumption questions (S, owner-gated, W1)
- No code. Run the command, run the stopwatch protocol, ask the five questions; record the numbers and answers in requirements.md (Baseline and Users sections). Feeds the outcome metrics, the flip criteria and RICE Reach. If the owner declines to measure, the baseline stays "UNMEASURED" and every outcome claim in the final report says so.

---

## Phase 1: Pure engine (no Compose)

### Epic 1: Table model, serializer, edits, clipboard codec
**Goal**: A lossless `TableDoc`, a serializer that re-emits untouched rows verbatim, pure edit operations, and a TSV/CSV codec, all in `core`.

#### Story 1.1: Lossless parse and serialize
**As the** owner, **I want** a table I never edited to be byte-identical after load/save, **so that** my files do not churn.
**Acceptance Criteria**:
- Untouched round trip is byte-identical.
  - *Given* a `TableDoc` parsed from `|  a  | b |\n|:-|-:|\n| 1 | 2 | 3 |\n` (padded, compact delimiter, ragged row with an extra cell), *When* `TableSerializer.serialize` runs with no edit, *Then* the output equals the input string exactly, including the extra cell `3`.
- An edited cell rewrites only its row.
  - *Given* the same `TableDoc` (header row 1 is source line 1, the delimiter is source line 2 and is not a row, the body row is `CellAddress(2,1)` on source line 3), *When* `SetCell(CellAddress(2,1), "9")` is applied and serialized, *Then* lines 1 and 2 are unchanged and line 3 reads `| 9 | 2 | 3 |`; `TableDoc.lineIndexOf(2)` returns 3 and `lineIndexOf(1)` returns 1.
- Escapes survive.
  - *Given* a `CellText` of `a|b` and one containing a newline, *When* serialized, *Then* the output has `a\|b` and `<br>` and re-parses to the same `CellText` after unescaping.
- Untouched cells in an edited row keep their source text.
  - *Given* a body row `| x<br/>y | p<BR>q | a\\|b | 1 |`, *When* only the last cell is set with `SetCell`, *Then* the row re-emits the first three cells byte-for-byte (`<br/>`, `<BR>` and the literal `\\|` unchanged) and only `1` changes.
- The renderer is unchanged.
  - *Given* `parseTableContent` now backed by `TableDoc`, *When* `TableModelTest`, `ParseTableContentTest` (jvmTest) and `TableBlockLayoutTest` run, *Then* all pass without edits to those files.
**Files**: `COMMON/table/TableDoc.kt`, `COMMON/table/TableParser.kt`, `COMMON/table/TableSerializer.kt`, `COMMON/ui/components/TableModel.kt` (view only), `CTEST/table/TableRoundTripPropertyTest.kt`, `CTEST/table/TableParserTest.kt` (new), `CTEST/table/TableSerializerTest.kt` (new)

##### Task 1.1a: `TableDoc` types and `TableParser` (M)
- Define `CellText`, `RowLine` (with `rawCells`), `DelimiterRow`, `ColumnAlign`, `CellAddress`, `CellRange`, `RowKey`, `TableDoc` (with `lineIndexOf(row)`) with verbatim prefix/suffix, line ending, pipe and padding style. Parser reuses `splitTableRow` semantics (copy the function into `table`, since `core` cannot import `ui_main`; keep `ui_main`'s copy delegating to it in 1.1d).
- Files: `COMMON/table/TableDoc.kt`, `COMMON/table/TableParser.kt`.

##### Task 1.1b: `TableSerializer` (M)
- Verbatim re-emit for non-dirty lines; dirty rows rendered in the detected style, re-escaping only cells whose `CellText` differs from the parsed one and re-emitting `rawCells` for the rest; escape `|`, newline to `<br>`, trailing-backslash guard; regenerate only changed delimiter cells.
- Files: `COMMON/table/TableSerializer.kt`.

##### Task 1.1c: Round-trip property and fixture tests (S)
- `Arb`/`checkAll` in `runTest`: (a) `serialize(parse(s)) == s` for generated tables (empty cells, `\|`, literal `\\|`, `<br>`, `<br/>`, `<BR>`, `<br />`, ragged, compact and padded delimiters, CRLF, missing outer pipes); (b) `parse(serialize(edit(doc))) == edit(doc)`; (c) untouched cells unchanged by any single edit (their source text, not just their parsed value), and `lineIndexOf` agrees with the parsed line list; plus example-based tests and `LogseqCompatFixtureTest` over the 0.2a fixtures.
- Tests live in commonTest (`CTEST/table/`); no `runBlocking`/`Dispatchers.IO` (wasm compile rule). Register: `scripts/kmp-module-tests-sync.py`.
- Files: `CTEST/table/TableRoundTripPropertyTest.kt`, `CTEST/table/LogseqCompatFixtureTest.kt`, `CTEST/table/TableParserTest.kt` (new: parse-failure logged once per block, `lineIndexOf`), `CTEST/table/TableSerializerTest.kt` (new: raw cells byte-for-byte, no computed value ever serialized, single-cell `SetCell` line rewrite). Registration note: all four are new commonTest classes; `scripts/kmp-module-tests-sync.py` regenerates the list and `kmp/src/commonTest/kotlin/BUILD.bazel` (wave integrator, once per wave).

##### Task 1.1d: Make `parseTableContent` a thin view over `TableDoc` (S)
- `TableData` is derived from `TableDoc` (trim and header-fixed columns remain a display concern); map `ColumnAlign` to `TableColumnAlignment`.
- Gate: `TableModelTest`, `TableColumnLayoutTest`, `TableBlockLayoutTest` unchanged and green; `ParseTableContentTest` lives in `jvmTest` (`src/jvmTest/.../ParseTableContentTest.kt`) and `TableBlockScreenshotTest` is a jvmTest UI test, so both run via `scripts/jvm-display-check.sh -- ...` (not a bare run); a failure without a confirmed display is unverified, not a regression.
- Files: `COMMON/ui/components/TableModel.kt`.

#### Story 1.2: Pure edit operations
**As a** user, **I want** insert/delete/move/sort/alignment/paste to be single predictable edits, **so that** undo is one step.
**Acceptance Criteria**:
- Structural ops keep the table well formed.
  - *Given* a 3x3 `TableDoc`, *When* `DeleteCols(2)` runs on the middle column, *Then* every row (including ragged ones) loses its cell at index 2 and the delimiter row keeps the other two markers.
- Header rules.
  - *Given* a header-only `TableDoc` (header plus delimiter, zero body rows), *When* `InsertRows(before=2, count=1)` runs, *Then* the outcome is `Applied` and one empty body row with the header's column count is added; *When* `DeleteRows(1)` (the header) is requested, *Then* the outcome is `Refused(RefusalReason.HeaderRow)` and no doc is produced (a caller cannot commit by mistake).
- Sort is stable and body-only.
  - *Given* body column values `10, abc, 2, "", 2` with the first `2` on row 4, *When* `SortRows(col=1, ascending=true)` runs, *Then* the order is `2` (row 4), `2` (row 6), `10`, `abc`, blank, header untouched; with `ascending=false` the order is `abc`, `10`, `2` (row 4), `2` (row 6), blank (blanks and errors are last in both directions; ties keep prior order in both).
- Sort never creates a self-referencing total (ADR-003).
  - *Given* body rows 2-5 with values and row 6 `Total | =SUM(B2:B5)`, *When* `SortRows(col=1)` is applied with no range selected, *Then* the trailing totals row is excluded from the sort and stays row 6 (`=SUM(B2:B5)` unchanged, no `#CYCLE!`); *Given* a selected body range `rows=3..4`, *When* sorted, *Then* only those rows move; *Given* a mid-table formula whose range would contain its own new row after the permutation, *Then* the outcome is `Refused(RefusalReason.SortWouldSelfReference)` with the count, and nothing is written.
- Totals-row detection is by dependency, not by `SUM` shape (ADR-003).
  - *Given* body rows 2-4 with values and row 5 `Total | =B2+B3+B4`, *When* `SortRows(col=1)` runs with no selection, *Then* row 5 is detected as a trailing totals row (it has a formula whose references include a cell in another body row, all of them in rows above it) and stays row 5; the same for `=B2*2+B3`, `=AVERAGE(B2:B4)` and `=B2`; *Given* trailing rows 5 `=B2+B3` and 6 `=B5*2` (the run extends upward while each trailing row has such a cross-row reference), *Then* both stay; *Given* a trailing row whose formulas reference only its own row (`=B5*C5`), *Then* it is an ordinary row and is sorted.
  - *Given* a mid-table formula in `D2` of `=SUM(A1:C2)` (a range that touches its own row and others but not its own cell), *When* sorted so the row moves to row 4, *Then* the range is not rewritten (sort never rewrites ranges, only single-cell references into the formula's own row); *Given* a permutation after which a moved formula's range would contain the formula's own cell, *Then* `Refused(SortWouldSelfReference)`.
- Paste grows within caps.
  - *Given* a 3x3 table and an 8x5 TSV pasted at `CellAddress(3,2)`, *When* `PasteRange` applies, *Then* the outcome is `Applied` with doc 10 rows by 6 columns and `EditSummary.Pasted(rows=8, cols=5, addedRows=7, addedCols=3)`, which `TableMessages` renders as "Pasted 8 x 5, added 7 rows, 3 columns"; a 30 000-cell paste is `Refused(RefusalReason.PasteTooLarge)`. The cap is one constant, `PASTE_CELL_CAP = 10_000` (UX-26, ADR-008), pinned at its boundary: *Given* a paste of exactly 10 000 cells into a table that stays at or below the 20 000-cell table cap, *Then* `Applied`; *Given* 10 001 cells, *Then* `Refused(PasteTooLarge)` and no doc. The same outcome carries `EditSummary.Pasted.transformedCells` (cells whose `|` or newline was transformed), which `TableMessages` renders as the "content transformed" notice (UX-27).
- Sort cannot silently change what unmoved formulas show (pre-mortem P2-3).
  - *Given* an `Applied` sort over a table with formulas, *Then* every formula cell outside the moved rows whose operands are order-insensitive (ranges under `SUM AVERAGE MIN MAX COUNT COUNTA`, or cells outside the moved rows) has the same computed value before and after (a property in Task 2.4b, which has `TableCalc`); *Given* a moved formula that is not in a detected totals row and holds a single-cell reference to another body row (`=B3*2` sitting in row 5), *When* sorted, *Then* the outcome is `Refused(RefusalReason.SortWouldChangeValues)` with the count and nothing is written (the not-sure case is refused, never guessed); *Given* an unmoved totals row with a single-cell cross-row reference (`=B2`), *Then* the sort applies and `EditSummary.Sorted.crossRowFormulaCount` counts it so the menu can warn before applying.
- A refused edit is a distinct type, not an unchanged doc.
  - *Given* any refused edit, *Then* `TableEditOutcome` is `Refused` and a property test asserts `Applied.doc != input doc` or the edit was a documented no-op `Applied` carrying `EditSummary.NoChange`.
**Files**: `COMMON/table/TableEdit.kt`, `COMMON/table/TableEditOutcome.kt`, `COMMON/table/TableEditor.kt` (facade), `COMMON/table/StructuralOps.kt`, `COMMON/table/SortOp.kt`, `COMMON/table/PasteOp.kt`, `COMMON/table/FormulaReferenceTransform.kt`, `CTEST/table/TableEditorTest.kt`, `CTEST/table/TableEditorPropertyTest.kt`

##### Task 1.2a: Cell, row and column operations (M)
- `SetCell`, `ClearRange`, `InsertRows/Cols(before, count)`, `DeleteRows/Cols`, `MoveRows/Cols`, `SetAlignment`, with `RowKey` preserved or reassigned; results are `TableEditOutcome` (`Applied(doc, EditSummary)` / `Refused(RefusalReason)`), no user-facing strings in `table`. `TableEditor(reference: FormulaReferenceTransform = FormulaReferenceTransform.None)` receives the reference transform by constructor so `table` never imports `table.formula`; structural ops call it for every formula cell and apply its non-null results.
- Files: `COMMON/table/TableEdit.kt`, `COMMON/table/TableEditOutcome.kt`, `COMMON/table/FormulaReferenceTransform.kt`, `COMMON/table/TableEditor.kt`, `COMMON/table/StructuralOps.kt`.

##### Task 1.2b: Sort and paste operations (M)
- `SortRows` (stable, numbers before text, blanks/errors last in both directions; computed value via an injected `valueOf` lambda so the engine stays a separate layer; optional `rows: RowRange` sorts only that selection; with no range, trailing totals rows are excluded by dependency exactly as ADR-003 and Story 1.2 define it (a trailing body row with a formula whose references include a cell in another body row, all above it; the run extends upward; `=B2+B3+B4`, `=B2*2+B3`, `=B2`, `=AVERAGE(B2:B4)` count, a row-local `=B5*C5` does not); refused with `SortWouldSelfReference` when a moved formula's range would contain its own new row, and with `SortWouldChangeValues` for the not-sure case of Story 1.2; the result carries `EditSummary.Sorted(column, blankCount, rewrittenFormulaCount, crossRowFormulaCount)`), `PasteRange` (grow, fill-selection, the `PASTE_CELL_CAP = 10_000` boundary test, `<br>`/`\|` transforms counted in `EditSummary.Pasted.transformedCells`).
- Files: `COMMON/table/SortOp.kt`, `COMMON/table/PasteOp.kt`, `COMMON/table/TableEdit.kt`, `COMMON/table/TableEditor.kt` (dispatch lines only).

##### Task 1.2c: Edit tests (S)
- Examples above plus properties: structural ops keep every row's cell count equal to the header's (for non-ragged input); sort never yields a formula whose range contains its own row (or is `Refused`); descending sort keeps blanks last and ties stable; `MoveRows` is a permutation; edits never touch untouched `RowLine`s (identity check on the verbatim text).
- Files: `CTEST/table/TableEditorTest.kt`, `CTEST/table/TableEditorPropertyTest.kt`.

#### Story 1.3: TSV/CSV codec
**As a** user, **I want** to paste from and copy to Google Sheets and Excel, **so that** data moves in and out.
**Acceptance Criteria**:
- Excel quoting round trips.
  - *Given* the TSV text `a\t"b\tc"\t"line1\nline2"\r\n1\t2\t3\r\n`, *When* `TabularText.parse(tab)` runs, *Then* it yields 2 rows: `[a, "b\tc", "line1\nline2"]` and `[1, 2, 3]`, and `format` of that result parses back to the same grid.
- CSV is supported.
  - *Given* `x,"y,z",""` with delimiter comma, *When* parsed, *Then* the cells are `x`, `y,z`, empty.
**Files**: `COMMON/table/TabularText.kt`, `CTEST/table/TabularTextTest.kt`, `CTEST/table/TabularTextPropertyTest.kt`

##### Task 1.3a: `TabularText` (S)
- State-machine parse and format, delimiter auto-detect (tab wins when any tab is present), trailing newline handling, size cap constant.
- Files: `COMMON/table/TabularText.kt`, `CTEST/table/TabularTextTest.kt`.

##### Task 1.3b: Codec property tests (S)
- `format(parse(x)) == x` normalization property and `parse(format(grid)) == grid` for generated grids including tabs, quotes, newlines.
- Files: `CTEST/table/TabularTextPropertyTest.kt`.

#### Story 1.4: Module placement and import guard
**As a** maintainer, **I want** the pure layer registered in `core` and guarded, **so that** it stays pure and Bazel builds.
**Acceptance Criteria**:
- Manifest and guard are green.
  - *Given* new files under `COMMON/table/`, *When* `scripts/kmp-modules-sync.py --check` and `ModuleManifestGuardTest` run, *Then* both pass with all `table/**` files owned by `core`.
- The guard catches planted violations.
  - *Given* a planted source map with `package ...table` importing `dev.stapler.stelekit.ui.components.WikiLinkText`, *When* `TableEngineImportDirectionTest.violations` runs, *Then* it reports exactly 1 violation, and 0 for the real sources; *Given* a planted `package ...table` file importing `dev.stapler.stelekit.table.formula.RefRewriter`, *Then* exactly 1 direction violation.
**Files**: `kmp/modules_manifest.bzl`, `kmp/src/businessTest/kotlin/dev/stapler/stelekit/table/TableEngineImportDirectionTest.kt`, `kmp/src/businessTest/kotlin/dev/stapler/stelekit/AllBusinessTests.kt`

##### Task 1.4a: Place `table/**` in `core` (XS, integrator step at the end of W1)
- Run `scripts/kmp-modules-sync.py`; it reports each brand-new directory as an orphan, so add the `table/` and `table/formula/` sources to `core` by hand in `kmp/modules_manifest.bzl`, then `--check`; this is the one allowed hand edit. Do the same for `ui/components/sheet/` (module `ui_main`, where `ui/components/` already lives) in the wave that adds its first file (W3, Task 7.1a), and for any other new directory under `commonMain` (state it in the task report). The entry lands in the same commit as the files and nothing is pushed with a failing `--check`; later waves are handled by the script once the directory has an owner.

##### Task 1.4b: `TableEngineImportDirectionTest` (XS)
- Model on `JobsScheduleImportDirectionTest`: forbidden `^import dev\.stapler\.stelekit\.(ui|db|git|merge)\b` and `^import androidx\.`; scan `table/` and `table/formula/` in `WiringInputs.productionSources`; plus an intra-package direction rule: files in package `...table` (not `.table.formula`) must not import `dev.stapler.stelekit.table.formula`, with its own planted-violation test (the cycle the reviews found); planted-violation and allowed-import tests. It reads sources, so it stays in the umbrella: register in `AllBusinessTests.kt`.

---

### Epic 2: Formula engine
**Goal**: A closed-grammar, bounded, incremental evaluator with AST-based reference rewriting (ADR-002, ADR-003).

#### Story 2.1: Tokenizer, parser, AST
**As a** user, **I want** `=SUM(B2:B5)` and arithmetic parsed safely, **so that** formulas work without execution risk.
**Acceptance Criteria**:
- Precedence and associativity are right.
  - *Given* the texts `=-2^2`, `=2^3^2`, `=10-4-3`, *When* parsed and evaluated, *Then* the results are -4 (power binds tighter than unary minus), 512 (`^` is right-associative) and 3 (`-` is left-associative).
- Unknown and malformed input is an error value, not an exception.
  - *Given* `=SUM(`, `=FOO(1)` and a 5 000-character formula, *When* parsed, *Then* the results are `#PARSE!`, `#NAME?`, `#LIMIT!` respectively and nothing throws.
- A rewritten formula stays a formula.
  - *Given* `=#REF!*2` and `=SUM(#REF!)`, *When* classified, *Then* both are `FormulaShaped`, parse to a tree containing a `RefError` node, print back byte-identically, and evaluate to `#REF!`; `=#nope` is not formula-shaped.
- Deep nesting is bounded everywhere.
  - *Given* 10 000 nested parentheses, *When* parsed, *Then* the result is `#LIMIT!` within the depth bound of 64 and no `StackOverflowError`; the same input and a 1 000-character formula are also fed to `FormulaPrinter`, `RefRewriter` and `TabularText.parse` in their own tests and none recurses beyond the bounded parser depth (no engine function recurses past depth 64; this bound, not the `Throwable` guard, is the protection on Wasm).
**Files**: `COMMON/table/formula/Tokenizer.kt`, `COMMON/table/formula/Expr.kt`, `COMMON/table/formula/FormulaParser.kt`, `COMMON/table/formula/FormulaPrinter.kt`

##### Task 2.1a: Tokenizer, `Expr`, Pratt parser (M)
- `FormulaShaped` classification (expression-start set includes the `#REF!` error token, so `=#REF!*2` stays a formula), A1/`$A$1` refs, `#REF!` as a `RefError` primary, ranges, `%`, comparison, string literals, the function allow-list as a table-driven registry. Depth and length limits.
- Files: `COMMON/table/formula/{Tokenizer,Expr,FormulaParser}.kt`.

##### Task 2.1b: Printer and parse-print round trip (S)
- `FormulaPrinter` (canonical text for newly built nodes, preserves `$`, prints `#REF!`) and token spans on every node so `RefRewriter` can splice instead of re-printing; unit tests in `CTEST/table/formula/FormulaParserTest.kt`.

#### Story 2.2: Evaluator, dependency graph, incremental recompute
**As a** user, **I want** values and errors per cell with fast edits, **so that** a 200x12 table stays responsive.
**Acceptance Criteria**:
- Values and errors follow ADR-002.
  - *Given* body cells `B2=3`, `B3=5`, `B4=""`, `B5="x"` and `C1==SUM(B2:B5)`, *When* `TableCalc` evaluates, *Then* `C1` is `Num(8.0)`; with `C1==AVERAGE(D2:D5)` over empty cells it is `#DIV/0!`; `=B2+B5` is `#VALUE!`.
- Cycles mark every member.
  - *Given* `B2==C2+1` and `C2==B2+1`, *When* evaluated, *Then* both show `#CYCLE!`; *When* `C2` is set to `4`, *Then* both clear to `5` and `4` (cycle result not cached).
- Range containing itself.
  - *Given* `B5==SUM(B2:B5)`, *When* evaluated, *Then* `B5` is `#CYCLE!`.
- Recompute is incremental.
  - *Given* a 200x12 `TableDoc` with a `SUM` over each of 12 columns, *When* one input cell changes, *Then* `TableCalc.evaluationCount` increases by the number of transitive dependents (1 for a single column total), not by 2 400.
- Huge ranges are clamped.
  - *Given* `=SUM(A1:ZZ99999)` on a 3x3 table, *When* evaluated, *Then* it reads at most 9 cells and returns a number.
- `#LIMIT!` is deterministic.
  - *Given* a formula that reads more than the per-formula budget of 10 000 operand cells (for example `=SUM(A1:A9000)+SUM(B1:B9000)` on a table that large) and a table over the 20 000-cell cap, *When* evaluated by a full pass and by any incremental pass touching it, *Then* that formula is `#LIMIT!` in both (the budget counts only the formula's own operand reads, never precedents' cached values, so pass size cannot change the outcome); over the table cap `TableCalc` is not built, cells show raw text and the sheet shows the banner "Formulas are off: this table has more than 20,000 cells" while editing stays allowed.
**Files**: `COMMON/table/formula/CellValue.kt`, `COMMON/table/formula/Evaluator.kt`, `COMMON/table/formula/DependencyGraph.kt`, `COMMON/table/formula/TableCalc.kt`, `CTEST/table/formula/EvaluatorTest.kt` (new, 2.2a), `CTEST/table/formula/DependencyGraphTest.kt` (new, 2.2b), `CTEST/table/formula/TableCalcTest.kt` (new, 2.2c)

##### Task 2.2a: `CellValue`/`CellError`, number grammar, function registry and evaluator (M)
- Iterative evaluation over a supplied topological order; per-formula operand-read budget (ADR-002) so `#LIMIT!` does not depend on pass size; number grammar from ADR-002; 12-significant-digit display formatter without `String.format` (verify per target). Tests: `CTEST/table/formula/EvaluatorTest.kt` (new; number grammar, clamping, `#LIMIT!`). Registration: new commonTest class via `scripts/kmp-module-tests-sync.py`.

##### Task 2.2b: `DependencyGraph` with range rectangles, cycle detection, dirty propagation (M)
- Kahn over the dirty subgraph; cycle members all marked; ranges stored as rectangles; graph rebuilt on structural change, patched on cell change. Tests: `CTEST/table/formula/DependencyGraphTest.kt` (new; Kahn, cycle members, range-includes-self). Registration as 2.2a.

##### Task 2.2c: `TableCalc` facade and `Throwable` guard (S)
- API: `TableCalc(doc)`, `valueAt(address)`, `update(edit)`, `evaluationCount`; guard returns `Err(Limit)` or `Err(Value)` for any `Throwable` and logs via `Logger`. The guard is a backstop only: `StackOverflowError`/`OutOfMemoryError` are not reliably catchable on Wasm/JS, so the real protection is that evaluation is iterative and every other engine function is bounded by parser depth 64. Not constructed above the 20 000-cell cap. Tests: `CTEST/table/formula/TableCalcTest.kt` (new; values, cycles, `Throwable` guard logging, over-cap not built). Registration as 2.2a. The three size thresholds are distinct and documented once here: 5 000 cells (inline row window, Task 4.5c), 10 000 cells (`PASTE_CELL_CAP`), 20 000 cells (formulas off, table cap).

#### Story 2.3: Reference rewriting in edits
**As a** user, **I want** formulas to keep pointing at the right data when I insert, delete, move or sort, **so that** totals stay correct.
**Acceptance Criteria**:
- Insert shifts references.
  - *Given* `C6==SUM(B2:B5)`, *When* `InsertRows(before=3)` applies, *Then* the stored text becomes `=SUM(B2:B6)` in its new row and values are unchanged.
- Delete of a referenced cell becomes `#REF!`.
  - *Given* `D2==B3*2`, *When* `DeleteRows(3)` applies, *Then* the stored text is `=#REF!*2`, evaluates to `#REF!`, and one undo restores `=B3*2` exactly.
- Column delete shrinks or poisons ranges, by which side (ADR-003).
  - *Given* `=SUM(B2:C5)`, *When* column B is deleted, *Then* the text becomes `=SUM(B2:B5)` (the range loses B and C shifts to B); *Given* `=SUM(B2:B5)`, *When* column B is deleted, *Then* the text becomes `=SUM(#REF!)`; *Given* `=SUM(A2:B5)`, *When* column B is deleted, *Then* `=SUM(A2:A5)`.
- Deleting referenced cells reports how many formulas broke (UX-37).
  - *Given* two formula cells referencing column B, *When* column B is deleted, *Then* `Applied.summary.formulasBroken == 2`, `TableMessages` renders "2 formulas now show #REF!", and the notice carries an Undo action (Task 5.1d).
- Range endpoints shrink.
  - *Given* `=SUM(B2:B5)`, *When* row 5 is deleted, *Then* the text becomes `=SUM(B2:B4)`.
- Sort own-row rule (ADR-003).
  - *Given* body rows 2 `Pear | 5 | =B2*2` and 3 `Apple | 3 | =B3*2`, *When* `SortRows(col=1, ascending=true)` applies, *Then* `Apple` becomes row 2 with `=B2*2` (value 6) and `Pear` row 3 with `=B3*2` (value 10): each formula moved with its row and its own-row reference was rewritten.
- No regex touches strings.
  - *Given* `=IF(A1>0,"B2",C3)`, *When* a row is inserted above row 2, *Then* the string `"B2"` is unchanged and `C3` becomes `C4`.
- Untouched formulas are byte-identical.
  - *Given* `=sum( b2:b5 )`, `=007+1` and `=1.50*2` in cells whose references do not move, *When* any structural edit or sort is applied, *Then* those cells' text and `RowLine` identity are unchanged (the rewriter returns null for them); *Given* `=sum( b2:b5 )` with row 1 inserted above row 2, *Then* only the two reference spans are replaced and the result is `=sum( b3:b6 )` (spacing and function-name case kept).
- Sort with totals (ADR-003).
  - *Given* the totals-row example from Story 1.2, *Then* no formula is rewritten for the excluded totals row and the sorted table has no `#CYCLE!`.
**Files**: `COMMON/table/formula/RefRewriter.kt`, `COMMON/table/StructuralOps.kt`, `COMMON/table/SortOp.kt`, `CTEST/table/formula/RefRewriterTest.kt`

##### Task 2.3a: `RefRewriter` (M)
- Pure `(Expr with token spans, TableEdit, bounds) -> spliced text or null`; implements `table.FormulaReferenceTransform` (`RefRewriter.asTransform()`), so `table.formula` depends on `table` and never the reverse; only reference spans whose address changes are replaced (a new `#REF!` token for deleted refs), everything else byte-verbatim; base-26 helpers (`Z -> AA`); permutation map for move; sort own-row rule.

##### Task 2.3b: Apply rewriting inside `TableEditor` ops (S)
- `StructuralOps`/`SortOp` call the injected `FormulaReferenceTransform` for every formula cell inside the same `TableEditOutcome.Applied` (so one serialized string) and mark a row dirty only for cells whose text changed; the count of sort formulas referencing other rows goes into `EditSummary.Sorted.crossRowFormulaCount` and own-row rewrites into `rewrittenFormulaCount`; `EditSummary.formulasBroken` counts formula cells that newly contain `#REF!` after a delete. The composition root (`ui_main`) constructs `TableEditor(RefRewriter.asTransform())`.

#### Story 2.4: Engine test corpus and mutation proof
**As a** maintainer, **I want** tests that were not derived from the implementation, **so that** plausible-but-wrong evaluator code fails.
**Acceptance Criteria**:
- Golden corpus authored independently.
  - *Given* 60 hand-checked cases (expression, grid, expected value or error) written by a separate agent from ADR-002 and documented Sheets/Excel behaviour without reading the implementation, *When* run against `TableCalc`, *Then* all pass; any disagreement is resolved by the ADR, recorded in the task report.
- Properties hold.
  - *Given* generated tables and random edit sequences, *When* incremental results are compared with a full recompute, *Then* they are equal; *When* a structural edit is applied, *Then* every unaffected formula's value is unchanged and a deleted-endpoint reference is `#REF!`, never a wrong number.
- Fuzz.
  - *Given* 5 000 random strings from the formula alphabet, *When* parsed and evaluated, *Then* none throws and each returns a value or an error.
- Mutation proof.
  - *Given* the evaluator with unary minus precedence flipped (`-2^2` = 4), *When* the suite runs, *Then* at least one test fails.
**Files**: `CTEST/table/formula/GoldenCorpusTest.kt`, `CTEST/table/formula/FormulaPropertyTest.kt`, `CTEST/table/formula/FormulaFuzzTest.kt`

##### Task 2.4a: Independent golden corpus (S)
- A fresh agent given only ADR-002/003 and the research docs writes the corpus; the implementer must not see it before passing. The corpus includes a "text that starts with `=`" group (`=5 apples`, `=2024 plan`, `==`, `=#nope`) with the expected classification (`FormulaShaped` or literal) and the expected display (`#PARSE!` with the literal text kept, or plain text) so the display path cannot turn user text into a bare error (pre-mortem P2-3).

##### Task 2.4b: Property, naive-model and fuzz suites (M)
- Includes an independent straight-line evaluator in the test (no shared code) for arithmetic over exactly representable doubles; the `incremental == full` property (including tables where the per-formula `#LIMIT!` budget is hit) and the structural-invariance properties; a property that an edit affecting no reference leaves every formula cell's text and `RowLine` identity unchanged; a delete-then-serialize-then-reparse case that stays `#REF!`; the sort property of Story 1.2 (`sortedTable_should_KeepUnmovedOrderInsensitiveFormulaValues`).

##### Task 2.4c: Mutation pass (S)
- Apply five deliberate mutations (precedence, range off-by-one, cycle with range-includes-self, `AVERAGE` empty, rewrite endpoint) one at a time, confirm failures, revert; evidence recorded in the task report as, per mutation, the diff and the name of the first failing test (also pasted into the PR description). The corpus agent in 2.4a likewise reports its disagreements with the implementation there.

---

### Epic 3: Interaction models (pure)
**Goal**: Selection, navigation, draft and sync state with no Compose, shared by inline and sheet.

#### Story 3.1: Selection, navigation, stats
**As a** keyboard user, **I want** spreadsheet navigation and range extension, **so that** I can work without a pointer.
**Acceptance Criteria**:
- Tab/Enter flow.
  - *Given* a 4x3 `TableDoc` with `GridSelection` at `CellAddress(2,3)` in `Navigate`, *When* `GridNavigator` handles Tab, *Then* the selection moves to `CellAddress(3,1)` (wrap to the next row); Shift+Enter from `CellAddress(2,1)` stays at row 1 header bounds.
- Range extension and whole-line selects.
  - *Given* selection at `CellAddress(2,2)`, *When* Shift+Down twice then Ctrl+Space, *Then* the selection is rows 2 to 4 of column 2 and then the whole column 2 (rows 1 to last).
- Data-region jump.
  - *Given* column 1 values in rows 2-5 filled and row 6 blank, *When* Ctrl+Down runs from `CellAddress(2,1)`, *Then* the selection lands on `CellAddress(5,1)`.
- Stats.
  - *Given* selected cells `3`, `5`, `x`, blank, *When* `SelectionStats` runs, *Then* COUNT 2, SUM 8, AVERAGE 4.
- One Back and Esc ladder as a pure function (ux.md 3.3, UX-71).
  - *Given* a surface (`Inline` or `Sheet`) and a state (conflict banner open, menu or panel open, IME visible, mode, selection size, strip visible), *When* `TableBackLadder.next(surface, state, key)` runs for Back and for Esc, *Then* it returns the first matching rung: a table test covers every rung on both surfaces and both keys (for example: Sheet, `Edit`, IME hidden, Back returns `Done` and never `CloseSheet`; the same with Esc returns `CancelEdit`; IME visible and Back returns `HideIme`; Inline `Navigate` with a range returns `CollapseRange`; Sheet `Navigate` on a single cell: Back returns `CloseSheet` and Esc returns `NotHandled` (Esc never closes the sheet, UX-93); Inline single cell with the strip visible returns `CloseStrip`; Inline with no active cell returns `NotHandled`); a property test shows repeated application from any state reaches `NotHandled` (inline Back and Esc, sheet Esc) or `CloseSheet` (sheet Back) in at most 6 steps, so the ladder terminates.
- Formula point mode as a pure function (ux.md S7, UX-20, UX-75).
  - *Given* drafts `=`, `=SUM(`, `=B2+`, `=B2*` and `=IF(A2>1, ` with the caret at the end, *Then* `PointModeRule.allows(draft, caret)` is true; for `=B2`, `=SUM(B2:B5)`, `abc`, `5` and a caret inside a token it is false; `allowsReplace` is true right after a reference inserted by point mode and false after any typed character; property: never true when the draft does not start with `=`.
**Files**: `COMMON/table/GridSelection.kt`, `COMMON/table/GridNavigator.kt`, `COMMON/table/SelectionStats.kt`, `COMMON/table/TableShortcutMap.kt`, `CTEST/table/GridNavigatorTest.kt`, `CTEST/table/GridSelectionPropertyTest.kt`, `CTEST/table/TableShortcutMapTest.kt` (new, 3.1b), `CTEST/table/SelectionStatsTest.kt` (new, 3.1c), `COMMON/table/TableBackLadder.kt` (new, 3.1d), `COMMON/table/PointModeRule.kt` (new, 3.1d), `CTEST/table/TableBackLadderTest.kt` (new, 3.1d), `CTEST/table/PointModeRuleTest.kt` (new, 3.1d)

##### Task 3.1a: `GridSelection` (S)
- Anchor/focus, row/column/table variants, extend, `contains`, bounds clamp; property: selection always inside bounds.

##### Task 3.1b: `GridNavigator` and `TableShortcutMap` (S)
- Iteration 7: Enter (and Shift+Enter) on the last (first) body row returns a `CommitStay` result: commit, no row appended, selection unchanged, mode `Navigate`, one polite "End of table" (UX-89); Ctrl/Cmd+Shift+Enter is mapped to `ToggleMaximize` (opens inline, closes in the sheet, ADR-009); there is no chord for closing the sheet by Esc.
- `GridNavigator` takes a `LayoutDirection`-free `rtl: Boolean`: Left/Right, Home/End and Tab direction mirror when true while `CellAddress` and file order stay logical (ux.md 3.7, UX-65); property test: navigating Right then Left in either direction returns to the start cell.
- Pure command mapping from a `GridCommand` sealed type; `TableShortcutMap` lists chord to command per ADR-009 (chords as plain data, not `androidx` types, to keep `table/` Compose-free). Tests: `CTEST/table/TableShortcutMapTest.kt` (new; no duplicate chords, ADR-009 conflicts absent). New commonTest class: registration via `scripts/kmp-module-tests-sync.py`.

##### Task 3.1c: `SelectionStats` and TSV extraction of a selection (S)
- Uses `TableCalc` values (needs Task 2.2c); `TabularText` for the range copy string (value form and "as formulas" form). Tests: `CTEST/table/SelectionStatsTest.kt` (new). Registration as 3.1b.

##### Task 3.1d: `TableBackLadder` and `PointModeRule` (S, W4)
- Two pure, Compose-free functions in `COMMON/table/` (import guard applies): the ladder of ux.md 3.3 as an enum result (`HideIme`, `Done`, `CancelEdit`, `CollapseRange`, `CloseStrip`, `CloseSheet`, `DismissBanner`, `CloseMenu`, `NotHandled`) and the point-mode rule of ux.md S7. Depends on 3.1a (`GridSelection` size). Tests as in the two criteria above; new commonTest classes, registration via `scripts/kmp-module-tests-sync.py`. Every Compose wrapper (4.4e inline, 5.3d sheet) calls these and decides nothing itself. Iteration 7: `PointModeRule` also requires the draft up to the caret to be a valid formula prefix under the Task 2.1a tokenizer (so `=5 apples`, `= total` and `==` never arm it; add 2.1a to this task's dependencies, it is W1) and exposes `PointModeState(armed, justInserted, stoppedForThisEdit)`: armed drives the text indicator "Tap a cell to insert its reference" (after an insert "Inserted B2. Extend... or type to continue"), `stoppedForThisEdit` is set by the **Stop inserting** control and cleared on the next edit; typing, caret moves and Done disarm. Tests (`PointModeRuleTest`): the literal-text cases, `stop_should_MakeTapsCommit_When_StoppedForThisEdit`, arming and disarming transitions.


#### Story 3.2: Draft and sync state
**As a** user, **I want** my typing to survive scrolling and external changes, **so that** nothing I type is lost.
**Acceptance Criteria**:
- The policy consumes content only, never a version (R2-B1). `TableSyncPolicy.classify(content: String)` has no version parameter. The host observes the repository's `getBlockByUuid`, whose `version` is the DB-row counter (`version = version + 1`, `SteleDatabase.sq:317`), while commits are issued in the UI space; the two cannot be compared (`BlockStateManager.kt:777-783`), so the host drops `version` at its boundary. The ledger keys states by content hash plus length and gives each its own monotonic `seq`.
  - *Given* the scripted sequence of Echo, Stale and Adopt cases below, *When* the host adapter is fed the same contents once with DB versions `1,2,3,...` and once with deliberately skewed, non-monotonic and repeated versions (`40, 7, 7, 99, 3`), *Then* the two decision lists are identical (a policy that read the version fails here).
- Classification order, over `OwnCommitLedger` (`currentSeq` is the displayed state):
  0. Ledger invariants (R3-C2): at most one entry per hash; `registerCommit` on an existing hash overwrites that entry with a new `seq` and state `InFlight`; "retired" means `Acked` (never removed); whenever an entry is echoed, every older `InFlight` entry (lower `seq`) also becomes `Acked`; a commit whose `Job` completes is marked `Acked` by `markDone(seq)` without waiting for an echo (R3-C5); eviction is LRU by `seq` (an `Adopt` or a re-commit re-seqs).
  1. content equals the displayed content: `Echo` (also covers a repeated emission);
  2. content is the registered expected undo/redo result (`expectAdopt`, R3-C1): `Adopt(content)`, new `seq`, becomes `currentSeq`, the expectation is consumed; this is the only way an `Acked` match is adopted while any `InFlight` entry exists;
  3. content matches an `InFlight` entry `e`: if `e.seq < currentSeq` (a newer commit was issued, or a newer state adopted, since) then `Stale` (ignored), else `Echo`; either way `e` and all older `InFlight` entries become `Acked`;
  4. content matches an `Acked` or loaded state: if any `InFlight` entry exists it is a late echo of an old state, so `Stale`; otherwise `Adopt(content)` (undo/redo; new `seq`, becomes `currentSeq`), or `ConflictKeepDraft` when a draft is pending;
  5. otherwise external: `Rebuild(selection)` with no draft, `ConflictKeepDraft` with one; the content is added with a new `seq`. An external edit that equals an `Acked`/loaded state is `Adopt` (silent revert, accepted); one equal to an older `InFlight` entry is `Stale` and the next commit overwrites it (last-writer-wins, accepted).
- Undo-produced states are registered by the sheet, not guessed (R3-C1). `TableCommitter.undo()`/`redo()` joins every pending commit `Job`, calls the manager's `undo()`/`redo()`, joins that `Job`, reads the resulting block content from the manager's own state, and calls `ledger.expectAdopt(content)`. The expectation may arrive after the matching emission, so the host re-classifies its last observed content right after registering (an emission already judged `Stale` is then corrected to `Adopt`).
  - *Given* S0 loaded, S1 committed and `Acked`, then undo (expected S0) and an immediate re-commit of S1 (`seq` 4, `InFlight`) before the S0 emission is delivered, *When* S0 is observed then S1 is observed, *Then* the decisions are `Adopt(S0)` then `Echo` (S1 is the entry re-committed, `seq` above the adopt), the display ends at S1 and the DB holds S1 (test "undo then immediate re-commit"; a ledger without `expectAdopt` and the `Stale`-while-`InFlight` rule fails it).
  - *Given* S0 loaded, S1 and S2 committed (Tab, Tab) and both `InFlight` with only S2 echoed so far, *When* Ctrl+Z is pressed and the post-undo emission S1 is observed, *Then* the decision is `Adopt(S1)` (expected result) and the display shows S1 (test "Tab, Tab, Ctrl+Z"; before this rule the S1 emission read `Stale` and the undo did nothing on screen).
  - *Given* S1 and S2 both `InFlight` and only S2 observed, *When* undo then S1 is observed, *Then* `Adopt(S1)` (R3-C2: S1 was marked `Acked` when S2 was echoed).
  - *Given* a commit whose `Job` completed without an echo (manager dropped it), *Then* its entry is `Acked` after `markDone`, and a later legitimate emission of that content is `Adopt` or `Rebuild`, never `Stale` (R3-C5).
- Echo is ignored.
  - *Given* a ledger whose displayed state is `S0` and an `InFlight` commit `S1` (`seq` 2, now `currentSeq`), *When* content `S1` is observed, *Then* the decision is `Echo` and the doc is not rebuilt.
- Out-of-order echoes never regress or flag a conflict.
  - *Given* two quick commits `S1` (`seq` 2) then `S2` (`seq` 3) both `InFlight`, *When* content `S1` is observed after `S2` was issued, *Then* the decision is `Stale`, the doc stays at `S2` and the next commit builds from `S2`; *When* `S2` is then observed, *Then* `Echo`; *When* only `S2` is ever observed (the feed conflated), *Then* `Echo` and the older `InFlight` `S1` becomes `Acked` (so a later undo to `S1` is `Adopt`).
- Undo and redo are known origins.
  - *Given* ledger states `S0` (loaded), `S1`, `S2` all `Acked`, no draft, *When* content `S1` is observed (undo), *Then* the decision is `Adopt(S1)` (rebuild the doc from `S1` keeping selection, no conflict, no toast); with a pending draft, `ConflictKeepDraft`; *When* `S2` is then observed (redo), *Then* `Adopt(S2)`.
- A late echo after an adoption does not flip the doc back.
  - *Given* `S2` still `InFlight` (`seq` 3) and an `Adopt(S1)` applied (`currentSeq` 4), *When* `S2` is observed, *Then* `Stale`.
- Ledger bound (128 entries, above the 100-entry undo stack).
  - *Given* 100 sequential commits then 100 undos, *Then* every undone state is `Adopt`, never `Rebuild`; *Given* 129 distinct committed states, *When* the oldest is observed again, *Then* it reads as external (`Rebuild`, or `ConflictKeepDraft` with a draft), the rebuild uses the observed content, and nothing is lost.
- External change (content not in the ledger) with a pending draft keeps the draft.
  - *Given* a `CellDraft` for `CellAddress(3,2)` holding `42` and block content changed to `S2` by another writer, *When* the policy evaluates, *Then* the decision is `ConflictKeepDraft` and the draft text is unchanged.
- External change without a draft rebuilds and keeps selection.
  - *Given* a selection at `CellAddress(4,2)` and external content that still has 4 rows, *When* the policy evaluates, *Then* the decision is `Rebuild(selection=CellAddress(4,2))`; with only 3 rows the selection clamps to row 3.
- Scripted cases come from the recorded trace, plus a randomized model test (pre-mortem P1-1).
  - *Given* the fixture `implementation/emission-trace.md` (Task 0.6a), *Then* every scripted event list in `TableSyncPolicyTest` cites the trace section it was taken from, and a case the trace contradicts is rewritten to the observed order, not kept as imagined.
  - *Given* generated event sequences (commit, echo delivered in any order or dropped, undo, redo, foreign write, `markDone`) run against a trivial model (last commit or adopted state wins), *Then* two invariants hold for every sequence: the displayed content is never older than the latest sheet commit, and no committed content is lost. Mutation proof recorded as for 5.1c (for example: drop the `InFlight`-older-than-`currentSeq` rule and show the property fails).
- Undo is table-scoped and named (iteration 7, ux.md rule 16b and c, UX-85).
  - *Given* a `TableUndoScope` at depth 0, *When* two commits with labels `edit B3` and `paste` are recorded (each with changed content) and a third with unchanged content, *Then* `undoDepth` is 2 and the label of the next undo is `paste`; *When* undo runs, *Then* `undoDepth` is 1, `redoDepth` is 1 and the next redo label is `paste`; *When* a new commit is recorded, *Then* `redoDepth` is 0; *When* `reset()` runs (strip hides, sheet opens) or `foreignEntry()` runs (inline), *Then* both depths are 0 and `canUndo` is false; depth never exceeds 100. A property test over generated commit/undo/redo/reset sequences shows `undoDepth` equals changed-content commits minus undos plus redos, within `0..100`.
- Field-level undo is draft-only (iteration 7, ux.md rule 16a, UX-86).
  - *Given* a `CellDraft` for `B3` with original `3`, typed `45` (two steps) then Undo, *Then* the text is `4`, `committedContent` is unchanged and no commit occurs; *Then* Redo restores `45`; *Given* a commit or cancel, *Then* the history is empty and `canUndo` is false; *Given* `composing = true`, *Then* no history step is recorded.
- The notice queue never drops an action (iteration 7, ux.md 3.10, UX-87).
  - *Given* a `NoticeQueue`, *When* an informational notice is visible and another informational one arrives, *Then* the second replaces it; *When* an action-bearing notice is visible and an informational one arrives, *Then* it is not queued (live region only); *When* two action-bearing notices arrive, *Then* the second queues behind the first, the first reports "1 more", and activating it advances; *When* a commit-failure Retry arrives, *Then* it shows first and the preempted notice stays queued; *When* a second Why? detail arrives, *Then* it replaces the first; *When* a sixth undo-bearing notice arrives, *Then* the oldest retires into the Undo control (its step remains in the `TableUndoScope`); a property test shows no action-bearing notice other than a retired undo is ever lost, and that height inputs (lines 2, landscape 1) are exposed as data.
- The real feed is covered separately: Story 5.1 (`TableSheetRealFeedTest`) runs two quick commits through a real manager and the real SQLDelight repository, because a scripted list here cannot prove the version spaces are right.
**Files**: `COMMON/table/TableViewState.kt`, `COMMON/table/CellDraft.kt`, `COMMON/table/TableSyncPolicy.kt`, `COMMON/table/OwnCommitLedger.kt` (3.2b), `CTEST/table/TableSyncPolicyTest.kt`, `CTEST/table/TableViewStateTest.kt` (new, 3.2a), `CTEST/table/TableViewStatePropertyTest.kt` (new, 3.2a), `CTEST/table/CellDraftTest.kt` (new, 3.2a), `COMMON/table/TableUndoScope.kt` and `COMMON/table/NoticeQueue.kt` (3.2c), `CTEST/table/TableUndoScopeTest.kt` and `CTEST/table/NoticeQueueTest.kt` (new, 3.2c)

##### Task 3.2a: `TableViewState` and `CellDraft` (S)
- Widths in dp (`Float` dp values, no Compose types), freeze flags, sort indicator that clears when order changes; `CellDraft` keyed by `CellAddress` holding text, selection and composition-active flag, plus its own field-level undo history (iteration 7: `undo()`, `redo()`, `canUndo`, `canRedo`; one step per word or per 1 s pause, no step recorded while composing, capped at 100, cleared on commit and on cancel; never touches committed content). Tests: `TableViewStateTest`, `TableViewStatePropertyTest`, `CellDraftTest` (all new commonTest classes; registration via `scripts/kmp-module-tests-sync.py` and `kmp/src/commonTest/kotlin/BUILD.bazel`).

##### Task 3.2b: `TableSyncPolicy` and `OwnCommitLedger` (M)
- `Echo`, `Stale`, `Adopt(content)`, `Rebuild(selection)`, `ConflictKeepDraft` over `OwnCommitLedger` exactly as the Story 3.2 classification order; ledger keyed by content hash plus length with its own monotonic `seq` counter (`registerCommit(content): Seq`, `registerLoaded`, `classify(content): Decision`), 128 entries (LRU by `seq`), no version anywhere in its API; API additions `expectAdopt(content)`, `markDone(seq)` and one-entry-per-hash re-commit semantics exactly as the Story 3.2 ledger invariants and rules 0-5; tests in commonTest as explicit scripted event lists (delayed/out-of-order, undo-then-echo, late-echo-after-adopt, conflation, 100-deep undo, eviction at 129, and the R3 cases: undo then immediate re-commit, Tab Tab Ctrl+Z, conflation then undo to the skipped state, dropped commit retired by `markDone`, external edit equal to an `Acked` state) plus the skewed-version equivalence test of Story 3.2, plus the randomized model test of Story 3.2 (generated interleavings against a trivial model; mutation proof recorded). Depends on Task 0.6a: each scripted case names the `emission-trace.md` section it was built from.

##### Task 3.2c: `TableUndoScope` and `NoticeQueue` (S, W4, iteration 7)
- Two pure, Compose-free classes in `COMMON/table/` (import guard applies), specified by the three Story 3.2 criteria above. `TableUndoScope`: `record(label, changed)`, `undo()`, `redo()`, `reset()`, `foreignEntry()`, `undoDepth`, `redoDepth`, `nextUndoLabel`, `nextRedoLabel`, cap 100; it replaces the ad hoc `sheetUndoDepth`/`sheetRedoDepth` counters of Story 5.1 (`TableCommitter` owns one for the sheet, the inline strip owns one per table, ADR-004). `NoticeQueue`: the merge, queue and retire rules of ux.md 3.10 plus the 2-line, landscape 1-line and 25% height inputs, used by `TableNoticeHost` (5.1d) and `TableOverlayStack` (4.4c). Depends on 3.2a (the `CellDraft` and label types). New commonTest classes `TableUndoScopeTest` and `NoticeQueueTest`; registration via `scripts/kmp-module-tests-sync.py` and `kmp/src/commonTest/kotlin/BUILD.bazel`.

---

## Phase 2: Inline editing

### Epic 4: Inline cell editing, selection and copy
**Goal**: Tap edits a cell in place, links still open, ranges selectable and copyable, with the flag off identical to today.

#### Story 4.1: Feature flags
**As the** owner, **I want** two default-off toggles, **so that** I can enable each path on my device when ready.
**Acceptance Criteria**:
- Flags default off and persist.
  - *Given* `Settings` with no stored value, *When* `tableDisplayPrefs()` runs, *Then* `inlineEditEnabled` and `sheetEnabled` are false; after `writeTableDisplayPrefs` with `inlineEditEnabled=true` a new read returns true.
- The settings rows exist.
  - *Given* `EditorSettings` with a `TablePreferences`, *When* rendered, *Then* "Edit table cells in place" and "Spreadsheet view" switches appear under "Tables" and flip `prefs`; the "Spreadsheet view" row is the one label for the sheet toggle everywhere (ux.md S17 uses it too), with helper text "Raw markdown editing is always available".
- The flag matrix is defined for all four combinations (the flags are independent), one test per row, implemented in Task 4.5a with `TableFlagMatrixTest` cases in `TableInlineAffordancesTest.kt`:
  | `inlineEditEnabled` | `sheetEnabled` | Tap on a cell | Action strip | Maximize button | Ctrl+Shift+Enter | "Edit as markdown" |
  |---|---|---|---|---|---|---|
  | off | off | raw markdown (today, `TableBlockLayoutTest` unchanged) | none | none | not bound | n/a (tap is already raw) |
  | on | off | edits that cell | shown, without Maximize | none | not bound | strip item |
  | off | on | raw markdown (today) | none | shown (48dp; the only entry to the sheet) | not bound (no grid focus without inline edit) | in the sheet overflow |
  | on | on | edits that cell | shown, with Maximize | shown | bound in `Navigate` | strip item |
  The "one action from any inline table" requirement therefore holds whenever the sheet flag is on, with or without inline edit.
**Files**: `COMMON/ui/components/TablePreferences.kt`, `COMMON/ui/components/settings/EditorSettings.kt`, `CTEST/ui/components/TablePreferencesTest.kt`, `ATEST/ui/components/SettingsTableRowsTest.kt` (new)

##### Task 4.1a: Add flags to `TableDisplayPrefs` and settings rows (S)
- New keys `table_inline_edit_enabled`, `table_sheet_enabled` (Boolean, default false); extend `tableDisplayPrefs()`, `writeTableDisplayPrefs`, and `TableSettingsRows`. Existing `TablePreferencesTest` extended, not rewritten. Also adds `ATEST/ui/components/SettingsTableRowsTest.kt` (new Robolectric class; register in `AllAndroidUnitTests.kt` and `kmp/src/androidUnitTest/kotlin/BUILD.bazel`).

#### Story 4.2: Single gesture owner
**As a** user, **I want** tap, link tap and long-press to behave predictably, **so that** edit never races block select.
**Acceptance Criteria**:
- Link tap wins in a non-editing cell.
  - *Given* flag on and a cell `see [[Page]]`, *When* a tap lands on the link text, *Then* `onLinkClick("Page")` fires once and no cell enters `Edit`.
- Plain tap edits that cell.
  - *Given* flag on and cell `Apple` at `CellAddress(2,1)`, *When* a tap lands on `Apple`, *Then* `GridMode` is `Edit` for `CellAddress(2,1)` and `onStartEditing` (raw edit) is not called.
- Long-press is the existing block select.
  - *Given* flag on, *When* a long-press lands on a cell, *Then* `onLongPressSelect` fires once and no cell enters `Edit`, even when the frame clock is held back: the test sets `mainClock.autoAdvance = false`, performs the long-press, calls `mainClock.advanceTimeBy(400)` and only then asserts (no wall-clock sleep).
- Flag off is today's behaviour.
  - *Given* flag off, *When* a tap lands anywhere, *Then* `onStartEditing` fires (`TableBlockLayoutTest` unchanged).
- Block selection mode keeps the table inert (iteration 6, ADR-005 precedence 0, UX-77).
  - *Given* flag on and `inBlockSelectionMode = true`, *When* a tap lands on a cell, *Then* the block-selection toggle fires once, no cell enters `Edit` and no strip shows; the commit-then-hide case (long-press into block selection while a draft exists commits once and hides the strip) is tested in Task 4.5e.
- Touch selection is the platform's, not a custom drag (the #240 lesson; pre-mortem P1-2).
  - *Given* flag on and a `PointerType.Touch` drag, *Then* the gesture owner starts no range and no custom text selection (horizontal scroll or the platform's own selection handles inside the editing cell only); a test asserts no `GridSelection` change from a touch drag.
  - Before Task 4.4a starts, the owner confirms in writing the wording of the flag-on touch behaviour (tap edits and a window-anchored strip appears with Close in `Navigate` or Cancel edit in `Edit` (one fixed-width first slot), then Done; a held conflict shows the reduced strip in every profile; long-press selects the block; strip actions commit the draft then select, copy, undo or maximize; there is one Extend picker and no "tap extends"; ux.md design rule 10); the confirmation is recorded in ADR-005 and listed as a wall-clock blocker.
**Files**: `COMMON/ui/components/TableBlock.kt`, `COMMON/ui/components/sheet/TableGestureOwner.kt`, `ATEST/ui/components/TableGestureOwnerTest.kt`

##### Task 4.2a: `TableGestureOwner` and `TableBlock` wiring (M)
- One `pointerInput` on the table; cell index from pointer position and the column/row layout; link hit test inside the cell via `WikiLinkText`'s layout result; mouse-vs-touch from `PointerType`; dp slop via `LocalDensity`. Incorporate Task 0.4a findings. Replace the table-level `combinedClickable` only when the flag is on. Takes `inBlockSelectionMode: Boolean` (default false, wired by Task 4.3c): when true the owner is inert (ADR-005 precedence 0).
- `TableBlock.kt` is serialized (see dependency note).

##### Task 4.2b: Gesture tests with a delayed frame clock (S)
- Robolectric (`androidUnitTest`): link tap, plain tap, long-press (with the `mainClock.autoAdvance = false` mechanism above), flag off, and `tap_should_ToggleBlockSelectionNotEdit_When_InBlockSelectionMode`; every test sets the flag explicitly. `// jvmTest-reason` not needed (no jvmTest file). Register in `AllAndroidUnitTests.kt` and `kmp/src/androidUnitTest/kotlin/BUILD.bazel`.

#### Story 4.3: In-cell editor and BlockItem seam
**As a** user, **I want** to type in the tapped cell and have it saved once, **so that** I never detour through raw markdown.
**Acceptance Criteria**:
- Commit on Enter is one undo entry.
  - *Given* flag on, a table with `Qty` cell `3` and `onContentChange` capturing calls, *When* the user taps the cell, types `4` then `5` replacing the text, and presses Enter, *Then* `onContentChange` is called exactly once with the table text whose row reads `| Apple | 45 | ...` and the selection moves down one row and that cell is in `Edit` (ux.md S2).
- Esc cancels.
  - *Given* the same state with draft `45`, *When* Esc is pressed, *Then* `onContentChange` is not called and the cell shows `3`.
- IME composition is not interrupted.
  - *Given* `TextFieldValue.composition != null` for the draft, *When* Enter or Tab arrives, *Then* the grid does not consume the key and no commit happens.
- Pipes and newlines are safe.
  - *Given* a draft `a|b` committed, *Then* the serialized text contains `a\|b`; *Given* Alt+Enter inserted a line break (ADR-009: Shift+Enter commits and moves up, Alt+Enter inserts the break), *Then* the commit writes `<br>`.
- No echo loop.
  - *Given* a commit whose serialized text comes back as `block.content`, *When* `remember(content)` would re-parse, *Then* `TableSyncPolicy` reports `Echo` and the editing cell keeps its cursor; with two quick commits whose echoes arrive out of order the second commit's text is never overwritten (policy criteria in Story 3.2).
- Undo stays in the table.
  - *Given* a committed cell edit on a real `BlockStateManager`, *When* `undo()` runs, *Then* the table shows the previous value, `editingBlockUuid` is null (no raw editor opens) and the cell's selection is kept; one `redo()` restores the edit.
- Version contract.
  - *Given* the inline commit, *Then* `BlockItem`'s seam routes it through `LocalTableCommitAction` (which calls `updateBlockContent(..., restoreEditFocus = false)`, so the manager stores `max(passedVersion, current.version + 1)` in the UI space) when provided, else falls back to `onContentChange(s, max(++localVersion, block.version + 1))`; a test with inline, sheet-committer and inline commits asserts strictly increasing stored versions, and a test of undo followed immediately by an inline commit (before `BlockItem`'s `LaunchedEffect(block.version)` resyncs `localVersion`) asserts the grid shows the new content and the DB content equals it.
- Flag off equals today.
  - *Given* flag off, *When* the existing `TableBlockLayoutTest` suite runs, *Then* all 10 tests pass unchanged and `BlockItem`'s Table branch renders as before.
**Files**: `COMMON/ui/components/TableBlock.kt`, `COMMON/ui/components/sheet/CellEditor.kt`, `COMMON/ui/components/BlockItem.kt`, `ATEST/ui/components/TableInlineEditTest.kt`, `ATEST/ui/components/TableBlockFlagOffEquivalenceTest.kt` (new, 4.3c)

##### Task 4.3a: `CellEditor` and edit state in `TableBlock` (M)
- Also exposes `commitDraft()` (commit-if-changed, one undo step) for the strip of Task 4.4a, and logs one `Logger("TableUsage")` line `via=inline` per inline commit (no cell text).
- One live `BasicTextField` (active cell only; others stay `WikiLinkText`), `FocusRequester` in `LaunchedEffect`, composition guard modelled on `BlockEditor.kt:~67`, Enter/Tab/Esc, Shift+Enter (commit, move up) and Alt+Enter (line break) per ADR-009, `onPreviewKeyEvent`. The field uses `KeyboardOptions(imeAction = ImeAction.Next)` with Enter semantics (ADR-009 touch equivalents) and, on Android, `IME_FLAG_NO_EXTRACT_UI` and `IME_FLAG_NO_FULLSCREEN` so a landscape IME never takes the whole screen (INFERRED Compose mechanism, verified in Task 8.2a). After a commit with Enter the next cell below enters `Edit` inline (ux.md S2), not only `Navigate`. Commit via `TableEditor` `SetCell` (act only on `Applied`) then `TableSerializer` then the `onCommit: (String) -> Unit` it is given. Uses `TableCalc` display values for non-editing formula cells (no `TableCalc` above the 20 000-cell cap). The draft is hoisted (Story 3.2 state), so an inline cell that leaves composition keeps its text; there is no commit-on-dispose.

##### Task 4.3b: Inline edit and IME tests (S)
- Robolectric tests per the criteria (commit once, Esc, composition, escaping, echo). Register as above.

##### Task 4.3c: `BlockItem` seam and flag-off equivalence (S)
- The seam also logs one `Logger("TableUsage")` line `via=raw` when the table's tap opens the raw editor while at least one table flag is on (the fallback-to-raw numerator); flag off logs nothing.
- In the Table branch of `BlockItem.kt` (`:498`) pass `blockUuid`, `onCommit = { s -> LocalTableCommitAction.current?.invoke(block.uuid, s, localVersion) ?: onContentChange(s, maxOf(++localVersion, block.version + 1)) }` (the `LocalTableCommitAction` value is read once at the top of the branch) and `onOpenSheet = LocalOpenTableSheetAction.current?.let { { it(block.uuid) } }`; and `inBlockSelectionMode` (the block-selection flag `BlockItem` already receives; ADR-005 precedence 0); nothing else in `BlockItem.kt` changes. `LocalOpenTableSheetAction`, `LocalTableCommitAction: ((BlockUuid, String, Long) -> Unit)?` and `LocalTableSheetOwner: BlockUuid?` (the block currently owned by the sheet, default null) are declared in `COMMON/ui/components/TableSheetAction.kt` next to the `AnnotateImageAction.kt` pattern (same `@Suppress("CompositionLocalAllowlist")` justification comment); the shell provides all three. Task 4.3c provides `LocalTableCommitAction` in `GraphContentActiveShell.kt` (one line, so inline undo never opens the raw editor once the flag is on); Task 5.1a provides the other two; until then they are null and the seam falls back to `onContentChange`.
- Test: flag-off equivalence (same node tree and `onStartEditing` path as before) in `ATEST/ui/components/TableBlockFlagOffEquivalenceTest.kt` (new Robolectric class; register in `AllAndroidUnitTests.kt` and `kmp/src/androidUnitTest/kotlin/BUILD.bazel`).
- Files: `COMMON/ui/components/BlockItem.kt`, `COMMON/ui/components/TableSheetAction.kt`, `COMMON/ui/components/TableBlock.kt`, `COMMON/ui/GraphContentActiveShell.kt` (one provider line).

#### Story 4.4: Inline range select and copy
**As a** user, **I want** to select cells and copy them, **so that** I can reuse data without raw editing.
**Acceptance Criteria**:
- Mouse drag selects a range and copies TSV.
  - *Given* flag on, a 4x3 table and a `PointerType.Mouse` drag from `CellAddress(2,1)` to `CellAddress(3,2)`, *When* Ctrl+C is pressed in `Navigate` mode, *Then* the clipboard text is `Apple\t3\r\nPear\t5` (no trailing newline per `TabularText` output rule) and `PageView`'s block copy does not fire.
- Touch uses the strip, not a drag.
  - *Given* a touch tap on a link-free cell then "Select row" in the action strip, *When* "Copy" is chosen, *Then* the row's cells are copied as one TSV line, and a touch drag scrolls horizontally instead of selecting.
- One touch rule: the strip is a window-anchored bottom bar, sticky for the whole edit (ux.md design rule 10, UX-57..UX-59, UX-72..UX-74).
  - *Given* a touch pointer, flag on and a cell in `Edit` with draft `45`, *Then* the strip is visible, its bottom edge is the top of the IME inset (the window bottom when the IME is hidden), its first controls are Cancel edit (left, fixed width) and Done, each button is at least 48dp at 360dp, and the strip bounds and the active cell bounds are disjoint.
  - *Given* `TableStripPolicy(mode, entryProfile)` (a pure function with a table test over profile `Touch|Hardware` x mode `Navigate|Edit` x a key event mid-edit), *Then* `Touch` shows the strip in `Edit`, `Hardware` hides it in `Edit`, both show it in `Navigate`, and no mid-edit event changes the answer; TalkBack or Switch Access enabled (a fake `AccessibilityState` input) maps to `Touch`; a Bluetooth key pressed during a `Touch` edit leaves the strip in place and a tap during a `Hardware` edit does not show it.
  - *Given* the same table in `Navigate` and then `Edit`, *Then* the strip's bottom edge is the same up to the IME inset, the relative order of the shared actions is identical (the test compares the action id lists), no animation spec is used (asserted on the test clock), and the cell field's `ImeOptions` carry no-extract-UI and no-fullscreen.
  - *Given* the strip in `Navigate` and then in `Edit`, *Then* the first slot has the same width and position in both, reads Close in `Navigate` and Cancel edit in `Edit`, uses different icons, and its label does not change while characters are typed; *Given* `Edit` with an unchanged draft, *Then* Cancel edit is present, leaves `Edit` without a commit and posts nothing; *Given* draft `45`, *Then* activating it restores `3`, commits nothing and posts "Discarded your edit" with an Undo that restores `45` into the cell; Close in `Navigate` collapses a range, else hides the strip.
  - *Given* a 360dp strip with every action, *Then* Markdown (Edit as markdown) is in the fixed trailing slot outside the scrolling part and is on screen without scrolling in `Navigate`, `Edit` and the collapsed landscape strip (UX-91); *Given* a touch-entry edit and then a mouse click, *Then* the strip stays docked; *Given* a hardware-entry edit and then a tap, *Then* the strip neither appears nor moves.
  - *Given* draft `45` and the strip's Done, *Then* one commit and `Navigate` on the same cell; *Given* Back with the IME hidden, *Then* one commit; *Given* Back with the IME visible, *Then* the IME hides and the mode stays `Edit` (Back is the ladder of ux.md 3.3, Task 4.4e).
  - *Given* draft `45` and each of Select row, Select column, Select table, Extend, Copy, Maximize and Markdown, *Then* exactly one commit happens first and then the action runs in `Navigate` (Copy copies the committed cell); a tap on the strip never counts as "tap outside". UX-05's 3 taps (cell, Select row, Copy) are one test.
- Undo and Redo are strip controls (UX-70).
  - *Given* a committed edit on a real `BlockStateManager`, *When* the strip's Undo (text "Undo: edit B3") is activated in `Navigate`, *Then* `undo()` runs once, the table shows the previous value, no raw editor opens and "Undone" is announced; *Given* the strip just appeared over a page whose stack holds a foreign entry `E` and no table commit, *Then* Undo is dimmed (`stateDescription` "Nothing more to undo in this table"), stays focusable, activating it calls `undo()` zero times, `E` is not reverted and the notice shows; Redo likewise; *Given* the strip hides and reappears, *Then* the depth is 0 again; *Given* a foreign entry recorded while the strip is visible, *Then* the depth resets to 0 (UX-85).
  - *Given* `Edit` with draft `45` (typed in two steps), *When* Ctrl+Z, or the strip's Undo, is used, *Then* the draft becomes `4`, no commit happens, `undo()` is called zero times and the document is unchanged; *Given* an unchanged draft, *Then* Undo is dimmed with "Nothing more to undo in this cell"; after Done, Undo reads "Undo: edit B3" and reverts that commit (UX-86).
- One Extend mode (UX-75).
  - *Given* a selected cell and the strip's Extend..., *Then* a picker lists right, down, to row end, to column end and to table end and each choice grows the range from the anchor; no tap on a cell extends a range while the picker is closed.
- Error detail without long-press (UX-76).
  - *Given* an active error cell, *Then* the strip shows Why?, and activating it shows the ux.md S12 detail as a notice with Edit and Dismiss that stays until acted on.
- Touch equivalents for Tab and Alt+Enter (UX-82).
  - *Given* touch `Edit` with draft `9`, *Then* Next column commits once and selects the cell to the right, and Line break inserts `<br>` at the caret (committing `a`, break, `b` writes `a<br>b`), each without a hardware key.
- Stacking and Maximize (UX-78).
  - *Given* a notice and a hint chip are both due, *Then* only the notice shows and the hint waits; *Given* the strip visible, *Then* the Maximize button is not composed; *Given* 360dp-high landscape, *Then* hints are suppressed and the notice is one line.
- Selection is visible without color alone.
  - *Given* a selected range, *When* rendered, *Then* an outline (3:1 contrast) surrounds it and `semantics { selected = true }` is set on each cell.
- Cell text selection works in the editing cell.
  - *Given* `Edit` mode on `Apple`, *When* the platform selects all and copies, *Then* the clipboard text is `Apple`.
**Files**: `COMMON/ui/components/sheet/TableSelectionOverlay.kt`, `COMMON/ui/components/sheet/TableActionStrip.kt`, `COMMON/ui/components/sheet/TableOverlayStack.kt` (new, 4.4c), `COMMON/ui/components/TableBlock.kt`, `ATEST/ui/components/TableInlineSelectionTest.kt`, `ATEST/ui/components/TableStripFlowTest.kt` (new, 4.4c), `ATEST/ui/components/TableInlineBackTest.kt` (new, 4.4e)

##### Task 4.4a: Selection overlay, mouse drag and action strip (M)
- `GridSelection` rendering (outline, not fill-only), mouse drag after slop, Shift+click, strip with "Select row", "Select column", "Select table", "Extend..." `[beyond-req]` (picker), Copy, "Maximize" (only when the sheet flag is on), "Edit as markdown" (min 48dp targets via dp). Also implements the touch rule: `TableStripPolicy(mode, entryProfile)` (decided at edit entry, sticky for the whole edit; TalkBack and Switch Access map to `Touch`), the window-anchored dock with one fixed relative action order (Close or Cancel edit in one fixed-width first slot, Done, Next column, Line break, then Select row, Select column, Select table, Extend..., Copy, Undo, Redo, Maximize in the scrolling part, with Markdown pinned in a fixed trailing slot), the active cell scrolled above the strip, the landscape collapse to Cancel edit or Close, Done, Markdown and "More", placement fixed with the visibility decision (a pointer-type change mid-edit moves nothing), the entry profile taken from `PointerType` (on Wasm the DOM pointer type: `touch` and `pen` = `Touch`, `mouse` = `Hardware`, INFERRED, checked at 390 px in 8.3a), commit-first for every other strip action, and "strip taps are inside the grid focus scope" (this is the one place the strip is defined; 4.3a only exposes `commitDraft()`). These are the one set of user-visible labels; ux.md uses the same names.

##### Task 4.4b: Copy path and tests (S)
- `TableClipboard` calls through the Compose adapter from Task 7.1a (interface used here, impl lands in 7.1a; use a recording fake in tests); tests per criteria; register.

##### Task 4.4c: Strip completion and overlay stack (S, W10; `TableActionStrip.kt`, new `TableOverlayStack.kt`, no `TableBlock.kt`)
- Depends on 4.4a, 4.5a (both edit `TableActionStrip.kt`, W8 and W9), 3.1d, 3.2c and 0.5a. Adds: the fixed-width first slot (Close in `Navigate`, Cancel edit in `Edit`, always present, distinct icons and words; the "Discarded your edit" Undo notice only when the draft differs); Undo and Redo (text "Undo: <step>", dimmed but focusable, "Nothing more to undo in this table" / "Nothing more to redo in this table" notice): in `Navigate` backed by a per-table `TableUndoScope` (starts at 0 when the strip appears, resets when it hides or on a foreign `undoRecordCount` rise; enabled only while `undoDepth > 0` and `canUndo` of Task 0.5a), in `Edit` backed by the `CellDraft` history and never committing; notices go through the shared `NoticeQueue`; the one Extend picker (drops any tap-extends path) shared with the sheet and with formula point mode; **Why?** for an error cell; **Next column** and **Line break** for touch `Edit` (dispatching the same `GridCommand` values as Tab and Alt+Enter, ADR-009); no animation on show or hide; `TableOverlayStack` (strip, notice, hint chip in the order of ux.md design rule 13: notice beats hint, active cell scrolled above the whole stack, hints suppressed and notices one line in landscape). Tests: `TableStripFlowTest` (new Robolectric class; register in `AllAndroidUnitTests.kt` and `kmp/src/androidUnitTest/kotlin/BUILD.bazel`) covering the criteria above. The inline `Maximize` button hiding while the strip shows is wired in 4.5e.

##### Task 4.4e: Inline Back ladder and rung tests (S, W11; `TableActionStrip.kt`)
- Depends on 4.4c and 3.1d. A `BackHandler` (Android only, enabled only while a cell is active) and the Esc handler both call `TableBackLadder` for the inline surface and perform the returned action; the platform gets Back when nothing is open (`NotHandled`). `TableInlineBackTest` (new Robolectric class; register as above): one test per rung 2 to 6 (menu or Extend picker or More menu or Why? notice closes and focus returns to its invoker; IME visible hides the IME and stays in `Edit`; `Edit` with the IME hidden: Back is one commit and `Navigate`, Esc is Cancel; range collapses; single cell closes the strip; nothing active is not handled). Rung 1 (inline conflict banner) is tested in 4.5e once the chip exists.


#### Story 4.5: Inline affordances and display
**As a** user, **I want** an obvious way to maximize or fall back to raw markdown, and to see formula values, **so that** I am never trapped.
**Acceptance Criteria**:
- Maximize and raw edit are reachable.
  - *Given* both flags on and a pending inline draft `45` in `CellAddress(2,2)`, *When* the 48dp Maximize button is activated, *Then* the draft is committed first (exactly one commit), then `LocalOpenTableSheetAction` is invoked once with the block's uuid; *When* "Edit as markdown" is chosen, *Then* `onStartEditing` fires and any draft is committed first.
- Ctrl+Shift+Enter maximizes from the inline grid (`[beyond-req]`, UX-10).
  - *Given* both flags on and an inline table in `Navigate` with a pending draft, *When* Ctrl+Shift+Enter is pressed, *Then* the draft commits once and `LocalOpenTableSheetAction` fires once; *Given* the sheet flag turned off, *Then* the chord is not bound (the binding is removed with the flag); with inline edit off it is never bound (flag matrix, Story 4.1).
- Flag matrix (Story 4.1) holds.
  - *Given* each of the four flag combinations, *Then* tap behaviour, strip, Maximize button and chord match the Story 4.1 table (one test per row).
- One editor per block.
  - *Given* `LocalTableSheetOwner` equals this block's uuid, *When* the inline table is tapped or a key arrives, *Then* no cell enters `Edit` and no commit is produced (inline renders read-only, selection cleared); when the owner becomes null the inline table is interactive again and rebuilds from the block content.
- Formula display.
  - *Given* a cell `=B2*2` with `B2=3`, *When* rendered not editing, *Then* the cell text is `6` with an `fx` marker and `stateDescription` containing `=B2*2`; in `Edit` mode the cell shows `=B2*2`.
- Error cells say why.
  - *Given* `=B2/0`, *When* rendered, *Then* the cell shows `#DIV/0!` and `error()` semantics with "Division by zero".
- Formula-shaped text that does not parse keeps its literal text (pre-mortem P2-3).
  - *Given* cells `=5 apples` and `=2024 plan`, *When* rendered, *Then* each shows its literal text with a small error marker and `error()` semantics "Not a valid formula" (never a bare `#PARSE!` replacing the user's words); in `Edit` the literal text is the draft.
- An unparseable table falls back with a reason (UX-44).
  - *Given* flag on and block content `TableParser` rejects (for example a missing delimiter row), *When* rendered, *Then* the grid is not entered, the block shows "This table can't be edited as a grid: missing delimiter row" with an "Edit as markdown" action, and `Logger("TableSheet")` has exactly one entry for the block; with the sheet open the sheet closes with the same message once.
- A very large table renders a capped row window inline (UX-44).
  - *Given* a table of more than 5 000 cells (`INLINE_CELL_WINDOW = 5_000`, distinct from the 10 000 paste cap and the 20 000 formula cap), *When* rendered inline, *Then* the first rows up to that cell budget are composed, a line shows "Showing the first N of M rows" with a "Maximize" action (only "Edit as markdown" when the sheet flag is off), and `collectionInfo` still reports the full counts.
- Inline accessibility semantics (iteration 6, ux.md 3.6b, UX-80).
  - *Given* flag on, *Then* the table keeps `collectionInfo` and `collectionItemInfo`, every cell has an "Edit cell" `onClick` and the custom actions of ux.md 3.6b (Select row, Select column, Select table, Extend selection in five variants, Copy, Undo, Redo, Maximize when the sheet flag is on, Edit as markdown); activating "Edit cell" moves focus into the text field and posts exactly one polite "Editing B3, 3" announcement (none while typing); Done, Cancel, Close and Back return focus to the same cell node; the strip is a traversal group placed directly after the table.
- Block selection and held conflicts are never silent (UX-77, UX-79).
  - *Given* a pending draft `45` and a long-press that enters block selection, *Then* the draft commits once and the strip hides. *Given* a pending draft and `TableSyncPolicy` returning `ConflictKeepDraft`, *Then* the strip shows a Resolve chip, inline commits are held (commit count 0), Resolve opens `TableConflictBanner`, Back dismisses only the banner (chip and draft remain, Back ladder rung 1), and Edit as markdown or "Take theirs" follow ux.md S14 (`RecoveredDraft`).
  - *Given* a mouse or hardware-key `Edit` (`Hardware` profile, strip normally hidden) with draft `45` and `ConflictKeepDraft`, *Then* the reduced strip (Resolve, Cancel edit, Markdown) appears below the table without taking focus and one polite "Your edit to B3 is not saved. Resolve it first" is announced; *When* Enter, Tab, Shift+Enter, the IME action key, Done, Next column, a tap on another cell or a tap outside is used, *Then* the commit count stays 0, the selection does not move, the draft is still `45` and the banner is shown again; *When* Back or Esc dismisses the banner and Esc is pressed again, *Then* Cancel edit runs with its Undo notice; the same holds for a `Touch` edit (UX-84, ux.md rule 15). `TableStripPolicy` gains the `conflictHeld` input and its table test the conflict rows.
- The Maximize button yields to the strip (UX-78).
  - *Given* a cell is active on touch, *Then* the Maximize button is not composed and the strip's Maximize is the entry; *Given* no active cell, *Then* the button is shown again.
- Discoverability (iteration 5, ux.md 3.9, UX-67).
  - *Given* a fresh `Settings` and a flag just turned on, *Then* the one-line note shows under the toggle; *Given* the first table opened afterwards, *Then* the one-time tip H1 shows once, never takes focus, and after dismissal (or a restart) does not return; *Given* "Show tips again" in the shortcuts panel, *Then* the `table_hints_seen` key is cleared and the tips can show once more.
**Files**: `COMMON/ui/components/TableBlock.kt`, `COMMON/ui/components/sheet/TableActionStrip.kt`, `COMMON/ui/components/sheet/FormulaCellDisplay.kt`, `COMMON/ui/components/sheet/TableHints.kt` (new, 4.5d), `ATEST/ui/components/TableInlineAffordancesTest.kt`, `ATEST/ui/components/TableHintsTest.kt` (new, 4.5d), `ATEST/ui/components/TableInlineA11yTest.kt` (new, 4.5e)

##### Task 4.5a: Maximize button (commit-first), strip actions, raw-edit escape, inline Ctrl+Shift+Enter, flag matrix, inline freeze while the sheet owns the block (S)
- Implements the Story 4.1 flag matrix and the inline Ctrl+Shift+Enter handler; `TableFlagMatrixTest` cases live in `TableInlineAffordancesTest.kt`.
##### Task 4.5b: Formula display with `fx` marker, error text and the literal-text marker for unparseable formula-shaped text (S)
- Both use `TableCalc` values; tests registered.
##### Task 4.5c: Unparseable-table fallback and capped inline row window (S, UX-44; `TableBlock.kt` #7, W11)
- Implements the two UX-44 criteria above. Message strings live in `TableMessages` (reasons come from a `TableParseFailure` enum, no strings in `table`). Tests: `unparseableTable_should_ShowFallbackWithReasonAndEditAction_When_ParserFails` and `largeTable_should_RenderCappedWindowWithMaximize_When_Over5000Cells` in `TableInlineAffordancesTest.kt`; register as for 4.5a.
##### Task 4.5d: Discoverability hints and first-run note (S, `TableBlock.kt` #8, W12)
- `TableHints` (one-time tips H1-H3 of ux.md 3.9, `table_hints_seen` in `Settings`, "Show tips again"), the first-run note under each toggle (`EditorSettings` text only), tip rendering above the strip (H1 on the inline table, H2 and H3 are rendered by the sheet through the same component). Non-modal, never moves focus, announced once politely. Depends on 4.5c (previous `TableBlock.kt` editor), 5.4a (the range mode H2 points at) and 4.4c (the hint chip is a slot of `TableOverlayStack`, which makes the notice win over it). Tests: `TableHintsTest` (show once, dismiss, restart, re-enable, no focus change) and the criterion above; the new Robolectric class is registered in `AllAndroidUnitTests.kt` and `kmp/src/androidUnitTest/kotlin/BUILD.bazel`. `TableMessages` gains disjoint entries (W12; the same wave as 5.4b and 5.7a, a third disjoint section the integrator merges).
##### Task 4.5e: Inline accessibility, block-selection wiring, Resolve chip, sticky-policy plumbing (S, `TableBlock.kt` #9, W13)
- Depends on 4.4c, 4.4e, 4.5d, 5.1b and 5.1d. In `TableBlock.kt`: the semantics of the criterion above (cell `onClick` and custom actions, focus entry and the single "Editing B3" announcement, strip traversal group); passes the entry profile (from the pointer type of the starting tap plus the accessibility-service state) once at edit start to `TableStripPolicy`; reacts to `inBlockSelectionMode` (commit then hide on entry); composes the Resolve chip and opens `TableConflictBanner` for `ConflictKeepDraft` (inline commits held); does not compose the Maximize button while the strip shows. Iteration 7 additions: the held-commit gate (every committing key or control re-shows the banner and announces once, UX-84), the reduced strip for a `Hardware` edit via `TableStripPolicy(mode, entryProfile, conflictHeld)` (extends `TableStripPolicyTest`), and the `onStop` commit for an inline draft that is skipped on a configuration change (UX-88). Tests: `TableInlineA11yTest` (new Robolectric class; register in `AllAndroidUnitTests.kt` and `kmp/src/androidUnitTest/kotlin/BUILD.bazel`) for the criteria above, including inline Back-ladder rung 1 and the held-conflict cases in `TableInlineAffordancesTest.kt`.


---

## Phase 3: Maximized sheet

### Epic 5: The sheet
**Goal**: A full-screen Dialog sheet with the whole owner-selected capability list.

#### Story 5.1: Host and presentation
**As a** user, **I want** one action to maximize any table, **so that** I can work on it at full size and close without losing anything.
**Acceptance Criteria**:
- Open from an inline table.
  - *Given* sheet flag on and a page with a table block, *When* the Maximize button is activated (it is shown whenever the sheet flag is on, also with inline edit off; Story 4.1 matrix), *Then* `TableSheetHost` shows a `Dialog` with `usePlatformDefaultWidth=false` titled with the page name and `LocalTableSheetOwner` equals the block uuid; with the sheet flag off, no Maximize button exists.
- Edits are already persisted when the sheet closes.
  - *Given* the sheet open and cell `B2` committed to `7` against a real `BlockStateManager` over in-memory repositories (the `BlockStateManagerTest` harness, not a mock), *When* the sheet closes, *Then* `flush()` was launched on the manager's scope (it is a `suspend fun`; the host calls it from a `DisposableEffect`-launched job on the manager scope, never `rememberCoroutineScope`) and the block content already contains `7`; a pending draft is committed on close, with `composition` cleared first if non-null.
- Undo works from inside the dialog and stays in the table.
  - *Given* a committed edit and focus in the grid in `Navigate` mode, *When* Ctrl+Z is pressed, *Then* `BlockStateManager.undo()` runs once, the grid shows the previous value and `editingBlockUuid` is still null (Task 0.5a); the announcement is the generic "Undone" (the manager keeps no entry labels, ADR-004).
- Undo from the sheet is scoped to the sheet's own entries (ADR-004). The undo stack is page-wide (100 entries), so the sheet keeps `sheetUndoDepth` (commits that changed content since the sheet opened, minus those undone) and `sheetRedoDepth`, and calls `undo()`/`redo()` only while the matching depth is above 0. R3-C3: the entry is recorded only after the DB write (`applyBlockContentUpdate` records after `applyContentChange`, `BlockStateManager.kt:1081-1097`, inside a `scope.launch`), so `TableCommitter` keeps every commit `Job`, issues commits one at a time (each joins its predecessor first, which also makes DB write order equal commit order for sheet commits, R3-C6), and `undo()`/`redo()` join all pending commit `Job`s before reading the depth. Depth is derived from the manager side: a commit counts only when the manager's content for the block changed across the joined `Job` (the `oldContent == newContent` early return records nothing), cross-checked against a new additive, read-only `BlockStateManager.undoRecordCount: StateFlow<Long>` (incremented in `record`, Task 0.5a); depth is clamped to 100 (a capped stack drops the deepest entries, so extra undos beyond it are no-ops and the notice shows).
  - *Given* the sheet just opened over a page whose undo stack holds an unrelated text-block edit `E` (and no sheet commit yet), *When* Ctrl+Z is pressed, *Then* `undo()` is not called, `E` is not reverted, `editingBlockUuid` stays null, and the sheet announces "Nothing more to undo in this table"; *Given* two sheet commits then three Ctrl+Z presses, *Then* exactly two `undo()` calls happen and the third shows the same notice; a new commit clears `sheetRedoDepth`; a commit whose serialized string equals the current content adds no depth (the manager records no entry for it).
  - *Given* an unrelated entry `E` on the stack and a sheet commit whose DB write is still suspended (fake repository gate), *When* Ctrl+Z is pressed immediately, *Then* the sheet waits for the commit `Job`, then undoes the sheet entry, and `E` is not reverted and `editingBlockUuid` stays null (R3-C3); with 101 sheet commits the depth is 100 and the 101st Ctrl+Z shows the notice.
  - *Given* two sheet commits issued back to back with the first DB write delayed, *Then* the DB write order is S1 then S2 and the final row equals the display (R3-C6; inline and foreign writers remain unordered, accepted in ADR-004).
  - *Given* the sheet open and a foreign entry recorded on the page-wide stack (a different block edited, `undoRecordCount` rises without a sheet commit), *Then* the sheet flushes and closes with "The page was edited elsewhere" and Ctrl+Z is not intercepted by the sheet (R3-C4; test: record a foreign entry through `record(...)` while open, assert closed and the foreign entry not undone by the sheet).
  - *Given* the sheet is open and `editingBlockUuid` becomes non-null for any block (a path the depth rule should make unreachable), *Then* the sheet flushes and closes with a one-line message, so the raw editor never shows under the Dialog; a test forces it.
  - Edits made inline before the sheet opened are not undoable from inside the sheet; the user closes it and uses the page's Ctrl+Z (documented in the shortcuts panel). The scope is a `TableUndoScope` (Task 3.2c) that starts at depth 0 on every open: *Given* the sheet closed after two commits and reopened, *Then* Undo is dimmed and no `undo()` call can reach the earlier open's commits (UX-85).
- Undo and Redo are controls, not only keys (iteration 6, UX-70).
  - *Given* the sheet on a phone, *Then* the top bar has labelled 48dp Undo and Redo buttons and the context strip leads with the same two; *Given* `sheetUndoDepth == 0`, *Then* Undo is dimmed (`stateDescription` "Nothing more to undo in this table"), stays focusable, and activating it calls no `undo()` and shows that notice once; *Given* depth 2, *Then* two activations call `undo()` twice, `sheetRedoDepth` becomes 2, Redo is enabled, and two activations call `redo()` twice; every enabled activation announces "Undone" or "Redone"; at 320dp width both move into the overflow menu as its first two items with the same behaviour. Button text names the step ("Undo: edit B3", "Redo: paste") on the top bar, context strip and range strip. In `Edit`, Undo and Redo are field-scoped (the `CellDraft` history), never commit, and are dimmed with "Nothing more to undo in this cell" when empty; table-level Undo needs `Navigate` (UX-86).
- Degraded mode: nothing is ever displayed from a guess (pre-mortem P1-1).
  - *Given* `TableSyncPolicy` returns `Stale` for the same content more than twice in a row, or `sheetUndoDepth` disagrees with `undoRecordCount` after joining the pending commit `Job`s, *Then* the sheet flushes, closes with a one-line message ("The table was reloaded from disk to stay safe"), and a reopen builds from the DB content; tests force each trigger with a fake feed.
- Block lifecycle.
  - *Given* the sheet open, *When* the block is deleted, or its content stops being a `BlockType.Table`, or `TableParser` fails on it, or the page unloads, or the active graph changes, *Then* the sheet closes after flushing, shows "This table was removed" or "This block is no longer a table" once, and no commit is ever issued for a block `findBlockOrNull` cannot find; a regenerated uuid for the same page position (external reload) is treated as removal, not followed.
- One editor per block.
  - *Given* the sheet open for a block, *Then* the inline `TableBlock` for that block is read-only (Story 4.5) and every sheet commit goes through the single `TableCommitter`; interleaved inline, sheet, inline commits yield strictly increasing stored versions (UI space, minted by the manager).
- A failed commit is visible and recoverable (UX-45).
  - *Given* a commit whose `Job` completes without the manager's block content equal to the committed string while the block still exists (or whose `Job` throws), *Then* `TableCommitter` raises `CommitFailed`, the sheet shows the notice "Couldn't save the change" with a Retry action, the `CellDraft` is kept until a retry succeeds, and Retry re-issues the same string with a new `seq` (INFERRED mechanism: `updateBlockContent` returns a `Job` with no failure channel, so failure is detected by comparing manager content after the `Job` joins; test with a fake repository returning `Left`).
- Notices that offer Undo come from one component (UX-24, UX-37, S11, S14).
  - *Given* a paste, a delete that broke formulas, or "Take theirs", *Then* `TableNoticeHost` shows the `TableMessages` text with the action: Undo for paste and delete (routes through `TableCommitter.undo()` and counts against `sheetUndoDepth`), and for "Take theirs" a "Discarded your edit" notice whose Undo restores the discarded draft text into a `CellDraft` (not a block-undo entry, since Take theirs makes no commit); every notice carries at least one action (UX-49).
  - Time limits (WCAG 2.2.1, UX-62; replaces the former 6-second limit). *Given* a notice that carries an action, *When* 60 seconds pass on a test clock with no input, *Then* it is still shown until acted on or dismissed; *Given* an informational notice ("Updated from disk"), *Then* it auto-dismisses after 10 s on the test clock, the timer pauses while the notice has pointer hover or focus, and resumes when they leave.
- Conflict banner is dismissible and non-lossy (S14, UX-68, UX-69).
  - *Given* the banner with a draft `42` in `B3`, *When* Back or Esc is pressed, *Then* only the banner closes, the sheet stays open, the draft is still in `B3`, no commit or write happens, a persistent Resolve chip shows, and further commits are held until a choice is made; Resolve reopens the banner.
  - *Given* an unresolved conflict, *When* "Edit as markdown" is chosen or the sheet is closed, *Then* the draft becomes a `RecoveredDraft` (session memory keyed by block and cell), no file is written behind `GraphWriter`, the raw editor (or page) shows the on-disk content, and a persistent notice offers Copy, Apply to B3 and Discard until one is chosen; Apply commits through `TableCommitter` as one undo step if the cell still exists, else it is disabled with the reason.
- Draft survival (ux.md 3.11, UX-88).
  - *Given* a draft `45` in `B3`, *When* the configuration changes (rotation, fold, resize), *Then* the draft is still `45`, uncommitted, and Cancel edit still restores the original; *When* `onStop` runs without a configuration change, *Then* one commit of `45` happens (no-op if unchanged) with composition cleared; *Given* a conflict-held draft, *Then* `onStop` commits nothing and records a `RecoveredDraft`.
- Notices queue by one rule (ux.md 3.10, UX-87).
  - *Given* a paste Undo notice visible and then a delete-with-`#REF!` Undo notice, *Then* the second queues, the first shows "1 more", and neither Undo is lost; *Given* "Updated from disk" arrives while an Undo notice shows, *Then* it appears only in the live region and status line; the notice host never exceeds two lines (one in landscape) or 25% of the space above the IME.
- Loading and in-flight states are defined (ux.md 3.8, UX-66; implemented in Task 5.9a).
- Read-only is a host parameter.
  - *Given* `TableSheetHost(readOnly = true)`, *When* the sheet opens, *Then* selection and copy work and edit/paste/sort/structure controls are disabled with the reason "This graph is read-only". Nothing sets the parameter today (no active-graph read-only flag exists; see Unresolved Questions), so there is no end-to-end read-only criterion until one does.
- External change policy (ADR-004).
  - *Given* the sheet open with no draft, *When* the block content changes on disk, *Then* the grid refreshes, keeps selection by (row, col) and shows "Updated from disk"; with a pending draft, the sheet shows its own conflict banner (Keep mine / Take theirs / Edit as markdown) and the draft is kept. This is new sheet UI, not the page-level `DiskConflict` dialog (which has no per-block flow).
**Files**: `COMMON/ui/components/sheet/TableSheetHost.kt`, `COMMON/ui/components/sheet/TableSheetScreen.kt`, `COMMON/ui/components/sheet/TableCommitter.kt`, `COMMON/ui/components/sheet/TableConflictBanner.kt`, `COMMON/ui/components/sheet/TableMessages.kt`, `COMMON/ui/components/sheet/TableNoticeHost.kt`, `COMMON/ui/GraphContentActiveShell.kt`, `ATEST/ui/components/TableSheetHostTest.kt`, `ATEST/ui/components/TableNoticeHostTest.kt`, `CTEST/ui/components/sheet/TableMessagesTest.kt` (new, 5.1a), `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/TableSheetRealFeedTest.kt`, `COMMON/table/OwnCommitLedger.kt` (created in 3.2b, consumed here), `COMMON/ui/components/sheet/SheetContextStrip.kt` (created in 5.4a, Undo and Redo added in 5.1e), `ATEST/ui/components/SheetUndoRedoControlsTest.kt` (new, 5.1e)

##### Task 5.1a: Action/commit/owner locals provider, `TableSheetHost`, Dialog, undo/redo key wiring (M)
- Provide `LocalOpenTableSheetAction` and `LocalTableSheetOwner` (`LocalTableCommitAction` is provided only by 4.3c) next to `LocalOpenSketchAction` (`GraphContentActiveShell.kt:350`); host observes only `getBlockByUuid` and closes on `Left`/null/non-Table/graph change (keyed by the active graph id); top bar (close, title, overflow with Edit as markdown, Copy as formulas, shortcuts panel, "Column widths reset when you close the sheet"). Verify with a test whether `onGraphKeyEvent` reaches the Dialog (Unresolved Question 3) and bind Ctrl+Z/Y in the sheet accordingly, gated by `sheetUndoDepth`/`sheetRedoDepth` (Story 5.1). Also creates `COMMON/ui/components/sheet/TableMessages.kt` (every `EditSummary`/`RefusalReason` string and the sheet notices such as "Nothing more to undo in this table", "Updated from disk", "This table was removed"), because 5.1b (W9), 4.4b (W9), 5.4b and 5.7a (W12) all read it; later tasks only add entries. Width: full below 600dp, ~90% at 840dp and above (`LocalWindowSizeClass`); between 600dp and 839dp full screen with the inline edit bar (UX-12). Also adds `CTEST/ui/components/sheet/TableMessagesTest.kt` (new commonTest class: every `RefusalReason`/`EditSummary`/notice value maps to text and at least one action, UX-49; registration via `scripts/kmp-module-tests-sync.py` and `kmp/src/commonTest/kotlin/BUILD.bazel`, the `ui/components/sheet` test directory is new).

##### Task 5.1b: Block sync, flush, `TableCommitter`, lifecycle, conflict banner `[beyond-req]` (justified by the no-silent-data-loss risk, not a stated requirement), `readOnly` parameter (M)
- `TableCommitter` logs one `Logger("TableUsage")` line `via=sheet` per sheet commit (no cell text).
- `TableSyncPolicy` integration: collect `getBlockByUuid`, drop `version` at the host boundary and pass only `content` to `classify(content)`; flush on close and `onStop` (launched on the manager scope); `TableCommitter` (registers each commit in the ledger, serializes commits by joining the previous `Job`, calls `markDone(seq)` when a `Job` completes, derives `sheetUndoDepth` from the manager side, joins pending `Job`s before `undo()`/`redo()` and registers `ledger.expectAdopt` with the resulting content, closes the sheet on an unexplained `undoRecordCount` rise: R3-C1, C3-C6); lifecycle closes; the sheet-owned conflict banner (the only new UI here; Back/Esc dismisses it and leaves the Resolve chip, commits are held while unresolved, Edit as markdown and close hand the draft to `RecoveredDraft`, see Story 5.1); `readOnly: Boolean = false` parameter; commit-pending-draft on close or flag flip; the degraded-mode rule of Story 5.1 (`Stale` more than twice for the same content, or a depth/`undoRecordCount` mismatch: flush, close with a one-line message, reopen from DB content). If `findBlockOrNull` cannot find the block when the pending draft is committed (`applyBlockContentUpdate` returns silently, `BlockStateManager.kt:~1081`), the host logs through `Logger` and shows "Your last edit could not be saved: the table was removed" once. Iteration 7: depends on 3.2c (`TableCommitter` owns the sheet's `TableUndoScope`, starting at 0 on every open, in place of the ad hoc depth counters); `onStop` commits the draft only when the host is not changing configuration (a rotation keeps it in the state holder), and a conflict-held draft goes to `RecoveredDraft`; the held-commit gate re-shows the banner and announces once for every committing key (UX-84).

##### Task 5.1d: `TableNoticeHost`, commit-failure path, Undo-bearing notices (S, W10)
- `TableNoticeHost` (snackbar-like, polite live region, one notice at a time; action-bearing notices persist until acted on or dismissed, informational ones auto-dismiss at 10 s with the timer paused on hover and focus, WCAG 2.2.1), `RecoveredDraft` holder and its persistent notice (Copy, Apply to cell, Discard), `CommitFailed` from `TableCommitter` with Retry, the Undo actions of Story 5.1, and `TableMessages` entries for them. `TableMessages.kt` section disjoint from 5.4b/5.7a. Iteration 7: renders the shared `NoticeQueue` of Task 3.2c (one visible, two lines or one in landscape and at most 25% of the space above the IME, "1 more" control, Retry first, `RecoveredDraft` in its own pinned slot; depends on 3.2c). Tests: `TableNoticeHostTest` (extended with the queue, height and retire cases) plus the `commitFailure_should_ShowRetryAndKeepDraft_When_RepositoryReturnsLeft` case in `TableSheetHostTest`; register.

##### Task 5.1c: Host tests (S)
- Robolectric over the real-manager harness: open/close, flush, undo (stays in the table, and the depth-scoped cases of Story 5.1), block-deleted/not-a-table/graph-switch closes including the pending-draft-on-deleted-block combination, interleaved-commit versions, `readOnly` parameter, external change with and without a draft; register.
- Real-feed test (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/TableSheetRealFeedTest.kt`, jvmTest-reason: real SQLDelight driver; reuses the harness built in Task 0.6a): a real `BlockStateManager` over the SQLDelight repository on an in-memory SQLite database (so the `version = version + 1` counter of `SteleDatabase.sq:317` is real, not simulated) with the host collecting the real `getBlockByUuid`. Two commits issued back to back (`S1`, `S2`) while echoes arrive in whatever order the feed produces: record every `TableSyncPolicy` decision and the displayed content after each emission, and assert the grid never shows `S1` after `S2` was committed, no decision is `Rebuild`, `Adopt` or `ConflictKeepDraft`, and the final display and the DB row both equal `S2`. Repeat with a pending draft (no conflict banner appears) and with undo then redo (each `Adopt`, no banner). Mutation proof, recorded in the task report: temporarily make `OwnCommitLedger` compare the observed DB `version` (or the UI `block.version`) and confirm this test and the skewed-version test of Task 3.2b fail.

##### Task 5.1e: Sheet Undo and Redo controls (S, W12)
- Depends on 5.1b, 5.1d and 5.4a (the context strip). Adds the two buttons to the top bar in `TableSheetScreen.kt` and as the leading items of `SheetContextStrip.kt`; they call the same depth-gated `TableCommitter.undo()`/`redo()` as Ctrl+Z/Y (text "Undo: <step>" from the `TableUndoScope` label stack, in the top bar, context strip and range strip, and field-scoped in `Edit`) (so the sheet's own entries are the only ones touched, ADR-004), render dimmed but focusable when the depth is 0 and show "Nothing more to undo in this table" / "Nothing more to redo in this table" through `TableNoticeHost`, and announce "Undone" / "Redone". `TableMessages` gains disjoint entries. Tests: `SheetUndoRedoControlsTest` (new Robolectric class over the real-manager harness of 5.1c; register in `AllAndroidUnitTests.kt` and `kmp/src/androidUnitTest/kotlin/BUILD.bazel`): disabled state, notice, depth 2 round trip, foreign entry not undone, narrow width moves them to the overflow.


#### Story 5.2: Lazy grid
**As a** user, **I want** 200x12 to scroll smoothly with a sticky header and row/column labels, **so that** large tables are usable.
**Acceptance Criteria**:
- Rows are lazy.
  - *Given* a 200x12 table in a 780dp-high sheet, *When* first composed, *Then* the composed row count is at most visible rows plus 2.
- Header and gutter stay.
  - *Given* the grid scrolled down 100 rows and right 6 columns, *Then* the column letter header row and row-number gutter remain visible and aligned with their cells (one shared horizontal scroll state).
- Edit state survives recycling (unit-testable part).
  - *Given* cell `B150` in `Edit` with draft `abc` (selection 1..2) held in `SheetState`, *When* the grid is scrolled until row 150 leaves composition and back, *Then* `SheetState.draft` is still `abc` with selection 1..2 and the re-composed field shows it; nothing was committed (`updateBlockContent` count 0): the draft lives above the lazy item and commits only on explicit triggers (Enter/Tab/arrow/blur to another cell, close, maximize, Edit as markdown, `onStop`), never on dispose.
- Edit state survives recycling (device-only part).
  - *Given* the same on `Pixel_Fold`, *Then* IME composition, focus and the soft keyboard survive: this cannot be asserted under Robolectric and is a step of Task 8.2a, not a CI test.
- Rotation and process death (UX-14).
  - *Given* the sheet with a selection, scroll offset, view state and a draft `abc`, *When* the activity is recreated (rotation, fold/unfold, or a configuration change), *Then* `SheetState` is restored from its `Saver` (plain data only: selection, mode, scroll offsets, `TableViewState`, draft text and selection) and the draft still reads `abc`; *When* the process is killed while the sheet is open, *Then* at most the text typed since the last `onStop` is lost (the draft commits on `onStop`), and the sheet is reopened only if the shell restores the owner uuid (otherwise it opens closed and nothing is half-applied). Test: `state_should_SurviveConfigChange_When_RecreateActivity`; the process-death part is a recorded manual step in Task 8.2a.
- Keys are stable.
  - *Given* a row inserted above the editing row, *Then* the editing cell stays attached to the same `RowKey` row and no other row receives its `TextFieldValue`.
**Files**: `COMMON/ui/components/sheet/SheetGrid.kt`, `COMMON/ui/components/sheet/SheetCell.kt`, `COMMON/ui/components/sheet/SheetState.kt`, `ATEST/ui/components/SheetGridTest.kt`

##### Task 5.2a: `SheetGrid` layout (M)
- `LazyColumn` of rows keyed by `RowKey`; non-editing cells are plain `Text` with a per-text cached `AnnotatedString` (links render as links, tap resolved by the same hit-test rule as inline); shared `horizontalScroll`; tallest-cell row height; no fixed row height; no `animateItem`.

##### Task 5.2b: `SheetState` hoisting (M)
- `GridSelection`, `GridMode`, `CellDraft`, `TableViewState`, `TableCalc` held in one remembered state class without a coroutine scope; commit lambdas supplied by the host; a `Saver` over its plain-data parts so rotation and fold restore it (Story 5.2). Incorporates Task 0.4a findings (including its pinned-editor fallback if the spike says so).

##### Task 5.2c: Grid tests (S)
- Robolectric: lazy composition count, sticky alignment, draft-state hoisting across recycle (text and selection only, not IME), key stability; register.

#### Story 5.3: Keyboard navigation and editing
**As a** keyboard user, **I want** spreadsheet keys, **so that** I can work without a pointer.
**Acceptance Criteria**:
- Navigate mode keys follow ADR-009.
  - *Given* `Navigate` at `CellAddress(2,2)`, *When* Enter is pressed, *Then* `GridMode` becomes `Edit` with the caret at the end; typing `x` instead replaces the cell content and enters `Edit`.
- Commit-and-move.
  - *Given* `Edit` on `CellAddress(2,2)` with draft `9`, *When* Tab is pressed, *Then* one commit occurs and the selection is `CellAddress(2,3)`; Shift+Enter commits and moves up.
- Alt+Enter inserts a line break.
  - *Given* `Edit` with draft `a`, *When* Alt+Enter is pressed and `b` typed, *Then* committing writes `a<br>b`.
- One Back and Esc ladder in the sheet (ux.md 3.3, UX-71; `TableBackLadder`, Task 3.1d).
  - *Given* a range selected, *When* Esc or Back is pressed in `Navigate`, *Then* the range collapses; *When* Back is pressed again with a single cell, *Then* any pending draft is committed, `flush()` runs and the sheet closes; *When* Esc is pressed with a single cell, *Then* nothing happens and the sheet stays open (UX-93); *When* Ctrl/Cmd+Shift+Enter or the (x) button is used, *Then* the same commit, flush and close runs. (Inline grid: the second Back or Esc closes the strip, Task 4.4e.)
  - *Given* `Edit` with the IME hidden, *When* Back is pressed, *Then* the draft commits once, the mode is `Navigate` on the same cell and the sheet stays open; *When* Esc is pressed, *Then* the draft is restored. *Given* the IME visible, *Then* Back hides it and the mode stays `Edit`.
  - *Given* each of the conflict banner, a header menu, the overflow menu, the shortcuts panel, the Extend picker and the paste fallback box open, *When* Back is pressed, *Then* only that layer closes (banner: draft kept and Resolve chip shown) and focus returns to its invoker; one test per rung, asserting the `BackHandler` consumes the key.
- Enter at the last row and the IME after Enter (UX-89).
  - *Given* `Edit` on the last body row with draft `9`, *When* Enter is pressed, *Then* one commit happens, the table has the same number of rows, the selection stays on that cell in `Navigate` and "End of table" is announced once; *Given* touch, `Edit` on a middle row, *When* the IME action key is pressed, *Then* one commit happens, the selection is the cell below in `Navigate`, the IME is hidden, the edit bar shows the next cell's literal text, and tapping the selected cell opens the IME again.
- Touch equivalents for Enter, Tab and Alt+Enter (UX-82).
  - *Given* the phone edit bar in `Edit` with draft `9`, *Then* the field's IME action is `Next` with Enter semantics (one commit, selection moves down), the edit bar's (...) menu "Next column" commits and moves right, and "Insert line break" inserts `<br>` at the caret (committing `a`, break, `b` writes `a<br>b`); none needs a hardware key.
- Shrinking the window moves the draft, never loses it.
  - *Given* an editing draft in a sheet at 700dp, *When* the window shrinks below 600dp, *Then* the same `CellDraft` is shown in the phone edit bar and no commit occurs.
- Edit bar shows literal text.
  - *Given* active cell `C2` with `=SUM(B2:B5)`, *Then* the edit bar shows `=SUM(B2:B5)` and the cell shows the value.
- IME safety (unit-testable part).
  - *Given* a `CellDraft` with `composing = true`, *When* Enter/Tab/arrows arrive at the key-decision function, *Then* it returns `PassToIme` and the grid consumes none; the Compose key handler is a thin wrapper over that function. Real IME behaviour (CJK, dead keys) is verified in Tasks 8.2a and 8.3a.
**Files**: `COMMON/ui/components/sheet/SheetKeyboard.kt`, `COMMON/ui/components/sheet/FormulaBar.kt`, `COMMON/ui/components/sheet/SheetBackHandler.kt` (new, 5.3d), `ATEST/ui/components/SheetKeyboardTest.kt`, `ATEST/ui/components/SheetBackLadderTest.kt` (new, 5.3d)

##### Task 5.3a: Key handling bound to `TableShortcutMap`/`GridNavigator` (M)
- `onPreviewKeyEvent` on the grid; `isMod` convention from `onGraphKeyEvent`; consumes Ctrl+A/C/X/V/Esc in navigation mode only; wasm Tab trap already exists (`wasmJsMain/.../browser/Main.kt:125`); Wasm edit-mode adjustments from Task 0.3a.

##### Task 5.3b: Edit bar `[beyond-req]` (S)
- Above the IME on phones (`imePadding`, bring-into-view of the active cell), inline top bar at 600dp and above; mirrors the active cell draft.

##### Task 5.3c: Keyboard tests (S)
- Robolectric `performKeyInput`; every shortcut in `TableShortcutMap` has at least one test; register.

##### Task 5.3d: Sheet Back ladder, IME action key, Insert line break and Next column (S, W13)
- Depends on 3.1d, 5.1b, 5.3a, 5.3b and 5.4a. New `SheetBackHandler.kt`: a `BackHandler` (Android) and the Esc handler both call `TableBackLadder` for the sheet surface and perform the result (`DismissBanner`, `CloseMenu`, `HideIme`, `Done`/`CancelEdit`, `CollapseRange`, `CloseSheet`); the host calls it from one line in `TableSheetScreen.kt` that the wave integrator merges. `FormulaBar.kt` (W12, 5.3b) gains the IME action `Next` with Enter semantics and the (...) menu on phones with "Insert line break" and "Next column", dispatching the same `GridCommand` values as Alt+Enter and Tab. Iteration 7: Esc maps to `NotHandled` in sheet `Navigate` on a single cell (it never closes the sheet), Ctrl/Cmd+Shift+Enter (`ToggleMaximize`) closes through the same commit-flush-close path as Back, the edit bar's Cancel edit (revert icon) is distinct from the top bar's Close table (x), and the IME hides after an Enter commit on touch. Tests: `SheetBackLadderTest` (new Robolectric class; register in `AllAndroidUnitTests.kt` and `kmp/src/androidUnitTest/kotlin/BUILD.bazel`), one per rung and per touch equivalent, plus `esc_should_NotCloseSheet_When_NavigateSingleCell` and `chord_should_CloseSheet_When_CtrlShiftEnter`.


#### Story 5.4: Range selection, copy, cut and paste
**As a** user, **I want** to move data in and out as TSV, **so that** Sheets and Excel interop works.
**Acceptance Criteria**:
- Touch range via handles.
  - *Given* a long-press on `CellAddress(2,1)` in the sheet, *When* the focus handle is dragged to `CellAddress(4,2)`, *Then* the selection is `A2:B4`; the handles have 48dp hit areas while drawing 12dp.
- Copy puts TSV of values; slot keeps formulas.
  - *Given* `A2:C2` = `Apple`, `3`, `=B2*2`, *When* Ctrl+C then paste into `A5`, *Then* the system clipboard text is `Apple\t3\t6`, and the in-app paste restores `=B2*2` (slot match) in `C5`.
- Paste from Sheets grows the table.
  - *Given* a 3x3 table, selection `B3`, and clipboard TSV of 8 rows by 5 columns, *When* Ctrl+V runs, *Then* one commit grows the table, announces "Pasted 8 x 5, added 7 rows, 3 columns" via the live region, and one Ctrl+Z restores the original string exactly.
- Single cell fills the selection.
  - *Given* clipboard `0` and selection `B2:B4`, *When* pasted, *Then* all three cells become `0`.
- Cut clears after copy.
  - *Given* `A2:A3` selected, *When* Ctrl+X, *Then* the TSV is on the clipboard and the cells are empty in one undo step.
- Selection stats.
  - *Given* selection of cells `3`, `5`, `x`, *Then* the status line shows "Count 2  Sum 8  Average 4"; a selection containing an error cell appends "1 error", and the status line recomputes at most once per 500 ms during a drag (ux.md S10).
- Paste, transform and clipboard-failure notices have actions.
  - *Given* the 8x5 paste above, *Then* the notice "Pasted 8 x 5, added 7 rows, 3 columns" carries Undo (Story 5.1); *Given* pasted cells containing `|` or a newline, *Then* a second line says how many cells were transformed (`transformedCells`) and "Line break stored as <br>" when a newline became `<br>`; *Given* a clipboard write that fails, *Then* a dialog offers "Show as text" with the TSV selected for manual copy.
**Files**: `COMMON/ui/components/sheet/SheetSelection.kt`, `COMMON/ui/components/sheet/SheetClipboard.kt`, `COMMON/ui/components/sheet/SheetStatusLine.kt`, `ATEST/ui/components/SheetSelectionClipboardTest.kt`, `COMMON/ui/components/sheet/TableMessages.kt`

##### Task 5.4a: Range selection gestures and handles (M)
- Long-press to range mode with two handles (dp), row/column header taps, Shift+click, mouse drag, the "Extend..." picker `[beyond-req]`, a haptic tick on range-mode entry `[beyond-req]`, hinge avoidance where the posture API exists `[beyond-req]` (INFERRED availability, verified in 8.2a); shares the single gesture owner design (ADR-005); non-drag alternatives in the strip (the sheet's context strip is `COMMON/ui/components/sheet/SheetContextStrip.kt`, created here; the Extend picker is the one shared with the inline strip, Task 4.4c).

##### Task 5.4b: Copy/cut/paste, `TableClipboard` port, `TableClipboardSlot` (M)
- `TableClipboardSlot` and the paste flow in `COMMON/ui/components/sheet/SheetClipboard.kt` (the `TableClipboard` port itself is created in Task 7.1a, W3); paste rules from ADR-008; `[beyond-req]` for the slot (formula-preserving in-app paste); `Throwable` guard around parse; above 2 000 cells parse on `PlatformDispatcher.Default` via the host's launch lambda; the transform and clipboard-failure notices above; `TableMessages` entries in this task's own section.

##### Task 5.4c: Status line and announcements (S)
- `SelectionStats`, live region (`LiveRegionMode.Polite`) for paste/sort/undo messages (`TableMessages`, created in Task 5.1a, maps `EditSummary` to text; this task only wires the live region and adds the selection-stats strings; undo/redo announce the generic "Undone"/"Redone" because `BlockUndoManager` entries carry no label, ADR-004).

##### Task 5.4d: Selection and clipboard tests (S)
- Robolectric with a fake `TableClipboard`; register.

#### Story 5.5: Structural operations
**As a** user, **I want** to add, remove and reorder rows and columns from a menu or keys, **so that** I never need to drag.
**Acceptance Criteria**:
- Header and row menus.
  - *Given* a tap on column letter `B`, *Then* a menu lists "Sort rows A-Z (changes file)", "Sort rows Z-A (changes file)", align left/center/right, insert left/right, move left/right, delete, width presets; tapping the row-number gutter lists insert above/below, move up/down, duplicate, delete.
- Ops are one undo each.
  - *Given* "Insert column right" on `B`, *Then* the table has one more column in every row and one Ctrl+Z restores the exact prior string.
- Non-drag reorder by keyboard.
  - *Given* row 3 selected, *When* Alt+Shift+Up, *Then* it swaps with row 2 and `=B2*2`-style references follow the data (ADR-003).
- Guard rails.
  - *Given* the last remaining column, *When* "Delete column" is chosen, *Then* it is refused with the notice "A table needs at least one column"; deleting the header row is refused.
**Files**: `COMMON/ui/components/sheet/SheetMenus.kt`, `COMMON/ui/components/sheet/SheetCommands.kt`, `ATEST/ui/components/SheetStructureTest.kt`

##### Task 5.5a: Header/gutter menus and command dispatch (M)
- Duplicate row is `[beyond-req]`; every `[beyond-req]` task still ships with a test, and the tag marks extras versus gaps for reviewers (so do `COUNTA`/`ROUND`/`ABS`, "Copy as formulas", HTML clipboard format, `SelectionStats`, the shortcuts panel and `Ctrl+Shift+Enter` maximize).
- Bottom sheet below 600dp, popup menu above; also Shift+F10/Menu key; context menu on desktop and web.

##### Task 5.5b: Structure shortcuts and tests (S)
- Ctrl+Shift+= / Ctrl+-, Alt+Shift+Arrows; tests; register.

#### Story 5.6: Resize, alignment, freeze
**As a** user, **I want** to size columns, align them and keep headers in view, **so that** the table reads well.
**Acceptance Criteria**:
- Drag resize with a non-drag alternative.
  - *Given* a header edge with a 48dp hit area, *When* dragged 60dp right, *Then* the column widens 60dp and no content write occurs (`updateBlockContent` call count 0); choosing "Wide" in the header menu does the same without a drag.
- Alignment is written to the file.
  - *Given* column `C` with delimiter `---`, *When* "Align right" is chosen, *Then* the delimiter cell becomes `---:` and only that line changes; Ctrl+Z restores it.
- Freeze (first-column freeze is `[beyond-req]`; requirements ask only for a frozen header row).
  - *Given* the header row frozen (default in the sheet) and first-column freeze toggled on a 360dp phone, *When* scrolling right, *Then* column A stays visible.
**Files**: `COMMON/ui/components/sheet/ColumnResize.kt`, `COMMON/ui/components/sheet/SheetGrid.kt`, `ATEST/ui/components/SheetViewStateTest.kt`

##### Task 5.6a: Resize handle, presets ("Narrow", "Medium", "Wide", "Auto-fit") `[beyond-req]`, alignment command, freeze toggles (M)
##### Task 5.6b: View-state tests (S)

#### Story 5.7: Sort
**As a** user, **I want** to sort rows by a column and undo it, **so that** lists can be ordered safely.
**Acceptance Criteria**:
- Sort is labelled, one undo, header untouched.
  - *Given* `Name` column `Pear, Apple, Fig` and "Sort rows A-Z (changes file)" on that column (the descending item reads "Sort rows Z-A (changes file)"; the TalkBack custom actions use the same two strings), *Then* the order is `Apple, Fig, Pear`, the header is unchanged, the announcement is "Sorted by Name", and one Ctrl+Z restores `Pear, Apple, Fig`.
- One announcement text, built from `EditSummary.Sorted` (ADR-003, ux.md S18, UX-32).
  - *Given* a sort with `crossRowFormulaCount = 2` and `blankCount = 3`, *Then* the announcement is "Sorted by Qty. 2 formulas reference other rows. 3 blank cells sorted last"; each suffix appears only when its count is above 0, so the base case is exactly "Sorted by Name". A sort refused with `SortWouldChangeValues` shows "Sort would change formula results: N formulas reference other rows" with Dismiss and Edit as markdown.
- The menu says what will happen before it happens (pre-mortem P2-3).
  - *Given* a sort whose dry-run `Applied` outcome has `rewrittenFormulaCount = 4`, *When* the sort submenu opens, *Then* its helper text reads "4 formulas will be rewritten" (and "M reference other rows" when `crossRowFormulaCount > 0`) before the user confirms; the dry run is the same pure `TableEditor` call, committed only on confirm.
- Numeric sort by computed value.
  - *Given* a column of `=A2*2` formulas over `3, 1, 2`, *When* sorted ascending, *Then* rows order by `2, 4, 6`.
- Totals rows stay put.
  - *Given* body rows with a trailing `Total | =SUM(B2:B5)` row and no range selected, *When* "Sort rows" runs, *Then* the totals row is excluded and the menu item's helper text says "Totals row stays at the bottom"; *Given* a multi-row selection, *Then* the items read "Sort selected rows A-Z (changes file)" / "Sort selected rows Z-A (changes file)" and only those rows move; a sort refused with `SortWouldSelfReference` shows "Sort would put a total inside its own range" with no change.
**Files**: `COMMON/ui/components/sheet/SheetMenus.kt`, `COMMON/ui/components/sheet/SheetCommands.kt`, `ATEST/ui/components/SheetSortTest.kt` (its own file, so 5.5b and 5.7a never edit one test file in the same wave)

##### Task 5.7a: Sort command (whole body minus trailing totals, or selected rows), pre-apply formula count, indicator, announcement (S)

#### Story 5.9: Loading and in-progress states (iteration 5)
**As a** user, **I want** to see that a large table is opening or a commit is saving, **so that** I never wonder whether the tap worked.
**Acceptance Criteria**:
- Sheet open on a large table (UX-66).
  - *Given* a table of more than 1,000 cells (or a fake parser that takes more than 100 ms on the test clock), *When* the sheet opens, *Then* the top bar and an "Opening table..." indicator show immediately, Close and Edit as markdown are enabled, and the grid replaces the indicator when ready; *Given* a small table, *Then* no indicator is composed.
- In-flight commit.
  - *Given* a commit whose DB write is held by a fake repository gate, *Then* "Saving..." shows in the status line only after 300 ms on the test clock, editing stays enabled, the notice clears on completion, and a failure goes through the existing `CommitFailed` path; *Given* close with a commit in flight, *Then* the dialog shows "Saving..." until `flush()` completes.
- Large paste or sort uses the same 300 ms rule with "Applying...".
**Files**: `COMMON/ui/components/sheet/SheetLoadingState.kt` (new), `COMMON/ui/components/sheet/TableMessages.kt`, `ATEST/ui/components/SheetLoadingStateTest.kt` (new)

##### Task 5.9a: Sheet-open and in-flight-commit indicators (S, W13)
- Pure thresholds (`SHEET_OPEN_INDICATOR_CELLS = 1_000`, 100 ms, 300 ms) in one small holder; parse and first calc run off the main thread (Dispatchers.Default via `PlatformDispatcher`, never `rememberCoroutineScope` for the held state); reads `TableCommitter`'s pending-job state; strings in `TableMessages` (W13 is the only wave touching it). Test class registered in `AllAndroidUnitTests.kt` and `kmp/src/androidUnitTest/kotlin/BUILD.bazel`. Depends on 5.1b, 5.1d, 5.2b.

#### Story 5.8: Formula UX
**As a** user, **I want** formulas to be inspectable and easy to write, **so that** I can total columns without leaving the note.
**Acceptance Criteria**:
- Formula cells are distinguishable.
  - *Given* cells `5` and `=2+3`, *Then* the second shows `5` with the `fx` tint and monospace formula text in the edit bar; the first has no marker.
- Cycle message names the path.
  - *Given* `B2==C2+1`, `C2==B2+1`, *Then* the cells show `#CYCLE!` and the detail text reads "Circular reference B2 -> C2 -> B2" (tooltip on desktop/web, the edit bar's message line when the cell is selected on touch, `error()` semantics).
- Point mode inserts a reference only after `=`, an operator, `(` or `,` (UX-20, UX-75; `PointModeRule`, Task 3.1d).
  - *Given* `Edit` on `D2` with draft `=SUM(`, *When* the user taps `B2`, *Then* the draft is `=SUM(B2` and focus stays in the text field; *When* "Extend..." then "down" is chosen three times (the non-drag path; a handle drag gives the same result), *Then* the draft becomes `=SUM(B2:B5`.
  - *Given* draft `=B2` with the caret right after the reference, or draft `abc`, *When* a cell is tapped, *Then* the tap commits and moves as in ux.md S2 instead of inserting (a tap after a point-mode insert replaces that reference until another character is typed).
  - *Given* point mode armed, *Then* the text indicator "Tap a cell to insert its reference" is shown (after an insert, "Inserted B2. Extend... or type to continue"); *When* **Stop inserting** is activated, *Then* the next cell tap commits and moves and the indicator is gone for the rest of this edit; *Given* draft `=5 apples`, `= total` or `==` with the caret at the end, *Then* point mode is not armed, no indicator shows and a tap commits and moves; Done, Enter and Tab commit while armed (UX-90).
**Files**: `COMMON/ui/components/sheet/FormulaCellDisplay.kt`, `COMMON/ui/components/sheet/FormulaBar.kt`, `ATEST/ui/components/SheetFormulaUxTest.kt`

##### Task 5.8a: `fx` tint `[beyond-req]`, error detail text `[beyond-req]`, literal-text marker for unparseable formula-shaped text (S)
- Error detail has three paths (WCAG 1.4.13, UX-61): hover popup (dismissible with Esc, hoverable, persistent until dismissed or focus moves), selecting the cell on touch (the detail shows in the edit bar's message line and the status line; inline, the strip's Why? action of Task 4.4c; long-press is not used, it is range mode), and keyboard or screen-reader focus of the cell, where the detail shows in the status line and the edit bar with no popup. Test: `errorDetail_should_BeReachableByKeyboardFocusAndDismissWithEsc_When_ErrorCellActive` in `SheetFormulaUxTest.kt`.
##### Task 5.8b: Reference insertion while editing a formula `[beyond-req]`, plus edit-bar monospace formula text (S)
##### Task 5.8c: Point-mode wiring and non-drag reference building (S, W14)
- Depends on 5.8b, 3.1d, 4.4c and 4.3a. Wires `PointModeRule` into the sheet's cell tap handler and the inline `CellEditor.kt`; the Extend... picker extends the reference just inserted (stepwise or to row end, column end, table end), so a range needs no drag; typing another character ends the replace-last-reference state; outside the rule a tap commits and moves. Iteration 7: renders the armed indicator and the **Stop inserting** control (edit bar on the sheet, strip inline; the phone edit bar's (...) menu also carries it) from `PointModeState` (Task 3.1d); tests `indicator_should_ShowWhileArmed_And_StopMakeTapsCommit` and `literalEqualsText_should_NotArm_When_NotFormulaPrefix` in `SheetFormulaUxTest.kt`. Tests: `tap_should_InsertReference_When_CaretAfterOperator`, `tap_should_CommitAndMove_When_CaretNotAfterOperator`, `extend_should_BuildRangeWithoutDrag_When_ReferenceJustInserted` in `SheetFormulaUxTest.kt`, and the inline equivalents in `TableInlineEditTest.kt`.

---

### Epic 6: Accessibility
**Goal**: Every drag, shortcut and gesture has a menu, keyboard or TalkBack alternative; the grid is announced as a grid.

#### Story 6.1: Semantics and alternatives
**As a** TalkBack or keyboard-only user, **I want** the grid announced and every command reachable, **so that** I have equal access.
**Acceptance Criteria**:
- Grid semantics.
  - *Given* the sheet with a header row plus 200 body rows and 12 columns (30 composed), *Then* the container has `collectionInfo(rowCount=201, columnCount=12)` (the header counts as row 1 in `CellAddress` terms, so 1 + 200; full counts, not the lazy window), each cell has `collectionItemInfo(rowIndex, columnIndex)`, header cells are headings, selected cells `selected=true`.
- Formula and error state.
  - *Given* cell `C2` `=SUM(B2:B5)` value 42, *Then* its `stateDescription` is "Formula =SUM(B2:B5), value 42"; an error cell exposes `error("Circular reference")`.
- Custom actions replace drags.
  - *Given* a header cell, *Then* `customActions` include "Move column right", "Move column left", "Resize column", "Sort rows A-Z (changes file)", "Sort rows Z-A (changes file)", "Insert column right" (so the TalkBack action carries the same file-changing warning as the menu); a cell exposes "Edit cell", "Extend selection right/down/to row end/to column end", "Select row", "Select column", "Select all", "Copy", "Paste", "Move row up/down", "Insert row/column".
- Every layer exits by keyboard, pointer and Back; Tab enters the grid once and leaves once (UX-47, UX-50).
  - *Given* each rung of the ux.md 3.3 ladder on both surfaces (conflict banner, menus and panels, IME, `Edit` with the IME hidden, range, strip, sheet), *When* Esc and Back are pressed, *Then* the layer closes and focus returns to the grid; *Given* Tab traversal through the sheet, *Then* the grid is one stop in and one stop out, and the focus ring contrast is at least 3:1 (the sheet's last rung exits via Back, (x) or Ctrl/Cmd+Shift+Enter, and Esc there is a no-op; `everyLayer_should_ExitViaEscAndBack_When_OpenedFromSection3_3List`, `focusRing_should_Contrast3To1_AndTabEnterLeaveOnce_When_TabbedThroughSheet`).
- Large font and long label.
  - *Given* font scale 2.0 and a 120-character cell, *Then* the row grows, nothing clips, every control keeps at least 48dp.
- Reflow, text spacing, forced colors, reduced motion and RTL (ux.md 3.7; UX-63, UX-64, UX-65; Task 6.1d).
  - *Given* a 320dp-wide host (the 320 CSS px reflow case) and the text-spacing overrides, *Then* the top bar wraps into the overflow menu, no cell text clips and the page has no horizontal scroll apart from the grid.
  - *Given* `TableVisualPrefs(highContrast = true)`, *Then* selection, focus, error and `fx` markers are drawn by outline width and glyph and meet 3:1 against the high-contrast tokens; *Given* `reducedMotion = true`, *Then* scroll-into-view is instant and the notice host runs no animation (test: no animation spec used, asserted through the test clock).
  - *Given* `LayoutDirection.Rtl`, *Then* column 1 is rightmost, Right moves toward the lower-numbered column, Home goes to the rightmost cell, TSV copy output is unchanged, and "Align left" still writes `:---`.
- Screen-reader position per platform (ux.md 3.7; UX-60; Task 6.1e).
  - *Given* the matrix of ux.md 3.7, *Then* each row is recorded as verified, unverified or non-goal with its reason in the final report.
  - *Given* the sheet flag and a platform whose screen-reader row is blank, *Then* the flip criteria of requirements.md are unmet for that platform (UX-81): Web and Desktop need the row recorded as verified, or as unverified or fails-with-fallback with the owner's written acceptance that "Edit as markdown" is the supported path there; Android needs TalkBack verified in Task 8.2a, including the inline custom actions of ux.md 3.6b (tested in Task 4.5e).
**Files**: `COMMON/ui/components/sheet/SheetSemantics.kt`, `COMMON/ui/components/sheet/TableVisualPrefs.kt` (new, 6.1d), `ATEST/ui/components/SheetA11yTest.kt`, `ATEST/ui/components/SheetVisualPrefsTest.kt` (new, 6.1d), `CTEST/ui/components/sheet/SheetContrastTest.kt` (new, 6.1b)

##### Task 6.1a: Semantics, custom actions, live-region wiring (M)
##### Task 6.1b: A11y tests (S)
- Includes: every `TableShortcutMap` command is also reachable by menu or custom action (a test iterates the map, the strip and the inline custom actions on both surfaces; UX-53, UX-70, UX-82), the Back-ladder exit test (UX-47, UX-71) and the focus-ring/Tab test of Story 6.1, and `CTEST/ui/components/sheet/SheetContrastTest.kt` (new commonTest class: pure contrast-ratio function over the theme tokens in light and dark, UX-51; registration via `scripts/kmp-module-tests-sync.py` and `kmp/src/commonTest/kotlin/BUILD.bazel`).
##### Task 6.1c: Wasm accessibility-tree check (XS)
- On the web build, inspect the browser accessibility tree for the sheet (Chrome tools); if the canvas grid is not exposed, the "Edit as markdown" plain text field must remain; record the result.
##### Task 6.1d: Reflow, text spacing, forced colors, reduced motion and RTL (M, W14)
- `TableVisualPrefs` (`reducedMotion`, `highContrast`) provided through a `CompositionLocal`; sources: Android animator scale and high-contrast text setting, web `matchMedia('(forced-colors: active)')` and `prefers-reduced-motion` through the existing `wasmJsMain` browser glue (INFERRED mechanism; if the glue cannot read them the preference defaults to off and the report says so), Desktop JVM defaults off (Windows High Contrast UNVERIFIED). RTL: pass `rtl` into `GridNavigator` (3.1b), mirror the grid and gutter, keep logical order for file and TSV. Reflow: the sheet top bar collapses into the overflow menu at narrow width. Tests: `SheetVisualPrefsTest` (new Robolectric class; register in `AllAndroidUnitTests.kt` and `kmp/src/androidUnitTest/kotlin/BUILD.bazel`) covering the three criteria; phone-render PNGs for RTL and 320dp added to 8.1a's set (opened and described). Depends on 6.1a, 3.1b, 5.8a, 5.1e (Undo and Redo become the first two overflow items when the top bar wraps).
##### Task 6.1e: Screen-reader matrix (XS, owner-gated, W15)
- Check and record, per ux.md 3.7: web with NVDA (Windows) and VoiceOver (macOS) if a machine is available; Desktop JVM with Orca on the owner's Linux machine (and NVDA/Narrator if a Windows machine is available); each recorded as verified, unverified (no machine) or fails with fallback "Edit as markdown". iOS VoiceOver and JAWS are recorded as explicit non-goals with the stated reasons. This task is the acceptance gate of UX-81: the sheet flag does not flip to default-on for Web or Desktop while that platform's row is blank. Depends on 6.1c and 6.1d.

---

### Epic 7: Platform clipboard
**Goal**: One port, an adapter per platform, Wasm via the DOM paste event.

#### Story 7.1: Clipboard adapters
**As a** user on any platform, **I want** copy and paste to work, **so that** data moves in and out.
**Acceptance Criteria**:
- JVM/Android adapter.
  - *Given* `ComposeTableClipboard` over a fake `ClipboardManager`, *When* `copy("a\tb", "<table>...")` is called, *Then* `writeHtml` is used where the platform supports it and plain text otherwise; `paste()` returns the text.
- Wasm paste event.
  - *Given* the wasm browser test with a dispatched `paste` event whose `clipboardData` is `1\t2\n3\t4`, *When* the sheet is focused, *Then* the bridge delivers that text to the paste handler once; when no event arrives, the "Paste here" fallback field appears.
- Wiring gates are green.
  - *Given* the new `TableClipboard` implementations, *When* `WiringCompletenessTest` and catalog checks run, *Then* they pass (implementations constructed in a composition root or catalogued).
**Files**: `COMMON/ui/components/sheet/ComposeTableClipboard.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/WasmPasteEventBridge.kt`, `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/browser/WasmPasteEventBridgeTest.kt`, `kmp/src/businessTest/resources/wiring-catalog.txt` (if required), `COMMON/table/TableClipboard.kt` (the port, created by 7.1a), `ATEST/ui/components/ComposeTableClipboardTest.kt` (new, 7.1a)

##### Task 7.1a: `TableClipboard` port and `ComposeTableClipboard` (S)
- Creates `COMMON/table/TableClipboard.kt` (the port, no Compose types) and `COMMON/ui/components/sheet/ComposeTableClipboard.kt` (first file under `ui/components/sheet/`, so the wave integrator hand-places that directory in `ui_main`, Task 1.4a). Wraps the existing `ClipboardManager`/`ClipboardProvider` (copy reuses `ClipboardProvider.writeText`/`writeHtml`; only paste is new, ADR-008); one-file swap path to `LocalClipboard` noted in a one-line comment. Tests: `ATEST/ui/components/ComposeTableClipboardTest.kt` (new Robolectric class; register in `AllAndroidUnitTests.kt` and `kmp/src/androidUnitTest/kotlin/BUILD.bazel`).

##### Task 7.1b: `WasmPasteEventBridge` (M)
- Reaches the common host through a `LocalPasteEventSource: PasteEventSource?` composition local (commonMain interface `PasteEventSource`, default null, provided by the wasm entry point; null means the "Paste here" fallback field). Dedicated collaborator class, not a field on `PlatformFileSystem` (memory: stelekit_wasmjs_platform_layer_pattern); beware field-initializer snapshot-vs-live-reference bugs; detekt does not scan `wasmJsMain`, so add a manual review checklist to the task report.

##### Task 7.1c: Wasm test, wiring catalog and seam gates (S)
- `wasmJsBrowserTest` (real Chrome; run `./scripts/fix-wasm-karma-tooling.sh` first if Karma fails); read `WiringAudit.SEAMS`; add the `wiring-catalog.txt` line (`Seam  EntryPointTest  effect:<label>`) and an entry-point test with `ObservableEffect.assert` if the interface is a seam; otherwise record why not.

---

## Phase 4: Verification and hardening

### Epic 8: Verify
**Goal**: Prove what reading code cannot: pixels, touch, IME, web, Logseq, performance, registration.

#### Story 8.1: Phone renders
**As the** owner, **I want** the layout shown at phone size, **so that** I do not discover a phone-only bug after release.
**Acceptance Criteria**:
- Renders are made, opened and described.
  - *Given* `@Config(sdk=[29], qualifiers="w360dp-h780dp-xxhdpi")` with `GraphicsMode.NATIVE`, *When* `TablePhoneRenderTest` captures: inline table (flag on), active cell editing with IME-height inset, sheet at rest, sheet with a selected range and status line, header menu open, 120-character cell, and font scale 2.0, *Then* PNGs under `kmp/build/outputs/roborazzi/` are opened with Read and the report states per image what is correct and what is not (no claim without opening them).
- Phone vertical budget and strip dock are measured, not eyeballed (UX-72, UX-83).
  - *Given* the 360 x 780dp config with an IME inset of 45% of the height, *When* the sheet is rendered editing a cell, *Then* the test asserts by node bounds that the edit bar, the active row and at least one neighbouring row lie fully above the IME, and that top bar, edit bar, context strip and column header heights sum to at most 50% of the space above the IME; *Given* the inline table in `Edit` with the strip and one notice, *Then* the active row and one more row are fully visible above the stack and the strip's bottom edge equals the IME top; the same at 780 x 360dp landscape with hint chips suppressed. A failed bound fails the test; the PNGs are still opened and described.
- No regression vs the Task 0.1a baseline.
  - *Given* the baseline PNG and the flag-off render, *Then* they are visually identical.
**Files**: `ATEST/ui/components/TablePhoneRenderTest.kt`

##### Task 8.1a: Render tests and open the PNGs (S)
- `export ANDROID_HOME=$HOME/Android/Sdk`; `./gradlew :kmp:testDebugUnitTest --tests "dev.stapler.stelekit.ui.components.TablePhoneRenderTest" -Proborazzi.test.record=true`; register the class. Also renders and asserts the bounds of the criterion above, an inline `Edit` frame with strip, notice and hint chip stacked, the inline `Navigate` strip at the window bottom, the sheet top bar and context strip with Undo and Redo, and landscape 780 x 360dp. Depends additionally on 4.4e, 4.5e, 5.1e and 5.3d.
##### Task 8.1b: Fix findings from the renders (S)
- Repair loop capped at one round; leftover polish reported, not looped.

#### Story 8.2: Device pass
**As the** owner, **I want** touch, IME and posture verified on `Pixel_Fold`, **so that** hit areas and the keyboard behave.
**Acceptance Criteria**:
- Touch and IME facts hold.
  - *Given* the `Pixel_Fold` AVD, *When* a scripted pass runs (tap-edit a cell, drag a range handle, drag a column edge, open the header menu, type with the soft keyboard, scroll the editing row away and back, fold/unfold), *Then* the report records pass/fail per step (including: IME composition, focus and the soft keyboard survive scrolling the editing row 30 rows away and back; a CJK or dead-key composition is not interrupted by Enter/Tab), the active cell is never hidden by the IME, selection, active cell, scroll and draft survive the posture change, and the persisted logs (`filesDir/logs/current.log`) contain no crash (no `adb logcat`).
**Files**: none (report); `ATEST/` only if a regression test is added.

##### Task 8.2a: Pixel_Fold pass (M)
- Wall-clock blocker (emulator). Commands as in Task 0.4a; read `filesDir/logs/*` with `adb exec-out run-as`. Also records the touch-rule steps of ux.md design rule 10 (strip on top of the IME with Cancel edit and Done, Select row then Copy in 3 taps, Back with the IME visible and hidden; validation M-05 steps 12-13) and the process-death step of Story 5.2 (kill the process with the sheet open, relaunch, confirm at most the text since the last `onStop` is lost), the hinge-avoidance check and the iteration-6 steps (validation M-05 steps 14 to 18): landscape IME with the strip (extract UI off), the Back ladder rung by rung on both surfaces, Undo and Redo buttons with TalkBack, an inline TalkBack pass (custom actions, the "Editing B3" announcement, focus return) and a Bluetooth keyboard key pressed during a touch edit (the strip stays); iteration-7 steps (validation M-05 steps 19 to 21): the 3-tap copy-row flow counts one IME show and one hide with no table jump beyond the IME inset (UX-92), backgrounding the app mid-edit commits once while a rotation or fold keeps the uncommitted draft (UX-88), and a mouse click during a touch edit leaves the strip docked (UX-91).
##### Task 8.2b: Early device smoke, inline (S, owner machine, W7)
- After 4.2a/4.3a: on `Pixel_Fold`, flag on, tap-edit an inline cell with the soft keyboard, tap a link, long-press a cell, scroll the page. Pass/fail per step recorded in the task report; a fail blocks the next gesture/grid wave (W8: 4.4a, 5.1a) until fixed or the design is changed. One emulator, one Gradle run at a time (commands as in 0.4a).
##### Task 8.2c: Early device smoke, sheet (S, owner machine, W11)
- After 5.2b: open the sheet, tap-edit a cell with the soft keyboard, scroll the editing row 30 rows away and back (draft, composition, focus, keyboard survive), tap a link, long-press into range mode. Pass/fail recorded; a fail blocks W12 and later gesture/grid tasks. Same constraints as 8.2b.

#### Story 8.3: Web check
**As the** owner, **I want** the web build checked by eye, **so that** Wasm clipboard, keys and layout are real.
**Acceptance Criteria**:
- Web build behaves.
  - *Given* `./gradlew :kmp:wasmJsBrowserDevelopmentRun -PenableJs=true` opened in Chrome at 390 px and 1280 px, *When* the owner flow runs (tap-edit, maximize, paste a Sheets TSV via Ctrl+V, resize a column, sort, undo), *Then* screenshots are taken, opened and described; the paste lands via the DOM event; Tab does not leave the page.
**Files**: none (report)

##### Task 8.3a: Web build pass (S)
- Real CJK IME composition is an owner confirmation (blocker). Also check in Chrome that `Ctrl+Shift+=` and `Ctrl+-` (insert/delete, ADR-009) are not swallowed by the browser's zoom commands; if they are, record it and use the menu alternatives only on Web. Iteration 7: at 390 px check that a `touch` pointer starts a `Touch` edit (strip docked, Markdown pinned and unclipped) and a `mouse` pointer a `Hardware` edit (no strip during `Edit`), and that a mouse event during a touch edit does not move the strip (UX-91; the Compose `PointerType` on Wasm is INFERRED to carry the DOM pointer type).

#### Story 8.4: Logseq compatibility
**As the** owner, **I want** to confirm Logseq reads untouched and edited tables, **so that** the format promise holds.
**Acceptance Criteria**:
- Fixtures opened in Logseq.
  - *Given* the Task 0.2a fixtures (untouched copies and copies edited by the app), *When* the owner opens each in Logseq per the checklist, *Then* each renders as a table, edited rows look correct, `<br>` renders as a line break, formula cells show their literal text, and the result is recorded in ADR-007 as confirmed or amended.
**Files**: `project_plans/table-sheet-editor/implementation/logseq-compat/results.md` (owner or agent records)

##### Task 8.4a: Produce edited fixture copies and the results sheet (S, W5)
- Run the real `TableEditor` (`SetCell`) over each fixture to produce the "after edit" copies, hand to the owner right after 1.1b/1.1c/1.2a land (pre-mortem P2-4), not at the end. Wall-clock blocker (owner).
##### Task 8.4b: Hand the untouched fixtures to the owner (XS, W2)
- The 0.2a fixtures need no engine: give the owner the pages and checklist as soon as they exist so a Logseq finding (for example `*` in `=B2*C2*D2` italicized, `<br>` rendering) arrives before the serializer is built. Results go into `logseq-compat/results.md`. Wall-clock blocker (owner).

#### Story 8.5: Performance
**As a** user, **I want** edits and scrolling on a 200x12 table to stay interactive, **so that** the sheet is usable.
**Acceptance Criteria**:
- Commit cost is deterministic in commonTest.
  - *Given* a generated 200x12 table with a total formula per column, *When* a single-cell commit (apply edit, incremental evaluate, serialize) runs, *Then* `evaluationCount` grew only by the transitive dependents (1 for a column total) and exactly one `RowLine` is dirty (serialized dirty-line count 1, other 200 lines verbatim). No wall-clock value is asserted in commonTest (it flakes on CI and is meaningless on Wasm).
- Commit time is tracked relatively through the existing harness.
  - *Given* the JVM scenario `table-commit-200x12` run through `UnifiedBenchmarkRunner`, *Then* it emits a `BenchmarkResult` with `commit_ms` (median of 200), `evaluation_count` and `serialized_dirty_lines`, which the harness's baseline comparison checks relatively against the committed baseline; the first run only records the baseline and the numbers go in the task report.
- Lazy rows.
  - *Given* the sheet at 780dp, *Then* composed rows at most visible plus 2 (asserted in 5.2c).
- Memory.
  - *Given* 100 snapshots of a 200x12 table in `BlockUndoManager`, *Then* the retained size is recorded.
- Probe.
  - *Given* the harness has no Compose scroll probe (`BenchmarkResult` scenarios are JVM/Wasm runners without a Compose host), *Then* `table-sheet-scroll-edit` is not added and the report says it is unsupported.
**Files**: `CTEST/table/TableCommitCountersTest.kt` (replaces `TableCommitPerformanceTest`), `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/TableCommitBenchmarkTest.kt` (new scenario; registered like the other benchmark tests in `AllJvmTests.kt`)

##### Task 8.5a: Counter tests, relative benchmark scenario, memory measurement (S)

#### Story 8.6: Gates and repo docs
**As a** maintainer, **I want** every repo gate green and the rules documented, **so that** CI and future work stay aligned.
**Acceptance Criteria**:
- Registration and guards pass.
  - *Given* all new test classes, *When* `scripts/kmp-modules-sync.py --check`, `scripts/kmp-module-tests-sync.py`, `scripts/check-bazel-test-listing.sh`, `ModuleManifestGuardTest`, `TableEngineImportDirectionTest`, `WiringCompletenessTest`, `TestPlacementAuditTest` and `TestHygieneAuditTest` run, *Then* all pass with no new baseline entries and no hand-edited generated manifest.
- Builds are green.
  - *Given* a clean tree, *When* `timeout 30m bazel test //kmp:business_tests`, `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY`, `./gradlew testDebugUnitTest`, `./gradlew :kmp:wasmJsBrowserTest` and `./gradlew ciCheck` (through the display wrapper) run one at a time, *Then* all pass (`bazel shutdown` afterwards); a UI-test failure without a confirmed display is reported as unverified, not as a regression.
- iOS is UNVERIFIED.
  - *Given* the iOS target (best effort per requirements; no Bazel path, no clipboard, Dialog or IME verification task), *Then* the final report states it as UNVERIFIED rather than silent.
- Flag default and rollback.
  - *Given* both flags off, *Then* `TableBlockLayoutTest` and `TableBlockScreenshotTest` are unchanged and green.
- Rules are documented.
  - *Given* `CLAUDE.md`, *Then* it gains a short "Table engine and sheet" section (pure `table` package and import guard, one commit one undo, session-only view state, single gesture owner, shortcut conflicts) in the style of the other architecture sections.
**Files**: `CLAUDE.md`, registries listed in the dependency note

##### Task 8.6a: Registration, guards and full green run (S)
- Run the wave integrator checklist; list exact commands and their outputs in the task report (no "done" without output). The same gate list also runs at each PR boundary (PR-A, PR-B, PR-C), so 8.6a in W16 is the last run, not the first.
##### Task 8.6b: Update `CLAUDE.md` rules (XS)

#### Story 8.7: Usability check and outcome measurement (iteration 5)
**As the** owner, **I want** a small real-user check and the outcome numbers, **so that** the hypotheses behind the interaction model are tested before any default flips.
**Acceptance Criteria**:
- A 3-5 person usability check is run and recorded (ux.md 5b).
  - *Given* a build with both flags on and 3-5 participants (the owner plus 2-4 people who use notes on a phone), *When* each does the 5 scripted tasks (fix a cell, copy a row, paste a Sheets TSV, add a column, sort) think-aloud on a phone and on desktop, *Then* the notes list every stall, whether the touch strip with Done/Cancel was found unprompted (H-C), whether the inline tap-edits versus sheet tap-selects split confused anyone (H-A), and whether conflict Back-dismisses was understood (H-B); a stall seen by 2 or more participants becomes a fix task before any default flips.
- Outcome numbers are computed against the baseline.
  - *Given* the Task 0.7a baseline and two weeks of owner use with flags on, *Then* the report states UXM-1 (steps and seconds), adoption (`via=inline|sheet|raw` shares) and fallback-to-raw rate from the local `Logger("TableUsage")` lines, each against its proposed target; a missing baseline is reported as UNMEASURED, not guessed.
**Files**: `project_plans/table-sheet-editor/implementation/usability-results.md` (written when the study runs)

##### Task 8.7a: Usability check and outcome report (S, owner-gated, W15)
- Needs a flag-on build (after 8.1a, 4.5d, 5.9a, 6.1d) and Task 0.7a. The `TableUsage` log lines are emitted by the inline commit (4.3a), `TableCommitter` (5.1b) and the raw-edit path in the `BlockItem` seam (4.3c): one `Logger("TableUsage")` line per table edit with `via=`; local only, no telemetry, no cell content. Counting them is a grep of the persisted log, not new code in this task. Owner-gated (recruiting participants); if no participants are available the report says UNVERIFIED and the flip criteria stay unmet.

---

## Effort estimate

Bases (INFERRED; recalibrate after the first implementer run): class midpoints XS 20k, S 55k, M 130k raw tokens; implementation output share 12% so 1 raw token = 1.48 CU (`1 + 4 x 0.12`, fresh-input-equivalent, cache savings ignored so the figure is an upper bound); verification multiplier x1.75 (spec and quality review per story, `6-verify`, CI reruns). Planning overhead excluded.

| Story | Tasks | Cost classes | Raw tokens | Output share | CU (x1.48) | Wall-clock blocker |
|---|---|---|---|---|---|---|
| 0.1 Baselines | 2 | S, XS | 75k | 12% | 111k | - |
| 0.2 Logseq fixtures | 1 | S | 55k | 12% | 81k | - |
| 0.3 Wasm spike | 2 | M, XS | 150k | 12% | 222k | Chrome session |
| 0.4 Android spike | 1 | M | 130k | 12% | 192k | Emulator |
| 0.5 Undo and commit contract | 1 | M | 130k | 12% | 192k | - |
| 0.6 Real emission trace | 1 | S | 55k | 12% | 81k | - |
| 0.7 Baseline and product assumptions | 1 | S | 55k | 12% | 81k | Owner measures and answers |
| 1.1 Lossless parse/serialize | 4 | M, M, S, S | 370k | 12% | 548k | - |
| 1.2 Edit ops | 3 | M, M, S | 315k | 12% | 466k | - |
| 1.3 TSV codec | 2 | S, S | 110k | 12% | 163k | - |
| 1.4 Module and guard | 2 | XS, XS | 40k | 12% | 59k | - |
| 2.1 Parser | 2 | M, S | 185k | 12% | 274k | - |
| 2.2 Evaluator | 3 | M, M, S | 315k | 12% | 466k | - |
| 2.3 Ref rewriting | 2 | M, S | 185k | 12% | 274k | - |
| 2.4 Corpus and mutation | 3 | S, M, S | 240k | 12% | 355k | - |
| 3.1 Selection/nav and pure ladders | 4 | S, S, S, S | 220k | 12% | 326k | - |
| 3.2 Draft/sync and undo scope | 3 | S, S, M | 240k | 12% | 355k | - |
| 4.1 Flags | 1 | S | 55k | 12% | 81k | - |
| 4.2 Gesture owner | 2 | M, S | 185k | 12% | 274k | - |
| 4.3 Cell editor + seam | 3 | M, S, S | 240k | 12% | 355k | - |
| 4.4 Inline selection and strip | 4 | M, S, S, S | 295k | 12% | 437k | - |
| 4.5 Affordances | 5 | S, S, S, S, S | 275k | 12% | 407k | - |
| 5.1 Host | 5 | M, M, S, S, S | 425k | 12% | 629k | - |
| 5.2 Grid | 3 | M, M, S | 315k | 12% | 466k | - |
| 5.3 Keyboard | 4 | M, S, S, S | 295k | 12% | 437k | - |
| 5.4 Select/clipboard | 4 | M, M, S, S | 370k | 12% | 548k | - |
| 5.5 Structure | 2 | M, S | 185k | 12% | 274k | - |
| 5.6 Resize/align/freeze | 2 | M, S | 185k | 12% | 274k | - |
| 5.7 Sort | 1 | S | 55k | 12% | 81k | - |
| 5.8 Formula UX | 3 | S, S, S | 165k | 12% | 244k | - |
| 5.9 Loading states | 1 | S | 55k | 12% | 81k | - |
| 6.1 Accessibility | 5 | M, S, XS, M, XS | 355k | 12% | 525k | Owner machine for the screen-reader matrix (6.1e) |
| 7.1 Clipboard adapters | 3 | S, M, S | 240k | 12% | 355k | - |
| 8.1 Phone renders | 2 | S, S | 110k | 12% | 163k | - |
| 8.2 Device pass | 3 | M, S, S | 240k | 12% | 355k | Emulator (owner machine) |
| 8.3 Web check | 1 | S | 55k | 12% | 81k | Chrome session; owner for CJK IME |
| 8.4 Logseq compat | 2 | S, XS | 75k | 12% | 111k | Owner opens Logseq |
| 8.5 Performance | 1 | S | 55k | 12% | 81k | - |
| 8.6 Gates and docs | 2 | S, XS | 75k | 12% | 111k | CI queue |
| 8.7 Usability and outcomes | 1 | S | 55k | 12% | 81k | Owner recruits 3-5 participants; two weeks of use |

**Totals** (recounted by script over the `##### Task` headings and this table, see repair-log.md Iteration 6): 97 tasks (29 M, 60 S, 8 XS), 7 230k raw tokens, 10 697k CU before verification (the sum of the 40 story rows); with the x1.75 verification multiplier about 18.7M CU. Size band: XL (starts above 15M). Iteration 7 added one task (3.2c `TableUndoScope` and `NoticeQueue`, S), +55k raw over the previous 96 tasks / 7 175k / 18.6M; every other iteration-7 decision amends an existing task. Iteration 6 added seven tasks, all S (3.1d pure Back ladder and point-mode rule, 4.4c strip completion, 4.4e inline Back ladder, 4.5e inline accessibility and conflict chip, 5.1e sheet Undo and Redo, 5.3d sheet Back ladder and touch equivalents, 5.8c point-mode wiring), +385k raw over the previous 89 tasks / 6 790k / 17.6M. Iteration 5 added six tasks (0.7a baseline and product questions, 4.5d hints, 5.9a loading states, 6.1d reflow/forced colors/motion/RTL, 6.1e screen-reader matrix, 8.7a usability and outcomes), +370k raw over the previous 83 tasks / 6 420k / 16.6M. Iteration 4 added six tasks (0.6a emission trace, 4.5c unparseable fallback and capped window, 5.1d notice host and commit-failure path, 8.2b and 8.2c early device smokes, 8.4b untouched-fixture hand-off), +295k raw over the previous 77 tasks / 6 125k / 15.9M. Informational, not a gate; the plan pursues full scope.

**Critical path in agent waves** (16 waves, re-checked after Iteration 7 from the dependency graph; every task sits in a wave strictly after all of its dependencies, and `TableBlock.kt` is edited by exactly one task per wave):
1. W1: 0.1a, 0.1b, 0.2a, 0.3a, 0.4a, 0.5a (green, test and fix in one commit), 0.7a (owner-gated), 1.3a, 2.1a, 4.1a; integrator step 1.4a (places `table/`, `table/formula/`)
2. W2: 0.3b, 0.6a, 1.1a, 1.3b, 1.4b, 2.1b, 2.2a, 8.4b (owner-gated)
3. W3: 1.1b, 2.2b, 3.1a, 3.2a, 7.1a (first `ui/components/sheet/` file; integrator places it), 7.1b
4. W4: 1.1c, 1.1d, 1.2a, 3.1b, 3.1d, 3.2b, 3.2c
5. W5: 1.2b, 2.2c, 2.3a, 4.2a (`TableBlock.kt` #1), 8.4a (owner-gated)
6. W6: 1.2c, 2.3b, 3.1c, 4.2b, 4.3a (`TableBlock.kt` #2)
7. W7: 2.4a, 2.4b, 4.3c (`TableBlock.kt` #3, `BlockItem.kt`), 8.2b (owner-gated)
8. W8: 2.4c, 4.3b (needs 4.3a and the `LocalTableCommitAction` provider of 4.3c), 4.4a (`TableBlock.kt` #4), 5.1a (creates `TableMessages.kt`)
9. W9: 4.4b, 4.5a (`TableBlock.kt` #5), 5.1b, 5.2a
10. W10: 4.5b (`TableBlock.kt` #6), 5.1c, 5.1d, 5.2b, 4.4c (`TableActionStrip.kt`, not `TableBlock.kt`)
11. W11: 4.5c (`TableBlock.kt` #7), 5.2c, 5.3a, 5.4a, 5.5a, 5.6a, 4.4e (`TableActionStrip.kt`), 8.2c (owner-gated)
12. W12: 4.5d (`TableBlock.kt` #8), 5.3b, 5.3c, 5.4b, 5.5b, 5.6b, 5.7a, 5.8a, 5.1e, 6.1a
13. W13: 4.5e (`TableBlock.kt` #9), 5.3d, 5.4c, 5.4d, 5.8b, 5.9a, 7.1c
14. W14: 5.8c, 6.1b, 6.1c, 6.1d, 8.1a, 8.3a (owner-gated), 8.5a
15. W15: 6.1e (owner-gated), 8.1b, 8.2a (owner-gated), 8.6b, 8.7a (owner-gated)
16. W16: 8.6a (last: full green run after every fix has landed)
(Longest chain: 0.1a -> 1.1a -> 1.1b -> 1.1d -> 4.2a -> 4.3a -> 4.3c -> 5.1a -> 5.2a -> 5.2b -> 5.3a -> 5.3b -> 5.8b -> 8.1a -> 8.1b -> 8.6a, 16 waves; it was 12 before the schedule was corrected, because the original waves placed 4.3c with 5.1a, 5.8a with its dependency 5.3b, 6.1a with its dependencies and three `TableBlock.kt` tasks in W8. Spikes 0.3a and 0.4a land before 7.1b/4.2a/5.2b; 0.5a lands before 4.3a/4.3c/5.1a. Round 2: 4.3b moved to W8, `TableMessages.kt` moved from 5.4c to 5.1a, 0.5a/0.5b merged.)

**Simplification trigger (pre-mortem P1-1)**: if Tasks 5.1b or 5.1c need a third repair round, stop and record the decision whether to simplify the sync machinery (sheet-exclusive commits, or undo disabled inside the sheet and available only on page close) before continuing. Report the delta and the cause, not a cut invitation; the decision is the owner's, recorded in ADR-004.

**Overrun checkpoint**: if cumulative spend on completed stories exceeds the table CU by more than 25% (for example Epic 1 above 1 545k CU), pause, report the delta and cause (wrong band versus real surprise), then continue. Informational, not a cut invitation.

**Wall-clock blockers** (owner in parentheses):
- Tasks 0.5a and 0.6a are agent-only (in-memory and SQLite harnesses, no device).
- Emulator passes: Tasks 0.4a, 8.2b (W7), 8.2c (W11) and 8.2a on `Pixel_Fold` (owner machine; one emulator at a time). A fail in 8.2b or 8.2c blocks the next gesture/grid wave.
- Owner opens the Logseq fixtures and records results: untouched pages in Task 8.4b (W2), app-edited copies in Task 8.4a (W5) (Tyler).
- Owner confirms in writing the wording of the flag-on touch behaviour before Task 4.4a starts (Story 4.2, recorded in ADR-005) (Tyler).
- Owner confirms real CJK IME composition in a cell on Wasm and Android, Tasks 0.3a/8.3a (Tyler).
- CI queue for `./gradlew ciCheck`, Bazel and `wasmJsBrowserTest`.
- Owner confirms flag-on behaviour on device before any follow-up PR flips a default (Tyler); the flip criteria are in requirements.md (Outcome Metrics and Flag-Flip Criteria).
- Task 0.7a (W1): owner runs the table-count command, the stopwatch baseline and answers the persona/platform/assumption questions (Tyler).
- Task 8.7a (W15): owner recruits 3-5 participants for the usability check and supplies two weeks of flag-on use for the outcome numbers (Tyler).
- Task 6.1e (W15): screen-reader checks on a Windows or macOS machine if available; otherwise recorded UNVERIFIED (Tyler). It is the gate of UX-81: the sheet flag does not flip to default-on for Web or Desktop while that platform's row is blank.
- Besides the owner-gated items above, no scope decision is open; the scope decisions are fixed.

Bands and weights INFERRED; recalibrate after the first implementer run.
