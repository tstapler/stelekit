package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.merge.DuplicateBlockId
import dev.stapler.stelekit.git.merge.findDuplicateBlockIds
import dev.stapler.stelekit.git.merge.hasConflicts
import dev.stapler.stelekit.git.merge.mergeMarkdownBlocks
import dev.stapler.stelekit.git.merge.toTwoWayConflictMarkerText
import dev.stapler.stelekit.git.model.ConflictHunk
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Repository

/**
 * [markerText] is ready to write back to the working tree; [hunks] is ready for
 * [dev.stapler.stelekit.git.model.ConflictFile.hunks]; [duplicateBlockIds] is ready for
 * [dev.stapler.stelekit.git.model.ConflictFile.duplicateBlockIds].
 */
data class BlockAwareConflictResolution(
    val markerText: String,
    val hunks: List<ConflictHunk>,
    val duplicateBlockIds: List<DuplicateBlockId>,
)

/**
 * Shared by [dev.stapler.stelekit.git.AndroidGitRepository] and
 * [dev.stapler.stelekit.git.JvmGitRepository]'s `merge()`: for a `.md` [gitRelativePath] JGit's
 * own recursive merge just reported as conflicting, re-derives conflict-marker text using the
 * block-aware [mergeMarkdownBlocks] merge instead of JGit's line-level markers — same technique
 * already wired into the web platform's [dev.stapler.stelekit.git.WasmGitWriteService], now
 * applied here too so all three platforms show the same block-granular conflicts instead of
 * Desktop/Android alone showing JGit's line-diff noise (a block moved to a different parent, or
 * two edits to different blocks that happen to sit on adjacent lines, no longer manufacture a
 * conflict — see [dev.stapler.stelekit.git.merge.BlockDiff3]'s doc for why).
 *
 * Reads base/local/remote content directly from the unmerged [DirCacheEntry] stages JGit leaves
 * behind after a CONFLICTING merge (stage 1=base, 2=ours/local, 3=theirs/remote) — no extra
 * fetch beyond what `merge()` already did. This only changes what conflict text the app shows
 * and resolves against; it does not touch JGit's own index/working-tree state — the existing
 * `resolveConflicts()` → `checkoutFile`/`applyResolutions`+`writeFile`+`markResolved` flow
 * (unchanged) is still what actually clears the conflict.
 *
 * Returns null (caller falls back to JGit's own marker content) when: [gitRelativePath] isn't
 * `.md`, the path isn't a real 3-stage text conflict (binary, rename-only, or already resolved
 * in the index), a stage's blob isn't decodable as UTF-8 text, or [mergeMarkdownBlocks] itself
 * fails to parse either side as markdown.
 */
fun tryBlockAwareConflict(
    repo: Repository,
    gitRelativePath: String,
    displayPath: String,
    wikiRoot: String,
): BlockAwareConflictResolution? {
    if (!gitRelativePath.endsWith(".md")) return null
    val (baseText, localText, remoteText) = readConflictStages(repo, gitRelativePath) ?: return null

    val chunks = runCatching { mergeMarkdownBlocks(baseText, localText, remoteText) }.getOrNull() ?: return null
    val markerText = chunks.toTwoWayConflictMarkerText(localLabel = "HEAD", remoteLabel = "origin")
    val hunks = if (chunks.hasConflicts()) {
        ConflictResolver().parseConflictFile(displayPath, markerText, wikiRoot).getOrNull()?.hunks ?: emptyList()
    } else {
        emptyList()
    }
    return BlockAwareConflictResolution(markerText, hunks, chunks.findDuplicateBlockIds())
}

/** The base/local/remote blob content for [path]'s three unmerged stages, or null if any stage is missing/unreadable. */
private fun readConflictStages(repo: Repository, path: String): Triple<String, String, String>? {
    val dirCache = repo.readDirCache()
    var idx = dirCache.findEntry(path)
    if (idx < 0) idx = -(idx + 1)

    var base: DirCacheEntry? = null
    var local: DirCacheEntry? = null
    var remote: DirCacheEntry? = null
    while (idx < dirCache.entryCount) {
        val entry = dirCache.getEntry(idx)
        if (entry.pathString != path) break
        when (entry.stage) {
            DirCacheEntry.STAGE_1 -> base = entry
            DirCacheEntry.STAGE_2 -> local = entry
            DirCacheEntry.STAGE_3 -> remote = entry
        }
        idx++
    }
    // A file conflicting for a non-content reason (binary, rename/rename, add/add without a
    // common base) won't carry all three stages — nothing for a text merge to work with.
    if (base == null || local == null || remote == null) return null

    fun blobText(entry: DirCacheEntry): String? =
        runCatching { repo.open(entry.objectId).bytes.toString(Charsets.UTF_8) }.getOrNull()

    val baseText = blobText(base) ?: return null
    val localText = blobText(local) ?: return null
    val remoteText = blobText(remote) ?: return null
    return Triple(baseText, localText, remoteText)
}
