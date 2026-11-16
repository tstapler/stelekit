# Build vs Buy — sync-lock-and-image-annotator

Accessed 2026-11-14. VERIFIED = opened source/file/Maven metadata this pass; INFERRED = reasoned; UNVERIFIED = needs a check.

## Bottom line
- Sync single-flight: adopt `kotlinx.coroutines` Mutex via the existing in-repo `NamedLocks` (no new dependency). Add `tryLock` coalescing; do not build a lock-file protocol.
- Calibration math: extend what exists. Normalised DLT homography, a division-model radial fit and a tick scale fit are already in `commonMain`. Add a tiny Kotlin solver; do not adopt OpenCV/EJML.
- Camera and gestures: stay on CameraX 1.4.1 (already a dependency) plus Camera2Interop for lenses; keep the in-repo gesture layer, adding only a bigger canvas.
- SaaS: Not recommended.

## 0. In-repo inventory (VERIFIED, files opened)
| Asset | What it already does |
|---|---|
| `git/ColdSyncRunner.kt:24` `NamedLocks` | process-wide `Map<String, Mutex>` with `withLock(name)`. Suspends, so a second press waits instead of coalescing. No `tryLock`. |
| `calibration/Homography.kt` (164 lines) `HomographyFitter` | DLT with Hartley normalisation, least squares for >4 points, typed failures, collinearity check. Tested by `HomographyTest` (round trip to 1 micrometre on random planes via property test). |
| `calibration/MultiPointCalibration.kt`, `LensMode` (Off/Fixed/Profile/Auto) | least-squares scale through ruler ticks, per-tick residuals, estimates `k` from tick residuals. |
| `calibration/RadialLens.kt`, `LensMeasurement.kt` | one-parameter division model `p' = c + (p-c)/(1+k r^2)`, distance/area through homography. |
| `model/GeometryCalibration.kt`, `LensCalibration.kt` | additive JSON blocks with `ignoreUnknownKeys`; unreadable blocks degrade to no correction. |
| `calibration/CameraProfile*.kt`, `CalibrationFallbackChain.kt` | per-device profile store and fallback chain (ADR-001). |
| Android sensor code, CameraX 1.4.1 (core, camera2, lifecycle, view) in `kmp/build.gradle.kts:318-324` | capture + preview already wired. |

Consequence: "calibrate from several known distances in several directions" is mostly a new fitter over existing pieces. It is not a new subsystem. Prior art (ADR-004) already rejects lensfun, Camera2 distortion on JPEGs, and monocular depth.

## 1. Existing OSS library or framework

### 1a. Single-flight / locking
| Option | Pros | Cons | Verdict |
|---|---|---|---|
| In-repo `NamedLocks` (Mutex) + `tryLock` coalescing | Zero deps; commonMain; already used by `ColdSyncRunner`; one lock name per repo path covers UI, scheduled tick and worker if all take it | Process-local only: WorkManager worker in a separate process (if any) is not covered; needs a "joined running sync" outcome | **Recommended** |
| Raw `kotlinx.coroutines.sync.Mutex` per call site | Standard | Re-invents NamedLocks; per-site mutexes do not coordinate | Not recommended |
| Lock-file protocol (own `.lock` with owner id/liveness) | Cross-process safe | Liveness/ownership is hard on Android; requirement says deleting a live lock corrupts the index; complexity | Viable only as a second layer if a separate worker process is proven |
| JGit lock helper | JGit already creates `index.lock` | JGit has no public cross-caller lock coordination API (INFERRED); lock is released in its own finally, not under our control | Not recommended |
Check first: is the `GitSyncWorker` in the same process? (INFERRED yes, default WorkManager process; UNVERIFIED.)

### 1b. Calibration math
| Option | Pros | Cons | Verdict |
|---|---|---|---|
| OpenCV (`org.opencv:opencv` 5.1.0 on Maven Central, published 2026-11-10; VERIFIED metadata) | calibrateCamera, undistortPoints, findHomography, battle-tested | Android-only JNI (no commonMain; needs expect/actual, no desktop/iOS parity); native libs add several MB per ABI (INFERRED, ~10-25 MB; UNVERIFIED exact); the calibration needs points the app already has, not image processing; Apache-2.0 (INFERRED) fine | Not recommended for runtime. **Viable as an offline oracle** (see section 3) |
| EJML (`org.ejml:ejml-core` 0.46.1; VERIFIED metadata) | pure Java/Kotlin, SVD/LM-friendly, Apache-2.0 (INFERRED) | JVM-only artifact (not KMP); wasm/iOS fail without a seam; the 8x8 normal equations we need are already solved by the in-repo Gaussian solve | Not recommended |
| Apache Commons Math (`LevenbergMarquardtOptimizer`) | proven LM | JVM-only, large, last releases old (UNVERIFIED) | Not recommended |
| BoofCV | full calibration suite | JVM-only, big, overkill | Not recommended |
| Extend in-repo math (small LM/Gauss-Newton over 2-3 params) | commonMain; no APK growth; sits beside tested `HomographyFitter`; testable on all targets | Needs a new solver and tests (section 3) | **Recommended** |

### 1c. Camera
| Option | Pros | Cons | Verdict |
|---|---|---|---|
| CameraX 1.4.1 (already in use) + `Camera2Interop` / `CameraSelector` filtering | no migration; keeps preview + capture + lifecycle; physical-ID selection possible through Camera2Interop where the OEM exposes physical IDs (INFERRED; UNVERIFIED on this phone) | OEM may hide ultra-wide/tele behind one logical camera, where only zoom ratio switches lenses | **Recommended** |
| Camera2 directly | full control, physical streams | Re-implements preview/lifecycle/rotation; large regression risk | Not recommended (fallback for one device quirk only) |
| Third-party lens-switch helper lib | n/a | none found worth adopting; UNVERIFIED search depth | Not recommended |

### 1d. Gestures / zoom
| Option | Pros | Cons | Verdict |
|---|---|---|---|
| In-repo `AnnotationGestureLayer` (`awaitEachGesture`) + larger virtual canvas | owns hit-testing, undo, mode routing (the rabbit hole); changes are scale-limits and padding | needs care in gesture routing | **Recommended** |
| Telephoto `me.saket.telephoto:zoomable` (latest 1.0.0-alpha02, Maven metadata modified 2026-04-02; VERIFIED) | maintained, KMP-capable (INFERRED, UNVERIFIED) | still alpha; would have to be re-fitted around custom drag/point placement; two gesture owners fight | Viable, not worth it |
| Compose `transformable` | built-in | no fling/bounds; the app already has a transform | Not recommended (redundant) |

## 2. SaaS / managed API
Not recommended. No hosted calibration/measurement service found that fits (UNVERIFIED breadth of search; the inputs are tap coordinates, so a service gains nothing). Photos are personal and confidential per requirements (no new network egress); a service adds cost, lock-in and offline failure for no accuracy gain. The math is a few hundred lines.

## 3. LLM-written custom math vs library
Risk of bespoke code: sign/convention errors, ill-conditioning, local minima in LM, silent bad fits. Mitigation, all already partly in the repo:
- Property tests with known synthetic ground truth (already done for the homography: random planes recovered within 1 micrometre). Add: synthetic radial `k` plus noise, recover `k` within tolerance; scale/axis recovery from N distances at varied angles; idempotence under normalisation; typed failure on degenerate input (collinear, too few ticks, repeated points).
- Oracle: generate a fixture file offline with OpenCV (`uvx` script in `stapler-scripts/`, as research/camera-intrinsics-distortion.md section 6 already recommends) and check Kotlin output against it. OpenCV is the oracle, not a dependency.
- Hold-out check: fit on N-1 distances, report error on the withheld one; ship it as the in-app residual. Mean absolute error on the owner's door-frame photo set vs current single scale is the acceptance metric.
Custom is reckless when: there is no synthetic-truth property test, the fit can return a number on degenerate input, or full lens intrinsics (K matrix, k1/k2/p1/p2 jointly) are estimated from a handful of taps (under-determined; use OpenCV offline for that). Keep the model small: per-axis scale or homography plus one-parameter k, in line with ADR-002 ordering (undistort, then plane map).

## 4. Fork / adapt
Recommended: adapt. Add a multi-distance fitter next to `MultiPointCalibration`, store its output in the existing additive JSON blocks (version field; `ignoreUnknownKeys` already gives forward-compat), and reuse `MeasurementAdvisor`/`LensMeasurement` for the readout. `MeasurementAnnotation.kt` (282 lines) gets a value-edit path rather than a new model. `CameraProvider` (commonMain) needs a lens list/selection method with Android implementation in `AndroidCameraProvider`.

## Open items (UNVERIFIED)
- Whether the sync worker shares the process with the UI (decides if NamedLocks alone suffices).
- Which physical camera IDs the owner phone exposes.
- Exact OpenCV native size, EJML/Telephoto KMP status, licences (not opened).
