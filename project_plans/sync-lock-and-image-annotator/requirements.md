# Requirements: sync-lock-and-image-annotator

**Date**: 2026-11-14
**Type**: bug fix + feature addition (four related items, Android-primary)
**Complexity**: 4 — cross-cutting: git repo integrity (data-safety), annotator UX + interaction model, a calibration model, and the capture pipeline

Labels: VERIFIED = code/log/screenshot read in this pass; INFERRED = reasoned from verified facts; UNVERIFIED = must be checked in research.

## Problem Statement

Four problems the owner hit on their phone (a multi-lens Android device), in the order reported:

1. **Git sync leaves an `index.lock`.** The git panel shows "Stale git lock file — tap to retry" and the sync fails. The owner suspects pressing Sync while a sync is already running cancels the running one, and that the cancellation leaves the lock behind. They do NOT want a second press to cancel anything.
   - VERIFIED (App Logs screenshot, 2026-11-14 21:57 UTC): `sync/fetch start` at :51.78, `syncState -> Error(Stale git lock file found: .../.git/index.lock)` at :52.14 (0.36 s later), another `sync/fetch start` at :54.54. Slow operations in the same window: `batchDeleteBlocks` 7585 ms, `loadFullPage` 6781 ms, `parseAndSavePage` 4472 ms.
   - VERIFIED (code): `SyncRunGuard.cancelled()` only resets the UI state to Idle; it does not touch the repo. `AndroidGitRepository.removeStaleLockFile` is a user-initiated delete. Whether anything cancels a sync mid-git-operation, or whether two syncs overlap (foreground + WorkManager worker + scheduled tick), is UNVERIFIED.
2. **Annotator tool modes are awkward.** After tapping Select the owner cannot easily switch between modes. Wanted: tap a tool, drag once to make the measurement, then return to Select automatically so camera pan/zoom works again. Also, the owner cannot zoom out past fit, so points near the image edge are hard to place or drag; they want a canvas larger than the image.
   - VERIFIED (screenshot): toolbar = Select / Distance / Area / Angle / Label / Reference | Calibrate, undo/redo, unit chip, +/−/Fit buttons; the image exactly fills the viewport at Fit.
3. **Measurements cannot be edited, and calibration is one-dimensional.** Wanted: edit the dimension (value) of a specific point-to-point measurement, and calibrate the photo in multiple dimensions from several known distances to correct camera distortion.
   - VERIFIED (sample photo, 3000×4000): wide-angle phone shot with visible barrel distortion (door frame, floor lines). Screenshot shows the banner "Not calibrated".
   - VERIFIED (prior art): `project_plans/measurement-accuracy-and-camera-profiles` found lens distortion to be the SMALLEST of four large error terms (depth/plane mismatch, short reference lines, tilt dominate). `project_plans/annotation-lens-distortion/design.md` and `model/{LensCalibration,GeometryCalibration}.kt` already exist. Research must reconcile this request with that finding rather than ignore it.
4. **In-app camera has no lens switching.** The take-a-picture button opens a bare capture viewer. The phone has at least three rear cameras; the owner cannot choose between them or use any controls.
   - VERIFIED (code): CameraX is in use (`androidMain/.../CameraViewfinderDialog.android.kt`, `AndroidCameraProvider.kt`, `AndroidCameraPreviewBinder.kt`).

Also visible in the screenshot: a toast "Depth model ready — estimation coming in a future update" overlaps the top of the image (a layout bug; `annotator-unit-prefs-ux-fixes` already touched this text, so check what shipped).

## Baseline

- Sync: pressing Sync during a running sync either overlaps or cancels it (mechanism UNVERIFIED); the owner then has to tap "Stale git lock file — tap to retry". Each such failure delays pushing their notes.
- Annotator: each tool must be re-selected deliberately; to pan the photo the owner must find and tap Select; zoom-out stops at fit; a measurement's value cannot be edited after creation; calibration uses a single reference scale (UNVERIFIED how many dimensions the current model supports).
- Camera: the main lens only, no controls.

## Users / Consumers

- The owner, on an Android phone with ≥3 rear cameras (primary); desktop is a secondary target for annotator/sync code that lives in `commonMain`.
- **Usage scenario and frequency (INFERRED from the owner's reports; the owner confirms the numbers):** the owner photographs a room, fixture or object with the in-app camera while planning a build or fit-out, measures roughly 5 to 15 segments per photo (door width, shelf depth, a clearance), in several sessions a week while a project is active; notes sync several times a day (scheduled tick plus manual presses, so overlap is a daily event, not a rarity). Errors that matter: a few percent on a door-sized distance changes whether a part fits; a lost or locked sync delays pushing notes the owner reads on another device.
- `GitSyncService`, `GitSyncTask`/`WorkManagerSyncScheduler`, `PeriodicSyncDriver`, `ScheduledSyncRunner` (sync entry points).
- Sidecar/JSON persistence of annotations (`ImageSidecarSchema`, `ImageAnnotation`) if the calibration model changes.

## Success Metrics

1. **Sync:** with a sync running, a second Sync press (UI, scheduled tick, or background worker) produces ZERO new `index.lock` files and does not cancel or restart the running sync; a regression test reproduces the overlap and fails on today's code. Across a forced-cancel and forced-crash test matrix, no run leaves a lock that the next sync cannot clear safely on its own. Baseline: the stale-lock error on overlapping presses (owner-reported, 1 verified log instance).
2. **Modes (SM2):** from a fresh editor, one tool tap + one drag creates a measurement and the editor is back in Select with pan/zoom working, with no extra taps (default). **Zoom-out, quantified:** at the zoom floor (0.25 of Fit) the whole image is visible and its rendered width is at most 25% of the viewport width, so its area is at most 6.25% of the viewport area (0.25 squared; Fit area never exceeds the viewport area); and a point can be placed on the outermost pixel row and column, meaning the stored normalised coordinate is exactly 0.0 or 1.0 on that axis, both at Fit (canvas laid out inside the system-gesture inset, ADR-006 decision 6) and at the floor. Baseline: the floor equals Fit today, so the 3000x4000 sample photo fills 100% of the viewport width (about 72% of the area in a 1080x2000 viewport); measured by Story 2.4's transform tests, owner-confirmed in Task 2.7a.
3. **Measurements, split so each half has a done-state:**
   - **SM3a (value editing, CI-closable):** the owner can change the value of any existing point-to-point measurement (as an override or as an explicit known distance), the edit persists and round-trips through DB and sidecar, and one undo restores it. Closed by CI tests (U-E3-16..20); no owner data needed. Epic 3 may be labelled "CI-green, SM3a closed, SM3b open" before the study.
   - **SM3b (calibration accuracy, owner-study-gated):** with at least N known distances in different places and directions (N is **provisional per model tier** (plan: SCALE with leave-one-out N >= 3, tilt tier N >= 5) because research did not set it; it is finalised only by the owner-run real-photo study, Story 3.8a), the default tier's held-out mean absolute error on the verification photo set (the owner's door-frame photo plus at least four more, six tape-measured distances each) is strictly lower than the single-scale calibration it replaces **and** within an absolute tolerance. **PROPOSED default tolerance, for the owner to confirm: at most 2% of the true length (0.36 cm on an 18 cm object), with a floor of 0.5 cm for objects under 25 cm where tape-reading error dominates.** Baseline: the single-scale error on the same set, which is UNVERIFIED until the Story 3.8a study measures it (the synthetic harness in Story 3.1 reproduces a baseline only on synthetic scenes). SM3b cannot be accepted before Story 3.8a; if the study does not meet it, the recorded outcome is "not met for distortion" and is shown to the owner (never blank).
4. **Camera (SM4):** the capture screen lists every rear lens the device exposes, lets the owner switch among them, and the saved photo records which lens/focal length took it. Baseline: main lens only. **Lens switch, quantified and measured:** time from the chip tap to the first preview frame of the new lens (the `camera bind lens=<id> result=ok ms=<n>` log line, taken from the CameraX preview stream reaching STREAMING) has a median of at most 1,000 ms and a worst case of at most 2,000 ms over 10 switches on the owner's phone; these thresholds are PROPOSED, for the owner to confirm. **Fallback acceptance (item 4 is still done):** if the phone exposes ultra-wide and tele only behind one logical camera, SM4 is met by zoom-ratio chips (for example 0.6x / 1x / 2x / 5x) read from the device's zoom range, each stop labelled optical or "Digital" and the lens that took the photo recorded as the logical camera plus zoom ratio (`source = SELECTED_LENS`); the UI says "Ultra-wide isn't available as a separate lens on this device" instead of showing an invented chip. Owner-verified by the Story 4.1 probe and the Task 4.7b checklist.

## Size

Large (3–15M CU) — INFERRED: four items, two of them (calibration model, multi-lens capture) need real research and on-device behavior that CI and the AVD cannot reproduce. Full scope is pursued; this is informational, not a gate.

**Wall-clock blockers**: (a) owner device runs for multi-lens capture and for judging calibration accuracy on real photos (the Pixel_Fold AVD has no real multi-camera hardware, UNVERIFIED), owner: Tyler; (b) CI wall time (~40 min per push observed on PR #676); (c) release cadence if the owner wants a build to test on the phone (release-please PR + ~30 min publish).

## Priority and ordering rationale

1. **Sync first (data safety).** A live `index.lock` deleted or a sync cancelled mid-merge can corrupt the notes repository; it is the only item where a defect loses user data, and it ships CI-green before the others.
2. **Annotator modes second.** It removes daily friction (an extra tap per measurement, no edge reach) with the smallest design risk and no schema change, and it creates the larger canvas and sheets that items 3 and 4 reuse.
3. **Measurements third.** Value editing (SM3a) is cheap and independent; the multi-distance calibration (SM3b) needs the owner's real photos, so it starts early (harness, solver) but can only close after the owner study.
4. **Camera fourth.** It has the most unverified, device-only behaviour (OEM lens exposure), so its probe is built in the first wave for the owner to run while the other items proceed; its lens record is an optional input to item 3 (Task 3.9), never a prerequisite, which is why calibration does not wait for the camera.

## Constraints

- Follow `CLAUDE.md`: Arrow `Either` at repository/service boundaries; writes through `DatabaseWriteActor`; no `rememberCoroutineScope` escaping into remembered classes; any new `CREATE TABLE` must also be in `MigrationRunner.all`; regenerate and commit SQLDelight sources if a `.sq` changes; new tests registered in `AllBusinessTests`/`AllJvmTests`/`AllAndroidUnitTests` and `commonTest` Bazel entries.
- The owner cannot run `adb logcat`; diagnosis must rely on persisted logs (`filesDir/logs`, `PreviousSessionLog`).
- Sync fixes must not weaken the data-safety work in PR #676 (unapplied-disk-content guard) or #662 (never stuck running).
- Open PRs as drafts; do not merge or release without the owner's say-so.

## Non-functional Requirements

- **Performance SLO**: pan/zoom/drag stays ≥ 60 fps-equivalent on the owner's phone on a 12 MP photo at the new, larger zoom-out range. **Definition and method:** a scripted pinch from Fit to 0.25 and back on a 12 MP image, measured with the existing jank harness (`kmp/TESTING_README.md`: JankStats / `scripts/gfxinfo-session.sh`) as p95 frame time at most 16.7 ms and jank rate (frames over 32 ms, the harness's JANK threshold) under 5% (the harness's "normal" band). **The baseline is UNVERIFIED until Task 2.7a captures the pre-change run (before Story 2.4 lands) on the same device and script**; the "after" run must stay in the same band as that baseline, and the absolute thresholds above are PROPOSED for the owner to confirm. Camera lens switch: see SM4 (median at most 1,000 ms, worst case at most 2,000 ms, PROPOSED; baseline for "today" is UNVERIFIED because today there is no switch).
- **Scalability**: not applicable beyond the existing 8 000-page graph scale for sync.
- **Security classification**: confidential (personal notes and photos on-device; no new network egress).
- **Data residency**: no special requirements.

## Scope

### In Scope
- Single-flight git sync (coalesce/ignore duplicate triggers instead of cancelling), safe own-lock handling on cancel/failure, and a test matrix for overlap, cancel and crash.
- Annotator interaction model: auto-return to Select after a measurement, a way to keep a tool active (lock), clearer mode switching, larger-than-image canvas and wider zoom-out range, fix the toast overlap.
- Editing a measurement's value; multi-distance, multi-dimension calibration to correct distortion; persistence of the calibration with the annotation.
- Camera capture screen: enumerate and switch rear lenses, basic controls (flash, zoom ratio, lens label), record lens/focal length in the photo's metadata/annotation for calibration use.

### Out of Scope
- Monocular depth "estimation" (the "coming in a future update" feature) beyond fixing the misleading toast.
- iOS/Web camera work.
- Changing git transport, auth, or LFS behavior.
- Per-camera factory distortion profile databases (unless research shows they are the cheapest route to item 3).

## Rabbit Holes

- Calibration math: per-axis scale vs. homography (4-point) vs. radial lens model are different products; users supply a handful of distances on a phone. Pick the smallest model that measurably wins (see prior art's error ranking) and de-risk with real photos before building UI.
- CameraX multi-camera: logical multi-camera vs. physical camera IDs differ by OEM; some phones hide ultra-wide/tele behind a single logical camera. Lens availability and zoom ratios are OEM-dependent.
- `index.lock` handling: deleting a lock that belongs to a live process corrupts the index. "Stale" must be decided by ownership/liveness, never by age or presence alone.
- Auto-return-to-Select changes gesture routing (`AnnotationGestureLayer`, `AnnotationNonDragMode`); easy to regress existing hit-testing and undo/redo.
- Existing annotation sidecars must keep loading after any calibration schema change (back/forward compatibility, `ImageSidecarSchema`).

## Alternatives Considered

- Sync: a process-wide mutex (`NamedLocks`) held by ALL entry points vs. a repo-level lock-file protocol vs. serializing in `GitSyncService` only (does not cover the WorkManager worker).
- Calibration: use per-device lens profiles (Camera2 `LENS_DISTORTION` / intrinsics, where exposed) vs. user-supplied known distances vs. both.
- Modes: sticky tools with a "done" button vs. one-shot tools with a lock gesture.

## Feasibility Risks

- Camera2/CameraX may not expose all three lenses or lens distortion coefficients on this phone (UNVERIFIED).
- A real multi-lens capture flow cannot be fully verified on the AVD; owner device runs are required.
- The root cause of the overlap (UI double-press, scheduled tick during manual sync, or WorkManager worker) is not yet proven; the 0.36 s gap in the log suggests the error came from a prior or concurrent operation rather than this sync's own git call.

## Observability Requirements

Log (via `Logger`, persisted sink) every sync trigger with source (manual / scheduled / worker), the decision (started / coalesced / skipped), and every lock create/clear with owner id and reason; record a "joined running sync" as a distinct log line (`decision=coalesced`) plus a one-shot `AlreadySyncing` UI event, with **no `SyncState` change** (clarified in the Phase 4 patch to match ADR-001 decisions 5 and 6: a new `SyncState` value would break every exhaustive consumer, and a coalesced trigger must not overwrite the running sync's own state or hide a prior failure). Calibration: log model chosen, number of reference distances, and residual error when saved. No new alerting infrastructure; the owner reads the in-app Logs screen and exports.

## Risk Control

- Sync: no feature flag; guard with a test matrix; the change must fail closed (when unsure whether a lock is live, report "another sync is running", do not delete it).
- Annotator/camera: ship behind a per-feature Settings toggle only where behavior changes default flows the owner may dislike (auto-return-to-Select default on; sticky-tool lock opt-in); calibration changes are additive to the sidecar schema with a version field.
- Rollback: each item lands in its own commits/PR so any one can be reverted independently; previous release (v0.113.3) remains installable.

## Open Questions

1. Which entry points can start a sync concurrently today (UI button, `PeriodicSyncDriver`, `ScheduledSyncRunner`, `GitSyncWorker`, rate-limit retry), and which of them cancels another?
2. What does JGit do when its operation coroutine is cancelled mid-`add`/`commit`/`merge`: is the lock released in `finally`, or does cancellation of a blocking call skip it?
3. What does the existing calibration model already support (multiple references? per-axis?), and what does `annotation-lens-distortion/design.md` + `measurement-accuracy-and-camera-profiles` already conclude about the best model for multi-distance calibration?
4. *(unresolved after Phase 2 research — needs an on-device probe; plan carries a one-time capability probe)* What do Camera2/CameraX expose on the owner's phone (physical camera IDs, focal lengths, distortion/intrinsics), and which CameraX API selects ultra-wide/tele reliably?
5. How many known distances can a person realistically supply, and which model gives a measurable error reduction with that many?
6. Does the AVD expose any multi-camera/emulated lenses for testing, or is on-device testing the only path?
