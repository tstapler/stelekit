# Research: Annotation feature audit (FEATURES dimension)

Scope: code-reading audit only (no gradle/emulator run). Paths are under `kmp/src/commonMain/kotlin/dev/stapler/stelekit/` unless noted; `VM` = `ui/annotate/AnnotationEditorViewModel.kt`, `Screen` = `ui/annotate/AnnotationEditorScreen.kt`.
Labels: VERIFIED = read in source (not executed); INFERRED = needs a run/log to confirm.

## 1. How the feature is wired (as implemented)

- Entry points: camera capture -> `GraphContentCameraCapture.kt:439` `navigateToAnnotationEditor`; Gallery tap `ScreenRouter.kt:353`; page image block `PageView.kt:546`; Assets detail "Annotate" `ScreenRouter.kt:401-407` -> `AssetDetailViewModel.resolveOrCreateAnnotation` (`ui/assets/AssetDetailViewModel.kt:137`).
- `ScreenRouter.kt:413-422`: VM is `remember(imageAnnotationUuid)`; `DisposableEffect` calls `close()`. VM is built with NO `onHapticFeedback`, no `CoroutineExceptionHandler`.
- State: single `MutableStateFlow<AnnotationEditorState>` (VM:80-110, :232). Points are normalized [0,1]; pixel distance = normalized delta x `imageWidthPx` (VM:772-775) where `imageWidthPx` comes from the decoded Coil painter (`Screen:436-441`).
- Persistence: optimistic local update then `scope.launch { saveMeasurementAnnotation }` per commit (VM:574); whole-set rewrite (`saveMeasurements`: delete-all + insert-all, `SqlDelightMeasurementAnnotationRepository.kt:63-80`) for undo/redo/calibration.
- Loupe: `LoupeOverlay` (Screen:1610-1684) composes a second Coil `AsyncImage` with a request sized by `loupeDecodeSize` (canvas x3, longest edge capped 2048, `platform/image/ImageSampling.kt:46`), shown while a finger is down.
- Export: `AnnotationExporter.bakeAnnotations` (full-size `ImageBitmap`) via `AndroidAnnotatedImageRenderer` (decode capped at 4096 px edge) -> Drive only (`GraphContentGoogleScreens.kt:70`). There is no "save/share annotated JPEG" path in the editor itself.

## 2. Industry comparison and edge cases peers handle (from background knowledge, NOT web-verified: INFERRED)

Apple Measure / Google Measure (AR, live camera), Moasure/Magicplan (room scanning, project files), photo-measure and markup apps (Apple Markup, Google Photos edit, Skitch, Annotable, Photo Measures, Tape Measure). Common patterns:

| Concern | What mature apps do | SteleKit |
|---|---|---|
| Precision aiming | Offset loupe + fine-adjust nudge, re-grab and move placed points, snap to edge/endpoint | Loupe + release-to-place; select/move exists; no nudge/snap |
| Undo | Per-action command log, unlimited or deep; undo never changes tool/calibration | 50 whole-state snapshots, undo restores tool/calibration/tags too (see C3, M1) |
| Large photos | Tile/subsample for view; keep original on disk; export from original at full-res | View is Coil-subsampled; calibration depends on the decoded size (C1) |
| Rotation/EXIF | Normalize orientation at import and measure in oriented space | View honors EXIF via Coil; export path does not (M5) |
| Process death / backgrounding | Autosave on every edit; restore in-progress state | Committed items persisted per edit; in-progress points, tool, selection lost; "Unsaved changes" dialog is misleading (M2) |
| Precision/units honesty | Show uncertainty; lock calibration; warn on scale change | Calibration badge, scale-change warning, single-level calibration undo (good) |
| Handedness / occlusion | Loupe offset away from finger, flips side near edges, user setting | Loupe fixed directly above finger; below at top; no left/right handling (M6) |
| Many annotations | Layer list, hide/show, label collision avoidance, selection by list | O(n^2) label collision in canvas, no list/layer UI, no cap |
| Accessibility | Screen-reader alternative to canvas taps, large targets, haptic confirmation | Canvas has one contentDescription; semantic overlay exists but unused; haptic not wired (M3, M4) |

Unstated user needs: trust that a number is correct (scale consistent across devices/orientations), that work is never lost, ability to correct a mis-placed point without redoing, export that visually contains the numbers, fast one-handed use, and keeping the original photo untouched.

## 3. Findings (ranked)

### BLOCKER
None confirmed from code alone. The reported crash is unconfirmed; see section 4. If candidate K1 or K2 is confirmed it is a BLOCKER.

### CRITICAL

**C1. Measurement scale depends on the Coil decode size, not the file's native size (INFERRED, high plausibility).**
`Screen:430-441` passes `success.painter.intrinsicSize` to `viewModel.setImageSize`. The `AsyncImage` has no fixed `size`, so Coil decodes toward the layout size (power-of-two subsampling), so `intrinsicSize` is the decoded bitmap, which varies with window/screen/fold state/rotation. `pixelsPerMeter` is stored as an absolute pixel value (`confirmGridRefCalibration` VM:455-482 uses `imageWidthPx`), then reused in `computeMeasurement` VM:764-790 on later sessions. A calibration made at one decode size and read at another (e.g. Pixel Fold folded vs unfolded, portrait vs landscape, desktop vs phone) scales every distance/area wrongly. Confirm: log `intrinsicSize` for the same photo in two window sizes; or unit-test `computeMeasurement` with differing `setImageSize` values. Fix direction: read native dimensions from file bounds (not the painter) or store calibration as normalized px/m relative to image width.

**C2. Loupe crashes on very wide or very tall images (VERIFIED by reading).**
`Screen:1626-1628`: `coerceIn(marginPx, canvasSize.height - loupeSizePx - marginPx)` and the same for width. When the image-aligned box is smaller than 120dp + 2x16dp = 152dp in either dimension (panorama 4:1 on a 360dp-wide phone is ~90dp tall; a tall receipt/screenshot is the mirror case), min > max and Kotlin `coerceIn` throws `IllegalArgumentException`. It happens in composition on the first touch down, on the main thread, so it kills the process on Android. Not the reported "several points" crash but a real one. Confirm: Compose test with a 4000x600 image and a touch.

**C3. Undo restores the whole editor state, producing ghost in-progress points (VERIFIED by reading).**
`addPoint` writes `inProgressPoints = newPoints` (VM:398) before `commitAnnotation` reads `val st = _state.value` and does `pushUndo(st)` (VM:557-558). The snapshot therefore contains the just-placed points as in-progress. `undo()` (VM:298-305) restores a state with a committed-annotation removed but 2 (DISTANCE) in-progress points re-appearing; the next tap makes 3 points >= required -> commits a 3-point DISTANCE. No test asserts `inProgressPoints` after undo (`AnnotationEditorViewModelTest.kt:164-176`, `...BranchTest.kt:395-404`).

**C4. No `CoroutineExceptionHandler` on the VM scope, contrary to CLAUDE.md (VERIFIED).**
VM:203 `CoroutineScope(SupervisorJob() + Dispatchers.Default)`; `DepthEstimationCoordinator` same (no handler by grep). Repository code catches `Exception` only (`SqlDelight...Repository.kt:48-60`), so an `OutOfMemoryError`/other `Error` in any `scope.launch` (save, `persistCommittedSet`, depth collector) reaches the default uncaught handler and kills the process on Android. This converts any memory pressure into "app closed". See K3.

### MAJOR

**M1. Save/undo races and data-loss paths (VERIFIED by reading, behaviour INFERRED).**
- `commitAnnotation` save (VM:574), `deleteAnnotation` (VM:749), `updateCalibration` (VM:629), `undoCalibration` (VM:651) run OUTSIDE `persistLock`; only `commitAnnotationEdit`/`persistCommittedSet` take it (VM:704-727). A late `saveMeasurementAnnotation` can land after an undo's delete-all+insert-all that excluded it, resurrecting the row in the DB but not the UI.
- `undoCalibration` restores `snapshot.annotations` captured at calibration time (VM:643-657): any annotation added after the scale change but before tapping the snackbar's Undo is dropped from state and the DB.
- Initial load: if `hasBeenMutated` is true when the load returns, persisted annotations are never merged (VM:340-352); the next `persistCommittedSet`/`updateCalibration` then deletes them all from the DB. A failed load (`Either.Left`) leaves state empty with the same consequence. Window is small but real on a slow DB.
- Save failure rolls back state but leaves the annotation in older undo snapshots.
- Undo restores `calibration`, `imageAnnotation` (tags), `currentTool`, `selectedAnnotationUuid` and `depthMap`, because they live in the snapshot; tag changes were persisted but the UI can show the old tags (VM:826-870).

**M2. "Unsaved changes" dialog is misleading (VERIFIED).** `Screen:727-739`: "Save" has `// TODO: trigger explicit save`; "Discard" navigates back but every commit is already persisted, so nothing is discarded. `hasUnsavedChanges` is set true when any annotation exists (Screen:240) and never reset except via the dialog. Users who press Discard believe they undid work.

**M3. Accessibility overlay is dead code (VERIFIED by grep).** `AnnotationSemanticNode.kt` is referenced nowhere outside its own file; the canvas exposes one `contentDescription` ("Annotation canvas - tap to place points", Screen:~1000). Screen-reader users cannot enumerate, select or read annotations, and cannot place points at all (touch-only `awaitEachGesture`). No live-region announcement on commit.

**M4. Haptic feedback never wired (VERIFIED).** `onHapticFeedback` defaults to a no-op and `ScreenRouter.kt:413-418` does not pass one; the Story 9.6 commit confirmation does not exist on-device.

**M5. Export correctness (VERIFIED code, device effect INFERRED).**
- `AnnotationExporter.drawLabel` (`ui/annotate/AnnotationExporter.kt`, ~line 120-135) draws only the dark pill background, never the text. The exported/Drive image has empty label boxes.
- Stroke 3f, dot radius 5f, pill sizes fixed in pixels irrespective of image resolution, so on a 4096-px bake lines are hairlines.
- `AndroidAnnotatedImageRenderer.decodeBounded` uses `BitmapFactory.decodeByteArray` without applying EXIF orientation, while on-screen coordinates are in Coil's EXIF-corrected space: portrait photos stored with orientation 6/8 would bake annotations rotated/misplaced (INFERRED: depends on whether import normalizes; `PlatformImageDownscaler` only rotates when it downsamples, `PlatformImageDownscaler.android.kt:23-26`).
- `ImageEncoder.android` catches `Exception` only (OOM propagates; outer renderer does catch `Throwable`).
- Bake allocates a full-size `ImageBitmap` plus the source (about 100 MB at 4096x3072).

**M6. Loupe behaviour at edges and under the finger (VERIFIED by reading, UX INFERRED).** (Screen:1610-1640)
- Horizontal/vertical clamp keeps the loupe inside the image box, good, but see C2 for the degenerate case.
- Near the image's left/right/top edge the inner zoomed image is offset out of range, so part of the circle shows no image (no background fill), and the crosshair still tracks the touch correctly.
- Loupe sits `gapPx` = 16 px (about 5 dp) above the finger, or the same below when within 140 dp of the top; the fingertip/hand occludes it in the below case. No handedness setting or horizontal flip.
- The loupe's AsyncImage has no placeholder, so on first press (cold cache) the circle is blank until the decode lands.

**M7. Repository writes bypass `DatabaseWriteActor` (VERIFIED).** Measurements use `@DirectRepositoryWrite` (`SqlDelightMeasurementAnnotationRepository.kt:39,62`) from the VM; CLAUDE.md says writes route through the actor. Concurrent saves are serialized only by the VM-side lock, which many paths skip (M1).

**M8. `loupeOffset` is read at screen scope (Screen:199, :476).** Every pointer-move event recomposes the whole `AnnotationEditorScreen` body (toolbar, panels), not just the loupe. Allocation/jank under fast drags (INFERRED; confirm with composition-count tracing).

### MINOR

- m1. `CommittedAnnotationsCanvas.pathMap` (Screen:892) is never pruned for deleted/undone annotations (tiny leak).
- m2. `MeasurementLabelOverlay` does O(n^2) collision checks and `textMeasurer.measure` per label per draw (MeasurementLabelOverlay.kt:51-81); fine at tens of annotations, degrades at hundreds. No cap or virtualization.
- m3. `commitAnnotation` and `addPoint` read-then-write `_state.value` (VM:365-398, :524) rather than a single `update`; only main-thread today, but depth/initial-load/persist coroutines also write state from `Dispatchers.Default`, so `pushUndo(before)` can capture a stale base.
- m4. Main image decodes twice: first at `fillMaxSize`, then again after `imageAspect` changes the container to `aspectRatio` (different Coil cache key), Screen:421-440.
- m5. Orphan assets (no `pageUuids`) make `resolveOrCreateAnnotation` return null (`AssetDetailViewModel.kt:137-139`), so the "Annotate" button does nothing and shows no message (`ScreenRouter.kt:401-407`).
- m6. `DISTANCE` with 3+ in-progress points (via C3) uses only the first two in `computeMeasurement` and silently drops the rest.
- m7. Short-line guard (0.5% of width, VM:515-521) silently discards the placement with no feedback.
- m8. Process death: `remember(imageAnnotationUuid)` VM is not saved; in-progress points, tool and selection reset. Committed annotations survive.

## 4. Candidate causes of the "several points back to back" crash (code-reading, ranked)

Reported symptom: abrupt close, looks like OOM, after placing several points quickly on Android; desktop never reproduces. Each press = down (loupe appears) -> release (loupe disappears) -> commit every 2nd point (DISTANCE).

| # | Candidate | Likelihood | Evidence in code | What would confirm |
|---|---|---|---|---|
| K1 | A new Coil `ImageLoader` is built on every press: `LoupeOverlay` calls `rememberSteleKitImageLoader()` (Screen:1648) and the composable enters/leaves composition per press, so `remember(notesPath, fileSystem, context)` rebuilds `buildSteleKitImageLoader` each time (`ui/components/SteleKitAssetFetcher.kt:111-118`). The same file's comment (lines 68-75) says Coil keeps every built loader reachable via a Context ComponentCallbacks registration. Shared memory cache bounds bitmaps but not loader objects, their scopes/dispatchers/registered system callbacks. | High that it accumulates; Medium that it is lethal in "several" presses (more likely a slow leak plus K2) | Screen:1646-1648, SteleKitAssetFetcher.kt:68-118 | Count `ImageLoader` constructions per press in a test; heap dump retained size of `RealImageLoader`; `last-crash.txt` stack. |
| K2 | Per-press loupe decode competes with the main image in a cache sized 10% of memory class (`IMAGE_MEMORY_CACHE_PERCENT = 0.10`). Loupe bitmap up to 2048 px edge (about 10 MB at 1301x2048), main image several MB; on a small-heap device the loupe entry evicts the main (or itself), so each press triggers a fresh JPEG decode from file; rapid presses overlap decodes (transient native + Java allocations of many MB each). Hardware bitmaps move this to GPU/graphics memory, which matches an "OOM-like" kill without a Java OOM. | Medium-High | `ImageSampling.kt:14,46`; SteleKitAssetFetcher.kt:68-84; Screen:1631-1636 | `Debug.getMemoryInfo` graphics/native vs Java across presses (the untracked `kmp/src/androidInstrumentedTest/.../AnnotPressMemRepro.kt` already samples this: run `v1/v2/v3`); `PreviousSessionLog` exit reason (OOM vs LOW_MEMORY/lmkd). |
| K3 | A genuine `OutOfMemoryError` (from K1/K2 or an unrelated allocation) thrown inside an unguarded `scope.launch` (VM scope has no handler, C4) or in Compose's draw/measure; on Android the default handler kills the process. The OOM lands in whichever coroutine allocates next, so the stack may point at innocuous save code. | High as the amplifier (turns pressure into a kill), whatever the trigger | VM:203; repo catches `Exception` only | `last-crash.txt` / `AndroidCrashRecorder` stack; check whether it names `AnnotationEditorViewModel` coroutines or `RealImageLoader`. |
| K4 | Loupe `requiredSize` = 3x canvas in dp (about 3240x5100 px layout) inside a clipped 120 dp box (Screen:1631, 1653-1657): a giant layout rect draws a 10 MB bitmap scaled to it; with `clip(CircleShape)` plus hardware canvas this can allocate large GPU render targets/textures per press. | Medium-Low | Screen:1637-1657 | GPU/graphics PSS growth in the repro; try a loupe with `drawWithContent` crop instead of an oversized child. |
| K5 | Coil `NetworkObserver` / system callback registration per loader (relates to K1): if each loader registers a platform callback, hitting the OS per-app registration cap throws a `RuntimeException` that is not an OOM but also looks like an abrupt close after tens of presses. | Low-Medium (INFERRED from Coil internals not read here) | K1 | Exception type in `last-crash.txt` (e.g. "too many callbacks"). |
| K6 | `pushUndo` whole-state snapshots / `committedAnnotations + m` copies. Snapshots share annotation objects and lists are of small data; 50 x n references is tiny. `depthMap` (FloatArray) is shared by reference unless depth is re-run, which this path does not. | Low (rule out) | VM:289-296, :524-525 | Heap histogram showing `AnnotationEditorState`/`ArrayList` dominance (unlikely). |
| K7 | Uncaught exception other than OOM from C2 (`coerceIn` on a narrow image) or from a state write race. | Low for a standard 4:3 photo; High for panoramas | Screen:1626-1628 | Stack trace with `IllegalArgumentException`. |

Ground truth first: ask for `last-crash.txt` + the exit reason in the in-app Logs screen (`PreviousSessionLog`); that distinguishes K1/K2 (OOM/LOW_MEMORY) from K5/K7 (exception). The repro harness in `AnnotPressMemRepro.kt` (100 rapid presses on 4000x3000 and 8000x6000 images, samples Java/native/PSS/graphics) is the right base for the regression benchmark; note it is not in `git ls-files` and uses `InMemoryMeasurementAnnotationRepository` with a `file://` URI, so it exercises the loupe/Coil path but not the real SQLDelight writes.

Cheapest mitigations to test (hypotheses, not verified): hoist the `ImageLoader` out of the loupe (use the one the screen already holds), keep a single loupe request alive for the whole editor session (decode once, crop in draw), add a `CoroutineExceptionHandler` that reports to `fatalError`, and cap the loupe request size by memory class.

## 5. Test-coverage gaps (behaviours with no automated test found)

Existing: `AnnotationEditorViewModelTest/BranchTest` (commit, undo bound at 50, calibration, select/move), `AnnotationHitTestTest`, `LoupeInnerOffsetTest`, `InProgressAnnotationCanvasGestureTest` (Robolectric), `AnnotationExporterTest` (jvm), `DepthEstimationCoordinatorTest`, keyboard/tag-wiring tests. Gaps:
1. Undo/redo and in-progress/tool/tag/calibration semantics (C3, M1): no assertion on `inProgressPoints` after undo; no undo-after-calibration or undo-after-tag test.
2. Concurrency: no test racing commit save vs `persistCommittedSet`, initial-load vs first mutation, or failed initial load followed by a write (data loss).
3. Loupe layout: no test for loupe on narrow/wide canvases (C2), edge clamping, or placement vs fingertip; `LoupeAlignmentE2ETest` lives only in PR #560 and is not run in CI.
4. Memory/stress: no automated "N rapid presses" test in CI; `AnnotPressMemRepro` is untracked.
5. Calibration portability across decode sizes (C1).
6. Exporter output content: text label drawn, scale-with-resolution, EXIF orientation (M5); JVM test covers the mapping only.
7. Entry points: Assets "Annotate" for orphan assets (m5), camera-capture -> editor E2E.
8. Process death/backgrounding mid-gesture (pointer cancel path `finally` at Screen:1042-1046 untested), and an AREA close-tolerance property test.
9. Accessibility: no TalkBack/semantics test since the overlay is unused (M3).
10. Instrumented annotation tests do not run in required CI (requirements.md: Feasibility Risks).

## 6. Summary for planning

- Treat C4 + K1/K2 as the crash workstream: add the exception handler, remove per-press `ImageLoader` construction, reuse/cap the loupe decode, then lock it with a rapid-placement instrumented test built from `AnnotPressMemRepro`.
- Correctness items to fix in the same project (BLOCKER/CRITICAL per requirements success metric 5): C1 (scale vs decode size), C2 (loupe `coerceIn`), C3 (ghost in-progress after undo), M1 (persist races/data loss).
- File follow-ups for M2-M8 and the test gaps; none require schema changes.
