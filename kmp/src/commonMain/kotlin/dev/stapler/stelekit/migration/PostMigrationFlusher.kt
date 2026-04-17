// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.flow.first

/**
 * After all migrations for a graph have run, flushes DB-applied changes back to Markdown
 * files on disk by calling [GraphWriter.savePage] for each touched page UUID.
 *
 * Pages are deduplicated by UUID to avoid redundant disk writes when multiple migrations
 * touch the same page.
 */
class PostMigrationFlusher(private val graphWriter: GraphWriter) {

    /**
     * Saves each page in [touchedPageUuids] back to disk.
     *
     * @param repoSet the repository set containing the up-to-date page and block state
     * @param touchedPageUuids set of page UUIDs that were mutated by any migration
     * @param graphPath the root directory of the Logseq graph on disk
     */
    suspend fun flush(repoSet: RepositorySet, touchedPageUuids: Set<String>, graphPath: String) {
        // Deduplicate is automatic since touchedPageUuids is a Set.
        for (pageUuid in touchedPageUuids) {
            val page = repoSet.pageRepository.getPageByUuid(pageUuid)
                .first().getOrNull() ?: continue
            val blocks = repoSet.blockRepository.getBlocksForPage(pageUuid)
                .first().getOrDefault(emptyList())
            graphWriter.savePage(page, blocks, graphPath)
        }
    }
}
