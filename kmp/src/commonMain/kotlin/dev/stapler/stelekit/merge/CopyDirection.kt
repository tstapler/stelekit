package dev.stapler.stelekit.merge

/**
 * Which graph is the open one in a copy. `Push`: source is the active graph, the user picks a
 * destination (Android/Desktop). `Pull`: destination is the active graph, the user picks a source
 * to read read-only (iOS/Web, where an inactive graph cannot be written).
 */
enum class CopyDirection { Push, Pull }
