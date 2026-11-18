# Owner verification checklist (excalidraw sketch engine)

Each section is one release or device pass. An item is checked by the owner before the release-please PR is merged; nothing here is
checked by an agent. Unchecked means UNMEASURED.

## Reader side of the combined release (Task 6.2.5e; depends on Stories 1.4.3, 1.4.5, 6.1.1 and 2.1.3 only, never on the A1 gate)

**There is no separate reader-only release.** The owner chose on 2026-11-17 (OD-1 amended, "Option B: ship together") to ship the reader and
conversion in ONE release. This section is the reader-side paragraph and checklist that go into that release's notes (Task 6.2.5c, next
section); the id 6.2.5e and its edges are kept. It needs only the router (1.4.3), the lease and refusal paths (1.4.5), the embed preview (6.1.1)
and the painter (2.1.3). Every outcome row of requirements.md "Combined outcomes" except "stop entirely" and "stop at the pure engines" has it.

### Reader-side release-note paragraph (paste into the combined release body, above the "Conversion and flag release" notes)

> **Sketches: this version can read the new sketch format.** This build opens and shows sketches saved in the new editor's format (v2).
> **A device still running an older SteleKit build cannot open ANY sheet of a project that contains an upgraded sketch**: it refuses the whole
> project and changes nothing. The only warning that device gets before the first new-format write is the confirmation dialog on the device
> that does the upgrade, so update SteleKit on **every device** that opens your sketch projects **before** you upgrade any sketch.

Claims, and the test or file that backs each:

| Claim | Evidence |
|---|---|
| Opens and shows v2 sheets read-only | `EditorRouterTest.v2_should_OpenReadOnlyWithTheReason_When_ModeIsOff` (`kmp/src/businessTest/kotlin/dev/stapler/stelekit/sketch/v2/EditorRouterTest.kt`) |
| Older builds fail closed on a project holding a v2 sheet | `LegacyStoreRefusesV2Test` (same directory); an older build's behaviour is only checked by the owner's device pass below |
| Reads v2 sheets in every mode | `EditorRouterTest` (above); creation and conversion are reachable in this release by design (Option B) |

### Recorded owner decision: Option B, ship together (chosen by the owner 2026-11-17)

OD-1 (decided 2026-11-16) said "ship a reader-only release one release before conversion is reachable". The owner CHANGED that on 2026-11-17 to
**Option B: ship reader and creation together**. No `V2Mode.kt` edit is made: `V2Build.CONVERSION_READY` and `V2Build.CONVERSION_OFFERED` stay
`true` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/V2Mode.kt`, lines 34 and 37 at the time of writing), so the Settings mode
control offers "New sketch projects only" and "All sketches (Upgrade)", the read-only banner offers "Use new", and conversion and v2 creation
are reachable in the same release as the reader. Option A (both constants `false` for one earlier release) was not chosen and has not been built or tested.

What this drops, stated plainly: the old-build safety step. A device that has not updated can meet a v2 project before it can read it. It then
refuses the WHOLE project (no data change) and has no notice; the first-write dialog on the upgrading device is the only warning. What remains:
backups and Restore original, the fail-closed old reader, per-device per-project acknowledgement, flag default `Off`, conversion only on the explicit
Upgrade action. What stays risky: a lagging Android release or cached wasm build that syncs the owner's graph sees the project as corrupt or
unreadable with no earlier warning, and nothing in this repo can change a build that already shipped. See plan.md "Decision log" 2026-11-17.

The conversion release (Task 6.2.5c) stays gated on the A1 gate, which was WAIVED, never passed (see the pass section below). Owner choice
recorded here: Option B, ship together, 2026-11-17.

### Checklist

- [ ] The combined release notes (this paragraph plus "Conversion and flag release") say plainly that a device still running an older SteleKit
  build cannot open ANY sheet of a project that contains an upgraded sketch, and that the first-write consent dialog is the only warning that
  device gets before the first v2 write. (Replaces the earlier "build cannot create or convert" item: creation IS reachable under Option B.)
- [ ] Both `V2Build` constants are `true` in the build released (Option B; `ConversionReadyConsistencyTest` still holds `CONVERSION_READY` honest).
- [ ] The reader-side paragraph above is in the release body, with the "update every device first" advice unchanged.
- [ ] Owner device pass: a v2 sheet copied to a second device on the previous release is refused there with no data change; the same
  sheet on this build shows read-only with the reason text "Turn on the new editor to edit." (`SketchReasonText.READ_ONLY_TURN_ON`).
- [ ] No A1-gate text appears in the reader-side paragraph (script-checked edge: 6.2.5e has no path to 6.2.4e, 6.2.4f, 6.2.5a or a parked node).

## Conversion and flag release (Task 6.2.5c; the ONE combined release with the reader side, Option B chosen 2026-11-17)

Not publishable by an agent. Needs Task 6.2.5e (the reader-side paragraph above, in the same release; Option B recorded above) and the A1 gate. The A1 gate was
WAIVED by the owner on 2026-11-17 (`a1-gate.md`, "Owner waiver of the A1 gate"); its verdict is **UNMEASURED**. This release's notes must
never say it passed. iOS is unverified (BUG-001, `docs/bugs/open/BUG-001-kotlin-native-bundle-service-classloader-mismatch.md`): no iOS
statement is made; the new editor is compile-only and storage-unavailable there.

### Flag defaults for this release

- `sketch.engine.v2` ships default `Off` (a missing, blank or unknown stored value reads as `Off`, `V2Mode.parse`). This release makes the
  new editor AVAILABLE; it does not turn it on for anyone. Turning it on is the user's own Settings > Sketches choice, behind the per-device
  confirmation (Task 1.4.6c) and, for the first v2 write in a project, the first-write dialog.
- Both `V2Build` constants must be `true` in the build released (Option B: unchanged; no earlier reader-only release exists).
  Under an A4 NO-GO, `CONVERSION_OFFERED` stays `false`: the mode setting then tops out at "New sketch projects only", "All sketches (Upgrade)"
  shows "Not available in this build yet", and the conversion paragraph of the notes below is omitted.
- Changing the DEFAULT mode in code is NOT part of this release. It follows the "Flag-exit checklist" below and the owner's ADR-005 entry.

### Release notes (paste into the release body; omit the conversion sentences under an A4 NO-GO or an import/export-first pivot)

> **Sketches: a new editor you can turn on.** SteleKit now has a new sketch editor (shapes, text, arrows that follow shapes, frames,
> layers, constraints and `.excalidraw` import and export). It is **off by default**. Turn it on in Settings > Sketches, per graph:
> "New sketch projects only" creates new-editor sketches only in new sketch projects; "All sketches (Upgrade)" also lets you upgrade
> existing sketches one at a time.
>
> **Read this before you turn it on.** A device still running an older SteleKit build cannot open **any** sheet of a project that contains an
> upgraded sketch. It refuses the whole project and changes nothing. This release is the first that can read the new format, so there was no
> earlier version to update to first: update SteleKit on every device before you upgrade a sketch. The confirmation dialog the first time you
> upgrade in a project is the only warning an un-updated device gets before that first write.
>
> **Your original is kept.** Upgrading keeps a copy of the original sketch in the project's `backup/` folder. To go back, open the
> upgraded sketch and choose "Restore original (v1) sheet"; the new-editor version is kept in `backup/` too.
> "Delete backups" in Settings removes those copies and ends Restore for them.
>
> **To turn it off:** set the mode to Off. New-editor sketches then open read-only ("Turn on the new editor to edit.") and nothing is
> converted back by itself.
>
> **Not verified:** the new editor has not been measured against Excalidraw on a real task (that check was skipped on purpose), iOS is not
> verified and cannot save new-editor sketches yet, and the timing, device and usability checks listed in the project's owner checklist
> have not been recorded. No claim here depends on them.

### Rollback procedure (what to do when a stop-ship row fires or a report arrives)

1. Settings > Sketches > mode: Off. (Code: set `sketch.engine.v2` to `Off`; v2 sheets then render read-only, no data conversion needed.)
2. To get an upgraded sheet's v1 content back, run Restore original on that sheet (`SketchBackupRestore`, Story 6.2.6): it copies
   `backup/<uuid>.v1.json` to `sheets/<uuid>.json`, removes `sheets/<uuid>.v2.json`, rewrites the model reference and `fileSha256` in one
   model write, and keeps the new-editor version as `backup/<uuid>.v2-before-restore.json`. It is a tested function, not a manual file copy.
3. Code revert is a standard PR revert; a revert never touches written sheets.
4. Log any loss in `quality-signals.md` (data-loss log) with its regression test, and halt the release until the test exists.

### Checklist (each is checked by the owner before the release-please PR is merged)

- [ ] The A1 gate status is stated in the notes as "not run / waived", never as passed.
- [ ] The release-please PR for this release records Option B (ship together, no earlier reader-only release) as chosen 2026-11-17.
- [ ] Both `V2Build` constants match the intent above (`true` for conversion; `CONVERSION_OFFERED = false` under an A4 NO-GO).
- [ ] The release notes above are in the release body, the "older versions cannot open ANY sketch" paragraph unchanged, and the
  "where backups live, how to restore, how to turn it off" paragraphs present.
- [ ] Owner device pass of the previous section ("Owner device and desktop passes") recorded, or listed as not run in the notes.
- [ ] Restore original was exercised once on a real upgraded sheet on a device: ______
- [ ] A4 (a user-facing pre-upgrade warning, NOT a release gate since 2026-11-17): devices that open the graph and their builds, listed in the notes as "update these first": ______

## Flag-exit and legacy-retirement checklist (Task 6.2.5d)

Nothing in this section changes the flag default by itself. Counters never flip anything: the owner records a flip in the ADR-005 index.
The Settings > Sketches readout now shows each counter row against its threshold (`QualityThresholds`, tested by
`QualitySignalsThresholdTest`: integer arithmetic, "not enough data yet" below the minimum n) and a stop-ship line; the data-loss row is read
from the owner's log, `quality-signals.md`. Thresholds are INFERRED and `[tunable]` (requirements.md "Flag and legacy-editor exit criteria").
The window starts at `window-start:` in `quality-signals.md` (UNSET: no flag release exists yet) and is capped at 26 weeks.

| Signal | To flip the default | Stop-ship (flag back OFF) |
|---|---|---|
| Data-loss reports (owner log) | 0 confirmed in the 4 weeks before the flip, each root-caused with a regression test | any confirmed report: flag OFF, release halted until a regression test exists, restore offered |
| Crash rate `crashes / v2Sessions` | n at least 50 and at most floor(n / 100) crashes (0 in 50 to 99, at most 1 in 100 to 199) | 2 or more among the last 20 sessions |
| `NoConvergence` rate | n at least 200 committed solves and at most floor(n / 50) (4 in 200) | 11 or more among the last 100 solves: constraint UI hidden |
| Save conflicts | informational | none |

Insufficient data: while the constraint UI is parked or hidden the `NoConvergence` row is not applicable; a row whose minimum n is unmet at
26 weeks reads "insufficient data, UNMEASURED", flips nothing, and the mode stays `Off` unless the owner records `NewProjectsOnly`, or
extends the window once. At one-user scale the minimum n is likely never reached, so the flip is in practice the owner's recorded decision.

Flip to `On` (ceiling `On`, A4 GO) needs ALL of:
- [ ] the A1 gate recorded PROCEED (it is UNMEASURED today because it was waived; this item stays unchecked until the owner runs the paired run or records a different decision in the ADR index)
- [ ] `ParityV1SuiteTest` and the `FlagRolloutTest` baseline at 100%
- [ ] the applicable thresholds above holding with minimum n met, or the owner's recorded insufficient-data decision
- [ ] the combined release out (no separate reader-only release; "every device reader-capable (A4)" is no longer a criterion, it is a user-facing warning, 2026-11-17)
- [ ] the owner's confirmation in the ADR-005 index

Flip to `NewProjectsOnly` (capped rows: A4 NO-GO, PIVOT "import/export first", PIVOT "Job 2 first" under A4 NO-GO) needs ALL of:
- [ ] the A1 gate recorded PROCEED, or PIVOT with the option that ships this release recorded in its ADR
- [ ] `FlagRolloutTest` baseline at 100% and `ParityV1SuiteTest` at 100% for the behaviours a new sheet uses
- [ ] applicable thresholds holding from the same window start and the same 26-week cap
- [ ] the reader side shipped with the flag release (6.2.5e); no "every device reader-capable (A4)" criterion applies to either flip since 2026-11-17
- [ ] the owner's confirmation in the ADR-005 index

A flipped default shows the per-device enable confirmation (Task 1.4.6c) before the first v2 sheet is created on a device that has not acknowledged it.

Retiring the classic editor UI (its own change and ADR, not a task of this plan) additionally needs: the default `On` for at least 8 weeks
with data loss at 0 and the crash threshold held; every sheet in the owner's graphs either Converted or consciously `Declined`; the parity
gate still green. Retirement removes only the editor UI: the v1 READ path, the `SketchRenderDispatcher` v1 branch and `V1ToV2.lift` stay.

## Task 6.2.5b verification run

Run by an agent in the worktree before this file was committed; results are in the commit message and the report of that wave, not
repeated here as a claim. Not covered and stated so: the display-checked `jvm_tests` UI run, `:kmp:wasmJsBrowserTest` and
`compileKotlinIosArm64` (BUG-001 blocks iOS on this Linux host; no iOS result exists). Owner or CI: ______

## Observation windows (owner only)

- [ ] `window-start:` in `quality-signals.md` set to the date of the flag release, then counters copied in when a window is read.
- [ ] 8-week adoption window (metric 8, advisory) read: authored ______ of new diagrams (minimum 5): ______
- [ ] 4-week data-loss window before any default flip: reports ______

The Task 6.2.4c device passes follow.

## Owner device and desktop passes (Task 6.2.4c; before any flag release of Task 6.2.5c)

Nothing in this section is checked, timed or judged by an agent. Every figure is UNMEASURED and every verdict UNRECORDED until the owner
writes it in. Failure of an advisory item is reported here and in the release notes; it never fails CI.

What this section does not claim:

- **The A1 gate was WAIVED, not passed.** The owner waived the pre-check (Task 0.1.7c, `baselines.md`, 2026-11-16) and then waived the hold on
  the gated stories (`a1-gate.md`, "Owner waiver of the A1 gate"). The gate verdict stays **UNMEASURED**; no paired in-app and excalidraw.com
  run exists. Nothing in the release notes, the docs or the product may say the A1 gate passed.
- **iOS is unverified.** BUG-001 blocks the iOS compile on a Linux host (`pivots.md`, Task 6.2.9a row "iOS"; `research/input-spike.md`). A macOS or CI run
  must compile the target before any iOS statement is made; no check below covers it.
- The scripted scenario of metric 2 (at most 60 actions) is CI (`ArchitectureScenarioTest`, `ArchitectureScenarioAndroidTest`); the timed run below is
  the owner's, advisory.

### 1. Metric 2 timed run: the 15-element architecture diagram (requirements metric 2b; advisory, bar 1.5x)

The diagram: 9 labelled boxes, 4 bound arrows that follow a moved box, 1 frame, 1 group. First attempt, in-app, on the owner's own device.

- excalidraw.com median (Task 0.1.7a, `baselines.md`): UNRECORDED. `baselines.md` has no Task 0.1.7a entry, so the 1.5x bar cannot be computed and the
  result below stays "unmeasured", never "met".
- [ ] Desktop: in-app time ______ ; ratio to the median ______ ; at most 1.5x? yes / no / not computable
- [ ] Pixel_Fold (device or AVD): in-app time ______ ; ratio to the median ______ ; at most 1.5x? yes / no / not computable
- [ ] Blockers or defects met while drawing (each becomes a defect against its owning story): ______

### 2. Pixel_Fold frame time (OD-2; advisory gate, never CI)

- [ ] 500 elements, pan for 120 frames on the Pixel_Fold (a headless software-rendered AVD is not a reliable source): p95 ______ ms; empty-canvas p95 ______ ms.
  Absolute bar: at most 24 ms. Record pass or fail; a failure is reported, and `CanvasPerfCheckpoint` (`pivots.md`, Task 6.2.9c) then decides narrowing.
- [ ] The owner restates the relative rule. `baselines.md` Task 0.1.7b measured the empty canvas as a bare clear (46x to 59x on the JVM), so "at most 1.5x the empty
  canvas" cannot be met by that definition; until restated, only the 24 ms ceiling discriminates. Restated rule: ______
- [ ] Sketchy draw-list build for 500 elements on the device, ratio to the Clean build: ______ (`pivots.md` Task 6.2.9a lists Android as "not measured"; over 2x narrows Android as it did Web)

### 3. Android pixel parity against the JVM golden (requirements metric 4; Spike 0.1.3e)

Spike 0.1.3e (`research/determinism-spike.md`) found Robolectric native graphics exact for shapes and strokes but 1.5% of pixels differing in the text region (metric 4 bar:
at most 0.5%, every differing pixel within 2 px of a stroke edge), from one run. Until this row is recorded the Android column of metric 4 reads "owner-checked, not recorded", never "CI-verified" for text.

- [ ] On the device, render the architecture-15 fixture and compare against the JVM golden (`kmp/src/commonTest/resources/sketch/golden/*`): differing pixels ______ %; all within 2 px of a stroke edge? yes / no
- [ ] Draw-list equality on the device (the exact claim): equal / differs: ______

### 4. Android storage (Task 1.4.3g)

- [ ] A `saf://` graph's `readFilePrefix` (the `contentResolver.openInputStream(uri)` prefix read of Task 1.4.3g) returns at most 256 bytes on the device. Bytes returned for a sheet larger than 256 bytes: ______ ; pass or fail: ______
- [ ] SAF durability caveat, recorded with the owner's pass or fail: `supportsAtomicReplace` is false on SAF, so a sidecar write there is TEMP_RENAME with a delete-then-rename window
  (a crash between the two leaves the destination absent until the next write; `AtomicFileWriter` KDoc). Accepted / not accepted: ______ ; any absent file seen after a forced stop during a save: ______

### 5. Web browser pass (Task 1.4.7d)

One row per browser whose Spike 0.1.8 verdict is Available (Chrome, Firefox, Safari), plus the classic-only check for a browser whose verdict is Unavailable. Record the browser, its version and
its verdict, then each step as pass, fail or not run.

- [ ] Create, edit, save and reload a sketch: ______
- [ ] Import a `.excalidraw` file by the file picker, then by drop: ______
- [ ] Embed a sketch and copy a v2 sketch: ______
- [ ] Open a desktop-synced v2 sheet with the classic-file-side setting `Off` (found by the discovery scan): ______
- [ ] Open the same graph in two tabs; the second tab is refused at boot or opens read-only with "Edited in another tab.": ______
- [ ] Time the preload of a 500-sheet project: ______ s
- [ ] Repeat the classic steps once with the classic file side turned on (`localStorage.setItem('sketch.web.classicFileSide', 'On')`, then reload): ______
- [ ] A browser whose verdict is Unavailable shows the classic-only state; record whether it can still READ a v2 sheet (the claim of ux.md 14.7 that a sheet it cannot save it also cannot export is a limit of this release, not a measured fact, until this row exists): ______

### 6. Error states: the dead-end walk (UX-75; Task 6.2.7b)

`DeadEndWalkTest` walks every `SketchErrorState` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/sketch/v2/runtime/telemetry/SketchErrorState.kt`) in a fake host and `ErrorLogCoverageTest` checks one
`sketch.*` line each. Both are model-level checks; the walk of the real screens is the owner's.

- [ ] Walk ux.md sections 1 to 16 on one device. For every error or blocked state reached (load corrupt, sheet busy, newer schema, save failed, save conflict, low memory, import and export
  failures, conversion failure, constraint conflict and no-convergence, constraint and element caps, locked layer, copy refused), an action or a dismissal returns to a usable editor
  (no modal left, tools enabled or the read-only banner present). States not reachable on this device: ______ ; dead ends found: ______
- [ ] Save failed: leaving the sketch offers Retry (focused first), Export copy and Discard, and in-memory work is kept on every other path: ______

### 7. Shortcuts footnote (U8, U11, U12; keymap freeze, Task 3.6.1c)

- [ ] If the owner answered "use defaults" for U8 (shortcut and tool-lock behaviour against the live Excalidraw dialog), U11 (screen-reader quick keys) and U12 (stylus eraser end and barrel),
  the shortcuts sheet footnote still reads "not verified against the live Excalidraw dialog or your screen reader". Re-check each against the live dialog, the owner's reader and the stylus, then record: U8 ______ ; U11 ______ ; U12 ______

### 8. Usability sessions (Story 6.2.8; protocol in `usability-protocol.md`)

- [ ] Schedule the think-aloud sessions (2 to 4 people the owner knows plus the owner, one on a phone without a stylus). NOT RUN: `usability-results.md` is a blank
  template and every usability claim stays UNMEASURED until the owner fills it in. Date booked: ______

### Record

- Date of the pass, device model, app build: ______
- Release this pass gates (Task 6.2.5c): ______
