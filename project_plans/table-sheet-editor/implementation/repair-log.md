# Repair log: table-sheet-editor plan

Inputs: `architecture-review.md` (AR) and `adversarial-review.md` (ADV). Docs-only edits to `plan.md`, ADR-001/002/003/004/006/007/008, `design/ux.md`, `requirements.md` (size line). Labels: VERIFIED = read in code or run; INFERRED = reasoning only.

## Code facts checked first
- VERIFIED (read `BlockStateManager.kt:1080-1100`): undo and redo closures end with `requestEditBlock(...)`; `BlockItem` shows the raw editor for the editing block.
- VERIFIED (read `:1128-1130`): `_blocks` is replaced only when `stored.version <= version`; undo re-applies `oldVersion`. INFERRED that this leaves stale content after undo (not run). Test-first task 0.5a settles it.
- VERIFIED (read `BlockUndoManager.kt:18-21`): `UndoEntry` has no label.
- VERIFIED: `BlockItem.kt:105,165,250` use `localVersion` / `++localVersion`; `insertTextAtCursor` mints `block.version + 1` (`:935`); `flush()` is `suspend` (`:1231`).
- VERIFIED (grep): `DiskConflict` (`ui/AppState.kt:276`) is page-level, no per-block flow; no active-graph read-only flag in `commonMain`.
- VERIFIED: `kmp/modules_manifest.bzl` has no `table/` or `ui/components/sheet/` entries; `ParseTableContentTest` is in `jvmTest`; benchmark harness is `benchmark/BenchmarkTypes.kt` plus `jvmTest/.../UnifiedBenchmarkRunner.kt` (non-Compose).
- Not run: no code was compiled or tested; this is a docs-only pass.

## Blockers
| Finding | Change |
|---|---|
| AR B1 / ADV B1: undo opens raw editor; no `BlockStateManager` task; ADR-004 "no change needed" false | New Story 0.5: Task 0.5a (failing-first characterization test) and 0.5b (`restoreEditFocus: Boolean = true`, fresh-version re-apply when false); Tech Debt Disposition row for `BlockStateManager` (isolate via seam); `BlockUndoManager` row reworded; ADR-004 corrected; 4.3, 5.1 criteria assert `editingBlockUuid` stays null; `LocalTableCommitAction` routes inline commits through the `false` path (provided in 4.3c). |
| ADV B2: version guard may block undo display | Covered by 0.5a (asserts content after undo and redo) and 0.5b (fresh `block.version + 1`); noted as INFERRED until 0.5a runs; instructions to drop the version part if the test passes. |
| ADV B3: `TableSyncPolicy` echo race | Redesigned on `OwnCommitLedger` (bounded states plus in-flight versions): `Echo`, `Stale`, `Adopt` (undo/redo), `Rebuild`, `ConflictKeepDraft`. Story 3.2 gains out-of-order, undo-then-echo and stale-after-rebuild criteria; Task 3.2b now M; glossary and ADR-004 updated. |
| ADV B4: `FormulaShaped` vs `=#REF!*2` | `#REF!` added to the expression-start set (glossary, ADR-002, Task 2.1a); Story 2.1 criterion that `=#REF!*2` stays a formula and re-parses to `RefError`; Story 2.4b property for delete-then-reparse. |

## Architecture concerns
| Finding | Change |
|---|---|
| 5.4c/5.7 "Undid: Sort rows" with no label | Dropped labels: generic "Undone"/"Redone" (ADR-004, plan 5.1/5.4c, ux.md S18). Reason: a label API on `BlockUndoManager` is extra surface for one string. |
| Version contract (`(String, Long)`) | Settled before 5.1b: inline keeps `BlockItem` `++localVersion`; sheet uses one `TableCommitter` (`max(observed, lastMinted)+1`); single live editor; interleaved-commit test (5.1c, 4.3). Unresolved Question 6 closed. |
| `TableSyncPolicy` out-of-order | See ADV B3 row. |
| Package cycle `table` <-> `table.formula` | `FormulaReferenceTransform` fun interface in `table`; `RefRewriter` in `table.formula` implements it; `TableEditor` receives it by constructor; `TableEngineImportDirectionTest` gains the `table` must not import `table.formula` rule with a planted test (1.4b, ADR-001, ADR-003). |
| Rewrite alters untouched formulas (AR, ADV) | Token-splice rewrite, null for unchanged formulas; 2.3 criterion and 2.4b property; `FormulaPrinter` only for new nodes; token spans on nodes (2.1b). |
| 1.1 `CellAddress(3,1)` example, `after`/`before` | Example fixed to `CellAddress(2,1)` with `lineIndexOf`; anchor convention `before` everywhere; `InsertRows(before=2)` in 1.2. |
| Wave schedule | Re-derived from the dependency graph: 16 waves (was 12, which hid violations); every task is after its dependencies; `TableBlock.kt` one task per wave (4.2a W5 ... 4.5b W10); 3.1c now after 2.2c; 5.3a/5.3c in different waves; dependency diagram updated. |
| Manifest placement of `ui/components/sheet/` | 1.4a and the serialization paragraph place `table/`, `table/formula/`, `ui/components/sheet/` and any other new directory by hand as the one allowed exception, in the same commit; "no push without clean `--check`" added (also ADV minor on ordering). |
| `TableEditResult` encodes refusal as unchanged doc | Sealed `TableEditOutcome` (`Applied` / `Refused`) with structured `EditSummary`/`RefusalReason`; strings in `ui_main` `TableMessages`; glossary and 1.2 criteria updated; `CellDraft` glossary no longer says `TextFieldValue`. |

## Adversarial concerns
| Finding | Change |
|---|---|
| `<br>` / escape normalization | `RowLine.rawCells`; only changed cells re-escaped; generator in 1.1c and a 1.1 criterion include `<br/>`, `<BR>`, `<br />`, literal `\\|`. |
| Inline grid and sheet both write | `LocalTableSheetOwner`: inline read-only while the sheet owns the block; opening the sheet commits the inline draft first (4.5a, 5.1 criteria). |
| Open-sheet lifecycle | 5.1 criterion: delete, non-table, parse failure, page unload, graph change close the sheet after flush, no write to a missing block; uuid regenerated treated as removal. |
| DiskConflict / read-only claims | Not real per-block: sheet-owned conflict banner (new UI, sized in 5.1b, now M); read-only reduced to a `readOnly` host parameter that nothing sets (ADR-006, plan, ux.md S13/UX-41/43). |
| Sort with totals rows; descending order | ADR-003 and Story 1.2/5.7: trailing totals rows excluded by default, or sort only selected rows; `Refused(SortWouldSelfReference)`; blanks/errors last both directions; ux.md rows added. |
| `#LIMIT!` vs incremental == full | ADR-002: per-formula operand-read budget (10 000) independent of pass size; over the 20 000-cell cap no `TableCalc`, banner, editing allowed; 2.2 criterion and 2.4b property at the limit. |
| Commit on dispose vs IME | Chose hoisted draft with explicit commit triggers, no commit on dispose (ADR-004, 5.2, ux.md); composing draft is committed with composition cleared at close/`onStop`. |
| Untestable criteria | 16 ms gate replaced: deterministic counters in commonTest plus a relative `BenchmarkResult` scenario via the existing harness (8.5); IME survival split into a unit-testable hoisting test (5.2c) and a device step (8.2a); IME key rule split into a pure `PassToIme` decision (5.3) plus 8.2a/8.3a; 400 ms frame clock named as `mainClock.autoAdvance = false` + `advanceTimeBy` (4.2); 5.1 uses the real-manager harness; `collectionInfo` counts the header (6.1). |
| `Throwable` guard / recursion | Parser depth 64 is the real bound; printer, rewriter, `TabularText` get the nesting tests (2.1, 2.2c, ADR-002). |
| Scope drift | `[beyond-req]` tags on 5.8b, 5.6a presets/auto-fit, duplicate row and the clipboard slot, with a note that each still has a test. |
| Task 1.4a ordering | See manifest row above. |
| Minors | iOS marked UNVERIFIED (8.6); `ParseTableContentTest` stated as jvmTest behind the display wrapper (1.1d); 0.1a told to read `BlockParser` first; `=B2*C2*D2` italics fixture added to 0.2a and ADR-007; mutation/corpus evidence location stated (2.4c); task count recounted by script. |

## Nitpicks
| Finding | Change |
|---|---|
| `TableClipboard` vs `ClipboardProvider` | ADR-008: copy reuses `ClipboardProvider`; `LocalPasteEventSource` composition local reaches the Wasm bridge (7.1b); the port moves to Task 7.1a (W3) so 4.4b/5.4b can depend on it. |
| Read-only source | See concerns row (parameter only). |
| `StackOverflowError` on Wasm | Stated in 2.2c and ADR-002. |
| Two editors on one block | Covered by the single-owner rule. |
| `TableEditor.kt` God file | Split into `StructuralOps.kt`, `SortOp.kt`, `PasteOp.kt` behind a facade (1.2). |

## Counts and estimate
Recounted by script over the task headings and the table: 78 tasks (27 M, 45 S, 6 XS), 6 105k raw, 9 035k CU, about 15.8M CU with the x1.75 multiplier, so the band is XL (requirements size line updated). The old 76 / 5 825k / 15.1M figures were wrong (the story rows summed to 5 845k, and Story 5.4 was 20k low).

## Not changed, and why
- Research docs (`research/pitfalls.md` still says "commit on dispose"): left as historical research; plan and ADR-004 supersede it.
- No Compose scroll probe: the harness has no Compose host, so the plan records it as unsupported rather than inventing one.
- The `Dialog`-receives-`onGraphKeyEvent` question stays open on purpose; it needs Task 5.1a's test.

---

# Iteration 2 (round-2 findings: adversarial R2-B1 and concerns, architecture round 2)

Inputs: `adversarial-review.md` Round 2, `architecture-review.md` Round 2. Docs-only; nothing compiled or run. Edits: `plan.md`, ADR-003, ADR-004, ADR-006, `design/ux.md`, `requirements.md` (size line).

## Code facts re-checked first (all VERIFIED by reading)
- `SteleDatabase.sq:316-317`: `updateBlockContent` sets `version = version + 1`, a DB-row counter.
- `BlockStateManager.kt:777-783`: the comment says that counter "cannot be matched against the UI-local keystroke version".
- `BlockEditorPorts.kt:19`: `fun updateBlockContent(blockUuid, newContent, version): Job` is declared on `BlockEditingPort`.
- `BlockUndoManager.kt:16`: `maxHistory = 100`; `findBlockOrNull` (`BlockStateManager.kt:894`) is private, so the sheet cannot read a UI version itself.

## Blocker
| Finding | Change |
|---|---|
| R2-B1 `OwnCommitLedger` mixes DB and UI version spaces | Named the observed flow (`getBlockByUuid`, DB space) and removed versions from the policy entirely: `TableSyncPolicy.classify(content: String)` has no version parameter; the ledger is keyed by content hash plus length with its own monotonic `seq`, states `InFlight`/`Acked`, `currentSeq`. New classification order (displayed -> `Echo`; in-flight older than `currentSeq` -> `Stale`, else `Echo`; acked/loaded -> `Adopt`; else external). Story 3.2 rewritten with a skewed-DB-version equivalence test (fails if a version is read), conflation, late-echo-after-adopt, 100-deep undo and eviction cases; Task 3.2b updated; new real-feed test `TableSheetRealFeedTest` in Story 5.1/Task 5.1c (real manager, real SQLDelight in-memory repository, two quick commits, assert no regress and no `Rebuild`/`Adopt`/`ConflictKeepDraft`) with a recorded mutation proof (key the ledger on the DB or UI version, confirm both tests fail). ADR-004 gained a "Version spaces" paragraph and the new policy text. Commit versions: the manager now mints the stored UI version for table commits (`max(passedVersion, current.version + 1)`), so `TableCommitter` no longer mints (glossary rewritten). |
| Ledger size (concern) | 128 entries (undo 100 plus redo and in-flight). Storing hashes, not strings, so memory is trivial; eviction reads as external, which is lossless because rebuild uses observed content; a hash collision only turns `Rebuild` into `Adopt`. Tested at 100 and 129 states. |

## Wave and task defects
| Finding | Change |
|---|---|
| 4.3b and 4.3c both in W7, 4.3b needs 4.3c | 4.3b moved to W8; diagram now `4.3a + 4.3c --> 4.3b`. |
| `TableMessages.kt` created in 5.4c (W13), used in W9/W12 | Created in Task 5.1a (W8), listed in Story 5.1 Files; 5.4c only wires the live region and adds stats strings; serialization paragraph states who owns it. |
| 5.5b and 5.7a both edit `SheetStructureTest.kt` in W12 | 5.7a now owns `SheetSortTest.kt`; 5.5b keeps `SheetStructureTest.kt`. |
| 5.3b -> 5.3c arrow | Diagram: `5.3a --> 5.3b ; 5.3a --> 5.3c` (5.8b still needs 5.3b). |
| 0.5b omits `BlockEditorPorts.kt`; 0.5a red in W1 | 0.5a and 0.5b merged into one Task 0.5a (M): test written and run red on the unmodified manager (output pasted), then fix, then green, one commit, so no red wave. Files and the serialization paragraph now list `BlockEditorPorts.kt` (default on the interface, override cannot redeclare it; callers verified source-compatible). Every `0.5b` reference updated. |
| Minor: `LocalTableCommitAction` listed in 5.1a and 4.3c | Removed from 5.1a; only 4.3c provides it. |

## Concerns
| Finding | Change |
|---|---|
| Ctrl+Z in the sheet pops the global stack and can open another block's raw editor | Chose scoping over blocking: `TableCommitter` keeps `sheetUndoDepth`/`sheetRedoDepth` (content-changing commits since open); the sheet calls `undo()`/`redo()` only while the matching depth is above zero, otherwise "Nothing more to undo in this table". Backstop: `editingBlockUuid` non-null for any block closes the sheet after flush. Story 5.1 criteria, 5.1c tests, ADR-004, ux.md updated. Cost: inline edits made before opening are undone after closing (documented). INFERRED that an unchanged-content commit records no undo entry (early return); 5.1c tests it. |
| Inline `++localVersion` stale after undo | Inline commits route through `LocalTableCommitAction` with `localVersion` as a lower bound; the manager stores `max(passed, current.version + 1)`; fallback is `onContentChange(s, max(++localVersion, block.version + 1))`. Tests: undo then immediate inline commit (Story 0.5 and 4.3). |
| Totals detector misses `=B2+B3+B4` | Detection by dependency: a trailing body row with a formula referencing a cell in a different body row, all above it; extends upward while each row qualifies (ADR-003, Story 1.2 cases `=B2+B3+B4`, `=AVERAGE(B2:B4)`, `=B2`, and `=B5*C5` not detected). |
| Own-row range and column-delete shrink rules (round-2 note) | ADR-003/Story 1.2: sort never rewrites ranges; a range touching the own row but not the own cell stays literal; one that would contain the own cell is refused. Story 2.3: `=SUM(B2:C5)` minus B -> `=SUM(B2:B5)`, `=SUM(B2:B5)` minus B -> `=SUM(#REF!)`, `=SUM(A2:B5)` minus B -> `=SUM(A2:A5)`. |
| Commit-pending-draft on a deleted block dropped silently | 5.1b logs through `Logger` and shows "Your last edit could not be saved: the table was removed"; the combination is added to 5.1c. |

## Counts (recounted by script over `##### Task` headings and the estimate table)
77 tasks (28 M, 43 S, 6 XS), 6 125k raw tokens, 9 065k CU, about 15.9M CU with x1.75 (XL, was 78 / 6 105k / 15.8M). Every one of the 36 story rows matches its tasks; the 16-wave list contains all 77 task ids exactly once. Wave count unchanged at 16.

## Not changed, and why
- Round-1 and round-2 review files are left as written (history).
- ADR-006's action type keeps `(BlockUuid, String, Long)`; the `Long` is now documented as a lower bound.
- Not run: no tests exist yet; every new criterion is a specification, not a result.


# Iteration 3

Inputs: `adversarial-review.md` Round 3. Docs-only; nothing compiled or run. Edits: `plan.md` (Story 3.2, Task 3.2b, Story 5.1 undo criteria, Tasks 0.5a and 5.1b), ADR-004.

## Code facts re-checked first (VERIFIED by reading)
- `BlockStateManager.kt:1064`: `updateBlockContent` is a `scope.launch`; `:1081-1097`: `applyContentChange` (DB write awaited) runs before `record(...)`, so the undo entry exists only after the write. `:605-606`: `undo()`/`redo()` return the `Job` of a launched pop.

## Findings
| Finding | Change |
|---|---|
| C1 (HIGH) undo result vs late echo | `ledger.expectAdopt(content)` registered by `TableCommitter.undo()/redo()` after joining; an unexpected `Acked` match is `Stale` while any `InFlight` exists; host re-classifies last content after registering. Scripted tests "undo then immediate re-commit" and "Tab, Tab, Ctrl+Z" added to Story 3.2 and Task 3.2b. |
| C2 "retired" | Defined as `Acked`; one entry per hash, re-commit overwrites with a new `seq`; echoing a state acks all older `InFlight`; LRU-by-`seq` eviction. Test: S1, S2 in flight, only S2 seen, undo, S1 seen gives `Adopt`. |
| C3 depth vs recording lag | `TableCommitter` joins pending `Job`s before undo, derives depth from manager-side content change, adds `undoRecordCount: StateFlow<Long>` to Task 0.5a, clamps depth to 100. Test with a gated DB write. |
| C4 foreign entries | Unexplained `undoRecordCount` rise while open flushes and closes the sheet; test via `record(...)`. |
| C5 dropped commits | `markDone(seq)` on `Job` completion acks the entry; test in Story 3.2. |
| C6 write order | Sheet commits serialized (join predecessor) so DB order equals commit order; inline/foreign writers accepted limitation in ADR-004; real-feed test asserts DB equals display. |

## Not changed
No task ids, waves or counts changed: all work lives inside Tasks 0.5a, 3.2b and 5.1b (W1, W4, W9; 5.1b's dependency on 0.5a is already satisfied). Not run: no tests exist yet.


# Iteration 4 (phase 4 patches)

Inputs: `pre-mortem.md` (P1-1, P1-2, P2-3, P2-4, P3-5) and `consistency-review.md` (B1-B4, C1-C5, N1-N3). Docs-only; nothing compiled or run. Edited: `plan.md`, `validation.md`, `design/ux.md`, `requirements.md`, ADR-001/003/004/005/007/008/009, and one-line supersession notes in `research/{pitfalls,features,stack,ux}.md`.

## Pre-mortem
| Finding | Change |
|---|---|
| P1-1 sync/undo machinery | New Story 0.6 / Task 0.6a (S, agent-only): real manager + SQLDelight emission trace recorded to `implementation/emission-trace.md`; it depends on 0.5a so it sits in W2 (not W1 as the pre-mortem wrote), still ahead of 3.2b (W4), whose scripted cases now cite it. Task 3.2b adds the randomized model test (invariants: display never older than the latest sheet commit; no committed content lost) with a mutation proof (MP-10). Story 5.1 adds the degraded-mode criterion (`Stale` more than twice, or depth vs `undoRecordCount` mismatch: flush, close, reopen from DB); Task 5.1b implements it. Simplification trigger (third repair round of 5.1b/5.1c) added to the Overrun section and ADR-004. |
| P1-2 late device reality | Task 8.2b (W7, after 4.2a/4.3a) and Task 8.2c (W11, after 5.2b): the pre-mortem named one task 8.2b with two runs; one id per wave slot is needed, so it is two tasks. Fail blocks the next gesture/grid wave. Go/no-go rules written into Tasks 0.3a (Wasm: view-only plus Edit as markdown, drop 5.3a Wasm part and 7.1b) and 0.4a (pinned editor outside the `LazyColumn`). Story 4.2 gains "touch drag starts no range, platform selection only" and the owner-confirmed touch wording before 4.4a (ADR-005 PENDING, wall-clock blocker). |
| P2-3 silent sort/format meaning change | Story 1.2: property for unmoved order-insensitive formulas, `Refused(SortWouldChangeValues)` for the not-sure case (a moved non-totals formula with a single-cell cross-row reference), pre-apply count; this narrows ADR-003's "others stay literal" and ADR-003 now says so. Story 4.5/5.8: unparseable formula-shaped text shows its literal text with a marker. Task 2.4a corpus gains `=5 apples`, `=2024 plan`, `==`, `=#nope`. Story 5.7: "N formulas will be rewritten" shown before confirm. |
| P2-4 delivery timing | Three PRs (A engine, B inline, C sheet), gate list of 8.6a run at each PR boundary. Owner Logseq checks split: new Task 8.4b (XS, W2, untouched fixtures); Task 8.4a moved W7 to W5 (edited copies). |
| P3-5 `BlockStateManager` seam | Task 0.5a: default-path characterization pack (`BlockStateManagerDefaultPathTest`) before and after, diff budget, `undoRecordCount` split into its own commit if exceeded. |

## Consistency review
| Finding | Change |
|---|---|
| B1 Shift+Enter | Story 4.3 and Task 4.3a now: Alt+Enter inserts `<br>`, Shift+Enter commits and moves up (ADR-009, ux.md). Research lines carry "Superseded by ADR-009". |
| B2 totals detector | Task 1.2b now states the ADR-003 dependency rule and the `=B2+B3+B4`, `=B2*2+B3`, `=B2`, `=AVERAGE(B2:B4)` cases. |
| B3 Esc | Story 5.3: second Esc (single cell, `Navigate`) commits any draft, flushes and closes the sheet; inline keeps "leaves grid focus". |
| B4 flag matrix | Story 4.1 has the 2x2 table (tap, strip, Maximize button, Ctrl+Shift+Enter, Edit as markdown) and Task 4.5a one test per row; ux.md S1 table and S17 updated; sheet on + inline off shows the Maximize button as the only entry. |
| C1 stale statements | ux.md formula error text now lists `SUM AVERAGE MIN MAX COUNT COUNTA IF ROUND ABS`; ADR-001 "about 30 files"; supersession notes at pitfalls.md:32/79, features.md:33/42, stack.md:43, research/ux.md:166/168. |
| C2 terminology | "Edit as markdown", "Spreadsheet view", "Maximize" (button, strip, tooltip, capped-window action), "Select row/column/table", "Sort rows A-Z (changes file)" / "Sort rows Z-A (changes file)" (menu, TalkBack actions, ADR-007, UX-32), "Auto-fit" applied across plan, ux.md, ADRs, validation. "Edit bar" vs `FormulaBar.kt` left as is (file name only). |
| C3 requirements | Perf wording is counters plus relative baseline, no absolute gate; wall-clock blockers list the owner-gated items; function-set open question points to ADR-002; size 16.6M CU. |
| C4 scope drift | `[beyond-req]` added to first-column freeze, conflict banner, edit bar, `fx` tint and error detail, "Extend..." picker, haptic tick, hinge avoidance; requirements.md In Scope names them. |
| C5 sort text | `EditSummary.Sorted(column, blankCount, rewrittenFormulaCount, crossRowFormulaCount)` defined in the glossary; ADR-003, Story 5.7, ux.md S18/UX-32 quote one string with optional suffixes. |
| N1 coverage gaps | UX-44: Task 4.5c (fallback message plus capped inline window, `INLINE_CELL_WINDOW = 5_000`, three size thresholds documented in Task 2.2c). UX-45 and Undo-bearing notices: Task 5.1d (`TableNoticeHost`, `CommitFailed` with Retry, Take-theirs undo of the draft). `EditSummary` gained `formulasBroken` and `Pasted.transformedCells`. Rotation/process death: Story 5.2 criterion, `SheetState` `Saver`, process-death step in 8.2a. Also added: inline Ctrl+Shift+Enter handler (Story 4.5), window shrink below 600dp, UX-47/UX-50 tests, clipboard-failure and transform notices, status-line error count; ux.md section 6 items resolved (Enter moves down; strip shows once a cell is active). |
| N2 | Inline Enter enters `Edit` on the next cell (Story 4.3); 600-839dp width stated in Task 5.1a; TalkBack action list aligned in Story 6.1; the 500 ms debounce and "1 error" in Story 5.4. |
| N3 | ADR-008 example now anchored (8 x 5 at B3 into 3 x 3: added 7 rows, 3 columns); `PASTE_CELL_CAP = 10_000` boundary test pinned (10 000 applies, 10 001 refused); `OwnCommitLedger.kt` and `TableClipboard.kt` added to the Files lines; Chrome zoom-chord check added to Task 8.3a and ADR-009. ADR-006's `onContentChange` wording and ADR-004's `expectAdopt` ordering text not changed (plan.md is authoritative). |

## New test classes owned by tasks
validation.md marks 15 classes `(new)` (the instruction said 14; recounted by script). All 15 are now named in their owning task's Files or task text with registration notes, plus 3 added this iteration (`BlockStateManagerEmissionTraceTest` 0.6a, `BlockStateManagerDefaultPathTest` 0.5a, `TableNoticeHostTest` 5.1d): 18, listed in validation.md "New test classes and owning tasks".

## Counts (recounted by script over `##### Task` headings, the estimate table and the wave list)
83 tasks (28 M, 48 S, 7 XS; was 77), 6 420k raw tokens, 9 502k CU, about 16.6M CU with x1.75 (XL). Added tasks: 0.6a, 4.5c, 5.1d, 8.2b, 8.2c, 8.4b. Every one of the 37 story rows matches its tasks; the 16-wave list contains all 83 ids exactly once; wave count unchanged at 16. No existing task id changed; 8.4a moved W7 to W5. validation.md test rows 191 to 217 (Unit 117, Integration 56, Property 22, Mutation 10, Guard 5, Real-feed 5, Failing-first 2), manual items M-01..M-15.

## Not changed, and why
- Round 1-3 review files, pre-mortem.md and consistency-review.md are left as written (history).
- Not run: no tests exist yet; every new criterion is a specification, not a result. The three-PR split and the 5 000-cell inline window are plan decisions awaiting owner review.


# Iteration 5 (triad repair 1)

Inputs: triad readiness findings (product and UX lenses; `triad-product.txt`, `triad-ux.txt`). Docs-only; nothing compiled or run except one `rg` command syntax check (74 delimiter rows in 17 files when pointed at this project directory). Edited: `design/ux.md`, `requirements.md`, `implementation/plan.md`, `implementation/validation.md`, ADR-005 (one sentence).

## UX blocker: touch inline flow
| Finding | Change |
|---|---|
| Strip "not editing" vs first tap edits vs no Esc on phones vs UX-05 3 taps vs "strip would overlap IME" | One rule (ux.md design rule 10): on touch the strip is visible in `Navigate` and `Edit`, leading with Done and Cancel in `Edit`, docked to the IME top edge; every other strip action commits the draft first (one undo step), then acts; Back with IME hidden = Done, with IME visible = hide IME; hardware keyboard or mouse: strip in `Navigate` only. Propagated to S2, S3 (flow, placement, edge rows), 3.2 state machine, 3.3 layers, section 6 (the old "not editing" decision is replaced), UX-05, UX-06, new UX-57..UX-59, plan Story 4.4 criteria and Task 4.4a (`TableStripPolicy`), Task 4.3a (`commitDraft()`), Story 4.2 owner wording, ADR-005, validation rows UX-05/06/57-59 and M-05 steps 12-13 / M-19. |

## UX gaps
| Gap | Change |
|---|---|
| Screen readers beyond TalkBack | ux.md 3.7 matrix: TalkBack verified; web NVDA/VoiceOver and Desktop NVDA/Narrator/Orca UNVERIFIED with "Edit as markdown" fallback; iOS VoiceOver and JAWS explicit non-goals with reasons. New Task 6.1e (owner-gated), UX-60, M-17. |
| WCAG 1.4.13, 2.2.1, 1.4.10/1.4.12, forced colors, reduced motion | ux.md 3.7, design rule 11, UX-61..UX-64; Story 5.1 time-limit criterion (action notices persist, info notices 10 s with pause; replaces the 6 s Undo), Task 5.1d, Task 5.8a (keyboard-focus path for error detail), new Task 6.1d (`TableVisualPrefs`, reflow, text spacing). |
| RTL and localisation | RTL implemented (mirrored columns and arrows, logical file/TSV order) in 3.1b `rtl` flag and Task 6.1d, UX-65; English-only v1 stated as an explicit non-goal with `TableMessages` as the seam. |
| Loading and in-flight states | ux.md 3.8, UX-66, Story 5.9 and new Task 5.9a. |
| Discoverability | ux.md 3.9 hints H1-H3 and first-run note, UX-67, Story 4.5 criterion and new Task 4.5d. |
| S14 conflict Back and lossy pending draft | Back/Esc dismisses the banner (Resolve chip, commits held); Edit as markdown or close while conflicted stores a `RecoveredDraft` with a persistent Copy / Apply / Discard notice; UX-43, UX-68, UX-69, Story 5.1 criteria, Tasks 5.1b/5.1d. Process death still loses it, stated as a known limit. |
| UX success metrics and validation | ux.md 5b (UXM-1..UXM-5), new Task 8.7a (3-5 person study, owner-gated), M-18. |

## Product gaps
| Gap | Change |
|---|---|
| No measured baseline | requirements.md Baseline marked UNMEASURED with the delimiter-row `rg` command and the stopwatch protocol; new Task 0.7a (W1, owner-gated), REQ-29, M-16. |
| Persona, JTBD, platform ranking | requirements.md Users section (owner assumption, to confirm in 0.7a). |
| Outcome metrics and flip criteria | requirements.md "Outcome Metrics and Flag-Flip Criteria" (steps/time, adoption via local `Logger("TableUsage")` lines, fallback-to-raw, trust) and per-flag flip criteria; usage lines added to Tasks 4.3a, 4.3c, 5.1b and the Observability Plan. |
| ICE/RICE and why now | requirements.md "Prioritisation and Why Now" (ICE scores marked INFERRED, RICE deferred until 0.7a gives Reach). |
| Product assumptions untested | requirements.md "Risky Product Assumptions" A1-A5 each with a test or spike; REQ-30. |

## Stale arrow
plan.md line 177 already read `5.2b + 3.1b --> 5.3a --> 5.3b ; 5.3a --> 5.3c` (fixed in Iteration 3 per the table above; `grep` for `5.3b --> 5.3c` finds only history in architecture-review.md and this log). No change needed.

## Counts (recounted by script)
89 tasks (29 M, 52 S, 8 XS; was 83), 40 estimate-table story rows summing to 89 tasks and 6 790k raw tokens, 10 049k CU, about 17.6M CU with x1.75 (XL). Added tasks: 0.7a (W1), 4.5d (W12, `TableBlock.kt` #8), 5.9a (W13), 6.1d (W14), 6.1e (W15), 8.7a (W15). The 16-wave list contains all 89 ids exactly once (1.4a is the W1 integrator step); wave count unchanged at 16, every new task sits after its dependencies. UX acceptance criteria 56 to 69, manual items M-01..M-19, new test classes 18 to 22.

## Not changed, and why
- No scope was cut. The ICE scores, UX/outcome targets, the 1 000-cell and 100/300 ms loading thresholds and the 10 s notice timeout are INFERRED proposals for the owner to confirm. Nothing was built or run; every new criterion is a specification.
- Test rows 217 in validation.md were not recounted; the REQ-29/REQ-30 rows (+4) are noted in the inventory table.


# Iteration 6 (triad repair 2)

Inputs: triad UX findings (2 blockers groups, gaps list from the caller). Docs-only; nothing compiled or run except the counting script (python over the `##### Task` headings, the estimate table and the wave list). Edited: `design/ux.md`, `decisions/ADR-005`, `decisions/ADR-009`, `implementation/plan.md`, `implementation/validation.md`, `requirements.md` (flip criterion and counts). No scope was cut.

## Blockers
| Finding | Decision and where propagated |
|---|---|
| 1 Undo/Redo has no touch path | Undo and Redo are 48dp labelled controls in the sheet top bar (first overflow items at 320 CSS px), the sheet context strip and the inline strip; dimmed but focusable; activating a dimmed one shows "Nothing more to undo in this table" (inline: "Nothing more to undo"). Sheet uses the depth-gated path of Story 5.1; inline uses the page stack through new read-only `canUndo`/`canRedo` (Task 0.5a). ux.md rule 14, S3, S4, S15, UX-70; Story 5.1 criterion; Tasks 5.1e, 4.4c, 0.5a; validation UX-70. |
| 2 Back ladder | One ladder (ux.md 3.3): conflict banner, menu or panel, IME visible, `Edit` with IME hidden (Back = Done, Esc = Cancel), range, inline strip (inline) or sheet close. Pure `TableBackLadder` (Task 3.1d), inline `BackHandler` enabled only while a cell is active (Task 4.4e), sheet wiring (Task 5.3d), one test per rung; Back in sheet `Edit` no longer closes the sheet. UX-47 rewritten, UX-71, UX-15 and S2/S4/S10/S14 rows, ADR-005. |
| 3 Touch inline flow | Strip is a window-anchored bottom bar with one fixed relative order, no animation, `IME_FLAG_NO_EXTRACT_UI`/`NO_FULLSCREEN` (INFERRED mechanism, device-checked in 8.2a), sticky for the whole edit via `TableStripPolicy(mode, entryProfile)` fixed at entry (TalkBack and Switch Access = touch; Web/Desktop readers not detected), explicit Close, Cancel only while the draft differs (left of Done, with an Undo notice), one Extend picker (no "tap extends"), point mode only after `=`, an operator, `(` or `,` (pure `PointModeRule`) with a non-drag path (tap, then Extend...), error detail via select-cell edit-bar message or the strip's Why? (long-press dropped), inert table during block selection (ADR-005 precedence 0), stacking order IME, strip, notice, hint with the notice winning, Maximize button hidden while the strip shows, inline Resolve chip for held drafts. ux.md rules 10 to 13, S1 to S3, S7, S12, S14, 3.2, UX-57..UX-59, UX-72..UX-79; Tasks 4.2a/b, 4.3a, 4.3c, 4.4a (amended), 4.4c, 4.4e, 4.5d (dependency), 4.5e, 5.8a, 5.8c. |

## Gaps
| Gap | Decision and where propagated |
|---|---|
| Inline a11y semantics | ux.md 3.6b: "Edit cell" action and custom actions on every cell, one "Editing B3" announcement on entry, focus return, strip as traversal group. Story 4.5 criterion, Task 4.5e, UX-80, M-05 step 17. |
| Web/Desktop screen-reader gate | ux.md 3.7 gate, UX-81, Task 6.1e text, Story 6.1 criterion, requirements.md flip criterion for `table_sheet_enabled`, wall-clock blocker. |
| Alt+Enter and Tab on touch | IME action key = Enter (`ImeAction.Next`), strip and phone edit bar "Next column" (Tab) and "Insert line break" (Alt+Enter). ADR-009 touch table, S2, S7, UX-82, Tasks 4.3a, 4.4c, 5.3d. |
| UX-16 per input type | Touch at most 5, pointer at most 4, keyboard at most 4, three tests (the old 4-step count assumed tap-then-type, which does not edit on the sheet). |
| Done/Cancel order | Cancel left of Done (Android dialog convention), observed under H-C (UXM-5 text, UX-74 test). |
| Phone vertical budget | UX-83 and Story 8.1 criterion: node-bounds assertions for sheet and inline, portrait and 780 x 360 landscape, in `TablePhoneRenderTest`. |

## Counts (recounted by script)
96 tasks (29 M, 59 S, 8 XS; was 89), 40 estimate-table story rows summing to 96 tasks, 7 175k raw tokens, 10 616k CU (row sum), about 18.6M CU with x1.75 (XL). Added tasks, all S: 3.1d (W4), 4.4c (W10), 4.4e (W11), 5.1e (W12), 4.5e (W13, `TableBlock.kt` #9), 5.3d (W13), 5.8c (W14). The 16-wave list contains all 96 ids exactly once (script: 0 missing, 0 duplicate, 0 extra); each new task sits in a wave after all its dependencies; `TableBlock.kt` is still edited by one task per wave, `TableActionStrip.kt` (4.4a W8, 4.5a W9, 4.4c W10, 4.4e W11) and `TableSheetScreen.kt` (5.1a W8, 5.1e W12, 5.9a W13, 6.1d W14; 5.3d adds a new file plus a one-line call) likewise. UX acceptance criteria 69 to 83, manual items M-01..M-20, new test classes 22 to 29, validation UX rows 83.

## Not changed, and why
- Waves of existing tasks are unchanged; 8.1a, 6.1b and 6.1d gained dependencies on new tasks that all land earlier.
- The test inventory (217 rows) was not recounted; iteration 6 added UX rows only.
- INFERRED and unverified: the Compose IME-flag mechanism, `canUndo`/`canRedo` needing no `BlockUndoManager` change, the Android `AccessibilityManager` signal for Switch Access, and the vertical-budget thresholds (50% of the space above the IME) are proposals for the owner to confirm; nothing was built or run.


# Iteration 7 (triad repair 3)

Inputs: the final UX triad gaps (3 HIGH, a MED group, a LOW group). Docs-only; nothing compiled or run except the counting script (python over the `##### Task` headings, the estimate table, the wave list and the UX ids in `design/ux.md` against `validation.md`). Edited: `design/ux.md`, `decisions/ADR-004`, `ADR-005`, `ADR-009`, `implementation/plan.md`, `implementation/validation.md`, `requirements.md` (counts). No scope was cut. All mechanisms named below (Android `isChangingConfigurations`, Compose `PointerType` on Wasm, platform text-state undo) are INFERRED and checked in the tasks named.

## HIGH
| Finding | Single decision and where propagated |
|---|---|
| 1 Held conflict silent for Hardware-profile edits | A held conflict is the one exception to the sticky strip rule: the reduced strip (Resolve, Cancel edit, Markdown) shows below the table in every profile and mode, takes no focus, announces once; every committing key or control (Enter, Tab, Shift+Enter, IME action, Done, Next column, tap elsewhere, tap outside, other strip actions) commits nothing and re-shows the banner; Cancel edit still discards with its Undo notice. ux.md rule 15, S2, S14, 3.2 (`TableStripPolicy(mode, entryProfile, conflictHeld)`), 3.3 rung 1, UX-84; Story 4.5 criterion, Tasks 4.5e, 5.1b; validation UX-84, UX-57. |
| 2 Inline Undo is the page-wide stack | Inline and sheet Undo are both scoped to the table's own content-changing commits through one pure `TableUndoScope` (new Task 3.2c); inline depth starts at 0 when the strip appears and resets when it hides or a foreign entry arrives; the sheet scope starts at 0 on every reopen; the button text names the step ("Undo: edit B3"). ux.md rule 16b and c, S3, S4, S10, UX-70, UX-85; ADR-004 (new bullet; the manager still keeps no labels, the scope does); Story 3.2, 5.1 and 4.4 criteria; Tasks 3.2c, 4.4c, 5.1b, 5.1e. |
| 3 Undo during Edit commits then undoes | Ctrl+Z and strip or edit-bar Undo and Redo in `Edit` are field-scoped on a `CellDraft` history, never commit, never reach the document, dimmed with "Nothing more to undo in this cell"; table Undo needs `Navigate` (Done first). ux.md rule 16a, S2, UX-58 (Undo and Redo removed from the commit-first list), UX-86; ADR-004 (replaces "the platform text field owns in-cell text undo"); Tasks 3.2a, 4.4c; validation UX-86, UX-58. |

## MED
| Gap | Decision and where propagated |
|---|---|
| Notice queue, merge, height | One `NoticeQueue` (Task 3.2c): informational replaces informational and is not queued behind an action-bearing notice; action-bearing never dropped, FIFO with a "1 more" control, Retry first; `RecoveredDraft` and the Resolve chip pinned outside; undo-bearing past 5 retire into the Undo control; max two lines (one in landscape) and 25% of the space above the IME. ux.md 3.10, rule 13, UX-87; Tasks 3.2c, 5.1d, 4.4c. |
| Draft survival (S4, S14, ADR-004) | Rotation, fold and resize keep the uncommitted draft; `onStop` that is not a configuration change commits once; a conflict-held draft becomes a `RecoveredDraft`; after process death the file has the text and selection is not restored. The S4 claim "draft restored after process death" is replaced. ux.md 3.11, S4, S14, UX-88; ADR-004; Tasks 5.1b, 4.5e; validation M-05 step 20. |
| Fixed-width Close/Cancel slot, distinct icons | One fixed-width first slot: Close in `Navigate`, Cancel edit in `Edit`, always present, different icons and words, label never changes while typing; Cancel edit with an unchanged draft just leaves `Edit`; the phone edit bar's Cancel edit differs from the top bar's Close table. Rule 10, S2, S3, S7, UX-57, UX-58, UX-74, UX-89; ADR-005, ADR-009; Tasks 4.4a, 4.4c. |
| Sheet Esc in Navigate | Esc collapses a range and is otherwise not handled; the sheet closes by Back, (x) or Ctrl/Cmd+Shift+Enter (the inline chord toggles). Ladder rung 6', S4, S10, S15, UX-15, UX-16 (keyboard budget closes with the chord), UX-18, UX-47, UX-71, UX-93; ADR-009 rows; Tasks 3.1d, 5.3d; validation REQ-5, UX-15, UX-18, UX-47. |
| Enter at last row; IME after sheet Enter on touch | Enter on the last body row commits and stays (no row appended), "End of table" once; in the sheet the IME hides after the commit and a tap on the selected cell edits. S2, S4, ADR-009, UX-89; Tasks 3.1b, 5.3d. |
| Point-mode escape, indicator, literal `=` | Armed only for a valid formula prefix under the tokenizer (so `=5 apples`, `= total`, `==` never arm); an "Inserting reference" text indicator and a **Stop inserting** control; Done, Enter and Tab always commit. S7, UX-20, UX-75, UX-90; Tasks 3.1d (now also after 2.1a, W1), 5.8c. |
| Distinct cancel-draft vs close | See the fixed-slot row; edit-bar and top-bar controls named Cancel edit and Close table with different icons. |

## LOW
| Gap | Decision and where propagated |
|---|---|
| Strip placement when the profile switches mid-edit | Placement is decided with visibility and never moves (UX-91; rule 10, S3, 3.2; Task 4.4a). |
| S10 range-strip Undo/Redo | The range strip leads with the same Undo and Redo as the context strip (S10, UX-70). |
| IME on first tap for copy-row | IME stays open on the first tap (UX-01 keeps the caret in the cell); added a jank criterion UX-92 (one show, one hide, no table jump beyond the IME inset), device-checked by M-05 step 19. |
| Edit as markdown pinned | Fixed trailing **Markdown** button outside the scrolling part, also kept in the collapsed landscape strip (rule 10, UX-08, UX-91). |
| Web touch entry, 390 px strip | Entry profile from the pointer type (`touch` and `pen` = Touch); strip checked at 390 px in Task 8.3a and M-21 (3.2, UX-91). |
| Hint starvation | A deferred hint shows at the next idle moment and is dropped for the session after 3 deferrals without being marked seen (3.9, UX-87). |
| Read-only touch trigger | No trigger exists beyond the host `readOnly` parameter; a tap selects without the IME, shows the strip and the reason once (S13, UX-41). |

## Counts (recounted by script)
97 tasks (29 M, 60 S, 8 XS; was 96), 40 estimate-table story rows (Story 3.2 now 3 tasks S, S, M) summing to 97 tasks, 7 230k raw tokens, 10 697k CU (row sum), about 18.7M CU with x1.75 (XL). Added task: 3.2c (S, W4, after 3.2a in W3; consumed by 4.4c W10, 5.1b W9, 5.1d W10, 5.1e W12). The 16-wave list contains all 97 ids exactly once (script: 0 missing, 0 duplicate, 0 extra). UX acceptance criteria 83 to 93 (UX-01..UX-93, 93 ids in ux.md, 93 rows in validation.md, none missing), manual items M-01..M-21 (M-05 gains steps 19 to 21), new test classes 29 to 31 (`TableUndoScopeTest`, `NoticeQueueTest`), 15 validation rows rewritten (REQ-5, UX-06, 08, 15, 16, 18, 20, 41, 47, 57, 58, 70, 71, 74, 75).

## Not changed, and why
- Waves of existing tasks are unchanged; 3.1d gained a dependency on 2.1a (W1) and 4.4c, 5.1b, 5.1d, 5.1e on 3.2c, all earlier waves. `TableBlock.kt` is still edited by one task per wave.
- The test inventory (217 rows) was not recounted; iteration 7 added UX rows only.
- INFERRED and unverified: `isChangingConfigurations` as the config-change signal, Compose `PointerType` carrying the DOM pointer type on Wasm, platform text-state undo as an alternative to the `CellDraft` history, the 25% notice-height cap and the 5-notice retire threshold are proposals for the owner to confirm; nothing was built or run.

## Phase 5 (PR-A) findings

### P5-1: Logseq-native indented tables parse as `BlockType.Bullet`, not `Table` (found by Task 0.1a)
VERIFIED by `TableBlockContentShapeTest` (15 tests): `BlockType.Table` is produced only when the pipe rows are unindented. `- | a | b |` followed by two-space-indented rows (Logseq's own format) parses as `BlockType.Bullet` with the same de-indented content. `BlockItem` renders `TableBlock` only for `BlockType.Table` (`BlockItem.kt:498`). Impact: not PR-A (the engine is independent of block type; `TableParser` takes the longest contiguous pipe run and keeps other lines verbatim). It affects Tasks 4.3c/5.1a (seam and sheet-open gating) in PR-B: they must decide how Bullet-typed table content gets the table UI. Not redesigned here.

### P5-2: Bullet-form content keeps `\r` (CRLF) on line ends; Table-form content is LF-normalized
Same test. `TableParser` strips `\r` per line for parsing and restores it on serialize (handled in Task 1.1a).

### P5-3 (PLANNING ERROR, blocks Task 3.2b and Story 5.1): the manager's view does not follow a foreign DB write (found by Task 0.6a)
Source: `emission-trace.md` scenarios (d), (d2), (d3), generated by `BlockStateManagerEmissionTraceTest` (real `BlockStateManager` over in-memory SQLDelight).
- Story 3.2 and ADR-004 assume `BlockStateManager`'s in-memory content follows the repository feed. Observed: after a foreign write F (through `DatabaseWriteActor.updateBlockContentOnly`, or straight to the repository) the feed emits F, but `BlockStateManager.blocks` still holds the last local commit S1, also 3 s later. Cause not traced (suspected: the dirty-marker merge kept after a local commit).
- Consequence (d3): sheet adopts F via `Rebuild`; the user edits back to content equal to S1; the committer sends S1; `applyBlockContentUpdate` compares against the stale in-memory S1, returns early (`oldContent == newContent`), so no write and no undo entry; DB stays F, display shows S1. That is a silent loss of a user commit.
- Also observed (not a contradiction): (a) two unjoined `updateBlockContent` calls race, final state can be S1 not S2; with each commit joining its predecessor (a2) order is S1,S2. The "serialized commits" assumption is load-bearing for `TableCommitter` (test it in 5.1c). Undo emissions arrive before the undo Job completes (b, b2, c, c2), so "re-classify the last observed content after registering expectAdopt" is the main path, not an edge case.
- Not decided here (needs an owner/planner amendment, not improvisation): either the commit path refreshes manager content from the DB before the equality check, or a foreign write clears the dirty marker. The pure `TableSyncPolicy` classification itself is consistent with the trace, but Task 3.2b is held until Story 3.2 is amended as Story 0.6 requires. Tasks 3.2b/5.1b/5.1c are the affected tasks.

**P5-3 resolution (owner chose "make the manager follow the feed")**: root cause confirmed by running `BlockStateManagerEmissionTraceTest`: `applyContentPatch` deliberately ignored every `BlockContentPatched` content (a guard for the keystroke-race bug), and `updateBlockContentOnly` emits no invalidation pull, so a foreign write never reached `_blocks`. Fix: `BlockStateManager.applyContentPatch` now adopts the patch content when the block has no pending local write and no dirty marker (a foreign write) and either side parses as a table (`adoptForeignTableContent`); non-table blocks are unchanged. Proof: `foreignTableWriteReachesManagerAndLaterEqualRecommitIsPersisted` passes, and fails with the adoption branch disabled; emission-trace (d)/(d3) regenerated (manager shows F, the re-commit of S1 persists). 269 `ui.state`/`ui.screens`/`editor` jvmTests, 0 failures. Story 3.2 / ADR-004 stand as written; Task 3.2b is unblocked. Still open: (d2) a write straight to the repository (no actor, no push) is not followed; production writers all go through the actor.

### P5-4: deviations recorded during PR-A implementation
- `PointModeRule` (Task 3.1d) lives in package `table.formula`, not `table`: it needs the tokenizer, and the Task 1.4b guard forbids `table` importing `table.formula`.
- `TableEdit.SortMove(fromRow, toRow)` was added: `FormulaReferenceTransform.apply(cell, edit)` has no cell address, so the sort own-row rule is delivered as per-row `SortMove` calls (RefRewriter handles it; callers cannot issue it, `TableEditor` refuses it with `OutOfRange`).
- `SelectionStats` takes lookup lambdas (it is in `table`, which cannot import `table.formula`).
- `TableEngine.editor()` (in `table.formula`) is the production composition point for `TableEditor` with `RefRewriter`; PR-B callers use it.
- Owner decided (Excel/Sheets semantics, recorded in ADR-002): `^` is left-associative (`=2^3^2` is 64) and unary minus binds tighter than `^` (`=-2^2` is 4); a range wholly outside the table is clamped to empty (`SUM`/`COUNT` 0, `AVERAGE` `#DIV/0!`, `MIN`/`MAX` 0) while a single-cell reference outside stays `#REF!`. Implemented in `FormulaParser`, `FormulaPrinter` and `Evaluator`; `GoldenCorpusTest` pins the new answers and `FormulaPropertyTest.pow_should_FoldLeftToRight_When_UnparenthesizedChainGenerated` fails if associativity is flipped (mutation-checked: 6 formula tests fail).
- Cross-target: `putIfAbsent` and `StringBuilder.replace(Int,Int,String)` do not exist on wasmJs; fixed. The table tests ran in a real headless browser: 192 tests, 0 failures.

### P5-5: Task 3.2b known limits closed (owner decision): superseded acked echoes and foreign content overtaking an in-flight commit
Three limits were pinned as `knownLimit_*` tests: (1) after `markDone` (or after an undo's `Adopt`) a late emission of an older acked state read `Adopt` and moved the display backwards; (2) a foreign `Rebuild` while an own commit was `InFlight` left the older foreign content displayed (the commit's echo read `Stale`); (3) the "undo then re-commit before the undo emission" ambiguity.
- Rule 4 (Story 3.2): an `Acked`/loaded entry below the own-write floor (newest `registerCommit` or `expectAdopt` seq; a foreign Rebuild never raises it, found by the model: a floor of `currentSeq` made the newest commit's own emission `Stale` after a late older foreign Rebuild, display F, DB S) is `Stale`; only `expectAdopt` adopts an older state. Consequence: `TableCommitter.undo()/redo()` MUST register `expectAdopt` for every undo and redo (it already must, R3-C1); an unregistered undo is `Stale`, and a foreign edit equal to a superseded state is ignored until the next write (accepted).
- Rule 5: unknown content while any `InFlight` entry exists is `Defer` (held, newest wins; any later emission drops it because the feed is ordered); `TableSyncPolicy.markDone(seq, draftPending, selection)` now returns the released `SyncDecision?` (`Rebuild` or `ConflictKeepDraft`) once nothing is `InFlight`. Host impact (Story 5.1b `TableCommitter`/host): apply the decision `markDone` returns the same way as a `classify` result, and treat `Defer` as "no change".
- Ambiguity: undo then re-commit (same or different content) before the undo emission ends with display == DB == the re-commit in every delivery shape (scripted tests plus the model with undo no longer flushing the feed and `joinAll`); the old rule answered `Echo` to the re-commit's emission although an `Adopt(S0)` had moved the display to S0 (display S0, DB S1, found by the model); an `InFlight` match that is not the displayed content and not superseded is now `Adopt`, and the expected undo target is still adopted before it (a dropped re-commit cannot leave a stale display).
- Accepted residual: a foreign emission delivered after `markDone` but before the commit's own emission (no `InFlight` entry to hold it) rebuilds to the older foreign content, then the commit's emission re-adopts the commit; the display converges but may flash.
- Tests: the three `knownLimit_*` tests became asserting tests; `undoRedo_should_AdoptAckedStates_When_NothingIsInFlight`, `externalEditEqualToAckedState_should_Adopt_When_NothingInFlight`, the unregistered half of `hundredDeepUndo` and `markDone_should_RetireDroppedCommit_When_NoEchoEverArrives` were amended to the new rules. Model test no longer uses the markDone-after-echo or flush-before-foreign workarounds. Mutation proofs (each reverted): dropping the own-write-floor `Stale` rule fails 7 tests including the model; replacing `Defer` with `Rebuild` fails 5 including the model; making a non-displayed `InFlight` match `Echo` instead of `Adopt` fails 4 including the model.

