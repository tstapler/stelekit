// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.Modifiers
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import kotlin.math.exp
import kotlin.math.hypot

enum class RawPhase { Down, Move, Up, Cancel, Hover }

/** One platform pointer sample in screen pixels. [secondaryButton] is the right mouse button or a stylus barrel button. */
data class RawPointer(
    val id: Int,
    val kind: PointerKind,
    val phase: RawPhase,
    val position: ScreenPx,
    val pressure: Double? = null,
    val secondaryButton: Boolean = false,
    val modifiers: Modifiers = Modifiers.None,
    val timestamp: Long = 0L,
)

enum class WheelUnit { Pixel, Line, Page }

/** A wheel or trackpad scroll. [zoomModifier] is Ctrl (Cmd on macOS), which makes the wheel zoom instead of pan. */
data class WheelInput(val position: ScreenPx, val deltaX: Double, val deltaY: Double, val unit: WheelUnit = WheelUnit.Pixel, val zoomModifier: Boolean = false)

/** What the adapter asks the host to do. */
sealed interface PointerAction {
    /**
     * Feed [event] to the active tool, or to [toolOverride] when it is not null: an eraser-end stroke runs the Eraser
     * tool for that stroke only (UX-101), and the previous tool is active again from the next action on.
     */
    data class ToolInput(val event: InputEvent, val toolOverride: ToolId? = null) : PointerAction

    /** The view moved (pan or zoom); the adapter already holds the new viewport. */
    data class ViewChange(val viewport: SketchViewport) : PointerAction

    /** Secondary click (right mouse or stylus barrel button): open the context menu here. */
    data class ContextMenu(val at: ScenePoint) : PointerAction
}

/**
 * Normalises raw pointer input to [InputEvent]s and navigation (Story 3.1.2, UX-07, UX-09, UX-101).
 *
 * Touch navigates and stylus or mouse acts; Finger drawing ON makes a one-finger drag draw. Two fingers always
 * pinch and pan, and a second finger during a one-finger draw first cancels the tool preview (the tool sees the
 * second pointer's Down), then navigates. A touch that arrives while a stylus or mouse stroke is active is ignored
 * (palm). Pure state machine: no Compose, no clock, no coroutines. Platform palm classification and edge-pan strips
 * belong to Tasks 3.1.2g and 3.1.2h.
 */
class SketchPointerAdapter(
    viewport: SketchViewport = SketchViewport.of(),
    var fingerDrawing: Boolean = false,
    /** Height in pixels of one page of wheel scrolling. */
    var pageSizePx: Double = DEFAULT_PAGE_PX,
) {
    var viewport: SketchViewport = viewport
        private set

    private sealed interface Mode {
        data object Idle : Mode
        data class Tool(val id: Int, val kind: PointerKind, val override: ToolId?) : Mode
        data class Pan(val id: Int) : Mode
        data class Pinch(val a: Int, val b: Int) : Mode

        /** Waiting for every pointer to lift before a new gesture may start; actions are dropped meanwhile. */
        data object Drain : Mode
    }

    private var mode: Mode = Mode.Idle
    private val down = LinkedHashMap<Int, ScreenPx>()

    /** Replaces the viewport after a host-side change (fit, fold or rotate, sheet resize). */
    fun setViewport(value: SketchViewport) {
        viewport = value
    }

    fun onPointer(p: RawPointer): List<PointerAction> = when (p.phase) {
        RawPhase.Hover -> if (mode == Mode.Idle && p.kind != PointerKind.Touch) listOf(toolInput(p, InputType.Move, null)) else emptyList()
        RawPhase.Down -> onDown(p)
        RawPhase.Move -> onMove(p)
        RawPhase.Up -> onEnd(p, InputType.Up)
        RawPhase.Cancel -> onEnd(p, InputType.Cancel)
    }

    /** Esc: the tool abandons its gesture; the pointers still down are ignored until they lift. */
    fun onEscape(timestamp: Long = 0L): List<PointerAction> {
        val tool = (mode as? Mode.Tool)
        if (tool != null) mode = if (down.isEmpty()) Mode.Idle else Mode.Drain
        return listOf(PointerAction.ToolInput(InputEvent.escape(timestamp), tool?.override))
    }

    fun onWheel(w: WheelInput): List<PointerAction> {
        val dx = w.deltaX * wheelPixels(w.unit)
        val dy = w.deltaY * wheelPixels(w.unit)
        viewport = if (w.zoomModifier) viewport.zoomBy(w.position, exp(-dy * WHEEL_ZOOM_PER_PX)) else viewport.panBy(-dx, -dy)
        return listOf(PointerAction.ViewChange(viewport))
    }

    private fun wheelPixels(unit: WheelUnit): Double = when (unit) {
        WheelUnit.Pixel -> 1.0
        WheelUnit.Line -> WHEEL_LINE_PX
        WheelUnit.Page -> pageSizePx
    }

    private fun onDown(p: RawPointer): List<PointerAction> {
        down[p.id] = p.position
        return when (val m = mode) {
            Mode.Idle -> startGesture(p)
            is Mode.Tool -> when {
                p.kind != PointerKind.Touch || m.kind != PointerKind.Touch -> emptyList()
                else -> {
                    // A second finger during a one-finger draw: the tool cancels on seeing it, then the pair navigates.
                    val cancel = toolInput(p, InputType.Down, m.override)
                    mode = Mode.Pinch(m.id, p.id)
                    listOf(cancel)
                }
            }
            is Mode.Pan -> if (p.kind == PointerKind.Touch) {
                mode = Mode.Pinch(m.id, p.id)
                emptyList()
            } else {
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun startGesture(p: RawPointer): List<PointerAction> {
        val barrel = p.secondaryButton && p.kind != PointerKind.Touch && p.kind != PointerKind.Eraser
        return when {
            barrel -> {
                mode = Mode.Drain
                listOf(PointerAction.ContextMenu(viewport.toScene(p.position)))
            }
            p.kind == PointerKind.Eraser -> {
                mode = Mode.Tool(p.id, p.kind, ToolId.Eraser)
                listOf(toolInput(p, InputType.Down, ToolId.Eraser))
            }
            p.kind == PointerKind.Touch && !fingerDrawing -> {
                mode = Mode.Pan(p.id)
                emptyList()
            }
            else -> {
                mode = Mode.Tool(p.id, p.kind, null)
                listOf(toolInput(p, InputType.Down, null))
            }
        }
    }

    private fun onMove(p: RawPointer): List<PointerAction> {
        val previous = down[p.id] ?: return emptyList()
        down[p.id] = p.position
        return when (val m = mode) {
            is Mode.Tool -> if (p.id == m.id) listOf(toolInput(p, InputType.Move, m.override)) else emptyList()
            is Mode.Pan -> if (p.id == m.id) navigate(p.position.x - previous.x, p.position.y - previous.y) else emptyList()
            is Mode.Pinch -> pinch(m, p.id, previous)
            else -> emptyList()
        }
    }

    private fun navigate(dx: Double, dy: Double): List<PointerAction> {
        viewport = viewport.panBy(dx, dy)
        return listOf(PointerAction.ViewChange(viewport))
    }

    private fun pinch(m: Mode.Pinch, movedId: Int, movedFrom: ScreenPx): List<PointerAction> {
        if (movedId != m.a && movedId != m.b) return emptyList()
        val a = down[m.a] ?: return emptyList()
        val b = down[m.b] ?: return emptyList()
        val otherId = if (movedId == m.a) m.b else m.a
        val other = down[otherId] ?: return emptyList()
        val now = down[movedId] ?: return emptyList()
        val beforeCentre = ScreenPx((movedFrom.x + other.x) / PAIR, (movedFrom.y + other.y) / PAIR)
        val centre = ScreenPx((a.x + b.x) / PAIR, (a.y + b.y) / PAIR)
        val beforeSpan = hypot(movedFrom.x - other.x, movedFrom.y - other.y)
        val span = hypot(now.x - other.x, now.y - other.y)
        var v = viewport.panBy(centre.x - beforeCentre.x, centre.y - beforeCentre.y)
        if (beforeSpan > 0.0 && span > 0.0) v = v.zoomBy(centre, span / beforeSpan)
        viewport = v
        return listOf(PointerAction.ViewChange(viewport))
    }

    private fun onEnd(p: RawPointer, type: InputType): List<PointerAction> {
        down.remove(p.id) ?: return emptyList()
        val out = when (val m = mode) {
            is Mode.Tool -> if (p.id == m.id) {
                mode = Mode.Idle
                listOf(toolInput(p, type, m.override))
            } else {
                emptyList()
            }
            is Mode.Pan -> {
                if (p.id == m.id) mode = Mode.Idle
                emptyList()
            }
            is Mode.Pinch -> {
                if (p.id == m.a || p.id == m.b) mode = Mode.Drain
                emptyList()
            }
            else -> emptyList()
        }
        if (down.isEmpty()) mode = Mode.Idle
        return out
    }

    private fun toolInput(p: RawPointer, type: InputType, override: ToolId?): PointerAction.ToolInput {
        val pressure = if (p.kind == PointerKind.Stylus || p.kind == PointerKind.Eraser) p.pressure else null
        return PointerAction.ToolInput(
            InputEvent(type, viewport.toScene(p.position), pressure, p.kind, p.id, p.timestamp, p.modifiers),
            override,
        )
    }

    private companion object {
        const val DEFAULT_PAGE_PX = 800.0
        const val WHEEL_LINE_PX = 16.0
        const val WHEEL_ZOOM_PER_PX = 0.002
        const val PAIR = 2.0
    }
}
