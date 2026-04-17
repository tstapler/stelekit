package dev.stapler.stelekit.performance

import kotlinx.serialization.Serializable

@Serializable
data class SerializedSpan(
    val name: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val durationMs: Long,
    val attributes: Map<String, String> = emptyMap(),
    val statusCode: String = "OK"  // "OK", "ERROR", "UNSET"
)

/**
 * Fixed-capacity circular buffer for [SerializedSpan] entries.
 *
 * Thread-safe via [@Synchronized]. When capacity is exceeded, the oldest entry is dropped.
 * Call [record] to add entries — safe to call from any thread, including the main thread
 * (e.g. from JankStats' OnFrameListener).
 * Call [snapshot] to get a stable copy for bug report assembly.
 */
class RingBufferSpanExporter(val capacity: Int = 1000) {
    private val buffer = ArrayDeque<SerializedSpan>(capacity)

    @Synchronized
    fun record(span: SerializedSpan) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(span)
    }

    @Synchronized
    fun snapshot(): List<SerializedSpan> = buffer.toList()

    @Synchronized
    fun clear() = buffer.clear()
}
