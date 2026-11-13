// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.DimensionStatus
import dev.stapler.stelekit.fusion.InvariantVerdict
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.parsing.TableExtraction

/** Text the harness fuses. Page order fixes the opaque `page-N` slugs; nothing here is ever written back. */
internal data class HarnessInputs(
    val pagesScanned: Int,
    val pages: List<String>,
    val yaml: String?,
    val sheets: List<SheetSvg>,
    val registry: DimensionRegistry,
    val spec: FusionSpec,
)

/**
 * Counts from one harness run. [shareable] holds counts and conflict kinds only (no values, titles or
 * file names); [localReport] adds per-dimension values and the rendered digest and stays on this machine.
 */
internal data class HarnessReport(
    val pagesScanned: Int,
    val pagesSelected: Int,
    val drawings: Int,
    val tables: Int,
    val measurementsBound: Int,
    val fused: Int,
    val confirmed: Int,
    val conflicts: Int,
    val unbound: Int,
    val conflictKinds: Map<String, Int>,
    val invariantFailures: Int,
    val warnings: Set<String>,
    val localReport: String,
) {
    val shareable: String
        get() = buildString {
            appendLine("pages_scanned=$pagesScanned pages_selected=$pagesSelected drawings=$drawings tables=$tables")
            appendLine("measurements_bound=$measurementsBound fused=$fused confirmed=$confirmed conflicts=$conflicts unbound=$unbound")
            appendLine("invariant_failures=$invariantFailures warnings=${warnings.sorted()}")
            appendLine("conflict_kinds=${conflictKinds.toSortedMap()}")
        }
}

/**
 * Runs the shipped extractors and the fusion engine over [HarnessInputs] and summarises the outcome.
 * Read-only by construction: it never touches DigestWriter or any writer, only [DigestRenderer.render].
 */
internal object FusionHarness {
    private const val SLUG = "harness"

    suspend fun run(inputs: HarnessInputs): HarnessReport {
        val pages = inputs.pages.mapIndexed { i, text -> TablePageText("page-${i + 1}", text) }
        val texts = ProjectSourceTexts(pages = pages, yamlSnapshot = inputs.yaml, sheets = inputs.sheets)
        val collected = ProjectSourceCollector(inputs.registry, RefTable()) { texts }.collect()
        val digestInput = checkNotNull(ProjectFusionStage(SLUG, inputs.registry, inputs.spec).digestInput(collected)) { "fusion failed" }
        val result = digestInput.result

        val statuses = result.dimensions.values
        val conflicted = result.dimensions.filterValues { it is DimensionStatus.Conflict }
        val kinds = conflicted.values.flatMap { status ->
            (status as DimensionStatus.Conflict).pairs.map { listOf(it.first.source.kind.name, it.second.source.kind.name).sorted().joinToString("~") }
        }.groupingBy { it }.eachCount()
        val unbound = collected.svgUnboundLabels.size + collected.tableNeedsMapping.size
        val invariantFailures = result.invariants.count { it.verdict == InvariantVerdict.FAILS }

        val local = buildString {
            appendLine("# Local fusion harness report (do not commit)")
            result.dimensions.forEach { (key, status) ->
                appendLine("- $key: ${describe(status)}")
            }
            appendLine("unbound_labels=${collected.svgUnboundLabels.size} tables_needing_mapping=${collected.tableNeedsMapping.size}")
            appendLine()
            append(DigestRenderer.render(digestInput))
        }
        return HarnessReport(
            pagesScanned = inputs.pagesScanned,
            pagesSelected = inputs.pages.size,
            drawings = inputs.sheets.size,
            tables = inputs.pages.sumOf { TableExtraction.parseTables(it).size },
            measurementsBound = collected.observations.size,
            fused = statuses.count { it !is DimensionStatus.UnderConstrained },
            confirmed = statuses.count { it is DimensionStatus.Confirmed },
            conflicts = conflicted.size,
            unbound = unbound,
            conflictKinds = kinds,
            invariantFailures = invariantFailures,
            warnings = result.warnings.map { it.name }.toSet(),
            localReport = local,
        )
    }

    private fun describe(status: DimensionStatus): String = when (status) {
        is DimensionStatus.Confirmed -> "confirmed x${status.sources} = ${status.value}"
        is DimensionStatus.SingleSource -> "single ${status.source.kind} = ${status.value}"
        is DimensionStatus.Conflict -> "CONFLICT " + status.pairs.joinToString("; ") {
            "${it.first.source.kind} ${it.first.value} vs ${it.second.source.kind} ${it.second.value}"
        }
        is DimensionStatus.Derivable -> "derived = ${status.value}"
        is DimensionStatus.NotRepresentableValue -> "not representable = ${status.exact}"
        DimensionStatus.UnderConstrained -> "under-constrained"
    }
}
