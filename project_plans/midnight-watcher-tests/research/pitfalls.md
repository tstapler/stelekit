# Pitfalls: rewriting the midnight-watcher tests to drive the real production method

Scope: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt` tests at
lines 740, 772, 800, using the correct pattern at line 841 as reference. Production method:
`StelekitViewModel.startMidnightBoundaryWatcher` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt:1966`).

## 1. Flakiness risk of real-time coroutine tests

**Why `runTest`'s virtual clock can't be used.** `startMidnightBoundaryWatcher` launches on
`scope.launch(...)` (`StelekitViewModel.kt:1968`) where `scope` is whatever `CoroutineScope` was
injected via `StelekitViewModelDependencies` — in the reference test this is a real
`CoroutineScope(Dispatchers.Default + SupervisorJob())` (`GraphLoaderProgressiveTest.kt:864`), not
the `TestScope`/`StandardTestDispatcher` that `runTest` provides. `advanceTimeBy`/`advanceUntilIdle`
only fast-forward coroutines running on the test dispatcher; a `delay()` inside a coroutine on
`Dispatchers.Default` is real wall-clock time regardless. This is exactly why line 841 uses
`runBlocking`, not `runTest` — the three tests being rewritten currently use `runTest`
(`GraphLoaderProgressiveTest.kt:740`, `772`, `800`) purely because their *local* reimplementation
of the loop runs `launch { }` inside the test's own coroutine scope, which *is* on the test
dispatcher. Once they call the real `startMidnightBoundaryWatcher`, they must switch to
`runBlocking` (or drop `runTest` entirely), same as line 841.

**Is `MIN_MIDNIGHT_DELAY_MS` already small enough?** Yes — `StelekitViewModel.kt:1993`:
`private const val MIN_MIDNIGHT_DELAY_MS = 1_000L`, applied via `coerceAtLeast` in
`millisUntilNextMidnight` (`StelekitViewModel.kt:1963`). It is a `private const val` in the
companion object — not overridable/injectable — so the only lever a test has is *where it places
the fake clock*, not shrinking the constant. The reference test exploits this by seeding
`FakeClock` 1 ms before midnight (`GraphLoaderProgressiveTest.kt:855`: `LocalDate(2026, 5,
29).atStartOfDayIn(tz) - 1.milliseconds`), so `millisUntilNextMidnight` computes ~1 ms, which
`coerceAtLeast(1_000L)` bumps to exactly 1000 ms. The real wait is bounded at *almost exactly* one
second per midnight crossing, regardless of how many crossings a test needs — the same trick
must be reused for the cancellation and skip-guard rewrites. Do **not** place the fake clock
further from midnight (e.g. minutes before, as the *old* local-reimplementation tests did at
`:743`, `:776`, `:805`) — that inflates the real per-crossing wait proportionally, since
`millisUntilNextMidnight` is a real formula, not mocked.

**What goes wrong on CI with real delays.** CI runs on `ubuntu-latest`
(`.github/workflows/ci.yml:22`) and Gradle is configured with
`maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)`
(`kmp/build.gradle.kts:465`, `:490`) — multiple test JVM forks contend for CPU on a
2-vCPU-class shared runner. Under that contention:
- A `Dispatchers.Default` coroutine's `delay(1000)` can resume noticeably late (GC pause, fork
  scheduling, host noisy-neighbor) — usually tens to low-hundreds of ms, not multi-second, but
  additive across N crossings in one test.
- The reference test bounds total risk with `withTimeout(5000)` around the poll loop
  (`GraphLoaderProgressiveTest.kt:894`) against a nominal ~1200 ms expected wait (200 ms seed
  yield + 1000 ms delay + poll interval) — roughly 4x headroom. A rewritten test that chains
  *two* midnight crossings (skip-guard test, requirement item 2) doubles the nominal wait to
  ~2200 ms against the same 5000 ms budget — still comfortable, but tighter than the single
  reference test. Size any multi-crossing `withTimeout` proportionally (e.g. 8000–10000 ms for
  two crossings) rather than reusing 5000 ms unchanged.
- Prefer polling with a short interval (the reference test's `delay(50)` at
  `GraphLoaderProgressiveTest.kt:896`) over a single long sleep-then-check — it fails fast on the
  common case and only pays the full timeout when something is actually broken.

## 2. Test isolation / leaked coroutines

`GraphLoaderProgressiveTest` has **no `@BeforeTest`/`@AfterTest`** (confirmed by grep — none in
the file) and **no class-level shared state**: every test method builds its own `pageRepo`,
`blockRepo`, `vmScope`, and `StelekitViewModel` locally. This is good — there's no shared
`midnightWatcherJob` or `lastJournalDate` field across test methods, since each test gets its own
`StelekitViewModel` instance and thus its own private `midnightWatcherJob`/`lastJournalDate`
(`StelekitViewModel.kt:1955-1956`) scoped to that instance.

**The real leak risk is per-test, not cross-test.** The reference test only cancels its watcher
at the very end, unconditionally after all assertions (`vmScope.cancel()` at
`GraphLoaderProgressiveTest.kt:903`). If an assertion earlier in the same test body throws
(`assertEquals`/`assertNotNull`/`withTimeout` timeout), the test function exits via exception and
`vmScope.cancel()` on line 903 **never runs**. The watcher coroutine (real `Dispatchers.Default`
thread pool work, `CoroutineName("midnight-boundary-watcher")` at `StelekitViewModel.kt:1968`)
keeps running for the remainder of the JVM test process, holding references to that test's
`pageRepo`/`fakeClock`/`journalService`. Consequences:
- It doesn't corrupt *other* tests' data (each test has its own repo instances), but it does leak
  a live coroutine + thread-pool work item, which can show up as "Test worker leaked" warnings
  from Gradle's test JVM, or contribute to thread-pool exhaustion if several tests in the same
  fork leak watchers this way (three rewritten tests × one gets a failure = one leaked infinite
  `while (isActive)` loop each, forever re-arming `delay()` since nothing ever crosses a boundary
  again after the fake clock stops advancing — low CPU but permanent).
- **Recommendation:** wrap the body in `try { ... } finally { vmScope.cancel() }`, or use
  `vmScope.use { }`-style cleanup, so cancellation happens on both the happy path and any
  assertion failure. This is a **deviation from the line-841 pattern** worth calling out
  explicitly in the plan, since line 841 itself doesn't do this — it's a latent gap in the
  reference implementation, not something to blindly copy three more times.

**`FakeClock` is not thread-safe.** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/testing/FakeClock.kt:7-9`
holds `instant` as a plain (non-`@Volatile`) `var`:
```kotlin
class FakeClock(private var instant: Instant) : Clock {
    override fun now(): Instant = instant
    fun advance(duration: Duration) { instant += duration }
}
```
The test thread calls `fakeClock.advance(...)` while the watcher coroutine (a different OS
thread, since it's on `Dispatchers.Default`) calls `fakeClock.now()` inside the loop
(`StelekitViewModel.kt:1972`, `:1977`). Without `@Volatile` or other synchronization, the JMM does
not guarantee the watcher thread ever observes the test thread's write — it happens to work in
practice because thread-pool dispatch (submitting/resuming a coroutine via an `Executor`)
typically introduces a happens-before edge, but this is incidental to the executor
implementation, not a language guarantee. This is a pre-existing property of `FakeClock`, not
something to fix as part of this test-only task (out of scope per requirements.md), but it's worth
flagging: if the rewritten tests become flakier than the reference test with no other explanation,
this is a plausible root cause, and any fix would live in `FakeClock.kt`, not in the test file.

## 3. Race between watcher seeding `lastJournalDate` and the test thread advancing the clock

`startMidnightBoundaryWatcher` seeds `lastJournalDate = clock.now().toLocalDateTime(tz).date`
(`StelekitViewModel.kt:1972`) **before** entering the `while (isActive)` loop, on the coroutine
that was just `launch`ed onto `Dispatchers.Default`. `launch` does not guarantee the child
coroutine has started executing by the time the launching call returns — dispatch is asynchronous.
The existing reference test documents this exact race at `GraphLoaderProgressiveTest.kt:879-891`:

```
// Give the watcher coroutine time to start on Dispatchers.Default and seed
// lastJournalDate (= May 28) BEFORE we advance the clock past midnight.
// Without this yield, the test thread can race ahead and advance fakeClock to
// May 29 before the watcher reads it, causing lastJournalDate to be seeded as
// May 29 and the midnight-crossing check to be skipped.
kotlinx.coroutines.delay(200)
```

If the test thread calls `fakeClock.advance(...)` before the watcher's seed line executes, the
seed reads the *already-advanced* date, and the very first crossing the test expects to fire
looks like a no-op crossing to the watcher (today == lastJournalDate at seed time == today after
advance) — a false skip that has nothing to do with the guard logic under test.

**This race is more dangerous for the new skip-guard test than it was for the reference test**,
because the skip-guard test's entire premise is "assert a crossing was skipped." A seeding race
that accidentally causes an *unintended* skip is indistinguishable from the *intended* skip the
test is trying to verify — the test would pass for the wrong reason, and wouldn't fail if the
guard were removed (violates AC-01). Concretely: if the test seeds the clock at "1 ms before
midnight on day N" intending `lastJournalDate` to seed as day N, but the 200 ms yield is skipped
or too short and the watcher reads the clock late (after some other advance), the seed could land
on day N+1, making the "first crossing should skip" assertion trivially true regardless of the
guard's correctness.

**Mitigation (same as line 886):** keep the `delay(200)` yield between `startMidnightBoundaryWatcher(...)`
and the first `fakeClock.advance(...)` in all three rewritten tests, not just the ones that
"need" it for correctness — it's cheap insurance against a race that produces false positives, not
just wrong dates. For the skip-guard test specifically, also assert (via
`pageRepo.getJournalPageByDate`) that the *seed date's* journal is untouched/absent immediately
after the yield and before the first `advance()`, to catch a seed that already advanced past where
the test expects it — this converts a silent race into a fast, loud test failure instead of a
false-pass.

## 4. Distinguishing "watcher hasn't run yet" from "watcher ran and correctly skipped"

This is the core design risk requirements.md flags at lines 68-72. The observation mechanism is
indirect: `pageRepo.getJournalPageByDate(date)` returning `null` is consistent with *both* "the
watcher hasn't reached that crossing yet" and "the watcher crossed and correctly skipped calling
`ensureTodayJournal`." A naive "wait N ms, assert still null" is inherently a negative-result test
— its confidence is capped by how long you're willing to wait, and making it *longer* to reduce
false negatives directly increases suite runtime and CI flakiness surface (competing with pitfall
1's contention concerns).

**Two concrete approaches, in order of preference:**

**(a) Bound the wait using the same 1-ms-before-midnight trick, and chain the assertion to the
*next* observable event instead of a bare timeout.** Since `millisUntilNextMidnight` is
deterministic once the fake clock position is fixed, you know almost exactly when the watcher
*should* wake up for the first (skipped) crossing and again for the second (fired) crossing.
Structure the test as:
1. Seed fake clock 1 ms before midnight on day N → watcher seeds `lastJournalDate = N`.
2. Yield 200 ms (race mitigation from §3).
3. Advance clock to day N+1, wait ~1000 ms (the bounded first-crossing delay) — this is the
   "skip" crossing since `lastJournalDate` was already seeded to a date the guard treats as
   equal. Use a short, tight assertion window here (e.g. `delay(1200)` then assert page for N+1
   is absent) rather than a generous `withTimeout` — you're not waiting to see if something
   *might* happen, you're confirming it stays absent through a window you know the loop has
   already woken up and decided within.
4. Advance clock to day N+2, and *now* use `withTimeout(5000) { poll for page N+2 }` exactly like
   the reference test — this is a positive assertion, so a generous timeout is safe and won't
   mask a bug (a bug here means it never becomes true, and the timeout still fails the test).
   The fact that step 4 succeeds retroactively validates that step 3's window was long enough for
   the loop to have processed that crossing (if it hadn't, step 4's second `advance()` would land
   before the watcher finished processing the first, and the dates would desync) — this is the
   same self-checking structure the reference test already relies on implicitly.

**(b) Add a lightweight call-count observation point instead of relying purely on repository
state**, per requirements.md's own suggested fallback (lines 70-72): wrap `journalService` in a
thin counting decorator that forwards to the real `JournalService.ensureTodayJournal()` and
increments an `AtomicInteger`, constructed the same way `StelekitViewModelDependencies` already
accepts a `journalService` override (`GraphLoaderProgressiveTest.kt:875`). This turns "assert
nothing happened" into "assert count is still 0 after N ms, then count becomes 1 after the next
crossing" — functionally similar to (a) but makes the *first* crossing's completion observable
independent of repository timing (the counter increments synchronously inside the same coroutine
that would call `ensureTodayJournal`, so there's no separate polling loop needed for the skip
check — only a short fixed delay to let the loop finish its `continue` branch). This still doesn't
eliminate the "wait and see nothing" window entirely, but shrinks it to just past the known wake-up
time rather than a generic guess, and avoids conflating "the DB write settled" with "the watcher
decided" as two different sources of latency to account for.

Recommend (a) first since it requires no new test scaffolding and stays inside the "no production
changes" constraint most conservatively; fall back to (b) only if (a) proves flaky in practice,
per requirements.md's explicit permission to add a minimal test-only hook if needed.

## 5. Detekt / lint exposure

Detekt's `source.setFrom(...)` in `kmp/build.gradle.kts:785-790` lists only `src/commonMain/kotlin`,
`src/jvmMain/kotlin`, `src/androidMain/kotlin`, `src/iosMain/kotlin` — **`src/jvmTest/kotlin` is
not analyzed by detekt at all**, confirmed by reading the `detekt { }` block directly. So none of
the coroutine rules under `coroutines:` in `kmp/config/detekt/detekt.yml:107-123`
(`SleepInsteadOfDelay`, `GlobalCoroutineUsage`, `SuspendFunSwallowedCancellation`, etc.) can fire
on this test file regardless of what real-time `delay()` calls the rewrite adds. `SleepInsteadOfDelay`
specifically flags `Thread.sleep()` inside suspend contexts (recommending `delay()` instead), so
even if test sources were linted, using `kotlinx.coroutines.delay()` — as the reference test and
this rewrite both do — is the *compliant* direction, not a violation. No lint changes are needed
or expected as part of this task.

## Summary for planning

- The 1-ms-before-midnight `FakeClock` seeding trick bounds each crossing's real wait to ~1000 ms
  regardless of the constant `MIN_MIDNIGHT_DELAY_MS` (`StelekitViewModel.kt:1993`, private/not
  injectable) — reuse it for all three rewrites, and don't seed the clock minutes-early as the old
  local-loop tests did.
- Wrap each rewritten test body in `try { ... } finally { vmScope.cancel() }` — the reference test
  at line 841 only cancels on the happy path, and rewriting three more tests without fixing this
  triples the exposure to a leaked infinite watcher coroutine on assertion failure.
- The skip-guard test's core risk is a false-pass, not a timeout: an unmitigated seed race
  (`StelekitViewModel.kt:1972` racing the test thread's first `advance()`, documented at
  `GraphLoaderProgressiveTest.kt:879-891`) can make the guard look like it fired when it actually
  just got lucky on seeding — keep the `delay(200)` yield and consider asserting the seed date's
  page is absent immediately after it, before advancing.
