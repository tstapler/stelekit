// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// js() calls must be top-level functions in Kotlin/Wasm — TextFile/Dir/rootDir/FakeCacheAccess/
// newReadCounter/readCounterValue/toJsArray live in HostDirectoryTestFixtures.kt, same package.

/** Task 5.5.2a: counts how many times [HostDirectorySync.pollHostDirectoryOnce]'s walk begins
 * (once per call, since the walk always lists the root directory's entries first) — a dedicated
 * counting root, distinct from `newReadCounter`'s per-file content-read counter. */
private fun newTickCounter(): JsAny = js("({ count: 0 })")
private fun tickCounterValue(counter: JsAny): Int = js("counter.count | 0")
private fun countingRootDir(children: JsAny, tickCounter: JsAny): JsAny = js(
    """
    ({
        kind: 'directory',
        name: 'root',
        values: function() {
            tickCounter.count = tickCounter.count + 1;
            var idx = 0;
            return {
                next: function() {
                    if (idx < children.length) {
                        return Promise.resolve({ done: false, value: children[idx++] });
                    }
                    return Promise.resolve({ done: true, value: undefined });
                }
            };
        }
    })
    """,
)

/**
 * Epic 5.5 (REQUIRED, not optional — closes adversarial-review.md Blocker 6 and pre-mortem.md's
 * remaining P1): large-graph poller-cost benchmark for [HostDirectorySync.pollHostDirectoryOnce]
 * (Story 5.5.1, per-tick cost at 8,030 files, matching `LargeGraphWarmStartCrashTest`'s scale) and
 * [HostDirectorySync.startHostDirectoryPolling]'s [HostDirectorySync.effectivePollIntervalMs]
 * backoff (Story 5.5.2, cumulative tick-count under virtual time for the hidden-tab/observer-healthy/
 * combined cases).
 *
 * Reuses [TextFile]/[Dir]/[rootDir]/[FakeCacheAccess]/[newReadCounter]/[readCounterValue] from
 * `HostDirectoryTestFixtures.kt` (shared with `HostDirectorySyncReconciliationBenchmarkTest`, per
 * that file's own doc comment anticipating this one) — [buildFixtureFiles] below is this file's
 * own 8,030-file generator (Task 5.5.1a), matching `HostDirectorySyncReconciliationBenchmarkTest`'s
 * `buildFixtureFiles` convention exactly (same `PAGE_COUNT = 8_030`/two-line-markdown shape as
 * `LargeGraphWarmStartCrashTest`).
 *
 * **Measured numbers (Task 5.5.1d) — PLACEHOLDER, needs real-browser confirmation**, for the same
 * reason `HostDirectorySyncReconciliationBenchmarkTest`'s class doc comment records: real-browser
 * execution (`CHROME_BIN=/usr/bin/google-chrome-stable ./gradlew :kmp:wasmJsBrowserTest`) requires
 * `compileTestKotlinWasmJs` to succeed for the *entire* `kmp` module's test sources, and that
 * compilation fails on two pre-existing, unrelated files — `commonTest/.../transfer/
 * FrameTransportSignatureTest.kt` (JVM-only `kotlin.reflect` usage) and `commonTest/.../transfer/
 * qrcode/QrRoundTripFidelityTest.kt` (unresolved `runBlocking` on this target) — both predating
 * this dispatch and confirmed broken independent of any change made here. This file and
 * `HostDirectorySyncExternalChangeTest.kt` compile cleanly on their own. Until that pre-existing
 * breakage is fixed (out of this epic's scope) or these tests are run on a checkout where it
 * already is, [STEADY_STATE_BUDGET]/[FIRST_PASS_BUDGET]/[BURST_BUDGET] below are conservative,
 * unconfirmed placeholders — generous enough that they should hold once real numbers are
 * available, chosen by the same reasoning `HostDirectorySyncReconciliationBenchmarkTest` used for
 * its own budgets (steady-state pass ≈ same cost shape as that file's steady-state reconciliation
 * pass, since both are an 8,030-entry walk with a zero-content-read mtime/size pre-filter
 * short-circuit; this file's per-tick walk does strictly less work per file than reconciliation's
 * classification pass, so its budget is set at least as tight).
 *
 * **Poll interval default (Task 5.5.1d)**: [HostDirectorySync]'s `hostPollIntervalMs` default of
 * 10,000ms (10s) is **provisionally confirmed, pending the real-browser run above** — the
 * steady-state per-tick cost this file measures is architecturally bounded by the same mechanism
 * `HostDirectorySyncReconciliationBenchmarkTest` already measured at ≈1s for a full 8,030-entry
 * zero-content-read walk (that file's `STEADY_STATE_BUDGET`), and this file's poll walk does
 * strictly less work per visited file (no [ReconciliationOutcome] classification, no
 * `cacheAccess.keysUnder` pass) — so a steady-state poll tick is expected to cost comfortably
 * under 10-20% of the 10s interval. If a real run's numbers contradict this, [STEADY_STATE_BUDGET]
 * and this comment must be updated together with a revised `hostPollIntervalMs` default.
 */
class HostDirectoryPollerBenchmarkTest {

    private companion object {
        const val FILE_COUNT = 8_030
        const val BURST_COUNT = 100
        const val OPFS_PATH = "/stelekit/poller-bench-graph"
        const val BASE_MTIME = 1_700_000_000_000L

        // Task 5.5.1b/c: explicit upper bounds chosen to be generous relative to this file's
        // class doc comment's reasoning (real regression gates, not no-op assertions) — see that
        // comment for the "needs real-browser confirmation" caveat.
        val FIRST_PASS_BUDGET = 8.seconds
        val STEADY_STATE_BUDGET = 1.seconds
        val BURST_BUDGET = 2.seconds

        // Task 5.5.2: 3,600 simulated seconds at the base 10s interval widened 6x (hidden/observer
        // healthy) or 1x (neither) — expressed in milliseconds since HostDirectorySync's timer
        // loop uses delay(Long) milliseconds.
        const val SIMULATED_WINDOW_MS = 3_600_000L
        const val EXPECTED_WIDENED_TICKS = 60 // 3600s / 60s (10s * 6x)
        const val WIDENED_TICK_TOLERANCE = 5
    }

    /** Task 5.5.1a: 8,030 flat `pages/File&lt;N&gt;.md` [TextFile] entries — mirrors
     * `HostDirectorySyncReconciliationBenchmarkTest.buildFixtureFiles`'s convention exactly
     * (same `PAGE_COUNT`/content shape, matching `LargeGraphWarmStartCrashTest`'s scale). */
    private fun buildFixtureFiles(baseMtime: Long = BASE_MTIME): List<TextFile> = (1..FILE_COUNT).map { i ->
        TextFile(
            name = "Page$i.md",
            content = "- first block of Page $i\n- second block with [[Page 1]] link",
            lastModified = baseMtime + i,
        )
    }

    private fun newSync(cache: FakeCacheAccess, scope: CoroutineScope): HostDirectorySync =
        HostDirectorySync(graphIdProvider = { "poller-bench-graph" }, cacheAccess = cache, scope = scope)

    // ── Story 5.5.1: per-tick cost at 8,030 files ──────────────────────────────────────────────

    @Test
    fun pollHostDirectoryOnce_should_ReadZeroFiles_When_SteadyStateTickOver8030UnchangedFiles() = runTest {
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(cache, testScope)
        val files = buildFixtureFiles()

        // ── Pass 1: warm the mtime/size baseline (first-ever tick — every file gets read once). ──
        val counter1 = newReadCounter()
        val host1 = rootDir(Dir("pages", files), counter = counter1)
        val mark1 = TimeSource.Monotonic.markNow()
        sync.pollHostDirectoryOnce(host1, OPFS_PATH)
        val firstPassElapsed = mark1.elapsedNow()

        println(
            "[SteleKit][benchmark] pollHostDirectoryOnce first-ever tick over $FILE_COUNT files " +
                "took $firstPassElapsed (content reads: ${readCounterValue(counter1)})",
        )
        assertEquals(FILE_COUNT, readCounterValue(counter1), "first-ever tick must content-read every file")
        assertTrue(firstPassElapsed < FIRST_PASS_BUDGET, "first-ever tick took $firstPassElapsed, expected < $FIRST_PASS_BUDGET")

        // ── Pass 2: steady state — same tree, nothing changed. ──────────────────────────────────
        val counter2 = newReadCounter()
        val host2 = rootDir(Dir("pages", files), counter = counter2)
        val mark2 = TimeSource.Monotonic.markNow()
        sync.pollHostDirectoryOnce(host2, OPFS_PATH)
        val steadyStateElapsed = mark2.elapsedNow()

        println(
            "[SteleKit][benchmark] pollHostDirectoryOnce steady-state tick over $FILE_COUNT files " +
                "took $steadyStateElapsed (content reads: ${readCounterValue(counter2)})",
        )
        assertEquals(0, readCounterValue(counter2), "steady-state tick must perform zero content reads")
        assertTrue(steadyStateElapsed < STEADY_STATE_BUDGET, "steady-state tick took $steadyStateElapsed, expected < $STEADY_STATE_BUDGET")

        testScope.cancel()
    }

    @Test
    fun pollHostDirectoryOnce_should_ReadExactlyTheChangedFiles_When_100Of8030FilesChanged() = runTest {
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(cache, testScope)

        // ── Pass 1: warm the baseline. ───────────────────────────────────────────────────────────
        val baseline = buildFixtureFiles()
        val warmupCounter = newReadCounter()
        sync.pollHostDirectoryOnce(rootDir(Dir("pages", baseline), counter = warmupCounter), OPFS_PATH)
        assertEquals(FILE_COUNT, readCounterValue(warmupCounter))

        // ── Pass 2: a "just did a git pull" burst — 100 of 8,030 files have a bumped mtime. ──────
        val burst = baseline.mapIndexed { index, file ->
            if (index < BURST_COUNT) file.copy(lastModified = file.lastModified + 1) else file
        }
        val burstCounter = newReadCounter()
        val host = rootDir(Dir("pages", burst), counter = burstCounter)

        val mark = TimeSource.Monotonic.markNow()
        sync.pollHostDirectoryOnce(host, OPFS_PATH)
        val elapsed = mark.elapsedNow()

        println(
            "[SteleKit][benchmark] pollHostDirectoryOnce burst tick ($BURST_COUNT of $FILE_COUNT changed) " +
                "took $elapsed (content reads: ${readCounterValue(burstCounter)})",
        )
        assertEquals(BURST_COUNT, readCounterValue(burstCounter), "only the changed files must be content-read")
        assertTrue(elapsed < BURST_BUDGET, "burst tick took $elapsed, expected < $BURST_BUDGET")

        testScope.cancel()
    }

    // ── Story 5.5.2: cumulative tick-count under virtual time (hidden-tab / observer-healthy / ──
    // combined) — proves effectivePollIntervalMs()'s maxOf composition, not just a cheap tick.

    /**
     * Task 5.5.2a: virtual-time harness — [HostDirectorySync.startHostDirectoryPolling]'s
     * `scope.launch { while (isActive) { delay(...); ... } }` loop runs on [dispatcher]'s
     * [kotlinx.coroutines.test.TestCoroutineScheduler], so [SIMULATED_WINDOW_MS] of loop cadence
     * advances without any real wall-clock wait. The steady-state 8,030-file fixture (baseline
     * pre-warmed) is reused for all three Story 5.5.2 cases so every tick after warm-up is a
     * zero-content-read steady-state tick, keeping the real (non-virtual) cost of 60 ticks' worth
     * of directory-metadata walks small regardless of [SIMULATED_WINDOW_MS]'s size.
     */
    private fun runCumulativeTickCountCase(isTabHidden: Boolean, observerConfirmedActive: Boolean): Int {
        val dispatcher = StandardTestDispatcher()
        val testScope = TestScope(dispatcher)
        val cache = FakeCacheAccess()
        val sync = newSync(cache, testScope)

        val tickCounter = newTickCounter()
        val files = buildFixtureFiles()
        val hostRoot = countingRootDir(toJsArray(listOf(buildEntry(Dir("pages", files)))), tickCounter)

        sync.hostDirHandle = hostRoot
        sync.hostGraphOpfsPath = OPFS_PATH
        sync.isTabHidden = isTabHidden
        sync.observerConfirmedActive = observerConfirmedActive

        sync.startHostDirectoryPolling()
        testScope.advanceTimeBy(SIMULATED_WINDOW_MS)
        testScope.runCurrent()

        val ticks = tickCounterValue(tickCounter)
        sync.stopHostDirectoryPolling()
        testScope.cancel()
        return ticks
    }

    @Test
    fun startHostDirectoryPolling_should_TickAtWidenedCadence_When_TabHiddenForSimulatedHour() = runTest {
        val ticks = runCumulativeTickCountCase(isTabHidden = true, observerConfirmedActive = false)
        println("[SteleKit][benchmark] hidden-tab cumulative ticks over ${SIMULATED_WINDOW_MS / 1000}s: $ticks")
        assertTrue(
            ticks in (EXPECTED_WIDENED_TICKS - WIDENED_TICK_TOLERANCE)..(EXPECTED_WIDENED_TICKS + WIDENED_TICK_TOLERANCE),
            "expected ~$EXPECTED_WIDENED_TICKS ticks (widened 6x cadence) while tab hidden, got $ticks",
        )
    }

    @Test
    fun startHostDirectoryPolling_should_TickAtWidenedCadence_When_ObserverConfirmedActiveForSimulatedHour() = runTest {
        val ticks = runCumulativeTickCountCase(isTabHidden = false, observerConfirmedActive = true)
        println("[SteleKit][benchmark] observer-healthy cumulative ticks over ${SIMULATED_WINDOW_MS / 1000}s: $ticks")
        assertTrue(
            ticks in (EXPECTED_WIDENED_TICKS - WIDENED_TICK_TOLERANCE)..(EXPECTED_WIDENED_TICKS + WIDENED_TICK_TOLERANCE),
            "expected ~$EXPECTED_WIDENED_TICKS ticks (widened 6x cadence) while observer confirmed active, got $ticks",
        )
    }

    @Test
    fun startHostDirectoryPolling_should_NotCompoundBackoffs_When_BothTabHiddenAndObserverConfirmedActive() = runTest {
        val ticks = runCumulativeTickCountCase(isTabHidden = true, observerConfirmedActive = true)
        println("[SteleKit][benchmark] combined-backoff cumulative ticks over ${SIMULATED_WINDOW_MS / 1000}s: $ticks")
        assertTrue(
            ticks in (EXPECTED_WIDENED_TICKS - WIDENED_TICK_TOLERANCE)..(EXPECTED_WIDENED_TICKS + WIDENED_TICK_TOLERANCE),
            "expected ~$EXPECTED_WIDENED_TICKS ticks (maxOf, not product, of both backoffs), got $ticks",
        )
    }
}
