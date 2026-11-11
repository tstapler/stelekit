package dev.stapler.stelekit.ui.placement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Behaviour every [PlacementController] consumer must show; subclasses supply a real [PlacementSpace]. */
abstract class PlacementControllerContractSuite<P> {
    abstract fun space(): PlacementSpace<P>

    /** A point well inside the bounds and not the center. */
    abstract fun interiorPoint(): P

    abstract fun outsidePoint(): P

    private fun controller() = PlacementController(space())

    private fun PlacementController<P>.point(): P = state.crosshairPoint ?: error("no crosshair: $state")

    @Test
    fun startsIdleAndIgnoresNudgesAndTaps() {
        val c = controller()
        assertEquals(PlacementState.Idle, c.state)
        assertEquals(PlacementState.Idle, c.nudge(NudgeDirection.LEFT, NudgeStep.TEN_PIXELS).state)
        assertEquals(PlacementState.Idle, c.moveTo(interiorPoint()).state)
        assertEquals(PlacementState.Idle, c.confirm().state)
    }

    @Test
    fun beginShowsTheCrosshairAtTheCenter() {
        val c = controller().begin()
        assertEquals(space().center(), c.point())
    }

    @Test
    fun everyNudgeMovesTheCrosshair() {
        for (direction in NudgeDirection.entries) for (step in NudgeStep.entries) {
            val start = controller().begin()
            assertNotEquals(start.point(), start.nudge(direction, step).point(), "$direction $step")
        }
    }

    @Test
    fun nudgingForeverStaysInsideTheBounds() {
        for (direction in NudgeDirection.entries) {
            var c = controller().begin()
            repeat(500) { c = c.nudge(direction, NudgeStep.ONE_PERCENT) }
            assertTrue(space().contains(c.point()), "$direction left the canvas")
        }
    }

    @Test
    fun oppositeNudgesReverseTheMove() {
        for (direction in NudgeDirection.entries) for (step in NudgeStep.entries) {
            val start = controller().begin()
            var c = start
            repeat(3) { c = c.nudge(direction, step) }
            repeat(3) { c = c.nudge(direction.opposite, step) }
            assertEquals(start.point(), c.point(), "$direction $step")
        }
    }

    @Test
    fun aTapMovesTheCrosshairAndPlacesNothing() {
        val c = controller().begin().moveTo(interiorPoint())
        assertEquals(PlacementState.Crosshair(interiorPoint()), c.state)
    }

    @Test
    fun aTapOffTheCanvasIsClamped() {
        assertTrue(space().contains(controller().begin().moveTo(outsidePoint()).point()))
    }

    @Test
    fun confirmPlacesAtTheCrosshairAndANudgeResumesAiming() {
        val aimed = controller().begin().moveTo(interiorPoint())
        val placed = aimed.confirm()
        assertEquals(PlacementState.Placed(interiorPoint()), placed.state)
        assertTrue(placed.nudge(NudgeDirection.RIGHT, NudgeStep.TEN_PIXELS).state is PlacementState.Crosshair)
        assertEquals(placed.state, placed.confirm().state)
    }

    @Test
    fun releasingOffTheCanvasCancelsAndOnTheCanvasMoves() {
        val aimed = controller().begin()
        assertEquals(PlacementState.Idle, aimed.release(outsidePoint(), onCanvas = false).state)
        assertEquals(PlacementState.Crosshair(interiorPoint()), aimed.release(interiorPoint(), onCanvas = true).state)
    }

    @Test
    fun cancelReturnsToIdle() {
        assertEquals(PlacementState.Idle, controller().begin().cancel().state)
    }

    @Test
    fun withSpaceKeepsTheStateAndReclampsIt() {
        val moved = controller().begin().moveTo(interiorPoint())
        assertEquals(moved.state, moved.withSpace(space()).state)
        assertEquals(PlacementState.Idle, controller().withSpace(space()).state)
        val placed = moved.confirm()
        assertEquals(placed.state, placed.withSpace(space()).state)
    }
}
