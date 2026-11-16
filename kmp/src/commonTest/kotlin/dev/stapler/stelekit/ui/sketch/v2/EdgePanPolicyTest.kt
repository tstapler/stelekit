// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import dev.stapler.stelekit.sketch.v2.runtime.settings.EdgePanSettings
import dev.stapler.stelekit.sketch.v2.runtime.settings.EdgePanSide
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.1.2 (UX-102, UX-121; D-18, D-32; Task 3.1.2g): the edge-strip policy, geometry, hit routing and the cancel backstop. */
class EdgePanPolicyTest {
    private val supported = EdgeSpikeResults { EdgeSupport.Supported }
    private val strings = ChromeBannerStrings(ChromeTableParser.parseFixture())

    @Test
    fun edgePanPolicy_should_DefaultOff_When_TargetNotMarkedSupported_Or_ApiBelow29() {
        for (target in EdgeTarget.entries) {
            assertEquals(EdgePanSide.Off, EdgePanPolicy.defaultSide(target, PHONE_DP, canRequestExclusion = true, exclusionFailed = false), "$target has no measured result")
        }
        val notSupported = EdgeSpikeResults { EdgeSupport.NotSupported }
        assertEquals(EdgePanSide.Off, EdgePanPolicy.defaultSide(EdgeTarget.Android, PHONE_DP, true, false, notSupported))
        assertEquals(EdgePanSide.Off, EdgePanPolicy.defaultSide(EdgeTarget.Android, PHONE_DP, canRequestExclusion = false, exclusionFailed = false, spike = supported), "Android below API 29 cannot ask")
    }

    @Test
    fun edgePanPolicy_should_DefaultRightOnCompact_BothFromTheCompactWidth_When_Supported() {
        val compactWidth = ChromeTableParser.parseFixture().params.getValue("compactWidth")
        assertEquals(compactWidth, EdgePanPolicy.BOTH_FROM_DP, "the chrome table owns the width")
        assertEquals(EdgePanSide.Right, EdgePanPolicy.defaultSide(EdgeTarget.Android, compactWidth - 1, true, false, supported))
        assertEquals(EdgePanSide.Both, EdgePanPolicy.defaultSide(EdgeTarget.Android, compactWidth, true, false, supported))
    }

    @Test
    fun exclusionFailed_should_PersistSoNextSessionStartsOff() {
        val raw = InMemorySettings()
        val settings = EdgePanSettings(raw)
        assertEquals(EdgePanSide.Right, EdgePanPolicy.effectiveSide(settings, EdgeTarget.Android, PHONE_DP, true, supported))
        settings.markExclusionFailed()
        val nextSession = EdgePanSettings(raw)
        assertTrue(nextSession.exclusionFailed)
        assertEquals(EdgePanSide.Off, EdgePanPolicy.effectiveSide(nextSession, EdgeTarget.Android, PHONE_DP, true, supported))
        nextSession.choose(EdgePanSide.Left)
        assertFalse(nextSession.exclusionFailed, "choosing a side explicitly clears the marker")
        assertEquals(EdgePanSide.Left, EdgePanPolicy.effectiveSide(EdgePanSettings(raw), EdgeTarget.Android, PHONE_DP, true, supported))
        nextSession.choose(EdgePanSide.Off)
        assertEquals(EdgePanSide.Off, EdgePanPolicy.effectiveSide(EdgePanSettings(raw), EdgeTarget.Android, PHONE_DP, true, supported), "an explicit Off sticks")
    }

    @Test
    fun strip_should_StartAtInset_Be44dpHit_24dpVisible_And_CoverOnlyProtectedSpan() {
        val host = EdgePanGeometry.Host(widthDp = PHONE_DP.toDouble(), heightDp = PHONE_HEIGHT_DP, insetStartDp = INSET_DP, insetEndDp = INSET_DP)
        val strip = EdgePanGeometry.strips(EdgePanSide.Left, host).single()
        assertEquals(INSET_DP, strip.hit.left, "starts at the system gesture inset")
        assertEquals(EdgePanGeometry.HIT_DP.toDouble(), strip.hit.width)
        assertEquals(EdgePanGeometry.BAND_DP.toDouble(), strip.band.width)
        assertEquals(INSET_DP, strip.band.left, "the visible band is next to the inset")
        assertEquals(EdgePanGeometry.EXPECTED_SPAN_DP.toDouble(), strip.hit.height, "only the protected span")
        assertEquals((PHONE_HEIGHT_DP - strip.hit.height) / 2.0, strip.hit.top, "centred on its edge")
        assertTrue(strip.hit.height < PHONE_HEIGHT_DP)

        val rects = EdgePanGeometry.exclusionRects(listOf(strip), PHONE_DP.toDouble(), pxPerDp = 1.0)
        assertEquals(1, rects.size)
        assertEquals(EdgePanGeometry.EXPECTED_SPAN_DP, rects.single().height, "exactly the span, never the whole edge")
        assertEquals(0, rects.single().left, "reaches the screen edge so the inset is included")

        val both = EdgePanGeometry.strips(EdgePanSide.Both, host)
        assertEquals(listOf(StripEdge.Left, StripEdge.Right), both.map { it.edge })
        assertTrue(EdgePanGeometry.strips(EdgePanSide.Off, host).isEmpty())
        val tall = EdgePanGeometry.strips(EdgePanSide.Right, EdgePanGeometry.Host(PHONE_DP.toDouble(), SHORT_HEIGHT_DP)).single()
        assertEquals(SHORT_HEIGHT_DP, tall.hit.height, "a window shorter than the span is covered fully")
    }

    @Test
    fun hitRouting_should_PanInsideTheStripAndSpan_AndDrawOutside() {
        val host = EdgePanGeometry.Host(widthDp = PHONE_DP.toDouble(), heightDp = PHONE_HEIGHT_DP, insetStartDp = INSET_DP)
        val strips = EdgePanGeometry.strips(EdgePanSide.Left, host)
        val adapter = SketchPointerAdapter(fingerDrawing = true)
        adapter.edgeStrips = EdgePanGeometry.hitRects(strips, pxPerDp = 1.0)
        val middle = PHONE_HEIGHT_DP / 2.0

        fun drag(x: Double, y: Double, id: Int): List<PointerAction> {
            val down = adapter.onPointer(RawPointer(id, PointerKind.Touch, RawPhase.Down, ScreenPx(x, y)))
            val move = adapter.onPointer(RawPointer(id, PointerKind.Touch, RawPhase.Move, ScreenPx(x + MOVE_PX, y)))
            adapter.onPointer(RawPointer(id, PointerKind.Touch, RawPhase.Up, ScreenPx(x + MOVE_PX, y)))
            return down + move
        }
        assertTrue(drag(INSIDE_X, middle, 1).none { it is PointerAction.ToolInput }, "x = 60 dp pans and creates nothing")
        assertEquals(MOVE_PX, adapter.viewport.panX, 1e-9)
        assertTrue(drag(OUTSIDE_X, middle, 2).any { it is PointerAction.ToolInput }, "x = 80 dp draws")
        assertTrue(drag(INSIDE_X, OUTSIDE_SPAN_Y, 3).any { it is PointerAction.ToolInput }, "x = 60 dp outside the protected span draws")
        assertTrue(drag(IN_INSET_X, middle, 4).any { it is PointerAction.ToolInput }, "the system inset is not hit-tested by the app")
        assertTrue(ScreenPx(INSIDE_X, middle) in adapter.edgeStrips.single())
        assertFalse(ScreenPx(IN_INSET_X, middle) in adapter.edgeStrips.single())

        adapter.fingerDrawing = false
        adapter.edgeStrips = emptyList()
        assertTrue(drag(INSIDE_X, middle, 5).none { it is PointerAction.ToolInput }, "with the strip absent the other pan paths still pan")
    }

    @Test
    fun backstop_should_HideAfterTwoCancels_MarkTheDevice_AndShowTheNoticeOnce() {
        val raw = InMemorySettings()
        val settings = EdgePanSettings(raw)
        val sequencer = OnboardingSequencer(OnboardingCounters(raw))
        val backstop = EdgePanBackstop(settings, sequencer, strings)
        backstop.onStripDragCancelled()
        assertFalse(backstop.hiddenForSession)
        assertNull(sequencer.visible)
        assertFalse(settings.exclusionFailed)

        backstop.onStripDragCancelled()
        assertTrue(backstop.hiddenForSession)
        assertTrue(settings.exclusionFailed)
        assertEquals(OnboardingItem.EdgePanStopped, sequencer.visible)
        assertEquals("Edge pan stopped.", sequencer.visibleCandidate()?.summary)
        assertEquals(EdgePanBackstop.DETAILS, sequencer.visibleCandidate()?.details)

        sequencer.dismiss(OnboardingItem.EdgePanStopped, Dismissal.Close)
        backstop.onStripDragCancelled()
        assertNull(sequencer.visible, "the notice is shown once")
    }

    @Test
    fun adapter_should_ReportASystemCancelOfAStripDrag_ButNotOfAnOrdinaryTouch() {
        var cancelled = 0
        val adapter = SketchPointerAdapter(fingerDrawing = true)
        adapter.edgeStrips = listOf(ScreenRect(0.0, 0.0, STRIP_RIGHT, STRIP_BOTTOM))
        adapter.listener = object : PointerGestureListener {
            override fun onStripDragCancelled() {
                cancelled += 1
            }
        }
        adapter.onPointer(RawPointer(1, PointerKind.Touch, RawPhase.Down, ScreenPx(5.0, 5.0)))
        adapter.onPointer(RawPointer(1, PointerKind.Touch, RawPhase.Move, ScreenPx(9.0, 5.0)))
        adapter.onPointer(RawPointer(1, PointerKind.Touch, RawPhase.Cancel, ScreenPx(9.0, 5.0)))
        assertEquals(1, cancelled)
        adapter.onPointer(RawPointer(2, PointerKind.Touch, RawPhase.Down, ScreenPx(STRIP_RIGHT + 5.0, 5.0)))
        adapter.onPointer(RawPointer(2, PointerKind.Touch, RawPhase.Cancel, ScreenPx(STRIP_RIGHT + 5.0, 5.0)))
        assertEquals(1, cancelled, "a cancel outside the strip is not the backstop's business")
    }

    private companion object {
        const val PHONE_DP = 360
        const val PHONE_HEIGHT_DP = 640.0
        const val SHORT_HEIGHT_DP = 150.0
        const val INSET_DP = 24.0
        const val INSIDE_X = 60.0
        const val OUTSIDE_X = 80.0
        const val IN_INSET_X = 10.0
        const val OUTSIDE_SPAN_Y = 20.0
        const val MOVE_PX = 30.0
        const val STRIP_RIGHT = 44.0
        const val STRIP_BOTTOM = 100.0
    }
}
