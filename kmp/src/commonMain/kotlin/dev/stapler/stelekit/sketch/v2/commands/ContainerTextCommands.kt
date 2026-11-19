// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.binding.followBoundArrows
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.text.EditLayoutResult
import dev.stapler.stelekit.sketch.v2.text.TextLayout
import dev.stapler.stelekit.sketch.v2.text.TextLayoutPolicy
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import kotlin.math.hypot
import kotlin.math.max

/** An axis-aligned box in scene units. */
internal data class Box(val x: Double, val y: Double, val width: Double, val height: Double) {
    val centreX: Double get() = x + width / HALF
    val centreY: Double get() = y + height / HALF
}

/**
 * The shared rules for text inside a shape (Story 3.3.3). The usable area of a rectangle is the box less [PADDING] on every
 * side; an ellipse and a diamond offer the rectangle inscribed in them ([ELLIPSE_INSET], [DIAMOND_INSET] of each side).
 * A container only grows: shortening the text never shrinks it, so a hand-sized shape keeps its size.
 */
object ContainerText {
    /** Space kept between the text and the container's edge, in scene units (Excalidraw's bound-text padding). */
    const val PADDING = 5.0

    /** Side of the largest rectangle inscribed in an ellipse, as a fraction of its bounding box (1 / sqrt 2). */
    const val ELLIPSE_INSET = 0.7071067811865476

    /** Side of the rectangle inscribed in a diamond, as a fraction of its bounding box. */
    const val DIAMOND_INSET = 0.5

    /** Upper bound on grow-and-rewrap passes: the loop guard of `autoResize`. A fit settles in one pass; more means a bug. */
    const val MAX_FIT_PASSES = 4

    private const val DEFAULT_FONT_FAMILY = 1

    internal fun inset(container: SceneElement): Double? = when (container) {
        is RectElement -> 1.0
        is EllipseElement -> ELLIPSE_INSET
        is DiamondElement -> DIAMOND_INSET
        else -> null
    }

    internal fun boxOf(container: SceneElement): Box? = when (container) {
        is RectElement -> Box(container.x.value, container.y.value, container.width.value, container.height.value)
        is DiamondElement -> Box(container.x.value, container.y.value, container.width.value, container.height.value)
        is EllipseElement -> Box(container.cx.value - container.rx.value, container.cy.value - container.ry.value, container.rx.value * 2.0, container.ry.value * 2.0)
        else -> null
    }

    /** The label of [containerId]: the text element that names it as its container, if any. */
    fun labelOf(scene: SketchSceneV2, containerId: ElementId): TextElement? =
        scene.elementsInOrder.firstOrNull { it is TextElement && it.containerId == containerId } as TextElement?

    /** The wrapped text of one fit and the height the container needs to hold it. */
    data class Fit(val layout: TextLayout, val containerHeight: Double)

    /**
     * Wraps [text] to [container]'s width and returns the height the container needs, never less than its own. Repeats until
     * the wrap width and the height agree, at most [MAX_FIT_PASSES] times, so growing the container cannot loop.
     */
    fun fit(container: SceneElement, text: String, style: TextStyle, metrics: TextMetricsProvider): Either<CommandError, Fit> = either {
        val box = boxOf(container) ?: raise(CommandError.Unsupported(container.id.value, OPERATION))
        val inset = inset(container) ?: raise(CommandError.Unsupported(container.id.value, OPERATION))
        var height = box.height
        var layout: TextLayout? = null
        var previousWidth = Double.NaN
        repeat(MAX_FIT_PASSES) {
            val wrapWidth = max(box.width * inset - PADDING * HALF, MIN_WRAP_WIDTH)
            if (wrapWidth == previousWidth) return@repeat
            previousWidth = wrapWidth
            val measured = when (val r = TextLayoutPolicy.forEdit(text, wrapWidth, style, metrics)) {
                EditLayoutResult.FontNotLoaded -> raise(CommandError.FontNotLoaded)
                is EditLayoutResult.Measured -> r.layout
            }
            layout = measured
            val needed = (measured.height + PADDING * HALF) / inset
            if (needed > height) height = needed
        }
        Fit(layout ?: raise(CommandError.FontNotLoaded), height)
    }

    /** [container] grown to [height] with its top edge fixed, or the same element when it already is that tall. */
    internal fun grownTo(container: SceneElement, height: Double, limits: SceneLimits): Either<CommandError, SceneElement> = either {
        val box = boxOf(container) ?: raise(CommandError.Unsupported(container.id.value, OPERATION))
        if (height <= box.height) return@either container
        val h = sceneUnit(height, limits).bind()
        when (container) {
            is RectElement -> container.copy(height = h)
            is DiamondElement -> container.copy(height = h)
            is EllipseElement -> container.copy(
                cy = sceneUnit(box.y + height / HALF, limits).bind(),
                ry = sceneUnit(height / HALF, limits).bind(),
            )
            else -> raise(CommandError.Unsupported(container.id.value, OPERATION))
        }
    }

    /** [text] moved (not rewrapped) to where [container] puts it, honouring its alignment. */
    internal fun placed(container: SceneElement, text: TextElement, limits: SceneLimits): Either<CommandError, TextElement> = either {
        val box = boxOf(container) ?: raise(CommandError.Unsupported(container.id.value, OPERATION))
        val w = text.width.value
        val h = text.height.value
        val x = when (text.textAlign) {
            TextAlign.Left -> box.x + PADDING
            TextAlign.Center -> box.centreX - w / HALF
            TextAlign.Right -> box.x + box.width - PADDING - w
        }
        val y = when (text.verticalAlign) {
            VerticalAlign.Top -> box.y + PADDING
            VerticalAlign.Middle -> box.centreY - h / HALF
            VerticalAlign.Bottom -> box.y + box.height - PADDING - h
        }
        val angle = when (container) {
            is RectElement -> container.angle
            is DiamondElement -> container.angle
            is EllipseElement -> container.angle
            else -> text.angle
        }
        text.copy(x = sceneUnit(x, limits).bind(), y = sceneUnit(y, limits).bind(), angle = angle)
    }

    /** A measured label: the wrapped [layout] of [raw] set in [style]. */
    internal data class Measured(val layout: TextLayout, val raw: String, val style: TextStyle)

    internal fun textOf(base: ElementBase, container: SceneElement, measured: Measured, limits: SceneLimits): Either<CommandError, TextElement> = either {
        TextElement(
            base = base,
            x = SceneUnit.ZERO,
            y = SceneUnit.ZERO,
            width = sceneUnit(measured.layout.width, limits).bind(),
            height = sceneUnit(measured.layout.height, limits).bind(),
            text = measured.layout.lines.joinToString("\n"),
            originalText = measured.raw,
            fontSize = measured.style.fontSize,
            fontFamily = measured.style.fontFamily,
            textAlign = TextAlign.Center,
            verticalAlign = VerticalAlign.Middle,
            containerId = container.id,
        )
    }

    internal fun styleOf(existing: TextElement?): TextStyle =
        TextStyle(existing?.fontFamily ?: DEFAULT_FONT_FAMILY, existing?.fontSize ?: TextElement.DEFAULT_FONT_SIZE)

    private const val MIN_WRAP_WIDTH = 1.0
}

private const val HALF = 2.0
private const val OPERATION = "container text"

/**
 * Sets, replaces or (for blank [text]) removes the label of a rectangle, diamond or ellipse (Story 3.3.3, Task 3.3.3a).
 * The label wraps to the container's width, the container grows to hold it with its top edge fixed, arrows bound to the
 * container follow, and the whole change is one undo step. A new label takes [textId]; an existing one keeps its id. The
 * text is refused, not truncated, past `SceneLimits.maxTextChars`. Deleting the container deletes the label with it
 * (`DeleteElements`), in the same step.
 */
data class SetContainerText(
    val containerId: ElementId,
    val textId: ElementId,
    val text: String,
    val metrics: TextMetricsProvider,
    val style: ElementStyle? = null,
) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        val limits = scene.limits
        val container = scene[containerId] ?: raise(CommandError.UnknownElement(containerId.value))
        if (ContainerText.boxOf(container) == null) raise(CommandError.Unsupported(containerId.value, OPERATION))
        if (text.length > limits.maxTextChars) raise(CommandError.TextTooLong(limits.maxTextChars))
        val existing = ContainerText.labelOf(scene, containerId)
        if (text.isBlank()) {
            if (existing == null) return@either Applied(doc, emptySet())
            return@either finish(doc, scene.remove(existing.id).bind(), setOf(existing.id, containerId))
        }
        val textStyle = ContainerText.styleOf(existing)
        val fit = ContainerText.fit(container, text, textStyle, metrics).bind()
        val grown = ContainerText.grownTo(container, fit.containerHeight, limits).bind()
        var next = if (grown == container) scene else scene.replace(grown.touched()).bind()
        val shell = existing ?: TextElement(
            base = ElementBase(textId, layerId = container.base.layerId, frameId = container.base.frameId, groupIds = container.base.groupIds, style = style ?: ElementStyle()),
            x = SceneUnit.ZERO, y = SceneUnit.ZERO, width = SceneUnit.ZERO, height = SceneUnit.ZERO, text = "",
        )
        val built = ContainerText.textOf(shell.base, grown, ContainerText.Measured(fit.layout, text, textStyle), limits).bind()
            .copy(textAlign = shell.textAlign.takeIf { existing != null } ?: TextAlign.Center, verticalAlign = shell.verticalAlign.takeIf { existing != null } ?: VerticalAlign.Middle)
        val placed = ContainerText.placed(grown, built, limits).bind()
        val labelled: SketchDocumentV2 = if (existing == null) {
            AddElements(listOf(placed)).apply(doc.copy(scene = next)).bind().doc
        } else {
            next = next.replace(if (placed == existing) existing else placed.touched() as TextElement).bind()
            doc.copy(scene = next)
        }
        val followed = labelled.scene.followBoundArrows(setOf(containerId)).bind()
        finish(labelled, followed.scene, setOf(containerId, existing?.id ?: textId) + followed.arrows)
    }
}

/** Where arrow labels sit. */
object ArrowLabels {
    /** The point halfway along the polyline's length, or null for an arrow with no points. */
    fun midpoint(points: List<ScenePoint>): ScenePoint? {
        if (points.isEmpty()) return null
        if (points.size == 1) return points.first()
        val lengths = points.zipWithNext { a, b -> hypot(b.x.value - a.x.value, b.y.value - a.y.value) }
        val half = lengths.sum() / HALF
        var walked = 0.0
        for ((i, len) in lengths.withIndex()) {
            if (walked + len >= half && len > 0.0) {
                val t = (half - walked) / len
                val a = points[i]
                val b = points[i + 1]
                return ScenePoint(
                    SceneUnit.of(a.x.value + (b.x.value - a.x.value) * t).getOrNull() ?: a.x,
                    SceneUnit.of(a.y.value + (b.y.value - a.y.value) * t).getOrNull() ?: a.y,
                )
            }
            walked += len
        }
        return points.last()
    }

    internal fun placed(arrow: ArrowElement, text: TextElement, limits: SceneLimits): Either<CommandError, TextElement> = either {
        val mid = midpoint(arrow.points) ?: raise(CommandError.Unsupported(arrow.id.value, "arrow label"))
        text.copy(
            x = sceneUnit(mid.x.value - text.width.value / HALF, limits).bind(),
            y = sceneUnit(mid.y.value - text.height.value / HALF, limits).bind(),
        )
    }
}

/**
 * Sets, replaces or (for blank [text]) removes the label at the midpoint of an arrow (Story 3.3.3, Task 3.3.3b). Labels are
 * not wrapped (an explicit line break is kept) and are centred on the arrow's midpoint; [relayoutLabels] keeps them there when
 * the arrow later moves.
 */
data class SetArrowLabel(
    val arrowId: ElementId,
    val textId: ElementId,
    val text: String,
    val metrics: TextMetricsProvider,
    val style: ElementStyle? = null,
) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        val limits = scene.limits
        val arrow = scene[arrowId] as? ArrowElement ?: raise(CommandError.UnknownElement(arrowId.value))
        if (text.length > limits.maxTextChars) raise(CommandError.TextTooLong(limits.maxTextChars))
        val existing = ContainerText.labelOf(scene, arrowId)
        if (text.isBlank()) {
            if (existing == null) return@either Applied(doc, emptySet())
            return@either finish(doc, scene.remove(existing.id).bind(), setOf(existing.id, arrowId))
        }
        val textStyle = ContainerText.styleOf(existing)
        val layout = when (val r = TextLayoutPolicy.forEdit(text, null, textStyle, metrics)) {
            EditLayoutResult.FontNotLoaded -> raise(CommandError.FontNotLoaded)
            is EditLayoutResult.Measured -> r.layout
        }
        val shell = existing ?: TextElement(
            base = ElementBase(textId, layerId = arrow.base.layerId, frameId = arrow.base.frameId, groupIds = arrow.base.groupIds, style = style ?: ElementStyle()),
            x = SceneUnit.ZERO, y = SceneUnit.ZERO, width = SceneUnit.ZERO, height = SceneUnit.ZERO, text = "",
        )
        val built = ContainerText.textOf(shell.base, arrow, ContainerText.Measured(layout, text, textStyle), limits).bind()
        val placed = ArrowLabels.placed(arrow, built, limits).bind()
        if (existing == null) {
            val added = AddElements(listOf(placed)).apply(doc).bind()
            finish(added.doc, added.doc.scene, added.changedIds + arrowId)
        } else {
            val bumped = if (placed == existing) existing else placed.touched() as TextElement
            finish(doc, scene.replace(bumped).bind(), setOf(existing.id, arrowId))
        }
    }
}

/** The scene with the labels of [moved] containers and arrows put back where their owner places them, and the labels that moved. */
data class RelayoutResult(val scene: SketchSceneV2, val labels: Set<ElementId>)

/**
 * After a solve or a bound-arrow follow moved shapes or arrows in [moved], moves their labels with them. Labels are only
 * moved, never rewrapped, so no text is measured; a label already in place is left byte for byte.
 */
fun relayoutLabels(scene: SketchSceneV2, moved: Set<ElementId>): Either<CommandError, RelayoutResult> = either {
    var out = scene
    val labels = LinkedHashSet<ElementId>()
    for (label in scene.elementsInOrder) {
        val owner = (label as? TextElement)?.containerId?.takeIf { it in moved }?.let { out[it] } ?: continue
        val limits = scene.limits
        val placed = when (owner) {
            is ArrowElement -> ArrowLabels.placed(owner, label, limits).bind()
            else -> ContainerText.placed(owner, label, limits).bind()
        }
        if (placed == label) continue
        out = out.replace(placed.touched()).bind()
        labels += label.id
    }
    RelayoutResult(out, labels)
}
