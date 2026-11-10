// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

/** A wiki page a project is bound to; [filePath] is where the loader reads it from. */
data class BoundPageRef(val pageName: String, val filePath: String)

/** What one collection pass found. Later stories add fields; today the shell finds nothing. */
data class CollectedSources(val boundPages: List<BoundPageRef> = emptyList())

/**
 * Collector shell: every later source story (tables, yaml, svg, geometry) adds one stage call here.
 * Until then it returns an empty [CollectedSources], so the job runs with no data flowing.
 */
open class ProjectSourceCollector {
    open suspend fun collect(): CollectedSources = CollectedSources()
}
