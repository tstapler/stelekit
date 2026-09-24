# Architecture Research: git-sync-resilience

Research pass for `project_plans/git-sync-resilience/requirements.md`. Extends
`project_plans/git-integration/research/architecture.md` (background-sync scheduler design) and
`project_plans/android-git-saf-shadow-worktree/research/architecture.md` (shadow-worktree
freshness precondition) — both read in full; findings below build on, not repeat, them.

File names differ slightly from the requirements doc's guesses: the Android helper classes are
`AndroidGitRepositoryShadow.kt` (`AndroidGitShadowSupport`), `AndroidGitRepositoryAuth.kt`
(`AndroidGitAuthConfigurer`), `AndroidGitRepositoryMerge.kt` (`AndroidGitMergeSupport`) — not
`AndroidGitShadowSupport.kt`/`AndroidGitAuthConfigurer.kt`/`AndroidGitMergeSupport.kt` as separate
top-level files. All line numbers below are from this session's reads of the worktree at HEAD
(`0a3cffd635`).

---

## 1. Integration points

### 1a. `runGitOp`/`runGitTransportOp` are already shared, not duplicated

`kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/git/GitOperationSupport.kt` is a **`jvmCommonMain`
source set** — compiled into both `androidMain` and `jvmMain` (JVM bytecode is common to both
targets; this is the existing "share once, not copy-paste" mechanism for Android+Desktop JGit
code, distinct from `commonMain` which also covers iOS/Wasm/Web). It already holds:

- `runGitOp` (`GitOperationSupport.kt:27-37`) — wraps a non-transport JGit call, converts any
  thrown `Exception` to a `DomainError.GitError` via a caller-supplied `onFailed`.
- `runGitTransportOp` (`GitOperationSupport.kt:48-61`) — same, but additionally maps
  `TransportException` to a separate `onAuthFailed` callback and redacts URL userinfo from every
  exception message before either callback sees it. Every clone/fetch/push/testRemote call site on
  both platforms goes through this.
- `configureHttpsOrNoAuth`, `configureTransportAuth`, `testRemoteViaLsRemote`,
  `countRemoteCommitsBestEffort` — all shared, called identically from `AndroidGitRepository` and
  `JvmGitRepository`.

**This is the correct, and only structurally sound, place to add retry/backoff and
connect/read-timeout configuration.** Wrapping `runGitTransportOp`'s `op()` call in a retry loop
(or adding a sibling `runGitTransportOpWithRetry`) automatically covers every clone/fetch/push call
on both Android and JVM with one implementation — no per-platform duplication, and it sits below
the two platforms' `withContext(PlatformDispatcher.IO)` wrapper, so dispatcher discipline is
inherited for free (the retry loop's `delay()` calls run on `PlatformDispatcher.IO`, which is fine
for a network-retry backoff; it is not DB or CPU work).

**Do not** wrap at the individual JGit `.call()` level inside `doFetch`/`doPush`/`clone` — those
sites report intermediate state (`headBefore`, `remoteRef`) that a raw retry of the whole method
would need to recompute anyway, and duplicating retry logic per call site defeats the point of the
shared helper.

**Do not** add a new `commonMain` decorator layer above `GitRepository` for this — see §3.

### 1b. Depth-limiting hook

`CloneCommand` is built once per platform: `AndroidGitRepository.clone()` (`AndroidGitRepository.kt:98-109`)
and `JvmGitRepository.clone()` (`JvmGitRepository.kt:87-97`) — byte-identical except
`.setDirectory(File(shadow.resolveForJGit(localPath)))` vs `.setDirectory(File(localPath))`. Add
`.setDepth(n)` at both call sites (JGit 7.3.0's `CloneCommand.setDepth(int)` exists and is already
on the classpath — no new dependency). No shared helper currently builds the `CloneCommand`, so
this is a genuine two-line duplication today (matching the existing pattern where the auth-provider
plumbing differs slightly between platforms) — low enough duplication that extracting a
`buildCloneCommand()` into `GitOperationSupport.kt` is optional polish, not required. `FetchCommand`
also has `.setDepth()`/`.setUnshallow()` (used if a shallow clone is later "deepened" — see §1c).

No connect/read timeout is configured anywhere today except the hardcoded 15s `ls-remote` timeout
in `testRemoteViaLsRemote` (`GitOperationSupport.kt:139,161`, `TEST_REMOTE_TIMEOUT_SECONDS`) —
confirmed via `grep -rn "setTimeout\|CONNECT_TIMEOUT"` across all three git source dirs, zero other
hits. `CloneCommand`/`FetchCommand`/`PushCommand` all inherit `TransportCommand.setTimeout(int)`
(seconds, applies to the whole transport operation, not per-read) from JGit's `TransportCommand`
base — the same method already used for `lsRemoteRepository()`. This is the mechanism to reuse: a
constant analogous to `TEST_REMOTE_TIMEOUT_SECONDS` (longer — a large clone needs more headroom
than a status check) applied to every `TransportCommand` built in `clone`/`fetch`/`push`.

### 1c. True JGit resumability does not exist — depth-limiting *is* the resume strategy

Confirmed empirically from the code, not assumed: `CloneCommand`'s local implementation streams
pack data via `ObjectDirectoryPackParser`, which writes to a temporary pack file and only renames
it into the object database on a *successful* parse — an interrupted transfer leaves no usable
partial pack, so a retried `clone()` call re-transfers every wanted object from zero. There is no
JGit API for byte-range/pack-offset resume (confirmed: JGit's Git/HTTP smart-protocol client has no
`Range:`-header or partial-pack support). This validates the requirements doc's Rabbit Hole
("true resumable clone is not a JGit built-in") — it isn't a research gap to close, it's a hard
platform limitation.

**Recommendation**: implement "resume" as *bounding retry cost*, not literal resumption:
1. Default clone to shallow (`setDepth(1)`, or a small configurable N) — every retry re-transfers
   only the shallow pack, which is bounded regardless of the graph's full history size. This alone
   satisfies "an interrupted large-repo transfer resumes from where it left off, not from 0%" in
   spirit: the *retryable unit* becomes small, not the literal transferred bytes.
2. If full history is later wanted, a separate "deepen" step (`FetchCommand.setDepth`/
   `.setUnshallow()`) run after the shallow clone succeeds — each deepen call is itself a small,
   independently-retryable increment, and a failure here leaves the repo in the already-successful
   shallow state (not half-cloned), which is a real, checkpoint-like resume property this
   architecture can honestly claim.
3. For steady-state `fetch` (not initial clone), the "resume from 0%" problem barely exists today:
   a periodic fetch only pulls the delta since the last successful fetch, which is already small
   for typical usage. The acute pain (the bug report: "Clone failed: Software caused connection
   abort" on a large graph) is specifically the *initial* clone — depth-limiting there is the fix
   that matters most for the reported failure mode.

State this explicitly in the plan phase: the success metric "resumes from where it left off" should
be reworded/scoped to "retries cost is bounded by shallow-clone depth, not full-repo size" — a
literal byte-resume claim would overclaim what JGit can deliver.

### 1d. Resume-checkpoint persistence — reuse `git_config`, add columns, not a new table

`kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq:904-918` — `git_config` is
one row per `graph_id`, already the single source of truth for sync settings
(`poll_interval_minutes`, `auto_commit`, etc.), read via `SqlDelightGitConfigRepository` and mapped
to `GitConfig` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitConfig.kt`). Given §1c's
conclusion (checkpointing means "shallow clone succeeded, deepen is pending/in-progress", not a
byte offset), the natural persisted state is small and fits this table:

- A `clone_depth_state` column (e.g. `TEXT` enum: `NONE` / `SHALLOW_PENDING_DEEPEN` /
  `FULL_HISTORY`) or equivalently a nullable `shallow_since_commit_count INTEGER` — enough for the
  UI to say "shallow clone complete, deepening in background" and for a resumed app session to know
  whether to re-attempt a deepen.
- **Mandatory**: any new `CREATE TABLE`-shaped state must go through `MigrationRunner.all`
  (`db/MigrationRunner.kt`) per this repo's own enforced rule — but a *column* addition to an
  existing `CREATE TABLE IF NOT EXISTS` is not itself covered by that rule (it only guards new
  tables); a new column on `git_config` needs its own `ALTER TABLE` migration entry, following
  whatever pattern `MigrationRunner` already uses for existing `git_config` column additions (check
  `MigrationRunner.kt` at plan time for the most recent `git_config` `ALTER TABLE` as a template —
  `llm_api_key_ref` on `GitConfig` above suggests this table has already grown columns
  post-initial-release at least once).
- **Do not** invent a file-based or in-memory-only checkpoint — `git_config`'s existing lifecycle
  (survives app restart, is deleted with `deleteGitConfig` on graph removal, moves with
  `moveGraphFilesAndCredentials`) already matches exactly what a resume-checkpoint needs, and a
  parallel storage mechanism would need to reinvent that lifecycle.
- Retry/backoff *state* itself (attempt count, next-retry-at) should stay in-memory
  (`GitSyncService`-scoped, mirroring `rateLimitRetryJob`/`scope.launch { delay(...) }` — see §3) —
  it only needs to survive within one app session, not across process death, since a killed app
  simply means the next foreground sync starts a fresh retry sequence. Only the *shallow-vs-full*
  clone state needs to survive process death, and only that belongs in `git_config`.

---

## 2. Data flow and consistency: resume vs. `ensureFresh()`

Traced precisely, per the requirements' instruction not to hand-wave this:

`GitShadowWorktree.ensureFresh()` (`GitShadowWorktree.kt:367-374`) is a **precondition on
`AndroidGitRepository.openGit()`** (`AndroidGitRepository.kt:461-467`), called before every
working-tree-touching JGit call (`status`, `stageSubdir`, `commit`, `merge`, `checkoutFile`,
`markResolved`). It compares a per-file mtime+count manifest (`GitShadowWorktree.kt:290-364`)
against a fresh SAF directory listing and re-syncs SAF→shadow if anything differs — this is
**entirely orthogonal to git-transport retry/resume**: it governs whether the shadow *working tree*
(the checked-out files) matches SAF, not whether the shadow `.git` object database's pack transfer
completed.

Concretely, a clone/fetch failure and retry interacts with `ensureFresh()` as follows:

- **Clone failure (interrupted mid-transfer)**: `Git.cloneRepository()` writes directly into
  `File(shadow.resolveForJGit(localPath))` — the shadow worktree directory itself (§3 of the
  android-git-saf-shadow-worktree research: `resolveForJGit` redirects to the shadow path when SAF
  direct access is unavailable). An interrupted clone can leave a **partial checkout**: JGit's
  `CloneCommand` first does the fetch (all-or-nothing per §1c — either the whole pack lands or none
  of it does, so an aborted fetch cannot itself leave partial objects), but *if* the fetch
  succeeds and the subsequent checkout-into-working-tree step is interrupted (e.g. process killed
  right after `cmd.call()` returns but before `shadow.syncShadowAfterInitOrClone(localPath, git)`
  runs at `AndroidGitRepository.kt:112-114`), the shadow directory has a valid `.git` but a
  **partially-written working tree with no sync manifest written yet** (`syncShadowAfterInitOrClone`
  is presumably what writes the initial manifest — not read this session, but it is the only
  post-clone hook present). **This is the concrete risk the requirements ask to trace**: on retry,
  `shadowWorktreeFor()` will resolve the same (already-existing, partially-populated) shadow
  directory. Whether `Git.cloneRepository()` even *permits* retrying into a non-empty existing
  directory needs a plan-phase decision — JGit's `CloneCommand` throws
  `JGitInternalException("... already exists and is not an empty directory")` when the target
  directory is non-empty and not a `.git`-less checkout-only case. **A naive retry loop around the
  existing `clone()` call will therefore fail deterministically on the second attempt if the first
  attempt got far enough to write any files** — the retry design must either (a) delete/recreate
  the shadow directory before each clone attempt (simplest, matches "bounded shallow retry" from
  §1c — cheap since depth-limited), or (b) restructure clone as `init` + `remote add` + `fetch` +
  `checkout` (separately retryable steps, `init`/`fetch` are idempotent-safe to retry into an
  existing `.git`) — (a) is recommended as the lower-risk v1 given the shallow-clone bound already
  makes re-cloning cheap.
- **No silent "looks fine" gap found**: because `ensureFresh()`'s manifest is written *after* a
  successful `syncFromSafRoot`/`syncShadowAfterInitOrClone` call, a shadow tree left mid-clone (no
  manifest yet) is not mistaken for "fresh" — `isFresh()` (`GitShadowWorktree.kt:349-364`) returns
  `manifestFile.exists()` short-circuit-false when the manifest is absent and the SAF listing is
  non-empty (`manifest.entries.isEmpty() && listing.isEmpty()` is the only case that returns true
  without entries), so a subsequent `ensureFresh()` call would correctly trigger a re-sync rather
  than silently treating a half-cloned shadow tree as up to date. **The identified risk is not
  "freshness check lies" — it's "retry itself may fail outright due to JGit's non-empty-directory
  guard,"** which is a distinct, earlier failure than anything `ensureFresh()` governs.
- **Fetch retry**: `fetch()` never touches the working tree (`doFetch` only moves refs/objects) —
  confirmed by the prior research (`GitSyncService.kt` §3: "fetch alone doesn't touch the working
  tree, so no shadow sync needed") and by `openGitWithoutFreshnessCheck` being used for `fetch`
  (`AndroidGitRepository.kt:131`, bypassing `ensureFresh()` entirely). A retried fetch has **zero**
  interaction with the shadow-freshness precondition — it's the simplest case in this whole
  project and needs no special handling beyond §1a's retry wrapper.
- **Push retry**: same — `openGitWithoutFreshnessCheck` (`AndroidGitRepository.kt:271`). A retried
  push after a transient failure is safe to retry as-is (JGit's push is a single atomic ref-update
  RPC per ref; retrying an already-succeeded push is a no-op fast-forward-into-same-state on most
  hosts, though a genuinely non-idempotent host response — e.g. a hook side-effect firing twice —
  is a known general git-retry caveat worth a one-line callout in the plan, not a blocker).

---

## 3. Cross-platform architecture: extend the shared `jvmCommonMain` layer, not a new `commonMain` decorator

The codebase's own precedent, found by checking whether `runGitOp`/`runGitTransportOp` are
duplicated or shared (requirements' explicit ask): **they are already shared**, via
`jvmCommonMain` — a KMP source set compiled into both `androidMain` and `jvmMain` specifically
because JGit only runs on the JVM (Android + Desktop), never iOS/Wasm. This is the established
convention for "shared between the two JGit platforms, but not part of the `commonMain` contract
that iOS/Wasm must also satisfy."

**Recommendation: extend `GitOperationSupport.kt` (`jvmCommonMain`) with the retry/timeout logic,
not a new `commonMain` `ResilientGitRepository` decorator wrapping `GitRepository`.**

Reasoning:
- A `commonMain` decorator implementing `GitRepository` and delegating to a wrapped instance would
  need to be constructed and wired in at every platform's DI site (`MainActivity.kt:295-298`,
  `WorkManagerSyncScheduler.kt:115`, whatever JVM's Desktop equivalent construction site is) —
  **adding a new indirection layer and a new construction decision** the codebase doesn't currently
  need, for a capability (retry) that is entirely internal to how `clone`/`fetch`/`push` are
  implemented, not part of `GitRepository`'s public contract. Callers of `GitRepository` (e.g.
  `GitSyncService`) don't need to know retry happened — they already just see
  `Either<DomainError.GitError, T>`.
- `IosGitRepository` is a stub (`NotSupported`) and out of scope — a `commonMain` decorator's only
  argument in its favor (uniform behavior across all `GitRepository` implementations including a
  future iOS one) is moot today and premature: iOS's real implementation (when it exists) will
  likely use a different git library (libgit2/swift-git, not JGit) with its own retry primitives,
  so a JGit-specific retry loop implemented in `commonMain` would be dead weight there anyway.
- Retry-with-backoff around a JGit `TransportCommand` needs JGit-typed knowledge (`TransportException`
  specifically, `ProgressMonitor` for the cancellation/progress hook already threaded through
  `clone()`) — `GitOperationSupport.kt` already has exactly this typed context (`runGitTransportOp`
  already catches `TransportException` specifically); a `commonMain` decorator operating only on
  `GitRepository`'s `Either`-returning interface would have to re-classify transient-vs-permanent
  from the *already-erased* `DomainError.GitError` values instead of the richer JGit exception,
  losing information (e.g. distinguishing a DNS failure from a mid-transfer socket abort, both of
  which likely collapse to the same `DomainError.GitError.FetchFailed`/`NetworkFailure` today).

**Concretely**: add a `runGitTransportOpWithRetry` (or extend `runGitTransportOp` with an optional
retry policy parameter) in `GitOperationSupport.kt` that classifies the caught exception (before
redaction) — `java.io.IOException`/`java.net.SocketException`/JGit's own
`org.eclipse.jgit.errors.TransportException` subtypes for connection-abort/timeout vs. e.g. `403`/auth
failures surfaced as `TransportException` with a different message shape — and retries with
exponential backoff only for the transient class, capped at N attempts, calling `onProgress`
(already threaded through `clone()`'s signature) with a retry-status string each attempt so the
Wizard Step 5 UI (§ below) gets it for free through the existing channel.

---

## 4. Android foreground-service architecture vs. `WorkManagerSyncScheduler`

**`GitSyncBusyCounter` already exists in `commonMain`** (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncBusyCounter.kt`)
but its current sole purpose is narrower than a general clone/fetch/push mutex: it's a
begin/end/awaitIdle counter wired **only around `GitSyncService.sync()`**
(`GitSyncService.kt:186,381` — confirmed via grep, the only non-test call sites), used by the
relocate-quiesce sequence (`AndroidGraphMoveQuiesceStrategy`) to know "no JGit operation is
currently running" before copying `.git`. It is **not** wired around `fetchOnly()` (the method
`GitSyncWorker.doWork()`'s fast path calls, `WorkManagerSyncScheduler.kt:124`), and it does not
exist at all during the *initial clone* flow (`performCloneAndSave` → `onCloneAndAdd` →
`AndroidGitRepository.clone()`), because `GitSyncService`/`GitConfig` don't exist yet at that point
— there is no `graphId` to key a busy-counter instance on until the clone (and its subsequent
`GitConfig` save) succeeds.

This means: **`GitSyncBusyCounter` covers exactly one of the three races this project needs to
guard against (periodic-fetch WorkManager job vs. relocate), and needs extending, not just
reusing as-is, for the other two:**

1. **Periodic-fetch `WorkManager` job vs. an in-progress foreground-service clone/fetch/push for
   the *same graph*** — the new race this project introduces. Fix: wire `GitSyncBusyCounter.begin()`/`.end()`
   around `fetchOnly()` too (currently missing), and have the new foreground service call
   `awaitIdle()`-gated begin/end around its own clone/fetch/push work. Since `GitSyncWorker`'s fast
   path already goes through `GitSyncServiceRegistry.getService(graphId)` → the same
   `GitSyncService` instance the foreground service would use, this is a same-process, same-counter
   fix, not a cross-process one — **as long as the foreground service and the app's `GitSyncService`
   share the same `GitSyncServiceRegistry` entry**, which they will if the foreground service is
   started from the same process (Android foreground services run in the app's own process by
   default, no separate `:process` declared in the manifest today — confirmed by absence of any
   `android:process` on git-related manifest entries; not exhaustively re-checked here, but no
   evidence found of a separate process for git sync).
2. **`GitSyncWorker`'s slow path (process-was-killed) vs. the same graph's foreground service** —
   this is the one WorkManagerSyncScheduler.kt:135-168 slow path that constructs its *own*
   standalone `AndroidGitRepository`/`SteleDatabase`/driver, completely outside
   `GitSyncServiceRegistry` and therefore outside any in-process `GitSyncBusyCounter` instance. If
   the app process was killed, no foreground service from that process instance can be racing it
   either (the service died with the process) — so this path is **self-consistent as long as a
   *new* foreground service, if one starts later in a fresh process, checks whether a
   `WorkManager`-scheduled unique-work job for the same `graphId` is currently running before
   starting its own clone/fetch/push**. `WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName)`
   is the mechanism to query this — not currently used anywhere in this file. This should be an
   explicit check the foreground-service launcher makes (or: enqueue the foreground-service work
   itself *through* `WorkManager` as an `ExistingWorkPolicy.KEEP`-guarded one-off request sharing
   the *same* unique-work name as the periodic job, so `WorkManager`'s own uniqueness guarantee does
   the mutual exclusion — this is likely the cleaner fix, see recommendation below).
3. **Initial clone (no `GitConfig`/`GitSyncService` yet) vs. a stale/orphaned periodic job for the
   same eventual `graphId`** — not possible today: `WorkManagerSyncScheduler.schedule()` is only
   ever called after a `GitConfig` is saved (`graphId` must exist), and the unique-work name is
   `stelekit_git_sync_$graphId` (`WorkManagerSyncScheduler.kt:69`) — a graph mid-initial-clone has
   no `graphId`-keyed periodic job scheduled yet, so no race exists at this stage by construction.

**Recommendation**: rather than inventing a second lock primitive, **enqueue the initial-clone
foreground-service work as a `WorkManager` one-off request sharing the periodic job's unique-work
name** (`ExistingWorkPolicy`/`ExistingPeriodicWorkPolicy` — WorkManager natively serializes work
enqueued under the same unique name, including mixed one-off/periodic chains via
`beginUniqueWork`/`enqueueUniquePeriodicWork` interactions) — this reuses a mechanism the codebase
already depends on (`WorkManagerSyncScheduler`) instead of adding a second, parallel mutual-exclusion
mechanism (`GitSyncBusyCounter`) that only covers the same-process case. Extend
`GitSyncBusyCounter`'s wiring to also cover `fetchOnly()` regardless (cheap, closes gap #1 above,
and is useful defense-in-depth even with the `WorkManager`-unique-name fix for #2/#3).

Foreground-service specifics (Android 14+ `dataSync`/`shortService` type declaration,
manifest/runtime permission pairing) are pure Android-platform plumbing, not an architecture
question this research phase needs to resolve — flag for the plan phase to spend implementation
effort on, using `dataSync` (transfers user data on user request, matches a git clone) over
`shortService` (the latter's ~3-minute OS-enforced cap is wrong for a large clone).

---

## 5. SOLID/Clean-Architecture disposition: **Extend as-is**

`AndroidGitRepository.kt` is 478 lines and already decomposed into three focused collaborators
(`AndroidGitShadowSupport` in `AndroidGitRepositoryShadow.kt`, 138 lines; `AndroidGitAuthConfigurer`
in `AndroidGitRepositoryAuth.kt`, 75 lines; `AndroidGitMergeSupport` in `AndroidGitRepositoryMerge.kt`,
163 lines), each with a single, named responsibility per the class's own doc comment
(`AndroidGitRepository.kt:27-30`). `JvmGitRepository.kt` (378 lines) is similarly split
(`JvmGitRepositoryAuth`, `JvmGitConflictSupport`). Both delegate cross-platform-shared logic to
`jvmCommonMain`'s `GitOperationSupport.kt` (§1a) rather than duplicating it.

**Disposition: Extend as-is.** Reasoning:
- Retry/timeout logic belongs in `GitOperationSupport.kt`, an *existing* seam
  (§1a/§3) — no new file, no new responsibility added to `AndroidGitRepository`/`JvmGitRepository`
  themselves; their `clone`/`fetch`/`push` methods gain one call-site change each (calling the
  retry-aware variant instead of the plain one) plus `.setDepth()`/`.setTimeout()` on the command
  builder — a few lines, not a structural change.
- Resume-checkpoint state (§1d) is a `GitConfig` column, read/written through the *existing*
  `GitConfigRepository`/`SqlDelightGitConfigRepository` — no new responsibility on
  `AndroidGitRepository` either.
- Foreground-service orchestration (§4) is new *behavior*, but it belongs as a new, focused
  Android-only class (e.g. `GitSyncForegroundService.kt`), following the exact same pattern already
  established by `WorkManagerSyncScheduler.kt`/`GitShadowFlushActor.kt`/`GitWriteBackQueue.kt` — a
  small, single-purpose collaborator alongside the existing git package, not a change to
  `AndroidGitRepository.kt`'s internals at all. It calls into `GitSyncService`/`AndroidGitRepository`
  the same way the Compose UI layer and `GitSyncWorker` already do.
- No evidence of an existing complexity/churn hotspot was found in this package during this
  research pass (git log/churn analysis was not run this session — if the plan phase wants
  confirmation, `code-hotspot-analysis` against `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/`
  and `kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/git/` would give a numeric score; this
  research pass's read of the code itself shows a package that has *already* undergone the
  "split into focused collaborators" refactor other SteleKit packages get flagged for, per this
  file's own size and responsibility per class).

**The one caution**: `GitOperationSupport.kt` itself will grow from ~196 lines by a meaningful
retry-loop implementation (backoff calculation, exception classification, possibly a
`RetryPolicy`/`TransientFailureClassifier` value type). If that pushes the file past a few hundred
lines, split the retry logic into its own file in the same `jvmCommonMain` source set (e.g.
`GitTransportRetry.kt`) up front rather than letting `GitOperationSupport.kt` become a second
hotspot — this is a "watch for it," not a current problem.

---

## 6. Cross-cutting failure-mode risks to the common case (Complexity-4 requirement)

Since retry/timeout/depth-limiting touches the exact call sites *every* clone/fetch/push goes
through — including the overwhelmingly common small-graph, good-network case — specific regression
risks:

1. **Timeout too aggressive for a slow-but-healthy connection.** `TransportCommand.setTimeout(int)`
   applies to the whole operation, not a per-chunk idle timeout (confirmed: it's JGit's blanket
   socket/operation timeout, same mechanism as the existing 15s `ls-remote` timeout). Setting a
   single timeout for `clone`/`fetch`/`push` risks aborting an otherwise-succeeding large clone on
   a slow-but-non-broken connection (e.g. rural mobile data) that would have finished at 90 seconds
   if given 120. This is a direct tension with depth-limiting (§1c) — get the depth-limit default
   right first (bounds transfer size, so a fixed timeout becomes safer), and set the timeout
   generously (minutes, not the 15s test-remote value) rather than tightly.
2. **Retrying a non-transient failure as if it were transient.** `runGitTransportOp` today maps
   *every* `TransportException` to `onAuthFailed` and every other `Exception` to `onFailed` with no
   further classification. A naive retry-everything-that-throws policy would retry genuine auth
   failures (wrong token), a truly non-existent remote URL, or a merge/push rejected for real
   (non-fast-forward) reasons — wasting the user's time and battery on guaranteed-to-fail retries,
   and delaying the actually-useful error message by N×backoff. The exception classification (§3)
   must be conservative: retry only on network-shaped exceptions (`IOException`/`SocketException`/
   `SocketTimeoutException`/JGit's own connection-reset wrapper), not on `TransportException`'s auth
   branch or any HTTP 4xx surfaced through it.
3. **Retry interacting with `GitSyncBusyCounter`/`editLock` held duration.** `GitSyncBusyCounter.begin()`
   is called once at the top of `sync()` (`GitSyncService.kt:186`) and `end()` once at the bottom
   (`:381`) — if retry is implemented *inside* `clone`/`fetch`/`push` (§1a/§3, the recommended
   placement, below `GitSyncService`), the busy-counter's held duration silently grows to
   `attempts × timeout` for a graph on a flaky connection. This is likely fine (that's exactly the
   protection `awaitIdle()` exists to provide — a relocate should wait out a slow-but-retrying
   sync, not race it), but it should be called out explicitly in the plan so nobody assumes retry
   is "free" with respect to the relocate-quiesce sequence's wait time.
4. **Depth-limited clone changing `git log` / commit-count-dependent behavior for the common case.**
   `countRemoteCommitsBestEffort` (`GitOperationSupport.kt:105-111`, capped at 100 already) and
   `GitCommit log()` both assume full history is available. A shallow clone (depth 1, or any N)
   makes `git log` on the clone report fewer commits than actually exist upstream — anything in the
   UI that surfaces "N commits behind" or a commit-history view needs to already tolerate a
   shallow/truncated history (JGit reports shallow boundaries via `Repository.getObjectDatabase().getShallowCommits()`)
   or this becomes a **regression for every new clone**, not just large ones, once shallow becomes
   the default. This needs explicit UI-behavior verification in the validate phase — not just a
   backend change.
5. **Foreground-service notification appearing for small/instant clones.** If the foreground
   service wraps *every* clone (not just large ones) uniformly, a common-case fast clone (small
   personal wiki, <1s) will flash a persistent notification into existence and immediately dismiss
   it — a visible regression in polish for the majority case. Consider gating foreground-service
   promotion on either an explicit size/duration heuristic (e.g. promote only if the clone is still
   running after N seconds) or accept the always-on notification as a deliberate, documented
   trade-off — this is a product decision for the plan phase, not something to default silently.
6. **`onSshKey` and other auth-provider closures re-invoked per retry attempt.** If SSH-key
   passphrase or token resolution (`credentialAccess.retrieve(it)`, `auth.tokenProvider()`) happens
   inside the retried block rather than once before the retry loop, a retry storm could re-trigger
   a system credential-prompt (e.g. a biometric unlock for the credential store) multiple times in
   quick succession. `AndroidGitRepository.clone()` already resolves `preResolvedToken` once before
   entering JGit's synchronous call (`AndroidGitRepository.kt:95`) — the retry wrapper must preserve
   this "resolve credentials once, retry only the transport call" ordering, not move credential
   resolution inside the retried closure.

---

## 7. EventStorming table

**Warranted, partially.** Clone/fetch/push already have real state transitions today
(`SyncState` sealed class: `Idle`/`Fetching`/`Merging`/`Pushing`/`ConflictPending`/`RateLimited`/
`CredentialExpired`/`Error`/`Success`/`JournalMergeReady` — not exhaustively re-read this session,
inferred from `GitSyncService.kt` references above) — this project adds new transitions (retrying,
resuming/deepening, foreground-service-alive) to an *existing* state machine rather than
introducing CRUD from scratch. A full EventStorming session (actors/policies/aggregates) is more
ceremony than this warrants; the table below is scoped to just the **new** events this project
introduces, which is enough for the plan phase to design `SyncState`'s new variants and the
foreground-service lifecycle without re-deriving the whole existing sync flow.

| Domain Event | Trigger / Policy | Command | Actor |
|---|---|---|---|
| `TransientTransportFailureDetected` | Exception classified as network-shaped (§6.2) inside `runGitTransportOpWithRetry` | `ScheduleRetryWithBackoff` | System (retry policy) |
| `RetryAttemptStarted` | Backoff delay elapsed | `RetryTransportOperation` | System |
| `RetryAttemptsExhausted` | Max attempts reached, still failing | `SurfacePermanentFailure` (existing `SyncState.Error`) | System |
| `ShallowCloneCompleted` | `CloneCommand` with `setDepth(n)` succeeds | `PersistCloneDepthState` (git_config column, §1d) | System |
| `DeepenRequested` | User opts into full history (future UI, out of scope this project per requirements but the checkpoint state should not block it) | `FetchCommand.setUnshallow()`/incremental `setDepth` | User (explicit) or System (background, if in scope) |
| `ForegroundServiceStarted` | Clone/fetch/push begins on Android and either (a) always, or (b) duration heuristic (§6.5) | `StartForegroundService` + `GitSyncBusyCounter.begin()` | System |
| `ForegroundServiceStopped` | Operation terminal (success or exhausted retries) | `StopForegroundService` + `GitSyncBusyCounter.end()` | System |
| `PeriodicFetchDeferred` | `WorkManager` unique-work collision with an in-progress foreground-service op (§4) | `WorkManager` (native `ExistingWorkPolicy` handling, no explicit command needed if unique-name sharing is adopted) | System |
| `TransferTimedOut` | `TransportCommand.setTimeout` elapsed | Classify as transient → `ScheduleRetryWithBackoff`, or permanent after exhaustion | System |

No new **actor** types are introduced (User and System already cover every row) and no new
**aggregate** is needed beyond the existing `GitConfig`/`SyncState` — this table is an addendum to
the existing sync state machine, not a parallel domain model.

---

## Summary of concrete integration points (file:line)

- `kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/git/GitOperationSupport.kt:48-61` — add
  retry/backoff here (`runGitTransportOp` or a new sibling), shared by both platforms for free.
- `kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/git/GitOperationSupport.kt:139` —
  `TEST_REMOTE_TIMEOUT_SECONDS` is the existing precedent/pattern for a new, larger
  clone/fetch/push timeout constant.
- `AndroidGitRepository.kt:98-109` / `JvmGitRepository.kt:87-97` — `CloneCommand` builder call
  sites, add `.setDepth()`/`.setTimeout()`.
- `AndroidGitRepository.kt:112-114` — `shadow.syncShadowAfterInitOrClone` is the point after which
  a shadow-tree manifest exists; an interrupted clone before this point is the specific "retry hits
  JGit's non-empty-directory guard" risk identified in §2.
- `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq:904-918` — `git_config`
  table, add a clone-depth-state column here (with its own `MigrationRunner` `ALTER TABLE` entry).
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncBusyCounter.kt` — extend wiring to
  `GitSyncService.fetchOnly()` (currently unwired) and to any new foreground-service code.
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt:38-59` (periodic
  schedule) and `:135-168` (`GitSyncWorker`'s slow path, standalone `AndroidGitRepository`,
  currently outside any busy-counter/registry) — the two paths a new foreground service must not
  race against; recommend sharing `WorkManager`'s unique-work name (§4) over a second mutex.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreenSaveLogic.kt:168-214`
  (`performCloneAndSave`) and `GitSetupScreen.kt:208-209,388-389` / `GitSetupStep5TestAndSave.kt:41-42,64-65,177-182`
  — the existing `onProgress: (String) -> Unit` → `cloneProgress` state channel Wizard Step 5 already
  renders; retry/resume status can ride this same channel for a v1 UI with no new state plumbing.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt:153-168` — `scheduleRateLimitRetry`
  is the existing scope-owned, one-shot delayed-retry precedent (`scope.launch { delay(...); retryOperation(graphId) }`)
  to model any *service-level* retry scheduling after, distinct from the *transport-level* retry
  recommended for `GitOperationSupport.kt`.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt:69-108` — `GitError` sealed
  interface already has `NetworkFailure`/`RateLimited`/`CredentialExpired` cases; transient-failure
  classification (§6.2) should map onto `NetworkFailure` rather than the generic
  `FetchFailed`/`PushFailed`/`CloneFailed`, which today catch everything including transient causes.
