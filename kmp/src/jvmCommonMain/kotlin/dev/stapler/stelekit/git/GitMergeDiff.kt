// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.io.DisabledOutputStream

/**
 * Git-relative paths (e.g. "pages/foo.md") that differ between HEAD's pre- and post-merge parent
 * commit — used by [AndroidGitRepository.merge] and [JvmGitRepository.merge] to compute
 * `MergeResult.changedFiles` after a successful (possibly conflicting) merge. Returns an empty
 * list, rather than throwing, for a repo with no HEAD yet or when the diff itself fails — a merge
 * that already succeeded in JGit's index must not be reported as a failure just because this
 * best-effort "what changed" computation couldn't run.
 */
fun computeChangedGitRelativePaths(repo: Repository): List<String> = try {
    val headAfter = repo.resolve("HEAD")
    if (headAfter != null) {
        val revWalk = RevWalk(repo)
        val headCommit = revWalk.parseCommit(headAfter)
        val parentCommit = headCommit.parents.firstOrNull()?.let { revWalk.parseCommit(it) }
        val diffFormatter = DiffFormatter(DisabledOutputStream.INSTANCE)
        diffFormatter.setRepository(repo)
        val files = if (parentCommit != null) {
            diffFormatter.scan(parentCommit.tree, headCommit.tree).map { it.newPath }
        } else {
            emptyList()
        }
        diffFormatter.close()
        revWalk.close()
        files
    } else {
        emptyList()
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    emptyList()
}
