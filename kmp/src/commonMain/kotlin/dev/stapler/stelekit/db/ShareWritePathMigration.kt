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
import dev.stapler.stelekit.merge.AssetCopier
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Moves journals, pages and assets that share/capture writers wrote to `<repoRoot>/journals|pages|assets`
 * into `<repoRoot>/<wikiSubdir>/…`, where the graph (and git sync's staging) actually lives.
 *
 * Those writers used the registry `path` (the repo root) instead of the effective notes path, so on a
 * graph with `wikiSubdir = "logseq"` a shared journal landed outside the only folder git sync stages and
 * never reached the remote. Page rows kept pointing at the stray file, so later editor saves went there too.
 *
 * Driven by the DB's `pages.file_path`, never by scanning the repo root, so a file SteleKit did not write
 * is never touched. Runs once per graph; a run that leaves a conflict or failure is retried on the next open.
 */
class ShareWritePathMigration(
    private val writeActor: DatabaseWriteActor,
    private val fileSystem: FileSystem,
) {
    private val logger = Logger("ShareWritePathMigration")

    suspend fun runIfNeeded(db: SteleDatabase, repoRoot: String, notesPath: String) {
        // Nothing to repair, and NOT marked done: the wiki subdir may simply not be detected yet.
        if (!ShareWritePathRepair.isSubdirLayout(repoRoot, notesPath)) return

        val done = db.steleDatabaseQueries.selectMetadata(METADATA_KEY)
            .asFlow().mapToOneOrNull(PlatformDispatcher.DB).first()
        if (done == "done") return

        val pages = db.steleDatabaseQueries.selectPageUuidAndFilePath()
            .asFlow().mapToList(PlatformDispatcher.DB).first()
            .mapNotNull { row -> row.file_path?.let { ShareWritePathRepair.PagePath(row.uuid, it) } }

        val outcome = withContext(PlatformDispatcher.IO) {
            ShareWritePathRepair(fileSystem).repair(repoRoot, notesPath, pages)
        }
        logger.info(
            "Share write-path repair: moved=${outcome.moved} repointed=${outcome.repoints.size} " +
                "assetsMoved=${outcome.assetsMoved} conflicts=${outcome.conflicts.size} failures=${outcome.failures.size}",
        )
        outcome.conflicts.forEach { logger.warn("Share write-path repair: both copies exist and differ, left in place: $it") }
        outcome.failures.forEach { logger.warn("Share write-path repair: could not move $it") }

        writeBack(db, outcome)
    }

    @OptIn(DirectSqlWrite::class)
    private suspend fun writeBack(db: SteleDatabase, outcome: ShareWritePathRepair.Outcome) {
        val restricted = RestrictedDatabaseQueries(db.steleDatabaseQueries)
        writeActor.execute {
            try {
                restricted.transaction {
                    for ((uuid, newPath) in outcome.repoints) restricted.updatePageFilePathByUuid(newPath, uuid)
                }
                if (outcome.clean) restricted.upsertMetadata(METADATA_KEY, "done")
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
    }
}

/** The file-system half of [ShareWritePathMigration], free of the database so it can be tested directly. */
class ShareWritePathRepair(private val fs: FileSystem) {

    data class PagePath(val uuid: String, val filePath: String)

    class Outcome(
        /** page uuid -> new `file_path`, for every page whose file is now (or already was) under the notes path. */
        val repoints: Map<String, String>,
        val moved: Int,
        val assetsMoved: Int,
        /** Both copies exist with different bytes; left untouched for the user to resolve. */
        val conflicts: List<String>,
        val failures: List<String>,
    ) {
        val clean: Boolean get() = conflicts.isEmpty() && failures.isEmpty()
    }

    private enum class Settled { MOVED, ALREADY_THERE, DEDUPED, NEITHER, CONFLICT, FAILED }

    fun repair(repoRoot: String, notesPath: String, pages: List<PagePath>): Outcome {
        val root = repoRoot.trimEnd('/')
        val notes = notesPath.trimEnd('/')
        val repoints = mutableMapOf<String, String>()
        val conflicts = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var moved = 0
        var assetsMoved = 0
        val assetRefs = LinkedHashSet<String>()
        val assetScanner = AssetCopier(fs)

        if (!isSubdirLayout(root, notes)) return Outcome(repoints, 0, 0, conflicts, failures)

        for (page in pages) {
            val rel = strayRelativePath(root, notes, page.filePath) ?: continue
            val newPath = "$notes/$rel"
            when (settle(page.filePath, newPath)) {
                Settled.MOVED -> { moved++; repoints[page.uuid] = newPath }
                Settled.ALREADY_THERE, Settled.DEDUPED -> repoints[page.uuid] = newPath
                Settled.CONFLICT -> conflicts += page.filePath
                Settled.FAILED -> failures += page.filePath
                Settled.NEITHER -> continue
            }
            if (page.uuid in repoints) fs.readFile(newPath)?.let { assetRefs += assetScanner.assetRefs(it) }
        }

        for (asset in assetRefs) {
            if (asset.startsWith("/") || asset.split('/').any { it == ".." }) continue
            val old = "$root/assets/$asset"
            when (settle(old, "$notes/assets/$asset")) {
                Settled.MOVED -> assetsMoved++
                Settled.CONFLICT -> conflicts += old
                Settled.FAILED -> failures += old
                Settled.ALREADY_THERE, Settled.DEDUPED, Settled.NEITHER -> Unit
            }
        }
        return Outcome(repoints, moved, assetsMoved, conflicts, failures)
    }

    /** Copy, read back, then delete: only primitives every platform's [FileSystem] implements, and never a lost file. */
    private fun settle(old: String, new: String): Settled {
        val oldBytes = fs.readFileBytes(old)
        val newBytes = fs.readFileBytes(new)
        return when {
            oldBytes == null && newBytes == null -> Settled.NEITHER
            oldBytes == null -> Settled.ALREADY_THERE
            newBytes != null -> when {
                !oldBytes.contentEquals(newBytes) -> Settled.CONFLICT
                fs.deleteFile(old) -> Settled.DEDUPED
                else -> Settled.FAILED
            }
            else -> {
                fs.createDirectory(new.substringBeforeLast('/'))
                if (!fs.writeFileBytes(new, oldBytes) || fs.readFileBytes(new)?.contentEquals(oldBytes) != true) {
                    fs.deleteFile(new)
                    Settled.FAILED
                } else if (fs.deleteFile(old)) {
                    Settled.MOVED
                } else {
                    Settled.FAILED
                }
            }
        }
    }

    companion object {
        fun isSubdirLayout(repoRoot: String, notesPath: String): Boolean {
            val root = repoRoot.trimEnd('/')
            return root.isNotEmpty() && notesPath.trimEnd('/').startsWith("$root/")
        }

        /** `journals/…` or `pages/…` relative to [root] when [filePath] sits directly under it, else null. */
        private fun strayRelativePath(root: String, notes: String, filePath: String): String? {
            if (!filePath.startsWith("$root/") || filePath.startsWith("$notes/")) return null
            val rel = filePath.removePrefix("$root/")
            return rel.takeIf { it.substringBefore('/') in STRAY_FOLDERS && it.contains('/') && ".." !in it.split('/') }
        }

        private val STRAY_FOLDERS = setOf("journals", "pages")
    }
}
