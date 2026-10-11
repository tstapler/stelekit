# Architecture Review: stelekit-capture-auto-enrich

**Date**: 2026-08-27
**Verdict**: CLEAN

> **Historical note**: this review pre-dates a later plan.md revision that removed the
> `saveOpMutex` design mentioned below in favor of a synchronous-fold pattern (plan.md's
> Pattern Decisions table, "Remove `saveOpMutex` — do not add it"). References to
> `saveOpMutex` here describe a superseded design, not the shipped code — see
> `CaptureViewModel.acceptSuggestion()`/`save()` and `validation.md`'s
> `acceptSuggestionThenSave_synchronousFoldWinsRace_...` test for the actual final design.

**Constitution check**: `docs/adr/ADR-000-architecture-constitution.md` does not exist in this
repository (`docs/adr/` runs ADR-001…ADR-017, no ADR-000). No constitution section applies.

This is a re-review verifying fixes to the prior BLOCKER and 4 CONCERNS, not a fresh review.

---

## Resolved since prior review

- **Blocker (Epic 4.2 / Task 4.2.1a, graph-identity gap)**: Genuinely fixed, verified at the code
  level, not just narrated.
  - `SavedCaptureContext` (Task 2.3.2a, plan.md:541-557) now carries `graphId`, `pageRepository`,
    `blockRepository` alongside the existing `writer`/`writeActor`. `performSave()` (Task 2.3.2b,
    plan.md:559-562) populates all of these from real values already resolved at that point
    (`graphManager.getActiveGraphId()`, `repoSet.pageRepository`, `repoSet.blockRepository`) — not
    placeholders.
  - `acceptSuggestionPostSave()` (Task 4.2.1a, plan.md:788-811) performs the `ctx.graphId !=
    graphManager?.getActiveGraphId()` check as the *first* statement in the method body, before
    the existence read (`ctx.pageRepository.getPageByName(term)`, line 796) or any write — the
    ordering the prior review required.
  - The `writeActor == null` fallback (Task 4.2.1b, plan.md:813-833) now goes through
    `ctx.blockRepository` (captured in `SavedCaptureContext`) instead of a freshly-fetched active
    repo set — confirmed symmetric with the existence-check fix, and explicitly called out as such
    in the task text (plan.md:832).
  - ADR-002 (`decisions/ADR-002-post-save-second-write-scope-boundary.md`) now has constraint 6
    documenting this exact requirement — the graph-identity comparison, the mandate that
    `SavedCaptureContext` carry the originating repositories, and why `savedContext != null` alone
    isn't sufficient evidence the constraint holds (ADR-002 lines 65-80, Consequences section
    lines 119-125).

- **Concern 1 (`getOrCreateEnrichmentCoordinator()` await-inside-lock)**: Fixed. Task 1.2.1b
  (plan.md:259-288) now looks up/inserts the `Deferred` inside `coordinatorMutex.withLock { }` and
  returns `graphId to deferred` from the lock, then calls `.await()` *after* the lock is released
  (plan.md:275-276). Matches the remediation the prior review proposed.

- **Concern 2 (no failure-eviction path)**: Fixed. The same Task 1.2.1b wraps `.await()` in a
  try/catch that re-acquires `coordinatorMutex` on any non-cancellation `Throwable` and clears
  `coordinatorFor` if it still points at the failed entry (plan.md:279-284), so the next call
  attempts a fresh construction rather than replaying the same exception. AC and Given-When-Then
  for this are present at plan.md:235-236.

- **Concern 3 (nullable `ScanResult?` collapsing two failure modes)**: Fixed. `scan()` now returns
  a sealed `ScanOutcome` (`MatcherNotReady` / `TimedOut` / `Success(result)`) nested in
  `CaptureEnrichmentCoordinator` (Task 1.1.1a, plan.md:131-141; implementation at plan.md:147-156).
  `CaptureViewModel`'s consuming `when` (Task 2.1.2b, plan.md:378-388) maps both non-success cases
  to `ScanState.NotReady` for save-time behavior but logs `TimedOut` distinctly at `debug` — the
  "scan budget exceeded (debug)" Observability Plan line is now implementable, confirmed in the
  Observability Plan text itself (plan.md:64) and Story 5.1.1's test (plan.md:912-914).

- **Concern 4 (Task 4.3.1c "verify only" + scrim clickable bug)**: Fixed. Task 4.3.1c
  (plan.md:890-900) is explicitly retitled "real code change, not verify-only" and its own body
  says so. The scrim `clickable` gets an explicit `isDone` branch ahead of the existing
  blank/non-blank branches: `if (isDone) onSaved() else if (captureText.isBlank()) onDismiss() else
  viewModel.save()` (plan.md:894-896) — a tap during the "Done" window now finishes immediately
  via `onSaved()` rather than re-invoking `save()`. `BackHandler` is correctly left alone (already
  confirmed clean by the prior review; unchanged here).

## Sanity-check on the new Mutex serialization (adversarial reviewer's save/accept race)

No obvious deadlock. `saveOpMutex` is acquired by `save()`'s `scope.launch { }` body and by
`acceptSuggestion()`'s `scope.launch { }` body (Task 2.3.1b/4.1.1a), each as a single top-level
`withLock { }` per call. `acceptSuggestion()`'s post-save branch calls `acceptSuggestionPostSave(ctx,
term)` directly from *inside* its own `withLock` block (plan.md:720) — `acceptSuggestionPostSave()`
itself does not attempt to re-acquire `saveOpMutex` (it only touches `graphManager`,
`ctx.pageRepository`, `ctx.writer`, `ctx.writeActor`/`ctx.blockRepository` — plan.md:788-845), so
there is no nested/re-entrant acquisition on this call path, and no cross-mutex ordering with
`coordinatorMutex` (the two mutexes are never held simultaneously by the same coroutine anywhere in
the plan). One minor asymmetry: the plan shows an explicit code snippet for `acceptSuggestion()`'s
`saveOpMutex.withLock { }` wrap (Task 4.1.1a) but only describes `save()`'s wrap in prose (Task
2.3.1b) without an equivalent snippet — not a correctness issue, just a documentation-completeness
nit, listed below.

---

## Blockers

(none)

## Concerns

(none)

## Nitpicks

- Task 2.3.1b describes wrapping `save()`'s `scope.launch { }` body in
  `saveOpMutex.withLock { }` in prose only, unlike `acceptSuggestion()`'s equivalent wrap (Task
  4.1.1a) which has an explicit code snippet. Worth adding a snippet for `save()` too at
  implementation time so the exact wrap boundary (does it include the `_saveState` update? yes,
  per the prose) isn't left to interpretation.
- The `"[image: <path>]\n<freeText>"` composite-string convention (`CaptureActivity.kt:82,120`)
  still forces `CaptureViewModel.splitImagePrefix()` to regex-reparse a string `CaptureActivity`
  already built from structured `ShareContent`. Carried forward from the prior review — not worth
  fixing in this feature's scope, flagged as a follow-up.
- Epic 2.2's enhancement coroutine (`scope.launch { }`) is deliberately detached from
  `collectLatest` so it survives being superseded by a newer scan. On a slow LLM provider with a
  fast-typing user this can pile up several concurrent in-flight enhancement calls. Carried forward
  from the prior review — not a correctness issue for v1.

---

## Verified clean (no finding) — carried forward, still holds

- **Layer coupling** (`CaptureEnrichmentCoordinator` placement): `androidApp/build.gradle.kts`
  depends on `:kmp` and not vice versa; `GraphManager` lives in `kmp/src/commonMain`.
- **GoF composition-over-inheritance rejection of Alternative C**: `ImportViewModel` takes
  `matcherFlow: StateFlow<AhoCorasickMatcher?>` as an injected constructor dependency; the plan's
  rejection of forking/subclassing `ImportViewModel` is well-founded.
- **`RequestCoalescer` rejection**: `RequestCoalescer.execute()` evicts its key in a `finally`
  block once the loader completes — unsuited to AC #8's *permanent* per-graph memoization.
- **`GraphId` primitive obsession**: `GraphId` is a real `value class` newtype, used correctly
  throughout the updated plan (`coordinatorFor: Pair<GraphId, Deferred<...>>`,
  `SavedCaptureContext.graphId`).
- **`writeActor` liveness re-validation on the second write**: `DatabaseWriteActor`'s channel is
  scoped to the same per-graph `CoroutineScope` `GraphManager.switchGraph()` cancels — the second
  `saveBlock()`'s `ClosedSendChannelException` catch (Task 4.2.1b) is a genuine re-validation, now
  additionally backstopped by the graph-identity check that runs before it.
- **TOCTOU in the enrichment merge (Epic 2.2)**: no suspension point between the `_scanState.value`
  read and the write-back in the enhancement coroutine's completion handler — no race window.
