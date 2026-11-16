// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.ValueError

// Style properties that live on one element kind (Task 3.5.4a): the text properties of the style panel and the two
// arrowheads. Elements of other kinds are left alone, so a mixed selection styles what it can.

/** Sets font family, size or alignment on the text elements of [ids]; a null field is left as is. */
data class StyleText(
    val ids: List<ElementId>,
    val fontFamily: Int? = null,
    val fontSize: Double? = null,
    val textAlign: TextAlign? = null,
) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        fontSize?.let { if (!it.isFinite() || it <= 0.0) raise(CommandError.ValueRejected(ValueError.NotPositive(it))) }
        var out = doc.scene
        val changed = LinkedHashSet<ElementId>()
        for (id in ids.distinct()) {
            val e: SceneElement = out[id] ?: raise(CommandError.UnknownElement(id.value))
            if (e !is TextElement) continue
            val next = e.copy(
                fontFamily = fontFamily ?: e.fontFamily,
                fontSize = fontSize ?: e.fontSize,
                textAlign = textAlign ?: e.textAlign,
            )
            if (next == e) continue
            out = out.replace(next.touched()).bind()
            changed += id
        }
        finish(doc, out, changed)
    }
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
