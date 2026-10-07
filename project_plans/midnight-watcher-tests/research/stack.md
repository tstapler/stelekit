# Test-stack research: midnight-watcher-tests

## 1. Current imports/usage in `GraphLoaderProgressiveTest.kt`

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`

Already imported (lines 1-50): `InMemoryPageRepository`, `InMemoryBlockRepository`,
`InMemorySearchRepository` (`dev.stapler.stelekit.repository.*`), `PlatformFileSystem`
(`dev.stapler.stelekit.platform`), `InMemorySettings` (`dev.stapler.stelekit.ui.fixtures`),
`FakeClock` (`dev.stapler.stelekit.testing`), `StelekitViewModel` /
`StelekitViewModelDependencies` (`dev.stapler.stelekit.ui`), `JournalService`
(`dev.stapler.stelekit.repository`), plus coroutine-test imports
(`kotlinx.coroutines.test.advanceTimeBy`, `kotlinx.coroutines.test.runTest`),
`kotlinx.coroutines.runBlocking`, `withTimeout`, and `kotlinx.datetime.*` / `kotlin.time.*`.
No new imports or fakes are needed for the rewrite — everything the fix requires is already
imported or trivially reachable from the same package paths.

## 2. `FakeClock`

`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/testing/FakeClock.kt`:

```kotlin
class FakeClock(private var instant: Instant) : Clock {
    override fun now(): Instant = instant
    fun advance(duration: Duration) { instant += duration }
}
```

Minimal `kotlin.time.Clock` implementation — mutable `Instant`, no listeners/callbacks.
`advance(Duration)` mutates in place; `now()` reads the current value. This is exactly what
`StelekitViewModel.millisUntilNextMidnight(clock: Clock)` and
`startMidnightBoundaryWatcher(clock: Clock)` accept as an injectable `Clock`, and it's what
`JournalService` also accepts (`clock = fakeClock` constructor param, used at line 860 of the
test file) so "today" inside `ensureTodayJournal()` resolves from the same fake time.

## 3. In-memory test doubles

All confirmed present and directly usable with no changes:
- `InMemoryPageRepository`, `InMemoryBlockRepository`, `InMemorySearchRepository` —
  `dev.stapler.stelekit.repository` package (jvmTest or shared test source, referenced
  directly by existing tests with a no-arg constructor).
- `InMemorySettings` — `dev.stapler.stelekit.ui.fixtures`.
- `PlatformFileSystem` — real `dev.stapler.stelekit.platform.PlatformFileSystem` (JVM actual),
  used directly (not a fake) in the existing real-path test at line 845, since
  `GraphLoader`/`GraphWriter` need a concrete `FileSystem` and the test never touches disk in
  a way that matters for the watcher assertions.

No new fakes/helpers are required for the fix — `buildMinimalViewModel()` (line 692) already
assembles pageRepo/blockRepo/searchRepo/fs/loader/writer/scope into a real
`StelekitViewModel`, and the line-841 test shows the pattern for wiring a `JournalService`
with a shared `FakeClock` when the test needs to observe `ensureTodayJournal()` side effects
via repository state.

## 4. Coroutines/Kotlin versions

- `settings.gradle.kts:9`: `kotlin("multiplatform") version "2.3.21"`.
- `kmp/build.gradle.kts`: `kotlinx-coroutines-core:1.10.2` and
  `kotlinx-coroutines-test:1.10.2` (repeated across jvmTest/commonTest/androidUnitTest source
  sets, lines 60/106/252/270/282/296).

`kotlinx-coroutines-test` 1.10.x is well past the 1.6 rewrite that introduced the current
`runTest` + `TestScheduler` API (`advanceTimeBy`, `advanceUntilIdle`, `runCurrent`, shared
`TestCoroutineScheduler` across dispatchers). `advanceTimeBy` semantics used at test lines
758-767 (advance-then-assert, no `runCurrent()` needed since `advanceTimeBy` itself runs
pending tasks up to the new virtual time) are standard for this version — no API gap.

## 5. Why line 841's test uses `runBlocking` + real time, not `runTest`

Read lines 841-905. Key points:
- `vmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())` — a **real** dispatcher.
- Comment at lines 862-863: *"vmScope uses real Dispatchers.Default so its internal
  observe-coroutines do not join the test scheduler and cause advanceUntilIdle() to spin
  forever."*
- Confirmed why this concern is real: `StelekitViewModel.init {}` launches roughly two dozen
  `scope.launch { ... }` blocks (lines 284, 320, 341, 379, 411, 550, 561, 570, 579, ... through
  792+), mostly infinite `Flow.collect` loops observing repositories/settings. If `scope` were
  backed by the shared `TestCoroutineScheduler` (e.g. `StandardTestDispatcher(testScheduler)`
  passed into `StelekitViewModelDependencies`), those collectors would share the virtual clock
  with the watcher coroutine. `advanceUntilIdle()` would then have to wait for all of them to
  go idle, which is fragile-to-impossible with infinite `Flow` collectors mixed with real
  suspension points (repository flows are backed by real coroutine primitives, not scheduled
  virtual-time delays) — hence "spin forever."
- Given that, the test uses the real production entry point but real time: `millisUntilNextMidnight()` is engineered to return exactly `1000ms` by placing `FakeClock` 1ms before
  midnight (`coerceAtLeast(1_000L)` floor), so the watcher's `delay()` call is a real 1-second
  sleep — short enough to keep the test fast but real, not virtual.
- The test then polls repository state (`pageRepo.getJournalPageByDate(...).first()`) inside
  `withTimeout(5000) { while (...) { delay(50) } }` rather than asserting synchronously, since
  there's no virtual clock to fast-forward past the async repository write.
- Tradeoff (to state explicitly, not to solve): this makes the test ~1.2s slower than a
  virtual-time test and introduces a small window for CI flakiness under load (the `delay(200)`
  guard at line 886 exists specifically to avoid a startup race — see its comment). This is the
  known cost of testing a coroutine launched on a real dispatcher; it is already accepted by the
  existing correct test and is the precedent for the three replacement tests.

## 6. Can the cancellation and lastJournalDate-guard tests use virtual time instead?

**No — not while sharing `scope` with `StelekitViewModel.init`'s other launches.** Because
`startMidnightBoundaryWatcher` launches on `scope.launch(...)` where `scope` is
`StelekitViewModelDependencies.scope` (`StelekitViewModel.kt:117`, `deps.scope`), and that same
`scope` instance also carries every other `init`-time observer coroutine, any attempt to make
`delay()` inside the watcher virtual-time-controllable requires `scope` to run on a dispatcher
backed by `TestCoroutineScheduler` — which reintroduces exactly the "spin forever" hazard the
existing test's comment documents. There is no narrower injection point: `startMidnightBoundaryWatcher(clock)` takes a `Clock` parameter (already fakeable) but not a
`CoroutineDispatcher` or scope parameter — the launch dispatcher is fixed to whatever `scope`
was constructed with.

**Conclusion: all three rewritten tests should follow the exact same `runBlocking` + real
`Dispatchers.Default` scope + `FakeClock` + `withTimeout`-polling pattern as the line-841
test.** Concretely:
- **Cancellation test**: build the VM/scope/FakeClock as at line 841, call
  `vm.startMidnightBoundaryWatcher(fakeClock)`, then instead of cancelling a locally-launched
  `Job`, cancel the *scope* (`vmScope.cancel()`) — mirroring what the production code path
  actually exposes (there's no public accessor for `midnightWatcherJob` itself outside the
  class). Advance the clock and assert (e.g. via a short real-time wait) that no new journal
  page is created after cancellation. If a handle on the actual `Job` is wanted for a tighter
  assertion (`job.isCancelled`/`isActive`), that would require exposing
  `midnightWatcherJob` for tests (not currently public) — but cancelling `vmScope` and
  asserting no further side effects is sufficient and matches the existing style; no production
  API change needed.
- **lastJournalDate-guard test**: build the VM the same way, but pre-seed the guard by starting
  the `FakeClock` such that the watcher's initial seed line (`lastJournalDate = clock.now()...`,
  `StelekitViewModel.kt:1972`) captures the date that a first `advance()` will also land on —
  i.e. reproduce the "already on today" condition using the real seeding logic instead of a
  local `var lastJournalDate`. Poll (with `withTimeout`) to confirm no journal page is created
  for the skipped date, then advance past a *second* boundary and confirm the page for that next
  date **does** appear — proving the guard is read from the real `StelekitViewModel` field
  (`lastJournalDate`, `StelekitViewModel.kt:1956`) and not a test-local copy.
- Both keep `runTest`/`advanceTimeBy` out of the loop entirely for the parts that touch
  `scope.launch`; virtual time is not usable here without a broader change to how
  `StelekitViewModel` takes its scope/dispatcher, which is out of scope for this
  Complexity-1 fix.

## Summary

- All test doubles needed (`FakeClock`, `InMemoryPageRepository`/`InMemoryBlockRepository`/`InMemorySearchRepository`, `InMemorySettings`, `PlatformFileSystem`) already exist and are already imported in `GraphLoaderProgressiveTest.kt`; no new fakes, dependencies, or imports required.
- `kotlinx-coroutines-test:1.10.2` (Kotlin 2.3.21) supports `runTest`/`advanceTimeBy` fully, but virtual time cannot be used for the two tests being fixed because `startMidnightBoundaryWatcher` launches on `StelekitViewModelDependencies.scope`, which also carries ~20 infinite `init`-time `Flow` collectors in `StelekitViewModel` — sharing a `TestCoroutineScheduler` there risks `advanceUntilIdle()` hanging, which is exactly why the existing line-841 reference test deliberately uses `Dispatchers.Default` + `runBlocking` + real ~1s delay + `withTimeout` polling instead.
- The fix for all three tests (lines 740, 772, 800) should copy the line-841 pattern verbatim: real `StelekitViewModel` + shared `FakeClock` wired into `JournalService`, call `vm.startMidnightBoundaryWatcher(fakeClock)` directly, drive `fakeClock.advance(...)`, and assert via polling real repository state (`pageRepo.getJournalPageByDate(...).first()`) rather than a local counter/var — cancellation test cancels `vmScope` (no public `Job` accessor exists), and the guard test relies on the production seeding at `StelekitViewModel.kt:1972` instead of a test-local `lastJournalDate`.
