package dev.stapler.stelekit.performance

import dev.stapler.stelekit.util.UuidGenerator

/**
 * Stable identifiers for the current app process lifetime.
 * Generated once per process; never persisted across restarts.
 */
object AppSession {
    val id: String = UuidGenerator.generateV7()
    val startEpochMs: Long = HistogramWriter.epochMs()

    /**
     * Standard attributes added to every span automatically.
     * Includes session.id (always), app.version, and app.commit (when set by the platform
     * entry point via [BuildInfo]).
     */
    fun autoAttributes(): Map<String, String> = buildMap {
        put("session.id", id)
        val ver = BuildInfo.appVersion
        if (ver.isNotEmpty()) put("app.version", ver)
        val commit = BuildInfo.commitHash
        if (commit != "unknown" && commit.isNotEmpty()) put("app.commit", commit)
    }
}
