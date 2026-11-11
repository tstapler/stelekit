package dev.stapler.stelekit.ui.placement

/** Where a point lives, in the consumer's coordinates (normalised image points, sheet inches, ...). */
interface PlacementSpace<P> {
    val canvasWidthPx: Int

    fun center(): P

    fun contains(point: P): Boolean

    /** Nearest point inside the canvas bounds. */
    fun clamp(point: P): P

    /** [point] moved by [offset] canvas pixels, not yet clamped. */
    fun shift(point: P, offset: PxOffset): P
}

sealed interface PlacementState<out P> {
    data object Idle : PlacementState<Nothing>

    data class Crosshair<P>(val point: P) : PlacementState<P>

    data class Placed<P>(val point: P) : PlacementState<P>
}

/** The point the crosshair currently marks, or null while idle. */
val <P> PlacementState<P>.crosshairPoint: P?
    get() = when (this) {
        PlacementState.Idle -> null
        is PlacementState.Crosshair -> point
        is PlacementState.Placed -> point
    }

/**
 * Non-drag placement: a crosshair moved by taps and nudge buttons that places only on [confirm].
 * Immutable; every operation returns the next controller, and the point is always clamped to the
 * [space] bounds. A tap or a release on the canvas only moves the crosshair; a release off the
 * canvas cancels.
 */
class PlacementController<P>(val space: PlacementSpace<P>, val state: PlacementState<P> = PlacementState.Idle) {
    private fun next(state: PlacementState<P>) = PlacementController(space, state)

    /** Shows the crosshair at [at] (clamped), or at the canvas center. */
    fun begin(at: P? = null): PlacementController<P> = next(PlacementState.Crosshair(space.clamp(at ?: space.center())))

    fun moveTo(point: P): PlacementController<P> = if (state.crosshairPoint == null) this else begin(point)

    fun nudge(direction: NudgeDirection, step: NudgeStep): PlacementController<P> {
        val current = state.crosshairPoint ?: return this
        return begin(space.shift(current, NudgeStepper.offset(step, direction, space.canvasWidthPx)))
    }

    fun release(point: P, onCanvas: Boolean): PlacementController<P> = if (onCanvas) moveTo(point) else cancel()

    fun confirm(): PlacementController<P> = (state as? PlacementState.Crosshair)?.let { next(PlacementState.Placed(it.point)) } ?: this

    fun cancel(): PlacementController<P> = next(PlacementState.Idle)

    /** The same state against a new [space] (the view moved or resized), re-clamped. */
    fun withSpace(newSpace: PlacementSpace<P>): PlacementController<P> {
        val point = state.crosshairPoint ?: return PlacementController(newSpace, state)
        val moved = PlacementController(newSpace, PlacementState.Crosshair(point)).begin(point).state
        return PlacementController(newSpace, if (state is PlacementState.Placed) PlacementState.Placed(moved.crosshairPoint ?: point) else moved)
    }
}
