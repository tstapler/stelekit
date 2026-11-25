// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningKind
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.sketch.v2.scene.WallJustification
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKinds
import dev.stapler.stelekit.sketch.v2.snap.SnapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wall tool chains walls and closes rooms; the opening tools place doors, windows and gaps on a wall. */
class WallToolTest {
    private fun tap(b: ScenarioBuilder, x: Int, y: Int) {
        b.down(x, y)
        b.up(x, y)
    }

    private fun walls(r: ScenarioResult) = r.commands.filterIsInstance<AddElementCommand>().map { it.element }.filterIsInstance<WallElement>()

    private fun xy(w: WallElement) = w.points.map { it.x.value.toInt() to it.y.value.toInt() }

    @Test
    fun taps_should_ChainWalls_And_AnEnterOnTheLastVertexFinishesThem() {
        val tool = WallTool()
        val r = runScenario(tool) {
            tap(this, 0, 0)
            tap(this, 200, 0)
            tap(this, 200, 100)
            tap(this, 200, 100)
        }
        val wall = walls(r).single()
        assertEquals(listOf(0 to 0, 200 to 0, 200 to 100), xy(wall))
        assertTrue(!wall.closed)
        assertEquals(WallElement.DEFAULT_THICKNESS_UNITS.toDouble(), wall.thickness.value)
        assertEquals(0, wall.base.style.roughness)
        assertTrue(wall.base.style.backgroundColor != "transparent", "a new wall takes the wall fill")
    }

    @Test
    fun tappingTheFirstVertexAgain_should_CloseTheRoomAutomatically() {
        val r = runScenario(WallTool()) {
            tap(this, 0, 0)
            tap(this, 200, 0)
            tap(this, 200, 120)
            tap(this, 0, 120)
            tap(this, 1, 1)
        }
        val wall = walls(r).single()
        assertTrue(wall.closed)
        assertEquals(listOf(0 to 0, 200 to 0, 200 to 120, 0 to 120), xy(wall))
    }

    @Test
    fun tappingTheFirstVertexOfATwoVertexChain_should_NotCloseIt() {
        val r = runScenario(WallTool()) {
            tap(this, 0, 0)
            tap(this, 200, 0)
            tap(this, 0, 0)
        }
        assertTrue(walls(r).isEmpty() || !walls(r).single().closed)
    }

    @Test
    fun aTap_should_ShowTheChainSoFar_WithoutAPointerMove() {
        val r = runScenario(WallTool()) {
            tap(this, 0, 0)
            tap(this, 200, 0)
            tap(this, 200, 120)
        }
        val shown = r.previews.filterNotNull()
        assertTrue(shown.first().elements.single() is dev.stapler.stelekit.sketch.v2.scene.PointElement, "one vertex shows a marker")
        val chain = shown.last().elements.single() as WallElement
        assertEquals(listOf(0 to 0, 200 to 0, 200 to 120), xy(chain))
    }

    @Test
    fun aDrag_should_DrawOneWall_InOneGesture() {
        val r = runScenario(WallTool()) {
            down(0, 0)
            move(100, 0)
            up(300, 0)
        }
        assertEquals(listOf(0 to 0, 300 to 0), xy(walls(r).single()))
    }

    @Test
    fun withLength_should_PlaceTheNextVertexExactlyThatFarAlongThePointerHeading() {
        var tool: Tool = WallTool()
        val ctx = testToolContext()
        for (e in listOf(0 to 0)) tool = tool.onEvent(down(e.first, e.second), ctx).next ?: tool
        tool = tool.onEvent(up(0, 0), ctx).next ?: tool
        tool = tool.onEvent(move(50, 0), ctx).next ?: tool
        val typed = (tool as WallTool).withLength(1234.0, ctx)
        val next = assertNotNull(typed.next) as WallTool
        assertEquals(listOf(0.0 to 0.0, 1234.0 to 0.0), next.placedPoints.map { it.x.value to it.y.value })
        val done = next.done(ctx)
        assertEquals(1234.0, WallGeometry.length(assertIs<WallElement>((done.command as AddElementCommand).element)), 1e-9)
    }

    @Test
    fun withLength_should_IgnoreANonPositiveLength_And_AnEmptyChain() {
        val ctx = testToolContext()
        assertEquals(ToolResult.Nothing, WallTool().withLength(10.0, ctx))
        val started = WallTool().onEvent(down(0, 0), ctx).next as WallTool
        assertEquals(ToolResult.Nothing, started.withLength(0.0, ctx))
        assertEquals(ToolResult.Nothing, started.withLength(-4.0, ctx))
    }

    @Test
    fun escape_should_AbandonTheChain_WithNoCommand() {
        val r = runScenario(WallTool()) {
            tap(this, 0, 0)
            tap(this, 100, 0)
            escape()
        }
        assertTrue(r.commands.isEmpty())
        assertTrue((r.finalTool as WallTool).placedPoints.isEmpty())
    }

    @Test
    fun angleSnapOption_should_LockTheNextWallToFifteenDegrees() {
        val r = runScenario(WallTool(WallOptions(angleSnap = true))) {
            tap(this, 0, 0)
            down(100, 7)
            up(100, 7)
            tap(this, 100, 0)
        }
        val ys = xy(walls(r).single()).map { it.second }
        assertEquals(listOf(0, 0), ys, "a slightly sloped click snaps to horizontal")
    }

    @Test
    fun snapping_should_LockAWallStartOntoAnExistingWallEndpointAndMidpoint() {
        val existing = WallElement(ElementBase(elId("w0")), listOf(sp(0, 0), sp(200, 0)), su(8))
        val scene = sceneOfElements(existing)
        val ctx = testToolContext(scene).copy(snap = SnapSettings(points = PointSnapKinds.All))
        val r = runScenario(WallTool(), ctx) {
            tap(this, 197, 3)
            tap(this, 197, 120)
            tap(this, 197, 120)
        }
        assertEquals(listOf(200 to 0, 197 to 120), xy(walls(r).single()), "the first tap snaps onto the endpoint, the second has nothing to lock onto")
        val mid = runScenario(WallTool(), ctx) {
            tap(this, 102, 2)
            tap(this, 102, 90)
            tap(this, 102, 90)
        }
        assertEquals(100, xy(walls(mid).single()).first().first)
    }

    @Test
    fun aWallWithJustificationOptions_should_CarryThemToTheElement() {
        val r = runScenario(WallTool(WallOptions(thickness = 40.0, justification = WallJustification.Inside))) {
            down(0, 0)
            up(200, 0)
        }
        val wall = walls(r).single()
        assertEquals(40.0, wall.thickness.value)
        assertEquals(WallJustification.Inside, wall.justification)
    }

    // ---- openings --------------------------------------------------------------------------------------------------

    private val hostWall = WallElement(ElementBase(elId("w1"), style = ElementStyle()), listOf(sp(0, 0), sp(600, 0), sp(600, 400), sp(0, 400)), su(8), closed = true)

    private fun placed(r: ScenarioResult) = r.commands.filterIsInstance<AddElementCommand>().map { it.element }.filterIsInstance<OpeningElement>()

    @Test
    fun doorTool_should_PlaceADoorCentredOnTheWall_AndSwingItToThePressedSide() {
        val ctx = testToolContext(sceneOfElements(hostWall))
        val outsideTop = runScenario(PlanTools.create(PlanMode.Door), ctx) {
            down(300, -3)
            up(300, -3)
        }
        val door = placed(outsideTop).single()
        assertEquals(OpeningKind.Door, door.kind)
        assertEquals(elId("w1"), door.hostId)
        assertEquals(0, door.segment)
        assertEquals(OpeningElement.DEFAULT_DOOR_UNITS.toDouble(), door.width.value)
        assertEquals(300.0, door.offset.value + door.width.value / 2.0, 1e-9)
        val inside = runScenario(PlanTools.create(PlanMode.Door), ctx) {
            down(300, 3)
            up(300, 3)
        }
        assertTrue(placed(inside).single().flipSide != door.flipSide, "pressing the other face swings the door to the other side")
    }

    @Test
    fun doorTool_should_KeepTheDoorInsideTheWallNearACorner() {
        val ctx = testToolContext(sceneOfElements(hostWall))
        val r = runScenario(PlanTools.create(PlanMode.Window), ctx) {
            down(2, 0)
            up(2, 0)
        }
        val window = placed(r).single()
        val span = assertNotNull(WallGeometry.span(hostWall, 0))
        assertTrue(window.offset.value >= span.lo - 1e-9 && window.offset.value + window.width.value <= span.hi + 1e-9)
        assertEquals(OpeningKind.Window, window.kind)
    }

    @Test
    fun openingTools_should_PlaceNothing_When_ThePressIsOffEveryWall() {
        val ctx = testToolContext(sceneOfElements(hostWall))
        val r = runScenario(PlanTools.create(PlanMode.Opening), ctx) {
            down(300, 200)
            up(300, 200)
        }
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun openingPreview_should_ShowWhereTheOpeningWouldLand_BeforeItIsCommitted() {
        val ctx = testToolContext(sceneOfElements(hostWall))
        val r = runScenario(PlanTools.create(PlanMode.Door), ctx) { move(300, 2) }
        assertNotNull(r.previews.single()).also { assertTrue(it.elements.isNotEmpty()) }
        val away = runScenario(PlanTools.create(PlanMode.Door), ctx) { move(300, 200) }
        assertNull(away.previews.single())
    }

    @Test
    fun planModes_should_AllLiveUnderTheLineToolId() {
        for (mode in PlanMode.entries) assertEquals(ToolId.Line, PlanTools.create(mode).id)
        assertIs<LineTool>(PlanTools.create(PlanMode.Line))
        assertIs<WallTool>(PlanTools.create(PlanMode.Wall))
        assertEquals(SketchSceneV2.empty().size, 0)
    }

    private fun down(x: Int, y: Int) = event(InputType.Down, x, y)

    private fun up(x: Int, y: Int) = event(InputType.Up, x, y)

    private fun move(x: Int, y: Int) = event(InputType.Move, x, y)

    private fun event(type: InputType, x: Int, y: Int) = InputEvent(type, sp(x, y))
}
