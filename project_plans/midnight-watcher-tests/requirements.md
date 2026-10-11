# Requirements: midnight-watcher-tests

## Complexity

**Complexity 1** — quick task. Test-only change confined to one existing test file
(`GraphLoaderProgressiveTest.kt`), no new production code, no new dependencies, no
user-facing surface, no new architectural seams (the reference pattern already exists
at line 841). Research is scoped to test-stack idioms and coroutine-test pitfalls, not
full architecture/UX/build-vs-buy analysis.

## Problem Statement

`GraphLoaderProgressiveTest` (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`)
contains three tests covering the midnight-boundary journal watcher that each reimplement the
watcher's `while (isActive) { delay(...); ... }` loop locally instead of driving
`StelekitViewModel.startMidnightBoundaryWatcher` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt:1966`).
A fourth test in the same file already exercises the real method correctly and is the pattern
to follow.

Flagged during review of PR #108.

## Affected Tests (current state, ~line numbers)

| Test | Line | Problem |
|---|---|---|
| `midnight watcher calls ensureTodayJournal after simulated day crossing` | 740 | Reimplements the loop locally with a local `callCount`; doesn't call `vm.startMidnightBoundaryWatcher`. Superseded by the existing real-path test below. |
| `midnight watcher is cancelled when scope is cancelled` | 772 | Cancels a locally-launched `Job`, not the job started by `startMidnightBoundaryWatcher`. A bug in the production job's cancellation wiring would not be caught. |
| `midnight watcher skips call when lastJournalDate already equals today` | 800 | Reimplements the `lastJournalDate` guard (`if (afterDate == lastJournalDate) continue`) as a local `var` instead of exercising the production field at `StelekitViewModel.kt:1956,1978`. A guard bypass in production goes undetected. |

## Existing reference implementation (already correct — do not duplicate)

`midnight watcher calls ensureTodayJournal via real startMidnightBoundaryWatcher` (line 841) already:
1. Builds a real `StelekitViewModel` with a real `JournalService` wired to a shared `FakeClock`.
2. Calls `vm.startMidnightBoundaryWatcher(fakeClock)` directly (the production entry point).
3. Advances `fakeClock` past a midnight boundary.
4. Observes the effect of `ensureTodayJournal()` by polling `pageRepo.getJournalPageByDate(...)`
   — i.e. verifies via real repository state, not a mock/fake of `JournalService`.

This test demonstrates the intended pattern for the fix: no new interface, DI seam, or mock
framework is required. `JournalService` is a concrete class already constructible with an
in-memory `PageRepository`/`BlockRepository` and an injectable `Clock`; `StelekitViewModelDependencies`
already accepts a `journalService` override. Observability is via repository side effects, which
is sufficient to detect a broken/deleted `startMidnightBoundaryWatcher`.

## What needs to change

Replace tests at lines 740 and 772 (and rewrite the test at 800) so each drives
`StelekitViewModel.startMidnightBoundaryWatcher` directly, following the pattern already
established at line 841:

1. **Cancellation test**: start the real watcher via `vm.startMidnightBoundaryWatcher(fakeClock)`,
   cancel it (via `vmScope.cancel()`, matching the existing real-path test), advance the clock
   past a midnight boundary, and assert no journal page is created — i.e. no local `Job`/loop
   reimplementation.
2. **`lastJournalDate` guard test**: seed the real watcher (via the production seeding at
   `StelekitViewModel.kt:1972`, which sets `lastJournalDate` to the clock's *current* date at
   watcher start) so the first midnight crossing lands on an already-seen date, and verify via
   `pageRepo.getJournalPageByDate` that `ensureTodayJournal()` was *not* called for that
   crossing, then advance past a second boundary and verify it *is* called.
3. **Simulated day-crossing test**: either delete it (fully superseded by the line-841 test) or
   fold its multi-crossing assertion (3 consecutive midnights) into the real-path test/a new
   real-path variant — reviewer intent is "no local reimplementation survives," not "keep the
   same test count."

Because `startMidnightBoundaryWatcher` currently has no return value or exposed job handle
beyond the private `midnightWatcherJob` field, tests must observe behavior through
`JournalService`'s effects on the (fake/in-memory) repositories, as line 841 already does — not
by inspecting `StelekitViewModel` internals. No production code changes are anticipated; if the
"skip" case can't be observed without a way to distinguish "watcher didn't run yet" from "watcher
ran and correctly skipped," a minimal test-only hook (e.g. an optional call-count callback param,
or asserting on `journalService`'s call count via a thin counting wrapper/spy) may be the smallest
addition — to be confirmed in planning, not assumed here.

## Acceptance Criteria

- AC-01: Each of the three tests (cancellation, `lastJournalDate` guard, and whatever replaces/
  merges the simulated-crossing test) fails if `StelekitViewModel.startMidnightBoundaryWatcher`
  is deleted or its body is broken (e.g. guard condition inverted, `delay` call removed).
- AC-02: No test body contains a local `while (isActive) { ... delay ... }` reimplementation of
  the watcher loop.
- AC-03: Tests call `vm.startMidnightBoundaryWatcher(...)` (or an equivalent production entry
  point) directly, not a copy of its logic.
- AC-04: The `lastJournalDate` guard is exercised against the real production field/behavior,
  not a local `var`.
- AC-05: `./gradlew jvmTest --tests "dev.stapler.stelekit.db.GraphLoaderProgressiveTest"` passes.
- AC-06: No regression to the existing correct test (line 841) or to other tests in the file.

## Out of Scope

- Any change to `startMidnightBoundaryWatcher`'s production behavior (this is a test-only fix).
- Broader refactors of `JournalService` into an interface for mocking, unless research/planning
  determines the acceptance criteria are unreachable without it (not expected — see reference
  implementation above).
