// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Who wrote an element and whether a person has looked at it yet (epic E8). Stored in the element's `extras.provenance`, so a
 * sheet without it keeps its exact bytes and an older reader carries it through untouched. An agent that reads dimensions off a
 * photo is weak at them, so everything it writes is marked [AGENT] and stays "unreviewed" until someone accepts it.
 *
 * [confidence] is a whole percent (0 to [MAX_CONFIDENCE]) the agent reports, never a measurement; [note] says where the value
 * came from ("read off the tape in the photo"). [accepted] is set by the reviewer and keeps the record that an agent wrote it.
 */
data class Provenance(
    val source: String = AGENT,
    val confidence: Int? = null,
    val note: String? = null,
    val accepted: Boolean = false,
) {
    init {
        require(confidence == null || confidence in 0..MAX_CONFIDENCE) { "confidence is a percent" }
    }

    /** True for an agent-written element nobody has accepted: the editor ghosts it and offers Accept. */
    val needsReview: Boolean get() = source == AGENT && !accepted

    companion object {
        const val AGENT = "agent"
        const val MAX_CONFIDENCE = 100
        const val KEY = "provenance"
        private const val SOURCE = "source"
        private const val CONFIDENCE = "confidence"
        private const val NOTE = "note"
        private const val ACCEPTED = "accepted"

        /** Reads the marker; null when absent or not shaped as written here (an unknown shape is left alone, never guessed at). */
        fun read(extras: JsonObject): Provenance? {
            val o = extras[KEY] as? JsonObject ?: return null
            val source = (o[SOURCE] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val confidence = (o[CONFIDENCE] as? JsonPrimitive)?.intOrNull
            if (confidence != null && confidence !in 0..MAX_CONFIDENCE) return null
            val accepted = (o[ACCEPTED] as? JsonPrimitive)?.booleanOrNull ?: false
            return Provenance(source, confidence, (o[NOTE] as? JsonPrimitive)?.contentOrNull, accepted)
        }

        /** [extras] with [provenance] stored; null removes it. Optional fields are omitted when unset, so the bytes stay minimal. */
        fun write(extras: JsonObject, provenance: Provenance?): JsonObject {
            if (provenance == null) return JsonObject(extras - KEY)
            val fields = LinkedHashMap<String, JsonElement>()
            fields[SOURCE] = JsonPrimitive(provenance.source)
            provenance.confidence?.let { fields[CONFIDENCE] = JsonPrimitive(it) }
            provenance.note?.takeIf { it.isNotBlank() }?.let { fields[NOTE] = JsonPrimitive(it) }
            if (provenance.accepted) fields[ACCEPTED] = JsonPrimitive(true)
            return JsonObject(extras + (KEY to JsonObject(fields)))
        }
    }
}

/** The provenance marker of this element, or null for an element a person drew. */
val ElementBase.provenance: Provenance? get() = Provenance.read(extras)

fun ElementBase.withProvenance(provenance: Provenance?): ElementBase = copy(extras = Provenance.write(extras, provenance))

/** The unreviewed elements a reviewer can see and change: [unreviewed] minus those on hidden or locked layers, which Accept all leaves alone. */
fun SketchSceneV2.reviewable(): List<SceneElement> = unreviewed().filter { e ->
    val layer = layer(e.base.layerId)
    layer == null || (layer.visible && !layer.locked)
}

/** Every element of the scene an agent wrote that nobody accepted yet, in paint order. */
fun SketchSceneV2.unreviewed(): List<SceneElement> = elementsInOrder.filter { it.base.provenance?.needsReview == true }
