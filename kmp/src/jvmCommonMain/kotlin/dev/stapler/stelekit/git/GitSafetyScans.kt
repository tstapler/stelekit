// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.security.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.LsRemoteCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RawText
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

/** `Repository.getRepositoryState()` plus unmerged index paths. Read errors propagate; [GitSafetyOps] fails closed. */
internal fun repositoryHealthOf(repo: Repository): RepositoryHealth {
    val state = repo.repositoryState
    return when {
        state != RepositoryState.SAFE -> RepositoryHealth(false, state.name)
        repo.readDirCache().hasUnmergedPaths() -> RepositoryHealth(false, "UNMERGED_PATHS")
        else -> RepositoryHealth.Clean
    }
}

/** Added-line conflict-marker scan of [scope]; see [GitRepository.scanForConflictMarkers]. Read errors propagate. */
internal fun scanConflictMarkers(git: Git, config: GitConfig, scope: GuardScope): MarkerScan {
    val diff = diffFor(git, config, scope) ?: return MarkerScan.Clean
    return scanDiff(git.repository, diff.entries)
}

/** Returns [fallback] when [op] throws (cancellation still propagates): the guards must fail closed, never throw. */
internal inline fun <T> failClosed(fallback: T, op: () -> T): T =
    try {
        op()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback
    }

/**
 * The guard and preview operations both [GitRepository] backends share; each backend supplies only how it opens
 * the repository. Every operation hops to IO and fails closed on any read error.
 */
internal class GitSafetyOps(private val open: (GitConfig) -> Git) {
    suspend fun health(config: GitConfig): RepositoryHealth = withContext(PlatformDispatcher.IO) {
        failClosed(RepositoryHealth(safe = false, state = "UNREADABLE")) { open(config).use { repositoryHealthOf(it.repository) } }
    }

    suspend fun markers(config: GitConfig, scope: GuardScope): MarkerScan = withContext(PlatformDispatcher.IO) {
        failClosed<MarkerScan>(MarkerScan.Incomplete("repository unreadable")) { open(config).use { scanConflictMarkers(it, config, scope) } }
    }

    suspend fun deletions(config: GitConfig, scope: GuardScope): DeletionSummary = withContext(PlatformDispatcher.IO) {
        failClosed(DeletionSummary.None) { open(config).use { summarizeDeletions(it, config, scope) } }
    }

    suspend fun preview(
        config: GitConfig,
        configureLsRemote: (LsRemoteCommand) -> Unit,
    ): Either<DomainError.GitError, FirstSyncPreview> = withContext(PlatformDispatcher.IO) {
        runGitOp({ e -> DomainError.GitError.FetchFailed(Redactor.redactGitMessage(e.message.orEmpty())) }) {
            open(config).use { buildFirstSyncPreview(it, config, configureLsRemote) }
        }
    }
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
            val loader = repo.open(entry.newId.toObjectId())
            if (isBinaryBlob(loader)) continue // images and other binaries cannot hold text conflict markers
            val size = loader.size
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

/** Git's own heuristic: a NUL byte in the first 8000 bytes. Reads only that prefix, so a large photo costs no memory. */
private fun isBinaryBlob(loader: org.eclipse.jgit.lib.ObjectLoader): Boolean =
    loader.openStream().use { RawText.isBinary(it.readNBytes(BINARY_SNIFF_BYTES)) }

private const val BINARY_SNIFF_BYTES = 8000

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
