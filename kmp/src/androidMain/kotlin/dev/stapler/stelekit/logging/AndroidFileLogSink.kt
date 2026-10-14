package dev.stapler.stelekit.logging

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlinx.coroutines.CancellationException

/**
 * Persists logs so they survive a crash and can be read on the next launch from the in-app Logs
 * screen — no adb/logcat needed.
 *
 * Layout in [logDir]: `current.log` (this process) and `previous.log` (the process before it).
 * Every line is flushed immediately because the interesting lines are the last ones before a kill
 * (OOM kill, ANR, native crash) where no shutdown hook runs. [maxBytes] bounds a runaway session:
 * past it only WARN+ is kept.
 */
class AndroidFileLogSink(
    private val logDir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : LogSink {

    private val currentFile = File(logDir, CURRENT)
    private var writer: BufferedWriter? = null
    private var bytesWritten = 0L

    init {
        logDir.mkdirs()
        if (currentFile.exists()) {
            // A near-empty session (crash loop) must not overwrite the informative one before it.
            if (currentFile.length() >= MIN_ROTATE_BYTES) {
                val previous = File(logDir, PREVIOUS)
                previous.delete()
                currentFile.renameTo(previous)
            } else {
                currentFile.delete()
            }
        }
        writer = BufferedWriter(FileWriter(currentFile, true))
    }

    @Synchronized
    override fun write(entry: LogEntry, formatted: String) {
        val w = writer ?: return
        val overBudget = bytesWritten >= maxBytes
        if (overBudget && entry.level < LogLevel.WARN) return
        if (bytesWritten >= maxBytes * 2) return
        try {
            w.write(formatted)
            w.newLine()
            entry.throwable?.let { w.write(it.stackTraceToString()); w.newLine() }
            w.flush()
            bytesWritten += formatted.length + 1
        } catch (e: CancellationException) {
            throw e
        } catch (_: java.io.IOException) {
            // Disk full / removed: logging must never take the app down.
        }
    }

    @Synchronized
    override fun flush() { runCatching { writer?.flush() } }

    @Synchronized
    override fun close() {
        runCatching { writer?.flush(); writer?.close() }
        writer = null
    }

    companion object {
        const val CURRENT = "current.log"
        const val PREVIOUS = "previous.log"
        const val LAST_CRASH = "last-crash.txt"
        private const val DEFAULT_MAX_BYTES = 2_000_000L
        private const val MIN_ROTATE_BYTES = 2_048L
    }
}

/**
 * Records the stack trace of an uncaught exception synchronously (before the process dies), then
 * defers to the previously installed handler so platform crash behaviour is unchanged.
 */
object AndroidCrashRecorder {
    fun install(logDir: File, memoryNote: () -> String = ::runtimeMemoryNote) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                logDir.mkdirs()
                File(logDir, AndroidFileLogSink.LAST_CRASH).writeText(
                    buildString {
                        appendLine("thread=${thread.name}")
                        appendLine("memory=${memoryNote()}")
                        appendLine(throwable.stackTraceToString())
                    }
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun runtimeMemoryNote(): String {
        val r = Runtime.getRuntime()
        val mb = 1024 * 1024
        return "used=${(r.totalMemory() - r.freeMemory()) / mb}MB max=${r.maxMemory() / mb}MB"
    }
}

/** Replays what the previous process left behind into [LogManager]'s in-memory buffer. */
object PreviousSessionLog {
    private const val TAG = "PrevSession"
    private const val TAIL_LINES = 200

    /** Call before registering the file sink so replayed lines are not re-written to disk. */
    fun replay(logDir: File, extraNotes: List<String> = emptyList()) {
        extraNotes.forEach { LogManager.addLog(LogEntry(LogLevel.WARN, TAG, it)) }

        val crash = File(logDir, AndroidFileLogSink.LAST_CRASH)
        if (crash.exists()) {
            LogManager.addLog(LogEntry(LogLevel.ERROR, TAG, "Previous session crashed:\n${crash.readText().take(4000)}"))
            crash.delete()
        }

        val previous = File(logDir, AndroidFileLogSink.PREVIOUS)
        if (previous.exists()) {
            previous.readLines().takeLast(TAIL_LINES).forEach {
                LogManager.addLog(LogEntry(LogLevel.INFO, TAG, it))
            }
        }
    }
}
