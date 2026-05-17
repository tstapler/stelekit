package dev.stapler.stelekit.benchmark

import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TC-12: [LatencyShimFileSystem] blocks the calling thread for at least the configured latency.
 *
 * This validates ADR-001: [Thread.sleep] (not `delay`) is the correct simulation of
 * SAF Binder IPC blocking. If [delay] were used instead of [Thread.sleep], the calling
 * thread would NOT be held and the benchmark would not accurately simulate the Binder IPC
 * starvation of [Dispatchers.Default] threads that caused the Android insert lag.
 */
class LatencyShimFileSystemTest {

    @Test
    fun writeFile_blocksCallingThreadForConfiguredLatency() {
        val latencyMs = 20L
        val shim = LatencyShimFileSystem(FakeFileSystem(), writeLatencyMs = latencyMs)

        val start = System.currentTimeMillis()
        shim.writeFile("/tmp/test.md", "content")
        val elapsed = System.currentTimeMillis() - start

        assertTrue(
            elapsed >= latencyMs,
            "LatencyShim must block the calling thread for at least ${latencyMs}ms (ADR-001), was ${elapsed}ms",
        )
    }

    @Test
    fun fileExists_blocksCallingThreadForConfiguredLatency() {
        val latencyMs = 15L
        val shim = LatencyShimFileSystem(FakeFileSystem(), existsLatencyMs = latencyMs)

        val start = System.currentTimeMillis()
        shim.fileExists("/tmp/test.md")
        val elapsed = System.currentTimeMillis() - start

        assertTrue(
            elapsed >= latencyMs,
            "LatencyShim.fileExists must block for at least ${latencyMs}ms, was ${elapsed}ms",
        )
    }

    @Test
    fun readFile_blocksCallingThreadForConfiguredLatency() {
        val latencyMs = 15L
        val shim = LatencyShimFileSystem(FakeFileSystem(), readLatencyMs = latencyMs)

        val start = System.currentTimeMillis()
        shim.readFile("/tmp/test.md")
        val elapsed = System.currentTimeMillis() - start

        assertTrue(
            elapsed >= latencyMs,
            "LatencyShim.readFile must block for at least ${latencyMs}ms, was ${elapsed}ms",
        )
    }

    @Test
    fun delegatesActualResultsToUnderlyingFileSystem() {
        val shim = LatencyShimFileSystem(FakeFileSystem(), writeLatencyMs = 1L, existsLatencyMs = 1L, readLatencyMs = 1L)

        // FakeFileSystem returns true/true/"" for all calls
        assertTrue(shim.writeFile("/tmp/test.md", "content"), "writeFile result must come from delegate")
        assertTrue(shim.fileExists("/tmp/test.md"), "fileExists result must come from delegate")
    }
}
