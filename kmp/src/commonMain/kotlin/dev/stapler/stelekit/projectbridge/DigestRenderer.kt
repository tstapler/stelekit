// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.DimensionStatus
import dev.stapler.stelekit.fusion.FusionResult
import dev.stapler.stelekit.fusion.FusionWarning

data class DigestInput(
    val slug: String,
    val result: FusionResult,
    val registry: DimensionRegistry,
    val checks: List<SumCheck>,
)

/**
 * Markdown digest of a fused project (Story 5.5a). Verdict lines come from the solver's invariant
 * results, so the digest cannot disagree with it. Output is deterministic (no timestamp) and capped
 * at [TOKEN_BUDGET] tokens by dropping trailing dimension lines, never checks or conflicts.
 */
object DigestRenderer {
    const val TOKEN_BUDGET = 2000
    private const val CHARS_PER_TOKEN = 4

    /** Top-level blocks in order: heading, checks, conflicts, under-constrained, then dimensions. */
    fun renderBlocks(input: DigestInput): List<String> {
        val (registry, result) = input.registry to input.result
        fun kindOf(key: String) = registry.entry(key)?.kind ?: DimensionKind.LENGTH
        fun label(key: String) = registry.entry(key)?.label ?: key

        val head = buildList {
            add("# Project digest: ${input.slug}")
            add("## Checks")
            input.checks.map { DimensionDisplay.evaluate(it, result, ::kindOf) }
                .forEach { add("- ${it.label}: ${it.verdict} ${it.summary}") }
            if (FusionWarning.MIRRORED_VIEW in result.warnings) add("- Warning: the drawing looks mirrored; its zone labels were ignored")
            if (result.excludedSelf > 0) add("- Excluded ${result.excludedSelf} reading(s) from our own digest")
        }
        val conflicts = result.dimensions.entries.mapNotNull { (key, status) -> (status as? DimensionStatus.Conflict)?.let { key to it } }
        val underConstrained = result.dimensions.filterValues { it == DimensionStatus.UnderConstrained }.keys
        val priority = head +
            listOf("## Conflicts (${conflicts.size})") + conflicts.flatMap { (key, status) ->
                status.pairs.map { p ->
                    val (a, b) = DimensionDisplay.value(p.first.value, kindOf(key)) to DimensionDisplay.value(p.second.value, kindOf(key))
                    val diff = DimensionDisplay.value(p.difference, kindOf(key))
                    "- ${label(key)}: $a (${p.first.source.kind.name.lowercase()}) vs $b (${p.second.source.kind.name.lowercase()}), differ by $diff"
                }
            } +
            listOf("## Under-constrained (${underConstrained.size})") + underConstrained.map { "- ${label(it)}" } +
            listOf("## Dimensions")

        val lines = result.dimensions.entries
            .filter { it.value !is DimensionStatus.Conflict && it.value != DimensionStatus.UnderConstrained }
            .map { (key, status) -> "- ${label(key)}: ${describe(status, kindOf(key))}" }

        val budget = TOKEN_BUDGET * CHARS_PER_TOKEN
        var used = priority.sumOf { it.length + 1 }
        val kept = ArrayList<String>()
        for (line in lines) {
            if (used + line.length + 1 + omissionLine(lines.size).length + 1 > budget) break
            kept += line
            used += line.length + 1
        }
        val omitted = lines.size - kept.size
        return priority + kept + if (omitted > 0) listOf(omissionLine(omitted)) else emptyList()
    }

    fun render(input: DigestInput): String = renderBlocks(input).joinToString("\n", postfix = "\n")

    private fun omissionLine(count: Int) = "- $count more omitted"

    private fun describe(status: DimensionStatus, kind: DimensionKind): String = when (status) {
        is DimensionStatus.Confirmed -> "${DimensionDisplay.value(status.value, kind)} (confirmed, ${status.sources} sources)"
        is DimensionStatus.SingleSource -> "${DimensionDisplay.value(status.value, kind)} (single source: ${status.source.kind.name.lowercase()})"
        is DimensionStatus.Derivable -> "${DimensionDisplay.value(status.value, kind)} (derived)"
        is DimensionStatus.NotRepresentableValue -> "${DimensionDisplay.value(status.exact, kind)} (exact, not a whole sixteenth)"
        is DimensionStatus.Conflict, DimensionStatus.UnderConstrained -> error("handled by the priority blocks")
    }
}
