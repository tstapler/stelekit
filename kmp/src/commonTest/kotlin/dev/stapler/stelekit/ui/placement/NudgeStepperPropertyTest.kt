package dev.stapler.stelekit.ui.placement

import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class IntPoint(val x: Int, val y: Int)

private class IntSpace(private val half: Int, override val canvasWidthPx: Int = 800) : PlacementSpace<IntPoint> {
    override fun center() = IntPoint(0, 0)
    override fun contains(point: IntPoint) = point.x in -half..half && point.y in -half..half
    override fun clamp(point: IntPoint) = IntPoint(point.x.coerceIn(-half, half), point.y.coerceIn(-half, half))
    override fun shift(point: IntPoint, offset: PxOffset) = IntPoint(point.x + offset.dx, point.y + offset.dy)
}

class NudgeStepperPropertyTest {

    @Test
    fun stepSizesAreOnePixelTenPixelsAndOnePercentOfTheCanvas() {
        assertEquals(1, NudgeStepper.stepPx(NudgeStep.ONE_PIXEL, 800))
        assertEquals(10, NudgeStepper.stepPx(NudgeStep.TEN_PIXELS, 800))
        assertEquals(8, NudgeStepper.stepPx(NudgeStep.ONE_PERCENT, 800))
        assertEquals(1, NudgeStepper.stepPx(NudgeStep.ONE_PERCENT, 40))
        assertEquals(PxOffset(-8, 0), NudgeStepper.offset(NudgeStep.ONE_PERCENT, NudgeDirection.LEFT, 800))
        assertEquals(PxOffset(0, 10), NudgeStepper.offset(NudgeStep.TEN_PIXELS, NudgeDirection.DOWN, 800))
    }

    @Test
    fun everyStepMovesAtLeastOnePixelForAnyCanvasWidth() = runTest {
        checkAll(Arb.int(0..100_000), Arb.enum<NudgeStep>(), Arb.enum<NudgeDirection>()) { width, step, direction ->
            val offset = NudgeStepper.offset(step, direction, width)
            assertTrue(kotlin.math.abs(offset.dx) + kotlin.math.abs(offset.dy) >= 1)
        }
    }

    @Test
    fun anyNudgeSequenceStaysInsideTheBounds() = runTest {
        val space = IntSpace(half = 60)
        checkAll(Arb.list(Arb.enum<NudgeDirection>(), 0..80), Arb.list(Arb.enum<NudgeStep>(), 80..80)) { dirs, steps ->
            var c = PlacementController(space).begin()
            dirs.forEachIndexed { i, d -> c = c.nudge(d, steps[i]) }
            assertTrue(space.contains(c.state.crosshairPoint!!))
        }
    }

    @Test
    fun oppositeNudgesInReverseOrderRestoreThePoint() = runTest {
        val space = IntSpace(half = 1_000_000)
        checkAll(Arb.list(Arb.enum<NudgeDirection>(), 0..60), Arb.list(Arb.enum<NudgeStep>(), 60..60)) { dirs, steps ->
            val start = PlacementController(space).begin()
            var c = start
            dirs.forEachIndexed { i, d -> c = c.nudge(d, steps[i]) }
            dirs.indices.reversed().forEach { i -> c = c.nudge(dirs[i].opposite, steps[i]) }
            assertEquals(start.state, c.state)
        }
    }
}
