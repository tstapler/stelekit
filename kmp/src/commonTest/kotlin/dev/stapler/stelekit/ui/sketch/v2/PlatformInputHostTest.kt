// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.platform.EdgeExclusionRect
import dev.stapler.stelekit.platform.EdgeGestureExclusion
import dev.stapler.stelekit.platform.RawPointerSource
import dev.stapler.stelekit.platform.SketchPlatformInput
import dev.stapler.stelekit.sketch.v2.runtime.settings.EdgePanSettings
import dev.stapler.stelekit.sketch.v2.runtime.settings.EdgePanSide
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 3.1.2h wiring: the host that carries the platform's input seams to the v2 canvas. Synthesised input, not a device run (Task 0.1.4d). */
class PlatformInputHostTest {
    private val canvas = ScreenRect(100.0, 50.0, 500.0, 450.0)
    private val router = RawPointerRouter({ canvas }, { SCALE })

    private fun raw(id: Int, phase: RawPhase, x: Double, y: Double) =
        RawPointer(id = id, kind = PointerKind.Mouse, phase = phase, position = ScreenPx(x, y))

    private class FakeExclusion(override val canRequest: Boolean = true) : EdgeGestureExclusion {
        var requests = 0
        override fun request(rects: List<EdgeExclusionRect>) {
            requests += 1
        }

        override fun release() = Unit
    }

    private fun host(
        platform: HostPlatform,
        adapter: SketchPointerAdapter = SketchPointerAdapter(),
        actions: MutableList<PointerAction> = mutableListOf(),
        raw: InMemorySettings = InMemorySettings(),
    ) = PlatformInputHost(platform, adapter, { actions += it }, OnboardingSequencer(OnboardingCounters(raw)))

    @Test
    fun router_should_DropAContactThatStartsOutsideTheCanvas_AndLocaliseOneThatStartsInside() {
        assertNull(router.route(raw(1, RawPhase.Down, 10.0, 10.0)), "50 css px is left of the canvas at scale 2")
        assertNull(router.route(raw(1, RawPhase.Move, 100.0, 100.0)), "a move of a dropped contact stays dropped")
        val down = assertNotNull(router.route(raw(2, RawPhase.Down, 100.0, 100.0)))
        assertEquals(ScreenPx(100.0, 150.0), down.position)
    }

    @Test
    fun router_should_KeepAContactThatLeavesTheCanvas_UntilItEnds() {
        router.route(raw(3, RawPhase.Down, 100.0, 100.0))
        assertNotNull(router.route(raw(3, RawPhase.Move, 1000.0, 1000.0)), "a drag may leave the canvas")
        assertNotNull(router.route(raw(3, RawPhase.Up, 1000.0, 1000.0)))
        assertNull(router.route(raw(3, RawPhase.Move, 100.0, 100.0)), "the contact ended")
    }

    @Test
    fun router_should_DropAHoverOutsideTheCanvas_AndKeepOneInside() {
        assertNull(router.route(raw(4, RawPhase.Hover, 1.0, 1.0)))
        assertNotNull(router.route(raw(4, RawPhase.Hover, 200.0, 200.0)))
    }

    @Test
    fun edgePan_should_StayOff_WhenNoTargetIsMarkedSupported_EvenWithAnExclusionHost() {
        val exclusion = FakeExclusion()
        val adapter = SketchPointerAdapter(fingerDrawing = true)
        val host = host(
            HostPlatform(input = SketchPlatformInput(edgeExclusion = exclusion), edgeTarget = EdgeTarget.Android, edgePan = EdgePanSettings(InMemorySettings())),
            adapter,
        )
        host.onBounds(ScreenPx(0.0, 0.0), ScreenPx(PHONE_PX, TALL_PX), 1.0)
        assertTrue(host.strips.isEmpty())
        assertTrue(adapter.edgeStrips.isEmpty())
        assertEquals(0, exclusion.requests)
    }

    @Test
    fun edgePan_should_ShowTheChosenSide_AndTheSecondStripCancelHidesItForTheSession() {
        val raw = InMemorySettings()
        val settings = EdgePanSettings(raw).also { it.choose(EdgePanSide.Right) }
        val adapter = SketchPointerAdapter(fingerDrawing = true)
        val host = host(HostPlatform(input = SketchPlatformInput(edgeExclusion = FakeExclusion()), edgePan = settings), adapter, raw = raw)
        host.onBounds(ScreenPx(0.0, 0.0), ScreenPx(PHONE_PX, TALL_PX), 1.0)
        assertEquals(listOf(StripEdge.Right), host.strips.map { it.edge })
        assertTrue(host.stripAtEnd)
        assertEquals(1, adapter.edgeStrips.size)

        val listener = assertNotNull(adapter.listener)
        listener.onStripDragCancelled()
        assertFalse(host.strips.isEmpty(), "one cancel is not yet a pattern")
        listener.onStripDragCancelled()
        assertTrue(host.strips.isEmpty())
        assertTrue(adapter.edgeStrips.isEmpty())
        assertTrue(settings.exclusionFailed, "the next session starts Off")
    }

    @Test
    fun host_should_KeepForwardingToAListenerThatWasAlreadyOnTheAdapter() {
        val adapter = SketchPointerAdapter()
        var drags = 0
        adapter.listener = object : PointerGestureListener {
            override fun onOneFingerDrag() {
                drags += 1
            }
        }
        host(HostPlatform(), adapter)
        adapter.listener?.onOneFingerDrag()
        assertEquals(1, drags)
    }

    @Test
    fun rawPointerSource_should_FeedTheAdapter_ThroughTheRouter_UntilDisposed() {
        var emit: (RawPointer) -> Unit = {}
        var disposed = false
        val source = RawPointerSource { sink ->
            emit = sink
            return@RawPointerSource { disposed = true }
        }
        val actions = mutableListOf<PointerAction>()
        val host = host(HostPlatform(input = SketchPlatformInput(rawPointers = source)), actions = actions)
        assertTrue(host.usesRawPointers)
        host.onBounds(ScreenPx(0.0, 0.0), ScreenPx(PHONE_PX, TALL_PX), 1.0)
        val stop = host.installRawPointers()
        emit(raw(5, RawPhase.Down, 20.0, 20.0))
        assertTrue(actions.any { it is PointerAction.ToolInput }, "a contact inside the canvas reaches the tool")
        stop()
        assertTrue(disposed)
    }

    @Test
    fun aPlatformWithNoSeams_should_KeepTheComposePointerPath() {
        assertFalse(host(HostPlatform()).usesRawPointers)
    }

    private companion object {
        const val SCALE = 2.0
        const val PHONE_PX = 360.0
        const val TALL_PX = 800.0
    }
}
