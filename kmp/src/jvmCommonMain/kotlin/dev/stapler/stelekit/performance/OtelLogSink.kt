package dev.stapler.stelekit.performance

import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogSink
import io.opentelemetry.api.trace.Span

/**
 * A [LogSink] that enriches each log line with the active OTel trace_id and span_id.
 * Wrap around an existing sink (e.g. [FileLogSink]) to correlate logs with APM traces.
 *
 * Mirrors the Datadog Timber integration's `setBundleWithTraceEnabled(true)` behaviour.
 *
 * Usage:
 * ```kotlin
 * LogManager.addSink(OtelLogSink(FileLogSink()))
 * ```
 */
class OtelLogSink(private val delegate: LogSink) : LogSink {
    override fun write(entry: LogEntry, formatted: String) {
        val spanContext = Span.current().spanContext
        val enriched = if (spanContext.isValid) {
            "$formatted [trace=${spanContext.traceId.takeLast(16)} span=${spanContext.spanId}]"
        } else {
            formatted
        }
        delegate.write(entry, enriched)
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
