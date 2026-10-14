package dev.stapler.stelekit.logging

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidFileLogSinkTest {
    private lateinit var dir: File

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("logsink").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively(); LogManager.clearLogs() }

    private fun entry(level: LogLevel, msg: String, t: Throwable? = null) = LogEntry(level, "T", msg, throwable = t)

    @Test fun `lines are on disk without close or flush so a hard kill keeps them`() {
        val sink = AndroidFileLogSink(dir)
        sink.write(entry(LogLevel.INFO, "x"), "loading graph")
        assertTrue(File(dir, AndroidFileLogSink.CURRENT).readText().contains("loading graph"))
    }

    @Test fun `next session rotates current to previous`() {
        AndroidFileLogSink(dir).write(entry(LogLevel.INFO, "x"), "a".repeat(3_000))
        AndroidFileLogSink(dir)
        assertTrue(File(dir, AndroidFileLogSink.PREVIOUS).readText().startsWith("aaa"))
    }

    @Test fun `a near-empty crash-loop session does not overwrite the informative one`() {
        AndroidFileLogSink(dir).write(entry(LogLevel.INFO, "x"), "important ".repeat(500))
        AndroidFileLogSink(dir).write(entry(LogLevel.INFO, "x"), "boot")
        AndroidFileLogSink(dir)
        assertTrue(File(dir, AndroidFileLogSink.PREVIOUS).readText().contains("important"))
    }

    @Test fun `over budget keeps only warnings and errors`() {
        val sink = AndroidFileLogSink(dir, maxBytes = 10)
        sink.write(entry(LogLevel.INFO, "x"), "0123456789ABCDEF")
        sink.write(entry(LogLevel.INFO, "x"), "dropped-info")
        sink.write(entry(LogLevel.ERROR, "x"), "kept-error")
        val text = File(dir, AndroidFileLogSink.CURRENT).readText()
        assertFalse(text.contains("dropped-info"))
        assertTrue(text.contains("kept-error"))
    }

    @Test fun `crash recorder writes the trace and still delegates to the previous handler`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var delegated: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, t -> delegated = t }
        try {
            AndroidCrashRecorder.install(dir) { "mem-note" }
            val boom = IllegalStateException("boom")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

            val crash = File(dir, AndroidFileLogSink.LAST_CRASH).readText()
            assertTrue(crash.contains("IllegalStateException: boom"))
            assertTrue(crash.contains("mem-note"))
            assertEquals(boom, delegated)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test fun `replay surfaces the crash and previous session tail in the log buffer`() {
        File(dir, AndroidFileLogSink.LAST_CRASH).writeText("OutOfMemoryError")
        File(dir, AndroidFileLogSink.PREVIOUS).writeText("line one\nline two\n")
        LogManager.clearLogs()

        PreviousSessionLog.replay(dir, listOf("Previous process exit: LOW_MEMORY"))

        val messages = LogManager.logs.value.map { it.message }
        assertTrue(messages.any { it.contains("OutOfMemoryError") })
        assertTrue(messages.any { it == "line two" })
        assertTrue(messages.any { it.contains("LOW_MEMORY") })
        assertFalse(File(dir, AndroidFileLogSink.LAST_CRASH).exists(), "crash file is consumed once shown")
    }
}
