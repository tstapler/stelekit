# Implementation Plan: depth-model-download-stall

**Feature**: Fix the depth-model download progress indicator that reports a frozen "0%" for the
entire ~100MB transfer, add cancel/stall affordances, and wire the currently-unreachable
`DepthEstimationPanel` into the live app so the fix is actually testable/shippable.
**Date**: 2026-07-28
**Status**: Ready for implementation
**ADRs**: ADR-001 (instance-owned polling scope + split cancellation semantics), ADR-002
(capability-interface wiring instead of extending the shared `MonocularDepthEstimator`
interface), ADR-003 (route stall timeout into the existing `Failed` state)

---

## Correction to research/architecture.md before you read the tasks below

`research/architecture.md`'s headline finding states `SensorModule.monocularDepthEstimator` is
"never reassigned... anywhere in androidMain." This was verified **false** during planning: it
*is* assigned, at `androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt:72`
(`SensorModule.monocularDepthEstimator = OnnxMonocularDepthEstimator(applicationContext)`,
inside `onCreate()`). The research grepped only `kmp/src/androidMain` — it missed that the real
Android app entry point (`MainActivity`, `SteleKitApplication`) lives in a **separate Gradle
module**, `androidApp/`, not inside the `kmp/` KMP module.

What research got right and remains fully confirmed: **`ScreenRouter.kt:400-411` is the sole
call site of `AnnotationEditorScreen` in the entire repo** (checked across `kmp/` and
`androidApp/`), and it passes neither `onDownloadDepthModel` nor `onEstimateDepth` — both stay
`null`, so `DepthEstimationPanel` never renders in the shipped app
(`AnnotationEditorScreen.kt:581` gate). Nothing anywhere calls
`AnnotationEditorViewModel.updateDepthModelUiState` either. **The wiring gap is real — it's just
narrower than research described**: only the ScreenRouter→panel plumbing and the
`modelState`→`DepthModelUiState` subscription are missing, not the `SensorModule` assignment
itself. Phase 1 below is scoped to the real, narrower gap.

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `DepthModelDownloader` | androidMain class (`platform/ml/DepthModelDownloader.kt`) that owns the `DownloadManager` enqueue/poll/cancel lifecycle and exposes `modelState` as a `StateFlow`. | Instantiated once inside `OnnxMonocularDepthEstimator`, which is itself a process-lifetime singleton via `SensorModule`. |
| `ModelState` | androidMain sealed interface (`Absent` / `Downloading(progress: Int)` / `Ready` / `Failed`) — the download lifecycle as `DepthModelDownloader` sees it. | `progress` is 0-100, or -1 for indeterminate. |
| `DepthModelUiState` | commonMain sealed interface (`AnnotationEditorViewModel.kt:115-127`) — a platform-independent mirror of `ModelState` for UI consumption. | Kept as a separate type from `ModelState` by design (ADR-001/Pattern Decisions) — bridged by an explicit mapper, not merged. |
| `DownloadableDepthModel` | New commonMain capability interface (`platform/ml/MonocularDepthEstimator.kt`) exposing `modelState: StateFlow<DepthModelUiState>`, `suspend fun downloadModel()`, `fun cancelDownload()`. | Implemented only by `OnnxMonocularDepthEstimator` (Android). `NoOpMonocularDepthEstimator` and any future iOS estimator do **not** implement it — see ADR-002. |
| `activeDownloadId` | Existing `Long` field on `DepthModelDownloader` (`DepthModelDownloader.kt:40`) tracking the in-flight `DownloadManager.enqueue()` ID; `-1L` when idle. | Becomes the authoritative reattachment guard — see reattachment guard below. |
| `DepthModelDownloader.scope` | New instance-owned `CoroutineScope(SupervisorJob() + PlatformDispatcher.IO + CoroutineExceptionHandler)` field, living as long as the singleton (app-process lifetime). | Mirrors the existing repo idiom in `GraphFileWatcher.kt`/`SafChangeDetector.kt`. Not a `remember`-scoped object, so this does not violate the `rememberCoroutineScope` rule. |
| `pollingJob` | `Job` field on `DepthModelDownloader`, the sibling coroutine launched on `scope` that queries `DownloadManager.Query()` every 200-500ms while a download is active. | Cancelled explicitly by `cancelDownload()`, and stops itself on observing a terminal `DownloadManager` status. |
| reattachment guard | The check `if (activeDownloadId != -1L) { /* await existing modelState instead of re-enqueuing */ }` at the top of `downloadModel()`. | Fixes the confirmed double-enqueue bug (pitfalls.md §4, features.md edge case 2) and is the mechanism that makes AC5 hold structurally. |
| `cancelDownload()` | New public method on `DepthModelDownloader`: cancels `pollingJob`, calls `downloadManager.remove(activeDownloadId)`, deletes `modelFile` if present, sets `modelState = Absent`. | Distinct from — and no longer triggered by — ordinary caller-coroutine cancellation (ADR-001). |
| `STALL_TIMEOUT_MS` | New constant (`30_000L`), matching `SafChangeDetector`'s existing 30s poll-tolerance constant used elsewhere in this codebase as the "reasonable slow network" reference point. | Threshold of no byte-count movement before a `Downloading` transfer is treated as stalled. |
| `lastBytesDownloaded` / `lastProgressAt` | Polling-loop-local `var`s tracking the most recent distinct byte count and the wall-clock time it was observed. | Used to compute the stall condition; reset whenever bytes advance. |
| `Failed.reason` | New nullable `String?` field added to both `ModelState.Failed` and `DepthModelUiState.Failed` (converted from `data object` to `data class`). | Carries plain-language copy, e.g. `"This is taking longer than expected."` for a stall-triggered failure, vs. `null` for the existing generic failure — see ADR-003. |
| `toUiState()` | New androidMain extension function `DepthModelDownloader.ModelState.toUiState(): DepthModelUiState` performing the explicit 1:1 mapping between the two sealed hierarchies. | Lives in androidMain (only androidMain can see both types without inverting the platform→ui dependency direction). |
| Depth-wiring `LaunchedEffect` | New `LaunchedEffect` block in `ScreenRouter.kt`'s `AnnotationEditor` branch that collects `(SensorModule.monocularDepthEstimator as? DownloadableDepthModel)?.modelState` and calls `annotationEditorViewModel.updateDepthModelUiState(...)` on each emission. | The missing link research identified — nothing calls `updateDepthModelUiState` anywhere today. |
| `DepthEstimationPanel` | Existing commonMain composable (`AnnotationEditorScreen.kt:1319-1427`) rendering the floating overlay for the four `DepthModelUiState`-derived branches. | Gains a Cancel `TextButton` in the `Downloading` branch and a `liveRegion` semantics modifier on state transitions. |
| `computeProgressPercent` / `hasStalled` | New pure top-level functions extracted from the polling loop (`DepthModelDownloader.kt`) so the percentage/stall math is unit-testable without mocking `DownloadManager`. | `computeProgressPercent(bytes: Long, total: Long): Int`, `hasStalled(lastProgressAt: Long, now: Long, timeoutMs: Long = STALL_TIMEOUT_MS): Boolean`. |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Overall implementation strategy | Full targeted fix: instance-owned polling scope, explicit cancel, stall timeout, capability-interface wiring (Option B in Step 0.5 brainstorm) | This plan | (A) Minimal-diff inline `while(isActive){delay}` loop inside the existing `suspendCancellableCoroutine`, cancel wired straight to caller-coroutine cancellation | Ties polling lifetime to one caller's continuation — fails AC5 (screen navigate-away would still destroy the download) and does nothing for the confirmed double-enqueue bug (pitfalls.md §4) |
| Overall implementation strategy | (same as above) | This plan | (C) Adopt a download library (Fetch) or rewrite via WorkManager+OkHttp/Ktor | Explicitly contradicts requirements.md's stated non-goal ("General DownloadManager → OkHttp/Ktor migration... existing choice is intentional") and build-vs-buy.md's finding of real maintenance risk (Fetch's stale Android-14 issue) |
| Download/UI wiring boundary | Capability interface `DownloadableDepthModel` (Interface Segregation Principle) implemented only by `OnnxMonocularDepthEstimator`; `ScreenRouter` uses a safe cast (`as? DownloadableDepthModel`) | GoF/SOLID — ISP | Extend the base `MonocularDepthEstimator` interface with default no-op `modelState`/`downloadModel()`/`cancelDownload()` members for all platforms | Would touch iOS/JVM/WASM implementations for a fix requirements.md scopes as Android-only (explicit Non-Goal), and leaks an Android-`DownloadManager`-shaped concept into a platform-agnostic interface |
| Progress polling loop | Structured-concurrency sibling `launch` on `DepthModelDownloader`'s own instance-owned `CoroutineScope` (app-process lifetime, matches its `SensorModule` singleton lifetime) | Existing repo idiom (`GraphFileWatcher.kt:115-133`, `SafChangeDetector.kt:185-190`) | Inline `while(isActive){delay()}` nested inside `downloadModel()`'s `suspendCancellableCoroutine` lambda | Automatically dies with the calling coroutine, so a second `downloadModel()` call while one is in flight still double-enqueues; conflates "screen navigated away" with "user cancelled" |
| Cancellation semantics | Explicit public `cancelDownload()` distinct from `invokeOnCancellation` | Command pattern — explicit user action vs. incidental coroutine teardown | Keep the existing `invokeOnCancellation`-only cleanup and wire the UI Cancel button to `job.cancel()` | Same trigger (coroutine cancellation) currently means both "user explicitly cancelled" (AC3: destroy the download) and "screen navigated away" (AC5: must NOT destroy the download) — these need separate triggers |
| Stall-detection target state | Route stall timeout into the existing `Failed` state, with a new optional `reason: String?` field | Reuse over invention; matches AC4's literal wording ("timeout-driven transition to Failed with retry") and ux.md's explicit recommendation | Add a new `Stalled` sealed variant to both `ModelState` and `DepthModelUiState` | Requires touching both sealed hierarchies plus the `DepthEstimationPanel` `when` plus `toUiState()` an extra time, for a distinction the AC doesn't require |
| `ModelState` ↔ `DepthModelUiState` boundary | Keep as two separate types joined by an explicit `toUiState()` mapper in androidMain | Anti-Corruption Layer (DDD) at the platform/commonMain boundary | Merge into a single commonMain type used directly by `DepthModelDownloader` | Would make androidMain's platform-layer file depend on a type currently owned by the ui layer (or vice versa), inverting `CLAUDE.md`'s stated layering (`Platform abstracts →` is the lowest layer); larger diff than this bug-fix ticket warrants |
| Concurrent `downloadModel()` calls | Reattachment guard on the existing `activeDownloadId` field (`if (activeDownloadId != -1L) return awaitExisting()`) | Guard clause / Idempotent Receiver | New `Mutex` around `downloadModel()` | `activeDownloadId` is already the field the codebase treats as the in-flight marker; a `Mutex` introduces a second, potentially-inconsistent notion of "in flight" |
| Partial file safety on cancel/stall | Explicit `modelFile.delete()` immediately after `downloadManager.remove()` | Belt-and-suspenders defensive cleanup | Download-to-temp-file-then-rename-on-success (`GraphWriter`-style atomic write) | Closes the specific async-deletion race (pitfalls.md §6) in one line; temp-file+rename is more robust generally but touches the `Request` destination, `isModelReady()`, and the completion handler — larger change than this ticket needs |
| Testing the polling/stall math | Extract pure functions (`computeProgressPercent`, `hasStalled`) unit-tested directly | Pure-function extraction (no `DownloadManager` mock needed) | Mock `DownloadManager`/`Cursor` end-to-end for every progress-math case | No `mockk` dependency exists in `androidUnitTest` today (confirmed in `kmp/build.gradle.kts:326-339` — only Robolectric/JUnit); pure functions are testable in `commonTest`/`androidUnitTest` with zero new dependencies |

---

## Migration Plan

Omit — no SQLDelight schema or persisted-data changes. All state here is in-memory
(`StateFlow`) plus a file on disk (`filesDir/models/depth_anything_v2_small.onnx`), unchanged
in location/format.

## Observability Plan

- **Logs**: Add a `Logger("DepthModelDownloader")` instance (matching the existing
  `Logger("AnnotationEditorViewModel")` convention at `AnnotationEditorViewModel.kt:197`).
  Log at `info` on enqueue, cancel, and terminal transitions (bytes/status only, no PII); log at
  `warn` when the stall timeout fires and when a reattachment occurs instead of a fresh enqueue.
- **Metrics**: None required by the ACs; no existing telemetry pipeline for this feature was
  found in research. Not adding new metrics infrastructure for this fix.
- **Alerts**: None — Android-only client-side UX fix with no server component.

## Risk Control

- **Feature flag**: None. No feature-flag infrastructure was found in research (`stack.md`
  doesn't mention one), and this fix — including the wiring task — is what makes the panel
  reachable at all; merging the PR is the rollout gate.
- **Rollback procedure**: Standard `git revert` of the PR. Changes are isolated to
  `DepthModelDownloader.kt` (androidMain), `MonocularDepthEstimator.kt`/`AnnotationEditorViewModel.kt`/
  `DepthEstimationCoordinator.kt`/`AnnotationEditorScreen.kt`/`ScreenRouter.kt` (commonMain) — no
  DB/schema footprint, so revert is a clean file-level rollback with no migration to undo.
- **Staged rollout**: None available in this repo's pipeline. Verification gate before merge:
  `./gradlew ciCheck` plus a manual on-device pass (`./gradlew installAndroid`, trigger a real
  download over a throttled connection, confirm percentage advances, cancel works, and a
  simulated stall — e.g. airplane mode mid-download — transitions to `Failed` within
  `STALL_TIMEOUT_MS`).

## Unresolved Questions

- [ ] Should `Failed.reason` be a free-form `String?` or a closed `enum FailureReason`? — blocks
      Task 4.1.2b (Failed-branch UI copy) — owner: implementer; plan defaults to nullable
      `String?` for minimal diff (see Pattern Decisions) unless product wants finer-grained
      copy variants later.
- [ ] Does any other control in `AnnotationEditorScreen` gate on `depthModelUiState == Ready`
      (ux.md Q2 flagged this as unconfirmed)? — blocks the exact copy chosen in Task 1.2.1a
      ("keep annotating" framing is only accurate if true) — owner: implementer, resolved by a
      grep during that task, not a separate research pass.
- [ ] Is the Hugging Face `resolve/main` redirect a real (not just perceived) contributor to
      stalls? — explicitly out of scope per requirements.md Non-Goals unless this fix's own
      diagnostic logging (stall `warn` logs above) surfaces it as the dominant cause after
      shipping — no blocking task, informational only.

## Dependency Visualization

```
Phase 1 — Wiring (REQUIRED prerequisite — nothing below is reachable/testable without it)
  Epic 1.1 (capability interface)  ──┐
  Epic 1.2 (ScreenRouter wiring)   ──┴──> unlocks Phases 2-6 (panel now renders, downloadModel() reachable)

Phase 2 — Real progress (AC1, AC2)
  Epic 2.1 (instance scope + reattachment guard + polling loop) ──> unlocks Phase 3 (cancel needs pollingJob/scope to exist)

Phase 3 — Cancel (AC3)
  Epic 3.1 (cancelDownload + partial-file cleanup + UI button) ──> unlocks Phase 4 (stall reuses cancelDownload's cleanup path)

Phase 4 — Stall detection (AC4)
  Epic 4.1 (stall timer + Failed.reason + UI copy)

Phase 5 — Regression safety (AC5, AC6)
  Epic 5.1 (reattachment/fast-path/retry regression tests)

Phase 6 — Accessibility polish (ux.md recommendations, non-blocking)
  Epic 6.1 (liveRegion semantics, byte-progress string)

Sequencing: 1 → 2 → 3 → 4 → 5, with 6 parallelizable after Phase 3 (touches only UI copy/semantics).
```

---

## Phase 1: Wiring the depth-model feature into the live app

### Epic 1.1: Capability interface for the downloadable depth model

**Goal**: Give commonMain (`ScreenRouter`) a safe, platform-agnostic way to reach
`DepthModelDownloader`'s download surface without polluting the shared `MonocularDepthEstimator`
interface for iOS/JVM/WASM (ADR-002).

#### Story 1.1.1: Add `DownloadableDepthModel` capability interface + `Failed.reason` field
**As a** developer wiring the depth-model panel into `ScreenRouter`, **I want** a capability
interface exposing `modelState`/`downloadModel()`/`cancelDownload()`, **so that** I can call it
from commonMain via a safe cast without touching `NoOpMonocularDepthEstimator` or any iOS stub.
**Acceptance Criteria**:
- `DownloadableDepthModel` compiles in commonMain and is implemented by `OnnxMonocularDepthEstimator`.
  - *Given* `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ml/MonocularDepthEstimator.kt`,
    *When* `DownloadableDepthModel` is added to that file and `OnnxMonocularDepthEstimator`
    declares `: MonocularDepthEstimator, DownloadableDepthModel`, *Then* `./gradlew :kmp:compileDebugKotlinAndroid`
    and `./gradlew jvmTest` both succeed with no changes required to `NoOpMonocularDepthEstimator`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ml/MonocularDepthEstimator.kt`,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/OnnxMonocularDepthEstimator.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorViewModel.kt`

##### Task 1.1.1a: Add `DownloadableDepthModel` interface to `MonocularDepthEstimator.kt` (~3 min)
- After the existing `NoOpMonocularDepthEstimator` class (end of file), add:
  ```kotlin
  /**
   * Capability interface for a [MonocularDepthEstimator] whose model must be downloaded before
   * use. Implemented only by platforms with a downloadable asset (Android today) — see ADR-002
   * for why this is not part of the base [MonocularDepthEstimator] interface.
   */
  interface DownloadableDepthModel {
      val modelState: kotlinx.coroutines.flow.StateFlow<dev.stapler.stelekit.ui.annotate.DepthModelUiState>
      suspend fun downloadModel(): Either<DomainError, Unit>
      fun cancelDownload()
  }
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ml/MonocularDepthEstimator.kt`

##### Task 1.1.1b: Convert `DepthModelUiState.Failed` from `data object` to `data class` with `reason` (~3 min)
- In `AnnotationEditorViewModel.kt:125-126`, change
  `data object Failed : DepthModelUiState` to
  `data class Failed(val reason: String? = null) : DepthModelUiState`.
- Update the doc comment above it to mention the optional reason string (e.g. surfaced when a
  stall timeout, not a generic failure, caused the transition).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorViewModel.kt`

##### Task 1.1.1c: Convert `DepthModelDownloader.ModelState.Failed` from `data object` to `data class` with `reason` (~2 min)
- In `DepthModelDownloader.kt:148-149`, change
  `data object Failed : ModelState` to `data class Failed(val reason: String? = null) : ModelState`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 1.1.1d: Fix the two existing `ModelState.Failed` call sites for the new constructor (~2 min)
- `DepthModelDownloader.kt:103` (`_modelState.value = ModelState.Failed`) → no change needed,
  default `reason = null` still resolves; verify it compiles as-is.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`
  (verification-only task — confirm `./gradlew :kmp:compileDebugKotlinAndroid` passes)

#### Story 1.1.2: Implement `DownloadableDepthModel` on `OnnxMonocularDepthEstimator` + add `toUiState()` mapper
**As a** developer, **I want** `OnnxMonocularDepthEstimator` to forward `DownloadableDepthModel`
calls to its `downloader`, mapping `ModelState` → `DepthModelUiState`, **so that** commonMain
never needs to know `ModelState` exists.
**Acceptance Criteria**:
- `OnnxMonocularDepthEstimator.modelState` (the `DownloadableDepthModel` one) emits
  `DepthModelUiState`, not `ModelState`.
  - *Given* `downloader.modelState` emits `ModelState.Downloading(progress = 55)`, *When* it's
    observed through `OnnxMonocularDepthEstimator.modelState` (the new `DownloadableDepthModel`
    property), *Then* the observed value is `DepthModelUiState.Downloading(progress = 55)`.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/OnnxMonocularDepthEstimator.kt`,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 1.1.2a: Add `toUiState()` extension mapper in `DepthModelDownloader.kt` (~4 min)
- At the bottom of `DepthModelDownloader.kt` (after the class, before/after `companion object` is
  fine since it's a top-level extension), add:
  ```kotlin
  /** Explicit 1:1 mapping — see ADR-001/Pattern Decisions for why these stay two types. */
  fun DepthModelDownloader.ModelState.toUiState(): dev.stapler.stelekit.ui.annotate.DepthModelUiState =
      when (this) {
          is DepthModelDownloader.ModelState.Absent -> dev.stapler.stelekit.ui.annotate.DepthModelUiState.Absent
          is DepthModelDownloader.ModelState.Downloading ->
              dev.stapler.stelekit.ui.annotate.DepthModelUiState.Downloading(progress)
          is DepthModelDownloader.ModelState.Ready -> dev.stapler.stelekit.ui.annotate.DepthModelUiState.Ready
          is DepthModelDownloader.ModelState.Failed ->
              dev.stapler.stelekit.ui.annotate.DepthModelUiState.Failed(reason)
      }
  ```
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 1.1.2b: Implement `DownloadableDepthModel` members on `OnnxMonocularDepthEstimator` (~4 min)
- Change class declaration to
  `class OnnxMonocularDepthEstimator(private val context: Context) : MonocularDepthEstimator, DownloadableDepthModel`.
- Replace the existing `val modelState: StateFlow<DepthModelDownloader.ModelState> = downloader.modelState`
  (line 49, which is no longer needed verbatim) with the `DownloadableDepthModel`-typed override:
  ```kotlin
  override val modelState: StateFlow<DepthModelUiState> =
      downloader.modelState.map { it.toUiState() }.stateIn(
          scope = CoroutineScope(PlatformDispatcher.Default),
          started = SharingStarted.Eagerly,
          initialValue = downloader.modelState.value.toUiState(),
      )
  override suspend fun downloadModel(): Either<DomainError, Unit> = downloader.downloadModel().map {}
  override fun cancelDownload() = downloader.cancelDownload()
  ```
  (Note: `downloader.downloadModel()` today returns `Either<DomainError, File>` — `.map {}`
  discards the `File`, matching the `DownloadableDepthModel` interface's `Unit` return; callers
  needing the path still use `downloader.modelFilePath()` directly, unchanged.)
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/OnnxMonocularDepthEstimator.kt`

---

### Epic 1.2: Wire `ScreenRouter` → `AnnotationEditorScreen` with live depth-model state

**Goal**: Make `DepthEstimationPanel` actually render in the shipped app and stay in sync with
`DepthModelDownloader.modelState` — the prerequisite research flagged as blocking every other AC.

#### Story 1.2.1: Pass `onDownloadDepthModel`/`onEstimateDepth` from `ScreenRouter` and subscribe to `modelState`
**As a** user opening the photo annotation editor, **I want** the "Download depth model" button
to actually appear and work, **so that** I can use AI scale estimation at all.
**Acceptance Criteria**:
- `DepthEstimationPanel` renders on a real device/emulator when `AnnotationEditorScreen` is
  reached via normal navigation.
  - *Given* the app is running on Android (where `SensorModule.monocularDepthEstimator` is an
    `OnnxMonocularDepthEstimator`, per `SteleKitApplication.kt:72`), *When* the user navigates to
    `Screen.AnnotationEditor`, *Then* `ScreenRouter.kt`'s `AnnotationEditorScreen(...)` call now
    includes non-null `onDownloadDepthModel`/`onEstimateDepth` lambdas and the panel is visible
    (no longer gated out by `AnnotationEditorScreen.kt:581`'s `null` check).
- `updateDepthModelUiState` is actually called.
  - *Given* `DepthModelDownloader.modelState` emits `ModelState.Downloading(progress = 10)`,
    *When* the mapped `DepthModelUiState.Downloading(10)` flows through
    `OnnxMonocularDepthEstimator.modelState`, *Then* the new `LaunchedEffect` in `ScreenRouter.kt`
    calls `annotationEditorViewModel.updateDepthModelUiState(DepthModelUiState.Downloading(10))`
    within one collection tick, and `AnnotationEditorState.depthModelUiState` reflects it.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ScreenRouter.kt`

##### Task 1.2.1a: Add the depth-model `LaunchedEffect` + lambdas in `ScreenRouter.kt`'s `AnnotationEditor` branch (~5 min)
- Inside the `is Screen.AnnotationEditor ->` block (`ScreenRouter.kt:370-413`), after the existing
  `DisposableEffect` (line 379-381), add:
  ```kotlin
  val downloadableDepthModel = SensorModule.monocularDepthEstimator as? DownloadableDepthModel
  LaunchedEffect(downloadableDepthModel) {
      downloadableDepthModel?.modelState?.collect { uiState ->
          annotationEditorViewModel.updateDepthModelUiState(uiState)
      }
  }
  ```
- Add `import dev.stapler.stelekit.platform.ml.DownloadableDepthModel` to the file's imports.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ScreenRouter.kt`

##### Task 1.2.1b: Pass `onDownloadDepthModel`/`onEstimateDepth` into the `AnnotationEditorScreen(...)` call (~4 min)
- In the `AnnotationEditorScreen(...)` invocation (`ScreenRouter.kt:400-411`), add:
  ```kotlin
  onDownloadDepthModel = downloadableDepthModel?.let {
      { scope.launch { it.downloadModel() } }
  },
  onEstimateDepth = downloadableDepthModel?.let {
      // Estimation itself is unrelated to this bug — leave wiring to whatever bitmap-capture
      // callback the editor already uses for other estimate triggers, or a follow-up ticket if
      // none exists; deliberately out of scope for the download-stall fix.
      { /* left for a separate ticket — see Unresolved Questions if this needs to ship together */ }
  },
  ```
  Note: verify during implementation whether a `scope`/`rememberCoroutineScope()` is already
  available in this composable scope for `onDownloadDepthModel`'s launch — if not, add a
  transient `rememberCoroutineScope()` at the top of the `AnnotationEditor` branch (this is a
  short-lived UI event-handler use, which is exactly what `rememberCoroutineScope()` is for per
  `CLAUDE.md` — not a violation of the scope-ownership rule since it only launches the one-shot
  `downloadModel()` call, whose actual work now lives on `DepthModelDownloader`'s own
  instance-owned scope after Phase 2, not on this transient one).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ScreenRouter.kt`

##### Task 1.2.1c: Confirm no other control gates on `depthModelUiState == Ready` (~2 min)
- `grep -n "depthModelUiState" kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`
  and confirm the only usage is the `DepthEstimationPanel` call itself (resolves the Unresolved
  Question about "keep annotating" copy accuracy).
- Files: none changed — verification task; record the answer in a one-line PR description note.

---

## Phase 2: Real-time progress polling (AC1, AC2)

### Epic 2.1: Instance-owned scope, reattachment guard, and the polling loop

**Goal**: Replace the single `Downloading(progress = 0)` snapshot with periodic real progress,
and fix the confirmed double-enqueue bug as a side effect of the reattachment guard.

#### Story 2.1.1: Give `DepthModelDownloader` its own instance-owned `CoroutineScope`
**As a** developer, **I want** polling to live on a scope that outlives any single
`downloadModel()` call, **so that** the download and its progress-reporting genuinely survive
screen navigation (matching the class's own "survives backgrounding" doc-comment claim).
**Acceptance Criteria**:
- `DepthModelDownloader` has a `scope` field that is not tied to any caller's coroutine.
  - *Given* `DepthModelDownloader` is constructed once inside `OnnxMonocularDepthEstimator`
    (itself a `SensorModule` singleton), *When* the class is inspected, *Then* it has
    `private val scope = CoroutineScope(SupervisorJob() + PlatformDispatcher.IO + exceptionHandler)`
    where `exceptionHandler` logs via `Logger("DepthModelDownloader")` rather than crashing the
    process (per `CLAUDE.md`'s "uncaught Throwables kill the process on Android" rule).
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 2.1.1a: Add `scope` field + `CoroutineExceptionHandler` + `Logger` (~4 min)
- Add near the top of the class body (after `activeDownloadId`):
  ```kotlin
  private val logger = Logger("DepthModelDownloader")
  private val scope = CoroutineScope(
      SupervisorJob() + PlatformDispatcher.IO +
          CoroutineExceptionHandler { _, e -> logger.warn("Uncaught in DepthModelDownloader scope", e) },
  )
  ```
- Add imports: `kotlinx.coroutines.CoroutineExceptionHandler`, `kotlinx.coroutines.CoroutineScope`,
  `kotlinx.coroutines.SupervisorJob`, `dev.stapler.stelekit.coroutines.PlatformDispatcher`,
  `dev.stapler.stelekit.logging.Logger` (confirm exact `Logger` import path from
  `AnnotationEditorViewModel.kt`'s existing import during implementation).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 2.1.1b: Add `STALL_TIMEOUT_MS` and `POLL_INTERVAL_MS` constants (~2 min)
- In the `companion object` (`DepthModelDownloader.kt:157-163`), add:
  ```kotlin
  private const val POLL_INTERVAL_MS = 300L
  private const val STALL_TIMEOUT_MS = 30_000L
  ```
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

#### Story 2.1.2: Extract pure `computeProgressPercent` / `hasStalled` functions
**As a** developer, **I want** the percentage and stall math as standalone pure functions,
**so that** they're unit-testable without mocking `DownloadManager`/`Cursor` (no `mockk`
dependency exists in `androidUnitTest` — see Pattern Decisions).
**Acceptance Criteria**:
- `computeProgressPercent(bytesDownloaded = 42_000_000L, totalBytes = 100_000_000L)` returns `42`.
  - *Given* `bytesDownloaded = 42_000_000L` and `totalBytes = 100_000_000L`, *When*
    `computeProgressPercent` is called, *Then* it returns `42` (not `42.0` or a `Float`).
- `computeProgressPercent` returns `-1` for unknown total size.
  - *Given* `totalBytes = -1L` (Android's documented "unknown" sentinel), *When*
    `computeProgressPercent` is called, *Then* it returns `-1` without dividing by a
    non-positive number.
- `hasStalled` correctly identifies a stall.
  - *Given* `lastProgressAt = 1_000L`, `now = 32_000L`, `timeoutMs = 30_000L`, *When*
    `hasStalled(lastProgressAt, now, timeoutMs)` is called, *Then* it returns `true` (31s of no
    movement exceeds the 30s threshold).
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`,
new test file `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloaderProgressMathTest.kt`

##### Task 2.1.2a: Add `computeProgressPercent` and `hasStalled` top-level functions (~4 min)
- Add at file scope in `DepthModelDownloader.kt` (below the class, alongside `toUiState()`):
  ```kotlin
  internal fun computeProgressPercent(bytesDownloaded: Long, totalBytes: Long): Int =
      if (totalBytes <= 0L) -1 else ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)

  internal fun hasStalled(lastProgressAt: Long, now: Long, timeoutMs: Long = STALL_TIMEOUT_MS): Boolean =
      (now - lastProgressAt) > timeoutMs
  ```
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 2.1.2b: Write `DepthModelDownloaderProgressMathTest` covering the 3 GWT examples above (~5 min)
- New file with 3 `@Test` functions: `computeProgressPercent returns proportional percentage`,
  `computeProgressPercent returns -1 for unknown total size`,
  `hasStalled returns true past the timeout threshold` (plus a `false` case for movement within
  the window).
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloaderProgressMathTest.kt`

#### Story 2.1.3: Implement the polling loop + reattachment guard in `downloadModel()`
**As a** user, **I want** the progress percentage to advance as bytes actually download, **so
that** the download no longer looks frozen at 0%.
**Acceptance Criteria**:
- Progress advances every ~300ms while downloading (AC1).
  - *Given* a download is enqueued and `DownloadManager.Query()` reports
    `bytesDownloaded = 20_000_000`, then 300ms later `bytesDownloaded = 21_500_000`, out of
    `totalBytes = 100_000_000`, *When* the polling loop ticks twice, *Then* `modelState` emits
    `Downloading(20)` then `Downloading(21)` in sequence — never staying at `Downloading(0)`.
- Indeterminate size falls back to a spinner, not "0%" (AC2).
  - *Given* `DownloadManager.Query()` reports `totalBytes = -1` on the first few ticks (HF
    redirect not yet resolved), *When* the polling loop ticks, *Then* `modelState` emits
    `Downloading(progress = -1)`, and `DepthEstimationPanel` (existing dead-code branch at
    `AnnotationEditorScreen.kt:1390-1392`) renders "Downloading model…" with no "%" — this branch
    becomes reachable for the first time.
- Calling `downloadModel()` while one is already in flight reattaches instead of double-enqueuing.
  - *Given* `activeDownloadId = 12345L` from a prior enqueue that hasn't completed, *When*
    `downloadModel()` is called again (e.g. a second `AnnotationEditorViewModel` instance after
    navigating back to the screen), *Then* no second `DownloadManager.Request` is enqueued —
    the call instead suspends on `modelState.first { it is Ready || it is Failed }` against the
    *existing* `activeDownloadId`'s transfer.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 2.1.3a: Add the reattachment guard at the top of `downloadModel()` (~4 min)
- After the existing fast-path check (`DepthModelDownloader.kt:55-58`), before the
  `modelFile.parentFile?.mkdirs()` line, add:
  ```kotlin
  if (activeDownloadId != -1L) {
      logger.info("downloadModel() called while a download is already in flight — reattaching")
      val terminal = modelState.first { it is ModelState.Ready || it is ModelState.Failed }
      return when (terminal) {
          is ModelState.Ready -> modelFile.right()
          is ModelState.Failed -> DomainError.SensorError.HardwareUnavailable(
              (terminal as ModelState.Failed).reason ?: "Depth model download failed",
          ).left()
          else -> error("unreachable — filtered to Ready/Failed above")
      }
  }
  ```
- Add import `kotlinx.coroutines.flow.first`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 2.1.3b: Extract `startPolling(downloadId, downloadManager)` private method (~5 min)
- New private method on `DepthModelDownloader`:
  ```kotlin
  private fun startPolling(downloadId: Long, downloadManager: DownloadManager): Job = scope.launch {
      var lastBytes = -1L
      var lastProgressAt = System.currentTimeMillis()
      while (isActive) {
          delay(POLL_INTERVAL_MS)
          val query = DownloadManager.Query().setFilterById(downloadId)
          val (bytes, total, status) = downloadManager.query(query)?.use { c ->
              if (!c.moveToFirst()) return@use Triple(-1L, -1L, -1)
              val bytesIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
              val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
              val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
              if (bytesIdx < 0 || totalIdx < 0 || statusIdx < 0) return@use Triple(-1L, -1L, -1)
              Triple(c.getLong(bytesIdx), c.getLong(totalIdx), c.getInt(statusIdx))
          } ?: Triple(-1L, -1L, -1)

          if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
              break // BroadcastReceiver remains sole authority for the terminal transition.
          }
          if (bytes != lastBytes) {
              lastBytes = bytes
              lastProgressAt = System.currentTimeMillis()
          } else if (hasStalled(lastProgressAt, System.currentTimeMillis())) {
              logger.warn("Download stalled for ${STALL_TIMEOUT_MS}ms — cancelling")
              downloadManager.remove(downloadId)
              modelFile.delete()
              activeDownloadId = -1L
              _modelState.value = ModelState.Failed(reason = "This is taking longer than expected.")
              break
          }
          _modelState.value = ModelState.Downloading(progress = computeProgressPercent(bytes, total))
      }
  }
  ```
- Add imports: `kotlinx.coroutines.Job`, `kotlinx.coroutines.isActive`, `kotlinx.coroutines.delay`,
  `kotlinx.coroutines.launch`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 2.1.3c: Call `startPolling` from `downloadModel()` and store the `Job` (~3 min)
- Add a `private var pollingJob: Job? = null` field near `activeDownloadId`.
- Inside `downloadModel()`, right after `activeDownloadId = downloadId` (line 77) and before
  `_modelState.value = ModelState.Downloading(progress = 0)` (line 78), add:
  `pollingJob = startPolling(downloadId, downloadManager)`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 2.1.3d: Stop `pollingJob` from the `BroadcastReceiver`'s completion handler (~3 min)
- Inside `onReceive` (`DepthModelDownloader.kt:82-110`), right after
  `context.unregisterReceiver(this)` (line 87), add `pollingJob?.cancel()` — ensures the polling
  loop doesn't emit one more `Downloading(...)` after a terminal state is set (pitfalls.md §4
  completion-vs-poll race).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 2.1.3e: Update `invokeOnCancellation` to also stop `pollingJob` (~2 min)
- Inside `continuation.invokeOnCancellation { ... }` (`DepthModelDownloader.kt:120-125`), add
  `pollingJob?.cancel()` alongside the existing `runCatching { context.unregisterReceiver(receiver) }`
  and `downloadManager.remove(downloadId)` calls, ordered so the polling job is cancelled first
  (per pitfalls.md §4's "cancel poll job first, then set the terminal state" ordering).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

#### Story 2.1.4: Guard `getColumnIndex` against `-1` everywhere it's used
**As a** developer, **I want** every `Cursor` column lookup guarded, **so that** a malformed
`DownloadManager` row can't throw `CursorIndexOutOfBoundsException` and crash the app (per
`CLAUDE.md`'s uncaught-`Throwable`-kills-Android-process rule).
**Acceptance Criteria**:
- The existing completion handler's unguarded `getColumnIndex` (line 94) is fixed alongside the
  new polling code.
  - *Given* `DownloadManager.Query()` returns a cursor where `getColumnIndex(COLUMN_STATUS)`
    returns `-1` (defensive/theoretical case — column missing), *When* the completion handler
    runs, *Then* it treats this as `succeeded = false` rather than calling `c.getInt(-1)` and
    throwing.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 2.1.4a: Guard the existing completion-handler column lookup (~3 min)
- In `onReceive`'s cursor block (`DepthModelDownloader.kt:92-97`), change:
  ```kotlin
  val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
  c.getInt(statusCol) == DownloadManager.STATUS_SUCCESSFUL
  ```
  to:
  ```kotlin
  val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
  statusCol >= 0 && c.getInt(statusCol) == DownloadManager.STATUS_SUCCESSFUL
  ```
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

(Task 2.1.3b above already guards the new polling loop's three column lookups inline.)

---

## Phase 3: Cancel affordance (AC3)

### Epic 3.1: `cancelDownload()` + partial-file cleanup + UI Cancel button

**Goal**: Let the user explicitly cancel a `Downloading` transfer from the UI without conflating
it with ordinary navigate-away.

#### Story 3.1.1: Add `cancelDownload()` to `DepthModelDownloader`
**As a** developer, **I want** an explicit cancel method distinct from coroutine cancellation,
**so that** AC3 (explicit cancel) and AC5 (navigate-away must not cancel) can't be conflated
(ADR-001).
**Acceptance Criteria**:
- Calling `cancelDownload()` removes the request, deletes the partial file, and resets state.
  - *Given* `modelState = Downloading(progress = 47)` and `activeDownloadId = 12345L`, *When*
    `cancelDownload()` is called, *Then* `downloadManager.remove(12345L)` is invoked,
    `modelFile.delete()` is invoked if `modelFile.exists()`, `pollingJob` is cancelled,
    `activeDownloadId` resets to `-1L`, and `modelState` becomes `ModelState.Absent`.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 3.1.1a: Add `fun cancelDownload()` public method (~4 min)
- Add near `modelFilePath()`/`isModelReady()` (public API section, after line 130):
  ```kotlin
  fun cancelDownload() {
      if (activeDownloadId == -1L) return
      pollingJob?.cancel()
      val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
      downloadManager.remove(activeDownloadId)
      runCatching { modelFile.delete() } // belt-and-suspenders — see pitfalls.md §6
      activeDownloadId = -1L
      _modelState.value = ModelState.Absent
  }
  ```
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 3.1.1b: Write `DepthModelDownloaderCancelTest` (Robolectric) covering the GWT above (~5 min)
- Use `Robolectric` + `Shadows.shadowOf(downloadManager)` to fake an enqueued download, call
  `cancelDownload()`, and assert `modelState.value is ModelState.Absent` and that the shadow's
  `remove` was invoked for the right ID.
- Files: new `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloaderCancelTest.kt`

#### Story 3.1.2: Wire the Cancel button through `DepthEstimationPanel`
**As a** user watching a download progress, **I want** a visible Cancel action, **so that** I'm
not stuck waiting with no escape hatch (matches the `Failed` branch's existing `TextButton`
styling per ux.md).
**Acceptance Criteria**:
- A Cancel `TextButton` appears in the `Downloading` branch and calls `onCancelDownload`.
  - *Given* `modelState is DepthModelUiState.Downloading`, *When* `DepthEstimationPanel` renders,
    *Then* a `TextButton` labeled `"Cancel"` (with `contentDescription = "Cancel model download"`)
    appears next to the spinner/percentage, and tapping it invokes the new `onCancelDownload`
    callback.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ScreenRouter.kt`

##### Task 3.1.2a: Add `onCancelDownload` param to `AnnotationEditorScreen` and `DepthEstimationPanel` (~3 min)
- Add `onCancelDownload: (() -> Unit)? = null` next to `onDownloadDepthModel`
  (`AnnotationEditorScreen.kt:142`), and thread it into the `DepthEstimationPanel(...)` call
  (line 582-591) as `onCancel = onCancelDownload ?: {}`.
- Add `onCancel: () -> Unit` param to `DepthEstimationPanel`'s signature (`AnnotationEditorScreen.kt:1319-1326`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`

##### Task 3.1.2b: Render the Cancel `TextButton` in the `Downloading` branch (~4 min)
- In the `modelState is DepthModelUiState.Downloading ->` branch (`AnnotationEditorScreen.kt:1382-1397`),
  after the existing `Text(...)` for the percentage, add:
  ```kotlin
  Spacer(Modifier.width(8.dp))
  TextButton(
      onClick = onCancel,
      modifier = Modifier.semantics { contentDescription = "Cancel model download" },
  ) {
      Text("Cancel", style = MaterialTheme.typography.labelSmall, color = Color.White)
  }
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`

##### Task 3.1.2c: Wire `onCancelDownload` from `ScreenRouter.kt` to `DownloadableDepthModel.cancelDownload()` (~3 min)
- In the `AnnotationEditorScreen(...)` call (`ScreenRouter.kt:400-411`, extended in Task 1.2.1b),
  add:
  ```kotlin
  onCancelDownload = downloadableDepthModel?.let { { it.cancelDownload() } },
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ScreenRouter.kt`

---

## Phase 4: Stall detection (AC4)

### Epic 4.1: Surface "taking longer than expected" via the existing `Failed` state

**Goal**: A stalled transfer must not look identical to a healthy one indefinitely — already
implemented mechanically in Task 2.1.3b (the `hasStalled` check inside `startPolling`); this
epic covers the UI copy and regression coverage.

#### Story 4.1.1: Stall-triggered `Failed` transitions render distinct copy
**As a** user whose download has genuinely stalled, **I want** to see "taking longer than
expected" rather than a frozen percentage, **so that** I know to retry instead of waiting
indefinitely.
**Acceptance Criteria**:
- `Failed(reason = "This is taking longer than expected.")` renders that exact copy instead of
  the generic "Download failed — tap to retry" text.
  - *Given* `modelState = DepthModelUiState.Failed(reason = "This is taking longer than expected.")`,
    *When* `DepthEstimationPanel` renders the `Failed` branch, *Then* the displayed text is
    `"This is taking longer than expected. Tap to retry."` (or equivalent single-sentence
    concatenation), not the generic message — while `Failed(reason = null)` (e.g. from a genuine
    `STATUS_FAILED`) keeps the existing "Download failed — tap to retry" copy.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`

##### Task 4.1.1a: Branch on `Failed.reason` in `DepthEstimationPanel`'s `Failed` case (~4 min)
- In the `modelState is DepthModelUiState.Failed ->` branch (`AnnotationEditorScreen.kt:1400-1415`),
  change the hardcoded `Text(text = "Download failed — tap to retry", ...)` to:
  ```kotlin
  Text(
      text = modelState.reason?.let { "$it Tap to retry." } ?: "Download failed — tap to retry",
      style = MaterialTheme.typography.labelSmall,
      color = Color(0xFFEF5350),
  )
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`

#### Story 4.1.2: Distinguish legitimate `STATUS_PAUSED` from a genuine stall
**As a** user on a network that's briefly waiting to reconnect, **I want** the stall timer to not
misfire while `DownloadManager` is legitimately paused, **so that** a temporary connectivity blip
doesn't force an unnecessary retry.
**Acceptance Criteria**:
- A `STATUS_PAUSED` row with `COLUMN_REASON == PAUSED_WAITING_FOR_NETWORK` does not trigger the
  stall timeout by itself — only genuine no-byte-movement over `STALL_TIMEOUT_MS` does.
  - *Given* the polling loop observes `STATUS_PAUSED`/`PAUSED_WAITING_FOR_NETWORK` for 45
    seconds straight (exceeding `STALL_TIMEOUT_MS`) with `bytesDownloaded` unchanged the whole
    time, *When* the stall check evaluates, *Then* it still transitions to `Failed` — the
    reason string differs only in log detail (`logger.warn` includes the pause reason), and the
    AC4 requirement ("no network progress for N seconds") is honored literally: no special-case
    exemption for `PAUSED_WAITING_FOR_NETWORK`, since a 30s+ stall is exactly the "stuck" user
    experience being fixed regardless of the underlying `DownloadManager` reason code.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 4.1.2a: Log the pause reason (diagnostic only, no behavior change) (~3 min)
- In `startPolling` (Task 2.1.3b), when `status == DownloadManager.STATUS_PAUSED`, read
  `COLUMN_REASON` (guarded the same way as the other columns) and include it in the
  `logger.warn("Download stalled...")` call's message when the stall fires, e.g.
  `"Download stalled for ${STALL_TIMEOUT_MS}ms (status=PAUSED, reason=$reasonCode) — cancelling"`.
  This is intentionally diagnostic-only per the AC above — no behavioral branch on the reason
  code, since AC4's literal wording doesn't ask for one and pitfalls.md flags over-engineering
  this as a risk (a `PAUSED_QUEUED_FOR_WIFI` download that never starts is indistinguishable from
  "stuck" from the user's point of view either way, and 30s is already generous).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

---

## Phase 5: Regression safety (AC5, AC6)

### Epic 5.1: Reattachment, fast-path, and retry regression tests

**Goal**: Prove AC5 and AC6 hold with tests, not just code review — these are the two ACs most
at risk of silent regression from the Phase 2/3 refactor.

#### Story 5.1.1: Reattachment across a simulated screen-navigate-away
**As a** developer, **I want** an automated test proving a second `downloadModel()` call
reattaches instead of double-enqueuing, **so that** AC5 doesn't silently regress in a future
change.
**Acceptance Criteria**:
- Two sequential `downloadModel()` calls with no intervening completion result in exactly one
  `DownloadManager.enqueue()` call.
  - *Given* `downloadModel()` is called once (enqueuing `downloadId = 100L`) and, before it
    completes, `downloadModel()` is called a second time from a simulated second
    `AnnotationEditorViewModel`/coordinator chain, *When* both calls are inspected, *Then*
    `Shadows.shadowOf(downloadManager)`'s enqueue call count is `1`, and both suspended calls
    eventually resolve to the same terminal result once the (single) download completes.
**Files**: new `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloaderReattachTest.kt`

##### Task 5.1.1a: Write the reattachment Robolectric test (~5 min)
- Use `Robolectric` + `kotlinx-coroutines-test` (`runTest`) to launch two concurrent
  `downloadModel()` coroutines against the same `DepthModelDownloader` instance, assert a single
  enqueue via the shadow `DownloadManager`, then simulate `ACTION_DOWNLOAD_COMPLETE` and assert
  both coroutines resolve.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloaderReattachTest.kt`

#### Story 5.1.2: Fast-path and `Failed`→retry regressions (AC6)
**As a** developer, **I want** tests proving the existing fast path and retry flow still work
after this refactor, **so that** AC6 ("no regression") is verified, not assumed.
**Acceptance Criteria**:
- `isModelReady()` fast path still short-circuits without enqueueing.
  - *Given* `modelFile` already exists and is `> MIN_MODEL_SIZE_BYTES` (10MB), *When*
    `downloadModel()` is called, *Then* it returns `modelFile.right()` immediately, `modelState`
    becomes `Ready`, and the shadow `DownloadManager`'s enqueue count stays `0`.
- Retry from `Failed` re-enqueues correctly.
  - *Given* `modelState = Failed(reason = null)` (e.g. after a genuine `STATUS_FAILED`), *When*
    `downloadModel()` is called again, *Then* `activeDownloadId == -1L` (guard doesn't fire),
    the fast path's `isModelReady()` check is `false` (partial/no file), and a fresh
    `DownloadManager.Request` is enqueued exactly as it was before this refactor.
**Files**: new `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloaderRegressionTest.kt`

##### Task 5.1.2a: Write the fast-path regression test (~3 min)
- Pre-create a fake `modelFile` > 10MB in the test's `filesDir`, call `downloadModel()`, assert
  `Ready` and zero enqueue calls.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloaderRegressionTest.kt`

##### Task 5.1.2b: Write the `Failed`→retry regression test (~4 min)
- Force `_modelState.value = ModelState.Failed()` and `activeDownloadId = -1L` directly (test-only
  access — confirm during implementation whether a `@VisibleForTesting` seam or constructor
  param is needed, or whether driving a real failed download via the shadow is cleaner), call
  `downloadModel()` again, assert a fresh enqueue occurs.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloaderRegressionTest.kt`

#### Story 5.1.3: Manual on-device verification pass
**As a** developer shipping this fix, **I want** a manual verification checklist, **so that**
the real `DownloadManager`/HF-redirect behavior (which Robolectric's shadow doesn't fully
replicate) is confirmed working before merge.
**Acceptance Criteria**:
- All 6 ACs are manually confirmed on a physical or emulated Android device.
  - *Given* a debug build installed via `./gradlew installAndroid`, *When* the tester opens the
    annotation editor, taps "Download depth model (~100MB)", watches the percentage advance,
    taps Cancel mid-download, retries, and (optionally, via airplane mode toggle mid-download)
    triggers a stall, *Then* every one of AC1-AC6's Given/When/Then examples above is visually
    confirmed on-device, not just in unit tests.
**Files**: none (manual QA task, documented in the PR description)

##### Task 5.1.3a: Run the manual verification checklist and record results in the PR description (~5 min)
- Files: none — process task.

---

## Phase 6: Accessibility polish (non-blocking, parallelizable after Phase 3)

### Epic 6.1: Live-region announcements and byte-progress string

**Goal**: Apply the `FolderSyncReconciliationProgress.kt` accessibility precedent (ux.md) so
TalkBack users get the same "it's alive, and I know when it's done" signal sighted users get
from the advancing percentage — announced on state transitions only, not per-tick (avoids
TalkBack spam).

#### Story 6.1.1: `liveRegion` semantics on state transitions
**As a** TalkBack user, **I want** the download's state changes announced, **so that** I know
when it starts, fails, stalls, or completes without polling the screen manually.
**Acceptance Criteria**:
- The `Downloading` row and the transition into `Ready`/`Failed`/`Absent` are wrapped in
  `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`, matching
  `FolderSyncReconciliationProgress.kt:84-104`'s `ConnectingState` pattern exactly — the
  percentage text itself is NOT re-announced on every tick.
  - *Given* `modelState` transitions from `Absent` to `Downloading(0)`, *When* TalkBack is
    active, *Then* the transition is announced once; subsequent `Downloading(1)` through
    `Downloading(99)` ticks are NOT individually announced (only the container's presence, per
    `LiveRegionMode.Polite` semantics on a stable container, not per-recomposition text change).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`

##### Task 6.1.1a: Add `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` to `DepthEstimationPanel`'s outer `Column` (~3 min)
- Wrap the existing `Column` at `AnnotationEditorScreen.kt:1332-1334` with the semantics
  modifier, following the exact `FolderSyncReconciliationProgress.kt:84-90` precedent (apply at
  the container level, not per-branch, so transitions between branches are what gets announced).
- Add imports `androidx.compose.ui.semantics.LiveRegionMode`, `androidx.compose.ui.semantics.liveRegion`,
  `androidx.compose.ui.semantics.semantics` if not already present in the file.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`

#### Story 6.1.2: Byte-progress string alongside the percentage
**As a** user on a slow connection, **I want** to see "47 MB / 98 MB" alongside the percentage,
**so that** rounding-induced stalls in the percentage (44% → 44% → 45% over several seconds)
don't read as frozen (ux.md recommendation, not a hard AC — cheap addition given the polling
loop already has both byte values).
**Acceptance Criteria**:
- The `Downloading` row shows both the percentage and a byte count when available.
  - *Given* `ModelState.Downloading(progress = 47)` with the polling loop's last-known
    `bytesDownloaded = 47_000_000L` and `totalBytes = 100_000_000L`, *When*
    `DepthEstimationPanel` renders, *Then* the text reads
    `"Downloading model… 47% (47.0 MB / 100.0 MB)"` (or equivalent formatting) rather than just
    `"Downloading model… 47%"`.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorViewModel.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`

##### Task 6.1.2a: Add `bytesDownloaded`/`totalBytes` fields to `Downloading` in both sealed types (~4 min)
- `ModelState.Downloading(val progress: Int, val bytesDownloaded: Long = -1L, val totalBytes: Long = -1L)`
  (`DepthModelDownloader.kt:143`) and the matching `DepthModelUiState.Downloading`
  (`AnnotationEditorViewModel.kt:120`) — defaults keep this additive/non-breaking for any other
  call site.
- Update `toUiState()` (Task 1.1.2a) to forward the two new fields.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`,
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorViewModel.kt`

##### Task 6.1.2b: Populate the new fields from `startPolling` (~2 min)
- In `startPolling` (Task 2.1.3b), change the final `_modelState.value = ModelState.Downloading(progress = ...)`
  line to also pass `bytesDownloaded = bytes, totalBytes = total`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

##### Task 6.1.2c: Render the byte-progress string in `DepthEstimationPanel` (~4 min)
- In the `Downloading` branch (`AnnotationEditorScreen.kt:1382-1397`), format
  `bytesDownloaded`/`totalBytes` (when both `>= 0`) as MB with one decimal place, appended to the
  existing percentage text.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`
