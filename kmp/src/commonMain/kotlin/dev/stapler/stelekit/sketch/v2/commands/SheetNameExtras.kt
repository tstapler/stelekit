// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why a typed sheet name was refused. */
sealed interface SheetNameError {
    data object Blank : SheetNameError
    data class TooLong(val limit: Int) : SheetNameError
}

/**
 * Where a sheet keeps the name the user gave it: one optional `extras` string, written only once someone names the sheet, so a
 * sheet that never was keeps the exact bytes it has and an older reader keeps the key as an unknown extra. Like the sheet kind it
 * is a sheet property, not an edit: not an undo step, saved with the next document. A three-way merge picks it per key with the
 * other sheet extras.
 */
object SheetNameExtras {
    const val KEY = "sheetName"

    private val lineBreaks = Regex("[\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029]+")

    /** [raw] as one line: control characters and line or paragraph separators become a space and the ends are trimmed. */
    fun normalize(raw: String): String = raw.replace(lineBreaks, " ").trim()

    /** The name to store for what the user typed: blank and over-long names are refused, never cut. */
    fun validate(raw: String, limits: SceneLimits = SceneLimits.Default): Either<SheetNameError, String> {
        val name = normalize(raw)
        return when {
            name.isEmpty() -> SheetNameError.Blank.left()
            name.length > limits.maxNameChars -> SheetNameError.TooLong(limits.maxNameChars).left()
            else -> name.right()
        }
    }

    /** The stored name, or null when the sheet has none. A name written by another tool past the cap is shown cut to it. */
    fun read(extras: JsonObject, limits: SceneLimits = SceneLimits.Default): String? =
        (extras[KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::normalize)?.let { clip(it, limits.maxNameChars) }?.takeIf { it.isNotEmpty() }

    // Never ends on half of a surrogate pair, so an emoji at the cut is dropped whole.
    private fun clip(name: String, limit: Int): String {
        if (name.length <= limit) return name
        val cut = name.take(limit)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    /** [extras] with [name] stored; null removes the key. */
    fun write(extras: JsonObject, name: String?): JsonObject =
        if (name == null) JsonObject(extras - KEY) else JsonObject(extras + (KEY to JsonPrimitive(name)))
}

/** The name the user gave this sheet, or null (see [SheetNameExtras.read]). */
val SketchDocumentV2.sheetName: String? get() = SheetNameExtras.read(extras, scene.limits)

/** [this] carrying [name] in its extras; null clears it. */
fun SketchDocumentV2.withSheetName(name: String?): SketchDocumentV2 {
    val written = SheetNameExtras.write(extras, name)
    return if (written == extras) this else copy(extras = written)
}
