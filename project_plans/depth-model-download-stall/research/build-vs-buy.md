# Build vs. Buy — Depth Model Download Stall

## Research question
Should the fix (real progress, cancel affordance, stall detection, screen-leave resilience) be built by extending the existing `DownloadManager`-based `DepthModelDownloader`, or by adopting a library?

## Repo state confirmed
- `kmp/build.gradle.kts` has no existing download-management dependency. `grep -n -i "fetch\|workmanager\|work-runtime\|ktor-client\|okhttp"` shows:
  - `androidx.work:work-runtime-ktx:2.9.1` (line 305) — already present, but used only for periodic background git sync (`// WorkManager — periodic background git sync`), not for file downloads.
  - `io.ktor:ktor-client-*:3.1.3` — already present across common/android/js/ios source sets for the app's general HTTP client (`UrlFetcherJvm` etc.), not currently used for downloads.
  - No `Fetch`/`Fetch2`/`tonyofrancis` reference anywhere in the build.
- `DepthModelDownloader.kt` (androidMain, 165 lines) confirms the requirements doc's root-cause read: `enqueue()` → sets `Downloading(progress = 0)` once → suspends on a `BroadcastReceiver` for `ACTION_DOWNLOAD_COMPLETE` → the terminal `DownloadManager.Query()` (used only to check `COLUMN_STATUS` on completion) is the *only* place the code already touches the `Query()` API. No polling loop exists yet.

## Option 1 — Existing OSS library (Fetch2 / Fetch, WorkManager-based downloader, or Ktor streaming download)

**Fetch (tonyofrancis/Fetch)**
- Apache 2.0, mature API (queue-based, pause/resume/priority), most recent tagged release ~3.4.1.
- Maintenance signal: issue #660 "Please adapt for Android 14" has been open since July 2023 with no resolution — a strong signal of low/no active maintenance heading into 2025-2026 for a library whose whole value proposition is staying current with platform download/foreground-service restrictions.
- Architecturally, Fetch does **not** wrap the system `DownloadManager` — it implements its own OkHttp-backed transfer engine with a local SQLite-backed queue and a foreground service for persistence. Adopting it means swapping the transport away from system `DownloadManager`, which directly contradicts this project's explicit **non-goal**: *"General DownloadManager → OkHttp/Ktor migration (existing choice is intentional: survives process death)."*
- Android-only artifact (Room/Service internals) — no KMP relevance either way since `DepthModelDownloader.kt` already lives in `androidMain` only, but it's still a new third-party dependency with unclear long-term support to pull in for one file.

**WorkManager-based downloader**
- `work-runtime-ktx` is already a dependency (for git sync), so no *new* dependency — but using it here means writing a `CoroutineWorker` that does the actual HTTP transfer itself (via OkHttp/Ktor), plus a foreground-service notification, plus `setProgressAsync`/`WorkInfo` observation plumbing. This is materially more code than the fix warrants and, again, replaces `DownloadManager` as the transport — same non-goal conflict as Fetch.
- WorkManager's persistence-across-process-death story is real but weaker for a single already-in-flight large transfer: it re-runs the *worker* (restarting or resuming the HTTP request depending on implementation effort), whereas the system `DownloadManager` service itself keeps the socket-level transfer going independent of the app process entirely. Reproducing that guarantee via WorkManager+OkHttp is nontrivial extra engineering, not a wash.

**Ktor client streaming download with manual progress callback**
- Ktor is already a dependency for other purposes. `onDownload` progress callbacks are straightforward to wire. But this is explicitly the "OkHttp/Ktor migration" the requirements doc rules out as a non-goal, and it forfeits the process-death survival the team already deliberately chose `DownloadManager` for.

**Verdict: Not recommended.** All three variants of "pull in a library/alternate transport" either (a) show real maintenance risk (Fetch) or (b) require reimplementing the HTTP transfer path in a way that conflicts with the project's own stated non-goal of moving off `DownloadManager`. None of them is a drop-in *progress-reporting wrapper* around the system `DownloadManager` the code already uses — that combination doesn't exist as a maintained library because it's a thin, stable API most teams just poll directly (see Option 2).

## Option 2 — Manual `DownloadManager.Query()` polling (no new dependency)

- This is a very well-established, widely documented Android pattern (multiple gists/blog posts confirmed in current search: "Observe Download manager progress using LiveData and Coroutine" and equivalents), going back to the earliest `DownloadManager` API (added API 9 / Android 2.3) and essentially unchanged since — it is one of the most stable corners of the Android SDK.
- The code already touches `DownloadManager.Query()` in the completion handler (line 90-97 of the current file), so this is incremental to a pattern already present in the codebase, not a new integration surface.
- Known correctness traps, all well-documented and mechanically avoidable in ~15-30 lines:
  - Cursor queries are blocking — must run the polling loop on `Dispatchers.IO` (or per this repo's convention, whatever dispatcher is appropriate for non-DB, non-UI blocking I/O), not on the coroutine that owns `_modelState`.
  - `COLUMN_TOTAL_SIZE_BYTES` can legitimately be `-1` if the server response omits `Content-Length` — this is exactly AC #2's "indeterminate fallback," so the trap is actually a requirement, not a surprise.
  - The polling loop must stop on terminal states (`STATUS_SUCCESSFUL`/`STATUS_FAILED`) or on cursor-row-absence (row removed via `downloadManager.remove()`), otherwise it spins forever — straightforward `while` + early-return.
  - Must be cancellable together with the existing `suspendCancellableCoroutine`/`invokeOnCancellation` cleanup already in place (lines 120-125), so cancellation semantics are additive, not a redesign.
  - Stall detection (AC #4) is a straightforward "no byte-count progress for N polls → timeout → Failed" on top of the same loop; no extra API needed.
- Requires zero new dependencies, zero new licenses to track, and stays entirely inside the file already responsible for this behavior.

**Verdict: Recommended.**

## Option 3 — SaaS/managed
Not applicable. This is a local system-API file download of a static, self-hosted-alongside-the-app ONNX model artifact from Hugging Face — there is no managed service surface to buy (no upload/transform/CDN-selection concern), just a client-side transfer-progress problem.

## Option 4 — LLM-generated bespoke polling loop vs. battle-tested library
- The task is "poll a frozen, ~15-year-stable system API on a timer and map 4-5 known cursor columns to a sealed state" — this is squarely in the class of code an LLM (or any competent engineer) can write correctly against the documented `DownloadManager.Query` contract, and it is easy to unit-test by injecting a fake/mock `DownloadManager` (the existing `DepthModelDownloader` already isn't unit-tested against the real Android service — tests would mock the `DownloadManager`/cursor, same as any other approach).
- Correctness risk of the bespoke loop is low and bounded: the failure modes (forgetting to stop polling, not handling `-1` total size, wrong dispatcher) are exactly the AC's already itemized, so implementing the ACs *is* implementing the risk mitigations.
- Correctness/maintenance risk of adopting a full download-management library is arguably *higher* here: it means depending on a third party's queue/DB/service lifecycle model for a single one-shot download, absorbing its own bug surface and update cadence (see Fetch's stale Android 14 issue), and in every case examined it requires abandoning the `DownloadManager` transport this repo intentionally chose for process-death survival.
- The explicit non-goal ("General DownloadManager → OkHttp/Ktor migration ... existing choice is intentional") does rule out essentially every mainstream Android download library, because none of them are thin wrappers over the system `DownloadManager` — they all bring their own OkHttp/Ktor-based transfer engine as their core value proposition. A library that *only* added polling/progress on top of system `DownloadManager` without changing the transport isn't a distinct product category that exists in maintained form; it's exactly the 15-30 line loop in Option 2.

## Final recommendation
**Build, don't buy.** Extend `DepthModelDownloader.kt` with a manual `DownloadManager.Query()` polling coroutine (200-500ms interval per AC #1), reading `COLUMN_BYTES_DOWNLOADED_SO_FAR`/`COLUMN_TOTAL_SIZE_BYTES` for real progress, falling back to indeterminate when total size is `-1` (AC #2), adding a stall timer that transitions to `Failed` after N polls with no byte-count movement (AC #4), and wiring a cancel affordance that calls `downloadManager.remove(downloadId)` (reusing the existing `invokeOnCancellation` cleanup path, AC #3/#5). No new dependency, no license/maintenance risk, no conflict with the repo's documented process-death-survival rationale for `DownloadManager`. Pulling in Fetch, a WorkManager+OkHttp rewrite, or a Ktor-based rewrite is not recommended — each either shows real staleness risk or forces exactly the transport migration the requirements doc rules out as a non-goal.
