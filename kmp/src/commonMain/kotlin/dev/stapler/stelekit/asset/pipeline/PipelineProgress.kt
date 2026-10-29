package dev.stapler.stelekit.asset.pipeline

/** Why a drain stopped before finishing. Typed so a UI never matches on strings. */
sealed interface PauseReason {
    data object Memory : PauseReason
    data object SessionCap : PauseReason
    data object DailyCap : PauseReason
    data object Network : PauseReason
    data object Charger : PauseReason
    data object User : PauseReason
}

/** Observable state of the plugin drain. [Running.done] counts assets that reached a terminal state (processed or failed). */
sealed interface PipelineProgress {
    data object Idle : PipelineProgress
    data class Running(val done: Int, val total: Int, val failed: Int) : PipelineProgress
    data class Paused(val reason: PauseReason) : PipelineProgress
    data class Finished(val done: Int, val failed: Int) : PipelineProgress
}
