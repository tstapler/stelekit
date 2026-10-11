# Pitfalls: Polling `DownloadManager` progress + cancel/timeout from Compose/coroutines

Research for the Depth Model Download Stall project. Grounded in a direct read of
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`
(current implementation, lines 53-127) and the UI consumer in
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`
(`DepthModelUiState`, rendered around line 1310-1400) plus
`AnnotationEditorViewModel.kt` (`updateDepthModelUiState`, line 890).

Note: `DepthModelDownloader` is currently only referenced from
`OnnxMonocularDepthEstimator.kt` in `androidMain` — the `onDownloadDepthModel` callback
wiring from the screen down to a real Android call site is not fully traced here; treat
that plumbing as something to verify during planning, not assumed-correct.

## 1. Coroutine leak risks

- **Polling loop must live inside the same `suspendCancellableCoroutine` lambda's
  lifecycle, not a detached `launch`.** If the progress-polling loop is started with a
  bare `CoroutineScope(...).launch { while(true) { poll(); delay(300) } }` instead of
  being driven by (or tied to) the same cancellable continuation, cancelling the outer
  suspend function (e.g., user navigates away) will cancel the continuation but leave the
  polling `Job` running forever — it has no reference back to the continuation's
  cancellation. **Fix pattern:** launch the poll loop as a child coroutine of the same
  scope the `suspendCancellableCoroutine` block can reach into, and cancel it explicitly
  inside `invokeOnCancellation`, or drive polling with `while (isActive)` inside a
  coroutine that is itself cancelled together with the outer call.
- **`suspendCancellableCoroutine` gives you exactly one resume, but polling needs an
  ongoing loop *outside* that continuation.** The clean shape is: `downloadModel()`
  becomes `coroutineScope { launch { pollProgress() }; awaitCompletionViaCallback() }` —
  the poll loop is a sibling coroutine inside a `coroutineScope`, so structured
  concurrency cancels it automatically when the parent function returns/throws/is
  cancelled. Do **not** reach for `GlobalScope.launch` or a manually created
  `CoroutineScope` for the poll loop — per this repo's `CLAUDE.md` scope-ownership rule,
  any scope that outlives the call needs an explicit owner with a
  `CoroutineExceptionHandler`; a poll loop has no good reason to outlive the call that
  started it.
- **This repo's `rememberCoroutineScope()` rule applies at the call site, not inside
  `DepthModelDownloader`.** `DepthModelDownloader` itself has no `remember`-scoped state,
  but whatever Compose call site invokes `downloadModel()` (per
  `AnnotationEditorScreen.kt:139-142`, `onDownloadDepthModel: (() -> Unit)?`) must not
  store the download coroutine's `Job`/scope in a `remember { }`-held object built from
  `rememberCoroutineScope()`. If the screen leaves composition mid-download and the
  polling loop is tied to a `rememberCoroutineScope()` job, the *screen's* cancellation
  will silently kill the poll loop — that's actually desired for cancel-on-navigate-away,
  but only if it also triggers the `invokeOnCancellation` cleanup (unregister receiver +
  `downloadManager.remove()`). If instead the loop is tied to the ViewModel's long-lived
  scope, navigating away will *not* cancel the download (which AC 5 wants — "leaving mid-
  download and returning doesn't strand the user" — so surviving navigation may be the
  actual desired behavior, not a bug; this needs an explicit design decision, not an
  assumption).
- **`BroadcastReceiver` double-unregister:** in the current code, `onReceive` (line 87)
  unregisters unconditionally, and `invokeOnCancellation` (line 121) also unregisters,
  wrapped in `runCatching`. Kotlin's `suspendCancellableCoroutine` contract guarantees
  `invokeOnCancellation` only fires if the continuation is cancelled *before* `resume()`
  is (successfully) called — once `onReceive` calls `continuation.resume(...)`,
  `invokeOnCancellation` will not also fire for that same completion. So on the *happy
  path* there is no double-unregister. The `runCatching` in `invokeOnCancellation` is
  there specifically to guard the race where cancellation and the broadcast arrive
  concurrently on different dispatch (the receiver's `onReceive` always runs on the main
  thread since no `Handler` was passed to `registerReceiver`, but the cancellation
  callback can run on whatever thread triggered `job.cancel()`) — without it, a genuine
  `IllegalArgumentException: Receiver not registered` from Android's internal state is
  possible. **When adding polling, keep this `runCatching` guard and add an equivalent
  one anywhere else the receiver might be unregistered** (e.g., in a timeout handler that
  fires the "Failed" transition and tears down the receiver independently of both the
  completion broadcast and cancellation).
- **New leak surface if a timeout is added as a separate `delay()`-based coroutine
  racing the broadcast:** a common but buggy pattern is
  `select { onReceive { ... }; onTimeout(TIMEOUT_MS) { ... } }`-style manual racing done
  with two independent `launch`es and a `CompletableDeferred`. If the timeout coroutine
  fires and resumes the continuation with `Failed`, but the `BroadcastReceiver` is not
  also unregistered at that same moment, the receiver stays registered — a leaked
  registration that will still fire on a later matching broadcast (which no longer has a
  waiting continuation to resume, but will try to call `continuation.resume()` a second
  time on an already-completed continuation, throwing `IllegalStateException: Already
  resumed`). **Every exit path (success, failure, cancellation, timeout) must
  unregister the receiver exactly once and must guard `continuation.resume()` /
  `resumeWithException()` against being called twice** (e.g., via
  `continuation.tryResume()` + `completeResume()`, or a `CompletableDeferred` /
  `AtomicBoolean` guard flag).

## 2. Battery / main-thread polling cadence

- 200-500ms is reasonable for a **foreground, visible progress UI** — this is the same
  order of magnitude Android's own `ProgressBar`/`Notification` progress updates use, and
  well above the ~16ms frame budget so it won't cause jank by itself. The actual cost is
  the `ContentProvider` query to `DownloadManager`'s backing DB on every tick (see below),
  not the coroutine `delay()` itself.
- **Polling must pause when the screen isn't visible**, both for battery and because a
  `Cursor` query against `DownloadManager`'s provider from a screen no longer on screen is
  wasted work. Since this is Android-only code, the cleanest anchor here is
  `Lifecycle.State` via `repeatOnLifecycle(Lifecycle.State.STARTED)` at the collection
  site (screen), or — since polling itself lives in `DepthModelDownloader`, a platform
  class with no `Lifecycle` reference — expose the poll loop as a plain coroutine that
  the *caller* drives, and let the Compose call site gate collection with
  `LifecycleEventObserver`/`repeatOnLifecycle` rather than trying to plumb Android
  lifecycle into `DepthModelDownloader` itself (which should stay lifecycle-agnostic per
  the existing architecture — it is instantiated from `androidMain` platform code, not a
  Composable).
- **Do not poll on the main thread synchronously.** `DownloadManager.query()` returns a
  `Cursor` backed by a `ContentProvider` binder call — it is I/O bound (SQLite query) and
  should not run on `Dispatchers.Main`. Route the poll's `downloadManager.query(...)` call
  through `Dispatchers.IO` (this repo's `PlatformDispatcher.DB` is for the app's own
  SQLDelight database, not general Android system-provider I/O — `PlatformDispatcher.IO`
  is the correct one per this repo's dispatcher matrix in `CLAUDE.md`). A tight 200ms
  loop querying the provider on the main thread repeatedly is a plausible source of
  jank/ANR risk under load, distinct from the coroutine `delay()` itself being cheap.
- Battery: DownloadManager's own notification updates already happen independently of
  app-side polling (it's a system service with its own progress notification via
  `setNotificationVisibility(VISIBILITY_VISIBLE)`, already set at line 71) — so the
  app-side poll doesn't affect whether the OS is "actively transferring," it only affects
  how often *this process* wakes up to check `Cursor` state. 200-500ms while the screen is
  foregrounded is fine; the risk is solely about not doing it once backgrounded/Doze
  (see §5).

## 3. Query-cursor resource leaks in a polling loop

- The existing code does `cursor?.use { c -> ... }` correctly, but only **once**, at
  broadcast-completion time (lines 90-97). A polling loop calling
  `downloadManager.query(query)` every 200-500ms must wrap **every single call** in
  `.use { }` (or explicit `try/finally { cursor.close() }`). Missing this on any iteration
  leaks a `Cursor`/binder object per tick — over a ~100MB download at, say, 300-500KB/s on
  a slow connection, that's minutes of ticks × unclosed cursors, i.e., hundreds of leaked
  cursors, which will eventually trigger `CursorLeak` `StrictMode` warnings or genuine
  binder/FD exhaustion.
- **Re-create `DownloadManager.Query()` per iteration** — it's a lightweight builder
  object, cheap to allocate, and must be re-filtered by ID each time; there's no query
  object to reuse across polls (this is a non-issue, just noting it so nobody tries to
  "optimize" by hoisting the `Query` out of the loop and reusing a stale query state).
- **Column index lookups (`getColumnIndex`) should be re-resolved per cursor**, not
  cached across polls — cursor column ordering is stable for a given query shape here so
  caching the int index is technically safe, but `getColumnIndex` returning `-1` (column
  not found) is unguarded in the existing code (line 94: `c.getInt(statusCol)` with no
  check that `statusCol != -1`). Same risk applies to `COLUMN_BYTES_DOWNLOADED_SO_FAR` /
  `COLUMN_TOTAL_SIZE_BYTES` in a new polling query — an unguarded `-1` index passed to
  `Cursor.getInt`/`getLong` throws `CursorIndexOutOfBoundsException`, crashing the poll
  loop (and, if uncaught, per this repo's `CLAUDE.md` rule on uncaught `Throwable`s in
  coroutines, potentially killing the whole app process on Android since there's no
  documented `CoroutineExceptionHandler` around this scope currently).
- **`COLUMN_TOTAL_SIZE_BYTES` can legitimately be `-1`** (Android's documented value for
  "size unknown yet," e.g., before the server has responded with `Content-Length`, or for
  chunked-encoding transfers) — this must be treated as "indeterminate," matching AC 2
  ("indeterminate total size falls back to a spinner"), not divided-by to compute a
  percentage (would throw `ArithmeticException` or produce a garbage negative percentage).

## 4. Race conditions

- **Completion-vs-poll race:** yes, it's entirely possible (and likely on a fast
  connection or small remaining chunk) for the broadcast to fire between two poll ticks.
  In that case the UI can show `Downloading(progress = 97)` then jump straight to
  `Ready` without ever showing 100% — this is not a correctness bug (the terminal state
  is still correct) but is a minor UX inconsistency worth deciding on deliberately: either
  accept the jump, or have the completion handler briefly emit `Downloading(100)` before
  `Ready` for visual continuity. Not a correctness requirement, just a product decision.
- **Reverse race — poll reads `STATUS_SUCCESSFUL` from the cursor microseconds before the
  broadcast fires and unregisters/resumes:** if the poll loop's own query reports success
  first, the loop needs a defined contract for whether it or the `BroadcastReceiver` is
  authoritative for triggering the terminal-state transition and continuation resume. If
  both paths race to call `continuation.resume()`, that throws
  `IllegalStateException: Already resumed` unless guarded (same fix as §1: single-resume
  guard). **Recommendation:** keep the `BroadcastReceiver` as the sole authority for
  terminal transitions (`Ready`/`Failed`); the poll loop should only ever emit
  intermediate `Downloading(progress)` updates and should stop polling (but not resume
  the continuation itself) the moment it observes `STATUS_SUCCESSFUL` or
  `STATUS_FAILED`, waiting for the broadcast for the actual terminal signal — or,
  alternatively, make the poll loop capable of resolving completion itself (some devices
  are documented to delay or drop the `ACTION_DOWNLOAD_COMPLETE` broadcast, especially
  under Doze/battery-optimization — see §5) and have it call the *same* single-resume-
  guarded completion function the receiver uses, so whichever fires first wins safely and
  the other becomes a no-op.
- **Stale `activeDownloadId` from a concurrent second `downloadModel()` call:** confirmed
  bug-shaped risk. `downloadModel()` is `Safe to call multiple times` per its own doc
  comment (line 51), but nothing in the current implementation actually de-duplicates
  concurrent in-flight calls. If `downloadModel()` is invoked twice before the first
  completes (e.g., double-tap on a slow UI, or a recomposition re-triggering the download
  callback), the code will: enqueue a *second* real `DownloadManager` request for the same
  ~100MB file, overwrite the class-level `activeDownloadId` (line 77) with the second
  call's ID (stranding the first call's ID from any future cancel-by-`activeDownloadId`
  logic), and register two independent `BroadcastReceiver`s — each correctly scoped to
  its own captured local `downloadId`, so each will resume its own continuation
  correctly and isn't itself a resume-twice bug, but the user now has two ~100MB
  transfers running, only one of which is cancellable via a UI wired to
  `activeDownloadId`. This needs an explicit guard: check `activeDownloadId != -1L` (or a
  dedicated `Mutex`) at the top of `downloadModel()` and either return the existing
  in-flight `Deferred`/attach to the existing state, or reject the second call outright.
  This is exactly the class of bug the repo's `DatabaseWriteActor` pattern exists to
  prevent elsewhere (serialize concurrent operations through a single owned actor/mutex
  rather than a bare mutable field) — worth mirroring that shape here rather than
  inventing a new one.
- **Cancel-while-poll-in-flight:** if cancel is triggered (user taps cancel, or
  `invokeOnCancellation` fires from scope cancellation) while a poll's `downloadManager
  .query()` call is mid-flight (blocked on the binder call), `downloadManager.remove
  (downloadId)` racing with an in-progress `query()` for the same ID is documented-safe
  at the OS level (the provider handles concurrent access), but the *poll loop's own
  coroutine* must itself be cancelled/joined before or as part of the cleanup in
  `invokeOnCancellation`, otherwise it can complete one more tick and call
  `_modelState.value = Downloading(...)` *after* `invokeOnCancellation` already set
  `_modelState.value = Absent` — a last-write-wins UI flicker/regression where a stale
  "Downloading" state briefly overwrites "Absent" post-cancel. Cancel the poll job first
  (or make it a structured child of the same `coroutineScope` so cancellation is
  automatic and ordered), *then* set `Absent`.

## 5. Known `DownloadManager` quirks

- **Doze mode / App Standby Buckets:** downloads via `DownloadManager` are generally
  exempted from Doze network restrictions (it's a first-party system service using its
  own scheduling), but on some OEM skins (Samsung, Xiaomi/MIUI, Huawei especially)
  aggressive background-app-killing can still throttle or pause DownloadManager transfers
  for apps not currently foregrounded, and — separately — can kill the **app process**
  hosting the `BroadcastReceiver`/coroutine, which does not stop the underlying transfer
  (DownloadManager itself survives process death, per the existing doc comment at line
  23-24) but does mean the `suspendCancellableCoroutine` waiting for that broadcast is
  gone, along with its poll loop; on next app launch, `isModelReady()`'s size check
  (line 133) is the only recovery path, plus a possible orphaned-but-still-running
  `DownloadManager` request nobody is listening to. AC 5 ("leaving the screen mid-
  download and returning doesn't permanently strand the user") should specifically cover
  this "process died mid-download" case, not just "screen navigated away within the same
  process" — on relaunch, code should re-query `DownloadManager` for a still-active
  request by a persisted download ID (e.g., in `SharedPreferences`/DataStore) rather than
  assuming `Absent` and re-enqueueing a duplicate download.
- **`setAllowedOverRoaming(false)`** (line 73): if the device is roaming, the request is
  silently queued but never starts — `DownloadManager` gives no error/callback for this,
  it just sits in `STATUS_PENDING` indefinitely. This is indistinguishable, from the
  app's perspective, from "slow network" unless the poll loop specifically checks
  `COLUMN_STATUS == STATUS_PENDING` combined with `COLUMN_REASON` and surfaces a distinct
  message — otherwise this manifests as exactly the bug being fixed (stuck progress,
  never advances) but for a different, roaming-specific root cause. Given requirements
  explicitly flag "unverified secondary factor: HF URL redirect behavior" but not
  roaming, this is worth at least a status/reason check in the poll loop's diagnostic
  path even if not surfaced as distinct UI copy.
- **`STATUS_PAUSED`** with `COLUMN_REASON` values like `PAUSED_WAITING_FOR_NETWORK`,
  `PAUSED_WAITING_TO_RETRY`, `PAUSED_QUEUED_FOR_WIFI` are all legitimate non-error states
  the poll loop will observe — a naive timeout-to-`Failed` after N seconds of "no progress
  bytes change" would incorrectly fail a download that's correctly waiting for WiFi
  (e.g., `setAllowedOverMetered(true)` is already set at line 72, so this specific case
  may be moot, but `PAUSED_WAITING_FOR_NETWORK` for a temporary connectivity drop is
  still possible). AC 4's "timeout-driven transition to Failed" should distinguish "no
  network progress because paused-with-a-known-reason" (keep waiting, maybe surface
  "waiting for network") from "genuinely stalled with no explanation" (actually timeout).
- **`POST_NOTIFICATIONS` runtime permission (API 33+):** `DownloadManager`'s own progress
  notification (enabled via `VISIBILITY_VISIBLE` at line 71) requires this permission on
  API 33+ to actually display; if not granted, the **download itself still proceeds
  fine** (this only affects whether the OS-level notification is shown, not the transfer)
  — so it's not a functional download-stall cause, but it does mean the app cannot assume
  "user sees a system notification with progress" as a fallback UX if the in-app progress
  bar has the bug described in this backlog item; the in-app polling fix is the actual
  and only reliable fix.
- **HTTP redirect handling:** `DownloadManager` does follow HTTP redirects, but has
  historically had bugs/limitations around HTTPS→HTTPS redirects losing headers or
  cross-origin redirects being blocked on some Android versions/OEM builds; Hugging Face's
  `resolve/main` URLs redirect to a CDN (`cdn-lfs.huggingface.co` or similar) with signed
  query-string auth — this is generally fine for `DownloadManager` but is exactly the kind
  of thing that varies by Android version/OEM and is hard to verify without a live device
  test matrix. Per the requirements' own non-goal, this is flagged as unverified/secondary
  and shouldn't block the polling-loop fix, but the poll loop's diagnostic path (status +
  reason columns) is the cheapest way to *observe* whether redirect handling is the actual
  culprit for any user who still reports stalls after the polling fix ships.

## 6. Partial/corrupt file left behind after cancel — `isModelReady()` false positive

- Confirmed real risk given the existing check. `setDestinationUri(Uri.fromFile
  (modelFile))` (line 70) means `DownloadManager` writes directly to the final
  `modelFile` path (not a separate `.tmp` staging path) as bytes arrive. If a download is
  cancelled via `downloadManager.remove(downloadId)` (line 122, existing cancellation
  path), `DownloadManager` is documented to delete the file it was writing to — but this
  is not universally reliable across OEM `DownloadProvider` implementations, and there is
  a window (the removal is asynchronous — `remove()` returns immediately and deletion
  happens on the provider's own thread) where the app could re-check `isModelReady()`
  before the partial file is actually deleted.
- **The `MIN_MODEL_SIZE_BYTES` check (10MB, line 162) is a coarse sanity check, not an
  integrity check** — a partial download that happens to be cancelled/killed at, say,
  15MB into a 100MB transfer would pass `isModelReady()`'s size check and be treated as a
  valid, complete model file. This is a real false-positive risk introduced (or at least
  made more likely) by adding cancel support: today the only way to get a partial file at
  all is a process death mid-download (already possible), but an explicit user-facing
  cancel button (AC 3) makes hitting this window far more common/reachable, since users
  will now routinely cancel at arbitrary progress points including >10MB.
- **Mitigations to consider (flag for planning, not prescribing the exact fix):**
  - After `downloadManager.remove(downloadId)`, explicitly `modelFile.delete()` as a
    belt-and-suspenders step rather than relying solely on `DownloadManager`'s internal
    cleanup — this closes the async-deletion race directly and doesn't depend on
    OEM-specific provider behavior.
  - Alternatively/additionally, download to a temp file (`modelFile.tmp` or into
    `cacheDir`) via `setDestinationUri`, and only `renameTo`/move it into place after the
    `BroadcastReceiver` confirms `STATUS_SUCCESSFUL` — this is the more robust general
    fix (matches the "atomic rename after verified write" pattern used elsewhere for
    file writes in this codebase, e.g., `GraphWriter`) and also incidentally fixes the
    "partial file survives process death" case with no extra code, since `isModelReady()`
    checking the final path would simply never see a partial file. This is a larger
    change than the polling fix itself but is the only approach that closes *all* partial-
    file windows (cancel, process death, crash) rather than just the explicit-cancel one.
  - A stronger integrity check than size-only (checksum/hash pinned to the known model
    file) would also catch this class of bug but is likely out of scope for this backlog
    item unless the temp-file/rename approach is deemed too large a change.
