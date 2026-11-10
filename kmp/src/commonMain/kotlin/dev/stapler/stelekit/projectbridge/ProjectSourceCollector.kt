// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.SourceOrigin

/** A wiki page a project is bound to; [filePath] is where the loader reads it from. */
data class BoundPageRef(val pageName: String, val filePath: String)

/** What one collection pass found. Later stories add fields; today the shell finds nothing. */
data class CollectedSources(
    val boundPages: List<BoundPageRef> = emptyList(),
    val tableObservations: List<Observation> = emptyList(),
    val tableNeedsMapping: List<NeedsMapping> = emptyList(),
)

/** Markdown of one bound table page; [locator] stays device-local in the [RefTable]. */
data class TablePageText(val locator: String, val markdown: String, val origin: SourceOrigin = SourceOrigin())

/**
 * Collector shell: every later source story (tables, yaml, svg, geometry) adds one stage call here.
 * Until then it returns an empty [CollectedSources], so the job runs with no data flowing.
 */
open class ProjectSourceCollector(
    registry: DimensionRegistry = DimensionRegistry(dimensions = emptyList()),
    refTable: RefTable = RefTable(),
) {
    private val tableExtractor = TableMeasurementExtractor(registry, refTable)

    open suspend fun collect(): CollectedSources {
        val tables = extractTables(emptyList()) // Story 5.9b supplies the bound pages' text
        return CollectedSources(tableObservations = tables.observations, tableNeedsMapping = tables.needsMapping)
    }

    /** Table stage (Story 5.2). */
    internal fun extractTables(pages: List<TablePageText>): TableExtractionResult =
        pages.fold(TableExtractionResult()) { acc, p -> acc + tableExtractor.extract(p.markdown, p.locator, p.origin) }
}
