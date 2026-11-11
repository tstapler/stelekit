// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionRegistry
import kotlinx.serialization.json.JsonObject

/** What a project's synced settings say about fusion; a damaged setting is "needs attention", never an exception. */
sealed interface FusionSettingsState {
    /** No registry recorded yet: nothing to fuse and nothing wrong. */
    data object Absent : FusionSettingsState

    data class Valid(val registry: DimensionRegistry, val spec: FusionSpec) : FusionSettingsState

    /** [reason] names the damaged part only; it never repeats stored text. */
    data class NeedsAttention(val reason: String) : FusionSettingsState
}

object FusionSettingsLoader {
    const val BAD_REGISTRY = "The saved dimension registry could not be read"
    const val BAD_SPEC = "The saved constraints could not be read"
    const val SPEC_WITHOUT_REGISTRY = "Constraints are saved but the dimension registry is missing"

    fun load(settings: JsonObject): FusionSettingsState {
        val rawRegistry = settings[ProjectFusionSettings.REGISTRY_KEY]
        val rawSpec = settings[ProjectFusionSettings.FUSION_KEY]
        if (rawRegistry == null) {
            return if (rawSpec == null) FusionSettingsState.Absent else FusionSettingsState.NeedsAttention(SPEC_WITHOUT_REGISTRY)
        }
        val registry = DimensionRegistry.fromJson(rawRegistry.toString()).getOrNull()
            ?: return FusionSettingsState.NeedsAttention(BAD_REGISTRY)
        val spec = if (rawSpec == null) FusionSpec() else FusionSpecJson.decode(rawSpec)
            ?: return FusionSettingsState.NeedsAttention(BAD_SPEC)
        if (ConstraintEditing.validate(spec) != null) return FusionSettingsState.NeedsAttention(BAD_SPEC)
        return FusionSettingsState.Valid(registry, spec)
    }
}
