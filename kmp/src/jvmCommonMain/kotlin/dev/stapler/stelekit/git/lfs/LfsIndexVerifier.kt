// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File

/**
 * Repository-level backstop for the LFS safety floor, shared by the JVM and Android adapters: walks what
 * is staged (index entries that differ from HEAD) and reports every blob at an LFS-tracked path that is not a
 * pointer. A blob already raw in HEAD and untouched is not a change, so legacy history never blocks a commit.
 *
 * Rules come from the index versions of `.gitattributes` (what the commit will contain), falling back to the
 * working-tree file for directories with no staged one, plus `.git/info/attributes`.
 */
class LfsIndexVerifier(private val repo: Repository) {
    private val logger = Logger("LfsIndexVerifier")

    private class StagedBlob(val path: String, val id: ObjectId)

    /** Reports violations without touching the index. */
    suspend fun stagedViolations(): List<LfsViolation> {
        val dirCache = repo.readDirCache()
        val staged = stagedChanges(dirCache)
        if (staged.isEmpty()) return emptyList()
        val policy = LfsPathPolicy(loadRules(dirCache, staged.map { it.path }))
        if (!policy.hasAnyRules) return emptyList()
        repo.newObjectReader().use { reader ->
            val byPath = staged.associateBy { it.path }
            val probe = object : LfsContentProbe {
                override suspend fun size(path: String): Long? =
                    byPath[path]?.let { reader.getObjectSize(it.id, Constants.OBJ_BLOB) }

                override suspend fun readHead(path: String, maxBytes: Int): ByteArray? =
                    byPath[path]?.let { reader.open(it.id, Constants.OBJ_BLOB).getCachedBytes(maxBytes) }
            }
            return LfsGuard.evaluate(staged.map { it.path }, policy, probe).held
        }
    }

    /** Reports violations and removes them from the index, so a following commit cannot contain them. */
    suspend fun verifyAndUnstage(git: Git): List<LfsViolation> {
        val violations = stagedViolations()
        if (violations.isNotEmpty()) unstage(git, violations.map { it.path })
        return violations
    }

    /** Hard-failure form used right before `commit()`: violations are un-staged and surfaced as an error. */
    suspend fun refuseOnViolation(git: Git): DomainError.GitError.LfsRawBinaryRefused? {
        val violations = verifyAndUnstage(git)
        if (violations.isEmpty()) return null
        logger.warn("refusing to commit ${violations.size} raw file(s) at LFS-tracked paths")
        return DomainError.GitError.LfsRawBinaryRefused(violations.map { it.path })
    }

    /** Removes [paths] from the index again, restoring HEAD's version (or dropping them on an unborn branch). */
    fun unstagePaths(git: Git, paths: Collection<String>) {
        if (paths.isNotEmpty()) unstage(git, paths.toList())
    }

    private fun unstage(git: Git, paths: List<String>) {
        val hasHead = repo.resolve(Constants.HEAD + "^{tree}") != null
        if (hasHead) {
            val reset = git.reset()
            paths.forEach { reset.addPath(it) }
            reset.call()
            return
        }
        // Unborn branch: there is no HEAD entry to restore, so drop the entries outright.
        val dirCache = repo.lockDirCache()
        try {
            val editor = dirCache.editor()
            paths.forEach { editor.add(DirCacheEditor.DeletePath(it)) }
            editor.commit()
        } finally {
            dirCache.unlock()
        }
    }

    /**
     * Index entries that differ from HEAD. While a merge is in progress the other parent's content also differs from
     * HEAD but is already on the remote, so entries equal to MERGE_HEAD's are not ours to judge: refusing (or
     * un-staging) them would block, or silently revert, the remote's changes.
     */
    private fun stagedChanges(dirCache: DirCache): List<StagedBlob> {
        val headTree = repo.resolve(Constants.HEAD + "^{tree}")
        val mergeTree = repo.resolve(Constants.MERGE_HEAD + "^{tree}")
        val result = ArrayList<StagedBlob>()
        TreeWalk(repo).use { walk ->
            walk.isRecursive = true
            val indexTree = walk.addTree(DirCacheIterator(dirCache))
            val headIndex = if (headTree != null) walk.addTree(headTree) else -1
            val mergeIndex = if (mergeTree != null) walk.addTree(mergeTree) else -1
            while (walk.next()) {
                val mode = walk.getFileMode(indexTree)
                if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) continue
                val id = walk.getObjectId(indexTree)
                val headId = if (headIndex >= 0) walk.getObjectId(headIndex) else ObjectId.zeroId()
                val mergeId = if (mergeIndex >= 0) walk.getObjectId(mergeIndex) else ObjectId.zeroId()
                if (id != headId && id != mergeId) result += StagedBlob(walk.pathString, id)
            }
        }
        return result
    }

    private fun loadRules(dirCache: DirCache, stagedPaths: List<String>): AttributeRuleSet {
        val files = LinkedHashMap<String, GitAttributesFile>()
        repo.newObjectReader().use { reader ->
            for (i in 0 until dirCache.entryCount) {
                val entry = dirCache.getEntry(i)
                val path = entry.pathString
                if (entry.stage != 0 || path.substringAfterLast('/') != LfsPathPolicy.GITATTRIBUTES_FILE) continue
                val loader = reader.open(entry.objectId, Constants.OBJ_BLOB)
                if (loader.size > MAX_ATTRIBUTES_BYTES) continue
                val dir = path.substringBeforeLast('/', "")
                files[dir] = GitAttributesParser.parse(loader.bytes.decodeToString(), dirPrefix = dir)
            }
        }
        repo.workTree?.let { workTree -> addWorkingTreeFallbacks(workTree, stagedPaths, files) }
        val info = File(repo.directory, "info/attributes").takeIf { it.isFile }
            ?.let { GitAttributesParser.parse(it.readText(), allowMacros = true, sourceName = ".git/info/attributes") }
        return AttributeRuleSet(files.values.toList(), info)
    }

    /** Ancestor directories with no staged `.gitattributes` still honour a working-tree one. */
    private fun addWorkingTreeFallbacks(workTree: File, stagedPaths: List<String>, files: MutableMap<String, GitAttributesFile>) {
        val directories = LinkedHashSet<String>().apply { add("") }
        for (path in stagedPaths) {
            var dir = path.substringBeforeLast('/', "")
            while (dir.isNotEmpty() && directories.add(dir)) dir = dir.substringBeforeLast('/', "")
        }
        for (dir in directories) {
            if (dir in files) continue
            val file = File(if (dir.isEmpty()) workTree else File(workTree, dir), LfsPathPolicy.GITATTRIBUTES_FILE)
            if (file.isFile && file.length() <= MAX_ATTRIBUTES_BYTES) {
                files[dir] = GitAttributesParser.parse(file.readText(), dirPrefix = dir)
            }
        }
    }

    private companion object {
        const val MAX_ATTRIBUTES_BYTES = 1L shl 20
    }
}
