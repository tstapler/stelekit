// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.RefTable

/** A wiki page a project is bound to; [filePath] is where the loader reads it from. */
data class BoundPageRef(val pageName: String, val filePath: String)

/** What one collection pass found. Later stories add fields; today the shell finds nothing. */
data class CollectedSources(
    val boundPages: List<BoundPageRef> = emptyList(),
    val yamlObservations: List<Observation> = emptyList(),
)

/**
 * Collector shell: every later source story (tables, yaml, svg, geometry) adds one stage call here.
 * Until then it returns an empty [CollectedSources], so the job runs with no data flowing.
 */
open class ProjectSourceCollector(
    registry: DimensionRegistry = DimensionRegistry(dimensions = emptyList()),
    refTable: RefTable = RefTable(),
) {
    private val yamlExtractor = YamlMeasurementExtractor(registry, refTable)

    open suspend fun collect(): CollectedSources {
        val yaml = extractYaml(null) // Story 5.9c supplies the sidecar snapshot
        return CollectedSources(yamlObservations = yaml.observations)
    }

    /** YAML stage (Story 5.3); [snapshot] is the sidecar's YAML text, never a path (OD-H2). */
    internal fun extractYaml(snapshot: String?): YamlExtractionResult =
        if (snapshot == null) YamlExtractionResult() else yamlExtractor.extract(snapshot, "yaml-snapshot")
}
