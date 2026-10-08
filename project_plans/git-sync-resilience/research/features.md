# Features Research — git-sync-resilience

Research Agent 2 (Features). Scope: comparable implementations (in-repo and in the wild),
edge cases/failure modes beyond requirements.md, and unstated user needs.

---

## 1. Comparable implementations in this codebase

### 1a. The shared retry/poll idiom already exists — reuse it, don't invent a new one

`GitHubDeviceFlowClient.pollForToken()`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHubDeviceFlowClient.kt:96-173`) is a
**directly transplantable exponential-backoff pattern already living in the git package**:

- Deadline-bounded `while` loop, `delay(intervalMs)` between attempts (no busy-poll).
- `catch (e: CancellationException) { throw e }` always first — cancellation is never
  swallowed.
- Distinguishes **transient** (`IOException`, generic `Exception` → double `intervalMs`,
  capped at 60s, keep looping) from **terminal** (`expired_token`, `access_denied` → return
  `Either.Left` immediately, no retry) failures — this is exactly the "transient vs.
  permanent" distinction the Rabbit Holes section flags as undecided for clone/fetch/push.
  For git transport, `org.eclipse.jgit.api.errors.TransportException` (auth/connectivity) vs.
  other `Exception` is the analogous split, and `runGitTransportOp` (below) already makes that
  split at the type level.
- A status callback (`onStateChange`) fired on state transitions, not every tick — directly
  maps to "Wizard Step 5 progress UI shows retry/resume status" without inventing new UX
  plumbing.

`TagAvailabilityPoller.pollUntilAvailable()`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagAvailabilityPoller.kt:38-127`) is a
second instance of the same shape, explicitly modeled on `pollForToken` (its own doc comment
says so at line 13). Two independent precedents converging on one idiom is a strong signal
this project already has a house style for "poll/retry with backoff and a status callback" —
the git-sync-resilience implementation should be a third instance, not a fourth pattern.
Notable refinements to carry over:
- Catches `Throwable`, not `Exception`, in the per-tick catch — see the CLAUDE.md
  "Uncaught coroutine Throwables kill the process on Android" rule below.
- Elapsed time is accumulated by summing completed `delay()` ticks, not by re-reading the wall
  clock — makes the loop testable under `kotlinx.coroutines.test.runTest` virtual time. Any
  clone/fetch/push retry loop's tests should use the same technique rather than real `delay`.
- `startedAtOverride` lets a *resumed* poll (app reopened, user hits retry) compute
  elapsed/deadline math relative to the original start time, not a fresh "now" — relevant if
  retry-with-backoff state needs to survive a screen rotation/process-death/resume boundary in
  the wizard.

### 1b. The retry/timeout seam already exists at the exact right choke point

`kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/git/GitOperationSupport.kt` is a
**Desktop+Android shared file** (the `jvmCommonMain` source set) that both
`AndroidGitRepository.kt` and `JvmGitRepository.kt` funnel every JGit call through:

- `runGitTransportOp(onAuthFailed, onFailed) { ... }` (lines 48-61) already separates
  `TransportException` (→ `onAuthFailed`) from any other `Exception` (→ `onFailed`) — this is
  the natural place to add retry-with-backoff (wrap the whole `op()` call in a bounded retry
  loop, re-raising `TransportException`/auth failures immediately and only retrying the
  "other transient" bucket) and to add the connect/read timeout call. It is used by every
  clone/fetch/push/testRemote call site on **both** platforms, so a fix here fixes both
  platforms in one place — matches the requirement's "Desktop + Android" scope exactly.
- **Confirmed gap**: `testRemoteViaLsRemote` (lines 149-165) already calls
  `.setTimeout(TEST_REMOTE_TIMEOUT_SECONDS)` (15s) on its `Git.lsRemoteRepository()` command —
  but `AndroidGitRepository.clone()` (`AndroidGitRepository.kt:98-109`) and `.fetch()`
  (`AndroidGitRepository.kt:139-142`), and their `JvmGitRepository.kt` counterparts, call
  `Git.cloneRepository()` / `git.fetch()` with **no `.setTimeout(...)` at all**. JGit's
  `TransportCommand.setTimeout(int)` is inherited by `CloneCommand` and `FetchCommand` exactly
  as it's already used for `lsRemoteRepository()` — the fix is mechanical, not novel. This is
  very likely why the reported failure surfaces as "Software caused connection abort" instead
  of a clean timeout: JGit will block until the OS-level socket itself gives up, not until any
  JGit-level deadline.
- `redactUrlUserinfo`/`redactedTransportException` (lines 63-83) already scrub credentials from
  exception messages before they reach `DomainError` — any new retry-loop logging/status
  callback must route through the same redaction, not re-stringify the raw exception.
- `countRemoteCommitsBestEffort` (lines 105-111) is a precedent for "a resilience helper must
  not fail the outer op it supports" — a decent model for how a resume/retry helper should
  degrade if its own bookkeeping (e.g. reading a partial-transfer marker) throws.

### 1c. Freshness/staleness precondition pattern (`GitShadowWorktree`) — retry must respect it

`GitShadowWorktree.ensureFresh()` / `isFresh()`
(`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt:349-374`) is the
"is my local copy still valid before I touch it" precondition the Rabbit Holes section worries
about interacting with retry/backoff. Key structural facts for the design:
- The lock is `GitWorktreeLocks.lockFor(shadowKey)`, a per-shadow-key `Mutex` shared by
  `syncFromSafRoot` (SAF→shadow), `GitShadowFlushActor.flush()` (shadow→SAF), and
  `PlatformFileSystem`'s write-behind flush — **a retry loop that re-enters `openGit()` /
  `ensureFresh()` on each attempt will re-acquire this same mutex every time**, so retries are
  naturally serialized against concurrent SAF-side writes; no new locking primitive is needed,
  but a retry loop must not hold the shadow lock for the *entire* backoff `delay()` window (it
  currently doesn't — `syncFromSafRoot`'s lock scope is just the sync call) or it will starve
  `GitShadowFlushActor` for the whole retry sequence.
- The staleness check is mtime+size based, not content-hash based (`isEntryStale`,
  lines 281-286) — deliberately cheap. A resume/retry mechanism should not add a second,
  more expensive validity check on the hot path; reuse this one.
- `force: Boolean` parameter on `syncFromSafRoot` (line 197) is the existing escape hatch for
  "the mtime-based skip is wrong this time" (used today after `abortMerge()`'s hard reset,
  per the comment at lines 228-235) — a post-interruption resync (e.g. after a shallow clone's
  partial state is discarded and redone) should very likely pass `force = true` for the same
  reason: JGit's own working-tree writes during a failed/aborted transfer can produce mtimes
  the skip-check would misread as "already fresh."

### 1d. Storage-guard pattern — extend, don't duplicate

`AndroidGitShadowSupport.insufficientShadowStorageError()`
(`kmp/src/androidMail/.../AndroidGitRepositoryShadow.kt:72-84`, actual path
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryShadow.kt`) already
fails fast via `StatFs(...).availableBytes` *before* `init()`/`clone()` starts, returning a
typed `DomainError.GitError.WorkingTreeSyncFailed`. This only guards the **pre-flight** case
(not enough room to start). The Edge Cases section below (§3) covers the **mid-clone**
storage-exhaustion case this guard does not — the natural extension is a periodic re-check
inside the retry/progress loop using the same `StatFs` call, not a new storage-check
abstraction.

### 1e. Stale-lock recovery precedent — the shape for a "was this really abandoned" test

`AndroidGitRepository.removeStaleLockFile()` / `deleteStaleLockFile()`
(`AndroidGitRepository.kt:419-436`) already encodes the idiom for "an artifact left behind by
an interrupted operation is safe to clean up only if it's old enough to not be a live
in-progress operation" — a 60-second mtime-age threshold before deleting `.git/index.lock`.
This is the same shape a "was this shallow-clone/partial-transfer directory abandoned by a
force-killed process, or is a foreground service still working on it" check would need — reuse
the age-threshold idiom rather than inventing session/PID tracking.

### 1f. CLAUDE.md rules any new coroutine work here must follow

- **"Uncaught coroutine Throwables kill the process on Android — guard long-lived scopes"**
  (this repo's `CLAUDE.md`): any new retry loop or foreground-service worker must catch
  `Throwable`, not just `Exception`, in its outermost per-attempt catch (mirrors
  `TagAvailabilityPoller`'s explicit widening from `Exception` to `Throwable`, and
  `GitShadowFlushActor.flushPage`'s `catch (e: Throwable)` at line 95, both citing the same
  rule). A long-running clone under memory pressure (large repo, low-end device) is a
  plausible `OutOfMemoryError` site — exactly the case this rule exists for.
- **"Coroutine scope ownership — `rememberCoroutineScope` must not escape composition"**: if
  a retry/resume controller needs to outlive the Step 5 composable (e.g. survive navigating
  away and back), it must own its own `CoroutineScope(SupervisorJob() + Dispatchers.Default)`
  internally, never accept a `rememberCoroutineScope()` value — `GitSyncService`/
  `GitShadowFlushActor` already follow this; a new retry controller should be a sibling, not a
  field on a `remember { }`-scoped object.
- **Dispatcher matrix**: all JGit I/O in this design already runs under
  `PlatformDispatcher.IO` (`withContext(PlatformDispatcher.IO)` wraps every
  `AndroidGitRepository`/`JvmGitRepository` method) — a retry loop should stay inside that same
  `withContext`, not introduce a second dispatcher hop per attempt.

---

## 2. Comparable implementations in the wild

**Caveat up front**: none of the systems below have true byte-level resumable clone over the
git smart-HTTP/SSH protocol in the way Dropbox/Google Drive resume a single large file — a
`git clone`'s initial pack transfer is one negotiated request/response; if it's severed
mid-stream, the received bytes so far are not spliced onto a retry. "Resume" in every
git-native system below actually means one of: (a) retry-with-backoff, restarting a fresh
request; (b) start shallow and widen after landing (`git fetch --unshallow` /
`--deepen`), so the *retryable unit* is small; or (c) partial clone (`--filter=blob:none` +
protocol v2), which changes what "complete" means rather than resuming a transfer. This
matters directly for the Rabbit Holes item — "true resumable clone is not a JGit built-in" is
correct, and it's not a built-in anywhere else either.

- **Plain `git` CLI / index-pack**: an interrupted `git clone`'s partial pack data is
  discarded on the next attempt (`fetch-pack`/`index-pack` fail with `fatal: early EOF` /
  `fatal: index-pack failed` on truncation) — there is no `--continue` flag that resumes a
  half-received pack. The practical git-native pattern (also the one to adopt here, and it's
  literally what "Large-repo clones default to depth-limited (shallow) clone" already
  requires): clone shallow (`--depth 1`, small/fast, low retry cost if interrupted), then widen
  incrementally with `git fetch --deepen=<n>` or `--unshallow`, each widening step being its
  own retryable, boundable unit instead of one unbounded transfer.
- **JGit itself**: `CloneCommand`/`FetchCommand` support `.setDepth(int)` (shallow clone) and
  inherit `TransportCommand.setTimeout(int)` — both already confirmed present in the JGit
  version this repo pins (7.3.0) and unused today for clone/fetch (§1b above). No JGit-level
  resume primitive exists beyond that.
- **libgit2**: same story — no resumable smart-transport clone; open issues
  (`libgit2/libgit2#3563` "would block" SSH errors, general timeout/retry requests) show
  consumers hand-roll retry-with-backoff around `git_clone()` exactly the way this project's
  `pollForToken` already does for OAuth polling.
- **isomorphic-git**: its `onProgress` callback delivers `{phase, loaded, total}` events, and
  explicitly documents that clone is internally `fetch + indexPack + checkout` sub-phases, so a
  single percentage is not naturally available — direct precedent for why this project's
  current `clone(onProgress: (String) -> Unit)` signature (JGit's `ProgressMonitor.beginTask`
  title string only, no byte counts —
  `AndroidGitRepository.kt:101-109`/`JvmGitRepository.kt`'s equivalent) already matches
  industry practice for *what's available*, but the Step 5 UX (§4 below) should show
  phase-name text ("Receiving objects…", "Resolving deltas…") rather than attempt a fabricated
  percentage bar, since JGit's `ProgressMonitor.beginTask(title, totalWork)` does expose a
  `totalWork` unit count per phase that the current `onProgress` signature (title-string only)
  is currently discarding — a low-cost win: widen the callback to also pass `completed`/
  `totalWork` (already computed inside `ProgressMonitor.update(completed: Int)`, presently a
  no-op at line 104) for an actual progress bar within a phase.
- **GitHub Desktop**: recent user-facing issues (`desktop/desktop#20089`, "DNS Unreachable"
  mid-clone on network change; `#2838`, `exit status 128` surfaced raw) show GitHub Desktop
  does **not** auto-resume on network recovery today — the user must manually re-trigger
  clone, and its error surface (a raw git CLI exit code / DNS error) is a cautionary example
  of what *not* to do: SteleKit's "Software caused connection abort" bug report is the same
  category of leaky, non-actionable error text this design should replace with a classified
  (transient/permanent) message.
- **CI-runner retry idioms** (GitLab Runner, generic clone-retry wrappers): the converging
  pattern across every unrelated implementation found is small (2-3 attempt), bounded,
  doubling backoff (e.g. 500ms → 1s → 2s) around the *whole* clone attempt, not fine-grained
  mid-transfer retry — reinforces that `pollForToken`'s style (whole-operation retry with a
  capped doubling interval) is the right level of granularity to copy, not something more
  ambitious.
- **Android WorkManager + foreground service ("dataSync" type)**: current Android platform
  guidance (developer.android.com, foreground-service-types-required doc, current as of
  Android 14+) is: `CoroutineWorker.setForeground()`/`setForegroundAsync()` with the
  `dataSync` `FOREGROUND_SERVICE_DATA_SYNC` type + manifest permission declaration, invoked
  only for genuinely long user-initiated work — not a hand-rolled `Service`. This maps
  directly onto the existing `GitSyncWorker : CoroutineWorker`
  (`WorkManagerSyncScheduler.kt:108-169`) — the natural implementation is a **new**,
  user-initiated (not periodic) `CoroutineWorker` for clone/fetch/push, enqueued from
  `GitSetupScreenSaveLogic.performCloneAndSave` instead of running on the setup screen's own
  `rememberCoroutineScope`-backed scope as it does today, with `setForeground()`/dataSync
  called at start — reusing the periodic worker's proven WorkManager scheduling machinery
  rather than introducing a bespoke foreground `Service`.

---

## 3. Edge cases and failure modes beyond requirements.md

1. **Remote ref moves during a stalled/retried clone/fetch.** JGit's clone/fetch negotiates
   refs at the start of each attempt; a retry that restarts the whole operation (§2's "whole
   operation retry" pattern) naturally re-negotiates against the *current* remote state on
   each attempt, so this mostly self-resolves for clone. It's a real hazard specifically for a
   **shallow-then-widen** resume (widening a shallow clone against a remote whose branch has
   since force-pushed/rebased) — the depth-widen step should re-validate the target ref's
   current OID before widening, not assume the ref it saw at initial-shallow-clone time is
   still current, and should surface a distinct, non-retryable error (not silently retry
   forever) if the widen target has diverged.
2. **Force-kill (not just backgrounding) mid-transfer.** A foreground service survives
   backgrounding/Doze but not the OS killing the process outright (low-memory kill, user
   swipes the app away, `adb shell am force-stop`). WorkManager persists its own work queue
   across process death and Android will restart a `CoroutineWorker` (see `GitSyncWorker`'s
   existing "Slow path: process was killed and WorkManager restarted it" branch,
   `WorkManagerSyncScheduler.kt:133-168` — this exact scenario is **already handled for the
   periodic fetch path** and is directly reusable groundwork), but the shadow worktree/`.git`
   directory itself is left in whatever partial state JGit's process left it in (partial
   pack, possibly a stale `.git/index.lock` — §1e's existing 60s-age recovery already covers
   that specific artifact). Resume-after-restart therefore needs: (a) WorkManager's own
   restart (free, already proven for fetch), plus (b) a startup/resume check equivalent to
   `ensureFresh`/the storage guard that detects "this repoRoot has a `.git` dir but no
   completed clone marker" and restarts the clone from scratch (not append) rather than
   letting JGit choke on a half-written repository. This answers the "does resume need to
   survive process death" question from the task: **yes for the queue/scheduling layer (free,
   via WorkManager), no for the transfer itself (JGit has no partial-pack resume regardless of
   what killed it) — the resume story is "detect and restart," not "splice and continue."**
3. **Storage fills up mid-clone, not just pre-flight.** §1d's `insufficientShadowStorageError`
   only runs once, before `init()`/`clone()` starts. A large clone can exhaust free space
   partway through (the reported bug's repo is explicitly called out as "large"). The
   `ProgressMonitor` callback (already invoked per JGit task, `AndroidGitRepository.kt:102-108`)
   is a natural place to re-run the same `StatFs` check periodically and abort cleanly with the
   existing `WorkingTreeSyncFailed` error type rather than letting JGit hit a raw `IOException`
   from a failed write — same error type, just a second call site for the same guard, not a
   new one.
4. **WorkManager periodic fetch racing a foreground clone/push.** `GitWorktreeLocks.lockFor`
   (§1c) already serializes `syncFromSafRoot`/`GitShadowFlushActor`/`PlatformFileSystem`
   flush against each other by shadow key, but **JGit's own clone/fetch/push calls are not
   currently routed through that same mutex** — they operate directly on the JGit `Repository`
   object with no coordinating lock between `GitSyncWorker`'s background fetch and a
   foreground clone/push happening at the same time for the same `graphId`. Concretely: a
   user starts a large foreground clone, backgrounds the app, and 15 minutes later
   `WorkManagerSyncScheduler`'s periodic `GitSyncWorker` fires `fetchOnly()` against a
   `repoRoot` whose shadow worktree is mid-write from the still-running clone. This is a real
   gap the resilience design needs to close, most cheaply by having the new user-initiated
   clone/fetch/push worker call `WorkManagerSyncScheduler.pauseFor(context, graphId)` before
   starting and `resumeFor(...)` after finishing/failing — that pause/resume pair already
   exists today for exactly this kind of race (`WorkManagerSyncScheduler.kt:71-96`, built for
   the relocate/link storage-move quiesce case) and is a direct fit, not a new mechanism.
5. **Shallow-clone side effects the requirements don't mention.** A default shallow clone
   changes user-visible behavior beyond "faster": `git log`/blame history is truncated,
   any future `push` of a rewritten/rebased history can behave unexpectedly against a shallow
   local ref, and JGit shallow-clone support has historically lagged native git's (the
   `bugs.eclipse.org/475615` depth-support request found in research shipped, but a shallow
   JGit repo's `git push` and `merge` code paths in *this* codebase — `AndroidGitRepositoryMerge.kt`
   /`JvmGitRepositoryMerge.kt` — are untested against a shallow history today and should get
   explicit regression coverage, not assumed to "just work" because depth-limiting itself
   works).
6. **Retry loop's own backoff colliding with `WorkManager`'s independent backoff.** `GitSyncWorker`
   already returns `Result.retry()` on failure (`WorkManagerSyncScheduler.kt:129-131,165-167`),
   which triggers WorkManager's own exponential backoff at the OS scheduling layer. If the new
   user-initiated clone/fetch/push worker *also* implements an in-process retry loop (per
   requirements) AND returns `Result.retry()` on final exhaustion, the two backoff systems
   stack (WorkManager retries a worker that itself already retried N times) — the design should
   pick one layer to own "give up and tell the user" vs. "silently reschedule," not both.

---

## 4. Users' unstated needs

- **Cancel a long-running clone.** Today `clone()`'s `ProgressMonitor.isCancelled()` is
  already wired to the calling coroutine's `Job` (`AndroidGitRepository.kt:106`:
  `override fun isCancelled() = job?.isCancelled == true`) — cancellation plumbing already
  exists at the JGit layer. The gap is UI: Step 5 has no visible cancel affordance today
  (`performCloneAndSave` has no cancel path in `GitSetupScreenSaveLogic.kt`). Moving the
  operation into a WorkManager `CoroutineWorker` (§2/§4) must preserve cancellability
  (`WorkManager.cancelUniqueWork` cancels the worker's `Job`, which JGit already respects) and
  the wizard needs an explicit "Cancel" button wired to it — currently absent, easy to lose if
  the operation moves off the UI's own scope.
- **Visibility that the clone was shallow, and a path to un-shallow later.** Requirements only
  ask for a shallow default and retry/resume status; they don't ask for the natural follow-up
  a user would want once they discover truncated history (`git log` looking oddly short,
  or blame stopping abruptly). A "Full history" / "Unshallow" action (backed by
  `Repository.getObjectDatabase()`'s shallow-commit set +
  `FetchCommand.setUnshallow(true)`, JGit's equivalent of `git fetch --unshallow`) surfaced
  somewhere reachable (git settings screen, not necessarily the wizard) is the natural
  complement — and ties back to Edge Case #5's ref-move hazard, since unshallowing has the
  same "did the remote move since I last looked" concern as a widen-retry.
  Note: no direct evidence in-repo that `depth`/shallow state is currently surfaced anywhere
  in `GitConfig` or the settings UI — this would be new state to add, not a rename of
  existing state (checked via `grep -rn "shallow\|setDepth\|isShallow" kmp/src` — no hits
  today, confirming this is unbuilt).
- **A visible, specific reason the retry is happening — not just a spinner.** The reported bug
  ("Software caused connection abort") is exactly the kind of raw platform string
  `pollForToken`'s `DeviceFlowPollState.NetworkError(message)` pattern (§1a) was designed to
  replace with a classified status the UI can render meaningfully ("Network interrupted,
  retrying in 4s…" vs. a stack-trace fragment) — the same classification (transient-network vs.
  transient-server vs. terminal-auth vs. terminal-other) that already exists for OAuth device
  flow should extend to clone/fetch/push status, both because it's the established house
  pattern and because it directly answers "Wizard Step 5 progress UI shows retry/resume
  status" with a concrete shape to build against instead of a fresh design.
- **Knowing when it's safe to leave the app.** Once a foreground service is added (in scope),
  the notification itself becomes the answer to "can I switch apps now" — this is table stakes
  for the feature to deliver on its own success metric ("survives backgrounding via a
  foreground service") in a way the user can actually observe and trust, not just a background
  guarantee they have no visibility into.

---

## Key files referenced

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHubDeviceFlowClient.kt` (retry/backoff idiom to copy)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagAvailabilityPoller.kt` (second instance of the idiom, with `Throwable` + virtual-time refinements)
- `kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/git/GitOperationSupport.kt` (shared Desktop+Android choke point — `runGitTransportOp`, missing timeout on clone/fetch)
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt` (clone/fetch call sites, `ProgressMonitor`/cancellation wiring, stale-lock recovery)
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryShadow.kt` (pre-flight storage guard)
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt` (freshness/staleness precondition, per-shadow-key locking)
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowFlushActor.kt` (`Throwable`-catch precedent, same lock)
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt` (existing periodic-fetch WorkManager + process-death restart + pause/resume-for-quiesce precedent)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreenSaveLogic.kt` (Step 5's current clone/save flow, no cancel/retry UI today)
- `kmp/src/iosMain/kotlin/dev/stapler/stelekit/git/IosGitRepository.kt` (confirmed out-of-scope stub)
- `project_plans/git-integration/research/pitfalls.md` §1 (Doze/OEM battery-killer background-kill risks, cited in requirements.md)
