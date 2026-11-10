// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvgReaderInlineTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 200 100">
        <g transform="scale(2,-2)"><g transform="translate(5,6)">
          <path d="M0,0 L10,0 H20 V5 Z"/>
          <line x1="1" y1="2" x2="3" y2="4"/>
          <rect x="1" y="2" width="3" height="4"/>
        </g></g>
        <text x="7" y="8" text-anchor="middle">  3'  4"</text>
        <circle r="1"/><path d="M0,0 C1,1 2,2 3,3"/>
        <metadata>stelekit-sketch-export</metadata>
    </svg>"""

    @Test
    fun readsViewBoxSizeAndMetadata() {
        val doc = readSvg(svg)
        assertEquals("0 0 200 100", doc.viewBox)
        assertEquals(200.0, doc.width)
        assertEquals(100.0, doc.height)
        assertEquals("stelekit-sketch-export", doc.metadata)
    }

    @Test
    fun pathSegmentsFromMLHVZAndLineAndRect() {
        val doc = readSvg(svg)
        val path = doc.paths.first()
        assertEquals(
            listOf(
                SvgLine(0.0, 0.0, 10.0, 0.0), SvgLine(10.0, 0.0, 20.0, 0.0),
                SvgLine(20.0, 0.0, 20.0, 5.0), SvgLine(20.0, 5.0, 0.0, 0.0),
            ),
            path.segments,
        )
        assertEquals(listOf("scale(2,-2)", "translate(5,6)"), path.transforms)
        assertEquals(1, doc.lines.size)
        assertEquals(SvgRect(1.0, 2.0, 3.0, 4.0, listOf("scale(2,-2)", "translate(5,6)")), doc.rects.single())
        assertTrue("scale(2,-2)" in doc.transforms)
    }

    @Test
    fun textKeepsWhitespaceAndAnchor() {
        val t = readSvg(svg).texts.single()
        assertEquals("  3'  4\"", t.rawText)
        assertEquals("3'  4\"", t.text)
        assertEquals("middle", t.anchor)
        assertEquals(7.0, t.x)
    }

    @Test
    fun unsupportedElementsAndCommandsAreReportedNotThrown() {
        val kinds = readSvg(svg).unsupported.map { it.kind }
        assertTrue("circle" in kinds, "$kinds")
        assertTrue("path-command:C" in kinds, "$kinds")
    }

    @Test
    fun garbageNeverThrows() {
        val doc = readSvg("<<<not xml &&& <svg><path d='M'/>")
        assertTrue(doc.elementsVisited >= 0)
    }
}
