package dev.stapler.stelekit.performance

import kotlinx.serialization.Serializable

/**
 * Per-session aggregate stats prepended to the export so an LLM can answer
 * "is this session an outlier?" without scanning all spans.
 */
@Serializable
data class SessionSummary(
    val sessionId: String,
    val sessionStartMs: Long,
    val sessionDurationMs: Long,
    val totalSpans: Int,
    val errorSpans: Int,
    val sloViolations: List<String>,
    val p99ByOperation: Map<String, Long>,
)

@Serializable
data class PerfExportReport(
    val exportedAt: Long,
    val appVersion: String,
    val platform: String,
    val session: SessionSummary,
    val spans: List<SerializedSpan>,
    val histograms: Map<String, PercentileSummary>,
)
