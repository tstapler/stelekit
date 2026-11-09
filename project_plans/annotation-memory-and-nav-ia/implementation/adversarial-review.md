# Adversarial Review: annotation-memory-and-nav-ia

**Date**: 2026-11-08
**Verdict**: CONCERNS (Re-review 1: B1, B2, B3 resolved; no remaining blockers; see "Re-review 1" at the end)

Reviewed: `implementation/plan.md` (924 lines), `decisions/ADR-001..009`, `requirements.md` (incl. Amendment), `research/cpu.md`, `research/pitfalls.md`, `research/features.md` (C1/C2 sections). Review-only; nothing else was changed.

The plan's epistemic framing (the "What we know" table, the Keep rule, the honesty about the unreproduced kill) is good. The weaknesses are in what the gates and proofs can actually demonstrate.

## Blockers

- [ ] **B1. The crash-class gates cannot fail, and the proof mechanism for the other gates is circular.**
  - `loaderConstructions == 1` is a counter introduced *inside* task A.4c (line 271). B.5c (line 410) proves the gate by reverting A.4c, which also reverts the counter.
  - B.5c also says to "run the scenarios on `408907d9cd` (pre-fix)". That commit has no ResourceProbe, no `DirectTouchInjector`, no `eventListenerFactory` seam and no counter, so the run is impossible as written. The harness would have to be back-ported, and the plan does not say so.
  - Only two mutations are specified (a loader revert and a 10 ms busy-wait). The gates that map to Success Metrics 1 and 3 have no failing proof:
    - `process survived`
    - `no bad exit reasons`
    - `idle CPU < 10%` in the background
    - `AnnotationRapidPlacementSmokeTest` (B.6b)
  - The kill never reproduced (cpu.md Summary, pitfalls.md Part B), so each of these passes on the pre-fix code by construction. ADR-001 requires "(a) fails on the pre-fix commit" for every gate. For these gates (a) is unachievable and (b) is not specified.
  - **Recommendation:**
    1. Move the loader-construction counter and the `CoilDecodeStats` seam into B.2. They must exist before any fix so a revert cannot remove them.
    2. Add a per-gate proof matrix to B.5 with columns gate, pre-fix proof, mutation proof, and "cannot be proven (reason)".
    3. Make a mutation that keeps burning CPU after `onStop` (for example a leaked `while(true)` coroutine in the editor scope) mandatory for the idle and exit-reason gates.
    4. State in B.5c how the harness is applied to `408907d9cd` (a worktree, cherry-picking B.1 and B.2).
    5. State in the plan, not only in a footnote, that Success Metric 1's "reproduces the pre-fix failure" is unmet and needs explicit owner acceptance.

- [ ] **B2. The "widened repro" (A.3, ADR-002) has no positive control and may not reach the state the stock rule applies to.**
  - The plan's only route to the stock kill is "burst, then background, then busy loop". Nothing in the tree is known to loop in the background (cpu.md: "None was found", post-idle 2-4%). So the A.3 background phase passes trivially.
  - Nothing shows that the instrumented test process becomes HOME/cached after `pressHome()`. INFERRED by this reviewer: a process hosting an instrumentation is normally held at an elevated oom-adj, and the stock rule needs `PROCESS_STATE_HOME` or higher (cpu.md section 1). If so, the test can never hit the rule even with a real busy loop.
  - A 60 s window is also not the 5-minute window (ADR-002 acknowledges this).
  - **Recommendation:**
    1. Add a positive-control task. Inject a deliberate background busy loop and show the probe reports a CPU % above the gate. Record the process importance, read from `ActivityManager.getRunningAppProcesses()` or `/proc/<pid>/oom_score_adj`, during the background phase.
    2. If the importance does not reach cached or HOME, say that A.3's background phase measures idle CPU only, not the OS rule. Rename the claim accordingly in A.3 and the "What we know" consequence paragraph.

- [ ] **B3. The C.1 "legacy calibration flag" requires persisted data, so there is a hidden schema/serialization change that the Migration Plan calls a contingency.**
  - The C.1 acceptance criterion says a calibration "saved before the fix and flagged `legacy`" shows "Recalibrate for accuracy" with "no stored value changes". features.md C1 shows `pixelsPerMeter` is stored as an absolute pixel value at the *then-current decode width* (VM:455-482).
  - The decode width varied by device, fold state and rotation. Git history of #556/#558 (spike C.1a) cannot tell which decode size an old row used.
  - Either every existing calibration is legacy, or a new persisted marker (basis/version) is needed to distinguish old from new. Either way a column or serialized field is needed. The new `NativeImageSize` basis also silently changes the meaning of every *new* row relative to old ones with no way to tell them apart.
  - Yet "Default: no schema or on-disk format change" (line 106) and the C.1 estimate (3 tasks, 2S+1M) assume none.
  - C1 itself is labelled INFERRED in features.md. It is also ranked CRITICAL, and Epic C promises tests for it before the C.1a spike has run.
  - **Recommendation:**
    1. Run C.1a now. It is a read-only spike and needs no implementation.
    2. Resolve ADR-004 to Accepted with the concrete marker and storage shape before approving the plan.
    3. If a marker is stored, move C.1 to the Migration Plan proper: `MigrationRunner` entry or serialized default, plus `SteleDatabase.sq`, plus regenerated SQLDelight sources. Re-estimate C.1.
    4. Add a unit test for the pre-fix to post-fix row read, and label C1 as unconfirmed until the two-window-size log from features.md C1 ("Confirm") is run.

## Concerns

- [ ] **C1. "VERIFIED" evidence is weaker than presented.**
  - The "hoisting the ImageLoader changed nothing measurable VERIFIED" row (plan line 20, ADR context) comes from cpu.md suspect #4. That measurement was one run per variant on the `performTouchInput` harness, which the same document says inflates CPU 5-7x. It showed 792 vs 705 ms/press (+12%, outside the quoted ±10% noise).
  - The "±10% noise band" used by the Keep rule (plan line 251) is quoted from that inflated, n=1 harness. It is not a measured direct-injection noise floor and not a CI-emulator floor.
  - Nothing in cpu.md states how many repeats produced the 142/99/86 ms figures.
  - A.4's Goal says "the measured CPU hot spots". Per-component costs (suspects 1, 3) are INFERRED in cpu.md. Only the loupe on/off delta is measured.
  - **Recommendation:**
    1. Make the first deliverable of A.3 a noise-floor measurement (n >= 10 runs of the same scenario on the CI emulator).
    2. Derive the Keep-rule band from that, not from cpu.md.
    3. Reword A.4's goal to "suspected hot spots".

- [ ] **C2. Gates and the CI soak interact badly with the 16 GB runner and emulator.**
  - ADR-001 puts "post-gesture idle CPU % < 10" in the day-one hard-gate set, with no calibration run. The measured emulator baseline is 2-4%, taken after JIT warm-up and with swiftshader; a cold CI emulator right after `pressHome()` can spike from JIT, GC and the activity-stop path. Calibration (B.7) is skipped for exactly this gate.
  - The plan contradicts itself on the sustained-drag gate. A.3's acceptance criterion says "sustained CPU < 60% (initial `Gate`, baseline 36%)", while B.5b says sustained CPU 60% is "marked `report-only` until the first CI calibration".
  - Several `Gate` thresholds are new, uncalibrated numbers: loupe `maxBytes <= 2,097,152` (A.5), `maxDecodeBytes <= 4,194,304` (B.3 scenario f), and `MAX_DECODE_PIXELS * 4` (A.3). The loupe 2 MB gate is also fixed before the ADR-003 spike chooses the strategy.
  - pitfalls.md shows the AVD ignored `-memory 2048`, and ADR-009 relies on `ram-size`. B.6 treats a mismatch as `invalid-device` and skips memory gates. `android-smoke` already uses `-memory 2048`, which the local run showed to be ineffective, so the "low-memory profile" in Success Metric 1 is unverified.
  - **Recommendation:**
    1. Start idle CPU as `ReportOnly` and promote through B.7, or make the gate a median of 3 windows.
    2. Reconcile the 60% contradiction between A.3 and B.5b (pick report-only).
    3. Derive every numeric `Gate` from either a deterministic rule or the A.5a/B.7 data.
    4. Verify that `ram-size` reaches the guest in a CI dry run before B.6 claims a low-RAM profile.
    5. Say explicitly that if the pin fails, Success Metric 2's numeric memory budgets are report-only, not gating.

- [ ] **C3. Dependency and ordering errors between epics.**
  - A.4 "before/after probe delta" needs the A.3 baseline. Both are in wave W-C with no intra-wave order stated for A.3 vs A.4a-e.
  - C.2 is listed as "quick, independent" in W-A and the visualization. But C.2b edits `ANN/LoupeOverlay.kt`, which is created by A.4a (W-C). C.2a (pure) can go first; C.2b cannot.
  - PR #560 (loupe `layout` fix, `LoupeAlignmentE2ETest`) touches the same loupe code that A.4a (extraction, lines ~1607-1684), C.2b, C.9a and A.5 rewrite. It is only a dependency of B.6c. The CPU baselines in cpu.md were measured *without* #560 (pitfalls.md). Rebasing creates merge conflicts, and the baselines change if #560 changes the layout cost.
  - D.4's acceptance criterion needs `first-open-screens.txt` updated, but that file is in D.8, which is in the later wave W-F.
  - Add the sequencing as explicit edges, and include rebasing on #560 (or confirming it is not merged) in Wave A.

- [ ] **C4. A new index in D.7 may be a hidden migration.**
  - D.7's acceptance criterion needs `QueryPlanAuditTest` to show the paged and tag-filtered queries "use an index and not a full scan". If no suitable index already exists on `image_annotations`, one must be added.
  - A new index created in `SteleDatabase.sq` only helps existing user databases if it is also created in `MigrationRunner` (the CLAUDE.md rule applies by the same logic as tables).
  - ADR-008 says "no new table, so no `MigrationRunner` entry" and never mentions indexes.
  - Check this before D.7a. If an index is needed, add the `MigrationRunner` entry and a `MigrationRunnerSchemaSyncTest` extension for indexes.

- [ ] **C5. Test placement violations or ambiguities (TestPlacementAudit / CLAUDE.md).**
  - `FixtureGraph.kt` is in `androidInstrumentedTest` (AIT) while its test `FixtureGraphCountsTest` is in `jvmTest` (B.3a). jvmTest cannot see AIT classes, so this will not compile. Put the seeding logic in commonTest/businessTest and have AIT consume it, or test it in AIT.
  - `ImageLoaderConstructionCountTest` and `CoilSeamTest` are placed in jvmTest as "pure seam". CLAUDE.md puts pure logic that touches only commonMain in commonTest. The Coil loader builder is a possible Android-only surface, so confirm the source set before assigning.
  - `MeasurementLabelCacheTest` is "jvmTest only if it needs a real renderer; otherwise androidUnitTest". The audit needs a decision at plan time.
  - `NavModelCoverageTest` (D.2) in commonTest enumerates sealed subtypes of `Screen`. `KClass.sealedSubclasses` is JVM-only reflection, so this will not compile for wasm/iOS. Place it in businessTest/jvmTest or use an explicit list plus a compiler-exhaustive `when`.
  - A.4b's "textMeasurer.measure invoked 0 times" needs a seam that is not listed in any task's Files. The "screen-body composition counter" via `@VisibleForTesting` in commonMain needs an expect/annotation decision.

- [ ] **C6. Wiring gates (D.4, D.5) are under-specified.**
  - D.5 adds an "Annotate a photo" command that starts an image-picker flow. `WiringCompletenessTest` expectations are mentioned, but there is no task for the platform picker callback and no JVM/wasm behaviour (desktop has no equivalent).
  - D.4 introduces `MoreSheet.android.kt` as a new Android-only composable. The plan needs a `WiringCompletenessTest` or equivalent audit entry, and the iOS/desktop shells need a non-regression note (the drawer stays there).
  - `DemoGraphCoverageTest` help-exempt list and the help/docs pages are in the D.8 table. Say which task owns them if option B or C renames screens.

- [ ] **C7. Scope drift against the requirements.**
  - Success Metric 5 requires every BLOCKER/CRITICAL fixed or tracked. The plan fixes all MAJOR and minors, builds a new crosshair-and-nudge placement mode (C.8c, a new feature that still awaits owner confirmation), a first-run tip (D.9), "Annotated" filter chips and an "Open in Photos" action (D.6b), and a production `setProcessStateSummary` plus a persistent CPU sampler (A.2d).
  - Success Metric 4 only needs sectioned nav with descriptions plus an ADR and UX review. The requirements' own Rabbit Holes warn about IA scope creep.
  - The owner's standing preference is full scope, so this is not a request to cut. Mark C.8c and D.5-D.6b as depending on owner confirmation (C.8c is already an Unresolved Question, but the effort table counts it as committed).
  - Note also that nothing in A-D ties the C2 loupe-narrow-canvas crash (the one reproducible crash by reading) to a persistent device test; it is Robolectric-only.

- [ ] **C8. Estimates.**
  - Costs are expressed in cost classes and CU (good), but the CU-per-token conversion (0.93), the x1.75 verification multiplier and the XS/S/M token midpoints are explicitly INFERRED (plan line 895 and 912).
  - Unlike the task bands, B.6a ("M") bundles a three-invocation Gradle workflow change, a RAM pin and emulator iteration. A.5b ("M") covers an `expect`/`actual` across JVM, Android, wasm and iOS fallback. C.1 is sized with no migration (see B3). These are likely under-classed.
  - The story rows sum to 9,229k vs a stated 9,231k total (trivial rounding).
  - The overrun checkpoint is a good control. Add B3's C.1, A.5b and B.6a to its named "likeliest sources".

- [ ] **C9. Unresolved-question handling is mostly sound but has gaps.**
  - W1 (the owner's Logs line) blocks the claim that anything cures the crash, yet the epics run to completion regardless. There is no explicit "stop and replan" trigger if the owner's `importance=` is FOREGROUND (A.1 only adds an OEM note).
  - The plan has no plan for the case where the kill was a different reason (the A.1 branch "prioritise memory/ANR" has no tasks attached).
  - The "Calibration soak" B.7 needs 10 valid CI runs, and the benchmark workflow triggers on every push and PR. The workflow would therefore also double the job count per PR; the Unresolved Question about job duration is deferred to after merge, which is too late for a gate that fails PRs.

## Minors

- Glossary says "25 terms"; verify the count (the table lists 26 rows).
- `ProbeScenarioId` in the glossary lists scenario names but not the suffix letters used elsewhere ((h), (j) in A.3b); the lettering of Success Metric 2 (a)-(g) vs the extra scenarios is easy to misread.
- B.3 scenario (g) uses `WeakReference` plus three GCs as a Gate. Three `System.gc()` calls are not guaranteed to clear a weak reference on ART; use a loop with a timeout.
- A.2 tests "AUT/.../EditorDiagnosticsSamplerTest (Robolectric-free)" sit in androidUnitTest; it could be commonTest with a fake clock, matching CLAUDE.md's "pure logic in commonTest".
- Two decision records still read "Proposed" while the plan header says "Ready for implementation"; ADR-004, ADR-006 and ADR-003 are legitimately open (spike or owner gate).
- `androidx.test.uiautomator:uiautomator:2.3.0` is claimed to be "already used by `macrobenchmark/`" (ADR-002). This review did not find a matching dependency declaration with a quick grep of `*.kts`/`*.toml`; confirm the version before pinning.
- D.6a and D.7a both regenerate and commit `kmp/src/generated/sqldelight/` in different waves; expect merge conflicts in generated files. Sequence them or regenerate on rebase.
- Observability: requirements ask each record to carry the budget value. `ProbeRecord` lists scenario, metric, value, unit, sha, device profile but not the budget; the evaluator joins it later, which is acceptable but should be stated.


## Re-review 1 (2026-11-08)

**Verdict: CONCERNS.** Scope: B1, B2, B3 and the folded concerns. Evidence: plan.md lines 27, 117, 254-262, 275-282, 362-364, 387-390, 441-476, 564-581, 1079-1093; MigrationRunner.kt and MigrationRunnerSchemaSyncTest.kt read.

### B1: resolved, with one concern
- The counter moved to B.2d inside the probe-only PR (B.1d), so `git revert <A.4c>` leaves it in place. Step 1 of the procedure is realistic: probe-only PR on unfixed behaviour yields `loaderConstructions = 100`. The matrix gives every crash-class row a red route (`backgroundBusyLoop`, `exitOnBackground`, OOM injection) and lists "cannot be proven" honestly. Success Metric 1 is flagged for owner acceptance (line 156).
- [ ] **R1-C1 (concern). Mutation flags are described as "androidInstrumentedTest only", but B.5d Files lists only `AIT/.../Mutations.kt`.** `loaderPerPress`, `perDrawMeasure`, `busyWaitMove` and `backgroundBusyLoop` (a coroutine in the editor scope) need a hook in production code. Specify a default no-op `internal` interface in the probe-only PR (so it is in the "zero behaviour change" PR) and a release-build check that the hook is never set. Also add a check to B.1d that the PR changes no production behaviour (for example, an unchanged-output run of existing tests).

### B2: resolved
- A.3d states the control can end in outcome (a) or (b), and (b) forces the rename to "idle CPU after burst" and removes stock-rule wording. Importance is recorded via `getRunningAppProcesses()` and `oom_score_adj`. The claim vocabulary ("mitigated and monitored" absent the owner's `importance=` line) is fixed in advance (line 27). Minor: A.3d's "AIT-only busy loop started from the editor scope" has the same seam issue as R1-C1.

### B3: resolved
- C.1 is now a real migration: `calibration_basis_version` in `SteleDatabase.sq`, a guarded `AddColumn` in `MigrationRunner`, `RestrictedDatabaseQueries` stubs, regenerated sources via rsync, `MigrationRunnerSchemaSyncTest`, and a pre/post row test. TelemetryDatabase is not involved. Ordering is safe: spike C.1a (W-A) produces the ADR-004 amendment before any code, and C.1d precedes C.1b writing version 1.
- Minor: `MigrationRunnerSchemaSyncTest` checks table names and a specific AddColumn example; it does not generically check new columns. The pre/post row test covers this; consider extending the sync test for columns. The dependency diagram lists "C.1b/c/d" together, so state "d before b" explicitly there.

### New blockers from the repair
- None found. No circular epic dependencies; no human-time units (grep clean); counts (93 tasks, 32 stories) are consistent between the header and the repair log.
