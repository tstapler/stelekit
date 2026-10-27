// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

/**
 * Deletes every block, children before parents. Letting ON DELETE CASCADE walk parent->child (or
 * page->block) is far slower with foreign_keys=ON and the FTS delete trigger; see GraphWipeScaleReproTest.
 * Call inside a transaction.
 */
internal suspend fun SteleDatabaseQueries.deleteAllBlocksLeafFirst() {
    while (deleteLeafBlocks() > 0) { /* one tree level per pass */ }
    deleteAllBlocks() // anything a pass cannot reach (e.g. a parent cycle)
}
