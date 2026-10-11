# Pitfalls Research — git-sync-resilience

_Research date: 2026-09-23_

This document builds on `project_plans/git-integration/research/pitfalls.md` (the original
git-integration project's pitfalls doc, "the 2025-05-02 doc" below). Its §1 (Doze/OEM
battery-kill), §6 (file-watcher/merge race), and §8 (stale lock file) are **not** repeated here
except where this project's retry/resilience layer changes their risk profile. Read that doc
first.

All code citations are `path:line` against this worktree
(`/home/tstapler/.stapler-squad/workspaces/d685c4b1a423cca3/worktrees/stelekit-dowbolad_18d823a382cb9bc4`)
as of commit `0a3cffd635` (branch `stelekit-dowbolad`).

---

## 0. What exists today (baseline the new work must not break)

Confirmed by reading the code, not assumed:

- **No connect/read timeout** on `clone`, `fetch`, or `push` in either
  `AndroidGitRepository.kt` or `JvmGitRepository.kt`. The only place `setTimeout` is called at
  all is `testRemoteViaLsRemote` (`kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/git/GitOperationSupport.kt:161`,
  15s, added specifically because "a black-holed host leaves the 'Test connection' UI spinning
  forever" — same failure mode this project must now fix for clone/fetch/push).
- **No retry loop today for transport failures.** `runGitTransportOp`
  (`GitOperationSupport.kt:48-61`) catches `TransportException` and any other `Exception` exactly
  once and converts it to a `DomainError.GitError` — no retry.
- **A retry mechanism already exists, but only for HTTP 429 rate-limiting**, not transient
  network failure: `GitSyncService.scheduleRateLimitRetry`
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt:153-166`) schedules a
  **single** one-shot re-invocation of the whole `sync()`/`fetchOnly()` operation after
  `retryAfterSeconds` (default `DEFAULT_RATE_LIMIT_RETRY_SECONDS`, GitSyncService.kt:652-654),
  on the service's own long-lived `scope` (`GitSyncService.kt:123`,
  `SupervisorJob() + PlatformDispatcher.IO + exceptionHandler`). This is the natural template
  for the new resilience layer's scheduling code — but see §1 for what it's missing (jitter,
  bounded retry count, and it isn't reused by the WorkManager path).
- **A second, independent retry path already exists on Android via WorkManager**:
  `GitSyncWorker.doWork()` (`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt:117-168`)
  calls `Result.retry()` on any non-cancellation exception, both on its "fast path" (an
  already-registered `GitSyncService.fetchOnly`) and its "slow path" (process was killed;
  constructs a throwaway `AndroidGitRepository` and calls `fetch()` directly, bypassing
  `GitSyncService` entirely — line 155-159). `Result.retry()` uses WorkManager's own default
  backoff policy (linear, `WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS` = 10s, uncapped retry count
  since this isn't overridden with `setBackoffCriteria`). **This is a second, uncoordinated retry
  policy that already exists in production** — any new resilience layer must either replace it,
  wrap it, or explicitly reason about the two compounding. See §1.
- **Existing exponential-backoff precedent has no jitter anywhere in the codebase.**
  `WasmSectionSyncService.githubFetch` (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/sync/WasmSectionSyncService.kt:88-105`)
  uses `(1 shl retryCount).coerceAtMost(60)` — a pure function of `retryCount`, deterministic,
  capped at 4 retries. `GitSyncService.scheduleRateLimitRetry`'s doc comment
  (`GitSyncService.kt:148-149`) explicitly cites this as its own precedent. Neither has jitter.
  This is the pattern the new work is most likely to copy — flagged in §1.
- **`removeStaleLockFile` is a separate, caller-invoked operation, not automatic.** Both
  platforms implement it identically (`AndroidGitRepository.kt:419-436`,
  `JvmGitRepository.kt:356-373`): deletes `.git/index.lock` only if it's >60s old, otherwise
  returns `DomainError.GitError.StaleLockFile`. **No clone/fetch/push/merge call site calls
  this before running** — it's invoked by the UI/service layer as its own step. See §2.
- **No `depth`/shallow-clone option exists on `GitConfig` or in `clone()`** — confirmed by
  reading `GitConfig` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitConfig.kt:9-24`,
  no depth field) and both `clone()` implementations (no `.setDepth(...)` call). The "default to
  shallow clone" success metric is greenfield, not a toggle on existing behavior.
- **No mutex serializes JGit calls against the *real* `.git` directory across call sites.**
  `GitWorktreeLocks` (`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/GitWorktreeLocks.kt`)
  is a per-`shadowKey` `Mutex`, but its doc comment says exactly what it guards: "the write-back
  actor and the SAF-to-shadow sync path" — i.e. the **SAF shadow-worktree** sync, not the
  underlying JGit `Repository`/`.git/index.lock`. `GitSyncWorker`'s background fetch and a
  foreground `GitSyncService.sync()`/wizard clone on the same `repoRoot` are not serialized
  against each other at the JGit level by anything in this codebase today. See §5.

---

## 1. Retry/backoff pitfalls

### 1.1 Two independent retry layers already exist and are not coordinated

This is the single most concrete risk in this section, and it's already latent in the codebase,
not hypothetical. `GitSyncWorker.doWork()` returns `Result.retry()` on any exception
(`WorkManagerSyncScheduler.kt:129`, `:166`), which triggers WorkManager's own backoff and
re-invocation — completely independent of whatever this project adds inside
`AndroidGitRepository.fetch()`/`clone()`/`push()`. If the new resilience layer adds its own
internal retry-with-backoff loop *inside* those methods (the natural place to put it, since it's
shared with Desktop), a single real transient failure now produces **two nested retry storms**:
N internal attempts (each with its own backoff), and if all N are exhausted and the method still
throws, WorkManager retries the whole worker *again*, which calls `fetch()` again, which runs its
own N internal attempts again. Worst case is a multiplicative, not additive, blow-up in attempt
count and wall-clock time for what started as one blip — directly the "clean fast failure becomes
a slow stuck state" risk named in the requirements' §5.

**Recommendation to validate in planning**: pick one owner. Either (a) the internal
retry-with-backoff layer owns all retries and the WorkManager worker returns `Result.failure()`
(not `Result.retry()`) once its internal budget is exhausted, treating "exhausted" as final until
the next scheduled 15-minute WorkManager run; or (b) WorkManager owns retry scheduling
(`setBackoffCriteria` with an explicit bound) and the internal layer does zero or one retry only
(just enough to survive a single dropped packet, not a sustained outage). Do not implement both
as unbounded loops.

### 1.2 No jitter anywhere in the codebase's retry precedent — thundering-herd risk is real, not theoretical

Every existing backoff implementation in this repo (`WasmSectionSyncService.githubFetch`,
`GitSyncService.scheduleRateLimitRetry`) computes a **deterministic** delay from `retryCount`
alone — no randomization. `GitSyncService.scheduleRateLimitRetry`'s doc comment
(`GitSyncService.kt:148-149`) explicitly names `WasmSectionSyncService`'s formula as its
precedent, so a same-pattern implementation for this project is the likely default. The
requirements' scenario — "many graphs retrying simultaneously after a network blip" — is
concrete for this app specifically: a phone reconnecting to Wi-Fi after a dead zone, or a laptop
waking from sleep (§4), fires a shared connectivity-restore event across every open graph at
once. If N graphs all failed at the same wall-clock moment and all compute the same
`(1 shl retryCount)` schedule, they retry in lockstep at t=1s, 2s, 4s, 8s… — the classic
thundering herd this task explicitly names. **Add jitter (e.g. `delay * (0.5 + random() * 0.5)`)
to whatever backoff formula this project introduces**, and note that the existing
`scheduleRateLimitRetry`/`WasmSectionSyncService` precedents do not have it — don't copy them
verbatim without adding it.

### 1.3 `git push` retry safety — empirically better than the requirements' worst case, but not free

The requirements ask directly: is a blind push retry safe if the server accepted the push but the
client never got the ack? Evidence from JGit's own issue tracker
([eclipse-jgit/jgit#126](https://github.com/eclipse-jgit/jgit/issues/126)) on patching
`PushCommand` to retry after `TransportException`: "retrying had unwanted side effects where in
the second retry, the `PushCommand` returns a `PushResult` of `UP_TO_DATE`." That is actually the
**safe** outcome — a retried push of the same ref update, once the remote already has the commit,
reports `UP_TO_DATE` rather than creating a duplicate ref or object. Git's object model makes push
idempotent *at the object level*: pushing the same commit SHA twice is a no-op the second time.

The real risk in this codebase is not "double push," it's **double *commit* from retrying at the
wrong granularity**. `GitSyncService.sync()` runs `stageSubdir → commit → … → push` as one
sequential flow (`GitSyncService.kt:243-349`), and nothing in this codebase calls
`CommitCommand.setAllowEmpty(...)` (confirmed: zero matches for `setAllowEmpty` or
`EmptyCommitException` anywhere under `kmp/src/`). JGit's documented default is to throw
`EmptyCommitException` when there's nothing new to commit. So the retry-granularity question
matters:

- If retry is scoped to **just the transport step** (fetch/push), a second attempt calls
  `git.push()` again against the same already-committed local `HEAD` — safe, and per the JGit
  issue above, JGit itself reports it as a no-op.
- If retry is scoped to **the whole `sync()` operation** (the natural unit today, since that's
  what `scheduleRateLimitRetry` re-invokes — `GitSyncService.kt:159-166` calls `sync(g)` or
  `fetchOnly(g)` in full, not a sub-step), a second attempt re-runs `commit()`. If nothing changed
  since the first attempt's commit (the common case — the first attempt's `commit()` already
  succeeded, only `push()` failed), this either throws `EmptyCommitException` (breaking the
  retry) or, if some future change adds `setAllowEmpty(true)` to paper over that, silently creates
  a second, near-duplicate commit with a fresh timestamp/SHA on every retry.

**This needs an explicit design decision, not an assumption**: retry must be scoped to the
transport step, not re-run `stageSubdir`/`commit`. Verify whether `sync()`'s failure paths
(`GitSyncService.kt:249-251`, `:271-273`, `:289-291`, `:350-352`) already skip past `commit()` on
a retry triggered after a push failure — a quick read suggests `scheduleRateLimitRetry` always
calls the entry-point function (`sync`/`fetchOnly`) fresh, which re-runs `commit()` unconditionally.

### 1.4 Retrying non-idempotent-by-content operations: `stageSubdir`'s `git add -u`

Less central than push, but worth naming: `stageWikiSubdir` (`AndroidGitRepository.kt:181-187`,
`JvmGitRepository.kt:166-172`) does `git add <pattern>` then `git add -u <pattern>` (stage
deletions) as two separate JGit calls with no transaction across them. If a retry re-runs this
step after the first `add` succeeded but the process was interrupted before the second, the
working tree's staged state is inconsistent going into `commit()` — not novel to this project
(it's a pre-existing two-step non-atomicity), but the requirements' Android foreground-service
survival goal will make interruption *between* these two calls newly reachable in cases (screen
lock mid-stage) that were previously vanishingly rare because the whole operation was fast and
foregrounded.

### 1.5 Battery drain from retry loops — extends the 2025-05-02 doc's §1, doesn't repeat it

The prior doc already flags "retrying with WorkManager's exponential backoff will re-run the
entire fetch" and OEM background-kill as risks. What's new for *this* project: a bounded internal
retry loop (§1.1) that runs **inside a held wakelock / foreground service** (per the Android
scope item) is a different battery cost than WorkManager's own backoff, which sleeps between
attempts without holding anything. If the foreground service keeps the radio/CPU active across
every internal retry attempt (not just the first transfer attempt), a sustained outage — plane
mode, no signal in a basement — turns "one clone attempt, fails fast" into "foreground service +
notification + wakelock held for the full internal retry budget," which is worse for battery than
today's single fast failure, not better. Cap the internal retry *count* and *total elapsed time*
independent of the per-attempt backoff cap, and stop holding the foreground service the moment the
retry budget is exhausted rather than falling through to WorkManager's separate retry (§1.1).

---

## 2. JGit-specific pitfalls

### 2.1 `removeStaleLockFile` is not wired into any retry path — a retried clone/fetch will not self-heal

Confirmed by reading both `AndroidGitRepository.kt:419-436` and `JvmGitRepository.kt:356-373`:
`removeStaleLockFile` exists as a **separate, explicitly-invoked** operation. Nothing in
`clone()`, `fetch()`, `merge()`, or `push()` calls it before running. If a network interruption
happens while JGit holds `.git/index.lock` (JGit takes this lock during `fetch`/`merge`'s
index-write phase, not `clone` before the repo exists) and the process is killed mid-operation
(exactly the scenario this project targets — screen lock / backgrounding on Android), the lock
file survives. **A naive automatic retry that just calls `fetch()`/`merge()` again will fail
again immediately** with JGit's own lock-acquisition error, not the original transient network
error — masking the real problem and burning a retry attempt on a guaranteed failure. The new
retry loop must call (or replicate) `removeStaleLockFile`'s logic as a pre-retry step, not just
before the *first* attempt. Note the existing 60-second staleness threshold
(`AndroidGitRepository.kt:431`, `JvmGitRepository.kt:368`) was presumably chosen for
"stale from a previous app session," not "stale from an operation this same retry loop killed 2
seconds ago" — a retry loop must not treat its own just-failed attempt's lock as immediately
stale-and-removable without first confirming the failure was fatal (a lock held by a still-running
JGit call inside a cancelled-but-not-yet-unwound coroutine is a real race, not just a
hypothetical).

### 2.2 Interrupted clone does not clean up after itself — and JGit's `Git.open`/`Git.cloneRepository` treat a non-empty target directory as a hard failure, not a resume point

Two separate, compounding facts, both confirmed against JGit's own issue history rather than
assumed:

1. JGit's `CloneCommand` does not reliably clean up a partially-created target directory when
   `call()` throws mid-transfer — corroborated by JGit/Grgit issue reports of locked/orphaned
   `.git/objects/pack/*` files surviving a failed or interrupted clone
   ([ajoberstar/grgit#132](https://github.com/ajoberstar/grgit/issues/132),
   [ajoberstar/grgit#33](https://github.com/ajoberstar/grgit/issues/33) — Windows-specific file
   locking, but the underlying "clone failure leaves a partial `.git/` behind" behavior is not
   Windows-specific, only the *inability to delete it afterward* is).
2. This repo's own `clone()` (`AndroidGitRepository.kt:82-118`, `JvmGitRepository.kt:73-103`)
   calls `Git.cloneRepository().setDirectory(File(localPath))...call()` with no pre-check, no
   `try`/cleanup around a failed `call()`, and no existing-directory guard. A plain retry of
   `clone()` into the same `localPath` after a partial failure will hit JGit's own
   "destination path … already exists and is not an empty directory" failure — a **different**
   error than the original transient network failure, and one that cannot be retried away without
   first deleting the partial directory.

Combined with the requirements' own stated constraint ("JGit has no first-class resumable-clone
API"), this means: **the practical "resume" contract cannot be a byte-level resume of
`CloneCommand`.** The realistic options are (a) delete-and-restart the clone from scratch on
retry — safe but not "resume from where it left off" for a large repo, defeating the success
metric's intent; or (b) synthesize resumability out of `init()` + repeated `fetch()` calls with an
increasing `--depth`/shallow-since boundary instead of one `clone()` call — `fetch()` is
naturally incremental (only new objects since the last successful fetch), so a failed multi-object
fetch retried from the last *successful* fetch checkpoint approximates resumability where
`clone()` cannot. This is a genuine architecture decision for the planning phase, not just an
implementation detail — flag it as a rabbit hole per the requirements' own list.

### 2.3 Shallow clone + `MergeStrategy.RECURSIVE` — the documented git-level limitation applies directly to `AndroidGitMergeSupport`/`JvmGitRepositoryMerge`

Both platforms' `merge()` hard-codes `MergeStrategy.RECURSIVE` with
`FastForwardMode.NO_FF` (`AndroidGitRepository.kt:216-220`, `JvmGitRepository.kt:200-204`) —
i.e., every merge is forced to create a merge commit and always runs a recursive three-way merge,
which needs a merge base. Git's own documentation (surfaced via search, consistent with general
git behavior, not JGit-specific) states plainly: shallow repositories truncate history, and
"commands such as `git merge-base`... cannot be counted on to work as expected" once the true
common ancestor predates the shallow boundary. If this project's shallow-clone default produces a
repo whose local shallow boundary is *newer* than the actual merge base with a diverged remote
branch (plausible after the wiki has been shallow-cloned and both sides have since diverged
significantly), `RECURSIVE`'s three-way merge either picks a wrong/degenerate merge base or fails
outright — a real interaction the requirements' own Rabbit Holes section already names
abstractly ("retry/backoff interacting with the shadow-worktree freshness precondition" is
adjacent but doesn't name this specific one). **Mitigation to plan for**: either unshallow
(`fetch --unshallow` / JGit's fetch with `setUnshallow`) before any merge that could hit this, or
detect a shallow-boundary-crosses-merge-base situation and fail closed with a clear error rather
than let JGit silently produce a degraded merge. This has not been implemented or tested anywhere
in this codebase today — confirmed by the absence of `setDepth`/shallow config noted in §0.

### 2.4 `runGitTransportOp`'s exception classification is coarse — do not classify "transient" by exception type alone

`runGitTransportOp` (`GitOperationSupport.kt:48-61`) distinguishes exactly two buckets:
`TransportException` → auth-shaped error, everything else → generic failure. It does not
distinguish "socket dropped mid-transfer" (transient, retry-worthy) from "repository not found"
or "permission denied" (permanent, JGit also throws these as `TransportException` in many
transports — auth failures and network failures are not cleanly separable by exception type in
JGit's own class hierarchy). A retry-with-backoff layer built naively on top of "catch
`TransportException`, retry" will also retry permanent auth/404 failures on every attempt,
burning the entire backoff budget on an error no amount of retrying will fix — user-visible as
"just hangs retrying" instead of "fails immediately with a clear auth error," a direct instance
of the requirements' own named risk (Feasibility Risks: "distinguishing transient vs. permanent
transport failures"). JGit's underlying causes are typically wrapped `java.io` exceptions
(`SocketException`, `SocketTimeoutException`, `UnknownHostException`) — inspecting
`TransportException.getCause()`'s type, not just catching `TransportException` itself, is the
more reliable transient/permanent signal and should be the classification this project's retry
predicate uses.

---

## 3. Android-specific pitfalls (extends, does not repeat, the 2025-05-02 doc's §1)

### 3.1 `dataSync` foreground service type has its own Android 14 execution-time cap — different from Doze, and easy to conflate

The prior doc's §1 covers Doze/App-Standby/OEM background kill for a *background* fetch. A
foreground service is a different mechanism with a **different** Android-14-specific limit not
covered there: a `dataSync`-typed foreground service (the correct type for a git transfer, not
`connectedDevice` — see the existing `AndroidMeasurementForegroundService` precedent below, which
uses `connectedDevice` for BLE and is not directly reusable) is capped by the OS at roughly 6
hours of cumulative runtime per rolling 24-hour window on Android 14+; the system calls
`Service.onTimeout()` and the service must stop itself. For a large-repo shallow clone with
retry/backoff over a degraded connection, this cap is reachable in a way a quick BLE-connection
service never approaches — the retry/backoff budget (§1) must be bounded well under this ceiling,
and the service must handle `onTimeout()` gracefully (persist retry-resume state and surface "sync
paused, resume later" in the UI) rather than being killed mid-write.

### 3.2 `ForegroundServiceStartNotAllowedException` — this app's clone is a strong candidate for hitting it

Android 12+ forbids starting a foreground service from the background in most cases. This app's
wizard-driven clone (Step 5, foregrounded, user-initiated — safe) is not the risk; the risk is any
path that tries to **promote an already-running background operation to a foreground service**
after the fact — e.g., a `WorkManager`-triggered background fetch (`GitSyncWorker`, already
running today with no foreground service at all — confirmed in §0) that discovers mid-fetch that
it needs to become a foreground service to survive backgrounding. Starting a foreground service
from a `CoroutineWorker`'s `doWork()` context, if the app process itself is not already in a
foreground-eligible state, throws `ForegroundServiceStartNotAllowedException` on API 31+. The safe
pattern (also used by `CoroutineWorker.setForeground()`, which exists precisely for this) is to
call `setForeground()` from *within* the worker before doing the transfer, not to start a separate
`Service` from arbitrary background code — but that only works for work WorkManager itself
scheduled; it does not help a wizard-initiated clone that later needs the app to be backgrounded
mid-transfer, which needs the classic `startForegroundService()` + `startForeground()` pattern
called while the app is still in the foreground (i.e., start the foreground service *before* the
user can background the app, even if the transfer hasn't hit trouble yet) — starting it lazily
"only when a retry is needed" risks exactly this exception.

### 3.3 Existing foreground-service precedent in this codebase is an unfinished stub — don't assume it's a working reference implementation

`AndroidMeasurementForegroundService`
(`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/measurement/ble/AndroidMeasurementForegroundService.kt`)
is explicitly a stub: `onStartCommand` has commented-out `TODO(BLE)` lines for the actual
`startForeground()` call, and its own doc comment says "To activate: 1. Add Kable dependency...".
It is useful as a **manifest-declaration pattern** (its `foregroundServiceType="connectedDevice"`
entry in `AndroidManifest.xml:67-72` shows the required manifest shape), and as a note that
POST_NOTIFICATIONS + a dedicated notification channel are already identified as prerequisites
elsewhere in this codebase — but it is not a tested, working foreground-service implementation to
copy wholesale. The new git-sync foreground service will be the first *functioning* one in this
codebase; budget for it accordingly rather than treating this as "just wire up the existing
pattern."

### 3.4 WorkManager's periodic fetch-only job and a new foreground-service clone/push are two independent, un-synchronized paths onto the same repo

`WorkManagerSyncScheduler` already runs a periodic (≥15 min) fetch-only job
(`WorkManagerSyncScheduler.kt:38-58`) independent of any user-initiated sync. Nothing in this
codebase serializes that against a concurrent user-initiated clone/fetch/push/merge on the same
`repoRoot` at the JGit level (§0's last bullet — `GitWorktreeLocks` only guards the SAF shadow
sync, not the underlying `.git`). Today this is a narrow, mostly-harmless race because both paths
are fast and JGit's own file-level locking (`.git/index.lock`) serializes the actual write. Once
this project adds (a) retries that hold operations open longer, and (b) a foreground service that
keeps a wizard-initiated clone alive through backgrounding, the window in which
`GitSyncWorker`'s periodic fetch can collide with a foreground-service-held clone/push on the same
repo grows substantially — and per §2.1, a lock collision looks like a `StaleLockFile` error, not
an obviously-transient one, so a naive retry classifier may not retry it, or worse, may
prematurely delete a lock that a *sibling in-process operation* (not a truly stale one) currently
holds. This needs an explicit per-`repoRoot` (not per-shadowKey) mutex or WorkManager
`ExistingWorkPolicy` guard that the periodic worker checks/yields to before starting when a
foreground sync is active — not present today.

---

## 4. Desktop-specific pitfalls

### 4.1 Laptop sleep/hibernate mid-transfer — the JVM does not "resume," the socket is simply dead on wake

The prior doc's §1 ("Desktop JVM: No Restrictions... The only risk is the user sleeping/hibernating
their laptop mid-fetch — handle as a network error with retry") already names this as in-scope but
doesn't go into mechanism. To be explicit for planning: a coroutine blocked inside a JGit
synchronous socket read (JGit's transport layer is blocking I/O, called from `withContext(
PlatformDispatcher.IO)` — confirmed, every `clone`/`fetch`/`push` in `JvmGitRepository.kt` runs
under `PlatformDispatcher.IO`, which resolves to `Dispatchers.IO`, i.e. a real blocking thread, not
a suspending one) does not get cancelled or resumed by the OS sleep/wake cycle — the underlying
TCP socket either times out on its own (if a read timeout is configured — currently **not**
configured, per §0) or the read call simply hangs until the OS eventually reports the connection
as broken (which can take minutes, since TCP keepalive on a sleeping machine is OS/router-dependent
and not something this repo controls). Without an explicit read timeout, a sleep/wake cycle can
leave a git operation "stuck" for an indefinite period post-wake rather than failing promptly into
the new retry logic — undermining the whole point of this project. Setting explicit connect/read
timeouts (already an in-scope deliverable) is a prerequisite for the retry layer to even be
reachable after a sleep/wake event, not an independent nice-to-have.

### 4.2 Multi-instance: nothing prevents two Desktop app instances from retrying into the same directory concurrently

Confirmed by code inspection: `JvmGitRepository` has no file-lock, PID-file, or single-instance
guard anywhere in `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/`. If a user runs two instances of
the desktop app (a real, if edge-case, scenario — e.g. launching from two shortcuts, or a second
instance surviving a crash-relaunch) pointed at the same graph, both processes' `GitSyncService`
instances would independently run their own retry/backoff loops against the same `repoRoot`,
racing on `.git/index.lock` acquisition from two separate JVMs (JGit's file lock *is*
cross-process safe at the OS-file-lock level for a single operation, but two independent retry
loops competing for the same lock indefinitely is a worse failure mode than today's single fast
failure — each process's retry attempts will repeatedly observe the other's lock as "busy," and
per §2.1, an automated retry that also tries to clear stale locks could delete a lock a *different
process* is legitimately still holding, corrupting that process's in-flight operation). This is
out of scope to fully solve (no cross-process coordination primitive exists in this codebase
today, and the requirements don't ask for a Desktop background-survival service), but the
retry/backoff and stale-lock-clearing logic should be scoped defensively: don't delete a lock file
solely because it's >60s old if this project's own retry loop is what's been failing against it
for 60+ seconds — that's the retry loop's own lock, not a truly abandoned one, in the
single-instance case, and in the multi-instance case it may belong to a sibling process, not this
one, so age alone is an insufficient staleness signal once retry loops themselves can run past 60s.

---

## 5. Cross-cutting pitfalls given Complexity 4

### 5.1 Blast radius of a buggy retry layer: turning today's clean fast failure into a slow, resource-consuming stuck state

This is not hypothetical for this codebase — §1.1 already shows the mechanism (two uncoordinated
retry layers) by which it happens, and §1.5/§3.1 show it compounds with battery and Android-14
service-timeout risk specifically. The common-case regression to design against explicitly: today,
a real permanent failure (bad URL, revoked token, repo deleted) fails in well under a second via
`runGitTransportOp`'s single catch. After this project, if retry classification (§2.4) is too
broad — retrying anything shaped like a `TransportException` regardless of cause — that same
permanent failure now takes as long as the full retry budget to surface, with a foreground service
and notification alive the whole time on Android. **The regression test that matters most for this
project is not "does retry work," it's "does a permanent failure still fail fast"** — the
requirements' own Success Metrics list "Regression: common-case behavior unchanged," and the
permanent-failure-fails-fast case is the sharpest version of that regression, not the happy-path
sync case.

### 5.2 Testing risk: "socket dies mid-transfer" has no existing deterministic harness in this codebase

Searched the existing test suite for a precedent: none of `JvmGitRepositoryTest.kt`,
`GitSyncServiceTest.kt`, `GitSyncServiceRateLimitRetryTest.kt`, or
`GitSyncServiceConflictResolutionTest.kt` inject a mid-transfer socket failure — the closest
existing precedent, `GitSyncServiceRateLimitRetryTest.kt`, tests real-time scheduling behavior
(its own doc comment, lines ~30-40, explains it deliberately uses `runBlocking` with real
wall-clock `delay()` rather than `kotlinx.coroutines.test.runTest`'s virtual time, because
`PlatformDispatcher.IO` resolves to a fixed, non-swappable `Dispatchers.IO` on the JVM — it cannot
be pointed at a `TestDispatcher`). This has two consequences for planning:

1. **Testing the scheduling/backoff logic** (does retry N happen after the right delay, does
   jitter/cap work, does cancellation-on-manual-retry work) can follow
   `GitSyncServiceRateLimitRetryTest.kt`'s existing real-wall-clock pattern — slow (seconds per
   test) but deterministic, an established precedent, not a new risk.
2. **Testing "does a mid-transfer socket death actually get detected and retried"** is a
   genuinely different and harder problem: it requires either (a) a fake/mock `Transport` at the
   JGit level that throws partway through `UploadPack`'s object negotiation (JGit does support
   pluggable transports and there is prior art for testing against a local `file://` or in-process
   transport, but building a transport that fails *partway through a multi-object transfer* on
   demand is nontrivial and not present anywhere in this codebase today), or (b) a real local git
   daemon/server the test can kill mid-clone (slow, flaky by nature — the class of test this
   project's own Feasibility Risks section already flags as a risk, not a solved problem). There is
   no existing scaffold for either in this repo. Plan for this as its own task with real time
   budget, not an afterthought bolted onto existing repository tests — and prefer (a) a
   fault-injecting fake at the `GitRepository` interface boundary (this codebase already has
   `StubGitRepository` in `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/testsupport/
   StubGitRepository.kt` for `GitSyncService`-level tests) for testing the *retry/backoff/UI*
   logic without needing a real socket failure at all, reserving any real-transport test for a
   small number of slow/flaky-tolerant integration tests, not the primary coverage mechanism.

### 5.3 Wizard Step 5 UX: retry/resume status needs a state machine distinct from today's `SyncState`

Not explored in depth here since it's UI-layer rather than a "pitfall" in the traditional sense,
but worth flagging for planning: `GitSyncService`'s existing `SyncState.RateLimited` variant
(referenced throughout `GitSyncService.kt`, e.g. lines 249-251) is the only existing precedent for
surfacing "operation is paused and will auto-retry" to the UI. It carries just
`retryAfterSeconds`. A retry/resume status for clone specifically needs more: which attempt number,
whether it's mid-shallow-clone vs. mid-merge, and (per §2.2) that "resume" for clone is really
"restart from a checkpoint," not a byte-accurate resume — the UI should not imply progress-bar
continuity it can't actually deliver once §2.2's architecture decision is made.

---

## Sources

- [eclipse-jgit/jgit#126 — "jgit push throwing TransportException: Connection reset"](https://github.com/eclipse-jgit/jgit/issues/126) — confirms retried `PushCommand` after a transport failure reports `UP_TO_DATE` rather than double-applying (§1.3).
- [ajoberstar/grgit#132 — "Cannot delete a cloned repo"](https://github.com/ajoberstar/grgit/issues/132) and [#33](https://github.com/ajoberstar/grgit/issues/33) — JGit `CloneCommand` resource/cleanup behavior on failure (§2.2).
- [Git shallow clone tutorial](https://blog.openreplay.com/git-shallow-clone/) and general `git-scm` fetch-options documentation on shallow-repo `merge-base` limitations (§2.3).
- CommitCommand Javadoc (multiple JGit versions, e.g. [6.2.0](https://archive.eclipse.org/jgit/site/6.2.0.202206071550-r/apidocs/org/eclipse/jgit/api/CommitCommand.html)) — `EmptyCommitException` default behavior, `setAllowEmpty` (§1.3).
- This repository's own `project_plans/git-integration/research/pitfalls.md` (2025-05-02) — battery/Doze/OEM background-kill baseline, extended not repeated here.
