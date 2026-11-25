// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import kotlin.math.abs
import kotlin.math.round

/** One element the screen reader can reach: [bounds] are scene units, [locked] elements are announced but never tab stops. */
data class SemanticsNode(val id: ElementId, val label: String, val bounds: SceneBounds, val selected: Boolean, val locked: Boolean)

/** What the canvas exposes to a screen reader: the [nodes] (at most `SceneLimits.maxSemanticsNodes`) and the cap [statement], when one applies. */
data class SemanticsTree(val nodes: List<SemanticsNode>, val statement: String?) {
    /** The nodes Tab visits, in paint order: a locked element is never a stop (the one selectability rule, UX-12). */
    val tabStops: List<ElementId> get() = nodes.filterNot { it.locked }.map { it.id }

    companion object {
        val Empty = SemanticsTree(emptyList(), null)
    }
}

/** The canvas as the semantics sees it: the [viewport] and the size of the canvas area in pixels. */
data class SemanticsView(val viewport: SketchViewport, val widthPx: Double, val heightPx: Double) {
    /** The part of the scene on screen, in scene units. */
    fun sceneBounds(): SceneBounds {
        val topLeft = viewport.toScene(ScreenPx(0.0, 0.0))
        val bottomRight = viewport.toScene(ScreenPx(widthPx, heightPx))
        return SceneBounds(topLeft.x.value, topLeft.y.value, bottomRight.x.value, bottomRight.y.value)
    }
}

/**
 * Per-element semantics (Story 3.6.2, UX-44): one node per element with a label by kind, bounded by the viewport. A sheet
 * of at most `maxSemanticsNodes` elements emits them all; a larger one emits the selected elements first and then the
 * elements in view, up to the cap, and says so in [SemanticsTree.statement] (reach the others with Go to element).
 * Elements of a hidden layer and labels bound to a container are never nodes: the container's label carries the text.
 */
object SceneSemantics {
    fun build(
        scene: SketchSceneV2,
        view: SemanticsView,
        selection: Set<ElementId>,
        annotations: Map<ElementId, String> = emptyMap(),
    ): SemanticsTree {
        val layers = scene.layers.associateBy { it.id }
        val eligible = scene.elementsInOrder.filter { isNode(it, layers) }
        val cap = scene.limits.maxSemanticsNodes
        val chosen = if (eligible.size <= cap) eligible else capped(eligible, view, selection, cap)
        val nodes = chosen.mapNotNull { e ->
            val bounds = SceneBounds.of(e) ?: return@mapNotNull null
            SemanticsNode(e.id, label(scene, e, annotations), bounds, e.id in selection, isLocked(e, layers))
        }
        val statement = if (eligible.size > cap) SketchStrings.Semantics.capStatement(nodes.size, eligible.size) else null
        return SemanticsTree(nodes, statement)
    }

    /** The selected elements first, then the elements in view in paint order, [cap] in all; the result stays in paint order. */
    private fun capped(eligible: List<SceneElement>, view: SemanticsView, selection: Set<ElementId>, cap: Int): List<SceneElement> {
        val inView = view.sceneBounds()
        val picked = LinkedHashSet<ElementId>()
        eligible.filter { it.id in selection }.take(cap).forEach { picked += it.id }
        for (e in eligible) {
            if (picked.size >= cap) break
            if (e.id !in picked && SceneBounds.of(e)?.intersects(inView) == true) picked += e.id
        }
        return eligible.filter { it.id in picked }
    }

    internal fun isNode(e: SceneElement, layers: Map<LayerId, Layer>): Boolean =
        (layers[e.base.layerId] ?: Layer.DefaultLayer).visible && !(e is TextElement && e.containerId != null)

    private fun isLocked(e: SceneElement, layers: Map<LayerId, Layer>): Boolean = e.base.locked || (layers[e.base.layerId]?.locked == true)

    /** The spoken label of [element]: its name, its kind-specific detail, then "locked" where it applies. */
    fun label(scene: SketchSceneV2, element: SceneElement, annotations: Map<ElementId, String> = emptyMap()): String {
        val core = describe(scene, element)
        val annotated = annotations[element.id]?.let { SketchStrings.Semantics.withDetail(core, it) } ?: core
        val locked = element.base.locked || scene.layer(element.base.layerId)?.locked == true
        return if (locked) SketchStrings.Semantics.lockedSuffix(annotated) else annotated
    }

    /** The description template of every kind: an exhaustive `when`, so a new kind cannot compile without its words. */
    internal fun describe(scene: SketchSceneV2, e: SceneElement): String {
        val name = ElementNames.nameOf(e)
        val s = SketchStrings.Semantics
        return when (e) {
            is RectElement -> labelled(scene, e, s.sized(name, units(e.width.value), units(e.height.value)))
            is DiamondElement -> labelled(scene, e, s.sized(name, units(e.width.value), units(e.height.value)))
            is EllipseElement ->
                labelled(scene, e, if (e.isCircle) s.radius(name, units(e.rx.value)) else s.sized(name, units(e.rx.value * 2), units(e.ry.value * 2)))
            is LineElement -> describeLine(name, e)
            is ArrowElement -> describeArrow(scene, e, name)
            is FreedrawElement -> name
            is TextElement -> s.quoted(name, spoken(e.text, scene))
            is ImageElement -> s.sized(name, units(e.width.value), units(e.height.value))
            is FrameElement -> s.frame(name, scene.elementsInOrder.count { it.base.frameId == e.id })
            is PointElement -> s.pointAt(name, units(e.x.value), units(e.y.value))
            is ArcElement -> s.radius(name, units(e.radius.value))
            is WallElement -> s.wall(name, units(WallGeometry.length(e)), units(e.thickness.value))
            is OpeningElement -> s.opening(name, units(e.width.value))
        }
    }

    private fun describeLine(name: String, e: LineElement): String {
        val s = SketchStrings.Semantics
        val points = e.points
        if (points.size != TWO_POINTS) return s.polyline(name, points.size)
        val (a, b) = points
        val dx = b.x.value - a.x.value
        val dy = b.y.value - a.y.value
        return when {
            dy == 0.0 -> s.lineLength(name, s.HORIZONTAL, units(abs(dx)))
            dx == 0.0 -> s.lineLength(name, s.VERTICAL, units(abs(dy)))
            else -> s.polyline(name, points.size)
        }
    }

    /** "Arrow from API to DB": the bound ends read as their text label, else their name; a user-named arrow keeps its name first. */
    private fun describeArrow(scene: SketchSceneV2, e: ArrowElement, name: String): String {
        val s = SketchStrings.Semantics
        val from = e.startBinding?.let { endName(scene, it.elementId) }
        val to = e.endBinding?.let { endName(scene, it.elementId) }
        val sentence = when {
            from != null && to != null -> s.arrowBetween(from, to)
            from != null -> s.arrowFrom(from)
            to != null -> s.arrowTo(to)
            else -> return name
        }
        return if (e.base.name.isNullOrBlank()) sentence else s.arrowNamed(name, sentence)
    }

    private fun endName(scene: SketchSceneV2, id: ElementId): String? =
        scene[id]?.let { target -> boundText(scene, target)?.let { spoken(it, scene) } ?: ElementNames.nameOf(target) }

    /** The text of the label bound to a container, or null. */
    private fun boundText(scene: SketchSceneV2, e: SceneElement): String? {
        val refs = when (e) {
            is RectElement -> e.boundElements
            is DiamondElement -> e.boundElements
            is EllipseElement -> e.boundElements
            else -> return null
        }
        return refs.firstOrNull { it.kind == BoundKind.Text }?.let { (scene[it.id] as? TextElement)?.text }?.takeIf { it.isNotBlank() }
    }

    private fun labelled(scene: SketchSceneV2, e: SceneElement, base: String): String =
        boundText(scene, e)?.let { SketchStrings.Semantics.container(base, spoken(it, scene)) } ?: base

    private fun spoken(text: String, scene: SketchSceneV2): String = text.lineSequence().first().trim().take(scene.limits.maxNameChars)

    private fun units(value: Double): Long = round(value).toLong()

    private const val TWO_POINTS = 2
}

private fun SceneBounds.intersects(o: SceneBounds): Boolean = maxX >= o.minX && minX <= o.maxX && maxY >= o.minY && minY <= o.maxY

/** The canvas is an application region: NVDA and JAWS pass keys through to it (UX-108). Compose has no such role, so it is a custom property. */
val CanvasApplicationRole = SemanticsPropertyKey<Boolean>("sketch.canvas.application-role")

var SemanticsPropertyReceiver.canvasApplicationRole: Boolean by CanvasApplicationRole

/**
 * The semantics layer over the canvas (Task 3.6.2a): one focusable node per [SemanticsTree] entry at the element's place on
 * screen, plus the cap statement as a polite live region. It draws nothing. [requesters] lets the traversal move focus to a
 * node; a node that takes focus reports it through [onFocusElement].
 */
@Composable
fun SceneSemanticsLayer(
    tree: SemanticsTree,
    viewport: SketchViewport,
    modifier: Modifier = Modifier,
    requesters: Map<ElementId, FocusRequester> = emptyMap(),
    onFocusElement: (ElementId) -> Unit = {},
    /** TalkBack's action menu on the canvas node: the gesture-free routes (outline, add shape at coordinates). */
    canvasActions: List<CustomAccessibilityAction> = emptyList(),
) {
    val density = LocalDensity.current
    Box(modifier.fillMaxSize().semantics { canvasApplicationRole = true; contentDescription = SketchStrings.Semantics.CANVAS; customActions = canvasActions }) {
        tree.nodes.forEach { node ->
            val left = (node.bounds.minX * viewport.zoom + viewport.panX).toInt()
            val top = (node.bounds.minY * viewport.zoom + viewport.panY).toInt()
            val widthPx = (node.bounds.width * viewport.zoom).toFloat().coerceAtLeast(1f)
            val heightPx = (node.bounds.height * viewport.zoom).toFloat().coerceAtLeast(1f)
            val requester = requesters[node.id]
            Box(
                Modifier
                    .offset { IntOffset(left, top) }
                    .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                    .let { if (requester != null) it.focusRequester(requester) else it }
                    .onFocusChanged { if (it.isFocused) onFocusElement(node.id) }
                    .semantics { contentDescription = node.label; selected = node.selected }
                    .let { if (node.locked) it else it.focusable() },
            )
        }
        tree.statement?.let { statement ->
            Box(Modifier.size(1.dp).semantics { contentDescription = statement; liveRegion = LiveRegionMode.Polite })
        }
    }
}
