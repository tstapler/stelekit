package dev.stapler.stelekit.logging

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * JVM file-based log sink. Writes structured logs to daily rotating files
 * at `~/.logseq/logs/stelekit-YYYY-MM-DD.log`.
 *
 * Features:
 * - Daily rotation: new file per day
 * - Auto-cleanup: removes log files older than [retentionDays]
 * - Buffered writes with periodic flush
 * - Stack traces written inline for exceptions
 * - Thread-safe via synchronized writes
 */
class FileLogSink(
    private val logDir: File = defaultLogDir(),
    private val retentionDays: Int = 7
) : LogSink {

    private var currentDate: LocalDate = LocalDate.now()
    private var writer: BufferedWriter? = null

    init {
        logDir.mkdirs()
        openWriter()
        cleanOldLogs()
    }

    @Synchronized
    override fun write(entry: LogEntry, formatted: String) {
        // Rotate if day changed
        val today = LocalDate.now()
        if (today != currentDate) {
            rotateLog(today)
        }

        writer?.let { w ->
            w.write(formatted)
            w.newLine()
            entry.throwable?.let { t ->
                w.write(t.stackTraceToString())
                w.newLine()
            }
            // Flush on WARN/ERROR immediately; buffer DEBUG/INFO
            if (entry.level >= LogLevel.WARN) {
                w.flush()
            }
        }
    }

    @Synchronized
    override fun flush() {
        writer?.flush()
    }

    @Synchronized
    override fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    private fun openWriter() {
        val file = logFileForDate(currentDate)
        writer = BufferedWriter(FileWriter(file, /* append */ true), 8192)
    }

    private fun rotateLog(newDate: LocalDate) {
        close()
        currentDate = newDate
        openWriter()
        cleanOldLogs()
    }

    private fun logFileForDate(date: LocalDate): File {
        val name = "stelekit-${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}.log"
        return File(logDir, name)
    }

    private fun cleanOldLogs() {
        val cutoff = LocalDate.now().minusDays(retentionDays.toLong())
        val prefix = "stelekit-"
        logDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix) && file.name.endsWith(".log")) {
                try {
                    val dateStr = file.name.removePrefix(prefix).removeSuffix(".log")
                    val fileDate = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
                    if (fileDate.isBefore(cutoff)) {
                        file.delete()
                    }
                } catch (_: Exception) {
                    // Skip files with unparseable names
                }
            }
        }
    }

    companion object {
        fun defaultLogDir(): File {
            val userHome = System.getProperty("user.home")
            return File(userHome, ".stelekit/logs")
        }

        /**
         * Returns the path to today's log file (for display in UI or CLI).
         */
        fun currentLogPath(): String {
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            return File(defaultLogDir(), "stelekit-$today.log").absolutePath
        }
    }
}
