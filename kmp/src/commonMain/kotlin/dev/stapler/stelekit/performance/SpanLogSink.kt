package dev.stapler.stelekit.performance

import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogLevel
import dev.stapler.stelekit.logging.LogSink

/**
 * A [LogSink] that converts ERROR-level log entries into ERROR spans.
 * Registered with [dev.stapler.stelekit.logging.LogManager] at graph startup
 * so error logs appear in the Spans tab's error filter automatically.
 *
 * Only ERROR level is bridged; DEBUG/INFO/WARN would be too noisy.
 */
class SpanLogSink(private val spanEmitter: SpanEmitter) : LogSink {
    override fun write(entry: LogEntry, formatted: String) {
        if (entry.level != LogLevel.ERROR) return
        val nowMs = entry.timestamp.toEpochMilliseconds()
        spanEmitter.emit(
            name = "log.error",
            startMs = nowMs,
            endMs = nowMs,
            statusCode = "ERROR",
            attrs = buildMap {
                put("log.tag", entry.tag)
                put("log.message", entry.message.take(400))
                entry.throwable?.let {
                    put("error.type", it::class.simpleName ?: "Unknown")
                    it.message?.let { msg -> put("error.message", msg.take(200)) }
                }
            }
        )
    }
}
