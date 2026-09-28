# Research: Similar Features, Edge Cases, Unstated Needs (Agent 2 — Features)

## 1. Precedent patterns in this codebase for "download/transfer with progress"

### a) Git clone progress callback — `GitSetupScreen.kt` / `onCloneAndAdd`
`GitSetupScreen.kt:105` threads a callback through the suspend clone call:
```kotlin
onCloneAndAdd: (suspend (url, localPath, auth, onProgress: (String) -> Unit) -> Either<DomainError.GitError, String>)?
```
The screen holds `cloneInProgress: Boolean` + `cloneProgress: String` as local Compose state
(`GitSetupScreen.kt:150-151`), updated live inside the lambda (`cloneProgress = progress`,
line 388) as JGit's `ProgressMonitor` reports steps. This is the codebase's one existing
precedent for **live, incremental progress reporting** from a long suspend operation up to
Compose UI — a lambda callback rather than a `StateFlow`, but the same shape the fix needs
(periodic updates, not a single fire-and-forget set). `DepthModelDownloader.downloadModel()`
by contrast sets `Downloading(progress = 0)` exactly once and never again until terminal state —
this is the confirmed root cause and this file is the closest in-repo model of "how we've done
it before" for the fix.

### b) WorkManager background sync — `WorkManagerSyncScheduler.kt` / `GitSyncWorker`
Precedent for **process-death survival and re-attachment**, which the current
`DepthModelDownloader` design lacks:
- `Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)` — precedent for a
  network-type gate (relevant to a possible Wi-Fi-only preference for the ~100 MB model).
- `GitSyncServiceRegistry` (in-memory map) + a **"slow path"** in `GitSyncWorker.doWork()`:
  when the registered service is gone (process was killed and WorkManager restarted the app in
  the background), the worker reconstructs a minimal `DriverFactory`/`GitRepository` from disk
  state rather than assuming the in-memory object graph survived. This is exactly the shape of
  fix needed for "app killed mid-download, can we re-attach by ID" — `DepthModelDownloader`
  should persist `activeDownloadId` (e.g. `SharedPreferences` or a small file) so that on
  re-construction it can `DownloadManager.Query().setFilterById(persistedId)` and resume
  observing/polling rather than silently reporting `Absent`.

### c) `DriveExportService.kt` — progress acknowledged but not implemented
Only a doc comment ("For files > 5 MB: callers should show progress UI") — no actual
byte-level progress plumbing exists here to borrow from. Not a strong precedent.

### d) No precedent in `AssetRepository` / `AssetPipelineService` / `SqlDelightAssetRepository`
These deal with **local** file import/dedup/hashing of pasted or picked images, never a network
download — no progress-percentage or cancellation pattern to reuse from that layer.

### e) `WorkManagerSyncScheduler`'s poll-interval model (`GitConfig.pollIntervalMinutes`)
Shows the codebase already has a concept of a configurable poll interval persisted in
`SteleDatabase.sq` (`git_config.poll_interval_minutes`) — useful precedent if the fix wants a
DB-backed or settings-backed polling interval rather than a hardcoded constant.

**Conclusion:** there is no existing "big model/asset download with progress" feature to lift
wholesale. The fix will be closest to combining (a)'s "surface periodic progress to Compose
state" shape with (b)'s "persist an ID so a killed-and-restarted process can re-attach" shape —
both already-established patterns in this codebase, so the fix should follow them rather than
invent a third idiom.

## 2. Edge cases beyond the explicit ACs

1. **App killed mid-download, then restarted.** `DownloadManager`'s own transfer *does* survive
   process death (it's a system service) — that part is fine today. But `activeDownloadId` is a
   plain `private var` on `DepthModelDownloader` (`DepthModelDownloader.kt:40`), never
   persisted. On restart, `resolveInitialState()` (line 154) only checks `isModelReady()`
   (file exists AND > 10 MB) — it has no way to discover the in-flight `downloadId` and re-attach.
   Confirmed gap: user sees `Absent` (must tap download again) even though Android is still
   silently finishing the previous download to the same destination path, which will then race
   the second enqueue writing to the same file.
2. **Concurrent `downloadModel()` calls are not guarded.** `activeDownloadId` is written
   (line 77) but **never checked at entry** — nothing short-circuits a second call while one is
   already in flight. Two callers (e.g., recomposition double-tap, or two `AnnotationEditorScreen`
   instances sharing the process-wide `SensorModule.monocularDepthEstimator` singleton) would
   each `enqueue()` a fresh `DownloadManager.Request` pointed at the **same**
   `setDestinationUri(Uri.fromFile(modelFile))` — two transfers writing the same file
   concurrently, wasted bandwidth, and a real corruption risk. The fix must add an
   in-flight guard (e.g., if `activeDownloadId != -1L`, return/await the existing coroutine's
   result instead of re-enqueuing).
3. **Low storage mid-download.** Not currently surfaced distinctly — `DownloadManager` would
   fail the transfer (e.g. `STATUS_FAILED`/insufficient space), which the receiver maps to a
   generic `ModelState.Failed` / `"Depth model download failed"` with no reason string. Users
   can't distinguish "no space" from "no network" from "server error" today, and the redesigned
   `Failed` state should carry an optional reason.
4. **Partial/corrupt file left behind on failure.** `isModelReady()`'s only integrity check is
   `length() > 10 MB` (`MIN_MODEL_SIZE_BYTES`, line 162) — a truncated ~100 MB download that
   still exceeds 10 MB (e.g., network cut near the end) would pass the fast-path check on next
   launch and report `Ready`, then fail later at ONNX session load with a confusing error far
   from the download code. On natural failure (not cancellation) the code never explicitly
   deletes `modelFile` — only the `invokeOnCancellation` path calls
   `downloadManager.remove(downloadId)` (which does delete the file per Android docs). The
   `Failed` path should also delete any partial file, and ideally the sanity check should use a
   size range or checksum, not just a lower bound.
5. **User backgrounds the app mid-download.** Not itself a problem — `DownloadManager` continues
   independent of app foreground state — but `_modelState` is a `MutableStateFlow` on the
   `DepthModelDownloader` instance owned by the process-wide `SensorModule` singleton
   (`SensorModule.kt`), so as long as the **process** survives, returning to the screen
   re-observes the same flow correctly. Only process death (edge case 1) breaks this.
6. **Leaving `AnnotationEditorScreen` and returning (AC5), within the same process.** This is
   already handled correctly today because `SensorModule.monocularDepthEstimator` /
   `DepthModelDownloader` is a singleton, not screen-scoped — the `StateFlow` keeps ticking and
   the screen just needs to re-collect it. The stall bug is therefore *not* about losing state on
   navigation; it is purely the missing polling loop. Worth confirming in the design doc so no
   one "fixes" this by adding unnecessary screen-scoped persistence.
7. **Redirect handling from Hugging Face `resolve/main` URL** (flagged as unverified in the
   requirements) — `DownloadManager` is known to sometimes mis-handle HTTP 302 redirects to
   CDN-signed URLs (losing headers, or failing silently) on some OEM Android builds. Worth a
   quick manual repro/log check before ruling it out, since it would also *look like* a stall.
8. **Query cursor lifecycle for polling.** Any polling implementation must `use { }` /close the
   `Cursor` from `DownloadManager.Query()` on every tick (existing one-shot code does this
   correctly at line 92 via `cursor?.use`) — a polling loop that forgets this leaks cursors every
   200-500 ms.
9. **`Downloading` progress must handle `COLUMN_TOTAL_SIZE_BYTES == -1`** (server didn't send
   `Content-Length`) — this is explicitly acknowledged in the existing `ModelState.Downloading`
   doc comment ("progress is 0–100, or -1 if indeterminate", line 142) but the *producer* side
   never emits anything other than a hardcoded `0` today, so the -1/indeterminate branch is
   currently dead code on the write side even though the UI (`AnnotationEditorScreen.kt:1390-1392`)
   already has a read-side branch for it (`if (pct >= 0) ... else "Downloading model…"`). The
   fix's polling loop must actually populate -1 when total size is unknown.

## 3. Unstated user needs beyond the explicit ACs

- **MB downloaded / total, not just a bare percentage.** For a ~100 MB download on possibly slow
  connections, "47%" is less reassuring than "47 MB / 100 MB" — users commonly want the absolute
  numbers to judge remaining time, especially since this bug report is specifically about *not
  trusting* the indicator.
- **Estimated time remaining / throughput indicator**, even coarse (e.g. "~2 min left") — a
  natural next ask once byte-level progress exists, though not in the ACs; worth flagging as a
  stretch rather than silently scope-creeping it in.
- **Not re-downloading on every screen visit** — already effectively satisfied by the singleton
  `SensorModule` + `isModelReady()` fast path (edge case 6 above); should be preserved and
  explicitly covered by a regression test in the redesign (AC6 already calls this out generally).
- **Wi-Fi-only preference for a 100 MB background download** — not in the ACs, and the current
  code explicitly sets `setAllowedOverMetered(true)` (line 72), i.e. today it *will* burn mobile
  data. Given `WorkManagerSyncScheduler` already establishes `NetworkType.CONNECTED` as a
  constraint precedent elsewhere in the app, a "Wi-Fi only" toggle is a plausible unstated want,
  but the requirements doc's Non-Goals section doesn't mention it — flag as an explicit
  scope decision for Phase 3 rather than assuming it in.
- **Visibility into *why* a download failed** (network vs. storage vs. server) rather than a
  single generic `Failed` state — ties into edge case 3, and directly serves "distinguish stalled
  from actively downloading" (AC4) by giving the user actionable next steps instead of just a
  retry button.
- **Confidence that cancel actually frees the ~100 MB of partial download** — AC3 only requires
  state to return to `Absent`; users will also expect the partial bytes to not silently occupy
  storage forever (ties to edge case 4).

## Key files referenced
- `/home/tstapler/Programming/stelekit/kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt` (full file read — root cause + `activeDownloadId`/`ModelState` details)
- `/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorViewModel.kt` (lines 95-127 — `DepthModelUiState` mirror, no `Cancelling`/error-reason variant)
- `/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt` (lines 1305-1410 — `DepthEstimationPanel` rendering, confirms no cancel button on `Downloading`, dead `pct < 0` branch)
- `/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt` (lines 105, 150-151, 357-390 — precedent: lambda-based live progress callback)
- `/home/tstapler/Programming/stelekit/kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt` (full file — precedent: `NetworkType.CONNECTED` constraint, registry + "slow path" re-init after process death)
- `/home/tstapler/Programming/stelekit/kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/OnnxMonocularDepthEstimator.kt` (line 36, 46 — owns the `DepthModelDownloader` instance)
- `/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/sensor/SensorModule.kt` (confirms process-wide singleton wiring, so screen navigation doesn't lose download state — only process death does)
- `/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/google/DriveExportService.kt` (line 98 — progress acknowledged in comment only, no implementation to borrow)
