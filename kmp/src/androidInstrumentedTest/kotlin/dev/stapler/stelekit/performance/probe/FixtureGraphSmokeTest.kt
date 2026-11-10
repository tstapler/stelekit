// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.fixtures.FixtureGraphSeeds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [FixtureGraph] must produce the counts `FixtureGraphCountsTest` pins, on the Android drivers (plan B.3e). */
@RunWith(AndroidJUnit4::class)
class FixtureGraphSmokeTest {
    @Test
    fun seed_should_InsertTheRequestedRows_AndWriteDecodableJpegs() = runBlocking {
        val graph = FixtureGraph(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            graph.seed(annotationRows = SMALL, assetRows = SMALL)
            assertEquals(SMALL.toLong(), graph.assets.countAssets().getOrNull())
            val pageRows = graph.annotations.getImageAnnotationsByPage("synthetic-page-0").first().getOrNull().orEmpty()
            assertTrue(pageRows.isNotEmpty())
            assertEquals(FixtureGraphSeeds.DISTINCT_JPEGS, (0 until FixtureGraphSeeds.DISTINCT_JPEGS).count { graph.jpegFile(it).length() > 0 })
        } finally {
            graph.close()
        }
    }

    private companion object {
        const val SMALL = 200
    }
}
