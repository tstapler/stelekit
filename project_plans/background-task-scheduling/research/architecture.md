# Architecture research: neutral background-task scheduling port

Date: 2026-11-10. Inputs: `../requirements.md`, `project_plans/git-sync-resilience/` (research/architecture.md, decisions/ADR-002), existing code. Labels: **VERIFIED** = opened/grepped in this worktree; **INFERRED** = design judgement, not yet run.

Paths below are relative to `kmp/src/` unless noted. Not run: no build or test was executed for this document (read-only research).

## 1. Current state (VERIFIED)

| Fact | Source |
|---|---|
| Port is git-named, takes only `intervalMinutes`; `cancel()` has no kind/graph args | `commonMain/.../git/BackgroundSyncScheduler.kt:9-15` |
| Per-graph instance comes from `BackgroundSyncSchedulerFactory(minimumIntervalMinutes, build)`; `None` for desktop/web/tests | `BackgroundSyncScheduler.kt:22-34` |
| `BackgroundSyncScheduler` is a wiring-gate seam; two catalog lines (`scheduled-sync-tick`, `periodic-work-enqueued`) | `businessTest/kotlin/.../wiring/WiringAudit.kt:18-25`; `businessTest/resources/wiring-catalog.txt:7-8` |
| Android adapter hard-codes `NetworkType.CONNECTED`, 15-min floor, unique name `stelekit_git_sync_$graphId`, `ExistingPeriodicWorkPolicy.UPDATE` | `androidMain/.../git/WorkManagerSyncScheduler.kt:48-70, 83` |
| `pauseFor`/`resumeFor` cancel/re-enqueue at the 15-min minimum (not the configured interval) around graph moves | `WorkManagerSyncScheduler.kt:96-110` |
| Worker returns `Result.failure()` never `retry()` once internal retry is exhausted (ADR-002: single retry owner) | `WorkManagerSyncScheduler.kt:150-158`; `git-sync-resilience/decisions/ADR-002-single-retry-owner.md` |
| Worker has a fast path (live `GitSyncService.scheduled.run`) and a cold path (`ColdSyncRunner`, fetch only) | `WorkManagerSyncScheduler.kt:143-200` |
| `ScheduledSyncPolicy.decide` is pure; `Skip`/`FetchOnly`/`Run`; backoff 1..30 min | `commonMain/.../git/ScheduledSyncPolicy.kt:98-124` |
| `ScheduledSyncRunner.run(graphId): TickOutcome` gathers inputs, asks policy, runs full sync or fetch | `ScheduledSyncRunner.kt:46-64` |
| `TickOutcome` = Success / Fetched / Failure / Skip(SkipReason); `SkipReason` = Busy / Offline / Policy(...) | `PeriodicSyncDriver.kt:26-46` |
| `ScheduledSyncPreferences` is one global boolean under `scheduled_sync_enabled`; fail-closed read (absent or exception = false) | `ScheduledSyncPreferences.kt:140-157`; `platform/Settings.kt:4` |
| The poll interval is already per-graph, in the `git_config` DB table (`poll_interval_minutes`) | `db/MigrationRunner.kt:368-385` |
| UI glue (`rememberGraphContentScheduledSyncControls`) drives loop + closed-app job from `(enabled, interval)`; calls `scheduler.schedule(interval)` / `cancel()` | `commonMain/.../ui/GraphContentGitSyncSetup.kt:206-250` (lines 15-45 of that excerpt) |
| `BackgroundJobRegistry` is read-only (`jobs`, `cancel(id)`, `clearFinished`), keyed by `JobKind` enum (GitSync, ModelDownload, LfsTransfer, ProjectDigest, ...) | `commonMain/.../jobs/BackgroundJobs.kt:9-17, 206-215` |
| Android registry discovers work through WorkManager tags `stelekit-job`, `stelekit-job-kind:<name>` | `androidMain/.../jobs/WorkManagerJobRegistry.kt:19-34` |
| Existing second-consumer candidates: `DepthModelDownloadWorker` (one-time, `CONNECTED`, `ExistingWorkPolicy.KEEP`), `LfsTransferPolicy.deferOnMetered` | `androidMain/.../platform/ml/DepthModelDownloader.kt:74-87`; `commonMain/.../git/lfs/LfsTransferPolicy.kt:12-33` |
| A metered signal already exists: `NetworkMonitor.isMetered: Boolean?` (nullable; Android implements it) | `androidMain/.../platform/AndroidNetworkMonitor.kt:69-71` |
| Settings is a four-method key-value store (`getBoolean/putBoolean/getString/putString/containsKey`); no enumeration of keys | `commonMain/.../platform/Settings.kt:6-18` |
| Factory is wired from three roots: Android `MainActivity.kt:519`, Desktop `Main.kt:239` (`None`), wasm `Main.kt:365,514` (`None`); consumed in `StelekitAppDependencies.kt:93` | grep |
| Staleness and last-outcome history already persist per graph in Settings (`git_sync_staleness_<graphId>`, capped history) | `commonMain/.../git/SyncStalenessStore.kt:29-45` |

## 2. Package placement and dependency direction

**Recommendation (INFERRED, resolves requirements Open Question 3):** new package `dev.stapler.stelekit.jobs.schedule` in `commonMain`, a sibling of the existing `jobs/BackgroundJobs.kt`.

Dependency direction (enforced by an import-scan test, see section 9):

```
git.*  (GitSyncTask, GitSyncService) ─┐
platform.ml / transfer (LFS, model)   ├──►  jobs.schedule  (port + value types, pure)
ui (settings)                         ┘            ▲
androidMain WorkManagerTaskScheduler ──────────────┤ implements
jvmMain InProcessTaskScheduler (or commonMain) ────┘
jobs.schedule ──► only kotlin stdlib, kotlinx.coroutines, kotlinx.datetime/kotlin.time, logging.Logger, platform.Settings
jobs.schedule ──✗ git.*, ui.*, db.*
```

- `jobs.schedule` may import `jobs.JobKind` only if the kind enum is kept; see section 3 for why it should not.
- `jobs` already imports `merge.*` in `InProcessJobs.kt:6-9`, so `jobs` is not a leaf package today. The new subpackage must be stricter than its parent: no `git`, `merge`, `ui`.
- The in-process (desktop) adapter has no platform API, so it can live in `commonMain` (`jobs/schedule/InProcessTaskScheduler.kt`) and be reused by iOS stub fallback and tests. It uses an injected `CoroutineScope`, `Clock`, `delay`. INFERRED.

## 3. Port shape

Minimal contract (INFERRED; names are proposals):

```kotlin
// jobs/schedule/ScheduledTaskKind.kt  -- open identity, not a closed enum
@JvmInline value class TaskKind(val id: String)          // "git-sync", "lfs-transfer", "model-download"

data class TaskKey(val kind: TaskKind, val scope: String)  // scope = graphId, or "" for app-wide; unique-work name derives from it

data class Schedule(val intervalMinutes: Int, val jitterMinutes: Int)   // fixed interval + jitter only (out of scope: cron)
enum class NetworkPolicy { Any, Unmetered, Off }          // Off = no network needed or task disabled; see sec. 6
data class TaskConstraints(val network: NetworkPolicy, val requiresCharging: Boolean = false, val batteryNotLow: Boolean = false)

data class TaskConfig(val enabled: Boolean, val schedule: Schedule, val constraints: TaskConstraints)

sealed interface TaskRunResult {
    data object Success : TaskRunResult
    data class Partial(val detail: String) : TaskRunResult           // maps git "Fetched"
    data class Skipped(val reason: String) : TaskRunResult           // pure-policy skip; makes no network call
    data class Failed(val message: String, val retryable: Boolean = false) : TaskRunResult
}

fun interface BackgroundTask { suspend fun run(key: TaskKey, ctx: TaskRunContext): TaskRunResult }
data class TaskRunContext(val scheduledAtMs: Long?, val startedAtMs: Long, val appOpen: Boolean, val constraintState: ConstraintState)

interface BackgroundTaskScheduler {
    val capabilities: SchedulerCapabilities      // minIntervalMinutes, supportsUnmetered, supportsCharging, supportsJitter
    fun apply(key: TaskKey, config: TaskConfig)  // idempotent "make platform state equal config"; enabled=false cancels
    fun cancel(key: TaskKey)
    fun pause(scope: String); fun resume(scope: String)   // graph-move quiesce, replaces pauseFor/resumeFor
}

interface TaskRegistry { fun register(kind: TaskKind, spec: TaskSpec, task: BackgroundTask); fun taskFor(kind: TaskKind): BackgroundTask? }
```

Design points and rationale:

1. **`TaskKind` is an open value class, not the existing `JobKind` enum.** `JobKind` (`BackgroundJobs.kt:9-17`) is a closed UI-label enum that includes non-schedulable kinds (`PageCopy`, `StorageMove`). Reusing it would couple scheduling to display copy and force a port edit per new task. A mapping `TaskKind -> JobKind` lives in the registry's `TaskSpec` (`jobKind`, `title`) so the read-only registry tags stay correct. INFERRED.
2. **Single `apply(key, config)` instead of `schedule(interval)`/`cancel()`.** Level-triggered, idempotent: the UI glue today calls `schedule` or `cancel` from a `LaunchedEffect` (`GraphContentGitSyncSetup.kt`, `if (active...) scheduler.schedule(interval) else scheduler.cancel()`); `apply` makes that a single call and makes "read the enqueued request back" (requirements success metric 1) a straightforward assertion against the config. INFERRED.
3. **`capabilities` replaces `BackgroundSyncSchedulerFactory.minimumIntervalMinutes` and the `None` null-return.** Today "closed-app checks available" is `scheduler != null` (`GraphContentGitSyncSetup.kt:`, `closedAppChecks = scheduler != null`). A capabilities value lets the Settings UI disable unsupported options with a reason (desktop cannot honour `Unmetered` without a signal; section 6) instead of hiding them, which matches the repo rule "show disabled with a reason" used for `TargetWriterCapabilities` in CLAUDE.md.
4. **`TaskRunResult` is neutral; git's `TickOutcome` maps into it** in the git adapter (`Success->Success`, `Fetched->Partial`, `Skip(*)->Skipped(reason.name)`, `Failure->Failed`). The git-specific `ScheduledSkipReason` stays in `git` and is rendered to a string at the boundary, keeping the port free of `git.*`.
5. **Run handler is separate from the scheduler.** `BackgroundTask` + `TaskRegistry` is the "registration surface": a feature registers `(kind, spec, task)` once in its composition root; the Android `CoroutineWorker` is one generic `TaskWorker` that looks up the task by `kind` from `inputData`, so adding a task needs no new Worker class (today each of `GitSyncWorker`, `GitCloneWorker`, `DepthModelDownloadWorker` is bespoke). Cold-start constraint: the registry must be populated in `Application.onCreate` before WorkManager runs work, the same requirement `GitSyncServiceRegistry` already has (`WorkManagerSyncScheduler.kt:119-120`). INFERRED; it is the main risk of this design (section 10).

## 4. Relationship to `BackgroundJobRegistry`

Keep them separate; connect through tags and the run log. (INFERRED)

- `BackgroundJobRegistry` = **observation** of in-flight and finished jobs ("what is running now", cancel/clear). `BackgroundTaskScheduler` = **intent** ("run this kind every N minutes under these constraints"). Merging them would give the read-only list write powers and make `NoBackgroundJobs` ambiguous.
- Bridge, no code coupling: the Android adapter tags every request with `JobTags.ANY`, `JobTags.kind(spec.jobKind)`, `JobTags.title(spec.title)` (`WorkManagerJobRegistry.kt:19-34`). Periodic work therefore already appears in the jobs list with no registry change. Verify that a periodic request in state `ENQUEUED` between runs reads as `Queued` (`toJobStatus` maps `ENQUEUED` to `Queued`, `WorkManagerJobRegistry.kt:88`) rather than cluttering the list; this is an open check (UNVERIFIED).
- The desktop in-process adapter reports to the existing `JobTracker.begin(...)` (`InProcessJobs.kt:25-27`) so in-process runs also appear. Reuse, do not duplicate.
- The new "per-run outcome log" (requirements Observability) is a separate `TaskRunLog` (section 7), not a `JobStatus`; `JobStatus` has no skip reason or scheduled-vs-actual time.

## 5. GitSync migration with `ScheduledSyncPolicy` as the gate

Migration steps (INFERRED; each step keeps existing tests green):

1. Add `GitSyncTask : BackgroundTask` in `git/` (git depends on the port, not vice versa). Its `run` is exactly the body of `GitSyncWorker.doWork` fast/cold path: fast path calls `service.scheduled.run(graphId)`, cold path calls `runSlowPathFetch`. `ScheduledSyncPolicy.decide` stays inside `ScheduledSyncRunner.run` (`ScheduledSyncRunner.kt:48-51`) unchanged; the port never sees `ScheduledSyncInputs`.
2. Network-policy gate placement: the pure check `NetworkPolicy` vs live `isMetered/online` runs in the **generic worker before** `task.run`, so a metered tick under `Unmetered` returns `Skipped("metered")` with no git call. Missing signal (`isMetered == null`) fails closed for `Unmetered`. The existing `online` input of the git policy is retained as is (`ScheduledSyncPolicy.kt:76`). INFERRED; mirrors the requirement "no network call on metered".
3. Shim first: reimplement `WorkManagerSyncScheduler` as a thin adapter over `BackgroundTaskScheduler.apply(TaskKey(git-sync, graphId), TaskConfig(...))`; `BackgroundSyncSchedulerFactory` is kept as `@Deprecated` wrapper for one release so `GitSyncSchedulingEntryPointTest.kt:79,116` and `WiredAppFixture` keep compiling. This is the "extend in place as step one" option from requirements, done as a pass-through.
4. Unique-work naming: keep `stelekit_git_sync_$graphId` as the git-sync name (`TaskKey(git-sync, g).workName()` special-cases the legacy name; others use `stelekit_task_<kind>_<scope>`). This preserves `GitCloneWorker` sharing the same name for mutual exclusion (`WorkManagerSyncScheduler.kt:81-83`; `git-sync-resilience/research/architecture.md` section 4, lines 295-299) and `WorkManagerSyncSchedulerUniqueWorkTest`. Requirements Risk Control ("keep old unique-work names until the new request is enqueued, then cancel old") is satisfied by not renaming.
5. Jitter on WorkManager (requirements Rabbit Hole, INFERRED, to be validated by spike): `PeriodicWorkRequest` has only a flex window. Recommended approach: **periodic request with `flexInterval` + randomised `setInitialDelay` on enqueue**, because chained one-time work abandons WorkManager's built-in periodic uniqueness and the `UPDATE` policy semantics the retry-owner and unique-work tests rely on. Constraint: `flex >= 5 min`, `repeat >= 15 min`, and `jitter <= interval - flexMin` clamp lives in a pure `Schedule.clamp(capabilities)` function (commonTest property test: offsets in `[0, jitter]`, never negative, never outside platform bounds). `initialDelay` only shifts the first run; later runs drift by WorkManager's own periodic anchoring. If per-run jitter is required, the generic worker re-enqueues a one-time request with a fresh delay; that is the fallback and an Open Question for the spike.
6. `resumeFor` today re-enqueues at the 15-minute minimum, not the configured interval, relying on `GitSyncService` to restore (`WorkManagerSyncScheduler.kt:100-110`). With `apply(key, config)` driven from persisted settings, `resume(scope)` can re-apply the stored config, removing that approximation. INFERRED improvement; flag as a behaviour change.
7. ADR-002 invariant carried over: the generic worker must never return `Result.retry()` for a `Failed` result once the task owns its own retry (`Failed.retryable` defaults false). Keep `WorkManagerSyncSchedulerRetryOwnerTest` passing against the generic worker.
8. In-app loop: `PeriodicSyncDriver` (in-process timer, `PeriodicSyncDriver.kt`) is the desktop adapter's behaviour today and also the Android foreground loop. Migration option A (recommended): leave `PeriodicSyncDriver` in place for the in-app path in this project and make the desktop adapter a generic `InProcessTaskScheduler` that drives `BackgroundTask.run`; unify later. Option B (rewrite the driver on top of the port now) is higher blast radius for no requirement-listed gain. INFERRED.

## 6. Network policy semantics

- `Unmetered` maps to `NetworkType.UNMETERED` (not "Wi-Fi"); user copy must say "unmetered" (requirements Rabbit Hole). `Any` maps to `CONNECTED`. `Off` = scheduling disabled for that task (equivalent to `enabled=false`), not a constraint. INFERRED.
- Live check agrees with the constraint via `NetworkMonitor.isMetered: Boolean?` (VERIFIED existing, `AndroidNetworkMonitor.kt:69-71`). `null` = unknown; for `Unmetered` unknown means skip (fail closed).
- Desktop: JVM has no portable metered signal, so `capabilities.supportsUnmetered = false` and the Settings UI shows the option disabled with a reason, unless a platform signal appears. Do not silently treat desktop as unmetered. INFERRED.
- The LFS consumer already has `deferOnMetered` (`LfsTransferPolicy.kt:14`) with its own live check; migrating it means mapping `deferOnMetered` to `NetworkPolicy.Unmetered` for the `lfs-transfer` kind and deleting the duplicate setting.

## 7. Where per-graph per-kind settings persist

Options:

| Option | For | Against |
|---|---|---|
| A. Settings key-value, keys `task.<kind>.<graphId>.{enabled,intervalMinutes,jitterMinutes,network,charging}` | No SQLDelight regen, no `MigrationRunner` entry, readable from a cold WorkManager process that cannot open the graph DB cheaply, existing pattern (`git_sync_staleness_<graphId>`, `SyncStalenessStore.kt:29`; `LfsTransferPolicySource`) | No key enumeration (`Settings.kt:6-18`), so "list all tasks for graph" needs an index key or registry iteration; untyped strings; graph deletion must clean keys |
| B. New DB table `background_task_config(graph_id, kind, enabled, interval, jitter, network, ...)` + `MigrationRunner.all` entry + generated-source regen | Typed, queryable, FK-style cleanup with graph, enumeration free | Cold path already has to open a driver per graph to read `git_config` (`WorkManagerSyncScheduler.kt:210-219`) so cost exists but is real; CLAUDE.md migration rules (`MigrationRunnerSchemaSyncTest`, SQLDelight regen, `RestrictedDatabaseQueries` stub); DB is per-graph (`getDatabaseUrl(graphId)`), so an app-wide kind (e.g. model-download) has no home |
| C. Extend `git_config` columns | Reuses per-graph row | Git-specific table for a neutral concept; violates the neutral-port requirement |

**Recommendation: A (Settings), with a typed `TaskSettingsStore` in `jobs.schedule`** that serialises one string per `(kind, scope)` key (`task_config_<kind>_<scope>` holding a small versioned encoding), the same pattern as `SyncStalenessStore` (VERIFIED pattern, `SyncStalenessStore.kt:29-45`). One string per key makes read-modify-write atomic, avoids the missing-enumeration problem for reads (keys are derived), and matches fail-closed (decode failure or exception = `enabled=false`). Enumeration need (Settings "all background tasks" screen) is met by the registry's known kinds x the open graph list, no key scan. Revisit B only if a query across graphs is needed (INFERRED; no such requirement exists). Because no table is added, no `MigrationRunner`/SQLDelight work arises, and the `.sq` regeneration rule does not trigger.

Existing interval: `git_config.poll_interval_minutes` already holds the git interval per graph. To avoid two sources of truth, the git task's `TaskConfig.schedule.intervalMinutes` reads from the task store, seeded from `poll_interval_minutes` on first read, and the UI writes the task store; `git_config.poll_interval_minutes` is retained read-only for one release (requirements Risk Control rollback). INFERRED; the cleanest alternative is leaving interval in `git_config` and storing only the new fields (jitter, network, charging, enabled) in the store, which is less migration but a split config. Decision needed (see section 11).

## 8. Migration of `scheduled_sync_enabled`

Fail-safe, one-way, idempotent, no silent on/off (INFERRED, aligned with requirements Risk Control):

1. On first read of `task_config_git-sync_<graphId>` when the key is absent (`containsKey` false, `Settings.kt:12-18`), derive `enabled` from the legacy global `scheduled_sync_enabled` (`getBoolean(key, false)`) **only for graphs that have a git config** (a remote), and `intervalMinutes` from `git_config.poll_interval_minutes`. Persist the derived value so it is explicit thereafter.
2. Defaults for new fields: jitter 0, network `Any` (today's `CONNECTED` behaviour preserved), charging off. Existing users see no behaviour change.
3. The legacy key is not deleted for one release (rollback reads it, `ScheduledSyncPreferences` keeps its signature). After migration the UI writes only the new key; to keep rollback safe, also mirror `enabled` of the most recently edited graph? Rejected: mirroring a per-graph value to a global flag recreates ambiguity. Rollback to the old build re-reads the old global value, which is the pre-upgrade user choice; acceptable. INFERRED.
4. A settings read failure returns `enabled=false` (fail closed), never defaulting to legacy-on after an exception.
5. Test: property/example test over `(legacy flag, has remote, has new key)` truth table; asserts no graph flips from off to on, and absent+legacy-true+no-remote stays off.

## 9. Wiring and composition roots

VERIFIED constraints: `WiringAudit.SEAMS` is a text-match list (`WiringAudit.kt:18-25`); a seam's implementations must appear as `Name(` in a root (`WiringRoots.kt`: `Main.kt`, `MainActivity.kt`, `SteleKitApplication.kt`, `StelekitAppDependencies.kt`, `RepositoryFactory.kt`, plus `GraphContent*Setup.kt`), or be allowlisted with a 90-day expiry; each wired seam needs a `wiring-catalog.txt` line `Seam  EntryPointTest  effect:<label>` and an entry-point test calling `ObservableEffect.assert`.

Plan (INFERRED):

- Add `BackgroundTaskScheduler` to `WiringAudit.SEAMS` (the Glossary comment says it mirrors the plan Glossary, so update both). Keep `BackgroundSyncScheduler` in `SEAMS` while the deprecated shim exists; remove it and its two catalog lines in the PR that deletes the shim (policy: "a PR that wires or deletes a listed class deletes its allowlist line").
- New catalog lines: `BackgroundTaskScheduler  TaskSchedulerAndroidEntryPointTest  effect:task-work-enqueued` (androidUnitTest, reads the WorkManager request back with the configured constraints) and `BackgroundTaskScheduler  TaskSchedulerDesktopEntryPointTest  effect:in-process-task-tick` (jvmTest, `DesktopWiredAppFixture`).
- Roots: Android `MainActivity.kt` constructs `WorkManagerTaskScheduler` and the `TaskRegistry` and registers `GitSyncTask`, the LFS/model task; `SteleKitApplication.kt` populates the registry before WorkManager runs work (cold start). Desktop `Main.kt` constructs `InProcessTaskScheduler`. Wasm/iOS pass `NoopTaskScheduler` (with `capabilities` all false). `StelekitAppDependencies.kt` replaces `backgroundSyncSchedulerFactory` (`StelekitAppDependencies.kt:93`) with `backgroundTaskScheduler` + `taskRegistry`; the factory field stays during the shim release.
- A `NoopTaskScheduler`/`InProcessTaskScheduler` class constructed only in a non-root file would trip `WiringCompletenessTest`; either construct in a root or add a dated allowlist line.
- New Settings screen rows: if a new screen is added, it needs `first-open-screens.txt` and `FIRST_OPEN_SCREENS` in `FirstOpenSmokeTest.kt` (CLAUDE.md); prefer extending the existing sync section rather than a new screen.
- `Application.onCreate` rules: `catch (Throwable)` around registry population; the generic `TaskWorker.doWork` catches `Throwable` (not just `Exception`) and the scheduler's long-lived scope carries a `CoroutineExceptionHandler` (CLAUDE.md).

## 10. Testing seams

- **Pure, commonTest:** `Schedule.clamp`/jitter offset function (property tests with `kotest-property`: offset in `[0, jitter]`, non-negative, within platform bounds), `NetworkPolicy` x live-state gate, settings codec round-trip and legacy migration truth table, `TaskRunResult` mapping from `TickOutcome`. Existing `ScheduledSyncPolicy` tests unchanged.
- **Fake adapter:** `FakeBackgroundTaskScheduler` in commonTest recording `apply/cancel/pause/resume` calls keyed by `TaskKey` and exposing a `fireNow(key)` that runs the registered task with a supplied `TaskRunContext`. Consumers (GitSyncTask wiring, Settings glue) are tested against the fake with no WorkManager.
- **Contract suite:** abstract `BackgroundTaskSchedulerContract` in commonTest (mirrors `TargetWriterContractSuite` pattern in CLAUDE.md) asserting: `apply` idempotent; `apply(enabled=false)` cancels; two keys do not collide; `pause` then `resume` restores the same config; `capabilities` honestly reflect clamping (interval below minimum clamped, not rejected silently). Concrete subclasses: fake (commonTest), `InProcessTaskScheduler` (jvmTest with a test dispatcher and injected clock; no real-clock delay, per `TestHygieneAuditTest`), `WorkManagerTaskScheduler` (androidUnitTest, Robolectric + `WorkManagerTestInitHelper`, following `WorkManagerSyncSchedulerUniqueWorkTest.kt:20,76`). Android-specific assertions (constraint is `UNMETERED`, flex window, unique name) go beside the contract, reading `WorkInfo`/request back, not "no error".
- **Contract suite placement:** an abstract class in commonTest is only inherited by jvmTest/androidUnitTest if those source sets depend on commonTest. They do (CLAUDE.md "Test source sets"), so INFERRED fine; check Bazel targets expose the abstract class to both (UNVERIFIED).
- **Import-direction test:** a businessTest scanning `jobs/schedule/*.kt` sources for `import dev.stapler.stelekit.git` / `.ui.` / `.db.` (same bundling mechanism as `wiring_*_as_resources`, CLAUDE.md "Wiring and test-hygiene gates"); needs a `filegroup` for the new source dir.
- Regression guards to keep green: `GitSyncSchedulingEntryPointTest`, `WorkManagerSyncScheduler{PauseTest,RetryOwnerTest,SlowPathYieldTest,UniqueWorkTest,WatchdogTest}` (VERIFIED present in `androidUnitTest/.../git/`).

## 11. Observability hooks (summary)

- `TaskRunLog` port in `jobs.schedule`; Settings-backed implementation capped like `SyncStalenessStore.HISTORY_LIMIT`, plus a `Logger` line per run `TaskRun kind=... scope=... scheduledAt=... startedAt=... result=... constraints=...` (reaches the persisted log, no logcat). Generic worker writes it in one place for all kinds. INFERRED.
- "Behind" state: generalise `SyncStalenessPresentation` over `(lastSuccessAtMs, intervalMinutes, multiple)`; git keeps its richer presentation. `BackgroundRestrictionProbe` (`BackgroundSyncScheduler.kt:37-39`) moves to `jobs.schedule` unchanged (it is neutral already; git typealias for source compatibility).

## 12. Disposition for the touched hotspot area

**Isolate via seam.** The git scheduling files (`GitSyncService`, `WorkManagerSyncScheduler.kt` with its worker, the 17+ test files that pin its behaviour) are high-churn and heavily tested, so rewriting in place is riskier than putting the new port in front: the new generic worker/scheduler is added beside them, GitSync migrates behind a shim that keeps `ScheduledSyncPolicy` and existing unique-work names, and the old surface is deleted in a follow-up once the contract suite is green on both adapters. (No hotspot metrics were computed here; "high-churn" is INFERRED from the number of tests and ADR/plan files that reference it, not from `git log` data. Run `code-hotspot-analysis` if a measured basis is required.)

## 13. Event-Command-Policy table (EventStorming grammar)

| Event (past tense) | Trigger / source | Command | Policy ("whenever X then Y") | Actor |
|---|---|---|---|---|
| TaskRegistered | Composition root at startup | RegisterTask(kind, spec, task) | Whenever the app process starts (UI or cold WorkManager), the registry is populated before work runs | Composition root |
| TaskConfigChanged | User edits enabled/interval/jitter/network in Settings | SaveTaskConfig(key, config) | Whenever config is saved, persist then ApplySchedule; persist failure keeps in-memory value only for the session (existing `ScheduledSyncPreferences` behaviour) | User |
| LegacyFlagMigrated | First read of an absent per-graph key | DeriveConfigFromLegacy(graphId) | Whenever the per-graph key is absent, derive from `scheduled_sync_enabled` + `git_config`, persist, never turn an off graph on | System |
| ScheduleApplied | SaveTaskConfig / app start / resume | ApplySchedule(key, config) | Whenever config exists and enabled, enqueue with clamped interval, jitter, constraints; whenever disabled, cancel | Scheduler |
| ScheduleClamped | Interval below platform floor or jitter beyond bounds | ClampSchedule | Whenever requested values exceed `capabilities`, clamp and surface the effective value in the UI | Scheduler |
| TaskTickStarted | Platform scheduler fires (WorkManager / timer) | RunTask(key, ctx) | Whenever a tick starts, evaluate network policy against live state before calling the task | Platform |
| NetworkPolicyViolated | Live metered/offline state conflicts with `NetworkPolicy` (or signal unknown) | SkipTick(reason) | Whenever policy is `Unmetered` and the network is metered or unknown, skip with no network call (fail closed) | System |
| PolicyGateEvaluated | Git task begins | EvaluateScheduledSyncPolicy | Whenever the git task runs, `ScheduledSyncPolicy.decide` decides Run / FetchOnly / Skip; the port never overrides it | GitSyncTask |
| TaskSkipped | Network gate or domain policy skip | RecordRun(result=Skipped(reason)) | Whenever a tick is skipped, log reason; do not count as failure or backoff | System |
| TaskSucceeded / TaskFailed | Task returns | RecordRun(result) | Whenever a run ends, append to run log and `Logger`; on Failed do not return `retry()` if the task owns retry (ADR-002) | System |
| TaskBehindDetected | Last success older than multiple x interval | ShowBehindState | Whenever now - lastSuccess > multiple x interval, show "behind" in the status line | System |
| BackgroundRestricted | `BackgroundRestrictionProbe.isRestricted()` true | ShowRestrictionHint | Whenever the probe reports restriction, show the hint beside the schedule | System |
| GraphMoveStarted / GraphMoveFinished | Relocate/link quiesce | PauseTasks(scope) / ResumeTasks(scope) | Whenever a move begins, cancel the graph's work; when it ends, re-apply the stored config (not the 15-min minimum) | Move coordinator |
| TaskCancelled by user | Cancel in the jobs list | `BackgroundJobRegistry.cancel(id)` | Whenever a single run is cancelled, the periodic schedule remains; disabling is a config change, not a job cancel | User |
| GraphRemoved | Graph deleted | CancelAllTasks(scope) + purge keys | Whenever a graph is removed, cancel its work and delete its settings keys | System |

## 14. Open questions and risks

1. Generic `TaskWorker` cold start: registry must exist before WorkManager restarts the process into `doWork`; if `GitSyncTask` needs `GitSyncServiceRegistry` state, the cold path must stay a standalone fetch (as today, `WorkManagerSyncScheduler.kt:162-200`). VERIFIED current constraint; design is INFERRED.
2. Jitter approach needs a spike on the `Pixel_Fold` AVD or `WorkManagerTestInitHelper` (test driver can `setPeriodDelayMet`; real flex/initial-delay timing is not CI-observable). Owner runs device checks.
3. Interval single-source decision (section 7): task store vs `git_config.poll_interval_minutes`.
4. `ENQUEUED` periodic work shown as `Queued` in the jobs list may be noisy (UNVERIFIED); may need a tag filter.
5. Second consumer: recommend `lfs-transfer` (policy already exists, `LfsTransferPolicy.deferOnMetered`) over `model-download` (one-time `KEEP` work, not periodic, `DepthModelDownloader.kt:74-87`). The port should also support one-shot "deferred" runs (`runOnce(key, constraints)`), which `model-download` needs; periodic-only would not prove it. Needs requirement-owner confirmation.
6. Open stacked PRs on git sync (requirements wall-clock blocker 3) were not checked.
