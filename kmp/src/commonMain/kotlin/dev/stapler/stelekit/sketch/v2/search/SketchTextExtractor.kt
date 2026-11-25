// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.search

import dev.stapler.stelekit.sketch.MetricScene
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.units.LengthFormat

/** The searchable text of one sheet: lower-cased lines joined by `\n`, plus a hash of them for change detection. */
data class SketchSearchText(val body: String, val hash: String) {
    val isEmpty: Boolean get() = body.isEmpty()
}

/**
 * Turns a sheet into the text global search matches against (E8): text elements and shape labels, names the user gave
 * elements (walls and rooms included), the names of layers that hold something, the width of every opening and the value
 * of every length dimension. Lengths are written both as feet-inches and as inches so `36"` finds `3' 0"` and the reverse.
 * Pure and bounded: it keeps at most [BODY_BUDGET] characters, so one sheet never makes an unbounded row.
 */
object SketchTextExtractor {
    /** Characters of one row; later lines are dropped (a sheet this wordy is still found by its first lines). */
    const val BODY_BUDGET = 8_000

    private val inchFormats = listOf(LengthFormat.FEET_INCHES, LengthFormat.DECIMAL_INCHES, LengthFormat.INCHES_MIXED)

    fun extract(doc: SketchDocumentV2): SketchSearchText {
        val lines = LinkedHashSet<String>()
        val used = HashSet<LayerId>()
        fun add(raw: String?) {
            val line = raw?.replace('\n', ' ')?.trim()?.lowercase().orEmpty()
            if (line.isNotEmpty()) lines += line
        }
        for (e in doc.scene.elementsInOrder) {
            used += e.base.layerId
            add(e.base.name)
            if (e is TextElement) add(e.text)
            if (e is OpeningElement) lengths(e.width.value).forEach(::add)
        }
        for (layer in doc.scene.layers) if (layer.id != LayerId.Default && layer.id in used) add(layer.name)
        for (c in doc.constraints) {
            val value = c.value
            if (c.isDimensional && c.kind != ConstraintKind.Angle && value != null) lengths(value).forEach(::add)
        }
        val body = clip(lines)
        return SketchSearchText(body, fnv1a(body))
    }

    private fun lengths(units: Double): List<String> = inchFormats.map { MetricScene.formatUnits(units, it) }.distinct()

    private fun clip(lines: Collection<String>): String {
        val out = StringBuilder()
        for (line in lines) {
            val added = if (out.isEmpty()) line.length else line.length + 1
            if (out.length + added > BODY_BUDGET) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }

    /** FNV-1a 64 over UTF-8: the same on every target, and only ever a "did this change" check. */
    private fun fnv1a(text: String): String {
        var h = FNV_OFFSET
        for (b in text.encodeToByteArray()) h = (h xor b.toUByte().toULong()) * FNV_PRIME
        return h.toString(HEX_RADIX).padStart(HASH_WIDTH, '0')
    }

    private const val FNV_OFFSET = 0xcbf29ce484222325uL
    private const val FNV_PRIME = 0x00000100000001b3uL
    private const val HEX_RADIX = 16
    private const val HASH_WIDTH = 16
}
