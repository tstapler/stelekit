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

    companion object {
        const val MAX_EXPORT_SPANS = 10_000
    }

    /** Default directory for exports (platform Downloads folder). */
    fun defaultExportDirectory(): String = fileSystem.getDownloadsPath()

    /**
     * Opens the platform-native save-file picker and writes the performance report to the
     * chosen location. Returns the path on success, or null if the user cancelled.
     * Falls back to [export] with the default Downloads directory on platforms that don't
     * support a native picker (iOS, WASM).
     */
    suspend fun exportWithPicker(): String? {
        val timestamp = formatTimestamp(HistogramWriter.epochMs())
        val suggestedName = "stelekit-perf-$timestamp.json"
        val pickedPath = fileSystem.pickSaveFileAsync(suggestedName, "application/json")
        return if (pickedPath != null) {
            val content = withContext(PlatformDispatcher.IO) { buildReportContent() }
            check(fileSystem.writeFile(pickedPath, content)) { "Failed to write perf report to $pickedPath" }
            pickedPath
        } else {
            // Platform has no native picker; fall back to the default Downloads directory
            export(directory = null)
        }
    }

    /**
     * Exports all recent spans and histogram summaries to a JSON file.
     * If [directory] is null the platform Downloads folder is used.
     * Returns the absolute file path on success.
     */
    suspend fun export(directory: String? = null): String = withContext(PlatformDispatcher.IO) {
        val content = buildReportContent()
        val timestamp = formatTimestamp(HistogramWriter.epochMs())
        val dir = directory?.takeIf { it.isNotBlank() } ?: fileSystem.getDownloadsPath()
        val path = "$dir/stelekit-perf-$timestamp.json"
        check(fileSystem.writeFile(path, content)) { "Failed to write perf report to $path" }
        path
    }

    private suspend fun buildReportContent(): String {
        val spans = spanRepository.getRecentSpans(limit = MAX_EXPORT_SPANS).first()
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
        return json.encodeToString(
            PerfExportReport(
                exportedAt = nowMs,
                appVersion = appVersion,
                platform = platform,
                session = session,
                spans = spans,
                histograms = histograms,
            )
        )
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
