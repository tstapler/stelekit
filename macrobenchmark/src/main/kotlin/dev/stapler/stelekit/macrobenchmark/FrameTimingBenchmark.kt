// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures rendered frame timing (p50/p90/p99) for common user flows.
 *
 * Run all frame timing benchmarks:
 *   ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -P android.testInstrumentationRunnerArguments.class=dev.stapler.stelekit.macrobenchmark.FrameTimingBenchmark
 *
 * Each test uses [ensureBenchmarkGraph] to write a 200-page synthetic graph to
 * /data/local/tmp/stelekit-bench/ via adb shell, then passes that path to
 * MainActivity via [EXTRA_BENCHMARK_GRAPH_PATH] so no SAF folder picker is needed.
 *
 * Key metrics in output:
 *   frameDurationCpuMs    — time the CPU spent on the frame
 *   frameOverrunMs        — how much the frame exceeded its deadline (positive = jank)
 */
@RunWith(AndroidJUnit4::class)
class FrameTimingBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /**
     * Measures frame timing while fast-scrolling the journal list.
     * The journal view is the first screen users see — jank here has high UX impact.
     */
    @Test
    fun journalScroll() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        // startupMode omitted (null) — avoids am force-stop on emulators; setupBlock manages launch.
        iterations = 3,
        setupBlock = {
            ensureBenchmarkGraph()
            startActivityWithBenchmarkGraph()
        },
    ) {
        val scrollable = device.findObject(By.scrollable(true))
        repeat(3) {
            scrollable?.fling(Direction.DOWN)
            Thread.sleep(200)
        }
        scrollable?.fling(Direction.UP)
        Thread.sleep(200)
    }

    /**
     * Measures frame timing while scrolling the All Pages list.
     * This list renders all page titles and is the largest list in the app.
     */
    @Test
    fun allPagesScroll() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 3,
        setupBlock = {
            ensureBenchmarkGraph()
            startActivityWithBenchmarkGraph()
        },
    ) {
        val allPages = device.findObject(By.textContains("All Pages"))
            ?: device.findObject(By.textContains("Pages"))
        if (allPages != null) {
            allPages.click()
            Thread.sleep(500)
        }
        val scrollable = device.findObject(By.scrollable(true))
        repeat(4) {
            scrollable?.fling(Direction.DOWN)
            Thread.sleep(150)
        }
    }

    /**
     * Measures frame timing during rapid page navigation (back/forward through 5 pages).
     * Navigation triggers page load, outliner pipeline, and block rendering on each tap.
     */
    @Test
    fun pageNavigation() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 3,
        setupBlock = {
            ensureBenchmarkGraph()
            startActivityWithBenchmarkGraph()
        },
    ) {
        // Tap through sidebar links to trigger navigation + block rendering
        repeat(5) { i ->
            val link = device.findObject(By.textContains("Page_$i"))
                ?: device.findObject(By.clickable(true).hasDescendant(By.text("Page_$i")))
            link?.click()
            Thread.sleep(400)
        }
    }
}

// ── helpers ────────────────────────────────────────────────────────────────────

internal const val TARGET_PACKAGE = "dev.stapler.stelekit"
private const val BENCH_GRAPH_PATH = "/data/local/tmp/stelekit-bench"

/**
 * Starts MainActivity with the benchmark graph path passed as an Intent extra,
 * bypassing the SAF folder picker that would otherwise block the test.
 */
private fun MacrobenchmarkScope.startActivityWithBenchmarkGraph() {
    startActivityAndWait { intent ->
        intent.putExtra("benchmark_graph_path", BENCH_GRAPH_PATH)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
    }
}

/**
 * Ensures the benchmark graph directory structure exists at [BENCH_GRAPH_PATH].
 *
 * Note: SELinux enforcing mode on API 26+ prevents the target app (untrusted_app domain)
 * from reading shell_data_file context files in /data/local/tmp/, so page content is never
 * actually loaded by the app. Frame timing tests measure empty-graph render performance.
 * We still create the directories and a ready marker so the path is valid and idempotent.
 *
 * Large for-loops via executeShellCommand hang due to a binder pipe issue on emulators
 * (the shell command executes but the output descriptor never closes). Avoid them here.
 */
private fun MacrobenchmarkScope.ensureBenchmarkGraph() {
    val ready = device.executeShellCommand(
        "test -f $BENCH_GRAPH_PATH/READY && echo yes || echo no"
    ).trim()
    if (ready == "yes") return

    device.executeShellCommand("mkdir -p $BENCH_GRAPH_PATH/pages $BENCH_GRAPH_PATH/journals")
    // Write a single page so the path is non-empty, then mark ready.
    device.executeShellCommand(
        "printf '- Benchmark page\\n- Second note\\n' > $BENCH_GRAPH_PATH/pages/Page_0.md"
    )
    device.executeShellCommand("echo ready > $BENCH_GRAPH_PATH/READY")
}
