package dev.stapler.stelekit.performance

import dev.stapler.stelekit.cache.PlatformLock
import dev.stapler.stelekit.cache.withLock
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
 * Thread-safe: [record], [snapshot], [drain], and [clear] synchronize on [lock] so callers
 * on different threads (backgroundIndexDispatcher, DatabaseWriteActor coroutine, UI frame loop)
 * can all call [record] concurrently without corruption or ConcurrentModificationException.
 *
 * On wasmJs (single-threaded) [PlatformLock] is a no-op.
 *
 * When capacity is exceeded the oldest entry is dropped.
 *
 * Set [enabled] to false to make [record] a no-op (for opt-in span capture).
 * Histograms are unaffected by this flag — they are collected separately.
 */
class RingBufferSpanExporter(val capacity: Int = 1000) {
    @kotlin.concurrent.Volatile var enabled: Boolean = false
    private val lock = PlatformLock()
    private val buffer = ArrayDeque<SerializedSpan>(capacity)

    fun record(span: SerializedSpan) {
        if (!enabled) return
        lock.withLock {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(span)
        }
    }

    fun snapshot(): List<SerializedSpan> = lock.withLock { buffer.toList() }

    /** Returns all buffered spans and clears the buffer. */
    fun drain(): List<SerializedSpan> = lock.withLock {
        val all = buffer.toList()
        buffer.clear()
        all
    }

    fun clear() = lock.withLock { buffer.clear() }
}
