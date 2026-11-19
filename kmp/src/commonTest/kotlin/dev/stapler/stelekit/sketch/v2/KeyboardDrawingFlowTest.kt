// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.AddElementCommand
import dev.stapler.stelekit.sketch.v2.tools.AddFrameCommand
import dev.stapler.stelekit.sketch.v2.tools.ArcTool
import dev.stapler.stelekit.sketch.v2.tools.ArrowTool
import dev.stapler.stelekit.sketch.v2.tools.DiamondTool
import dev.stapler.stelekit.sketch.v2.tools.EllipseTool
import dev.stapler.stelekit.sketch.v2.tools.FrameTool
import dev.stapler.stelekit.sketch.v2.tools.LineTool
import dev.stapler.stelekit.sketch.v2.tools.PointTool
import dev.stapler.stelekit.sketch.v2.tools.RectangleTool
import dev.stapler.stelekit.sketch.v2.tools.TextTool
import dev.stapler.stelekit.sketch.v2.tools.Tool
import dev.stapler.stelekit.sketch.v2.tools.ToolContext
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sceneOfElements
import dev.stapler.stelekit.sketch.v2.tools.testToolContext
import dev.stapler.stelekit.ui.sketch.v2.CrosshairDrawing
import dev.stapler.stelekit.ui.sketch.v2.CrosshairKey
import dev.stapler.stelekit.ui.sketch.v2.EscOrder
import dev.stapler.stelekit.ui.sketch.v2.EscRow
import dev.stapler.stelekit.ui.sketch.v2.KeyAction
import dev.stapler.stelekit.ui.sketch.v2.KeyContext
import dev.stapler.stelekit.ui.sketch.v2.KeyDispatcher
import dev.stapler.stelekit.ui.sketch.v2.KeyOutcome
import dev.stapler.stelekit.ui.sketch.v2.KeyPress
import dev.stapler.stelekit.ui.sketch.v2.KeyboardDrawingPolicy
import dev.stapler.stelekit.ui.sketch.v2.KeyboardRegions
import dev.stapler.stelekit.ui.sketch.v2.KeymapEnv
import dev.stapler.stelekit.ui.sketch.v2.ShortcutsSheetModel
import dev.stapler.stelekit.ui.sketch.v2.StripPlacement
import dev.stapler.stelekit.ui.sketch.v2.TabStop
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 3.6.3c (UX-107, UX-41, UX-105; WCAG 2.1.1): every drawing tool is walked with the keyboard alone through Crosshair mode,
 * Esc backs out one row at a time, the tab order is the one list of ux.md section 10 at every layout, and the Freedraw
 * exemption is stated on the shortcuts sheet.
 */
class KeyboardDrawingFlowTest {
    private fun at(x: Int, y: Int) = ScenePoint(SceneUnit.ofInt(x), SceneUnit.ofInt(y))

    private val right = CrosshairKey.Move(1, 0, far = false)
    private val farRight = CrosshairKey.Move(1, 0, far = true)
    private val farDown = CrosshairKey.Move(0, 1, far = true)

    /** A keyboard-only session: keys go in one at a time, the commands the tool emitted come out. */
    private class Walk(tool: Tool, start: ScenePoint, private val ctx: ToolContext) {
        var session: CrosshairDrawing? = CrosshairDrawing.begin(tool, start)
        val commands = mutableListOf<SceneElement>()
        val announcements = mutableListOf<String>()
        val escRows = mutableListOf<EscRow>()
        private var clock = 0L

        fun press(vararg keys: CrosshairKey) = keys.forEach { key ->
            val step = (session ?: error("Crosshair already ended")).press(key, ctx, clock)
            clock += CLOCK_STEP_MS
            session = step.session
            when (val command = step.command) {
                is AddElementCommand -> commands += command.element
                is AddFrameCommand -> commands += command.frame
                else -> Unit
            }
            step.announcement?.let { announcements += it }
            step.escRow?.let { escRows += it }
        }

        fun times(n: Int, key: CrosshairKey) = repeat(n) { press(key) }

        private companion object {
            const val CLOCK_STEP_MS = 1_000L
        }
    }

    private fun walk(tool: Tool, start: ScenePoint = at(0, 0), ctx: ToolContext = testToolContext()) = Walk(tool, start, ctx)

    // ---- the per-tool walks ---------------------------------------------------------------------------------------

    @Test
    fun rectangle_should_BeCommittedByTwoEnters_And_AnnouncedByName() {
        val w = walk(RectangleTool())
        w.press(CrosshairKey.Enter)
        w.times(5, farRight)
        w.times(3, farDown)
        w.press(CrosshairKey.Enter)
        val rect = assertIs<RectElement>(w.commands.single())
        assertEquals(50.0, rect.width.value)
        assertEquals(30.0, rect.height.value)
        val named = rect.copy(base = rect.base.copy(ordinal = 3))
        assertEquals("Added Rectangle 3", CrosshairDrawing.announceAdded(named))
        assertNotNull(w.session, "Crosshair stays on for the next shape")
    }

    @Test
    fun diamondEllipseAndFrame_should_FollowTheSameCornerRule() {
        for ((tool, type) in listOf<Pair<Tool, KClass<*>>>(DiamondTool() to DiamondElement::class, EllipseTool() to EllipseElement::class, FrameTool() to FrameElement::class)) {
            val w = walk(tool)
            w.press(CrosshairKey.Enter)
            w.times(2, farRight)
            w.times(2, farDown)
            w.press(CrosshairKey.Enter)
            assertEquals(type, w.commands.single()::class, "${tool.id}")
        }
    }

    @Test
    fun shiftArrow_should_MoveTenUnits_And_PlainArrowOne() {
        val w = walk(RectangleTool())
        w.press(right)
        assertEquals(1.0, w.session?.crosshair?.x?.value)
        w.press(farRight)
        assertEquals(11.0, w.session?.crosshair?.x?.value)
        assertTrue(w.announcements.last().startsWith("Crosshair at"))
    }

    @Test
    fun line_should_FinishOnEnterTwice_AtTheLastPoint() {
        val w = walk(LineTool())
        w.press(CrosshairKey.Enter)
        w.times(4, farRight)
        w.press(CrosshairKey.Enter)
        w.times(4, farDown)
        w.press(CrosshairKey.Enter, CrosshairKey.Enter)
        val line = assertIs<LineElement>(w.commands.single())
        assertEquals(3, line.points.size)
        assertFalse(w.session!!.midDraw)
    }

    @Test
    fun point_should_BePlacedByOneEnter() {
        val w = walk(PointTool(), at(7, 9))
        w.press(CrosshairKey.Enter)
        val point = assertIs<PointElement>(w.commands.single())
        assertEquals(7.0, point.x.value)
    }

    @Test
    fun arc_should_TakeThreePoints_And_RefuseThreeCollinearOnes() {
        val ok = walk(ArcTool(), at(0, 0))
        ok.press(CrosshairKey.Enter)
        ok.times(5, farRight)
        ok.times(3, farDown)
        ok.press(CrosshairKey.Enter)
        ok.times(5, farRight)
        ok.times(3, CrosshairKey.Move(0, -1, far = true))
        ok.press(CrosshairKey.Enter)
        assertIs<ArcElement>(ok.commands.single())

        val flat = walk(ArcTool(), at(0, 0))
        flat.press(CrosshairKey.Enter)
        flat.times(5, farRight)
        flat.press(CrosshairKey.Enter)
        flat.times(5, farRight)
        flat.press(CrosshairKey.Enter)
        assertTrue(flat.commands.isEmpty())
        assertTrue(flat.announcements.contains(SketchStrings.KeyboardDrawing.NOTHING_DRAWN))
    }

    @Test
    fun arrow_should_BindToAShape_When_EnterIsPressedOnIt() {
        val scene = sceneOfElements(filledRectEl("r1", 0, 0, 100, 50), filledRectEl("r2", 300, 0, 100, 50))
        val w = walk(ArrowTool(), at(100, 25), testToolContext(scene))
        w.press(CrosshairKey.Enter)
        w.times(20, farRight)
        w.press(CrosshairKey.Enter, CrosshairKey.Enter)
        val arrow = assertIs<ArrowElement>(w.commands.single())
        assertEquals(ElementId("r1"), arrow.startBinding?.elementId, "Enter on a shape binds")
        assertEquals(ElementId("r2"), arrow.endBinding?.elementId)
    }

    @Test
    fun text_should_OpenTheEditorOnEnter_And_EscCommitsIt() {
        val w = walk(TextTool(FixedAdvanceTextMetricsProvider()), at(10, 10))
        w.press(CrosshairKey.Enter)
        assertTrue(w.session!!.editingText)
        assertTrue(EscRow.TextOverlayOrDimensionField in w.session!!.escRows())
        w.press(CrosshairKey.Escape)
        assertEquals(listOf(EscRow.TextOverlayOrDimensionField), w.escRows)
        assertFalse(w.session!!.editingText)
        assertNotNull(w.session, "committing text does not leave Crosshair")
        assertTrue(w.commands.all { it is TextElement })
    }

    // ---- Esc order ------------------------------------------------------------------------------------------------

    @Test
    fun esc_should_CancelTheShapeFirst_ThenLeaveCrosshair_WithNoCommand() {
        val w = walk(RectangleTool())
        w.press(CrosshairKey.Enter)
        w.times(3, farRight)
        w.press(CrosshairKey.Escape)
        assertEquals(listOf(EscRow.MidDraw), w.escRows)
        assertTrue(w.announcements.contains(SketchStrings.KeyboardDrawing.CANCELLED))
        assertNotNull(w.session)
        assertFalse(w.session!!.midDraw)
        w.press(CrosshairKey.Escape)
        assertEquals(listOf(EscRow.MidDraw, EscRow.Crosshair), w.escRows)
        assertNull(w.session)
        assertTrue(w.commands.isEmpty(), "Esc at any point commits nothing")
    }

    @Test
    fun crosshairRows_should_ResolveInTheOrderOfTheEscTable() {
        val w = walk(LineTool())
        w.press(CrosshairKey.Enter)
        assertEquals(listOf(EscRow.MidDraw, EscRow.Crosshair), EscOrder.drain(w.session!!.escRows()))
        assertTrue(EscRow.MidDraw.rank < EscRow.Crosshair.rank)
    }

    // ---- who may draw -----------------------------------------------------------------------------------------------

    @Test
    fun freedrawSelectAndFriends_should_HaveNoKeyboardPath_Except_TheStatedExemption() {
        assertNull(CrosshairDrawing.begin(dev.stapler.stelekit.sketch.v2.tools.FreedrawTool(), at(0, 0)))
        assertEquals(ToolId.Freedraw, KeyboardDrawingPolicy.exempt)
        assertEquals(
            setOf(ToolId.Rectangle, ToolId.Diamond, ToolId.Ellipse, ToolId.Frame, ToolId.Line, ToolId.Arrow, ToolId.Point, ToolId.Arc, ToolId.Text),
            KeyboardDrawingPolicy.tools.toSet(),
        )
    }

    @Test
    fun shortcutsSheet_should_StateTheFreedrawExemption() {
        val model = ShortcutsSheetModel.of(KeymapEnv(isMac = false, isWeb = false), singleKeyEnabled = true)
        assertTrue(model.sections.any { SketchStrings.Shortcuts.FREEDRAW_EXEMPTION in it.notes })
    }

    // ---- key routing -----------------------------------------------------------------------------------------------

    private fun ctx(crosshair: Boolean, selection: Boolean = true) = KeyContext(
        env = KeymapEnv(isMac = false, isWeb = false),
        singleKeyEnabled = true,
        canvasFocused = true,
        textInputOrDialogFocused = false,
        hasSelection = selection,
        crosshairActive = crosshair,
    )

    @Test
    fun arrows_should_MoveTheCrosshair_NotTheSelection_WhileCrosshairIsActive() {
        val shifted = KeyDispatcher.dispatch(KeyPress("ArrowRight", shift = true), ctx(crosshair = true))
        assertEquals(KeyOutcome.Handled(KeyAction.Crosshair(farRight)), shifted)
        val plain = KeyDispatcher.dispatch(KeyPress("ArrowRight"), ctx(crosshair = false))
        assertEquals(KeyOutcome.Handled(KeyAction.Nudge(1, 0, far = false)), plain)
        assertEquals(KeyOutcome.Handled(KeyAction.Crosshair(CrosshairKey.Enter)), KeyDispatcher.dispatch(KeyPress("Enter"), ctx(crosshair = true)))
        assertEquals(KeyOutcome.Handled(KeyAction.Crosshair(CrosshairKey.Escape)), KeyDispatcher.dispatch(KeyPress("Escape"), ctx(crosshair = true)))
    }

    @Test
    fun chords_should_StillWork_WhileCrosshairIsActive() {
        val undo = KeyDispatcher.dispatch(KeyPress("z", ctrl = true), ctx(crosshair = true))
        assertEquals(KeyOutcome.Handled(KeyAction.Undo), undo)
    }

    // ---- tab order ------------------------------------------------------------------------------------------------

    private val sectionTen = listOf(
        TabStop.TopBar, TabStop.NoticeRegion, TabStop.ToolStrip, TabStop.Canvas, TabStop.ActionChipRow, TabStop.CrosshairPad,
        TabStop.SelectionBar, TabStop.ViewCluster, TabStop.SidePane, TabStop.NoticesList, TabStop.StatusLine,
    )

    /** The stops each layout of the chrome table has: the compact-height rail has no selection bar (the top-bar menu holds its actions). */
    private fun presentFor(placement: StripPlacement, crosshair: Boolean, selection: Boolean, chips: Boolean): Set<TabStop> = buildSet {
        addAll(setOf(TabStop.TopBar, TabStop.ToolStrip, TabStop.Canvas, TabStop.ViewCluster, TabStop.SidePane, TabStop.StatusLine))
        if (crosshair) add(TabStop.CrosshairPad)
        if (chips) add(TabStop.ActionChipRow)
        if (selection && placement == StripPlacement.BottomDock) add(TabStop.SelectionBar)
    }

    @Test
    fun tabOrder_should_BeTheOneListOfSectionTen_AtEveryLayout() {
        assertEquals(sectionTen, TabStop.entries.toList())
        val layouts = StripPlacement.entries.flatMap { p -> listOf(false, true).flatMap { c -> listOf(false, true).flatMap { s -> listOf(false, true).map { k -> listOf(p, c, s, k) } } } }
        for (layout in layouts) {
            val placement = layout[0] as StripPlacement
            val crosshair = layout[1] as Boolean
            val selection = layout[2] as Boolean
            val chips = layout[3] as Boolean
            val present = presentFor(placement, crosshair, selection, chips)
            val order = KeyboardRegions.tabOrder(present)
            assertEquals(sectionTen.filter { it in present }, order, "$placement crosshair=$crosshair selection=$selection chips=$chips")
            assertEquals(order.size, order.toSet().size, "no stop twice")
            assertTrue(crosshair == (TabStop.CrosshairPad in order), "the pad is a stop only while Crosshair mode is active")
        }
    }

    @Test
    fun tabWalk_should_NeverTrap_WhenWalkedForwardOrBack() {
        val present = presentFor(StripPlacement.BottomDock, crosshair = true, selection = true, chips = true) + setOf(TabStop.NoticeRegion, TabStop.NoticesList)
        val order = KeyboardRegions.tabOrder(present)
        assertEquals(TabStop.entries.toList(), order, "every stop is reachable by Tab, and Shift+Tab walks the same list reversed")
        assertEquals(TabStop.SelectionBar, KeyboardRegions.skipCanvasTarget(present - TabStop.ActionChipRow - TabStop.CrosshairPad))
        assertEquals(TabStop.CrosshairPad, KeyboardRegions.skipCanvasTarget(present - TabStop.ActionChipRow))
    }
}
