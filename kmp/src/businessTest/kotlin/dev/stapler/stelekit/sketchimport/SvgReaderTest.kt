// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvgReaderTest {
    private fun fixture(name: String): String =
        checkNotNull(SvgReaderTest::class.java.classLoader.getResourceAsStream("home-sketch/closet/$name")) {
            "fixture $name missing"
        }.bufferedReader().use { it.readText() }

    @Test
    fun elevationParsesAllPathsWithinVisitBound() {
        val doc = readSvg(fixture("closet-elevation.svg"))
        assertEquals(245, doc.paths.size)
        // R-TIME: work-count bound, one visit per element (svg, rect, 4 g, 245 paths, 11 texts).
        assertEquals(262, doc.elementsVisited)
        assertTrue(doc.unsupported.isEmpty(), "${doc.unsupported}")
        assertEquals(11, doc.texts.size)
    }

    @Test
    fun elevationTransformChainAndLabels() {
        val doc = readSvg(fixture("closet-elevation.svg"))
        assertTrue(doc.transforms.single().startsWith("scale(11.06382978723404, -11.06382978723404)"))
        assertTrue(doc.paths.first().transforms.single().startsWith("scale("))
        assertTrue(doc.texts.any { it.text == "33 7/16\"" && it.anchor == "middle" })
        assertTrue(doc.texts.any { it.text == "Total Width: 136\"" })
        assertEquals(1800.0, doc.width)
    }

    @Test
    fun sectionParses() {
        val doc = readSvg(fixture("closet-section.svg"))
        assertEquals(44, doc.paths.size)
        assertTrue(doc.texts.any { it.text.endsWith("Usable Depth") })
        assertTrue(doc.paths.all { it.segments.isNotEmpty() })
    }
}
