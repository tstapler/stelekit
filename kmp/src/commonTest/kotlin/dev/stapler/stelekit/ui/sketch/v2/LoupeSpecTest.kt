// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The touch loupe's pure rules: when it shows, where the bubble goes and how the scene is magnified under it. */
class LoupeSpecTest {
    private val phoneW = 360.0
    private val phoneH = 700.0

    private fun reduce(state: LoupeState, type: InputType, x: Double = 0.0, y: Double = 0.0, kind: PointerKind = PointerKind.Touch, id: Int = 0, pxPerDp: Double = 1.0) =
        LoupeSpec.reduce(state, InputEvent(type, InputEvent.Origin, pointerKind = kind, pointerId = id), ScreenPx(x, y), pxPerDp)

    @Test
    fun reduce_should_StayHidden_When_ATouchTapsWithoutMoving() {
        val down = reduce(LoupeState(), InputType.Down, 50.0, 50.0)
        assertEquals(LoupePhase.Armed, down.phase)
        assertFalse(down.visible)
        assertEquals(LoupeState(), reduce(down, InputType.Up, 50.0, 50.0))
    }

    @Test
    fun reduce_should_Show_When_TheTouchMovesPastTheSlop_ScaledByDensity() {
        val slopPx = LoupeSpec.MOVE_SLOP_DP * 3.0
        val down = reduce(LoupeState(), InputType.Down, 50.0, 50.0, pxPerDp = 3.0)
        val near = reduce(down, InputType.Move, 50.0 + slopPx - 1.0, 50.0, pxPerDp = 3.0)
        assertFalse(near.visible, "under the slop is still a tap")
        val far = reduce(near, InputType.Move, 50.0 + slopPx, 50.0, pxPerDp = 3.0)
        assertTrue(far.visible)
        assertEquals(ScreenPx(50.0 + slopPx, 50.0), far.at)
        assertTrue(reduce(far, InputType.Move, 50.0, 50.0, pxPerDp = 3.0).visible, "once shown it stays until release")
    }

    @Test
    fun holdElapsed_should_ShowAnArmedTouch_OnlyForItsOwnPointer() {
        val armed = reduce(LoupeState(), InputType.Down, 10.0, 10.0, id = 4)
        assertFalse(LoupeSpec.holdElapsed(armed, pointerId = 5).visible)
        assertTrue(LoupeSpec.holdElapsed(armed, pointerId = 4).visible)
        assertEquals(LoupeState(), LoupeSpec.holdElapsed(LoupeState(), pointerId = 0))
    }

    @Test
    fun reduce_should_NeverArm_When_ThePointerIsNotATouch() {
        for (kind in listOf(PointerKind.Mouse, PointerKind.Stylus, PointerKind.Eraser)) {
            assertEquals(LoupeState(), reduce(LoupeState(), InputType.Down, kind = kind), "$kind")
        }
    }

    @Test
    fun reduce_should_Hide_When_ASecondFingerLandsOrTheGestureEnds() {
        val showing = LoupeSpec.holdElapsed(reduce(LoupeState(), InputType.Down, id = 1), 1)
        assertEquals(LoupeState(), reduce(showing, InputType.Down, id = 2), "pinch")
        for (end in listOf(InputType.Up, InputType.Cancel, InputType.Escape)) {
            assertEquals(LoupeState(), reduce(showing, end, id = 1), "$end")
        }
        assertEquals(showing, reduce(showing, InputType.Move, 99.0, 0.0, id = 2), "another pointer's move is ignored")
    }

    @Test
    fun shouldShow_should_FollowTheSetting() {
        val showing = LoupeSpec.holdElapsed(reduce(LoupeState(), InputType.Down), 0)
        assertTrue(LoupeSpec.shouldShow(showing, enabled = true))
        assertFalse(LoupeSpec.shouldShow(showing, enabled = false))
    }

    @Test
    fun place_should_GoAboveTheFinger_When_ThereIsRoom() {
        val p = LoupeSpec.place(ScreenPx(180.0, 400.0), phoneW, phoneH, 1.0)
        assertEquals(LoupeSide.Above, p.side)
        assertEquals(180.0, p.centre.x, EPS)
        assertEquals(400.0 - LoupeSpec.GAP_DP - LoupeSpec.DIAMETER_DP / 2, p.centre.y, EPS)
    }

    @Test
    fun place_should_FlipBesideTheFinger_When_NearTheTopEdge() {
        val nearLeft = LoupeSpec.place(ScreenPx(60.0, 30.0), phoneW, phoneH, 1.0)
        assertEquals(LoupeSide.Right, nearLeft.side, "toward the roomier side")
        assertTrue(nearLeft.centre.x - LoupeSpec.DIAMETER_DP / 2 > 60.0, "clear of the finger")
        assertEquals(LoupeSide.Left, LoupeSpec.place(ScreenPx(300.0, 30.0), phoneW, phoneH, 1.0).side)
    }

    @Test
    fun place_should_GoBelow_When_NoSideFitsAtTheTop() {
        assertEquals(LoupeSide.Below, LoupeSpec.place(ScreenPx(145.0, 20.0), 300.0, phoneH, 1.0).side)
    }

    @Test
    fun place_should_KeepAboveAndClampSideways_When_NearASideEdge() {
        val p = LoupeSpec.place(ScreenPx(5.0, 500.0), phoneW, phoneH, 1.0)
        assertEquals(LoupeSide.Above, p.side)
        assertEquals(LoupeSpec.MARGIN_DP + LoupeSpec.DIAMETER_DP / 2, p.centre.x, EPS)
    }

    @Test
    fun place_should_ScaleWithDensity() {
        val one = LoupeSpec.place(ScreenPx(180.0, 400.0), phoneW, phoneH, 1.0)
        val three = LoupeSpec.place(ScreenPx(540.0, 1200.0), phoneW * 3, phoneH * 3, 3.0)
        assertEquals(one.centre.x * 3, three.centre.x, EPS)
        assertEquals(one.centre.y * 3, three.centre.y, EPS)
    }

    @Test
    fun place_should_KeepTheBubbleInsideTheCanvasAndOffTheFinger_ForAnyContact() = runTest {
        checkAll(Arb.double(0.0, phoneW), Arb.double(0.0, phoneH)) { x, y ->
            val half = LoupeSpec.DIAMETER_DP / 2
            val p = LoupeSpec.place(ScreenPx(x, y), phoneW, phoneH, 1.0)
            assertTrue(p.centre.x - half >= LoupeSpec.MARGIN_DP - EPS && p.centre.x + half <= phoneW - LoupeSpec.MARGIN_DP + EPS, "x for ($x,$y) $p")
            assertTrue(p.centre.y - half >= LoupeSpec.MARGIN_DP - EPS && p.centre.y + half <= phoneH - LoupeSpec.MARGIN_DP + EPS, "y for ($x,$y) $p")
            assertTrue(hypot(p.centre.x - x, p.centre.y - y) >= half, "the bubble must not cover the contact ($x,$y) $p")
        }
    }

    @Test
    fun place_should_CentreTheBubble_When_TheCanvasIsSmallerThanIt() {
        assertEquals(30.0, LoupeSpec.place(ScreenPx(20.0, 20.0), 60.0, 60.0, 1.0).centre.x, EPS)
    }

    @Test
    fun layerTransform_should_PutTheContactAtTheBubbleCentre_And_MagnifyAroundIt() = runTest {
        checkAll(Arb.double(0.0, phoneW), Arb.double(0.0, phoneH), Arb.double(-50.0, 50.0), Arb.double(-50.0, 50.0)) { cx, cy, dx, dy ->
            val contact = ScreenPx(cx, cy)
            val centre = LoupeSpec.place(contact, phoneW, phoneH, 1.0).centre
            val t = LoupeSpec.layerTransform(contact, centre)
            val atContact = t.map(contact)
            assertTrue(abs(atContact.x - centre.x) < EPS && abs(atContact.y - centre.y) < EPS)
            val moved = t.map(ScreenPx(cx + dx, cy + dy))
            assertEquals(centre.x + LoupeSpec.MAGNIFICATION * dx, moved.x, EPS)
            assertEquals(centre.y + LoupeSpec.MAGNIFICATION * dy, moved.y, EPS)
        }
    }

    private companion object {
        const val EPS = 1e-6
    }
}
