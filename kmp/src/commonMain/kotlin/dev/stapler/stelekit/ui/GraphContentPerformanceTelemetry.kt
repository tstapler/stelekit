// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.performance.DebugMenuState
import dev.stapler.stelekit.performance.FrameMetric
import dev.stapler.stelekit.performance.HistogramWriter
import dev.stapler.stelekit.performance.PercentileSummary
import dev.stapler.stelekit.performance.PlatformJankStatsEffect
import dev.stapler.stelekit.performance.QueryStat
import dev.stapler.stelekit.performance.SerializedSpan
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Bundles [GraphContent]'s eager performance-data collection state (Parameter Object pattern). */
internal class GraphContentPerformanceTelemetry(
    val frameMetricState: MutableStateFlow<FrameMetric>,
    val perfSpans: MutableStateFlow<List<SerializedSpan>>,
    val perfHistograms: MutableStateFlow<Map<String, PercentileSummary>>,
    val perfQueryStats: MutableStateFlow<List<QueryStat>>,
)

/**
 * Data collection starts when the Performance screen is first opened so the tabs show content
 * immediately on first render. Pollers run only while the graph is loaded and the user has
 * visited the Performance screen at least once — no background DB reads on devices that never
 * open the Performance screen. Also wires the always-on frame-duration histogram/jank ring-buffer
 * recorder and [PlatformJankStatsEffect].
 */
@Composable
internal fun rememberGraphContentPerformanceTelemetry(
    repos: RepositorySet,
    viewModel: StelekitViewModel,
    debugMenuState: DebugMenuState,
): GraphContentPerformanceTelemetry {
    val telemetry = GraphContentPerformanceTelemetry(
        frameMetricState = remember { MutableStateFlow(FrameMetric()) },
        perfSpans = remember { MutableStateFlow(emptyList()) },
        perfHistograms = remember { MutableStateFlow(emptyMap()) },
        perfQueryStats = remember { MutableStateFlow(emptyList()) },
    )

    // Set to true the first time the user navigates to Screen.Performance; never resets.
    // This gates the pollers so we don't run background DB reads for users who never open
    // the Performance screen.
    val perfScreenEverOpened = remember { MutableStateFlow(false) }
    LaunchedEffect(viewModel) { trackPerformanceScreenOpened(viewModel, perfScreenEverOpened) }

    // Span data: reactive SQLDelight flow — fires whenever new spans are written to SQLite.
    // Starts immediately (spans are cheap to subscribe to; the reactive query only fires on writes).
    LaunchedEffect(repos.spanRepository) { collectRecentSpans(repos, telemetry.perfSpans) }

    // Histogram summaries: poll every 2s, but only after Performance screen first opened.
    LaunchedEffect(repos.histogramWriter) { pollHistograms(repos, perfScreenEverOpened, telemetry.perfHistograms) }

    // Query stats: poll every 5s, but only after Performance screen first opened.
    LaunchedEffect(repos.queryStatsRepository) { pollQueryStats(repos, perfScreenEverOpened, telemetry.perfQueryStats) }

    PlatformJankStatsEffect(histogramWriter = repos.histogramWriter, isEnabled = debugMenuState.isJankStatsEnabled)
    LaunchedEffect(repos.histogramWriter) { frameDurationLoop(repos, telemetry.frameMetricState) }

    return telemetry
}

private suspend fun trackPerformanceScreenOpened(viewModel: StelekitViewModel, perfScreenEverOpened: MutableStateFlow<Boolean>) {
    viewModel.uiState.collect { state ->
        if (state.currentScreen is Screen.Performance) perfScreenEverOpened.value = true
    }
}

private suspend fun collectRecentSpans(repos: RepositorySet, perfSpans: MutableStateFlow<List<SerializedSpan>>) {
    repos.spanRepository?.getRecentSpans(500)?.collect { result -> result.getOrNull()?.let { perfSpans.value = it } }
}

private suspend fun pollHistograms(
    repos: RepositorySet,
    perfScreenEverOpened: MutableStateFlow<Boolean>,
    perfHistograms: MutableStateFlow<Map<String, PercentileSummary>>,
) {
    val writer = repos.histogramWriter ?: return
    perfScreenEverOpened.first { it }   // suspend until first Performance screen visit
    while (true) {
        perfHistograms.value = withContext(PlatformDispatcher.DB) { queryAllPercentiles(writer) }
        delay(2_000)
    }
}

private fun queryAllPercentiles(writer: dev.stapler.stelekit.performance.HistogramWriter): Map<String, PercentileSummary> {
    val summaries = mutableMapOf<String, PercentileSummary>()
    for (op in writer.queryAllOperations()) {
        summaries[op] = writer.queryPercentiles(op) ?: continue
    }
    return summaries
}

private suspend fun pollQueryStats(
    repos: RepositorySet,
    perfScreenEverOpened: MutableStateFlow<Boolean>,
    perfQueryStats: MutableStateFlow<List<QueryStat>>,
) {
    val repo = repos.queryStatsRepository ?: return
    perfScreenEverOpened.first { it }   // suspend until first Performance screen visit
    while (true) {
        perfQueryStats.value = withContext(PlatformDispatcher.DB) {
            val version = repo.getAllVersions().firstOrNull() ?: ""
            repo.getTopByTotalMs(version, 50)
        }
        delay(5_000)
    }
}

/**
 * Records the always-on frame-duration histogram and jank ring-buffer entries. Only records when
 * a frame was actively rendered — withFrameNanos fires on demand, so when the app is idle Compose
 * can skip frames for hundreds of ms; gaps > 100ms are idle sleeps, not slow renders, and would
 * inflate the histogram and trigger false jank alerts if recorded.
 */
private suspend fun frameDurationLoop(repos: RepositorySet, frameMetricState: MutableStateFlow<FrameMetric>) {
    var lastNanos = 0L
    while (true) {
        withFrameNanos { nanos ->
            if (lastNanos != 0L) {
                recordFrameDuration(repos, frameMetricState, durationMs = (nanos - lastNanos) / 1_000_000L)
            }
            lastNanos = nanos
        }
    }
}

private fun recordFrameDuration(repos: RepositorySet, frameMetricState: MutableStateFlow<FrameMetric>, durationMs: Long) {
    if (durationMs !in 1L..100L) return
    repos.histogramWriter?.record("frame_duration", durationMs)
    val isJank = durationMs > 32L
    frameMetricState.value = FrameMetric(durationMs, isJank)
    if (isJank) {
        repos.ringBuffer?.record(
            SerializedSpan(
                name = "jank_frame",
                startEpochMs = HistogramWriter.epochMs() - durationMs,
                endEpochMs = HistogramWriter.epochMs(),
                durationMs = durationMs,
                attributes = mapOf("frame.duration_ms" to durationMs.toString()),
            )
        )
    }
}
