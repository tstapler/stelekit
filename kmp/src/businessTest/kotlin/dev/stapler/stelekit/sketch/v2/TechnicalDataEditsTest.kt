// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.TechnicalData
import dev.stapler.stelekit.projectbridge.TechnicalEditError
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.SketchCommand
import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.ZoneConstraints
import dev.stapler.stelekit.sketch.ZoneSet
import dev.stapler.stelekit.sketch.export.SketchCsvWriter
import dev.stapler.stelekit.sketch.export.exportDimensions
import dev.stapler.stelekit.ui.sketch.fromJson
import dev.stapler.stelekit.ui.sketch.v2.TechnicalDataView
import dev.stapler.stelekit.units.Rational
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Story 4.2.5: the model-level edits behind the v2 technical-data panel. A converted sheet keeps its zones and the project its
 * named dimensions; an edit changes exactly one entry, and the outputs equal what a v1 edit of the same values produces.
 */
class TechnicalDataEditsTest {
    private val sheetA = SheetUuid(MixedProjectFixtures.SHEET_A)
    private val wallA = SketchTestSupport.dimension("d-wall", key = "Wall A").copy(value = Rational(120, 1))
    private val wallB = SketchTestSupport.dimension("d-wall-b", key = "Wall B").copy(value = Rational(96, 1))

    private fun converted(): ProjectBundle = MixedProjectFixtures.bundle(convertA = true).let { it.copy(dimensions = listOf(wallA, wallB)) }

    private fun <T> Either<TechnicalEditError, T>.ok(): T = fold({ error("refused: $it") }, { it })

    private fun zonesOf(b: ProjectBundle, sheet: SheetUuid = sheetA) = TechnicalData.zones(b, sheet)

    private fun csvOf(zones: ZoneSet): String = SketchCsvWriter.write(SketchDocument(zones = zones).exportDimensions())

    @Test
    fun editingANamedDimension_should_ChangeOnlyThatDimension_AndLeaveEverythingElseByReference() {
        val before = converted()
        val after = TechnicalData.withDimensionValue(before, "Wall A", Rational(132, 1)).ok()

        assertEquals(Rational(132, 1), after.dimensions.first { it.key == "Wall A" }.value)
        assertSame(before.dimensions.first { it.key == "Wall B" }, after.dimensions.first { it.key == "Wall B" })
        assertSame(before.sheets, after.sheets)
        assertSame(before.primitives, after.primitives)
        assertEquals(before.sheetPayloads, after.sheetPayloads, "the v2 sheet bytes are untouched: technical data lives in the model")
    }

    @Test
    fun dimensionEdit_should_EqualTheV1EditOfTheSameValue_InEveryWrittenFile() {
        val before = converted()
        val v1 = before.copy(dimensions = before.dimensions.map { if (it.key == "Wall A") it.copy(value = Rational(132, 1)) else it })
        val v2 = TechnicalData.withDimensionValue(before, "Wall A", Rational(132, 1)).ok()

        val encodedV1 = SidecarCodec.encode(v1)
        val encodedV2 = SidecarCodec.encode(v2)
        assertEquals(encodedV1.keys, encodedV2.keys)
        for ((path, bytes) in encodedV1) assertTrue(bytes.contentEquals(encodedV2.getValue(path)), "$path differs from the v1 edit")
        val untouched = SidecarCodec.encode(before)
        val changed = encodedV2.keys.filterNot { untouched.getValue(it).contentEquals(encodedV2.getValue(it)) }
        assertEquals(listOf(SidecarCodec.MODEL_PATH), changed, "only project-model.json changes")
    }

    @Test
    fun zoneEdit_should_ChangeOnlyThatZone_AndLeaveTheOthersAndTheScaleUntouched() {
        val before = converted()
        val after = TechnicalData.withZoneText(before, sheetA, "left", "4'").ok()

        val was = zonesOf(before)
        val now = zonesOf(after)
        assertEquals("4'", now[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertSame(was[1], now[1])
        assertSame(was[2], now[2])
        assertEquals(before.sheets[1], after.sheets[1], "the other sheet is unchanged")
        assertEquals(before.sheets[0].scaleSixteenthsPerUnit, after.sheets[0].scaleSixteenthsPerUnit)
        assertEquals(before.dimensions, after.dimensions)
    }

    @Test
    fun zoneEdit_should_MatchTheV1CommandInZonesCheckAndCsv() {
        val before = converted()
        val v2 = zonesOf(TechnicalData.withZoneText(before, sheetA, "left", "4'").ok())
        val v1Doc = SketchCommand.SetZoneText("left", "4'").apply(SketchDocument(zones = ZoneSet.fromJson(zonesOf(before)))).fold({ error("v1 refused $it") }, { it })

        assertEquals(v1Doc.zones, ZoneSet.fromJson(v2))
        assertEquals(ZoneConstraints.resolve(v1Doc.zones).getOrNull(), ZoneConstraints.resolve(ZoneSet.fromJson(v2)).getOrNull())
        assertEquals(csvOf(v1Doc.zones), csvOf(ZoneSet.fromJson(v2)))
    }

    @Test
    fun theZoneCheck_should_ReRunAfterEachCommit_AsInV1() {
        val before = converted()
        val bad = TechnicalData.withZoneText(before, sheetA, "left", "4'").ok()
        val view = TechnicalDataView.of(bad, sheetA)
        assertEquals(true, view.report?.sumViolated, "3' + 2' no longer adds up once left is 4'")
        assertEquals(false, TechnicalDataView.of(before, sheetA).report?.sumViolated)
        assertEquals(ZoneConstraints.resolve(ZoneSet.fromJson(zonesOf(bad))).getOrNull(), view.report)
    }

    @Test
    fun clearingZoneText_should_RemoveTheTextKeyOnly() {
        val cleared = TechnicalData.withZoneText(converted(), sheetA, "right", null).ok()
        assertEquals(JsonObject(mapOf("key" to JsonPrimitive("right"))), zonesOf(cleared)[1])
    }

    @Test
    fun zoneTotal_should_BeAtMostOnePerSheet_LikeTheV1Command() {
        val before = converted()
        val moved = TechnicalData.withZoneTotal(before, sheetA, "left", true).ok()
        val v1 = SketchCommand.SetZoneTotal("left", true).apply(SketchDocument(zones = ZoneSet.fromJson(zonesOf(before)))).fold({ error("$it") }, { it })
        assertEquals(v1.zones, ZoneSet.fromJson(zonesOf(moved)))
        assertEquals(listOf("left"), ZoneSet.fromJson(zonesOf(moved)).zones.filter { it.isTotal }.map { it.key })
    }

    @Test
    fun addAndRemoveZone_should_FollowTheV1Rules() {
        val before = converted()
        val added = TechnicalData.withZoneAdded(before, sheetA, "island").ok()
        assertEquals(listOf("left", "right", "all", "island"), ZoneSet.fromJson(zonesOf(added)).zones.map { it.key })
        assertEquals(TechnicalEditError.DuplicateZone, TechnicalData.withZoneAdded(added, sheetA, "island").fold({ it }, { error("accepted a duplicate") }))
        val removed = TechnicalData.withZoneRemoved(added, sheetA, "island").ok()
        assertEquals(zonesOf(before), zonesOf(removed))
    }

    @Test
    fun refusedEdits_should_ChangeNothing_AndNameTheReason() {
        val before = converted().let { b -> b.copy(dimensions = b.dimensions + SketchTestSupport.dimension("d-lock", key = "Fixed").copy(locked = true)) }
        assertEquals(TechnicalEditError.DimensionLocked, TechnicalData.withDimensionValue(before, "Fixed", Rational(1, 1)).fold({ it }, { error("edited a locked dimension") }))
        assertEquals(TechnicalEditError.UnknownDimension, TechnicalData.withDimensionValue(before, "nope", Rational(1, 1)).fold({ it }, { error("edited nothing") }))
        assertEquals(TechnicalEditError.UnknownZone, TechnicalData.withZoneText(before, sheetA, "nope", "1'").fold({ it }, { error("edited nothing") }))
        assertEquals(TechnicalEditError.UnknownSheet, TechnicalData.withZoneText(before, SheetUuid("none"), "left", "1'").fold({ it }, { error("edited nothing") }))
    }

    @Test
    fun anEditToTheSameValue_should_ReturnTheSameBundle() {
        val before = converted()
        assertSame(before, TechnicalData.withDimensionValue(before, "Wall A", Rational(120, 1)).ok())
        assertEquals(before, TechnicalData.withZoneText(before, sheetA, "left", "3'").ok())
    }

    @Test
    fun theView_should_ListDimensionsInFeetAndInches_AndFlagOnesThatAreNotWholeSixteenths() {
        val odd = SketchTestSupport.dimension("d-odd", key = "Odd").copy(value = Rational(1, 3))
        val view = TechnicalDataView.of(converted().let { it.copy(dimensions = it.dimensions + odd) }, sheetA)
        assertEquals(listOf("Wall A", "Wall B", "Odd"), view.dimensions.map { it.key })
        assertEquals("10' 0\"", view.dimensions[0].valueText)
        assertEquals(true, view.dimensions[0].representable)
        assertEquals(false, view.dimensions[2].representable)
        assertNotEquals("", view.dimensions[2].valueText)
        val noJson = JsonArray(emptyList())
        assertEquals(ZoneSet.EMPTY, ZoneSet.fromJson(noJson))
    }
}
