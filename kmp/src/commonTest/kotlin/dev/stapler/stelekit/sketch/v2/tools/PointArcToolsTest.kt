// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.2.4 (OD-8, UX-08, UX-40): the Point and Arc tools and `ArcFromThreePoints`. */
class PointArcToolsTest {
    private val base = ElementBase(elId("arc"))

    private fun arcOf(r: ScenarioResult) = assertIs<ArcElement>(assertIs<AddElementCommand>(r.commands.single()).element)

    @Test
    fun pointClick_should_CommitOnePointAtTheClick() {
        val r = runScenario(PointTool()) { down(40, 40); up(40, 40) }
        val p = assertIs<PointElement>(assertIs<AddElementCommand>(r.commands.single()).element)
        assertEquals(40.0, p.x.value)
        assertEquals(40.0, p.y.value)
    }

    @Test
    fun pointTool_should_CancelOnEscape_Or_SecondPointer() {
        val esc = runScenario(PointTool()) { down(40, 40); escape(); up(40, 40) }
        val second = runScenario(PointTool()) { down(40, 40); down(1, 1, pointer = 1); up(40, 40) }
        assertTrue(esc.commands.isEmpty() && second.commands.isEmpty())
    }

    @Test
    fun arc_should_PassThroughStartEndAndBulge_WithPositiveRadiusAndIncreasingAngle() {
        val r = runScenario(ArcTool()) { down(0, 0); up(0, 0); down(100, 0); up(100, 0); down(50, -30); up(50, -30) }
        val arc = arcOf(r)
        assertTrue(arc.radius.value > 0.0)
        assertTrue(arc.endAngle > arc.startAngle)
        for ((x, y) in listOf(0.0 to 0.0, 100.0 to 0.0, 50.0 to -30.0)) {
            assertEquals(arc.radius.value, hypot(x - arc.cx.value, y - arc.cy.value), 1e-6)
        }
        val mid = atan2(-30.0 - arc.cy.value, 50.0 - arc.cx.value)
        assertTrue(inSweep(arc, mid), "the bulge point is on the arc, not on the rest of its circle")
    }

    @Test
    fun arcFromThreePoints_should_ChooseTheSweepThatHoldsTheBulge_ForEitherSide() {
        for (bulgeY in listOf(-30, 30)) {
            val arc = assertNotNull(ArcFromThreePoints.of(sp(0, 0), sp(100, 0), sp(50, bulgeY), base))
            assertTrue(inSweep(arc, atan2(bulgeY - arc.cy.value, 50.0 - arc.cx.value)))
            assertTrue(arc.endAngle - arc.startAngle < 2 * PI)
        }
    }

    @Test
    fun collinearPoints_should_CommitNothing_AndSayNothingDrawn() {
        val r = runScenario(ArcTool()) { down(0, 0); up(0, 0); down(100, 0); up(100, 0); down(50, 0); up(50, 0) }
        assertTrue(r.commands.isEmpty())
        assertEquals(ToolNotice.NothingDrawn, r.notices.last())
        assertNull(ArcFromThreePoints.of(sp(0, 0), sp(100, 0), sp(50, 0), base))
        assertNull(ArcFromThreePoints.of(sp(5, 5), sp(5, 5), sp(9, 1), base))
    }

    @Test
    fun escapeMidWay_should_CancelWithNoCommand_AndRestartFromTheFirstPoint() {
        val r = runScenario(ArcTool()) {
            down(0, 0); up(0, 0); down(100, 0); up(100, 0); escape()
            down(0, 0); up(0, 0); down(100, 0); up(100, 0); down(50, -30); up(50, -30)
        }
        arcOf(r)
        val aborted = runScenario(ArcTool()) { down(0, 0); up(0, 0); down(100, 0); up(100, 0); escape(); down(50, -30); up(50, -30) }
        assertTrue(aborted.commands.isEmpty())
    }

    @Test
    fun secondPointerDuringAClick_should_DropThatClickOnly() {
        val r = runScenario(ArcTool()) {
            down(0, 0); up(0, 0)
            down(100, 0); down(7, 7, pointer = 1); up(100, 0)
            down(100, 0); up(100, 0); down(50, -30); up(50, -30)
        }
        arcOf(r)
    }

    @Test
    fun hoverAfterTwoPoints_should_PreviewTheArc_And_BeforeThatNothingOrALine() {
        val none = runScenario(ArcTool()) { move(10, 10) }
        assertNull(none.previews.last())
        val two = runScenario(ArcTool()) { down(0, 0); up(0, 0); down(100, 0); up(100, 0); move(50, -30) }
        assertIs<ArcElement>(assertNotNull(two.previews.last()).elements.single())
    }

    private fun inSweep(arc: ArcElement, angle: Double): Boolean {
        val full = 2 * PI
        fun turn(a: Double) = ((a % full) + full) % full
        return turn(angle - arc.startAngle) <= turn(arc.endAngle - arc.startAngle)
    }

    @Test
    fun hint_should_FollowThePlacedPoints() {
        assertTrue(ArcTool().hint().contains("start"))
        val one = runScenario(ArcTool()) { down(0, 0); up(0, 0) }.finalTool as ArcTool
        assertTrue(one.hint().contains("end"))
    }
}
