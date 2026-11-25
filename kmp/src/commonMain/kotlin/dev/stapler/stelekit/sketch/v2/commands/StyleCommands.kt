// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.binding.followBoundArrows
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowType
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.ValueError
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.scene.withArrowType
import dev.stapler.stelekit.sketch.v2.binding.ElbowRouting
import dev.stapler.stelekit.sketch.v2.text.TextAutoSize
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextRefit
import dev.stapler.stelekit.sketch.v2.text.TextStyle

// Style properties that live on one element kind (Task 3.5.4a): the text properties of the style panel and the two
// arrowheads. Elements of other kinds are left alone, so a mixed selection styles what it can.

/**
 * Sets font family, size or alignment (horizontal or vertical) on the text elements of [ids]; a null field is left as is.
 * A change of family or size also re-measures the box ([metrics]: wrapped lines, width and height, anchored by the text's
 * alignment) so the frame and hit box keep matching the glyphs; without [metrics], or while the font is not loaded, the
 * box is scaled with the size instead. A label bound to a shape or arrow is re-fitted by that owner's rules.
 */
data class StyleText(
    val ids: List<ElementId>,
    val fontFamily: Int? = null,
    val fontSize: Double? = null,
    val textAlign: TextAlign? = null,
    val verticalAlign: VerticalAlign? = null,
    val metrics: TextMetricsProvider? = null,
) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        fontSize?.let { if (!it.isFinite() || it <= 0.0) raise(CommandError.ValueRejected(ValueError.NotPositive(it))) }
        var out = doc.scene
        val limits = out.limits
        val changed = LinkedHashSet<ElementId>()
        val labels = mutableListOf<TextElement>()
        for (id in ids.distinct()) {
            val e: SceneElement = out[id] ?: raise(CommandError.UnknownElement(id.value))
            if (e !is TextElement) continue
            var next = e.copy(
                fontFamily = fontFamily ?: e.fontFamily,
                fontSize = fontSize ?: e.fontSize,
                textAlign = textAlign ?: e.textAlign,
                verticalAlign = verticalAlign ?: e.verticalAlign,
            )
            if (next == e) continue
            val reshaped = next.fontSize != e.fontSize || next.fontFamily != e.fontFamily
            if (reshaped && e.containerId != null) {
                if (metrics != null) labels += next
            } else if (reshaped) {
                val refit = metrics?.let { TextAutoSize.refit(e, it, next.fontSize, next.fontFamily) } ?: TextAutoSize.scaled(e, next.fontSize)
                next = next.withBox(refit, limits).bind()
            }
            out = out.replace(next.touched()).bind()
            changed += id
        }
        val reflowed = changed.filter { id -> (out[id] as? TextElement)?.let { it.containerId == null } == true }.toSet()
        if (reflowed.isNotEmpty()) {
            val followed = out.followBoundArrows(reflowed).bind()
            out = followed.scene
            changed += followed.arrows
        }
        var applied = finish(doc, out, changed)
        for (label in labels) applied = refitLabel(applied, label).bind()
        applied
    }

    private fun refitLabel(applied: Applied, label: TextElement): Either<CommandError, Applied> = either {
        val scene = applied.doc.scene
        val owner = label.containerId?.let { scene[it] } ?: return@either applied
        val m = metrics ?: return@either applied
        val command = if (owner is ArrowElement) SetArrowLabel(owner.id, label.id, label.originalText, m) else SetContainerText(owner.id, label.id, label.originalText, m)
        val refit = command.apply(applied.doc).bind()
        Applied(refit.doc, applied.changedIds + refit.changedIds)
    }
}

/**
 * Replaces the words of a stand-alone text element and re-measures its box with its own font and alignment anchor, like a
 * size change does. Text bound to a shape or arrow is edited through that owner's label commands, not this one; text over
 * the scene's cap, blank text and an unloaded font are refused so nothing unmeasured or empty is stored.
 */
data class SetTextContent(val id: ElementId, val text: String, val metrics: TextMetricsProvider) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val e = doc.scene[id] ?: raise(CommandError.UnknownElement(id.value))
        if (e !is TextElement || e.containerId != null) raise(CommandError.Unsupported(id.value, "set text"))
        if (text.isBlank() || text.length > doc.scene.limits.maxTextChars) raise(CommandError.Unsupported(id.value, "set text"))
        if (text == e.originalText) raise(CommandError.NoChange)
        val wrap = if (e.autoResize) null else e.width.value
        val fit = TextAutoSize.fit(text, TextStyle(e.fontFamily, e.fontSize), e.lineHeight, wrap, metrics) ?: raise(CommandError.Unsupported(id.value, "font not loaded"))
        val next = e.copy(originalText = text).withBox(TextAutoSize.anchored(e, fit), doc.scene.limits).bind()
        val out = doc.scene.replace(next.touched()).bind()
        val followed = out.followBoundArrows(setOf(id)).bind()
        finish(doc, followed.scene, setOf(id) + followed.arrows)
    }
}

/** [this] with the box and wrapped text of [refit] (limits-checked). */
internal fun TextElement.withBox(refit: TextRefit, limits: SceneLimits): Either<CommandError, TextElement> = either {
    copy(
        x = sceneUnit(refit.x, limits).bind(),
        y = sceneUnit(refit.y, limits).bind(),
        width = sceneUnit(refit.width, limits).bind(),
        height = sceneUnit(refit.height, limits).bind(),
        text = refit.text,
    )
}

/** Sets the start and end arrowheads of the arrows of [ids]; a null side is left as is. */
data class StyleArrowheads(val ids: List<ElementId>, val start: Arrowhead? = null, val end: Arrowhead? = null) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        var out = doc.scene
        val changed = LinkedHashSet<ElementId>()
        for (id in ids.distinct()) {
            val e: SceneElement = out[id] ?: raise(CommandError.UnknownElement(id.value))
            if (e !is ArrowElement) continue
            val next = e.copy(startArrowhead = start ?: e.startArrowhead, endArrowhead = end ?: e.endArrowhead)
            if (next == e) continue
            out = out.replace(next.touched()).bind()
            changed += id
        }
        finish(doc, out, changed)
    }
}

/** Sets the arrow type (sharp, round or the stored-only elbow, FB-SP-14) of the arrows of [ids]; other kinds are left alone. */
data class StyleArrowType(val ids: List<ElementId>, val type: ArrowType) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        var out = doc.scene
        val changed = LinkedHashSet<ElementId>()
        for (id in ids.distinct()) {
            val e: SceneElement = out[id] ?: raise(CommandError.UnknownElement(id.value))
            if (e !is ArrowElement) continue
            val typed = e.withArrowType(type)
            val next = if (typed.elbowed) ElbowRouting.pointsOf(out, typed)?.let { typed.copy(points = it) } ?: typed else typed
            if (next == e) continue
            out = out.replace(next.touched()).bind()
            changed += id
        }
        finish(doc, out, changed)
    }
}
