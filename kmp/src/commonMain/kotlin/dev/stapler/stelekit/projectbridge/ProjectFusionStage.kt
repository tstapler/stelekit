// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.Constraint
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.DimensionStatus
import dev.stapler.stelekit.fusion.FusionInput
import dev.stapler.stelekit.fusion.fuseObservations
import dev.stapler.stelekit.logging.Logger

/** A project's structural constraints, the named checks the digest reports, and its left-to-right zone keys. */
data class FusionSpec(
    val constraints: List<Constraint> = emptyList(),
    val checks: List<SumCheck> = emptyList(),
    val zoneOrder: List<String> = emptyList(),
)

/**
 * Fuse stage of the post-sync job: [CollectedSources] through the engine into the digest input and
 * the LLM payload, both projected from the one result. Returns null on an engine error so the job
 * writes nothing rather than a partial digest; the log line carries counts only.
 */
class ProjectFusionStage(
    private val slug: String,
    private val registry: DimensionRegistry,
    private val spec: FusionSpec,
) {
    private val logger = Logger("ProjectFusionStage")

    fun digestInput(collected: CollectedSources): DigestInput? {
        val input = FusionInput(collected.observations, spec.constraints, spec.zoneOrder, collected.svgZoneLabels)
        val result = fuseObservations(input, registry).fold(
            { logger.warn("fusion failed: ${it::class.simpleName}"); return null },
            { it },
        )
        val conflicts = result.dimensions.values.count { it is DimensionStatus.Conflict }
        val underConstrained = result.dimensions.values.count { it == DimensionStatus.UnderConstrained }
        logger.info("fusion dims=${result.dimensions.size} conflicts=$conflicts underConstrained=$underConstrained")
        return DigestInput(slug, result, registry, spec.checks)
    }

    fun outputs(collected: CollectedSources): ProjectOutputs? =
        digestInput(collected)?.let { PostSyncProjectJob.projectOutputs(it, collected.observations) }
}
