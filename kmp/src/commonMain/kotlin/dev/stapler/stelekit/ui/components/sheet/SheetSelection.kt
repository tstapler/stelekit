// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridBounds
import dev.stapler.stelekit.ui.components.TableMessages

private val HANDLE_HIT = 48.dp
private val HANDLE_DRAWN = 12.dp

/** Which corner of the range a handle sits on: [Start] the top-left, [End] the bottom-right. */
internal enum class HandleCorner { Start, End }

/** The cell under the pointer, or one step outward when the pointer left the grid (the drag then scrolls by revealing the focus). */
internal fun handleTarget(hit: CellAddress?, pointer: Offset, size: Size, focus: CellAddress, bounds: GridBounds): CellAddress {
    if (hit != null) return hit
    val dRow = when {
        pointer.y < 0f -> -1
        pointer.y > size.height -> 1
        else -> 0
    }
    val dCol = when {
        pointer.x < 0f -> -1
        pointer.x > size.width -> 1
        else -> 0
    }
    return bounds.clamp(CellAddress((focus.row + dRow).coerceAtLeast(1), (focus.col + dCol).coerceAtLeast(1)))
}

/**
 * The two touch range handles (Story 5.4a): 12dp drawn, 48dp hit, centred on the range's top-left and bottom-right
 * corners. They follow their cells through [hits], so a scroll keeps them on the corners, and a handle whose cell
 * scrolled out of composition parks off screen. A drag reads the pointer in window coordinates because the handle moves
 * as the range changes; the opposite corner stays fixed. The Extend... picker is the non-drag path (WCAG 2.5.7).
 */
@Composable
internal fun RangeHandles(state: SheetState, hits: CellHitRegistry) {
    if (!state.rangeMode) return
    val range = state.selection.range
    SideEffect { hits.tracked = setOf(range.topLeft, range.bottomRight) }
    DisposableEffect(hits) { onDispose { hits.tracked = emptySet() } }
    Box(Modifier.fillMaxSize().testTag("rangeHandles")) {
        RangeHandle(state, hits, HandleCorner.Start, range.topLeft)
        RangeHandle(state, hits, HandleCorner.End, range.bottomRight)
    }
}

@Composable
private fun RangeHandle(state: SheetState, hits: CellHitRegistry, corner: HandleCorner, cell: CellAddress) {
    val density = LocalDensity.current
    val half = with(density) { (HANDLE_HIT / 2).roundToPx() }
    var coords: LayoutCoordinates? by remember { mutableStateOf(null) }
    DisposableEffect(hits, corner) { onDispose { hits.removeHandle(corner) } }
    val label = if (corner == HandleCorner.Start) TableMessages.RANGE_START_HANDLE else TableMessages.RANGE_END_HANDLE
    Box(
        modifier = Modifier
            .offset {
                val b = hits.boundsInOwner(cell) ?: return@offset IntOffset(OFFSCREEN, OFFSCREEN)
                val x = if (corner == HandleCorner.Start) b.left else b.right
                val y = if (corner == HandleCorner.Start) b.top else b.bottom
                IntOffset(x.toInt() - half, y.toInt() - half)
            }
            .size(HANDLE_HIT)
            .onGloballyPositioned {
                coords = it
                hits.setHandle(corner, it)
            }
            .semantics { contentDescription = label }
            .testTag(if (corner == HandleCorner.Start) "handleStart" else "handleEnd")
            .pointerInput(corner) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val r = state.selection.range
                    val fixed = if (corner == HandleCorner.Start) r.bottomRight else r.topLeft
                    drag(down.id) { change ->
                        change.consume()
                        val handle = coords ?: return@drag
                        val owner = hits.owner?.takeIf { it.isAttached } ?: return@drag
                        val local = owner.windowToLocal(handle.localToWindow(change.position))
                        val hit = hits.cellAt(local)
                        val target = handleTarget(hit, local, Size(owner.size.width.toFloat(), owner.size.height.toFloat()), state.selection.focus, state.bounds)
                        state.dragHandle(fixed, target)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(HANDLE_DRAWN).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}

private const val OFFSCREEN = -100_000
