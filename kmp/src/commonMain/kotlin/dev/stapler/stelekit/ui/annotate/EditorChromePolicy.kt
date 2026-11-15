package dev.stapler.stelekit.ui.annotate

/** The six strip levels of ux.md 3.5, highest priority first. */
enum class StripPriority {
    ERROR, TOOL_PROMPT, FIRST_TIME_HINT, STATUS_TEXT, VOICE_HINT, IDLE;

    companion object {
        /** The single winning message: the first level whose condition holds; IDLE is the fallback. */
        fun resolve(
            hasError: Boolean,
            hasToolPrompt: Boolean,
            hasFirstTimeHint: Boolean,
            hasStatusText: Boolean,
            hasVoiceHint: Boolean,
        ): StripPriority = when {
            hasError -> ERROR
            hasToolPrompt -> TOOL_PROMPT
            hasFirstTimeHint -> FIRST_TIME_HINT
            hasStatusText -> STATUS_TEXT
            hasVoiceHint -> VOICE_HINT
            else -> IDLE
        }
    }
}

enum class BackAction { DismissModal, CancelNudge, DisarmSilently, ConfirmDiscardThenDisarm, ResolveLeave }

/** Back order of ux.md 3.5: the first match consumes the press. */
object EditorBackPolicy {
    /** Unfinished multi-step points at which disarming asks "Discard N unfinished points?". */
    const val CONFIRM_DISCARD_MIN_POINTS: Int = 2

    fun resolve(
        modalOpen: Boolean,
        nudgeOpen: Boolean,
        toolArmed: Boolean,
        unfinishedPoints: Int,
    ): BackAction = when {
        modalOpen -> BackAction.DismissModal
        nudgeOpen -> BackAction.CancelNudge
        toolArmed && unfinishedPoints >= CONFIRM_DISCARD_MIN_POINTS -> BackAction.ConfirmDiscardThenDisarm
        toolArmed -> BackAction.DisarmSilently
        else -> BackAction.ResolveLeave
    }
}
