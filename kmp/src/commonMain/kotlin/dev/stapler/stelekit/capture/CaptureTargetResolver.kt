// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.model.GraphId

/**
 * Pure fallback chain: last-used (if remembered and still registered) -> default (if still
 * registered) -> the active graph. A deleted graph falls through to the next step; the caller
 * shows the result rather than silently redirecting.
 */
object CaptureTargetResolver {

    fun resolve(
        defaultGraphId: GraphId?,
        lastGraphId: GraphId?,
        rememberLast: Boolean,
        availableGraphIds: Set<GraphId>,
    ): CaptureTarget {
        val candidates = listOfNotNull(lastGraphId.takeIf { rememberLast }, defaultGraphId)
        val pick = candidates.firstOrNull { it in availableGraphIds }
        return if (pick != null) CaptureTarget.NamedGraph(pick) else CaptureTarget.ActiveGraph
    }

    fun resolve(settings: CaptureTargetSettings, availableGraphIds: Set<GraphId>): CaptureTarget =
        resolve(settings.defaultGraphId, settings.lastGraphId, settings.rememberLast, availableGraphIds)
}
