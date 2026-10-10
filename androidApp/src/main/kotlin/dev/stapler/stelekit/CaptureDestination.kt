// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit

import dev.stapler.stelekit.capture.CaptureTarget
import dev.stapler.stelekit.capture.CaptureTargetResolver
import dev.stapler.stelekit.capture.CaptureTargetSettings
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry

/** A graph as the share overlay names it ("Work graph"). */
data class GraphChoice(val id: GraphId, val name: String) {
    val label: String get() = if (name.endsWith(" graph", ignoreCase = true)) name else "$name graph"
}

/** What the destination row shows (UX S10). */
sealed interface CaptureDestination {
    /** Not running inside the real app; keeps the plain "Today's Journal" label. */
    data object Legacy : CaptureDestination

    /** Target not resolved or probed yet; capped at [CaptureDestinations.CHECKING_CAP_MS]. */
    data object Checking : CaptureDestination

    data object NoGraphs : CaptureDestination

    data class Ready(val graph: GraphChoice, val isActive: Boolean, val note: String? = null) : CaptureDestination

    data class Unavailable(
        val graphId: GraphId?,
        val graphName: String,
        val reason: String,
        val fallback: GraphChoice?,
    ) : CaptureDestination
}

/** Everything [CaptureDestinations.resolve] reads. [activeOpen]: the active graph's repositories are up. */
data class DestinationInputs(
    val registry: GraphRegistry,
    val settings: CaptureTargetSettings,
    val explicitChoice: GraphId? = null,
    val overrideId: GraphId? = null,
    val activeOpen: Boolean = false,
    val checkingExpired: Boolean = false,
)

object CaptureDestinations {
    const val CHECKING_CAP_MS = 2_000L

    /**
     * Pure destination choice: explicit pick, else Direct Share override, else the resolver chain.
     * [unavailableReason] is the probe result per non-active graph (null = writable).
     */
    fun resolve(
        inputs: DestinationInputs,
        unavailableReason: (GraphInfo) -> String?,
    ): CaptureDestination {
        val registry = inputs.registry
        val settings = inputs.settings
        val activeOpen = inputs.activeOpen
        if (registry.graphs.isEmpty()) return CaptureDestination.NoGraphs
        val pinned = inputs.explicitChoice ?: inputs.overrideId
        val info = if (pinned != null) {
            registry.graphs.firstOrNull { it.id == pinned }
                ?: return unavailable(registry, null, "That graph", "isn't available: it was removed.", activeOpen, unavailableReason)
        } else {
            pickByResolver(registry, settings)
        }
        val choice = GraphChoice(info.id, info.displayName)
        if (info.id == registry.activeGraphId) {
            return when {
                activeOpen -> CaptureDestination.Ready(choice, isActive = true, note = fallbackNote(registry, settings, pinned))
                inputs.checkingExpired ->
                    unavailable(registry, info, choice.label, "isn't open yet: open SteleKit to finish loading it.", activeOpen, unavailableReason)
                else -> CaptureDestination.Checking
            }
        }
        val reason = unavailableReason(info)
            ?: return CaptureDestination.Ready(choice, isActive = false, note = fallbackNote(registry, settings, pinned))
        return unavailable(registry, info, choice.label, "isn't available: $reason", activeOpen, unavailableReason)
    }

    private fun pickByResolver(registry: GraphRegistry, settings: CaptureTargetSettings): GraphInfo =
        when (val t = CaptureTargetResolver.resolve(settings, registry.graphIds)) {
            is CaptureTarget.NamedGraph -> registry.graphs.first { it.id == t.graphId }
            CaptureTarget.ActiveGraph ->
                registry.graphs.firstOrNull { it.id == registry.activeGraphId } ?: registry.graphs.first()
        }

    private fun fallbackNote(registry: GraphRegistry, settings: CaptureTargetSettings, pinned: GraphId?): String? {
        if (pinned != null) return null
        val first = listOfNotNull(settings.lastGraphId.takeIf { settings.rememberLast }, settings.defaultGraphId).firstOrNull()
        return if (first != null && first !in registry.graphIds) "Your usual graph isn't available. Saving to the open graph." else null
    }

    private fun unavailable(
        registry: GraphRegistry,
        failed: GraphInfo?,
        name: String,
        reasonTail: String,
        activeOpen: Boolean,
        unavailableReason: (GraphInfo) -> String?,
    ): CaptureDestination.Unavailable {
        val fallback = registry.graphs
            .filter { it.id != failed?.id }
            .firstOrNull { (it.id == registry.activeGraphId && activeOpen) || (it.id != registry.activeGraphId && unavailableReason(it) == null) }
            ?.let { GraphChoice(it.id, it.displayName) }
        return CaptureDestination.Unavailable(failed?.id, name, "$name $reasonTail".trim(), fallback)
    }
}
