// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures startup time for the SteleKit app.
 *
 * Run with:
 *   ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -P android.testInstrumentationRunnerArguments.class=dev.stapler.stelekit.macrobenchmark.StartupBenchmark
 *
 * Results include timeToInitialDisplay and timeToFullDisplay (ms) reported to the
 * Android Studio benchmark output and written to a JSON file on the device.
 *
 * NOTE: startupMode is omitted (null) so the benchmark does not call am force-stop
 * between iterations. On physical devices with StartupMode.COLD the launcher would be
 * pre-warmed; without it we effectively measure warm startup even for coldStartup().
 * self-instrumentation (experimentalProperties["android.experimental.self-instrumenting"])
 * keeps the runner in its own process so force-stop is safe, but null avoids an extra
 * kill cycle that is only needed for true cold-start measurement.
 *
 * Requires a connected device or emulator with the benchmark APK installed.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        // startupMode omitted (null) — avoids am force-stop on emulators where runner and target
        // share the same process; use StartupMode.COLD on physical devices for true cold-start.
        iterations = 3,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun warmStartup() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 3,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun hotStartup() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 3,
    ) {
        startActivityAndWait()
    }
}
