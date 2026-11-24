# Cross-artifact consistency review: table-sheet-editor

**Method**: `~/.claude/skills/sdd/skills/4-validate/cross-artifact-consistency-prompt.md` (coverage gaps, scope drift, UX-plan misalignment, terminology drift, direct contradictions), run by reading `requirements.md`, `implementation/plan.md`, `design/ux.md`, `decisions/ADR-001..009`, plus `research/*.md` and `implementation/repair-log.md` for stale-statement checks. Docs only; nothing compiled or run. Line numbers are from the files as read on 2026-11-24.

**Result: 12 findings: 4 BLOCKER, 5 CONCERN, 3 NITPICK.** (Counts are of the numbered findings below; sub-bullets inside a finding are not counted separately.)

Severity follows the prompt (contradiction or coverage gap = BLOCKER; scope drift or terminology = CONCERN; UX alignment = NITPICK). Two deviations are marked "escalated" or "downgraded" with the reason.

## Checks that passed (VERIFIED by command)

- Every `## Scope -> In Scope` item in requirements.md maps to at least one plan story: inline edit (4.2, 4.3), select/copy (4.4), links (4.2), one-action maximize (4.5, 5.1), keyboard grid (5.3), range select (5.4), TSV/CSV in and out (1.3, 5.4), structure ops (5.5), resize, alignment, frozen header (5.6), sort (5.7), formulas and error display (2.x, 5.8), round trip (1.1), undo (0.5, 5.1), accessibility (6.1). No requirement is without a story.
- Task headings: 77 (`grep -c '^##### Task'`), split 28 M / 43 S / 6 XS (matches plan.md:1005). The 16-wave list contains exactly the 77 task ids, once each (diffed against the headings). Story-row raw tokens sum to 6125k (matches plan.md:1005). UX criteria: 56 (`grep -c '^- \*\*UX-'`), matching ux.md:773.
- ADR-001..009 are all referenced by plan.md:6 and every ADR file exists.

## BLOCKER

### B1. Shift+Enter: line break (plan 4.3) vs commit-and-move-up (plan 5.3, ux.md, ADR-009)
- plan.md:583 (Story 4.3): "*Given* Shift+Enter inserted a line break, *Then* the commit writes `<br>`".
- plan.md:714 (Story 5.3): "Shift+Enter commits and moves up"; `Alt+Enter` inserts the line break (plan.md:716).
- ux.md:112-114 (S2): Shift+Enter = "Commit, move up"; Alt+Enter = line break. ADR-009 adopts research/ux.md section 5 "unchanged", which has the same split (research/ux.md:149).
- Root cause (INFERRED from grep): plan 4.3 inherited the older research text (research/features.md:33 and research/stack.md:43 both say "Shift+Enter inserts `<br>`"). ADR-009 and ux.md superseded it, plan 4.3 was not updated.
- Resolution: change plan.md:583 to Alt+Enter; add a one-line "superseded by ADR-009" note to the two research lines (see C1).

### B2. Task 1.2b describes the old totals-row detector; ADR-003 and Story 1.2 replaced it
- plan.md:328 (Task 1.2b): trailing totals rows are "a trailing run of body rows each containing a formula with a range reference that spans rows above it".
- ADR-003 (Sort row) and plan.md:315 (Story 1.2): detection is by dependency, so `=B2+B3+B4`, `=B2*2+B3`, `=B2` and `=AVERAGE(B2:B4)` count and a row-local `=B5*C5` does not. `=B2+B3+B4` has no range reference, so an implementer following Task 1.2b fails the Story 1.2 acceptance criterion. The repair log lists this as fixed ("Totals detector misses `=B2+B3+B4`") but the task text was not touched.
- Resolution: replace the Task 1.2b clause with the ADR-003 rule (or "as ADR-003").

### B3. Esc in the sheet: "second Esc leaves grid focus" (plan 5.3) vs "closes the sheet" (ux.md)
- plan.md:718 (Story 5.3): "*Given* a range selected, *When* Esc is pressed in `Navigate`, *Then* the range collapses to the active cell; a second Esc leaves grid focus."
- ux.md:206 (S4): Esc "when `Navigate` with a single cell" closes the sheet; ux.md:410 (S10) "second press closes sheet"; ux.md:655 and 670 (state machine, layers table) the same. "Leaves grid focus" is the inline behaviour (ux.md:165, 655).
- Resolution: reword plan.md:718 to "second Esc closes the sheet (inline: leaves grid focus)" and add the pending-draft commit-and-flush expectation (ux.md:206).

### B4. Flag matrix is undefined for "sheet on, inline off"; "one action from any inline table" has no criterion for it
- requirements.md:21: the maximized sheet is "reachable from any inline table in one action". Two independent flags exist (requirements.md:80, plan.md:110).
- plan.md:628 (Story 4.5) and 652 (Story 5.1) only cover "flag on and sheet flag on" and "flag off, no expand button". ADR-006 says "With both off, `TableBlock` takes today's code path". Nothing says whether the expand button and the inline strip render when `table_sheet_enabled = true` and `table_inline_edit_enabled = false` (tap then still opens raw markdown, so the expand button is the only entry), or whether `table_inline_edit_enabled = true` with the sheet off shows a strip without Maximize. ux.md:76 and 87 cover only "inline flag off = today" and "sheet flag off = no expand button".
- Resolution: add a 2x2 flag table (button, strip, Ctrl+Shift+Enter, tap behaviour per cell) to Story 4.1 or 4.5 with one test per row.

## CONCERN

### C1. Stale statements superseded by later repairs (research docs and ADR-001)
- **research/pitfalls.md:32** "commit on dispose (don't lose the draft when the item leaves the viewport)" contradicts ADR-004 ("There is no commit on dispose", hoisted `CellDraft`) and plan.md:51, 692. The repair log (repair-log.md "Not changed") deliberately left research as historical, which is why this is CONCERN and not BLOCKER (downgraded). Risk: Task 5.2b/4.3a workers are told to read research and could re-add a dispose-commit, which also violates the IME rule (ADR-004).
- **research/features.md:33** and **research/stack.md:43** (Shift+Enter inserts `<br>`): see B1.
- **research/ux.md:166, 168** list `Ctrl+Alt+M` and `Ctrl+Shift+E` as proposals; ADR-009 rejects both. They are labelled "(proposal)", so low risk.
- **research/pitfalls.md:79**, **features.md:42**, **requirements.md:85** and ux.md:302, 479 list the function set as `SUM, AVERAGE, MIN, MAX, COUNT, IF`; ADR-002 and plan.md:129 add `COUNTA ROUND ABS`. The two ux.md strings are user-visible error text ("Supported: ..."), so they would ship wrong. Fix ux.md:302 and 479 to the ADR-002 list; the research lines only need the supersession note.
- **ADR-001:21** "core grows about 12 files": the plan's `table/` plus `table/formula/` file lists total about 29 (count of distinct files named in Stories 1.1-3.2 and 7.1a). Reword to "about 30 files".
- Resolution: add a one-line `Superseded by ADR-004 / ADR-009 / ADR-002` note at each research line above rather than rewriting history.

### C2. Terminology drift (same thing, different names)
| Concept | Names in use | Where |
|---|---|---|
| Fallback to raw editor | "Edit as markdown" vs "Open as markdown" | plan.md:132, 628, 671, ADR-004/006 use "Edit as markdown"; ux.md:531, 539, 548, 668, 752 (S14 banner, UX-43) use "Open as markdown" |
| Settings toggle for the sheet | "Spreadsheet view" vs "Open tables as a sheet" | plan.md:547 (Story 4.1 AC, literal label) vs ux.md:610 (S17) |
| Maximize | "maximized sheet" (requirements), "expand button" (plan 4.5), "Open as sheet" tooltip (ux.md:279), "Maximize" strip label (plan 4.4a, ADR-005), "(expand)" strip label (ux.md:150) | across all |
| Inline strip buttons | "Select row/column/table" (plan 4.4a, ADR-005) vs "Row / Col / Table" (ux.md:150) | plan 4.4 AC names "Select row" |
| Sort menu item | "Sort rows (changes file)" (ADR-007, UX-32, plan 5.7) vs "Sort rows A-Z (changes file)" / "Z-A" (ux.md:318-321) vs "sort asc/desc" (plan 5.5) vs custom action "Sort ascending" with no "(changes file)" (plan 6.1) | the a11y action omits the warning that the sort rewrites the file |
| Column width preset | "Auto" (ux.md:326) vs "Auto-fit" (ux.md:325, 741) | minor |
| Formula editor | "Edit bar" (plan, ux.md S7 title) vs "formula bar" (file `FormulaBar.kt`) | minor |
- Resolution: pick one name each (suggest "Edit as markdown", "Open as sheet" for the user-visible label with "Maximize" as the internal action name, "Sort rows A-Z (changes file)" and "Sort rows Z-A (changes file)" everywhere including the TalkBack action) and align plan 4.1 to the ux S17 label or the reverse.

### C3. Requirements NFR and wall-clock claims no longer match the plan
- requirements.md:39: "Specific numbers set in research". research/stack.md:64 proposes "cell commit < 16 ms for 200x12 on JVM". The plan (Story 8.5, plan.md:928-937) deliberately dropped any absolute number (counters plus a relative baseline, no Compose probe) and says UX-17 is checked "by eye". That is a reasonable repair (repair-log "Untestable criteria") but requirements.md still reads as if an absolute SLO exists. Resolution: edit requirements.md:39 to "relative baseline plus deterministic counters; no absolute frame-time gate" so verification does not fail an SLO that was never testable.
- requirements.md:29: "none owned by the owner beyond review". plan.md:1031-1034 has owner-gated tasks (8.4a Logseq fixtures, CJK IME confirmation in 0.3a/8.3a, flipping defaults). Resolution: update the requirements blocker line.

### C4. Scope drift: stories or tasks with no requirement behind them, not all tagged `[beyond-req]`
Tagged (plan.md:755, 777, 794, 822): clipboard slot, duplicate row, `COUNTA/ROUND/ABS`, Copy as formulas, HTML clipboard, `SelectionStats`, shortcuts panel, Ctrl+Shift+Enter, width presets and auto-fit, reference insertion while editing.
Untagged extras:
- First-column freeze (plan.md:791 AC, ux.md:326, 560); requirements.md:47 asks only for a "frozen header row".
- Sheet conflict banner (Keep mine / Take theirs / Edit as markdown): justified by the "no silent data loss" risk but not in requirements.
- Edit bar / formula bar as a separate surface (Task 5.3b), `fx` tint and per-error detail text (5.8a), the "Extend..." picker, haptic tick, hinge avoidance (ux.md:251, 406).
- Resolution: tag them `[beyond-req]` or add one line to requirements.md In Scope; the tag only marks extras for reviewers, it does not remove them (full scope is the default).

### C5. Sort announcement text differs between ADR-003 and plan/ux (escalated from NITPICK: user-visible string)
- ADR-003: "Sorted by Qty. 2 formulas reference other rows" (a count of formulas whose outside-row references now point at different data, carried in `EditSummary.Sorted`, plan.md:443).
- plan.md:801 (Story 5.7 AC) and ux.md:626, UX-32: "Sorted by Name" only. ux.md:348 adds a third message ("3 blank cells sorted last") that no document defines in `EditSummary`.
- Resolution: define the full set of `EditSummary.Sorted` fields (column, blank count, cross-row formula count) in plan.md:38 and make Story 5.7 and S18 quote the same string.

## NITPICK (UX criteria with no matching task, or small UX-plan mismatches)

### N1. UX criteria and surfaces with no plan task or acceptance criterion (escalated for the first three: explicit numbered UX criteria)
- **UX-44 / S16** unparseable-table fallback ("This table can't be edited as a grid: missing delimiter row" plus Edit as markdown) and the >5,000-cell inline row window with "Open sheet" (ux.md:581-599). The plan only closes an open sheet on parse failure (plan.md:665) and logs once (plan.md:105); no inline fallback message or capped-row-window task exists, and 5,000 is a third size threshold beside 10,000 (paste) and 20,000 (formulas).
- **UX-45** commit failure Snackbar "Couldn't save the change" with Retry (ux.md:227): no task. `updateBlockContent` returns a `Job` with no failure channel in the plan; only the deleted-block case is handled (plan.md:678).
- **UX-24, UX-37, S11, S14** require an "Undo" action on a snackbar or notice (paste summary, "2 formulas now show #REF!", "Discarded your edit" for 6 s). `TableMessages` only maps strings and 5.4c wires a live region; no snackbar component owns the Undo button. Also, `EditSummary` has no field for the #REF! count (UX-37) or for "content transformed" counts (UX-27, ux.md:434, ADR-008 "status line says when content was transformed"); `EditSummary.Pasted` is rows/cols/added only (plan.md:318). "Take theirs" undo is not a block-undo entry and is unspecified.
- Clipboard write failure toast with "Show as text" dialog (ux.md:173) and the "Line break stored as <br>" notice (ux.md:131): no task.
- Process death and rotation (ux.md:226, UX-14): plan.md:703 holds `SheetState` in "one remembered state class"; `remember` does not survive activity recreation. Fold/unfold is covered by Task 8.2a; rotation and process death have no criterion (INFERRED; depends on where the host keeps state).
- Window shrinking below 600dp moves a draft into the phone edit bar (ux.md:254): no criterion.
- Inline Ctrl+Shift+Enter handler (UX-10, ux.md:618 "toggle off removes the binding"): only listed as a `[beyond-req]` item (plan.md:777); no inline key-handling task or test.
- UX-47 (every layer's exit works with keyboard, pointer and Back) and UX-50 (Tab enters the grid once, leaves once): no test; Task 6.1b iterates only `TableShortcutMap`.
- ux.md:785 "Open design items" (Enter after commit in the sheet, auto-show strip) are undecided while plan.md:1035 says "the scope decisions are fixed".

### N2. Smaller UX-plan mismatches
- Inline Enter: ux.md:111, 121 say the next cell enters `Edit` inline; plan.md:577 (4.3) only asserts "the selection moves down one row".
- Width at 600-839dp: UX-12 says full screen with inline edit bar; plan.md:675 says "full below 600dp, ~90% at 840dp and above" and leaves the middle band implicit.
- TalkBack custom actions: ux.md:686 lists Edit cell, Extend to row/column end, Copy, Paste, Move row up/down, Insert row/column, Sort descending; plan.md:837 (Story 6.1) lists a subset. The 6.1b "every shortcut reachable" test would catch the gap only for shortcuts, not for these actions.
- ux.md:413 status line adds "1 error"; `SelectionStats` (plan.md:58, 482) has COUNT/SUM/AVERAGE only. ux.md:414 (500 ms debounce) is not in Task 5.4c.
- ux.md:206 focus lands on the previous active cell else `A2`; ux.md:618 S17 note "Raw markdown editing is always available." not in Story 4.1.

### N3. Small cross-document numeric and naming slips
- ADR-008:12 example "Pasted 8 x 5, added 5 rows, 2 columns" has no anchor; plan.md:318, UX-24 and ux.md:432 use "added 7 rows, 3 columns" for a 3x3 table pasted at `CellAddress(3,2)` (recomputed: rows 3+8-1 = 10, so 7 added; cols 2+5-1 = 6, so 3 added; matches plan). UX-24 does not state the anchor, so "7 rows, 3 columns" is only true from `B3`.
- Paste cap: ux.md:435, 463, UX-26 and ADR-008 say 10,000 cells; plan.md:318 only shows a 30,000-cell refusal and never states the 10,000 constant, so no test pins the boundary.
- ADR-006 says the `BlockItem` Table call passes "`onContentChange`"; plan 4.3c passes an `onCommit` lambda that falls back to it, and also edits `GraphContentActiveShell.kt`. Plan.md:672 (Story 5.1 Files) and plan.md:526 (Story 3.2 Files) omit `OwnCommitLedger.kt`, and Story 7.1's Files line omits `COMMON/table/TableClipboard.kt` that Task 7.1a creates (plan.md:865).
- ADR-004's classification order (items 1-4) omits the `expectAdopt` step that plan.md:502 places before the `InFlight` rule (ADR-004 describes it only afterwards as "Round 3 additions").
- ADR-009 binds `Ctrl+Shift+=` and `Ctrl+-` (insert/delete) without the browser check it applies to other chords; in Chrome these are zoom commands (INFERRED, not tested). Task 8.3a should include them.

## Suggested fix order
1. B1, B2, B3 are one-line plan edits; do them first.
2. B4 needs a small owner-visible decision (flag matrix), then one table and tests.
3. C1/C2/C5 are string alignment across ux.md, plan.md and ADRs.
4. N1 items that need new work (UX-44, UX-45, snackbar-with-Undo, #REF! and transform counts in `EditSummary`) should either become tasks or be struck from ux.md with a reason, so `sdd:4-validate` does not report UX criteria with no test.
