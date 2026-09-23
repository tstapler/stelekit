# Research: Stack — DownloadManager Polling, Cancel/Retry, Compose Wiring

## 1. Current implementation (confirmed by direct read)

`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt:53-127`

- `downloadModel()` is a `suspend fun` wrapping the whole transfer in a single
  `suspendCancellableCoroutine { continuation -> ... }`.
- Sets `_modelState.value = ModelState.Downloading(progress = 0)` exactly once at
  enqueue time (line 78), then does nothing until `ACTION_DOWNLOAD_COMPLETE` fires.
- `continuation.invokeOnCancellation { ... downloadManager.remove(downloadId); _modelState.value = Absent }`
  already exists — cancellation plumbing for "coroutine cancelled" is present, but
  nothing in the UI ever cancels the coroutine (no cancel affordance renders).
- `minSdk = 26`, `compileSdk = 36` (`kmp/build.gradle.kts:1326,1330`). All
  `DownloadManager` columns needed (`COLUMN_BYTES_DOWNLOADED_SO_FAR`,
  `COLUMN_TOTAL_SIZE_BYTES`, `COLUMN_STATUS`, `COLUMN_REASON`) have been available
  since API 9/11 — no SDK-version gating needed for the fix.

## 2. Data flow / wiring (important — affects scope of the fix)

```
DepthModelDownloader (androidMain)          — owns DownloadManager, StateFlow<ModelState>
  └─ modelState: StateFlow<ModelState>       (Absent/Downloading/Ready/Failed)
OnnxMonocularDepthEstimator (androidMain)    — wraps downloader, re-exposes modelState
DepthEstimationCoordinator (commonMain)      — owns DepthCoordinatorState.depthModelUiState
  └─ updateDepthModelUiState(uiState: DepthModelUiState)  — pushed in from Android layer
AnnotationEditorViewModel (commonMain)       — sealed interface DepthModelUiState (mirror of
                                                DepthModelDownloader.ModelState, line 115-127)
AnnotationEditorScreen.kt:1319 DepthEstimationPanel(modelState, onDownload, ...) — renders UI
```

`DepthModelUiState` (commonMain, `AnnotationEditorViewModel.kt:115-127`) is a
**platform-independent mirror** of `DepthModelDownloader.ModelState` — any new state
(e.g. a `Stalled`/timeout signal, or richer `Downloading(bytesDownloaded, totalBytes)`)
must be added to *both* the androidMain `ModelState` and the commonMain
`DepthModelUiState`, plus the mapping that converts one to the other (search for
that mapping — it wasn't found by name in this pass; likely inline in the Android
composable/Activity glue or `SensorModule`, worth confirming in implementation).

**Gap found, relevant to any fix:** `AnnotationEditorScreen(...)` is invoked from
`ScreenRouter.kt:400-411` **without** `onDownloadDepthModel` or `onEstimateDepth`
arguments — both default to `null` (`AnnotationEditorScreen.kt:142`), and the panel
only renders `if (onDownloadDepthModel != null || onEstimateDepth != null)`
(`AnnotationEditorScreen.kt:581`). As currently wired at the real call site, the
depth-model download panel may not render at all in production navigation. The
implementation plan needs to confirm/fix this wiring gap, not just the polling
logic — otherwise the polling fix has no live call site.

## 3. Idiomatic polling pattern — matches existing codebase conventions

This repo already has the exact shape needed, twice:

**`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphFileWatcher.kt:115-133`**
```kotlin
watcherJob = scope.launch {
    launch {
        while (isActive) {
            try {
                delay(pollIntervalMs)
                checkDirectoryForChanges(pagesDir)
                checkDirectoryForChanges(journalsDir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Error in graph watcher", e)
            }
        }
    }
}
```

**`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/SafChangeDetector.kt:185-190`**
```kotlin
pollingJob = scope.launch(Dispatchers.IO) {
    while (isActive) {
        delay(30_000)
        onExternalChange()
    }
}
```

Both are **separate `launch`ed jobs**, not inline loops inside a
`suspendCancellableCoroutine`. That's the idiom to follow: don't try to poll from
inside the existing `suspendCancellableCoroutine` block in `DepthModelDownloader`.
Instead, launch a sibling polling coroutine (on the class's own scope, or a
`coroutineScope { }` wrapping both the completion-wait and the polling launch) that:

1. Queries `DownloadManager.Query().setFilterById(downloadId)` every 200-500ms.
2. Reads `COLUMN_BYTES_DOWNLOADED_SO_FAR` / `COLUMN_TOTAL_SIZE_BYTES` via the cursor.
3. Emits `_modelState.value = ModelState.Downloading(progress = pct)` — or
   `Downloading(progress = -1)` when `COLUMN_TOTAL_SIZE_BYTES` is `-1` (unknown
   total size — DownloadManager returns this when the server doesn't send
   `Content-Length`), matching acceptance criterion 2 (spinner, not fixed "0%").
4. Is cancelled together with the outer suspend function — either by structuring
   the whole thing as `coroutineScope { launch { pollLoop() }; /* await broadcast */ }`
   so cancelling the caller's coroutine cancels both children automatically, or by
   keeping a `Job` reference and calling `.cancel()` in the existing
   `invokeOnCancellation` block (`DepthModelDownloader.kt:120-125`) alongside the
   existing `downloadManager.remove(downloadId)` call.

`DepthModelDownloader` currently has **no `CoroutineScope` of its own** — it relies
entirely on the caller's coroutine via `suspendCancellableCoroutine`. Given the
CLAUDE.md rule that any class living in `remember { }` must own its scope
internally (not applicable here — `DepthModelDownloader` isn't stored via
`remember`, it's instantiated inside `OnnxMonocularDepthEstimator`, itself created
at `SensorModule` registration time, so its lifetime is app/estimator-scoped, not
composition-scoped). The safest structure is `coroutineScope { }` inside
`downloadModel()` itself (structured concurrency, no leaked class-level scope
needed) — a `launch { pollLoop() }` child alongside converting the
`suspendCancellableCoroutine` into a nested `suspendCancellableCoroutine` for the
broadcast-wait, both children cancelled together when the parent `coroutineScope`
exits (success, failure, or cancellation).

## 4. Cancel affordance — Compose pattern

No existing "cancel a suspend operation from a button" UI pattern was found
verbatim in this codebase (searched `DownloadManager`, `cancel`, `Job` button
wiring). The general shape used elsewhere for cancellable async UI work
(`GraphLoader`, `QrTransferCoordinator`) is: ViewModel/Coordinator holds a `Job?`
field for the in-flight operation, exposes a `cancel()` method that calls
`job?.cancel()`, and the Composable wires a button's `onClick` to that method. For
`DepthModelDownloader`, the natural shape:

- Add `fun cancelDownload()` to `DepthModelDownloader` that calls
  `downloadManager.remove(activeDownloadId)` directly (not just relying on
  coroutine cancellation) and sets `_modelState.value = ModelState.Absent`. This
  satisfies acceptance criterion 3 even if the underlying coroutine job reference
  isn't trivially reachable from the UI thread that renders the cancel button.
- Thread this through `DepthModelUiState` (commonMain) → `DepthEstimationPanel`
  the same way `onDownload` is threaded today (`AnnotationEditorScreen.kt:142,586`)
  — add an `onCancelDownload: (() -> Unit)? = null` parameter, render a cancel
  icon/button next to the `CircularProgressIndicator` in the `Downloading` branch
  (`AnnotationEditorScreen.kt:1382-1397`).

## 5. Timeout → Failed transition (acceptance criterion 4)

No existing "stall timeout" pattern found in this codebase for network-style
operations (the closest analogs — `GitHubDeviceFlowClient`, `WorkManagerSyncScheduler`
— use fixed poll intervals but not stall detection). Recommended approach, staying
in the same polling loop: track `lastBytesDownloaded` and a `lastProgressAt`
timestamp; if bytes-downloaded hasn't advanced for N seconds (e.g. 30s, matching
the `SafChangeDetector` 30s polling fallback constant already used elsewhere as a
"reasonable slow-network tolerance" in this codebase) transition to
`ModelState.Failed` and cancel the DownloadManager request, rather than waiting
indefinitely for `ACTION_DOWNLOAD_COMPLETE` (which never fires if the OS silently
drops/pauses the download, e.g. `STATUS_PAUSED` with `PAUSED_WAITING_FOR_NETWORK`).
`DownloadManager.COLUMN_STATUS` values `STATUS_PAUSED`/`STATUS_PENDING` combined
with `COLUMN_REASON` can distinguish "paused, waiting on network" (recoverable,
keep polling) from a genuine stall (bytes not advancing despite `STATUS_RUNNING`).

## 6. Screen lifecycle (acceptance criterion 5 — leaving mid-download)

`DepthModelDownloader` is owned by `OnnxMonocularDepthEstimator`, which per
`SensorModule.kt:20` is registered once at app/sensor-module scope, not
recreated per-composition of `AnnotationEditorScreen`. That means the underlying
`DownloadManager` enqueue (a system service) already survives navigation away from
the screen — Android's `DownloadManager` continues the transfer regardless of app
foreground state. The risk is narrower than "download stops": it's that the
*coroutine* awaiting completion (`suspendCancellableCoroutine` inside
`downloadModel()`) may be tied to a caller scope that gets cancelled when the
screen is torn down (e.g. if `downloadModel()` is called from
`AnnotationEditorViewModel`'s scope rather than a longer-lived one) — need to trace
the actual call site of `downloadModel()` in the implementation phase (not found
by name search in this pass — likely invoked reactively from a `LaunchedEffect`
or from `OnnxMonocularDepthEstimator.initialize()`; confirm before implementing,
since if the awaiting coroutine dies, the `invokeOnCancellation` block will also
fire `downloadManager.remove(downloadId)`, destructively cancelling a still-healthy
system download just because the user navigated away — this must NOT happen for
AC5 to hold). The `modelState` StateFlow itself is safe to re-observe on return
(it's `StateFlow`, always has a current value) — the risk is specifically whether
re-entering the screen can re-attach to an in-flight download's *progress polling*
without restarting the download from scratch.

## Key files for implementation phase

| File | Role |
|---|---|
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt` | Core fix location — polling loop, cancel, timeout |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/OnnxMonocularDepthEstimator.kt` | Re-exposes `modelState`; find `downloadModel()` call site here |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorViewModel.kt:115-127` | `DepthModelUiState` mirror — extend for cancel/stall if needed |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/DepthEstimationCoordinator.kt` | `updateDepthModelUiState`, coordinator scope |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt:1318-1427` | `DepthEstimationPanel` — add cancel button UI |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ScreenRouter.kt:400-411` | Wiring gap — `onDownloadDepthModel`/`onEstimateDepth` not passed |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/SafChangeDetector.kt:185-190` | Reference polling-loop idiom |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphFileWatcher.kt:115-133` | Reference polling-loop idiom (try/catch(CancellationException) shape) |
| `kmp/build.gradle.kts:1326,1330` | `compileSdk = 36`, `minSdk = 26` — no API gating concerns |
