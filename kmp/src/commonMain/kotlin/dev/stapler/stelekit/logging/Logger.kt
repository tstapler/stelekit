package dev.stapler.stelekit.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.Instant

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val timestamp: Instant = Clock.System.now(),
    val throwable: Throwable? = null
)

/**
 * Platform-specific log output destination.
 * Implementations write logs to files, logcat, os_log, etc.
 */
interface LogSink {
    fun write(entry: LogEntry, formatted: String)
    fun flush() {}
    fun close() {}
}

object LogManager {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private const val MAX_LOGS = 1000
    private val sinks = mutableListOf<LogSink>()

    /** Minimum level for in-memory log buffer and sinks. */
    var minLevel: LogLevel = LogLevel.DEBUG

    fun addSink(sink: LogSink) {
        sinks.add(sink)
    }

    fun removeSink(sink: LogSink) {
        sinks.remove(sink)
        sink.close()
    }

    fun addLog(entry: LogEntry) {
        if (entry.level < minLevel) return

        // In-memory buffer for the Logs screen
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(0, entry)
        if (currentLogs.size > MAX_LOGS) {
            currentLogs.removeAt(currentLogs.lastIndex)
        }
        _logs.value = currentLogs

        // Format once, send to all sinks
        val formatted = formatLogEntry(entry)

        // Always print to stdout
        println(formatted)
        entry.throwable?.printStackTrace()

        // Write to registered sinks
        for (sink in sinks) {
            try {
                sink.write(entry, formatted)
            } catch (_: Exception) {
                // Don't let a broken sink crash the app
            }
        }
    }

    fun flush() {
        for (sink in sinks) {
            try { sink.flush() } catch (_: Exception) {}
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun formatLogEntry(entry: LogEntry): String {
        val ts = entry.timestamp.toString().substringAfter("T").substringBefore("Z").take(12)
        val throwableStr = entry.throwable?.let { " | ${it::class.simpleName}: ${it.message}" } ?: ""
        return "$ts [${entry.level}] ${entry.tag}: ${entry.message}$throwableStr"
    }
}

class Logger(private val tag: String) {
    fun debug(message: String) = log(LogLevel.DEBUG, message)
    fun info(message: String) = log(LogLevel.INFO, message)
    fun warn(message: String, throwable: Throwable? = null) = log(LogLevel.WARN, message, throwable)
    fun error(message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, message, throwable)

    private fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        LogManager.addLog(LogEntry(level, tag, message, throwable = throwable))
    }
}
