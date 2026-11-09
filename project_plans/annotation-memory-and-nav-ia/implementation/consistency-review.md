# Cross-artifact consistency review (SDD Phase 4)

Artifacts: `requirements.md`, `implementation/plan.md`, `design/ux.md`, `decisions/ADR-001..009`. Read-only pass; nothing else edited.
Totals: 3 BLOCKER, 13 CONCERN, 8 NITPICK.

## Mechanical checks (run by script)

- `grep -c '^### Story' plan.md` = 32; `grep -c '^##### Task' plan.md` = 93; 4 epics. Header ("4 epics, 32 stories, 93 tasks") and the effort-table Total (93 tasks, 10,666k, 9,927k CU) match. Per-story task counts equal the table rows for all 32 stories; summed table columns reproduce 93 / 10,666 / 9,927.
- Glossary rows = 29, matches "Glossary count: 29".
- UX IDs: ux.md defines UX-01..UX-41 (41, matches its own total). Plan cites UX-19, 28, 29, 30, 31, 33, 34, 36, 38, 39, 40, and all exist. No dangling UX references.
- ADR statuses: all nine are `Proposed`; none `Accepted`. The plan never claims an ADR is Accepted (it sets 004 and 006 to Accepted as future task outputs).
- `OD-1..OD-4` appear only in plan.md (0 hits in ux.md); see C-5.

## BLOCKER

**B-1. Success Metric 1 is unmeetable as written and requirements.md was not amended (requirements vs plan).** requirements.md metric 1 requires "an automated on-device test reproduces the pre-fix failure and passes after." plan.md (What-we-know table, Unresolved Questions last-but-two item) states the kill never reproduced, so this cannot be met, and substitutes the pre-fix proof procedure (Story B.5). The plan header still says "ready for implementation" while this owner acceptance is open. Resolution: owner accepts the substitute and requirements.md metric 1 is amended (or the header says "blocked on owner acceptance of metric 1").

**B-2. Schema change contradicts requirements Out of Scope (requirements vs plan).** requirements.md Out of Scope: no `.sq` schema change "unless the crash root cause requires it." Plan C.1d adds `image_annotations.calibration_basis_version` plus a `MigrationRunner` entry and regenerated sources for a calibration defect (C1), not the crash; D.7c may add indexes that must also go in `MigrationRunner`. Resolution: amend requirements.md Out of Scope to allow additive migrations for C1/D.7c, or get explicit owner approval before Phase 5.

**B-3. ADR-003 contradicts the plan on the loupe seam (ADR vs plan).** ADR-003 Consequences: option 3 "adds an expect/actual in `commonMain/platform/image/` with JVM and Android actuals and wasm/iOS stubs." Plan A.5 Files and A.5b: a commonMain `fun interface LoupeSource`, "no `expect` is declared," no iOS/wasmJs actual files (a repair-pass decision driven by the architecture review). ADR-003 was never amended, so an implementer following the ADR would create the expect/actual the plan forbids. Resolution: amend ADR-003 Consequences to the injected-interface design.

## CONCERN

**C-1. ADR-001 still lists a decode-bytes gate that the plan makes report-only.** ADR-001 "Initial modes": "hard gate from day one = deterministic metrics (max single decode bytes, ...)" (also repeats "process survived" twice). Plan B.5b and A.5/B.3 ACs: max single decode bytes is `ReportOnly` until the A.5a spike (loupe 2 MB) and cell-size measurement (4 MB). Plan B.5b says the ADR-001 list "is amended to match," but the list was not corrected, only an addendum for idle/sustained CPU was added. Resolution: remove max-decode-bytes from the day-one gate list; keep "decode count per press".

**C-2. ADR-007 and ux.md disagree with the plan on the nav drift guard.** ADR-007 says "a commonTest invariant"; plan D.2 replaced it with a compiler-exhaustive `when` plus businessTest `NavModelExhaustivenessTest` (JVM reflection cannot live in commonTest); ux.md S1 names it `NavModelCoverageTest`. Three names/locations for one test. Resolution: use `NavModelExhaustivenessTest` everywhere; amend ADR-007.

**C-3. Option A skips tasks the UX criteria need regardless of option.** ADR-006 and ux.md S4 say Option A skips D.6a-D.6c. But D.6c carries Retry on load errors (UX-13), unique card descriptions (UX-16) and empty states (UX-12), which ux.md S5/S6 define for every option and UX-13/UX-16 state unconditionally. Resolution: split D.6c into option-independent (Retry, descriptions, empty state with Capture/Attach) and option-C-only (cross-link buttons).

**C-4. ux.md surfaces and criteria with no plan story or task.**
- S5/UX-15: missing-file tile with Keep/Remove and its confirmation, skeleton loading, page-2+ inline Retry (D.7 only mentions "load-more error" in state type).
- S9/UX-22: post-kill launch banner with View log; A.2a only reformats the Logs line. No task builds the banner or "never repeats for same record".
- S10/UX-26: "Saving..." wait up to 2 s before leaving, "Close anyway" confirm; C.6 covers only the dialog text.
- S13/UX-36/UX-37: 48 dp vertex touch areas, non-colour-only selected style, Delete with 6 s Undo snackbar. Plan cites UX-36 only under OD-4; no task covers handles or delete-undo.
- S15/UX-29: progress/success/"Sign in to Google Drive"/"Export smaller" states and the themed-colour contrast fix for the hard-coded `0xFF1565C0` icon. C.7 covers labels, scale, EXIF, OOM only; plan ties UX-29 solely to the OD-4 offline copy, though most of UX-29 is not an owner decision.
- S16/UX-23: Logs [Copy] action with "Copied" confirmation; A.2 only extends the line.
- S8/UX-21: "Something went wrong... [Retry] [Close editor]" banner; A.2b only sets `state.error`.
Resolution: add tasks (or explicit "deferred" notes) for each, or mark the UX criteria out of this project's scope. Per the prompt's scale these are NITPICK-level UX alignment; raised to CONCERN because each has a numbered acceptance criterion that Phase 6 will test.

**C-5. OD-1..OD-4 are defined only in plan.md; ux.md has no OD labels and the mapping is partial.** Plan C.8d says to record answers in "ux.md (open question 1, 4)". ux.md open question 1 bundles off-canvas cancel (OD-3) with the crosshair step selector (OD-2); question 4 is Drive offline only. OD-1 (whether crosshair mode exists at all) is not an open question in ux.md; ux.md S12 presents it as the design, not as PROPOSED/unapproved, and the "Move vertex N" actions (OD-4) are in the S13 table, not the questions list. Resolution: label OD-1..OD-4 in ux.md's open-questions list one-to-one.

**C-6. UX copy asserts behaviour that depends on unapproved decisions.** ux.md S11's first-use hint "Drag off the image to cancel" and S8's "Place at crosshair (suggested)" are unconditional text, but plan makes them conditional on OD-3 and OD-1 (A.5c ships the L2 chip without the offered action until decided). If rejected, the copy would be false. Resolution: mark the strings conditional in ux.md S8/S11.

**C-7. ux.md S10 asserts "Placed measurements are already saved" while S9 and Open Question 2 treat that guarantee as UNVERIFIED.** The plan's C.5 AC only guarantees ordering and no lost rows, not "saved up to last placement". Resolution: gate the S10 sentence on the same confirmation as S9.

**C-8. Plan's mutation-flag list is shorter than the proof matrix.** Story B.5 procedure step 3 lists `loaderPerPress, perDrawMeasure, busyWaitMove, backgroundBusyLoop, leakStrongRef, exitOnBackground`; the matrix rows also use `loupeFullDecode`, `cacheNoLimit`, `undoUnbounded` and "OOM injection". B.5d says "implement the named mutation flags," so those four rows have no defined flag. Also B.5d requires a per-gate "mutation name" in the budgets file, but `BudgetEntry` (glossary) and ADR-001's schema have no such field. Resolution: extend the list and add the field to `BudgetEntry`/ADR-001.

**C-9. Requirements In Scope audit items without a plan task.** requirements.md audits `PlatformImageDownscaler`, Coil disk-cache sizing and depth-estimation inputs. `grep -iE 'Downscaler|disk cache' plan.md` returns nothing; depth only appears as A.4e CPU/cancellation. Resolution: add them to a B.2/B.3 audit task or note they were covered in `research/architecture.md`.

**C-10. Observability requirement vs `DiagnosticsSample`.** requirements.md requires logs with "screen, image dimensions, cache size, undo depth". Glossary `DiagnosticsSample` has cpu delta, top-3 threads, foreground, heap, undo depth, screen, image dims, no cache size. Resolution: add Coil cache size to the sample.

**C-11. Feature traceability gaps in Epic C.** Header line "M6, m1-m8 -> C.9" but C.9 is titled "minors m3-m7" and no task covers m8 (process death resets VM state, `research/features.md:93`). M8 (loupeOffset at screen scope) is not in the traceability sentence (it is covered by A.4a). Resolution: map m8 to a task or list it as a tracked follow-up; add M8 -> A.4a.

**C-12. Scope drift (stories with no requirement behind them).** D.5 "Annotate a photo" palette command (requirements Q6 asks only for grouping/descriptions in the palette); D.9 first-run tip (not in requirements; metric 4 asks for sections and descriptions); C.6/C.7/C.8/C.9 MAJOR and minor fixes (metric 5 requires only BLOCKER/CRITICAL fixed or tracked); handedness in C.9a (ux.md calls it optional, setting location open question 5, no UX criterion, not in the OD list). Consistent with the owner's "pursue full scope" stance, so recorded as drift to confirm, not remove.

**C-13. ADR-002 and plan differ on the idle-CPU assertion.** ADR-002: background CPU "asserted against the idle gate." Plan A.3/B.5b: idle CPU is `ReportOnly` until B.7 and A.3d may rename the phase. ADR-002 is amended only after A.3d/A.3e run. Resolution: reword ADR-002 now to "reported against the proposed 10% budget; promoted after calibration."

## NITPICK

- N-1. Terminology: "Library" is both the nav section (Photos + Files), a "Library Stats" row inside the Graph section (ux.md S1), and ADR-006 option B's merged screen name. Rename the stats row or the section.
- N-2. Terminology: Gallery/Assets (code and ADR-006 option A), Photos/Measured Photos/`{PHOTOS}` (ux.md, D.5, D.6), `MeasuredPhotos`/`Files` (glossary, "working names") are used interchangeably in plan story titles ("Cross-links between Photos and Files" vs "Gallery reads are bounded"). Acceptable while D.1 is open, but D.1 should also sweep plan prose.
- N-3. "Probe" naming is consistent (`ResourceProbe`, glossary notes it replaced "MemoryCpuProbe"), but plan A.3b/B.3 use undefined scenario letters (h), (j) and none for BackgroundIdle; Success Metric 2 defines only (a)-(g). Define letters or drop them.
- N-4. Gate vs report-only wording: JSON values `gate`/`report-only`, enum `Gate`/`ReportOnly`, prose "hard gate"/"Gate"; ADR-001 also says "hard gate". Pick one set in the glossary.
- N-5. Bazel checklist (Story B.8) omits `MeasurementAnnotationActorRoutingTest` (C.5c, businessTest) which needs an `AllBusinessTests` entry.
- N-6. ux.md S12 wireframe note is self-contradictory ("pans image under it?" vs "crosshair moves over a fixed image"); the plan AC (crosshair moves 1/imageWidthPx per nudge) matches the second.
- N-7. ux.md S2 bottom-bar label "Pages" vs sheet row "All Pages"; requirements Q6 calls it "Journals / Pages / Search / More". Confirm intended label.
- N-8. Plan header says "ready for implementation" while all nine ADRs are `Proposed` (004 and 006 depend on spike/owner). Consider "ready for implementation of Epics A, B and non-gated C" in the header.
