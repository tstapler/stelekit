// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.v2.RawPhase
import dev.stapler.stelekit.ui.sketch.v2.RawPointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun createHost(): JsAny = js("(function() { const d = document.createElement('div'); document.body.appendChild(d); return d; })()")

private fun dispatch(host: JsAny, type: String, pointerType: String, pressure: Double, id: Int, buttons: Int): Unit = js(
    "(function() { host.dispatchEvent(new PointerEvent(type, { pointerType: pointerType, pressure: pressure, pointerId: id, buttons: buttons, bubbles: true })); })()",
)

/** Task 3.1.2c: the web canvas host sets touch-action and maps raw DOM pointer events, including pen eraser and barrel, to the adapter. */
class WebSketchPlatformInputTest {
    private val origin = ScreenPx(1.0, 2.0)

    @Test
    fun host_should_SetTouchActionNone_And_ForwardPenPressure() {
        val host = createHost()
        val log = mutableListOf<RawPointer>()
        val dispose = WebSketchPlatformInput.install(host) { log += it }
        assertEquals("none", WebSketchPlatformInput.computedStyle(host, "touch-action"))
        dispatch(host, "pointerdown", "pen", 0.6, 1, 1)
        dispose()
        dispatch(host, "pointerdown", "pen", 0.6, 2, 1)
        val down = log.single()
        assertEquals(PointerKind.Stylus, down.kind)
        assertEquals(RawPhase.Down, down.phase)
        assertEquals(0.6, down.pressure!!, 1e-6)
    }

    @Test
    fun toRaw_should_MapEraserBarrelAndMouseButtons() {
        val kinds = HashMap<Int, PointerKind>()
        val eraser = WebSketchPlatformInput.toRaw("pointerdown", "pen", 0.5, 33, 7, origin, 0, 0L, kinds)!!
        assertEquals(PointerKind.Eraser, eraser.kind)
        // The eraser bit is gone by pointerup; the kind chosen at pointerdown sticks.
        val up = WebSketchPlatformInput.toRaw("pointerup", "pen", 0.0, 0, 7, origin, 0, 1L, kinds)!!
        assertEquals(PointerKind.Eraser, up.kind)
        assertEquals(RawPhase.Up, up.phase)

        val barrel = WebSketchPlatformInput.toRaw("pointerdown", "pen", 0.5, 3, 8, origin, 0, 0L, kinds)!!
        assertEquals(PointerKind.Stylus, barrel.kind)
        assertEquals(true, barrel.secondaryButton)

        val right = WebSketchPlatformInput.toRaw("pointerdown", "mouse", 0.5, 2, 9, origin, 2, 0L, kinds)!!
        assertEquals(PointerKind.Mouse, right.kind)
        assertEquals(true, right.secondaryButton)
        assertEquals(true, right.modifiers.ctrl)
    }

    @Test
    fun toRaw_should_TreatButtonlessMoveAsHover_And_IgnoreOtherEvents() {
        val kinds = HashMap<Int, PointerKind>()
        assertEquals(RawPhase.Hover, WebSketchPlatformInput.toRaw("pointermove", "mouse", 0.0, 0, 1, origin, 0, 0L, kinds)!!.phase)
        assertEquals(RawPhase.Move, assertNotNull(WebSketchPlatformInput.toRaw("pointermove", "touch", 0.5, 1, 2, origin, 0, 0L, kinds)).phase)
        assertNull(WebSketchPlatformInput.toRaw("pointerenter", "mouse", 0.0, 0, 1, origin, 0, 0L, kinds))
    }
}
