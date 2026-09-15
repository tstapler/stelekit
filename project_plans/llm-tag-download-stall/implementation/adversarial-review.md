# Adversarial Review: llm-tag-download-stall

**Date**: 2026-07-29
**Verdict**: CONCERNS

## Blockers

None. All 3 prior blockers are concretely resolved in the current `plan.md`.

**Blocker A (NFR-3 real-sleep violation) — RESOLVED.** Task 4.1.1 adds
`dispatcher: CoroutineDispatcher = Dispatchers.Default` and
`pollDeadlineMs`/`pollIntervalMs`/`pollEscalationThresholdMs` (all defaulting to
`TagAvailabilityPoller`'s production constants) to `TagSuggestionViewModel`'s constructor,
used to build `scope`. Verified `App.kt:1122`
(`TagSuggestionViewModel(tagEngine, onPropose = viewModel::proposeLlmSuggestion)`) is the
only production construction site and needs zero changes — production behavior is
unaffected. Task 4.4.4's rewritten test builds `StandardTestDispatcher(testScheduler)` off
`runTest`'s own scheduler and passes `pollDeadlineMs = 200L, pollIntervalMs = 50L`, then
asserts `isSuggestionJobActiveForTest` (a new `internal` test accessor) becomes `false`
after `advanceUntilIdle()` — a strictly stronger assertion than the original "reaches
Stalled" check (it proves the coroutine itself terminates, not just that `_state` reflects a
terminal value), not a weakened one. Task 4.5.1 uses `pollDeadlineMs = 1_000L, pollIntervalMs
= 10L` and keeps its original `formatCalls == 2` / `checkAvailabilityCalls == 5` assertions
unchanged. Both tests now run in milliseconds of real and virtual time instead of ~120s/~20s.

**Blocker B (unguarded `checkAvailability()` in poll loop) — RESOLVED.** Task 3.1.1's
`TagAvailabilityPoller.pollUntilAvailable` now wraps the `checkAvailability()` call in
`try { ... } catch (e: CancellationException) { throw e } catch (e: Throwable) { logger.warn(...); null }`
— structured cancellation still propagates, everything else (including `Error` subtypes like
OOM/native binder crashes, deliberately widened beyond `pollForToken`'s `Exception`-only
catch, with an explicit KDoc justification for the widening) degrades to "log and keep
polling," matching the cited `GitHubDeviceFlowClient.pollForToken` continue-on-transient-
failure pattern. Task 3.2.5 is a concrete regression test: a probe that throws
`IllegalStateException` on tick 2 then resolves `Available` on tick 3 proves the loop
survives the throw and still resolves correctly (`assertEquals(3, calls)`), never
propagating into `TagSuggestionViewModel`'s `CoroutineExceptionHandler`.

**Blocker C (`Failed.retryable` dead code) — RESOLVED.** Task 4.2.1's `ifLeft` branch now
computes retryable from a real 3-way match: `RequestFailed && err.retryable` →
`Stalled(retryable = true)`; `DomainError.NetworkError.Timeout` → `Failed(message =
err.message, retryable = true)`; everything else → `Failed(retryable = false)`. Verified
against the actual source (not just the plan's prose): `Timeout` is a real, independently
reachable `DomainError.NetworkError` case (`error/DomainError.kt:48`, structurally distinct
from `RequestFailed`, not a sub-case of it), and `LlmTagProvider.suggestTags()`
(`tags/LlmTagProvider.kt:46-78`, pre-existing code this project does not touch) already
produces it from a genuine `TimeoutCancellationException` thrown by
`withTimeout(timeoutSeconds.seconds)` wrapping the *entire* `provider.format()` call — i.e. a
real slow/hung round-trip, unrelated to the download-availability signal. Confirmed the
genuinely-unsupported-device path does **not** route through `Timeout`: in
`MlKitLlmFormatterProvider.format()` (`androidMain/.../MlKitLlmFormatterProvider.kt:85-87`)
the not-supported branch returns `LlmResult.Failure.ApiError(-1, "On-device LLM not
supported on this device")`, which `LlmTagProvider` maps to `DomainError.NetworkError.HttpError`
(line 50-52) — falling into Task 4.2.1's `else` branch (`Failed(retryable = false)`),
unchanged from today's non-retryable behavior. So broadening `Timeout` to `retryable = true`
does not make the genuinely-unsupported-device case retryable — FR-4's "no behavior change
for genuinely unsupported device" holds; no over-broadening found. Task 5.2.2's `Failed`
branch now reads `status.retryable` and conditionally renders a `TextButton("Retry")` wired
to `onRetry`, structurally absent (an `if`, not `enabled=false`) when `!retryable`, matching
the `Stalled` branch's existing accessibility pattern. Task 5.2.5 adds the corresponding GWT
for the retryable-`Failed`/`Timeout` case.

No new blocker was introduced by any of the three fixes.

## Concerns

See prior review pass for the 7 concerns / 4 minors not re-evaluated this pass, unrelated to
the 3 blockers just fixed (background-polling lifecycle gaps, unsynchronized VM fields,
`retryLastRequest()`'s stale-content-snapshot risk, ADR-001's wrong file reference, the
generic-to-iOS poll-loop concern, the deferred `BACKGROUND_USE_BLOCKED` item, and the
`TagChipRow` ripple to `VoiceCaptureButton.kt`).

One incidental observation from this pass: Blocker C's fix adds a new Pattern Decisions row
("Which retryable `DomainError`s become `Stalled` vs a retryable `Failed`?") and rewrites
Task 4.2.1's `ifLeft` branch and Task 5.2.2's `Failed` rendering substantially from what the
prior review's concerns/minors were filed against. None of the prior 7 concerns or 4 minors
target this specific branch's logic, so nothing appears incidentally fixed or newly broken by
this change — but if any of those items are re-verified in a future pass, re-read them
against the current Task 4.2.1/5.2.2 text rather than assuming they still describe the
original hardcoded-`false` version.

## Minors

None newly found in this pass.
