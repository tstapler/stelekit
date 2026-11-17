# Owner verification checklist (excalidraw sketch engine)

Each section is one release or device pass. An item is checked by the owner before the release-please PR is merged; nothing here is
checked by an agent. Unchecked means UNMEASURED.

## Reader-only release (Task 6.2.5e; depends on Stories 1.4.3, 1.4.5, 6.1.1 and 2.1.3 only, never on the A1 gate)

Needs only the router (1.4.3), the lease and refusal paths (1.4.5), the embed preview (6.1.1) and the painter (2.1.3), so it can ship
before the A1 gate (Task 6.2.4e), the solver, the importer or conversion are released. Every outcome table row of requirements.md
"Combined outcomes" except "stop entirely" and "stop at the pure engines" allows it.

### Release notes (paste into the release body)

> **Sketches: this version can read the new sketch format.** This build opens and shows sketches saved in the new editor's format
> (v2) in read-only mode. It never creates one and never converts an existing sketch. Older SteleKit versions cannot open a project
> that holds a new-format sketch: they refuse it instead of risking the file. Update SteleKit on **every device** that opens your
> sketch projects **before** you upgrade any sketch in a later release.

Claims, and the test or file that backs each:

| Claim | Evidence |
|---|---|
| Opens and shows v2 sheets read-only | `EditorRouterTest.v2_should_OpenReadOnlyWithTheReason_When_ModeIsOff` (`kmp/src/businessTest/kotlin/dev/stapler/stelekit/sketch/v2/EditorRouterTest.kt`) |
| Older builds fail closed on a project holding a v2 sheet | `LegacyStoreRefusesV2Test` (same directory); an older build's behaviour is only checked by the owner's device pass below |
| Never creates a v2 sheet | holds only while no creation path is reachable: see the first checklist item |

### Checklist

- [ ] The build being released cannot create or convert a v2 sheet. UNVERIFIED: at the time of writing `V2Build.CONVERSION_READY` and
  `V2Build.CONVERSION_OFFERED` are both `true` in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/V2Mode.kt`, so the mode
  picker can offer New projects only and On. The owner decides how the reader-only build closes that (build constants for this
  release, or the picker hidden) before this note is published; until then the note's "never creates" sentence is not true of this tree.
  After Story 1.4.6 the build constants ARE the one edit: every reader of the two flags (the router, the mode control, the
  flag-enable confirmation, the classic-notice policy and the read-only banner's "Use new") goes through `V2Capabilities.of`, which
  reads `V2Build`. Setting both to `false` disables both On radios with "Not available in this build yet" and removes "Use new"
  (the banner then says "This version of SteleKit can't edit new-editor sketches yet."); `ConversionReadyConsistencyTest` must be
  re-read at the same time, because it holds `CONVERSION_READY` honest against the registered test classes. The flip was NOT made in Story 1.4.6.
- [ ] The release notes above are in the release body, with the "update every device first" sentence unchanged.
- [ ] Owner device pass: a v2 sheet copied to a second device on the previous release is refused there with no data change; the same
  sheet on this build shows read-only with the reason text "Turn on the new editor to edit." (`SketchReasonText.READ_ONLY_TURN_ON`).
- [ ] No A1-gate, solver, importer or conversion text appears in these notes (script-checked edge: 6.2.5e has no path to 6.2.4e, 6.2.4f,
  6.2.5a or a parked node).

Later releases (Task 6.2.5c and 6.2.5d) add their own sections here; the Task 6.2.4c device passes follow.

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
