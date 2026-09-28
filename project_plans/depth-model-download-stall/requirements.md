# Requirements: Depth Model Download Stall

## Source

Backlog item `505fb733-9621-4621-b7fc-27712e36d084`, title "Device model download kinda of
sucks". Description: screenshot showing the download UI "stuck here and never improves."

## Background

The photo-annotation feature (`AnnotationEditorScreen` / `AnnotationEditorViewModel`) uses a
~100 MB Depth Anything V2 ViT-S ONNX model to estimate scale/calibration from a single photo.
On first use, `DepthModelDownloader` (Android-only,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`) enqueues
the download via Android's system `DownloadManager` and exposes a `ModelState` (`Absent` /
`Downloading(progress)` / `Ready` / `Failed`) as a `StateFlow`. `AnnotationEditorScreen.kt`
renders `Downloading` as a spinner plus `"Downloading model… $pct%"`.

## Root Cause (confirmed by direct code read)

1. **Progress never updates during transfer.** `downloadModel()`
   (`DepthModelDownloader.kt:53-127`) sets `_modelState.value = ModelState.Downloading(progress = 0)`
   exactly once, at enqueue time (line 78). The suspend function then does nothing until an
   `ACTION_DOWNLOAD_COMPLETE` broadcast fires — i.e., only on final success or failure
   (lines 81-110). There is no polling of `DownloadManager.Query()` /
   `COLUMN_BYTES_DOWNLOADED_SO_FAR` / `COLUMN_TOTAL_SIZE_BYTES` while the transfer is in flight, so
   `progress` is hardcoded at `0` for the entire ~100 MB download. The UI literally reads
   "Downloading model… 0%" for the whole duration — this is the "stuck, never improves" the user
   is reporting.
2. **No cancel/retry affordance while `Downloading`.** Only the `Failed` state renders a retry
   button (`AnnotationEditorScreen.kt:1399-1409`). If the transfer is genuinely stalled at the OS
   level (no network, Wi-Fi-only constraint pending, `DownloadManager` internal pause), the user
   has no way to see why or intervene — the only escape is leaving the screen, which cancels the
   coroutine via `invokeOnCancellation` (`DepthModelDownloader.kt:120-125`) and silently resets
   state to `Absent`, discarding the in-flight download with no explanation.
3. **Unverified secondary factor:** the Hugging Face `resolve/main` model URL
   (`DepthModelDownloader.kt:158-159`) may redirect in a way `DownloadManager` handles poorly on
   some networks/carriers, contributing to real (not just perceived) stalls. Not confirmed without
   a device repro; out of scope to fix speculatively but worth instrumenting for.

Note: `project_plans/tag-suggestion-model-stall/` (an empty stub) and
`project_plans/tag-suggestion-trigger/` were checked and are unrelated — the tag-suggestion
feature does not download a model. This bug is specific to `DepthModelDownloader` and has no
prior triage.

## Problem Statement

Users attempting to use the photo-annotation scale-estimation feature see a progress indicator
that reports 0% for the entire ~100 MB download, with no way to tell whether the download is
actually progressing, stalled, or dead — and no way to cancel or retry without abandoning the
screen. This reads as broken/frozen even when the underlying transfer may be proceeding normally.

## Acceptance Criteria

1. While a depth-model download is in progress, the UI progress percentage advances
   proportionally to bytes actually downloaded (not fixed at 0%), updated at a reasonable interval
   (e.g. ~every 200-500ms) rather than a single snapshot.
2. If `DownloadManager` reports an indeterminate/unknown total size, the UI falls back to an
   indeterminate spinner without a misleading fixed "0%" label (existing `progress = -1` /
   "Downloading model…" branch already supports this — must be reachable/correct once real
   progress is wired in).
3. A user can cancel an in-progress download from the `Downloading` UI state, and cancellation
   cleanly removes the partial `DownloadManager` request and returns state to `Absent`.
4. A user can distinguish "actively downloading" from "stalled/no progress for N seconds" — at
   minimum, a stalled transfer must not look identical to a healthy one indefinitely (e.g. surface
   a "taking longer than usual" affordance or timeout-driven transition to `Failed` with retry).
5. Leaving the annotation screen mid-download and returning does not permanently strand the user
   in a state where the download can never be resumed or retried (currently: `Absent`, requiring
   the user to start over — acceptable if `downloadModel()` is safely re-callable, but must be
   verified/documented, not just assumed).
6. No regression to the existing fast path (`isModelReady()` short-circuit to `Ready`) or the
   existing `Failed` → retry button flow.

## Non-Goals

- Fixing/replacing the Hugging Face model hosting URL itself (item 3 above) unless investigation
  during implementation finds it to be the dominant cause.
- iOS/desktop/wasm support for this downloader — it is Android-only today; out of scope to port to
  other platforms as part of this fix.
- General `DownloadManager` → OkHttp/Ktor migration — out of scope; the existing choice (survives
  process death) is intentional per the class doc comment.
