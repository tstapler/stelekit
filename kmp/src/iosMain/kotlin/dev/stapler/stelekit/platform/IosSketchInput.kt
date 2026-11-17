// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

/**
 * The iOS root's [SketchPlatformInput]: edge deferral through the host view controller. The repository has no iOS app entry
 * yet, so nothing calls this; the entry that builds `StelekitAppPlatformIntegrations` passes the result as `sketchInput`
 * and supplies [setDeferredEdges] from its `preferredScreenEdgesDeferringSystemGestures`. COMPILE-ONLY: not built on the Linux
 * host (BUG-001) and device behaviour UNVERIFIED (Task 0.1.4d), so the edge-pan default stays Off.
 */
fun iosSketchPlatformInput(screenWidthPx: () -> Int, setDeferredEdges: (Set<IosEdge>) -> Unit): SketchPlatformInput =
    SketchPlatformInput(edgeExclusion = IosEdgeGestureExclusion(screenWidthPx, setDeferredEdges))
