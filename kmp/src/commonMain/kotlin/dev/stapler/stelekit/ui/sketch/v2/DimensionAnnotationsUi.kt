// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.tools.SelectTool
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.TouchFloor
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings as S
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.delay

object DimensionAnnotationTags {
    const val LAYER = "sketch.annotations"
    const val LINES = "sketch.annotations.lines"
    const val PENDING_TOAST_KEY = "sketch.annotation.pending"
    fun value(id: String, kind: PillKind) = "sketch.annotation.$id.${kind.name.lowercase()}"
}

private const val VIEW_MARGIN_DP = 48.0
/** The top strip the empty-selection hint text occupies; no annotation goes there while nothing is selected. */
private const val HINT_BAND_DP = 60.0
private const val LINE_DP = 1f
private const val TICK_DP = 4f
private const val LINE_ALPHA = 0.75f
private const val CASING_ALPHA = 0.85f
private const val CASING_FACTOR = 3f
private const val TEXT_COLOR_ALPHA = 0.9f
private const val CHIP_ALPHA = 0.85f
private const val CHIP_RADIUS_DP = 3

/** How far, in dp, a pan may carry the cached layout before it is laid out again (under the view margin, so no edge shows), and how long a pan must rest to settle. */
private const val PAN_REUSE_DP = 24f
private const val PAN_SETTLE_MS = 150L

/** The annotation colours, all read from the theme so a dark scheme gets light ink on a dark chip; the contrast helpers let a test hold them to the WCAG bars. */
object AnnotationInk {
    fun chip(scheme: ColorScheme): Color = scheme.surface.copy(alpha = CHIP_ALPHA)

    fun line(scheme: ColorScheme): Color = scheme.onSurface.copy(alpha = LINE_ALPHA)

    fun text(scheme: ColorScheme): Color = scheme.onSurface.copy(alpha = TEXT_COLOR_ALPHA)

    /** Contrast of a fully faded-in value over its chip, the chip over the canvas background (text needs 4.5:1). */
    fun textContrast(scheme: ColorScheme): Double {
        val chip = chip(scheme).compositeOver(scheme.background)
        return FocusRingContrast.ratio(text(scheme).compositeOver(chip), chip)
    }

    /** Contrast of a fully faded-in dimension line over its casing, the chip colour at [CASING_ALPHA] over the canvas background (a line needs 3:1). */
    fun lineContrast(scheme: ColorScheme): Double {
        val casing = scheme.surface.copy(alpha = CASING_ALPHA).compositeOver(scheme.background)
        return FocusRingContrast.ratio(line(scheme).compositeOver(casing), casing)
    }
}

/** What one layout depends on; two equal keys mean the cached layout is still right. */
private data class AnnotationKey(
    val revision: Long,
    val zoom: Double,
    val panX: Double,
    val panY: Double,
    val density: Float,
    val fontScale: Float,
    val widthDp: Double,
    val heightDp: Double,
    val selection: Set<String>,
    val scope: DimensionScope,
    val pillsFor: ElementId?,
    val tapMinDp: Double,
    val format: LengthFormat,
)

/**
 * The always-on dimensions of a technical sheet, drawn like an elevation: a thin dimension line with extension lines and ticks
 * outside each visible shape and its value centred on the line. The selected shape keeps its tappable pills instead. The lines
 * and values are not pointer nodes (so no canvas gesture is taken) and the lines are hidden from screen readers; each value's
 * finger-sized target is tapped through [AnnotationTapTargets], and a clean tap
 * selects that shape and opens its size editor (the pills' own edit). Hidden while a tool gesture is in flight (the lines would
 * trail the shape); targets are off for a read-only sheet, another tool than Select, and a value still fading in; the layout is cached per revision, viewport and selection.
 */
@Composable
internal fun DimensionAnnotationsOverlay(controller: SketchEditorController, extras: EditorExtras, pointer: PointerClass) {
    val state = extras.pills
    val taps = extras.annotationTaps
    if (controller.sheetKind != SheetKind.Technical || !state.visible || controller.tools.preview != null) {
        SideEffect { taps.targets = emptyList() }
        return
    }
    val viewport = controller.ui.viewport
    val density = LocalDensity.current
    val selection = controller.ui.selection
    val tool = controller.ui.activeTool
    val tappable = tool == ToolId.Select && !controller.readOnly
    val ink = MaterialTheme.colorScheme.onSurface
    val chip = AnnotationInk.chip(MaterialTheme.colorScheme)
    val surface = MaterialTheme.colorScheme.surface
    // A pan only moves the cached layout: it is laid out again once the pan stops or has travelled far enough that its edges would show.
    var anchor by remember { mutableStateOf(PanAnchor(viewport.zoom, viewport.panX, viewport.panY)) }
    val reuse = anchor.reusableAt(viewport.zoom, viewport.panX, viewport.panY, PAN_REUSE_DP * density.density)
    val layoutPanX = if (reuse) anchor.panX else viewport.panX
    val layoutPanY = if (reuse) anchor.panY else viewport.panY
    SideEffect { if (!reuse) anchor = PanAnchor(viewport.zoom, viewport.panX, viewport.panY) }
    LaunchedEffect(viewport.panX, viewport.panY) {
        delay(PAN_SETTLE_MS)
        anchor = PanAnchor(viewport.zoom, viewport.panX, viewport.panY)
    }
    val shiftX = (viewport.panX - layoutPanX) / density.density
    val shiftY = (viewport.panY - layoutPanY) / density.density
    BoxWithConstraints(Modifier.fillMaxSize().testTag(DimensionAnnotationTags.LAYER)) {
        val widthDp = maxWidth.value.toDouble()
        val heightDp = maxHeight.value.toDouble()
        val single = selection.singleOrNull()?.let { ElementId(it) }
        val pillsFor = single?.takeIf { DimensionPillModel.shown(true, controller.readOnly, true, selection.size, tool) }
        val key = AnnotationKey(
            controller.revision, viewport.zoom, layoutPanX, layoutPanY, density.density, density.fontScale, widthDp, heightDp,
            selection, state.scope, pillsFor, TouchFloor.dp(pointer == PointerClass.TouchOrPen || controller.touchPresent), extras.lengthUnit.format,
        )
        val annotations = remember(key) { compute(controller, key) }
        val active = if (tappable) annotations.filter { it.alpha >= DimensionAnnotationLayout.TAP_MIN_ALPHA } else emptyList()
        SideEffect { taps.targets = active.map { a -> AnnotationTapTargets.Target(a.tapRect.shifted(shiftX, shiftY)) { shift -> select(controller, state, a, shift) } } }
        DisposableEffect(taps) { onDispose { taps.targets = emptyList() } }
        if (annotations.isEmpty()) return@BoxWithConstraints
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = shiftX.toFloat() * density.density; translationY = shiftY.toFloat() * density.density }) {
            Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) { drawLines(annotations, ink, surface) }
            annotations.forEach { a ->
                key(a.elementId.value, a.kind) { AnnotationValue(a, tappable && a.alpha >= DimensionAnnotationLayout.TAP_MIN_ALPHA, ink, chip) { select(controller, state, a, shift = false) } }
            }
        }
    }
}

/** The zoom and pan a layout was computed at; a pan within reach of it only shifts that layout. */
private data class PanAnchor(val zoom: Double, val panX: Double, val panY: Double) {
    fun reusableAt(zoom: Double, panX: Double, panY: Double, reachPx: Float): Boolean =
        zoom == this.zoom && abs(panX - this.panX) <= reachPx && abs(panY - this.panY) <= reachPx
}

/** How many layouts have run in this process; a test reads the difference to prove a pan did not lay out again. */
internal object AnnotationLayoutStats {
    var computed: Int = 0
}

private fun compute(controller: SketchEditorController, key: AnnotationKey): List<DimensionAnnotation> {
    AnnotationLayoutStats.computed++
    val scene = controller.doc.scene
    val density = key.density.toDouble()
    val canvas = PillCanvas(
        toScreenDp = { x, y -> ((x * key.zoom + key.panX) / density) to ((y * key.zoom + key.panY) / density) },
        dpPerUnit = key.zoom / density,
        width = key.widthDp,
        height = key.heightDp,
    )
    val margin = VIEW_MARGIN_DP * density / key.zoom
    val view = Bounds((-key.panX) / key.zoom - margin, (-key.panY) / key.zoom - margin, (key.widthDp * density - key.panX) / key.zoom + margin, (key.heightDp * density - key.panY) / key.zoom + margin)
    val visible: List<SceneElement> = controller.spatialIndex.query(view).mapNotNull { scene[it] }.filter { e -> scene.layer(e.base.layerId)?.visible != false }
    val selected = key.selection.mapTo(HashSet(), ::ElementId)
    val candidates = if (key.scope == DimensionScope.Selected) visible.filter { it.id in selected } else visible
    val hint = if (key.selection.isEmpty()) listOf(AnnotationRect(0.0, 0.0, key.widthDp, HINT_BAND_DP)) else emptyList()
    val reserved = key.pillsFor?.let { scene[it] }?.let { e -> DimensionPillModel.layout(e, canvas).map { AnnotationRect(it.left, it.top, it.right, it.bottom).inflated(DimensionAnnotationLayout.TEXT_GAP_DP) } }.orEmpty()
    return DimensionAnnotationLayout.layout(candidates.filter { it.id != key.pillsFor }, visible, canvas, reserved, key.fontScale.toDouble(), key.tapMinDp, key.format, hint)
}

/**
 * A tap on a value picks its shape by the Select tool's own rule ([SelectTool.clickSelection]: a group is taken whole, Shift adds or
 * removes), and opens the size editor only when that leaves this one shape selected. A size being typed in another field is applied first.
 */
private fun select(controller: SketchEditorController, state: DimensionPillsState, a: DimensionAnnotation, shift: Boolean) {
    val element = controller.doc.scene[a.elementId] ?: return
    state.commitPending { command -> controller.submit(command) }?.let {
        controller.notifier.toast(DimensionAnnotationTags.PENDING_TOAST_KEY, it)
        return
    }
    val current = controller.ui.selection.map(::ElementId)
    val next = SelectTool.clickSelection(a.elementId, current, shift, clickCount = 1, scene = controller.doc.scene)
    controller.tools.select(next)
    if (next == listOf(a.elementId)) state.open(element, a.kind) else state.close()
}

/** Each line is drawn twice: a wider [casing] stroke first, then the [ink] on it, so the line reads over a light or dark shape fill alike. */
private fun DrawScope.drawLines(annotations: List<DimensionAnnotation>, ink: Color, casing: Color) {
    drawPass(annotations, casing, CASING_ALPHA, LINE_DP * CASING_FACTOR)
    drawPass(annotations, ink, LINE_ALPHA, LINE_DP)
}

private fun DrawScope.drawPass(annotations: List<DimensionAnnotation>, base: Color, alpha: Float, widthDp: Float) {
    val stroke = widthDp.dp.toPx()
    val tick = TICK_DP.dp.toPx()
    for (a in annotations) {
        val color = base.copy(alpha = alpha * a.alpha.toFloat())
        val rect = Rect(a.textRect.left.dp.toPx(), a.textRect.top.dp.toPx(), a.textRect.right.dp.toPx(), a.textRect.bottom.dp.toPx())
        clipRect(rect.left, rect.top, rect.right, rect.bottom, ClipOp.Difference) {
            drawLine(color, a.line.a.offset(this), a.line.b.offset(this), stroke)
        }
        a.extensions.forEach { drawLine(color, it.a.offset(this), it.b.offset(this), stroke) }
        tickAt(a.line.a, a.line.b, color, tick, stroke)
        tickAt(a.line.b, a.line.a, color, tick, stroke)
    }
}

private fun AnnotationPoint.offset(scope: DrawScope): Offset = with(scope) { Offset(x.dp.toPx(), y.dp.toPx()) }

/** An architectural tick: a short stroke across the line's end at 45 degrees. */
private fun DrawScope.tickAt(end: AnnotationPoint, other: AnnotationPoint, color: Color, half: Float, stroke: Float) {
    val e = end.offset(this)
    val o = other.offset(this)
    val len = hypot(o.x - e.x, o.y - e.y).takeIf { it > 0f } ?: return
    val dx = (o.x - e.x) / len
    val dy = (o.y - e.y) / len
    val sx = (dx - dy) * half
    val sy = (dx + dy) * half
    drawLine(color, Offset(e.x - sx, e.y - sy), Offset(e.x + sx, e.y + sy), stroke)
}

@Composable
private fun AnnotationValue(a: DimensionAnnotation, tappable: Boolean, ink: Color, chip: Color, onTap: () -> Unit) {
    val description = DimensionAnnotationLayout.description(a)
    val label = DimensionPillModel.label(a.kind)
    val base = Modifier.offset(a.tapRect.left.dp, a.tapRect.top.dp).size(a.tapRect.width.dp, a.tapRect.height.dp).alpha(a.alpha.toFloat())
        .testTag(DimensionAnnotationTags.value(a.elementId.value, a.kind))
    val interactive = if (tappable) {
        base.semantics(mergeDescendants = true) {
            contentDescription = description
            role = Role.Button
            onClick(label = S.Annotation.editAction(label)) { onTap(); true }
        }
    } else {
        base.semantics(mergeDescendants = true) { contentDescription = description }
    }
    Box(interactive) {
        Box(
            Modifier.offset((a.textRect.left - a.tapRect.left).dp, (a.textRect.top - a.tapRect.top).dp).size(a.textRect.width.dp, a.textRect.height.dp)
                .background(chip, RoundedCornerShape(CHIP_RADIUS_DP.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(a.text, color = ink.copy(alpha = TEXT_COLOR_ALPHA), style = TextStyle(fontSize = DimensionAnnotationLayout.TEXT_SP.sp, textAlign = TextAlign.Center), maxLines = 1, softWrap = false)
        }
    }
}
