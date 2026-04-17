package dev.stapler.stelekit.performance

/**
 * Runtime flag indicating whether the app is running in a debug / development build.
 *
 * The app entry point (ComposeHost on Android, Main.kt on desktop) must set this
 * before any UI is rendered. It defaults to false so release builds are safe even
 * if the caller forgets to set it.
 */
object DebugBuildConfig {
    @Volatile
    var isDebugBuild: Boolean = false
}
