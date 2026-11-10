package dev.stapler.stelekit.ui.placement

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NudgeStepperPropertyTest {
    private val width = 640L
    private val height = 480L
    private val space = PixelCanvasSpace(width, height)
    private val stepper = NudgeStepper(space)

    private val arbPoint = Arb.bind(Arb.long(0, width), Arb.long(0, height)) { x, y -> PixelPoint(x, y) }
    private val arbMove = Arb.pair(Arb.element(NudgeDirection.entries), Arb.element(NudgeStep.entries))
    private val arbMoves = Arb.list(arbMove, 0..40)

    @Test
    fun nudge_should_StayInsideBoundsForAnySequence() = runTest {
        checkAll(arbPoint, arbMoves) { start, moves ->
            var p = start
            for ((d, s) in moves) {
                p = stepper.nudge(p, d, s)
                assertTrue(space.contains(p), "left the canvas: $p")
            }
        }
    }

    @Test
    fun nudge_should_BeReversedByTheOppositeNudgesWhenNothingClamped() = runTest {
        checkAll(arbPoint, arbMoves) { start, moves ->
            var p = start
            var clamped = false
            for ((d, s) in moves) {
                val next = stepper.nudge(p, d, s)
                if (next != space.offset(p, d, s)) clamped = true
                p = next
            }
            if (clamped) return@checkAll
            for ((d, s) in moves.asReversed()) p = stepper.nudge(p, d.opposite, s)
            assertEquals(start, p)
        }
    }

    @Test
    fun nudge_should_ReverseExactlyFromTheCentreForAnyShortSequence() = runTest {
        val centre = PixelPoint(width / 2, height / 2)
        checkAll(Arb.list(arbMove, 0..20)) { moves ->
            var p = centre
            for ((d, s) in moves) p = stepper.nudge(p, d, s)
            for ((d, s) in moves.asReversed()) p = stepper.nudge(p, d.opposite, s)
            assertEquals(centre, p, "20 steps of at most 1 percent plus 10 px cannot reach an edge from the centre")
        }
    }

    @Test
    fun step_should_CycleThroughEverySizeAndBack() {
        assertEquals(
            listOf(NudgeStep.TEN_PIXELS, NudgeStep.ONE_PERCENT, NudgeStep.ONE_PIXEL),
            generateSequence(NudgeStep.ONE_PIXEL) { it.next() }.drop(1).take(3).toList(),
        )
    }

    @Test
    fun opposite_should_BeAnInvolution() {
        for (d in NudgeDirection.entries) {
            assertEquals(d, d.opposite.opposite)
            assertEquals(0, d.dx + d.opposite.dx)
            assertEquals(0, d.dy + d.opposite.dy)
        }
    }
}
