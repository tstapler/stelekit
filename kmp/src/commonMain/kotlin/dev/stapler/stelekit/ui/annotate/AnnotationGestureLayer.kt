// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint

internal const val ANNOTATION_CANVAS_DESCRIPTION = "Annotation canvas — tap to place points"

private const val DOUBLE_TAP_WINDOW_MILLIS = 300L

/** A touch for the loupe: where to draw it ([local]) and which canvas pixel it samples ([canvas]). */
internal data class LoupeTouch(val local: Offset, val canvas: Offset)

/** What the gesture layer reports; positions are canvas pixels unless noted. */
internal class AnnotationGestureCallbacks(
    val onTap: (Offset) -> Unit,
    val onLoupe: (LoupeTouch?) -> Unit,
    /** Local (screen) position, since the ripple is drawn at constant size on screen. */
    val onRipple: (Offset) -> Unit,
    val onSelect: (String?) -> Unit,
    val onEditStart: () -> Unit,
    val onEditPoints: (String, List<NormalizedPoint>) -> Unit,
    val onEditEnd: (String) -> Unit,
)

/** The view the gesture layer reads and steers; every member is read at event time, never during composition. */
internal class AnnotationViewHandle(
    val canvasSize: () -> IntSize,
    val canvasOrigin: () -> Offset,
    val view: () -> AnnotationViewTransform,
    val geometry: () -> ViewGeometry,
    val setView: (AnnotationViewTransform) -> Unit,
) {
    fun local(position: Offset): Offset = position - canvasOrigin()
    fun canvas(position: Offset): Offset = view().toCanvas(local(position))
    fun touch(position: Offset): LoupeTouch = LoupeTouch(local(position), canvas(position))
    fun transform(centroid: Offset, pan: Offset, zoom: Float) = setView(view().transformed(local(centroid), pan, zoom, geometry()))
}

private class GestureEnv(
    val handle: AnnotationViewHandle,
    val callbacks: AnnotationGestureCallbacks,
    val annotations: () -> List<MeasurementAnnotation>,
    val tapThresholdPx: Float,
    val vertexRadiusPx: Float,
    val edgeRadiusPx: Float,
    val onEmptyTap: (TapRecord) -> Unit,
)

private enum class GestureEnd { DONE, HANDOFF_TO_VIEW }

/**
 * Transparent layer over the whole viewport that owns every touch. One finger drives the active tool
 * (select/move, or aim with the loupe and place on release). A second finger cancels whatever the tool
 * was doing, without placing or committing anything, and from then on the gesture only pinches and
 * pans the view. Mouse: middle-button drag pans, the wheel zooms about the pointer.
 *
 * Pointer positions arrive in viewport pixels and are converted through [handle], so tools see canvas
 * pixels exactly as they did before zoom existed.
 */
@Composable
internal fun AnnotationGestureLayer(
    currentTool: AnnotationTool,
    annotations: List<MeasurementAnnotation>,
    handle: AnnotationViewHandle,
    callbacks: AnnotationGestureCallbacks,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(currentTool) {
        onDispose { callbacks.onLoupe(null) }
    }
    val density = LocalDensity.current
    val currentAnnotations by rememberUpdatedState(annotations)
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = ANNOTATION_CANVAS_DESCRIPTION }
            .pointerInput(currentTool) {
                val env = newGestureEnv(handle, callbacks, { currentAnnotations }, density)
                awaitEachGesture { runGesture(env, currentTool) }
            }
            .pointerInput(Unit) { wheelZoom(handle) },
    )
}

private fun newGestureEnv(
    handle: AnnotationViewHandle,
    callbacks: AnnotationGestureCallbacks,
    annotations: () -> List<MeasurementAnnotation>,
    density: Density,
): GestureEnv {
    var lastTap: TapRecord? = null
    val doubleTapSlopPx = with(density) { 32.dp.toPx() }
    return GestureEnv(
        handle = handle,
        callbacks = callbacks,
        annotations = annotations,
        tapThresholdPx = with(density) { 16.dp.toPx() },
        vertexRadiusPx = with(density) { 28.dp.toPx() },
        edgeRadiusPx = with(density) { 20.dp.toPx() },
        onEmptyTap = { tap ->
            if (isDoubleTap(lastTap, tap, DOUBLE_TAP_WINDOW_MILLIS, doubleTapSlopPx)) {
                handle.setView(handle.view().toggledAt(handle.local(tap.position), handle.geometry()))
                lastTap = null
            } else {
                lastTap = tap
            }
        },
    )
}

private suspend fun AwaitPointerEventScope.runGesture(env: GestureEnv, tool: AnnotationTool) {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (currentEvent.buttons.isTertiaryPressed) return mousePan(env, down)
    val end = if (tool == AnnotationTool.SELECT) selectGesture(env, down) else placeGesture(env, down)
    if (end == GestureEnd.HANDOFF_TO_VIEW) viewGesture(env)
}

private fun PointerEvent.pressedCount(): Int = changes.count { it.pressed }

private fun PointerEvent.changeFor(down: PointerInputChange): PointerInputChange? = changes.firstOrNull { it.id == down.id }

/** Two-finger pinch and pan until every pointer lifts. */
private suspend fun AwaitPointerEventScope.viewGesture(env: GestureEnv) {
    while (true) {
        val event = awaitPointerEvent()
        if (event.pressedCount() == 0) break
        val zoom = event.calculateZoom()
        val pan = event.calculatePan()
        if (zoom != 1f || pan != Offset.Zero) {
            env.handle.transform(event.calculateCentroid(), pan, zoom)
            event.changes.forEach { if (it.pressed) it.consume() }
        }
    }
}

private suspend fun AwaitPointerEventScope.mousePan(env: GestureEnv, down: PointerInputChange) {
    down.consume()
    while (true) {
        val change = awaitPointerEvent().changeFor(down) ?: break
        if (!change.pressed) break
        env.handle.setView(env.handle.view().panned(change.position - change.previousPosition, env.handle.geometry()))
        change.consume()
    }
}

/** Mouse-wheel and trackpad scroll zoom about the pointer. Never consumes anything else. */
private suspend fun PointerInputScope.wheelZoom(handle: AnnotationViewHandle) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull() ?: continue
            val factor = wheelZoomFactor(change.scrollDelta.y)
            handle.transform(change.position, Offset.Zero, factor)
            change.consume()
        }
    }
}

private suspend fun AwaitPointerEventScope.placeGesture(env: GestureEnv, down: PointerInputChange): GestureEnd {
    val handle = env.handle
    down.consume()
    env.callbacks.onLoupe(handle.touch(down.position))
    var lastPosition = down.position
    while (true) {
        val event = awaitPointerEvent()
        if (event.pressedCount() >= 2) {
            // A second finger means a pinch, not a placement: drop the aim without placing.
            env.callbacks.onLoupe(null)
            return GestureEnd.HANDOFF_TO_VIEW
        }
        val change = event.changeFor(down) ?: break
        lastPosition = change.position
        change.consume()
        env.callbacks.onLoupe(handle.touch(lastPosition))
        if (!change.pressed) break
    }
    // The loupe is for aiming: drag to the crosshair, release to place — at any distance.
    env.callbacks.onRipple(handle.local(lastPosition))
    env.callbacks.onTap(handle.canvas(lastPosition))
    env.callbacks.onLoupe(null)
    return GestureEnd.DONE
}

private suspend fun AwaitPointerEventScope.selectGesture(env: GestureEnv, down: PointerInputChange): GestureEnd {
    val handle = env.handle
    val hit = hitTestAnnotations(
        annotations = env.annotations(),
        position = handle.local(down.position),
        canvasSize = handle.canvasSize(),
        vertexRadiusPx = env.vertexRadiusPx,
        edgeRadiusPx = env.edgeRadiusPx,
        view = handle.view(),
    ) ?: return emptySpaceGesture(env, down)
    down.consume()
    env.callbacks.onSelect(hit.uuid)
    return dragAnnotation(env, hit, down)
}

private suspend fun AwaitPointerEventScope.dragAnnotation(env: GestureEnv, hit: AnnotationHit, down: PointerInputChange): GestureEnd {
    val original = env.annotations().first { it.uuid == hit.uuid }.normalizedPoints
    var editing = false
    var end = GestureEnd.DONE
    try {
        while (true) {
            val event = awaitPointerEvent()
            if (event.pressedCount() >= 2) {
                end = GestureEnd.HANDOFF_TO_VIEW
                break
            }
            val change = event.changeFor(down) ?: break
            if (!editing && (change.position - down.position).getDistance() >= env.tapThresholdPx) {
                editing = true
                env.callbacks.onEditStart()
            }
            if (editing) {
                change.consume()
                applyEdit(env, hit, original, down, change)
            }
            if (!change.pressed) break
        }
    } finally {
        env.callbacks.onLoupe(null)
        // A pinch that interrupts a drag restores the annotation rather than leaving it half-moved.
        if (editing && end == GestureEnd.HANDOFF_TO_VIEW) env.callbacks.onEditPoints(hit.uuid, original)
        if (editing) env.callbacks.onEditEnd(hit.uuid)
    }
    return end
}

private fun applyEdit(
    env: GestureEnv,
    hit: AnnotationHit,
    original: List<NormalizedPoint>,
    down: PointerInputChange,
    change: PointerInputChange,
) {
    val handle = env.handle
    val size = handle.canvasSize()
    when (hit) {
        is AnnotationHit.Vertex -> {
            handle.canvas(change.position).toNormalized(size)?.let { moved ->
                env.callbacks.onEditPoints(hit.uuid, original.toMutableList().also { it[hit.index] = moved })
            }
            env.callbacks.onLoupe(handle.touch(change.position))
        }
        is AnnotationHit.Body -> if (size.width > 0 && size.height > 0) {
            val delta = handle.canvas(change.position) - handle.canvas(down.position)
            env.callbacks.onEditPoints(
                hit.uuid,
                translateClamped(original, delta.x.toDouble() / size.width, delta.y.toDouble() / size.height),
            )
        }
    }
}

/** SELECT on empty space: a tap deselects (and can complete a double tap); a second finger starts a pinch. */
private suspend fun AwaitPointerEventScope.emptySpaceGesture(env: GestureEnv, down: PointerInputChange): GestureEnd {
    while (true) {
        val event = awaitPointerEvent()
        if (event.pressedCount() >= 2) return GestureEnd.HANDOFF_TO_VIEW
        val change = event.changeFor(down) ?: return GestureEnd.DONE
        if (change.pressed) continue
        if ((change.position - down.position).getDistance() < env.tapThresholdPx) {
            env.callbacks.onSelect(null)
            env.onEmptyTap(TapRecord(change.uptimeMillis, change.position))
        }
        return GestureEnd.DONE
    }
}
