// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.LsRemoteCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId

/** Keeps the live `ls-remote` from hanging the review on an offline or black-holed remote. */
internal const val PREVIEW_LS_REMOTE_TIMEOUT_SECONDS = 10

/**
 * Builds the first-sync review data from the local repository plus one best-effort `ls-remote`.
 * Does not fetch: [GuardScope.NetPush] and the ahead/behind counts use the remote-tracking refs as they are.
 */
internal fun buildFirstSyncPreview(
    git: Git,
    config: GitConfig,
    configureLsRemote: (LsRemoteCommand) -> Unit,
): Either<DomainError.GitError, FirstSyncPreview> {
    val repo = git.repository
    val syncRef = "${config.remoteName}/${config.remoteBranch}"
    val remoteTip = repo.exactRef("${Constants.R_REMOTES}$syncRef")?.objectId
    val head = repo.resolve(Constants.HEAD)

    val localOnly = if (remoteTip != null && head != null) commitsBetween(git, head, remoteTip) else emptyList()
    val behind = if (remoteTip != null && head != null) commitsBetween(git, remoteTip, head).size else null

    val live = liveRemoteBranches(git, config, configureLsRemote)
    val names = live ?: knownRemoteBranches(repo, config.remoteName)

    return FirstSyncPreview(
        syncRef = syncRef,
        ahead = if (remoteTip != null && head != null) localOnly.size else null,
        behind = behind,
        remoteBranches = names.sorted().map { PreviewRemoteBranch(it, isSyncBranch = it == config.remoteBranch) },
        remoteBranchesLive = live != null,
        localOnlyCommits = localOnly.take(FirstSyncPreview.MAX_LOCAL_COMMITS),
        filesToDelete = try {
            summarizeDeletions(git, config, GuardScope.NetPush).deletedPaths.size
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        },
    ).right()
}

/** Commits reachable from [include] but not from [exclude], newest first; empty when history cannot be walked. */
private fun commitsBetween(git: Git, include: ObjectId, exclude: ObjectId): List<PreviewCommit> = try {
    git.log().add(include).not(exclude).call().map {
        PreviewCommit(it.abbreviate(SHORT_SHA_LENGTH).name(), it.shortMessage, it.commitTime.toLong())
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    emptyList()
}

private const val SHORT_SHA_LENGTH = 7

private fun liveRemoteBranches(git: Git, config: GitConfig, configure: (LsRemoteCommand) -> Unit): List<String>? = try {
    val heads = git.lsRemote().setRemote(config.remoteName).setHeads(true)
        .setTimeout(PREVIEW_LS_REMOTE_TIMEOUT_SECONDS)
        .also(configure).call()
    branchShortNames(heads)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

private fun knownRemoteBranches(repo: org.eclipse.jgit.lib.Repository, remote: String): List<String> {
    val prefix = "${Constants.R_REMOTES}$remote/"
    return repo.refDatabase.getRefsByPrefix(prefix).map { it.name.removePrefix(prefix) }.filter { it != Constants.HEAD }
}
