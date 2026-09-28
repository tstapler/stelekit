package dev.stapler.stelekit.performance

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.logging.LogManager
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
    private val queryStatsRepository: QueryStatsRepository? = null,
    private val queryStatsCollector: QueryStatsCollector? = null,
    private val queryPlanRepository: QueryPlanRepository? = null,
) {
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    companion object {
        const val MAX_EXPORT_SPANS = 10_000
        private const val MIN_REGRESSION_SAMPLES = 3
        private const val MIN_REGRESSION_ABS_MS = 100L
        private const val MIN_REGRESSION_PCT = 50
    }

    /** Default directory for exports (platform Downloads folder). */
    fun defaultExportDirectory(): String = fileSystem.getDownloadsPath()

    /**
     * Opens the platform-native save-file picker and writes the performance report to the
     * chosen location. Returns the path on success, or null if the user cancelled.
     * Falls back to [export] with the default Downloads directory on platforms that don't
     * support a native picker (iOS, WASM).
     *
     * The picker is shown immediately before building the report to avoid perceived lag.
     * Platforms that have a native picker (Android/JVM) always support gzip, so the
     * suggested filename uses `.json.gz` unconditionally.
     */
    suspend fun exportWithPicker(): String? {
        val timestamp = formatTimestamp(HistogramWriter.epochMs())
        val suggestedName = "stelekit-perf-$timestamp.json.gz"
        val pickedPath = fileSystem.pickSaveFileAsync(suggestedName, "application/gzip")
        return if (pickedPath != null) {
            val (bytes, isGzip) = withContext(PlatformDispatcher.IO) { buildReportBytes() }
            val ok = if (isGzip) fileSystem.writeFileBytes(pickedPath, bytes)
                     else fileSystem.writeFile(pickedPath, bytes.decodeToString())
            check(ok) { "Failed to write perf report to $pickedPath" }
            pickedPath
        } else {
            // Platform has no native picker; fall back to the default Downloads directory
            export(directory = null)
        }
    }

    /**
     * Opens the platform-native save-file picker and writes the in-memory log buffer
     * (up to 1 000 most recent entries from [LogManager]) to a plain-text file.
     * Returns the path on success, or null if the user cancelled.
     */
    suspend fun exportLogsWithPicker(): String? {
        val timestamp = formatTimestamp(HistogramWriter.epochMs())
        val suggestedName = "stelekit-logs-$timestamp.txt"
        val pickedPath = fileSystem.pickSaveFileAsync(suggestedName, "text/plain") ?: return null
        val content = buildLogsText()
        val ok = fileSystem.writeFile(pickedPath, content)
        check(ok) { "Failed to write logs to $pickedPath" }
        return pickedPath
    }

    private fun buildLogsText(): String {
        val logs = LogManager.logs.value
        val header = "# SteleKit debug log export\n" +
            "# WARNING: these logs may contain snippets of your note content.\n" +
            "# Only share with trusted parties.\n"
        return header + logs.joinToString("\n") { entry ->
            val ts = entry.timestamp.toString().substringAfter("T").substringBefore("Z").take(12)
            val throwableSuffix = entry.throwable?.let {
                "\n  ${it::class.simpleName}: ${it.message}"
            } ?: ""
            "$ts [${entry.level}] ${entry.tag}: ${entry.message}$throwableSuffix"
        }
    }

    /**
     * Exports all recent spans and histogram summaries to a (gzip-compressed) JSON file.
     * If [directory] is null the platform Downloads folder is used.
     * Returns the absolute file path on success.
     */
    suspend fun export(directory: String? = null): String = withContext(PlatformDispatcher.IO) {
        val (bytes, isGzip) = buildReportBytes()
        val timestamp = formatTimestamp(HistogramWriter.epochMs())
        val dir = directory?.takeIf { it.isNotBlank() } ?: fileSystem.getDownloadsPath()
        val ext = if (isGzip) ".json.gz" else ".json"
        val path = "$dir/stelekit-perf-$timestamp$ext"
        val ok = if (isGzip) fileSystem.writeFileBytes(path, bytes)
                 else fileSystem.writeFile(path, bytes.decodeToString())
        check(ok) { "Failed to write perf report to $path" }
        path
    }

    /** Returns the report as (bytes, isGzip). Bytes are GZIP-compressed on JVM/Android. */
    private suspend fun buildReportBytes(): Pair<ByteArray, Boolean> {
        val content = buildReportJson()
        val raw = content.encodeToByteArray()
        val compressed = gzipBytes(raw)
        return if (compressed != null) compressed to true else raw to false
    }

    private suspend fun buildReportJson(): String {
        queryStatsCollector?.drainNow()
        val spans = spanRepository.getRecentSpans(limit = MAX_EXPORT_SPANS).first().getOrNull() ?: emptyList()
        val histograms = withContext(PlatformDispatcher.DB) {
            histogramWriter.queryAllPercentilesForExport()
        }
        val queryStats = withContext(PlatformDispatcher.DB) {
            queryStatsRepository?.getTopByTotalMs(appVersion, 100) ?: emptyList()
        }
        val sqlSamples = queryStatsCollector?.getSqlSamples() ?: emptyMap()
        val queryPlan = withContext(PlatformDispatcher.DB) {
            queryPlanRepository?.explainAll(sqlSamples) ?: emptyMap()
        }
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
        val spanStats = computeSpanStats(spans)
        val regressions = detectRegressions(spanStats, appVersion)
        return json.encodeToString(
            PerfExportReport(
                exportedAt = nowMs,
                appVersion = appVersion,
                commitHash = BuildInfo.commitHash,
                platform = platform,
                session = session,
                spans = spans,
                histograms = histograms,
                spanStats = spanStats,
                regressions = regressions,
                queryStats = queryStats,
                queryPlan = queryPlan,
            )
        )
    }

    private fun computeSpanStats(spans: List<SerializedSpan>): Map<String, List<SpanOperationStats>> {
        return spans
            .groupBy { it.attributes["app.version"]?.takeIf { v -> v.isNotEmpty() } ?: "unknown" }
            .mapValues { (version, vSpans) ->
                vSpans.groupBy { it.name }
                    .map { (operation, opSpans) ->
                        val sorted = opSpans.map { it.durationMs }.sorted()
                        val n = sorted.size
                        fun pct(p: Double) = sorted[((n - 1) * p / 100.0).toInt()]
                        val p95Threshold = pct(95.0)
                        val slowSpans = opSpans.filter { it.durationMs >= p95Threshold }
                        SpanOperationStats(
                            operation = operation,
                            appVersion = version,
                            count = n,
                            p50Ms = pct(50.0),
                            p75Ms = pct(75.0),
                            p95Ms = p95Threshold,
                            p99Ms = pct(99.0),
                            maxMs = sorted.last(),
                            totalMs = sorted.sum(),
                            errorCount = opSpans.count { it.statusCode == "ERROR" },
                            slowCaseAttributes = buildSlowCaseAttributes(slowSpans),
                        )
                    }
                    .sortedByDescending { it.totalMs }
            }
    }

    private val ignoredAttrKeys = setOf("session.id", "app.version", "app.commit")

    private fun buildSlowCaseAttributes(slowSpans: List<SerializedSpan>): Map<String, String> {
        if (slowSpans.isEmpty()) return emptyMap()
        val numeric = mutableMapOf<String, MutableList<Long>>()
        val strings = mutableMapOf<String, MutableMap<String, Int>>()
        for (span in slowSpans) {
            for ((k, v) in span.attributes) {
                if (k in ignoredAttrKeys) continue
                val num = v.toLongOrNull()
                if (num != null) {
                    numeric.getOrPut(k) { mutableListOf() }.add(num)
                } else {
                    val m = strings.getOrPut(k) { mutableMapOf() }
                    m[v] = (m[v] ?: 0) + 1
                }
            }
        }
        return buildMap {
            numeric.forEach { (k, vals) -> put(k, "avg=${vals.sum() / vals.size}") }
            strings.forEach { (k, counts) ->
                val top = counts.maxByOrNull { it.value }
                if (top != null) put(k, top.key)
            }
        }
    }

    private fun detectRegressions(
        spanStats: Map<String, List<SpanOperationStats>>,
        currentVersion: String,
    ): List<RegressionAlert> {
        val knownVersions = spanStats.keys
            .filter { it != "unknown" && it.isNotEmpty() }
            .sortedWith(Comparator { a, b -> compareSemver(a, b) })
        val currentIdx = knownVersions.indexOf(currentVersion)
        if (currentIdx <= 0) return emptyList()
        val prevVersion = knownVersions[currentIdx - 1]
        val curr = spanStats[currentVersion]?.associateBy { it.operation } ?: return emptyList()
        val prev = spanStats[prevVersion]?.associateBy { it.operation } ?: return emptyList()
        return curr.values.mapNotNull { currStats ->
            val prevStats = prev[currStats.operation] ?: return@mapNotNull null
            if (currStats.count < MIN_REGRESSION_SAMPLES || prevStats.count < MIN_REGRESSION_SAMPLES) return@mapNotNull null
            val absDelta = currStats.p95Ms - prevStats.p95Ms
            if (absDelta <= MIN_REGRESSION_ABS_MS) return@mapNotNull null
            val changePct = if (prevStats.p95Ms > 0) (absDelta * 100 / prevStats.p95Ms).toInt() else 0
            if (changePct <= MIN_REGRESSION_PCT) return@mapNotNull null
            RegressionAlert(
                operation = currStats.operation,
                baselineVersion = prevVersion,
                currentVersion = currentVersion,
                metric = "p95",
                baselineMs = prevStats.p95Ms,
                currentMs = currStats.p95Ms,
                changePercent = changePct,
            )
        }.sortedByDescending { it.changePercent }
    }

    private fun compareSemver(a: String, b: String): Int {
        val pa = a.split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val diff = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return a.compareTo(b)
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
