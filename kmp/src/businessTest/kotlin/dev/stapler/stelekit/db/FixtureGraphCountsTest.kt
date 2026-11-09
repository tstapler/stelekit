// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.fixtures.FixtureGraphSeeds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the Library scenario (f) fixture: 5,000 annotation rows, 5,000 asset rows, 20 distinct JPEGs. */
class FixtureGraphCountsTest {
    @Test
    fun annotationRows_should_Be5000_WithUniqueUuids() {
        val rows = FixtureGraphSeeds.imageAnnotations()
        assertEquals(5_000, rows.size)
        assertEquals(5_000, rows.map { it.uuid }.toSet().size)
    }

    @Test
    fun assetRows_should_Be5000_WithUniqueUuidsAndPaths() {
        val rows = FixtureGraphSeeds.assetEntries()
        assertEquals(5_000, rows.size)
        assertEquals(5_000, rows.map { it.uuid.value }.toSet().size)
    }

    @Test
    fun rows_should_ReuseExactly20DistinctJpegs() {
        val bases = (0 until FixtureGraphSeeds.ASSET_ROWS).map { FixtureGraphSeeds.baseIndex(it) }.toSet()
        assertEquals(20, bases.size)
        assertEquals(5_000, FixtureGraphSeeds.imageAnnotations().map { it.filePath }.toSet().size)
    }

    @Test
    fun everyName_should_BeSyntheticAndUnderTheSyntheticGraph() {
        assertTrue(FixtureGraphSeeds.imageAnnotations(200).all {
            it.uuid.startsWith("synthetic-") && it.filePath.startsWith(FixtureGraphSeeds.GRAPH_PATH)
        })
        assertTrue(FixtureGraphSeeds.assetEntries(200).all { it.uuid.value.startsWith("synthetic-") })
    }
}
