# ADR-002: `GitOperationSupport`'s internal retry loop is the single retry owner

**Status**: Accepted
**Date**: 2026-09-23
**Project**: git-sync-resilience

## Context

Two independent retry mechanisms already exist in production, uncoordinated with each other
(`research/pitfalls.md` §1.1, `research/stack.md` §3):

1. `GitSyncWorker.doWork()` (`WorkManagerSyncScheduler.kt:117-168`) returns `Result.retry()` on
   any non-cancellation exception, on both its fast path (delegates to a registered
   `GitSyncService.fetchOnly()`) and its slow path (process was killed; constructs a standalone
   `AndroidGitRepository` and calls `fetch()` directly). This triggers WorkManager's own default
   linear backoff (`WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS` = 10s), uncapped in retry count
   since `setBackoffCriteria` is never called.
2. `GitSyncService.scheduleRateLimitRetry` (`GitSyncService.kt:153-166`) is a hand-rolled,
   scope-owned, one-shot delayed re-invocation of the *whole* `sync()`/`fetchOnly()` operation,
   used only for HTTP 429.

This project adds a third mechanism by design: retry-with-backoff around the JGit transport call
itself, inside `GitOperationSupport.kt` (per `research/architecture.md` §1a/§3 — the correct,
shared choke point for both platforms). Without an explicit decision, a single real transient
failure would trigger nested retry storms: N internal attempts (each with its own backoff), and
if the method still throws after internal exhaustion, WorkManager retries the whole worker again,
which repeats the N internal attempts again — multiplicative, not additive, blow-up in both
attempt count and wall-clock/battery cost. This directly undermines the "regression: common-case
behavior unchanged" success metric and the "genuinely stuck" failure mode `research/ux.md` §1
explicitly designs against.

## Decision

**`GitOperationSupport.runGitTransportOpWithRetry` (Story 1.2.2) is the single owner of
transport-level retry.** It wraps only the transport call itself (not `commit()`/`stageSubdir()`
— see `research/pitfalls.md` §1.3 on push/commit retry granularity), using
`RetryPolicies.gitTransportTransient`, gated by `classifyGitFailure` so only
`GitFailureClass.Transient` failures are retried.

Every caller built on top of this — `GitSyncWorker.doWork()` (Story 1.2.3), the new
`GitCloneWorker` (Story 3.1.5) — treats "internal retry budget exhausted" as a **final** result
for that invocation: both return `Result.failure()`, never `Result.retry()`, once
`runGitTransportOpWithRetry` gives up. WorkManager's own periodic 15-minute schedule (for
`GitSyncWorker`) remains the only "outer" retry, and it is already bounded and independent —
not a second backoff layered on the same failure.

`GitSyncService.scheduleRateLimitRetry` is left as-is: it is a distinct mechanism for a distinct,
server-dictated case (HTTP 429's `retryAfterSeconds`, not a backoff curve) and does not compound
with transport-level retry, since a 429 is classified as `GitFailureClass.Permanent` for the
purposes of `runGitTransportOpWithRetry` (it must not be retried by the generic transient-network
schedule) and continues to be handled by the existing rate-limit path.

## Consequences

- `GitSyncWorker.doWork()`'s two `catch` blocks (fast path and slow path) both change from
  `Result.retry()` to `Result.failure()` — WorkManager will no longer re-invoke the worker early
  after a transient failure; the next attempt is the regularly-scheduled 15-minute periodic run.
  This is an intentional behavior change, not an oversight: retrying sooner than 15 minutes is
  now `runGitTransportOpWithRetry`'s job, executed *within* that single `doWork()` call.
- Retry no longer "storms" battery/CPU indefinitely: `GitOperationSupport`'s retry budget is
  bounded both in attempt count and total wall-clock time (Story 3.1.4), and once exhausted the
  operation is definitively over for that invocation — no silent extension via an outer layer.
- A future change to either layer must preserve this single-owner invariant: if WorkManager ever
  needs its own `setBackoffCriteria`-based retry again, `runGitTransportOpWithRetry`'s retry count
  must be reduced accordingly (e.g. to 1) so the two never both run a full multi-attempt schedule
  for the same failure.

## Alternatives Considered

- **WorkManager owns all retry/backoff** (`setBackoffCriteria`, `GitOperationSupport` does zero
  internal retries) — rejected: WorkManager's scheduler exists only on Android, so Desktop would
  need an entirely separate retry-owning mechanism anyway, splitting the one-shared-`jvmCommonMain`-
  fix architecture the research explicitly recommends. It also retries at the wrong granularity —
  a re-invoked `doWork()`/`sync()` re-runs `stageSubdir()`/`commit()`, which risks
  `EmptyCommitException` or (if ever papered over with `setAllowEmpty`) duplicate commits on a
  retry that only needed to re-attempt the `push()` step (`research/pitfalls.md` §1.3).
- **Both layers retry independently, with no coordination** — rejected: this is today's actual
  (accidental) state and is the specific defect this ADR exists to close; see Context above.
