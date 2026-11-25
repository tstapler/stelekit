# Requirements: table-sheet-editor

**Date**: 2026-11-24
**Type**: feature addition
**Complexity**: 3 — system design (several epics, new editing model over an existing renderer, round-trip data-format decisions)

## Problem Statement
SteleKit renders GFM pipe tables read-only. The renderer already wraps cell text and routes link taps (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TableBlock.kt`, `TableModel.kt`), but the owner cannot work with the data: a tap anywhere opens the whole block as raw markdown, cell text cannot be selected and copied, and there is no spreadsheet-style way to edit a table of any real size. Raw pipe-table editing is the slowest part of the table experience; Logseq has no better answer.

## Baseline
- Wrapping cells and working link taps: shipped (TableBlock layout, `LocalUriHandler`, `WikiLinkText`). Must not regress.
- Edit: tap opens the block as raw markdown text; no per-cell edit, no row/column operations, no paste of a range.
- Copy: no cell or range selection; the owner copies by editing the raw block.
- Tall cells: the owner reported clipping on older builds; the current layout measures the tallest cell per row. Verify on a phone and the web build before and after (regression anchor).
- **Measured baseline: UNMEASURED today.** No count of tables in the owner's graph and no time or step count per cell edit exists, so "slowest part of the table experience" is an owner observation, not a measurement. Phase-0 Task 0.7a measures both before any outcome claim is made:
  - Table count and size, from the graph directory (counts GFM delimiter rows; run once per graph, `<graph>` is the graph root):
    `rg -c --glob '*.md' '^\s*(-\s+)?\|\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$' <graph> | awk -F: '{n+=$2; f++} END {print n " tables in " f " files"}'`
  - Cost of a cell edit today: stopwatch protocol on a phone and on desktop, 5 trials each, changing one cell in a 5 x 4 table through the raw-markdown editor; record steps (taps and keystrokes) and seconds; report the median.

## Users / Consumers
The owner and SteleKit users on Desktop (JVM), Android, Web (Wasm/JS) and iOS (best effort; iOS has no Bazel path). Graph files stay Logseq-compatible: the same `.md` opens in Logseq.

**Primary persona and job (owner assumption, to confirm in Task 0.7a):** the owner as a notes-based tracker, someone who keeps small-to-medium data tables inside notes (lists, comparisons, simple budgets). Job to be done: "When a value in a note's table is wrong or a row needs adding, I want to fix it right where I am, on whichever device I'm holding, without rewriting pipe syntax, so the note stays the single place the data lives." Secondary: the occasional fixer (tidies a table now and then), and the pasting user (brings a block from Google Sheets or Excel).

**Primary platform ranking (owner assumption, to confirm):** 1) Android phone (`Pixel_Fold`), where raw pipe editing is worst and touch design is hardest; 2) Desktop JVM; 3) Web; 4) iOS (best effort). The ranking decides which platform's behaviour wins a design conflict (for example the touch strip rule in ux.md design rule 10) and which device pass gates a default flip.

## Success Metrics
- Inline: tapping a cell edits that cell in place (no raw-markdown detour); links in a non-editing cell still open on tap; cell text and ranges can be selected and copied.
- Maximized sheet: reachable from any inline table in one action; supports keyboard grid navigation (arrows/Tab/Enter/Esc), range selection, copy/paste of TSV/CSV ranges, add/remove/reorder rows and columns, column resize, column alignment, sort, frozen header row, and formulas.
- Round trip: parse → edit → serialize is lossless for untouched cells (property test over generated tables); a table the app never edited is byte-identical after load/save.
- Formulas stored as literal `=SUM(B2:B5)` cell text; the app displays the computed value; no hidden state.
- No regression of wrap/links, verified by `TableBlockLayoutTest` / `TableBlockScreenshotTest` plus a phone-size render and the web build checked by eye.

## Outcome Metrics and Flag-Flip Criteria
The Success Metrics above are acceptance criteria. These are the outcome measures; baselines come from Task 0.7a, and every target is a proposal (INFERRED) for the owner to confirm.

| Outcome | Measure | Target |
|---|---|---|
| Faster cell edit | Median steps and seconds to change one cell, flags on versus baseline, phone and desktop | At most half the baseline on each (UXM-1 in design/ux.md section 5b) |
| Adoption | Share of table edits made through inline or sheet versus raw markdown, from local `Logger("TableUsage")` lines (`via=inline|sheet|raw`; no telemetry leaves the device) | At least 70% inline or sheet after two weeks of owner use |
| Fallback-to-raw rate | Share of edits that use "Edit as markdown" while the flags are on | Below 20% (UXM-3) |
| Trust | Round-trip fixtures open in Logseq, untouched tables byte-identical, zero data-loss reports | 18 of 18 fixtures pass (M-12); zero lost drafts in the study (Task 8.7a) |

**Flip criteria, per flag (each flip is its own PR; the owner decides):**
- `table_inline_edit_enabled` to default on: M-11 passes on the primary platform (Android phone), the 3-5 person study (Task 8.7a) shows no stall seen by 2 or more people, fallback-to-raw is below 20% and the adoption target holds over two weeks of owner use, and Logseq fixtures pass (M-12).
- `table_sheet_enabled` to default on: all of the above for the sheet, plus M-14 and Task 8.2a pass, the web build is checked (Task 8.3a), no open BLOCKER in the sheet's sync or undo behaviour (ADR-004), and the screen-reader gate (ux.md 3.7, UX-81): Task 6.1e has recorded the Web and Desktop rows as verified, or as unverified/fails-with-fallback with the owner's written acceptance that "Edit as markdown" is the supported path there.

## Prioritisation and Why Now
ICE (Impact x Confidence x Ease, each 1-10; scores are the author's INFERRED estimates, not evidence): Impact 7 (daily friction for a table-heavy notes user, zero for others), Confidence 5 (the engine is pure and testable; touch, IME and Wasm behaviour are not yet proven on devices), Ease 2 (XL, 97 tasks): ICE 70 of 1000. RICE's Reach is the number of tables the owner edits per week, which only Task 0.7a can supply, so RICE is deferred until that count exists. Ranking against other backlog items is an owner decision and is not done here.

Why now (INFERRED from repository history named in this document, not from a demand measurement): the read-only renderer has just stabilized (wrapping, link taps #721, trailing-text #724, tap-versus-selection race #240), the Dialog-host and Robolectric phone-render patterns this feature needs already exist from the sketch editor, and the lossless-model groundwork is cheapest before more table-adjacent features land.

## Risky Product Assumptions
| # | Assumption | Test or spike | Evidence lands in |
|---|---|---|---|
| A1 | Spreadsheet behaviour (formulas, sort, freeze) is wanted inside an outliner, not just cell editing | Task 0.7a counts tables with numeric columns and asks the owner which of {edit cell, paste range, sort, formulas} they would use weekly; Task 8.7a observes use | Flag-flip decision for `table_sheet_enabled`; informs ordering, not scope |
| A2 | A literal `=SUM(B2:B5)` is acceptable when the file is opened in Logseq or another markdown app | Fixtures 0.2a and Tasks 8.4b/8.4a (owner opens them in Logseq) | M-12 results, ADR-007 |
| A3 | Inline tap = edit, sheet tap = select is learnable despite the inconsistency | Task 8.7a, hypothesis H-A | Study notes |
| A4 | Users find the Maximize button, the strip and long-press range mode without help | Task 8.7a, hypothesis H-C, plus the hints of ux.md 3.9 | Study notes |
| A5 | The phone is where most table edits happen (platform ranking) | Task 0.7a owner question | Persona note above |

## Size
XL (about 18.7M CU after the iteration-7 patches; 18.6M after iteration 6; 17.6M after iteration 5; 16.6M after iteration 4; was Large 3–15M) — informational estimate for all agents incl. review/verify (bands INFERRED, see ESTIMATION.md). Full scope is the default; not a gate.

**Wall-clock blockers**: device/emulator passes for touch behaviour (selection handles, IME, hit areas) on the `Pixel_Fold` AVD (two early smokes, then the full pass); CI runs. Owner-gated: the Phase-0 baseline measurement and persona/platform confirmation (Task 0.7a), the 3-5 person usability study (Task 8.7a), the screen-reader checks that need a Windows or macOS machine (Task 6.1e), opening the Logseq fixtures (untouched early, app-edited copies after the serializer), confirming real CJK IME composition on Wasm and Android, confirming the flag-on touch behaviour wording before the inline selection work starts, and confirming flag-on behaviour on device before any default flips.

## Constraints
- Kotlin Multiplatform commonMain first; platform code only where an API forces it.
- Persist through the existing block write path (`BlockStateManager` → `GraphWriter`), never writing markdown behind it. Pipe cells must keep `\|` escaping (`splitTableRow`).
- Touch targets in dp (`pxPerDp`), not raw pixels.
- Tests: pure logic in commonTest; Compose behaviour in androidUnitTest (Robolectric); new test classes registered per CLAUDE.md and the module-test generator (`scripts/kmp-module-tests-sync.py`).
- Bazel is canonical; keep new sources inside the existing module manifest rules.

## Non-functional Requirements
- **Performance SLO**: editing a cell and scrolling a 200-row × 12-column table stay at interactive frame times on a mid-range phone; formula recalculation incremental, no O(cells²) recompute per keystroke. No absolute frame-time gate (it cannot be tested reliably on CI or Wasm): the plan asserts deterministic counters (incremental `evaluationCount`, one dirty `RowLine` per commit, composed rows at most visible plus 2) and a relative baseline through the existing benchmark harness; frame smoothness is checked by eye on device.
- **Scalability**: tables up to a few thousand cells; lazy row composition in the maximized sheet.
- **Security classification**: internal (user notes). Formulas must not execute anything beyond a closed arithmetic/aggregate function set; no network, no file access.
- **Data residency**: no special requirements.

## Scope
### In Scope
- Inline: tap-to-edit-cell in place, select/copy of cell text and ranges, link taps preserved, a one-action entry to maximize.
- Maximized sheet editor: keyboard grid navigation; single and range selection; copy/paste (TSV/CSV in and out, interop with Google Sheets/Excel clipboard); insert/delete/reorder rows and columns; column resize; column alignment (writes GFM `:---:` markers); sort; frozen header row; formulas (`=SUM`, cell and range references, arithmetic) with an error display for cycles and bad references.
- Markdown round trip, undo/redo for table edits, accessibility labels and keyboard-only reachability.
- Extras beyond this list are tagged `[beyond-req]` in `implementation/plan.md` (clipboard slot, duplicate row, `COUNTA`/`ROUND`/`ABS`, Copy as formulas, HTML clipboard, `SelectionStats`, shortcuts panel, Ctrl+Shift+Enter, width presets and auto-fit, reference insertion while editing, first-column freeze, the sheet conflict banner, the edit bar, `fx` tint and per-error detail text, the "Extend..." picker, haptic tick, hinge avoidance); the tag marks them for reviewers and does not remove them.

### Out of Scope
- Charts, pivot tables, conditional formatting, multi-sheet workbooks, cell merging.
- Non-GFM table syntaxes; Logseq query tables.
- Real-time collaboration.
- Persisting view-only state (column widths, freeze, sort order) in a way other markdown apps would misread — see Open Questions.

## Rabbit Holes
- Text selection across many `WikiLinkText` cells (platform selection containers, taps vs selection gestures; see the recent tap-vs-selection race fix, #240).
- IME/composition inside a cell on Android and Wasm; focus handling when the grid scrolls.
- Where view state lives (column width, freeze, sort): markdown has no place for it; persisting it risks Logseq incompatibility.
- Sort rewrites row order in the file (a data change, needs undo).
- Formula semantics: reference rewriting when rows/columns are inserted, deleted, sorted or reordered; cycle detection; locale-dependent number parsing.
- Cells that contain rich markdown or `[[wiki links]]`: editing raw text vs rendered display; multi-line cell content in a pipe table (`<br>`).
- Large paste performance and clipboard permission differences on Web.
- iOS has no Bazel path and limited test reach.

## Alternatives Considered
- A third-party grid or spreadsheet library: rejected by default per the project's preference for custom Compose renderers; research should confirm no KMP option fits.
- Edit only in the maximized sheet, keep raw edit inline: rejected by the owner (inline cell edit chosen).
- Store formulas with a cached value or in a hidden property: rejected by the owner (literal formula in the cell).

## Feasibility Risks
- Wasm/JS clipboard and IME behaviour.
- A shared selection model across inline and maximized views.
- Formula reference stability under structural edits.

## Observability Requirements
Standard `Logger` logging for parse/serialize failures and formula errors (cycle, bad reference, parse error), surfaced in the UI as a cell error, not a crash. No metrics beyond the existing probe catalog; add a probe for maximized-sheet scroll/edit frame time if the benchmark harness supports it.

## Risk Control
- Ship behind a feature flag or settings toggle for the maximized sheet and inline cell edit (default off until the owner confirms on device), with raw-markdown edit always reachable.
- Rollback: disable the flag; the file format is unchanged because formulas are plain cell text and untouched tables serialize byte-identically.

## Open Questions
- Where should column widths, freeze and sort live: in-memory per session only, a block property, or nowhere? (Research: what Logseq tolerates.)
- Formula function set beyond SUM, AVERAGE, MIN, MAX, COUNT, IF, and arithmetic (resolved in ADR-002: `SUM AVERAGE MIN MAX COUNT COUNTA IF ROUND ABS`); case-insensitivity; number locale.
- Reference style when rows/columns move: shift references automatically (Sheets behaviour) or leave literal?
- Cell-level undo granularity vs the existing block undo stack.
- Selection model: reuse platform text selection vs a custom range selection with its own copy path.
- Maximize presentation per platform: full-screen dialog, a separate route, or an in-place expanded block.
