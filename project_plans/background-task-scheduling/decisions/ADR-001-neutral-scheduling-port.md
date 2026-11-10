# ADR-001: Neutral scheduling port in `jobs.schedule`, level-triggered `apply`, separate from `BackgroundJobRegistry`

**Status**: Proposed, revised in repair loop 1 (accepted when Epic 1.1 and the contract suite are green)
**Date**: 2026-11-10

## Context
`BackgroundSyncScheduler` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/BackgroundSyncScheduler.kt`) is git-named and takes only `intervalMinutes`. Requirements demand a port with zero git types, two consumers, two shapes (periodic and one-shot), and capabilities the Settings UI can read. `BackgroundJobRegistry` (`jobs/BackgroundJobs.kt`) is read-only observation keyed by the closed `JobKind` enum.

## Decision
- New package `dev.stapler.stelekit.jobs.schedule` in `commonMain`. It imports only stdlib, coroutines, kotlin.time/datetime, `Logger`, `platform.Settings`, `platform.NetworkMonitor` types. An import-scan test forbids `git`, `ui`, `db`, `merge`.
- `TaskKind` is an open value class (`"git-sync"`, `"model-download"`), not `JobKind`. `TaskSpec` maps a kind to a `JobKind` and a title so the read-only registry tags stay correct.
- `BackgroundTaskScheduler` has `apply(key, TaskConfig)` (level-triggered, idempotent, periodic only: `TaskConfig` holds a validated `Schedule`, so a one-shot shape or negative interval cannot reach it), `runOnce(key, constraints, onConflict = Keep | Replace)` (one-shot), `cancel(key)`, `cancelAll(scope)`, `pause(scope)`, `resume(scope)`, `describe(key)` (effective interval, clamp flag, next run; read path for the UI, Desktop status and the contract suite) and `capabilities`. `pause` snapshots the live work it cancels (interval, flex, constraints, tags) into an `AppliedConfigLedger` (explicit `Settings`, plus an in-memory copy), and `resume` restores that snapshot, never the 15-minute floor. The settings store (which lazily migrates legacy state) is consulted only when there is no snapshot, and an explicit store entry or tombstone saying disabled wins over a snapshot, so resume never resurrects disabled work. `apply` also writes the ledger. Callers apply a periodic config through `apply` only when `capabilities.runsInBackgroundWhenClosed` (Android); Desktop git stays on `PeriodicSyncDriver`.
- `NetworkPolicy` has two values, `Any` and `Unmetered`. "Off" is `enabled = false`, so a user never sees two controls that mean the same thing (research/ux.md section 3).
- `BackgroundJobRegistry` stays read-only. The Android adapter bridges by tags; the in-process adapter reports through `JobTracker`.
- Desktop git loop ownership (revised, repair loop 1): **`PeriodicSyncDriver` stays the git loop on Desktop and Android for this release** (the former fallback is now the default), fed by the same per-graph settings through `GitSyncScheduleController`. Reason: the driver owns pause-after-3-failures, `immediateFirstTick`, `tickInFlight`, busy/offline skips, restart on manual success and the `ScheduledSyncStatus` fields `scheduledSyncLine` renders; none has a counterpart in `InProcessTaskScheduler`, and two jitter implementations would diverge. `InProcessTaskScheduler` is still delivered and proven by the contract suite and a non-git fake consumer, is the explicit Desktop `backgroundTaskScheduler` (capabilities for the UI), and is the host for non-git Desktop kinds. Moving git onto it is a follow-up gated on a status-line and failure-pause parity test.
- `TaskKind` is restricted to `[a-z0-9-]+` and `TaskSpec.jobKind` is nullable, so a kind with no `JobKind` gets no kind tag. `jobs.schedule` imports `jobs.JobKind`, and the parent `jobs` package imports `merge.*` (`InProcessJobs.kt`); the import-scan test cannot see that transitive edge. Accepted.

## Consequences
New consumers register `(kind, spec, task)` and never write a Worker class. The port depends on nothing git-specific, so `GitSyncTask` maps `TickOutcome` into `TaskRunResult` at the boundary. Cost: a deprecated shim (`BackgroundSyncSchedulerFactory`) for one release.

## Alternatives rejected
Extend `BackgroundSyncScheduler` in place (keeps git naming, one consumer); reuse `JobKind` as the key (couples scheduling to UI labels and non-schedulable kinds); merge scheduler into `BackgroundJobRegistry` (gives a read-only list write powers).
