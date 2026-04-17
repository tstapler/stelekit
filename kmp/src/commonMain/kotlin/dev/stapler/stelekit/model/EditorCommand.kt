package dev.stapler.stelekit.model

/**
 * Represents an editor command with ID and arguments.
 */
data class EditorCommand(
    val id: String,
    val args: Map<String, Any> = emptyMap()
)