package dev.stapler.stelekit.ui.placement

enum class NudgeDirection(val dx: Int, val dy: Int) {
    LEFT(-1, 0),
    RIGHT(1, 0),
    UP(0, -1),
    DOWN(0, 1),
    ;

    /** The direction that undoes this one. */
    val opposite: NudgeDirection
        get() = when (this) {
            LEFT -> RIGHT
            RIGHT -> LEFT
            UP -> DOWN
            DOWN -> UP
        }
}

/** Distance of one nudge: 1 px, 10 px or 1 percent of the canvas extent along the nudge axis. */
enum class NudgeStep {
    ONE_PIXEL,
    TEN_PIXELS,
    ONE_PERCENT,
    ;

    /** The next step in the selector cycle (small to large, then back). */
    fun next(): NudgeStep = entries[(ordinal + 1) % entries.size]
}

/**
 * The geometry of one canvas, in whatever coordinate type its consumer uses (normalised fractions, exact
 * `Inches`). The controller never does arithmetic on [P]; everything numeric lives here, so the same
 * controller serves every coordinate space.
 */
interface PlacementSpace<P> {
    fun contains(point: P): Boolean

    /** The nearest point to [point] that [contains] accepts. */
    fun clamp(point: P): P

    /** [point] moved one [step] along [direction], not clamped. */
    fun offset(point: P, direction: NudgeDirection, step: NudgeStep): P
}

/** Moves a point by whole [NudgeStep]s and keeps it on the canvas. */
class NudgeStepper<P>(private val space: PlacementSpace<P>) {
    fun nudge(point: P, direction: NudgeDirection, step: NudgeStep): P =
        space.clamp(space.offset(point, direction, step))
}
