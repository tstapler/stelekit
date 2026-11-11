// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fusion.FusionInput
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.SourceOrigin
import dev.stapler.stelekit.fusion.fuseObservations
import dev.stapler.stelekit.merge.OwnedMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 5.4c: our own digest page, once present in the graph, must not inflate the source counts on re-extract. */
class DigestSelfFeedTest {
    private val check = SumCheck("Zones sum to opening", 1, "niche.width", ClosetFusion.zones)

    private fun fuse(observations: List<dev.stapler.stelekit.fusion.Observation>) =
        fuseObservations(FusionInput(observations, ClosetFusion.constraints()), ClosetFusion.registry).fold({ error("$it") }, { it })

    @Test
    fun theDigestPageIsExcludedFromTheNextExtraction() {
        val clean = fuse(ClosetFusion.observations())
        val page = DigestWriter.buildPage("graph-a", "Sample Digest", DigestInput("proj-sample", clean, ClosetFusion.registry, listOf(check)))
        val origin = SourceOrigin(firstBlockPropertyKeys = checkNotNull(OwnedMarker.of(page)).properties.keys)

        val extractor = TableMeasurementExtractor(ClosetFusion.registry, RefTable())
        val fromDigest = extractor.extract(ClosetFusion.tables, "Sample Digest", origin)
        assertTrue(fromDigest.observations.isEmpty())
        assertTrue(fromDigest.skippedSelf >= 1)

        val base = ClosetFusion.observations()
        val echoed = base.first().let { it.copy(source = it.source.copy(origin = origin)) }
        val fed = fuse(base + echoed)
        assertTrue(fed.excludedSelf >= 1)
        assertEquals(clean.dimensions, fed.dimensions)
    }
}
