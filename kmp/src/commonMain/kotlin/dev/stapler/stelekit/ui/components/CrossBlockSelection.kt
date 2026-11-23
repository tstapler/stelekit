// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult

/** Settings key for the cross-block drag-select feature flag. */
const val MULTI_BLOCK_DRAG_SELECT_KEY = "feature.multi_block_drag_select"

/** Provided by the app root from [MULTI_BLOCK_DRAG_SELECT_KEY]; [BlockList] reads it. */
@Suppress("CompositionLocalAllowlist") // threading the flag through BlockList -> BlockRenderer -> BlockItem -> BlockViewer would touch every row signature
val LocalMultiBlockDragSelect = compositionLocalOf { false }

/** Uuid of the block whose text [WikiLinkText] is rendering; null outside a [BlockList] row. */
@Suppress("CompositionLocalAllowlist") // threading the uuid through BlockList -> BlockRenderer -> BlockItem -> BlockViewer would touch every row signature
val LocalRenderedBlockUuid = compositionLocalOf<String?> { null }

/** A caret position inside the *rendered* text of one block. */
data class BlockTextPosition(val blockUuid: String, val offset: Int)

/** Drag selection from [anchor] (press) to [focus] (pointer), in either document direction. */
data class CrossBlockSelection(val anchor: BlockTextPosition, val focus: BlockTextPosition)

/**
 * Selected character range of [blockUuid] within its rendered text of length [textLength], or
 * null when the block lies outside the selection. [order] is the visible blocks in document order.
 * A block strictly between the endpoints yields the full `0..textLength` range.
 */
fun CrossBlockSelection.rangeFor(blockUuid: String, textLength: Int, order: List<String>): IntRange? {
    val a = order.indexOf(anchor.blockUuid)
    val f = order.indexOf(focus.blockUuid)
    val i = order.indexOf(blockUuid)
    if (a < 0 || f < 0 || i < 0) return null
    val (start, end) = if (a < f || (a == f && anchor.offset <= focus.offset)) anchor to focus else focus to anchor
    val si = order.indexOf(start.blockUuid)
    val ei = order.indexOf(end.blockUuid)
    if (i < si || i > ei) return null
    val from = if (i == si) start.offset.coerceIn(0, textLength) else 0
    val to = if (i == ei) end.offset.coerceIn(0, textLength) else textLength
    return from until to
}

/** Plain text of the selection in document order, one line per block (partial blocks sliced). */
fun CrossBlockSelection.selectedText(order: List<String>, renderedText: Map<String, String>): String =
    order.mapNotNull { uuid ->
        val text = renderedText[uuid] ?: return@mapNotNull null
        rangeFor(uuid, text.length, order)?.takeIf { !it.isEmpty() }?.let { text.substring(it.first, it.last + 1) }
    }.joinToString("\n")

/** Layout info a block's text registers so the list-level gesture can hit-test and highlight. */
class BlockTextInfo(val text: String, val layout: TextLayoutResult, val coords: LayoutCoordinates)

/** Per-[BlockList] holder for the current selection and the registered block layouts. */
@Stable
class CrossBlockSelectionState {
    var selection by mutableStateOf<CrossBlockSelection?>(null)
    var order: List<String> = emptyList()
    private val infos = HashMap<String, BlockTextInfo>()

    fun register(blockUuid: String, info: BlockTextInfo) { infos[blockUuid] = info }
    fun unregister(blockUuid: String) { infos.remove(blockUuid) }
    fun info(blockUuid: String): BlockTextInfo? = infos[blockUuid]
    fun clear() { selection = null }

    /** Highlight range for [blockUuid]'s rendered text, or null. Reads [selection] (snapshot-observed). */
    fun highlightFor(blockUuid: String, textLength: Int): IntRange? =
        selection?.rangeFor(blockUuid, textLength, order)?.takeIf { !it.isEmpty() }

    fun selectedText(): String? {
        val sel = selection ?: return null
        val texts = order.mapNotNull { u -> infos[u]?.let { u to it.text } }.toMap()
        return sel.selectedText(order, texts).ifEmpty { null }
    }

    /** Caret position under root-space [rootPos], snapping to the nearest block by vertical distance. */
    fun positionAt(rootPos: androidx.compose.ui.geometry.Offset): BlockTextPosition? {
        var best: Pair<String, Float>? = null
        for (uuid in order) {
            val c = infos[uuid]?.coords?.takeIf { it.isAttached } ?: continue
            val top = c.positionInRoot().y
            val bottom = top + c.size.height
            val d = when { rootPos.y < top -> top - rootPos.y; rootPos.y > bottom -> rootPos.y - bottom; else -> 0f }
            if (best == null || d < best.second) best = uuid to d
        }
        val (uuid, _) = best ?: return null
        val info = infos.getValue(uuid)
        val local = rootPos - info.coords.positionInRoot()
        return BlockTextPosition(uuid, info.layout.getOffsetForPosition(local).coerceIn(0, info.text.length))
    }

    /** True when [rootPos] lies inside some registered block's text bounds. */
    fun hitsText(rootPos: androidx.compose.ui.geometry.Offset): Boolean = order.any { u ->
        val c = infos[u]?.coords?.takeIf { it.isAttached } ?: return@any false
        val p = c.positionInRoot()
        rootPos.x >= p.x && rootPos.x <= p.x + c.size.width && rootPos.y >= p.y && rootPos.y <= p.y + c.size.height
    }
}

@Suppress("CompositionLocalAllowlist") // threading the state through BlockList -> BlockRenderer -> BlockItem -> BlockViewer would touch every row signature
val LocalCrossBlockSelectionState = compositionLocalOf<CrossBlockSelectionState?> { null }
