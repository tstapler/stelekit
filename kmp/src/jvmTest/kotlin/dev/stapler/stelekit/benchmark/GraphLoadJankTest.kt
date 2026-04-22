package dev.stapler.stelekit.benchmark

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking

/**
 * Phase 1 TTI (time-to-interactive) regression tests.
 *
 * These tests fail when graph loading becomes slow enough to cause jank on startup.
 * Phase 1 loads the most-recent journals and fires [onPhase1Complete] — the point at
 * which the UI transitions from spinner to interactive. Anything beyond these budgets
 * will feel like a freeze to users on the first launch.
 *
 * Budgets are intentionally generous to avoid CI flake. If these tests are failing
 * by a wide margin, investigate GraphLoader, the parser, or database write latency.
 *
 * Run with JFR profiling to identify the bottleneck:
 *   ./gradlew :kmp:jvmTestProfile -PgraphPath=/your/graph
 */
class GraphLoadJankTest {

    private val fileSystem = PlatformFileSystem()

    private data class TtiResult(val phase1TtiMs: Long, val phase2Ms: Long)

    private fun runLoad(graphPath: String): TtiResult = runBlocking {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val loader = GraphLoader(fileSystem, pageRepo, blockRepo)

        val start = Clock.System.now()
        var phase1TtiMs = -1L
        var phase2Ms = -1L

        loader.loadGraphProgressive(
            graphPath = graphPath,
            immediateJournalCount = 10,
            onProgress = {},
            onPhase1Complete = { phase1TtiMs = (Clock.System.now() - start).inWholeMilliseconds },
            onFullyLoaded = { phase2Ms = (Clock.System.now() - start).inWholeMilliseconds },
        )

        TtiResult(phase1TtiMs, phase2Ms)
    }

    // ── TINY graph (50 pages, 14 journals) ───────────────────────────────────
    //
    // A TINY graph represents a new user's first few weeks. Phase 1 should fire
    // quickly — well under any perceptible delay.

    @Test
    fun tinyGraph_phase1TtiIsUnder3Seconds() {
        val dir = Files.createTempDirectory("jank-tiny").toFile()
        SyntheticGraphGenerator(SyntheticGraphGenerator.TINY).generate(dir)

        val result = runLoad(dir.absolutePath)

        println("[jank] TINY graph — Phase 1 TTI: ${result.phase1TtiMs}ms, Phase 2: ${result.phase2Ms}ms")
        assertTrue(
            result.phase1TtiMs in 0..3_000,
            "Phase 1 TTI must be <3 s on a TINY graph (${result.phase1TtiMs} ms)"
        )

        dir.deleteRecursively()
    }

    // ── SMALL graph (200 pages, 30 journals) ─────────────────────────────────
    //
    // Represents a typical user after a few months. Phase 1 processes only the 10
    // most-recent journals — page count does not affect TTI. Phase 2 runs in the
    // background; the budget is a ceiling on total background work.

    @Test
    fun smallGraph_phase1TtiIsUnder5Seconds() {
        val dir = Files.createTempDirectory("jank-small").toFile()
        SyntheticGraphGenerator(SyntheticGraphGenerator.SMALL).generate(dir)

        val result = runLoad(dir.absolutePath)

        println("[jank] SMALL graph — Phase 1 TTI: ${result.phase1TtiMs}ms, Phase 2: ${result.phase2Ms}ms")
        assertTrue(
            result.phase1TtiMs in 0..5_000,
            "Phase 1 TTI must be <5 s on a SMALL graph (${result.phase1TtiMs} ms)"
        )

        dir.deleteRecursively()
    }

    // ── Phase 1 must complete before Phase 2 ─────────────────────────────────
    //
    // Ordering guarantee: the UI becomes interactive (Phase 1) before background
    // loading finishes (Phase 2). If this is violated, users will see the spinner
    // disappear and then content jump in unexpectedly.

    @Test
    fun phase1AlwaysCompletesBeforePhase2() {
        val dir = Files.createTempDirectory("jank-order").toFile()
        SyntheticGraphGenerator(SyntheticGraphGenerator.TINY).generate(dir)

        val result = runLoad(dir.absolutePath)

        assertTrue(
            result.phase1TtiMs >= 0,
            "Phase 1 callback must have fired (was: ${result.phase1TtiMs})"
        )
        assertTrue(
            result.phase2Ms >= result.phase1TtiMs,
            "Phase 2 must not complete before Phase 1 (phase1=${result.phase1TtiMs}ms, phase2=${result.phase2Ms}ms)"
        )

        dir.deleteRecursively()
    }
}
