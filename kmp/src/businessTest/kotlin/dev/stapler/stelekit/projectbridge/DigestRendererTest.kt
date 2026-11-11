// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fixtures.ClosetFixtureText
import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fixtures.FC
import dev.stapler.stelekit.fusion.DimensionAliases
import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.FusionInput
import dev.stapler.stelekit.fusion.FusionResult
import dev.stapler.stelekit.fusion.InvariantVerdict
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.Source
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.fusion.SourceRef
import dev.stapler.stelekit.fusion.fuseObservations
import dev.stapler.stelekit.units.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 5.5a: the digest is a deterministic projection of the solver's verdicts and values. */
class DigestRendererTest {
    private val registry = ClosetFusion.registry
    private val zoneCheck = SumCheck("Zones sum to opening", constraintId = 1, total = "niche.width", parts = ClosetFusion.zones)

    private fun fuse(observations: List<Observation>): FusionResult =
        fuseObservations(FusionInput(observations, ClosetFusion.constraints()), registry).fold({ error("fusion failed: $it") }, { it })

    private fun digest(result: FusionResult) = DigestRenderer.render(DigestInput("proj-closet-1", result, registry, listOf(zoneCheck)))

    private val clean = fuse(ClosetFusion.observations())

    @Test
    fun `digest bytes equal the golden for the closet`() {
        assertEquals(ClosetFixtureText.load("expected-digest.md"), digest(clean))
    }

    @Test
    fun `verdict line carries the PASS and every zone value`() {
        val text = digest(clean)
        assertTrue("- Zones sum to opening: PASS (33 7/16\" + 22 1/2\" + 24 3/16\" + 22 1/2\" + 33 3/8\") = 136\"" in text, text)
        assertEquals(FC.opening, Rational(136))
    }

    @Test
    fun `verdict equals the solver verdict for clean and for a seeded zone conflict`() {
        val mutated = fuse(ClosetFusion.observations(tablesText = ClosetFusion.tables.replace("**33 7/16\"**", "**33 3/8\"**")))
        for (result in listOf(clean, mutated)) {
            val solver = result.invariants.single { it.constraintId == 1 }.verdict
            val line = digest(result).lines().single { it.startsWith("- Zones sum to opening:") }
            val expected = when (solver) {
                InvariantVerdict.HOLDS -> "PASS"
                InvariantVerdict.FAILS -> "FAIL"
                InvariantVerdict.UNDETERMINED -> "UNKNOWN"
            }
            assertTrue(line.startsWith("- Zones sum to opening: $expected "), line)
        }
        assertEquals(InvariantVerdict.FAILS, mutated.invariants.single { it.constraintId == 1 }.verdict)
    }

    @Test
    fun `conflicts come before the dimension list`() {
        val mutated = fuse(ClosetFusion.observations(tablesText = ClosetFusion.tables.replace("**33 7/16\"**", "**33 3/8\"**")))
        val text = digest(mutated)
        assertTrue(text.indexOf("## Conflicts (1)") in 0 until text.indexOf("## Dimensions"))
        assertTrue("- Left long hang width: 33 3/8\" (table) vs 33 7/16\"" in text && "differ by 1/16\"" in text, text)
    }

    @Test
    fun `rendering twice gives identical bytes and blocks join to the text`() {
        val input = DigestInput("proj-closet-1", clean, registry, listOf(zoneCheck))
        assertEquals(DigestRenderer.render(input), DigestRenderer.render(input))
        assertEquals(DigestRenderer.render(input), DigestRenderer.renderBlocks(input).joinToString("\n", postfix = "\n"))
    }

    @Test
    fun `a large model is capped at the token budget and says how many were omitted`() {
        val entries = (1..600).map { DimensionEntry("dim.$it", DimensionKind.LENGTH, "Measured dimension number $it", DimensionAliases()) }
        val big = DimensionRegistry(dimensions = entries)
        val observations = entries.flatMap { e ->
            listOf(SourceKind.TABLE, SourceKind.YAML).map {
                Observation(e.key, Rational(10), Rational.ZERO, DimensionKind.LENGTH, Source(it, SourceRef(it, e.key)))
            }
        }
        val result = fuseObservations(FusionInput(observations), big).fold({ error("fusion failed: $it") }, { it })
        val text = DigestRenderer.render(DigestInput("proj-closet-1", result, big, emptyList()))
        assertTrue(text.length <= DigestRenderer.TOKEN_BUDGET * 4, "length ${text.length}")
        val omitted = Regex("- (\\d+) more omitted").find(text)?.groupValues?.get(1)?.toInt() ?: error("no omission line")
        assertEquals(600, text.lines().count { it.startsWith("- Measured dimension") } + omitted)
    }

    @Test
    fun `job seam yields the same digest and the same payload as the two projections`() {
        val input = DigestInput("proj-closet-1", clean, registry, listOf(zoneCheck))
        val outputs = PostSyncProjectJob.projectOutputs(input, ClosetFusion.observations())
        assertEquals(digest(clean), outputs.digest)
        assertEquals("proj-closet-1", outputs.payload.project)
    }

    @Test
    fun `digest never carries a fixture filename or a source ref`() {
        val text = digest(clean)
        assertFalse(Regex("\\.(md|svg|yaml|png)\\b").containsMatchIn(text))
        assertFalse(Regex("\\b(table|svg|yaml|prose):").containsMatchIn(text))
    }
}
