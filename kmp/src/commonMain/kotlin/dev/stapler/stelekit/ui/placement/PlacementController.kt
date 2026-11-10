package dev.stapler.stelekit.ui.placement

sealed interface PlacementState<out P> {
    data object Idle : PlacementState<Nothing>

    /**
     * A candidate position is showing. [offCanvas] is true while a pointer drag is outside the canvas: [point]
     * stays clamped to the edge so the crosshair remains visible, and releasing there cancels.
     */
    data class Crosshair<P>(val point: P, val offCanvas: Boolean = false) : PlacementState<P>

    data class Placed<P>(val point: P) : PlacementState<P>
}

/**
 * Non-drag placement: show a crosshair, nudge it with the keyboard or buttons, then confirm. A pointer drag
 * feeds the same states through [moveTo] and [release], so one controller serves both input styles.
 *
 * Pure and Compose-free; coordinates are opaque (see [PlacementSpace]). Not thread-safe: call from the one
 * thread that owns the editor state.
 */
class PlacementController<P>(
    private val space: PlacementSpace<P>,
    initialStep: NudgeStep = NudgeStep.TEN_PIXELS,
) {
    private val stepper = NudgeStepper(space)

    var state: PlacementState<P> = PlacementState.Idle
        private set

    var step: NudgeStep = initialStep
        private set

    /** Shows the crosshair at [at], clamped to the canvas. Restarts from any state. */
    fun begin(at: P) {
        state = PlacementState.Crosshair(space.clamp(at), offCanvas = !space.contains(at))
    }

    /** Follows a pointer to [to]; ignored unless a crosshair is showing. */
    fun moveTo(to: P) {
        if (state !is PlacementState.Crosshair) return
        begin(to)
    }

    /** Moves the crosshair by one [step]; ignored unless a crosshair is showing. */
    fun nudge(direction: NudgeDirection) {
        val current = state as? PlacementState.Crosshair ?: return
        state = PlacementState.Crosshair(stepper.nudge(current.point, direction, step))
    }

    fun selectStep(next: NudgeStep) {
        step = next
    }

    fun cycleStep() {
        step = step.next()
    }

    /** Places at the crosshair, or cancels when the pointer left the canvas. Returns whether a point was placed. */
    fun release(): Boolean {
        val current = state as? PlacementState.Crosshair ?: return false
        if (current.offCanvas) {
            state = PlacementState.Idle
            return false
        }
        state = PlacementState.Placed(current.point)
        return true
    }

    /** Non-drag confirmation; the same rule as [release]. */
    fun confirm(): Boolean = release()

    fun cancel() {
        state = PlacementState.Idle
    }

    /** The placed point, once, then back to Idle. Null unless [state] is Placed. */
    fun take(): P? {
        val placed = state as? PlacementState.Placed ?: return null
        state = PlacementState.Idle
        return placed.point
    }
}
