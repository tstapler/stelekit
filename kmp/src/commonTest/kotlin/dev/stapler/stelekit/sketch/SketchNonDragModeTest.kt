package dev.stapler.stelekit.sketch

import arrow.core.Either
import dev.stapler.stelekit.ui.placement.NudgeDirection
import dev.stapler.stelekit.ui.placement.NudgeStep
import dev.stapler.stelekit.ui.placement.PlacementState
import dev.stapler.stelekit.ui.placement.crosshairPoint
import dev.stapler.stelekit.ui.sketch.PlacementCommit
import dev.stapler.stelekit.ui.sketch.SketchPlacementSession
import dev.stapler.stelekit.ui.sketch.SketchPlacementSpace
import dev.stapler.stelekit.ui.sketch.VertexRef
import dev.stapler.stelekit.ui.sketch.confirmed
import dev.stapler.stelekit.ui.sketch.describe
import dev.stapler.stelekit.ui.sketch.initialSketchView
import dev.stapler.stelekit.ui.sketch.moveVertexLabel
import dev.stapler.stelekit.ui.sketch.nudged
import dev.stapler.stelekit.ui.sketch.nudgeDescription
import dev.stapler.stelekit.ui.sketch.respaced
import dev.stapler.stelekit.ui.sketch.sixteenthsFor
import dev.stapler.stelekit.ui.sketch.spokenLength
import dev.stapler.stelekit.ui.sketch.startMove
import dev.stapler.stelekit.ui.sketch.tapped
import dev.stapler.stelekit.ui.sketch.toggled
import dev.stapler.stelekit.ui.sketch.vertexAt
import dev.stapler.stelekit.units.Inches
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SketchNonDragModeTest {
    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))
    private fun <A> Either<*, A>.ok(): A = fold({ error("unexpected $it") }, { it })
    private val space = SketchPlacementSpace(initialSketchView(), 800, 600, 2f)
    private val line = SketchPrimitive.Line("l", pt(0, 0), pt(192, 0))
    private val rect = SketchPrimitive.Rect("r", pt(0, 0), pt(48, 32))

    @Test
    fun sixteenthsForAlwaysMovesANonZeroPixelCount() = runTest {
        checkAll(Arb.int(-5_000..5_000), Arb.long(1L..400L)) { px, per ->
            val moved = sixteenthsFor(px, per / 100.0)
            if (px == 0) assertEquals(0L, moved) else assertTrue(moved != 0L && (moved > 0) == (px > 0))
        }
    }

    @Test
    fun spaceCoversTheVisibleSheetAndClamps() {
        assertTrue(space.contains(space.center()))
        assertTrue(space.contains(space.clamp(pt(-1_000_000, 1_000_000))))
        assertTrue(!space.contains(pt(1_000_000, 0)))
    }

    @Test
    fun toggleStartsAtTheCenterAndTogglesOff() {
        val on = SketchPlacementSession().toggled(space)
        assertEquals(space.center(), on.state.crosshairPoint)
        assertTrue(on.active)
        assertEquals(SketchPlacementSession(), on.toggled(space))
    }

    @Test
    fun nudgeAndTapMoveTheCrosshairButNeverPlace() {
        val on = SketchPlacementSession().toggled(space)
        val nudged = on.nudged(space, NudgeDirection.RIGHT, NudgeStep.ONE_PERCENT)
        assertTrue(nudged.state.crosshairPoint != on.state.crosshairPoint)
        assertEquals(pt(10, 10), on.tapped(space, pt(10, 10)).state.crosshairPoint)
    }

    @Test
    fun confirmPlacesAndKeepsTheCrosshairForTheNextPoint() {
        val aimed = SketchPlacementSession().toggled(space).tapped(space, pt(10, 10))
        val (next, commit) = aimed.confirmed(space)
        assertEquals(PlacementCommit.Place(pt(10, 10)), commit)
        assertEquals(PlacementState.Placed(pt(10, 10)), next.state)
    }

    @Test
    fun confirmWhileIdleCommitsNothing() {
        assertNull(SketchPlacementSession().confirmed(space).second)
    }

    @Test
    fun moveVertexEndsTheModeAfterApply() {
        val ref = VertexRef("l", 1)
        val moving = SketchPlacementSession().startMove(space, ref, pt(12, 4))
        assertEquals(pt(12, 4), moving.state.crosshairPoint)
        val (next, commit) = moving.confirmed(space)
        assertEquals(PlacementCommit.Move(ref, pt(12, 4)), commit)
        assertEquals(SketchPlacementSession(), next)
    }

    @Test
    fun respacedKeepsAnInsideCrosshair() {
        val aimed = SketchPlacementSession().toggled(space).tapped(space, pt(10, 10))
        assertEquals(aimed.state, aimed.respaced(space).state)
    }

    @Test
    fun moveVertexCommandMovesLineEndsAndRectCorners() {
        val doc = SketchDocument(SketchScene.EMPTY.add(line).ok().add(rect).ok())
        val movedLine = SketchCommand.MoveVertex("l", 1, pt(100, 20)).apply(doc).ok()
        assertEquals(pt(100, 20), movedLine.scene.vertexAt(VertexRef("l", 1)))
        val movedCorner = SketchCommand.MoveVertex("r", 1, pt(60, 8)).apply(doc).ok()
        assertEquals(pt(60, 8), movedCorner.scene.vertexAt(VertexRef("r", 1)))
        assertTrue(SketchCommand.MoveVertex("r", 9, pt(0, 0)).apply(doc).isLeft())
        assertTrue(SketchCommand.MoveVertex("nope", 0, pt(0, 0)).apply(doc).isLeft())
        assertNull(doc.scene.vertexAt(VertexRef("l", 7)))
    }

    @Test
    fun movingAnyCornerPutsThatCornerAtTheTarget() = runTest {
        checkAll(Arb.int(0..3), Arb.long(-500L..500L), Arb.long(-500L..500L)) { i, x, y ->
            val moved = rect.withVertex(i, pt(x, y))!!
            assertEquals(pt(x, y), moved.vertices[i])
        }
        assertNull(rect.withVertex(4, pt(0, 0)))
        assertNull(line.withVertex(2, pt(0, 0)))
    }

    @Test
    fun lockedLayerRefusesAVertexMove() {
        val doc = SketchDocument(SketchScene.EMPTY.add(line.copy(layer = "L")).ok())
        val locked = doc.copy(sheet = doc.sheet.copy(layers = doc.sheet.layers + SketchLayer("L", visible = true, locked = true)))
        assertTrue(SketchCommand.MoveVertex("l", 0, pt(1, 1)).apply(locked).isLeft())
    }

    @Test
    fun everyPrimitiveIsDescribedWithItsValue() {
        assertEquals("Empty sheet", SketchScene.EMPTY.describe())
        assertEquals("Line 1, 1 ft", line.describe(1))
        assertEquals("Rectangle 2, 3 in by 2 in", rect.describe(2))
        val slanted = SketchPrimitive.Line("s", pt(-16, 0), pt(32, 48)).describe(3)
        assertTrue(slanted.startsWith("Line 3 from x minus 1 in") && slanted.contains("to x 2 in, y 3 in"), slanted)
        assertEquals("Line 1, 1 ft. Rectangle 2, 3 in by 2 in", SketchScene.EMPTY.add(line).ok().add(rect).ok().describe())
    }

    @Test
    fun spokenLengthsReadAsWords() {
        assertEquals("0 in", spokenLength(0))
        assertEquals("3/16 in", spokenLength(3))
        assertEquals("2 and 3/16 in", spokenLength(35))
        assertEquals("1 ft 2 in", spokenLength(224))
        assertEquals("1 ft 3/16 in", spokenLength(195))
        assertEquals("2 ft", spokenLength(-384))
    }

    @Test
    fun talkBackLabelsNameTheDirectionStepAndVertex() {
        assertEquals("Move vertex 1", moveVertexLabel(0))
        assertEquals("Nudge left, 1 pixel step", nudgeDescription(NudgeDirection.LEFT, NudgeStep.ONE_PIXEL))
        assertEquals("Nudge up, 10 pixels step", nudgeDescription(NudgeDirection.UP, NudgeStep.TEN_PIXELS))
        assertEquals("Nudge right, 1 percent step", nudgeDescription(NudgeDirection.RIGHT, NudgeStep.ONE_PERCENT))
        assertEquals("Nudge down, 1 pixel step", nudgeDescription(NudgeDirection.DOWN, NudgeStep.ONE_PIXEL))
    }
}
