package dev.stapler.stelekit.merge

/**
 * Which graph is the open one in a copy. `Push`: source is the active graph, the user picks a
 * destination (Android/Desktop). `Pull`: destination is the active graph, the user picks a source
 * to read read-only (iOS/Web, where an inactive graph cannot be written).
 */
enum class CopyDirection { Push, Pull }

/** Directions the copy flow exposes on [platform] in v1: iOS/Web can only pull, Android/Desktop only push. */
fun offeredDirections(platform: SourcePlatform): List<CopyDirection> = when (platform) {
    SourcePlatform.Ios, SourcePlatform.Web -> listOf(CopyDirection.Pull)
    SourcePlatform.Desktop, SourcePlatform.Android -> listOf(CopyDirection.Push)
}
