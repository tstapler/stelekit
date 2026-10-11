# Implementation Plan: midnight-watcher-tests

**Feature**: Rewrite the three local-loop-reimplementation tests in `GraphLoaderProgressiveTest.kt`
so they drive `StelekitViewModel.startMidnightBoundaryWatcher` directly, following the pattern
already proven by the existing correct test at (current) line 841.
**Date**: 2026-09-22
**Status**: Ready for implementation
**ADRs**: None

---

*(Domain Glossary omitted — Complexity 1, no new domain types, methods, or variables are being
introduced; every name used below already exists in `StelekitViewModel.kt` or
`GraphLoaderProgressiveTest.kt`.)*

## Pattern Decisions

**Step 0.5 creative pass** — three structuring options were considered for the replacement tests:

1. **Three separate focused real-path tests** (one per behavior: cancellation, guard, and delete
   the redundant multi-crossing test) — mirrors the file's existing one-test-per-behavior
   structure; each test name states exactly what broke if it fails.
2. **One consolidated multi-crossing mega-test** combining cancellation + guard + multi-crossing
   assertions in a single test body — fewer test methods, but a single body doing three things
   means a failure in the first assertion can prevent later assertions in the same body from ever
   running (JUnit stops at first failure), which obscures *which* of the three behaviors broke.
3. **Extend the existing line-841 test in place** to also assert cancellation and guard behavior,
   deleting the other three entirely — reuses setup boilerplate, but requirements.md explicitly
   frames line-841 as "already correct — do not duplicate," and folding unrelated concerns into it
   means any future change to cancellation or guard behavior touches the one test everyone treats
   as the canonical reference.

**Chosen: Option 1** (three separate focused tests, one deleted). Recorded as the "Alternative
Rejected" columns below.

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Test structure for the 3 replacement tests | Given-When-Then via the real production entry point (`vm.startMidnightBoundaryWatcher`), one focused test per behavior | `research/build-vs-buy.md` Option A; reference test `GraphLoaderProgressiveTest.kt:841` | Option 2: one consolidated multi-crossing mega-test | Conflates 3 independent behaviors into one test body; a single assertion failure masks whether the other two still hold, and AC-01/AC-03/AC-04 map to 3 distinct, independently-failing behaviors that should fail independently |
| Test structure (same) | (same as above) | (same) | Option 3: extend the line-841 test in place, delete the other three | requirements.md frames line-841 as the fixed reference pattern, not a growth point; bloating it with cancellation/guard assertions violates single-responsibility test naming and makes the "canonical" test harder to read |
| Coverage of the old 3-crossing simulated test (current line ~740) | Deleted; its coverage is subsumed by line-841 (proves a single real crossing fires) plus the rewritten guard test (which itself drives two real crossings) | requirements.md "What needs to change" item 3 ("either delete it... reviewer intent is no local reimplementation survives, not keep the same test count") | Fold a dedicated 3-consecutive-midnight real-path variant | Would add ~3 more real wall-clock seconds of test runtime for coverage already implied by 1 single-crossing test + 1 two-crossing test; no acceptance criterion requires exactly 3 consecutive crossings |
| Observing `ensureTodayJournal()` side effects | Repository-state polling via `pageRepo.getJournalPageByDate(date)`, using a **distinct calendar date per crossing** to distinguish "skipped" from "not yet run" | `research/build-vs-buy.md` Option A (recommended) | Option B (mockk/all-open + interface extraction) / Option C (thin counting wrapper) | `JournalService` is `final` and only reachable as a concrete type through `StelekitViewModelDependencies.journalService` (`research/build-vs-buy.md` Facts); no mocking library is on the classpath, and widening it to an interface is explicitly out of scope per requirements.md. Distinct-date polling gives the same AC coverage with zero new code or dependencies |
| Timing control around the watcher's `delay()` | Real `Dispatchers.Default` scope + `runBlocking` + `FakeClock` seeded 1 ms before midnight + `withTimeout`-bounded polling | `research/stack.md` §5–6 | `runTest` virtual-time scheduler (`advanceTimeBy`/`advanceUntilIdle`) | `startMidnightBoundaryWatcher` launches on `StelekitViewModelDependencies.scope`, the same scope carrying ~20 other infinite `init`-time `Flow` collectors in `StelekitViewModel`; putting that scope on a `TestCoroutineScheduler` risks `advanceUntilIdle()` hanging forever (`research/stack.md` §5, `research/pitfalls.md` §1) |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| `GraphLoaderProgressiveTest.kt:841-903` (the reference test itself) | `vmScope.cancel()` only runs on the happy path (last line of the test body); if any assertion earlier in the body throws, the real `Dispatchers.Default` watcher coroutine leaks for the rest of the JVM test process (`research/pitfalls.md` §2) | Extend as-is — wrap the body in `try { ... } finally { vmScope.cancel() }` alongside the 3 rewrites | This task is already editing this exact file for this exact real-time-coroutine-scope pattern; fixing the leak in the 2 new tests but leaving the reference test (the one every other test is told to imitate) unfixed would ship an inconsistency the research already diagnosed in the same PR |

---

## Migration Plan
N/A — complexity 1.

## Observability Plan
N/A — complexity 1.

## Risk Control
N/A — complexity 1.

## Unresolved Questions
None. `research/stack.md`, `research/pitfalls.md`, and `research/build-vs-buy.md` fully resolve the
timing, seeding-race, and observability-mechanism questions raised in requirements.md.

## Dependency Visualization

All work is confined to one file — `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`
— so tasks are a strict sequential chain (no safe parallelism; concurrent edits to the same file
would conflict):

```
1.1.1  Delete old "simulated day crossing" test (current line ~740)
          │
          ▼
1.1.2  Rewrite cancellation test to drive the real watcher (current line ~772)
          │
          ▼
1.1.3  Rewrite lastJournalDate guard test against the real production field (current line ~800)
          │
          ▼
1.1.4  Add try/finally cleanup to the reference test (current line ~841)
          │
          ▼
1.1.5  Remove now-unused imports, then run jvmTest to verify (AC-05, AC-06)
```

---

## Phase 1: Rewrite midnight-watcher tests to drive the real production entry point

### Epic 1.1: Replace local-loop reimplementations with real-path tests in `GraphLoaderProgressiveTest.kt`
**Goal**: Every test in the file that exercises the midnight-boundary watcher does so by calling
`StelekitViewModel.startMidnightBoundaryWatcher` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt:1966`)
directly — no test contains a local `while (isActive) { delay(...); ... }` copy of the loop.

#### Story 1.1.1: Remove the superseded simulated-crossing test
**As a** reviewer of this test file, **I want** the redundant local-loop 3-crossing test removed,
**so that** no test in the file reimplements the watcher's loop once the other two are fixed.

**Acceptance Criteria**:
- AC-02 (partial: this test's removal): No `while (isActive)` reimplementation remains from this
  test.
  - *Given* the test `` `midnight watcher calls ensureTodayJournal after simulated day crossing` ``
    (current lines 738-768, using `runTest` + a locally `launch`ed loop with a local
    `AtomicInteger callCount`), *When* the rewrite in this plan is complete, *Then* that test
    function no longer exists in `GraphLoaderProgressiveTest.kt`, and its coverage (proving the
    watcher fires `ensureTodayJournal()` on a crossing, repeatedly across boundaries) is covered by
    the untouched reference test at (current) line 841 for a single crossing and by Story 1.1.3's
    two-crossing guard test for a second crossing.

**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`

##### Task 1.1.1a: Delete the simulated-crossing test (~2 min)
- Delete the entire function `` `midnight watcher calls ensureTodayJournal after simulated day crossing`() = runTest { ... } `` (current lines 738-768, including its `@OptIn(ExperimentalCoroutinesApi::class)` annotation and `@Test` annotation).
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`

---

#### Story 1.1.2: Cancellation test drives the real watcher and the real scope
**As a** maintainer of `startMidnightBoundaryWatcher`, **I want** the cancellation test to cancel
the actual coroutine the production method launches, **so that** a regression in the production
job's cancellation wiring is caught instead of a regression in a copy-pasted local `Job`.

**Acceptance Criteria**:
- AC-01, AC-02, AC-03, AC-05, AC-06 (as they apply to this test):
  - *Given* a real `StelekitViewModel` built with `vmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())`, a real `JournalService(pageRepo, blockRepo, clock = fakeClock)`, and `fakeClock = FakeClock(LocalDate(2026, 5, 29).atStartOfDayIn(tz) - 1.milliseconds)` (seeding `lastJournalDate = May 28`, matching the reference test's seeding trick), and `vm.startMidnightBoundaryWatcher(fakeClock)` has been called followed by a `delay(200)` race-mitigation yield (per `research/pitfalls.md` §3),
    *When* the test calls `vmScope.cancel()` **before** advancing the clock, then calls `fakeClock.advance(1001.milliseconds)` to cross into May 29, then waits a fixed `delay(1200)` settle window,
    *Then* `pageRepo.getJournalPageByDate(LocalDate(2026, 5, 29)).first().getOrNull()` is `null` — proving cancellation stopped the *production* job (not a local reimplementation) before it could call `ensureTodayJournal()`.
  - AC-01 concrete failure example for this test: if `startMidnightBoundaryWatcher`'s `scope.launch(...)` call (`StelekitViewModel.kt:1968`) were changed to launch on an unrelated scope (e.g. a module-level `GlobalScope.launch` instead of `scope.launch`), `vmScope.cancel()` would no longer stop the watcher, the May 29 journal page would still be created, and this test's `assertNull`/`getOrNull() == null` assertion would fail — catching the regression that AC-01 requires.
  - AC-06: the test must not touch `pageRepo`/`fakeClock` instances used by any other test (each test builds its own, per `research/pitfalls.md` §2 — no shared class-level state to introduce).

**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`

##### Task 1.1.2a: Rewrite the cancellation test to drive `vm.startMidnightBoundaryWatcher` (~5 min)
- Replace the entire function `` `midnight watcher is cancelled when scope is cancelled`() = runTest { ... } `` (current lines 770-796, including its `@OptIn` annotation) with a `runBlocking` test that:
  1. Builds `pageRepo`/`blockRepo`/`searchRepo`/`fs`/`loader`/`writer` exactly as the reference test at (current) line 841-847 does (do not reuse `buildMinimalViewModel()` — it does not expose a cancellable `vmScope` or accept a `journalService` override).
  2. Seeds `fakeClock = FakeClock(LocalDate(2026, 5, 29).atStartOfDayIn(tz) - 1.milliseconds)`.
  3. Builds `journalService = JournalService(pageRepo, blockRepo, clock = fakeClock)`.
  4. Builds `vmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())` and the `vm` with `scope = vmScope, journalService = journalService`.
  5. Wraps the remainder of the body in `try { ... } finally { vmScope.cancel() }` (per Story 1.1.4's disposition, applied here too since this is new code).
  6. Calls `vm.startMidnightBoundaryWatcher(fakeClock)`, then `kotlinx.coroutines.delay(200)`.
  7. Calls `vmScope.cancel()`.
  8. Calls `fakeClock.advance(1001.milliseconds)`.
  9. Calls `kotlinx.coroutines.delay(1200)`.
  10. Asserts `pageRepo.getJournalPageByDate(LocalDate(2026, 5, 29)).first().getOrNull()` is `null`, with an assertion message stating cancellation must stop the production job.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`

---

#### Story 1.1.3: `lastJournalDate` guard test drives the real production field
**As a** maintainer of the midnight-boundary guard, **I want** the "already handled today" skip
behavior tested against `StelekitViewModel`'s real `lastJournalDate` field and real seeding logic,
**so that** a guard-condition bug (e.g. an inverted `==`) is caught instead of a bug in a
test-local `var` copy of the same idea.

**Acceptance Criteria**:
- AC-01, AC-02, AC-03, AC-04, AC-05, AC-06:
  - *Given* `fakeClock = FakeClock(LocalDate(2026, 5, 29).atStartOfDayIn(tz) - 1.milliseconds)` (so the production seed line `lastJournalDate = clock.now().toLocalDateTime(tz).date` at `StelekitViewModel.kt:1972` sets `lastJournalDate = LocalDate(2026, 5, 28)`), and `vm.startMidnightBoundaryWatcher(fakeClock)` called followed by the `delay(200)` race-mitigation yield,
    *When* the test does **not** advance `fakeClock` for a fixed `delay(1200)` window (so when the watcher's first `delay(millisUntilNextMidnight(fakeClock))` elapses in real time, `clock.now()` still resolves to May 28, and the guard at `StelekitViewModel.kt:1978` (`if (today == lastJournalDate) continue`) sees `today == May 28 == lastJournalDate` and skips),
    *Then* `pageRepo.getJournalPageByDate(LocalDate(2026, 5, 28)).first().getOrNull()` is `null` — proving the crossing was skipped, not merely "not yet reached" (AC-04: this exercises the real field at `StelekitViewModel.kt:1956`, not a local `var`).
  - *Given* the above skip has been confirmed, *When* the test calls `fakeClock.advance(1001.milliseconds)` (crossing into `LocalDate(2026, 5, 29)`) and then polls with `withTimeout(8000) { while (pageRepo.getJournalPageByDate(LocalDate(2026, 5, 29)).first().getOrNull() == null) { delay(50) } }`, *Then* the poll completes with a non-null page whose `journalDate == LocalDate(2026, 5, 29)` — proving the guard does not wrongly suppress the *next* real crossing after correctly skipping the first.
  - AC-01 concrete failure example: if `StelekitViewModel.kt:1978`'s guard were inverted to `if (today != lastJournalDate) continue`, the May 28 window would instead call `ensureTodayJournal()` and create a May 28 journal page, so the first assertion (`getJournalPageByDate(May 28) == null`) would fail immediately — catching the inversion without waiting for the second crossing.
  - AC-05 timing budget: total real wall-clock time for this test is ~200 ms (yield) + 1200 ms (skip window) + up to 8000 ms (positive poll, generous per `research/pitfalls.md` §1's "size `withTimeout` proportionally... 8000-10000 ms for two crossings" guidance) ≈ 9.4 s worst case, well inside Gradle's default test timeout.

**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`

##### Task 1.1.3a: Rewrite the `lastJournalDate` guard test against the real field (~5 min)
- Replace the entire function `` `midnight watcher skips call when lastJournalDate already equals today`() = runTest { ... } `` (current lines 798-838, including its `@OptIn` annotation and local `var lastJournalDate`) with a `runBlocking` test that:
  1. Builds `pageRepo`/`blockRepo`/`searchRepo`/`fs`/`loader`/`writer` as in Task 1.1.2a / the reference test.
  2. Seeds `fakeClock = FakeClock(LocalDate(2026, 5, 29).atStartOfDayIn(tz) - 1.milliseconds)`.
  3. Builds `journalService = JournalService(pageRepo, blockRepo, clock = fakeClock)`, `vmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())`, and `vm` wired the same way.
  4. Wraps the body in `try { ... } finally { vmScope.cancel() }`.
  5. Calls `vm.startMidnightBoundaryWatcher(fakeClock)`, then `delay(200)`.
  6. Waits `delay(1200)` **without** advancing `fakeClock`.
  7. Asserts `pageRepo.getJournalPageByDate(LocalDate(2026, 5, 28)).first().getOrNull() == null` with a message noting this proves the guard skipped (not "hasn't run yet"), since the loop's first `delay()` is bounded to ~1000 ms real time by the 1-ms-before-midnight seeding trick.
  8. Calls `fakeClock.advance(1001.milliseconds)`.
  9. Runs `withTimeout(8000) { while (pageRepo.getJournalPageByDate(LocalDate(2026, 5, 29)).first().getOrNull() == null) { delay(50) } }`.
  10. Asserts the resulting page is non-null with `journalDate == LocalDate(2026, 5, 29)`.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`

---

#### Story 1.1.4: Close the try/finally cleanup gap in the reference test
**As a** maintainer of this test file, **I want** the existing correct test at (current) line 841
to also clean up on an assertion failure, **so that** the same coroutine-leak risk documented in
`research/pitfalls.md` §2 is not left in the one test everyone else is told to copy.

**Acceptance Criteria**:
- AC-06 (no regression to the existing correct test): behavior and assertions of the line-841 test
  are otherwise unchanged.
  - *Given* the reference test `` `midnight watcher calls ensureTodayJournal via real startMidnightBoundaryWatcher` `` (current lines 841-904), *When* any assertion in its body (e.g. `assertNotNull(page, ...)` at current line 900) throws before reaching `vmScope.cancel()` at current line 903, *Then* `vmScope.cancel()` still executes because the body from immediately after `vmScope` is constructed through the final assertion is wrapped in `try { ... } finally { vmScope.cancel() }` — no leaked `Dispatchers.Default` watcher coroutine survives a test failure.
  - Running the test with all existing assertions intact (happy path) produces identical pass/fail behavior to before this change — the `finally` block is purely additive cleanup, not a behavior change.

**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`

##### Task 1.1.4a: Wrap the reference test body in try/finally (~3 min)
- In the test `` `midnight watcher calls ensureTodayJournal via real startMidnightBoundaryWatcher`() = runBlocking { ... } `` (current lines 841-904), wrap everything from immediately after `val vmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())` (current line 864) through the final `assertEquals(tomorrow, page.journalDate)` (current line 901) in `try { ... } finally { vmScope.cancel() }`, and remove the now-redundant standalone `vmScope.cancel()` call at current line 903 (its job moves into the `finally` block).
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`

---

#### Story 1.1.5: Remove now-unused imports and verify the full test class
**As a** reviewer of this diff, **I want** no dangling unused imports left behind by removing the
three local-loop reimplementations, and confirmation that the full test class still passes,
**so that** the change is a clean, verified diff rather than a claim.

**Acceptance Criteria**:
- AC-05: `./gradlew jvmTest --tests "dev.stapler.stelekit.db.GraphLoaderProgressiveTest"` passes.
  - *Given* Stories 1.1.1-1.1.4 are complete, *When* `./gradlew jvmTest --tests "dev.stapler.stelekit.db.GraphLoaderProgressiveTest"` is run, *Then* the command exits 0 and every test in the class (including the untouched tests such as `` `millisUntilNextMidnight returns positive value less than 24h` `` and the modified reference test) reports as passed in the test report.
- AC-02 (verification): *Given* the rewrite is complete, *When* running `grep -n "while (isActive)" kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`, *Then* the command produces no output.
- Unused-import hygiene (not tied to a specific AC, but a direct consequence of removing the 3 local loops): after Tasks 1.1.1a/1.1.2a/1.1.3a, the tokens `ExperimentalCoroutinesApi`, `isActive`, `launch`, `advanceTimeBy` (`kotlinx.coroutines.test`), `DateTimeUnit`, `toLocalDateTime`, `plus` (`kotlinx.datetime`), `hours`, and `minutes` (`kotlin.time.Duration.Companion`) no longer appear anywhere in the file outside their own `import` lines (verified below) and their imports should be removed.

**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`

##### Task 1.1.5a: Remove unused imports (~3 min)
- Delete these import lines (verified via `awk 'NR<738 || NR>839' kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt | grep -n "isActive\|DateTimeUnit\|toLocalDateTime\|advanceTimeBy\|\\blaunch(\|ExperimentalCoroutinesApi"` and a matching check for `hours`/`minutes`/`plus`, both returning only their own import-line matches before this rewrite — i.e. these tokens are used *only* inside the three tests being deleted/rewritten):
  - `import kotlinx.coroutines.ExperimentalCoroutinesApi`
  - `import kotlinx.coroutines.isActive`
  - `import kotlinx.coroutines.launch`
  - `import kotlinx.coroutines.test.advanceTimeBy`
  - `import kotlinx.datetime.DateTimeUnit`
  - `import kotlinx.datetime.plus`
  - `import kotlinx.datetime.toLocalDateTime`
  - `import kotlin.time.Duration.Companion.hours`
  - `import kotlin.time.Duration.Companion.minutes`
- Do **not** remove `kotlinx.coroutines.test.runTest` or `kotlinx.coroutines.ExperimentalCoroutinesApi`'s sibling usages elsewhere if any remain — re-run the same `awk`/`grep` check after editing to confirm `runTest` is still used (it is, by `` `test progressive loading phases` `` at current line 629) and is therefore kept.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`

##### Task 1.1.5b: Run the test class and confirm green (~3 min)
- Run `./gradlew jvmTest --tests "dev.stapler.stelekit.db.GraphLoaderProgressiveTest"` (from the repo root, using the Gradle wrapper per this repo's `CLAUDE.md`).
- Confirm the task reports `BUILD SUCCESSFUL` and the HTML/XML test report shows every test in `GraphLoaderProgressiveTest` passed, including the 2 rewritten tests, the reference test with its new `try/finally`, and the untouched `millisUntilNextMidnight` tests.
- If a rewritten test times out or asserts incorrectly, diagnose against `research/pitfalls.md` (seeding race, timeout sizing) before changing assertions — do not loosen a timeout or assertion to make a real bug disappear.
- Files: none (verification only; no file changes expected if Tasks 1.1.1a-1.1.5a were done correctly).
