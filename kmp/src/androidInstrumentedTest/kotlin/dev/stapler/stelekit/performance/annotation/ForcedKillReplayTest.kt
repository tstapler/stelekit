package dev.stapler.stelekit.performance.annotation

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.platform.diagnostics.EXIT_LINE_PREFIX
import dev.stapler.stelekit.platform.diagnostics.EXIT_RECORD_REQUEST_COUNT
import dev.stapler.stelekit.platform.diagnostics.ExitRecord
import dev.stapler.stelekit.platform.diagnostics.formatExitRecord
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Device-only evidence for PR-0 (plan A.0a); NOT a CI gate. Two instrumentation runs:
 *
 *  1. `-e killPhase kill`   writes a marker, then kills the process with [Process.killProcess]
 *     (the stand-in for the `exitOnBackground` mutation: the OS records a SIGNALED exit).
 *  2. `-e killPhase replay` (a later run, new process) asserts the platform exit record is readable
 *     and formats with reason name, `importance=`, status, PSS and description, i.e. what the Logs
 *     screen and the post-kill banner are built from.
 *
 * Run: `./gradlew :kmp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=
 * dev.stapler.stelekit.performance.annotation.ForcedKillReplayTest -Pandroid.testInstrumentationRunnerArguments.killPhase=kill`
 * then again with `killPhase=replay`. Without a phase argument both tests are skipped.
 */
class ForcedKillReplayTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("killPhase")
    private val marker get() = File(context.filesDir, "forced-kill-marker")

    @Test
    fun run1_should_KillTheProcessAfterWritingAMarker() {
        assumeTrue(phase == "kill")
        marker.writeText(System.currentTimeMillis().toString())
        Process.killProcess(Process.myPid())
    }

    @Test
    fun run2_should_ReplayTheExitRecordInTheLogsFormat() {
        assumeTrue(phase == "replay")
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        assertTrue(marker.exists(), "run the kill phase first")
        val am = context.getSystemService(ActivityManager::class.java)
        val records = am.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_RECORD_REQUEST_COUNT).map {
            ExitRecord(it.reason, it.importance, it.status, it.pss, it.rss, it.timestamp, it.description)
        }
        assertTrue(records.isNotEmpty(), "no historical exit records were returned")

        val line = formatExitRecord(records.first())
        LogManager.clearLogs()

        assertTrue(line.startsWith(EXIT_LINE_PREFIX), line)
        assertTrue(Regex("importance=[A-Z_]+|importance=IMPORTANCE_\\d+").containsMatchIn(line), line)
        assertTrue(Regex("pss=\\d+MB").containsMatchIn(line), line)
        assertTrue(line.contains("status="), line)
        marker.delete()
    }
}
