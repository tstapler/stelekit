// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.system.Os
import android.system.OsConstants
import dev.stapler.stelekit.platform.diagnostics.ProcFsReader
import dev.stapler.stelekit.platform.diagnostics.ProcReader
import dev.stapler.stelekit.platform.diagnostics.ProcStatParser
import java.io.File

/** One finished process, as `ApplicationExitInfo` reports it; filtered by the budget checker's allowlist. */
data class ExitRecord(val reason: String, val pid: Int, val timestamp: Long)

/**
 * The one helper that records CPU, per-thread share, heap, native, PSS, `VmHWM` and exit reasons
 * around any scenario (ADR-001), so CPU and memory come from the same run. Frame metrics come from
 * [FrameSampler] and peaks from [PeakSampler]; both are driven by the scenario around its measured block.
 */
class ResourceProbe(
    private val context: Context,
    private val proc: ProcReader = ProcFsReader,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private val clockTicksPerSecond: Long = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(DEFAULT_TICKS)

    private fun ticksToMs(ticks: Long) = ticks * MS_PER_SECOND / clockTicksPerSecond

    fun snapshot(): ProbeSnapshot {
        val rt = Runtime.getRuntime()
        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val threads = ProcStatParser.parseAll(proc.threadStats())
        return ProbeSnapshot(
            wallNanos = System.nanoTime(),
            cpuMs = ticksToMs(proc.selfStat()?.let(ProcStatParser::parseLine)?.ticks ?: -1L),
            threadCpuMs = ProcStatParser.topGroups(threads, TOP_THREAD_GROUPS)
                .map { it.copy(ticks = ticksToMs(it.ticks)) },
            javaUsedBytes = rt.totalMemory() - rt.freeMemory(),
            nativeAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            pssKb = mem.totalPss.toLong(),
            graphicsKb = mem.getMemoryStat("summary.graphics")?.toLongOrNull() ?: 0L,
            vmHwmKb = vmHwmKb(),
        )
    }

    /** Three GC cycles with a pause each; one `System.gc()` is a hint on ART, not a collection. */
    fun settle() {
        repeat(GC_CYCLES) {
            System.gc()
            Runtime.getRuntime().runFinalization()
            sleep(SETTLE_SLEEP_MS)
        }
    }

    /** Post-GC Java heap, so retained memory is not confused with garbage not yet collected. */
    fun settledJavaUsedBytes(): Long {
        settle()
        return snapshot().javaUsedBytes
    }

    /**
     * Exit reasons recorded for this app, newest first (API 30+; empty below). The caller filters by
     * pid and scenario start; the budget checker applies the bad-reason allowlist.
     */
    fun exitRecords(maxCount: Int = MAX_EXIT_RECORDS): List<ExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getHistoricalProcessExitReasons(context.packageName, 0, maxCount).map {
            ExitRecord(reasonName(it.reason), it.pid, it.timestamp)
        }
    }

    /** True while this process is still the one the scenario started in. */
    fun processSurvived(startPid: Int): Boolean = Process.myPid() == startPid

    private fun vmHwmKb(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM:") }?.filter { it.isDigit() }?.toLong() ?: 0L
        }
    }.getOrDefault(-1L)

    companion object {
        const val GC_CYCLES = 3
        const val SETTLE_SLEEP_MS = 100L
        private const val TOP_THREAD_GROUPS = 3
        private const val MAX_EXIT_RECORDS = 16
        private const val DEFAULT_TICKS = 100L
        private const val MS_PER_SECOND = 1000L

        fun reasonName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            else -> "OTHER"
        }
    }
}
