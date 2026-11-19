// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.ui.sketch.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun createHost(): JsAny = js("(function() { const d = document.createElement('div'); document.body.appendChild(d); return d; })()")

private fun dispatch(host: JsAny, type: String, pointerType: String, pressure: Double, id: Int, primary: Boolean, buttons: Int): Unit = js(
    "(function() { host.dispatchEvent(new PointerEvent(type, { pointerType: pointerType, pressure: pressure, pointerId: id, isPrimary: primary, buttons: buttons, bubbles: true })); })()",
)

/** Spike 0.1.4 (Task 0.1.4b). Synthetic DOM events: proves the host styles and what a pen PointerEvent carries, not real browser scrolling. */
class WebInputSpikeProbeTest {
    @Test
    fun hostStopsBrowserGesturesAndReceivesPenPressure() {
        val host = createHost()
        val log = mutableListOf<WebPointerSample>()
        val dispose = installWebInputSpikeProbe(host) { log += it }
        assertEquals("none", computedStyleProperty(host, "touch-action"), "touch-action")
        val overscroll = computedStyleProperty(host, "overscroll-behavior")
        assertTrue(overscroll.startsWith("none"), "overscroll-behavior=$overscroll")

        dispatch(host, "pointerdown", "pen", 0.6, 1, true, 1)
        dispatch(host, "pointerdown", "pen", 0.5, 2, false, 33) // buttons bit 5 (32) = pen eraser per Pointer Events; bit 2 (2) = barrel
        println("INPUT-SPIKE wasm pen: " + log.joinToString(" | "))
        assertEquals(2, log.size, "log=$log")
        assertEquals("pen", log.getOrNull(0)?.pointerType, "log=$log")
        assertEquals(0.6, log[0].pressure, 1e-6, "log=$log")
        dispose()
    }

    @Test
    fun twoFingerPinchDeliversBothPointers() {
        val host = createHost()
        val log = mutableListOf<WebPointerSample>()
        val dispose = installWebInputSpikeProbe(host) { log += it }
        dispatch(host, "pointerdown", "touch", 0.5, 10, true, 1)
        dispatch(host, "pointerdown", "touch", 0.5, 11, false, 1)
        dispatch(host, "pointermove", "touch", 0.5, 10, true, 1)
        dispatch(host, "pointermove", "touch", 0.5, 11, false, 1)
        println("INPUT-SPIKE wasm pinch: " + log.joinToString(" | "))
        assertEquals(setOf(10, 11), log.map { it.pointerId }.toSet())
        assertTrue(log.any { !it.isPrimary })
        dispose()
    }
}
