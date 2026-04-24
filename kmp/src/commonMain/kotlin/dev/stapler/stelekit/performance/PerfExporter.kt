package dev.stapler.stelekit.performance

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PerfExporter(
    private val spanRepository: SpanRepository,
    private val histogramWriter: HistogramWriter,
    private val fileSystem: FileSystem,
    private val appVersion: String,
    private val platform: String,
) {
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    /** Default directory for exports (platform Downloads folder). */
    fun defaultExportDirectory(): String = fileSystem.getDownloadsPath()

    /**
     * Exports all recent spans and histogram summaries to a JSON file.
     * If [directory] is null the platform Downloads folder is used.
     * Returns the absolute file path on success.
     */
    suspend fun export(directory: String? = null): String = withContext(PlatformDispatcher.IO) {
        val spans = spanRepository.getRecentSpans(limit = 10_000).first()
        val histograms = HistogramWriter.KNOWN_OPERATIONS
            .mapNotNull { op -> histogramWriter.queryPercentiles(op)?.let { op to it } }
            .toMap()
        val nowMs = HistogramWriter.epochMs()
        val sloViolations = spans
            .filter { it.name == "slo.violation" && it.statusCode == "ERROR" }
            .mapNotNull { it.attributes["operation"] }
            .distinct()
        val session = SessionSummary(
            sessionId = AppSession.id,
            sessionStartMs = AppSession.startEpochMs,
            sessionDurationMs = nowMs - AppSession.startEpochMs,
            totalSpans = spans.size,
            errorSpans = spans.count { it.statusCode == "ERROR" },
            sloViolations = sloViolations,
            p99ByOperation = histograms.mapValues { it.value.p99Ms },
        )
        val report = PerfExportReport(
            exportedAt = nowMs,
            appVersion = appVersion,
            platform = platform,
            session = session,
            spans = spans,
            histograms = histograms,
        )
        val content = json.encodeToString(report)
        val timestamp = formatTimestamp(nowMs)
        val dir = directory?.takeIf { it.isNotBlank() } ?: fileSystem.getDownloadsPath()
        val path = "$dir/stelekit-perf-$timestamp.json"
        check(fileSystem.writeFile(path, content)) { "Failed to write perf report to $path" }
        path
    }

    private fun formatTimestamp(epochMs: Long): String {
        val local = Instant.fromEpochMilliseconds(epochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        return "${local.year.toString().padStart(4, '0')}-" +
            "${local.monthNumber.toString().padStart(2, '0')}-" +
            "${local.dayOfMonth.toString().padStart(2, '0')}-" +
            "${local.hour.toString().padStart(2, '0')}" +
            local.minute.toString().padStart(2, '0')
    }
}
