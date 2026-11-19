// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

/** The screen edges the host view controller may defer system gestures on. */
enum class IosEdge { Left, Right }

/**
 * iOS edge deferral (Task 3.1.2h): a rectangle touching an edge of the screen asks the host view controller to defer
 * that edge's system gesture (`preferredScreenEdgesDeferringSystemGestures`). The controller itself is supplied by the
 * app's iOS entry as [setDeferredEdges]; this class only decides which edges, so it has no UIKit dependency.
 * COMPILE-ONLY in v1 and device behaviour UNVERIFIED (Task 0.1.4d), so the edge-pan default stays Off on iOS.
 */
class IosEdgeGestureExclusion(
    private val screenWidthPx: () -> Int,
    private val setDeferredEdges: (Set<IosEdge>) -> Unit,
) : EdgeGestureExclusion {
    override val canRequest: Boolean get() = true

    override fun request(rects: List<EdgeExclusionRect>) {
        val width = screenWidthPx()
        val edges = buildSet {
            if (rects.any { it.left <= 0 }) add(IosEdge.Left)
            if (rects.any { it.right >= width }) add(IosEdge.Right)
        }
        setDeferredEdges(edges)
    }

    override fun release() = setDeferredEdges(emptySet())
}
