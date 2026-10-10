// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/**
 * What a remote says its default branch is. Ambiguity is never auto-guessed: [Ambiguous] forces
 * the caller to ask the user.
 */
sealed interface DefaultBranchDetection {
    data class Detected(val name: String) : DefaultBranchDetection
    data class Ambiguous(val candidates: List<String>) : DefaultBranchDetection
    data object EmptyRemote : DefaultBranchDetection

    /** The remote couldn't be asked (offline, auth); says nothing about which branches exist. */
    data class Unreachable(val cause: String) : DefaultBranchDetection
}

private const val HEADS_PREFIX = "refs/heads/"

/**
 * Pure classification of an `ls-remote` answer.
 *
 * @param headSymrefTarget full ref `HEAD` points at when the server advertises a symref
 *   (e.g. `refs/heads/master`), else null.
 * @param headObjectId object id `HEAD` resolves to, if advertised.
 * @param heads branch short name -> object id for every branch head on the remote.
 *
 * Preference order: server symref, then the single branch whose tip equals `HEAD`'s, then the
 * only branch. Never returns a name that is not in [heads].
 */
fun classifyDefaultBranch(
    headSymrefTarget: String?,
    headObjectId: String?,
    heads: Map<String, String>,
): DefaultBranchDetection {
    if (heads.isEmpty()) return DefaultBranchDetection.EmptyRemote

    val symref = headSymrefTarget?.removePrefix(HEADS_PREFIX)
    if (symref != null && symref in heads) return DefaultBranchDetection.Detected(symref)

    if (headObjectId != null) {
        val matching = heads.filterValues { it == headObjectId }.keys.sorted()
        if (matching.size == 1) return DefaultBranchDetection.Detected(matching.single())
        if (matching.size > 1) return DefaultBranchDetection.Ambiguous(matching)
    }
    return if (heads.size == 1) {
        DefaultBranchDetection.Detected(heads.keys.single())
    } else {
        DefaultBranchDetection.Ambiguous(heads.keys.sorted())
    }
}
