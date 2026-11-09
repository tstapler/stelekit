// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.diagnostics

import android.app.ActivityManager
import android.system.Os
import android.system.OsConstants
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.ui.components.sharedImageMemoryCacheSizeBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Source of the `/proc` text the sampler parses; a seam so tests need no real procfs. */
interface ProcReader {
    fun selfStat(): String?
    fun threadStats(): List<String>
}

object ProcFsReader : ProcReader {
    override fun selfStat(): String? = readOrNull(File("/proc/self/stat"))

    override fun threadStats(): List<String> =
        File("/proc/self/task").listFiles().orEmpty().mapNotNull { readOrNull(File(it, "stat")) }

    // A thread can exit between listing and reading, so any single read may fail.
    private fun readOrNull(file: File): String? = try {
        file.readText()
    } catch (_: Exception) {
        null
    }
}

/**
 * Writes one `DiagnosticsSample` line to the persisted log every [intervalMs] while the annotation
 * editor is composed (PR-0 minimum form: no burst trigger, no process-state summary yet). The next
 * launch replays the last lines into the Logs screen, so a kill leaves its own context behind.
 *
 * Owns its scope with a [CoroutineExceptionHandler]: an uncaught Throwable here would kill the process
 * on Android, and a diagnostic must never be the thing that crashes the app.
 */
class EditorDiagnosticsSampler(
    private val procReader: ProcReader,
    private val isForeground: () -> Boolean,
    private val coilCacheBytes: () -> Long?,
    private val clockTicksPerSecond: Long = DEFAULT_CLOCK_TICKS_PER_SECOND,
    private val intervalMs: Long = DiagnosticsSample.TICK_INTERVAL_MS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : EditorDiagnosticsSink {
    private val logger = Logger("DiagnosticsSample")
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, t ->
            runCatching { logger.warn("Diagnostics sampler failed: ${t::class.simpleName}: ${t.message}") }
        },
    )
    private var job: Job? = null
    private var lastTicks: Long? = null

    @Synchronized
    override fun start(source: EditorDiagnosticsSource) {
        job?.cancel()
        lastTicks = null
        job = scope.launch {
            while (isActive) {
                sampleOnce(source)
                delay(intervalMs)
            }
        }
    }

    @Synchronized
    override fun stop() {
        if (job == null) return
        job?.cancel()
        job = null
        logger.info(DiagnosticsSample.STOPPED_LINE)
    }

    /** Takes and logs one sample. Never throws: a failed read skips the tick. */
    internal fun sampleOnce(source: EditorDiagnosticsSource) {
        try {
            val sample = collect(source)
            logger.info(sample.format())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Diagnostics sample skipped: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun collect(source: EditorDiagnosticsSource): DiagnosticsSample {
        val total = procReader.selfStat()?.let(ProcStatParser::parseLine)?.ticks
        val previous = lastTicks
        val delta = if (total != null && previous != null) (total - previous).coerceAtLeast(0) else 0L
        if (total != null) lastTicks = total
        val runtime = Runtime.getRuntime()
        return DiagnosticsSample(
            cpuMsDelta = delta * MS_PER_SECOND / clockTicksPerSecond,
            topThreadGroups = ProcStatParser.topGroups(ProcStatParser.parseAll(procReader.threadStats())),
            foreground = isForeground(),
            heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB,
            heapMaxMb = runtime.maxMemory() / BYTES_PER_MB,
            coilCacheKb = coilCacheBytes()?.let { it / BYTES_PER_KB },
            editor = source.snapshot(),
        )
    }

    companion object {
        private const val DEFAULT_CLOCK_TICKS_PER_SECOND = 100L
        private const val MS_PER_SECOND = 1000L
        private const val BYTES_PER_KB = 1024L
        private const val BYTES_PER_MB = 1024L * 1024L

        fun create(): EditorDiagnosticsSampler = EditorDiagnosticsSampler(
            procReader = ProcFsReader,
            isForeground = {
                val info = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(info)
                info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            },
            coilCacheBytes = ::sharedImageMemoryCacheSizeBytes,
            clockTicksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: DEFAULT_CLOCK_TICKS_PER_SECOND,
        )
    }
}
