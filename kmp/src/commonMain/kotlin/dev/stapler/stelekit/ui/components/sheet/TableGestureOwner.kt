// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.ui.components.TAG_TAG
import dev.stapler.stelekit.ui.components.WIKI_LINK_TAG
import kotlinx.coroutines.withTimeoutOrNull

/** Touch and pen entry count as `Touch` for the strip policy; mouse and anything else is `Hardware`. */
internal fun isTouchLikePointer(type: PointerType): Boolean = type == PointerType.Touch || type == PointerType.Stylus

internal sealed interface CellLink {
    data class Page(val name: String) : CellLink
    data class Url(val url: String) : CellLink
}

/** What the table-level gesture owner does with a tap. */
internal enum class GestureMode {
    /** Tap edits a cell, link tap navigates, long-press selects the block. */
    Interactive,

    /** The sheet owns the block: links still open, nothing edits. */
    ReadOnly,

    /** Block selection mode: only a tap toggles the block's selection (ADR-005 precedence 0). */
    BlockSelection,
}

/** Where each rendered cell and its text sit, so the single owner can resolve a pointer to a cell and a link. */
internal class CellHitRegistry {
    private class TextHit(val text: AnnotatedString, val layout: TextLayoutResult?, val coords: LayoutCoordinates?)

    private val cells = HashMap<CellAddress, LayoutCoordinates>()
    private val texts = HashMap<CellAddress, TextHit>()
    var owner: LayoutCoordinates? = null

    // The range handles sit inside the owner's box; a press that lands on one is the handle's gesture, not the owner's.
    private val handles = HashMap<HandleCorner, LayoutCoordinates>()

    fun setHandle(corner: HandleCorner, coords: LayoutCoordinates) {
        handles[corner] = coords
    }

    fun removeHandle(corner: HandleCorner) {
        handles.remove(corner)
    }

    /** True when [position] (owner coordinates) is inside a handle's hit area. */
    fun onHandle(position: Offset): Boolean {
        val ownerCoords = owner?.takeIf { it.isAttached } ?: return false
        return handles.values.any { c ->
            c.isAttached && Rect(0f, 0f, c.size.width.toFloat(), c.size.height.toFloat()).contains(c.localPositionOf(ownerCoords, position))
        }
    }

    /** Cells whose position the sheet's range handles follow; a change in their layout bumps [layoutVersion]. */
    var tracked: Set<CellAddress> = emptySet()
    var layoutVersion: Int by mutableIntStateOf(0)
        private set

    /** The column the sheet keeps visible (first-column freeze), or 0; its cells win a hit where they overlap scrolled ones. */
    var frozenColumn: Int = 0

    /** A composed cell's rectangle in the owner's coordinates, or null when it is off screen. */
    fun boundsInOwner(address: CellAddress): Rect? {
        layoutVersion
        val ownerCoords = owner?.takeIf { it.isAttached } ?: return null
        val coords = cells[address]?.takeIf { it.isAttached } ?: return null
        val origin = ownerCoords.localPositionOf(coords, Offset.Zero)
        return Rect(origin, androidx.compose.ui.geometry.Size(coords.size.width.toFloat(), coords.size.height.toFloat()))
    }

    /** A strip of the owner's coordinates that sticky chrome covers (the sheet's gutter); taps there resolve to no cell. */
    var shield: Rect? = null

    fun setCell(address: CellAddress, coords: LayoutCoordinates) {
        cells[address] = coords
        if (address in tracked) layoutVersion++
    }

    fun setText(address: CellAddress, text: AnnotatedString, layout: TextLayoutResult?, coords: LayoutCoordinates?) {
        texts[address] = TextHit(text, layout, coords)
    }

    fun remove(address: CellAddress) {
        cells.remove(address)
        texts.remove(address)
        if (address in tracked) layoutVersion++
    }

    /** [position] is in the owner's coordinates. */
    fun cellAt(position: Offset): CellAddress? {
        val ownerCoords = owner?.takeIf { it.isAttached } ?: return null
        if (shield?.contains(position) == true) return null
        if (frozenColumn > 0) {
            for ((address, coords) in cells) {
                if (address.col != frozenColumn || !coords.isAttached) continue
                val local = coords.localPositionOf(ownerCoords, position)
                if (Rect(0f, 0f, coords.size.width.toFloat(), coords.size.height.toFloat()).contains(local)) return address
            }
        }
        for ((address, coords) in cells) {
            if (!coords.isAttached) continue
            val local = coords.localPositionOf(ownerCoords, position)
            if (Rect(0f, 0f, coords.size.width.toFloat(), coords.size.height.toFloat()).contains(local)) return address
        }
        return null
    }

    /** The link under [position] (owner coordinates) inside [address]'s text, or null. Same priority as `WikiLinkText`. */
    fun linkAt(address: CellAddress, position: Offset): CellLink? {
        val ownerCoords = owner?.takeIf { it.isAttached } ?: return null
        val hit = texts[address] ?: return null
        val layout = hit.layout ?: return null
        val coords = hit.coords?.takeIf { it.isAttached } ?: return null
        if (hit.text.isEmpty()) return null
        val local = coords.localPositionOf(ownerCoords, position)
        // getOffsetForPosition snaps to the nearest glyph boundary, so test the glyph on either side of it.
        val boundary = layout.getOffsetForPosition(local)
        val glyph = listOf(boundary, boundary - 1).firstOrNull { it in hit.text.indices && layout.getBoundingBox(it).contains(local) } ?: return null
        val annotations = hit.text.getStringAnnotations(start = glyph, end = glyph + 1)
        annotations.firstOrNull { it.tag == WIKI_LINK_TAG }?.let { return CellLink.Page(it.item) }
        annotations.firstOrNull { it.tag == TAG_TAG }?.let { return CellLink.Page(it.item) }
        annotations.firstOrNull { it.tag == "link" || it.tag == "url" || it.tag == "image" }?.let { return CellLink.Url(it.item) }
        return null
    }
}

private val TOUCH_SLOP = 8.dp
private val MOUSE_SLOP = 3.dp

private sealed interface Release {
    data class Up(val position: Offset, val shift: Boolean = false) : Release
    data object Moved : Release
    data object Cancelled : Release
}

/**
 * The single pointer handler of an inline table. It watches the Initial pass without consuming, so the cells'
 * own no-op text handlers and the horizontal scroller are unaffected, and decides: tap on a link opens it, a plain
 * tap edits that cell, a long-press selects the block once. A drag past the dp slop is ignored (touch selection is
 * the platform's inside the editing cell, never a custom range). UNVERIFIED on device: gesture timing with the
 * real IME and TalkBack (spike 0.4a).
 */
@Composable
internal fun Modifier.tableGestureOwner(
    hits: CellHitRegistry,
    mode: GestureMode,
    editingCell: () -> CellAddress?,
    onEditCell: (CellAddress, PointerType) -> Unit,
    onLink: (CellLink) -> Unit,
    onLongPressSelect: (() -> Unit)?,
    onToggleSelect: () -> Unit,
    onRangeDrag: ((CellAddress, CellAddress) -> Unit)? = null,
    onShiftClick: ((CellAddress) -> Boolean)? = null,
    onLongPressCell: ((CellAddress) -> Unit)? = null,
    onSecondaryClick: ((CellAddress) -> Unit)? = null,
): Modifier {
    val currentMode = rememberUpdatedState(mode)
    val currentEditing = rememberUpdatedState(editingCell)
    val currentEdit = rememberUpdatedState(onEditCell)
    val currentLink = rememberUpdatedState(onLink)
    val currentLongPress = rememberUpdatedState(onLongPressSelect)
    val currentToggle = rememberUpdatedState(onToggleSelect)
    val currentRangeDrag = rememberUpdatedState(onRangeDrag)
    val currentShiftClick = rememberUpdatedState(onShiftClick)
    val currentLongPressCell = rememberUpdatedState(onLongPressCell)
    val currentSecondary = rememberUpdatedState(onSecondaryClick)
    return this
        .onGloballyPositioned { hits.owner = it }
        .pointerInput(hits) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (hits.onHandle(down.position)) {
                    awaitRelease(down.id, down.position, Float.MAX_VALUE)
                    return@awaitEachGesture
                }
                val slop = (if (down.type == PointerType.Mouse) MOUSE_SLOP else TOUCH_SLOP).toPx()
                val secondary = currentSecondary.value
                if (secondary != null && down.type == PointerType.Mouse && currentEvent.buttons.isSecondaryPressed) {
                    hits.cellAt(down.position)?.let(secondary)
                    awaitRelease(down.id, down.position, Float.MAX_VALUE)
                    return@awaitEachGesture
                }
                val release = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitRelease(down.id, down.position, slop) }
                if (release == null) {
                    val onLong = currentLongPress.value
                    val onLongCell = currentLongPressCell.value
                    val pressed = if (down.type == PointerType.Mouse) null else hits.cellAt(down.position)
                    if (currentMode.value == GestureMode.Interactive && onLongCell != null && pressed != null) {
                        onLongCell(pressed)
                        awaitRelease(down.id, down.position, Float.MAX_VALUE)
                        return@awaitEachGesture
                    }
                    if (currentMode.value == GestureMode.Interactive && onLong != null) {
                        onLong()
                        awaitRelease(down.id, down.position, Float.MAX_VALUE)
                        return@awaitEachGesture
                    }
                }
                val resolved = release ?: awaitRelease(down.id, down.position, slop)
                // A mouse drag past the slop selects a range; a touch drag never does (it scrolls or is the platform's).
                val drag = currentRangeDrag.value
                if (drag != null && startsMouseRangeDrag(resolved, down.type, currentMode.value)) {
                    val start = hits.cellAt(down.position)
                    if (start != null) dragRange(down.id, start, hits, drag)
                    return@awaitEachGesture
                }
                val up = resolved as? Release.Up ?: return@awaitEachGesture
                if (up.shift && down.type == PointerType.Mouse && currentMode.value == GestureMode.Interactive) {
                    val target = hits.cellAt(up.position)
                    if (target != null && currentShiftClick.value?.invoke(target) == true) return@awaitEachGesture
                }
                dispatchTap(up.position, down.type, hits, currentMode.value, currentEditing.value(), currentEdit.value, currentLink.value, currentToggle.value)
            }
        }
}

private suspend fun AwaitPointerEventScope.awaitRelease(
    id: androidx.compose.ui.input.pointer.PointerId,
    start: Offset,
    slop: Float,
): Release {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.size > 1) return Release.Cancelled
        val change = event.changes.firstOrNull { it.id == id } ?: return Release.Cancelled
        if (change.changedToUpIgnoreConsumed()) return Release.Up(change.position, event.keyboardModifiers.isShiftPressed)
        if ((change.position - start).getDistance() > slop) return Release.Moved
    }
}

private fun startsMouseRangeDrag(release: Release, type: PointerType, mode: GestureMode): Boolean =
    release == Release.Moved && type == PointerType.Mouse && mode == GestureMode.Interactive

private suspend fun AwaitPointerEventScope.dragRange(
    id: androidx.compose.ui.input.pointer.PointerId,
    start: CellAddress,
    hits: CellHitRegistry,
    onRange: (CellAddress, CellAddress) -> Unit,
) {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == id } ?: return
        hits.cellAt(change.position)?.let { onRange(start, it) }
        if (change.changedToUpIgnoreConsumed() || !change.pressed) return
    }
}

private fun dispatchTap(
    position: Offset,
    type: PointerType,
    hits: CellHitRegistry,
    mode: GestureMode,
    editing: CellAddress?,
    onEditCell: (CellAddress, PointerType) -> Unit,
    onLink: (CellLink) -> Unit,
    onToggleSelect: () -> Unit,
) {
    if (mode == GestureMode.BlockSelection) {
        onToggleSelect()
        return
    }
    val cell = hits.cellAt(position) ?: return
    // The editing cell's own text field handles taps (caret placement, platform selection handles).
    if (cell == editing) return
    hits.linkAt(cell, position)?.let { onLink(it); return }
    if (mode == GestureMode.Interactive) onEditCell(cell, type)
}
