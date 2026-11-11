// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import dev.stapler.stelekit.fixtures.ClosetFixtureText
import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fixtures.FC
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.projectbridge.ProjectSourceCollector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelBinderTest {
    private val registry = ClosetFusion.registry
    private fun bind(name: String): LabelBindingResult =
        LabelBinder(registry, RefTable()).bind(readSvg(ClosetFixtureText.load(name)), name)

    private val zoneKeys = ClosetFusion.zones

    @Test
    fun elevationGoldenCounts() {
        val r = bind("closet-elevation.svg")
        assertEquals(11, readSvg(ClosetFixtureText.load("closet-elevation.svg")).texts.size)
        assertEquals(10, r.bound.size)
        assertEquals(0, r.unbound.size)
        assertEquals(listOf("Ceiling Heights"), r.skipped)
        assertEquals(10, r.observations.size)
        assertEquals(5, r.zoneLabelOrder.size)
    }

    @Test
    fun fiveZoneWidthLabelsBindInTableOrderWithHighConfidence() {
        val r = bind("closet-elevation.svg")
        val zones = r.bound.filter { it.dimensionKey in zoneKeys }
        assertEquals(zoneKeys, zones.map { it.dimensionKey })
        assertTrue(zones.all { it.confidence >= 0.8 }, "${zones.map { it.confidence }}")
        assertEquals(
            listOf(FC.zoneLeftLong, FC.zoneLeftShort, FC.zoneShelves, FC.zoneRightShort, FC.zoneRightLong),
            zones.map { it.value },
        )
    }

    @Test
    fun aliasHintsBindRodWidthAndHeights() {
        val byKey = bind("closet-elevation.svg").bound.associateBy { it.dimensionKey }
        assertEquals(FC.opening, byKey.getValue("niche.width").value)
        assertEquals(Rational38, byKey.getValue("rod.long.height").value)
        assertEquals(36, byKey.getValue("rod.short.height").value.num)
        assertTrue(byKey.getValue("niche.height.front").confidence >= 0.8)
        assertTrue(byKey.getValue("niche.height.rear").confidence >= 0.8)
    }

    @Test
    fun lossyDecimalsCarryPrecisionOneTwoHundredth() {
        val byKey = bind("closet-elevation.svg").bound.associateBy { it.dimensionKey }
        val front = byKey.getValue("niche.height.front")
        assertEquals(FC.lossyDecimalA, front.text.substringBefore('"'))
        assertEquals(FC.labelPrecision, front.precision)
        assertEquals(FC.labelPrecision, bind("closet-section.svg").bound.first { it.dimensionKey == "lowercab.height" }.precision)
    }

    @Test
    fun sectionSheetBindsSevenAndReturnsTheShelfCaptionForManualTagging() {
        val r = bind("closet-section.svg")
        assertEquals(7, r.bound.size)
        assertEquals(listOf("Adjustable Shelves (22 1/4\" Depth)"), r.unbound.map { it.text })
        assertNull(r.unbound.single().dimensionKey)
        assertTrue(r.skipped.isEmpty())
    }

    @Test
    fun refsAreOpaqueAndDistinct() {
        val r = bind("closet-elevation.svg")
        assertTrue(r.bound.all { it.ref.startsWith("svg:") && !it.ref.contains("closet") })
        assertEquals(r.bound.size, r.bound.map { it.ref }.toSet().size)
        assertTrue(r.observations.all { it.source.kind == SourceKind.SVG_LABEL })
    }

    @Test
    fun zoneLabelWithNoMatchingSpanIsNeverGuessed() {
        val svg = ClosetFixtureText.load("closet-elevation.svg").replace("33 7/16\"", "40 7/16\"")
        val r = LabelBinder(registry, RefTable()).bind(readSvg(svg), "m")
        assertEquals(0, r.bound.count { it.dimensionKey in zoneKeys })
        assertEquals(5, r.unbound.size)
        assertTrue(r.unbound.all { it.dimensionKey == null })
        assertEquals(4, r.unbound.count { it.candidates == zoneKeys })
    }

    @Test
    fun collectorSvgStageReturnsObservationsAndUnbound() {
        val collector = ProjectSourceCollector(registry, RefTable())
        val r = collector.extractSvg(ClosetFixtureText.load("closet-section.svg"))
        assertEquals(7, r.observations.size)
        assertEquals(1, r.unbound.size)
        assertEquals(0, collector.extractSvg(null).bound.size)
    }

    private companion object {
        val Rational38 = dev.stapler.stelekit.units.Rational(38)
    }
}
