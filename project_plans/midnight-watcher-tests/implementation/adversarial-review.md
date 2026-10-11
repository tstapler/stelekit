# Adversarial Review: midnight-watcher-tests

**Date**: 2026-09-22
**Verdict**: CONCERNS

## Blockers

None. Every line number and code-shape claim in `plan.md` was checked against the real source
(`GraphLoaderProgressiveTest.kt:738-905`, `StelekitViewModel.kt:1955-1993`, `FakeClock.kt`,
`kmp/build.gradle.kts:465/490`) and all matched exactly. The timing budget (1000ms per crossing via
the 1-ms-before-midnight seeding trick, `withTimeout(8000)` for the two-crossing guard test) is
sized with real reasoning about CI contention in `research/pitfalls.md`, not guessed. Nothing found
here rises to "must fix before implementation starts."

## Concerns

- [ ] **`FakeClock.instant` is a plain (non-`@Volatile`) `var` read cross-thread, and this plan
  triples its real-time-coroutine usage (1 test → 3).** `research/pitfalls.md` §2 already diagnoses
  that the test thread writes via `advance()` while the watcher coroutine (a different OS thread
  under `Dispatchers.Default`) reads via `now()`, with no JMM guarantee of visibility — it "happens
  to work" only because thread-pool dispatch incidentally introduces a happens-before edge. The plan
  correctly calls this pre-existing and out of scope for `StelekitViewModel.kt`, but `FakeClock.kt`
  is test-only infrastructure (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/testing/FakeClock.kt`),
  not production code — a one-line `@Volatile` fix there isn't blocked by requirements.md's
  production-code-change restriction, and this plan is the exact change that multiplies exposure to
  the risk it already identified. — **Recommendation**: add `@Volatile` to `FakeClock.instant` as
  part of this change (or as an immediately-following one-line commit); it's the cheapest available
  mitigation for a risk this plan is actively amplifying.

- [ ] **Story 1.1.4 (try/finally on the reference test) is scope creep beyond requirements.md's
  named scope, though a defensible one.** requirements.md's "Affected Tests" table names exactly
  three tests (lines 740/772/800) and explicitly frames the line-841 test as "already correct — do
  not duplicate" (i.e., a fixed baseline, not a target). AC-06 requires "no regression to the
  existing correct test," which is a weaker check when the diff also modifies that same test — you
  can no longer point to an untouched baseline to prove non-regression, only to the plan's own
  reasoning that the change is purely additive. The fix itself is small, well-diagnosed (real
  coroutine leak on assertion failure, `research/pitfalls.md` §2), and consistent with the pattern
  being added to the other two rewrites, which is a legitimate "touching the same file/pattern
  anyway" argument — but it's still an edit to a test that AC-06 says shouldn't need touching, added
  by planner judgment rather than requested. — **Recommendation**: keep the fix, but call it out
  explicitly to whoever reviews the diff (e.g., a distinct commit/paragraph in the PR description)
  rather than folding it silently into "rewrite 3 tests" — so a reviewer checking AC-06 knows they're
  reviewing a modified reference test, not verifying an untouched one.

- [ ] **AC-01's "delay call removed" failure mode isn't demonstrated for Story 1.1.3 (or any test in
  this file).** The plan's concrete failure example for the guard test only walks through an
  inverted-guard mutation (`if (today != lastJournalDate) continue`), which the two-assertion
  structure does catch. But AC-01 also lists "delay call removed" as an example break. If
  `delay(delayMs)` at `StelekitViewModel.kt:1976` were deleted, the loop would busy-spin instead of
  waiting, but would still converge to the same final repository state the polling assertions check
  for (page appears once dates cross) — none of the outcome-based tests in this file, including the
  existing line-841 reference test, would catch that regression, since they assert *what* happened,
  not *how long it took to get there*. This is a pre-existing limitation of the repository-polling
  approach (not introduced by this plan), but the plan's AC-01 justification implicitly claims
  broader mutation coverage than it demonstrates. — **Recommendation**: no code change needed: note
  in the plan or PR body that "delay removed" is not actually covered by these tests (nor was it by
  the original reference test), so a future reader doesn't assume AC-01 is fully closed on that
  specific mutation.

- [ ] **The guard test's negative-assertion window has the thinnest margin of the three rewrites.**
  Task 1.1.3a's skip check waits a fixed `delay(1200)` against a nominal ~1000ms watcher wake-up
  (200ms yield + up to ~1000ms first delay), leaving ~400ms of slack before the assertion runs — in
  contrast to the 8000ms budget the same task gives the positive (second-crossing) poll. A negative
  assertion timing out early doesn't cause a false failure (the page really is absent either way,
  per the reasoning above), but it does mean this window is the one most likely to need enlarging
  first if the suite proves flaky on a loaded runner. — **Recommendation**: no change required now;
  if `research/pitfalls.md`'s CI-contention numbers ("tens to low-hundreds of ms," not seconds) hold,
  400ms margin is adequate — but note this is the first knob to check if `midnight watcher skips
  call...` becomes flaky in practice, one which the plan's own AC-01 concrete-failure walkthrough
  should not be confused with.

## Minors

- Three separate `try { ... } finally { vmScope.cancel() }` blocks across the rewritten/extended
  tests duplicate the same cleanup shape. A tiny shared test helper (e.g.
  `withCancellableVmScope(vmScope) { ... }`) would remove the repetition, but at Complexity 1 with
  only 3 call sites this is a stylistic nice-to-have, not worth the extra abstraction.
- Task 1.1.5a's unused-import list was independently re-verified by grep against the real file
  (`ExperimentalCoroutinesApi`, `isActive`, `launch`, `advanceTimeBy`, `DateTimeUnit`,
  `toLocalDateTime`, `plus`, `hours`, `minutes` — all confirmed to appear only inside lines 738-838
  or as incidental substring matches in comments/strings elsewhere, e.g. "24 hours" in a message
  string at line 721, "second launch" in a doc comment at line 76). The claim is correct as written;
  flagging only so the reviewer doesn't need to re-derive this from scratch.
