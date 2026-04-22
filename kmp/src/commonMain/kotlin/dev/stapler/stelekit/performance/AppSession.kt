package dev.stapler.stelekit.performance

import dev.stapler.stelekit.util.UuidGenerator

/**
 * Stable identifiers for the current app process lifetime.
 * Generated once per process; never persisted across restarts.
 */
object AppSession {
    val id: String = UuidGenerator.generateV7()
    val startEpochMs: Long = HistogramWriter.epochMs()
}
