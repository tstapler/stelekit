// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.git.merge.StrayPageMerge
import dev.stapler.stelekit.git.merge.StrayPageMergeResult
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.platform.FileSystem

/** What moving a misplaced page into the notes folder would do, for showing the user before they choose. */
sealed interface ProposedResolution {
    /** Nothing at that location any more. */
    data object Missing : ProposedResolution

    /** The notes folder has no copy, so the page simply moves. */
    data object MoveAsNew : ProposedResolution

    /** The notes folder already has exactly this page, so the stray copy is redundant. */
    data object IdenticalCopy : ProposedResolution

    /** Both copies exist and differ; the three-way merge keeps every block and adds [blocksToAdd] to the notes copy. */
    data class MergeIntoExisting(val blocksToAdd: Int) : ProposedResolution

    /**
     * Both copies exist and differ, and merging them automatically could lose a block. [canAppend] means the user
     * can still choose to append the misplaced copy's blocks to the end of the notes page and tidy them by hand.
     */
    data class CannotMerge(val reason: String, val canAppend: Boolean = false) : ProposedResolution
}

/** The result of moving one misplaced page into the notes folder. */
sealed interface StrayResult {
    enum class How { MOVED, MERGED, DEDUPED }

    data class Done(val how: How, val blocksAdded: Int, val assetsMoved: Int, val assetsLeft: Int) : StrayResult
    data object Missing : StrayResult
    data class Refused(val reason: String) : StrayResult
    data class Failed(val reason: String) : StrayResult
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
        /** Pages that existed in both places and were merged into the notes copy. */
        val merged: Int = 0,
    ) {
        val clean: Boolean get() = conflicts.isEmpty() && failures.isEmpty() && pendingAssets.isEmpty()

        /** The metadata value to persist: page status on the first line, then each asset still to settle. */
        fun nextState(): String {
            val pagesSettled = conflicts.isEmpty() && failures.isEmpty()
            val head = if (pagesSettled) ShareWritePathMigration.STATE_DONE else ShareWritePathMigration.STATE_OPEN
            return (listOf(head) + pendingAssets.sorted()).joinToString("\n")
        }
    }

    private enum class Settled { MOVED, MERGED, ALREADY_THERE, DEDUPED, NEITHER, CONFLICT, FAILED }

    /** Where a page merge backs up both originals, and where it reports why a merge was refused. */
    private class MergeContext(val backupBase: String, val warnings: MutableList<String>, val appendOnRefusal: Boolean = false) {
        var blocksAdded = 0
        var refusal: String? = null
    }

    private class Progress {
        val repoints = mutableMapOf<String, String>()
        val conflicts = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val pendingAssets = linkedSetOf<String>()
        var moved = 0
        var merged = 0
        var assetsMoved = 0

        fun toOutcome() = Outcome(repoints, moved, assetsMoved, conflicts, failures, pendingAssets, warnings, merged)
    }

    /** [retryAssets] are asset paths left over from an earlier run; they are settled without any page rows. */
    fun repair(repoRoot: RepoRoot, notesPath: EffectiveNotesPath, pages: List<PagePath>, retryAssets: Set<String> = emptySet()): Outcome {
        val root = repoRoot.value.trimEnd('/')
        val notes = notesPath.value.trimEnd('/')
        val progress = Progress()
        if (!isSubdir(root, notes)) return progress.toOutcome()

        val assetRefs = LinkedHashSet(retryAssets)
        settlePages(root, notes, pages, progress, assetRefs)
        settleAssets(root, notes, assetRefs, progress)
        return progress.toOutcome()
    }

    private fun settlePages(root: String, notes: String, pages: List<PagePath>, progress: Progress, assetRefs: MutableSet<String>) {
        for (page in pages) {
            val rel = strayRelativePath(root, notes, page.filePath) ?: continue
            val newPath = "$notes/$rel"
            val mergeContext = MergeContext("$root/$BACKUP_DIR/$rel", progress.warnings)
            when (settle(page.filePath, newPath, mergeContext)) {
                Settled.MOVED -> { progress.moved++; progress.repoints[page.uuid] = newPath }
                Settled.MERGED -> { progress.merged++; progress.repoints[page.uuid] = newPath }
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

    /** What [settleOne] would do with `<root>/[relativePath]`, decided without writing anything. */
    fun previewOne(repoRoot: RepoRoot, notesPath: EffectiveNotesPath, relativePath: String): ProposedResolution {
        val root = repoRoot.value.trimEnd('/')
        val notes = notesPath.value.trimEnd('/')
        if (!isSubdir(root, notes) || !isStrayRelativePath(relativePath)) return ProposedResolution.Missing
        val strayBytes = fs.takeIf { it.fileExists("$root/$relativePath") }?.readFileBytes("$root/$relativePath") ?: return ProposedResolution.Missing
        if (!fs.fileExists("$notes/$relativePath")) return ProposedResolution.MoveAsNew
        val notesBytes = fs.readFileBytes("$notes/$relativePath") ?: return ProposedResolution.CannotMerge("the copy in your notes cannot be read")
        if (strayBytes.contentEquals(notesBytes)) return ProposedResolution.IdenticalCopy
        if (!relativePath.endsWith(MARKDOWN_SUFFIX)) return ProposedResolution.CannotMerge("only plain markdown pages can be merged")
        return when (val merge = StrayPageMerge.merge(notesBytes.decodeToString(), strayBytes.decodeToString())) {
            is StrayPageMergeResult.Merged -> ProposedResolution.MergeIntoExisting(merge.addedFromStray)
            is StrayPageMergeResult.Refused -> ProposedResolution.CannotMerge(merge.reason, canAppend = true)
        }
    }

    /**
     * Moves `<root>/[relativePath]` into the notes folder, three-way merging it into the copy already there. Both
     * originals are backed up first; the assets the page links move with it. This is the user-driven entry point,
     * so unlike [repair] it never needs a page row. With [appendOnRefusal], a page the merge refuses is appended
     * to the notes copy instead ([StrayPageMerge.appendAll]), after the same backups.
     */
    fun settleOne(repoRoot: RepoRoot, notesPath: EffectiveNotesPath, relativePath: String, appendOnRefusal: Boolean = false): StrayResult {
        val root = repoRoot.value.trimEnd('/')
        val notes = notesPath.value.trimEnd('/')
        if (!isSubdir(root, notes) || !isStrayRelativePath(relativePath)) return StrayResult.Failed("not a file outside the notes folder")
        val context = MergeContext("$root/$BACKUP_DIR/$relativePath", mutableListOf(), appendOnRefusal)
        val how = when (settle("$root/$relativePath", "$notes/$relativePath", context)) {
            Settled.MOVED -> StrayResult.How.MOVED
            Settled.MERGED -> StrayResult.How.MERGED
            Settled.DEDUPED -> StrayResult.How.DEDUPED
            Settled.ALREADY_THERE, Settled.NEITHER -> return StrayResult.Missing
            Settled.CONFLICT -> return StrayResult.Refused(context.refusal ?: "the two copies differ and cannot be merged")
            Settled.FAILED -> return StrayResult.Failed("a file could not be copied safely; nothing was removed")
        }
        val assets = Progress()
        val assetRefs = LinkedHashSet<String>()
        fs.readFile("$notes/$relativePath")?.let { assetRefs += AssetLinks.refs(it) }
        settleAssets(root, notes, assetRefs, assets)
        return StrayResult.Done(how, context.blocksAdded, assets.assetsMoved, assets.pendingAssets.size)
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
                Settled.MERGED, Settled.ALREADY_THERE, Settled.DEDUPED, Settled.NEITHER -> Unit
            }
        }
    }

    /** Copy, read back, then delete: only primitives every platform implements, and never a lost file. */
    private fun settle(old: String, new: String, merge: MergeContext? = null): Settled {
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
                !oldBytes.contentEquals(newBytes) -> merge?.let { mergeInto(old, new, oldBytes, newBytes, it) } ?: Settled.CONFLICT
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

    /**
     * Folds the stray page [old] into the notes copy [new]. Both originals are backed up and verified before
     * anything is written; if the merged write fails the notes copy is restored, so no outcome loses a block.
     */
    private fun mergeInto(old: String, new: String, oldBytes: ByteArray, newBytes: ByteArray, context: MergeContext): Settled {
        if (!old.endsWith(MARKDOWN_SUFFIX) || !new.endsWith(MARKDOWN_SUFFIX)) return Settled.CONFLICT
        val strayText = oldBytes.decodeToString()
        val notesText = newBytes.decodeToString()
        // Only plain UTF-8 text is merged; anything else keeps the old keep-both-and-report behaviour.
        if (!strayText.encodeToByteArray().contentEquals(oldBytes) || !notesText.encodeToByteArray().contentEquals(newBytes)) return Settled.CONFLICT
        val merged = when (val result = StrayPageMerge.merge(notesText, strayText)) {
            is StrayPageMergeResult.Merged -> result
            is StrayPageMergeResult.Refused -> if (context.appendOnRefusal) {
                StrayPageMerge.appendAll(notesText, strayText)
            } else {
                context.refusal = result.reason
                context.warnings += "not merged, ${result.reason}: $old"
                return Settled.CONFLICT
            }
        }
        if (!backUp("${context.backupBase}.stray", oldBytes) || !backUp("${context.backupBase}.notes", newBytes)) return Settled.FAILED
        if (!fs.writeFile(new, merged.text) || fs.readFile(new) != merged.text) {
            fs.writeFileBytes(new, newBytes)
            return Settled.FAILED
        }
        context.blocksAdded = merged.addedFromStray
        context.warnings += "merged ${merged.addedFromStray} block(s) of $old into $new (originals in ${context.backupBase}.*)"
        return if (fs.deleteFile(old)) Settled.MERGED else Settled.FAILED
    }

    private fun backUp(path: String, bytes: ByteArray): Boolean {
        fs.createDirectory(path.substringBeforeLast('/'))
        return fs.writeFileBytes(path, bytes) && fs.readFileBytes(path)?.contentEquals(bytes) == true
    }

    private fun isTooLarge(path: String): Boolean = (fs.getFileSize(path) ?: 0L) > MAX_FILE_BYTES

    companion object {
        /** Files are held in memory up to three times while copying; skip anything bigger (an Android OOM kills the app). */
        private const val MAX_FILE_BYTES = 32L * 1024 * 1024
        private const val ENCRYPTED_SUFFIX = ".stek"
        private const val MARKDOWN_SUFFIX = ".md"

        /** Outside the notes folder, so git sync never stages the backups. */
        private const val BACKUP_DIR = ".stelekit/share-path-repair-backup"
        private val STRAY_FOLDERS = setOf("journals", "pages")

        fun isSubdirLayout(repoRoot: RepoRoot, notesPath: EffectiveNotesPath): Boolean =
            isSubdir(repoRoot.value.trimEnd('/'), notesPath.value.trimEnd('/'))

        private fun isSubdir(root: String, notes: String): Boolean = root.isNotEmpty() && notes.startsWith("$root/")

        /** `journals/…` or `pages/…`, with no way out of those folders. */
        fun isStrayRelativePath(relativePath: String): Boolean =
            relativePath.substringBefore('/') in STRAY_FOLDERS && relativePath.contains('/') && isSafeRelative(relativePath)

        /** `journals/…` or `pages/…` relative to [root] when [filePath] sits directly under it, else null. */
        private fun strayRelativePath(root: String, notes: String, filePath: String): String? {
            if (!filePath.startsWith("$root/") || filePath.startsWith("$notes/")) return null
            val rel = filePath.removePrefix("$root/")
            if (!isStrayRelativePath(rel)) return null
            return rel.takeIf { PageFileResolver.isWithin("$root/${rel.substringBefore('/')}", filePath) }
        }

        /** Asset refs come from page markdown, so they are untrusted: no escapes, absolute paths, drives or backslashes. */
        private fun isSafeAssetRef(ref: String): Boolean = isSafeRelative(ref) && !ref.startsWith("/")

        private val UNSAFE_CHARS = setOf('\\', ':', '\u0000')

        private fun isSafeRelative(path: String): Boolean =
            path.isNotEmpty() && path.none { it in UNSAFE_CHARS } && path.split('/').none { it == ".." || it == "." }
    }
}
