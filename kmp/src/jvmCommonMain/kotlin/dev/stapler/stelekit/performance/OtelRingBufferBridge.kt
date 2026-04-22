package dev.stapler.stelekit.performance

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * Bridges the OTel SDK span pipeline into [RingBufferSpanExporter] for in-app display.
 * Register with [SdkTracerProvider] via [SimpleSpanProcessor] for zero-delay export.
 */
class OtelRingBufferBridge(private val ringBuffer: RingBufferSpanExporter) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        spans.forEach { span ->
            val otelParentId = span.parentSpanId
            ringBuffer.record(SerializedSpan(
                name = span.name,
                traceId = span.spanContext.traceId,
                spanId = span.spanContext.spanId,
                parentSpanId = if (otelParentId == "0000000000000000") "" else otelParentId,
                startEpochMs = span.startEpochNanos / 1_000_000,
                endEpochMs = span.endEpochNanos / 1_000_000,
                durationMs = (span.endEpochNanos - span.startEpochNanos) / 1_000_000,
                attributes = span.attributes.asMap().entries.associate { (k, v) -> k.key to v.toString() },
                statusCode = span.status.statusCode.name
            ))
        }
        return CompletableResultCode.ofSuccess()
    }
    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
