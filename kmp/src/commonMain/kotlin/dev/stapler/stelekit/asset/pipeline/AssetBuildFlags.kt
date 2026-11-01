package dev.stapler.stelekit.asset.pipeline

/**
 * Build-variant gate for the "Asset indexing" switch (`asset_pipeline_enabled`).
 *
 * Release builds hide the switch and never run the index backfill until the heap gate (Task 5.2c)
 * has a recorded pass and the one-line flip PR (Task 5.2e) sets [RELEASE_TOGGLE_VISIBLE] to true.
 * Debug/internal builds show it: the Android application sets [internalBuild] from
 * `FLAG_DEBUGGABLE` before any UI is composed.
 */
object AssetBuildFlags {
    /** Flipped to true only by Task 5.2e, citing the recorded Task 5.2c heap-gate runs. */
    const val RELEASE_TOGGLE_VISIBLE: Boolean = false

    @kotlin.concurrent.Volatile
    var internalBuild: Boolean = false

    val PIPELINE_TOGGLE_VISIBLE: Boolean
        get() = RELEASE_TOGGLE_VISIBLE || internalBuild

    const val PIPELINE_ENABLED_KEY: String = "asset_pipeline_enabled"
}
