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

/** Percentile breakdown for a single operation in a single app version. */
@Serializable
data class SpanOperationStats(
    val operation: String,
    val appVersion: String,
    val count: Int,
    val p50Ms: Long,
    val p75Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val maxMs: Long,
    val totalMs: Long,
    val errorCount: Int,
    /** Average numeric attribute values (and most-common string values) for spans at or above p95. */
    val slowCaseAttributes: Map<String, String> = emptyMap(),
)

/**
 * Flags an operation whose p95 increased materially between two consecutive
 * versions present in the export's span history.
 */
@Serializable
data class RegressionAlert(
    val operation: String,
    val baselineVersion: String,
    val currentVersion: String,
    val metric: String,
    val baselineMs: Long,
    val currentMs: Long,
    val changePercent: Int,
)

@Serializable
data class PerfExportReport(
    val exportedAt: Long,
    val appVersion: String,
    val commitHash: String,
    val platform: String,
    val session: SessionSummary,
    val spans: List<SerializedSpan>,
    val histograms: Map<String, PercentileSummary>,
    /** Per-version, per-operation stats computed from [spans]. Outer key = app_version. */
    val spanStats: Map<String, List<SpanOperationStats>> = emptyMap(),
    /** Operations whose p95 regressed materially vs the nearest prior version in [spanStats]. */
    val regressions: List<RegressionAlert> = emptyList(),
)
