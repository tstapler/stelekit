// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

/** A straight segment in the element's own coordinate space; [transforms] on the owner place it on the sheet. */
data class SvgLine(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

data class SvgRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val transforms: List<String> = emptyList(),
)

/** A path restricted to the M L H V Z subset; [transforms] is the ancestor chain, outermost first. */
data class SvgPath(val segments: List<SvgLine>, val transforms: List<String> = emptyList())

/** A drawn segment from a `<line>` element. */
data class SvgLineElement(val line: SvgLine, val transforms: List<String> = emptyList())

/** [rawText] is exactly what the file holds (whitespace kept); [text] is trimmed for parsing. */
data class SvgText(
    val x: Double,
    val y: Double,
    val anchor: String,
    val rawText: String,
    val transforms: List<String> = emptyList(),
) {
    val text: String get() = rawText.trim()
}

/** Something the subset reader skipped: an element name, or `path-command:<letter>`. */
data class Unsupported(val kind: String)

data class SvgDoc(
    val width: Double?,
    val height: Double?,
    val viewBox: String?,
    val lines: List<SvgLineElement>,
    val rects: List<SvgRect>,
    val paths: List<SvgPath>,
    val texts: List<SvgText>,
    /** Distinct `transform` attribute strings seen, in document order. */
    val transforms: List<String>,
    val metadata: String?,
    val unsupported: List<Unsupported>,
    /** Elements visited; the R-TIME work-count bound for tests (one visit per element). */
    val elementsVisited: Int,
)
