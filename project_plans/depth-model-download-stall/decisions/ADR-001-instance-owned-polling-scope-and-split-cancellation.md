# ADR-001: Instance-owned polling scope on `DepthModelDownloader`, with cancellation split from navigate-away

## Status
Accepted

## Context

`DepthModelDownloader.downloadModel()` currently wraps the entire download lifecycle — enqueue,
await completion, cleanup on cancellation — in a single `suspendCancellableCoroutine`. The only
cancellation path is `continuation.invokeOnCancellation`, which fires whenever the *calling
coroutine* is cancelled. That happens for two very different reasons today, with identical
effect:

1. The user taps an explicit "Cancel" button (desired: destroy the partial download — AC3).
2. The user simply navigates away from `AnnotationEditorScreen`, which cancels
   `AnnotationEditorViewModel`'s scope, which cascades to `DepthEstimationCoordinator.close()`
   (desired: the download should keep going in the background per AC5 — "leaving the screen
   mid-download and returning doesn't permanently strand the user" — and per the class's own doc
   comment claiming `DownloadManager` was chosen specifically because transfers "survive process
   death"/backgrounding).

Additionally, nothing in the current code prevents a second `downloadModel()` call from
double-enqueueing a fresh `DownloadManager.Request` at the same destination path while a first
one is still in flight (confirmed real risk — `activeDownloadId` is written but never checked at
entry). This matters concretely for AC5: navigating back to `AnnotationEditorScreen` after
leaving mid-download recreates `AnnotationEditorViewModel` (and, before this fix, would call
`downloadModel()` again), which today would race two transfers writing the same file.

## Decision

`DepthModelDownloader` gets its own instance-owned `CoroutineScope` (`SupervisorJob() +
PlatformDispatcher.IO + CoroutineExceptionHandler`), matching the existing repo idiom for
long-lived platform classes (`GraphFileWatcher.kt:115-133`, `SafChangeDetector.kt:185-190`) —
legitimate here because `DepthModelDownloader` is, once correctly wired, a process-lifetime
singleton (owned by `OnnxMonocularDepthEstimator`, assigned once to
`SensorModule.monocularDepthEstimator` at `SteleKitApplication.onCreate()`), not a
composition-scoped or `remember`-held object. This does **not** violate `CLAUDE.md`'s
`rememberCoroutineScope` rule, which concerns scopes that are cancelled when a composable leaves
composition — this scope is never derived from one.

The progress-polling loop (`startPolling`) is launched as a sibling `Job` on this scope, not
nested inside `downloadModel()`'s `suspendCancellableCoroutine`. `downloadModel()` itself gains a
reattachment guard: if `activeDownloadId != -1L` when called, it does not enqueue a second
request — it awaits the existing transfer's terminal state via
`modelState.first { it is Ready || it is Failed }`.

Cancellation is split into two distinct triggers:
- `cancelDownload()` — a new public method, called only from an explicit UI Cancel action. Tears
  down the polling job, removes the `DownloadManager` request, deletes the partial file, and
  resets state to `Absent`.
- Ordinary caller-coroutine cancellation (navigating away) — after this change, no longer tied to
  the download's lifecycle at all, because the polling loop and the `DownloadManager` request now
  live on `DepthModelDownloader`'s own scope, independent of whichever `AnnotationEditorViewModel`
  instance happens to be awaiting `downloadModel()`'s suspend call at a given moment.

## Consequences

- AC3 (explicit cancel) and AC5 (navigate-away must not cancel) are structurally distinct
  code paths, not two effects of the same trigger — eliminates the class of bug where a fix for
  one silently breaks the other.
- The double-enqueue bug is fixed as a side effect of the reattachment guard, not a separate
  patch.
- `DepthModelDownloader` now needs a `CoroutineExceptionHandler` it didn't have before, since an
  uncaught `Throwable` on a long-lived Android scope kills the process (`CLAUDE.md`). This is a
  new piece of infrastructure this class didn't previously need, given its prior lifecycle was
  entirely borrowed from the caller's `suspendCancellableCoroutine`.
- Progress state does not need to survive process death (no requirement asks for this, and no
  existing pattern in this codebase persists `activeDownloadId` across restarts) — only within-
  process navigation, which this design handles for free once the scope is instance-owned.
