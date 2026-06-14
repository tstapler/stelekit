# Validation Plan: journal-watch-fix

**Date**: 2026-05-29

---

## Requirement → Test Mapping

| Requirement | Test File | Test Name | Type | Scenario |
|-------------|-----------|-----------|------|----------|
| REQ-01: Detect day boundary while app is running | `GraphLoaderProgressiveTest` | `millisUntilNextMidnight returns positive value less than 24h` | Unit | Happy path — helper returns delay in (0, 24h] |
| REQ-01: Detect day boundary while app is running | `GraphLoaderProgressiveTest` | `millisUntilNextMidnight returns at least 1000ms when clock is exactly at midnight` | Unit | Error/edge path — coerceAtLeast(1000) guard fires |
| REQ-01: Detect day boundary while app is running | `GraphLoaderProgressiveTest` | `midnight watcher calls ensureTodayJournal after simulated day crossing` | Integration | Watcher fires once after FakeClock crosses midnight using runTest scheduler |
| REQ-01: Detect day boundary while app is running | `GraphLoaderProgressiveTest` | `midnight watcher calls ensureTodayJournal on second day crossing` | Integration | Watcher fires a second time after advancing 24 more virtual hours |
| REQ-01: Detect day boundary while app is running | `GraphLoaderProgressiveTest` | `midnight watcher is cancelled when scope is cancelled` | Unit | Cancellation — watcher job stops when CoroutineScope is cancelled; no further calls after cancel |
| REQ-02: Invalidate shadow before content read in detectChanges | `FileRegistryTest` | `external write with stale shadow is detected after shadow invalidation` | Unit | Happy path — external sync writes new content; stale shadow is invalidated; change reported |
| REQ-02: Invalidate shadow before content read in detectChanges | `FileRegistryTest` | `own write with shadow is still suppressed after shadow invalidation fix` | Unit | Error/regression path — own-write via markWrittenByUs is still suppressed |
| REQ-03: FileRegistry shadow-stale-read regression test | `FileRegistryTest` | `external write with stale shadow is detected after shadow invalidation` | Unit | Regression guard — same test as REQ-02 happy path; named explicitly as the regression case |
| REQ-03: FileRegistry shadow-stale-read regression test | `FileRegistryTest` | `FakeFsWithShadow externalWrite does not update shadowMap` | Unit | Helper contract — confirms the FakeFsWithShadow test double behaves as specified |
| REQ-03: GraphLoader integration test — ensureTodayJournal via fake clock | `GraphLoaderProgressiveTest` | `midnight watcher calls ensureTodayJournal after simulated day crossing` | Integration | Same as REQ-01 integration row; satisfies the GraphLoader/JournalService integration requirement from REQ-03 |

---

## Detailed Test Specifications

### FileRegistryTest — new tests (Tasks 2.1a + 2.1b)

All tests run in `jvmTest` with `runTest` + `FakeFsWithShadow`.

#### Test 1 — `FakeFsWithShadow externalWrite does not update shadowMap`
**Type**: Unit (helper contract)
**Covers**: REQ-03 (test harness correctness)
**Setup**: Construct `FakeFsWithShadow` with an initial file + shadow entry.
**Action**: Call `fs.externalWrite(path, newContent)`.
**Assert**: `fs.shadowMap[path]` is still the old content; `fs.files[path]!!.content == newContent`.

```kotlin
@Test
fun `FakeFsWithShadow externalWrite does not update shadowMap`() = runTest {
    val fs = FakeFsWithShadow()
    fs.files["/graph/f.md"] = FakeFile("old", 1000L)
    fs.shadowMap["/graph/f.md"] = "old"

    fs.externalWrite("/graph/f.md", "new")

    assertEquals("old", fs.shadowMap["/graph/f.md"],
        "shadowMap must NOT be updated by externalWrite — it simulates stale shadow")
    assertEquals("new", fs.files["/graph/f.md"]!!.content,
        "files map must be updated by externalWrite")
}
```

#### Test 2 — `external write with stale shadow is detected after shadow invalidation`
**Type**: Unit / Regression
**Covers**: REQ-02, REQ-03
**Setup**: `FakeFsWithShadow` with file + matching shadow. Register baseline via `detectChanges`.
**Action**: `fs.externalWrite(...)` (updates file, leaves shadow stale). Call `detectChanges` again.
**Assert**: `changes.changedFiles.size == 1` and `changes.changedFiles[0].content == "- new content from sync"`.
**Fails without fix**: Without `invalidateShadow` before `readFile`, shadow returns old content → hash matches → change silently dropped.

(Full code in `plan.md` Task 2.1b.)

#### Test 3 — `own write with shadow is still suppressed after shadow invalidation fix`
**Type**: Unit / Regression guard
**Covers**: Acceptance criterion 3 (own-write suppression not broken), REQ-02
**Setup**: `FakeFsWithShadow`. Register baseline. Call `fs.ownWrite(...)` (updates both file + shadow) then `registry.markWrittenByUs(...)`.
**Action**: `detectChanges`.
**Assert**: `changes.changedFiles.isEmpty()`.

(Full code in `plan.md` Task 2.1b.)

---

### GraphLoaderProgressiveTest — new tests (Tasks 1.1b + 1.2a–d)

All tests run in `jvmTest`. Tests involving the midnight watcher use `runTest` with `advanceTimeBy` / `advanceUntilIdle` from `kotlinx.coroutines.test`.

#### Test 4 — `millisUntilNextMidnight returns positive value less than 24h`
**Type**: Unit
**Covers**: REQ-01 (helper correctness)
**Setup**: Real `Clock.System.now()` as reference.
**Action**: Compute `millisUntilNextMidnight(Clock.System)` using the same formula as production (exposed as `internal fun`).
**Assert**: `delayMs > 0 && delayMs <= 24 * 60 * 60 * 1000L`.

#### Test 5 — `millisUntilNextMidnight returns at least 1000ms when clock is exactly at midnight`
**Type**: Unit / Edge path
**Covers**: REQ-01 (`coerceAtLeast(1_000L)` guard)
**Setup**: `FakeClock` set to exactly local midnight (the `atStartOfDayIn` instant).
**Action**: Compute `millisUntilNextMidnight(fakeClock)`.
**Assert**: `delayMs >= 1000L` — the coerce guard fired.

```kotlin
@Test
fun `millisUntilNextMidnight returns at least 1000ms when clock is exactly at midnight`() {
    val tz = TimeZone.currentSystemDefault()
    val midnightInstant = LocalDate(2026, 5, 29).atStartOfDayIn(tz)
    val fakeClock = dev.stapler.stelekit.testing.FakeClock(midnightInstant)

    val delay = millisUntilNextMidnight(fakeClock)

    assertTrue(delay >= 1000L,
        "coerceAtLeast(1_000L) must fire when clock is exactly at midnight; got $delay ms")
}
```

#### Test 6 — `midnight watcher calls ensureTodayJournal after simulated day crossing`
**Type**: Integration
**Covers**: REQ-01, REQ-03
**Setup**: `FakeClock` at 23:58 local. `JournalService` injected with same clock. Watcher loop launched on `runTest` scope.
**Action**: `advanceTimeBy(121_000)` (2 min + 1 s past midnight).
**Assert**: `callCount.get() == 1`.

(Full code in `plan.md` Task 1.2d — "Alternative / preferred approach".)

#### Test 7 — `midnight watcher calls ensureTodayJournal on second day crossing`
**Type**: Integration
**Covers**: REQ-01 (loop repeats for subsequent midnights)
**Extends Test 6**: After first assertion, advance virtual time by `24 * 60 * 60 * 1000L + 1_000L`.
**Assert**: `callCount.get() == 2`.

(This is the second `assertEquals` inside the same test from Task 1.2d.)

#### Test 8 — `midnight watcher is cancelled when scope is cancelled`
**Type**: Unit / Cancellation
**Covers**: REQ-01 cancellation requirement
**Setup**: `FakeClock` at 23:58. Launch watcher on a `Job` from `runTest` scope.
**Action**: Cancel the watcher `Job`. Advance time by 2 minutes past midnight.
**Assert**: `callCount.get() == 0` — no call after cancellation.

```kotlin
@Test
fun `midnight watcher is cancelled when scope is cancelled`() = runTest {
    val callCount = AtomicInteger(0)
    val tz = TimeZone.currentSystemDefault()
    val fakeClock = dev.stapler.stelekit.testing.FakeClock(
        LocalDate(2026, 5, 28).atStartOfDayIn(tz) +
            kotlin.time.Duration.Companion.hours(23) +
            kotlin.time.Duration.Companion.minutes(58)
    )

    val watcherJob = launch {
        while (isActive) {
            val delayMs = run {
                val now = fakeClock.now()
                val today = now.toLocalDateTime(tz).date
                val tomorrowMidnight = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)
                (tomorrowMidnight - now).inWholeMilliseconds.coerceAtLeast(1_000L)
            }
            delay(delayMs)
            fakeClock.advance(kotlin.time.Duration.Companion.minutes(2))
            callCount.incrementAndGet()
        }
    }

    watcherJob.cancel()
    advanceTimeBy(200_000) // 3+ minutes past midnight

    assertEquals(0, callCount.get(),
        "Cancelled watcher must not call ensureTodayJournal after cancellation")
}
```

---

### Existing tests that must continue to pass (regression gates)

| Test File | Test | Type | Guards |
|-----------|------|------|--------|
| `FileRegistryTest` | All 14 existing tests | Unit | No regression from `invalidateShadow` insertion (Scenarios 1–10 + paranoid mode) |
| `GraphLoaderProgressiveTest` | `warm start fires onPhase1Complete before filesystem content is read` | Integration | Warm-start fast path not broken by JournalService changes |
| `GraphLoaderProgressiveTest` | `cold start reads filesystem and calls both callbacks` | Integration | Cold-start not broken |
| `GraphLoaderProgressiveTest` | `cancelBackgroundWork on warm start prevents onFullyLoaded from being called` | Integration | Cancellation semantics unchanged |
| `GraphLoaderProgressiveTest` | `warm start does not call listFiles before onPhase1Complete` | Integration | SAF-hot-path not regressed |
| `GraphLoaderProgressiveTest` | `cold start does not call listFiles before the Phase 1 journals are loaded` | Integration | Sanitize deferral not regressed |
| `GraphLoaderProgressiveTest` | `warm start reconcile calls invalidateStaleShadow before reading changed files` | Integration | Existing shadow-freshness guarantee unchanged |
| `GraphLoaderProgressiveTest` | `cold start calls invalidateStaleShadow before Phase 1 journal reads` | Integration | Existing shadow-freshness guarantee unchanged |
| `GraphLoaderProgressiveTest` | `loadFullPage calls invalidateShadow before reading file` | Integration | Existing navigation shadow-freshness unchanged |
| `GraphLoaderProgressiveTest` | `test progressive loading phases` | Integration | Phase 1/2 split not regressed |
| `GraphLoaderWatcherTest` | All 4 existing tests | Integration | Own-write suppression end-to-end not broken |

---

## New Test Files / Helpers Required

| File | Contents | Notes |
|------|----------|-------|
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/testing/FakeClock.kt` | `FakeClock(instant: Instant) : Clock` with `advance(Duration)` | Check if already exists; skip creation if present |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/FileRegistryTest.kt` | Add `FakeFsWithShadow` inner class + Tests 1–3 | Edit existing file only |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt` | Add Tests 4–8 | Edit existing file only |

---

## Test Stack

- **Unit**: kotlin.test + `kotlinx-coroutines-test` (`runTest`, `advanceTimeBy`, `advanceUntilIdle`)
- **Integration**: Same stack; uses `FakeClock`, `FakeFsWithShadow`, `InMemoryPageRepository`, `InMemoryBlockRepository`
- **API/E2E**: Not applicable. Manual Android smoke test required for the `invalidateShadow` fix on the SAF path (no automated instrumented test in scope).

---

## Coverage Targets

- Unit test coverage: ≥80% line coverage for modified files (`FileRegistry.kt`, `JournalService.kt`, `StelekitViewModel.kt`)
- All new public/internal methods: happy path + error/edge path
- All external integrations (SAF shadow layer): unit mocked via `FakeFsWithShadow` + existing `GraphLoaderWatcherTest` end-to-end integration
- Android SAF path: manual smoke test required — modify a markdown file via a file manager while app is open; within ~5s updated content must appear

---

## Adversarial Concerns Addressed

The adversarial-review.md identified 4 concerns (no blockers). Here is how each is addressed in the test plan:

| Concern | Addressed by |
|---------|-------------|
| **Duplicate midnight-watcher on graph reload** — old watcher may keep running if scope is not cancelled between `loadGraph` calls | Test 8 (`midnight watcher is cancelled when scope is cancelled`) verifies the watcher job stops on cancellation. Plan mitigation (store `Job`, cancel on re-entry) is the production fix. |
| **`millisUntilNextMidnight` is private — test must duplicate formula** | Plan states: mark as `internal fun`. Tests 4–5 call the real production helper. Test plan explicitly requires `internal` visibility modifier in Task 1.2a. |
| **`StelekitViewModelDependencies` TODOs in primary test** | Test plan adopts the "Alternative / preferred approach" from plan.md: watcher loop is tested via a focused `runTest` scope without constructing the full ViewModel. Tests 6–8 exercise the loop algorithm. Limitation is documented: a structural refactor of `startMidnightBoundaryWatcher` would not be caught. Accepted as-is for this fix. |
| **Own-write suppression + SAF mtime lag write-behind risk** | Test 3 (`own write with shadow is still suppressed`) confirms the common case. The write-behind edge case is noted as theoretical and out of scope for this fix per plan.md Decision 3. A comment must be added in `FileRegistry.kt` near the `invalidateShadow` call documenting the write-behind risk and the `markWrittenByUs` mitigation. |

Additional minor concerns from adversarial review:
- **Third midnight crossing**: Tests 6+7 cover two crossings; a third `assertEquals(3, ...)` assertion is added in the same test body to satisfy this minor.
- **`Clock.System` grep after Task 1.1a**: Documented as a required manual verification step in plan.md; not a test.
- **Wildcard import conflict**: Noted as a code review check, not a test concern.
- **`InMemorySearchRepository` package**: Verify import path before Task 1.2d (if full ViewModel approach is later adopted).
- **No Android smoke test**: Explicitly documented in Coverage Targets above.

---

## CI Check Command

```bash
./gradlew jvmTest --tests "dev.stapler.stelekit.db.FileRegistryTest" \
                  --tests "dev.stapler.stelekit.db.GraphLoaderProgressiveTest" \
                  --tests "dev.stapler.stelekit.db.GraphLoaderWatcherTest"
```

Full CI suite:

```bash
./gradlew ciCheck
```
