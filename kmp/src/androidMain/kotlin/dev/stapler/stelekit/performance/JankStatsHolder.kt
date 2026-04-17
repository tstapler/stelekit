package dev.stapler.stelekit.performance

/**
 * Process-scoped singleton that holds the active [JankStatsManager] instance.
 *
 * Set by the Activity (or ComposeHost) after JankStatsManager is created so that
 * the [PlatformFrameTimeOverlay] composable can read frame metrics without requiring
 * the manager to be threaded through the entire composable tree.
 */
object JankStatsHolder {
    @Volatile
    var instance: JankStatsManager? = null
}
