// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import kotlin.math.roundToInt

/** The canvas the keys pan: its size in pixels and the pixels per dp, which turns the 40 dp and 200 dp steps into pixels. */
data class CanvasSize(val widthPx: Double, val heightPx: Double, val pxPerDp: Double)

/**
 * Keyboard panning (Story 3.6.1, UX-105, WCAG 2.1.1): an arrow key moves the view 40 dp, Shift+arrow 200 dp, PageUp and
 * PageDown one screen height, Home fits the content. [KeyDispatcher] decides when an arrow pans (nothing selected, the Hand
 * tool, or Ctrl or Cmd with the arrow); this turns the resulting [KeyAction] into the new viewport.
 */
object KeyboardPanning {
    const val STEP_DP = 40.0
    const val FAR_STEP_DP = 200.0

    /** The viewport after [action], or null when [action] is not a panning action. [fit] supplies the fit-to-content viewport for Home. */
    fun apply(action: KeyAction, viewport: SketchViewport, canvas: CanvasSize, fit: () -> SketchViewport): SketchViewport? = when (action) {
        // An arrow moves the VIEW, so the content shifts the other way.
        is KeyAction.Pan -> {
            val step = (if (action.far) FAR_STEP_DP else STEP_DP) * canvas.pxPerDp
            viewport.panBy(-action.dx * step, -action.dy * step)
        }
        is KeyAction.PanPage -> viewport.panBy(0.0, -action.direction * canvas.heightPx)
        is KeyAction.FitToContent -> fit()
        else -> null
    }

    /**
     * What the live region says after the view has settled: the zoom and the scene point at the canvas centre. Said once per
     * burst (last wins through `SketchNoticeQueue.announceStatus` with [STREAM_KEY]), never per key press.
     */
    fun settledAnnouncement(viewport: SketchViewport, canvas: CanvasSize): String {
        val centre = viewport.toScene(ScreenPx(canvas.widthPx / 2.0, canvas.heightPx / 2.0))
        return SketchStrings.Panning.settled((viewport.zoom * PERCENT).roundToInt(), centre.x.value.roundToInt(), centre.y.value.roundToInt())
    }

    const val STREAM_KEY = "view.settled"
    private const val PERCENT = 100.0
}
