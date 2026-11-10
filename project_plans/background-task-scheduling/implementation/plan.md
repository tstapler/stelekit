# Implementation Plan: background-task-scheduling

**Feature**: A neutral, platform-agnostic background-task port (`dev.stapler.stelekit.jobs.schedule`) with Android WorkManager, Desktop in-process and iOS/Web no-op adapters; GitSync and model download run on it with per-graph, per-task-kind settings (enabled, interval, jitter, network policy), a persisted run log, a "Late" schedule state and a restricted-background hint.
**Date**: 2026-11-10
**Status**: Triad repair 1 applied (product, UX and engineering gaps; 116 tasks) — ready for triad re-check. Phase 4 repair applied — ready for readiness gate. (Phase 4 repair = pre-mortem P1-1, P1-2, P2 #3-5, validation gaps and the consistency review B1-B4/C1-C11/nitpicks; see "Phase 4 repair" table below. Earlier: Re-review 2 CONCERNS folded in by repair loop iteration 3.)
**ADRs**: [ADR-001](../decisions/ADR-001-neutral-scheduling-port.md), [ADR-002](../decisions/ADR-002-jitter-flex-plus-deterministic-offset.md), [ADR-003](../decisions/ADR-003-settings-store-and-legacy-migration.md), [ADR-004](../decisions/ADR-004-second-consumer-model-download.md), [ADR-005](../decisions/ADR-005-work-naming-and-orphan-reconcile.md), [ADR-006](../decisions/ADR-006-fail-closed-network-gate-and-capabilities.md)

Inputs: `requirements.md`; `research/{stack,features,architecture,pitfalls,ux,build-vs-buy}.md`. Owner decisions already made (not re-asked): platforms = Android WorkManager + Desktop in-process + iOS stub; settings per graph per task kind; second consumer = model download (LFS shares `NetworkPolicy`); git interval moves into the new store with `git_config.poll_interval_minutes` as fallback; jitter = flex plus deterministic offset, validated by an early spike.

## Revision Log (triad repair 1)

| Finding | Resolution (where) |
|---|---|
| Product: why-now, outcome metrics, evidence, risky assumptions, owner-confirm defaults, Late rationale | `requirements.md` new sections "Why Now / RICE-lite", "Outcome Metrics", "Risky Assumptions", "Owner-confirm Items"; evidence marked STATED / REPO / INFERRED; no telemetry |
| UX: Reset to default, loading state, display names + date/time in Logs, a11y focus/title/search/disabled-reason, large-font/RTL, Custom format, day granularity, precedence alignment, S6 states, busy copy, success feedback, 5-min helper line | `design/ux.md` (S1/S2 shared rules, S2, S3, S5, S6, UX-8, UX-13, new UX-30..UX-39); plan Story 4.1.1 (new ACs, new Task 4.1.1g, 5-min AC), 4.1.2, 4.1.3, 3.2.1, 5.1.3; `validation.md` rows |
| UX: UX-28/UX-29/TalkBack as owner device items | Plan Story 5.1.3 criterion; `validation.md` M-6..M-9 |
| Engineering: serial depth, "(existing)" tests, PR-stack result | Notes in the Effort estimate section; `validation.md` gaps note and "verify exists before relying" markers |
| Counts | 116 tasks (+1: 4.1.1g), 27 stories, 9 epics; effort table updated |

## Revision Log (Phase 4 repair)

Findings from `pre-mortem.md` (P1-1, P1-2, P2 #3-5), `validation.md` gaps and the plan/requirements/UX consistency review (B1-B4, C1-C11, nitpicks). Where a later row contradicts an older revision row below (iteration 1-3), this table wins.

| Finding | Resolution (story/task) |
|---|---|
| P1-1 model-download never runs (`enabled` default off skipped a one-shot) | `enabled` is periodic-only: `TaskWorker` never reads it for `TaskShape.OneShot`; explicit `runOnce` always runs; one-shot network policy comes from the input-data key `KEY_NETWORK_POLICY` set by `runOnce`, not from the store (this also makes "Download now anyway" effective). New Story 2.2.2 AC (no store entry runs the body), Story 3.2.1 end-to-end test from empty `Settings` (Task 3.2.1h), `TaskSpec` KDoc (Task 1.1.1b), Glossary |
| P1-2 / B3 git bypasses gate and run log | Thin `GitSyncWorker` still has no registry/store dependency (`RetryOwnerTest` holds) but (a) evaluates `NetworkGate` via `AndroidNetworkSnapshotProvider` with policy from `KEY_NETWORK_POLICY` (absent = `Any`), (b) appends one `TaskRunRecord` through the shared write-only, `Throwable`-guarded `recordRun` (Task 2.2.2i). Story 3.1.2 ACs; `apply`/`resume` enqueue `GitSyncWorker` for `git-sync` (definitive); Spike 0.1.1 outcome is a hard gate (Task 3.1.2g); ADR-002 updated |
| B1 git-sync default jitter | `git-sync` `defaultConfig` jitter = `JitterPreset.ALittle.minutesFor(60)` = 6 min (matches `ux.md`); migrated graphs keep jitter 0 (behaviour-preserving). Story 1.1.4 AC, Task 3.1.1c |
| B2 `TaskOverrides` shape / one-shot constraints | `TaskOverrides.schedule` is optional (null for one-shot kinds); `DepthModelDownloader` resolves constraints from `TaskSettingsStore.read(model-download, "")` defaulting `Unmetered` and passes them to `runOnce` (Task 3.2.1g, read-back test); `enabled=false` for a one-shot means "automatic (non-user-initiated) starts off"; v1 has none, so the Settings switch is hidden for `model-download` |
| B4 foreground ticks and the run log | `PeriodicSyncDriver` ticks (Desktop git, Android in-app) append to `TaskRunLog` under a distinct source key (`RunSource.Foreground`), so each key keeps ONE writer; `recent` merges both sources newest-first. Tasks 1.2.1e, 3.1.3g; Observability Plan |
| C1 / C2 / C3 / C4 / C5 / C6 | `requirements.md` amended (Desktop git stays on `PeriodicSyncDriver` this release; "behind" -> "Late"; configurable multiple dropped, constant `LATE_MULTIPLE = 2`; network "off" = `enabled=false`; backoff wording; orphan reconcile / removal hook / self-heal / bundled product changes added to In Scope as flagged owner-confirm items) |
| C7 `TaskForegroundProvider` | Stated: it only preserves the existing `DepthModelDownloadWorker` notification (Glossary, Task 2.2.2d) |
| C8 migration semantics | Kept `enabled = legacyFlag && hasRemote`; rationale recorded (previously composition-driven scheduling enabled such graphs when opened); listed as an owner-confirm item (Unresolved Questions) |
| C9 Story 1.1.2 ACs | Rewritten over `Schedule.coerce(rawInterval, rawJitter)` / `Schedule.of`; no constructor call that cannot compile |
| C10 pause vs paused | Glossary note: port `pause`/`resume` is internal quiesce for clone/graph move and is never shown; UI "Paused" is only the 3-failure error stop. No rename |
| C11 per-graph cap | No hard cap: `LazyColumn` graph rows plus search at >= 8 graphs; unique work names bounded by graphs x kinds (Story 4.1.1, Task 4.1.1f) |
| Nitpicks | Story 4.1.1 ACs for S1 empty state, search at >= 8, hiding on no-op platforms (`SchedulerCapabilities.schedulesWork`), UX-11 clamp summary and Desktop helper (existing `DESKTOP_HELPER` string); S6/S8 presentation mappings (Task 3.2.1f, Story 3.2.2 AC); Risk Control reworded in `requirements.md` ("same unique-work name, `UPDATE` in place"); Android 15-minute reason string VERIFIED equal to `ux.md` copy (`ANDROID_FIVE_MINUTE_REASON` at `ScheduledSyncSection.kt:73`) |
| Pre-mortem P2 #3 snapshot failure ordering | `pause` writes the process-static copy first, then the ledger, each caught and logged, and ALWAYS proceeds to `cancelUniqueWork` (a blocked clone is worse than a lost snapshot; `resume` falls back snapshot -> in-memory -> ledger (last applied) -> store, startup `reapplyAll` is the backstop). Story 2.2.3, Task 2.2.3b |
| Pre-mortem P2 #3b user change during a clone | While a `PausedSnapshot` exists, `apply` updates the snapshot's config (no enqueue); `apply(enabled=false)` clears it. Story 2.2.3 AC + test |
| Pre-mortem P2 #4 cold Keystore | `TaskSettingsStore.readResult` distinguishes `ReadFailed` from `Disabled`; failures are logged via `Logger` and a plain-prefs `SettingsReadHealth` marker feeds a "Can't read settings" status; early owner Keystore checkpoint right after Story 2.2.2 (Tasks 2.2.2g, 2.2.2h) |
| Pre-mortem P2 #5 copy vs flex semantics | Spread copy derives from per-adapter `describe(key)` (`minGapMinutes`, `maxGapMinutes`, `appliesAfterNextRun`); Android copy finalised from Spike 0.1.1's observed distribution; "takes effect after the next run" when `UPDATE` retains the next-run time (Story 0.1.1, Story 4.1.1, ADR-002) |
| Validation gaps | `BackgroundTasksSectionTest` (Task 4.1.1a), `ModelDownloadUiTest` (Tasks 3.2.1a/e) added; both Compose, Gradle-only |

## Revision Log (repair loop iteration 1)

Every BLOCKER and concern from `architecture-review.md` (Arch-1..3 plus concerns) and `adversarial-review.md` (B1..B5, C1..C12, minors) is resolved below or listed under Unresolved Questions. Map:

| Finding | Resolution (where) |
|---|---|
| Arch-1 / Adv B2 Desktop loses sync; inconsistent migration trigger | Migration is lazy and platform-neutral inside `TaskSettingsStore.read` through an injected `LegacyConfigSeed`; `GitSyncScheduleController.ensureMigrated` and `ScheduleStartup` call the same read eagerly. Stories 1.1.4, 3.1.3, 3.1.4; ADR-003 |
| Arch-2 / Adv C4 orphan cancel kills live work | `KnownScopes` provider with `Unknown` result; empty/unreadable/corrupt cancels nothing; tombstone only from `GraphManager.removeGraph` success path. Stories 2.2.2, 2.2.3, 3.1.4; ADR-005; disposition row for `GraphManager` |
| Arch-3 false "Late" | `LastSuccessSource`; git supplies `max(TaskRunLog, SyncStalenessStore)`. Stories 1.2.1, 1.2.2, 3.1.1, 4.1.2 |
| Adv B1 test commands and BUILD edits | New "Test Infrastructure Rules" section; Android evidence via `./gradlew :kmp:testDebugUnitTest --tests`; explicit registration tasks (`AllBusinessTests`, `AllJvmTests`, `AllAndroidUnitTests`); new contract-fixture targets; no `@Test` classes added to `common_test_fixtures` |
| Adv B2 entry-point tests | Task 3.1.3e updates `GitSyncSchedulingEntryPointTest`, `GitSyncStackEntryPointTest`, `ScheduledSyncOriginE2ETest`, `ScheduledSyncControls` consumers |
| Adv B3 resume vs `WorkManagerSyncSchedulerPauseTest` | `AppliedConfigLedger` written by every adapter `apply`; `resume` reads ledger, then store (lazy legacy seed). Contract invariant added. Stories 1.1.6, 2.2.3. **Superseded by iteration 2 (N1): `resume` now restores the pause-time snapshot first, then the store; see the iteration-2 table.** |
| Adv B4 registry double registration | Registration only in `SteleKitApplication.onCreate`; `register` idempotent for an identical spec; both-roots test. Stories 1.1.1, 2.2.2, 3.1.1, 3.2.1 |
| Adv B5 skip maps to success for one-shot | One-shot skip maps to bounded `Result.retry()` then terminal `Failed` that resolves the waiter. Stories 2.2.2, 3.2.1 |
| Concerns (Desktop driver, `TaskConfig` validity, injective keys, cold-process network signal, REPLACE, Noop default, capability clamp, `GitSyncTask` androidMain hooks, `GitSyncWorker` shim, `stelekit-job` tag, LFS files, Spike 0.1.2 Keystore, file-edit serialization, UX gaps) | See the story each one names below; the Revision Log in each story header lines say "(rev1)" where changed |

Spot-checks done while planning (VERIFIED by opening files in this worktree): `BackgroundSyncScheduler.kt` (git package, 39 lines, `schedule(intervalMinutes)`/`cancel()`); `WiringAudit.SEAMS` contains `BackgroundSyncScheduler`; `wiring-catalog.txt` has two `BackgroundSyncScheduler` lines; `jobs/` contains only `BackgroundJobs.kt` and `InProcessJobs.kt`; `MainActivity.kt:519-521` builds the factory with `WorkManagerSyncScheduler`; `GraphManager.removeGraph` exists at `db/GraphManager.kt:556`; `kmp/src/commonMain/kotlin/BUILD.bazel` compiles commonMain by glob (new main files need no BUILD edit) but `kmp/src/commonTest/kotlin/BUILD.bazel` lists test sources explicitly (new commonTest files need BUILD entries). The research claims about `WorkManagerSyncScheduler` line numbers were not individually re-verified; tasks that depend on them re-read the file first.

Additional facts VERIFIED in this worktree during the repair loop (cited so implementers do not re-derive them):
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/AllAndroidUnitTests.kt` is an explicit `@Suite.SuiteClasses` list; it contains no `WorkManagerSyncScheduler*Test` and no `GitSyncSchedulingEntryPointTest` (grep count 0). `kmp/src/androidUnitTest/kotlin/BUILD.bazel` compiles tests with `glob(["**/*.kt"])`, so adding a file needs no BUILD edit but needs a suite registration to run under Bazel.
- `kmp/src/businessTest/kotlin/BUILD.bazel` compiles `glob(["**/*.kt"])` (no BUILD edit for new businessTest files) but `AllBusinessTests` is an explicit list guarded by `AllBusinessTestsCompletenessTest`; commonTest tests reach Bazel only through `//kmp/src/commonTest/kotlin:business_common_tests` (explicit `srcs`) plus an `AllBusinessTests` entry.
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/AllJvmTests.kt` is an explicit list too (it names `GitSyncStackEntryPointTest`, `ScheduledSyncOriginE2ETest`, `DesktopWiredAppFixtureTest`).
- `WorkInfo.periodicityInfo` is already read on 2.9.1 by `GitSyncSchedulingEntryPointTest.kt:95` (`period.repeatIntervalMillis`), so read-back of the repeat interval is available; flex and `nextScheduleTimeMillis` read-back remain for Spike 0.1.1.
- `GraphManager.loadRegistry` (`db/GraphManager.kt:272-312`) resets to an empty `GraphRegistry()` and re-saves on a corrupt registry; `removeGraph` (`:556`) calls `activeGraphJobs.remove(id)?.cancel()` first, then has `return false` guards (not found, demo, active-but-not-last-real) before `saveRegistry()` and credential cleanup, then `return true`.
- `SteleKitApplication.kt` lives at `androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt` (not in `kmp/src/androidMain`).
- `StelekitAppPlatformIntegrations.backgroundSyncSchedulerFactory` (`ui/StelekitAppDependencies.kt:93`) has no default; hosts that name it: `jvmMain/desktop/Main.kt:239`, `jvmMain/desktop/ui/App.kt:30`, `wasmJsMain/browser/Main.kt:365`, `androidUnitTest/wiring/WiredAppFixture.kt:45`, `jvmTest/wiring/DesktopWiredAppFixture.kt:102`, Android `MainActivity.kt`.
- `SyncStaleness` (`git/SyncStalenessStore.kt`) exposes `lastMergedAtMs` and `lastFetchedAtMs`; the foreground loop and "Sync now" write these, not `TaskRunLog`.
- `LfsRepositorySupport.kt:45` (`isMetered: () -> Boolean = { false }`) and `:144` (`limits.deferOnMetered && isMetered()`) are in `jvmCommonMain` (shared with Desktop).
- Two existing tests construct `WorkManagerSyncScheduler(context, "g1").schedule(15)`, then static `pauseFor/resumeFor`, with no store entry (`WorkManagerSyncSchedulerPauseTest`).

## Revision Log (repair loop iteration 3)

Findings from `adversarial-review.md` "Re-review 2" (C2-1..C2-4, 3 minors).

| Finding | Remedy (story/task) |
|---|---|
| C2-1 snapshot-restored job class/constraints | `resume` for `git-sync` enqueues `GitSyncWorker` (never `TaskWorker`) unless Spike 0.1.1 proves `UPDATE` changes `workerClassName`; the snapshot keeps the initial delay (Task 2.2.3b); explicit assertion in Task 2.2.3a using `UniqueWorkTest`'s own `WorkerFactory` |
| C2-2 `interval_minutes` | Story 3.1.2 wording corrected: the wrapper reads `KEY_INTERVAL_MINUTES` (15 default) and passes it to `notifyUpdatesWaiting`; adapter enqueues keep setting it |
| C2-3 blocking read in `pauseFor` | Thread/context stated, no-clobber and consume-on-resume rules added (Story 2.2.3, Task 2.2.3b) |
| C2-4 drift | Row 21 marked superseded; Story 2.2.3 last bullet and Story 3.1.2 heading reworded |
| Minors | Test-class list stated once (3.1.2b); snapshot-wins rationale (ADR-003); `SteleKitContext.init` safety note (2.2.3b) |

## Revision Log (repair loop iteration 2)

Findings from `adversarial-review.md` "Re-review 1" (N1, N2, R-C1..R-C11, minors) and `architecture-review.md` "Re-review 1" (A-C1..A-C5). Claims below were re-verified against this worktree before remedies were written: `loadGitConfigFromDb` is a `private fun` at `androidMain/.../git/WorkManagerSyncScheduler.kt:210` (not on `GitSyncWorker`); `workManagerOrNull` is `private` at `WorkManagerJobRegistry.kt:58` and has a second private copy at `DepthModelDownloader.kt:128`; `DepthModelDownloadWorker.MAX_ATTEMPTS = 5` (`:100`); `GraphManager.removeGraph` is non-suspend (`:556`); `AndroidGitCloneWorkerLauncher` calls `WorkManagerSyncScheduler.pauseFor` (`:91`) BEFORE the clone and `resumeFor` in `finally` (`:106`), so a pause-time snapshot is available to resume.

| Finding | Resolution (where) |
|---|---|
| N1 ledger fails in existing tests | `pause` snapshots the live `WorkInfo` (interval, flex, constraints, tags) into the ledger BEFORE `cancelUniqueWork`; `resume` restores from that snapshot; the store is used only when there is no snapshot, and an explicit store entry or tombstone saying disabled wins over a snapshot (R-C7). `AppliedConfigLedger` takes an explicit `Settings`; the shim/`resumeFor` call `SteleKitContext.init(context)` before `PlatformSettings()`; a process-static in-memory copy of the snapshot backs a `Settings` that silently no-ops. Stories 1.1.6, 2.2.3 (new scenarios: `PauseTest`, `UniqueWorkTest`, pre-upgrade unmigrated job), 3.1.2 |
| N2 `GitSyncWorker` vs `RetryOwnerTest` | `GitSyncWorker.doWork` stays a thin wrapper calling the extracted git-sync task functions directly: no registry lookup, no `enabled` gate, no store read, `Throwable` guard. `runTaskWork` is used only by `TaskWorker`. Empty-registry `RetryOwnerTest` AC added. Tech Debt row, Stories 2.2.2, 3.1.2 |
| R-C1 Desktop `apply` contradiction | The controller calls `apply` only when `capabilities.runsInBackgroundWhenClosed`; Desktop `git-sync` registration dropped. Stories 3.1.1, 3.1.3 |
| R-C2 composition-path seed | Seed built inside `rememberGraphContentScheduledSyncControls` from its `configRepo` param and `deps.platformSettings`. Task 3.1.3e |
| R-C3 `assumeTrue` in commonTest | Early `return` guarded by capability. Rule 5, Stories 1.1.6, 2.1.1 |
| R-C4 androidUnitTest Bazel lib deps | Rule 6: androidUnitTest uses plain `kotlin.test`, no `Arb`. |
| R-C5 `onTerminalFailure` vs `ModelState` | Dropped; error travels in the failure output `Data`; the existing `WorkInfo` collector yields `Failed(reason)`. Stories 2.2.2, 3.2.1 |
| R-C6 `CANCEL_AND_REENQUEUE` ambiguity | Used only by `resume`; `apply`/`reapplyAll` use `UPDATE` and skip when non-periodic work holds the name. Task 2.2.3b |
| R-C7 resurrecting disabled work | Store entry/tombstone wins over a snapshot. Story 2.2.3 |
| R-C8 workName property / 64-bit hash | Charset assertion only for hashed kinds; hash declared probabilistically injective, collision detected in `apply`. Story 1.1.1 |
| R-C9 ADR-006 | Sentence corrected (UNMETERED is system-evaluated). ADR-006 |
| R-C10 `workManagerOrNull` private | New Task 2.2.1f extracts it first. |
| R-C11 attempt budget | One-shot skips and real failures share `runAttemptCount` and `MAX_ATTEMPTS`; `oneShotRetryCap` is the same budget, not additive. Stories 2.2.2, 3.2.1 |
| Minor: `TaskSpec` equality | Compared fields listed; the task instance is excluded. Story 1.1.1 |
| A-C1 `GitLegacyConfigSource` | Interface with explicit `Unavailable` in `git/` (Task 1.1.4f); Android impl promotes the private cold read (Task 3.1.2f); Desktop impl over the open graph's `GitConfigRepository` (Task 3.1.3f). "Cannot read" never means "no remote"; no marker on `Unavailable`. ADR-003 |
| A-C2 `onGraphRemoved` shape | Root launches on a guarded scope; startup reconcile is the backstop; a throwing callback does not change `removeGraph`'s return (test). Task 2.2.3d |
| A-C3 tombstone vs re-add | `write` supersedes a tombstone; a re-added graph starts disabled (test). Story 1.1.4 |
| A-C4 cold worker DB open | Seed only when entry and marker are both absent; existing-entry-never-calls-seed worker test. Story 2.2.2 |
| A-C5 serialization chain | 3.1.3e and 3.1.4c added to the chain. Serialization rule, critical path, diagram |

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `TaskKind` | Open identity of a schedulable task type (`"git-sync"`, `"model-download"`) | `@JvmInline value class TaskKind(val id: String)`; `init { require(id.matches(Regex("[a-z0-9-]+"))) }` so no `_` or separator can appear in a kind (injective keys); not `JobKind` |
| `TaskScope` | The owner a task instance is scoped to: a graph id, or `""` for app-wide | Value class over `String`. `""` means app-wide only; per-kind defaults are NOT a scope (see `TaskDefaults`) |
| `TaskKey` | `(TaskKind, TaskScope)`; identity of one scheduled instance and source of its work name, settings key and run-log key | `workName()` special-cases legacy names; every encoded key uses `TaskKeyEncoding.encode` (kind + `.` + length-prefixed scope), injective by construction (ADR-005) |
| `TaskShape` | Sealed: `Periodic(defaultSchedule)` or `OneShot` | Lives only in `TaskSpec` (registration). `apply` takes a periodic `TaskConfig`; `runOnce` is the one-shot entry, so `OneShot` cannot reach `apply` |
| `Schedule` | Fixed interval plus jitter window: `intervalMinutes`, `jitterMinutes` | Validated: private constructor, `Schedule.of(interval, jitter)` requires `interval >= 1`, `0 <= jitter <= interval`; the codec uses `Schedule.coerce(raw)`; no negatives exist after construction. No cron; clamped by `capabilities` |
| `EffectiveSchedule` | `Schedule` after `Schedule.clamp(capabilities)`; what the platform actually gets and the UI shows | Surfaces the clamp, never silent |
| `JitterPolicy` | Pure functions: `offsetMinutes(seed, jitter)`, `flexMinutes(interval, jitter)`, `nextDelayMs(interval, jitter, random)` | Injected `Random`; property-tested |
| `JitterSeed` | Stable FNV-1a hash of `kind:scope` | Same key, same seed across processes |
| `NetworkPolicy` | Enum `Any` / `Unmetered` | "Off" is `enabled = false`, not a policy value |
| `NetworkGate` | Pure three-valued check of `NetworkPolicy` vs live `online` and `isMetered: Boolean?` | Unknown fails closed for `Unmetered`. `evaluateMetered(policy, isMetered)` is the explicit helper for callers with no `online` signal (LFS) |
| `NetworkSkipReason` | Sealed: `Offline`, `MeteredNetwork`, `UnknownNetwork` | Rendered to a string at the port boundary |
| `TaskConstraints` | `network: NetworkPolicy`, `requiresCharging`, `batteryNotLow` | Mapped to WorkManager `Constraints` |
| `TaskConfig` | `enabled`, `schedule: Schedule`, `constraints` | Periodic only. Persisted per `TaskKey` as part of `TaskEntry`. The "Late" multiple is the constant `ScheduleLateness.LATE_MULTIPLE = 2` (matches `STALE_FACTOR`), not a setting |
| `TaskEntry` / `TaskDefaults` / `TaskOverrides` | `TaskEntry(enabled, overrides: TaskOverrides?)`: `overrides == null` means "Using default"; `TaskOverrides(schedule: Schedule?, constraints)` where `schedule` is null for one-shot kinds; `TaskDefaults(schedule: Schedule?, constraints)` is stored once per kind at `task_default_<kind>` | Resolves the UX default-row collision: the default is a per-kind record, never a `TaskScope`. Resolved `TaskConfig = entry.enabled + (overrides ?: defaults ?: spec.defaultConfig)`; a periodic kind with a null schedule falls back to the default schedule. For a one-shot kind only `.constraints` of the resolution is used and `enabled` is ignored (see `TaskSpec`) |
| `TaskReadResult` | Sealed `Ok(TaskConfig)` / `ReadFailed(cause)` returned by `TaskSettingsStore.readResult(key)` | `read(key)` stays fail-closed (`ReadFailed` -> disabled default) for UI and controller callers; workers and the status line use `readResult` so "cannot read" is distinguishable from "disabled" (pre-mortem #4) |
| `SettingsReadHealth` | androidMain, plain (non-Keystore) `SharedPreferences` record of the last settings read/write failure time | Feeds a "Can't read settings" status instead of a false Late; never holds user data |
| `RunSource` | Enum `Background` (worker, in-process scheduler) / `Foreground` (`PeriodicSyncDriver` ticks) | Each source writes its own run-log key, so every key keeps one writer; `TaskRunLog.recent` merges them newest-first |
| `recordRun` | Top-level write-only function `recordRun(runLog, record)` in androidMain, `Throwable`-guarded (rethrows `CancellationException`), never changes a worker `Result` | Shared by `TaskWorker` (`runTaskWork`) and the thin `GitSyncWorker`; needs no registry or store |
| `KEY_NETWORK_POLICY` | WorkManager input-data key (`"any"` / `"unmetered"`) set by every adapter enqueue | The worker's `NetworkGate` policy for one-shots and for `GitSyncWorker`; absent (pre-upgrade job) = `Any`. Keeps the worker independent of the store |
| `LegacyConfigSeed` | `fun interface` in `jobs.schedule`: `suspend fun seed(key): TaskConfig?` | Injected into `TaskSettingsStore`; git implements it (`LegacyGitSyncMigration`). Lets the store migrate lazily without importing `git` |
| `AppliedConfigLedger` | Settings-backed record (explicit `Settings` constructor param) of the last `TaskConfig` each adapter `apply`ed per key, plus a `PausedSnapshot` taken from the live work at `pause` time | `resume` restores from the pause snapshot; the store only when there is no snapshot; an explicit store entry/tombstone saying disabled wins (never resurrect). Cleared on `cancel`/disable. A process-static in-memory copy of the snapshot backs a silently no-op `Settings` |
| `GitLegacyConfigSource` | `fun interface` in `git/`: `suspend fun read(graphId): LegacyGitConfigResult` (`Found(hasRemote, pollIntervalMinutes)` or `Unavailable`) | `Unavailable` (or a throw) is "cannot read", never "no remote"; the seed writes no marker. Android impl over the promoted cold DB read; Desktop impl over the open graph's `GitConfigRepository` |
| `KnownScopes` | `fun interface` in `jobs.schedule`: `suspend fun read(): KnownScopesResult` (`Known(set)` or `Unknown`) | `Unknown` on empty/unreadable/corrupt registry; destructive paths do nothing on `Unknown` (ADR-005). Impl `GraphRegistryKnownScopes` lives in `db/` |
| `LastSuccessSource` | `fun interface`: `suspend fun lastSuccessAtMs(key): Long?` | What `ScheduleLateness` consumers read; git supplies `max(TaskRunLog, SyncStalenessStore)` |
| `NetworkSnapshotProvider` | `fun interface`: `suspend fun snapshot(): NetworkSnapshot(online, isMetered: Boolean?)` | One implementation over `ConnectivityManager` used by `TaskWorker` and LFS; throws => `UnknownNetwork` skip |
| `OneShotConflict` | `Keep` / `Replace` | `runOnce(key, constraints, onConflict = Keep)`; `Replace` is "Download now anyway" |
| `TaskSettingsStore` | Typed, versioned, fail-closed store over `Settings`, one string per `TaskKey` | ADR-003 |
| `LegacyMigration` | One-time derivation of git-sync config from `scheduled_sync_enabled` + `git_config` | Marker key; idempotent |
| `BackgroundTask` | `fun interface`: `suspend run(key, ctx): TaskRunResult` | Registered by feature code |
| `TaskSpec` | Registration metadata: `jobKind: JobKind?`, `title`, `shape: TaskShape`, `defaultConfig`, `allowedInColdPath`, `oneShotRetryCap` | **`enabled` is periodic-only** (KDoc on `TaskSpec.shape` and `TaskConfig.enabled`): for `TaskShape.OneShot`, `TaskWorker` never consults `enabled`, an explicit `runOnce` always runs, and `enabled=false` means only "automatic, non-user-initiated starts are off" (none exist in v1). `jobKind` is nullable: a kind with no `JobKind` gets no kind tag. Bridges to `JobKind`/tags. "Identical spec" equality compares exactly these fields; the `BackgroundTask` instance is NOT compared (two `GitSyncTask` objects from two process starts must register without throwing). `oneShotRetryCap` is part of the shared one-shot attempt budget (see Story 2.2.2) |
| `TaskRegistry` | Kind to `(TaskSpec, BackgroundTask)` lookup, populated in `SteleKitApplication.onCreate` only on Android | Cold-start requirement. `register` is idempotent for an identical spec and throws only for a conflicting spec |
| `TaskRunContext` | `scheduledAtMs?`, `startedAtMs`, `appOpen`, `ConstraintState` | Built by the adapter |
| `ConstraintState` | `online`, `isMetered: Boolean?`, `charging: Boolean?` snapshot at run start | Logged with every run |
| `TaskRunResult` | Sealed: `Success`, `Partial(detail)`, `Skipped(reason)`, `Failed(message, retryable)` | `retryable` default false. For periodic work `Skipped` is `Result.success()` (next period retries); for one-shot work `Skipped` is bounded `Result.retry()` then terminal `Failed` (see Story 2.2.2) |
| `BackgroundTaskScheduler` | The port: `apply(key, TaskConfig)`, `runOnce(key, constraints, onConflict = Keep)`, `cancel`, `cancelAll(scope)`, `pause(scope)`, `resume(scope)`, `describe(key): TaskScheduleInfo?`, `capabilities` | Seam in `WiringAudit.SEAMS`. `describe` is on the port (UI effective value, Desktop status line, contract suite). **Naming note (C10)**: the port's `pause`/`resume` are an internal quiesce/unquiesce for clone and graph moves and are never shown to a user; the UI word "Paused" means only the 3-failure error stop (`PAUSE_AFTER_FAILURES`). No rename this release (a rename would touch the static `pauseFor`/`resumeFor` the existing tests pin) |
| `TaskScheduleInfo` | Result of `describe`: effective interval, `clamped`, `nextRunAtMs?`, `minGapMinutes`, `maxGapMinutes`, `appliesAfterNextRun`, optional downgrade reason | The UI spread copy derives from it per adapter (flex semantics differ from the in-process `[interval, interval + jitter]`) |
| `SchedulerCapabilities` | What an adapter honours (min interval, min flex, unmetered, charging, jitter, runs when closed, `schedulesWork`) | UI disables options with a reason; `schedulesWork=false` (Noop) hides the Background tasks section |
| `TaskRunLog` | Persisted, capped per-run records plus a `Logger` line | Survives process death. Two sources per key (`RunSource.Background` written by workers/in-process scheduler, `RunSource.Foreground` written by `PeriodicSyncDriver` ticks), one writer each; `recent` merges |
| `TaskRunRecord` | `key`, `source`, `scheduledAtMs?`, `startedAtMs`, `result`, `constraintState` | One log row |
| `ScheduleLateness` | Pure: `Late` / `OnTime` / `NotApplicable` from last success, effective interval and the constant `LATE_MULTIPLE = 2` | Wording is "Late", never "Behind" |
| `RestrictionReason` | Enum `None`, `BatterySaver`, `AppRestricted`, `StandbyRestricted` | Replaces the boolean probe |
| `TaskForegroundProvider` | androidMain-only, per kind, user-initiated one-shot foreground notification | Not in the port. In v1 it only PRESERVES the existing `DepthModelDownloadWorker` dataSync notification (no new notification, no new kind uses it) |
| `NoopTaskScheduler` | Adapter with all capabilities false (iOS, Web, tests) | No default anywhere: every host (Android, Desktop `Main.kt`/`App.kt`, wasm, iOS, test fixtures) names its scheduler explicitly in `StelekitAppPlatformIntegrations` |
| `InProcessTaskScheduler` | Coroutine-loop adapter for Desktop; runs while the app is open | Owns its scope + `CoroutineExceptionHandler` |
| `WorkManagerTaskScheduler` | Android adapter over `androidx.work` 2.9.1 | Generic `TaskWorker` |
| `TaskWorker` | The generic `CoroutineWorker` that gates, runs, logs, for one-shots and non-git periodic kinds | Catches `Throwable`; never `retry()` unless `Failed.retryable`. `git-sync` is NOT enqueued as `TaskWorker`: `apply`/`resume` enqueue `GitSyncWorker` for it (Spike 0.1.1 gate, Story 3.1.2) |
| `ScheduledTaskReconciler` | Startup cancel of orphaned work for removed graphs | Tag-based; acts only on `KnownScopes.Known` (ADR-005) |
| `GitSyncTask` | `BackgroundTask` for `git-sync`; `ScheduledSyncPolicy` stays the gate inside it | Lives in `git/`; androidMain behaviour (watchdog, `SyncNotifier`, slow-path yield) is supplied through `TaskAdapterHooks` |
| `TaskAdapterHooks` | androidMain-only per-kind `preRun`/`postRun` hooks owned by the adapter, not the port | Carries the clone-worker watchdog, slow-path yield and `SyncNotifier` for `git-sync` |
| `ModelDownloadTask` | `BackgroundTask` for `model-download`, `OneShot` | Lives in `platform/ml` (androidMain) |

---

## Test Infrastructure Rules (apply to every story; VERIFIED facts in Spot-checks)

1. **Android/Robolectric evidence uses Gradle.** Run `./gradlew :kmp:testDebugUnitTest --tests '<fqcn>'` (CI runs this; CLAUDE.md lists it) and paste its output. `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests` runs only the curated `AllAndroidUnitTests` list and proves nothing about an unregistered class. Wherever this plan previously said to run the Bazel android target, it now says the Gradle command.
2. **Registration is an explicit task, not a BUILD glob side effect.**
   - New `businessTest` class: add to `AllBusinessTests` (`AllBusinessTestsCompletenessTest` fails CI otherwise). `businessTest/kotlin/BUILD.bazel` is a glob: no BUILD edit.
   - New `commonTest` pure test that must run under Bazel: add its path to `business_common_tests` `srcs` in `kmp/src/commonTest/kotlin/BUILD.bazel` AND register the class in `AllBusinessTests`. Never add `@Test` classes to `common_test_fixtures` (its comment warns they leak into other completeness guards).
   - New `jvmTest` class: add to `AllJvmTests` (explicit list); the jvm BUILD is a glob.
   - New `androidUnitTest` class: no BUILD edit (glob). Add to `AllAndroidUnitTests` only when Bazel Android coverage is wanted and the class does not hit the documented Bazel Android gaps (Compose `setContent` tests stay Gradle-only). This plan wants Bazel coverage for: `WorkManagerJitterSpikeTest`, `WorkManagerTaskScheduler*Test`, `TaskWorkerTest`, `ScheduleStartupTest`, `LegacyWorkUpgradeTest`, the existing `WorkManagerSyncScheduler*Test` classes and `GitSyncSchedulingEntryPointTest` (all currently Gradle-only). Compose UI tests (`ScheduledSyncSettingsTest`, `TaskRunLogRowsTest`) stay Gradle-only.
3. **Contract-suite fixtures get dedicated targets.** Task 1.1.6c adds `scheduler_contract_fixtures_jvm` (`kt_jvm_library`, for `jvm_tests`) and `scheduler_contract_fixtures_android` (`kt_android_library`, visible to `//kmp/src/androidUnitTest/kotlin`, because `android_unit_test_lib` depends only on `git_testsupport_android`, `fake_file_system_android`, `observable_effect_android`). Both contain the abstract `BackgroundTaskSchedulerContract` and `FakeBackgroundTaskScheduler`, no concrete `*Test` classes. The concrete `FakeBackgroundTaskSchedulerContractTest` is listed in `business_common_tests` (with the contract and fake sources) and registered in `AllBusinessTests`.
4. **Commands for the full local gate** are in Task 5.1.1d.
5. **No JVM-only test API in `commonTest`** (rev2, R-C3). `assumeTrue` is `org.junit.Assume` and does not exist in `kotlin.test`; the contract suite and fake live in `commonTest` (compiled for wasmJs too). A capability-conditional invariant is written as an early `return` guarded by the capability (`if (!capabilities.supportsUnmetered) return`).
6. **androidUnitTest tests use plain `kotlin.test`/JUnit only** (rev2, R-C4). `android_unit_test_lib` compiles `glob(["**/*.kt"])` and has no `kotest-property`; a file using `Arb`/`checkAll` there breaks the Bazel Android build even when it is not registered in `AllAndroidUnitTests`. Property tests belong in `commonTest` (or add the dependency to the library as an explicit task, which this plan does not need).

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Approach (Step 0.5) | New neutral port with two adapters, git behind a shim | Ports and Adapters | (A) widen `BackgroundSyncScheduler` in place; (B) wrap a KMP WorkManager library | (A) keeps git naming and one consumer; strength: smallest diff. (B) strength: free iOS later; weakness: no Desktop target and hides the `UPDATE` / read-back behaviour our tests assert. Chosen approach: strength is two consumers and testable seams; weakness is a one-release shim |
| `BackgroundTaskScheduler` | Adapter (GoF) | GoF | Direct WorkManager calls per feature | Duplicated constraint logic, no shared settings UI |
| `apply(key, config)` | Level-triggered idempotent command | Reconciler pattern | `schedule()/cancel()` edge-triggered | Composition `LaunchedEffect` re-fires on every key change; read-back assertion is simple |
| Task registration | Registry + generic worker (Service Locator at the platform edge only) | PoEAA Registry | One Worker class per task | Adding a task needs no platform code |
| Schedule math, gate, lateness | Transaction Script as pure functions | PoEAA | Domain Model with entities | No identity or lifecycle, only arithmetic over values |
| `TaskSettingsStore` | Repository over `Settings` (typed codec) | PoEAA Repository | DB table + `MigrationRunner` | Cold WorkManager process; no enumeration needed; no SQLDelight regen (ADR-003) |
| `TaskKind`, `TaskScope`, `TaskKey` | Newtypes | type-driven-design | raw strings | Prevent kind/scope swap |
| `TaskRunResult`, `TaskShape`, `NetworkSkipReason`, `RestrictionReason` | Sealed interface / enum | type-driven-design | strings / booleans | Exhaustive handling; skip is not failure |
| Network policy | `NetworkGate` pure Strategy-like function over 3-valued input | type-driven-design | `Boolean` metered | `null` must be representable and fail closed |
| Contract tests | Abstract contract suite, one subclass per adapter | `TargetWriterContractSuite` precedent | Per-adapter ad hoc tests | Same invariants on fake, in-process, WorkManager |
| Git migration | Shim / Strangler Fig | Fowler | Big-bang rename | 17+ tests pin current behaviour |
| Legacy settings migration | Lazy migration inside the repository read (injected `LegacyConfigSeed`), eagerly triggered at startup/controller | Repository + Strangler | Startup-only migration (Android `Application.onCreate`) | Startup-only misses Desktop, cold workers, tests and composition; one read path means one trigger |
| Resume after pause | Snapshot of the live work taken at `pause` (Memento), store consulted only when no snapshot, explicit disabled store entry wins | GoF Memento | Ledger written only by `apply`; re-read user store only | `PauseTest` (no `SteleKitContext.init`, no store entry) and `UniqueWorkTest` (job enqueued directly) and pre-upgrade jobs never went through `apply`; only a pause-time snapshot of what is actually running covers them |
| Destructive reconcile | Fail-closed `KnownScopes` provider | type-driven-design | Pass a `Set<String>` | A bare set cannot say "unknown"; empty set would cancel everything |
| Restriction probe | Boolean to enum, boolean kept as derived helper | type-driven-design | boolean only | Settings copy differs per cause |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|----------------|-------------|---------------|
| `git/WorkManagerSyncScheduler.kt` + `GitSyncWorker` (git-coupled worker, hard-coded `CONNECTED`, `resumeFor` restores the 15-min floor, catches `Exception` not `Throwable`) | Hotspot named in `research/architecture.md` section 12; 17+ pinned tests | Isolate via seam | New generic scheduler/worker added beside it; the old class becomes a thin shim over the port (Story 3.1.2). `GitSyncWorker` stays a real `final` class (tests build it with `TestListenableWorkerBuilder<GitSyncWorker>`, assert `workerClassName == GitSyncWorker::class.java.name` and use `GitSyncWorker.KEY_GRAPH_ID`); it keeps its class name and input keys and (rev2, N2) `doWork` stays a THIN WRAPPER that calls the extracted git-sync task functions (fast path, cold slow path, hooks) directly: no `TaskRegistry` lookup, no `enabled` gate, no `TaskSettingsStore` read, with a `Throwable` guard. (Phase 4 repair, P1-2: it DOES evaluate `NetworkGate` through `AndroidNetworkSnapshotProvider` with the policy from input key `KEY_NETWORK_POLICY`, absent = `Any`, and appends one run-log record through the shared write-only `recordRun`; neither needs the registry or the store. Disable always goes through `apply`/`cancel`, so the worker needs no `enabled` read; startup `reapplyAll` and `reconcile` are the backstop for a missed cancel.) `WorkManagerSyncSchedulerRetryOwnerTest` builds the worker with only `KEY_GRAPH_ID` and an empty registry and asserts `Failure`; a registry-dependent body would break it. `runTaskWork`/`TaskWorker` serve one-shots and non-git periodic kinds only; `apply`/`resume` enqueue `GitSyncWorker` for `git-sync`. Deleted in a follow-up once contract suites pass on both adapters. The `Exception`-vs-`Throwable` gap is fixed by the wrapper's guard; the 15-min `resumeFor` is fixed in `resume` (pause snapshot) |
| `git/BackgroundSyncScheduler.kt` / `BackgroundSyncSchedulerFactory` / `BackgroundRestrictionProbe` | Neutral concepts living in `git` | Isolate via seam | Kept `@Deprecated` for one release; `BackgroundRestrictionProbe` moves to `jobs.schedule` with a `typealias` left in `git` |
| `ui/GraphContentGitSyncSetup.kt` (scheduling driven from composition; global flag; `ScheduledSyncControls` consumed by `ScheduledSyncSection` and `ScheduledSyncSettingsTest`) | Per-graph state hung off a global flag and a composable | Refactor-first for the scheduling slice | Story 3.1.3 extracts the `apply()` call and settings read into `GitSyncScheduleController` over `TaskSettingsStore` before UI stories build on it; `ScheduledSyncControls` keeps its public shape (global-looking `enabled`/`setEnabled(Boolean)` now delegate to the graph's entry) so its consumers and tests compile; the rest of the file is left alone |
| `git/PeriodicSyncDriver.kt` | Wall-clock `nextAtMs`; git `TickOutcome` types; owns pause-after-3-failures (`PAUSE_AFTER_FAILURES`), `immediateFirstTick`, `tickInFlight`, busy/offline skips, restart on manual `Success`, and the `ScheduledSyncStatus` fields behind `scheduledSyncLine` | Extend as-is | Stable, tested. **Default for this release (rev1): it stays the git foreground loop on BOTH Android and Desktop** (ADR-001 fallback is now the default). Desktop git is fed by the same settings and jitter seam; `InProcessTaskScheduler` is proven by the contract suite and by `model-download`/fake consumers, and drives non-git kinds. Switching Desktop git onto `InProcessTaskScheduler` is a follow-up gated on a status-line and failure-pause parity test. Only the single `intervalMs()` seam gains jitter (Task 3.1.3d) and a write-only tick recorder (Task 3.1.3g) that appends each tick to `TaskRunLog` under `RunSource.Foreground`; no other driver behaviour changes |
| `db/GraphManager.kt` (1663 lines) | Registry and removal logic; gains one more responsibility (cancel scheduled work on removal) | Extend as-is, narrow callback only | One `onGraphRemoved: (GraphId) -> Unit` callback invoked on the success path of `removeGraph` after `saveRegistry()` (after every `return false` guard); no scheduling code in `db`. `removeGraph` is non-suspend, so the callback only hands off: the root's implementation launches `cancelAll` + `tombstone` on a root-owned guarded scope (`SupervisorJob` + `CoroutineExceptionHandler`), and startup `reconcile` (Known-only) is the backstop for a missed cancel. The invocation is wrapped so a throwing callback never changes `removeGraph`'s return value. A refused removal must not invoke it (tests in Story 2.2.3) |
| `git/lfs/LfsRepositorySupport.kt:45,:144` (`jvmCommonMain`, shared with Desktop) and `git/lfs/LfsTransferPolicy.kt` | `isMetered: () -> Boolean = { false }` default; `deferOnMetered && isMetered()` | Extend as-is, narrow edit | Story 3.2.2 changes the signal to nullable and routes through `NetworkGate.evaluateMetered`, gated on `lfsMeteredDetectionSupported` so Desktop (JVM monitor always returns `null`) keeps today's behaviour |
| `ScheduledSyncPolicy` | None touched | Extend as-is | Remains the gate inside `GitSyncTask`; decisions unchanged |
| `ScheduledSyncPreferences` | Global boolean | Extend as-is (read-only legacy) | Kept for rollback; read only by `LegacyGitSyncMigration` (via `LegacyConfigSeed`) |
| `platform/ml/DepthModelDownloader.kt` | Ad hoc constraints, bespoke worker | Isolate via seam | Download body moves behind `ModelDownloadTask`; worker tests stay green (Story 3.2.1) |

---

## Migration Plan
No SQL schema change: settings are key-value (ADR-003), so no `MigrationRunner.all` entry and no SQLDelight regeneration.
- **Data migration**: lazy and platform-neutral. `TaskSettingsStore.read(key)` calls the injected `LegacyConfigSeed` when the entry and the marker are both absent (Story 1.1.4), so every path (composition, Desktop, cold worker, `resume`, tests) migrates the same way. `GitSyncScheduleController.ensureMigrated(graphId)` (Desktop graph open, settings glue) and `ScheduleStartup` (Android, active graph only) call the same read eagerly. Marker key: encoded key of `task_migrated` + `TaskKey`. Semantics, stated explicitly: `enabled = legacyFlag && graphHasRemote`, i.e. the old global switch applied to every graph with a remote; a never-composed remote-bearing graph therefore turns on, which matches what the global switch already promised the user. Rationale (C8): before this change scheduling was composition-driven, so every remote-bearing graph was enabled whenever it was opened with the global switch on; the lazy seed reproduces that outcome without requiring the open. This is a behaviour change for a never-opened graph (background work now starts without an open) and is listed as an owner-confirm item (Unresolved Questions). The migrated entry keeps jitter 0 and the legacy interval (behaviour-preserving); only NEW graphs get the git-sync default spread of 6 min. Covered by a test (Story 1.1.4) and recorded in ADR-003. The graph's `hasRemote`/`poll_interval_minutes` live in that graph's SQLite `git_config`; the seed opens a non-active graph's DB lazily and one graph at a time through the `GitLegacyConfigSource` Android implementation (Task 3.1.2f, which promotes the private cold read `loadGitConfigFromDb` in `WorkManagerSyncScheduler.kt`), never all graphs in `Application.onCreate`; an unreadable DB is `Unavailable` (no marker), never "no remote".
- **Reversibility**: the legacy keys (`scheduled_sync_enabled`, `git_config.poll_interval_minutes`) are never modified or deleted. A reverted build reads them as before. New keys are ignored by old code.
- **Zero-downtime strategy**: git-sync keeps the work name `stelekit_git_sync_<graphId>` and periodic type, so `UPDATE` converts the running job in place; no cancel-then-enqueue window.
- **Rollback procedure**: revert the PR. Orphaned new-tag metadata is harmless. New keys (`task_entry_*`, `task_default_*`, `task_applied_*`, `task_migrated_*`, `task_run_log_*`) are ignored by old code. Downgrade-then-upgrade is safe because the marker prevents re-deriving from a stale global flag.

## Observability Plan
- **Logs**: one `Logger` line per run from `TaskWorker`, the thin `GitSyncWorker` (both through the shared `recordRun`), `InProcessTaskScheduler`, and `PeriodicSyncDriver` ticks (Foreground source; Task 3.1.3g): `TaskRun kind=<k> scope=<s> scheduledAt=<ms|-> startedAt=<ms> result=<Success|Partial|Skipped:<reason>|Failed:<msg>> online=<b> metered=<true|false|unknown> charging=<..>`. Schedule applications log `TaskApply key=... enabled=... effectiveInterval=... flex=... offset=... network=...`. Reaches `filesDir/logs/current.log`; no `android.util.Log`.
- **Run log**: `TaskRunLog` persists the last N (cap 20 per key; `SyncStalenessStore.HISTORY_LIMIT` is 10) records through `Settings`, so a cold worker process records skips and the Logs screen can replay them. Single writer per key makes the read-modify-write of one string safe: each (key, `RunSource`) pair has its own settings key (`task_run_log_<kind>.<len>.<scope>` for Background, `task_run_log_fg_...` for Foreground), written only by the worker / in-process loop (Background) or by the `PeriodicSyncDriver` tick recorder (Foreground), and `TaskRunLog.recent` merges both newest-first (cap 20 per source, 20 returned). `SyncStalenessStore` is still written by the foreground loop and "Sync now", and `GitLastSuccessSource` still takes the max of both (a manual "Sync now" never goes through the driver tick). Failures to read settings in a cold process are logged via `Logger` and recorded in the plain-prefs `SettingsReadHealth` so the status line can say "Can't read settings" instead of Late (pre-mortem #4).
- **Metrics**: none (local-first app). The user-facing status line is the alert: `ScheduleLateness` (`Late`) and `RestrictionReason` hint.
- **Alerts**: no new alerts required.

## Risk Control
- **Feature flag**: per-(graph, kind) `enabled` for periodic kinds, default off for new graphs; migrated users keep their effective choice. Global fail-closed settings read (decode failure or exception = disabled). `enabled` does not gate one-shot kinds: an explicit `runOnce` always runs (P1-1).
- **Kill switch**: user disables the task in Settings (cancels the work); `BackgroundTaskScheduler.cancelAll(scope)`.
- **Rollback procedure**: standard revert via PR close + revert commit; legacy keys untouched (see Migration Plan).
- **Staged rollout**: full rollout on merge through release-please. Android device verification (Story 5.1.3) gates promoting the release PR, not the merge.

## Unresolved Questions
- [ ] Does `UPDATE` with a changed `flexInterval` / `initialDelay` behave as ADR-002 assumes on 2.9.1, does `UPDATE` change `workerClassName` from `GitSyncWorker` to `TaskWorker`, and does `UPDATE` on a currently RUNNING periodic request behave? — blocks Stories 1.1.2 (final clamp constants), 2.2.1, and is a HARD GATE on Story 3.1.2 (Task 3.1.2g) — owner: Spike 0.1.1 (agent), result recorded in ADR-002 (the three cases are now in the spike, not deferred to 3.1.2d). Decision already made independent of the outcome: `apply` and `resume` enqueue `GitSyncWorker` for `git-sync`; the spike result only decides whether a later move to `TaskWorker` is possible.
- [x] `WorkInfo.periodicityInfo` read-back of `repeatIntervalMillis` is available on 2.9.1 (RESOLVED: used by `GitSyncSchedulingEntryPointTest.kt:95`). Still open for Spike 0.1.1: flex and `nextScheduleTimeMillis` read-back. If absent, read back via `WorkManagerImpl` `WorkSpec` in the Robolectric test only.
- [ ] Owner confirmation of product behaviour changes bundled here (adversarial C12): (a) model-download default `CONNECTED` -> `UNMETERED` (ADR-004; one-line revert: `TaskSpec.defaultConfig`), (b) "Download now anyway" action, (c) LFS fail-closed on unknown network (Android only; Desktop unchanged, Story 3.2.2), (d) graph-removal callback in `GraphManager` (and the orphan reconcile / startup self-heal it pairs with), (e) migration `enabled = legacyFlag && hasRemote` also turning on a never-opened remote-bearing graph (C8; rationale in the Migration Plan; the alternative is `enabled=false` until opened), (f) Desktop git stays on `PeriodicSyncDriver` this release (C1, recorded in `requirements.md`), (g) default spread "A little" (6 min) for new git-sync graphs, (h) Desktop radio arrow-key navigation. All are listed in `requirements.md` In Scope as flagged items. Not blocking implementation; each lands as its own commit so the owner can drop one at PR review. Requirements lines 54 and 103 already name a Wi-Fi/unmetered-constrained second consumer.
- [ ] Open PR stack touching git sync (`gh pr list --search "git sync"`): which must merge before Story 3.1.2? — blocks Story 3.1.2 — owner: Task 0.1.2b (agent), merge decisions with the repo owner.
- [ ] Android 14/15 FGS limit numbers for any UI copy — not blocking (copy avoids numbers) — owner: Task 4.1.2d.
- [ ] Does the Robolectric `ConnectivityManager` shadow report the network as online for `RetryOwnerTest`'s unchanged setup once the thin `GitSyncWorker` evaluates `NetworkGate`? — blocks Task 3.1.2c — owner: Task 3.1.2b (agent) runs the existing class against a gate-enabled build first. Mitigation already decided: with policy `Any` (absent key), a throwing or unavailable provider PROCEEDS (WorkManager's `CONNECTED` constraint already guaranteed connectivity); only an explicit `online=false` skips. If the shadow still reports offline, the minimal fix is a test-only `GitSyncWorker.snapshotProviderOverride`, not an edit to the existing assertions.
- [x] Jobs-list noise (RESOLVED by decision, adversarial C8): periodic scheduling requests carry only scheduling tags (`stelekit-sched`, `stelekit-sched-scope:<scope>`, plus the kind tag), NOT `stelekit-job` (`JobTags.ANY`); one-shot requests carry `stelekit-job` so they appear in the jobs list. `WorkManagerJobRegistry` therefore never shows a permanent "Queued" Git sync row. Test in Task 2.2.1e.

## Effort estimate
Basis: bands and weights INFERRED (ESTIMATION.md); recalibrate after the first implementer run. CU = `in + 1.25*cw + 0.1*cr + 5*out`; shown here as raw tokens x (1 + 4 x output share), ignoring cache discounts (conservative).

| Story | Tasks | Class | Est. raw tokens | Assumed output share | Est. CU | Wall-clock blocker |
|---|---|---|---|---|---|---|
| 0.1.1 WorkManager jitter spike | 4 | M | 190k | 10% | 266k | none |
| 0.1.2 Settings/probe/PR-stack spike | 2 | S | 70k | 8% | 92k | none |
| 1.1.1 Port value types + registry | 5 | M | 160k | 15% | 256k |  |
| 1.1.2 JitterPolicy + clamp | 4 | M | 130k | 15% | 208k |  |
| 1.1.3 NetworkGate | 3 | S | 70k | 15% | 112k |  |
| 1.1.4 Settings store, defaults, lazy migration | 6 | M | 285k | 15% | 456k |  |
| 1.1.5 Import-scan + suite registration | 2 | S | 50k | 12% | 74k |  |
| 1.1.6 Fake, ledger + contract suite + fixtures | 3 | M | 170k | 15% | 272k |  |
| 1.2.1 TaskRunLog + LastSuccessSource | 5 | M | 145k | 15% | 232k |  |
| 1.2.2 Lateness + RestrictionReason | 4 | M | 110k | 15% | 176k |  |
| 2.1.1 InProcessTaskScheduler | 4 | M | 160k | 15% | 256k |  |
| 2.1.2 Noop + explicit host wiring | 2 | M | 90k | 12% | 133k |  |
| 2.2.1 WorkManagerTaskScheduler | 6 | M | 240k | 15% | 384k |  |
| 2.2.2 Generic TaskWorker + hooks + KnownScopes + recordRun + read health | 9 | M | 340k | 15% | 544k | Early owner Keystore checkpoint (Task 2.2.2h) |
| 2.2.3 pause-snapshot/resume + reconcile + removal hook | 4 | M | 235k | 15% | 376k |  |
| 3.1.1 GitSyncTask + last-success source | 3 | M | 140k | 15% | 224k |  |
| 3.1.2 Legacy shim + exclusion regression + gate/record | 7 | M | 275k | 15% | 440k | open PR stack merge (repo owner); Spike 0.1.1 ADR-002 gate |
| 3.1.3 Glue, migration trigger, Desktop driver, tick recorder | 7 | M | 320k | 15% | 512k |  |
| 3.1.4 Startup migrate/reconcile/self-heal | 3 | M | 120k | 15% | 192k |  |
| 3.2.1 ModelDownloadTask + runOnce + e2e + presentation | 8 | M | 300k | 15% | 480k |  |
| 3.2.2 LFS shares NetworkPolicy | 3 | M | 115k | 15% | 184k |  |
| 4.1.1 Settings UI (+ Task 4.1.1g, triad repair 1) | 7 | M | 395k | 15% | 632k |  |
| 4.1.2 Status line + restriction hint | 5 | M | 205k | 15% | 328k |  |
| 4.1.3 Logs screen run records | 2 | S | 85k | 15% | 136k |  |
| 5.1.1 Wiring gates | 4 | M | 130k | 12% | 192k |  |
| 5.1.2 Docs / ADR status | 2 | S | 40k | 10% | 56k |  |
| 5.1.3 Device verification | 2 | S | 30k | 5% | 36k | Owner device runs (see below) |

Triad repair 1 (UX gaps) adds one task (4.1.1g, +110k raw / +176k CU); the other UX additions (Stories 4.1.2, 4.1.3, 3.2.1, 5.1.3 criteria and the owner checklist) are folded into existing tasks and treated as within estimate noise. Updated sum (9 epics, 27 stories, 116 tasks; recounted by `grep -c '^##### Task '`): about 4.60M raw tokens, about 7.25M CU before verification, about 12.7M CU with the x1.75 multiplier (still Large, 3-15M, informational). The figures in the next paragraph are the pre-triad Phase 4 repair totals.

Previous sum (9 epics, 27 stories, 115 tasks; recounted by grep after the Phase 4 repair, was 105 after rev2, 101 after rev1 and 90 originally): about 4.49M raw tokens, about 7.07M CU before verification (was 4.11M raw / 6.47M CU after rev2). Phase 4 repair adds +10 tasks (1.2.1e, 2.2.2g/h/i, 3.1.2g, 3.1.3g, 3.2.1f/g/h, 4.1.1f) and about +380k raw / +604k CU, mostly the one-shot end-to-end test, the shared `recordRun`/gate on `GitSyncWorker`, the foreground tick recorder and the extra Settings UI cases. With the x1.75 verification multiplier (story review, `6-verify`, CI reruns): about 12.4M CU, size band Large (3-15M, informational only; no gate). Rev2 growth (+4 tasks: 1.1.4f, 2.2.1f, 3.1.2f, 3.1.3f; heavier 2.2.2 and 2.2.3) comes from the pause-snapshot ledger, the `GitLegacyConfigSource` implementations, the extracted `workManagerOrNull` and the extra worker/resume/removal tests. (Row CUs are rounded to the nearest k, so three rows differ from `raw x (1 + 4 x share)` by under 1k.)

**Note (triad repair 1, engineering)**: the 8 story-level waves below understate the real serial run depth. The root-file chain alone (2.1.2b -> 2.2.3d -> 3.1.1c -> 3.1.2c -> 3.1.3c -> 3.1.3e -> 3.1.3g -> 3.1.4c -> 3.2.1d, plus 2.2.2c) is about 10 serial tasks that cannot be parallelised because they edit the same root files, so wall-clock is governed by that chain, not by the wave count.

**PR-stack check (triad repair 1)**: `gh pr list` for open git-sync PRs was run during triad review and found none; this is a point-in-time result, so Task 0.1.2b and Task 3.1.2a re-check before Story 3.1.2 starts.

**Critical path in agent waves** (serial dependency depth, 8 waves): W1 Spikes 0.1.1 and 0.1.2 (parallel) -> W2 Epic 1.1 story 1.1.1 (types) -> W3 1.1.2, 1.1.3, 1.1.4, 1.1.5 and Epic 1.2 stories (parallel; disjoint files, except every story appends one line to `AllBusinessTests.kt` and `business_common_tests` `srcs`: those registration tasks are the only shared-file edits and are done by each story's own registration task with a rebase, not in one commit) -> W4 1.1.6 (needs types + fake + ledger) -> W5 2.1.x and 2.2.1 (parallel; but 2.1.2b is the first root-file edit) -> W6 2.2.2, 2.2.3, 3.1.1 (2.2.3d then 3.1.1c serialize on root files) -> W7 3.1.2 -> 3.1.3 -> 3.1.4, with 3.2.x in parallel except 3.2.1d (root-file edit, last) -> W8 4.1.x -> 5.1.x. Stories that share files and must serialize: 2.2.1 -> 2.2.2 -> 2.2.3 (`jobs/` androidMain), the root-file chain 2.1.2b -> (2.2.2c, in Story 2.2.2) -> 2.2.3d -> 3.1.1c -> 3.1.2c -> 3.1.3c -> 3.1.3e -> 3.1.3g -> 3.1.4c -> 3.2.1d (`StelekitAppDependencies.kt`, `MainActivity.kt`, `SteleKitApplication.kt`, `desktop/Main.kt`, both wired-app fixtures), 4.1.1 -> 4.1.2 (`ScheduledSyncSection.kt`).

**Overrun checkpoint**: if cumulative spend exceeds 125% of the table value for completed stories (for example more than about 2.68M CU of spend by the end of Phase 1, whose cumulative planned CU with Phase 0 is about 2.14M, before the verification multiplier), pause and report the delta and cause. Informational, not an invitation to cut.

**Wall-clock blockers** (separate from tokens):
0. Early owner Keystore cold-worker checkpoint right after Story 2.2.2 (Task 2.2.2h) so Epic 3.x does not build on an unverified fail-closed read path (pre-mortem #4). Owner: repo owner.
1. Owner device verification: `Pixel_Fold` AVD standby-bucket / Doze run, a physical OEM device (Xiaomi/Samsung class) run, and the Keystore-backed cold-worker settings check; read persisted logs, not logcat. Owner: repo owner (Task 5.1.3b).
2. Open PR stack on git sync merging first (Story 3.1.2). Owner: repo owner.
2a. Owner confirmation of the bundled product-behaviour changes (Unresolved Questions, adversarial C12); non-blocking.
3. Soak: after release, the "Late" state and run log are the only field evidence of OEM behaviour; observe one release cycle before declaring the reliability metric met. Owner: repo owner.
4. Human review latency on the draft PR(s).

## Dependency Visualization

```
Phase 0 (spikes, run first, parallel)
  0.1.1 WorkManager jitter/UPDATE/worker-class spike ──┐
  0.1.2 settings(fallback-only)/probe/PR-stack spike ──┤
                                                       ▼
Phase 1 (pure commonMain core; each story registers its tests in AllBusinessTests)
  1.1.1 types+keys+registry ─┬─► 1.1.2 JitterPolicy ───────────┐
                             ├─► 1.1.3 NetworkGate ────────────┤
                             ├─► 1.1.4 SettingsStore+defaults+ │
                             │        lazy LegacyConfigSeed ───┤
                             ├─► 1.1.5 import-scan+AllBusiness │
                             ├─► 1.2.1 TaskRunLog+LastSuccess ─┤
                             └─► 1.2.2 Lateness+Restriction ───┤
  1.1.1 + 1.1.2 + 1.1.3 + 1.1.4 ─► 1.1.6 fake+ledger(pause snapshot)+contract ◄┘
                                   + fixture targets (jvm, android)
                                          │
Phase 2 (adapters)                         ▼
  1.1.6 ─┬─► 2.1.1 InProcess ─► 2.1.2 Noop + explicit host fields (FIRST root-file edit)
         └─► 2.2.1 WorkManager ─► 2.2.2 TaskWorker+hooks+KnownScopes+NetworkSnapshot
                                       ─► 2.2.3 pause(snapshot)/resume+reconcile+removal hook(guarded launch)
                                          │
Phase 3 (consumers)  root-file chain: 2.1.2b → 2.2.3d → 3.1.1c → 3.1.2c → 3.1.3c → 3.1.3e → 3.1.3g → 3.1.4c → 3.2.1d
  1.1.4f GitLegacyConfigSource iface ─► 3.1.2f Android impl, 3.1.3f Desktop/active impl
  2.2.1f workManagerOrNull extract ─► 2.2.1b (and 3.2.1c)
  2.2.2 + 1.1.4 + 1.2.1 ─► 3.1.1 GitSyncTask+GitLastSuccessSource ─► 3.1.2 shim (GitSyncWorker = thin wrapper, no registry/store, but shared NetworkGate + recordRun; HARD GATE: ADR-002 holds Spike 0.1.1 outcomes; pause snapshots live work)
        ─► 3.1.3 controller (apply only if runsInBackgroundWhenClosed)+ensureMigrated+Desktop keeps PeriodicSyncDriver+3 entry-point tests
        ─► 3.1.4 startup migrate(active)/reconcile(KnownScopes)/reapply(ledger)
  2.2.2 + 1.1.3 ─► 3.2.1 ModelDownloadTask (one-shot skip→retry(shared 5-attempt budget)→failure(KEY_ERROR)) ─► 3.2.2 LFS (nullable metered, Desktop unchanged)
                                          │
Phase 4 (UI)                               ▼
  3.1.3 + 1.2.2 + 1.2.1 + 3.1.1 ─► 4.1.1 Settings UI ─► 4.1.2 status+hint ─► 4.1.3 Logs rows
                                          │
Phase 5                                    ▼
  all ─► 5.1.1 wiring gates (also written per-story) ─► 5.1.2 docs ─► 5.1.3 device verification (owner)
```

Wiring-gate catch-up rule: every adapter story adds its own catalog/allowlist entry in the same PR (policy "a PR that wires or deletes a listed class deletes its allowlist line"); Story 5.1.1 is the final audit (including the test-registration audit) plus the shim-era `SEAMS` cleanup.

---

## Phase 0: Spikes (riskiest first)

### Epic 0.1: Validate assumptions before dependent stories start
**Goal**: Resolve the jitter mechanism and API availability on WorkManager 2.9.1 and the facts the plan leans on.

#### Story 0.1.1: WorkManager jitter / `UPDATE` / read-back spike
**As a** maintainer, **I want** executable evidence of how `PeriodicWorkRequest` flex, initial delay and `UPDATE` behave on 2.9.1, **so that** ADR-002 is accepted or its fallback adopted before any dependent code is written.
**Acceptance Criteria**:
- A Robolectric test enqueues periodic work with `flexInterval` and `initialDelay` and reads them back.
  - *Given* a `TaskKey(git-sync, "g1")` and interval 60 min, jitter 10 min, seed offset 7 min, *When* the spike enqueues `PeriodicWorkRequest(60 min, flex 10 min)` with `initialDelay = 7 min` under `UPDATE` and reads `WorkInfo`, *Then* the recorded `WorkInfo` exposes interval 60 min, flex 10 min, constraints `UNMETERED` (or the spike records exactly which fields are unavailable).
- `UPDATE` re-apply semantics are recorded.
  - *Given* work enqueued at interval 60 min / flex 10 min, *When* `enqueueUniquePeriodicWork(..., UPDATE, request(interval 120 min, flex 20 min, initialDelay 9 min))` runs, *Then* the spike records whether interval, flex and next-run time changed and whether the work id stayed the same; the result is written into ADR-002 (Accepted, or Accepted-with-fallback).
- `UPDATE` across the worker-class change and on RUNNING work is recorded (rev1: moved here from Task 3.1.2d, because the shim design depends on it).
  - *Given* a periodic request enqueued with worker class `GitSyncWorker` under `stelekit_git_sync_g1`, *When* `enqueueUniquePeriodicWork(..., UPDATE, <request with worker class TaskWorker>)` runs, *Then* the spike records whether `WorkSpec.workerClassName` changed and whether the work id and run history stayed; *Given* the same work in state RUNNING (test driver `setPeriodDelayMet`/running state), *When* `UPDATE` is applied, *Then* the spike records whether the running instance is cancelled, left alone, or the update deferred. Decision independent of the outcome (Phase 4 repair): `apply` and `resume` enqueue `GitSyncWorker` for `git-sync`, a thin wrapper that gates and records itself (Story 3.1.2). If the class does NOT change under `UPDATE`, ADR-002 records that `GitSyncWorker` stays the class for existing jobs permanently; if it DOES change, ADR-002 records that a later move to `TaskWorker` is possible but is not done in this release. The outcome is a HARD GATE on Story 3.1.2 (Task 3.1.2g): 3.1.2c does not start until ADR-002 holds it.
- Observed run-time distribution and `UPDATE` next-run semantics (Phase 4 repair, pre-mortem #5).
  - *Given* a periodic request (60 min, flex 10 min, initial delay 7 min) driven by `TestDriver` (`setPeriodDelayMet` over several periods), *When* the spike records each run's virtual start time, *Then* ADR-002 records the observed gap range between consecutive runs (flex runs in the LAST `flex` minutes of each period, so the gap is `[interval - flex, interval + flex]`, not `[interval, interval + jitter]`) and whether an `UPDATE` keeps the existing next-run time (so a changed offset or flex applies only after the next run). Story 4.1.1's spread copy and `TaskScheduleInfo.minGapMinutes`/`maxGapMinutes`/`appliesAfterNextRun` are set from this, per adapter.
  - *Given* a periodic request carrying input data `graph_id`, `interval_minutes` and `network_policy`, *When* `UPDATE` re-applies it with a different `network_policy`, *Then* the spike records whether the stored input data changed (the worker's gate reads `KEY_NETWORK_POLICY`, so an UPDATE that keeps the old input would apply a stale policy).
- Result is a go/no-go for `periodicityInfo` read-back and `setNextScheduleTimeOverride`.
  - *Given* WorkManager 2.9.1 on the test classpath, *When* the spike calls `WorkInfo.periodicityInfo` and `setNextScheduleTimeOverride` through the test driver, *Then* ADR-002 records "available" or "unavailable" for each with the exact API used.
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/jobs/schedule/WorkManagerJitterSpikeTest.kt` (kept as a regression test), `project_plans/background-task-scheduling/decisions/ADR-002-jitter-flex-plus-deterministic-offset.md`, `kmp/src/androidUnitTest/kotlin/BUILD.bazel`

##### Task 0.1.1a: Test harness (test first) (XS)
- Copy the `WorkManager.initialize` try/catch-`IllegalStateException` guard from `WorkManagerSyncSchedulerPauseTest.kt:36-49` (WorkManager is a JVM-static singleton under Robolectric) and `WorkManagerTestInitHelper` setup from `AndroidGitCloneWorkerLauncherTest.kt`.
- Files: `.../jobs/schedule/WorkManagerJitterSpikeTest.kt`
##### Task 0.1.1b: Flex/initialDelay/constraint read-back test (S)
- Write the scenarios above (including the worker-class and RUNNING `UPDATE` cases); assert on read-back fields, not elapsed time. Mark fields that cannot be read back with an explicit `assumeTrue` and a log.
- Files: `WorkManagerJitterSpikeTest.kt`
##### Task 0.1.1c: Record outcome in ADR-002 (XS)
- Run `./gradlew :kmp:testDebugUnitTest --tests 'dev.stapler.stelekit.jobs.schedule.WorkManagerJitterSpikeTest'` and paste the result; flip ADR-002 status or write the fallback; if a bump of androidx.work is needed, record it in ADR-006 as a separate change.
- Files: `ADR-002-...md`, `ADR-006-...md`
##### Task 0.1.1d: Register the spike under Bazel (XS) (rev1)
- Add `WorkManagerJitterSpikeTest::class` to `AllAndroidUnitTests` (explicit list; see Test Infrastructure Rule 2). `androidUnitTest/kotlin/BUILD.bazel` is a glob, so no BUILD edit.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/AllAndroidUnitTests.kt`

#### Story 0.1.2: Settings backing, probe, PR-stack facts
**As a** planner, **I want** the three facts the research left UNVERIFIED confirmed, **so that** dependent stories do not rest on guesses.
**Acceptance Criteria**:
- Settings persistence on the cold path is confirmed, with its limits stated (rev1).
  - *Given* `SteleKitContext.init(applicationContext)` has run (required; the earlier AC omitted it) and `PlatformSettings()` is constructed with no UI init in a Robolectric test, *When* `putString("task_entry_probe", "v1|1")` then `getString` is called from a second `PlatformSettings()` instance, *Then* the value round-trips; ADR-003 records this as evidence for the plain-prefs FALLBACK path only, because Robolectric has no Android Keystore.
  - *Given* production `PlatformSettings` is `EncryptedSharedPreferences` (Keystore-backed), *When* the spike cannot exercise it under Robolectric, *Then* ADR-003 states that gap, the Keystore path (cold-worker read/write, and the cost of a run-log write from a cold `TaskWorker`) is added to the owner device checklist in Story 5.1.3 (new AC), and the plan does not claim the Keystore path is verified.
- `SyncStalenessStore` key shape is per graph only.
  - *Given* `SyncStalenessStore.kt` source, *When* keys are enumerated, *Then* the plan note states the exact key (`git_sync_staleness_<graphId>`), that its `lastMergedAtMs`/`lastFetchedAtMs` are written by the foreground loop and "Sync now", and that lateness combines them with `TaskRunLog.lastSuccessAtMs` through `LastSuccessSource` (Stories 1.2.1/1.2.2/3.1.1).
- Open PR stack is listed.
  - *Given* `gh pr list --search "git sync" --state open`, *When* the command runs, *Then* the output (numbers, titles, touched files) is pasted into the Unresolved Questions line for Story 3.1.2.
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/jobs/schedule/PlatformSettingsColdPathTest.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/AllAndroidUnitTests.kt`, `project_plans/background-task-scheduling/decisions/ADR-003-settings-store-and-legacy-migration.md`

##### Task 0.1.2a: `PlatformSettings` cold-path test (S)
- Write the round-trip test above (test first, it is the deliverable); call `SteleKitContext.init` first; label the test and ADR-003 note "fallback path only". Register in `AllAndroidUnitTests`.
- Files: `PlatformSettingsColdPathTest.kt`, `AllAndroidUnitTests.kt`
##### Task 0.1.2b: PR stack and key-shape facts (XS)
- Run `gh pr list`, open `SyncStalenessStore.kt`, write both into this plan's Unresolved Questions and ADR-003 (the last-success source is `TaskRunLog.lastSuccessAtMs` in Story 1.2.1 combined with `SyncStaleness`, not a "Task 1.2.2" timestamp).
- Files: `plan.md`, `ADR-003-...md`

---

## Phase 1: Pure core in commonMain

### Epic 1.1: Port, policies, settings, contract suite
**Goal**: The neutral port and every pure rule exist, are property-tested, and an adapter-independent contract suite runs against a fake.

#### Story 1.1.1: Port value types and registry
**As a** feature author, **I want** a git-free port and registration surface, **so that** I can declare a task without platform code.
**Acceptance Criteria**:
- Port lives in `jobs.schedule` with no git/ui/db import (enforced by Story 1.1.5).
  - *Given* a `TaskKey(TaskKind("git-sync"), TaskScope("graph-a"))`, *When* `workName()` is called, *Then* it returns `stelekit_git_sync_graph-a`; for `TaskKey(TaskKind("lfs-transfer"), TaskScope("My Graph/2"))` it returns `stelekit_task_lfs-transfer_` followed by the 16-hex FNV-1a hash of `My Graph/2`.
- Keys are injective (rev1, adversarial C5 / architecture settings-key concern).
  - *Given* `TaskKind("a")` with scope `b_c` and `TaskKind("a-b")` with scope `c`, *When* `workName()`, `TaskKeyEncoding.settingsKey("task_entry", key)` and `TaskKeyEncoding.settingsKey("task_run_log", key)` are computed, *Then* all three differ between the two keys. `TaskKind("a_b")` and `TaskKind("A")` throw `IllegalArgumentException` (charset `[a-z0-9-]+`). A scope that is itself a 16-hex string and the hashed form of another scope never collide: every non-legacy scope is hashed unconditionally (never passed through), and the settings/run-log key uses length-prefix encoding `<prefix>_<kind>.<len(scope)>.<scope>`.
- `TaskRunResult` is neutral and `Failed.retryable` defaults to false.
  - *Given* `TaskRunResult.Failed("auth")`, *When* `retryable` is read, *Then* it is `false`.
- Config validity (rev1, architecture concern on illegal states).
  - *Given* `Schedule.of(-5, 3)` or `Schedule.of(10, 30)` or `Schedule.of(0, 0)`, *When* constructed, *Then* it fails (`null`/exception, per the chosen factory), and `Schedule.coerce(-5, 30)` returns a valid schedule (interval >= 1, jitter in `0..interval`). `apply` accepts only `TaskConfig` (periodic: `schedule: Schedule`); a `TaskShape.OneShot` cannot be passed to `apply` because `TaskShape` appears only in `TaskSpec`.
- Registry is idempotent for an identical registration and rejects conflicts (rev1, adversarial B4).
  - *Given* `TaskRegistry` with `git-sync` registered, *When* `register` is called again with an equal `TaskSpec` and a DIFFERENT `BackgroundTask` instance (a second process start builds a new `GitSyncTask`), *Then* it is a no-op and does not throw: spec equality compares `jobKind`, `title`, `shape`, `defaultConfig`, `allowedInColdPath`, `oneShotRetryCap` and never the task instance; *When* called with a different spec for the same kind, *Then* it throws `IllegalStateException`; `taskFor(TaskKind("nope"))` returns null.
- `enabled` is periodic-only (Phase 4 repair, P1-1).
  - *Given* a `TaskSpec` with `shape = OneShot`, *When* its KDoc and the `TaskConfig.enabled` KDoc are read, *Then* both state that `enabled` is not consulted for one-shot tasks and that an explicit `runOnce` always runs; a compile-level fixture asserts `TaskOverrides(schedule = null, constraints)` is valid and that a periodic resolution with a null override schedule falls back to the default schedule.
- Port shape (rev1): `apply(key, TaskConfig)`, `runOnce(key, constraints, onConflict: OneShotConflict = Keep)`, `cancel`, `cancelAll(scope)`, `pause(scope)`, `resume(scope)`, `describe(key): TaskScheduleInfo?`, `capabilities`.
  - *Given* the compiled port, *When* a unit test calls `runOnce(key, constraints)` and `runOnce(key, constraints, OneShotConflict.Replace)` on the fake, *Then* both compile and the fake records the conflict mode.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/jobs/schedule/{TaskKey.kt,TaskKeyEncoding.kt,TaskModels.kt,TaskRunResult.kt,BackgroundTaskScheduler.kt,TaskRegistry.kt}`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/jobs/schedule/TaskKeyTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel` (`business_common_tests` srcs), `kmp/src/businessTest/kotlin/dev/stapler/stelekit/AllBusinessTests.kt`

##### Task 1.1.1a: `TaskKeyTest` (test first) (S)
- Examples above plus `Arb` properties over generated `(kind, scope)` pairs: for HASHED kinds only (not `git-sync`, whose legacy name keeps the raw graph id, so a scope with a space or `/` is legitimate there) `workName()` contains only `[A-Za-z0-9_-]`; the 16-hex FNV-1a name is injective only probabilistically (64 bits), which a property test cannot prove, so the test asserts no collision across the generated sample and `WorkManagerTaskScheduler.apply` detects a collision at runtime (a different scope tag already holding the same name: log, refuse, no cancel); `settingsKey`/`runLogKey` are injective (length-prefixed, by construction) (including scopes containing `_`, `.`, digits-only and 16-hex strings); kind charset rejection.
- Files: `TaskKeyTest.kt`
##### Task 1.1.1b: Value types (S)
- `TaskKind` (charset `require`), `TaskScope`, `TaskKey`, `TaskShape` (in `TaskSpec` only), validated `Schedule` (`of`/`coerce`), `NetworkPolicy`, `TaskConstraints`, `TaskConfig` (periodic; no `lateMultiple`), `TaskOverrides(schedule: Schedule?, constraints)` and `TaskDefaults(schedule: Schedule?, constraints)` (schedule null for one-shot kinds), `TaskSpec` (`jobKind: JobKind?`; KDoc states `enabled` is periodic-only and a one-shot ignores it, an explicit `runOnce` always runs), `ConstraintState`, `TaskRunContext`, `SchedulerCapabilities` (adds `schedulesWork`), `TaskScheduleInfo` (adds `minGapMinutes`, `maxGapMinutes`, `appliesAfterNextRun`), `OneShotConflict`, `RunSource`. `FNV-1a` helper `stableHex(String)` in `TaskKey.kt`; `TaskKeyEncoding` in its own file.
- Files: `TaskKey.kt`, `TaskKeyEncoding.kt`, `TaskModels.kt`
##### Task 1.1.1c: Port and result (S)
- `TaskRunResult`, `BackgroundTask`, `BackgroundTaskScheduler` with the shape above. No git types.
- Files: `TaskRunResult.kt`, `BackgroundTaskScheduler.kt`
##### Task 1.1.1d: Registry (XS)
- `TaskRegistry` (`register` idempotent for equal spec, throws for conflict; `taskFor`, `specFor`, `kinds()`).
- Files: `TaskRegistry.kt`
##### Task 1.1.1e: Register test classes (XS) (rev1)
- Add the file path to `business_common_tests` `srcs` in `kmp/src/commonTest/kotlin/BUILD.bazel` and `TaskKeyTest::class` to `AllBusinessTests` (Test Infrastructure Rule 2). Run `./gradlew :kmp:jvmTest --tests 'dev.stapler.stelekit.jobs.schedule.TaskKeyTest'` and `bazel test //kmp:business_tests` (the latter now proves it ran).
- Files: `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`

#### Story 1.1.2: JitterPolicy and `Schedule.clamp`
**As a** user of many devices, **I want** start times spread deterministically within my jitter window, **so that** devices do not hit the remote together.
**Acceptance Criteria**:
- Offsets stay in `[0, jitter]` and are deterministic per seed.
  - *Given* seed `JitterSeed("git-sync:graph-a")` and `jitterMinutes = 10`, *When* `offsetMinutes` is called twice, *Then* both results are equal integers in `0..10`.
- Interval/flex bounds are respected.
  - *Given* raw ints `(rawInterval = 10, rawJitter = 30)` passed through `Schedule.coerce(10, 30)` (the only way to build an out-of-range pair; `Schedule`'s constructor is private, C9) and capabilities `minInterval=15, minFlex=5`, *When* `Schedule.clamp(capabilities)` runs on the coerced schedule, *Then* `EffectiveSchedule(interval=15, jitter=10, flex=10, offset in 0..10)` (jitter limited to `interval - minFlex`), and `clamped = true` is reported.
  - *Given* the spike-recorded flex semantics (Story 0.1.1), *Then* the effective schedule also carries per-adapter gap bounds (`minGapMinutes`, `maxGapMinutes`) that `describe` exposes; this story's property tests assert the pure offset/flex bounds only, and the "runs N-M min apart" copy is derived from `describe`, not from a global `interval + offset` formula (pre-mortem #5).
- Zero jitter reproduces today's request.
  - *Given* `Schedule.of(60, 0)`, *When* `clamp` runs, *Then* flex is null and offset is 0.
- Different keys spread.
  - *Given* 200 generated `TaskKey`s and jitter 30, *When* offsets are bucketed by 5 min, *Then* every bucket in `0..30` is hit (distribution sanity; seeded).
- Default git-sync spread is the 10% preset (Phase 4 repair, B1).
  - *Given* `JitterPreset.ALittle.minutesFor(60)`, *When* evaluated, *Then* it is 6, the value `git-sync`'s `TaskSpec.defaultConfig` uses (Story 1.1.4, Task 3.1.1c).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/jobs/schedule/JitterPolicy.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/jobs/schedule/JitterPolicyPropertyTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel` (`business_common_tests`), `AllBusinessTests.kt`

##### Task 1.1.2a: Property tests (test first) (S)
- `kotest-property` `checkAll` in `runTest`: offset in `[0,jitter]`; non-negative for negative/oversized inputs (clamped); `interval >= minInterval`; `flex >= minFlex && flex < interval` when jitter > 0; `interval + offset` never below `minInterval`; same seed same output; `nextDelayMs(interval, jitter, Random(seed))` in `[intervalMs, intervalMs + jitterMs]`. Boundary examples: interval == 15, jitter == 0, jitter >= interval, negative inputs.
- Files: `JitterPolicyPropertyTest.kt`
##### Task 1.1.2b: `JitterPolicy`, `JitterSeed` and `JitterPreset` (S)
- Pure functions; no `Random.Default`; seed via FNV-1a. `JitterPreset { Off, ALittle, ALot }` with `minutesFor(interval)` (`Off=0`, `ALittle=max(1, round(10%))`, `ALot=max(2, round(25%))`) and `presetOf(schedule)` lives here in `jobs.schedule` (not in `git/ScheduledSyncPresentation.kt`) so `git-sync`'s `defaultConfig` (Task 3.1.1c) can use it before Story 4.1.1; the presentation layer (Task 4.1.1b) consumes it.
- Files: `JitterPolicy.kt`, `JitterPreset.kt`
##### Task 1.1.2c: Constants from the spike (XS)
- Set `minFlexMinutes`/`minIntervalMinutes` defaults from Spike 0.1.1 results, not memory.
- Files: `JitterPolicy.kt`, `TaskModels.kt`
##### Task 1.1.2d: Register the test (XS) (rev1)
- Add `JitterPolicyPropertyTest` to `business_common_tests` `srcs` and `AllBusinessTests` (Test Infrastructure Rule 2).
- Files: `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`

#### Story 1.1.3: NetworkGate
**As a** user who chose "Unmetered networks only", **I want** a skipped run to make no network call, **so that** mobile data is never spent.
**Acceptance Criteria**:
- Three-valued gate.
  - *Given* policy `Unmetered`, `online=true`, `isMetered=null`, *When* `NetworkGate.evaluate` runs, *Then* `Skip(UnknownNetwork)`; with `isMetered=true` -> `Skip(MeteredNetwork)`; with `isMetered=false` -> `Proceed`.
- `Any` ignores metered state but requires online.
  - *Given* policy `Any`, `online=false`, *When* evaluated, *Then* `Skip(Offline)`; with `online=true, isMetered=true` -> `Proceed`.
- Skips are not failures.
  - *Given* a `Skip(MeteredNetwork)`, *When* mapped to `TaskRunResult`, *Then* it is `Skipped("metered")` and never `Failed`.
- Explicit helper for callers with no `online` signal (rev1, LFS).
  - *Given* policy `Unmetered`, *When* `NetworkGate.evaluateMetered(policy, isMetered = true / false / null)` is called, *Then* it returns `Skip(MeteredNetwork)` / `Proceed` / `Skip(UnknownNetwork)`; with policy `Any` it always returns `Proceed`. `evaluateMetered` is defined as `evaluate(policy, online = true, isMetered)`, stated in its KDoc, so the LFS mapping is explicit rather than a coincidence.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/jobs/schedule/NetworkGate.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/jobs/schedule/NetworkGateTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel` (`business_common_tests`), `AllBusinessTests.kt`

##### Task 1.1.3a: Exhaustive truth-table test (test first) (S)
- Full `(policy x online x isMetered{true,false,null})` table asserted explicitly (12 rows) plus a property that `Proceed` implies `online`; the three `evaluateMetered` rows.
- Files: `NetworkGateTest.kt`
##### Task 1.1.3b: `NetworkGate` and `NetworkSkipReason` (XS)
- Sealed result, pure function, `evaluateMetered` helper.
- Files: `NetworkGate.kt`
##### Task 1.1.3c: Register the test (XS) (rev1)
- Add `NetworkGateTest` to `business_common_tests` `srcs` and `AllBusinessTests`.
- Files: `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`

#### Story 1.1.4: TaskSettingsStore, defaults and lazy legacy migration
**As a** user with sync already on, **I want** my choice carried over per graph on every platform, **so that** nothing silently turns on or off.
**Acceptance Criteria** (rev1: migration is lazy and platform-neutral; defaults are not a scope):
- Codec round-trips and fails closed.
  - *Given* `TaskEntry(enabled=true, overrides=TaskOverrides(Schedule.of(60,10), TaskConstraints(Unmetered)))`, *When* encoded and decoded, *Then* equal; *When* the stored string is `"garbage"`, *Then* decode returns the disabled default entry. The codec embeds no `kind` in the value (the key carries it), so the value cannot disagree with the key.
- Entry and defaults (resolves UX default-row collision).
  - *Given* `TaskDefaults(Schedule.of(120,0), TaskConstraints(Unmetered))` stored for `git-sync` and a graph entry `TaskEntry(enabled=true, overrides=null)`, *When* `read(key)` resolves, *Then* the `TaskConfig` is `enabled=true`, interval 120, `Unmetered` and `readEntry(key)` reports "Using default"; *Given* an entry with overrides, *Then* `readEntry` reports "Custom" and resolution uses the overrides. A graph whose scope is `""` is app-wide and unrelated to defaults: `readDefaults(kind)` reads `task_default_<kind>`, a different key family, so `TaskScope("")` and the default row never collide.
- Lazy, platform-neutral legacy migration (Arch-1 / B2).
  - *Given* `scheduled_sync_enabled=true`, graph `g1` has a remote with `poll_interval_minutes=30`, no entry and no marker, *When* `TaskSettingsStore(settings, seed).read(git-sync/g1)` runs on ANY platform (no startup hook, no composition), *Then* the returned config is `enabled=true, interval=30`, the entry and marker are persisted, and a second read does not call the seed again. *Given* graph `g2` has no remote, *Then* `enabled=false`; *Given* the legacy key is unset, *Then* `enabled=false`; *Given* the seed or `getBoolean` throws, *Then* `enabled=false` and NO marker is written (retry next read).
  - *Given* legacy flag true and a remote-bearing graph that was never composed, *Then* it migrates to `enabled=true` (documented semantics in ADR-003: the old global switch applied to every graph with a remote); this is asserted by a test named for that behaviour so a change is deliberate.
- Idempotent.
  - *Given* `g1` migrated, then the user sets enabled=false, *When* read runs again, *Then* the store still says `enabled=false`.
- Defaults (Phase 4 repair, B1).
  - *Given* a graph with no entry and no legacy source, *When* read, *Then* `enabled=false` and the kind's `TaskSpec.defaultConfig` schedule/constraints.
  - *Given* the `git-sync` spec, *When* a new graph with no entry is read, *Then* the resolved schedule is interval 60 and jitter `JitterPreset.ALittle.minutesFor(60)` = 6 (matches the `ux.md` default "spread: A little"); *Given* a graph migrated from the legacy flag, *Then* the seeded entry keeps jitter 0 with the legacy interval (behaviour-preserving: today's request had no jitter); both are asserted by named tests so the difference is deliberate.
- One-shot kinds resolve constraints without `enabled` (Phase 4 repair, B2/P1-1).
  - *Given* `model-download` (`OneShot`) with no store entry, *When* `read(TaskKey(model-download,""))` resolves, *Then* `.constraints.network == Unmetered` (spec default) and the caller ignores `.enabled`; *Given* `TaskEntry(enabled=false, overrides=TaskOverrides(schedule=null, constraints(Any)))`, *Then* the codec round-trips and `.constraints.network == Any`.
- Read failures are distinguishable (Phase 4 repair, pre-mortem #4).
  - *Given* a `Settings` that throws on read, *When* `readResult(key)` runs, *Then* it returns `ReadFailed` while `read(key)` still returns the disabled default (fail closed); a decode failure of a stored `"garbage"` string is `Ok(disabled default)`, not `ReadFailed`.
- Tombstone (rev2, A-C3: re-adding the same path).
  - *Given* `tombstone(key)`, *When* `read` runs with a seed that would return enabled, *Then* the config is disabled and the seed is never called (a removed graph never migrates back on).
  - *Given* a tombstoned key (`GraphId = sha256(path)`, so a re-added graph at the same path has the same `TaskKey`), *When* `write(key, TaskEntry(enabled=true, ...))` runs, *Then* the explicit write supersedes the tombstone and the next `read` reports `enabled=true`; *Given* the graph is re-added and the user has not touched the control, *Then* `read` is disabled (default for new graphs; it does not inherit the removed graph's old entry, and the seed is still not called).
- Cannot-read is not "no remote" (rev2, A-C1).
  - *Given* a `GitLegacyConfigSource` returning `Unavailable` (DB unopenable or not yet open) or throwing, legacy flag true, *When* `read` runs, *Then* the result is disabled for this read, NO marker and NO entry are written, and the next read calls the seed again; *Given* it returns `Found(hasRemote=false)`, *Then* the marker is written with `enabled=false`. A test names this distinction (permanently losing the user's sync is the failure mode).
- Seed only when needed (rev2, A-C4).
  - *Given* an existing entry (or a marker) for the key, *When* `read` runs, *Then* the seed is never invoked (a cold worker for a non-active graph does not open that graph's DB when an entry exists).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/jobs/schedule/{TaskConfigCodec.kt,TaskSettingsStore.kt,LegacyConfigSeed.kt}`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/{LegacyGitSyncMigration.kt,GitLegacyConfigSource.kt}`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/jobs/schedule/{TaskConfigCodecTest.kt,TaskSettingsStoreMigrationTest.kt}`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/LegacyGitSyncMigrationTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel` (`business_common_tests`), `AllBusinessTests.kt`

The migration needs the graph's remote/poll interval, which are git data; the port package cannot import `git`. `TaskSettingsStore` therefore takes the neutral `LegacyConfigSeed` fun interface; `LegacyGitSyncMigration` (package `dev.stapler.stelekit.git`) implements it over a `GitLegacyConfigSource` and `ScheduledSyncPreferences` (git depends on the port, never the reverse). `read`/`write` are `suspend` (the seed may open a graph DB). `GitLegacyConfigSource` (rev2, A-C1) is a commonMain interface in `git/` returning `LegacyGitConfigResult` (`Found(hasRemote, pollIntervalMinutes)` or `Unavailable`); a throw is treated as `Unavailable`. Implementations: Android (Task 3.1.2f) promotes the cold DB read that today is the `private fun loadGitConfigFromDb` at `androidMain/.../git/WorkManagerSyncScheduler.kt:210` (NOT on `GitSyncWorker`, not callable from commonMain) to an internal function and opens a non-active graph's DB lazily, one graph per call; Desktop and the active graph (Task 3.1.3f) read the open graph's `GitConfigRepository`. `read` calls the seed only when both the entry and the marker are absent.

##### Task 1.1.4a: Codec property + example tests (test first) (S)
- `Arb` generator over `TaskEntry`; decode(encode(x)) == x; random garbage strings decode to the disabled default; version prefix `v1`; key-family separation (`task_entry_`, `task_default_`, `task_migrated_`, `task_applied_`).
- Files: `TaskConfigCodecTest.kt`
##### Task 1.1.4b: Migration tests (test first) (S)
- The truth table above with an in-memory `Settings` and a throwing `Settings` wrapper plus a throwing seed; assert no marker on read failure; the never-composed-graph test; tombstone test; `write` supersedes a tombstone and a re-added graph starts disabled; `Unavailable`/throwing `GitLegacyConfigSource` writes no marker versus `Found(hasRemote=false)` writes one; an existing entry never calls the seed (counting fake). Run through `TaskSettingsStore.read`, not through a separate entry point.
- Files: `TaskSettingsStoreMigrationTest.kt`, `LegacyGitSyncMigrationTest.kt` (in `commonTest/.../git/`)
##### Task 1.1.4c: Codec and store (M)
- `TaskConfigCodec` (versioned, no kind in value; `TaskOverrides.schedule` and `TaskDefaults.schedule` nullable), `TaskSettingsStore.read(key)`/`readResult(key): TaskReadResult`/`readEntry(key)`/`write(key, entry)`/`readDefaults(kind)`/`writeDefaults(kind, defaults)`/`tombstone(key)`; every read wrapped fail-closed; lazy seed call with marker only when entry and marker are both absent; `write` overwrites a tombstone.
- Files: `TaskConfigCodec.kt`, `TaskSettingsStore.kt`, `LegacyConfigSeed.kt`
##### Task 1.1.4d: `LegacyGitSyncMigration` (S)
- Implements `LegacyConfigSeed` for `git-sync`: reads `ScheduledSyncPreferences` key and `GitLegacyConfigSource` (remote?, `poll_interval_minutes`); returns null for other kinds; on `Unavailable`/throw the seed throws a typed `SeedUnavailable` so the store writes no marker.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/LegacyGitSyncMigration.kt`
##### Task 1.1.4e: Register tests (XS) (rev1)
- Add the three test files to `business_common_tests` `srcs` and the classes to `AllBusinessTests`.
- Files: `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`
##### Task 1.1.4f: `GitLegacyConfigSource` interface (S) (rev2, A-C1)
- Define `GitLegacyConfigSource` and `LegacyGitConfigResult { Found(hasRemote, pollIntervalMinutes) | Unavailable }` in commonMain `git/`, with KDoc stating the contract: "cannot read" is `Unavailable` or a throw, never `Found(hasRemote=false)`. Add a commonTest fake used by Task 1.1.4b. The Android and Desktop implementations are Tasks 3.1.2f and 3.1.3f. Update ADR-003's wording (the cold read is not on `GitSyncWorker`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitLegacyConfigSource.kt`, `ADR-003-...md`

#### Story 1.1.5: Dependency-direction test and Bazel wiring
**As a** maintainer, **I want** the neutrality rule enforced, **so that** the port cannot regrow git types.
**Acceptance Criteria**:
- Import scan fails on a planted violation.
  - *Given* a test fixture string `import dev.stapler.stelekit.git.GitSyncService` in a `jobs/schedule/` file, *When* the scanner runs on it, *Then* it reports a violation; the real source tree reports none.
- The scanner actually sees the new sources under Bazel (rev1).
  - *Given* `jobs/schedule/*.kt` in `commonMain` and `androidMain`, *When* `//kmp:business_tests` runs, *Then* the scanner reports it scanned at least one file per source set (a non-zero count assertion), proving the bundle includes them.
**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/jobs/schedule/JobsScheduleImportDirectionTest.kt`, `kmp/src/businessTest/kotlin/dev/stapler/stelekit/AllBusinessTests.kt`

##### Task 1.1.5a: Scanner test with planted violation (test first) (S)
- Scans bundled sources for `import dev.stapler.stelekit.(git|ui|db|merge)`; the planted-violation fixture is a string, not a file under `jobs/schedule/`. Also asserts non-zero scanned-file count.
- Files: `JobsScheduleImportDirectionTest.kt`
##### Task 1.1.5b: Bundle check and suite registration (XS) (rev1)
- VERIFIED: `commonMain/kotlin/BUILD.bazel` `wiring_audit_sources` is `glob(["**/*.kt"])` and `androidMain/kotlin/BUILD.bazel` has its own `wiring_audit_sources`, both already listed in `wiring_kmp_sources_as_resources`; `businessTest/kotlin/BUILD.bazel` compiles `glob(["**/*.kt"])`. So no BUILD edit is needed; the task is to register `JobsScheduleImportDirectionTest` in `AllBusinessTests` (`AllBusinessTestsCompletenessTest` would fail otherwise) and run `bazel test //kmp:business_tests`.
- Files: `AllBusinessTests.kt`

#### Story 1.1.6: Fake scheduler, applied-config ledger and contract suite
**As a** maintainer, **I want** one suite that every adapter must pass, **so that** the three adapters cannot drift.
**Acceptance Criteria** (rev1: capability-parameterized; `describe` is on the port; resume is ledger-based):
- Contract invariants, parameterized by `capabilities` (adversarial C2). The suite reads `capabilities.minIntervalMinutes`, `supportsUnmetered` etc. from the adapter under test and asserts relative to them; invariants that need a capability start with an early `return` when the capability is absent (rev2, R-C3: `assumeTrue` is `org.junit.Assume`, not available in `commonTest`/wasmJs; Test Infrastructure Rule 5).
  - *Given* any adapter, *When* `apply(TaskKey(git-sync,"g1"), TaskConfig(enabled=true, Schedule.of(60,0), constraints))` is called twice, *Then* `describe(key)` is identical after both calls (idempotent) and reports interval `max(60, capabilities.minIntervalMinutes)`.
  - *Given* an applied key, *When* `apply(..., enabled=false)`, *Then* `describe(key)` is null.
  - *Given* keys `(git-sync,"g1")` and `(git-sync,"g2")`, *When* both applied and `cancel(g1)`, *Then* `g2` remains.
  - *Given* `Schedule.of(capabilities.minIntervalMinutes - 1, 0)` (skipped when `minIntervalMinutes == 1`), *When* applied, *Then* `describe` reports effective interval `capabilities.minIntervalMinutes` and `clamped=true`; with an interval at the minimum, `clamped=false`.
  - *Given* applied `(git-sync,"g1")` at interval 45, *When* `pause("g1")` then `resume("g1")`, *Then* the restored interval is 45 (not the platform floor).
  - *Given* a key applied through the adapter with NO `TaskSettingsStore` entry at all (the `WorkManagerSyncSchedulerPauseTest` scenario: `schedule(15)`, `pause`, `resume`), *When* `pause` then `resume`, *Then* the periodic work exists again: `pause` snapshots what it cancels into the `AppliedConfigLedger` and `resume` restores from that snapshot (rev2, N1).
  - *Given* work that exists in the platform with NO ledger entry, NO store entry and NO legacy flag (the `WorkManagerSyncSchedulerUniqueWorkTest` scenario and any pre-upgrade unmigrated job: enqueued directly, then `pause`, then `resume`), *When* `pause` runs, *Then* it snapshots the live work's interval and constraints first, and `resume` re-enqueues the same interval and constraints. The contract suite exposes this through an adapter hook `seedExternalPeriodic(key, config)` (fake: inserts into its table without touching the ledger).
  - *Given* a `Settings` that is not live (writes silently no-op, the `PlatformSettings`-without-`SteleKitContext.init` failure mode), *When* `pause` then `resume`, *Then* the work is still restored within the same process (the in-memory snapshot copy). The ledger takes an explicit `Settings`; the contract fixtures pass a guaranteed-live in-memory one, and one extra case passes a no-op `Settings`.
  - *Given* applied `(git-sync,"g1")` at 45 min and a `pause("g1")`, *When* the user changes the config via `apply(g1, interval 90)` while paused (a settings change during a long clone or graph move; Phase 4 repair, pre-mortem #3), *Then* nothing is enqueued during the pause, and `resume("g1")` restores interval 90 (the snapshot's config was updated by `apply`); *Given* `apply(g1, enabled=false)` while paused, *Then* the snapshot is cleared and `resume` enqueues nothing.
  - *Given* a `Settings` that throws on the snapshot write during `pause` (Phase 4 repair, pre-mortem #3), *When* `pause` runs, *Then* it does not throw, the underlying work IS still cancelled (never block the clone), and `resume` restores from the process-static in-memory copy written first; with the static copy absent too it falls back to the ledger's last applied config.
  - *Given* a snapshot exists but the store holds an explicit entry or tombstone with `enabled=false` (a disable that bypassed `apply`), *When* `resume` runs, *Then* nothing is enqueued and the snapshot is discarded (R-C7: disabled work is never resurrected). *Given* the store has an enabled entry and a snapshot, *Then* the snapshot wins (it holds what was actually running). *Given* no snapshot and no store entry (lazy seed returns null or `Unavailable`), *Then* `resume` enqueues nothing and does not throw (fail closed).
  - *Given* `runOnce(TaskKey(model-download,""), constraints(Unmetered))` on an adapter with `capabilities.supportsUnmetered`, *When* inspected via `describe`, *Then* a one-shot is recorded with network `Unmetered` and no periodic entry; on an adapter without it, `describe` reports the policy was downgraded with a reason.
  - *Given* a pending one-shot and `runOnce(key, constraints, OneShotConflict.Replace)`, *When* inspected, *Then* the request is replaced (new id) and with `Keep` the first request is retained.
- Fake supports `fireNow(key)`.
  - *Given* a registered task returning `Success`, *When* `fireNow` runs with `isMetered=true` and policy `Unmetered`, *Then* the task body is not invoked and the result is `Skipped("metered")`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/jobs/schedule/AppliedConfigLedger.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/jobs/schedule/{BackgroundTaskSchedulerContract.kt,FakeBackgroundTaskScheduler.kt,FakeBackgroundTaskSchedulerContractTest.kt}`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`

##### Task 1.1.6a: Abstract contract suite (test first) (S)
- `abstract class BackgroundTaskSchedulerContract` (name deliberately not ending in `Test`) with `createScheduler()` and `capabilities` from the adapter, `describe` from the port; invariants above; no real-clock delay (`TestHygieneAuditTest`).
- Files: `BackgroundTaskSchedulerContract.kt`
##### Task 1.1.6b: `FakeBackgroundTaskScheduler` and `AppliedConfigLedger` (S)
- Fake records `apply/cancel/pause/resume/runOnce` by key; shares the same clamp and `NetworkGate` as production; `fireNow(key, state)`; `seedExternalPeriodic`. `AppliedConfigLedger` (explicit `Settings` constructor parameter; key family `task_applied_`; `PausedSnapshot` plus process-static in-memory copy) is written by every adapter's `apply`, snapshotted by `pause` from the live work, and cleared on `cancel`/disable.
- Files: `FakeBackgroundTaskScheduler.kt`, `AppliedConfigLedger.kt`
##### Task 1.1.6c: Fixture targets and Bazel exposure (M) (rev1)
- Add `scheduler_contract_fixtures_jvm` (`kt_jvm_library`; srcs `BackgroundTaskSchedulerContract.kt`, `FakeBackgroundTaskScheduler.kt`; no concrete `*Test`) and `scheduler_contract_fixtures_android` (`kt_android_library`, `testonly`, `kotlinc_opts = "//:kmp_android_kotlinc_opts"`, visibility `//kmp/src/androidUnitTest/kotlin:__pkg__`, deps like `fake_file_system_android` incl. `android_main`) to `kmp/src/commonTest/kotlin/BUILD.bazel`; add `scheduler_contract_fixtures_jvm` to `//kmp/src/jvmTest/kotlin:jvm_tests` deps and `scheduler_contract_fixtures_android` to `android_unit_test_lib` deps. Do NOT add these files to `common_test_fixtures`. List the three source files and `FakeBackgroundTaskSchedulerContractTest.kt` in `business_common_tests` `srcs` (for Bazel business coverage) and add `FakeBackgroundTaskSchedulerContractTest::class` to `AllBusinessTests`. Verify with `bazel build //kmp/src/jvmTest/kotlin:jvm_tests //kmp/src/androidUnitTest/kotlin:android_unit_test_lib --config=android` (build only: proves visibility) and `bazel test //kmp:business_tests`.
- Files: `FakeBackgroundTaskSchedulerContractTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `kmp/src/jvmTest/kotlin/BUILD.bazel` (dep only), `kmp/src/androidUnitTest/kotlin/BUILD.bazel` (dep only), `AllBusinessTests.kt`

### Epic 1.2: Observability core
**Goal**: Pure lateness, restriction reason and a persisted run log, with no UI.

#### Story 1.2.1: TaskRunLog
**As a** user without `adb logcat`, **I want** every scheduled run and skip recorded, **so that** I can see what happened overnight.
**Acceptance Criteria**:
- Records are capped and ordered.
  - *Given* `TaskRunLog` with cap 20 and 25 appended records for `(git-sync,"g1")`, *When* read, *Then* 20 records newest first, oldest 5 dropped. Run-log keys use `TaskKeyEncoding.settingsKey("task_run_log", key)` (injective).
- Skips and failures are both logged with constraint state.
  - *Given* a record `Skipped("metered")` with `ConstraintState(online=true, isMetered=true)`, *When* appended, *Then* the persisted line and the `Logger` line both contain `result=Skipped:metered` and `metered=true`.
- Last-success time is separate from last attempt.
  - *Given* attempts Skipped at t=100, Failed at t=200, Success at t=50, *When* `lastSuccessAtMs` is read, *Then* 50.
- Neutral `LastSuccessSource` (rev1, Arch-3).
  - *Given* the interface `LastSuccessSource { suspend fun lastSuccessAtMs(key): Long? }` and a default `TaskRunLogLastSuccessSource(runLog)`, *When* a consumer supplies its own implementation, *Then* `ScheduleLateness` callers use it unchanged; the neutral package has no git type. Git's implementation is in Story 3.1.1.
- A foreground-only success is visible (asserted in Story 3.1.1/4.1.2): "Sync now" writes `SyncStalenessStore` only; `PeriodicSyncDriver` ticks write `SyncStalenessStore` AND append to `TaskRunLog` under `RunSource.Foreground` (Phase 4 repair, B4; Task 3.1.3g).
- Writer-safe sources (Phase 4 repair, B4).
  - *Given* `RunSource.Background` and `RunSource.Foreground` records appended for `(git-sync,"g1")` by two writers interleaved, *When* `recent(key)` is read, *Then* every record of both sources is present (no lost update: the two sources use distinct settings keys, `task_run_log_...` vs `task_run_log_fg_...`, so no read-modify-write is shared), the merged list is newest first and capped at 20 total, and `lastSuccessAtMs` is the max across both; `settingsKey` for the two sources never collides for any generated `(kind, scope)`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/jobs/schedule/{TaskRunLog.kt,SettingsTaskRunLog.kt,LastSuccessSource.kt}`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/jobs/schedule/TaskRunLogTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel` (`business_common_tests`), `AllBusinessTests.kt`

##### Task 1.2.1a: `TaskRunLogTest` (test first) (S)
- Cap, ordering, fail-closed read (throwing `Settings` returns empty), last-success vs last-attempt, recording a Logger line via a test `LogSink` if one exists.
- Files: `TaskRunLogTest.kt`
##### Task 1.2.1b: `TaskRunLog` interface, record codec (S)
- `TaskRunRecord`, `TaskRunLog` (`append`, `recent`, `lastSuccessAtMs`).
- Files: `TaskRunLog.kt`
##### Task 1.2.1c: Settings-backed implementation + Logger line (S)
- One string per key (injective `TaskKeyEncoding`), same pattern as `SyncStalenessStore`; log via `Logger`, not `android.util.Log`. KDoc: single writer per (key, source), so read-modify-write is safe (Task 1.2.1e adds the foreground source).
- Files: `SettingsTaskRunLog.kt`, `LastSuccessSource.kt`
##### Task 1.2.1d: Register the test (XS) (rev1)
- Add `TaskRunLogTest` to `business_common_tests` `srcs` and `AllBusinessTests`.
- Files: `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`
##### Task 1.2.1e: Source-keyed run log (writer-safe foreground path) (S) (Phase 4 repair, B4)
- Add `RunSource { Background, Foreground }` to `TaskRunRecord`; `SettingsTaskRunLog.append(record)` writes to `TaskKeyEncoding.settingsKey("task_run_log", key)` for Background and `settingsKey("task_run_log_fg", key)` for Foreground (injective across both families); `recent`/`lastSuccessAtMs` read both and merge. KDoc: one writer per (key, source). Extend `TaskRunLogTest` with the interleaved-writers case and the key-collision property (test first, same file as Task 1.2.1a).
- Files: `TaskRunLog.kt`, `SettingsTaskRunLog.kt`, `TaskKeyEncoding.kt`, `TaskRunLogTest.kt`

#### Story 1.2.2: ScheduleLateness and RestrictionReason
**As a** user, **I want** to know the schedule is late without the word "behind" (which means commits behind), **so that** I am not misled.
**Acceptance Criteria**:
- Late is measured from last success against the effective interval.
  - *Given* `lastSuccessAtMs = now - 150 min`, `EffectiveSchedule(interval=60)` and the constant `ScheduleLateness.LATE_MULTIPLE = 2` (not a setting, C2), *When* `ScheduleLateness.of(...)` runs, *Then* `Late(sinceMs=150 min)`; with `now - 90 min` -> `OnTime`.
- A foreground-only success is not Late (rev1, Arch-3).
  - *Given* a `LastSuccessSource` whose newest success is 10 min ago (supplied by a fake standing in for the foreground path) while `TaskRunLog` has only a Skipped record 5 h ago, *When* the lateness helper `lateness(key, schedule, lastSuccessSource, now)` runs, *Then* `OnTime`. (Git's real source is asserted in Story 3.1.1.)
- Policy skips do not count as late-because-of-Android.
  - *Given* the last 3 runs `Skipped("vault-locked")` and last success 5 h ago, *When* the attribution is computed, *Then* `Late` carries `cause = DeliberateSkip("vault-locked")` and the restricted hint is not implied.
- Clock sanity.
  - *Given* `lastSuccessAtMs` is in the future (clock moved back), *When* evaluated, *Then* `OnTime` (no negative age).
- Disabled or one-shot is not applicable.
  - *Given* `enabled=false`, *When* evaluated, *Then* `NotApplicable`.
- Restriction reason.
  - *Given* a probe fake returning `StandbyRestricted`, *When* the helper `isRestricted()` is called, *Then* `true`; for `None` -> `false`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/jobs/schedule/{ScheduleLateness.kt,RestrictionReason.kt}`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/BackgroundSyncScheduler.kt` (typealias), `kmp/src/commonTest/kotlin/dev/stapler/stelekit/jobs/schedule/ScheduleLatenessTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel` (`business_common_tests`), `AllBusinessTests.kt`

##### Task 1.2.2a: `ScheduleLatenessTest` (test first) (S)
- Examples above plus a property: result is monotone in `now` for fixed inputs; never negative age; foreground-only-success case.
- Files: `ScheduleLatenessTest.kt`
##### Task 1.2.2b: `ScheduleLateness` and `lateness(...)` helper (S)
- Pure `ScheduleLateness.of`; a thin suspend helper `lateness(key, schedule, source: LastSuccessSource, now)` that is the only thing UI/presentation calls (so no caller can read `TaskRunLog` directly and get a false Late). The multiple is the constant `LATE_MULTIPLE = 2` (matches `STALE_FACTOR`); it is not configurable in v1 (the "configurable multiple" idea is dropped, C2).
- Files: `ScheduleLateness.kt`
##### Task 1.2.2d: Register the test (XS) (rev1)
- Add `ScheduleLatenessTest` to `business_common_tests` `srcs` and `AllBusinessTests`.
- Files: `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`
##### Task 1.2.2c: `RestrictionReason` + moved probe (S)
- Move `BackgroundRestrictionProbe` to `jobs.schedule` returning `RestrictionReason`; keep `fun isRestricted()` derived; leave `typealias BackgroundRestrictionProbe` in `git` so `StelekitAppDependencies.kt:98` and `GitSyncSchedulingEntryPointTest` compile. Also port `StelekitAppPlatformIntegrations`'s probe field type through the alias with no host edits.
- Files: `RestrictionReason.kt`, `git/BackgroundSyncScheduler.kt`

---

## Phase 2: Adapters

### Epic 2.1: In-process and no-op adapters
**Goal**: Desktop runs tasks in-process under the same settings; iOS/Web compile with a honest no-op.

#### Story 2.1.1: InProcessTaskScheduler
**As a** Desktop user, **I want** the same schedule settings honoured while the app is open, **so that** behaviour matches Android.
**Acceptance Criteria**:
- Ticks at interval plus jitter on virtual time.
  - *Given* `InProcessTaskScheduler` with a `TestScope`, interval 15 min, jitter 5 min, `Random(42)`, *When* virtual time advances 3 intervals, *Then* the task ran 3 times, each gap in `[15, 20]` min.
- Skips never invoke the task.
  - *Given* policy `Unmetered` and `isMetered=null` (Desktop), *When* a tick fires, *Then* the task body is not called, a `Skipped("unknown-network")` record is logged. (The UI prevents choosing this on Desktop; this is defence in depth.)
- A throwing task does not kill the loop and `Throwable` is caught.
  - *Given* a task that throws `OutOfMemoryError` once then returns `Success`, *When* two ticks fire, *Then* the first is logged `Failed`, the second runs; `CancellationException` is rethrown.
- Owns its scope.
  - *Given* the adapter, *When* constructed, *Then* it creates `CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler)` itself and accepts no caller scope; `stop()` cancels it.
- Passes the contract suite (capability-parameterized: `minIntervalMinutes=1`, `supportsUnmetered=false`, so the clamp and unmetered-one-shot invariants adapt or return early on the missing capability).
  - *Given* the contract suite from `scheduler_contract_fixtures_jvm`, *When* run against `InProcessTaskScheduler` with a virtual clock, *Then* all applicable invariants pass, including pause/resume with no store entry (ledger-based).
- It proves a non-git consumer (rev1, ADR-001 default): a `FakeTask` of kind `model-download`-shaped periodic config ticks through the adapter under the same settings and run log. Git on Desktop is NOT moved onto this adapter in this release (see Story 3.1.3).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/jobs/schedule/InProcessTaskScheduler.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/jobs/schedule/InProcessTaskSchedulerContractTest.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/AllJvmTests.kt`

##### Task 2.1.1a: Contract subclass + tick tests (test first) (S)
- `// jvmTest-reason: desktop-only` header not needed if no Compose; uses `runTest` virtual time and `TestScope`, injected `TimeSource`, no `Thread.sleep`/real `delay`.
- Files: `InProcessTaskSchedulerContractTest.kt`, `AllJvmTests.kt` (explicit list: add the class; the jvm BUILD is a glob and the contract dependency was added in Task 1.1.6c)
##### Task 2.1.1b: Scheduler loop (M)
- Per-key `Job`, `Mutex` tick lock, busy skip, next delay from `JitterPolicy.nextDelayMs`; reports via `JobTracker.begin(...)` so runs appear in the jobs list; writes `TaskRunLog` and the `Logger` line; implements `describe(key)` (effective interval, `clamped`, `nextRunAtMs`) and exposes `status: StateFlow<Map<TaskKey, TaskScheduleStatus>>`. Wasm note: the class compiles on wasm but is never constructed there (dead code, acceptable).
- Files: `InProcessTaskScheduler.kt`
##### Task 2.1.1c: Capabilities (XS)
- `minIntervalMinutes=1`, `supportsUnmetered=false`, `supportsCharging=false`, `supportsJitter=true`, `runsInBackgroundWhenClosed=false`, `schedulesWork=true`. `describe` reports `minGapMinutes = interval`, `maxGapMinutes = interval + jitter`, `appliesAfterNextRun = false` (a changed config restarts the loop), which is what makes the Desktop copy "Runs 60-66 min apart" correct for this adapter only.
- Files: `InProcessTaskScheduler.kt`
##### Task 2.1.1d: `pause`/`resume`/`cancelAll` semantics (S)
- `pause` snapshots the running loop's config into the ledger before stopping it; `resume` restores from the snapshot, using `TaskSettingsStore` (lazy legacy seed) only when there is none, and honours an explicit disabled entry/tombstone over a snapshot; covered by the contract.
- Files: `InProcessTaskScheduler.kt`

#### Story 2.1.2: NoopTaskScheduler and explicit per-host wiring
**As a** maintainer, **I want** iOS and Web to compile with an honest adapter, and every host to name its scheduler, **so that** a host that forgets to wire one fails to compile instead of silently doing nothing.
**Acceptance Criteria** (rev1, architecture + adversarial C10: no default):
- All capabilities false and nothing persisted.
  - *Given* `NoopTaskScheduler`, *When* `apply(key, TaskConfig(enabled=true, ...))` is called, *Then* `capabilities.runsInBackgroundWhenClosed=false`, `capabilities.schedulesWork=false` (the Settings UI hides the Background tasks section on it, Story 4.1.1), no exception, `describe(key)` is null.
- No default; every host names its value.
  - *Given* `StelekitAppPlatformIntegrations` (where `backgroundSyncSchedulerFactory` lives at `ui/StelekitAppDependencies.kt:93`), *When* the new `backgroundTaskScheduler` and `taskRegistry` fields are added, *Then* they have NO default value, and each host passes its adapter explicitly: Android `MainActivity.kt` (`WorkManagerTaskScheduler`), `jvmMain/desktop/Main.kt` and `jvmMain/desktop/ui/App.kt` (`InProcessTaskScheduler` in `Main.kt`, explicit `NoopTaskScheduler()` in `App.kt` where the factory is `None`), `wasmJsMain/browser/Main.kt` and the iOS root (`NoopTaskScheduler()`), `androidUnitTest/wiring/WiredAppFixture.kt` and `jvmTest/wiring/DesktopWiredAppFixture.kt` (fake or real adapter per test). `WiringCompletenessTest` sees `NoopTaskScheduler(` in at least one non-test root because iOS/wasm construct it there.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/jobs/schedule/NoopTaskScheduler.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitAppDependencies.kt` (the `StelekitAppPlatformIntegrations` class), `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`, iOS root (locate with `sg --pattern 'StelekitAppPlatformIntegrations(' --lang kotlin`), `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/{Main.kt,ui/App.kt}`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/wiring/WiredAppFixture.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/wiring/DesktopWiredAppFixture.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/jobs/schedule/NoopTaskSchedulerTest.kt`

**Serialization rule (rev2, A-C5; Phase 4 repair adds 3.1.3g)**: Tasks 2.1.2b, 2.2.3d, 3.1.1c, 3.1.2c, 3.1.3c, 3.1.3e, 3.1.3g, 3.1.4c and 3.2.1d all touch `StelekitAppDependencies.kt`, `MainActivity.kt`/`Main.kt`, `SteleKitApplication.kt` and/or the two wired-app fixtures (which 2.1.2b already edits). They run strictly in this order, one at a time, never in parallel: 2.1.2b -> 2.2.3d -> 3.1.1c -> 3.1.2c -> 3.1.3c -> 3.1.3e -> 3.1.3g -> 3.1.4c -> 3.2.1d.

##### Task 2.1.2a: Noop test (test first) (XS)
- Tiny commonTest asserting capabilities and that `apply` has no effect; add to `business_common_tests` `srcs` and `AllBusinessTests`.
- Files: `NoopTaskSchedulerTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`
##### Task 2.1.2b: `NoopTaskScheduler` + explicit host fields (M)
- Add `backgroundTaskScheduler`/`taskRegistry` (no default) to `StelekitAppPlatformIntegrations`; update every host listed above in one task so the build never breaks between hosts; keep `backgroundSyncSchedulerFactory` during the shim release.
- Files: `NoopTaskScheduler.kt`, `StelekitAppDependencies.kt`, the hosts above

### Epic 2.2: Android WorkManager adapter
**Goal**: One generic scheduler and worker honouring constraints, jitter, names, tags, pause/resume and orphan reconcile.

#### Story 2.2.1: WorkManagerTaskScheduler (apply, runOnce, cancel, mapping)
**As an** Android user, **I want** my interval, jitter and network choice to reach WorkManager, **so that** the chosen values actually apply.
**Acceptance Criteria**:
- Constraints reach the request (read back, not "no error").
  - *Given* `TaskKey(git-sync,"g1")` with `TaskConfig(enabled=true, Schedule.of(60,10), constraints(Unmetered))`, *When* `apply` runs under Robolectric `WorkManagerTestInitHelper`, *Then* `getWorkInfosForUniqueWork("stelekit_git_sync_g1")` returns one `ENQUEUED` info with `constraints.requiredNetworkType == UNMETERED`, interval 60 min, flex 10 min (or the spike-recorded readable fields).
- Name and type are stable for git.
  - *Given* an existing periodic `stelekit_git_sync_g1`, *When* `apply` is called with a new interval, *Then* the work id is unchanged (`UPDATE`) and the name is `stelekit_git_sync_g1`.
- `enabled=false` cancels.
  - *Given* applied work, *When* `apply(enabled=false)`, *Then* no work info remains for the name.
- One-shot and conflict semantics (rev1).
  - *Given* `runOnce(TaskKey(model-download,""), constraints(Unmetered))`, *When* inspected, *Then* a `OneTimeWorkRequest` named `DepthModelDownloader.UNIQUE_WORK_NAME` with `KEEP` and `UNMETERED` exists; *Given* that pending request and `runOnce(key, constraints(Any), OneShotConflict.Replace)`, *Then* `ExistingWorkPolicy.REPLACE` is used, the request id changes and the constraint is `CONNECTED`; with the default `Keep` the original id and constraint remain.
- Tags and jobs-list noise (rev1, adversarial C8).
  - *Given* a periodic request, *When* its tags are read, *Then* they include `stelekit-sched`, `stelekit-sched-scope:g1` and the kind tag, and do NOT include `stelekit-job` (`JobTags.ANY`), so `WorkManagerJobRegistry` never lists a permanent `Queued` row for an enabled graph. *Given* a one-shot request, *Then* tags include `stelekit-job`, `stelekit-job-kind:ModelDownload` and the title tag so it appears in the jobs list. A kind whose `TaskSpec.jobKind` is null gets no kind tag.
- Worker class and network-policy input (Phase 4 repair, P1-2).
  - *Given* `apply(TaskKey(git-sync,"g1"), TaskConfig(enabled=true, ..., constraints(Unmetered)))`, *When* the `WorkInfo`/`WorkSpec` is read back, *Then* `workerClassName == GitSyncWorker::class.java.name` (definitive: `git-sync` is never enqueued as `TaskWorker`) and the input data holds `graph_id=g1`, `interval_minutes=<effective>` and `network_policy=unmetered`; re-applying with `Any` leaves `network_policy=any` (per the Spike 0.1.1 read-back of `UPDATE` input data; if `UPDATE` keeps stale input, `apply` uses the spike-recorded fallback and ADR-002 says so).
  - *Given* `runOnce(TaskKey(model-download,""), constraints(Any), Replace)`, *Then* the one-time request's input data holds `network_policy=any`, so the worker's gate honours "Download now anyway" and never re-reads the stored default.
  - *Given* a `TaskWorker`-run periodic kind (a contract-suite/non-git kind), *Then* the same `KEY_NETWORK_POLICY` input is set.
- `describe` carries spread semantics (Phase 4 repair, pre-mortem #5).
  - *Given* `apply` with `Schedule.of(60,6)`, *When* `describe(key)` is read, *Then* `minGapMinutes`, `maxGapMinutes` and `appliesAfterNextRun` equal the values Spike 0.1.1 recorded for flex periodic work (not `interval..interval+jitter`).
- Ledger.
  - *Given* any successful `apply(enabled=true)`, *Then* the `AppliedConfigLedger` holds that config; `apply(enabled=false)` and `cancel` clear it.
- Contract.
  - *Given* the contract suite from `scheduler_contract_fixtures_android`, *When* run against this adapter, *Then* all applicable invariants pass with capabilities `minIntervalMinutes=15`.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/jobs/schedule/WorkManagerTaskScheduler.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/jobs/schedule/WorkManagerTaskSchedulerContractTest.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/jobs/schedule/WorkManagerTaskSchedulerConstraintsTest.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/AllAndroidUnitTests.kt`

##### Task 2.2.1a: Contract subclass and constraint read-back tests (test first) (M)
- Reuse the init guard from `WorkManagerSyncSchedulerPauseTest.kt:36-49`; assert the read-back values above; the charging and battery-not-low constraint mapping too. Evidence: `./gradlew :kmp:testDebugUnitTest --tests 'dev.stapler.stelekit.jobs.schedule.WorkManagerTaskScheduler*'`.
- Files: the two androidUnitTest classes, `AllAndroidUnitTests.kt` (register both for Bazel coverage; `androidUnitTest/kotlin/BUILD.bazel` is a glob)
##### Task 2.2.1b: Request building and mapping (M)
- `Any -> CONNECTED`, `Unmetered -> UNMETERED`; `requiresCharging`, `requiresBatteryNotLow`; flex/offset from `JitterPolicy` and `Schedule.clamp(capabilities)`; `UPDATE` for periodic; use the shared `workManagerOrNull()` extracted by Task 2.2.1f (it is `private` in `WorkManagerJobRegistry.kt:58` today) rather than bare `getInstance`; never `setForeground`/`setExpedited` for periodic. Worker class: `GitSyncWorker` for `git-sync` (input `graph_id`, `interval_minutes`), `TaskWorker` for every other kind; every enqueue (periodic and one-shot) also sets `KEY_NETWORK_POLICY` from `config.constraints.network`; `describe` gap fields come from Spike 0.1.1.
- Files: `WorkManagerTaskScheduler.kt`
##### Task 2.2.1c: Names and tags (S)
- `TaskKey.workName()` mapping from ADR-005, legacy-name table (`git-sync`, `model-download`). Tags: periodic = `stelekit-sched`, `stelekit-sched-scope:<scope>`, kind tag (if `jobKind != null`); one-shot additionally `JobTags.ANY` and title. Scope tag value is the raw scope (read back by the reconciler).
- Files: `WorkManagerTaskScheduler.kt`, `kmp/src/androidMain/kotlin/dev/stapler/stelekit/jobs/schedule/WorkNames.kt`
##### Task 2.2.1d: `runOnce` (S)
- `OneTimeWorkRequest`; `OneShotConflict.Keep` -> `ExistingWorkPolicy.KEEP`, `Replace` -> `ExistingWorkPolicy.REPLACE` ("Download now anyway"); backoff criteria only when `TaskSpec.oneShotRetryCap > 0`; unmetered request on an adapter without support records a downgrade reason in `describe`.
- Files: `WorkManagerTaskScheduler.kt`
##### Task 2.2.1e: Jobs-list noise check (S)
- First read the CURRENT `WorkManagerSyncScheduler` request tags and `WorkManagerJobRegistryTest` to see what a periodic git row does today; extend `WorkManagerJobRegistryTest` asserting an enabled graph's periodic request (no `stelekit-job` tag) yields no job row and a one-shot with `stelekit-job` does. If today's git periodic work is already listed and that is intended, record the parity decision in the plan instead of removing it. (`WorkManagerJobRegistryTest` is also Gradle-only today; register it in `AllAndroidUnitTests` only if it runs under Bazel.)
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/jobs/WorkManagerJobRegistryTest.kt`, `WorkManagerJobRegistry.kt`
##### Task 2.2.1f: Extract `workManagerOrNull()` (XS) (rev2, R-C10)
- Move the `private fun workManagerOrNull()` at `WorkManagerJobRegistry.kt:58` to an `internal` top-level function in `kmp/src/androidMain/kotlin/dev/stapler/stelekit/jobs/WorkManagerAccess.kt` and call it from `WorkManagerJobRegistry` and the new adapter. `DepthModelDownloader.kt:128` has its own private copy: switch it to the shared one in Task 3.2.1c (not here, to avoid touching it before its story). Do this first in Story 2.2.1 (before Task 2.2.1b); `WorkManagerJobRegistryTest` stays green.
- Files: `WorkManagerAccess.kt`, `WorkManagerJobRegistry.kt`

#### Story 2.2.2: Generic TaskWorker
**As a** maintainer, **I want** one worker that gates, runs, records and never silently crashes, **so that** every task gets the same safety.
**Acceptance Criteria** (rev1: one-shot skip, cold network source, KnownScopes guard, adapter hooks):
- Network gate runs before the task, with a cold-process signal.
  - *Given* a registered periodic task and policy `Unmetered`, `NetworkSnapshotProvider` returning `isMetered=true`, *When* `TaskWorker.doWork` runs, *Then* the task body is not called, the run log has `Skipped("metered")`, and the result is `Result.success()` (the next period retries).
  - *Given* the provider throws (or `isMetered == null`) and policy `Unmetered`, *Then* the record is `Skipped("unknown-network")`, the body is not called, never `proceed`.
  - The provider is a single `NetworkSnapshotProvider` implementation over `ConnectivityManager` constructed from the worker's `Context` (no UI/`MainActivity` state), shared with `AndroidGitRepository`'s LFS metered read (`AndroidGitRepository.kt:85-93`). Task 2.2.2e adds it; the existing `AndroidNetworkMonitor` `SecurityException -> null` behaviour is reused, not duplicated.
- One-shot skip never reports success (Adv-5 / B5).
  - *Given* a registered one-shot task (`model-download`), policy `Unmetered`, `isMetered=true`, *When* `doWork` runs, *Then* the body is not called, the record is `Skipped("metered")` and the result is `Result.retry()` while `runAttemptCount < spec.oneShotRetryCap`; at the cap the result is `Result.failure(workDataOf(KEY_ERROR to "skipped: metered"))`. A skipped one-shot is never `Result.success()`. The error travels in the failure output `Data` (rev2, R-C5): `ModelState` is derived from the `WorkInfo` flow in `DepthModelDownloader` (a cold worker has no downloader instance and must not write `ModelState`), so the existing collector turns the `FAILED` info plus `KEY_ERROR` into `ModelState.Failed("skipped: metered")` and the `awaitTerminal()` waiter resolves. There is no `onTerminalFailure` callback.
  - Attempt budget (rev2, R-C11): skip retries and real download failures share ONE budget, `runAttemptCount` against `spec.oneShotRetryCap`, and `ModelDownloadTask`'s spec sets `oneShotRetryCap = DepthModelDownloadWorker.MAX_ATTEMPTS` (5, `DepthModelDownloadWorker.kt:100`), so the combined worst case (any mix of metered skips and transient errors) is 5 attempts total, not 5 + skips. *Given* 3 metered skips then 2 transient failures, *When* the worker runs a 6th time, *Then* the result is terminal `failure`; *Given* 5 consecutive skips, *Then* terminal `failure` with `skipped: metered`.
  - *Given* a one-shot with the WorkManager `UNMETERED` constraint already satisfied but `isMetered == null` (`AndroidNetworkMonitor.kt:69-74`, `SecurityException`), *Then* the same bounded retry then terminal failure applies. Decision (recorded in ADR-006): `null` still fails closed even when WorkManager verified `UNMETERED`, because the constraint and the live check derive from different signals; the bounded retry plus terminal `Failed` guarantees no hang and no zero-download success.
- ADR-002 (git-sync-resilience) preserved: no `retry()` for non-retryable failures.
  - *Given* a task returning `Failed("auth", retryable=false)`, *When* `doWork` runs, *Then* `Result.failure()` (never `retry()`); *Given* `Failed("net", retryable=true)` for `model-download`, *Then* `Result.retry()`.
- Catches `Throwable`.
  - *Given* a task throwing `OutOfMemoryError`, *When* `doWork` runs, *Then* a `Failed` record is logged and `Result.failure()` is returned; `CancellationException` is rethrown.
- Missing registry entry (cold start before registration) is safe for `TaskWorker`.
  - *Given* `TaskRegistry` empty, *When* `TaskWorker.doWork` runs for a periodic kind, *Then* `Result.success()` with a `Skipped("task-not-registered")` record and no crash. (This applies to `TaskWorker` only; `GitSyncWorker` does not consult the registry, Story 3.1.2.)
- Cold worker opens no DB it does not need (rev2, A-C4).
  - *Given* a cold `TaskWorker` for a PERIODIC kind and a non-active graph whose `task_entry_*` exists, *When* `doWork` runs, *Then* the `LegacyConfigSeed` is never invoked (no graph DB open), and an entry with `enabled=false` short-circuits to `Skipped("disabled")` before the network gate and before any hook; the seed runs only when entry and marker are both absent. A worker test with a counting seed asserts both.
- `enabled` is periodic-only: a one-shot always runs (Phase 4 repair, P1-1: the second consumer must not silently never download).
  - *Given* a registered `TaskShape.OneShot` task (`model-download`) and NO `task_entry_*` for its key (a fresh install; the store would resolve `enabled=false`), *When* `TaskWorker.doWork` runs with input `network_policy=unmetered` and `isMetered=false`, *Then* the task BODY IS CALLED and the result maps normally; *Given* an explicit store entry with `enabled=false` for the same key, *Then* the body is still called. For `OneShot`, `doWork` consults only: registry, `KnownScopes` guard (scope `""` is never cancelled), hooks, `NetworkGate` (policy from the input key `KEY_NETWORK_POLICY`, absent = `Unmetered` for a one-shot, i.e. fail closed to the safe default), the attempt cap and `recordRun`; it never calls `TaskSettingsStore.read`, so a locked or Keystore-broken store cannot stop a user-initiated download. `TaskWorkerTest` has one test per sentence.
- Unreadable settings are not a silent success (Phase 4 repair, pre-mortem #4; periodic `TaskWorker` kinds only).
  - *Given* a periodic task and `TaskSettingsStore.readResult` returning `ReadFailed` (cold process, Keystore locked/broken), *When* `doWork` runs, *Then* the body is not called, the result is `Result.success()` (a periodic kind must not `retry()`; the next period retries), the run log gets `Skipped("settings-unreadable")` (if the run-log write also fails, a `Logger` line is the fallback), and the plain-prefs `SettingsReadHealth` records the failure time so the status line shows "Can't read settings" instead of Late; it is never reported as `disabled` or `success` without a record.
- Early owner Keystore checkpoint (Phase 4 repair, pre-mortem #4; Task 2.2.2h).
  - *Given* Story 2.2.2 code complete, *Then* the owner runs the cold-worker Keystore check (previously only Story 5.1.3) BEFORE Epic 3.x builds on it; a failure re-opens the fail-closed design here, not at release time.
- Cold-path eligibility.
  - *Given* a `TaskSpec(allowedInColdPath=false)` and no live process state, *When* `doWork` runs, *Then* `Skipped("cold-path-not-allowed")`.
- Unknown scope never cancels on "unknown" (Arch-2 / C4).
  - *Given* a key whose scope is absent from a NON-EMPTY, successfully parsed registry (`KnownScopes.Known({g1})`, key scope `ghost`), *When* `doWork` runs, *Then* the worker cancels its own unique work and returns `success()`.
  - *Given* `KnownScopes` returns `Unknown` because the registry is empty, throws, or is corrupt JSON, *When* `doWork` runs for scope `ghost`, *Then* the worker still RUNS the task (no cancel).
- Adapter hooks preserve androidMain behaviour (C6).
  - *Given* a test-registered periodic kind carrying `TaskAdapterHooks` (stand-ins for the watchdog `checkAndSurfaceStuckGitCloneWorker`, slow-path yield `isGitCloneWorkerRunningFor` and `SyncNotifier.notifyUpdatesWaiting` post-run), *When* `TaskWorker.doWork` runs, *Then* the hooks run in the order preRun -> gate -> body -> postRun and a yield hook returning true makes the result `Result.success()` with `Skipped("clone-running")` and no body call. In production `git-sync` is run by the thin `GitSyncWorker`, which calls the SAME hook functions (`GitSyncAdapterHooks`) in the same order; that wiring is asserted in Story 3.1.2 (`WorkManagerSyncSchedulerWatchdogTest`/`SlowPathYieldTest` plus the new ordering test there), not through `TaskWorker`.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/jobs/schedule/{TaskWorker.kt,TaskWorkRunner.kt,TaskAdapterHooks.kt,AndroidNetworkSnapshotProvider.kt,TaskForegroundProvider.kt}`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/jobs/schedule/{KnownScopes.kt,NetworkSnapshotProvider.kt}`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/jobs/schedule/TaskWorkerTest.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/AllAndroidUnitTests.kt`

##### Task 2.2.2a: `TaskWorkerTest` (test first) (M)
- Use `TestListenableWorkerBuilder`; fake registry/log/`NetworkSnapshotProvider`/`KnownScopes`; one test per criterion above including `Throwable`, throwing provider, one-shot skip at and below the retry cap (including the mixed skip + transient-failure shared budget and the `KEY_ERROR` output data), `isMetered == null` with constraint satisfied, `KnownScopes` Unknown (empty / throwing / corrupt), hook order, existing entry never calls the counting seed, `enabled=false` entry short-circuits a PERIODIC kind, a one-shot with no store entry and with an `enabled=false` entry runs its body (P1-1), one-shot policy read from `KEY_NETWORK_POLICY` (an `Any` input over a stored `Unmetered` runs), periodic `ReadFailed` -> `Skipped("settings-unreadable")` + `SettingsReadHealth` set. Plain `kotlin.test`, no `Arb` (Rule 6). Register in `AllAndroidUnitTests`. Evidence: `./gradlew :kmp:testDebugUnitTest --tests 'dev.stapler.stelekit.jobs.schedule.TaskWorkerTest'`.
- Files: `TaskWorkerTest.kt`, `AllAndroidUnitTests.kt`
##### Task 2.2.2b: `TaskWorker.doWork` and shared `runTaskWork` (M)
- Order: resolve registry -> `KnownScopes` guard -> BY SHAPE: periodic = read config via `TaskSettingsStore.readResult` (`ReadFailed` -> `Skipped("settings-unreadable")` + `SettingsReadHealth`; existing entry short-circuits `enabled=false` before anything else; the lazy legacy seed runs only when entry and marker are both absent; fail closed), one-shot = NO store read and NO `enabled` check, policy from `KEY_NETWORK_POLICY` (P1-1) -> hooks.preRun -> `NetworkGate` with `NetworkSnapshotProvider` -> `task.run` -> map result by shape (periodic skip = success; one-shot skip = `retry()` while `runAttemptCount < oneShotRetryCap`, then `failure(KEY_ERROR)`; shared attempt budget with real failures) -> `recordRun` (Task 2.2.2i) -> hooks.postRun -> `Result`. Catch `Throwable`, rethrow `CancellationException`. The body lives in top-level `runTaskWork(...)`, used by `TaskWorker` only. `GitSyncWorker` (final class, Story 3.1.2) does NOT call `runTaskWork`: it is a thin wrapper over the extracted git-sync task functions with no registry, store or `enabled` dependency (rev2, N2), but it shares `NetworkGate` (policy from `KEY_NETWORK_POLICY`) and `recordRun` with this path (Phase 4 repair, P1-2).
- Files: `TaskWorker.kt`, `TaskWorkRunner.kt`
##### Task 2.2.2c: Registry population on cold start (S) (rev1)
- ONLY `SteleKitApplication.onCreate` (`androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt`) populates `TaskRegistry`, inside `catch (e: Throwable)`, before any worker; mirror the `GitSyncServiceRegistry` precedent. `MainActivity` does NOT register (it runs on every Activity creation). Add `TaskRegistryBothRootsTest`: constructing the Application registration then the activity-side root construction twice in sequence does not throw (idempotent register).
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt`, `TaskWorker.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/jobs/schedule/TaskRegistryBothRootsTest.kt`, `AllAndroidUnitTests.kt`
##### Task 2.2.2d: `TaskForegroundProvider` hook (S)
- androidMain-only map kind -> `ForegroundInfo` factory, used only for `runOnce` user-initiated work; test that periodic never calls it. Scope (C7): it only PRESERVES the existing `DepthModelDownloadWorker` dataSync notification (same channel, text and `ForegroundInfo` code moved behind the provider); it adds no new notification and no other kind registers one in v1.
- Files: `TaskWorker.kt`, `kmp/src/androidMain/kotlin/dev/stapler/stelekit/jobs/schedule/TaskForegroundProvider.kt`
##### Task 2.2.2e: `NetworkSnapshotProvider` and `KnownScopes` (S) (rev1)
- commonMain interfaces; androidMain `AndroidNetworkSnapshotProvider` over `ConnectivityManager` (single implementation, reused by `AndroidGitRepository` in Story 3.2.2); `GraphRegistryKnownScopes(settings)` in `db/` parses the `graph_registry` Settings string with `GraphManager`'s serializer: returns `Known(ids)` only when the string parses and has at least one non-demo graph, else `Unknown`. Never throws.
- Files: `NetworkSnapshotProvider.kt`, `KnownScopes.kt`, `AndroidNetworkSnapshotProvider.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphRegistryKnownScopes.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/db/GraphRegistryKnownScopesTest.kt` (register in `business_common_tests` + `AllBusinessTests`)
##### Task 2.2.2f: `TaskAdapterHooks` for git (S) (rev1)
- androidMain `GitSyncAdapterHooks` wiring the watchdog, `SyncNotifier` and slow-path yield from the current `GitSyncWorker.doWork` (re-read the file first); the same hook functions are called directly by the thin `GitSyncWorker` wrapper (Task 3.1.2c); `WorkManagerSyncSchedulerWatchdogTest` and `SlowPathYieldTest` stay green because the hooks call the same functions.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/jobs/schedule/TaskAdapterHooks.kt`, `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitSyncAdapterHooks.kt`
##### Task 2.2.2g: `readResult` consumers and `SettingsReadHealth` (S) (Phase 4 repair, pre-mortem #4)
- androidMain `SettingsReadHealth` over a plain (non-Keystore) `SharedPreferences` file: `recordFailure(nowMs)`, `lastFailureMs()`, `clear()` (cleared by the next successful `readResult`). `runTaskWork` calls `recordFailure` on `ReadFailed`; the status line (Task 4.1.2b) shows "Can't read settings" while a failure is newer than the last success instead of Late; never holds user data. Covered by the `TaskWorkerTest` cases above.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/jobs/schedule/SettingsReadHealth.kt`, `TaskWorkRunner.kt`
##### Task 2.2.2h: Early owner Keystore checkpoint (XS) (Phase 4 repair, pre-mortem #4)
- After 2.2.2 merges, hand the owner the cold-worker Keystore check (Story 5.1.3 item, repeated here): kill the process, let a scheduled worker read `TaskSettingsStore`, write a run-log record, reopen and read the persisted log. Record the result in the PR before Story 3.1.x starts; Story 5.1.3 keeps the final run. Wall-clock blocker listed in the Effort estimate.
- Files: none (PR description)
##### Task 2.2.2i: Shared `recordRun` (S) (Phase 4 repair, P1-2)
- Top-level androidMain function `recordRun(runLog, record)` that builds nothing from the registry or the store: appends the `TaskRunRecord` (Background source) and emits the `TaskRun` `Logger` line, wrapped in `try { } catch (Throwable)` (rethrow `CancellationException`) so a log failure never changes the worker `Result`. Used by `runTaskWork` and by the thin `GitSyncWorker` (Task 3.1.2c). Test (in `TaskWorkerTest` plus Story 3.1.2): a `Settings` that throws on write leaves the `Result` unchanged and logs via `Logger`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/jobs/schedule/RecordRun.kt`, `TaskWorkRunner.kt`

#### Story 2.2.3: pause/resume and orphan reconcile
**As a** user who moves a graph or deletes one, **I want** work to follow, **so that** schedules neither stay at 15 minutes nor run forever for a deleted graph, and no live graph is ever cancelled by mistake.
**Acceptance Criteria** (rev1: ledger-based resume, fail-closed reconcile, GraphManager success-path hook):
- `pause` snapshots, `resume` restores from the snapshot (rev2, N1/R-C7).
  - *Given* `(git-sync,"g1")` applied at 45 min, *When* `pause("g1")` then `resume("g1")`, *Then* the enqueued interval is 45 min, not 15. *Given* the `WorkManagerSyncSchedulerPauseTest` scenario (`schedule(15)` through the shim, no store entry, and NO `SteleKitContext.init` in the test), *Then* `pause`/`resume` re-enqueues: `pause` reads the live `WorkInfo` (`periodicityInfo.repeatIntervalMillis`, constraints, tags) and writes the snapshot to the ledger BEFORE `cancelUniqueWork`, and the in-memory copy covers a no-op `Settings`.
  - *Given* the `WorkManagerSyncSchedulerUniqueWorkTest` scenario (periodic request enqueued directly via `WorkManager`, no shim, no ledger, no store entry, no legacy flag; `AndroidGitCloneWorkerLauncher.launchClone` calls `pauseFor` at `:91` then `resumeFor` in `finally` at `:106`), *Then* after `launchClone` the unique work is periodic and `!state.isFinished` (restored from the snapshot taken at `pauseFor`).
  - *Given* a pre-upgrade periodic job (old `GitSyncWorker`, no scope tag, unmigrated, no ledger, store and legacy flag empty), *When* `pause` then `resume` run, *Then* the job is re-enqueued with its original interval and constraints.
  - *Given* no snapshot and a graph that is legacy-enabled but unmigrated, *When* `resume` runs, *Then* `TaskSettingsStore.read` lazily migrates (seed only because entry and marker are absent) and the periodic work is enqueued. *Given* a snapshot plus a store entry or tombstone saying `enabled=false`, *Then* `resume` enqueues nothing (the store wins over a snapshot; a disabled graph is never resurrected). *Given* a snapshot plus an enabled store entry, *Then* the snapshot is restored. `resumeFor`/the shim call `SteleKitContext.init(context)` (idempotent) before building `PlatformSettings()`.
- `pause` cancels only the named graph.
  - *Given* `g1` and `g2` applied, *When* `pause("g1")`, *Then* `g2` work remains.
- Reconcile cancels only positively-orphaned scopes (Arch-2).
  - *Given* enqueued work tagged `stelekit-sched-scope:ghost` and `KnownScopes.Known({g1})`, *When* `reconcile` runs, *Then* the `ghost` work is cancelled and `g1` work remains; app-wide scope `""` is never cancelled.
  - *Given* `KnownScopes.Unknown` (empty registry, throwing read, corrupt JSON), *When* `reconcile` runs, *Then* NOTHING is cancelled and no tombstone is written. Reconcile never tombstones.
- Graph removal cancels and tombstones, only on success.
  - *Given* graph `g1` applied, *When* `GraphManager.removeGraph(g1)` returns true, *Then* `onGraphRemoved(g1)` ran after `saveRegistry()`, `cancelAll("g1")` was invoked and `TaskSettingsStore.read(git-sync,"g1")` decodes to disabled.
  - *Given* `removeGraph` refuses (graph not found, demo graph, or active graph that is not the last real graph), *Then* `onGraphRemoved` is NOT invoked, nothing is cancelled and no tombstone is written (note `activeGraphJobs.remove(id)?.cancel()` runs before the guards today; the hook must not).
  - *Given* `onGraphRemoved` throws (rev2, A-C2), *When* `removeGraph(g1)` succeeds, *Then* it still returns `true` and the registry stays saved. *Given* the root's handler launched `cancelAll` + `tombstone` and the process died before it ran, *Then* the next startup `reconcile` (`KnownScopes.Known` without `g1`) cancels the work (backstop; `Unknown` still cancels nothing).
  - *Given* graph `g1` removed (tombstoned) and then re-added at the same path (same `GraphId`), *When* the user enables sync, *Then* the explicit `write` supersedes the tombstone (Story 1.1.4).
- Snapshot rules (rev3, C2-1/C2-3).
  - *Given* a pause over live periodic work, *Then* the `PausedSnapshot` keeps interval, flex, constraints, tags AND the remaining initial delay (the `WorkInfo.initialDelayMillis` is not enough to know what remains, so the snapshot stores `max(0, enqueueTime + initialDelay - now)`; if unreadable it stores the original initial delay). `resume` re-enqueues `GitSyncWorker` for `git-sync` with that delay and those constraints, so a `SynchronousExecutor` driver does not run it immediately.
  - *Given* `pause` over work that is NOT periodic (a clone already RUNNING under the shared name) or a second `pause` after the first, *Then* the existing snapshot is NOT overwritten or cleared (a no-clobber rule: a snapshot is written only from a periodic `WorkInfo`).
  - Failure ordering (Phase 4 repair, pre-mortem #3). *Given* `pause` over live periodic work, *Then* the order is: read live `WorkInfo` (caught) -> write the process-static in-memory copy (cannot fail) -> write the ledger snapshot (caught, `Logger` warning) -> `cancelUniqueWork` ALWAYS runs. A snapshot error never skips the cancel (a skipped cancel blocks the clone on the shared unique name; a lost snapshot is recoverable). *Given* the `WorkInfo` read itself throws, *Then* no snapshot is written, the cancel still runs, and `resume` falls back in-memory (absent) -> ledger (last applied config) -> `TaskSettingsStore.read` (lazy seed); startup `reapplyAll` is the final backstop. Test: `Settings` throwing on write during `pause` (also in the Story 1.1.6 contract) and a `WorkManager` read that throws.
  - User change during a pause (pre-mortem #3b). *Given* a `PausedSnapshot` exists for the key, *When* `apply` is called with a new config (the controller re-fires because the composition `LaunchedEffect` is keyed on `(key, config)`), *Then* `apply` updates the snapshot's schedule/constraints (keeping the remaining initial delay) and enqueues nothing; `apply(enabled=false)` clears the snapshot; `resume` then restores the NEW config, never the stale one.
  - *Given* a `resume` that restored a snapshot, *Then* it consumes it (deletes the ledger entry and clears the process-static copy); a second `resume` enqueues nothing. Test fixtures reset the static copy in `@Before`/`@After`.
  - *Given* the blocking `getWorkInfosForUniqueWork(...).get()` inside non-suspend `pauseFor`, *Then* it must not run on the main thread. Trace: `AndroidGraphMoveQuiesceStrategy.quiesce` (`db/GraphMoveQuiesceStrategy.android.kt:67`) is `suspend` and calls the `(String) -> Unit` lambda directly with no dispatcher switch (`:89` is the same for resume), and `AndroidGitCloneWorkerLauncher.launchClone` (`:91`, `:106`) calls `pauseFor`/`resumeFor` directly after a `withContext(Dispatchers.IO)` block that has already ended; the calling dispatcher of both is therefore the caller's and is UNVERIFIED as non-main. Remedy: wrap both call sites in `withContext(Dispatchers.IO)` (quiesce wraps the lambda call; launcher wraps the two calls), so no edit to the static function signatures is needed.
- `WorkManagerSyncSchedulerUniqueWorkTest` class check (rev3, C2-1).
  - *Given* the test's own `WorkerFactory` (returns `null` for any class except `GitSyncWorker`/`GitCloneWorker`), *When* `launchClone` over the directly enqueued periodic job completes, *Then* the restored job's `workerClassName == GitSyncWorker::class.java.name` and the state is `ENQUEUED` (not FAILED), asserted with that factory.
- Existing `WorkManagerSyncSchedulerPauseTest` assertions hold against the shim; green is checked by Story 3.1.2 (Task 3.1.2b run list), not asserted here.
**Files**: `WorkManagerTaskScheduler.kt`, `kmp/src/androidMain/kotlin/dev/stapler/stelekit/jobs/schedule/ScheduledTaskReconciler.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/jobs/schedule/WorkManagerTaskSchedulerPauseResumeTest.kt`, `.../ScheduledTaskReconcilerTest.kt`, `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerRemovalHookTest.kt`, `AllAndroidUnitTests.kt`, `AllBusinessTests.kt`

##### Task 2.2.3a: Pause/resume + reconcile + removal-hook tests (test first) (S)
- The scenarios above, including the `PauseTest`-without-`SteleKitContext.init`, `UniqueWorkTest`-style direct-enqueue and pre-upgrade unmigrated resume cases, store-disabled-beats-snapshot, the `UniqueWorkTest`-factory class assertion (`GitSyncWorker`, ENQUEUED), no-clobber, consume-on-resume and initial-delay cases, snapshot-write-throws (cancel still runs, resume from the in-memory copy), `WorkInfo`-read-throws, apply-during-pause updates the snapshot, `Unknown` registry cases, the refused-removal cases and the throwing-callback case (`GraphManagerRemovalHookTest` in `businessTest` using the real `GraphManager` with an in-memory `Settings`). Plain `kotlin.test` in androidUnitTest (Rule 6).
- Files: `WorkManagerTaskSchedulerPauseResumeTest.kt`, `ScheduledTaskReconcilerTest.kt`, `GraphManagerRemovalHookTest.kt`, `AllAndroidUnitTests.kt`, `AllBusinessTests.kt`
##### Task 2.2.3b: `pause`/`resume` (S)
- `pause` = read the live `WorkInfo` for the unique name (interval via `periodicityInfo`, constraints, tags; if flex is unreadable per Spike 0.1.1, derive it from the stored jitter) -> write the in-memory copy FIRST -> write the `PausedSnapshot` to the ledger (each step in its own `try/catch (Throwable)` with a `Logger` warning) -> `cancelUniqueWork` ALWAYS (pre-mortem #3 ordering). `apply` while a snapshot exists updates the snapshot instead of enqueuing (and `apply(enabled=false)` clears it). `resume`: store entry/tombstone with `enabled=false` wins (discard snapshot, enqueue nothing); else restore from the snapshot; else `TaskSettingsStore.read` (lazy seed, fail closed); then enqueue. `CANCEL_AND_REENQUEUE` is used ONLY by `resume` (after the clone's `finally`), where a clone worker is known to have run and finished under the shared name. `apply` and startup `reapplyAll` use `UPDATE` only and skip (log, no cancel) when the name is held by non-periodic work, so a RUNNING one-time clone worker is never cancelled (R-C6; matches `UniqueWorkTest:133`).
- rev3: `resume` for `git-sync` enqueues `GitSyncWorker` (the existing class, input keys `KEY_GRAPH_ID` and `KEY_INTERVAL_MINUTES`) with the snapshot's remaining initial delay and constraints, unless Spike 0.1.1 proves `UPDATE` changes `workerClassName` (then ADR-002 records it and non-git kinds may use `TaskWorker`). Apply the snapshot rules in Story 2.2.3 (no-clobber, consume on resume, blocking read off the main thread). `SteleKitContext.init(context)` in `pauseFor`/`resumeFor` is idempotent and runs after `Application.onCreate` (`SteleKitApplication.kt:234`); only test paths call it earlier.
- Files: `WorkManagerTaskScheduler.kt`, `AppliedConfigLedger.kt`, `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.android.kt` (IO wrap), `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitCloneWorkerLauncher.kt` (IO wrap)
##### Task 2.2.3c: `ScheduledTaskReconciler` (S)
- `getWorkInfosByTag("stelekit-sched")`; takes `KnownScopes`; acts only on `Known` non-empty; cancels scopes absent from it; never `""`; never tombstones.
- Files: `ScheduledTaskReconciler.kt`
##### Task 2.2.3d: Graph removal hook (S)
- Add a narrow callback to `GraphManager` (`onGraphRemoved: (GraphId) -> Unit`, default no-op) invoked at the end of `removeGraph`'s success path, after `saveRegistry()` and before `return true`, inside `try { } catch (Throwable)` that logs and never alters the return value. `removeGraph` is non-suspend, so the root sets the callback to a function that LAUNCHES `cancelAll` + `tombstone` on a root-owned guarded scope (`CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler)`; never `rememberCoroutineScope`); startup `reconcile` is the backstop for a missed cancel. No scheduling code in `db`. Lock-order rules in CLAUDE.md are not involved (no `GraphWriteLock`). Serialized after 2.1.2b (Serialization rule).
- Files: `GraphManager.kt`, `StelekitAppDependencies.kt` / `MainActivity.kt` (root wiring only)

---

## Phase 3: Consumers

### Epic 3.1: GitSync onto the port
**Goal**: Git sync runs through `BackgroundTaskScheduler`; existing behaviour and tests are preserved; settings and migration go live.

#### Story 3.1.1: GitSyncTask
**As a** maintainer, **I want** the git tick body behind `BackgroundTask`, **so that** `ScheduledSyncPolicy` stays the domain gate and the port stays git-free.
**Acceptance Criteria** (rev1: last-success source, adapter hooks, registration):
- Outcome mapping.
  - *Given* `TickOutcome.Fetched`, *When* mapped, *Then* `TaskRunResult.Partial("fetched")`; `Success -> Success`; `Skip(Policy(VaultLocked)) -> Skipped("vault-locked")`; `Failure(e) -> Failed(e.message, retryable=false)`.
- Policy unchanged.
  - *Given* `ScheduledSyncInputs(vaultLocked=true)`, *When* `GitSyncTask.run` executes through the real `ScheduledSyncRunner`, *Then* the result is `Skipped("vault-locked")` and no git call is made (existing `ScheduledSyncPolicyTest` still green).
- Fast and cold paths preserved.
  - *Given* a live `GitSyncService` in `GitSyncServiceRegistry`, *When* the task runs, *Then* `service.scheduled.run(graphId)`; with none registered (cold process), the standalone fetch-only slow path runs through the androidMain `ColdPathRunner`; SAF graphs stay fetch-only. The clone-worker watchdog, `SyncNotifier` and slow-path yield run as `TaskAdapterHooks` (Task 2.2.2f), not inside `GitSyncTask` (commonMain cannot import them).
- Vault-locked / conflict skips do not count toward lateness or backoff.
  - *Given* `Skipped("vault-locked")`, *When* `ScheduleLateness` attribution runs, *Then* it is a deliberate skip (Story 1.2.2).
- Git last-success source (Arch-3).
  - *Given* `GitLastSuccessSource(taskRunLog, syncStalenessStore)`, *When* `SyncStaleness.lastMergedAtMs = now-10min` (written by the foreground loop) and `TaskRunLog` holds only `Skipped` records, *Then* `lastSuccessAtMs(git-sync/g1) == now-10min` and `lateness(...)` is `OnTime`; *When* `TaskRunLog` has a `Success` newer than `lastFetchedAtMs`/`lastMergedAtMs`, *Then* the max wins; `Error` outcomes never count; read failures return null (never Late from an error).
- Registration happens once (adversarial B4).
  - *Given* Android, *Then* `git-sync` is registered only in `SteleKitApplication.onCreate` (Task 2.2.2c's block); `MainActivity` registers nothing. Desktop does NOT register `git-sync` in `Main.kt` this release (rev2, R-C1): nothing runs it in-process (`PeriodicSyncDriver` owns the Desktop loop), so a registration would only invite a second loop. Re-registering the identical spec on Android is a no-op (Story 1.1.1).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/{GitSyncTask.kt,TaskOutcomeMapping.kt,GitLastSuccessSource.kt}`, `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt` (cold-path function reuse only), `androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt` (registration line), `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/{GitSyncTaskMappingTest.kt,GitLastSuccessSourceTest.kt}`, `kmp/src/commonTest/kotlin/BUILD.bazel` (`business_common_tests`), `AllBusinessTests.kt`

##### Task 3.1.1a: Mapping and last-success tests (test first) (S)
- Table test for all `TickOutcome`/`SkipReason` variants (exhaustive `when`), property that no mapped value is `Failed` for a `Skip`; `GitLastSuccessSource` cases above. Register both in `business_common_tests` + `AllBusinessTests`.
- Files: `GitSyncTaskMappingTest.kt`, `GitLastSuccessSourceTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`
##### Task 3.1.1b: `GitSyncTask` + mapping + `GitLastSuccessSource` (S)
- Body of the former `GitSyncWorker.doWork` fast/cold decision as a function; the cold path stays an androidMain collaborator invoked through a `ColdPathRunner` fun interface (so commonMain `GitSyncTask` does not import androidx).
- Files: `GitSyncTask.kt`, `TaskOutcomeMapping.kt`, `GitLastSuccessSource.kt`
##### Task 3.1.1c: Spec and registration (S)
- `TaskSpec(jobKind=GitSync, title="Git sync", allowedInColdPath=true (fetch only), shape=Periodic(Schedule.of(60, 6)), defaultConfig(enabled=false, Schedule.of(60, JitterPreset.ALittle.minutesFor(60)) = 60/6, Any))` (B1: matches the `ux.md` default "spread: A little"; `JitterPreset` is created in Task 1.1.2b, so there is no dependency on Story 4.1.1). Android only: add the `register` call inside the existing `catch (Throwable)` block of `SteleKitApplication.onCreate` (alongside Task 2.2.2c). No Desktop registration (R-C1) and no `MainActivity` edit. Strictly after Task 2.2.3d (Serialization rule).
- Files: `GitSyncTask.kt`, `androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt`

#### Story 3.1.2: Legacy shim and exclusion regression
**As a** user mid-clone, **I want** clone and sync to keep excluding each other, **so that** the periodic job is neither stuck BLOCKED nor duplicated.
**Acceptance Criteria** (rev1: concrete shim design, ledger, in-flight legacy rows):
- All existing tests green unchanged, except `GitSyncSchedulingEntryPointTest`, which is updated, AND actually run (adversarial B1).
  - *Given* `WorkManagerSyncScheduler{PauseTest,RetryOwnerTest,SlowPathYieldTest,UniqueWorkTest,WatchdogTest}` and `GitSyncSchedulingEntryPointTest`, *When* run via `./gradlew :kmp:testDebugUnitTest --tests 'dev.stapler.stelekit.git.WorkManagerSyncScheduler*' --tests 'dev.stapler.stelekit.git.GitSyncSchedulingEntryPointTest'` against the shim-over-port implementation, *Then* all pass (RetryOwner: `assertIs<Result.Failure>` for non-retryable; UniqueWork: shared-name clone protocol; Pause: pause/resume with no store entry via the ledger). `GitSyncSchedulingEntryPointTest` is the exception that is updated, not unchanged: see Story 3.1.3 Task 3.1.3e for its explicit edits. These classes are added to `AllAndroidUnitTests` for Bazel coverage (Task 3.1.2e).
- Shared name preserved.
  - *Given* `AndroidGitCloneWorkerLauncher` pauses `(git-sync,"g1")`, runs a clone and restores, *When* the periodic job is re-applied from the ledger/store, *Then* it is periodic under `stelekit_git_sync_g1` and `ENQUEUED`, not BLOCKED.
- Old factory still compiles and delegates, and persists intent.
  - *Given* `BackgroundSyncSchedulerFactory(minimumIntervalMinutes=15) { gid -> WorkManagerSyncScheduler(ctx, gid) }`, *When* `schedule(30)` is called, *Then* the underlying port call is `apply(TaskKey(git-sync,gid), TaskConfig(enabled=true, Schedule.of(30, storedJitter), storedConstraints))` (stored values read from `TaskSettingsStore`, defaults when absent), and the ledger holds that config so a later static `resumeFor(context, gid)` re-enqueues it.
- `GitSyncWorker` stays a real class and a thin wrapper (rev2, N2); in-flight rows redirect correctly.
  - *Given* `GitSyncWorker` built with `TestListenableWorkerBuilder<GitSyncWorker>(context).setInputData(workDataOf(KEY_GRAPH_ID to g))`, an EMPTY `TaskRegistry`, no `TaskSettingsStore` entry and no `SteleKitContext.init` (the `WorkManagerSyncSchedulerRetryOwnerTest` setup: a service in `GitSyncServiceRegistry` whose fetch returns `RetryExhausted`), *When* `doWork` runs, *Then* the result is `Result.Failure` (non-retryable), identical to today; both `RetryOwnerTest` cases pass unchanged. `doWork` calls the extracted git-sync task functions and `GitSyncAdapterHooks` directly: no registry lookup, no `enabled` gate, no `TaskSettingsStore` read, and a `Throwable` guard (rethrow `CancellationException`). It DOES evaluate the shared `NetworkGate` and append a run-log record (next two bullets; both need neither registry nor store).
  - *Given* a pre-upgrade enqueued request whose worker class is `GitSyncWorker` and whose input data holds only `graph_id` and `interval_minutes` (no kind, no scope tag), *When* the worker runs, *Then* it derives the graph from `KEY_GRAPH_ID`, runs the git-sync body. The wrapper keeps reading `KEY_INTERVAL_MINUTES` with the same 15-minute default and passes it to `notifyUpdatesWaiting(graphId, staleness, intervalMinutes)` (`WorkManagerSyncScheduler.kt:140,:149`), so the notification threshold is unchanged; every new enqueue from the adapter keeps setting the key to the effective interval (test: a worker built with `KEY_INTERVAL_MINUTES=60` passes 60 to the notification input, and one without the key passes 15). *Given* input interval 15 but stored interval 60, *Then* the next `apply` re-enqueues at 60 (`LegacyWorkUpgradeTest`). `GitSyncWorker::class.java.name`, `KEY_GRAPH_ID` and `KEY_INTERVAL_MINUTES` are unchanged so the existing tests (`TestListenableWorkerBuilder<GitSyncWorker>`, `workerClassName` assertions) compile. Definitive (Phase 4 repair): `apply` and `resume` enqueue `GitSyncWorker` for `git-sync` regardless of the Spike 0.1.1 `UPDATE` outcome (ADR-002 records the outcome and what it permits later).
- Gated: a periodic git run on a metered connection skips (Phase 4 repair, P1-2a).
  - *Given* a `GitSyncWorker` built with `KEY_GRAPH_ID` and `network_policy=unmetered`, a `NetworkSnapshotProvider` reporting `isMetered=true`, and a live `GitSyncService`, *When* `doWork` runs, *Then* no git call is made, the result is `Result.success()` (periodic skip; never `retry()`), and the run log has exactly one `Skipped("metered")` record; with `isMetered=null` or a throwing provider, `Skipped("unknown-network")`; with `network_policy=any`, or the key ABSENT (a pre-upgrade job; absent = `Any`), the run proceeds, and with `Any` a throwing or unavailable provider proceeds (WorkManager's `CONNECTED` already held). `WorkManagerSyncSchedulerRetryOwnerTest` (only `KEY_GRAPH_ID`, empty registry) still returns `Result.Failure`.
- Recorded: exactly one run-log record per run (Phase 4 repair, P1-2b).
  - *Given* a `GitSyncWorker` run that ends in success, a policy/clone-yield skip, or a failure, *When* `doWork` completes, *Then* `TaskRunLog` holds exactly ONE new `Background` record for `(git-sync, graphId)` with the matching `TaskRunResult` (`Success`/`Partial`, `Skipped(reason)`, `Failed(message)`), the `ConstraintState` read at run start and the scheduled-vs-actual start, and one `TaskRun` `Logger` line; the record is written through the shared `recordRun` (no registry, no store). *Given* the run-log write throws (`Settings` throws, Keystore failure), *Then* the worker's `Result` is unchanged and a `Logger` warning is the fallback.
- Disable never depends on the worker (documented limit, pre-mortem #2).
  - *Given* `task_entry_*` says `enabled=false` but a stale `GitSyncWorker` job still exists (a disable that bypassed `apply`), *Then* the thin worker does not read `enabled`; the guarantee is that disable goes through `apply(enabled=false)`/`cancel`, with startup `reapplyAll` (ledger) and `reconcile` as the backstop. A test asserts `setEnabled(false)` cancels the job; the stale-job case is recorded as a known limit, not tested.
- Hook ordering in production (moved from Story 2.2.2).
  - *Given* a `GitSyncWorker` with the clone-running yield hook true, *Then* the order is preRun (watchdog) -> gate -> yield -> body -> postRun (`SyncNotifier`) and the result is `Result.success()` with `Skipped("clone-running")` and no body call; asserted in a new `GitSyncWorkerGateAndRecordTest` that also covers the two bullets above.
- Existing pause/clone tests pass without `SteleKitContext.init` and without a store entry (rev2, N1).
  - *Given* `WorkManagerSyncSchedulerPauseTest` and `WorkManagerSyncSchedulerUniqueWorkTest` unchanged (neither calls `SteleKitContext.init`; the latter enqueues the periodic request directly), *When* run against the shim, *Then* both pass, because `pauseFor` snapshots the live work and `resumeFor` restores it (Story 2.2.3), and the shim/`resumeFor` call `SteleKitContext.init(context)` before `PlatformSettings()`.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/BackgroundSyncScheduler.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/*` (assertion-preserving edits only if a name changed), `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitSyncWorkerGateAndRecordTest.kt` (new), `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/AllAndroidUnitTests.kt`

##### Task 3.1.2a: Check the open PR stack (XS)
- Re-run `gh pr list --search "git sync"`; if any PR touches `WorkManagerSyncScheduler.kt` or `GitSyncService.kt`, rebase onto it or sequence after its merge; record in this plan.
- Files: `plan.md`
##### Task 3.1.2b: Pin current behaviour with a characterization run (S)
- Run the six existing test classes (the five `WorkManagerSyncScheduler{PauseTest,RetryOwnerTest,SlowPathYieldTest,UniqueWorkTest,WatchdogTest}` plus `GitSyncSchedulingEntryPointTest`; listed once here) with `./gradlew :kmp:testDebugUnitTest --tests ...` (NOT the Bazel android target, which does not include them) and record green as the pre-change baseline (command output pasted into the PR); fix nothing yet.
- Files: none
##### Task 3.1.2c: Shim `WorkManagerSyncScheduler` over the port (M)
- `schedule(interval)`/`cancel()`/`pauseFor`/`resumeFor` delegate to `WorkManagerTaskScheduler`; `pauseFor`/`resumeFor(context, gid)` call `SteleKitContext.init(context)` (idempotent), construct the adapter from `PlatformSettings()` and `Context` (no UI state) and call `pause`/`resume`. `GitSyncWorker` stays a final class with its name/keys (adds the input key `KEY_NETWORK_POLICY`); `doWork` is a thin wrapper calling the extracted git-sync task functions and `GitSyncAdapterHooks` directly (no `runTaskWork`, no registry, no `TaskSettingsStore` or `enabled` read): it builds `AndroidNetworkSnapshotProvider` from its `Context`, evaluates `NetworkGate` with the policy from `KEY_NETWORK_POLICY` (absent = `Any`; `Any` + failing provider proceeds), and calls the shared `recordRun` (Task 2.2.2i) exactly once per run after `SteleKitContext.init(applicationContext)`; keep/upgrade to the `Throwable` catch. The new `GitSyncWorkerGateAndRecordTest` is test-first for this task. `@Deprecated` annotations on the old surface. Serialized after 3.1.1c.
- Files: `WorkManagerSyncScheduler.kt`, `BackgroundSyncScheduler.kt`
##### Task 3.1.2d: Exclusion + in-flight migration test (S)
- Test: pre-existing request enqueued with the *old* `GitSyncWorker` and no scope tag; after `apply` the same unique name is `UPDATE`d with the new tags and the periodic type retained; plus the stale-input-interval case and the RUNNING-`UPDATE` outcome recorded by Spike 0.1.1.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/jobs/schedule/LegacyWorkUpgradeTest.kt`, `AllAndroidUnitTests.kt`
##### Task 3.1.2f: Android `GitLegacyConfigSource` (S) (rev2, A-C1)
- Promote the cold DB read (`private fun loadGitConfigFromDb` at `WorkManagerSyncScheduler.kt:210`; re-read first, it is also used at `:185`) to an `internal` function and implement `GitLegacyConfigSource` over it: returns `Found(...)` when the graph's DB opens and `git_config` is read, `Unavailable` when the DB cannot be opened or read (never `Found(hasRemote=false)` for an unreadable DB). One graph per call; never invoked for all graphs at startup. Test (androidUnitTest, plain `kotlin.test`): unopenable DB path gives `Unavailable`; a DB with no remote gives `Found(hasRemote=false)`. The existing `GitSyncWorker` call site keeps compiling against the promoted function.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt`, `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitLegacyConfigSource.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitLegacyConfigSourceTest.kt`, `AllAndroidUnitTests.kt`
##### Task 3.1.2e: Register legacy scheduler tests for Bazel (XS) (rev1)
- Add `WorkManagerSyncSchedulerPauseTest`, `...RetryOwnerTest`, `...SlowPathYieldTest`, `...UniqueWorkTest`, `...WatchdogTest`, `GitSyncSchedulingEntryPointTest`, `LegacyWorkUpgradeTest`, `GitSyncWorkerGateAndRecordTest` and `AndroidGitLegacyConfigSourceTest` to `AllAndroidUnitTests` (Test Infrastructure Rule 2); confirm none hits the documented Bazel Android gaps (they are Robolectric/WorkManager only, no Compose `setContent`).
- Files: `AllAndroidUnitTests.kt`
##### Task 3.1.2g: Hard gate on Spike 0.1.1 (XS) (Phase 4 repair, P1-2)
- Before Task 3.1.2c starts, confirm ADR-002 records the Spike 0.1.1 outcomes (`UPDATE` and `workerClassName`, RUNNING `UPDATE`, `UPDATE` input-data read-back, observed gap distribution). If any is missing, stop and re-run the spike; do not start the shim. After the first compiling cut of Task 3.1.2c, re-run the six existing classes (Task 3.1.2b run list) to settle the Unresolved Question on the Robolectric `ConnectivityManager` shadow before finishing the shim.
- Files: `ADR-002-...md`

#### Story 3.1.3: Settings glue, migration trigger and Desktop loop ownership
**As a** user, **I want** the Settings values (interval, jitter, network, enabled) to drive git sync on every platform, **so that** the schedule is per graph and nothing is lost on upgrade.
**Acceptance Criteria** (rev1: Desktop keeps `PeriodicSyncDriver` by default; migration is lazy and also eager here; three existing tests are explicitly updated):
- Glue calls `apply` from the store, not a global flag.
  - *Given* `(git-sync,"g1")` stored as `enabled=true, 60, jitter 10, Unmetered`, *When* `rememberGraphContentScheduledSyncControls` composes for `g1`, *Then* `GitSyncScheduleController` reads `TaskSettingsStore` and, when `backgroundTaskScheduler.capabilities.runsInBackgroundWhenClosed` is true (Android `WorkManagerTaskScheduler`), `BackgroundTaskScheduler.apply` is invoked once with that config; `enabled=false` calls `apply(enabled=false)`. The rule (rev2, R-C1): the controller calls `apply` ONLY when `runsInBackgroundWhenClosed`; on a scheduler without it (Desktop `InProcessTaskScheduler`, `Noop`) the controller persists the entry and feeds `PeriodicSyncDriver` but never calls `apply`, so no second loop starts.
- Desktop migrates on first graph open (Arch-1).
  - *Given* the Desktop wired fixture with `scheduled_sync_enabled=true`, graph `g1` with a remote and `poll_interval_minutes=30`, and NO `task_entry_*` key, *When* the graph opens and `GitSyncScheduleController.ensureMigrated("g1")` runs (called from the Desktop graph-open path and from the settings glue), *Then* the first controller read reports `enabled=true`, interval 30, and the Desktop `PeriodicSyncDriver` for `g1` starts at 30 min; a Desktop user with sync on never sees it turn off after upgrade.
- Desktop git keeps `PeriodicSyncDriver` (ADR-001 fallback is the default this release).
  - *Given* the Desktop root, *When* `(git-sync,"g1")` is enabled at 15 min, *Then* `PeriodicSyncDriver` owns the tick (interval and jitter read from the store through the controller), `apply` is never called for it (capability rule above) so `InProcessTaskScheduler.describe(git-sync/g1)` is null and no `git-sync` task is registered on Desktop (no double tick; asserted by `TaskSchedulerDesktopEntryPointTest` against a counting scheduler), and the status line, pause-after-3-failures, `immediateFirstTick`, busy/offline skips and restart-on-manual-success behave as today (`PeriodicSyncDriver*Test` and `ScheduledSyncStatusTest` unchanged and green). `InProcessTaskScheduler` is constructed in `Main.kt` as the host's explicit `backgroundTaskScheduler`, supplying capabilities (`supportsUnmetered=false`, `runsInBackgroundWhenClosed=false`) to the Settings UI. Promoting git onto it is a follow-up gated on a parity test (status line, failure pause).
- Android foreground loop is unchanged.
  - *Given* the Android app open, *When* sync is enabled, *Then* `PeriodicSyncDriver` runs the in-app loop and WorkManager owns the closed-app job; one tick per interval per path.
- Jitter reaches the in-app loop.
  - *Given* jitter 5 min, *When* `PeriodicSyncDriver.intervalMs()` is evaluated across 50 cycles with a seeded `Random`, *Then* every delay is in `[interval, interval + 5 min]` (property test); jitter 0 is byte-identical to today.
- Foreground ticks are recorded writer-safely (Phase 4 repair, B4).
  - *Given* a `PeriodicSyncDriver` (Desktop git loop or the Android in-app loop) completing ticks that end `Success`, `Fetched`, `Skip(Policy(VaultLocked))` and `Failure`, *When* each tick finishes, *Then* the injected tick recorder appends exactly one `RunSource.Foreground` record for `(git-sync, graphId)` mapped through `TaskOutcomeMapping` (`Success`, `Partial("fetched")`, `Skipped("vault-locked")`, `Failed(message)`), with the in-app `ConstraintState`; a throwing recorder never changes the driver's `TickOutcome`, backoff or pause-after-3-failures behaviour; the Logs screen shows Desktop runs (Story 4.1.3); and a Background-source record written concurrently by a `GitSyncWorker` for the same key is never lost (distinct keys, Story 1.2.1). `PeriodicSyncDriver*Test` and `ScheduledSyncStatusTest` stay green unchanged (the recorder defaults to a no-op in their constructors).
- Existing entry-point tests are explicitly updated, not silently broken (adversarial B2).
  - *Given* `GitSyncSchedulingEntryPointTest` (androidUnitTest/git; drives `rememberGraphContentScheduledSyncControls(wired.deps, null, configRepo, graphId)` with only `SCHEDULED_SYNC_ENABLED_KEY=true`), *When* run after the change, *Then* its assertions are unchanged (one periodic job at 15 min from `pollIntervalMinutes=5`, `controls.closedAppChecks`, `controls.minimumIntervalMinutes`, cancel on `setEnabled(false)`), because the lazy legacy seed reads the same flag plus the test's `configRepo` remote/interval; the edit is to the FIXTURE (`WiredAppFixture` passes the new `backgroundTaskScheduler`, `taskRegistry`, `TaskSettingsStore` + `LegacyGitSyncMigration` seed over the same `FakeSettings`/`configRepo`) and `setEnabled(false)` now also writes `enabled=false` to the store.
  - *Given* `GitSyncStackEntryPointTest` and `ScheduledSyncOriginE2ETest` (jvmTest/wiring), *When* run, *Then* they pass with the same assertions; their `DesktopWiredAppFixture` edit adds the explicit scheduler/registry/store/seed. Any assertion that does change is listed in the PR with the reason.
  - *Given* `ScheduledSyncControls` consumers (`ScheduledSyncSection`, `ScheduledSyncSettingsTest`, `ScheduledSyncControlsApplyTest`), *Then* `ScheduledSyncControls` keeps its public constructor shape (`enabled`, `setEnabled(Boolean)`, `closedAppChecks`, `minimumIntervalMinutes`, ...), so no consumer changes; the per-graph entry is behind it.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/GraphContentGitSyncSetup.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/{GitSyncScheduleController.kt,PeriodicSyncDriver.kt}`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitAppDependencies.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/wiring/{DesktopWiredAppFixture.kt,GitSyncStackEntryPointTest.kt,ScheduledSyncOriginE2ETest.kt}`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/wiring/WiredAppFixture.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitSyncSchedulingEntryPointTest.kt`

##### Task 3.1.3a: Glue + migration + no-double-tick tests (test first) (S)
- `commonTest` controller test with `FakeBackgroundTaskScheduler` and in-memory `Settings` (glue, `ensureMigrated`, tombstone not migrated); jvm test via `DesktopWiredAppFixture` for the Desktop migrate-on-open case and no-double-tick; register in `business_common_tests` + `AllBusinessTests` (controller test) and `AllJvmTests` (jvm test).
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/GitSyncScheduleControllerTest.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/wiring/TaskSchedulerDesktopEntryPointTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`, `AllJvmTests.kt`
##### Task 3.1.3b: Extract the scheduling slice (refactor-first) (M)
- Move the `scheduler.schedule/cancel` + enabled/interval read out of the composable body into `GitSyncScheduleController` (`ensureMigrated(graphId)`, `read`, `setEnabled`, `setSchedule`, `setNetwork`; reads/writes `TaskSettingsStore`, calls `apply` only when `capabilities.runsInBackgroundWhenClosed`); composable only observes. Keep `LaunchedEffect` keyed on `(key, config)`; `UPDATE` makes repeated applies safe. Keep the `ScheduledSyncControls` public shape.
- Files: `GraphContentGitSyncSetup.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncScheduleController.kt`
##### Task 3.1.3c: Desktop root (M)
- Construct `InProcessTaskScheduler` in `Main.kt` as the explicit `backgroundTaskScheduler` (no `git-sync` registration); keep `PeriodicSyncDriver` as the Desktop git loop, reading interval/jitter from the controller; call `ensureMigrated` on graph open. Serialized after 3.1.2 (Serialization rule).
- Files: `Main.kt`, `GraphContentGitSyncSetup.kt`, `StelekitAppDependencies.kt`
##### Task 3.1.3d: Jitter into `intervalMs()` (S)
- Single seam at `PeriodicSyncDriver.kt:245` (re-verify the line): `intervalMs()` returns `JitterPolicy.nextDelayMs(...)` with an injected `Random`; jitter 0 is byte-identical to today (existing `PeriodicSyncDriver` tests green).
- Files: `PeriodicSyncDriver.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/PeriodicSyncDriverJitterTest.kt` (register in `business_common_tests` + `AllBusinessTests`)
##### Task 3.1.3e: Update the three entry-point tests and fixtures (M) (rev1)
- Edit `WiredAppFixture.kt` and `DesktopWiredAppFixture.kt` (explicit scheduler, registry, `TaskSettingsStore` over the fixture's `FakeSettings`). The `LegacyGitSyncMigration` seed for the COMPOSITION path is NOT supplied by the fixture (rev2, R-C2): the test-local `configRepo` is created after the fixture and passed only to the composable, so `rememberGraphContentScheduledSyncControls(deps, ..., configRepo, graphId)` builds the seed itself from its `configRepo` parameter (via the Desktop/active-graph `GitLegacyConfigSource`, Task 3.1.3f) and `deps.platformSettings`; that is what lets `GitSyncSchedulingEntryPointTest` see its own remote and `pollIntervalMinutes=5`. Run `GitSyncSchedulingEntryPointTest` (`./gradlew :kmp:testDebugUnitTest --tests ...`), `GitSyncStackEntryPointTest` and `ScheduledSyncOriginE2ETest` (`scripts/jvm-display-check.sh -- ./gradlew :kmp:jvmTest --tests ...`); list in the PR any assertion changed and why (expected: none). Add `ScheduledSyncControlsApplyTest` (Robolectric) proving `setEnabled(false)` persists to the store.
- Files: the fixtures, `GraphContentGitSyncSetup.kt` (seed construction in the composition path), the three tests, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/ScheduledSyncControlsApplyTest.kt`, `AllAndroidUnitTests.kt` (add only the non-Compose ones). Serialized: both fixtures were already edited by 2.1.2b, so 3.1.3e runs strictly after 3.1.3c and before 3.2.1d (Serialization rule).
##### Task 3.1.3f: Desktop / active-graph `GitLegacyConfigSource` (S) (rev2, A-C1)
- Implement `GitLegacyConfigSource` over the open graph's `GitConfigRepository` (commonMain `git/`): returns `Found(hasRemote, pollIntervalMinutes)` when the repository answers, `Unavailable` when the graph is not open yet or the read throws (never "no remote" for an unreadable graph). Used by the Desktop graph-open path, `rememberGraphContentScheduledSyncControls` (from its `configRepo` parameter) and the Android active-graph path; the Android cold path uses Task 3.1.2f. commonTest with the fake `GitConfigRepository`: `Found` with remote, `Found` without, `Unavailable` on throw; register in `business_common_tests` + `AllBusinessTests`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/ConfigRepositoryLegacyConfigSource.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/ConfigRepositoryLegacyConfigSourceTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`
##### Task 3.1.3g: `PeriodicSyncDriver` tick recorder (S) (Phase 4 repair, B4)
- Add an injected `TickRunRecorder` (`fun interface`, default no-op) to `PeriodicSyncDriver`, called once after each tick with the mapped `TaskRunResult` and the in-app `ConstraintState`; the production wiring (Desktop root and `rememberGraphContentScheduledSyncControls`) supplies `SettingsTaskRunLog(...).append(record.copy(source = Foreground))`, `Throwable`-guarded. Tests (test first): `PeriodicSyncDriverRunRecordTest` in commonTest covering the four outcomes, a throwing recorder, and that existing driver tests are unchanged; register in `business_common_tests` + `AllBusinessTests`. Serialized after 3.1.3e for the root wiring.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/PeriodicSyncDriver.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/TickRunRecorder.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/PeriodicSyncDriverRunRecordTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`, `GraphContentGitSyncSetup.kt`, `Main.kt`

#### Story 3.1.4: Startup migration, reconcile and self-heal
**As a** user, **I want** my schedule re-armed after a reboot, force-stop or update without opening the graph, **so that** background sync resumes.
**Acceptance Criteria** (rev1: eager call of the lazy read, KnownScopes guard, no DB fan-out):
- Startup migrates the active graph eagerly via the same lazy read and never flips a graph.
  - *Given* legacy flag true, active graph `g1` (remote), *When* `ScheduleStartup.run` executes, *Then* `TaskSettingsStore.read(git-sync/g1)` migrated it (`enabled`, marker); a second start changes nothing. Non-active graphs are NOT opened at startup (no DB fan-out in `Application.onCreate`); they migrate lazily on first controller read, worker run or `resume`.
- Self-heal re-applies from the ledger fail closed.
  - *Given* `g1` has a ledger entry but no WorkManager entry (force-stop wiped), *When* startup `reapplyAll` runs over `KnownScopes.Known` x registered periodic kinds, *Then* the periodic work exists (it reads the LEDGER only and never calls `TaskSettingsStore.read`, so it cannot trigger the lazy seed or open any graph DB; asserted with a counting seed); *Given* `KnownScopes.Unknown` or a throwing `Settings`, *Then* nothing is enqueued and `Application.onCreate` does not crash.
- Reconcile runs at startup and is fail closed (Arch-2).
  - *Given* work for scope `ghost` and `KnownScopes.Known({g1})`, *When* startup reconcile runs, *Then* it is cancelled; *Given* an empty, corrupt or unreadable `graph_registry`, *Then* nothing is cancelled.
**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt`, `kmp/src/androidMain/kotlin/dev/stapler/stelekit/jobs/schedule/ScheduleStartup.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/jobs/schedule/ScheduleStartupTest.kt`, `AllAndroidUnitTests.kt`

##### Task 3.1.4a: `ScheduleStartupTest` (test first) (S)
- The three scenarios above, plus "no network call at startup" and "no non-active graph DB opened". Register in `AllAndroidUnitTests`; evidence via `./gradlew :kmp:testDebugUnitTest --tests 'dev.stapler.stelekit.jobs.schedule.ScheduleStartupTest'`.
- Files: `ScheduleStartupTest.kt`, `AllAndroidUnitTests.kt`
##### Task 3.1.4b: `ScheduleStartup.run` (S)
- Order: migrate active graph (store read) -> reconcile (KnownScopes-guarded) -> reapplyAll (ledger-based); each step individually guarded (`catch Throwable`), skipped if WorkManager is not initialised.
- Files: `ScheduleStartup.kt`
##### Task 3.1.4c: Call from `Application.onCreate` (S)
- Inside the existing `catch (e: Throwable)` block of `androidApp/.../SteleKitApplication.kt` (path verified); off the main thread (`Dispatchers.Default`). Same file as Tasks 2.2.2c/3.1.1c/2.2.3d's root wiring: serialized in the chain 2.2.3d -> 3.1.1c -> 3.1.2c -> 3.1.3c -> 3.1.3e -> 3.1.3g -> 3.1.4c -> 3.2.1d (Serialization rule).
- Files: `SteleKitApplication.kt`

### Epic 3.2: Second consumer, model download and LFS
**Goal**: Model download runs through `runOnce` with the shared network policy; LFS shares the same type.

#### Story 3.2.1: ModelDownloadTask and runOnce
**As a** user, **I want** large model downloads to wait for an unmetered network unless I say otherwise, **so that** I do not burn mobile data.
**Acceptance Criteria**:
- Defaults to unmetered.
  - *Given* a fresh install, *When* the model download is requested, *Then* `runOnce(TaskKey(model-download,""), Unmetered)` enqueues `OneTimeWorkRequest` under `DepthModelDownloader.UNIQUE_WORK_NAME` with `UNMETERED` + storage-not-low.
- Constraints come from the store, defaulting to Unmetered (Phase 4 repair, B2).
  - *Given* `FakeSettings` with NO `task_entry_*` for `(model-download, "")`, *When* `DepthModelDownloader.downloadModel()` runs, *Then* it resolves `TaskSettingsStore.read(TaskKey(model-download,"")).constraints` (the spec default `Unmetered`), passes them to `runOnce`, and read-back of the enqueued request shows `UNMETERED`; *Given* the store holds `TaskOverrides(schedule=null, constraints(Any))`, *Then* the enqueued request is `CONNECTED`; *Given* the store read fails, *Then* it falls back to `Unmetered` (fail closed to the safe default) and never blocks the user's tap. `enabled` is not read.
- Works from an empty settings state, end to end (Phase 4 repair, P1-1).
  - *Given* a fresh install (`FakeSettings` empty, no `TaskRegistry` entries other than those the Application registers, WorkManager `TestDriver`), *When* the model download is requested on an unmetered network, *Then* `runOnce` -> `TestDriver.setAllConstraintsMet` -> `TaskWorker` runs the REAL `ModelDownloadTask` body against a fake HTTP source, the model file exists on disk and `ModelState` is `Ready` (never `Failed("disabled")` or stuck `Downloading`). Task 3.2.1h.
- User-facing download states map from pure presentation (Phase 4 repair, nitpick S6).
  - *Given* the pure `ModelDownloadPresentation` over (`WorkInfo` state, `KEY_ERROR`, last network snapshot), *Then* it yields: queued + metered/unknown -> "Waiting for an unmetered network" with "Download now anyway" and "Cancel"; queued + offline -> "Offline - will download when you are online" with "Cancel"; running -> progress with "Cancel"; failed + retryable -> "Download failed. It will retry automatically."; failed terminally -> "Download failed after 5 tries" with "Try again" (reruns `runOnce`); every state has at least one exit (UX-20).
- Remaining S6 states (triad repair 1, UX-38).
  - *Given* the pure `ModelDownloadPresentation`, *Then* it also yields: running -> "Downloading the depth model... N%" (indeterminate when size unknown) with "Cancel"; cancelled -> "Download cancelled." with "Download again" (reruns `runOnce` with the stored default, partial data kept); *Given* Android 13+ with `POST_NOTIFICATIONS` denied and a user-initiated download, *Then* the download still runs (the foreground notification is simply absent), and the row shows "Notifications are off, so you won't see download progress in the notification shade. The download still runs; progress shows here." with no permission re-prompt; every state keeps an exit.
- Manual override uses REPLACE (rev1).
  - *Given* a metered network and a queued download, *When* the user taps "Download now anyway", *Then* `runOnce(key, constraints(Any), OneShotConflict.Replace)` replaces the request (`ExistingWorkPolicy.REPLACE`, new work id) and the constraint is `CONNECTED`; the default `Keep` call from a second plain request leaves the existing request untouched. The override is per request and never persisted.
- A skipped one-shot never hangs the waiter (Adv-5 / B5).
  - *Given* the download request runs while `isMetered=true` or `null`, *When* `TaskWorker` skips it, *Then* it retries within the shared budget (`oneShotRetryCap = MAX_ATTEMPTS = 5`, counting skips and real failures together, so at most 5 attempts in total) then ends `FAILED` with `KEY_ERROR` in the output data, and `DepthModelDownloader`'s existing `WorkInfo` collector maps that to `ModelState.Failed("skipped: metered")` so `downloadModel()`'s `awaitTerminal()` resolves (never stays `Downloading`); the worker never writes `ModelState`; a test covers both `metered=true` and `isMetered == null` with the WorkManager `UNMETERED` constraint satisfied.
- Resume and attempt cap unchanged.
  - *Given* `DepthModelDownloadWorkerTest` scenarios (partial `.part` resume, 5 attempts), *When* run through the `ModelDownloadTask`, *Then* the same results; `Failed(retryable=true)` becomes `Result.retry()` with exponential backoff.
- Foreground notification only when user-initiated.
  - *Given* a user-initiated `runOnce`, *When* the worker runs, *Then* `TaskForegroundProvider` supplies the dataSync notification; a scheduled run does not.
- Shows in the jobs list with `JobKind.ModelDownload`.
  - *Given* the request, *When* `WorkManagerJobRegistry` reads tags, *Then* kind `ModelDownload` and the title tag are present.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/{ModelDownloadTask.kt,DepthModelDownloader.kt,DepthModelDownloadWorker.kt}`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/ModelDownloadTaskTest.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/jobs/schedule/OneShotSkipTest.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/ModelDownloadEndToEndTest.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/ModelDownloadUiTest.kt` (Compose; Gradle-only), `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/ModelDownloadPresentation.kt`, `AllAndroidUnitTests.kt`

##### Task 3.2.1a: `ModelDownloadTaskTest` and `ModelDownloadUiTest` (test first) (M)
- The criteria above (constraints-from-store read-back, override REPLACE, skip-never-hangs for `true` and `null`, resume/attempt cap, foreground only when user-initiated, jobs-list tags, presentation mapping); reuse `DepthModelDownloadWorkerTest` fixtures. Add `ModelDownloadUiTest` (Robolectric Compose, Gradle-only, NOT registered in `AllAndroidUnitTests`) for UX-19: queue an unmetered request on a metered snapshot, assert the row text and both buttons, tap "Download now anyway", assert the `Replace` call and that the stored default is still `Unmetered`; also the S6 failure/offline states each expose an exit (UX-20). Evidence via `./gradlew :kmp:testDebugUnitTest --tests '...ModelDownloadTaskTest' --tests '...ModelDownloadUiTest'`; register the non-Compose tests in `AllAndroidUnitTests` only if they run under the Bazel Android harness.
- Files: `ModelDownloadTaskTest.kt`, `OneShotSkipTest.kt`, `ModelDownloadUiTest.kt`, `AllAndroidUnitTests.kt`
##### Task 3.2.1b: Extract the download body (M)
- Move the download/resume logic to `ModelDownloadTask : BackgroundTask` returning `TaskRunResult`; `DepthModelDownloadWorker` delegates until removed; behaviour identical. No `onTerminalFailure` callback (rev2, R-C5): a skipped-out one-shot returns `Result.failure(workDataOf(KEY_ERROR to ...))` from `TaskWorker` and `DepthModelDownloader`'s `WorkInfo` collector (`DepthModelDownloader.kt:57,99-124`) reads `KEY_ERROR` from the output data to produce `ModelState.Failed(reason)`; `ModelDownloadTask`'s `TaskSpec` sets `oneShotRetryCap = DepthModelDownloadWorker.MAX_ATTEMPTS`.
- Files: `ModelDownloadTask.kt`, `DepthModelDownloadWorker.kt`
##### Task 3.2.1c: `DepthModelDownloader` schedules through the port (S)
- Replace its own `enqueueUniqueWork` with `scheduler.runOnce`; keep `UNIQUE_WORK_NAME`; replace its private `workManagerOrNull()` copy (`DepthModelDownloader.kt:128`) with the shared function from Task 2.2.1f.
- Files: `DepthModelDownloader.kt`
##### Task 3.2.1d: Register task + foreground provider (S)
- Register ONLY in `SteleKitApplication.onCreate` (adversarial B4; no `MainActivity` registration); provider reuses the existing notification code. Serialized last among root-file edits (Serialization rule).
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt`, `TaskForegroundProvider.kt`
##### Task 3.2.1e: "Download now anyway" action wiring (S)
- Hook the existing download UI trigger to `runOnce(..., constraints(Any), OneShotConflict.Replace)`; no new screen; includes the S6 "Cancel" and "Try again" states from `design/ux.md` (Cancel reuses the jobs-list cancel path; confirm with the existing jobs UI first), rendered from `ModelDownloadPresentation` (Task 3.2.1f) and covered by `ModelDownloadUiTest` (Task 3.2.1a).
- Files: the existing model-download UI composable (locate with `sg --pattern 'DepthModelDownloader' --lang kotlin`), `ModelDownloadUiTest.kt`, BUILD if needed
##### Task 3.2.1f: `ModelDownloadPresentation` (S) (Phase 4 repair, nitpick S6/S8)
- Pure mapping from (work state, `KEY_ERROR`, network snapshot, attempt count) to the S6 copy and actions listed in the Story AC; the "Waiting for an unmetered network" string is a shared constant also used by the LFS deferral status (Story 3.2.2, S8). Unit test with the full state table (test first), plain `kotlin.test` in androidUnitTest.
- Files: `ModelDownloadPresentation.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/ModelDownloadPresentationTest.kt`, `AllAndroidUnitTests.kt`
##### Task 3.2.1g: `DepthModelDownloader` resolves constraints from the store (S) (Phase 4 repair, B2)
- Before `runOnce`, read `TaskSettingsStore.read(TaskKey(model-download,"")).constraints` (never `enabled`), default `Unmetered` on no entry or a failed read, and pass them to `runOnce` (the adapter writes `KEY_NETWORK_POLICY` from them; "Download now anyway" passes `Any` explicitly and is never persisted). Read-back test of the enqueued request in `ModelDownloadTaskTest` (store empty -> UNMETERED; store Any -> CONNECTED; throwing store -> UNMETERED). Serialized after 3.2.1c (same file).
- Files: `DepthModelDownloader.kt`, `ModelDownloadTaskTest.kt`
##### Task 3.2.1h: End-to-end test from empty Settings (M) (Phase 4 repair, P1-1)
- `ModelDownloadEndToEndTest` (Robolectric, `WorkManagerTestInitHelper` `TestDriver`, fake HTTP): fresh empty `FakeSettings`, real `WorkManagerTaskScheduler` + `TaskWorker` + `ModelDownloadTask` + `DepthModelDownloader`; `downloadModel()` -> `TestDriver.setAllConstraintsMet` -> worker runs -> model file exists and `ModelState.Ready`. A second case with a stored `enabled=false` entry also downloads (`enabled` is periodic-only). Run: `./gradlew :kmp:testDebugUnitTest --tests 'dev.stapler.stelekit.platform.ml.ModelDownloadEndToEndTest'` and paste output.
- Files: `ModelDownloadEndToEndTest.kt`, `AllAndroidUnitTests.kt`

#### Story 3.2.2: LFS shares NetworkPolicy
**As a** user, **I want** one network rule, **so that** LFS and scheduled work agree.
**Acceptance Criteria** (rev1: all touched files named; Desktop semantics decided; explicit `online` arg):
- `deferOnMetered` maps to the shared gate via the explicit helper.
  - *Given* `LfsTransferPolicy(deferOnMetered=true)` and `isMetered=true` on a repository with `lfsMeteredDetectionSupported=true`, *When* the transfer is evaluated, *Then* it defers; the code calls `NetworkGate.evaluateMetered(Unmetered, isMetered)` (documented as `online = true`, since LFS runs inside an already-online sync), so the equivalence to `evaluate(Unmetered, online=true, isMetered=true) == Skip(MeteredNetwork)` is explicit.
- Unknown no longer proceeds (Android).
  - *Given* `deferOnMetered=true`, `lfsMeteredDetectionSupported=true` and `isMetered=null` (cold read throws `SecurityException` or `ConnectivityManager` absent), *When* evaluated, *Then* it defers (fail closed); previously the `?: false` default proceeded (behaviour change, noted in the PR). The metered read comes from the shared `AndroidNetworkSnapshotProvider` (Task 2.2.2e), so LFS and the worker agree.
- Desktop semantics unchanged (adversarial C9 decision).
  - *Given* a JVM repository with `lfsMeteredDetectionSupported=false` (the interface default at `git/GitRepository.kt:102`; the JVM monitor always returns `null`), *When* `deferOnMetered=true`, *Then* LFS downloads are NOT deferred (today's behaviour: `LfsRepositorySupport.isMetered` defaults to `{ false }`); the null-fails-closed rule applies only when `meteredDetectionSupported` is true, so Desktop users never get permanently blocked downloads. `LfsRepositorySupport` gains `meteredDetectionSupported` and a nullable `isMetered: () -> Boolean?`.
- Settings key unchanged.
  - *Given* `git_lfs_defer_on_metered=true` in `Settings`, *When* `LfsTransferPolicySource.current()` reads, *Then* `deferOnMetered=true`.
- S8 labels map from shared constants (Phase 4 repair, nitpick S8).
  - *Given* `LfsTransferSection` and the S2 network option, *When* their label strings are compared, *Then* the toggle label is exactly "Unmetered networks only" with the same supporting line as S2's option (one shared constant), the key stays `git_lfs_defer_on_metered`, and an LFS deferral status renders "Waiting for an unmetered network" (the constant shared with Task 3.2.1f); no string "Wi-Fi only" remains anywhere in settings or LFS (asserted by the UX-6 scan in `ScheduledSyncPresentationTest`).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/lfs/LfsTransferPolicy.kt`, `kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/git/lfs/LfsRepositorySupport.kt` (lines 45 and 144), `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`, existing `LfsTransferPolicyTest.kt` (`it.isMetered = { metered }` at line 81 must compile with the nullable type), `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/lfs/LfsNetworkGateTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel` (`business_common_tests`), `AllBusinessTests.kt`

##### Task 3.2.2a: `LfsNetworkGateTest` (test first) (S)
- The four criteria (including a Desktop-unsupported case that proves no deferral on `null`); existing `AndroidGitRepositoryLfsGuardTest` and `LfsTransferPolicyTest` must stay green; register the new test in `business_common_tests` + `AllBusinessTests`.
- Files: `LfsNetworkGateTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`
##### Task 3.2.2b: Route LFS through `NetworkGate` (S)
- `LfsTransferPolicy`/`LfsRepositorySupport` evaluate via `NetworkGate.evaluateMetered`; `AndroidGitRepository.kt:85-93` sets `lfs.isMetered` from the shared snapshot provider with the nullable result (remove `?: false`) and `lfs.meteredDetectionSupported = true`; update `LfsTransferPolicyTest.kt:81`.
- Files: `LfsTransferPolicy.kt`, `LfsRepositorySupport.kt`, `AndroidGitRepository.kt`, `LfsTransferPolicyTest.kt`
##### Task 3.2.2c: Settings copy alignment (XS)
- `LfsTransferSection` label to "Unmetered networks only" (string constant shared with the S2 network option; layout untouched) and the deferral status text "Waiting for an unmetered network" from the shared constant.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/LfsTransferSection.kt`

---

## Phase 4: UI

### Epic 4.1: Settings, status, run log
**Goal**: Users set interval, jitter, network policy and enabled per graph per kind, see schedule lateness and skip reasons, and get an actionable restricted-background hint.

#### Story 4.1.1: Per-graph, per-kind Settings UI
**As a** user with several graphs, **I want** to set each graph's sync schedule and network rule, **so that** each behaves as I choose.
**Acceptance Criteria**:
- Extends the existing section; no new top-level screen unless needed.
  - *Given* the existing `ScheduledSyncSection`, *When* rendered for graph "Work notes", *Then* it shows the master switch, interval presets (15 min, 30 min, 1 h, 2 h, 6 h, daily; Desktop also 5 min), a network choice ("Any network" / "Unmetered networks only"), and an "Advanced" disclosure with "Spread out start times: Off / A little / A lot" (0 / 10% / 25% of the interval).
- Disabled with a reason.
  - *Given* Desktop capabilities (`supportsUnmetered=false`), *When* rendered, *Then* "Unmetered networks only" is disabled with "Not available on desktop: SteleKit can't tell if a network is metered"; *Given* Android (`minIntervalMinutes=15`), *Then* the 5 min radio is NOT listed (no permanently disabled control) and a helper line reads "Android runs at most every 15 minutes." (triad repair 1; supersedes the earlier disabled-radio wording; Desktop keeps 5 min enabled).
- Effective value surfaced, derived per adapter (Phase 4 repair, pre-mortem #5 and nitpick UX-11).
  - *Given* interval 60 and jitter "A little" (6 min) on an adapter whose `describe(key)` reports `minGapMinutes=60, maxGapMinutes=66` (Desktop in-process), *When* Advanced is open, *Then* the summary reads "Runs 60-66 min apart" (about every hour). On the WorkManager adapter the copy is built from the `describe` gap fields Spike 0.1.1 recorded (flex runs fall in the last `flex` minutes of each period, so the gap is NOT `interval..interval+jitter`); the pending default wording is "About every hour; start times vary by up to 6 min", finalised from the spike, never from a global `interval + offset` formula. When `describe.appliesAfterNextRun` is true (UPDATE retains the existing next-run time) the summary adds "Takes effect after the next run".
  - *Given* Android with interval 5 min requested, *Then* the clamp is shown as "Runs every 15 min on this device" (UX-11; the existing idiom at `ScheduledSyncSection.kt:176`), and *Given* Desktop, *Then* the helper "Syncs with the remote while SteleKit is open." (the existing `DESKTOP_HELPER`, `ScheduledSyncSection.kt:68`; `ux.md` is aligned to it) is shown.
  - *Given* the clamp reason string, *Then* it equals the `ux.md` copy "Android runs at most every 15 minutes" (VERIFIED equal to `ANDROID_FIVE_MINUTE_REASON`, `ScheduledSyncSection.kt:73`); a test pins the constant.
- Accessibility.
  - *Given* the "Work notes" row, *When* TalkBack semantics are read, *Then* one merged node: "Git sync, Work notes, on, about every hour, unmetered networks only"; touch targets >= 48 dp.
- Changes persist and apply.
  - *Given* the user selects "Unmetered networks only", *When* saved, *Then* `TaskSettingsStore` holds `Unmetered` and `apply` is called once.
- Scale, no hard cap (Phase 4 repair, C11).
  - *Given* 25 graphs, *When* the Background tasks list renders, *Then* it shows a one-line summary per kind and graph rows that read "Using default" or "Custom", without 25 expanded cards; the graph rows are a lazy list (virtualized), there is NO hard cap on graphs (work names stay bounded by graphs x kinds), and *Given* 8 or more graphs, *Then* a search field filters the rows (absent below 8); a test seeds 7 and 8 graphs.
- S1 empty state and platform hiding (Phase 4 repair, nitpick).
  - *Given* no graphs registered, *When* Settings > Background tasks renders, *Then* it shows "No graphs yet. Add a graph to schedule syncing." with an "Add graph" button that invokes the existing add-graph handler; *Given* a scheduler with `capabilities.schedulesWork == false` (Noop: iOS, Web), *Then* the whole Background tasks section is hidden (not disabled), while Desktop's `InProcessTaskScheduler` (`schedulesWork=true`) shows it.
- One-shot rows (Phase 4 repair, B2).
  - *Given* the `model-download` row (app-wide, one-shot), *When* rendered, *Then* it shows only the network choice (no interval presets, no Advanced, no enabled switch: `enabled` is periodic-only and v1 has no automatic starts), writes `TaskEntry(enabled=false, overrides=TaskOverrides(schedule=null, constraints))` for an explicit choice, and never touches `apply`.
- Default row has its own representation (rev1, UX plan gap).
  - *Given* the "Default for new graphs" row, *When* the user changes it, *Then* it writes `TaskSettingsStore.writeDefaults(kind, TaskDefaults(...))` (key family `task_default_<kind>`), never a `TaskScope`, so the app-wide `TaskScope("")` (model-download) and the default row cannot collide; a graph with `overrides == null` reads "Using default" and follows the new default, and one with overrides reads "Custom: <summary>" (Story 1.1.4).
- Jitter presets are percentages and are recomputed (rev1, UX plan gap).
  - *Given* "A little" (10%) selected at interval 60 (6 min), *When* the user changes the interval to 120, *Then* the stored `jitterMinutes` becomes 12 and the Advanced summary is recomputed from `describe` (Desktop: "Runs 120-132 min apart"; Android: the spike-derived wording for a 12 min window); presets map `Off=0`, `A little=max(1, round(10% of interval))`, `A lot=max(2, round(25% of interval))`; `presetOf(schedule)` returns the nearest preset when the screen reloads, and a property test over the preset interval list proves `presetOf(minutesFor(i, p)) == p`.
- Failed save reverts (rev1, UX gap).
  - *Given* `TaskSettingsStore.write` or `apply` throws on a change, *When* the user picks a different network option, *Then* the control reverts to the stored value and "Couldn't save. Your change was not applied." shows under it (no apply call, no partially written state).
- Unreadable settings show Retry (rev1, UX gap).
  - *Given* `TaskSettingsStore.read` fails closed, *When* the section renders, *Then* it shows "Couldn't read settings. Background sync is paused until this loads." with a Retry button that re-reads; nothing is scheduled while unreadable.
- Reset to default (triad repair 1, UX-30).
  - *Given* a graph row whose entry has overrides ("Custom: every 6 h"), *When* the user taps "Reset to default", *Then* the overrides are cleared (`enabled` unchanged), `apply` is called once, the row reads "Using default" and the section shows "Back to the default schedule"; the action is absent on a "Using default" row; *Given* the write throws, *Then* the S2 "Couldn't save" revert applies and the overrides are unchanged.
- "Custom: <summary>" format, large font, RTL (triad repair 1, UX-36).
  - *Given* overrides of interval only / interval plus network / three differences, *Then* the row reads "Custom: every 6 h" / "Custom: every 2 h, unmetered networks only" / "Custom: 3 changes" (pure function, commonTest); *Given* 200% font scale and an RTL layout direction, *Then* summary lines and radio labels wrap without clipping, controls stay >= 48 dp, and the merged node description carries the full untruncated text.
- S2 loading state (triad repair 1, UX-32).
  - *Given* `TaskSettingsStore.readResult` has not completed, *When* S2 renders, *Then* placeholder (skeleton) rows show, every control is disabled with the reason "Loading settings...", "Loading settings" is announced once through a polite live region, and no default value is displayed as if stored; on completion the loaded state replaces it, on failure the "Couldn't read settings" state does.
- Success feedback after enabling (triad repair 1, UX-37).
  - *Given* the user turns the switch on, *Then* "First check in about N min" (N = effective, platform-clamped interval; never a clock time) appears as a polite announcement until the first run.
- Accessibility of navigation, search and disabled reasons (triad repair 1, UX-33..35).
  - *Given* drill-in S1 -> kind -> graph and back, *Then* accessibility focus moves to the new screen title (and is announced once) and Back restores focus to the opener; *Given* >= 8 graphs, *Then* the search field has a visible label "Search graphs" and an accessible name, and a no-match query shows "No matches" with a "Clear search" action; *Given* any disabled control, *Then* its reason is exposed through semantics so it announces "<name>, disabled, <reason>" (Robolectric semantics assertion; TalkBack wording on a device is owner item M-7/M-9).
- Desktop radio arrow-key navigation (rev1, UX not-verified item).
  - *Given* the Desktop interval radio group focused, *When* Down/Right is pressed, *Then* selection and focus move to the next enabled option (disabled options skipped) and Up/Left moves back; Space selects. Compose Desktop does not provide this automatically, so the group installs a key handler and a jvmTest asserts it.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/ScheduledSyncSection.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/BackgroundTasksSection.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/ScheduledSyncPresentation.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/settings/ScheduledSyncSettingsTest.kt` (path verified; the plan previously said `ui/settings/`), `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/settings/BackgroundTasksSectionTest.kt` (new; Compose, Gradle-only), `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/settings/ScheduledSyncRadioKeyboardTest.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/AllJvmTests.kt`

##### Task 4.1.1a: Robolectric UI tests (test first) (M)
- Extend `ScheduledSyncSettingsTest.kt` (Robolectric, not jvmTest; Gradle-only, it calls Compose `setContent`) with the criteria incl. failed-save revert, unreadable-settings Retry, default-row vs "Using default"/"Custom", the per-adapter spread copy, the clamp summary and the Desktop helper; add NEW `BackgroundTasksSectionTest.kt` (Robolectric Compose, Gradle-only, not registered in `AllAndroidUnitTests`) for UX-1 (enable in at most 3 taps), UX-5 (25 graphs, one summary per kind, search at 8 and not at 7, lazy rows), the S1 empty state with its Add graph button, hidden section on `schedulesWork=false`, and the one-shot model-download row; add `ScheduledSyncRadioKeyboardTest` in jvmTest with header `// jvmTest-reason: desktop-only` for the arrow-key navigation, run via `scripts/jvm-display-check.sh -- ./gradlew :kmp:jvmTest --tests ...` and register in `AllJvmTests`.
- Files: `ScheduledSyncSettingsTest.kt`, `BackgroundTasksSectionTest.kt`, `ScheduledSyncRadioKeyboardTest.kt`, `AllJvmTests.kt`
##### Task 4.1.1b: Pure presentation for interval/jitter/network labels (S)
- Pure functions in `ScheduledSyncPresentation.kt` (preset list per capabilities, consuming `JitterPreset.minutesFor(interval)` / `presetOf(schedule)` defined in Task 1.1.2b, with recompute on interval change, "about every" copy, and the per-adapter spread summary built from `TaskScheduleInfo` gap fields incl. "Takes effect after the next run") in commonTest with examples plus the round-trip property. `ScheduledSyncPresentationTest.kt` does not exist today (existing coverage is `ScheduledSyncStatusTest.kt`): create it as a new file and register it in `business_common_tests` + `AllBusinessTests`.
- Files: `ScheduledSyncPresentation.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/ScheduledSyncPresentationTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `AllBusinessTests.kt`
##### Task 4.1.1c: Section UI (M)
- Radios as `selectableGroup` with `Role.RadioButton`, whole-row targets, inner `onClick = null` (existing pattern); master switch stays operable when already on.
- Files: `ScheduledSyncSection.kt`
##### Task 4.1.1d: Background tasks list (M)
- `BackgroundTasksSection.kt` (see also Task 4.1.1f): kinds x graphs summary, per-kind defaults row (reads/writes `task_default_<kind>`, NOT a scope) with "Using default"/"Custom" per graph; model-download row app-wide (`TaskScope("")`). Add to `first-open-screens.txt` and `FIRST_OPEN_SCREENS` in `androidApp/.../FirstOpenSmokeTest.kt` only if it becomes a new screen (prefer a section inside Settings).
- Files: `BackgroundTasksSection.kt`, `kmp/src/businessTest/resources/first-open-screens.txt` (conditional)
##### Task 4.1.1e: Capabilities plumbing (S)
- Settings reads `BackgroundTaskScheduler.capabilities` and `describe(key)` from `StelekitAppPlatformIntegrations` (no default; Desktop reads `InProcessTaskScheduler` capabilities). Serialization rule applies to `StelekitAppDependencies.kt`.
- Files: `StelekitAppDependencies.kt`, `ScheduledSyncSection.kt`
##### Task 4.1.1f: Empty state, search, platform hiding, summaries (S) (Phase 4 repair, C11/nitpicks)
- In `BackgroundTasksSection.kt`: lazy graph rows (no hard cap), search field at >= 8 graphs, the S1 "No graphs yet" state with Add graph, whole-section hide on `capabilities.schedulesWork == false`, the model-download one-shot row (network only), and the UX-11 clamp/Desktop-helper summary lines from the existing constants. Tests are in Task 4.1.1a (`BackgroundTasksSectionTest`, `ScheduledSyncSettingsTest`).
- Files: `BackgroundTasksSection.kt`, `ScheduledSyncSection.kt`
##### Task 4.1.1g: Reset to default, loading state, accessibility and large-font/RTL (M) (triad repair 1)
- Implement the five Story 4.1.1 criteria added by triad repair 1 (Reset to default, "Custom: <summary>" pure formatter, S2 loading skeleton with disabled-with-reason controls, "First check in about N min", focus/title announcement, labelled search with "No matches", disabled-reason semantics, 200% font and RTL wrapping). Test first: extend `ScheduledSyncSettingsTest` and `BackgroundTasksSectionTest` (Robolectric Compose, Gradle-only) and `ScheduledSyncPresentationTest` (formatter examples plus a property that the summary lists only differing values); the Android 5 min radio is dropped from the preset list when `minIntervalMinutes >= 15` and replaced by the helper line.
- Files: `ScheduledSyncSection.kt`, `BackgroundTasksSection.kt`, `ScheduledSyncPresentation.kt`, `ScheduledSyncSettingsTest.kt`, `BackgroundTasksSectionTest.kt`, `ScheduledSyncPresentationTest.kt`

#### Story 4.1.2: Status line "Late", skip reasons, restricted-background hint
**As a** user, **I want** a calm "Late" state and a useful hint, **so that** I know the schedule is slipping and what to do.
**Acceptance Criteria**:
- Precedence and wording.
  - *Given* last success 3 h ago, interval 1 h, multiple 2, no pause, *When* the status renders, *Then* "Hasn't run in 3 h" (not "behind"); precedence Paused > Disabled-with-reason > Can't-read-settings > Late > CheckInProgress > Checked.
- Day granularity and busy/editing copy (triad repair 1, UX-36/39).
  - *Given* last success 3 days ago, *Then* "Hasn't run in 3 days" (hours up to 48 h, whole days after, minutes under 1 h); *Given* the most recent scheduled run was skipped because the user is editing, *Then* "Sync paused while you edit. It continues when you stop."; *Given* skipped because another sync or clone is running, *Then* "Another sync is running."; both fall back to Checked/next when the cause clears.
- Single precedence source (triad repair 1, UX-8): the order above is the one in `ux.md` S3, UX-8 and the `statusLine_should_followPrecedence...` test; no document states a shorter chain.
- Deliberate skips are not blamed on Android.
  - *Given* the last runs were `Skipped("vault-locked")`, *When* the status renders, *Then* "Locked. Sync resumes after you unlock." and no restricted hint.
- Hint by reason.
  - *Given* `RestrictionReason.BatterySaver`, *Then* "Battery saver is on. Background sync resumes when you turn it off or charge."; `StandbyRestricted` / `AppRestricted` show an "Open battery settings" button that launches `ACTION_APPLICATION_DETAILS_SETTINGS`; Late with probe `None` shows the softer "Some phones stop background apps. Check battery settings."
- Live-region discipline.
  - *Given* the section refreshing `now` every 30 s with unchanged state, *When* recomposed, *Then* the polite live region text is unchanged (no re-announcement).
- SAF/fetch-only.
  - *Given* a SAF graph, *When* the status renders, *Then* "This graph can only check for updates in the background" with Sync now available.
- No false Late while the app syncs in the foreground (rev1, Arch-3).
  - *Given* the app open, `TaskRunLog` showing only old skips, and `SyncStalenessStore.lastMergedAtMs` 10 min ago (foreground loop), *When* the status renders, *Then* "Last synced 10 min ago" (Checked), never "Hasn't run in N h"; the status model gets its last-success only from `lateness(key, schedule, GitLastSuccessSource, now)`.
- Unreadable background settings are not Late (Phase 4 repair, pre-mortem #4).
  - *Given* `SettingsReadHealth.lastFailureMs()` is newer than the last success and the last run record is `Skipped("settings-unreadable")` or absent, *When* the status renders, *Then* the line is "Can't read settings. Background sync may be paused." with a Retry action (re-reads the store and clears the marker on success), placed between Disabled-with-reason and Late in the precedence, and never "Hasn't run in N h".
- Battery-settings launcher failure (rev1, UX gap S4).
  - *Given* the launcher's `startActivity` throws `ActivityNotFoundException` or `SecurityException`, *When* the user taps "Open battery settings", *Then* the row text becomes "Open Android Settings > Apps > SteleKit > Battery." (non-clickable, selectable) and a `Logger` warning is recorded; the row clears when the cause clears.
- Existing actions are reused, not rebuilt (rev1, UX gap).
  - *Given* the S3 actions "Resume", "Unlock" and the conflict-review button, *Then* they call the existing handlers (the sync-status-sheet "Resume" that clears the 3-failure pause, the vault `Unlock` flow, and the conflict-review navigation already wired in `GraphContentGitSyncSetup`/`SyncStatusSheet`); Task 4.1.2e lists each handler by name after locating it, and a test asserts each action invokes it once. A "Cancel" for a queued model download reuses the jobs-list cancel path (Task 3.2.1e).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/ScheduledSyncPresentation.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/ScheduledSyncSection.kt`, `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/BatterySettingsLauncher.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/ScheduledSyncPresentationTest.kt` (new file, shared with Task 4.1.1b), `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/settings/ScheduledSyncStatusActionsTest.kt`

##### Task 4.1.2a: Presentation tests (test first) (S)
- New sealed states `Late(sinceMs, cause)` and `SkippedBecause(reason)` added to `scheduledSyncLine`; tests for precedence, wording, the three restriction reasons, and the foreground-success-not-Late case. Register in `business_common_tests` + `AllBusinessTests` (already done by Task 4.1.1b if same file).
- Files: `ScheduledSyncPresentationTest.kt`
##### Task 4.1.2b: Extend `scheduledSyncLine` (S)
- Add states; leave the existing `Checked/Paused/...` strings untouched so the old tests stay green.
- Files: `ScheduledSyncPresentation.kt`
##### Task 4.1.2c: Hint row + Android launcher (M)
- Non-modal info row with action; no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; launcher failure fallback text per the AC (launcher returns a result the row renders).
- Files: `ScheduledSyncSection.kt`, `BatterySettingsLauncher.kt`
##### Task 4.1.2e: Reuse-handler tests (S) (rev1)
- Locate and name the existing Resume, Unlock and conflict-review handlers (`sg --pattern 'Resume' --lang kotlin` under `git/` and `ui/`), wire the S3 action buttons to them, and add `ScheduledSyncStatusActionsTest` (Robolectric, Gradle-only) plus a launcher-failure test with a throwing fake launcher.
- Files: `ScheduledSyncSection.kt`, `ScheduledSyncStatusActionsTest.kt`
##### Task 4.1.2d: Doc check for copy (XS)
- Confirm the Android 14/15 foreground-service limit statements against current docs before any number appears in copy; default is copy with no numbers.
- Files: none (note in PR)

#### Story 4.1.3: Logs screen shows run records
**As a** user, **I want** each scheduled run visible in Logs, **so that** I can see what happened without `adb logcat`.
**Acceptance Criteria**:
- Run records surface.
  - *Given* a persisted record `Skipped("metered")` for `(git-sync,"g1")`, *When* the Logs screen opens, *Then* a row reads "Git sync / <graph display name> / skipped: metered" with the start time (display names per UX-31, never raw ids; fixture graph "g1" has display name "Work notes").
- Foreground and background records both appear (Phase 4 repair, B4).
  - *Given* one `Foreground` record (a Desktop `PeriodicSyncDriver` tick) and one `Background` record (a worker run) for `(git-sync,"g1")`, *When* the Logs screen opens, *Then* both rows show, newest first, each labelled the same way ("Git sync / Work notes / success"); the Desktop Logs screen is therefore not empty for a user whose only sync is the in-app loop.
- Display names and date+time (triad repair 1, UX-31).
  - *Given* a record for `(git-sync, g1)` where g1's display name is "Work notes", *Then* the row reads "Git sync / Work notes / skipped: metered" (task label from the registry spec, graph display name from the graph list; never raw ids, and an app-wide task omits the graph segment); a record from today shows time only (relative "12 min ago" within the last hour, also as the screen-reader text), an earlier one shows date plus time ("Nov 9, 21:15"); the raw ids remain only in the `Logger` line.
- Missing records handled.
  - *Given* an empty log, *When* opened, *Then* no row and no crash.
- Unreadable run log shows Retry (rev1, UX gap S5).
  - *Given* `TaskRunLog.recent` fails (throwing `Settings`), *When* the Logs screen opens, *Then* the section shows "Couldn't read run history" with a Retry button that re-reads; no crash, and the rest of the Logs screen renders.
**Files**: the existing Logs screen composable (locate with `sg --pattern 'PreviousSessionLog' --lang kotlin`), `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/TaskRunLogRowsTest.kt` (Compose; Gradle-only)

##### Task 4.1.3a: Test (test first) (S)
- Row text, display names (task label plus graph display name, not ids), today-vs-earlier time formatting, empty case, and unreadable-log Retry.
- Files: `TaskRunLogRowsTest.kt`
##### Task 4.1.3b: Rows (S)
- Read `TaskRunLog.recent` for all registered kinds x graphs (no key scan; keys derived from registry x graph list).
- Files: the Logs screen composable

---

## Phase 5: Gates, docs, device verification

### Epic 5.1: Wiring gates, docs, owner verification
**Goal**: Gates stay green, docs match reality, the real-device story is run by the owner.

#### Story 5.1.1: Wiring gates
**As a** maintainer, **I want** every new seam implementation reachable and proven, **so that** `WiringCompletenessTest` and the catalog stay green.
**Acceptance Criteria**:
- Seam listed.
  - *Given* `WiringAudit.SEAMS`, *When* inspected, *Then* it contains `"BackgroundTaskScheduler"` (and still `"BackgroundSyncScheduler"` while the shim exists).
- Every implementation constructed or allowlisted.
  - *Given* `WorkManagerTaskScheduler`, `InProcessTaskScheduler`, `NoopTaskScheduler`, *When* `WiringCompletenessTest` runs, *Then* each appears as `Name(` in a root (`MainActivity.kt` for WorkManager, Desktop `Main.kt` for in-process, `wasmJsMain/browser/Main.kt` / iOS root / Desktop `App.kt` for Noop; NOT as a field default) and no allowlist line is needed; `FakeBackgroundTaskScheduler` is test-only.
- Both Android roots construct without double registration (rev1, adversarial B4).
  - *Given* `SteleKitApplication.onCreate` registration followed by `MainActivity` root construction run twice in sequence (rotation/process restore), *When* the wired fixture builds both, *Then* nothing throws and `TaskRegistry.kinds()` has exactly `git-sync` and `model-download` (`TaskRegistryBothRootsTest`, Task 2.2.2c; re-asserted here through `WiredAppFixture`).
- Catalog entries and entry-point tests.
  - *Given* `wiring-catalog.txt`, *When* read, *Then* it has `BackgroundTaskScheduler  TaskSchedulerAndroidEntryPointTest  effect:task-work-enqueued` and `BackgroundTaskScheduler  TaskSchedulerDesktopEntryPointTest  effect:in-process-task-tick`; each test calls `ObservableEffect.assert("<label>")` about read-back runtime state (work request constraints; a fired tick).
- Hygiene gates.
  - *Given* the new tests, *When* `TestHygieneAuditTest` and `TestPlacementAuditTest` run, *Then* no new baseline lines.
**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/wiring/WiringAudit.kt`, `kmp/src/businessTest/resources/wiring-catalog.txt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/wiring/TaskSchedulerAndroidEntryPointTest.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/wiring/TaskSchedulerDesktopEntryPointTest.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/wiring/WiredAppFixture.kt`, `AllJvmTests.kt`, `AllAndroidUnitTests.kt`, `AllBusinessTests.kt` (if a businessTest class is added)

##### Task 5.1.1a: Entry-point tests (test first) (M)
- Drive `WiredAppFixture`/`DesktopWiredAppFixture` with the real root construction; assert effects by read-back.
- Files: the two entry-point tests, `WiredAppFixture.kt`, `DesktopWiredAppFixture.kt`
##### Task 5.1.1b: `SEAMS` + catalog (S)
- Update `WiringAudit.SEAMS` and its Glossary comment; add catalog lines.
- Files: `WiringAudit.kt`, `wiring-catalog.txt`
##### Task 5.1.1c: Bazel audit (S)
- Run `bazel test //kmp:business_tests`; confirm `AllBusinessTests` lists every new businessTest and `business_common_tests`-backed class (`AllBusinessTestsCompletenessTest` green) and that `JobsScheduleImportDirectionTest` reports a non-zero scanned-file count (Task 1.1.5a). Final audit: every Test Infrastructure Rule 2 registration exists (grep each new test class name in `AllBusinessTests.kt`/`AllJvmTests.kt`/`AllAndroidUnitTests.kt`).
- Files: BUILD files as needed
##### Task 5.1.1d: Run the full local gate set (S)
- `timeout 30m bazel test //kmp:business_tests`; `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY` (a bare run is unverified); `timeout 30m bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android` (covers ONLY the classes registered in `AllAndroidUnitTests`); AND the Gradle run that actually covers every Robolectric/Compose test: `./gradlew :kmp:testDebugUnitTest` (CI runs this); `./gradlew detekt` if Gradle-only lint applies. Run `bazel shutdown` before ending the worktree session.
- Files: none

#### Story 5.1.2: Docs and ADR status
**As a** future maintainer, **I want** the architecture notes to name the port, **so that** the next task uses it.
**Acceptance Criteria**:
- CLAUDE.md gains a short "Background task scheduling" rule block.
  - *Given* the repo `CLAUDE.md`, *When* read, *Then* it states: consumers use `BackgroundTaskScheduler` in `jobs.schedule`, no `git` imports there, fail-closed `NetworkGate`, git-sync keeps `stelekit_git_sync_<graphId>`, and the regression tests by name.
- ADRs updated.
  - *Given* ADR-001..006, *When* read, *Then* each has a status matching what was proven (Accepted or Accepted-with-fallback) and the spike result in ADR-002.
**Files**: `CLAUDE.md`, `project_plans/background-task-scheduling/decisions/ADR-00*.md`

##### Task 5.1.2a: CLAUDE.md section (S)
- Short block (one screen) following the cross-graph section style; include the test-registration rule (`AllBusinessTests`/`AllJvmTests`/`AllAndroidUnitTests`), the lazy-migration rule, and the fail-closed `KnownScopes` rule.
- Files: `CLAUDE.md`
##### Task 5.1.2b: ADR statuses (XS)
- Update each ADR header with the outcome.
- Files: `ADR-001..006`

#### Story 5.1.3: Device verification (owner-run)
**As a** product owner, **I want** the reliability claim checked on real devices, **so that** the "Late" state and run log are trusted.
**Acceptance Criteria**:
- `Pixel_Fold` AVD run reads persisted logs.
  - *Given* the AVD with a git graph enabled at 15 min, `Unmetered`, *When* the owner runs `adb shell cmd jobscheduler run` (no logcat) and sets a Rare standby bucket (`adb shell am set-standby-bucket`) and later opens the app, *Then* the Logs screen shows `TaskRun` records with scheduled-vs-actual start and the Late state appears once last success exceeds 2x the interval.
- Wi-Fi-only holds on a metered connection.
  - *Given* a device on mobile data (metered), policy `Unmetered`, *When* the worker runs, *Then* the run log shows `Skipped:metered` and the remote receives no request (checked via remote access log or fetch timestamp).
- OEM physical device.
  - *Given* a physical Xiaomi/Samsung-class device with the app sleeping, *When* 24 h of scheduled runs pass, *Then* the owner records run count and whether "Late" appeared; the result is added to the PR, not asserted by CI.
- Keystore-backed settings on a cold worker (rev1, adversarial C11; Spike 0.1.2 only proved the plain-prefs fallback). An EARLY run of this same check happens right after Story 2.2.2 (Task 2.2.2h); this item is the final confirmation on the release build.
  - *Given* a real device with `EncryptedSharedPreferences` (Keystore) and the app process killed, *When* a scheduled `TaskWorker` runs in a cold process, *Then* `TaskSettingsStore.read` returns the stored config, `TaskRunLog.append` persists a record readable in the Logs screen after the next launch, and the owner notes the run-log write latency; a failure here is reported before the release PR is promoted.
- Owner device checklist for UX items that cannot be automated (triad repair 1; `validation.md` M-6..M-9).
  - *Given* a Desktop build, *When* the owner opens the settings dialog from a control and presses Esc, *Then* it closes and focus returns to the opener (M-6, UX-29); *Given* TalkBack on a device, *When* the owner reads a graph row, a disabled control and a drill-in/back transition, *Then* wording matches "Git sync, Work notes, on, about every hour, unmetered networks only" and "<control>, disabled, <reason>" and focus lands on the screen title (M-7, M-9, UX-25/33/34/35); *Given* light and dark themes on a device, *Then* Late, Paused and the hint pair text with an icon and read at >= 4.5:1 contrast (M-8, UX-28). Each result is pasted into the PR; none gates merge, all gate promoting the release PR with M-1..M-4.
**Files**: `project_plans/background-task-scheduling/implementation/device-verification.md` (owner fills results; created only when the owner reports)

##### Task 5.1.3a: Verification script (XS)
- List the exact commands and the persisted-log paths (`filesDir/logs/current.log`, `previous.log`, Logs screen) so the owner needs no logcat; add `export ANDROID_HOME=$HOME/Android/Sdk` and the emulator command from CLAUDE.md. Placed in the PR description, not a new doc file.
- Files: none
##### Task 5.1.3b: Owner-run blocker (S)
- Owner runs the AVD and physical-device steps; agent reads the pasted log excerpts and records outcomes in the PR. Gate: promoting the release-please PR waits on this; merging does not.
- Files: PR description

---

## Requirements traceability

| Requirement / success metric | Story |
|---|---|
| Per-graph per-kind interval, jitter, network, enabled; values reach WorkManager (read back) | 1.1.4, 2.2.1, 4.1.1 |
| No scheduled network call on metered when "Unmetered" | 1.1.3, 2.2.2, 3.2.2 |
| Jitter property test within `[0, jitter]` and platform bounds | 1.1.2 |
| Two consumers, zero git types in the port | 1.1.5, 3.1.1, 3.2.1 |
| "Late" state (no false Late from foreground sync) and persisted run log, including GitSyncWorker and foreground-driver records | 1.2.1, 1.2.2, 3.1.1, 3.1.2, 3.1.3, 4.1.2, 4.1.3 |
| Second consumer runs from an empty-settings state (`enabled` periodic-only) | 2.2.2, 3.2.1 |
| Existing behaviour preserved (policy, first-sync, edit lock, vault, conflict, clone exclusion, retry owner) | 3.1.1, 3.1.2 |
| Migration of `scheduled_sync_enabled` without silent flip, on every platform | 1.1.4, 3.1.3, 3.1.4 |
| Orphaned work for removed graphs, never cancelling live work | 2.2.2, 2.2.3, 3.1.4 |
| Desktop adapter (proven by contract suite; git stays on `PeriodicSyncDriver` this release); iOS stub | 2.1.1, 2.1.2, 3.1.3 |
| Wiring gates, Bazel/suite registration | 1.1.5, 1.1.6, 5.1.1 |
| Device verification (owner) | 5.1.3 |

## Follow-ups (not in this plan)
Move Desktop git from `PeriodicSyncDriver` onto `InProcessTaskScheduler` once a status-line and failure-pause parity test exists; delete the deprecated shim (`BackgroundSyncSchedulerFactory`, old `GitSyncWorker` class name) once the contract suite is green on all adapters for one release; migrate `ProjectDigest`; schedule LFS as its own task; BGTaskScheduler adapter when iOS git is real; evaluate an androidx.work 2.12.0 bump separately.
