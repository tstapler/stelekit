package dev.stapler.stelekit.performance

import kotlinx.serialization.Serializable

@Serializable
data class SerializedSpan(
    val name: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val durationMs: Long,
    val attributes: Map<String, String> = emptyMap(),
    val statusCode: String = "OK",  // "OK", "ERROR", "UNSET"
    val traceId: String = "",
    val spanId: String = "",
    val parentSpanId: String = ""
)

/**
 * Fixed-capacity circular buffer for [SerializedSpan] entries.
 *
 * Not thread-safe. All calls to [record], [snapshot], and [clear] must be made from a
 * single thread or coroutine context. On wasmJs this is always true (single-threaded).
 * On JVM, dispatch to a dedicated coroutine dispatcher before calling.
 *
 * When capacity is exceeded, the oldest entry is dropped.
 */
class RingBufferSpanExporter(val capacity: Int = 1000) {
    private val buffer = ArrayDeque<SerializedSpan>(capacity)

    fun record(span: SerializedSpan) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(span)
    }

    fun snapshot(): List<SerializedSpan> = buffer.toList()

    /** Returns all buffered spans and clears the buffer. */
    fun drain(): List<SerializedSpan> {
        val all = buffer.toList()
        buffer.clear()
        return all
    }

    fun clear() = buffer.clear()
}
