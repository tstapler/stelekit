# ADR-002: Jitter is a flex window plus a deterministic per-(graph, kind) offset

**Status**: Proposed, gated by Spike 0.1.1 (Robolectric / `WorkManagerTestInitHelper`)
**Date**: 2026-11-10

## Context
`PeriodicWorkRequest` has a flex window and `setInitialDelay` (first run only), no random jitter (research/stack.md section 2). Chained one-time work gives true per-run jitter but abandons periodic semantics, the `UPDATE` policy and the shared-name clone-pause protocol, and a crash between runs silently ends the chain (research/pitfalls.md P1-C, P3).

## Decision
- Android: one periodic request per key with `flexInterval = JitterPolicy.flexMinutes(interval, jitter)` and `initialDelay = JitterPolicy.offsetMinutes(seed(graphId, kind), jitter)`, enqueued with `UPDATE`. The offset is deterministic (FNV-1a hash of `kind:scope` seeding `kotlin.random.Random`), so re-applying the same config produces an equal request and an app open never re-rolls.
- `jitter == 0` produces no flex and no initial delay, i.e. today's request.
- The pure functions (`JitterPolicy`, `Schedule.clamp`) live in `commonMain` with an injected `Random` and are property-tested: offset in `[0, jitter]`, never negative, `interval >= minInterval`, `flex >= minFlex` and `< interval`, `interval + offset` within bounds, same seed gives same offset.
- The in-process adapter draws a per-cycle offset from the same function with a per-cycle seed (true per-run jitter where it is cheap).
- Fallback, only if the spike shows `UPDATE` ignores a changed `flexInterval` or `initialDelay`: apply changes with `CANCEL_AND_REENQUEUE` on a settings change only (never on app open), and record the lost phase. Chained one-time work is rejected unless the spike shows flex is unusable.
- `setNextScheduleTimeOverride` (WorkManager 2.9.0+) is evaluated in the spike as an optional per-run jitter source from inside the worker; adopted only if the test driver can read it back.
- Spike 0.1.1 also records (moved up from Task 3.1.2d, repair loop 1): whether `UPDATE` changes `WorkSpec.workerClassName` from `GitSyncWorker` to `TaskWorker`, and how `UPDATE` behaves on RUNNING periodic work. Decision (Phase 4 repair, independent of the outcome): `apply` and `resume` enqueue `GitSyncWorker` for `git-sync`; it is a thin wrapper with no registry or store dependency that nevertheless evaluates the shared `NetworkGate` (policy from the input key `KEY_NETWORK_POLICY`, absent = `Any`) and appends one run-log record through the shared write-only `recordRun`. `TaskWorker` serves one-shots and non-git periodic kinds. If `UPDATE` changes the class, a later move of `git-sync` to `TaskWorker` becomes possible but is not done in this release. The spike result is a hard gate on Story 3.1.2 (Task 3.1.2g).
- The spike also records (Phase 4 repair): the observed gap distribution between consecutive runs on `TestDriver` (flex runs fall in the last `flex` minutes of each period, so the gap is `[interval - flex, interval + flex]`, not `[interval, interval + jitter]`; only the in-process adapter has the latter), whether `UPDATE` keeps the existing next-run time (so a changed offset/flex applies after the next run), and whether `UPDATE` replaces the stored input data (the worker's gate reads `KEY_NETWORK_POLICY` from it). Acceptance list for this ADR: (1) interval/flex/constraints read back, (2) `UPDATE` keeps work id, (3) class-change outcome, (4) RUNNING `UPDATE` outcome, (5) next-run retained or moved after `UPDATE`, (6) input-data replaced by `UPDATE`, (7) observed gap range. UI copy ("Runs N-M min apart" vs the flex wording and "Takes effect after the next run") derives from the per-adapter `describe` gap fields set from (5) and (7).
- Already VERIFIED: `WorkInfo.periodicityInfo.repeatIntervalMillis` read-back works on 2.9.1 (used at `GitSyncSchedulingEntryPointTest.kt:95`). Flex and next-schedule read-back are still the spike's job.

## Consequences
Cross-device spread is deterministic and testable; per-run spread on Android is limited to the flex window the OS picks from. The user-visible promise is "about every N" (UX copy), never exact.
