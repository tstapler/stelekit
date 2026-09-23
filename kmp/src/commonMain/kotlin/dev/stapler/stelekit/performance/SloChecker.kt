package dev.stapler.stelekit.performance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * An SLO threshold: if [operationName]'s p99 exceeds [p99MaxMs], a "slo.violation" ERROR span
 * is emitted. Violations appear in the span viewer's error filter automatically.
 */
data class SloThreshold(val operationName: String, val p99MaxMs: Long)

/**
 * Runs a background loop (every 60 s) that checks latency SLOs against live histogram data.
 * Violations are emitted as ERROR spans so they surface in the Spans tab without extra UI.
 *
 * Also runs a basic regression check: if the current p99 for an operation is ≥ 2× its SLO
 * threshold, a "regression.suspected" span is emitted with the delta for AI analysis.
 */
class SloChecker(
    private val histogramWriter: HistogramWriter,
    private val spanEmitter: SpanEmitter,
    scope: CoroutineScope,
    private val thresholds: List<SloThreshold> = DEFAULT_THRESHOLDS,
) {
    companion object {
        val DEFAULT_THRESHOLDS = listOf(
            SloThreshold("graph_load",    5_000L),
            SloThreshold("navigation",      100L),
            SloThreshold("search",          300L),
            SloThreshold("editor_input",    100L),
            SloThreshold("sql.select",       50L),
            SloThreshold("sql.insert",      150L),
            SloThreshold("sql.update",      150L),
            SloThreshold("sql.delete",      150L),
            SloThreshold("db.queue_wait",   500L),
        )

        // Android SAF (Storage Access Framework) I/O goes through Binder IPC and, for
        // write-behind flushes, a batched background actor — both add latency that direct
        // JVM/desktop filesystem calls don't have. A flat threshold would either false-positive
        // on every Android write or mask real slowness on desktop.
        //
        // Desktop numbers are anchored to a measured local write+fsync/rename benchmark
        // (p99 ≈130ms / ≈32ms on this dev machine's disk), doubled-to-tripled for headroom.
        // Android numbers are anchored to this repo's own CI benchmark history
        // (benchmarks/android-history/*.json: safPerFileMs 0.13-0.63ms/file, worst observed
        // safOverhead 19ms across a ~20-file batch) — but that's an emulator, not the slow
        // real-world hardware this SLO exists to catch, so these carry a large deliberate
        // margin and are meant to be tightened once field p99 data (via this same SloChecker)
        // shows real headroom, rather than trusted as final.
        val DESKTOP_DISK_THRESHOLDS = listOf(
            SloThreshold("file.write",           300L),
            SloThreshold("file.write.deferred",  300L),
            SloThreshold("file.rename",          100L),
            SloThreshold("file.delete",          100L),
        )
        val ANDROID_DISK_THRESHOLDS = listOf(
            SloThreshold("file.write",           800L),
            SloThreshold("file.write.deferred", 1_500L),
            SloThreshold("file.rename",          500L),
            SloThreshold("file.delete",          400L),
        )

        /** Picks disk-IO thresholds by [platform] (from [getDeviceInfo]'s `platform` field). */
        fun diskThresholdsFor(platform: String): List<SloThreshold> =
            if (platform == "Android") ANDROID_DISK_THRESHOLDS else DESKTOP_DISK_THRESHOLDS

        /** [DEFAULT_THRESHOLDS] plus device-appropriate disk-IO thresholds for [platform]. */
        fun thresholdsFor(platform: String): List<SloThreshold> =
            DEFAULT_THRESHOLDS + diskThresholdsFor(platform)
    }

    init {
        scope.launch {
            delay(30_000)  // let the app warm up before first check
            while (true) {
                check()
                delay(60_000)
            }
        }
    }

    private fun check() {
        val nowMs = HistogramWriter.epochMs()
        for (threshold in thresholds) {
            val summary = histogramWriter.queryPercentiles(threshold.operationName) ?: continue
            if (summary.sampleCount < 5) continue  // not enough data to judge

            if (summary.p99Ms > threshold.p99MaxMs) {
                spanEmitter.emit(
                    name = "slo.violation",
                    startMs = nowMs,
                    endMs = nowMs,
                    statusCode = "ERROR",
                    attrs = mapOf(
                        "operation"     to threshold.operationName,
                        "p99_ms"        to summary.p99Ms.toString(),
                        "p50_ms"        to summary.p50Ms.toString(),
                        "threshold_ms"  to threshold.p99MaxMs.toString(),
                        "sample_count"  to summary.sampleCount.toString(),
                        "excess_pct"    to "${((summary.p99Ms - threshold.p99MaxMs) * 100 / threshold.p99MaxMs)}%",
                    )
                )
            }

            // Regression check: 2× threshold is a clear regression signal
            if (summary.p99Ms >= threshold.p99MaxMs * 2 && summary.sampleCount >= 20) {
                spanEmitter.emit(
                    name = "regression.suspected",
                    startMs = nowMs,
                    endMs = nowMs,
                    statusCode = "ERROR",
                    attrs = mapOf(
                        "operation"    to threshold.operationName,
                        "p99_ms"       to summary.p99Ms.toString(),
                        "baseline_ms"  to threshold.p99MaxMs.toString(),
                        "multiplier"   to "${(summary.p99Ms * 10 / threshold.p99MaxMs) / 10.0}x",
                    )
                )
            }
        }
    }
}
