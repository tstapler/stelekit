// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.view.ScreenRect
import dev.stapler.stelekit.sketch.v2.view.SelectionReveal
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import kotlin.math.max
import kotlin.math.min

/** Where keyboard focus is inside the canvas region (ux.md section 10). */
sealed interface CanvasFocus {
    /** The canvas itself: shortcuts work here and Tab leaves the region (Esc, then Tab always leaves the canvas). */
    data object Container : CanvasFocus

    /** The "Skip canvas elements" link, the first Tab stop inside the region. */
    data object SkipLink : CanvasFocus

    data class Element(val id: ElementId) : CanvasFocus
}

/** The result of one Tab or Shift+Tab: focus moves inside the canvas region, or out of it to [region] (null when no other stop exists). */
sealed interface TabMove {
    data class Within(val focus: CanvasFocus) : TabMove

    data class Out(val region: TabStop?) : TabMove
}

/**
 * Tab and Shift+Tab inside the canvas region (Task 3.6.2b, UX-41, UX-117). Forward: the skip link, then the elements in paint
 * order, then the next stop of the one tab order after the canvas. Backward is the reverse. From the canvas container Tab
 * goes straight out, so Esc then Tab leaves the canvas at once; no press ever traps.
 */
object CanvasTraversal {
    /** Where focus lands when Tab ([backward] false) or Shift+Tab enters the canvas region from another stop. */
    fun enter(backward: Boolean, stops: List<ElementId>): CanvasFocus =
        if (backward) stops.lastOrNull()?.let { CanvasFocus.Element(it) } ?: CanvasFocus.SkipLink else CanvasFocus.SkipLink

    fun tab(current: CanvasFocus, backward: Boolean, stops: List<ElementId>, present: Set<TabStop>): TabMove {
        val sequence = listOf<CanvasFocus>(CanvasFocus.SkipLink) + stops.map { CanvasFocus.Element(it) }
        val at = sequence.indexOf(current)
        val next = when {
            current == CanvasFocus.Container || at < 0 -> -1
            backward -> at - 1
            else -> at + 1
        }
        return if (next in sequence.indices) TabMove.Within(sequence[next]) else TabMove.Out(neighbour(backward, present))
    }

    /** The "Skip canvas elements" activation: the next stop of the order after the canvas that exists (UX-117). */
    fun skip(present: Set<TabStop>): TabStop? = KeyboardRegions.skipCanvasTarget(present)

    /** F6 and Shift+F6 from anywhere inside the canvas region: the region after or before the canvas. */
    fun f6(backward: Boolean, present: Set<TabStop>): TabStop? = KeyboardRegions.f6Target(TabStop.Canvas, present, backward)

    /** Esc from an element returns focus to the canvas container (ux.md section 10). */
    fun escape(current: CanvasFocus): CanvasFocus = if (current is CanvasFocus.Element || current == CanvasFocus.SkipLink) CanvasFocus.Container else current

    private fun neighbour(backward: Boolean, present: Set<TabStop>): TabStop? {
        val order = KeyboardRegions.tabOrder(present)
        val at = order.indexOf(TabStop.Canvas)
        return if (backward) order.getOrNull(at - 1) else order.getOrNull(at + 1)
    }
}

/**
 * The two-tone focus ring (UX-45): a [innerDp] ring in [inner] (the theme's `onSurface`) inside a [outerDp] ring in [outer]
 * (its inverse), so at least one tone contrasts with any element fill and any canvas.
 */
data class FocusRingStyle(val inner: Color, val outer: Color, val innerDp: Dp = RING_DP, val outerDp: Dp = RING_DP) {
    companion object {
        val RING_DP = 2.dp

        /** The ring of a Material colour scheme: `onSurface` inside, `inverseOnSurface` outside. */
        fun of(onSurface: Color, inverseOnSurface: Color) = FocusRingStyle(inner = onSurface, outer = inverseOnSurface)
    }
}

/** WCAG contrast arithmetic for the ring (UX-45), pure so a test can sample the themes. */
object FocusRingContrast {
    /** The WCAG 2.x contrast ratio of two opaque colours, 1.0 to 21.0. */
    fun ratio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + LUMINANCE_OFFSET) / (min(la, lb) + LUMINANCE_OFFSET)
    }

    /** True when at least one tone of [style] meets [bar] against [beside]. */
    fun someToneContrasts(style: FocusRingStyle, beside: Color, bar: Double = NON_TEXT_BAR): Boolean =
        ratio(style.inner, beside) >= bar || ratio(style.outer, beside) >= bar

    const val NON_TEXT_BAR = 3.0
    private const val LUMINANCE_OFFSET = 0.05
}

/**
 * Where a focused element's ring sits and whether a panel hides it (UX-45, UX-87): the ring is the element box grown by the
 * ring widths. [revealed] is the viewport after the least pan that brings the ring inside [visible], the area no panel,
 * dock or sheet covers (D-52).
 */
object FocusRingPlacement {
    fun ringBounds(element: SceneBounds, style: FocusRingStyle, zoom: Double, density: Float): SceneBounds {
        val grow = (style.innerDp.value + style.outerDp.value) * density / zoom
        return SceneBounds(element.minX - grow, element.minY - grow, element.maxX + grow, element.maxY + grow)
    }

    fun revealed(viewport: SketchViewport, ring: SceneBounds, visible: ScreenRect): SketchViewport =
        SelectionReveal.reveal(viewport, ring, visible).viewport
}

/** Draws the two-tone ring just outside this node while it has focus. */
fun Modifier.focusRing(style: FocusRingStyle, focused: Boolean): Modifier = if (!focused) this else drawWithContent {
    drawContent()
    drawFocusRing(style, size)
}

/** The ring itself, drawn just outside a box of [boxSize] at the scope's origin; the audit renders it offscreen and samples the pixels. */
fun DrawScope.drawFocusRing(style: FocusRingStyle, boxSize: Size) {
    val inner = style.innerDp.toPx()
    val outer = style.outerDp.toPx()
    val innerInset = -inner / 2f
    val outerInset = -inner - outer / 2f
    drawRect(style.inner, topLeft = Offset(innerInset, innerInset), size = Size(boxSize.width - 2 * innerInset, boxSize.height - 2 * innerInset), style = Stroke(inner))
    drawRect(style.outer, topLeft = Offset(outerInset, outerInset), size = Size(boxSize.width - 2 * outerInset, boxSize.height - 2 * outerInset), style = Stroke(outer))
}

/** The ring colours of the current Material theme. */
@Composable
fun rememberFocusRingStyle(): FocusRingStyle {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme.onSurface, scheme.inverseOnSurface) { FocusRingStyle.of(scheme.onSurface, scheme.inverseOnSurface) }
}

object CanvasFocusTags {
    const val SKIP_LINK = "sketch.canvas.skip"
}

/**
 * "Skip canvas elements" (UX-117, WCAG 2.4.1): invisible until it has keyboard focus, then a labelled button; Enter, Space
 * or a click runs [onSkip], which moves focus to the stop [CanvasTraversal.skip] names.
 */
@Composable
fun SkipCanvasLink(onSkip: () -> Unit, modifier: Modifier = Modifier, requester: FocusRequester = remember { FocusRequester() }) {
    var focused by remember { mutableStateOf(false) }
    val label = SketchStrings.Semantics.SKIP_CANVAS_ELEMENTS
    val ring = rememberFocusRingStyle()
    Box(
        modifier
            .testTag(CanvasFocusTags.SKIP_LINK)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                val activates = e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.Spacebar)
                if (activates) onSkip()
                activates
            }
            .focusable()
            .semantics { role = Role.Button; contentDescription = label; onClick(label) { onSkip(); true } }
            .focusRing(ring, focused)
            .let { if (focused) it.border(1.dp, ring.inner).padding(horizontal = 12.dp, vertical = 12.dp) else it.size(1.dp) },
    ) {
        if (focused) Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Tab, Shift+Tab, F6, Shift+F6 and Esc on the canvas region, wired to [CanvasTraversal] (Task 3.6.2c). [focus] reads and
 * writes where focus is; [moveWithin] puts platform focus on a stop inside the region and [moveOut] on another region.
 */
fun Modifier.canvasRegionKeys(
    focus: () -> CanvasFocus,
    stops: () -> List<ElementId>,
    present: () -> Set<TabStop>,
    moveWithin: (CanvasFocus) -> Unit,
    moveOut: (TabStop) -> Unit,
): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val backward = e.isShiftPressed
    when (e.key) {
        Key.Tab -> {
            when (val move = CanvasTraversal.tab(focus(), backward, stops(), present())) {
                is TabMove.Within -> moveWithin(move.focus)
                is TabMove.Out -> move.region?.let(moveOut)
            }
            true
        }
        Key.F6 -> CanvasTraversal.f6(backward, present())?.let { moveOut(it); true } ?: false
        Key.Escape -> (focus() as? CanvasFocus.Element)?.let { moveWithin(CanvasTraversal.escape(it)); true } ?: false
        else -> false
    }
}
