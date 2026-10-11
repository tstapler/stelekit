// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger

/**
 * One-shot migration that re-roots every page's stored `file_path` onto the graph's current
 * [GraphInfo.path] whenever [GraphManager.updateGraphPath] has moved a graph to a new location.
 *
 * `updateGraphPath` renames the SQLite DB file and updates the registry's `path`/`id`, but it
 * never rewrote the `file_path` already stored on existing page rows — those kept pointing at
 * the graph's *old* root forever. Anything that builds a path fresh from the graph's *current*
 * registered path (e.g. [SidecarManager], newly created pages) then permanently disagreed with
 * `GraphWriter`'s content writes, which use the DB-stored `file_path` — since [HostDirectorySync]
 * is attached to whichever root the content writes actually use, every path built from the
 * live/current graph path (like every `.stelekit/pages/&lt;slug&gt;.meta.json` sidecar write) silently fails
 * to resolve to a host-relative path and is dropped (see `scheduleHostWriteThrough`'s null-path
 * branch) — a real, confirmed silent-write-loss bug, not just a display glitch.
 *
 * This migration runs once per graph (guarded by a `metadata` record) and rewrites every page's
 * `file_path` to swap in the current graph root, keeping the `journals/`/`pages/` suffix intact —
 * the two directory names every page path is guaranteed to contain.
 */
class FilePathRootMigration(
    private val writeActor: DatabaseWriteActor,
) {
    private val logger = Logger("FilePathRootMigration")

    /**
     * Runs the migration if it hasn't run yet for this graph.
     * Safe to call on every app start — checks the metadata guard first.
     */
    suspend fun runIfNeeded(db: SteleDatabase, graphPath: String) {
        val alreadyDone = db.steleDatabaseQueries.selectMetadata("file_path_root_migration_v1")
            .asFlow().mapToOneOrNull(PlatformDispatcher.DB).first()
        if (alreadyDone == "done") {
            logger.info("File-path-root migration already applied — skipping")
            return
        }

        logger.info("Running file-path-root migration for graph root: $graphPath")
        migrate(db, graphPath)
        logger.info("File-path-root migration complete")
    }

    @OptIn(DirectSqlWrite::class)
    private suspend fun migrate(db: SteleDatabase, graphPath: String) {
        val restricted = RestrictedDatabaseQueries(db.steleDatabaseQueries)

        val allRows = db.steleDatabaseQueries
            .selectPageUuidAndFilePath()
            .asFlow().mapToList(PlatformDispatcher.DB).first()

        val remap = mutableMapOf<String, String>() // uuid -> new file_path
        for (row in allRows) {
            val filePath = row.file_path ?: continue
            val rerooted = rerootFilePath(filePath, graphPath)
            if (rerooted != null && rerooted != filePath) {
                remap[row.uuid] = rerooted
            }
        }

        if (remap.isEmpty()) {
            logger.info("File-path-root migration: no paths needed re-rooting — marking as done")
            writeActor.execute {
                restricted.upsertMetadata("file_path_root_migration_v1", "done")
                Unit.right()
            }
            return
        }

        logger.info("File-path-root migration: re-rooting ${remap.size} page paths...")

        writeActor.execute {
            try {
                restricted.transaction {
                    for ((uuid, newPath) in remap) {
                        restricted.updatePageFilePathByUuid(newPath, uuid)
                    }
                }
                restricted.upsertMetadata("file_path_root_migration_v1", "done")
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("File-path-root migration transaction failed", e)
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    companion object {
        /**
         * Rewrites [filePath]'s root segment to [graphPath], preserving everything from its last
         * `/journals/` or `/pages/` segment onward. Returns null if neither marker is present (an
         * unrecognized layout — left untouched rather than guessed at).
         */
        internal fun rerootFilePath(filePath: String, graphPath: String): String? {
            val journalsIdx = filePath.lastIndexOf("/journals/")
            val pagesIdx = filePath.lastIndexOf("/pages/")
            val idx = maxOf(journalsIdx, pagesIdx)
            if (idx == -1) return null
            return graphPath + filePath.substring(idx)
        }
    }
}
