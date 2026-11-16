// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.sketch.v2.tools.Modifiers
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.v2.RawPhase
import dev.stapler.stelekit.ui.sketch.v2.RawPointer

/**
 * The canvas host of the v2 sketch editor on the web (Task 3.1.2c). Compose wasm reports every touch as
 * `PointerType.Touch` and has no pen type, pressure source, eraser or barrel button (research/input-spike.md), so the
 * adapter is fed from the raw DOM `PointerEvent` of the host element instead. Setting `touch-action: none` stops the
 * browser scrolling or zooming the page under a drag (Task 3.1.2h adds `overscroll-behavior` here, not in a second host).
 */
object WebSketchPlatformInput {
    /** DOM `buttons` bits (Pointer Events level 3): 2 secondary or barrel, 4 auxiliary, 32 eraser. */
    private const val BUTTON_SECONDARY = 2
    private const val BUTTON_AUXILIARY = 4
    private const val BUTTON_ERASER = 32
    private const val MOD_SHIFT = 1
    private const val MOD_CTRL = 2
    private const val MOD_ALT = 4
    private const val MOD_META = 8

    /** Applies the gesture styles to [host] and forwards each pointer event as a [RawPointer]; returns the disposer. */
    fun install(host: JsAny, onPointer: (RawPointer) -> Unit): () -> Unit {
        applyTouchAction(host)
        val kinds = HashMap<Int, PointerKind>()
        val listener = fun(type: String, pointerType: String, pressure: Double, buttons: Int, id: Int, x: Double, y: Double, mods: Int, time: Double) {
            val raw = toRaw(type, pointerType, pressure, buttons, id, ScreenPx(x, y), mods, time.toLong(), kinds) ?: return
            onPointer(raw)
        }
        val handle = addListeners(host, listener)
        return { removeListeners(host, handle) }
    }

    /**
     * Pure mapping from DOM pointer fields to a [RawPointer]; null for an event the adapter does not use. [kinds]
     * remembers the kind chosen at pointerdown, because an eraser's `buttons` bit is gone by pointerup.
     */
    internal fun toRaw(
        type: String,
        pointerType: String,
        pressure: Double,
        buttons: Int,
        id: Int,
        position: ScreenPx,
        mods: Int,
        time: Long,
        kinds: MutableMap<Int, PointerKind>,
    ): RawPointer? {
        val phase = when (type) {
            "pointerdown" -> RawPhase.Down
            "pointermove" -> if (buttons != 0) RawPhase.Move else RawPhase.Hover
            "pointerup" -> RawPhase.Up
            "pointercancel" -> RawPhase.Cancel
            else -> return null
        }
        val kind = if (phase == RawPhase.Down || !kinds.containsKey(id)) kindOf(pointerType, buttons) else kinds.getValue(id)
        if (phase == RawPhase.Down) kinds[id] = kind
        if (phase == RawPhase.Up || phase == RawPhase.Cancel) kinds.remove(id)
        val secondary = (buttons and (BUTTON_SECONDARY or BUTTON_AUXILIARY)) != 0
        return RawPointer(
            id = id,
            kind = kind,
            phase = phase,
            position = position,
            pressure = pressure,
            secondaryButton = secondary,
            modifiers = Modifiers(
                shift = (mods and MOD_SHIFT) != 0,
                ctrl = (mods and MOD_CTRL) != 0,
                alt = (mods and MOD_ALT) != 0,
                meta = (mods and MOD_META) != 0,
            ),
            timestamp = time,
        )
    }

    private fun kindOf(pointerType: String, buttons: Int): PointerKind = when (pointerType) {
        "pen" -> if ((buttons and BUTTON_ERASER) != 0) PointerKind.Eraser else PointerKind.Stylus
        "mouse" -> PointerKind.Mouse
        else -> PointerKind.Touch
    }

    internal fun computedStyle(host: JsAny, property: String): String = computedStyleOf(host, property)
}

private fun computedStyleOf(host: JsAny, property: String): String =
    js("window.getComputedStyle(host).getPropertyValue(property)")

private fun applyTouchAction(host: JsAny): Unit =
    js("(function() { host.style.touchAction = 'none'; })()")

private fun addListeners(host: JsAny, cb: (String, String, Double, Int, Int, Double, Double, Int, Double) -> Unit): JsAny = js(
    """
    (function() {
        const names = ['pointerdown', 'pointermove', 'pointerup', 'pointercancel'];
        const fn = function(e) {
            const r = host.getBoundingClientRect();
            const mods = (e.shiftKey ? 1 : 0) | (e.ctrlKey ? 2 : 0) | (e.altKey ? 4 : 0) | (e.metaKey ? 8 : 0);
            if (e.type === 'pointerdown') { try { host.setPointerCapture(e.pointerId); } catch (x) {} }
            cb(e.type, e.pointerType, e.pressure, e.buttons, e.pointerId, e.clientX - r.left, e.clientY - r.top, mods, e.timeStamp);
        };
        names.forEach(function(n) { host.addEventListener(n, fn); });
        return { names: names, fn: fn };
    })()
    """,
)

private fun removeListeners(host: JsAny, handle: JsAny): Unit =
    js("(function() { handle.names.forEach(function(n) { host.removeEventListener(n, handle.fn); }); })()")
