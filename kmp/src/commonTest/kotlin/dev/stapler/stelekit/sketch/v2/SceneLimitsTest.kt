// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 1.2.4: the canonical 33-field `SceneLimits` and its defaults (plan "Canonical SceneLimits"). */
class SceneLimitsTest {
    private val mb = 1_048_576L

    private val canonical: Map<String, Number> = linkedMapOf(
        "maxElements" to 5_000, "maxConstraints" to 200, "maxCoord" to 268_435_456.0, "maxPointsPerElement" to 2_000,
        "maxJsonDepth" to 64, "maxSheetBytes" to 16 * mb, "maxTextChars" to 10_000, "maxNameChars" to 80,
        "maxImportBytes" to 20 * mb, "maxImportSpanBytes" to 2 * mb, "maxImageBytes" to 60 * mb, "maxImageSidePx" to 8_192,
        "maxImagePixels" to 16_000_000, "maxDecodedImageBytesPerSheet" to 64 * mb, "maxParseMillis" to 5_000L,
        "maxLibraryItems" to 200, "maxLibraryBytes" to 16 * mb, "maxLayers" to 64, "maxLayerIdChars" to 64,
        "softElementThreshold" to 4_000, "softConstraintThreshold" to 160, "maxPngSidePx" to 16_000, "maxPngPixels" to 100_000_000L,
        "slowSolveIndicatorMs" to 100L, "maxHistoryEntries" to 100, "maxSemanticsNodes" to 200,
        "maxDiagnosticsEntries" to 20, "maxVisibleNotices" to 3, "maxGoToResults" to 50, "maxConflictCopiesPerSheet" to 10,
        "minZoomPercent" to 1, "maxZoomPercent" to 3_000, "maxPreloadBytes" to 128 * mb,
    )

    @Test
    fun sceneLimits_should_HaveExactlyTheCanonicalFieldsAndDefaults_And_NoImageMegapixelsFieldRemains() {
        val actual = SceneLimits.Default.asMap()
        assertEquals(33, canonical.size)
        assertEquals(canonical.keys.toList(), actual.keys.toList())
        canonical.forEach { (name, expected) -> assertEquals(expected.toDouble(), actual.getValue(name).toDouble(), name) }
        assertFalse("maxImageMegapixels" in actual)
    }

    @Test
    fun coordinates_should_BeRejected_WhenNonFiniteOrBeyond2Pow28() {
        val limits = SceneLimits()
        assertTrue(limits.isValidCoord(0.0))
        assertTrue(limits.isValidCoord(-268_435_456.0))
        assertTrue(limits.isValidCoord(268_435_456.0))
        assertFalse(limits.isValidCoord(268_435_456.0001))
        assertFalse(limits.isValidCoord(Double.NaN))
        assertFalse(limits.isValidCoord(Double.POSITIVE_INFINITY))
        assertFalse(limits.isValidCoord(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun softThresholds_should_SitBelowTheirHardCaps() {
        val l = SceneLimits()
        assertTrue(l.softElementThreshold < l.maxElements)
        assertTrue(l.softConstraintThreshold < l.maxConstraints)
        assertTrue(l.minZoomPercent < l.maxZoomPercent)
        assertTrue(l.maxImportSpanBytes < l.maxImportBytes)
    }
}
