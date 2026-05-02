package dev.stapler.stelekit.error

import dev.stapler.stelekit.logging.LogManager
import kotlinx.coroutines.CancellationException
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogLevel
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * JVM-specific implementation of ErrorTracker that logs to a local file and stdout.
 */
class JvmErrorTracker(private val logDir: String = "logs") : ErrorTracker {
    private val breadcrumbs = mutableListOf<String>()
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    
    init {
        val dir = File(logDir)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        
        // Hook into JVM uncaught exceptions
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            trackError(throwable, "Uncaught exception in thread ${thread.name}", mapOf("thread" to thread.name))
            flush()
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun trackError(throwable: Throwable, message: String?, metadata: Map<String, String>) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()
        
        val report = buildString {
            appendLine("========================================")
            appendLine("ERROR REPORT - ${dateFormatter.format(Date())}")
            if (message != null) appendLine("Message: $message")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
            if (metadata.isNotEmpty()) {
                appendLine("Metadata:")
                metadata.forEach { (k, v) -> appendLine("  $k: $v") }
            }
            appendLine("Breadcrumbs:")
            breadcrumbs.forEach { appendLine("  - $it") }
            appendLine("Stack Trace:")
            appendLine(stackTrace)
            appendLine("========================================")
        }
        
        // Log to manager
        LogManager.addLog(LogEntry(
            level = LogLevel.ERROR,
            tag = "ErrorTracker",
            message = message ?: throwable.message ?: "Unknown error",
            throwable = throwable
        ))
        
        // Write to file
        try {
            val fileName = "error-${SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())}.log"
            File(logDir, fileName).writeText(report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Failed to write error report to file: ${e.message}")
        }
    }

    override fun recordBreadcrumb(message: String, category: String?, metadata: Map<String, String>) {
        val breadcrumb = "[${dateFormatter.format(Date())}] ${category ?: "INFO"}: $message"
        breadcrumbs.add(breadcrumb)
        if (breadcrumbs.size > 100) {
            breadcrumbs.removeAt(0)
        }
    }

    override fun flush() {
        // Local file writes are already synchronous in this implementation
    }
}
