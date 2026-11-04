# ADR-007: Wire the periodic loop now with a fetch-only tick; cut over to `runScheduledSync` after the dev plan's guards ship

**Status**: Accepted (amended by plan repair iteration 1) | **Date**: 2026-10-27 | Builds on `dev-console-git-sync-fix` ADR-004

## Context
Verified 2026-10-27: `startPeriodicSync` (`GitSyncService.kt:751`) has no production caller and its tick is `fetchOnly`; `pollIntervalMinutes` is stored and unread; `WorkManagerSyncScheduler.schedule` is reached only through `resumeFor`; `GitSyncServiceRegistry.register` exists at `MainActivity.kt:413`. The repository-state guard, mass-change guard, first-sync review and `ScheduledSyncPolicy` the dev ADR-004 requires before scheduled **push** have not shipped (`grep` for `RepairNeeded|MassChange|ScheduledSyncPolicy` is empty).

## Decision
- **Refactor first**: `GitSyncService.kt` is a 786-line hotspot (12 commits in 90 days) and dev ADR-004 forbids scheduling branches in `sync()`. Story 2.0 extracts a `PeriodicSyncDriver` collaborator (`git/PeriodicSyncDriver.kt`) that owns the interval loop, per-tick `Throwable` guard, `SyncTick` log, busy skip, pause after 3 consecutive failures, immediate first tick and a `StateFlow<ScheduledSyncStatus>`; it takes an injected `tick: suspend () -> TickOutcome`, a clock and a CEH-guarded scope. `GitSyncService.startPeriodicSync` becomes a thin delegation, Story 2.3 reads the StateFlow, and Story 2.4 only swaps the tick lambda to `runScheduledSync`.
- The timer is started from `GraphContentGitSyncSetup` keyed on `(service, scheduled_sync_enabled, pollIntervalMinutes)`. `DesktopSyncScheduler` is deleted (owner gate) as a duplicate with no exception handler.
- **Step 1 (this project, ships first)**: fetch-only tick with per-tick Throwable guard, busy skip, wall-clock last-success, immediate first tick after load, default off, settings UI, Android periodic enqueue through a no-default `backgroundSyncSchedulerFactory`.
- **Step 2 (Story 2.4)**: tick body becomes `runScheduledSync`, only after the dev plan's Tasks 2.3e, 2.3f, 2.2d0 and 2.4a are merged. Story 2.4 depends on `ScheduledSyncPolicy` at compile time, so it cannot be done early. No guard or policy is re-specified here.
- Metric 4 is satisfied at Step 2 only. **It cannot close inside the unwired-features plan**: Step 2 depends on dev-plan PR-A1/PR-A2 units this plan does not fund, so M4 is reported "partially met" at U2 and the dependency is declared on U16 and in the wall-clock blockers. Step 1 delivers staleness visibility without risking a push.
- Hard-reset `abortMerge` stays unreachable from any background path.

## Alternatives rejected
Enable full sync before the guards (silent data-loss risk, pitfalls C5); implement the guards in this project (duplicates the dev plan); keep `DesktopSyncScheduler` as the desktop driver (second scope, no handler).
