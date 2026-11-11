// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.Source
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.units.Rational
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One dimension tagged on a PNG sheet, as stored: exact inches; [measured] is false for the typed calibration reference. */
data class PngTagValue(val key: String, val value: Rational, val measured: Boolean)

/**
 * A PNG sheet's calibration and tagged dimensions as stored in the synced `sources[]` (Story 4.5b). [contentHash] names the sheet,
 * never a file. [pixelsPerMeter] is kept as a decimal string, so the value a device computed is the value every device reads.
 */
data class PngCalibrationRecord(
    val contentHash: String,
    val basisVersion: Int,
    val pixelsPerMeter: String,
    val tags: List<PngTagValue>,
)

/** Reads and rewrites the `png-calibration` entries of `settings["sources"]`, leaving every other entry untouched. */
object PngTagSources {
    const val KIND = "png-calibration"

    /** The project's `settings` key shared with page, yaml and drawing entries; this object only touches its own kind. */
    private const val SOURCES_KEY = "sources"
    const val MAX_SHEETS = 50
    const val MAX_TAGS = 100
    private val hashShape = Regex("[0-9a-f]{64}")

    fun of(settings: JsonObject): List<PngCalibrationRecord> = entries(settings).mapNotNull(::decode)

    /** Adds or replaces the record for the same sheet; null once [MAX_SHEETS] would be exceeded or the record is malformed. */
    fun with(settings: JsonObject, record: PngCalibrationRecord): JsonObject? {
        if (!isValid(record)) return null
        val others = entries(settings).filterNot { sameSheet(it, record.contentHash) }
        if (others.count { decode(it) != null } + 1 > MAX_SHEETS) return null
        return replace(settings, others + encode(record))
    }

    fun without(settings: JsonObject, contentHash: String): JsonObject =
        replace(settings, entries(settings).filterNot { sameSheet(it, contentHash) })

    fun isValid(r: PngCalibrationRecord): Boolean =
        hashShape.matches(r.contentHash) && CalibrationBasis.isKnown(r.basisVersion) &&
            (r.pixelsPerMeter.toDoubleOrNull()?.let { it.isFinite() && it > 0.0 } == true) && r.tags.size <= MAX_TAGS

    fun encode(r: PngCalibrationRecord): JsonObject = JsonObject(
        mapOf(
            "kind" to JsonPrimitive(KIND),
            "contentHash" to JsonPrimitive(r.contentHash),
            "basisVersion" to JsonPrimitive(r.basisVersion),
            "pixelsPerMeter" to JsonPrimitive(r.pixelsPerMeter),
            "tags" to JsonArray(r.tags.map(::encodeTag)),
        ),
    )

    private fun encodeTag(t: PngTagValue) =
        JsonObject(mapOf("key" to JsonPrimitive(t.key), "value" to JsonPrimitive(t.value.toString()), "measured" to JsonPrimitive(t.measured)))

    private fun text(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.contentOrNull

    private fun decode(e: JsonElement): PngCalibrationRecord? {
        val o = e as? JsonObject ?: return null
        if (text(o, "kind") != KIND) return null
        val record = PngCalibrationRecord(
            contentHash = text(o, "contentHash") ?: return null,
            basisVersion = text(o, "basisVersion")?.toIntOrNull() ?: return null,
            pixelsPerMeter = text(o, "pixelsPerMeter") ?: return null,
            tags = (o["tags"] as? JsonArray).orEmpty().mapNotNull(::decodeTag),
        )
        return record.takeIf(::isValid)
    }

    private fun decodeTag(e: JsonElement): PngTagValue? {
        val o = e as? JsonObject ?: return null
        val key = text(o, "key") ?: return null
        val value = parseRational(text(o, "value") ?: return null) ?: return null
        return PngTagValue(key, value, text(o, "measured") == "true")
    }

    private fun parseRational(text: String): Rational? {
        val num = text.substringBefore('/').toLongOrNull() ?: return null
        val den = if ('/' in text) text.substringAfter('/').toLongOrNull() ?: return null else 1L
        return Rational.of(num, den).getOrNull()
    }

    private fun entries(settings: JsonObject): List<JsonElement> = (settings[SOURCES_KEY] as? JsonArray)?.toList().orEmpty()

    private fun sameSheet(e: JsonElement, contentHash: String) = decode(e)?.contentHash == contentHash

    private fun replace(settings: JsonObject, list: List<JsonElement>): JsonObject {
        val sorted = list.sortedBy { decode(it)?.contentHash.orEmpty() }
        val rest = settings - SOURCES_KEY
        return if (sorted.isEmpty()) JsonObject(rest) else JsonObject(rest + (SOURCES_KEY to JsonArray(sorted)))
    }
}

/** The PNG-tag stage's readings: one [SourceKind.PNG_TAG] observation per tagged key whose key is a registered length. */
object PngTagObservations {
    fun of(records: List<PngCalibrationRecord>, registry: DimensionRegistry, refTable: RefTable): List<Observation> =
        records.flatMap { record ->
            record.tags.mapNotNull { tag ->
                val entry = registry.entry(tag.key)?.takeIf { it.kind == DimensionKind.LENGTH } ?: return@mapNotNull null
                val ref = refTable.refFor(SourceKind.PNG_TAG, "png:${record.contentHash}|${tag.key}")
                val precision = if (tag.measured) RasterTagging.MEASURED_PRECISION else Rational.ZERO
                Observation(entry.key, tag.value, precision, entry.kind, Source(SourceKind.PNG_TAG, ref))
            }
        }
}

/** The first project's PNG records (the job fuses one project per graph); empty when there is none. */
suspend fun readPngTags(repositories: SketchRepositories): List<PngCalibrationRecord> {
    val project = repositories.projects.getProjects(1, 0).first().getOrNull()?.firstOrNull() ?: return emptyList()
    return PngTagSources.of(project.settings)
}
