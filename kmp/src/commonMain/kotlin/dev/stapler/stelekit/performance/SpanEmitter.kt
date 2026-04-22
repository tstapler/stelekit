package dev.stapler.stelekit.performance

import dev.stapler.stelekit.util.UuidGenerator

/**
 * Lightweight, non-suspending span recorder for use in non-coroutine contexts
 * (ViewModels, synchronous callbacks, log sinks).
 *
 * Writes to [RingBufferSpanExporter] only; the background drain job in
 * [RepositoryFactory.createRepositorySet] persists spans to SQLite every 5 seconds.
 *
 * Every span automatically carries [AppSession.id] so the export report can
 * distinguish spans from different process lifetimes.
 */
class SpanEmitter(private val ringBuffer: RingBufferSpanExporter?) {

    fun emit(
        name: String,
        startMs: Long,
        endMs: Long = HistogramWriter.epochMs(),
        traceId: String = UuidGenerator.generateV7(),
        parentSpanId: String = "",
        statusCode: String = "OK",
        attrs: Map<String, String> = emptyMap(),
    ) {
        ringBuffer?.record(
            SerializedSpan(
                name = name,
                startEpochMs = startMs,
                endEpochMs = endMs,
                durationMs = endMs - startMs,
                attributes = attrs + ("session.id" to AppSession.id),
                statusCode = statusCode,
                traceId = traceId,
                spanId = UuidGenerator.generateV7(),
                parentSpanId = parentSpanId,
            )
        )
    }

    /** Convenience: emit a root span with [startMs] already captured. */
    fun trace(name: String, startMs: Long, attrs: Map<String, String> = emptyMap()) {
        emit(name, startMs, attrs = attrs)
    }

    /** Convenience: emit a root span with [startMs] already captured and ERROR status. */
    fun error(name: String, startMs: Long, errorMsg: String, attrs: Map<String, String> = emptyMap()) {
        emit(name, startMs, statusCode = "ERROR", attrs = attrs + ("error.message" to errorMsg))
    }

    val isEnabled: Boolean get() = ringBuffer != null
}
