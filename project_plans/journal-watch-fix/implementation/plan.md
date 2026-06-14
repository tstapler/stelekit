# Implementation Plan: journal-watch-fix

**Feature**: Fix two regressions — external sync changes never reload (Android shadow stale-read) and new-day journal not created when app is open across midnight.
**Date**: 2026-05-29
**Status**: Ready for implementation
**ADRs**: None — all changes use existing project patterns and in-classpath libraries.

---

## Dependency Visualization

```
Story 2.1 (FileRegistry.detectChanges fix)
  └── Task 2.1a: add FakeFsWithShadow to FileRegistryTest (~3 min)
  └── Task 2.1b: write failing regression test (~3 min)
  └── Task 2.1c: add invalidateShadow call in detectChanges (~2 min)
        └── confirms test passes

Story 1.1 (JournalService Clock injection)
  └── Task 1.1a: add clock param to JournalService constructor (~3 min)
  └── Task 1.1b: add FakeClock helper to test utilities (~2 min)
        └── Story 1.2 (midnight-boundary watcher)
              └── Task 1.2a: add millisUntilNextMidnight helper in StelekitViewModel (~3 min)
              └── Task 1.2b: add startMidnightBoundaryWatcher in StelekitViewModel (~4 min)
              └── Task 1.2c: call startMidnightBoundaryWatcher from onPhase1Complete (~2 min)
              └── Task 1.2d: write midnight-boundary test in GraphLoaderProgressiveTest (~5 min)
```

**Ordering**: Implement Story 2 (Bug 2 — FileRegistry) first. It is a 2-line production change
with a clear regression test; completing it first validates the test harness before touching
the more complex coroutine work in Story 1.

---

## Phase 1: Fix external-sync shadow stale-read (Bug 2)

### Epic 2.1: Invalidate shadow before content read in detectChanges
**Goal**: When `FileRegistry.detectChanges` sees `modTime > lastKnown` for a non-encrypted
file, ensure the Android shadow cache is cleared before reading file content, so the
content-hash guard operates on fresh disk data rather than stale cached data.

#### Story 2.1: Shadow invalidation before content read
**As a** SteleKit user on Android with an external sync tool,
**I want** file changes from an external process to be detected within one poll interval,
**so that** I see updated content in the app without needing to restart.

**Acceptance Criteria**:
- A file modified by an external process (mtime increases, content changes) appears in
  `detectChanges().changedFiles` within the next poll interval even if a stale shadow
  exists that matches the old content hash.
- A file modified only by our own `GraphWriter` path (marked via `markWrittenByUs`) is
  still correctly suppressed — own-write suppression is not broken.
- Existing `FileRegistryTest` tests all pass.
- On JVM, `invalidateShadow` is a no-op — zero behaviour change on desktop.

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/FileRegistry.kt`
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/FileRegistryTest.kt`

##### Task 2.1a: Add FakeFsWithShadow to FileRegistryTest (~3 min)

Add a second fake `FileSystem` implementation below the existing `FakeFs` class, inside
`FileRegistryTest`. The new class simulates Android's shadow cache layer.

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/FileRegistryTest.kt`

Add after the closing brace of `FakeFs` (around line 53), before the first `@Test`:

```kotlin
/**
 * Fake filesystem with a shadow layer, simulating Android SAF behaviour.
 * - readFile returns shadowMap[path] if present, else files[path]
 * - invalidateShadow removes path from shadowMap
 * - externalWrite updates files only (NOT shadowMap) — simulates an external sync
 *   that wrote the file on-device but didn't go through our updateShadow path
 * - ownWrite updates both files AND shadowMap — simulates GraphWriter + updateShadow
 */
private class FakeFsWithShadow(
    val files: MutableMap<String, FakeFile> = mutableMapOf(),
    val shadowMap: MutableMap<String, String> = mutableMapOf()
) : FileSystem {
    override fun getDefaultGraphPath() = "/graph"
    override fun expandTilde(path: String) = path
    override fun readFile(path: String): String? = shadowMap[path] ?: files[path]?.content
    override fun writeFile(path: String, content: String): Boolean {
        val existing = files[path]
        val newMod = (existing?.modTime ?: 0L) + 1000L
        files[path] = FakeFile(content, newMod)
        return true
    }
    override fun listFiles(path: String) =
        files.keys.filter { it.startsWith("$path/") }.map { it.substringAfterLast("/") }
    override fun listDirectories(path: String) = emptyList<String>()
    override fun fileExists(path: String) = files.containsKey(path)
    override fun directoryExists(path: String) = true
    override fun createDirectory(path: String) = true
    override fun deleteFile(path: String): Boolean { files.remove(path); return true }
    override fun pickDirectory() = null
    override fun getLastModifiedTime(path: String) = files[path]?.modTime
    override fun invalidateShadow(path: String) { shadowMap.remove(path) }

    /** Simulates GraphWriter: update both file and shadow (own write path). */
    fun ownWrite(path: String, content: String) {
        val existing = files[path]
        val newMod = (existing?.modTime ?: 0L) + 1000L
        files[path] = FakeFile(content, newMod)
        shadowMap[path] = content
    }

    /** Simulates external sync: update file only, shadow remains stale. */
    fun externalWrite(path: String, content: String) {
        val existing = files[path]
        val newMod = (existing?.modTime ?: 0L) + 1000L
        files[path] = FakeFile(content, newMod)
        // shadowMap NOT updated — this is the bug scenario
    }
}
```

##### Task 2.1b: Write failing regression test for shadow stale-read (~3 min)

Add this test to `FileRegistryTest` at the end of the file (before the closing `}`).
Run it first to confirm it **fails** without the production fix, then passes after.

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/FileRegistryTest.kt`

```kotlin
// ── Scenario 11: Android shadow stale-read regression (Bug 2) ────────────────

/**
 * Regression test for the shadow stale-read bug:
 * External sync writes "new content" to the file, bumping mtime.
 * Shadow cache still holds "old content" (was not updated by our own write path).
 * WITHOUT the fix, detectChanges reads from shadow ("old content"), hash matches,
 * change is silently dropped.
 * WITH the fix, invalidateShadow is called first, then readFile falls through to
 * the real file ("new content"), hash differs, change is correctly reported.
 */
@Test
fun `external write with stale shadow is detected after shadow invalidation`() = runTest {
    val fs = FakeFsWithShadow()
    // Initial state: both file and shadow have "old content"
    fs.files["/graph/journals/2026_05_29.md"] = FakeFile("- old content", 1000L)
    fs.shadowMap["/graph/journals/2026_05_29.md"] = "- old content"
    val registry = FileRegistry(fs)

    // Register baseline (reads from shadow → "old content", stores hash)
    registry.detectChanges("/graph/journals")

    // External sync writes "new content" to the file only — shadow stays stale
    fs.externalWrite("/graph/journals/2026_05_29.md", "- new content from sync")

    // detectChanges must detect the change despite the stale shadow
    val changes = registry.detectChanges("/graph/journals")
    assertEquals(1, changes.changedFiles.size,
        "External write with stale shadow must be reported (invalidateShadow must be called before readFile)")
    assertEquals("- new content from sync", changes.changedFiles[0].content)
}

/**
 * Own writes (via markWrittenByUs) must still be suppressed even after the
 * shadow-invalidation fix — the fix must not break the own-write guard.
 */
@Test
fun `own write with shadow is still suppressed after shadow invalidation fix`() = runTest {
    val fs = FakeFsWithShadow()
    // Initial state
    fs.files["/graph/journals/2026_05_29.md"] = FakeFile("- original", 1000L)
    fs.shadowMap["/graph/journals/2026_05_29.md"] = "- original"
    val registry = FileRegistry(fs)
    registry.detectChanges("/graph/journals") // register baseline

    // Own write: GraphWriter writes file + updates shadow
    fs.ownWrite("/graph/journals/2026_05_29.md", "- user typed content")
    registry.markWrittenByUs("/graph/journals/2026_05_29.md")

    // Watcher poll — must be suppressed
    val changes = registry.detectChanges("/graph/journals")
    assertTrue(changes.changedFiles.isEmpty(),
        "Own write marked via markWrittenByUs must still be suppressed after shadow-invalidation fix")
}
```

##### Task 2.1c: Add invalidateShadow call in FileRegistry.detectChanges (~2 min)

This is the production fix. One line added to `FileRegistry.kt`.

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/FileRegistry.kt`

**Before** (lines 123–125):
```kotlin
                } else {
                    // Mod time changed — check content hash guard
                    val content = fileSystem.readFile(filePath) ?: continue
```

**After**:
```kotlin
                } else {
                    // Mod time changed — invalidate shadow first so readFile falls through
                    // to the real file (SAF on Android) rather than a stale on-device cache.
                    // On JVM, invalidateShadow is a no-op; zero cost on desktop.
                    fileSystem.invalidateShadow(filePath)
                    val content = fileSystem.readFile(filePath) ?: continue
```

Run `./gradlew jvmTest --tests "dev.stapler.stelekit.db.FileRegistryTest"` to confirm all
existing tests pass plus the two new regression tests.

---

## Phase 2: Fix new-day journal not created (Bug 1)

### Epic 1.1: Inject Clock into JournalService
**Goal**: Make `JournalService.ensureTodayJournal()` accept a `Clock` from its constructor
so tests can control "today's date" without real wall-clock time.

#### Story 1.1: Clock injection in JournalService
**As a** developer,
**I want** `JournalService` to accept an injected `Clock`,
**so that** the midnight-boundary test can control the perceived current date.

**Acceptance Criteria**:
- `JournalService` constructor gains `clock: Clock = Clock.System` parameter.
- All `Clock.System.now()` calls inside `JournalService` use `clock.now()` instead.
- All existing call sites pass no clock (default `Clock.System`) — no API breakage.
- All existing `JournalService`-related tests still pass.

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt`

##### Task 1.1a: Add clock parameter to JournalService constructor (~3 min)

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt`

**Before** (line 39–43):
```kotlin
class JournalService(
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
    private val writeActor: DatabaseWriteActor? = null
) : JournalDateResolver {
```

**After**:
```kotlin
class JournalService(
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
    private val writeActor: DatabaseWriteActor? = null,
    private val clock: Clock = Clock.System,
) : JournalDateResolver {
```

Then replace all `Clock.System.now()` calls inside the class with `clock.now()`.

There are 5 occurrences in `JournalService.kt`:

1. `ensureTodayJournal` line 83: `val today = Clock.System.now()` → `val today = clock.now()`
2. `ensureTodayJournal` line 108: `createdAt = today.atStartOfDayIn(TimeZone.currentSystemDefault()),`
   — this uses `today` not `Clock.System`; no change needed.
3. `ensureTodayJournal` line 109: `updatedAt = Clock.System.now(),` → `updatedAt = clock.now(),`
4. `ensureTodayJournal` line 125: `createdAt = Clock.System.now(),` → `createdAt = clock.now(),`
5. `ensureTodayJournal` line 126: `updatedAt = Clock.System.now(),` → `updatedAt = clock.now(),`
6. `healJournalDate` line 226: `updatedAt = Clock.System.now()` → `updatedAt = clock.now()`
7. `appendBlockToPage` lines 168, 169: `createdAt = Clock.System.now()`, `updatedAt = Clock.System.now()` → `clock.now()`
8. `createTranscriptPage` lines ~197, 207–208, 214–215: same replacements.

Verify: `grep "Clock.System" kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt`
should return zero matches after this task.

##### Task 1.1b: Add FakeClock to test utilities (~2 min)

Create a minimal `FakeClock` that can be advanced and shared between the
midnight-boundary watcher and `JournalService`.

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/testing/FakeClock.kt`

```kotlin
package dev.stapler.stelekit.testing

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Controllable fake clock for tests.
 * advance() moves the clock forward without real time passing.
 */
class FakeClock(private var instant: Instant) : Clock {
    override fun now(): Instant = instant
    fun advance(duration: Duration) { instant += duration }
}
```

Check if a `testing/` directory already exists at
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/testing/`. The directory listing shows it
does (`testing/` appears in `ls` of `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/`).
Use the existing package; do not create a duplicate.

---

### Epic 1.2: Midnight-boundary watcher coroutine in StelekitViewModel
**Goal**: After a graph finishes loading (Phase 1 complete), start a long-lived coroutine
on `deps.scope` that sleeps until the next local midnight, calls
`journalService.ensureTodayJournal()`, then loops for subsequent midnights.

#### Story 1.2: Midnight-boundary watcher
**As a** SteleKit user who leaves the app open overnight,
**I want** today's journal page to appear automatically after midnight,
**so that** I don't have to restart the app to get the new day's entry.

**Acceptance Criteria**:
- After `onPhase1Complete` fires, `ensureTodayJournal()` is called once (immediately,
  existing behaviour).
- When the test scheduler advances past the computed `millisUntilNextMidnight`, a second
  call to `ensureTodayJournal()` is observed with the FakeClock showing the next day.
- Advancing by 24 more hours triggers a third call.
- The coroutine is cancelled when `scope.cancel()` is called (no resource leak).
- No duplicate-watcher risk: each `loadGraph` call uses the same `scope`; old watcher is
  cancelled with the scope before a new `loadGraph` is called (ViewModel lifecycle).
- Warm-start: `GraphLoaderProgressiveTest.warm start fires onPhase1Complete` still passes.

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`

##### Task 1.2a: Add millisUntilNextMidnight helper in StelekitViewModel (~3 min)

Add a private companion-object-level (or top-level private) function to
`StelekitViewModel.kt`. Place it near the bottom of the class, before the `companion object`
or closing brace.

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

```kotlin
/**
 * Returns milliseconds from [clock.now()] until the next local midnight.
 * Uses [kotlinx.datetime.LocalDate.plus] + [atStartOfDayIn] so DST transitions
 * (spring-forward skips, fall-back repeats) are handled correctly by the stdlib.
 * coerceAtLeast(1_000L) guards against clocks that return exactly midnight,
 * which would cause a 0ms or negative delay.
 */
private fun millisUntilNextMidnight(clock: Clock = Clock.System): Long {
    val tz = TimeZone.currentSystemDefault()
    val now = clock.now()
    val today = now.toLocalDateTime(tz).date
    val tomorrowMidnight = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)
    return (tomorrowMidnight - now).inWholeMilliseconds.coerceAtLeast(1_000L)
}
```

Imports needed (add to the import block if not already present):
```kotlin
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlin.time.Clock
```

Most of these are already imported because `JournalService` calls them; confirm with a
quick grep before adding duplicates.

##### Task 1.2b: Add startMidnightBoundaryWatcher to StelekitViewModel (~4 min)

Add a private method to `StelekitViewModel.kt`. Place it near the other private
helper coroutine launchers (e.g. near the `lazy-phase3` launch block).

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

```kotlin
/**
 * Launches a long-lived coroutine that calls [journalService.ensureTodayJournal]
 * once per day boundary crossing.
 *
 * Coroutine lifecycle:
 * - Tied to [scope] (the ViewModel's own CoroutineScope, never a composable scope).
 * - Cancelled automatically when the graph is closed and scope.cancel() is called.
 * - If the graph is reloaded (loadGraph called again), the old scope is cancelled and
 *   a new scope is used — no duplicate watcher risk.
 *
 * [clock] is injectable for tests; defaults to Clock.System in production.
 */
private fun startMidnightBoundaryWatcher(clock: Clock = Clock.System) {
    scope.launch(CoroutineName("midnight-boundary-watcher")) {
        while (isActive) {
            val delayMs = millisUntilNextMidnight(clock)
            delay(delayMs)
            logger.info("Day boundary crossed — ensuring today's journal exists")
            try {
                journalService.ensureTodayJournal()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("ensureTodayJournal failed at midnight boundary: ${e.message}")
            }
        }
    }
}
```

Import needed (if not already present): `import kotlinx.coroutines.CoroutineName`

##### Task 1.2c: Call startMidnightBoundaryWatcher from onPhase1Complete (~2 min)

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

**Before** (lines 425–432):
```kotlin
                            onPhase1Complete = {
                                logger.info("Phase 1 complete - UI is now interactive")
                                _uiState.update { it.copy(isLoading = false, statusMessage = "Ready") }

                                // Ensure today's journal exists so it appears at the top of the
                                // journals list. No navigation — the list updates reactively.
                                scope.launch { journalService.ensureTodayJournal() }
                            },
```

**After**:
```kotlin
                            onPhase1Complete = {
                                logger.info("Phase 1 complete - UI is now interactive")
                                _uiState.update { it.copy(isLoading = false, statusMessage = "Ready") }

                                // Ensure today's journal exists so it appears at the top of the
                                // journals list. No navigation — the list updates reactively.
                                scope.launch { journalService.ensureTodayJournal() }

                                // Start the midnight-boundary watcher so a new journal is created
                                // automatically when the day changes while the app is running.
                                startMidnightBoundaryWatcher()
                            },
```

##### Task 1.2d: Write midnight-boundary test in GraphLoaderProgressiveTest (~5 min)

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderProgressiveTest.kt`

Add this test at the end of the class. It verifies that:
1. `ensureTodayJournal` is called once at startup (via `onPhase1Complete`).
2. After the scheduler advances past midnight, it is called a second time.
3. After another 24 hours, it is called a third time.

```kotlin
/**
 * Verifies the midnight-boundary watcher fires ensureTodayJournal on each day crossing.
 *
 * Strategy:
 * - FakeClock starts at 23:59:00 local time (1 minute before midnight).
 * - StelekitViewModelDependencies is constructed with the FakeClock-backed JournalService.
 * - After loadGraph completes (onPhase1Complete fires), ensureTodayJournal count = 1.
 * - Advance FakeClock by 2 minutes (now 00:01 the next day) AND advance the test
 *   scheduler past the 60-second delay → watcher fires → count = 2.
 * - Advance by 24h → count = 3.
 *
 * Note: this test exercises StelekitViewModel directly (not via GraphLoader) because the
 * midnight-boundary watcher lives in the ViewModel, not in GraphLoader.
 */
@Test
fun `midnight boundary watcher calls ensureTodayJournal on each day crossing`() = runTest {
    val callCount = AtomicInteger(0)

    // FakeClock: set to 23:59:00 on an arbitrary fixed date so midnight is 60s away.
    // Use kotlinx-datetime to compute the exact instant.
    val tz = TimeZone.currentSystemDefault()
    val startDate = LocalDate(2026, 5, 28)
    val startInstant = startDate.atStartOfDayIn(tz) +
        kotlin.time.Duration.Companion.hours(23) +
        kotlin.time.Duration.Companion.minutes(59)
    val fakeClock = dev.stapler.stelekit.testing.FakeClock(startInstant)

    // Counting JournalService wrapper — counts ensureTodayJournal calls
    val countingJournalService = object : dev.stapler.stelekit.repository.JournalService(
        pageRepository = InMemoryPageRepository(),
        blockRepository = InMemoryBlockRepository(),
        clock = fakeClock,
    ) {
        override suspend fun ensureTodayJournal(): dev.stapler.stelekit.model.Page {
            callCount.incrementAndGet()
            return super.ensureTodayJournal()
        }
    }

    // Build ViewModel deps with injected journalService and test dispatcher scope
    val viewModel = dev.stapler.stelekit.ui.StelekitViewModel(
        dev.stapler.stelekit.ui.StelekitViewModelDependencies(
            pageRepository = InMemoryPageRepository(),
            blockRepository = InMemoryBlockRepository(),
            searchRepository = dev.stapler.stelekit.repository.InMemorySearchRepository(),
            graphLoader = /* minimal stub — see below */ TODO("stub"),
            graphWriter = TODO("stub"),
            platformSettings = TODO("stub"),
            journalService = countingJournalService,
            scope = this,  // TestCoroutineScope from runTest
        )
    )
    // NOTE: if StelekitViewModelDependencies does not support minimal stubs easily,
    // test the watcher logic directly via a focused unit test on startMidnightBoundaryWatcher.
    // See alternative approach below.
}
```

**Alternative / preferred approach** — test `millisUntilNextMidnight` and the watcher loop
independently without constructing a full ViewModel:

```kotlin
/**
 * Focused unit test for the midnight delay computation.
 * Does not require a full ViewModel — tests the helper directly.
 */
@Test
fun `millisUntilNextMidnight returns positive value less than 24h`() {
    val tz = TimeZone.currentSystemDefault()
    val now = Clock.System.now()
    // Reuse the same formula from StelekitViewModel (extracted for testing via internal visibility
    // or package-private placement, or duplicated here):
    val today = now.toLocalDateTime(tz).date
    val tomorrowMidnight = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)
    val delayMs = (tomorrowMidnight - now).inWholeMilliseconds.coerceAtLeast(1_000L)

    assertTrue(delayMs > 0, "Delay until next midnight must be positive")
    assertTrue(delayMs <= 24 * 60 * 60 * 1000L, "Delay must not exceed 24 hours")
}

/**
 * Midnight watcher integration test using a CoroutineScope from runTest.
 * Verifies ensureTodayJournal is called after the simulated midnight delay elapses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Test
fun `midnight watcher calls ensureTodayJournal after simulated day crossing`() = runTest {
    val callCount = AtomicInteger(0)

    // Clock starts 2 minutes before midnight
    val tz = TimeZone.currentSystemDefault()
    val startDate = LocalDate(2026, 5, 28)
    val startInstant = startDate.atStartOfDayIn(tz) +
        kotlin.time.Duration.Companion.hours(23) +
        kotlin.time.Duration.Companion.minutes(58)
    val fakeClock = dev.stapler.stelekit.testing.FakeClock(startInstant)

    val pageRepo = InMemoryPageRepository()
    val blockRepo = InMemoryBlockRepository()
    val journalService = dev.stapler.stelekit.repository.JournalService(
        pageRepository = pageRepo,
        blockRepository = blockRepo,
        clock = fakeClock,
    )

    // Launch the watcher using the test scope (runTest's scheduler controls delay())
    val watcherJob = launch {
        while (isActive) {
            // Inline the watcher logic for testing (or expose via internal fun)
            val delayMs = run {
                val now = fakeClock.now()
                val today = now.toLocalDateTime(tz).date
                val tomorrowMidnight = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)
                (tomorrowMidnight - now).inWholeMilliseconds.coerceAtLeast(1_000L)
            }
            delay(delayMs)
            fakeClock.advance(kotlin.time.Duration.Companion.minutes(2))
            callCount.incrementAndGet()
            journalService.ensureTodayJournal()
        }
    }

    // Advance virtual time past first midnight (2 min = 120_000ms)
    advanceTimeBy(121_000)
    assertEquals(1, callCount.get(), "ensureTodayJournal must be called once after first midnight")

    // Advance by 24h for the second midnight
    advanceTimeBy(24 * 60 * 60 * 1000L + 1_000L)
    assertEquals(2, callCount.get(), "ensureTodayJournal must be called again after second midnight")

    watcherJob.cancel()
}
```

**Note on `millisUntilNextMidnight` visibility**: To avoid duplicating the helper in tests,
mark it `internal` in `StelekitViewModel.kt` (or extract it to a top-level `internal fun` in
the same file accessible from jvmTest). The preferred approach is marking it `internal` so
the test calls the real implementation.

---

## Key Decisions

### Decision 1: Where to put the midnight-boundary coroutine
**Choice**: `StelekitViewModel.startMidnightBoundaryWatcher()`, launched on `deps.scope`.

**Rationale**:
- `StelekitViewModel` already owns the `journalService` reference and the `scope`.
- `GraphLoader` does not hold `JournalService`; injecting it would create a cross-layer
  dependency between `db/` and `repository/`.
- `deps.scope` is the ViewModel's own `CoroutineScope` — outlives any composable,
  cancelled when the ViewModel is closed.
- The `onPhase1Complete` callback already launches on `deps.scope`; this is a natural
  extension of that pattern.

**Alternative considered**: `GraphLoader.startWatching()` — rejected because of the
cross-layer dependency requirement.

### Decision 2: How to inject Clock for testability
**Choice**: Add `clock: Clock = Clock.System` to the `JournalService` constructor (Option B
from research).

**Rationale**:
- The midnight-boundary watcher in `StelekitViewModel` also calls `clock.now()` (for
  `millisUntilNextMidnight`). Sharing the same `FakeClock` instance between the watcher
  and `JournalService` ensures both advance together in tests, eliminating the
  "FakeClock must be advanced on both axes" pitfall identified in `pitfalls.md`.
- Constructor injection is the existing pattern in this codebase (e.g. `GraphLoader`
  accepts `FileSystem` for testability).
- Default value `Clock.System` means zero change to any production call site.

**Alternative considered**: Adding `clock` only as a parameter to `ensureTodayJournal()`
(Option A from research) — rejected because the watcher also needs `clock.now()` for delay
computation, and two separate injection points complicate test setup.

### Decision 3: `invalidateShadow` before `readFile` vs. `readShadowOnly` pre-check
**Choice**: Simple `invalidateShadow(filePath)` immediately before `readFile(filePath)`
in the `modTime > lastKnown` non-encrypted branch.

**Rationale**:
- The `readShadowOnly` two-phase approach (check shadow hash first, only invalidate if
  mismatch) is more complex and adds a second `FileSystem` method call per changed file.
- The write-behind queue risk identified in `pitfalls.md` is addressed by the existing
  `markWrittenByUs` mechanism: own-written files have `modTimes[filePath]` set to the
  current mtime, so `modTime == lastKnown` on the next poll — the `modTime > lastKnown`
  branch (where `invalidateShadow` is called) is never entered for own-written files.
- On JVM, `invalidateShadow` is a no-op; zero performance impact on desktop.
- This exact pattern is already used in `GraphLoader.loadFullPage()` (line 699) and in
  `GraphFileWatcher.checkDirectoryForChanges()` — same codebase, established precedent.

**Alternative considered**: `readShadowOnly` pre-check — noted in `pitfalls.md` as a
safer alternative. Kept as a follow-up if the write-behind race materialises in practice;
not needed for this fix given the existing `markWrittenByUs` guard.

### Decision 4: Implement Bug 2 (FileRegistry) before Bug 1 (midnight watcher)
**Rationale**:
- Bug 2 is a 2-line production change with a focused unit test in an already-well-tested
  file (`FileRegistryTest`). Completing it first validates the `FakeFsWithShadow` helper
  and confirms the test harness is healthy before tackling the more complex coroutine work.
- Bug 1 requires changes to two files (`JournalService`, `StelekitViewModel`) and a new
  test involving `runTest` + `advanceTimeBy` + `FakeClock`. Starting with Bug 2 reduces
  the surface area of the first PR.

---

## Test Plan Summary

| Test | File | Type | Covers |
|------|------|------|--------|
| `external write with stale shadow is detected after shadow invalidation` | `FileRegistryTest` | Unit | REQ-02, Bug 2 regression |
| `own write with shadow is still suppressed after shadow invalidation fix` | `FileRegistryTest` | Unit | Own-write suppression not broken |
| All 14 existing `FileRegistryTest` tests | `FileRegistryTest` | Unit | No regression |
| `millisUntilNextMidnight returns positive value less than 24h` | `GraphLoaderProgressiveTest` | Unit | REQ-01 helper |
| `midnight watcher calls ensureTodayJournal after simulated day crossing` | `GraphLoaderProgressiveTest` | Integration | REQ-01 watcher fires at midnight |
| `warm start fires onPhase1Complete before filesystem content is read` | `GraphLoaderProgressiveTest` | Integration | Warm-start not regressed (existing) |
| `cold start reads filesystem and calls both callbacks` | `GraphLoaderProgressiveTest` | Integration | Cold-start not regressed (existing) |
| `GraphLoaderWatcherTest` (all existing) | `GraphLoaderWatcherTest` | Integration | Own-write suppression in watcher (existing) |

**CI check command**:
```bash
./gradlew jvmTest --tests "dev.stapler.stelekit.db.FileRegistryTest" \
                  --tests "dev.stapler.stelekit.db.GraphLoaderProgressiveTest" \
                  --tests "dev.stapler.stelekit.db.GraphLoaderWatcherTest"
```

Or run the full CI suite:
```bash
./gradlew ciCheck
```
