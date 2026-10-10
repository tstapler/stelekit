# Architecture Research: Depth Model Download Stall

## 0. Headline finding — the feature is currently unreachable in the shipped app

Before any polling-loop design matters, note this: `DepthEstimationPanel` (the UI that renders
`Downloading`/`Failed`) is gated by `onDownloadDepthModel != null || onEstimateDepth != null`
(`AnnotationEditorScreen.kt:581`). The **only** production call site of `AnnotationEditorScreen`
is `ScreenRouter.kt:400-411`, and it passes neither parameter — both default to `null`:

```kotlin
// ScreenRouter.kt:400-411 — actual, current
AnnotationEditorScreen(
    viewModel = annotationEditorViewModel,
    imageAnnotation = annotation,
    platformSettings = platformSettings,
    onNavigateBack = { ... },
)
```

Additionally, `SensorModule.monocularDepthEstimator` (`platform/sensor/SensorModule.kt:83`)
defaults to `NoOpMonocularDepthEstimator()` and is **never reassigned** to
`OnnxMonocularDepthEstimator` anywhere in `androidMain` (confirmed via repo-wide grep — the only
match for `monocularDepthEstimator =` is inside a `SensorModule.kt` doc-comment example, not real
init code). There is no `AnnotationEditorActivity` or other androidMain call site either.

**Consequence:** on current `main`, the depth-model download panel never renders, and
`DepthModelDownloader` is never instantiated in the running app. The backlog screenshot must
predate a refactor, or come from a build with local/experimental wiring that was never merged.
Either way, **the Phase 3 plan needs a wiring task** (assign `SensorModule.monocularDepthEstimator`
on Android startup, pass `onDownloadDepthModel`/`onEstimateDepth` through `ScreenRouter`, bridge
`DepthModelDownloader.ModelState` → `DepthModelUiState`) or the progress-polling fix will be
correct but untestable/unshippable from the UI. This isn't listed in requirements.md's Non-Goals,
so flag it explicitly rather than silently descoping it.

No existing tests reference `DepthModelDownloader` or `DepthEstimationCoordinator`
(`find ... -iname "*DepthModelDownloader*Test*"` → empty) — this is greenfield test territory.

## 1. Data flow, traced file:line

1. `ScreenRouter.kt:373-378` — `AnnotationEditorViewModel` is created via
   `remember(imageAnnotationUuid) { AnnotationEditorViewModel(...) }`. It is **not** an
   `androidx.lifecycle.ViewModel` — it's a plain class wrapped in Compose `remember`, so it is
   torn down and recreated whenever `imageAnnotationUuid` changes, and (per Compose semantics)
   is generally **not** preserved across process death, and is preserved across simple
   recomposition but is vulnerable to loss on Activity recreation unless the host uses
   `rememberSaveable`-backed retention (it doesn't — this is a plain `remember`).
2. `ScreenRouter.kt:379-381` — `DisposableEffect` calls `annotationEditorViewModel.close()` on
   dispose, which cascades to `depthCoordinator.close()` (`AnnotationEditorViewModel.kt:919-922`),
   which cancels `DepthEstimationCoordinator`'s internal `scope` (`DepthEstimationCoordinator.kt:117`).
3. `AnnotationEditorViewModel.kt:213-218` — the ViewModel constructs its own
   `DepthEstimationCoordinator` in a field initializer (not lazily) — one per ViewModel instance.
4. `AnnotationEditorViewModel.kt:226-237` (`init` block) — a coroutine on the ViewModel's own
   `scope` collects `depthCoordinator.state` and merges `depthModelUiState`,
   `isDepthInferenceRunning`, `depthMap`, `depthEstimationError` into the unified
   `AnnotationEditorState` exposed as `state: StateFlow<AnnotationEditorState>`.
5. `AnnotationEditorScreen.kt:151` — the composable collects `viewModel.state` and (if wired)
   would pass `state.depthModelUiState` into `DepthEstimationPanel` at line 583.
6. **The missing link**: nothing calls `AnnotationEditorViewModel.updateDepthModelUiState(uiState)`
   (`AnnotationEditorViewModel.kt:890-892`, which forwards to
   `depthCoordinator.updateDepthModelUiState`, `DepthEstimationCoordinator.kt:54-56`) in response
   to `DepthModelDownloader.modelState` (`DepthModelDownloader.kt:36-37`) changes. The doc comments
   at `AnnotationEditorScreen.kt:138-140` and `AnnotationEditorViewModel.kt:110-113` describe the
   intended wiring ("Called from the Android entry point... when
   `DepthModelDownloader.modelState` emits a new value") but no such call exists in the codebase.
   Whoever wires this up would need a `LaunchedEffect` (or similar) on the Android side collecting
   `OnnxMonocularDepthEstimator.modelState` and mapping `DepthModelDownloader.ModelState` →
   `DepthModelUiState` (they are structurally identical sealed interfaces — a 1:1 `when` mapper is
   the natural translation function, doesn't exist yet).
7. `downloadModel()` itself (`DepthModelDownloader.kt:53-127`) is likewise never called from
   `onDownloadDepthModel` today, because `onDownloadDepthModel` is never passed a non-null lambda.

## 2. Object lifetime — why this matters for the polling design

- `AnnotationEditorViewModel` + `DepthEstimationCoordinator`: **per-screen-instance**, torn down
  on navigate-away (`close()` cascades and cancels coroutines) and recreated per
  `imageAnnotationUuid`. Their scopes are correctly self-owned per the repo's
  `rememberCoroutineScope` rule (`CoroutineScope(SupervisorJob() + Dispatchers.Default)`), so no
  violation there.
- `DepthModelDownloader`: today, instantiated **fresh** inside `OnnxMonocularDepthEstimator`'s
  constructor (`val downloader: DepthModelDownloader = DepthModelDownloader(context)`,
  `OnnxMonocularDepthEstimator.kt:46`). `OnnxMonocularDepthEstimator` itself is only ever
  meant to become a **process-lifetime singleton** by being assigned to
  `SensorModule.monocularDepthEstimator` (a `@Volatile var` on a Kotlin `object`,
  `SensorModule.kt:83`) — that's the pattern used by every other provider on `SensorModule`
  (`cameraProvider`, `motionSensorProvider`, `depthSensorProvider`). Once wired correctly, this
  makes `DepthModelDownloader` outlive any individual `AnnotationEditorViewModel`/screen instance,
  for the lifetime of the process — this is the load-bearing property that must guide the fix.
- Implication: **`ModelState` progress does NOT need to survive process death** — no
  requirement or existing pattern in this codebase persists that. It **does** need to survive
  ViewModel/screen recreation (config change, back-then-forward navigation) *within the same
  process*, which falls out for free once `SensorModule.monocularDepthEstimator` is a real
  singleton and the UI layer re-subscribes to `downloader.modelState` on each screen mount
  (`AC5` — "leaving the screen mid-download and returning doesn't permanently strand the user").

## 3. Where should the polling loop live?

**Recommendation: decouple polling into a coroutine owned by `DepthModelDownloader`'s own
internal scope — not inside the `suspendCancellableCoroutine` block in `downloadModel()`.**

Rationale:

1. **Reattachment.** `downloadModel()` is "safe to call multiple times" per its own doc comment,
   and the fast path already handles the case where the model is already on disk
   (`isModelReady()`, lines 55-58). But there's a **second reattachment case the current code
   doesn't handle at all**: a download already in flight (`activeDownloadId != -1L`) when
   `downloadModel()` is called again — e.g. the user backgrounds the screen mid-download and
   returns, and a new `AnnotationEditorViewModel`/coordinator/collector chain calls
   `downloadModel()` again. Today this would enqueue a **second** `DownloadManager.Request` for
   the same destination file, register a second `BroadcastReceiver`, and race. If polling and the
   `DownloadManager.enqueue()` call both live inside the one-shot suspend function, this bug
   persists. If instead `DepthModelDownloader` tracks `activeDownloadId` as authoritative internal
   state and polling runs on its own persistent scope, `downloadModel()` becomes: "if a download
   is already active, just await/observe the existing `modelState` instead of re-enqueuing" —
   solving AC5 structurally rather than accidentally.
2. **Cancellation semantics must be split.** Currently the *only* cancellation path is
   `continuation.invokeOnCancellation` (`DepthModelDownloader.kt:120-125`), which fires whenever
   the *calling coroutine* is cancelled — which happens both (a) when the user hits an explicit
   "Cancel" button (a **desired** removal, per AC3) and (b) whenever
   `AnnotationEditorViewModel.close()`/`DepthEstimationCoordinator.close()` cancels the enclosing
   scope on ordinary navigate-away (an **undesired** removal, since it silently deletes the
   partial download and contradicts AC5's "leaving and returning shouldn't strand the user" —
   today it doesn't strand the user, it just secretly destroys progress). These two cases must be
   distinguished. Moving the `DownloadManager` lifecycle (enqueue/query/remove) and progress
   polling onto `DepthModelDownloader`'s own scope — independent of whatever coroutine happens to
   be suspended in `downloadModel()` at a given moment — means a caller-side cancellation
   (navigate away) no longer touches the download at all; only an explicit new
   `cancelDownload()` method (called from a UI-level Cancel button, AC3) tears it down.
3. **Matches the file's own stated rationale.** The class doc (`DepthModelDownloader.kt:20-27`)
   says `DownloadManager` was chosen specifically because "the system service continues the
   transfer even if the app is backgrounded." The current implementation partially betrays that
   promise: the transfer *file* survives backgrounding, but the *coroutine-based progress signal*
   does not — if the awaiting coroutine's scope is cancelled, `invokeOnCancellation` deletes the
   whole request anyway. Decoupling polling from any specific suspend call is what actually
   delivers on the "survives backgrounding" claim for the *progress-reporting* concern (AC1/AC2),
   as distinct from the *file transfer* concern (which `DownloadManager` already handles).

### Concrete shape

```
DepthModelDownloader(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)  // instance-owned,
                                                                                 // lives as long
                                                                                 // as the singleton
    private var pollingJob: Job? = null
    private var activeDownloadId: Long = -1L   // already exists

    fun downloadModel(): suspend variant unchanged for the "await completion" caller contract,
        BUT:
        - if activeDownloadId != -1L already (in-flight), skip enqueue, just await modelState
          reaching Ready/Failed (e.g. via modelState.first { it is Ready || it is Failed }).
        - else enqueue + start pollingJob on `scope` (NOT inside suspendCancellableCoroutine),
          then await completion the same way.

    private fun startPolling(downloadId, downloadManager) {
        pollingJob = scope.launch {
            var lastBytes = -1L
            var lastProgressAt = now()
            while (isActive) {
                delay(300)  // AC1: 200-500ms
                val (bytes, total, status) = queryProgress(downloadManager, downloadId)
                if (status == STATUS_FAILED) { _modelState.value = Failed; break }
                if (total <= 0) {
                    _modelState.value = Downloading(progress = -1)  // AC2: indeterminate spinner
                } else {
                    _modelState.value = Downloading(progress = (bytes*100/total).toInt())
                }
                if (bytes != lastBytes) { lastBytes = bytes; lastProgressAt = now() }
                else if (now() - lastProgressAt > STALL_TIMEOUT) {  // AC4
                    downloadManager.remove(downloadId)
                    _modelState.value = Failed
                    break
                }
            }
        }
    }

    fun cancelDownload() {  // AC3 — new public method, called from a UI Cancel affordance
        pollingJob?.cancel()
        downloadManager.remove(activeDownloadId)
        activeDownloadId = -1L
        _modelState.value = Absent
    }
}
```

The `BroadcastReceiver` for `ACTION_DOWNLOAD_COMPLETE` can stay as the authoritative "done" signal
(cheap, event-driven, avoids a final race with the polling loop's own terminal read) — polling
only needs to own the *in-flight* percentage, not the completion transition. Both the receiver and
the polling loop write to the same `_modelState`; polling loop should stop itself once it observes
`STATUS_SUCCESSFUL`/`STATUS_FAILED` from its own query, or be explicitly cancelled from the
receiver's callback once it fires, to avoid a dangling coroutine racing a final state write after
completion.

## 4. Why not just `while (isActive) { delay(...) }` inside the existing
   `suspendCancellableCoroutine` block?

That's the minimal-diff option and would satisfy AC1/AC2 in isolation (nothing stops you from
adding a second concurrent `launch` inside that same lambda scope — but note
`suspendCancellableCoroutine`'s lambda body is *not* itself a `CoroutineScope`, so you'd need to
launch on some scope anyway, most naturally the caller's `scope` or a locally-created one). The
reasons to reject it:

- It ties polling's lifetime to the specific `downloadModel()` invocation's continuation, so it's
  automatically killed by the same `invokeOnCancellation` that (per §3.2 above) shouldn't be
  conflated with explicit cancel.
- It does nothing for the reattachment case (§3.1) — a second call to `downloadModel()` while one
  is already in flight would still double-enqueue.
- It keeps the "cancel = delete partial download" behavior as the *only* cancellation semantics,
  which is exactly the AC3 vs. AC5 conflation this bug needs to resolve.

If reattachment and the AC3/AC5 split are explicitly descoped by the Phase 3 plan (e.g. "ship the
simplest fix, accept that navigating away still cancels"), the in-place `while(isActive)+delay`
variant is a legitimate cheaper fallback — call this out as an explicit trade-off decision for
`/sdd:3-plan`, not something to silently decide here.

## 5. State model changes needed

- `DepthModelDownloader.ModelState.Downloading(progress: Int)` already supports `-1` for
  indeterminate (doc comment at line 142: "0–100, or -1 if indeterminate") — the type is
  ready for AC2, only the producer side needs to emit it (when `COLUMN_TOTAL_SIZE_BYTES <= 0`,
  which happens before `DownloadManager` has resolved a Content-Length, e.g. right after enqueue
  or during a redirect — see §6).
- `DepthModelUiState` (`AnnotationEditorViewModel.kt:115-127`) mirrors `ModelState` 1:1 and has
  the same `-1` semantics already documented. No new states are strictly required for AC4 if
  "stalled" transitions straight to `Failed` (as the AC literally specifies: "timeout-driven
  transition to Failed with retry") — but if product wants a UI-distinguishable "stalled, still
  trying" third state, that's a type change touching both sealed interfaces plus the UI `when`
  branch in `DepthEstimationPanel` (`AnnotationEditorScreen.kt:1336-1425`) and the (currently
  nonexistent) mapper between the two hierarchies. Recommend sticking to the AC's literal
  wording (stall → `Failed`) to avoid this ripple.
- `DepthEstimationPanel` (`AnnotationEditorScreen.kt:1382-1397`) needs a Cancel action added to
  the `Downloading` branch (currently only shows a spinner + label, no button) to surface AC3 in
  the UI, and its `onDownload` callback reused/renamed appropriately for retry vs. cancel (two
  distinct actions needed: `onDownload` already exists for Absent/Failed→retry; a new
  `onCancel: () -> Unit` param is needed for the Downloading branch).

## 6. Secondary factor — HF redirect (per requirements.md, "unverified")

`MODEL_URL` (`DepthModelDownloader.kt:158-159`) points at
`https://huggingface.co/.../resolve/main/onnx/model.onnx`, which HF serves via a 302 redirect to
a signed S3/CloudFront URL. `DownloadManager.Request` follows HTTP redirects transparently, but
if the redirect response is slow to resolve or the final URL lacks a `Content-Length` header
until bytes start flowing, `COLUMN_TOTAL_SIZE_BYTES` can read `-1` for a while — which is exactly
the indeterminate case AC2 asks for, and requires no special-casing beyond honoring the polling
loop's `total <= 0` branch. This is not confirmed as "the dominant cause" per requirements.md's
Non-Goals, and no code change to `MODEL_URL` is warranted from this research — the polling design
above already tolerates it.

## Summary of architectural recommendations for Phase 3 planning

1. **Flag the wiring gap** (§0) as an explicit task — without it, none of the ACs are reachable
   or testable in the running app.
2. **Decouple polling from the `suspendCancellableCoroutine` in `downloadModel()`**; own it as a
   `Job` on `DepthModelDownloader`'s own instance-scoped `CoroutineScope`, gated by
   `activeDownloadId` so re-entrant `downloadModel()` calls reattach instead of double-enqueueing.
3. **Split cancellation semantics**: add an explicit `cancelDownload()` public method (AC3) that
   is distinct from — and no longer triggered by — ordinary caller-coroutine cancellation from
   screen navigation (fixes the AC3/AC5 conflation in the current `invokeOnCancellation` handler).
4. **Stall detection** (AC4): track last-changed-bytes timestamp inside the polling loop; on
   timeout, remove the request and transition straight to `Failed` (matches AC4's literal wording,
   avoids adding a third UI state).
5. Add the missing `ModelState` ↔ `DepthModelUiState` mapping function and the
   `updateDepthModelUiState` subscription wiring described (but never implemented) in the existing
   doc comments — this is required regardless of which polling design is chosen.
