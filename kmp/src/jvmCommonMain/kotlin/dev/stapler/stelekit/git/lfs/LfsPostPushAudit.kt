// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter

/**
 * Detection (not prevention) for commits that reached the remote without passing the guard, such as a raw
 * binary the user committed with a plain `git add` outside SteleKit: after a successful push, scans the pushed
 * range for blobs at LFS-tracked paths that are not pointers. The data is already remote by then, so the result
 * only feeds the held-files UI as [LfsViolationReason.RawBinaryPushed] and a log line.
 */
object LfsPostPushAudit {
    /** Bounds so a first push of a long history cannot stall a sync; older commits are simply not audited. */
    const val MAX_COMMITS = 200
    const val MAX_BLOBS = 2_000
    private const val MAX_ATTRIBUTES_BYTES = 1L shl 20

    /** [previousRemoteTip] is the remote-tracking tip before the push (null on a first push). */
    suspend fun audit(repo: Repository, previousRemoteTip: ObjectId?, pushedTip: ObjectId): List<LfsViolation> {
        val introduced = introducedBlobs(repo, previousRemoteTip, pushedTip)
        if (introduced.isEmpty()) return emptyList()
        repo.newObjectReader().use { reader ->
            val policy = LfsPathPolicy(rulesAt(repo, pushedTip))
            if (!policy.hasAnyRules) return emptyList()
            val probe = object : LfsContentProbe {
                override suspend fun size(path: String): Long? =
                    introduced[path]?.let { reader.getObjectSize(it, Constants.OBJ_BLOB) }

                override suspend fun readHead(path: String, maxBytes: Int): ByteArray? =
                    introduced[path]?.let { reader.open(it, Constants.OBJ_BLOB).getCachedBytes(maxBytes) }
            }
            return LfsGuard.evaluate(introduced.keys.toList(), policy, probe).held
                .map { it.copy(reason = LfsViolationReason.RawBinaryPushed) }
        }
    }

    /** Newest blob per path among the files added or changed by the pushed commits. */
    private fun introducedBlobs(repo: Repository, previous: ObjectId?, tip: ObjectId): Map<String, ObjectId> {
        val blobs = LinkedHashMap<String, ObjectId>()
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(tip))
            previous?.let { runCatching { walk.markUninteresting(walk.parseCommit(it)) } }
            var commits = 0
            for (commit in walk) {
                if (++commits > MAX_COMMITS || blobs.size >= MAX_BLOBS) break
                addChangedBlobs(repo, commit, blobs)
            }
        }
        return blobs
    }

    /** A merge commit also carries the other parent's files; those were on the remote already, so only blobs absent from every parent count. */
    private fun addChangedBlobs(repo: Repository, commit: RevCommit, into: MutableMap<String, ObjectId>) {
        TreeWalk(repo).use { tw ->
            tw.isRecursive = true
            tw.filter = TreeFilter.ANY_DIFF
            tw.addTree(commit.tree)
            val parentTrees = RevWalk(repo).use { walk -> commit.parents.map { walk.parseCommit(it.id).tree.id } }
            parentTrees.forEach { tw.addTree(it) }
            while (tw.next()) {
                val mode = tw.getFileMode(0)
                if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) continue
                val id = tw.getObjectId(0)
                if ((1..parentTrees.size).any { tw.getObjectId(it) == id }) continue
                into.putIfAbsent(tw.pathString, id)
            }
        }
    }

    private fun rulesAt(repo: Repository, tip: ObjectId): AttributeRuleSet {
        val files = ArrayList<GitAttributesFile>()
        RevWalk(repo).use { walk ->
            TreeWalk(repo).use { tw ->
                tw.addTree(walk.parseCommit(tip).tree)
                tw.isRecursive = true
                tw.filter = PathSuffixFilter.create(LfsPathPolicy.GITATTRIBUTES_FILE)
                while (tw.next()) {
                    val path = tw.pathString
                    if (path.substringAfterLast('/') != LfsPathPolicy.GITATTRIBUTES_FILE) continue
                    val loader = repo.open(tw.getObjectId(0))
                    if (loader.size > MAX_ATTRIBUTES_BYTES) continue
                    val dir = path.substringBeforeLast('/', "")
                    files += GitAttributesParser.parse(loader.bytes.decodeToString(), dirPrefix = dir)
                }
            }
        }
        return AttributeRuleSet(files)
    }
}
