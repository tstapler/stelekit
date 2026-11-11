package dev.stapler.stelekit.ui.placement

import kotlin.math.max

/** The three nudge sizes: one pixel, ten pixels, or one percent of the canvas width. */
enum class NudgeStep { ONE_PIXEL, TEN_PIXELS, ONE_PERCENT }

enum class NudgeDirection(val dx: Int, val dy: Int) {
    LEFT(-1, 0),
    RIGHT(1, 0),
    UP(0, -1),
    DOWN(0, 1),
    ;

    val opposite: NudgeDirection
        get() = when (this) {
            LEFT -> RIGHT
            RIGHT -> LEFT
            UP -> DOWN
            DOWN -> UP
        }
}

/** A canvas-pixel displacement; the consumer's [PlacementSpace] converts it into its own coordinates. */
data class PxOffset(val dx: Int, val dy: Int)

object NudgeStepper {
    private const val TEN = 10
    private const val PERCENT = 100

    /** Pixels moved by one press of [step]; never less than one so a press always visibly moves. */
    fun stepPx(step: NudgeStep, canvasWidthPx: Int): Int = when (step) {
        NudgeStep.ONE_PIXEL -> 1
        NudgeStep.TEN_PIXELS -> TEN
        NudgeStep.ONE_PERCENT -> max(1, canvasWidthPx / PERCENT)
    }

    fun offset(step: NudgeStep, direction: NudgeDirection, canvasWidthPx: Int): PxOffset {
        val px = stepPx(step, canvasWidthPx)
        return PxOffset(direction.dx * px, direction.dy * px)
    }
}
