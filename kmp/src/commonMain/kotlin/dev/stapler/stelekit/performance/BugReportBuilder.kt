package dev.stapler.stelekit.performance

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_REPRO_STEPS_LENGTH = 2000

@Serializable
data class BugReport(
    val generatedAt: Long,
    val appVersion: String,
    @Contextual val deviceInfo: DeviceInfo,
    val reproductionSteps: String,
    val histogramSummary: Map<String, PercentileSummary>,
    val recentSpans: List<SerializedSpan>
)

/**
 * Assembles a [BugReport] from ring buffer spans, histogram percentiles, device info,
 * and user-supplied reproduction steps.
 *
 * All inputs are passed explicitly — no global state accessed.
 */
class BugReportBuilder(
    private val ringBuffer: RingBufferSpanExporter,
    private val histogramWriter: HistogramWriter,
    private val operationNames: List<String> = listOf("graph_load", "navigation", "search", "frame_duration")
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun build(reproductionSteps: String = ""): BugReport {
        val steps = if (reproductionSteps.length > MAX_REPRO_STEPS_LENGTH) {
            reproductionSteps.take(MAX_REPRO_STEPS_LENGTH)
        } else {
            reproductionSteps
        }

        val spans = ringBuffer.snapshot()
        val histograms = operationNames.mapNotNull { op ->
            histogramWriter.queryPercentiles(op)?.let { op to it }
        }.toMap()

        val device = getDeviceInfo()
        return BugReport(
            generatedAt = HistogramWriter.epochMs(),
            appVersion = device.appVersion,
            deviceInfo = device,
            reproductionSteps = steps,
            histogramSummary = histograms,
            recentSpans = spans
        )
    }

    fun buildJson(reproductionSteps: String = ""): String =
        json.encodeToString(build(reproductionSteps))
}
