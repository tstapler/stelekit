# Architecture Review: depth-model-download-stall
**Date**: 2026-07-28
**Verdict**: BLOCKED

## Constitution Violations
- `docs/adr/ADR-000-architecture-constitution.md` does not exist in this repository — no
  constitution to check against. (CLAUDE.md itself documents several hard, project-specific
  rules — e.g. "uncaught coroutine Throwables kill the process on Android," "Platform abstracts"
  is the lowest architectural layer — that this plan violates in places; those are captured under
  Blockers below since they aren't a formal ADR-000 but are still documented, enforced project
  rules.)

## Blockers

- [ ] **ADR-001 / Task 2.1.3e (`invokeOnCancellation`) — the cancellation split the ADR promises
  is not actually implemented; AC5 fails as coded.** ADR-001's Decision section states ordinary
  caller-coroutine cancellation (navigate-away) is, after this change, "no longer tied to the
  download's lifecycle at all, because the polling loop and the `DownloadManager` request now
  live on `DepthModelDownloader`'s own scope." But Phase 2's concrete tasks never actually move
  the enqueue call or `BroadcastReceiver` registration off of `downloadModel()`'s
  `suspendCancellableCoroutine` — only the polling loop (Story 2.1.3) moves to `scope`. Task
  2.1.3e's literal instruction is to *keep* the existing `downloadManager.remove(downloadId)` and
  `_modelState.value = ModelState.Absent` inside `invokeOnCancellation`, adding only
  `pollingJob?.cancel()` alongside them. Since Task 1.2.1b calls `downloadModel()` from a
  transient `rememberCoroutineScope()` tied to `AnnotationEditorScreen`'s composition (the task's
  own note says so, then incorrectly asserts "the actual work now lives on
  `DepthModelDownloader`'s own instance-owned scope after Phase 2, not on this transient one" —
  false for the enqueue+receiver, true only for the polling loop), navigating away still cancels
  that coroutine, still fires `invokeOnCancellation`, still calls `downloadManager.remove()`, and
  still resets state to `Absent` — reproducing exactly the bug requirements.md's item 2 describes
  ("leaving the screen... discards the in-flight download with no explanation"). A second,
  compounding effect: `context.unregisterReceiver(receiver)` also fires on navigate-away, so even
  if `remove()` were fixed, no receiver would remain to observe `ACTION_DOWNLOAD_COMPLETE` when
  the transfer actually finishes in the background — the polling loop deliberately defers the
  terminal transition to "BroadcastReceiver remains sole authority" (Task 2.1.3b's own comment),
  so the state would never reach `Ready`/`Failed` from a real completion. **Remediation**: move
  enqueue + receiver registration/unregistration onto `scope` alongside the polling loop (register
  once per download, not per `downloadModel()` call), and replace the
  `suspendCancellableCoroutine`/`invokeOnCancellation` pattern entirely with the same
  `modelState.first { it is Ready || it is Failed }` suspension the reattachment guard (Task
  2.1.3a) already uses for both the initiating and reattaching call — a `Flow.first{}` collector's
  cancellation has no side effects on the upstream `StateFlow` or the underlying download, which is
  exactly the AC5 property needed and removes the `invokeOnCancellation` cleanup path from the
  navigate-away case altogether. `cancelDownload()` remains the only path that calls
  `downloadManager.remove()`/deletes the file/resets to `Absent`.

- [ ] **Task 1.1.2b — new `CoroutineScope(PlatformDispatcher.Default)` has no `SupervisorJob` or
  `CoroutineExceptionHandler`, violating CLAUDE.md's "uncaught coroutine Throwables kill the
  process on Android" rule.** `OnnxMonocularDepthEstimator.modelState`'s `.stateIn(scope =
  CoroutineScope(PlatformDispatcher.Default), started = SharingStarted.Eagerly, ...)` creates an
  ad-hoc, unreferenced, uncancellable scope for a process-lifetime singleton with no exception
  guard. CLAUDE.md is explicit: "Every long-lived `CoroutineScope` that hosts user-path collectors
  or fire-and-forget launches must attach a `CoroutineExceptionHandler`." Ironically, ADR-001
  correctly applies this exact rule to `DepthModelDownloader.scope` a few tasks later in the same
  plan, but Epic 1.1 (written first) misses it for this scope. An uncaught exception from
  `downloader.modelState.map { it.toUiState() }` (e.g. a future bug in `toUiState()`) would crash
  the app on Android instead of degrading gracefully. **Remediation**: give
  `OnnxMonocularDepthEstimator` its own instance-owned `scope` field (`SupervisorJob() +
  PlatformDispatcher.Default + CoroutineExceptionHandler`), matching the same idiom ADR-001
  mandates for `DepthModelDownloader`, and use it for `stateIn`.

- [ ] **Task 1.1.1a — `DownloadableDepthModel` (declared in `platform/ml/MonocularDepthEstimator.kt`)
  directly types its `modelState` as `dev.stapler.stelekit.ui.annotate.DepthModelUiState`,
  inverting CLAUDE.md's documented layer order.** CLAUDE.md's architecture diagram places
  `Platform abstracts → platform/` as the lowest layer and `UI (Compose) → ui/` as the top layer.
  A `platform/ml/` file importing a type owned by `ui/annotate/AnnotationEditorViewModel.kt` makes
  the platform layer depend on the UI layer — backwards. This is exactly the inversion the plan's
  own Pattern Decisions table warns against for a different boundary ("Would make androidMain's
  platform-layer file depend on a type currently owned by the ui layer... inverting CLAUDE.md's
  stated layering") when justifying keeping `ModelState`/`DepthModelUiState` as separate types —
  but that reasoning isn't applied to `DownloadableDepthModel`'s own signature, which commits the
  same violation directly. Practically: any future reuse of `MonocularDepthEstimator`/
  `DownloadableDepthModel` outside a Compose UI context (background service, CLI tooling, batch
  export) now transitively requires the `ui/annotate` package. **Remediation**: either (a) give
  `DownloadableDepthModel` its own platform-layer-owned progress type distinct from
  `DepthModelUiState`, with `ScreenRouter`/`AnnotationEditorViewModel` performing the
  platform→UI mapping (mirrors the existing `ModelState → DepthModelUiState` `toUiState()`
  anti-corruption-layer pattern the plan already uses one level down), or (b) relocate
  `DepthModelUiState` out of `ui/annotate/` into `platform/ml/` (or a neutral `model/` package)
  if it's meant to be the canonical commonMain download-state representation — ADR-002's own
  framing ("a platform-independent mirror of `ModelState` for UI consumption") suggests it
  conceptually belongs at the platform boundary, not inside a specific ViewModel file.

## Concerns

- [ ] **Story 2.1.3/3.1.1 — unsynchronized concurrent mutation of `_modelState`/`activeDownloadId`/
  `pollingJob`.** `cancelDownload()` (invoked from the UI/main thread) and `startPolling`'s loop
  (running on `PlatformDispatcher.IO` via `scope`) both write these fields with no `Mutex`/lock.
  `pollingJob?.cancel()` is cooperative — a poll iteration already past its `isActive` check when
  cancel is requested can still execute `_modelState.value = ModelState.Downloading(...)` *after*
  `cancelDownload()` has set `_modelState.value = ModelState.Absent`, silently reverting the
  cancellation the user just triggered (lost-update race). No task in Phase 3/5 tests this
  ordering. Recommend guarding the terminal-state writes with a small `Mutex` or by having the
  polling loop re-check `activeDownloadId` immediately before its final `_modelState.value` write.

- [ ] **Story 2.1.3b — `Triple<Long, Long, Int>` as the `DownloadManager.Query()` result shape is
  primitive obsession / not parsed at the boundary.** The same sentinel triple
  (`Triple(-1L, -1L, -1)`) is reused for three different failure modes (query returns null, cursor
  empty, column index missing) and conflated with legitimate `(bytes, total, status)` data,
  including raw Android status-code ints threaded through untyped. Recommend a small value class
  (e.g. `data class DownloadSnapshot(val bytesDownloaded: Long, val totalBytes: Long?, val status:
  DownloadStatus)` with `DownloadStatus` a small sealed/enum type mapped once from the Android
  int constants) so downstream code branches on named cases instead of positional
  `Triple`/sentinel comparisons — classic Parse-Don't-Validate at the `Cursor` boundary.

- [ ] **Task 6.1.2a — `Downloading(progress, bytesDownloaded = -1L, totalBytes = -1L)` allows
  illegal state combinations.** Adding two more `-1`-sentinel `Long` fields alongside the existing
  `-1`-sentinel `Int progress` lets the type represent nonsensical states (percent known but bytes
  unknown, or vice versa) that the polling loop never actually produces but the type system
  doesn't prevent. Recommend replacing the parallel-sentinel fields with a small sealed
  `DownloadProgress` (`Indeterminate` / `Determinate(percent: Int, bytesDownloaded: Long,
  totalBytes: Long)`) so illegal combinations are unrepresentable, consistent with this repo's own
  `type-driven-design` guidance.

- [ ] **Epic 2.1/3.1 — `DepthModelDownloader.scope` is not injectable, hurting testability of
  Stories 2.1.2/3.1.1b/5.1.1.** The scope is hard-coded in the class body
  (`CoroutineScope(SupervisorJob() + PlatformDispatcher.IO + ...)`), so Robolectric tests exercising
  the polling/stall loop must wait on real wall-clock delays (`POLL_INTERVAL_MS = 300`,
  `STALL_TIMEOUT_MS = 30_000`) rather than `kotlinx-coroutines-test` virtual time — a 30-second
  stall test will really take ~30 seconds. Recommend a constructor-injectable `scope`/dispatcher
  seam (default to the production value) so tests can substitute a `TestScope`.

- [ ] **Epic 5.1 — no regression test covers the actual AC5 mechanism (navigate-away must not
  destroy the download), only the double-enqueue reattachment guard.** Given the first Blocker
  above, this gap is exactly why the ADR-001/Task-2.1.3e contradiction wasn't caught during plan
  authoring. Add a test that cancels the *calling* coroutine (not `cancelDownload()`) mid-download
  and asserts the `DownloadManager` row and `modelState` are unaffected — this is the test that
  would have failed against the plan as currently specified.

- [ ] **`DepthModelDownloader` (Epic 2.1/3.1/4.1 cumulative) is accumulating a lot of
  responsibility in one androidMain class** — enqueue, receiver lifecycle, scope/exception
  handling, polling, stall detection, cancellation, partial-file cleanup, and logging. Consider
  extracting the polling/stall logic into a dedicated `DownloadProgressPoller` collaborator
  (consistent with this repo's own established "new capabilities should be dedicated collaborator
  classes, not new fields on the existing class" precedent from prior wasmJs platform-layer work),
  which would also make the Mutex/injectable-scope concerns above easier to address in isolation.

## Nitpicks

- Task 2.1.3a's `(terminal as ModelState.Failed).reason` is a redundant cast — `terminal` is
  already smart-cast to `ModelState.Failed` by the enclosing `is ModelState.Failed ->` branch.
- `cancelDownload()`'s `runCatching { modelFile.delete() }` silently swallows delete failures with
  no logging; low practical risk at ~100MB scale but worth a `logger.warn` on failure for
  diagnosability.
- `ScreenRouter`'s `SensorModule.monocularDepthEstimator as? DownloadableDepthModel` is a
  Service-Locator-style downcast, but it matches the existing `SensorModule.cameraFrameSource`
  usage pattern already present in `ScreenRouter.kt` — not a new smell introduced by this plan.
- `POLL_INTERVAL_MS`/`STALL_TIMEOUT_MS` as raw `Long` millisecond constants rather than
  `kotlin.time.Duration` — matches the existing `SafChangeDetector` idiom the plan cites, so
  stylistic only.
- `Failed.reason: String?` (ADR-003) is already flagged by the ADR itself as a deferred
  trade-off (enum vs. free-form string) — no new finding beyond what ADR-003 discloses.
