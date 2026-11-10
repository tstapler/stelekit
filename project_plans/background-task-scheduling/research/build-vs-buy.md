# Build vs Buy: KMP background-task scheduling port

Date: 2026-11-10. Confidence labels: VERIFIED = source opened this session; INFERRED = reasoning, not checked.

## Summary verdict

Adopt `androidx.work` (already a dependency) as the only Android engine. Write the port, the jitter/backoff policy and the settings model in-repo as pure, property-tested commonMain code. Do not adopt a KMP wrapper, Quartz, or a push service. Evolve the existing in-repo pieces rather than replacing them.

## Facts from the repo (VERIFIED by reading files)

- Elastic-2.0 project (`LICENSE`); source headers say `SPDX-License-Identifier: Elastic-2.0`.
- `kmp/build.gradle.kts:339` already pins `androidx.work:work-runtime-ktx:2.9.1`; work-testing is also present (line ~378).
- `BackgroundSyncScheduler` (`kmp/src/commonMain/.../git/BackgroundSyncScheduler.kt`, 39 lines): `schedule(intervalMinutes)` / `cancel()` plus `BackgroundSyncSchedulerFactory` and `BackgroundRestrictionProbe`. Git-named, no jitter or constraints.
- `WorkManagerSyncScheduler` (`kmp/src/androidMain/.../git/WorkManagerSyncScheduler.kt`, 396 lines): `PeriodicWorkRequestBuilder`, hard-coded `NetworkType.CONNECTED` (lines 51-53), unique work `stelekit_git_sync_<graphId>`, `pauseFor`/`resumeFor`, `GitSyncWorker` with a fast path (live service) and a slow path (`runSlowPathFetch`). Retry deliberately owned elsewhere (ADR-002: `Result.failure()`, no `Result.retry()`).
- `ScheduledSyncPolicy` (85 lines): pure `decide()` -> Run / FetchOnly / Skip(reason), and `backoffMs(failures)` (1, 2, 4 ... min, cap 30 min, no jitter).
- `PeriodicSyncDriver` (252 lines): coroutine `delay` loop with `tickLock`, `ScheduledSyncStatus`, `TickOutcome`/`SkipReason`. Git-specific only in its `TickOutcome` types and `DomainError.GitError` converter.
- No `jitter` code in commonMain; the port has no iOS adapter (iOS git is a `NotSupported` stub per requirements).

## Platform facts (VERIFIED, https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)

- Minimum periodic interval is 15 minutes; the interval is a minimum, actual run time depends on constraints and system optimisation.
- `PeriodicWorkRequest` supports a flex window (last `flexInterval` of each period), `setInitialDelay` delays only the first run; no native random jitter.
- Constraints include `NetworkType.UNMETERED`, `RequiresCharging`, `BatteryNotLow`. A constraint unmet within the interval can delay or skip a run.
- Backoff: `LINEAR` or `EXPONENTIAL`, minimum 10 s, default exponential 30 s. Backoff applies only to `Result.retry()`.
- Latest stable `androidx.work` is 2.12.0, released 2026-09-23 (https://developer.android.com/jetpack/androidx/releases/work, VERIFIED from the release table). The repo is on 2.9.1, so a bump is available. License: Apache-2.0 (INFERRED from AndroidX convention; the page fetched did not state it).

## Elastic-2.0 compatibility (INFERRED)

Elastic-2.0 restricts what users of this project may do (hosted-service, license-key circumvention); it does not restrict dependencies. Permissive dependencies (Apache-2.0, MIT, BSD) can be linked and distributed. Copyleft (GPL/AGPL) libraries are the risk. Quartz is Apache-2.0 (INFERRED, not fetched). Confirm each candidate's license file before adoption.

## 1. Libraries

### 1a. androidx.work (WorkManager)
- Pros: already in use; Google-maintained (2.12.0 released Sept 2026, VERIFIED); native constraints (UNMETERED, charging, battery), unique work, backoff, test driver (`WorkManagerTestInitHelper`), persistence across reboot; existing tests (`UniqueWork`, `RetryOwner`, `Pause`, `Watchdog`) encode hard-won invariants.
- Cons: Android-only; no jitter; 15-minute floor; Doze/OEM limits remain regardless.
- Verdict: **Recommended** as the Android adapter's engine. Consider bumping 2.9.1 -> 2.12.0 separately with its own CI run.

### 1b. KMP WorkManager wrappers
- `brewkits/kmpworkmanager` (`dev.brewkits:kmpworkmanager`): Apache-2.0, 214 stars, 401 commits, install snippet 3.5.0, Android 8+ and iOS 13+ only, supports `requiresUnmeteredNetwork`; no jitter mention; JVM/desktop not listed as a target; no Releases published on the page (VERIFIED from https://github.com/brewkits/kmpworkmanager and the web search at https://klibs.io/project/brewkits/kmpworkmanager). Other wrappers surfaced by search (worker-kmp by MobileByteLabs, KMPWorker by NeuralHeads at 0.1.0 alpha, backgrounder) are smaller or pre-release (VERIFIED only that they exist, https://klibs.io/project/neuralheads/kmpworker, https://central.sonatype.com/artifact/io.github.mobilebytelabs/worker-scheduler/4.0.2). Not inspected in depth.
- Pros: would give a free iOS BGTaskScheduler adapter later; license compatible.
- Cons: single-vendor project with a young API; no Desktop/JVM target, which is a required adapter here; it would sit between us and WorkManager and hide the exact behaviours our tests assert (unique-name `UPDATE` vs `CANCEL_AND_REENQUEUE`, `pauseFor`/`resumeFor`, readback of enqueued request); iOS is out of scope in the requirements; our policy model (skip reasons, fetch-only) stays custom anyway.
- Verdict: **Not recommended** now. Revisit as an iOS adapter candidate when iOS git is real; keep the port narrow so a wrapper could implement it.

### 1c. Kotlin coroutine / JVM schedulers
- Plain coroutines (`delay` loop, what `PeriodicSyncDriver` already is) are enough for Desktop: in-process, runs only while the app is open, per requirements.
- Quartz-like JVM schedulers (Quartz, db-scheduler, etc.): cron/persistence/cluster features we explicitly exclude ("not a cron scheduler"); heavy dependency, JVM-only, no Android story (INFERRED; not fetched).
- Verdict: Coroutine loop **Recommended** (existing). Quartz-class **Not recommended**.

### 1d. iOS BGTaskScheduler wrappers
- Out of scope. Only a `None`/stub adapter in v1. Verdict: **Not recommended** to evaluate further now (see 1b for later).

## 2. SaaS / push alternatives

- FCM-triggered sync: removes polling and gives near-real-time, but requires Firebase/Google Play services, a server that learns of remote git changes (a git host webhook receiver), device token storage and a privacy surface. Contradicts the local-first, no-server-component constraint (requirements: "no server component", "no remote alerting"). Push-triggered sync is explicitly out of scope in the requirements. Data-message FCM is also subject to Android background delivery priority limits (INFERRED).
- Note: `project_plans/push-architecture/` (VERIFIED by reading the requirements and grepping research files) is about in-app reactive block-list invalidation, not FCM/remote wake-ups. No FCM material exists there to reuse.
- Other SaaS (OneSignal, Pusher, hosted cron): same server dependency, plus a third party sees repo-change metadata.
- Verdict: **Not recommended** for this project; possibly a later opt-in complement (poll stays as the fallback).

## 3. Bespoke LLM-written policy code vs library

No library found covers jitter plus a skip-reason policy plus per-graph settings, and none targets our adapter set (see 1b). The logic is small, pure, deterministic given an injected random source, and therefore the cheapest thing to verify exhaustively.

Write custom (commonMain, pure, injected `Random`/clock, property-tested via kotest-property per CLAUDE.md):
- `JitterPolicy`: offset in `[0, jitter]`, never negative, clamped to platform min/max interval. Strategy to decide in planning: per-run randomised `setInitialDelay` on chained one-time work vs periodic plus flex window (Android flex only gives a window, no randomness, so chained one-time work gives true jitter at the cost of re-enqueue code; INFERRED, to be tested on Pixel_Fold).
- Backoff with jitter (full or equal jitter): extend `ScheduledSyncPolicy.backoffMs`; keep the cap.
- Network-policy decision (any / unmetered / off) evaluated against the live connection, mirroring the WorkManager constraint.
- Settings migration (`scheduled_sync_enabled` -> per-graph-per-kind).
Adopt: WorkManager for the actual scheduling, constraints, persistence and retry plumbing.
Risk with LLM-written code: plausible but wrong boundary arithmetic; mitigated by properties (bounds, monotonic backoff, determinism under a seed) written before the implementation, and by Robolectric readback of the enqueued request.
- Verdict: **Recommended** (custom pure policy plus adopted engine).

## 4. Fork/adapt existing in-repo code

| Piece | Disposition | Notes |
|---|---|---|
| `BackgroundSyncScheduler` | **Recommended** to generalise, not discard | Rename/move to neutral package (e.g. `jobs.schedule`), widen `schedule()` to take a `TaskSchedule` (interval, jitter) and `TaskConstraints` (network policy, charging, battery). Keep a git-named adapter shim for one release for rollback. Pro: smallest delta, existing wiring. Con: churn across ~30 files that reference these names (VERIFIED by grep). |
| `WorkManagerSyncScheduler` | **Recommended** to adapt | Keep unique-name, `pauseFor`/`resumeFor`, slow/fast path. Replace the hard-coded constraint (lines 51-53) with mapped constraints; generalise worker dispatch by task kind (second consumer: `DepthModelDownloadWorker`/`GitCloneWorker`-style). Con: `GitSyncWorker` is git-coupled and must be split into a generic runner plus a git task. |
| `PeriodicSyncDriver` | **Viable** to adapt | Reusable loop/lock/status; extract the git `TickOutcome` into a neutral `TaskRunResult`; add jittered delay. Con: its types are referenced by `GitSyncService`, `ScheduledSyncRunner`, UI. |
| `ScheduledSyncPolicy` | **Recommended** to keep as-is | Remains the gate inside the git task (requirements demand unchanged behaviour); only extend `backoffMs` with jitter. Put the generic policy beside it, not on top of it. |
| Full rewrite | **Not recommended** | Discards invariants tested in six WorkManagerSyncScheduler* test files. |

## Overall recommendation

1. Adopt `androidx.work` (optionally bump to 2.12.0 as a separate change); keep it behind the port.
2. Build in-repo: neutral port and types, pure jitter/backoff/network policy with property tests, settings migration, Desktop coroutine adapter.
3. Fork/adapt `BackgroundSyncScheduler`, `WorkManagerSyncScheduler`, `PeriodicSyncDriver`; keep `ScheduledSyncPolicy` as the git gate.
4. Defer: KMP wrapper (iOS later), push/FCM, Quartz.

## Gaps

- Did not verify Quartz/db-scheduler licenses or maintenance, nor the AndroidX license text, nor brewkits release dates/last commit.
- Jitter-on-WorkManager behaviour is untested on a device; decide in planning with a spike.
