// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.right
import dev.stapler.stelekit.fixtures.ClosetFixtureText
import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fixtures.FC
import dev.stapler.stelekit.fusion.DimensionStatus
import dev.stapler.stelekit.fusion.FusionResult
import dev.stapler.stelekit.fusion.FusionWarning
import dev.stapler.stelekit.fusion.InvariantVerdict
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.jobs.InProcessJobs
import dev.stapler.stelekit.llm.FakeLlmProvider
import dev.stapler.stelekit.llm.LlmFeature
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.llm.LlmSuggestionInbox
import dev.stapler.stelekit.llm.OutboundLlmGate
import dev.stapler.stelekit.llm.PrivateTermList
import dev.stapler.stelekit.llm.ProjectAnalysisPromptBuilder
import dev.stapler.stelekit.llm.ProjectAnalysisService
import dev.stapler.stelekit.merge.MergePage
import dev.stapler.stelekit.merge.WriteOutcome
import dev.stapler.stelekit.testing.GoldenFile
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.voice.LlmResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 5.8, success metrics 1 and 3: the SHIPPED extractors (table, yaml, prose, count, svg label,
 * svg geometry) read the frozen closet fixtures through the registry into the fusion engine, and the
 * result feeds the post-sync job's digest and gated analysis step. Every expected count below is a
 * hand-written constant; only the per-dimension summary goes through the golden file.
 *
 * Known extractor gaps are pinned, not hidden: the standard positions and the shelf gap are stated
 * in a prose list and a table cell that no extractor reads yet, and the cabinet bay has one source.
 * Closing a gap moves a dimension from [SINGLE_SOURCE_GAPS] to [TWO_SOURCE_DIMENSIONS] on purpose.
 */
class ClosetEndToEndTest {
    private val registry = ClosetFusion.registry
    private val zoneCheck = SumCheck("Zones sum to opening", constraintId = 1, total = "niche.width", parts = ClosetFusion.zones)
    private val spec = FusionSpec(ClosetFusion.constraints(), listOf(zoneCheck), ClosetFusion.zones)
    private val stage = ProjectFusionStage(SLUG, registry, spec)

    private suspend fun collect(reader: ProjectSourceReader = FixtureSourceReader()): CollectedSources =
        ProjectSourceCollector(registry, RefTable(), reader).collect()

    private fun fuse(collected: CollectedSources): FusionResult = checkNotNull(stage.digestInput(collected)) { "fusion failed" }.result

    private fun conflicts(result: FusionResult) = result.dimensions.filterValues { it is DimensionStatus.Conflict }.mapValues { it.value as DimensionStatus.Conflict }

    private fun invariantVerdicts(result: FusionResult): Map<String, InvariantVerdict> {
        val byId = result.invariants.associate { it.constraintId to it.verdict }
        return INVARIANT_IDS.mapValues { (_, ids) ->
            val each = ids.map { byId.getValue(it) }
            when {
                InvariantVerdict.FAILS in each -> InvariantVerdict.FAILS
                each.all { it == InvariantVerdict.HOLDS } -> InvariantVerdict.HOLDS
                else -> InvariantVerdict.UNDETERMINED
            }
        }
    }

    @Test
    fun twelveClosetDimensionsHaveAtLeastTwoIndependentSources() = runTest {
        val collected = collect()
        val sourcesByDimension = collected.observations.groupBy { it.dimension }.mapValues { (_, v) -> v.map { it.source.ref.normalised }.toSet().size }

        assertEquals(12, TWO_SOURCE_DIMENSIONS.size)
        TWO_SOURCE_DIMENSIONS.forEach { key -> assertTrue((sourcesByDimension[key] ?: 0) >= 2, "$key has ${sourcesByDimension[key]} source(s)") }
        SINGLE_SOURCE_GAPS.forEach { key -> assertTrue((sourcesByDimension[key] ?: 0) <= 1, "$key gained a source: update the pinned gaps") }
        val result = fuse(collected)
        TWO_SOURCE_DIMENSIONS.forEach { assertIs<DimensionStatus.Confirmed>(result.dimensions[it], "$it not confirmed") }
        assertEquals(0, collected.tableNeedsMapping.size)
    }

    @Test
    fun zoneValuesAndTheNegativeControlsConfirmWithoutAConflict() = runTest {
        val result = fuse(collect())
        fun value(key: String) = assertIs<DimensionStatus.Confirmed>(result.dimensions[key]).value
        assertEquals(FC.zoneLeftLong, value("zone.left-long.width"))
        assertEquals(FC.zoneLeftShort, value("zone.left-short.width"))
        assertEquals(FC.zoneShelves, value("zone.shelves.width"))
        assertEquals(FC.zoneRightShort, value("zone.right-short.width"))
        assertEquals(FC.zoneRightLong, value("zone.right-long.width"))
        assertEquals(FC.opening, value("niche.width"))
        assertEquals(FC.cabinetHeightA, value("niche.height.front"), "C6 decimal 43.31 agrees with its fraction")
        assertEquals(FC.usableCabinetHeight, value("lowercab.height"), "C6 decimal 23.12 agrees with its fraction")
        assertEquals(Rational(497, 8), value("hang.total"), "an approximate table value agrees with the yaml total")
    }

    @Test
    fun nineInvariantVerdictsAreCheckedAutomatically() = runTest {
        val verdicts = invariantVerdicts(fuse(collect()))
        assertEquals(9, verdicts.size)
        val expected = INVARIANT_IDS.keys.associateWith { InvariantVerdict.UNDETERMINED } +
            mapOf("I1" to InvariantVerdict.HOLDS, "I8" to InvariantVerdict.HOLDS)
        assertEquals(expected, verdicts)
        assertEquals(0, verdicts.values.count { it == InvariantVerdict.FAILS })
    }

    @Test
    fun theThreeRealDiscrepanciesAreReportedAndNothingElse() = runTest {
        val result = fuse(collect())
        val found = conflicts(result)

        assertEquals(setOf("rod-carrier.count", "rod.diameter"), found.keys, "C4 and C5 conflict; C3 is the mirrored-view warning")
        assertEquals(setOf(FusionWarning.MIRRORED_VIEW), result.warnings, "C3")
        val carriers = found.getValue("rod-carrier.count").pairs.flatMap { listOf(it.first.value, it.second.value) }.toSet()
        assertEquals(FC.carrierCounts.map { Rational(it.toLong()) }.toSet(), carriers, "C5")
        val diameter = found.getValue("rod.diameter").pairs.single()
        assertEquals(Rational(3, 16), diameter.difference, "C4: radius*2 against the prose outer diameter")
        assertEquals(setOf(SourceKind.PROSE, SourceKind.DERIVED), setOf(diameter.first.source.kind, diameter.second.source.kind))
    }

    @Test
    fun seededC1IsAConflictNamingTableAndYamlAndFailsTheZoneSum() = runTest {
        val tables = ClosetFixtureText.load("closet-tables.md")
        val mutated = FixtureSourceReader(tables = tables.replace("**33 7/16\"**", "**33 3/8\"**"))
        val collected = collect(mutated)
        val result = fuse(collected)

        val pairs = conflicts(result).getValue("zone.left-long.width").pairs
        assertTrue(pairs.all { it.difference == Rational(1, 16) })
        assertTrue(pairs.flatMap { listOf(it.first.source.kind, it.second.source.kind) }.containsAll(listOf(SourceKind.TABLE, SourceKind.YAML)))
        assertEquals(InvariantVerdict.FAILS, invariantVerdicts(result).getValue("I1"))
        assertEquals(InvariantVerdict.FAILS, result.invariants.single { it.constraintId == 1 }.bySource.getValue(SourceKind.TABLE))

        val digest = checkNotNull(stage.digestInput(collected)).let { DigestRenderer.render(it) }
        assertTrue("- Zones sum to opening: FAIL " in digest, digest)
        assertTrue("(table)" in digest && "(yaml)" in digest && "differ by 1/16\"" in digest, digest)
    }

    @Test
    fun seededC2IsNeverGuessedUntilTheOwnerRetagsTheLabelThenItConflictsWithTheOtherSources() = runTest {
        val changed = ClosetFixtureText.load("closet-elevation.svg").replace(">38\" Rod<", ">${FC.rodLabelMutated}<")
        val untagged = collect(FixtureSourceReader(elevation = changed))
        assertTrue(untagged.svgUnboundLabels.any { it.text == FC.rodLabelMutated }, "the changed label is offered for tagging")
        assertEquals(setOf("rod-carrier.count", "rod.diameter"), conflicts(fuse(untagged)).keys)

        val retagged = registry.copy(dimensions = registry.dimensions.map { entry ->
            if (entry.key == "rod.long.height") entry.copy(aliases = entry.aliases.copy(svgLabel = listOf(FC.rodLabelMutated))) else entry
        })
        val collected = ProjectSourceCollector(retagged, RefTable(), FixtureSourceReader(elevation = changed)).collect()
        val input = checkNotNull(ProjectFusionStage(SLUG, retagged, spec).digestInput(collected))

        val rod = conflicts(input.result).getValue("rod.long.height")
        assertTrue(rod.pairs.all { it.difference == Rational(1) })
        val kinds = rod.pairs.flatMap { listOf(it.first.source.kind, it.second.source.kind) }.toSet()
        assertTrue(SourceKind.SVG_LABEL in kinds && SourceKind.TABLE in kinds, kinds.toString())
        assertEquals(setOf("rod-carrier.count", "rod.diameter", "rod.long.height"), conflicts(input.result).keys)
        assertEquals(0, invariantVerdicts(input.result).values.count { it == InvariantVerdict.FAILS })
        val digest = DigestRenderer.render(input)
        assertTrue("## Conflicts (3)" in digest && "(svg_label)" in digest && "(table)" in digest, digest)
    }

    @Test
    fun aRenamedColumnIsNeedsMappingNotAGuess() = runTest {
        val tables = ClosetFixtureText.load("closet-tables.md").replace("| Zone | Width |", "| Zone | Span |")
        val collected = collect(FixtureSourceReader(tables = tables))

        assertEquals(1, collected.tableNeedsMapping.size)
        assertEquals(listOf("Span"), collected.tableNeedsMapping.single().columns)
        assertTrue(collected.observations.none { it.dimension == "zone.left-long.width" && it.source.kind == SourceKind.TABLE })
    }

    @Test
    fun theFusionSummaryMatchesTheStoredGolden() = runTest {
        val clean = collect()
        val result = fuse(clean)
        val mutated = collect(FixtureSourceReader(tables = ClosetFixtureText.load("closet-tables.md").replace("| Zone | Width |", "| Zone | Span |")))
        GoldenFile.assertMatches("expected-fusion.json", summary(result, clean.tableNeedsMapping.size, mutated.tableNeedsMapping.size))
    }

    @Test
    fun theDigestCarriesThePrecomputedVerdictAndEveryZoneValue() = runTest {
        val digest = DigestRenderer.render(checkNotNull(stage.digestInput(collect())))
        val verdict = digest.lines().single { it.startsWith("- Zones sum to opening:") }

        assertEquals("- Zones sum to opening: PASS (33 7/16\" + 22 1/2\" + 24 3/16\" + 22 1/2\" + 33 3/8\") = 136\"", verdict)
        listOf("Left long hang width: 33 7/16\"", "Left short hang width: 22 1/2\"", "Shelf zone width: 24 3/16\"",
            "Right short hang width: 22 1/2\"", "Right long hang width: 33 3/8\"").forEach { assertTrue(it in digest, "$it missing:\n$digest") }
    }

    @Test
    fun thePostSyncJobWritesTheRealDigestAndSendsAGatedPromptToAStubbedModel() = runTest {
        val settings = BridgeMapSettings().also { enableBridge(it) }
        val bridge = ProjectBridgeSettings(settings)
        val llmSettings = LlmSettings(settings)
        bridge.setAnalyseAfterSync(BRIDGE_GRAPH, true)
        llmSettings.setSelectedProviderId(LlmFeature.PROJECT_ANALYSIS, "stub")
        llmSettings.acknowledge(LlmFeature.PROJECT_ANALYSIS, "stub", "")
        val provider = FakeLlmProvider("stub", response = LlmResult.Success("The zone sum is PASS: the five zones add up to 136\""))
        val service = ProjectAnalysisService(LlmProviderRegistry(listOf(provider)), llmSettings, LlmSuggestionInbox())
        val collected = collect()
        val input = checkNotNull(stage.digestInput(collected))
        val written = mutableListOf<MergePage>()
        val writer = DigestWriter(
            replace = { _, _, page -> written += page; WriteOutcome.Created("p", "h").right() },
            readBack = { _, key -> DigestWriter.buildPage("g", key.name, input).right() },
            hasWriter = { true },
        )
        val jobs = InProcessJobs()
        val job = PostSyncProjectJob(
            bridgeDeps(settings, jobs, router = { newBridgeTestRouter() }),
            ProjectSourceCollector(registry, RefTable(), FixtureSourceReader()),
            digestWriter = writer,
            analysisStep = ProjectAnalysisStep(bridge, llmSettings, service, previewAccepted = { _, _ -> true }),
            fusion = stage,
        )

        assertIs<PostSyncRunOutcome.Completed>(job.run(BRIDGE_GRAPH) { false })

        assertEquals(1, written.size)
        val body = written.single().blocks.joinToString("\n") { it.content }
        assertTrue("Zones sum to opening: PASS" in body, body)
        val call = provider.calls.single()
        val prompt = ProjectAnalysisPromptBuilder.parts(SLUG, OutboundPreview.payloadText(input.let { PostSyncProjectJob.projectOutputs(it, collected.observations) }.payload))
        assertTrue(prompt.payload.trim() in call.transcript)
        assertTrue("- Zones sum to opening: PASS " in call.transcript)
        listOf("33 7/16\"", "22 1/2\"", "24 3/16\"", "33 3/8\"").forEach { assertTrue(it in call.transcript, it) }
        assertTrue(ProjectAnalysisPromptBuilder.estimatedTokens(prompt) < ProjectAnalysisPromptBuilder.TOKEN_BUDGET)
    }

    @Test
    fun theOutboundGateHardBlocksATitleAnAddressAndAFilenameInTheRealPayload() = runTest {
        val collected = collect()
        val address = listOf("12", "Maple", "Street").joinToString(" ")
        val cases = mapOf("Sample Page Title" to PrivateTermList(listOf("Sample Page Title")), address to PrivateTermList.EMPTY, "closet-tables.md" to PrivateTermList.EMPTY)
        for ((leak, terms) in cases) {
            val relabelled = registry.copy(dimensions = registry.dimensions.map { if (it.key == "niche.width") it.copy(label = leak) else it })
            val outputs = checkNotNull(ProjectFusionStage(SLUG, relabelled, spec).outputs(collected))
            val parts = ProjectAnalysisPromptBuilder.parts(SLUG, OutboundPreview.payloadText(outputs.payload))
            assertTrue(OutboundLlmGate.check(parts, terms).isLeft(), "'$leak' reached the provider")
        }
        val clean = checkNotNull(stage.outputs(collected))
        val gated = OutboundLlmGate.check(ProjectAnalysisPromptBuilder.parts(SLUG, OutboundPreview.payloadText(clean.payload)), PrivateTermList(listOf("Sample Page Title")))
        assertTrue(gated.isRight(), gated.leftOrNull()?.reason ?: "")
    }

    private fun summary(result: FusionResult, needsMapping: Int, mutatedNeedsMapping: Int): String {
        fun status(s: DimensionStatus) = when (s) {
            is DimensionStatus.Confirmed -> "confirmed ${s.value} x${s.sources}"
            is DimensionStatus.SingleSource -> "single ${s.value}"
            is DimensionStatus.Conflict -> "conflict " + s.pairs.flatMap { listOf(it.first.value, it.second.value) }.toSortedSet().joinToString(" | ")
            is DimensionStatus.Derivable -> "derivable ${s.value}"
            is DimensionStatus.NotRepresentableValue -> "not-representable ${s.exact}"
            DimensionStatus.UnderConstrained -> "under-constrained"
        }
        val verdicts = invariantVerdicts(result)
        return buildString {
            appendLine("{")
            appendLine("  \"fixture\": \"closet\",")
            appendLine("  \"dimensionsWithTwoOrMoreSources\": ${TWO_SOURCE_DIMENSIONS.size},")
            appendLine("  \"needsMapping\": { \"closet\": $needsMapping, \"renamedColumn\": $mutatedNeedsMapping },")
            appendLine("  \"warnings\": [${result.warnings.joinToString(", ") { "\"$it\"" }}],")
            appendLine("  \"invariants\": { ${verdicts.entries.joinToString(", ") { "\"${it.key}\": \"${it.value}\"" }} },")
            appendLine("  \"dimensions\": {")
            appendLine(result.dimensions.entries.joinToString(",\n") { "    \"${it.key}\": \"${status(it.value)}\"" })
            appendLine("  }")
            appendLine("}")
        }
    }

    private companion object {
        const val SLUG = "proj-closet-1"

        val TWO_SOURCE_DIMENSIONS = listOf(
            "niche.width", "zone.left-long.width", "zone.left-short.width", "zone.shelves.width", "zone.right-short.width",
            "zone.right-long.width", "niche.height.rear", "niche.height.front", "rod.short.height", "rod.long.height",
            "niche.depth", "lowercab.height",
        )
        val SINGLE_SOURCE_GAPS = listOf("lowercab.bay", "shelf.gap", "standards.positions.1", "standards.positions.3")

        val INVARIANT_IDS: Map<String, List<Int>> = mapOf(
            "I1" to listOf(1), "I2" to listOf(2), "I3" to listOf(3), "I4" to (4..8).toList(), "I5" to listOf(9, 10, 11),
            "I6" to (12..20).toList(), "I7" to listOf(21), "I8" to listOf(22), "I9" to listOf(23, 24, 25),
        )
    }
}
