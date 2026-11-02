// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.NullOutputStream
import java.io.ByteArrayOutputStream

private const val KIB = 1024L

/** Largest added/modified file the marker scan will format; beyond it the scan fails closed. */
internal const val MARKER_SCAN_FILE_CAP_BYTES = 2L * KIB * KIB

/** Largest total of bytes the marker scan will format in one run. */
internal const val MARKER_SCAN_TOTAL_CAP_BYTES = 32L * KIB * KIB

/** `Repository.getRepositoryState()` plus unmerged index paths; fails closed when the repository cannot be read. */
internal fun repositoryHealthOf(repo: Repository): RepositoryHealth = try {
    val state = repo.repositoryState
    when {
        state != RepositoryState.SAFE -> RepositoryHealth(false, state.name)
        repo.readDirCache().hasUnmergedPaths() -> RepositoryHealth(false, "UNMERGED_PATHS")
        else -> RepositoryHealth.Clean
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    RepositoryHealth(false, "UNREADABLE")
}

/** Added-line conflict-marker scan of [scope]; see [GitRepository.scanForConflictMarkers]. */
internal fun scanConflictMarkers(git: Git, config: GitConfig, scope: GuardScope): MarkerScan = try {
    val diff = diffFor(git, config, scope)
    if (diff == null) MarkerScan.Clean else scanDiff(git.repository, diff.entries)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    MarkerScan.Incomplete("${e::class.simpleName}")
}

/** Deleted paths of [scope]; no base tree gives [DeletionSummary.None]. Read errors propagate; the marker scan runs first and fails closed on the same read. */
internal fun summarizeDeletions(git: Git, config: GitConfig, scope: GuardScope): DeletionSummary {
    val diff = diffFor(git, config, scope) ?: return DeletionSummary.None
    val deleted = diff.entries.filter { it.changeType == DiffEntry.ChangeType.DELETE }.map { it.oldPath }
    return DeletionSummary(deleted, diff.baseTreeSize)
}

private class ScopeDiff(val entries: List<DiffEntry>, val baseTreeSize: Int)

private fun diffFor(git: Git, config: GitConfig, scope: GuardScope): ScopeDiff? {
    val repo = git.repository
    val headTree = repo.resolve("${Constants.HEAD}^{tree}")
    return when (scope) {
        GuardScope.Staged -> {
            val cmd = git.diff().setCached(true).setShowNameAndStatusOnly(true)
            ScopeDiff(cmd.call(), headTree?.let { countFiles(repo, it) } ?: 0)
        }
        GuardScope.NetPush -> {
            val remoteTree = repo.resolve("${Constants.R_REMOTES}${config.remoteName}/${config.remoteBranch}^{tree}") ?: return null
            headTree ?: return null
            val entries = repo.newObjectReader().use { reader ->
                git.diff()
                    .setOldTree(CanonicalTreeParser(null, reader, remoteTree))
                    .setNewTree(CanonicalTreeParser(null, reader, headTree))
                    .setShowNameAndStatusOnly(true)
                    .call()
            }
            ScopeDiff(entries, countFiles(repo, remoteTree))
        }
    }
}

private fun countFiles(repo: Repository, tree: ObjectId): Int = TreeWalk(repo).use { walk ->
    walk.addTree(tree)
    walk.isRecursive = true
    var count = 0
    while (walk.next()) count++
    count
}

private fun scanDiff(repo: Repository, entries: List<DiffEntry>): MarkerScan {
    val found = mutableListOf<String>()
    var total = 0L
    DiffFormatter(NullOutputStream.INSTANCE).use { sizing ->
        sizing.setRepository(repo)
        for (entry in entries) {
            if (entry.changeType == DiffEntry.ChangeType.DELETE || entry.newId.toObjectId() == ObjectId.zeroId()) continue
            val size = repo.open(entry.newId.toObjectId()).size
            if (size > MARKER_SCAN_FILE_CAP_BYTES) return MarkerScan.Incomplete("${entry.newPath} is larger than ${MARKER_SCAN_FILE_CAP_BYTES / KIB} KiB")
            total += size
            if (total > MARKER_SCAN_TOTAL_CAP_BYTES) return MarkerScan.Incomplete("changed files exceed ${MARKER_SCAN_TOTAL_CAP_BYTES / KIB / KIB} MiB")
        }
    }
    for (entry in entries) {
        if (entry.changeType == DiffEntry.ChangeType.DELETE) continue
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { fmt ->
            fmt.setRepository(repo)
            fmt.setContext(FULL_CONTEXT_LINES)
            fmt.format(entry)
        }
        val lines = parseDiffBody(out.toString(Charsets.UTF_8))
        if (ConflictMarkerDiffScan.hasAddedMarkerPair(lines)) found += entry.newPath
    }
    return if (found.isEmpty()) MarkerScan.Clean else MarkerScan.Found(found)
}

private const val FULL_CONTEXT_LINES = 1_000_000

/** Lines after the first `@@` hunk header; binary diffs have none and parse to an empty list. */
internal fun parseDiffBody(diff: String): List<DiffLine> {
    val body = diff.lineSequence().dropWhile { !it.startsWith("@@") }.drop(1)
    return body.mapNotNull { raw ->
        when {
            raw.startsWith("+") -> DiffLine(true, raw.substring(1))
            raw.startsWith(" ") -> DiffLine(false, raw.substring(1))
            else -> null
        }
    }.toList()
}
