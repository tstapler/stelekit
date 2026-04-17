package dev.stapler.stelekit.logging

import android.util.Log

/**
 * Android log sink that writes to logcat using the standard Android Log API.
 * Logs are automatically available via `adb logcat` with tag-based filtering.
 */
class AndroidLogSink : LogSink {
    override fun write(entry: LogEntry, formatted: String) {
        val tag = "SteleKit.${entry.tag}"
        when (entry.level) {
            LogLevel.DEBUG -> Log.d(tag, entry.message, entry.throwable)
            LogLevel.INFO -> Log.i(tag, entry.message, entry.throwable)
            LogLevel.WARN -> Log.w(tag, entry.message, entry.throwable)
            LogLevel.ERROR -> Log.e(tag, entry.message, entry.throwable)
        }
    }
}
