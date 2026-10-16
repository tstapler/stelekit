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
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Moves journals, pages and assets that share/capture writers put in `<repoRoot>/journals|pages|assets`
 * into `<repoRoot>/<wikiSubdir>/…` (the only folder git sync stages) and repoints their page rows.
 *
 * Driven by `pages.file_path`, so it never sweeps the repo root: only files a page row points at move.
 * Runs before the file watcher attaches, so the moves raise no external-change events.
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

    suspend fun runIfNeeded(db: SteleDatabase, repoRoot: String, notesPath: String) {
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
                "assetsMoved=${outcome.assetsMoved} conflicts=${outcome.conflicts.size} " +
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
        /** Assets (relative to `assets/`) still to settle; retried without re-reading page rows. */
        val pendingAssets: Set<String>,
        val warnings: List<String> = emptyList(),
    ) {
        val clean: Boolean get() = conflicts.isEmpty() && failures.isEmpty() && pendingAssets.isEmpty()

        /** The metadata value to persist: page status on the first line, then each asset still to settle. */
        fun nextState(): String {
            val pagesSettled = conflicts.isEmpty() && failures.isEmpty()
            val head = if (pagesSettled) ShareWritePathMigration.STATE_DONE else ShareWritePathMigration.STATE_OPEN
            return (listOf(head) + pendingAssets.sorted()).joinToString("\n")
        }
    }

    private enum class Settled { MOVED, ALREADY_THERE, DEDUPED, NEITHER, CONFLICT, FAILED }

    private class Progress {
        val repoints = mutableMapOf<String, String>()
        val conflicts = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val pendingAssets = linkedSetOf<String>()
        var moved = 0
        var assetsMoved = 0

        fun toOutcome() = Outcome(repoints, moved, assetsMoved, conflicts, failures, pendingAssets, warnings)
    }

    /** [retryAssets] are asset paths left over from an earlier run; they are settled without any page rows. */
    fun repair(repoRoot: String, notesPath: String, pages: List<PagePath>, retryAssets: Set<String> = emptySet()): Outcome {
        val root = repoRoot.trimEnd('/')
        val notes = notesPath.trimEnd('/')
        val progress = Progress()
        if (!isSubdirLayout(root, notes)) return progress.toOutcome()

        val assetRefs = LinkedHashSet(retryAssets)
        settlePages(root, notes, pages, progress, assetRefs)
        settleAssets(root, notes, assetRefs, progress)
        return progress.toOutcome()
    }

    private fun settlePages(root: String, notes: String, pages: List<PagePath>, progress: Progress, assetRefs: MutableSet<String>) {
        for (page in pages) {
            val rel = strayRelativePath(root, notes, page.filePath) ?: continue
            val newPath = "$notes/$rel"
            when (settle(page.filePath, newPath)) {
                Settled.MOVED -> { progress.moved++; progress.repoints[page.uuid] = newPath }
                Settled.ALREADY_THERE, Settled.DEDUPED -> progress.repoints[page.uuid] = newPath
                Settled.CONFLICT -> progress.conflicts += page.filePath
                Settled.FAILED -> progress.failures += page.filePath
                Settled.NEITHER -> Unit
            }
            if (page.uuid !in progress.repoints) continue
            // Ciphertext has no readable links, so its assets cannot be found from here.
            if (newPath.endsWith(ENCRYPTED_SUFFIX)) progress.warnings += "assets of encrypted page not scanned: $newPath"
            else fs.readFile(newPath)?.let { assetRefs += AssetLinks.refs(it) }
        }
    }

    private fun settleAssets(root: String, notes: String, assetRefs: Set<String>, progress: Progress) {
        val oldAssets = "$root/assets"
        val newAssets = "$notes/assets"
        for (asset in assetRefs) {
            if (!isSafeAssetRef(asset)) continue
            val old = "$oldAssets/$asset"
            val new = "$newAssets/$asset"
            if (!PageFileResolver.isWithin(oldAssets, old) || !PageFileResolver.isWithin(newAssets, new)) continue
            val result = settle(old, new)
            when (result) {
                Settled.MOVED -> progress.assetsMoved++
                Settled.CONFLICT, Settled.FAILED -> {
                    progress.pendingAssets += asset
                    progress.warnings += "asset ${if (result == Settled.CONFLICT) "differs from" else "could not be moved to"} its notes copy: $old"
                }
                Settled.ALREADY_THERE, Settled.DEDUPED, Settled.NEITHER -> Unit
            }
        }
    }

    /** Copy, read back, then delete: only primitives every platform implements, and never a lost file. */
    private fun settle(old: String, new: String): Settled {
        val oldExists = fs.fileExists(old)
        val newExists = fs.fileExists(new)
        if (!oldExists && !newExists) return Settled.NEITHER
        // An unreadable or oversized file is not an absent one: repointing past it would orphan its content.
        if (listOf(old to oldExists, new to newExists).any { (path, exists) -> exists && isTooLarge(path) }) return Settled.FAILED
        val oldBytes = if (oldExists) fs.readFileBytes(old) ?: return Settled.FAILED else null
        val newBytes = if (newExists) fs.readFileBytes(new) ?: return Settled.FAILED else null
        return when {
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

    private fun isTooLarge(path: String): Boolean = (fs.getFileSize(path) ?: 0L) > MAX_FILE_BYTES

    companion object {
        /** Files are held in memory up to three times while copying; skip anything bigger (an Android OOM kills the app). */
        private const val MAX_FILE_BYTES = 32L * 1024 * 1024
        private const val ENCRYPTED_SUFFIX = ".stek"
        private val STRAY_FOLDERS = setOf("journals", "pages")

        fun isSubdirLayout(repoRoot: String, notesPath: String): Boolean {
            val root = repoRoot.trimEnd('/')
            return root.isNotEmpty() && notesPath.trimEnd('/').startsWith("$root/")
        }

        /** `journals/…` or `pages/…` relative to [root] when [filePath] sits directly under it, else null. */
        private fun strayRelativePath(root: String, notes: String, filePath: String): String? {
            if (!filePath.startsWith("$root/") || filePath.startsWith("$notes/")) return null
            val rel = filePath.removePrefix("$root/")
            val folder = rel.substringBefore('/')
            if (folder !in STRAY_FOLDERS || !rel.contains('/') || !isSafeRelative(rel)) return null
            return rel.takeIf { PageFileResolver.isWithin("$root/$folder", filePath) }
        }

        /** Asset refs come from page markdown, so they are untrusted: no escapes, absolute paths, drives or backslashes. */
        private fun isSafeAssetRef(ref: String): Boolean = isSafeRelative(ref) && !ref.startsWith("/")

        private val UNSAFE_CHARS = setOf('\\', ':', '\u0000')

        private fun isSafeRelative(path: String): Boolean =
            path.isNotEmpty() && path.none { it in UNSAFE_CHARS } && path.split('/').none { it == ".." || it == "." }
    }
}
