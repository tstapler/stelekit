// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fixtures.ClosetFixtureText
import dev.stapler.stelekit.fixtures.ClosetFusion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The harness over the synthetic closet fixtures: counts are hand-written, and the shareable text carries no values. */
class FusionHarnessTest {
    private val spec = FusionSpec(ClosetFusion.constraints(), listOf(SumCheck("Zones sum to opening", 1, "niche.width", ClosetFusion.zones)), ClosetFusion.zones)

    private fun inputs(tables: String = ClosetFixtureText.load("closet-tables.md")) = HarnessInputs(
        pagesScanned = 7,
        pages = listOf(tables),
        yaml = ClosetFixtureText.load("closet.yaml"),
        sheets = listOf(SheetSvg("elevation", "e", ClosetFixtureText.load("closet-elevation.svg")), SheetSvg("section", "s", ClosetFixtureText.load("closet-section.svg"))),
        registry = ClosetFusion.registry,
        spec = spec,
    )

    @Test
    fun reportsInputCountsAndTheKnownFixtureConflicts() = runTest {
        val report = FusionHarness.run(inputs())

        assertEquals(7, report.pagesScanned)
        assertEquals(1, report.pagesSelected)
        assertEquals(2, report.drawings)
        assertTrue(report.tables >= 1)
        assertTrue(report.measurementsBound > report.fused)
        assertEquals(2, report.conflicts, "rod.diameter and rod-carrier.count, as in ClosetEndToEndTest")
        assertEquals(setOf("MIRRORED_VIEW"), report.warnings)
        assertEquals(0, report.invariantFailures)
    }

    @Test
    fun aSeededTableDisagreementShowsAsATableYamlConflictAndAFailedInvariant() = runTest {
        val report = FusionHarness.run(inputs(ClosetFixtureText.load("closet-tables.md").replace("**33 7/16\"**", "**33 3/8\"**")))

        assertEquals(3, report.conflicts)
        assertTrue("TABLE~YAML" in report.conflictKinds, report.conflictKinds.toString())
        assertTrue(report.invariantFailures >= 1)
    }

    @Test
    fun aRenamedColumnCountsAsUnboundAndTheShareableTextHasNoValues() = runTest {
        val report = FusionHarness.run(inputs(ClosetFixtureText.load("closet-tables.md").replace("| Zone | Width |", "| Zone | Span |")))

        assertTrue(report.unbound >= 1)
        assertTrue(report.shareable.none { it == '"' } && "136" !in report.shareable, report.shareable)
        assertTrue("136" in report.localReport)
    }
}
