// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.SnapKind
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.snap.GridSettings
import dev.stapler.stelekit.sketch.v2.snap.GuideAxis
import dev.stapler.stelekit.sketch.v2.snap.ResizeSides
import dev.stapler.stelekit.sketch.v2.snap.ResizeSnap
import dev.stapler.stelekit.sketch.v2.snap.SnapSettings
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Resize-handle and move snapping through the Select tool (preview equals commit), plus the [ResizeSnap] math on its own. */
class ResizeSnapTest {
    private val subject = filledRectEl("a", 0, 0, 200, 120)

    /** Reaches x 300..400 (centre 350) and y 0..120: a neighbour to the right of [subject], which is big enough to show every handle. */
    private val right = filledRectEl("right", 300, 0, 100, 120)

    /** Reaches x -300..-200: a neighbour to the left of [subject]. */
    private val left = filledRectEl("left", -300, 0, 100, 120)

    private val objects = SnapSettings(objects = true)

    private fun ctx(scene: SketchSceneV2, snap: SnapSettings = objects, zoom: Double = 1.0, pxPerDp: Double = 1.0) =
        testToolContext(scene, zoom).copy(snap = snap, pxPerDp = pxPerDp)

    private fun selectA() = SelectTool(selection = listOf(elId("a")))

    private fun resized(r: ScenarioResult, scene: SketchSceneV2): RectElement {
        val history = commitToolCommand(SketchDocumentV2(scene), assertIs<TransformSelectionCommand>(r.commands.single()))
        return history.present.scene[elId("a")] as RectElement
    }

    private fun RectElement.span() = listOf(x.value, y.value, width.value, height.value)

    // ---- handle resize ---------------------------------------------------------------------------------------------

    @Test
    fun eastHandle_should_SnapItsMovingEdgeToTheNeighbourEdge_And_LeaveTheAnchoredEdge() {
        val scene = sceneOfElements(subject, right)
        val r = runScenario(selectA(), ctx(scene)) { down(200, 60); move(296, 60); up(296, 60) }
        assertEquals(listOf(0.0, 0.0, 300.0, 120.0), resized(r, scene).span(), "right edge 296 snaps to 300, left edge stays at 0")
    }

    @Test
    fun westHandle_should_SnapToTheNeighbourOnItsSide_AndKeepTheFarEdge() {
        val scene = sceneOfElements(subject, left)
        val r = runScenario(selectA(), ctx(scene)) { down(0, 60); move(-197, 60); up(-197, 60) }
        assertEquals(listOf(-200.0, 0.0, 400.0, 120.0), resized(r, scene).span(), "left edge -197 snaps to -200, right edge stays at 200")
    }

    @Test
    fun edgeHandle_should_SnapToTheNeighbourCentre() {
        val scene = sceneOfElements(subject, right)
        val r = runScenario(selectA(), ctx(scene)) { down(200, 60); move(345, 60); up(345, 60) }
        assertEquals(350.0, resized(r, scene).let { it.x.value + it.width.value }, "within 8 of the centre line (350)")
    }

    @Test
    fun cornerHandle_should_SnapEachMovingEdgeIndependently() {
        val scene = sceneOfElements(subject, right)
        val r = runScenario(selectA(), ctx(scene)) { down(200, 120); move(297, 123); up(297, 123) }
        assertEquals(listOf(0.0, 0.0, 300.0, 120.0), resized(r, scene).span(), "x to the neighbour's left edge, y to its bottom edge")
    }

    @Test
    fun keepAspect_should_SnapThePrimaryAxis_And_LetTheOtherFollow() {
        val scene = sceneOfElements(subject, right)
        val shift = Modifiers(shift = true)
        val r = runScenario(selectA(), ctx(scene)) { down(200, 120); move(296, 150, mods = shift); up(296, 150, mods = shift) }
        assertEquals(listOf(0.0, 0.0, 300.0, 180.0), resized(r, scene).span(), "x scales most so it snaps to 300; y follows the aspect")
    }

    @Test
    fun fromCentre_should_SnapTheDraggedEdge_And_MirrorTheOpposite() {
        val scene = sceneOfElements(subject, right)
        val alt = Modifiers(alt = true)
        val r = runScenario(selectA(), ctx(scene)) { down(200, 60); move(297, 60, mods = alt); up(297, 60, mods = alt) }
        assertEquals(listOf(-100.0, 0.0, 400.0, 120.0), resized(r, scene).span(), "right edge on 300, left edge mirrored about the centre 100")
    }

    @Test
    fun gridOnly_should_RoundTheMovingEdgeToTheStep() {
        val scene = sceneOfElements(subject)
        val grid = SnapSettings(grid = GridSettings.diagram(true))
        val r = runScenario(selectA(), ctx(scene, grid)) { down(200, 60); move(253, 60); up(253, 60) }
        assertEquals(260.0, resized(r, scene).width.value)
        val technical = SnapSettings(grid = GridSettings.technical())
        val fine = runScenario(selectA(), ctx(scene, technical)) { down(200, 60); move(253, 60); up(253, 60) }
        assertEquals(253.0, resized(fine, scene).width.value, "the sixteenth-inch step is one unit")
    }

    @Test
    fun snap_should_BeOff_Or_BypassedWithCtrl_AndLeaveTheResizeUnchanged() {
        val scene = sceneOfElements(subject, right)
        val off = runScenario(selectA(), ctx(scene, SnapSettings.None)) { down(200, 60); move(296, 60); up(296, 60) }
        assertEquals(296.0, resized(off, scene).width.value)
        val ctrl = Modifiers(ctrl = true)
        val held = runScenario(selectA(), ctx(scene)) { down(200, 60); move(296, 60, mods = ctrl); up(296, 60, mods = ctrl) }
        assertEquals(296.0, resized(held, scene).width.value, "Ctrl bypasses")
        val plain = runScenario(selectA(), testToolContext(scene)) { down(200, 60); move(296, 60); up(296, 60) }
        assertEquals(296.0, resized(plain, scene).width.value, "a context with no snap settings sees raw geometry")
    }

    @Test
    fun tolerance_should_ShrinkWithZoom_And_GrowWithDensity() {
        val scene = sceneOfElements(subject, right)
        val far = runScenario(selectA(), ctx(scene, zoom = 4.0)) { down(200, 120); move(296, 120); up(296, 120) }
        assertEquals(296.0, resized(far, scene).width.value, "4 units is 16 px at zoom 4, past the 8 px reach")
        val sides = ResizeSides(1, 0)
        val dense = ResizeSnap.snap(scene, index(scene), setOf(elId("a")), frame, sides, 80.0, 0.0, false, false, 1.0, 3.0, objects)
        assertEquals(100.0, dense.dx, "edge 280 is 20 units from 300: within 8 dp at 3 px per dp (24 px)")
        val sparse = ResizeSnap.snap(scene, index(scene), setOf(elId("a")), frame, sides, 80.0, 0.0, false, false, 1.0, 1.0, objects)
        assertEquals(80.0, sparse.dx)
    }

    @Test
    fun preview_should_ShowTheSnappedBox_SoTheCommitDoesNotJump() {
        val scene = sceneOfElements(subject, right)
        val r = runScenario(selectA(), ctx(scene)) { down(200, 60); move(296, 60); up(296, 60) }
        val shown = assertNotNull(r.previews[1]).elements.single() as RectElement
        assertEquals(300.0, shown.width.value)
        assertEquals(resized(r, scene).span(), shown.span())
    }

    // ---- move ------------------------------------------------------------------------------------------------------

    @Test
    fun move_should_SnapInThePreviewToo_And_CtrlBypasses() {
        val scene = sceneOfElements(subject, right)
        val r = runScenario(SelectTool(selection = listOf(elId("a"))), ctx(scene)) { down(100, 60); move(197, 60); up(197, 60) }
        assertEquals(100.0, assertIs<MoveSelectionCommand>(r.commands.single()).dx, "right edge 297 snaps to 300")
        assertEquals(100.0, (assertNotNull(r.previews[1]).elements.single() as RectElement).x.value, "the preview shows the snapped place")
        val ctrl = Modifiers(ctrl = true)
        val held = runScenario(SelectTool(selection = listOf(elId("a"))), ctx(scene)) { down(100, 60); move(197, 60, mods = ctrl); up(197, 60, mods = ctrl) }
        assertEquals(97.0, assertIs<MoveSelectionCommand>(held.commands.single()).dx)
    }

    @Test
    fun shiftAxisLock_should_KeepItsZeroAxis_EvenWhenAnEdgeIsInReach() {
        val offset = filledRectEl("right", 300, 5, 100, 120)
        val scene = sceneOfElements(subject, offset)
        val shift = Modifiers(shift = true)
        val r = runScenario(SelectTool(selection = listOf(elId("a"))), ctx(scene)) { down(100, 60); move(197, 62, mods = shift); up(197, 62, mods = shift) }
        val move = assertIs<MoveSelectionCommand>(r.commands.single())
        assertEquals(100.0, move.dx)
        assertEquals(0.0, move.dy, "the lock keeps dy at zero although y=5 is within reach")
    }

    // ---- the math on its own ---------------------------------------------------------------------------------------

    private fun index(scene: SketchSceneV2) = SpatialIndex().also { it.rebuild(scene) }

    private val frame = SceneBounds(0.0, 0.0, 200.0, 120.0)

    @Test
    fun snap_should_ReportAVerticalGuideAtTheTargetEdge() {
        val scene = sceneOfElements(subject, right)
        val out = ResizeSnap.snap(scene, index(scene), setOf(elId("a")), frame, ResizeSides(1, 0), 97.0, 0.0, false, false, 1.0, 1.0, objects)
        assertEquals(100.0, out.dx)
        val guide = out.guides.single()
        assertEquals(GuideAxis.Vertical, guide.axis)
        assertEquals(300.0, guide.position)
        assertEquals(SnapKind.ENDPOINT, guide.kind)
    }

    @Test
    fun snap_should_NeverUseTheMovingElementsAsTargets() {
        val scene = sceneOfElements(subject)
        val out = ResizeSnap.snap(scene, index(scene), setOf(elId("a")), frame, ResizeSides(1, 0), 3.0, 0.0, false, false, 1.0, 1.0, objects)
        assertEquals(3.0, out.dx, "its own right edge (200) is no target for the dragged edge (203)")
        assertTrue(out.guides.isEmpty())
    }

    @Test
    fun snap_should_LeaveAnUndraggedAxisAlone() {
        val scene = sceneOfElements(subject, right)
        val out = ResizeSnap.snap(scene, index(scene), setOf(elId("a")), frame, ResizeSides(1, 0), 97.0, 3.0, false, false, 1.0, 1.0, objects)
        assertEquals(3.0, out.dy, "an east handle drags no horizontal edge, so dy is not snapped")
    }

    @Test
    fun snap_should_FallBackToTheGridWhenNoElementIsInReach() {
        val scene = sceneOfElements(subject)
        val grid = SnapSettings(objects = true, grid = GridSettings.diagram(true))
        val out = ResizeSnap.snap(scene, index(scene), setOf(elId("a")), frame, ResizeSides(1, 0), 53.0, 0.0, false, false, 1.0, 1.0, grid)
        assertEquals(60.0, out.dx, "edge 253 rounds to 260")
        assertEquals(SnapKind.GRID, out.guides.single().kind)
    }

    @Test
    fun snap_should_IgnoreUnusableZoom() {
        val scene = sceneOfElements(subject, right)
        assertEquals(97.0, ResizeSnap.snap(scene, index(scene), setOf(elId("a")), frame, ResizeSides(1, 0), 97.0, 0.0, false, false, 0.0, 1.0, objects).dx)
    }

    @Test
    fun aspectLock_should_KeepTheSnappedAxisPrimary_SoTheEdgeLandsOnTheLine() {
        val line = filledRectEl("n", 147, 500, 30, 30)
        val scene = sceneOfElements(line)
        val square = SceneBounds(0.0, 0.0, 100.0, 100.0)
        val out = ResizeSnap.snap(scene, index(scene), setOf(elId("a")), square, ResizeSides(1, 1), 50.0, 48.0, true, false, 1.0, 1.0, objects)
        val box = TransformMath.resize(Box(0.0, 0.0, 100.0, 100.0), 0.0, Handle.SE, out.dx, out.dy, keepAspect = true, fromCentre = false)
        assertEquals(147.0, box.width, "x snapped to 147; the derived y must not overtake it")
        assertEquals(147.0, box.height)
    }

    @Test
    fun hiddenLayerElements_should_NotAttractTheEdge() {
        val hiddenLayer = LayerId("hidden")
        val base = sceneOfElements(subject)
        val scene = base.withLayers(base.layers + Layer(hiddenLayer, "Hidden", visible = false, order = 1)).add(filledRectEl("h", 300, 0, 100, 120, RectSpec(layer = hiddenLayer))).getOrNull()!!
        val out = ResizeSnap.snap(scene, index(scene), setOf(elId("a")), frame, ResizeSides(1, 0), 97.0, 0.0, false, false, 1.0, 1.0, objects)
        assertEquals(97.0, out.dx)
    }

    @Test
    fun smallFrame_should_NotBeDominatedByANeighbourInsideTheFullRadius() {
        val scene = sceneOfElements(filledRectEl("n", 16, 0, 10, 10))
        val tiny = SceneBounds(0.0, 0.0, 12.0, 12.0)
        val out = ResizeSnap.snap(scene, index(scene), setOf(elId("a")), tiny, ResizeSides(1, 0), 0.0, 0.0, false, false, 1.0, 1.0, objects)
        assertEquals(0.0, out.dx, "4 units away is inside 8 px but past a quarter of a 12 unit frame")
    }
}
