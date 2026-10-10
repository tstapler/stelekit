// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/** A user-confirmable change of a graph's stored `remote_branch` from [from] to [to]. */
data class BranchRepairProposal(
    val graphId: String,
    val from: String,
    val to: String,
    val available: List<String>,
)

sealed interface BranchRepairResult {
    data class Repaired(val from: String, val to: String) : BranchRepairResult

    /** The stored branch is no longer [BranchRepairProposal.from]; nothing was written. */
    data class Stale(val currentBranch: String?) : BranchRepairResult

    /** The chosen branch is not one the remote has; nothing was written. */
    data class TargetNotOnRemote(val to: String) : BranchRepairResult

    data class SaveFailed(val message: String) : BranchRepairResult

    /** The write returned success but the stored row did not change (mutation read-back failed). */
    data class ReadBackMismatch(val expected: String, val actual: String?) : BranchRepairResult
}

/**
 * Applies a confirmed [BranchRepairProposal] through [GitConfigRepository.saveConfig] and reads the
 * row back before reporting success. Shared by the repair sheet and the console `git set-branch`.
 * Never syncs: it only records that a first-sync review is pending ([firstSync]).
 */
class BranchRepairService(
    private val configRepository: GitConfigRepository,
    private val firstSync: FirstSyncConfirmation? = null,
) {
    /** Builds a proposal to move off the missing branch, or null when the remote offers no choice. */
    fun proposalFor(
        graphId: String,
        error: DomainError.GitError.RemoteBranchNotFound,
        detection: DefaultBranchDetection,
    ): BranchRepairProposal? {
        val to = (detection as? DefaultBranchDetection.Detected)?.name?.takeIf { it in error.available }
            ?: return null
        return BranchRepairProposal(graphId, from = error.branch, to = to, available = error.available)
    }

    suspend fun apply(proposal: BranchRepairProposal): BranchRepairResult {
        if (proposal.to !in proposal.available) return BranchRepairResult.TargetNotOnRemote(proposal.to)

        val current = when (val r = configRepository.getConfig(proposal.graphId)) {
            is Either.Left -> return BranchRepairResult.SaveFailed(r.value.message)
            is Either.Right -> r.value ?: return BranchRepairResult.Stale(null)
        }
        if (current.remoteBranch != proposal.from) return BranchRepairResult.Stale(current.remoteBranch)

        configRepository.saveConfig(current.copy(remoteBranch = proposal.to)).onLeft {
            return BranchRepairResult.SaveFailed(it.message)
        }

        val stored = (configRepository.getConfig(proposal.graphId) as? Either.Right)?.value?.remoteBranch
        if (stored != proposal.to) return BranchRepairResult.ReadBackMismatch(proposal.to, stored)

        firstSync?.markReviewPending(proposal.graphId, previousBranch = proposal.from)
        return BranchRepairResult.Repaired(proposal.from, proposal.to)
    }

    /** Plain-text details for "Copy details"; the URL's userinfo (credentials) is stripped. */
    fun copyDetails(error: DomainError.GitError.RemoteBranchNotFound, remoteUrl: String?): String = buildString {
        appendLine("Branch '${error.branch}' not found on remote '${error.remote}'")
        appendLine("Remote branches: ${error.available.joinToString().ifEmpty { "(none)" }}")
        if (remoteUrl != null) appendLine("Remote URL: ${stripUserInfo(remoteUrl)}")
        append("Nothing was pulled.")
    }

    private fun stripUserInfo(url: String): String = url.replace(Regex("//[^/@]+@"), "//")
}
