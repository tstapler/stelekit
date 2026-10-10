// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk

/**
 * Resolves `refs/remotes/<remote>/<branch>` for fetch and merge on both platforms (ADR-003).
 *
 * Uses `exactRef`, never `Repository.resolve("<remote>/<branch>")`: `resolve` also matches a
 * local branch literally named `origin/main`, which would silently shadow a missing
 * remote-tracking ref. A missing ref is a typed error, never "no changes".
 *
 * [advertisedBranches] are the remote's branch short names from a fetch; when null (merge path)
 * the locally known remote-tracking branches are listed instead.
 */
internal fun resolveRemoteTrackingRef(
    repo: Repository,
    remote: String,
    branch: String,
    advertisedBranches: List<String>? = null,
): Either<DomainError.GitError, ObjectId> {
    if (!isValidRemoteName(remote)) return DomainError.GitError.InvalidRefName(remote).left()
    if (!isValidBranchName(branch)) return DomainError.GitError.InvalidRefName(branch).left()

    val ref = repo.exactRef("${Constants.R_REMOTES}$remote/$branch")
    val objectId = ref?.objectId
    if (objectId != null) return objectId.right()

    val available = (advertisedBranches ?: localRemoteBranches(repo, remote)).sorted()
    return if (available.isEmpty()) {
        DomainError.GitError.RemoteEmpty.left()
    } else {
        DomainError.GitError.RemoteBranchNotFound(remote, branch, available).left()
    }
}

/** Branch short names (`refs/heads/x` -> `x`) among [refs]. */
internal fun branchShortNames(refs: Collection<Ref>): List<String> =
    refs.map { it.name }
        .filter { it.startsWith(Constants.R_HEADS) }
        .map { it.removePrefix(Constants.R_HEADS) }

private fun localRemoteBranches(repo: Repository, remote: String): List<String> {
    val prefix = "${Constants.R_REMOTES}$remote/"
    return repo.refDatabase.getRefsByPrefix(prefix)
        .map { it.name.removePrefix(prefix) }
        .filter { it != Constants.HEAD }
}

private fun isValidRemoteName(remote: String): Boolean =
    remote.isNotEmpty() && Repository.isValidRefName("${Constants.R_REMOTES}$remote/x")

private fun isValidBranchName(branch: String): Boolean =
    branch.isNotEmpty() && Repository.isValidRefName("${Constants.R_HEADS}$branch")

/**
 * True when [remoteTip] has commits that [head] lacks (remote ahead or diverged). False when the
 * remote is equal to, or an ancestor of, [head] — a local-ahead-only repo has nothing to pull.
 * An unborn HEAD ([head] null) has everything to pull.
 */
internal fun isRemoteAhead(repo: Repository, head: ObjectId?, remoteTip: ObjectId): Boolean {
    if (head == null) return true
    if (head == remoteTip) return false
    RevWalk(repo).use { walk ->
        return !walk.isMergedInto(walk.parseCommit(remoteTip), walk.parseCommit(head))
    }
}

/**
 * Commits reachable from [after] but not [before], excluding the merge commit [after] itself when
 * the merge created it (NO_FF) — i.e. the remote commits that were actually brought in.
 */
internal fun countMergedCommits(repo: Repository, before: ObjectId?, after: ObjectId?, remoteTip: ObjectId): Int {
    if (after == null || after == before) return 0
    RevWalk(repo).use { walk ->
        val afterCommit = walk.parseCommit(after)
        walk.markStart(afterCommit)
        if (before != null) walk.markUninteresting(walk.parseCommit(before))
        var count = 0
        for (ignored in walk) count++
        if (afterCommit.parentCount > 1 && after != remoteTip) count -= 1
        return count.coerceAtLeast(0)
    }
}
