// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import arrow.core.Either
import dev.stapler.stelekit.fixtures.FC
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.projectbridge.ProjectSourceCollector
import dev.stapler.stelekit.units.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryExtractorTest {
    private fun fixture(name: String): String =
        checkNotNull(GeometryExtractorTest::class.java.classLoader.getResourceAsStream("home-sketch/closet/$name")) {
            "fixture $name missing"
        }.bufferedReader().use { it.readText() }

    /** The fixture map plus the aliases the golden needs for the multi-valued height rule (test-local). */
    private fun registry(): DimensionRegistry {
        val base = (DimensionRegistry.fromJson(fixture("dimension-map.json")) as Either.Right).value
        fun alias(key: String, id: String) = base.dimensions.map {
            if (it.key == key) it.copy(aliases = it.aliases.copy(svgGeom = it.aliases.svgGeom + id)) else it
        }
        return base.copy(dimensions = alias("niche.height.front", "elevation.bbox-height.0.0"))
            .let { r -> r.copy(dimensions = r.dimensions.map {
                if (it.key == "niche.height.rear") it.copy(aliases = it.aliases.copy(svgGeom = listOf("elevation.bbox-height.0.1"))) else it
            }) }
            .let { r -> r.copy(dimensions = r.dimensions.map {
                if (it.key == "niche.width") it.copy(aliases = it.aliases.copy(svgGeom = listOf("elevation.bbox-width"))) else it
            }) }
    }

    private fun measure(name: String): List<GeometryMeasurement> {
        val doc = readSvg(fixture(name))
        val cal = (calibrateSheet(doc) as Either.Right).value
        return (measureGeometry(doc, cal) as Either.Right).value
    }

    private fun List<GeometryMeasurement>.values(rule: String) = filter { it.rule == rule }.map { it.tag to it.value }

    @Test
    fun elevationGoldenMeasurements() {
        val m = measure("closet-elevation.svg")
        assertEquals(listOf("" to FC.opening), m.values(GeometryRule.BBOX_WIDTH))
        assertEquals(
            listOf("0.0" to FC.cabinetHeightA, "0.1" to FC.cabinetHeightB, "1.0" to Rational(38), "1.1" to Rational(36)),
            m.values(GeometryRule.BBOX_HEIGHT),
        )
        assertEquals(listOf("" to FC.shelfPitch), m.values(GeometryRule.BAND_PITCH))
        assertEquals(
            listOf(Rational(0), FC.zoneLeftLong, Rational(895, 16), Rational(641, 8), Rational(821, 8), FC.opening),
            m.values(GeometryRule.STRIP_POSITIONS).map { it.second },
        )
        assertTrue(m.values(GeometryRule.CIRCLE_DIAMETER).isEmpty())
        assertEquals(12, m.size)
    }

    @Test
    fun standardPositionsAreDifferencesOfTheZoneWidths() {
        val positions = measure("closet-elevation.svg").values(GeometryRule.STRIP_POSITIONS).map { it.second }
        val widths = positions.zipWithNext { a, b -> (b.subtract(a) as Either.Right).value }
        assertEquals(listOf(FC.zoneLeftLong, FC.zoneLeftShort, FC.zoneShelves, FC.zoneRightShort, FC.zoneRightLong), widths)
    }

    @Test
    fun sectionHasNoAxisAlignedStandardsRun() {
        assertTrue(measure("closet-section.svg").values(GeometryRule.STRIP_POSITIONS).isEmpty())
    }

    @Test
    fun registryResolvedObservationsAreExactOpaqueAndOnePerPosition() {
        val refs = RefTable()
        val result = GeometryExtractor(registry(), refs).extract(readSvg(fixture("closet-elevation.svg")), "page-title/sheet.svg", "elevation")
        assertNull(result.calibrationError)
        val byDimension = result.observations.associateBy { it.dimension }
        assertEquals(FC.opening, byDimension.getValue("niche.width").value)
        assertEquals(FC.cabinetHeightA, byDimension.getValue("niche.height.front").value)
        assertEquals(FC.cabinetHeightB, byDimension.getValue("niche.height.rear").value)
        assertEquals(FC.shelfPitch, byDimension.getValue("shelf.gap").value)
        assertEquals((0..5).map { "standards.positions.$it" }, byDimension.keys.filter { it.startsWith("standards.positions") }.sorted())
        assertEquals(10, result.observations.size)
        assertTrue(result.observations.all { it.source.kind == SourceKind.SVG_GEOMETRY && it.precision == Rational(1, 1_000_000) })
        assertTrue(result.observations.none { "page-title" in it.source.ref.normalised })
        assertEquals(result.observations.size, result.observations.map { it.source.ref }.distinct().size)
        assertEquals(listOf("elevation.bbox-height"), result.unmappedRules)
    }

    @Test
    fun unmappedRulesAreReportedAndNothingIsGuessed() {
        val empty = GeometryExtractor(DimensionRegistry(dimensions = emptyList()), RefTable())
            .extract(readSvg(fixture("closet-elevation.svg")), "x", "elevation")
        assertTrue(empty.observations.isEmpty())
        assertEquals(listOf("elevation.bbox-height", "elevation.bbox-width", "elevation.shelf-pitch", "elevation.standards"), empty.unmappedRules)
    }

    @Test
    fun uncalibratedSheetReportsTheErrorNotValues() {
        val noLabels = fixture("closet-elevation.svg").replace(Regex("<text[^>]*>[^<]*</text>"), "")
        val result = GeometryExtractor(registry(), RefTable()).extract(readSvg(noLabels), "x", "elevation")
        assertEquals(CalibrationError.NoKnownDimension, result.calibrationError)
        assertTrue(result.observations.isEmpty())
    }

    @Test
    fun collectorGeometryStageFeedsTheExtractor() {
        val collector = ProjectSourceCollector(registry(), RefTable())
        assertTrue(collector.extractGeometry(null).observations.isEmpty())
        assertEquals(10, collector.extractGeometry(fixture("closet-elevation.svg")).observations.size)
    }
}
