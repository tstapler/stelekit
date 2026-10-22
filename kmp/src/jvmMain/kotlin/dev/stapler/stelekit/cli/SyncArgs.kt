package dev.stapler.stelekit.cli

import dev.stapler.stelekit.model.EffectiveNotesPath

data class SyncArgs(
    /** The folder holding `pages/` and `journals/`, as `--graph` names it. */
    val graphPath: EffectiveNotesPath? = null,
    val commitOnly: Boolean = false,
    val fetchOnly: Boolean = false,
    val dryRun: Boolean = false,
    val jsonOutput: Boolean = false,
)
