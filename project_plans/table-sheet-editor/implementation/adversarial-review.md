# Adversarial Review: table-sheet-editor

**Date**: 2026-11-24
**Verdict**: BLOCKED

Method: read plan.md, requirements.md, ADR-001..009, then checked referenced files and symbols against the worktree. Code claims below cite file:line in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/`. VERIFIED means I opened the code. INFERRED means a reading that needs a test to confirm.

Referenced paths and symbols that do exist: `BlockStateManager.updateBlockContent` (:1064), `flush` (:1231), `undo`/`redo` (:605), `requestEditBlock` (:856), `BlockItem` Table branch (:498), `LocalOpenSketchAction` (`ui/components/AnnotateImageAction.kt:11`, provided at `GraphContentActiveShell.kt:350`), `splitTableRow` (`ui/components/TableModel.kt:34`), `TablePreferences.kt`, `EditorSettings.kt`, `ClipboardProvider`, `WiringAudit.SEAMS`, `JobsScheduleImportDirectionTest`, `PhoneMenuEntryTest`, `AllAndroidUnitTests.kt`, `TableBlockLayoutTest` (10 `@Test`, matches the plan), `TableBlockScreenshotTest` (jvmTest), `scripts/kmp-*.py`, `scripts/check-bazel-test-listing.sh`, `scripts/jvm-display-check.sh`. `ParseTableContentTest` is in `jvmTest`, not commonTest. Task 1.1d's gate list must not assume it runs headless.

## Blockers

- [ ] **Undo of a table commit opens the raw markdown editor (VERIFIED).** `applyBlockContentUpdate` records undo and redo closures that both call `requestEditBlock(blockUuid, content.length)` (`BlockStateManager.kt:1089-1097`). Every acceptance criterion that says "Ctrl+Z restores the previous value" (5.1, 5.4, 5.5, 5.6, 5.7) therefore also flips the block into raw edit mode: inline, the grid is replaced by `BlockEditor`; in the sheet, a raw editor opens under the Dialog. ADR-004 says "no `BlockUndoManager` change needed" and the plan has no task touching `BlockStateManager`. Recommendation: add a Phase 0 characterization test of undo on a Table block, then a task that adds a table-safe commit path. For example, `updateBlockContent(..., restoreEditFocus = false)` or a separate `commitStructuredContent`, with undo and redo not calling `requestEditBlock`. Update ADR-004 and put `BlockStateManager.kt` in the Files lists.

- [ ] **Undo may not update displayed content because of the version guard (INFERRED, needs a failing-first test).** Undo calls `applyContentChange(oldContent, oldVersion)`. `_blocks` is only updated when `pageBlocks[idx].version <= version` (`:1128-1130`). After an edit the stored version is `newVersion`, which is greater than `oldVersion`. The DB write goes through, but the in-memory block (and so the grid) can keep showing the new content. The existing `BlockStateManagerTest.undo_redo_basic_flow` only asserts `canUndo`/`canRedo`, never content, so nothing covers this. Unresolved Question 6 covers only the `version` argument for commits, not undo. Recommendation: before Phase 1 finishes, add a Phase 0 test asserting `blocks` content after undo and redo with a Table block. Specify the version rule for sheet commits (for example `current block.version + 1`, never BlockItem's `localVersion`) and for undo.

- [ ] **`TableSyncPolicy` echo detection is racy (design flaw).** The policy compares incoming content with "the last string this session serialized". `updateBlockContent` is `scope.launch`, so it is asynchronous and the repository or `_blocks` emissions arrive later. Failure cases:
  - Two commits in quick succession (Tab, Tab). Commit S1 then S2 are serialized. Echo S1 arrives, differs from last (S2), and is classified external. The doc is rebuilt to S1, edit 2 vanishes from the grid, and the next commit is built from S1 and overwrites S2 on disk. This is a silent data-loss path.
  - Undo and redo produce content that is neither the last serialized string nor an external write. They would trigger `ConflictKeepDraft` or a spurious rebuild.
  - A stale emission after a rebuild flips the doc back.
  Recommendation: key the policy on a set of in-flight serialized strings, or on `_pendingContentWriteCounts` (exists, :1106) plus block version. Treat undo and redo as a known origin. Add a test with delayed and out-of-order emissions (two commits, echo S1 after S2 is serialized) and an undo-then-echo test. Acceptance in 3.2 currently covers only the single-echo case.

- [ ] **`FormulaShaped` contradicts the `#REF!` rewrite output (ADR-002 vs ADR-003, story 2.3).** ADR-002 says a cell is formula-shaped only if the next token after `=` is a number, ref, function name, `(`, unary sign or quote. ADR-003 and story 2.3 produce `=#REF!*2` and `=SUM(#REF!)` and require them to evaluate to `#REF!`. `#` is not an expression-start token, so `=#REF!*2` is literal text after the delete: no `fx` marker, no `#REF!` value, and no rewrite on later edits. Recommendation: add the `#REF!` token to the expression-start set in ADR-002 and to Task 2.1a. Add the story 2.3 delete-then-reparse case as a test: serialize, re-parse, still `#REF!`.

## Concerns

- [ ] **Formula rewrite can alter untouched formulas.** `FormulaPrinter` emits "canonical text". If `RefRewriter` reprints every formula on a structural edit, then `=sum( b2:b5 )` becomes `=SUM(B2:B5)`, `=007+1` becomes `=7+1`, and `1.50` becomes `1.5`, even when no reference changed. This contradicts ADR-002's "`007` never rewritten" and bloats diffs. Recommendation: rewrite only when a reference node actually changes, by token-splice into the original text. Leave other formula cells non-dirty (the same `RowLine` identity). Add a property: an edit that affects no reference leaves every formula cell's text and `RowLine` identity unchanged.

- [ ] **`<br>` and escape normalization on dirty rows.** `CellText` is "unescaped", with newline mapped to `<br>` on write. A dirty row re-renders all its cells. `<br/>`, `<BR>` or `<br />` in an untouched cell of an edited row would be normalized to `<br>`. Property (c) "untouched cells unchanged by any single edit" only holds if the generator includes those variants. Recommendation: keep the raw source text per cell and normalize only cells whose text changed. Add `<br/>`, `<BR>` and literal `\\|` cases to the generator.

- [ ] **Inline grid and sheet can both write the same block.** The Dialog opens over a visible `TableBlock` that has its own `CellDraft` and `TableDoc`. Nothing says inline editing is frozen while the sheet is open. A pending inline draft and a sheet commit would race through the same block. Recommendation: while the sheet is open for a block, inline `TableBlock` is read-only, and the sheet commits any inline draft on open. Add this to 4.3 and 5.1 with a test.

- [ ] **Open-sheet block lifecycle is unspecified.** The host observes `getBlockByUuid`. Missing cases:
  - Block deleted or replaced by an external reload (uuid regenerated by `DiskConflictBlockMatcher`-style matching).
  - Page or graph switch.
  - Content changed so the block is no longer `BlockType.Table`, or parse fails.
  - `observePage` unloaded.
  Recommendation: add a story 5.1 criterion for each: the sheet closes with a message, never writes to a missing block, and flushes first.

- [ ] **"Existing DiskConflict path" and "read-only graph" are asserted, not shown to exist.**
  - `DiskConflict` is page-level app state (`AppState`, `GraphDialogLayer`), not a per-block keep mine, take theirs, edit as markdown flow. Story 5.1's "DiskConflict choices appear" may be new UI.
  - I found no active-graph read-only concept. `TargetWriterCapabilities` is for off-graph merge targets, so the "read-only target" criterion has no definable trigger.
  Recommendation: add a Phase 0 task that reads `DiskConflict` and the writer-capability code and either maps these to real hooks or redefines the criteria and sizes the new UI. Reword the read-only criterion to name the concrete flag.

- [ ] **Sort and totals rows.** Sort is body-only and moves every body row, including a totals row (`=SUM(B2:B5)` placed in row 6). After sorting, the totals row can land inside its own range (`#CYCLE!`), and other refs stay literal (the wrong answer is silent). ADR-003 offers only an announcement count. Recommendation: either sort only the selected row range, or detect formulas whose range will contain their own post-sort row and show a pre-sort notice. Add a test for the totals-row pattern. Also specify descending order for blanks and errors (ADR says "blanks last" only for ascending).

- [ ] **`#LIMIT!` makes incremental differ from full recompute.** "200 000 cell reads per recompute pass" depends on pass size. An incremental pass (few dirty cells) and a full pass can hit the budget in different places. That breaks the `incremental == full` property in 2.4b when limits are reached. Recommendation: budget per formula (deterministic), plus a separate whole-table cap. State behavior for tables over the 20 000-cell cap (formulas disabled, editing allowed, banner?). Currently undefined for the inline path, which is not lazy.

- [ ] **Commit on dispose conflicts with the IME rule.** Story 5.2 says a draft off-screen is committed on dispose, while ADR-004 says never commit during composition. It also says "draft, cursor and focus intact" on scroll back. These are two behaviors for one event. `DisposableEffect` cannot use a `rememberCoroutineScope` launch, which is cancelled on leaving composition (CLAUDE.md rule). Recommendation: pick one: keep the draft hoisted and commit only on explicit triggers, or pin the editing row. If dispose-commit is kept, say it calls the non-suspending `updateBlockContent` and what happens when `composition != null`.

- [ ] **Wave schedule breaks its own dependencies.**
  - W8 contains both 4.3c and 5.1a, but the dependency graph says 4.3c precedes 5.1a (must be W9 or later).
  - The `TableBlock.kt` serialization rule ("one per wave, order 4.2a, 4.3a, 4.4a, 4.5a, 4.5b") is violated: W8 has 4.3c (lists `TableBlock.kt`), 4.4a and 4.5b together. 4.5b is also scheduled before 4.5a (W9).
  - 3.1c (W4) uses `TableCalc` values but 2.2c (the facade) is W5, while the dependency note says 3.1c needs only 1.1a.
  - 5.3a and 5.3c (impl and tests) are in the same wave.
  Recommendation: re-derive the waves from the dependency graph and the serialization rule. Re-state 3.1c dependencies.

- [ ] **Several acceptance criteria are not testable as written.**
  - "Focus, composition and soft keyboard survive" (5.2) and "IME never hides the cell" (8.2) cannot be asserted in Robolectric. Mark them device-only and make them 8.2 steps.
  - "Frame clock delayed by 400 ms" (4.2) needs a named mechanism (`mainClock.autoAdvance = false`) or it will be flaky.
  - "16 ms median over 200 runs on the dev machine" (8.5) is a wall-clock gate in a commonTest. It flakes on CI and is meaningless on Wasm. Recommendation: gate on `evaluationCount` and serialized-line counts (deterministic), and report timing without asserting.
  - Story 5.1 "block content already contains 7" needs a specified fake (`BlockStateManager` harness) rather than a mock.
  - Story 6.1 `collectionInfo(rowCount=201)` should state whether the header is counted as a row in `CellAddress` terms (the table has 200 body rows plus a header).

- [ ] **Throwable guard cannot be relied on for `StackOverflowError` or `OutOfMemoryError` on every target (esp. Wasm).** The plan correctly makes evaluation iterative, but the printer, rewriter, `TabularText` and parser recursion at depth 64 are also in the guarded path. Recommendation: state that no engine function recurses beyond the bounded parser depth, and add the 10 000-nested-parens and 1000-char tests to the rewriter and printer, not only the parser.

- [ ] **Scope drift beyond requirements.** Items not asked for in requirements.md:
  - Tap-to-insert-reference while editing a formula (5.8b).
  - Duplicate row, width presets and auto-fit, "Copy as formulas", the in-app `TableClipboardSlot`, `COUNTA`/`ROUND`/`ABS` (acknowledged), HTML clipboard format, `SelectionStats` line, edit bar, shortcuts panel, `Ctrl+Shift+Enter` maximize binding, and a benchmark probe.
  The owner said to pursue full scope, so this is not a cut request. The risk is that several (5.8b, slot clipboard) have no requirement or acceptance anchor and drive the largest untested surface. Recommendation: tag each such task `[beyond-req]` so reviewers can distinguish gaps from extras, and make sure each has a test, not just a story.

- [ ] **Task 1.4a manifest ordering.** Between W1 and the wave integrator, `table/` files exist without a manifest entry. `kmp-modules-sync.py --check` and `ModuleManifestGuardTest` fail in CI for any intermediate commit. Recommendation: add the manifest entry in the same commit as each batch of `table/` files, and state "no push between waves without a clean `--check`".

## Minors

- Story 1.2 sort example is consistent (`10, abc, 2, "", 2` gives `2(r4), 2(r6), 10, abc, blank`); descending order is not specified.
- iOS has no clipboard, Dialog or IME verification task (requirements: best effort). Note it as UNVERIFIED in the final report rather than leaving silent.
- `ParseTableContentTest` runs under `jvmTest` (needs the display wrapper only if it uses Compose; confirm).
- Task 0.1a calls the content shape "unknown", but the `BlockParser` table path (`parsing/BlockParser.kt:215`, `:503`) already exists; read it first to shrink the spike.
- Formula text such as `=B2*C2*D2` will render with italics in Logseq or in SteleKit's flag-off path (the inline markdown parser sees `*`). Add a fixture to 0.2a and record the result in ADR-007.
- Mutation proof (2.4c) and the independent corpus (2.4a) are manual agent steps. State where evidence (diff of the mutation and the failing test name) is recorded.
- Effort table's 76 task count is not recounted here; recount it with a command before citing it.

---

# Round 2

**Date**: 2026-11-24
**Verdict**: BLOCKED (one blocker, cheap to close by specification; everything else is CONCERNS)

Method: re-read the round-1 findings, repair-log.md, plan.md, ADR-002/003/004, then checked the claims against `BlockStateManager.kt`, `BlockEditorPorts.kt`, `BlockTreeAlgorithms.kt`, `BlockItem.kt` and `SteleDatabase.sq` in this worktree, and re-derived the wave schedule from the per-task dependencies. Nothing was compiled or run. VERIFIED = opened in code; INFERRED = reasoning only.

## Round-1 blockers: did the repair close them?

| Round-1 item | Status | Why |
|---|---|---|
| B1 undo opens raw editor | CLOSED (design) | Task 0.5b adds `restoreEditFocus: Boolean = true`. VERIFIED safe for callers: `updateBlockContent` is declared once on `BlockEditingPort` (`ui/state/BlockEditorPorts.kt:19`), `BlockStateManager` is the only implementer, and the only other wrapper (`JournalsViewModel.kt:153`) calls it positionally. No `::updateBlockContent` references, no fakes. Adding a defaulted parameter to the interface method is source-compatible. |
| B2 version guard blocks undo display | CLOSED (design) | Fresh `block.version + 1` at undo time passes the `<=` guard (`BlockStateManager.kt:1128-1130`). Failing-first 0.5a settles whether it was real. |
| B3 echo race | NOT CLOSED, see R2-B1 | The ledger depends on a version space the plan never pins down. |
| B4 `#REF!` vs `FormulaShaped` | CLOSED | ADR-002 and Task 2.1a now put `#REF!` in the expression-start set; Story 2.1 and the 2.4b property cover delete, reparse, print-back. `=#nope` stays literal. |

## Blocker

- [ ] **R2-B1: `OwnCommitLedger` mixes two version spaces (VERIFIED in code; the plan is silent).** Task 5.1a says the host "observes only `getBlockByUuid`", and Story 3.2 feeds the policy `(content, version)` from "the block observer". `getBlockByUuid` is the repository, whose `version` is the DB row counter (`version = version + 1`, `SteleDatabase.sq:317`; the comment at `BlockStateManager.kt:781-783` says it "cannot be matched against the UI-local keystroke version"). The commits, though, are minted in UI space: `TableCommitter` uses `max(observed, lastMinted)+1`, the inline path uses `++localVersion`, and undo uses `block.version + 1`. `BlockTreeAlgorithms.mergeBlocks` (`:111-129`) shows the spaces already collide in existing code: it compares the UI dirty version to the DB `incoming.version`, and a clean merge replaces the block with the DB version. Consequences for the ledger:
  - "In-flight match" by version (S1, v2) never matches a DB emission (S1, dbN), so every own echo falls to "known state" and is `Adopt`-ed or, with a draft pending, `ConflictKeepDraft`. That is the spurious-conflict and rebuild path B3 was meant to remove.
  - "Version below the highest acknowledged" compares across spaces, so `Stale` can fire on a legitimate external change (an external reload with a lower DB version is silently ignored) or fail to fire on a late S1.
  - The scripted tests in 3.2b will pass because they use one invented version space, and so prove nothing about the real feed.
  Recommendation: state which flow the policy consumes. Either observe `BlockStateManager.blocks` (the optimistic UI space; then say what the clean-merge replacement does to versions) or make the ledger content-keyed with a monotonic sequence number it assigns itself at commit time and at each distinct observed content. Add a 3.2b/5.1c test over the real manager and real repository (not a scripted list) with two quick commits, and assert the grid never regresses.

## Concerns (regressions introduced or left by the repairs)

- [ ] **Wave schedule, recomputed: two dependency violations and one file collision remain.** I rebuilt each task's prerequisites from the dependency diagram and Story text. Most waves hold (all 6 `TableBlock.kt` tasks are in distinct waves W5..W10 in the stated order; 5.3a/5.3c, 3.1c after 2.2c, 4.3c before 5.1a, and 4.5b after 4.5a are fine). Remaining problems:
  - **4.3b (W7) is in the same wave as 4.3c (W7), but 4.3b's criteria need 4.3c.** "Undo stays in the table" (`editingBlockUuid` null) and the "Version contract" test depend on the `LocalTableCommitAction` provider that 4.3c adds. The diagram only gives `4.3a --> 4.3b`. Move 4.3b to W8 or split the two criteria into 4.3c's tests.
  - **`TableMessages.kt` is created by Task 5.4c (W13) but consumed earlier.** 5.4b (paste summary, W12), 5.5a and 5.7a (W12; "Sort would put a total inside its own range", `Sorted by ...`) and the 5.1b conflict banner (W9) all need message strings. Move `TableMessages` into an early task (for example 3.2a or 5.1a) and let 5.4c only wire the live region.
  - **`SheetStructureTest.kt` is listed by both 5.5b and 5.7a, and both are in W12.** Two tasks editing one file in a wave. Give 5.7a its own test file or move it to W13.
- [ ] **Undo from inside the sheet is global.** The sheet binds Ctrl+Z to `BlockStateManager.undo()` (ADR-004), but the stack is page-wide with 100 entries. After the sheet's own entries are exhausted, the next Ctrl+Z pops an unrelated text-block edit, whose closure calls `requestEditBlock(otherBlock)` (`:1091`, `:1307` and similar). The raw editor opens under the Dialog while the sheet stays open. Say what the sheet does when `editingBlockUuid` becomes non-null for any block (close with flush, or disable undo at the session boundary), and add a test.
- [ ] **Inline `++localVersion` after a manager-minted undo version.** After undo the stored version is `block.version + 1`, but `BlockItem.localVersion` resyncs only in a `LaunchedEffect(block.version)` (`BlockItem.kt:163`). An inline commit made before that effect runs uses a stale `localVersion` and is rejected by the `<=` guard in memory while still being written to the DB, so the grid shows old content and disk has the new. The window is small but it is the exact failure mode B2 describes. Have the seam mint `max(localVersion, block.version) + 1`, or route inline commits through the same `TableCommitter` rule, and test undo then immediate inline commit.
- [ ] **0.5a is red on purpose in W1, 0.5b fixes it in W2.** The serialization paragraph says no wave is pushed without clean checks. A failing-first test in the tree breaks that. Say that 0.5a and 0.5b land in one commit (or 0.5a is `@Ignore`d with the recorded failure until 0.5b), and that this is the allowed exception.
- [ ] **Token-splice rewrite: partial coverage on sort and two edge cases.**
  - Sort rewrites "references into the formula's own row", but the rule for a range that touches the own row and others (`=SUM(A1:C2)` placed in row 2) is undefined; only a range containing its own new row is refused.
  - Single-cell references to other rows (`=B2+B3` in a "total" row) are not checked by `SortWouldSelfReference`, and the totals-row detector requires a range spanning rows above. A totals row written `=B2+B3+B4` is not detected as trailing totals, so it is sorted into the middle with now-wrong literal references. The announcement count only covers it after the fact. Either widen detection to any trailing formula row referencing rows above, or state this as an accepted limitation in ADR-003.
  - A deleted reference inside a larger range (`=SUM(B2:B5)` with column B deleted) becomes `#REF!` by the ADR-003 rule, but `=SUM(B2:C5)` with B deleted shrinks to `C`-shifted. State which columns shrink and add one test for each side.
- [ ] **Sheet lifecycle flush on a deleted block.** 5.1b "closes after flush" for deletion, but `applyBlockContentUpdate` returns silently when `findBlockOrNull` is null (`:1081`). A commit-pending-draft on close is therefore dropped without trace. Log it and show the one-line message; add the case to 5.1c (it is listed for the lifecycle, but not for the pending-draft combination).
- [ ] **Ledger bound of 64 states plus undo depth 100.** Undo can reach states older than the 64 most recent ledger entries; those come back as "external" (`Rebuild`, or `ConflictKeepDraft` with a draft). Either size the ledger to the undo stack (100) or document that deep undo shows as an external rebuild without a toast.

## Minor

- Repair-log says `LocalTableCommitAction` is "provided in 4.3c" while Task 5.1a's text also lists it as provided there; Task 4.3c's text (one line in `GraphContentActiveShell.kt`) is the consistent one. Remove it from 5.1a.
- The dependency note says `3.1c needs 3.1a + 2.2c + 1.3a` but the top-of-file note still says 3.1c needs 1.1a only in places; reconcile.
- The effort figures (78 tasks) were not recounted here.

---

# Round 3

**Date**: 2026-11-24
**Verdict**: CONCERNS (R2-B1 is closed in design; the content-hash ledger has one real race window and two under-specified rules, all fixable by specification before Task 3.2b)

Method: re-read Round 1+2, repair-log "Iteration 2", plan Story 3.2/5.1/0.5a, ADR-004; checked `SteleDatabase.sq:316-317`, `BlockStateManager.kt:99-101, 605-606, 1064-1145`, `BlockUndoManager.kt`, `BlockEditorPorts.kt:19`, `SqlDelightBlockRepository.kt:115-119` and every `updateBlockContent(` caller by grep. Re-derived the wave schedule with a hand-built dependency table. Nothing was compiled or run. VERIFIED = opened in code; INFERRED = reasoning only.

## R2-B1: closed

VERIFIED: `getBlockByUuid` is `selectBlockByUuid(...).asDbFlowOrNull(...)` with no `distinctUntilChanged`, so it re-emits the current DB row on every invalidation of the table (many repeats, always the DB state at read time). The DB `version = version + 1` counter (`SteleDatabase.sq:317`) is a different space from the manager's `block.version`; a policy that takes only `content` never compares them. Rule 1 (equals displayed content: `Echo`) absorbs the repeats. Closed.

## Classification cases

| Case | Result |
|---|---|
| Edit, undo, same edit again | OK if `registerCommit` on an existing hash re-uses the entry and sets it `InFlight` with a new `seq`. Story 3.2 says "each its own seq" but not "one entry per hash, re-commit overwrites seq and state". Specify it (see C1). |
| Two quick commits, echoes in order, reversed, or conflated | OK for the listed cases. |
| Conflation then undo to the skipped state | Under-specified (C2). |
| Undo or redo while a commit is still in flight | Wrong result (C1). |
| External edit equal to an `Acked`/loaded entry | `Adopt` = same rebuild as `Rebuild` (both use observed content); with a draft, `ConflictKeepDraft` either way. Benign, but "no conflict, no toast" means an external revert is silent. Accept and say so. |
| External edit equal to an older `InFlight` entry | `Stale`, silently ignored; the next commit then overwrites it. Last-writer-wins, not worse than today; note it. |
| Eviction (128 vs undo 100 + redo) | Sufficient for pure sheet commits (101 distinct states at most). The ledger also records external observations and adoptions, none of which push an undo entry, so 100 commits plus 28 external changes evicts the deepest undo state; the result is a benign `Rebuild`, or a spurious `ConflictKeepDraft` if a draft is pending. Eviction order (FIFO by `seq` or LRU) is unspecified; an `Adopt` re-seqs, so say LRU-by-`seq`. |

## Concerns

- [ ] **C1 (HIGH): content alone cannot tell an undo-produced state from a late echo of an old acked state when a commit is in flight (INFERRED from the rules; timing window is DB-write latency).** Rule 3 has no `seq` guard, only rule 2 does. Sequence: S0 loaded, S1 acked; Ctrl+Z writes S0; the user commits S1 again before the S0 emission is delivered (`registerCommit(S1)` gives it `seq` 4, `InFlight`). The S0 emission matches a loaded entry, so it is `Adopt(S0)`, a new `seq` 5 and `currentSeq`. The S1 echo then reads `InFlight` with `seq` 4 below `currentSeq` 5 and is `Stale`. The grid shows S0, the DB holds S1, and the next commit builds from S0 and overwrites S1: the silent data-loss path B3 targeted. Same shape: Tab, Tab, Ctrl+Z when only the post-undo emission (S1, still `InFlight`, `seq` below `currentSeq`) is delivered; it is `Stale`, so the undo changes nothing on screen while the stack has already popped. Recommendation: the sheet owns the undo call, so it should register the expected result (`ledger.expectAdopt` after joining `undo()`/`redo()` and reading the resulting block content), and while any `InFlight` entry exists an `Acked` match that is not expected is `Stale`. Add scripted tests for both sequences and extend the 5.1c real-feed test with commit, undo, immediate commit.
- [ ] **C2 (MEDIUM): "the older `InFlight` S1 is retired" is ambiguous.** If retired means removed, an undo back to S1 reads as external (`Rebuild`; `ConflictKeepDraft` with a draft). If it means `Acked`, fine. Say `Acked`, and also mark every older `InFlight` entry `Acked` whenever a newer one is echoed (otherwise it keeps `seq < currentSeq` and an undo to it is `Stale`). Add the test: S1, S2 in flight, only S2 observed, undo, S1 observed gives `Adopt`.
- [ ] **C3 (MEDIUM): `sheetUndoDepth` is bumped when the sheet commits, but the manager records the undo entry later.** VERIFIED: `applyBlockContentUpdate` calls `applyContentChange` (DB write awaited) and only then `record(...)` (`BlockStateManager.kt:1081-1097`), and `updateBlockContent` is a `scope.launch` (`:1064`). A Ctrl+Z pressed in that window pops the previous entry, which can be another block's (`requestEditBlock` on it, closing the sheet via the backstop) and the depth is still decremented. A fast Ctrl+Z after Enter is a normal keystroke pair. Recommendation: `TableCommitter` keeps the returned `Job`s and `undo()`/`redo()` join them first; derive "content-changing" from the manager side (the commit is a no-op when `oldContent == newContent`, `:1084`), not from the sheet's own comparison, otherwise the depth drifts above the real entry count. Also say what happens when the 100-entry cap drops sheet entries (depth above the stack; extra undos are no-ops, which is acceptable if stated).
- [ ] **C4 (LOW): entries recorded by other actors while the sheet is open** (page-level moves, external-command or sync-driven records on the page-wide `BlockUndoManager`) sit on top of the sheet's entries, so Ctrl+Z pops a foreign entry even with `sheetUndoDepth` above zero. The backstop closes the sheet only if the closure calls `requestEditBlock`. Say that a foreign `canUndo` change while open also closes the sheet or is accepted as a limitation.
- [ ] **C5 (LOW): a commit the manager silently drops** (block not found, or content already equal at the manager) leaves its ledger entry `InFlight` forever, and a later legitimate emission of that content is `Stale`. Retire `InFlight` entries when the `Job` completes (the sheet already holds it per C3) instead of waiting for an echo.
- [ ] **C6 (LOW): DB write order vs commit order.** Commits are separate `scope.launch`es on a multi-threaded dispatcher, so the DB can end holding S1 while the display is S2 (the existing `maxOf` guard protects `_blocks` and the disk save, not the DB row). The policy cannot see it. Not a regression of this plan; record it as an accepted limitation in ADR-004.

## Does the manager-minted version break callers?

No. VERIFIED by grep over `kmp/src`: `BlockEditingPort` is implemented only by `BlockStateManager` (`override fun updateBlockContent` appears once, `BlockStateManager.kt:1064`); the other callers are `JournalsViewModel.kt:154`, `PageView.kt:509/579`, `JournalsView.kt:228/310` (positional), and about 30 tests in `BlockStateManagerTest.kt`, `ActiveTargetWriterContractTest.kt`, `EditorToolbarRecompositionTest.kt`, `TagInsertionFlagshipUiTest.kt`, `OutlinerMonkeyTest.kt` and `JournalViewFanoutBenchmarkTest.kt`, some with `newVersion =` named arguments. A defaulted `restoreEditFocus = true` leaves all of them on the old path, and the `max(passed, current.version + 1)` mint applies only when the flag is `false`. Two notes for Task 0.5a: the manager's parameter is named `newVersion` while the interface's is `version`, so keep the manager's name and add the new parameter after it (named-argument callers); and the interface default cannot be redeclared on the override (already stated).

## Wave schedule, re-derived

I built the prerequisite table from the dependency diagram plus the Story/Task text and checked all 77 task ids against the 16-wave list. Every task sits strictly after all its dependencies (spot-checks of the earlier trouble spots: 3.1c W6 after 2.2c W5; 4.3b W8 after 4.3a W6 and 4.3c W7; 4.4a W8 after 7.1a W3 and 4.3c W7; 5.1a W8 after 4.3c W7; 5.3a W11 after 5.2b W10 and 3.1b W4; 5.8b W13 after 5.8a and 5.3b W12; 6.1b W14 after 6.1a, 5.3c, 5.5b, 5.7a, 5.8a all W12; 8.1a W14 after 5.4c/5.4d/5.8b W13). `TableBlock.kt` is edited by exactly 4.2a (W5), 4.3a (W6), 4.3c (W7), 4.4a (W8), 4.5a (W9), 4.5b (W10); the other 4.x tasks (4.2b, 4.3b, 4.4b) are test-only. `SheetStructureTest.kt` is owned by 5.5b (W12) alone; 5.7a uses `SheetSortTest.kt`. `BlockStateManager.kt`/`BlockEditorPorts.kt` only in 0.5a. No violation found. One nit: Story 5.5's Files line lists `SheetStructureTest.kt` at story level while 5.5a (W11) and 5.5b (W12) both implement it; they are in different waves, so there is no collision, but say that 5.5a adds no test to it.

## Minor

- Story 3.2's rule order puts "equals displayed" before the in-flight check; with C2 fixed, add the invariant "at most one entry per hash" to the ledger text.
- The Round-2 minors (task count recount, top-of-file 3.1c note) were not re-verified here.
