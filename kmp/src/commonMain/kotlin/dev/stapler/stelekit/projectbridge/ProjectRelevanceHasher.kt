// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.util.ContentHasher

/**
 * Decides by content whether a sync brought anything the digest depends on (Story 5.7b). The hash covers
 * what the source stages read from the bound pages, the sidecar snapshot and the attached sheets, and never
 * the digest page itself, so the job's own writes cannot make the next run look relevant.
 */
object ProjectRelevanceHasher {
    /** Order-independent: the same inputs hash the same however the collector listed them. */
    fun hash(collected: CollectedSources): String {
        val lines = collected.boundPages.map { "page|${it.pageName}" } +
            collected.observations.map { "obs|$it" } +
            collected.tableNeedsMapping.map { "map|$it" } +
            collected.svgUnboundLabels.map { "unbound|$it" } +
            listOf("yaml|${collected.yamlSnapshotHash}")
        return ContentHasher.sha256(lines.sorted().joinToString("\n"))
    }

    /** A blank [stored] hash (no earlier digest) is never "unchanged". */
    fun isUnchanged(current: String, stored: String): Boolean = stored.isNotEmpty() && current == stored
}
