package dev.stapler.stelekit.sketch

import dev.stapler.stelekit.ui.sketch.DragIntent
import dev.stapler.stelekit.ui.sketch.DragPreview
import dev.stapler.stelekit.ui.sketch.indicatorLabel
import dev.stapler.stelekit.ui.sketch.shouldCommitDrag
import dev.stapler.stelekit.ui.sketch.SketchTool
import dev.stapler.stelekit.ui.sketch.VertexRef
import dev.stapler.stelekit.ui.sketch.handleAt
import dev.stapler.stelekit.ui.sketch.initialSketchView
import dev.stapler.stelekit.ui.sketch.previewDrag
import dev.stapler.stelekit.ui.sketch.readoutOf
import dev.stapler.stelekit.ui.sketch.resolveDragStart
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SketchDragTest {
    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))
    private val view = initialSketchView() // origin 16 dp, 96 dp per inch
    private fun screenAt(xIn: Double, yIn: Double) =
        ScreenPoint(Rational(((16 + 96 * xIn) * 1000).toLong(), 1000), Rational(((16 + 96 * yIn) * 1000).toLong(), 1000))
    private val line = SketchPrimitive.Line("l", pt(16, 16), pt(32, 16))
    private val rect = SketchPrimitive.Rect("r", pt(16, 16), pt(48, 48))
    private fun sceneOf(vararg p: SketchPrimitive) = p.fold(SketchScene.EMPTY) { s, x -> s.add(x).getOrNull()!! }
    private val grid = Inches(16)

    @Test
    fun drawToolDragStartsADrawAtTheSnappedPoint() {
        val intent = resolveDragStart(SketchTool.LINE, SketchScene.EMPTY, null, view, screenAt(1.03, 0.98), grid)
        assertEquals(DragIntent.Draw(pt(16, 16)), intent)
    }

    @Test
    fun handleOfSelectedShapeWinsInAnyTool() {
        val scene = sceneOf(line)
        assertEquals(DragIntent.MoveVertex(VertexRef("l", 1)), resolveDragStart(SketchTool.LINE, scene, "l", view, screenAt(2.0, 1.0), grid))
        assertNull(handleAt(line, view, screenAt(4.0, 4.0)))
    }

    @Test
    fun selectToolBodyDragMovesShapeAndEmptyDragPans() {
        val scene = sceneOf(line)
        assertEquals(DragIntent.MoveShape("l"), resolveDragStart(SketchTool.ZONE_SELECT, scene, null, view, screenAt(1.5, 1.0), grid))
        assertEquals(DragIntent.Pan, resolveDragStart(SketchTool.ZONE_SELECT, scene, null, view, screenAt(1.5, 3.0), grid))
    }

    @Test
    fun drawPreviewLocksStraightAndReadsOutTheLength() {
        val p = previewDrag(DragIntent.Draw(pt(16, 16)), SketchTool.LINE, SketchScene.EMPTY, view, screenAt(1.0, 1.0), screenAt(2.9, 1.03), null)!!
        assertEquals(SnapKind.AXIS_LOCK, p.snap)
        assertEquals(16L, p.point.y.sixteenths)
        assertTrue(readoutOf(p.shape).isNotBlank())
    }

    @Test
    fun vertexDragSnapsToAnotherShapesEndpointNotItsOwn() {
        val other = SketchPrimitive.Line("o", pt(80, 16), pt(80, 64))
        val p = previewDrag(DragIntent.MoveVertex(VertexRef("l", 1)), SketchTool.ZONE_SELECT, sceneOf(line, other), view, screenAt(2.0, 1.0), screenAt(5.02, 1.02), grid)!!
        assertEquals(SnapKind.ENDPOINT, p.snap)
        assertEquals(pt(80, 16), (p.shape as SketchPrimitive.Line).b)
        assertEquals(pt(16, 16), (p.shape as SketchPrimitive.Line).a)
    }

    @Test
    fun shapeDragTranslatesEveryVertexByTheSnappedOffset() {
        val p = previewDrag(DragIntent.MoveShape("r"), SketchTool.ZONE_SELECT, sceneOf(rect), view, screenAt(2.0, 1.0), screenAt(3.0, 2.0), grid)!!
        assertEquals(SketchPrimitive.Rect("r", pt(32, 32), pt(64, 64)), p.shape)
    }

    @Test
    fun moveShapeAndRectSizeCommands() {
        val doc = SketchHistory.of().present.copy(scene = sceneOf(rect))
        val moved = SketchCommand.MoveShape("r", 16, -16).apply(doc).getOrNull()!!
        assertEquals(pt(32, 0), (moved.scene.primitives.single() as SketchPrimitive.Rect).a)
        val sized = SketchCommand.SetRectSize("r", Inches(96), null).apply(doc).getOrNull()!!
        assertEquals(pt(112, 48), (sized.scene.primitives.single() as SketchPrimitive.Rect).b)
        assertTrue(SketchCommand.SetRectSize("nope", null, null).apply(doc).isLeft())
        assertTrue(SketchCommand.SetRectSize("l", null, null).apply(doc.copy(scene = sceneOf(line))).isLeft())
    }

    @Test
    fun indicatorLabelsAndCommitDecision() {
        assertNull(SnapKind.NONE.indicatorLabel())
        assertEquals(listOf("Endpoint", "Midpoint", "Intersection", "Grid", "Straight"), listOf(SnapKind.ENDPOINT, SnapKind.MIDPOINT, SnapKind.INTERSECTION, SnapKind.GRID, SnapKind.AXIS_LOCK).map { it.indicatorLabel() })
        val real = DragPreview(line, pt(32, 16), SnapKind.GRID, null)
        val flat = DragPreview(SketchPrimitive.Line("z", pt(0, 0), pt(0, 0)), pt(0, 0), SnapKind.NONE, null)
        assertTrue(shouldCommitDrag(DragIntent.Draw(pt(16, 16)), real))
        assertTrue(!shouldCommitDrag(DragIntent.Pan, real))
        assertTrue(!shouldCommitDrag(null, real))
        assertTrue(!shouldCommitDrag(DragIntent.MoveShape("l"), flat))
        assertTrue(!shouldCommitDrag(DragIntent.MoveShape("l"), null))
    }

    @Test
    fun readoutOfSlantedLineAndVerticalLine() {
        assertEquals("1.4\"", readoutOf(SketchPrimitive.Line("s", pt(0, 0), pt(16, 16))))
        assertTrue(readoutOf(SketchPrimitive.Line("v", pt(0, 0), pt(0, 16))).startsWith("1"))
    }
}
