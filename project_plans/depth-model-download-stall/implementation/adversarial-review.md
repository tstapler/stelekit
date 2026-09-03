# Adversarial Review: depth-model-download-stall
**Date**: 2026-07-28
**Verdict**: BLOCKED

## Blockers

- [ ] **AC5 is not actually achieved by the concrete task list — ADR-001's central claim is false
  against the diff it authorizes.** ADR-001 asserts that once `DepthModelDownloader` gets an
  instance-owned scope, "[o]rdinary caller-coroutine cancellation (navigating away)... [is] no
  longer tied to the download's lifecycle at all, because the polling loop **and the
  `DownloadManager` request** now live on `DepthModelDownloader`'s own scope." That is false for
  the request itself. Tasks 2.1.1–2.1.3 only move the *polling loop* (`startPolling`) onto the new
  `scope`; the enqueue + `BroadcastReceiver` registration + await-completion logic stays exactly
  where it is today, inside `downloadModel()`'s `suspendCancellableCoroutine` (verified by reading
  the current `DepthModelDownloader.kt:63-127` and cross-checking every task in Epic 2.1 — none of
  them relocates that block). `invokeOnCancellation` (`DepthModelDownloader.kt:120-125`) is left
  functionally unchanged by Task 2.1.3e — it still calls `downloadManager.remove(downloadId)` and
  sets `_modelState.value = ModelState.Absent` whenever the *calling* coroutine is cancelled. Per
  Task 1.2.1b, `onDownloadDepthModel` is wired as `scope.launch { it.downloadModel() }` on a
  composable-scoped (`rememberCoroutineScope()`) or ViewModel-scoped coroutine — both of which are
  cancelled when the user navigates away from `AnnotationEditorScreen`. So: **navigating away mid-
  download still destroys the in-flight `DownloadManager` request and resets state to `Absent`,
  exactly like today** — AC5 is not fixed, only the *symptom description* changed (progress now
  moves before you leave, but leaving still discards it). `research/pitfalls.md` §1 explicitly
  flagged this exact fork ("if the loop is tied to the ViewModel's long-lived scope, navigating
  away will not cancel the download... this needs an explicit design decision, not an
  assumption") — the plan made the decision in the ADR text but never implemented it in the task
  list. **Fix**: either move the enqueue/receiver/await logic itself onto the instance-owned
  `scope` (with `downloadModel()` becoming a thin "start-if-needed, then await via
  `modelState.first{}`" wrapper, matching the reattachment path), or drop the AC5 claim from
  ADR-001 and re-scope AC5 to "you can resume watching an existing download when reattaching to
  the same process" only.

- [ ] **`cancelDownload()` has no path to the in-flight `suspendCancellableCoroutine`'s receiver or
  continuation, and the reattachment guard's terminal filter excludes `Absent` — both hang
  callers.** `cancelDownload()` (Task 3.1.1a) touches `activeDownloadId`, `pollingJob`, and
  `_modelState` only. The `receiver` and `continuation` created inside `downloadModel()`'s
  `suspendCancellableCoroutine` lambda (current `DepthModelDownloader.kt:81-125`) are locals with
  no field reference anywhere in the plan's task list — `cancelDownload()` cannot reach them.
  Concretely: (a) the *original* caller still suspended in `downloadModel()` awaiting
  `ACTION_DOWNLOAD_COMPLETE` never gets resumed after `cancelDownload()` runs, because
  `downloadManager.remove()` does not fire that broadcast — that caller's coroutine, and the
  registered `BroadcastReceiver`, leak/hang indefinitely (or until independently cancelled by
  navigate-away, per the bug above); (b) a *reattached* caller (Task 2.1.3a) is suspended on
  `modelState.first { it is Ready || it is Failed }` — `cancelDownload()` sets `Absent`, which
  that predicate does not match, so the reattached caller also hangs forever instead of resolving
  to any `Either`. This is exactly the race the task brief asked to verify, and it is real: no
  task tests it (Task 3.1.1b only drives `cancelDownload()` against a fake enqueued download with
  no concurrently-suspended caller). **Fix**: `cancelDownload()` needs a way to resume/cancel the
  live continuation (e.g., store it in a field guarded the same way `activeDownloadId` is, or make
  cancellation flow through the same single-resume-guarded completion path pitfalls.md §4
  recommends), and the reattachment `.first{}` predicate must include `Absent` (or some other
  terminal-on-cancel signal) as a valid resolution.

- [ ] **Task 1.2.1b wires `onEstimateDepth` to a silent no-op, which reproduces this exact ticket's
  bug on a different button.** Once a download succeeds, `DepthEstimationPanel`'s `Ready` branch
  (`AnnotationEditorScreen.kt:1355-1362`) renders a fully enabled `OutlinedButton(onClick =
  onEstimate)` labeled "Estimate depth (AI)". With Task 1.2.1b's stub
  (`onEstimateDepth = downloadableDepthModel?.let { { /* left for a separate ticket */ } } }`),
  tapping that button after a real user downloads the ~100 MB model does *nothing* — no spinner,
  no error, no result, no feedback of any kind. That is functionally indistinguishable from the
  "stuck, never improves" complaint this entire ticket exists to fix, just relocated one screen
  deeper. Requirements.md's Non-Goals never mention shipping a dead "Estimate" button, and no
  story/AC in the plan covers or accepts this outcome — it is mentioned only in a task-level code
  comment, not in requirements.md, the Unresolved Questions section, or an ADR. Shipping this as-is
  will generate a follow-up bug report identical in shape to the one currently being fixed.
  **Fix**: either scope `onEstimateDepth` out entirely (pass `null`, so the `Ready` branch — wait,
  the panel is gated at the *file* level (`onDownloadDepthModel != null || onEstimateDepth !=
  null`), so `onDownloadDepthModel` alone already renders the whole panel including the `Ready`
  branch — `onEstimateDepth` cannot be dropped without also hiding the Estimate button by adding
  real gating logic there), implement the real estimation wiring in this same PR, or explicitly
  disable/hide the Estimate button (not just no-op its click) with copy indicating estimation isn't
  available yet, and get that explicitly signed off as an accepted interim state.

- [ ] **AC1 — the ticket's headline bug — has zero automated test coverage of the actual polling
  loop.** Story 2.1.3 states three Given/When/Then examples (progress advances every ~300ms across
  two ticks, indeterminate-size fallback, reattachment avoids double-enqueue) as its acceptance
  criteria, but every task under it (2.1.3a–e) is implementation-only; no test task exists for
  Story 2.1.3 anywhere in Phase 2 or Phase 5. Story 2.1.2's tests (`DepthModelDownloaderProgressMathTest`)
  cover only the extracted pure functions (`computeProgressPercent`, `hasStalled`) in isolation —
  they never exercise the polling coroutine, the `Cursor`/`DownloadManager.Query()` interaction,
  or a `Downloading(0)` → `Downloading(N)` sequence through the real `modelState` `StateFlow`.
  Story 5.1.1's reattachment test only proves a single enqueue count and eventual resolution on
  completion — it doesn't assert on intermediate progress values. So the one behavior a user
  filed this bug about — "the percentage actually moves" — is verified only by Story 5.1.3's
  manual on-device checklist, which is not run in CI and not gating merge in any enforced way.
  **Fix**: add a Robolectric test that drives the shadow `DownloadManager`'s query cursor through
  at least two distinct byte-count values and asserts `modelState` emits the corresponding
  `Downloading(N)` sequence (not just that `computeProgressPercent` is correct in a vacuum).

## Concerns

- [ ] **Process-death survival is explicitly flagged as a real, unaddressed edge case by two
  research docs, but silently dropped from the plan rather than scoped out.** `research/pitfalls.md`
  §5 states AC5 "should specifically cover this 'process died mid-download' case, not just 'screen
  navigated away within the same process'... code should re-query `DownloadManager` for a still-
  active request by a persisted download ID." `research/features.md` edge case 1 calls this a
  "confirmed gap": `activeDownloadId` is a plain `private var`, never persisted, so a process
  restart mid-download reports `Absent` and risks a duplicate enqueue racing the still-running
  original transfer into the same destination file. Neither the plan's Unresolved Questions nor
  its Non-Goals section mentions process death at all — it's neither fixed nor explicitly
  deferred, it's just absent from the document. Given `DownloadManager`'s own doc-comment claim
  ("survives process death") is quoted approvingly elsewhere in this same plan, a reader could
  reasonably assume this case is handled when it isn't. **Recommendation**: add an explicit
  Non-Goal sentence ("process-death re-attachment via persisted download ID is out of scope for
  this fix; only in-process navigation is addressed") or scope it in as a task — currently it's
  neither.

- [ ] **Cancel-path test coverage only exercises `cancelDownload()` in isolation, never against a
  concurrently-suspended caller.** Directly related to Blocker #2 above: Story 3.1.1b's Robolectric
  test fakes an enqueued download and calls `cancelDownload()` synchronously — there is no test
  where a coroutine is actually parked inside `downloadModel()`'s `suspendCancellableCoroutine` (or
  the reattachment `.first{}`) at the moment `cancelDownload()` fires. This is precisely the gap
  that would have caught Blocker #2 before merge.

- [ ] **Heavy reliance on manual on-device QA (Story 5.1.3) for exactly the race-prone integration
  points `pitfalls.md` spent the most words on** — completion-vs-poll race (§4), cancel-vs-poll
  race (§4), receiver double-unregister (§1), stale-`Downloading`-after-cancel ordering (§4's
  "cancel poll job first, then set Absent" requirement, implemented in Task 2.1.3e but not tested).
  None of these orderings has a regression test; a future refactor could silently reintroduce the
  "stale `Downloading` briefly overwrites `Absent` post-cancel" flicker pitfalls.md warned about,
  and nothing in CI would catch it.

- [ ] **Phase 1 (wiring) is a materially larger scope expansion than requirements.md describes**,
  discovered only during planning (the panel was never reachable in the shipped app at all). The
  expansion is well-justified — nothing downstream is testable without it — but it roughly doubles
  the surface area of the change (two new files touched that requirements.md never mentions:
  `MonocularDepthEstimator.kt`'s new capability interface, and `ScreenRouter.kt`'s new
  `LaunchedEffect`/callback wiring) and is where Blocker #3 (`onEstimateDepth` stub) originates.
  Worth flagging to product/reviewer explicitly in the PR description as "this PR also makes a
  previously-unreachable feature reachable," not just "fixes the progress bar," since it changes
  what ships to users beyond the literal bug report.

## Minors

- Task 2.1.3a's `(terminal as ModelState.Failed).reason` inside a `when` branch already guarded by
  `is ModelState.Failed ->` is a redundant explicit cast — Kotlin already smart-casts `terminal`
  there; harmless but sloppy, easy cleanup during implementation.
- ADR-003's accepted tradeoff (a download legitimately waiting for Wi-Fi via
  `PAUSED_QUEUED_FOR_WIFI` for >30s will surface as "This is taking longer than expected" /
  `Failed`, indistinguishable from a genuine stall) is reasonable per the AC's literal wording, but
  is worth a one-line confirmation from whoever owns the UX call before shipping, since it's a
  user-visible false-alarm on an arguably common Wi-Fi-only scenario — not a code defect, just an
  unconfirmed product judgment call baked into the ADR unilaterally.
- The completion-vs-poll race (`Downloading(97)` jumping straight to `Ready` without ever showing
  100%) is explicitly called "not a correctness bug, just a product decision" by pitfalls.md §4 and
  the plan makes no decision either way — fine to leave as-is, but worth a one-line note in the PR
  description so it isn't rediscovered as a "bug" later.
