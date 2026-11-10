package dev.stapler.stelekit.ui.placement

import dev.stapler.stelekit.units.Inches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What every consumer of [PlacementController] must get, whatever its coordinate type. Story 6.5a runs the
 * annotate editor's space through a subclass of this suite. Named `Suite`, not `Test`, so the completeness
 * guard does not expect the abstract class in `AllBusinessTests` (precedent: `TargetWriterContractSuite`).
 */
abstract class PlacementControllerContractSuite<P> {
    protected abstract fun space(): PlacementSpace<P>
    protected abstract val interior: P
    protected abstract val outside: P
    protected abstract val bottomRightCorner: P

    /** Distance the crosshair moved along one axis, in the space's smallest unit. */
    protected abstract fun travelled(from: P, to: P): Long

    private fun controller() = PlacementController(space())

    @Test
    fun controller_should_StartIdle() {
        assertEquals(PlacementState.Idle, controller().state)
    }

    @Test
    fun begin_should_ShowTheCrosshairAtTheRequestedPoint() {
        val c = controller()
        c.begin(interior)
        assertEquals(PlacementState.Crosshair(interior), c.state)
    }

    @Test
    fun begin_should_ClampAnOffCanvasStartAndFlagIt() {
        val c = controller()
        c.begin(outside)
        val s = assertIs<PlacementState.Crosshair<P>>(c.state)
        assertTrue(s.offCanvas)
        assertTrue(space().contains(s.point))
    }

    @Test
    fun release_should_CancelWhenThePointerLeftTheCanvas() {
        val c = controller()
        c.begin(interior)
        c.moveTo(outside)
        assertFalse(c.release())
        assertEquals(PlacementState.Idle, c.state)
        assertNull(c.take())
    }

    @Test
    fun release_should_PlaceWhenThePointerReturnsInside() {
        val c = controller()
        c.begin(interior)
        c.moveTo(outside)
        c.moveTo(interior)
        assertTrue(c.release())
        assertEquals(PlacementState.Placed(interior), c.state)
    }

    @Test
    fun confirm_should_PlaceTheNudgedCrosshairAndTakeReturnsItOnce() {
        val c = controller()
        c.begin(interior)
        c.nudge(NudgeDirection.RIGHT)
        val moved = (c.state as PlacementState.Crosshair<P>).point
        assertTrue(c.confirm())
        assertEquals(moved, c.take())
        assertNull(c.take())
        assertEquals(PlacementState.Idle, c.state)
    }

    @Test
    fun nudge_should_BeIgnoredWhileIdle() {
        val c = controller()
        c.nudge(NudgeDirection.UP)
        assertEquals(PlacementState.Idle, c.state)
    }

    @Test
    fun moveTo_should_BeIgnoredWhileIdle() {
        val c = controller()
        c.moveTo(interior)
        assertEquals(PlacementState.Idle, c.state)
    }

    @Test
    fun nudge_should_BeReversedByTheOppositeDirection() {
        val c = controller()
        c.begin(interior)
        for (step in NudgeStep.entries) {
            c.selectStep(step)
            c.nudge(NudgeDirection.DOWN)
            c.nudge(NudgeDirection.UP)
            assertEquals(PlacementState.Crosshair(interior), c.state, "step $step")
        }
    }

    @Test
    fun nudge_should_ClampAtTheCanvasEdge() {
        val c = controller()
        c.begin(bottomRightCorner)
        c.nudge(NudgeDirection.RIGHT)
        c.nudge(NudgeDirection.DOWN)
        assertEquals(PlacementState.Crosshair(bottomRightCorner), c.state)
    }

    @Test
    fun nudge_should_BringAnOffCanvasCrosshairBackAsAnOnCanvasOne() {
        val c = controller()
        c.begin(outside)
        c.nudge(NudgeDirection.LEFT)
        assertFalse((c.state as PlacementState.Crosshair<P>).offCanvas)
    }

    @Test
    fun step_should_ScaleTenPixelsToTenTimesOnePixelAndPercentToSomethingPositive() {
        fun moveBy(step: NudgeStep): Long {
            val c = controller()
            c.begin(interior)
            c.selectStep(step)
            c.nudge(NudgeDirection.RIGHT)
            return travelled(interior, (c.state as PlacementState.Crosshair<P>).point)
        }
        assertEquals(10 * moveBy(NudgeStep.ONE_PIXEL), moveBy(NudgeStep.TEN_PIXELS))
        assertTrue(moveBy(NudgeStep.ONE_PERCENT) > 0)
    }

    @Test
    fun cycleStep_should_VisitEveryStepThenWrap() {
        val c = controller()
        val start = c.step
        val seen = List(NudgeStep.entries.size + 1) { c.step.also { c.cycleStep() } }
        assertEquals(NudgeStep.entries.toSet(), seen.toSet())
        assertEquals(start, seen.last())
    }

    @Test
    fun cancel_should_ReturnToIdleFromAnyState() {
        val c = controller()
        c.begin(interior)
        c.cancel()
        assertEquals(PlacementState.Idle, c.state)
        c.begin(interior)
        c.confirm()
        c.cancel()
        assertEquals(PlacementState.Idle, c.state)
    }
}

class InchesPlacementControllerContractTest : PlacementControllerContractSuite<InchesPoint>() {
    private val width = Inches(1920)
    private val height = Inches(1536)

    override fun space() = InchesSheetSpace(width, height)
    override val interior = InchesPoint(Inches(960), Inches(768))
    override val outside = InchesPoint(Inches(-5), Inches(100))
    override val bottomRightCorner = InchesPoint(width, height)
    override fun travelled(from: InchesPoint, to: InchesPoint) = to.x.sixteenths - from.x.sixteenths
}

class PixelPlacementControllerContractTest : PlacementControllerContractSuite<PixelPoint>() {
    override fun space() = PixelCanvasSpace(640, 480)
    override val interior = PixelPoint(320, 240)
    override val outside = PixelPoint(700, 10)
    override val bottomRightCorner = PixelPoint(640, 480)
    override fun travelled(from: PixelPoint, to: PixelPoint) = to.x - from.x
}
