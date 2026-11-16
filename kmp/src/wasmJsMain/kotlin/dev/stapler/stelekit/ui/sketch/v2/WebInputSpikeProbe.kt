// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

/**
 * Spike 0.1.4 (Task 0.1.4b): a probe on the canvas host element. Compose 1.10.3 wasm builds its pointer
 * events from `touch*`/`mouse*` DOM events (PointerType.Touch or Mouse only), so pen type, pen pressure,
 * barrel button and eraser need the raw DOM PointerEvent this probe reads. Findings:
 * project_plans/excalidraw-sketch-engine/research/input-spike.md. Task 3.1.2c replaces this with the real host.
 */
internal data class WebPointerSample(
    val event: String,
    val pointerType: String,
    val pressure: Double,
    val buttons: Int,
    val pointerId: Int,
    val isPrimary: Boolean,
)

/** Sets `touch-action: none` and `overscroll-behavior: none` on [host] and logs every pointer event; returns a disposer. */
internal fun installWebInputSpikeProbe(host: JsAny, log: (WebPointerSample) -> Unit): () -> Unit {
    applyGestureStyles(host)
    val listener = fun(event: String, pointerType: String, pressure: Double, buttons: Int, pointerId: Int, isPrimary: Boolean) {
        log(WebPointerSample(event, pointerType, pressure, buttons, pointerId, isPrimary))
    }
    val handle = addPointerListeners(host, listener)
    return { removePointerListeners(host, handle) }
}

internal fun computedStyleProperty(host: JsAny, property: String): String =
    js("window.getComputedStyle(host).getPropertyValue(property)")

private fun applyGestureStyles(host: JsAny): Unit =
    js("(function() { host.style.touchAction = 'none'; host.style.overscrollBehavior = 'none'; })()")

private fun addPointerListeners(
    host: JsAny,
    cb: (String, String, Double, Int, Int, Boolean) -> Unit,
): JsAny = js(
    """
    (function() {
        const names = ['pointerdown', 'pointermove', 'pointerup', 'pointercancel', 'pointerenter', 'pointerleave'];
        const fn = function(e) { cb(e.type, e.pointerType, e.pressure, e.buttons, e.pointerId, e.isPrimary); };
        names.forEach(function(n) { host.addEventListener(n, fn); });
        return { names: names, fn: fn };
    })()
    """,
)

private fun removePointerListeners(host: JsAny, handle: JsAny): Unit =
    js("(function() { handle.names.forEach(function(n) { host.removeEventListener(n, handle.fn); }); })()")
