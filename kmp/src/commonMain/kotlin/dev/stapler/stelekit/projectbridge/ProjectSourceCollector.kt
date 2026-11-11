// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.SourceOrigin
import dev.stapler.stelekit.sketchimport.BoundLabel
import dev.stapler.stelekit.sketchimport.LabelBinder
import dev.stapler.stelekit.sketchimport.LabelBindingResult
import dev.stapler.stelekit.sketchimport.readSvg

/** A wiki page a project is bound to; [filePath] is where the loader reads it from. */
data class BoundPageRef(val pageName: String, val filePath: String)

/** What one collection pass found. Later stories add fields; today the shell finds nothing. */
data class CollectedSources(
    val boundPages: List<BoundPageRef> = emptyList(),
    val yamlObservations: List<Observation> = emptyList(),
    val tableObservations: List<Observation> = emptyList(),
    val tableNeedsMapping: List<NeedsMapping> = emptyList(),
    val proseObservations: List<Observation> = emptyList(),
    val svgObservations: List<Observation> = emptyList(),
    /** Drawing labels the binder could not bind; the tagging UI offers them for manual binding. */
    val svgUnboundLabels: List<BoundLabel> = emptyList(),
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
    /** The bound pages' text (Story 5.9b); a no-op when no source is bound. */
    private val sources: suspend () -> SourceReadResult = { SourceReadResult.EMPTY },
) {
    private val tableExtractor = TableMeasurementExtractor(registry, refTable)
    private val yamlExtractor = YamlMeasurementExtractor(registry, refTable)
    private val proseExtractor = ProseMeasurementExtractor(registry, refTable)
    private val countExtractor = CountExtractor(registry, refTable)
    private val labelBinder = LabelBinder(registry, refTable)

    open suspend fun collect(): CollectedSources {
        val read = sources()
        val tables = extractTables(read.tablePages)
        val yaml = extractYaml(null) // Story 5.9c supplies the sidecar snapshot
        val prose = extractProse(read.prosePages)
        val svg = extractSvg(null) // Story 5.9d supplies the attached sheets' SVG text
        return CollectedSources(
            boundPages = read.boundPages,
            tableObservations = tables.observations,
            tableNeedsMapping = tables.needsMapping,
            yamlObservations = yaml.observations,
            proseObservations = prose.observations,
            svgObservations = svg.observations,
            svgUnboundLabels = svg.unbound,
        )
    }

    /** Table stage (Story 5.2). */
    internal fun extractTables(pages: List<TablePageText>): TableExtractionResult =
        pages.fold(TableExtractionResult()) { acc, p -> acc + tableExtractor.extract(p.markdown, p.locator, p.origin) }

    /** YAML stage (Story 5.3); [snapshot] is the sidecar's YAML text, never a path (OD-H2). */
    internal fun extractYaml(snapshot: String?): YamlExtractionResult =
        if (snapshot == null) YamlExtractionResult() else yamlExtractor.extract(snapshot, "yaml-snapshot")

    /** Prose and count stage (Story 5.3b): lengths and counts stated in free text. */
    fun extractProse(pages: List<TablePageText>): ProseExtractionResult =
        pages.fold(ProseExtractionResult()) { acc, p ->
            acc + proseExtractor.extract(p.markdown, p.locator, p.origin) + countExtractor.extract(p.markdown, p.locator, p.origin)
        }

    /** SVG label stage (Story 4.3); [svg] is one sheet's text and [locator] stays device-local. */
    internal fun extractSvg(svg: String?, locator: String = "svg-sheet"): LabelBindingResult =
        if (svg == null) LabelBindingResult() else labelBinder.bind(readSvg(svg), locator)
}
