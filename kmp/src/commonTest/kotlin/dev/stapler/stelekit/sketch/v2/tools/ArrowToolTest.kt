// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowType
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.BindingMode
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.arrowType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.3.1 (UX-02, UX-08, OD-7): the arrow tool, its types and arrowheads, binding on drop and the highlight. */
class ArrowToolTest {
    private val ctrl = Modifiers(ctrl = true)
    private val meta = Modifiers(meta = true)

    private fun added(r: ScenarioResult) = assertIs<ArrowElement>(assertIs<AddElementCommand>(r.commands.single()).element)

    /** Two boxes with a gap between them: r1 spans x 0..100, r2 spans x 300..400, both y 0..50. */
    private fun twoBoxes() = sceneOfElements(filledRectEl("r1", 0, 0, 100, 50), filledRectEl("r2", 300, 0, 100, 50))

    private fun twoBoxContext() = testToolContext(twoBoxes())

    // ---- drawing --------------------------------------------------------------------------------------------------

    @Test
    fun arrow_should_CommitAThreePointCurvedArrow_When_RoundTypeAndThreeClicks() {
        val r = runScenario(ArrowTool(ArrowOptions(type = ArrowType.Round))) {
            down(0, 0); up(0, 0)
            down(50, 30); up(50, 30)
            down(100, 0); up(100, 0)
            down(100, 0); up(100, 0)
        }
        val arrow = added(r)
        assertEquals(listOf(0.0 to 0.0, 50.0 to 30.0, 100.0 to 0.0), arrow.points.map { it.x.value to it.y.value })
        assertNotNull(arrow.base.style.roundness)
        assertEquals(Arrowhead.Arrow, arrow.endArrowhead)
        assertEquals(Arrowhead.None, arrow.startArrowhead)
        assertEquals(ArrowType.Round, arrow.arrowType)
    }

    @Test
    fun arrow_should_CommitASharpTwoPointArrow_When_Dragged() {
        val r = runScenario(ArrowTool()) { down(0, 0); move(30, 10); up(60, 20) }
        val arrow = added(r)
        assertEquals(2, arrow.points.size)
        assertNull(arrow.base.style.roundness)
        assertEquals(ArrowType.Sharp, arrow.arrowType)
        assertNull(arrow.startBinding)
        assertNull(arrow.endBinding)
    }

    @Test
    fun arrow_should_StoreTheElbowFlag_And_KeepThePointsAsDrawn() {
        val r = runScenario(ArrowTool(ArrowOptions(type = ArrowType.Elbow))) { down(0, 0); up(0, 0); down(40, 0); up(40, 0); down(40, 40); up(40, 40); down(40, 40); up(40, 40) }
        val arrow = added(r)
        assertTrue(arrow.elbowed)
        assertNull(arrow.base.style.roundness)
        assertEquals(3, arrow.points.size)
    }

    @Test
    fun arrow_should_UseTheConfiguredArrowheads() {
        val options = ArrowOptions(startArrowhead = Arrowhead.Circle, endArrowhead = Arrowhead.Triangle)
        val arrow = added(runScenario(ArrowTool(options)) { down(0, 0); up(50, 0) })
        assertEquals(Arrowhead.Circle, arrow.startArrowhead)
        assertEquals(Arrowhead.Triangle, arrow.endArrowhead)
    }

    @Test
    fun arrow_should_SnapToFifteenDegrees_When_TheChipIsOn() {
        val arrow = added(runScenario(ArrowTool(ArrowOptions(angleSnap = true))) { down(0, 0); up(100, 20) })
        val (a, b) = arrow.points
        val angle = kotlin.math.atan2(b.y.value - a.y.value, b.x.value - a.x.value)
        val steps = angle / (kotlin.math.PI / 12.0)
        assertEquals(kotlin.math.round(steps), steps, 1e-6)
    }

    @Test
    fun arrow_should_CommitNothing_When_ZeroLength_OrCancelled() {
        val stray = runScenario(ArrowTool()) { down(5, 5); up(5, 5) }
        val esc = runScenario(ArrowTool()) { down(0, 0); move(40, 40); escape(); up(40, 40) }
        val second = runScenario(ArrowTool()) { down(0, 0); down(9, 9, pointer = 1); up(40, 40) }
        assertTrue(stray.commands.isEmpty() && esc.commands.isEmpty() && second.commands.isEmpty())
        assertTrue((esc.finalTool as ArrowTool).placedPoints.isEmpty())
    }

    @Test
    fun arrow_should_SayNothingDrawn_When_FinishedOnASingleVertex() {
        val ctx = testToolContext()
        val twice = runScenario(ArrowTool(), ctx) { down(5, 5); up(5, 5); down(5, 5); up(5, 5) }
        assertTrue(twice.commands.isEmpty())
        assertEquals(ToolNotice.NothingDrawn, twice.notices.last())
        assertEquals(listOf(null, null, null), twice.notices.take(3).map { it?.takeIf { n -> n != ToolNotice.NothingDrawn } })

        val one = runScenario(ArrowTool(), ctx) { down(5, 5); up(5, 5) }
        val finished = (one.finalTool as ArrowTool).done(ctx)
        assertNull(finished.command)
        assertEquals(ToolNotice.NothingDrawn, finished.notice)

        val abandoned = runScenario(ArrowTool(), ctx) { down(5, 5); up(5, 5); escape() }
        assertNull(abandoned.notices.last(), "Esc abandons quietly")
    }

    @Test
    fun arrow_should_FinishWithDone_AndKeepItsOptions() {
        val ctx = testToolContext()
        val options = ArrowOptions(endArrowhead = Arrowhead.Bar)
        val two = runScenario(ArrowTool(options), ctx) { down(0, 0); up(0, 0); down(50, 0); up(50, 0) }
        val finished = (two.finalTool as ArrowTool).done(ctx)
        val arrow = assertIs<ArrowElement>(assertIs<AddElementCommand>(finished.command).element)
        assertEquals(Arrowhead.Bar, arrow.endArrowhead)
        assertIs<ArrowTool>(finished.next)
    }

    // ---- binding ----------------------------------------------------------------------------------------------------

    @Test
    fun arrow_should_BindBothEnds_AndSnapThemToTheOutlines_When_DroppedNearTwoShapes() {
        val r = runScenario(ArrowTool(), twoBoxContext()) { down(98, 25); move(200, 25); up(302, 25) }
        val arrow = added(r)
        val start = assertNotNull(arrow.startBinding)
        val end = assertNotNull(arrow.endBinding)
        assertEquals("r1", start.elementId.value)
        assertEquals(1.0, start.fixedX)
        assertEquals(0.5, start.fixedY)
        assertEquals(BindingMode.Orbit, start.mode)
        assertEquals("r2", end.elementId.value)
        assertEquals(0.0, end.fixedX)
        assertEquals(listOf(100.0 to 25.0, 300.0 to 25.0), arrow.points.map { it.x.value to it.y.value })
    }

    @Test
    fun boundArrow_should_BeListedByItsTargets_AfterTheCommandRuns() {
        val doc = SketchDocumentV2(twoBoxes())
        val cmd = runScenario(ArrowTool(), testToolContext(doc.scene)) { down(98, 25); up(302, 25) }.commands.single()
        val after = commitToolCommand(doc, cmd).present.scene
        val arrowId = assertIs<AddElementCommand>(cmd).element.id
        assertEquals(listOf(BoundRef(arrowId, BoundKind.Arrow)), assertIs<RectElement>(after[elId("r1")]).boundElements)
        assertEquals(listOf(BoundRef(arrowId, BoundKind.Arrow)), assertIs<RectElement>(after[elId("r2")]).boundElements)
    }

    @Test
    fun arrow_should_StayFreeEnded_When_NoShapeIsUnderTheDrop() {
        val arrow = added(runScenario(ArrowTool(), twoBoxContext()) { down(150, 200); up(250, 200) })
        assertNull(arrow.startBinding)
        assertNull(arrow.endBinding)
        assertEquals(listOf(150.0, 250.0), arrow.points.map { it.x.value })
    }

    @Test
    fun arrow_should_NotBind_When_CtrlOrCmdIsHeldOnDrop_OrTheChipIsOff() {
        val withCtrl = added(runScenario(ArrowTool(), twoBoxContext()) { down(98, 25); up(302, 25, mods = ctrl) })
        val withMeta = added(runScenario(ArrowTool(), twoBoxContext()) { down(98, 25); up(302, 25, mods = meta) })
        val chipOff = added(runScenario(ArrowTool(ArrowOptions(bind = false)), twoBoxContext()) { down(98, 25); up(302, 25) })
        for (arrow in listOf(withCtrl, withMeta, chipOff)) {
            assertNull(arrow.startBinding)
            assertNull(arrow.endBinding)
            assertEquals(302.0, arrow.points.last().x.value)
        }
    }

    @Test
    fun arrow_should_AllowBothEndsOnTheSameShape() {
        val ctx = testToolContext(sceneOfElements(filledRectEl("r1", 0, 0, 100, 50)))
        val arrow = added(runScenario(ArrowTool(), ctx) { down(2, 25); up(98, 25) })
        assertEquals("r1", assertNotNull(arrow.startBinding).elementId.value)
        assertEquals("r1", assertNotNull(arrow.endBinding).elementId.value)
        assertEquals(listOf(0.0, 100.0), arrow.points.map { it.x.value })
    }

    @Test
    fun arrow_should_StayUnbound_When_BothEndsWouldSnapToTheSamePoint() {
        val ctx = testToolContext(sceneOfElements(filledRectEl("tiny", 0, 0, 10, 10)))
        val arrow = added(runScenario(ArrowTool(), ctx) { down(5, 3); up(5, -7) })
        assertNull(arrow.startBinding)
        assertNull(arrow.endBinding)
        assertEquals(-7.0, arrow.points.last().y.value)
    }

    @Test
    fun arrow_should_BindToALockedShape_But_NotToOneOnAHiddenLayer() {
        val hiddenLayer = LayerId("hidden")
        val scene = sceneOfElements(
            filledRectEl("locked", 0, 0, 100, 50, RectSpec(locked = true)),
            filledRectEl("ghost", 300, 0, 100, 50, RectSpec(layer = hiddenLayer)),
        ).withLayers(listOf(Layer.DefaultLayer, Layer(hiddenLayer, "Hidden", visible = false, order = 1)))
        val arrow = added(runScenario(ArrowTool(), testToolContext(scene)) { down(98, 25); up(302, 25) })
        assertEquals("locked", assertNotNull(arrow.startBinding).elementId.value)
        assertNull(arrow.endBinding)
    }

    @Test
    fun arrow_should_PreferTheTopmostShape_WhenSeveralAreInReach() {
        val scene = sceneOfElements(filledRectEl("under", 0, 0, 100, 50), filledRectEl("over", 50, 0, 100, 50))
        val arrow = added(runScenario(ArrowTool(), testToolContext(scene)) { down(75, 25); up(300, 300) })
        assertEquals("over", assertNotNull(arrow.startBinding).elementId.value)
    }

    @Test
    fun arrow_should_ReadASmallerReach_WhenZoomedIn() {
        val near = testToolContext(twoBoxes(), zoom = 4.0)
        val arrow = added(runScenario(ArrowTool(), near) { down(104, 25); up(250, 25) })
        assertNull(arrow.startBinding)
    }

    @Test
    fun preview_should_NameTheShapeTheEndWouldBindTo() {
        val r = runScenario(ArrowTool(), twoBoxContext()) { down(98, 25); move(200, 25); move(302, 25) }
        assertEquals(elId("r2"), assertNotNull(r.previews.last()).highlight)
        assertNull(r.previews[1]?.highlight?.takeIf { it == elId("r2") })
    }

    @Test
    fun preview_should_NameNoShape_WhenBindingIsOff() {
        val r = runScenario(ArrowTool(), twoBoxContext()) { down(98, 25); move(302, 25, mods = ctrl) }
        assertNull(assertNotNull(r.previews.last()).highlight)
    }

    @Test
    fun arrow_should_BindToARotatedShapeInItsLocalFrame() {
        val turned = RectElement(
            dev.stapler.stelekit.sketch.v2.scene.ElementBase(elId("t")), su(0), su(0), su(100), su(50),
            angle = kotlin.math.PI / 2.0,
        )
        // Rotated a quarter turn about (50, 25): the local right edge midpoint (100, 25) lands at (50, 75).
        val arrow = added(runScenario(ArrowTool(), testToolContext(sceneOfElements(turned))) { down(50, 77); up(300, 300) })
        val start = assertNotNull(arrow.startBinding)
        assertEquals(1.0, start.fixedX, 1e-9)
        assertEquals(0.5, start.fixedY, 1e-9)
        assertEquals(50.0, arrow.points.first().x.value, 1e-9)
        assertEquals(75.0, arrow.points.first().y.value, 1e-9)
    }

    @Test
    fun arrow_should_BeOneUndoStep_AndUndoLeavesTheShapesAsTheyWere() {
        val doc = SketchDocumentV2(twoBoxes())
        val cmd = runScenario(ArrowTool(), testToolContext(doc.scene)) { down(98, 25); up(302, 25) }.commands.single()
        val history = commitToolCommand(doc, cmd)
        assertEquals(1, history.undoDepth)
        assertEquals(doc, history.undo().present)
    }
}
