# Implementation plan: measurement accuracy, camera profiles, test strategy

Sizes: XS under a few files, S one module, M multi-module with tests, L cross-platform or new subsystem. Cost is stated as size class only (agent work, no human-time estimates). Wall-clock blockers named per epic.

## 1. Dependencies on in-flight work (state at 2026-11-11, from `gh pr list`)

| PR | Branch | Needed by | Note |
|---|---|---|---|
| #637 | fix/library-existing-images | Epic 7 Library tests | Library lists legacy images in release builds |
| #638 | feat/annotate-lens-distortion | Epics 2-5 | basis v2, multi-tick, division k, `Calibration.lens`, simulation tests |
| #639 | feat/annotate-existing-image | Epic 7 flows, Epic 2 (no sensor data for existing images) | open from viewer, inline block, Library |
| zoom/pan PR | not found in open PR list | Epic 7 | number/state UNKNOWN; confirm with owner. Existing coordinate tests: `commonTest/.../AnnotationCoordinateTransformTest.kt` |

Rule: Epics 2+ build on #638 merged (basis 2). If #638 is not merged, rebase the stories onto its branch; do not duplicate lens code.

## 2. Epics and stories

### Epic 0: quick wins with no code (owner, today)
See implementation/qa-protocol.md section 1. Blocker: owner time with the phone.

### Epic 1: measurement test harness and capability probe (do first)
- 1.1 (S) Golden scene generator in `commonTest`: given a pinhole camera (f_px, size), optional division k, plane pose (tilt), objects at heights, produces ground-truth pixel coordinates of known points. Pure math, no image rendering.
- 1.2 (M) Golden tests T-G1..T-G8 (section 4) asserting the app's measurement pipeline output vs truth.
- 1.3 (S) Device capability probe, debug-only screen or log: reports Camera2 `LENS_INTRINSIC_CALIBRATION` / `LENS_DISTORTION` / `DISTORTION_CORRECTION_AVAILABLE_MODES` / `REQUEST_AVAILABLE_CAPABILITIES` (depth output) per camera id, plus EXIF dump of a picked file (including whether Dynamic Depth XMP exists). Output written via `Logger` to the persisted log (the owner cannot run logcat, CLAUDE.md). Answers the UNVERIFIED reach questions on the owner's phone.
- 1.4 (XS) Extract the tilt predicate from `AnnotationEditorScreen` into a commonMain function used by UI and by `GpsTaggingTest`.

### Epic 2: guidance and guardrails (v1a)
- 2.1 (S) Reference-line quality: warn when reference < 15% of image long side ("short reference: +-1 px is N%"), show N from pixels; nudge to add ticks when < 3.
- 2.2 (S) Accuracy panel: shows active corrections and uncertainty (ADR-002 formula) next to each measurement.
- 2.3 (XS) Shooting guidance text from #638 `LensMeasurement.SHOOTING_GUIDANCE` extended with the protocol (same plane, top-down, central object, long ruler).
- 2.4 (S) Tilt tiers (ADR-002); "tilt unknown" state for gallery images.

### Epic 3: height offset (v1b)
- 3.1 (M) `HeightOffset` model: D estimation from f_px and reference size (EXIF focal, 35 mm-equivalent via diagonal, or manual camera height); factor (D - h)/D; clamps (h < D/2).
- 3.2 (M) Per-annotation or per-measurement h input with presets (card 0.76 mm, "on top of ruler").
- 3.3 (S) Persist in `Calibration.geometry` (additive JSON, sidecar key, migration entry) and show in accuracy panel.

### Epic 4: camera profiles (v1d, ADR-001)
- 4.1 (S) Profile data classes, JSON parser with strict validation, version field.
- 4.2 (S) Matcher with the ordering in ADR-001 + property tests.
- 4.3 (S) Device-local storage (settings key) + import/export of a `.json` file; snapshot into `Calibration.lens`.
- 4.4 (S) Self-check against #638 residuals (drop profile if it worsens residual by > 20%).
- 4.5 (M) Offline calibration script (`uvx` + OpenCV) that outputs the profile JSON from 10 to 20 checkerboard JPEGs; documented in the QA doc. Blocker: owner shoots the board.
- 4.6 (S) Persist EXIF subset at import (LensModel, DigitalZoomRatio, pixel dimensions) into `ImageSensorData` (additive; today only focal, make, model).
- 4.7 (M) In-app calibration flow using Epic 5's homography on a printed board (later).

### Epic 5: plane homography (v1c)
- 5.1 (M) DLT homography with normalisation, >= 4 points least squares, reprojection residual; pure commonMain.
- 5.2 (M) Reference presets: A4, US Letter, ID-1 card (85.60 x 53.98 mm, VERIFIED), custom rectangle; UI to tap 4 corners in order with zoom/pan (needs the zoom PR).
- 5.3 (M) Pipeline integration in the ADR-002 order; `Calibration.geometry.plane`.
- 5.4 (S) Fifth-point verification ("tap a known edge") and residual display.
- 5.5 (S) Basis/migration decision: additive field, no basis bump unless an existing field changes meaning; add migration test.

### Epic 6: depth scaffolding clean-up and v2 (ADR-003)
- 6.1 (S) Fix or hide FOV-less maths in `computeFromDepthFrame`, `computeFromMLDepth`, `ExifCalibrationService`; add tests with real pinhole numbers. Replace the "coming in a future update" banner copy.
- 6.2 (S) Dynamic Depth XMP read-only probe (reuse the 1.3 EXIF dump).
- 6.3 (L) v2 depth-assist capture (ARCore Depth, Android, in-app only, gated by `isAvailable`), writes D estimate only. Blocker: a device on the supported list; owner's phone model (open question).
- 6.4 (L) iOS LiDAR equivalent (`sceneDepth`). Blocker: iOS target decision and device.
- 6.5 (XS) Decide the fate of `CalibrationFallbackChain` (rewire or delete) per ADR-003.

### Epic 7: feature test matrix (request A), see section 3.

## 3. Feature-by-feature test matrix

Legend: E = exists today on main (grep), G = gap, P = depends on in-flight PR. Placement follows CLAUDE.md: pure logic in commonTest, business logic in businessTest, Compose behaviour in androidUnitTest (Robolectric), jvmTest only for pixels, instrumented only for device integration.

| Feature | Behaviours to assert | Existing (E) | Gap and new test | Where |
|---|---|---|---|---|
| Library lists legacy images | pre-annotation assets show; release-build indexing | LibraryAnnotatedJoinTest, LibraryViewModelStateTest; #637 P | G: release-style indexing test with unindexed asset folder fixture | businessTest |
| Library indexing state | progress, empty, error, re-index | LibrarySessionTest, LargeLibraryBatchingTest | G: indexing-state UI strings per state | androidUnitTest |
| Search / filter / sort | combined filters, SQL parity | LibraryFilterSqlParityTest, LibraryScreenFilterTest, LibraryParityTest | G: property test: any filter combination equals in-memory reference | businessTest |
| Sketch filter | include/exclude sketches | LibrarySketchFilterTest | none | n/a |
| Open / annotate / delete | actions from card, delete removes sidecar and DB row | LibraryViewModelActionsTest | G: delete removes `.measure.json` and asset; undo | businessTest |
| Annotate existing image (viewer / inline / Library) | each entry opens editor with image, creates annotation once, no duplicates, sensor data absent -> "tilt unknown" | P (#639) | G: Robolectric flow per entry point | androidUnitTest (style of HomeSketchFlowEntryPointTest) |
| Zoom / pan, hit-testing, nudge under transform | tap at zoom z maps to same normalised point; nudge step in screen px stays constant; handles hit under pan | AnnotationCoordinateTransformTest (commonTest), InProgressAnnotationCanvasGestureTest | G: property test: screen -> image -> screen roundtrip for random z, pan; nudge invariance; P zoom/pan PR | commonTest + androidUnitTest |
| Calibration basic | reference line sets scale; undo | CalibrationBasisServiceTest, CalibrationSheetTest, CalibrationNudgeBannerTest | G: tolerance golden (T-G1) | commonTest |
| Calibration multi-tick | LSQ scale, residuals; P | #638 LensDistortionSimulationTest (P) | G: tick-order and degenerate ticks property tests | commonTest |
| Calibration lens | auto k acceptance rule, preset k | #638 (P) | G: no false k on noisy taps (property) | commonTest |
| Calibration profile | match / no match / stale / self-check | none | new ProfileMatcherTest, ProfileParseTest, ProfileSelfCheckTest | commonTest |
| Height offset | factor, clamps, persistence | none | HeightOffsetTest, persistence in migration test | commonTest + businessTest |
| Homography | exact on synthetic, noisy taps, collinear rejection | none | HomographyTest + property: H of random plane maps back within 1e-6 | commonTest |
| Measurement accuracy golden | table in section 4 | none on main | T-G1..T-G8 | commonTest |
| Persistence / migration / sidecar | legacy 0, native 1, lens 2 rows unchanged; geometry additive; old sidecars load; unknown keys ignored; round trip | CalibrationBasisMigrationTest; #638 CalibrationLensPersistenceTest (P) | G: golden fixtures of old sidecars; forward-compat (new sidecar read by old parser ignores key) | businessTest |
| Depth scaffolding | NoOp provider; FOV maths; banner copy | CalibrationFallbackChainTest | G: pinhole numbers for fixed `computeFrom*` | commonTest |
| Tilt warning | tiers; absent data -> unknown | GpsTaggingTest (private predicate copy) | G: test the extracted function (1.4) and the banner via Robolectric | commonTest + androidUnitTest |
| End-to-end flow | Library -> open -> calibrate 3 ticks -> measure -> kill -> reopen shows same value | none | `MeasureFlowEntryPointTest` using `WiredAppFixture` | androidUnitTest |
| Device / manual | knife-and-ruler protocol | none | qa-protocol.md | manual |

Hygiene gates to respect: new screens add names to `first-open-screens.txt` and `FirstOpenSmokeTest`; wired seam classes need `wiring-allowlist.txt` / `wiring-catalog.txt` entries (CLAUDE.md); a jvmTest Compose test needs `// jvmTest-reason:`; no `Thread.sleep`, `GlobalScope`, real-clock `delay`; after any `.sq` change regenerate SQLDelight sources and add `MigrationRunner.all` entries (CLAUDE.md). Wrap Gradle/Bazel runs in `timeout`, and run `scripts/jvm-display-check.sh` before treating a jvmTest UI failure as real.

## 4. Golden measurement scenes (synthetic ground truth)

All scenes: 4000x3000, f_px 3100 (assumed value for a typical main camera, INFERRED), ruler 30 cm with 1 cm ticks, object 18.000 cm. "Tolerance" is the pass criterion for the corrected pipeline; "uncorrected" records today's expected error as a documented baseline (values to be generated by the harness, not asserted from memory, except where #638 already published them).

| ID | Scene | Pipeline under test | Tolerance |
|---|---|---|---|
| T-G1 | no distortion, top-down, same plane | scalar scale | 0.01 cm |
| T-G2 | division k = -0.01 and -0.02, object at centre, ruler along bottom | #638 Auto k | 0.03 cm (sim baseline: 0.05/0.10 uncorrected) |
| T-G3 | object edge position, k = -0.01 | #638 Auto k | 0.05 cm (uncorrected -0.14, #638 sim) |
| T-G4 | object 1 cm above ruler, D = 60 cm, h entered | height offset | 0.03 cm (uncorrected +0.30, #638 sim) |
| T-G5 | same, h NOT entered | accuracy panel | uncertainty interval contains truth |
| T-G6 | 5 degree tilt, object 25 cm lateral | 4-point homography | 0.05 cm (uncorrected 0.66, #638 sim) |
| T-G7 | 60 px reference, +-1 px noise, 200 random trials | warning + interval | warning fires; 95% of trials within reported interval |
| T-G8 | combined: k = -0.01, 3 degree tilt, h = 0.5 cm, 1 px tap noise (property: 500 random scenes) | full ADR-002 order | 95th percentile error under 0.1 cm when all inputs provided |
Correctness criterion for the harness itself: with all corrections off the harness must reproduce the #638 published baseline numbers (guards against a wrong simulator).

## 5. Property tests (kotest-property in commonTest, per CLAUDE.md)

- Homography: for random planes/poses, project then recover points within 1e-6; collinear/degenerate input returns a typed failure.
- Lens: undistort(distort(p)) within 0.01 px for |k| <= 0.05.
- Matcher: never matches when any field mismatches; order of profiles does not change the result.
- Transform: screen/image roundtrip for random zoom and pan.
- Persistence: serialize/parse round trip for every `Calibration` variant including unknown future keys.

## 6. Ordering and critical path

1.1 -> 1.2 first (a failing baseline in the harness proves the simulator). Then in parallel: Epic 2 (needs 1.4), Epic 3, Epic 4. Epic 5 follows 1.1 and uses zoom/pan for tapping. Epic 6.1 and 6.5 anytime; 6.3/6.4 last and only after the owner's phone model and capability probe (1.3) justify them. Recommended first PR: 1.1, 1.2, 1.3, 1.4, 2.1, 2.4.

## 7. Risks

- #638 numbers are simulation, not field data; the QA protocol (qa-protocol.md) is the check against reality.
- Phone JPEG pipelines vary; profiles must come from saved JPEGs (ADR-001).
- If the knife is simply lying on the ruler, height may be a few mm and the whole issue is 0.1 to 0.3 cm: Epic 3 is the likely fix.
- Public repo: fixtures use synthetic data only.
