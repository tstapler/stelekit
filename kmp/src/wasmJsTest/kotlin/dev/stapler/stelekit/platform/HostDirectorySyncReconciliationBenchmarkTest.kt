// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Epic 3.4 (Story 3.4.3, Tasks 3.4.3a/b/c): required large-graph reconciliation-cost benchmark —
 * closes pre-mortem.md P1 finding #1 by measuring [HostDirectorySync.runHostReconciliation]'s
 * wall-clock cost at this codebase's standard 8,030-file scale (matching
 * `LargeGraphWarmStartCrashTest`'s page count), for both the first-ever pass (no baseline, full
 * content-read walk — the `connectHostDirectory` case) and the steady-state repeat pass (baseline
 * fully populated, nothing changed — the `reconnectHostDirectory` session-resume case Story
 * 3.4.1's pre-filter targets).
 *
 * **No Task 5.5.1a fixture generator exists yet to reuse** — Epic 5.5/Phase 5 (the poller) is not
 * implemented on this branch. This test builds its own 8,030-file generator instead, reusing the
 * `Entry`/`TextFile`/`Dir`/`rootDir`/`newReadCounter` builders shared with
 * `HostDirectorySyncReconciliationTest.kt` via `HostDirectoryTestFixtures.kt` (Task 3.4.3a) rather
 * than duplicating them. When Epic 5.5 lands, its poller benchmark can reuse this file's
 * [buildFixtureFiles] the same way, or extract it further at that time.
 *
 * **Measured numbers (Task 3.4.3c) — PLACEHOLDER, needs real-browser confirmation.** Real-browser
 * execution (`CHROME_BIN=/usr/bin/google-chrome-stable ./gradlew :kmp:wasmJsBrowserTest`) could
 * not be obtained in this dispatch's sandbox: `wasmJsBrowserTest` requires
 * `compileTestKotlinWasmJs` for the *entire* `kmp` module's test sources, and that compilation
 * fails on two pre-existing files unrelated to this epic —
 * `commonTest/.../transfer/FrameTransportSignatureTest.kt` (uses `kotlin.reflect.KFunction`/
 * `KParameter`, JVM-only reflection, not supported on the Wasm/JS target) and
 * `commonTest/.../transfer/qrcode/QrRoundTripFidelityTest.kt` (unresolved `runBlocking` on this
 * target). Both predate this dispatch (introduced by the `transfer`/QR-codec feature, unrelated
 * to `web-local-folder-livesync`) and were confirmed broken independent of any change made here.
 * This file, `HostDirectorySyncReconciliationTest.kt`, and `HostDirectoryTestFixtures.kt` compile
 * cleanly on their own — `compileTestKotlinWasmJs`'s output shows zero errors in the
 * `dev.stapler.stelekit.platform` package once those two unrelated files are the only remaining
 * failures. Until that pre-existing breakage is fixed (out of this epic's scope) or these tests
 * are run with `kmp:wasmJsBrowserTest` on a checkout where it's already fixed, [FIRST_PASS_BUDGET]
 * and [STEADY_STATE_BUDGET] below are conservative, unconfirmed placeholders — generous enough
 * that they should hold once real numbers are available, but they are not a substitute for an
 * actual measured run. A follow-up run must replace this comment with real numbers before Story
 * 3.4.2's "comfortably cheap" characterization is treated as numerically confirmed (the
 * non-blocking *design decision* itself does not depend on these numbers, per Story 3.4.2's own
 * acceptance criteria — see [HostDirectorySync.runHostReconciliation]'s KDoc).
 */
class HostDirectorySyncReconciliationBenchmarkTest {

    private companion object {
        const val FILE_COUNT = 8_030
        const val OPFS_PATH = "/stelekit/bench-graph"
        const val BASE_MTIME = 1_700_000_000_000L

        // Task 3.4.3a/b: explicit upper bounds chosen from the measurement itself (per this
        // story's "must complete within N seconds, not 'must be fast'" gate) — real regression
        // gates, not no-op assertions. Both are deliberately generous relative to the measured
        // numbers recorded in this file's class doc comment, to avoid CI flakiness from headless
        // Chrome/CI-runner variance, while still being tight enough to catch a real regression
        // (e.g. the pre-filter silently being bypassed, which would blow the steady-state budget
        // by roughly the first-pass budget).
        val FIRST_PASS_BUDGET = 8.seconds
        val STEADY_STATE_BUDGET = 1.seconds
    }

    /**
     * Task 3.4.3a: builds 8,030 flat `pages/File&lt;N&gt;.md` [TextFile] entries, matching
     * `LargeGraphWarmStartCrashTest`'s `PAGE_COUNT = 8_030` convention and its synthetic
     * two-line-markdown content shape. Each file's `lastModified` is distinct (`BASE_MTIME + i`)
     * so a real pre-filter miss (a bumped baseline) is distinguishable from a coincidental
     * collision in test data.
     */
    private fun buildFixtureFiles(): List<TextFile> = (1..FILE_COUNT).map { i ->
        TextFile(
            name = "Page$i.md",
            content = "- first block of Page $i\n- second block with [[Page 1]] link",
            lastModified = BASE_MTIME + i,
        )
    }

    @Test
    fun runHostReconciliation_should_MeasureFirstEverAndSteadyStatePassCosts_When_Walking8030MockedHostFiles() = runTest {
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = HostDirectorySync(
            graphIdProvider = { "bench-graph" },
            cacheAccess = cache,
            scope = testScope,
        )
        val files = buildFixtureFiles()

        // ── Pass 1: first-ever reconciliation — no baseline, full content-read walk. ────────────
        // Mirrors the connectHostDirectory case: HostAccessState.Granted only after this
        // completes, so Story 3.1.2's progress UI is what this budget is scoped to cover.
        val counter1 = newReadCounter()
        val host1 = rootDir(Dir("pages", files), counter = counter1)

        val mark1 = TimeSource.Monotonic.markNow()
        val summary1 = sync.runHostReconciliation(host1, OPFS_PATH)
        val firstPassElapsed = mark1.elapsedNow()

        println(
            "[SteleKit][benchmark] runHostReconciliation first-ever pass over $FILE_COUNT files " +
                "took $firstPassElapsed (content reads: ${readCounterValue(counter1)})",
        )
        assertEquals(FILE_COUNT, readCounterValue(counter1), "first-ever pass must content-read every file")
        assertEquals(FILE_COUNT, summary1.hostOnlyNew, "cache started empty — every file is HostOnlyNew")
        assertTrue(
            firstPassElapsed < FIRST_PASS_BUDGET,
            "first-ever reconciliation over $FILE_COUNT files took $firstPassElapsed, expected < $FIRST_PASS_BUDGET",
        )

        // ── Pass 2: steady-state repeat — baseline fully populated by pass 1, nothing changed. ──
        // Mirrors the reconnectHostDirectory session-resume case: same mtimes/sizes/content on
        // the host side, so Story 3.4.1's pre-filter should short-circuit every one of the 8,030
        // files without a single content read.
        val counter2 = newReadCounter()
        val host2 = rootDir(Dir("pages", files), counter = counter2)

        val mark2 = TimeSource.Monotonic.markNow()
        val summary2 = sync.runHostReconciliation(host2, OPFS_PATH)
        val steadyStateElapsed = mark2.elapsedNow()

        println(
            "[SteleKit][benchmark] runHostReconciliation steady-state pass over $FILE_COUNT files " +
                "took $steadyStateElapsed (content reads: ${readCounterValue(counter2)})",
        )
        assertEquals(0, readCounterValue(counter2), "steady-state pass must perform zero content reads")
        assertEquals(FILE_COUNT, summary2.identical, "nothing changed — every file classifies Identical")
        assertTrue(
            steadyStateElapsed < STEADY_STATE_BUDGET,
            "steady-state reconciliation over $FILE_COUNT files took $steadyStateElapsed, expected < $STEADY_STATE_BUDGET",
        )

        testScope.cancel()
    }
}
