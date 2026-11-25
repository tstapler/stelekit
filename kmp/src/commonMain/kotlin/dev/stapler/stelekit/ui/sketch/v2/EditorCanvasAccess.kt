// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.semantics.CustomAccessibilityAction
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchOutlineStrings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.view.ScreenRect
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchA11yStrings

/**
 * The canvas region's keyboard and screen-reader access (Stories 3.6.2 and 3.6.6): which stop of the region has focus, the
 * per-element semantics tree, Tab and F6 through [CanvasTraversal], and the pan that keeps a focused element clear of the
 * overlays ([FocusReveal]). [window] is the chrome table's plan for this window, or null when the host measures it itself.
 */
class EditorCanvasAccess(private val controller: SketchEditorController, private val window: WindowDp?) {
    /** Where focus is inside the canvas region. */
    var focus: CanvasFocus by mutableStateOf(CanvasFocus.Container)
        private set

    /** The shell's regions, set by the shell once it composes them; null before that. */
    var regions: RegionFocus? = null

    val skipRequester = FocusRequester()
    private val requesters = HashMap<ElementId, FocusRequester>()

    /** The regions on screen, which decide where Tab and F6 go. */
    val present: Set<TabStop> get() = regions?.present.orEmpty()

    /** The canvas as a screen reader sees it, for the document as it is now. */
    fun tree(): SemanticsTree {
        val size = controller.view.canvasSize
        val view = SemanticsView(controller.ui.viewport, size.x, size.y)
        return SceneSemantics.build(controller.doc.scene, view, controller.ui.selection.mapTo(LinkedHashSet(), ::ElementId))
    }

    fun requestersFor(tree: SemanticsTree): Map<ElementId, FocusRequester> =
        tree.nodes.associate { it.id to requesters.getOrPut(it.id) { FocusRequester() } }

    /** Puts focus on [target] inside the canvas region. */
    fun moveWithin(target: CanvasFocus) {
        focus = target
        when (target) {
            CanvasFocus.Container -> regions?.focus(TabStop.Canvas)
            CanvasFocus.SkipLink -> runCatching { skipRequester.requestFocus() }
            is CanvasFocus.Element -> requesters[target.id]?.let { runCatching { it.requestFocus() } }
        }
    }

    /** "Skip canvas elements": focus goes to the next stop of the tab order after the canvas. */
    fun skip() {
        CanvasTraversal.skip(present)?.let { regions?.focus(it) }
    }

    fun moveOut(stop: TabStop) {
        regions?.focus(stop)
    }

    /** A node took keyboard focus: the view pans (never zooms) until the element and its ring are clear of the overlays. */
    fun onFocusElement(id: ElementId, ring: FocusRingStyle, density: Float) {
        focus = CanvasFocus.Element(id)
        val element = controller.doc.scene[id] ?: return
        val bounds = SceneBounds.of(element) ?: return
        val size = controller.view.canvasSize
        val visible = ScreenRect(0.0, 0.0, size.x, size.y)
        val ringed = FocusRingPlacement.ringBounds(bounds, ring, controller.ui.viewport.zoom, density)
        var viewport = FocusRingPlacement.revealed(controller.ui.viewport, ringed, visible)
        val centre = viewport.toScreen(ScenePoint(unit((bounds.minX + bounds.maxX) / 2), unit((bounds.minY + bounds.maxY) / 2)))
        viewport = FocusReveal.reveal(viewport, centre.x, centre.y, visible, overlaysOf(density)) ?: viewport
        if (viewport != controller.ui.viewport) controller.ui.viewport = viewport
    }

    private fun overlaysOf(density: Float): List<OverlayRect> {
        val size = window ?: return emptyList()
        val plan = ChromeBudget.plan(size.width, size.height, FONT_SCALE)
        val scale = density.toDouble()
        return FocusReveal.overlays(plan, FONT_SCALE, controller.ui.panelTab != null).map {
            val r = it.rect
            OverlayRect(it.name, ScreenRect(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale))
        }
    }

    private fun unit(v: Double) = SceneUnit.of(v).getOrNull() ?: SceneUnit.ZERO

    private companion object {
        const val FONT_SCALE = 1.0
    }
}

/**
 * The canvas semantics layer, the "Skip canvas elements" link and the focus ring of the focused element. The layer draws
 * nothing; the ring uses the editor's two-tone style, with the high-contrast tokens when the theme's pairs would fall short.
 */
@Composable
internal fun BoxScope.CanvasAccessLayer(controller: SketchEditorController, access: EditorCanvasAccess) {
    val viewport = controller.ui.viewport
    val density = LocalDensity.current.density
    val ring = rememberSketchFocusRing()
    val tree = remember(controller.revision, viewport, controller.ui.selection, controller.view.canvasSize) { access.tree() }
    SceneSemanticsLayer(
        tree = tree,
        viewport = viewport,
        requesters = access.requestersFor(tree),
        onFocusElement = { id -> access.onFocusElement(id, ring, density) },
        canvasActions = canvasActions(controller),
    )
    SkipCanvasLink(onSkip = access::skip, modifier = Modifier.align(Alignment.TopStart), requester = access.skipRequester)
    (access.focus as? CanvasFocus.Element)?.let { focused -> FocusedElementRing(controller, focused.id, ring) }
}

private fun canvasActions(controller: SketchEditorController): List<CustomAccessibilityAction> = buildList {
    add(CustomAccessibilityAction(SketchOutlineStrings.ACTION_OUTLINE) { controller.overlay = EditorOverlay.Outline; true })
    if (!controller.readOnly) add(CustomAccessibilityAction(SketchOutlineStrings.ACTION_ADD_SHAPE) { controller.overlay = EditorOverlay.AddShapeAt; true })
}

@Composable
private fun FocusedElementRing(controller: SketchEditorController, id: ElementId, style: FocusRingStyle) {
    val bounds = controller.doc.scene[id]?.let { SceneBounds.of(it) } ?: return
    val viewport = controller.ui.viewport
    val density = LocalDensity.current
    val left = (bounds.minX * viewport.zoom + viewport.panX).toInt()
    val top = (bounds.minY * viewport.zoom + viewport.panY).toInt()
    val width = with(density) { (bounds.width * viewport.zoom).toFloat().coerceAtLeast(1f).toDp() }
    val height = with(density) { (bounds.height * viewport.zoom).toFloat().coerceAtLeast(1f).toDp() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.offset { IntOffset(left, top) }.size(width, height).focusRing(style, focused = true))
    }
}

/** The focus ring of the theme, swapped for the high-contrast tokens when [SketchTokens] finds a ring pair below its 3:1 bar. */
@Composable
internal fun rememberSketchFocusRing(): FocusRingStyle {
    val scheme = MaterialTheme.colorScheme
    val themed = rememberFocusRingStyle()
    return remember(scheme, themed) {
        val ringFails = SketchTokens.failing(scheme).any { it.kind == TokenKind.NonText && it.name in RING_PAIR_NAMES }
        if (ringFails) {
            val tokens = SketchTokens.highContrast(dark = scheme.background.luminance() < DARK_LUMINANCE)
            FocusRingStyle.of(tokens.onSurface, tokens.inverseOnSurface)
        } else {
            themed
        }
    }
}

private val RING_PAIR_NAMES = setOf(SketchA11yStrings.Token.RING_HIGHLIGHT, SketchA11yStrings.Token.RING_INNER, SketchA11yStrings.Token.RING_OUTER)
private const val DARK_LUMINANCE = 0.5f
