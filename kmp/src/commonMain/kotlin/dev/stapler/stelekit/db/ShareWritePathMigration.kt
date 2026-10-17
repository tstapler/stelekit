// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Moves journals, pages and assets that share/capture writers put in `<repoRoot>/journals|pages|assets`
 * into `<repoRoot>/<wikiSubdir>/…` (the only folder git sync stages) and repoints their page rows.
 *
 * Driven by `pages.file_path`, so it never sweeps the repo root: only files a page row points at move.
 * When a page exists in both places with different content (say a sync pulled the same day's journal), the two
 * are three-way merged block by block instead of one being left behind; both originals are backed up first.
 * Files no page row points at are not touched here; [dev.stapler.stelekit.db.misplaced.MisplacedFilesService] shows
 * them to the user instead. Runs before the file watcher attaches, so the moves raise no external-change events.
 *
 * Retry state lives in one metadata value: a first line (`done` once every page is settled, else `open`) followed
 * by the asset paths still unresolved. Pages and assets retry independently; the value is exactly `done` when
 * nothing remains.
 */
class ShareWritePathMigration(
    private val writeActor: DatabaseWriteActor,
    private val fileSystem: FileSystem,
) {
    private val logger = Logger("ShareWritePathMigration")

    suspend fun runIfNeeded(db: SteleDatabase, repoRoot: RepoRoot, notesPath: EffectiveNotesPath) {
        // Nothing to repair, and NOT marked done: the wiki subdir may simply not be detected yet.
        if (!ShareWritePathRepair.isSubdirLayout(repoRoot, notesPath)) return

        val state = db.steleDatabaseQueries.selectMetadata(METADATA_KEY)
            .asFlow().mapToOneOrNull(PlatformDispatcher.DB).first()
        if (state == STATE_DONE) return
        val stateLines = state?.lines().orEmpty()
        val pendingAssets = stateLines.drop(1).filter { it.isNotBlank() }.toSet()

        // Pages are already settled when only assets are pending, so skip the full-table read.
        val pages = if (stateLines.firstOrNull() == STATE_DONE) emptyList() else db.steleDatabaseQueries.selectPageUuidAndFilePath()
            .asFlow().mapToList(PlatformDispatcher.DB).first()
            .mapNotNull { row -> row.file_path?.let { ShareWritePathRepair.PagePath(row.uuid, it) } }

        val outcome = try {
            withContext(PlatformDispatcher.IO) {
                ShareWritePathRepair(fileSystem).repair(repoRoot, notesPath, pages, pendingAssets)
            }
        } catch (e: UnsupportedOperationException) {
            logger.info("Share write-path repair skipped: this file system has no byte access")
            return
        }
        logger.info(
            "Share write-path repair: moved=${outcome.moved} repointed=${outcome.repoints.size} " +
                "merged=${outcome.merged} assetsMoved=${outcome.assetsMoved} conflicts=${outcome.conflicts.size} " +
                "failures=${outcome.failures.size} pendingAssets=${outcome.pendingAssets.size}",
        )
        outcome.conflicts.forEach { logger.warn("Share write-path repair: both copies exist and differ, left in place: $it") }
        outcome.failures.forEach { logger.warn("Share write-path repair: could not move $it") }
        outcome.warnings.forEach { logger.warn("Share write-path repair: $it") }

        writeBack(db, outcome)
    }

    @OptIn(DirectSqlWrite::class)
    private suspend fun writeBack(db: SteleDatabase, outcome: ShareWritePathRepair.Outcome) {
        val restricted = RestrictedDatabaseQueries(db.steleDatabaseQueries)
        val newState = outcome.nextState()
        writeActor.execute {
            try {
                restricted.transaction {
                    for ((uuid, newPath) in outcome.repoints) restricted.updatePageFilePathByUuid(newPath, uuid)
                    restricted.upsertMetadata(METADATA_KEY, newState)
                }
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Share write-path repair: DB update failed", e)
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    companion object {
        const val METADATA_KEY = "share_write_path_repair_v1"
        internal const val STATE_DONE = "done"
        internal const val STATE_OPEN = "open"
    }
}
