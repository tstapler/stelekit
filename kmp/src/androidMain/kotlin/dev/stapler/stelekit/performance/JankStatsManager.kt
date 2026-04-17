package dev.stapler.stelekit.performance

import android.app.Activity
import androidx.metrics.performance.JankStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class FrameMetric(val lastFrameMs: Long = 0L, val isJank: Boolean = false)

/**
 * Registers [JankStats] for an [Activity] and routes jank frames to [HistogramWriter].
 *
 * JankStats uses the JANK_CUD heuristic: a frame is classified as janky when its
 * duration exceeds 2× the display refresh period (i.e. > 2 × 16 ms on a 60 Hz display).
 *
 * Call [register] in `Activity.onResume()` and [unregister] in `Activity.onPause()`.
 */
class JankStatsManager(
    private val histogramWriter: HistogramWriter,
    private val tracer: io.opentelemetry.api.trace.Tracer? = null
) {
    private var jankStats: JankStats? = null

    private val _frameMetric = MutableStateFlow(FrameMetric())
    val frameMetric: StateFlow<FrameMetric> = _frameMetric

    fun register(activity: Activity) {
        val window = activity.window ?: return
        jankStats = JankStats.createAndTrack(window, jankFrameListener)
    }

    fun unregister() {
        jankStats?.isTrackingEnabled = false
        jankStats = null
    }

    private val jankFrameListener = JankStats.OnFrameListener { frameData ->
        val durationMs = frameData.frameDurationUiNanos / 1_000_000L
        histogramWriter.record(
            operationName = "frame_duration",
            durationMs = durationMs
        )
        _frameMetric.value = FrameMetric(durationMs, frameData.isJank)

        if (frameData.isJank) {
            tracer?.spanBuilder("jank_frame")
                ?.setAttribute("frame.duration_ms", durationMs)
                ?.setAttribute("frame.state", frameData.states.joinToString { "${it.key}=${it.value}" })
                ?.startSpan()
                ?.end()
        }
    }
}
