// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.SourceOrigin
import dev.stapler.stelekit.fusion.SvgLabel
import dev.stapler.stelekit.sketchimport.GeometryExtractionResult
import dev.stapler.stelekit.sketchimport.GeometryExtractor
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
    val geometryObservations: List<Observation> = emptyList(),
    val proseObservations: List<Observation> = emptyList(),
    val svgObservations: List<Observation> = emptyList(),
    /** Drawing labels the binder could not bind; the tagging UI offers them for manual binding. */
    val svgUnboundLabels: List<BoundLabel> = emptyList(),
    /** Zone-width labels with their sheet x, for the engine's mirrored-view value matching. */
    val svgZoneLabels: List<SvgLabel> = emptyList(),
) {
    /** Every reading from every source stage, ready for [dev.stapler.stelekit.fusion.FusionInput]. */
    val observations: List<Observation>
        get() = yamlObservations + tableObservations + geometryObservations + proseObservations + svgObservations
}

/** One attached drawing: [sheet] is its role (`elevation`), [locator] stays device-local. */
data class SheetSvg(val sheet: String, val locator: String, val svg: String)

/** The text every stage reads, as handed over by a [ProjectSourceReader]; no paths or titles inside. */
data class ProjectSourceTexts(
    val pages: List<TablePageText> = emptyList(),
    val yamlSnapshot: String? = null,
    val sheets: List<SheetSvg> = emptyList(),
    val boundPages: List<BoundPageRef> = emptyList(),
)

/** Where the collector gets its text: bound wiki pages, the sidecar's YAML snapshot and attached sheets (Stories 5.9b-d). */
fun interface ProjectSourceReader {
    suspend fun read(): ProjectSourceTexts
}

/** Markdown of one bound table page; [locator] stays device-local in the [RefTable]. */
data class TablePageText(val locator: String, val markdown: String, val origin: SourceOrigin = SourceOrigin())

/**
 * Runs every source stage over the text a [ProjectSourceReader] supplies: tables and prose over bound
 * pages, the yaml snapshot, and label binding plus geometry over each attached sheet. The default
 * reader supplies nothing, so an unwired collector yields an empty [CollectedSources].
 */
open class ProjectSourceCollector(
    registry: DimensionRegistry = DimensionRegistry(dimensions = emptyList()),
    refTable: RefTable = RefTable(),
    private val reader: ProjectSourceReader = ProjectSourceReader { ProjectSourceTexts() },
) {
    private val tableExtractor = TableMeasurementExtractor(registry, refTable)
    private val yamlExtractor = YamlMeasurementExtractor(registry, refTable)
    private val geometryExtractor = GeometryExtractor(registry, refTable)
    private val proseExtractor = ProseMeasurementExtractor(registry, refTable)
    private val countExtractor = CountExtractor(registry, refTable)
    private val labelBinder = LabelBinder(registry, refTable)

    open suspend fun collect(): CollectedSources {
        val texts = reader.read()
        val tables = extractTables(texts.pages)
        val yaml = extractYaml(texts.yamlSnapshot)
        val prose = extractProse(texts.pages)
        val geometry = texts.sheets.fold(GeometryExtractionResult()) { acc, s -> acc + extractGeometry(s.svg, s.sheet, s.locator) }
        val svg = texts.sheets.map { extractSvg(it.svg, it.locator) }
        return CollectedSources(
            boundPages = texts.boundPages,
            tableObservations = tables.observations,
            tableNeedsMapping = tables.needsMapping,
            yamlObservations = yaml.observations,
            geometryObservations = geometry.observations,
            proseObservations = prose.observations,
            svgObservations = svg.flatMap { it.observations },
            svgUnboundLabels = svg.flatMap { it.unbound },
            svgZoneLabels = texts.sheets.zip(svg).filter { (sheet, _) -> sheet.sheet == ELEVATION }.flatMap { (_, bound) -> bound.zoneLabelOrder },
        )
    }

    /** Table stage (Story 5.2). */
    internal fun extractTables(pages: List<TablePageText>): TableExtractionResult =
        pages.fold(TableExtractionResult()) { acc, p -> acc + tableExtractor.extract(p.markdown, p.locator, p.origin) }

    /** YAML stage (Story 5.3); [snapshot] is the sidecar's YAML text, never a path (OD-H2). */
    internal fun extractYaml(snapshot: String?): YamlExtractionResult =
        if (snapshot == null) YamlExtractionResult() else yamlExtractor.extract(snapshot, "yaml-snapshot")

    /** Geometry stage (Story 4.8); [svg] is one sheet's text, [sheet] its role, [locator] stays device-local. */
    internal fun extractGeometry(svg: String?, sheet: String = "elevation", locator: String = "svg-sheet"): GeometryExtractionResult =
        if (svg == null) GeometryExtractionResult() else geometryExtractor.extract(readSvg(svg), locator, sheet)

    /** Prose and count stage (Story 5.3b): lengths and counts stated in free text. */
    fun extractProse(pages: List<TablePageText>): ProseExtractionResult =
        pages.fold(ProseExtractionResult()) { acc, p ->
            acc + proseExtractor.extract(p.markdown, p.locator, p.origin) + countExtractor.extract(p.markdown, p.locator, p.origin)
        }

    /** SVG label stage (Story 4.3); [svg] is one sheet's text and [locator] stays device-local. */
    internal fun extractSvg(svg: String?, locator: String = "svg-sheet"): LabelBindingResult =
        if (svg == null) LabelBindingResult() else labelBinder.bind(readSvg(svg), locator)

    private companion object {
        /** Zone-width labels are read from the elevation only; other sheets carry no zone row. */
        const val ELEVATION = "elevation"
    }
}
