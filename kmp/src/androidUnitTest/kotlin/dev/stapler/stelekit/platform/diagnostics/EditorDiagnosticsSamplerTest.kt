package dev.stapler.stelekit.platform.diagnostics

import dev.stapler.stelekit.logging.LogManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorDiagnosticsSamplerTest {
    private class FakeProc(var selfTicks: Long = 100, var threads: List<String> = emptyList()) : ProcReader {
        override fun selfStat() = stat("self", selfTicks, 0)
        override fun threadStats() = threads
    }

    private companion object {
        fun stat(name: String, utime: Long, stime: Long) =
            "1 ($name) S 1 1 0 0 -1 0 0 0 0 0 $utime $stime 0 0 20 0 5 0 1 1 1"
    }

    private val source = EditorDiagnosticsSource {
        EditorDiagnosticsSnapshot("DISTANCE", "AnnotationEditor", 4000, 3000, undoDepth = 3)
    }

    @Before fun clear() = LogManager.clearLogs()

    @After fun cleanup() = LogManager.clearLogs()

    private fun samples() = LogManager.logs.value.map { it.message }.filter { it.contains("cpuMsDelta=") }

    @Test
    fun sampleOnce_should_LogCpuDeltaTopThreadsForegroundUndoDepthScreenAndImageSize() {
        val proc = FakeProc(
            selfTicks = 100,
            threads = listOf(stat("main", 50, 0), stat("RenderThread", 30, 0), stat("Binder:1", 5, 0), stat("tiny", 1, 0)),
        )
        val sampler = EditorDiagnosticsSampler(proc, isForeground = { true }, coilCacheBytes = { 2048 }, clockTicksPerSecond = 100)

        sampler.sampleOnce(source)
        proc.selfTicks = 160
        sampler.sampleOnce(source)

        val line = samples().first() // newest first
        assertTrue(line.contains("cpuMsDelta=600"), line)
        assertTrue(line.contains("threads=main:50,RenderThread:30,Binder:5"), line)
        assertTrue(line.contains("foreground=true"), line)
        assertTrue(line.contains("coilCacheKb=2"), line)
        assertTrue(line.contains("undoDepth=3"), line)
        assertTrue(line.contains("screen=AnnotationEditor"), line)
        assertTrue(line.contains("image=4000x3000"), line)
    }

    @Test
    fun sampleOnce_should_NeverThrow_When_ProcReadsFail() {
        val broken = object : ProcReader {
            override fun selfStat(): String? = null
            override fun threadStats(): List<String> = error("procfs gone")
        }
        val sampler = EditorDiagnosticsSampler(broken, isForeground = { false }, coilCacheBytes = { null })

        sampler.sampleOnce(source)

        assertTrue(samples().isEmpty())
    }

    @Test
    fun tick_should_SampleEveryIntervalWhileStartedAndWriteStopMarkerOnStop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sampler = EditorDiagnosticsSampler(
            FakeProc(), isForeground = { false }, coilCacheBytes = { null }, intervalMs = 10_000, dispatcher = dispatcher,
        )

        sampler.start(source)
        runCurrent()
        assertEquals(1, samples().size)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, samples().size)
        sampler.stop()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(2, samples().size)
        assertTrue(LogManager.logs.value.any { it.message == DiagnosticsSample.STOPPED_LINE })
    }
}
