// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter

/** Finds LFS pointer blobs in git history using the spec-strict [LfsPointerCodec], never JGit's lenient parser. */
class LfsPointerScanner(private val repo: Repository) {

    /** Pointers in [tree] (a tree-ish), keyed by repository-relative path. */
    fun pointersIn(tree: ObjectId): Map<String, LfsPointer> = scan(listOf(tree))

    /** Pointers added or changed going from [from] (null = empty) to [to]. */
    fun pointersChanged(from: ObjectId?, to: ObjectId): Map<String, LfsPointer> =
        if (from == null) scan(listOf(to)) else scan(listOf(from, to), diffOnly = true)

    /** Every pointer introduced by commits reachable from [tip] but from none of the remote-tracking refs. */
    fun pointersToPush(tip: ObjectId, remoteName: String): Set<LfsPointer> {
        val found = LinkedHashSet<LfsPointer>()
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(tip))
            repo.refDatabase.getRefsByPrefix("${Constants.R_REMOTES}$remoteName/").forEach { ref ->
                ref.objectId?.let { runCatching { walk.markUninteresting(walk.parseCommit(it)) } }
            }
            for (commit in walk) {
                val parent = commit.parents.firstOrNull()?.let { runCatching { walk.parseCommit(it) }.getOrNull() }
                found += (if (parent == null) scan(listOf(commit.tree)) else scan(listOf(parent.tree, commit.tree), diffOnly = true)).values
            }
        }
        return found
    }

    private fun scan(trees: List<ObjectId>, diffOnly: Boolean = false): Map<String, LfsPointer> {
        val result = LinkedHashMap<String, LfsPointer>()
        repo.newObjectReader().use { reader ->
            TreeWalk(repo).use { walk ->
                trees.forEach { walk.addTree(it) }
                walk.isRecursive = true
                if (diffOnly) walk.filter = TreeFilter.ANY_DIFF
                val last = trees.size - 1
                while (walk.next()) {
                    if (walk.getFileMode(last) != FileMode.REGULAR_FILE) continue
                    val blobId = walk.getObjectId(last)
                    pointerAt(reader, blobId)?.let { result[walk.pathString] = it }
                }
            }
        }
        return result
    }

    private fun pointerAt(reader: ObjectReader, blobId: ObjectId): LfsPointer? {
        val size = reader.getObjectSize(blobId, Constants.OBJ_BLOB)
        if (size < MIN_POINTER_BYTES || size >= LfsPointerCodec.MAX_POINTER_BYTES) return null
        val bytes = reader.open(blobId, Constants.OBJ_BLOB).getCachedBytes(LfsPointerCodec.MAX_POINTER_BYTES)
        return (LfsPointerCodec.parse(bytes) as? LfsPointerParse.Valid)?.pointer
    }

    private companion object {
        /** `version`, `oid sha256:<64>` and `size N` lines cannot fit in fewer bytes. */
        const val MIN_POINTER_BYTES = 120L
    }
}
