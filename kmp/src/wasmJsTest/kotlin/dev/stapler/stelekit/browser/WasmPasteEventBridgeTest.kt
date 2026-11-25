// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun newTarget(): JsAny = js("document.createElement('div')")

/** Dispatches a synthetic bubbling `paste` event carrying [text]; true when a listener cancelled it. */
private fun dispatchPaste(target: JsAny, text: String): Boolean = js(
    """
    (function() {
        const data = new DataTransfer();
        data.setData('text/plain', text);
        const event = new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true });
        target.dispatchEvent(event);
        return event.defaultPrevented;
    })()
    """,
)

/** A `paste` event with no clipboard data at all, as when the browser has nothing to hand over. */
private fun dispatchEmptyPaste(target: JsAny): Boolean = js(
    """
    (function() {
        const event = new ClipboardEvent('paste', { bubbles: true, cancelable: true });
        target.dispatchEvent(event);
        return event.defaultPrevented;
    })()
    """,
)

/** Story 7.1c: the DOM paste event reaches the common handler once; no event means the "Paste here" fallback stays the way in. */
class WasmPasteEventBridgeTest {
    @Test
    fun pasteEvent_should_DeliverTheClipboardTextOnce_AndCancelTheBrowsersOwnPaste() {
        val target = newTarget()
        val bridge = WasmPasteEventBridge(target)
        val received = mutableListOf<String>()
        bridge.register { received += it; true }
        val cancelled = dispatchPaste(target, "1\t2\n3\t4")
        assertEquals(listOf("1\t2\n3\t4"), received)
        assertTrue(cancelled, "a handler that took the text cancels the browser's own paste")
    }

    @Test
    fun handlerThatDeclines_should_LeaveTheBrowsersPasteAlone() {
        val target = newTarget()
        val bridge = WasmPasteEventBridge(target)
        var calls = 0
        bridge.register { calls++; false }
        assertFalse(dispatchPaste(target, "a\tb"), "the text field keeps its own paste")
        assertEquals(1, calls)
    }

    @Test
    fun eventWithoutData_should_DeliverNothing() {
        val target = newTarget()
        val bridge = WasmPasteEventBridge(target)
        var calls = 0
        bridge.register { calls++; true }
        assertFalse(dispatchEmptyPaste(target))
        assertFalse(dispatchPaste(target, ""))
        assertEquals(0, calls, "no text, no delivery: the sheet falls back to the clipboard port and the Paste here box")
    }

    @Test
    fun release_should_RemoveTheListener_AndALaterRegistrationShouldWorkAgain() {
        val target = newTarget()
        val bridge = WasmPasteEventBridge(target)
        val first = mutableListOf<String>()
        val release = bridge.register { first += it; true }
        release()
        assertFalse(dispatchPaste(target, "gone"))
        assertEquals(emptyList(), first)
        val second = mutableListOf<String>()
        bridge.register { second += it; true }
        dispatchPaste(target, "back")
        assertEquals(listOf("back"), second)
        assertEquals(emptyList(), first)
    }

    @Test
    fun twoRegistrations_should_EachGetTheEventOnce_FromOneListener() {
        val target = newTarget()
        val bridge = WasmPasteEventBridge(target)
        val a = mutableListOf<String>()
        val b = mutableListOf<String>()
        val releaseA = bridge.register { a += it; false }
        bridge.register { b += it; true }
        assertTrue(dispatchPaste(target, "x"))
        assertEquals(listOf("x"), a)
        assertEquals(listOf("x"), b)
        releaseA()
        dispatchPaste(target, "y")
        assertEquals(listOf("x"), a)
        assertEquals(listOf("x", "y"), b)
    }
}
