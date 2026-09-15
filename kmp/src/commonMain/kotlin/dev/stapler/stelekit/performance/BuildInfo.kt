package dev.stapler.stelekit.performance

/**
 * Build-time metadata set once at process startup by the platform entry point.
 *
 * Pattern mirrors [DebugBuildConfig]: a single write at app init, then read-only.
 * Defaults keep the common module safe on platforms that don't set it.
 */
object BuildInfo {
    @kotlin.concurrent.Volatile
    var commitHash: String = "unknown"

    @kotlin.concurrent.Volatile
    var appVersion: String = ""
}
