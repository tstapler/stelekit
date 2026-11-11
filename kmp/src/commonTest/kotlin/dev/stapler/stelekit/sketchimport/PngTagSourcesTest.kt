// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.units.Rational
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PngTagSourcesTest {
    private val hash = "a".repeat(64)
    private val record = PngCalibrationRecord(
        hash,
        CalibrationBasis.NATIVE_PIXELS,
        "1234.5",
        listOf(PngTagValue("closet.width", Rational(43, 8), measured = false), PngTagValue("rod.count", Rational(3), measured = true)),
    )
    private val registry = DimensionRegistry(
        dimensions = listOf(
            DimensionEntry("closet.width", DimensionKind.LENGTH, "Closet width"),
            DimensionEntry("rod.count", DimensionKind.COUNT, "Rods"),
        ),
    )
    private val page = JsonObject(mapOf("kind" to JsonPrimitive("table"), "bindingId" to JsonPrimitive("0a1b2c3d"), "contentHash" to JsonPrimitive("h")))
    private val yaml = JsonObject(mapOf("kind" to JsonPrimitive("yaml"), "snapshot" to JsonPrimitive("0a1b2c3d")))

    private fun settings(vararg entries: JsonObject) = JsonObject(mapOf("sources" to JsonArray(entries.toList())))

    @Test
    fun with_should_RoundTripTheRecord_AndKeepOtherEntries() {
        val written = assertNotNull(PngTagSources.with(settings(page, yaml), record))
        assertEquals(listOf(record), PngTagSources.of(written))
        val kinds = (written["sources"] as JsonArray).map { (it as JsonObject)["kind"].toString() }
        assertTrue("\"table\"" in kinds && "\"yaml\"" in kinds && "\"png-calibration\"" in kinds)
    }

    @Test
    fun with_should_ReplaceTheSameSheet_AndWithoutShouldRemoveOnlyIt() {
        val first = assertNotNull(PngTagSources.with(settings(page), record))
        val replaced = assertNotNull(PngTagSources.with(first, record.copy(pixelsPerMeter = "99.5", tags = emptyList())))
        assertEquals(listOf("99.5"), PngTagSources.of(replaced).map { it.pixelsPerMeter })
        val removed = PngTagSources.without(replaced, hash)
        assertTrue(PngTagSources.of(removed).isEmpty())
        assertEquals(listOf(page), (removed["sources"] as JsonArray).toList())
        assertNull(PngTagSources.without(settings(), hash)["sources"])
    }

    @Test
    fun with_should_RejectMalformedRecordsAndTooManySheets() {
        listOf(
            record.copy(contentHash = "abc"),
            record.copy(basisVersion = 7),
            record.copy(pixelsPerMeter = "NaN"),
            record.copy(pixelsPerMeter = "-3"),
            record.copy(pixelsPerMeter = "abc"),
            record.copy(tags = List(PngTagSources.MAX_TAGS + 1) { PngTagValue("closet.width", Rational(1), true) }),
        ).forEach { assertNull(PngTagSources.with(settings(), it)) }
        var full = JsonObject(emptyMap())
        repeat(PngTagSources.MAX_SHEETS) { i -> full = assertNotNull(PngTagSources.with(full, record.copy(contentHash = i.toString(16).padStart(64, '0')))) }
        assertNull(PngTagSources.with(full, record))
    }

    @Test
    fun of_should_SkipDamagedEntriesAndUnreadableTags() {
        val damaged = listOf(
            JsonObject(mapOf("kind" to JsonPrimitive("png-calibration"))),
            JsonObject(mapOf("kind" to JsonPrimitive("png-calibration"), "contentHash" to JsonPrimitive(hash), "basisVersion" to JsonPrimitive("x"), "pixelsPerMeter" to JsonPrimitive("1"))),
        )
        assertTrue(PngTagSources.of(settings(*damaged.toTypedArray())).isEmpty())
        val tags = JsonArray(
            listOf(
                JsonObject(mapOf("key" to JsonPrimitive("a"), "value" to JsonPrimitive("1/0"))),
                JsonObject(mapOf("key" to JsonPrimitive("b"), "value" to JsonPrimitive("x"))),
                JsonObject(mapOf("key" to JsonPrimitive("c"), "value" to JsonPrimitive("5"))),
                JsonObject(mapOf("value" to JsonPrimitive("5"))),
                JsonPrimitive("junk"),
            ),
        )
        val entry = PngTagSources.encode(record.copy(tags = emptyList())).let { JsonObject(it + ("tags" to tags)) }
        assertEquals(listOf(PngTagValue("c", Rational(5), false)), PngTagSources.of(settings(entry)).single().tags)
    }

    @Test
    fun observations_should_KeepOnlyRegisteredLengthKeys_WithOpaqueRefsAndPrecision() {
        val obs = PngTagObservations.of(listOf(record), registry, RefTable())
        val one = obs.single()
        assertEquals("closet.width", one.dimension)
        assertEquals(Rational(43, 8), one.value)
        assertEquals(Rational.ZERO, one.precision)
        assertEquals(SourceKind.PNG_TAG, one.source.kind)
        assertFalse(hash in one.source.ref.id)
        val measured = PngTagObservations.of(listOf(record.copy(tags = listOf(PngTagValue("closet.width", Rational(5), true)))), registry, RefTable())
        assertEquals(RasterTagging.MEASURED_PRECISION, measured.single().precision)
    }

    @Test
    fun pixelsPerMeterText_should_ReadBackTheSameDouble() = runTest {
        checkAll(300, Arb.double(1e-3, 1e7).filter { it.isFinite() }) { d ->
            assertEquals(d, d.toString().toDouble())
            val written = assertNotNull(PngTagSources.with(settings(), record.copy(pixelsPerMeter = d.toString())))
            assertEquals(d, PngTagSources.of(written).single().pixelsPerMeter.toDouble())
        }
    }
}
