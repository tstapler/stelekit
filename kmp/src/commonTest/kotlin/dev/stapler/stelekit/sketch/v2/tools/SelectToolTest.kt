// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.Layer
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Story 3.2.2 (UX-10, UX-12, UX-127): selection, marquee, move, resize, rotate, duplicate, flip, delete and cancel of the Select tool. */
class SelectToolTest {
    private val a = filledRectEl("a", 0, 0, 40, 40)
    private val b = filledRectEl("b", 60, 0, 40, 40)
    private val c = filledRectEl("c", 0, 100, 40, 40)

    private fun selected(tool: Tool) = (tool as SelectionAware).selection.map { it.value }

    private fun rectOf(doc: SketchDocumentV2, id: String) = doc.scene[elId(id)] as RectElement

    // ---- selection -----------------------------------------------------------------------------------------------

    @Test
    fun click_should_SelectTopmostElement_And_ClearOnEmptyCanvas() {
        val scene = sceneOfElements(a, b)
        val ctx = testToolContext(scene)
        val picked = runScenario(SelectTool(), ctx) { down(10, 10); up(10, 10) }
        assertEquals(listOf("a"), selected(picked.finalTool))
        assertTrue(picked.commands.isEmpty())
        val cleared = runScenario(picked.finalTool, ctx) { down(500, 500); up(500, 500) }
        assertEquals(emptyList(), selected(cleared.finalTool))
    }

    @Test
    fun shiftClick_should_AddAndToggleSelection() {
        val ctx = testToolContext(sceneOfElements(a, b))
        val shift = Modifiers(shift = true)
        val both = runScenario(SelectTool(), ctx) { down(10, 10); up(10, 10); down(70, 10, mods = shift); up(70, 10, mods = shift) }
        assertEquals(listOf("a", "b"), selected(both.finalTool))
        val removed = runScenario(both.finalTool, ctx) { down(10, 10, mods = shift); up(10, 10, mods = shift) }
        assertEquals(listOf("b"), selected(removed.finalTool))
    }

    @Test
    fun click_should_SelectWholeGroup_When_ElementIsGrouped() {
        val g1 = filledRectEl("g1", 0, 0, 40, 40, RectSpec(group = "grp"))
        val g2 = filledRectEl("g2", 100, 0, 40, 40, RectSpec(group = "grp"))
        val ctx = testToolContext(sceneOfElements(g1, g2, c))
        val r = runScenario(SelectTool(), ctx) { down(10, 10); up(10, 10) }
        assertEquals(listOf("g1", "g2"), selected(r.finalTool))
    }

    @Test
    fun canvas_should_NeverSelectLockedOrHiddenLayerElements_And_SelectAllSkipsThem() {
        val locked = filledRectEl("locked", 0, 0, 40, 40, RectSpec(locked = true))
        val hiddenLayer = LayerId("hidden")
        val inHidden = filledRectEl("inHidden", 100, 0, 40, 40, RectSpec(layer = hiddenLayer))
        val scene = sceneOfElements(locked, c).let {
            it.withLayers(it.layers + Layer(hiddenLayer, "Hidden", visible = false, order = 1)).add(inHidden).getOrNull()!!
        }
        val ctx = testToolContext(scene)
        val click = runScenario(SelectTool(), ctx) { down(10, 10); up(10, 10); down(110, 10); up(110, 10) }
        assertEquals(emptyList(), selected(click.finalTool))
        val all = SelectTool().selectAll(ctx)
        assertEquals(listOf("c"), selected(assertNotNull(all.next)))
    }

    @Test
    fun marquee_should_SelectFullyContainedSelectableElementsOnly() {
        val locked = filledRectEl("locked", 0, 200, 40, 40, RectSpec(locked = true))
        val ctx = testToolContext(sceneOfElements(a, b, c, locked))
        val r = runScenario(SelectTool(), ctx) { down(-10, -10); move(110, 50); up(110, 50) }
        assertEquals(listOf("a", "b"), selected(r.finalTool))
        val partial = runScenario(SelectTool(), ctx) { down(-10, -10); up(30, 60) }
        assertEquals(emptyList(), selected(partial.finalTool))
        val withLocked = runScenario(SelectTool(), ctx) { down(-10, 150); up(60, 260) }
        assertEquals(emptyList(), selected(withLocked.finalTool))
    }

    @Test
    fun marquee_should_ExposeItsRectangleWhileDragging() {
        val ctx = testToolContext(sceneOfElements(a))
        val r = runScenario(SelectTool(), ctx) { down(200, 200); move(250, 230) }
        assertEquals(Box(200.0, 200.0, 50.0, 30.0), (r.finalTool as SelectionAware).marquee)
    }

    // ---- move ----------------------------------------------------------------------------------------------------

    @Test
    fun drag_should_MoveSelectionInOneUndoStep() {
        val scene = sceneOfElements(a, b)
        val ctx = testToolContext(scene)
        val r = runScenario(SelectTool(), ctx) { down(10, 10); move(20, 15); up(30, 20) }
        val cmd = assertIs<MoveSelectionCommand>(r.commands.single())
        assertEquals(20.0, cmd.dx)
        assertEquals(10.0, cmd.dy)
        assertNotNull(r.previews[1])
        val doc = SketchDocumentV2(scene)
        val history = commitToolCommand(doc, cmd)
        assertEquals(20.0, rectOf(history.present, "a").x.value)
        assertEquals(doc, history.undo().present)
        assertEquals(1, history.undoDepth)
    }

    @Test
    fun smallMovement_should_BeAClick_NotADrag() {
        val ctx = testToolContext(sceneOfElements(a))
        val r = runScenario(SelectTool(), ctx) { down(10, 10); move(11, 11); up(12, 11) }
        assertTrue(r.commands.isEmpty())
        assertEquals(listOf("a"), selected(r.finalTool))
    }

    @Test
    fun shiftDuringDrag_should_LockMovementToTheDominantAxis() {
        val ctx = testToolContext(sceneOfElements(a))
        val r = runScenario(SelectTool(), ctx) { down(10, 10); up(40, 20, mods = Modifiers(shift = true)) }
        val cmd = assertIs<MoveSelectionCommand>(r.commands.single())
        assertEquals(30.0, cmd.dx)
        assertEquals(0.0, cmd.dy)
    }

    @Test
    fun altDrag_should_DuplicateAndSelectCopies_OnlyWhereEnabled() {
        val scene = sceneOfElements(a)
        val ctx = testToolContext(scene)
        val alt = Modifiers(alt = true)
        val r = runScenario(SelectTool(), ctx) { down(10, 10, mods = alt); up(60, 10, mods = alt) }
        val cmd = assertIs<DuplicateSelectionCommand>(r.commands.single())
        assertEquals(50.0, cmd.dx)
        val history = commitToolCommand(SketchDocumentV2(scene), cmd)
        assertEquals(2, history.present.scene.size)
        assertEquals(cmd.idMap.values.map { it.value }, selected(r.finalTool))
        val linux = runScenario(SelectTool(altDragDuplicates = false), ctx) { down(10, 10, mods = alt); up(60, 10, mods = alt) }
        assertIs<MoveSelectionCommand>(linux.commands.single())
    }

    @Test
    fun lockedMember_should_BeSkippedWithANotice_When_SelectionIsMixed() {
        val locked = filledRectEl("locked", 100, 0, 40, 40, RectSpec(locked = true))
        val ctx = testToolContext(sceneOfElements(a, locked))
        val tool = SelectTool(selection = listOf(elId("a"), elId("locked")))
        val r = runScenario(tool, ctx) { down(10, 10); up(30, 10) }
        val cmd = assertIs<MoveSelectionCommand>(r.commands.single())
        assertEquals(listOf(elId("a")), cmd.ids)
        assertEquals(ToolNotice.LockedSkipped(1), r.notices.last())
    }

    @Test
    fun drag_should_JoinAndLeaveFrames_InTheSameStep() {
        val frame = FrameElement(ElementBase(elId("f")), su(200), su(0), su(100), su(100))
        val inside = filledRectEl("m", 0, 0, 40, 40)
        val scene = sceneOfElements(frame, inside)
        val ctx = testToolContext(scene)
        val drop = runScenario(SelectTool(), ctx) { down(10, 10); up(220, 20) }
        val join = assertIs<MoveSelectionCommand>(drop.commands.single())
        assertEquals(mapOf(elId("m") to elId("f")), join.frameChanges)
        val moved = commitToolCommand(SketchDocumentV2(scene), join).present
        assertEquals(elId("f"), moved.scene[elId("m")]?.base?.frameId)

        val ctx2 = testToolContext(moved.scene)
        val out = runScenario(SelectTool(), ctx2) { down(220, 20); up(10, 10) }
        val leave = assertIs<MoveSelectionCommand>(out.commands.single())
        assertEquals(mapOf(elId("m") to null), leave.frameChanges)
        assertNull(commitToolCommand(moved, leave).present.scene[elId("m")]?.base?.frameId)
    }

    // ---- cancel (UX-08) --------------------------------------------------------------------------------------------

    @Test
    fun cancelRules_should_AbandonGestureWithNoCommand() {
        val ctx = testToolContext(sceneOfElements(a))
        val esc = runScenario(SelectTool(), ctx) { down(10, 10); move(50, 50); escape() }
        val second = runScenario(SelectTool(), ctx) { down(10, 10); move(50, 50); down(80, 80, pointer = 1) }
        val platform = runScenario(SelectTool(), ctx) { down(10, 10); move(50, 50); cancel() }
        for (r in listOf(esc, second, platform)) {
            assertTrue(r.commands.isEmpty())
            assertNull(r.previews.last())
            assertEquals(listOf("a"), selected(r.finalTool))
        }
    }

    // ---- transforms ----------------------------------------------------------------------------------------------

    @Test
    fun resizeRightHandle_should_ScaleBothAboutLeftEdge_AndUndoInOneStep() {
        val scene = sceneOfElements(a, b)
        val ctx = testToolContext(scene, zoom = 2.0)
        val tool = SelectTool(selection = listOf(elId("a"), elId("b")))
        val handles = assertNotNull(tool.handleLayout(ctx))
        assertEquals(HandleTier.Full, handles.tier)
        val r = runScenario(tool, ctx) { down(100, 20); move(150, 20); up(200, 20) }
        val cmd = assertIs<TransformSelectionCommand>(r.commands.single())
        val doc = SketchDocumentV2(scene)
        val history = commitToolCommand(doc, cmd)
        assertEquals(listOf(0.0, 80.0), listOf(rectOf(history.present, "a").x.value, rectOf(history.present, "a").width.value))
        assertEquals(listOf(120.0, 80.0), listOf(rectOf(history.present, "b").x.value, rectOf(history.present, "b").width.value))
        assertEquals(40.0, rectOf(history.present, "a").height.value)
        assertEquals(doc, history.undo().present)
        assertEquals(1, history.undoDepth)
    }

    @Test
    fun resize_should_KeepRotation_And_AspectWithShift_And_NeverCollapse() {
        val rotated = RectElement(ElementBase(elId("r"), style = a.base.style), su(0), su(0), su(100), su(50), angle = PI / 2.0)
        val box = Box(0.0, 0.0, 100.0, 50.0)
        // The element's local +x points down the screen once turned a quarter circle, so a world +y drag grows its width.
        val grown = TransformMath.resize(box, PI / 2.0, Handle.E, 0.0, 20.0, keepAspect = false, fromCentre = false)
        assertEquals(120.0, grown.width, 1e-9)
        assertEquals(50.0, grown.height, 1e-9)
        val square = TransformMath.resize(box, 0.0, Handle.SE, 50.0, 0.0, keepAspect = true, fromCentre = false)
        assertEquals(square.width / square.height, 2.0, 1e-9)
        val pinned = TransformMath.resize(box, 0.0, Handle.E, -500.0, 0.0, keepAspect = false, fromCentre = false)
        assertEquals(TransformMath.MIN_SIDE, pinned.width)
        assertEquals(RectElement::class, rotated::class)
    }

    @Test
    fun resizeFromOppositeHandle_should_KeepTheAnchorFixed_ForARotatedBox() {
        val box = Box(0.0, 0.0, 100.0, 40.0)
        val angle = PI / 6.0
        val before = anchor(box, angle, Handle.NW)
        val grown = TransformMath.resize(box, angle, Handle.SE, 15.0, 9.0, keepAspect = false, fromCentre = false)
        val after = anchor(grown, angle, Handle.NW)
        assertEquals(before.first, after.first, 1e-9)
        assertEquals(before.second, after.second, 1e-9)
    }

    private fun anchor(b: Box, angle: Double, h: Handle): Pair<Double, Double> =
        TransformMath.rotatePoint(b.cx, b.cy, b.cx + h.sx * b.width / 2.0, b.cy + h.sy * b.height / 2.0, angle)

    @Test
    fun rotateHandle_should_SetAbsoluteAngle_And_ShiftSnapsTo15Degrees() {
        val big = filledRectEl("big", 0, 0, 200, 100)
        val ctx = testToolContext(sceneOfElements(big))
        val tool = SelectTool(selection = listOf(elId("big")))
        val rotate = assertNotNull(tool.handleLayout(ctx)).handles.single { it.handle == Handle.Rotate }
        val sx = rotate.centre.x.value.toInt()
        val sy = rotate.centre.y.value.toInt()
        // From straight above the centre (100, 50) to a point to its right: a quarter turn.
        val quarter = runScenario(tool, ctx) { down(sx, sy); up(300, 50) }
        val step = (assertIs<TransformSelectionCommand>(quarter.commands.single()).steps.single() as ElementTransform.Fit)
        assertEquals(PI / 2.0, step.angle!!, 1e-9)
        val snapped = runScenario(tool, ctx) { down(sx, sy); up(300, 90, mods = Modifiers(shift = true)) }
        val snapStep = (assertIs<TransformSelectionCommand>(snapped.commands.single()).steps.single() as ElementTransform.Fit)
        assertEquals(0.0, (snapStep.angle!! / TransformMath.ROTATE_SNAP) % 1.0, 1e-9)
    }

    @Test
    fun rotation_should_BeUnavailable_When_SelectionHoldsALine() {
        val line = dev.stapler.stelekit.sketch.v2.scene.LineElement(ElementBase(elId("l")), listOf(sp(0, 0), sp(200, 100)))
        val ctx = testToolContext(sceneOfElements(a, line))
        val layout = assertNotNull(SelectTool(selection = listOf(elId("a"), elId("l"))).handleLayout(ctx))
        assertTrue(layout.handles.none { it.handle == Handle.Rotate })
    }

    // ---- host verbs ----------------------------------------------------------------------------------------------

    @Test
    fun duplicate_should_OffsetCopies_AndSelectThem() {
        val scene = sceneOfElements(a)
        val ctx = testToolContext(scene)
        val res = SelectTool(selection = listOf(elId("a"))).duplicate(ctx)
        val cmd = assertIs<DuplicateSelectionCommand>(res.command)
        assertEquals(SelectTool.DUPLICATE_OFFSET, cmd.dx)
        assertEquals(2, commitToolCommand(SketchDocumentV2(scene), cmd).present.scene.size)
        assertEquals(cmd.idMap.values.map { it.value }, selected(assertNotNull(res.next)))
    }

    @Test
    fun delete_should_RemoveEditableElements_AndSkipLockedWithNotice() {
        val locked = filledRectEl("locked", 100, 0, 40, 40, RectSpec(locked = true))
        val scene = sceneOfElements(a, locked)
        val ctx = testToolContext(scene)
        val res = SelectTool(selection = listOf(elId("a"), elId("locked"))).delete(ctx)
        val cmd = assertIs<DeleteSelectionCommand>(res.command)
        assertEquals(listOf(elId("a")), cmd.ids)
        assertEquals(ToolNotice.LockedSkipped(1), res.notice)
        assertEquals(1, commitToolCommand(SketchDocumentV2(scene), cmd).present.scene.size)
        assertEquals(emptyList(), selected(assertNotNull(res.next)))
    }

    @Test
    fun verbs_should_DoNothing_When_NothingIsEditable() {
        val locked = filledRectEl("locked", 0, 0, 40, 40, RectSpec(locked = true))
        val ctx = testToolContext(sceneOfElements(locked))
        val tool = SelectTool(selection = listOf(elId("locked")))
        assertSame(ToolResult.Nothing, tool.delete(ctx))
        assertSame(ToolResult.Nothing, tool.duplicate(ctx))
        assertSame(ToolResult.Nothing, tool.flip(ctx, horizontal = true))
    }

    @Test
    fun flipHorizontal_should_MirrorAboutTheSelectionCentre() {
        val scene = sceneOfElements(a, b)
        val ctx = testToolContext(scene)
        val res = SelectTool(selection = listOf(elId("a"), elId("b"))).flip(ctx, horizontal = true)
        val doc = commitToolCommand(SketchDocumentV2(scene), assertNotNull(res.command)).present
        assertEquals(60.0, rectOf(doc, "a").x.value)
        assertEquals(0.0, rectOf(doc, "b").x.value)
    }

    @Test
    fun escape_should_ClearSelection_When_Idle() {
        val ctx = testToolContext(sceneOfElements(a))
        val tool = SelectTool(selection = listOf(elId("a")))
        val r = tool.onEvent(InputEvent.escape(), ctx)
        assertEquals(emptyList(), selected(assertNotNull(r.next)))
        assertSame(ToolResult.Nothing, SelectTool().onEvent(InputEvent.escape(), ctx))
    }

    @Test
    fun scene_should_BeUntouched_While_Gesture_IsInProgress() {
        val scene = sceneOfElements(a)
        val ctx = testToolContext(scene)
        runScenario(SelectTool(), ctx) { down(10, 10); move(50, 50) }
        assertSame(scene, ctx.scene)
        val e: SceneElement = scene[elId("a")]!!
        assertEquals(0.0, (e as RectElement).x.value)
    }
}
