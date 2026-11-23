// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.SnapKind
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.snap.GuideAxis
import dev.stapler.stelekit.sketch.v2.snap.QuarterTurn
import dev.stapler.stelekit.sketch.v2.snap.ResizeSides
import dev.stapler.stelekit.sketch.v2.snap.SnapGuide
import dev.stapler.stelekit.sketch.v2.snap.SnapSettings
import dev.stapler.stelekit.sketch.v2.snap.SnapTicker
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Follow-ups to resize snapping: quarter-turn frames, guide lines and handles on the preview, the touch bypass and the haptic tick rule. */
class SnapFollowupsTest {
    private val objects = SnapSettings(objects = true)

    private fun ctx(scene: SketchSceneV2, snap: SnapSettings = objects) = testToolContext(scene).copy(snap = snap)

    private fun turned(id: String, angle: Double): RectElement = filledRectEl(id, 0, 0, 200, 120).copy(angle = angle)

    private fun select(id: String) = SelectTool(selection = listOf(elId(id)))

    private fun committed(r: ScenarioResult, scene: SketchSceneV2, id: String): RectElement {
        val history = commitToolCommand(SketchDocumentV2(scene), assertIs<TransformSelectionCommand>(r.commands.single()))
        return history.present.scene[elId(id)] as RectElement
    }

    // ---- quarter turns ---------------------------------------------------------------------------------------------

    @Test
    fun turnsOf_should_AcceptOnlyMultiplesOfAQuarterTurn() {
        assertEquals(0, QuarterTurn.turnsOf(0.0))
        assertEquals(1, QuarterTurn.turnsOf(PI / 2))
        assertEquals(2, QuarterTurn.turnsOf(PI))
        assertEquals(2, QuarterTurn.turnsOf(-PI))
        assertEquals(3, QuarterTurn.turnsOf(-PI / 2))
        assertEquals(1, QuarterTurn.turnsOf(PI / 2 + 1e-9), "float noise after normalising an angle still counts")
        assertNull(QuarterTurn.turnsOf(0.3))
        assertNull(QuarterTurn.turnsOf(PI / 4))
        assertNull(QuarterTurn.turnsOf(Double.NaN))
    }

    @Test
    fun align_should_SwapAxesForOddTurns_AndMapTheHandleSidesToWorldSides() {
        val east = ResizeSides(1, 0)
        val half = QuarterTurn.align(100.0, 60.0, 200.0, 120.0, PI / 2, east)
        val quarter = assertNotNull(half)
        assertEquals(listOf(40.0, -40.0, 160.0, 160.0), listOf(quarter.bounds.minX, quarter.bounds.minY, quarter.bounds.maxX, quarter.bounds.maxY), "90 degrees: 120 wide, 200 tall")
        assertEquals(ResizeSides(0, 1), quarter.sides, "the local east handle sits at the bottom after a quarter turn clockwise")
        assertEquals(ResizeSides(-1, 0), QuarterTurn.align(100.0, 60.0, 200.0, 120.0, PI, east)?.sides)
        assertEquals(ResizeSides(0, -1), QuarterTurn.align(100.0, 60.0, 200.0, 120.0, -PI / 2, east)?.sides)
        assertEquals(ResizeSides(-1, 1), QuarterTurn.align(100.0, 60.0, 200.0, 120.0, PI / 2, ResizeSides(1, 1))?.sides, "the south-east corner is at the bottom left after a quarter turn")
        assertNull(QuarterTurn.align(100.0, 60.0, 200.0, 120.0, 0.5, east))
    }

    @Test
    fun quarterTurnedResize_should_SnapTheVisibleBottomEdgeToTheNeighbour() {
        val below = filledRectEl("below", 0, 200, 100, 50)
        val scene = sceneOfElements(turned("a", PI / 2), below)
        // The local east handle of a shape turned a quarter turn sits at the bottom, (100, 160): the visible bottom edge is y 160.
        val r = runScenario(select("a"), ctx(scene)) { down(100, 160); move(100, 197); up(100, 197) }
        val shape = committed(r, scene, "a")
        assertEquals(240.0, shape.width.value, "bottom edge 197 snaps to the neighbour's top (200): the long side grows to 240")
        assertEquals(80.0, shape.y.value + shape.height.value / 2, "the top edge stays at -40, so the centre moves to y 80")
        assertEquals(PI / 2, shape.angle)
    }

    @Test
    fun halfTurnedResize_should_SnapTheVisibleLeftEdge() {
        val left = filledRectEl("left", -300, 0, 100, 120)
        val scene = sceneOfElements(turned("a", PI), left)
        val r = runScenario(select("a"), ctx(scene)) { down(0, 60); move(-197, 60); up(-197, 60) }
        val shape = committed(r, scene, "a")
        assertEquals(listOf(-200.0, 400.0), listOf(shape.x.value, shape.width.value), "visible left edge -197 snaps to -200; the right edge stays at 200")
    }

    @Test
    fun arbitraryAngle_should_StayUnsnapped_And_ShowNoGuide() {
        val right = filledRectEl("right", 300, 0, 100, 120)
        val scene = sceneOfElements(turned("a", 0.5), right)
        val snapped = runScenario(select("a"), ctx(scene)) { down(188, 108); move(293, 108); up(293, 108) }
        val ctrl = Modifiers(ctrl = true)
        val raw = runScenario(select("a"), ctx(scene)) { down(188, 108); move(293, 108, mods = ctrl); up(293, 108, mods = ctrl) }
        assertEquals(committed(raw, scene, "a"), committed(snapped, scene, "a"), "a 0.5 rad frame has no axis-aligned edge, so snapping changes nothing")
        assertTrue(snapped.previews.filterNotNull().all { it.guides.isEmpty() })
    }

    // ---- guides and handles on the preview -------------------------------------------------------------------------

    @Test
    fun resizePreview_should_CarryTheGuide_And_TheFrameOfTheSnappedBox() {
        val right = filledRectEl("right", 300, 0, 100, 120)
        val scene = sceneOfElements(filledRectEl("a", 0, 0, 200, 120), right)
        val r = runScenario(select("a"), ctx(scene)) { down(200, 60); move(296, 60); up(296, 60) }
        val shown = assertNotNull(r.previews[1])
        val guide = shown.guides.single()
        assertEquals(GuideAxis.Vertical, guide.axis)
        assertEquals(300.0, guide.position)
        assertEquals(300.0, assertNotNull(shown.frame).box.width, "handles are drawn on the snapped frame, not the pre-drag one")
    }

    @Test
    fun movePreview_should_CarryTheGuide_And_TheMovedFrame() {
        val right = filledRectEl("right", 300, 0, 100, 120)
        val scene = sceneOfElements(filledRectEl("a", 0, 0, 200, 120), right)
        val r = runScenario(select("a"), ctx(scene)) { down(100, 60); move(197, 60); up(197, 60) }
        val shown = assertNotNull(r.previews[1])
        assertTrue(shown.guides.any { it.axis == GuideAxis.Vertical && it.position == 300.0 && it.kind == SnapKind.ENDPOINT })
        assertEquals(100.0, assertNotNull(shown.frame).box.x)
    }

    @Test
    fun previewWithoutSnap_should_CarryNoGuides() {
        val scene = sceneOfElements(filledRectEl("a", 0, 0, 200, 120), filledRectEl("right", 300, 0, 100, 120))
        val r = runScenario(select("a"), ctx(scene, SnapSettings.None)) { down(200, 60); move(296, 60); up(296, 60) }
        assertTrue(assertNotNull(r.previews[1]).guides.isEmpty())
    }

    @Test
    fun shiftAxisLock_should_NotShowAGuideOnTheLockedAxis() {
        val offset = filledRectEl("right", 300, 0, 100, 120)
        val scene = sceneOfElements(filledRectEl("a", 0, 0, 200, 120), offset)
        val shift = Modifiers(shift = true)
        val r = runScenario(select("a"), ctx(scene)) { down(100, 60); move(100, 62, mods = shift); up(100, 62, mods = shift) }
        assertTrue(r.previews.filterNotNull().all { p -> p.guides.none { it.axis == GuideAxis.Vertical } }, "dx is locked at 0, so no vertical guide")
    }

    // ---- bypass ----------------------------------------------------------------------------------------------------

    @Test
    fun whileHeld_should_TurnSnappingOffOnlyWhileTheFingerRests() {
        val settings = SnapSettings(objects = true)
        assertEquals(SnapSettings.None, settings.whileHeld(true))
        assertEquals(settings, settings.whileHeld(false))
        val scene = sceneOfElements(filledRectEl("a", 0, 0, 200, 120), filledRectEl("right", 300, 0, 100, 120))
        val held = runScenario(select("a"), ctx(scene, settings.whileHeld(true))) { down(200, 60); move(296, 60); up(296, 60) }
        assertEquals(296.0, committed(held, scene, "a").width.value, "no snap while held")
        val free = runScenario(select("a"), ctx(scene, settings.whileHeld(false))) { down(200, 60); move(296, 60); up(296, 60) }
        assertEquals(300.0, committed(free, scene, "a").width.value)
    }

    // ---- haptic tick rule ------------------------------------------------------------------------------------------

    private fun guide(position: Double, kind: SnapKind = SnapKind.ENDPOINT) = SnapGuide(GuideAxis.Vertical, position, 0.0, 10.0, kind)

    @Test
    fun ticker_should_TickWhenANewTargetEngages_AndNotWhileStayingOnIt() {
        val ticker = SnapTicker(minGapMs = 80)
        assertFalse(ticker.onGuides(emptyList(), 0))
        assertTrue(ticker.onGuides(listOf(guide(300.0)), 10), "first engagement")
        assertFalse(ticker.onGuides(listOf(guide(300.0)), 200), "still on the same target")
        assertFalse(ticker.onGuides(emptyList(), 300), "leaving is silent")
        assertTrue(ticker.onGuides(listOf(guide(300.0)), 400), "engaging again after leaving ticks again")
        assertTrue(ticker.onGuides(listOf(guide(350.0)), 600), "sliding to another target ticks")
    }

    @Test
    fun ticker_should_DropTicksCloserThanTheDebounce() {
        val ticker = SnapTicker(minGapMs = 80)
        assertTrue(ticker.onGuides(listOf(guide(100.0)), 0))
        assertFalse(ticker.onGuides(listOf(guide(120.0)), 30), "30 ms after the last tick")
        assertFalse(ticker.onGuides(listOf(guide(120.0)), 200), "that target was already seen, its tick is not owed")
        assertTrue(ticker.onGuides(listOf(guide(140.0)), 300))
    }

    @Test
    fun ticker_should_TreatAGuideOfAnotherKindAsAnotherTarget() {
        val ticker = SnapTicker(minGapMs = 0)
        assertTrue(ticker.onGuides(listOf(guide(300.0, SnapKind.ENDPOINT)), 0))
        assertTrue(ticker.onGuides(listOf(guide(300.0, SnapKind.MIDPOINT)), 1))
    }
}
