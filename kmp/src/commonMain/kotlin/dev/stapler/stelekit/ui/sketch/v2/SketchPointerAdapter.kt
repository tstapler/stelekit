// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import kotlin.math.exp
import kotlin.math.hypot

/** A rectangle of the canvas in screen pixels; the edge-pan strip's hit area (Task 3.1.2g). */
data class ScreenRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    operator fun contains(p: ScreenPx): Boolean = p.x >= left && p.x < right && p.y >= top && p.y < bottom
}

/** What the host learns from the adapter besides the actions: the first stylus pen-up, finger drags and cancelled strip drags. */
interface PointerGestureListener {
    /** A stylus stroke ended with pen-up (never mid-gesture): the moment `StylusSeen` may flip Finger drawing (UX-102). */
    fun onStylusStrokeEnd() = Unit

    /** A one-finger drag with a drawing tool started, whether it pans (Finger drawing OFF) or draws (ON); fires once per drag (UX-80). */
    fun onOneFingerDrag() = Unit

    /** The system cancelled a one-finger drag that started in the edge strip (the backstop of Task 3.1.2g). */
    fun onStripDragCancelled() = Unit
}

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
 * (palm). Pure state machine: no Compose, no clock, no coroutines. With the Select tool, a touch that starts on empty
 * canvas pans (UX-102); with Finger drawing ON a touch that starts in an [edgeStrips] rectangle pans (UX-121). Platform
 * palm classification is Task 3.1.2h.
 */
class SketchPointerAdapter(
    viewport: SketchViewport = SketchViewport.of(),
    var fingerDrawing: Boolean = false,
    /** Height in pixels of one page of wheel scrolling. */
    var pageSizePx: Double = DEFAULT_PAGE_PX,
    /** True while the Hand tool is active: a primary drag by any pointer pans instead of reaching a tool (Task 3.2.3). */
    var handTool: Boolean = false,
    /** True while the active tool acts on a tap (Eraser, Select): a touch tap with Finger drawing OFF then reaches the tool as Down and Up (UX-101). */
    var touchTapsReachTool: Boolean = false,
) {
    /** True while the Select tool is active: a one-finger drag that starts on empty canvas pans instead of drawing a marquee (UX-102). */
    var selectTool: Boolean = false

    /** Whether a screen position is empty canvas (no element, no handle); the host answers from its hit tester. */
    var isEmptyCanvas: (ScreenPx) -> Boolean = { false }

    /** True while a drawing tool is active, which is when a one-finger drag raises the Finger drawing hint. */
    var drawingTool: Boolean = false

    /** The edge-pan strips' hit rectangles; with Finger drawing ON a one-finger drag that starts inside one pans (UX-121). */
    var edgeStrips: List<ScreenRect> = emptyList()

    /** True while a tool that takes every finger (the underlay adjust) is active: touches then reach it, a second finger included, instead of panning. */
    var multiPointerTool: Boolean = false

    /** True while any underlay tool is active: a touch goes to it even with Finger drawing off, so the loupe works. */
    var underlayTool: Boolean = false

    var listener: PointerGestureListener? = null

    /** Told of the kind of every pointer that touches the canvas, so the host learns when a touch or pen first shows up (UX-46). */
    var onKindSeen: (PointerKind) -> Unit = {}

    var viewport: SketchViewport = viewport
        private set

    private sealed interface Mode {
        data object Idle : Mode
        data class Tool(val id: Int, val kind: PointerKind, val override: ToolId?, val announced: Boolean = false, val others: Set<Int> = emptySet()) : Mode
        data class Pan(
            val id: Int,
            val start: ScreenPx,
            val travelled: Double = 0.0,
            val kind: PointerKind = PointerKind.Touch,
            val fromStrip: Boolean = false,
            val announced: Boolean = false,
        ) : Mode
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

    /** Timestamp of the latest stylus or eraser-end sample (hover or contact), or null once the pen left range or was never seen. */
    private var penLastSeen: Long? = null

    /** True while a pen hovers over or touches the canvas: the resting hand is then not a gesture (stylus palm rejection). */
    private fun penInRange(now: Long): Boolean = penLastSeen?.let { now - it <= PEN_RANGE_MS } == true

    private fun trackPen(p: RawPointer) {
        if (p.kind != PointerKind.Stylus && p.kind != PointerKind.Eraser) return
        penLastSeen = if (p.phase == RawPhase.Cancel) null else p.timestamp
    }

    fun onPointer(p: RawPointer): List<PointerAction> {
        trackPen(p)
        // A finger that lands while the pen is in range is a resting hand: it is dropped whole (its Move and Up find no Down to follow).
        if (p.kind == PointerKind.Touch && p.phase == RawPhase.Down && penInRange(p.timestamp)) return emptyList()
        if (p.phase == RawPhase.Down && !p.palm) onKindSeen(p.kind)
        return when {
            // A palm never starts or moves a gesture; its Up and Cancel still run so a contact that turned into a palm cannot wedge the state.
            p.palm && p.phase != RawPhase.Up && p.phase != RawPhase.Cancel -> emptyList()
            else -> onClassified(p)
        }
    }

    private fun onClassified(p: RawPointer): List<PointerAction> = when (p.phase) {
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
                multiPointerTool -> {
                    mode = m.copy(others = m.others + p.id)
                    listOf(toolInput(p, InputType.Down, m.override))
                }
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
            p.kind == PointerKind.Touch && fingerDrawing && edgeStrips.any { p.position in it } -> {
                mode = Mode.Pan(p.id, p.position, kind = p.kind, fromStrip = true)
                emptyList()
            }
            p.kind == PointerKind.Touch && underlayTool -> {
                mode = Mode.Tool(p.id, p.kind, null)
                listOf(toolInput(p, InputType.Down, null))
            }
            (p.kind == PointerKind.Touch && (!fingerDrawing || (selectTool && isEmptyCanvas(p.position)))) || handTool -> {
                mode = Mode.Pan(p.id, p.position, kind = p.kind)
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
            is Mode.Tool -> if (p.id == m.id || p.id in m.others) {
                if (!m.announced && announceFingerDrag(m.kind)) mode = m.copy(announced = true)
                listOf(toolInput(p, InputType.Move, m.override))
            } else {
                emptyList()
            }
            is Mode.Pan -> if (p.id == m.id) {
                val travelled = hypot(p.position.x - m.start.x, p.position.y - m.start.y).coerceAtLeast(m.travelled)
                val dragging = travelled > TAP_SLOP_PX && !m.fromStrip && !handTool
                val announced = m.announced || (dragging && announceFingerDrag(m.kind))
                mode = m.copy(travelled = travelled, announced = announced)
                navigate(p.position.x - previous.x, p.position.y - previous.y)
            } else {
                emptyList()
            }
            is Mode.Pinch -> pinch(m, p.id, previous)
            else -> emptyList()
        }
    }

    /** Tells the listener, once per drag, that a one-finger drag began with a drawing tool; true when it was told. */
    private fun announceFingerDrag(kind: PointerKind): Boolean {
        if (kind != PointerKind.Touch || !drawingTool) return false
        listener?.onOneFingerDrag()
        return true
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
            is Mode.Tool -> if (p.id in m.others) {
                mode = m.copy(others = m.others - p.id)
                listOf(toolInput(p, type, m.override))
            } else if (p.id == m.id && m.others.isNotEmpty()) {
                // The first finger lifted while a second still holds the photo: the second carries on as the gesture's pointer.
                val heir = m.others.first()
                mode = m.copy(id = heir, others = m.others - heir)
                listOf(toolInput(p, type, m.override))
            } else if (p.id == m.id) {
                mode = Mode.Idle
                if (m.kind == PointerKind.Stylus && type == InputType.Up) listener?.onStylusStrokeEnd()
                listOf(toolInput(p, type, m.override))
            } else {
                emptyList()
            }
            is Mode.Pan -> {
                if (p.id == m.id) mode = Mode.Idle
                if (p.id == m.id && m.fromStrip && type == InputType.Cancel) listener?.onStripDragCancelled()
                if (p.id == m.id && type == InputType.Up && isTap(m)) tapActions(p) else emptyList()
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

    /** A touch that never left the tap slop while the active tool takes taps (and the Hand tool is not the one panning). */
    private fun isTap(m: Mode.Pan): Boolean = touchTapsReachTool && !handTool && m.kind == PointerKind.Touch && m.travelled <= TAP_SLOP_PX

    private fun tapActions(p: RawPointer): List<PointerAction> =
        listOf(toolInput(p, InputType.Down, null), toolInput(p, InputType.Up, null))

    private fun toolInput(p: RawPointer, type: InputType, override: ToolId?): PointerAction.ToolInput {
        val pressure = if (p.kind == PointerKind.Stylus || p.kind == PointerKind.Eraser) p.pressure else null
        return PointerAction.ToolInput(
            InputEvent(type, viewport.toScene(p.position), pressure, p.kind, p.id, p.timestamp, p.modifiers, p.clickCount),
            override,
        )
    }

    private companion object {
        const val DEFAULT_PAGE_PX = 800.0

        /** Hover samples arrive every frame, so a pen with no sample for this long has left range even if no Exit came. */
        const val PEN_RANGE_MS = 500L

        /** A touch that moves no further than this many screen pixels is a tap, not a pan. */
        const val TAP_SLOP_PX = 10.0
        const val WHEEL_LINE_PX = 16.0
        const val WHEEL_ZOOM_PER_PX = 0.002
        const val PAIR = 2.0
    }
}
