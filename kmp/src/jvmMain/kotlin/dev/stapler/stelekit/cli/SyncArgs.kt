package dev.stapler.stelekit.cli

data class SyncArgs(
    val graphPath: String? = null,
    val commitOnly: Boolean = false,
    val fetchOnly: Boolean = false,
    val dryRun: Boolean = false,
    val jsonOutput: Boolean = false,
)
