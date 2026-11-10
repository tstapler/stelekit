// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fusion.FusionInput
import dev.stapler.stelekit.fusion.FusionResult
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.fuseObservations

internal object ClosetProjectOutputs {
    const val SLUG = "proj-closet-1"
    val zoneCheck = SumCheck("Zones sum to opening", constraintId = 1, total = "niche.width", parts = ClosetFusion.zones)

    fun input(observations: List<Observation> = ClosetFusion.observations()): DigestInput {
        val result: FusionResult =
            fuseObservations(FusionInput(observations, ClosetFusion.constraints()), ClosetFusion.registry).fold({ error("$it") }, { it })
        return DigestInput(SLUG, result, ClosetFusion.registry, listOf(zoneCheck))
    }

    fun outputs(observations: List<Observation> = ClosetFusion.observations()): ProjectOutputs =
        PostSyncProjectJob.projectOutputs(input(observations), observations)
}
