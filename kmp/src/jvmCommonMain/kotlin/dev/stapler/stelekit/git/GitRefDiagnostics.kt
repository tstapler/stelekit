// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.LsRemoteCommand
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants

/**
 * Plain-text description of the repo's refs, for the diagnostics export. Shows whether
 * `<remoteName>/<remoteBranch>` — the ref [GitRepository.fetch] compares HEAD against —
 * resolves locally, and (best effort, needs network + auth) which branches the remote actually has.
 * Every section is independently try/caught so one failure doesn't hide the rest.
 * Never prints credentials: the remote URL has any userinfo stripped.
 */
internal fun describeGitRefs(
    git: Git,
    config: GitConfig,
    configureLsRemote: (LsRemoteCommand) -> Unit,
): String = buildString {
    val repo = git.repository
    val configuredRef = "${config.remoteName}/${config.remoteBranch}"

    section("HEAD") {
        appendLine("branch=${repo.branch} fullBranch=${repo.fullBranch} head=${repo.resolve(Constants.HEAD)?.name ?: "<none>"}")
        appendLine("isShallow=${repo.objectDatabase.shallowCommits.isNotEmpty()}")
    }
    section("remote url") {
        val raw = repo.config.getString("remote", config.remoteName, "url") ?: "<unset>"
        appendLine("${config.remoteName}.url=${raw.replace(Regex("//[^/@]+@"), "//")}")
    }
    section("configured ref") {
        val resolved = repo.resolve(configuredRef)
        appendLine("resolve($configuredRef)=${resolved?.name ?: "NOT RESOLVED — fetch() will report no remote changes"}")
        val local = repo.fullBranch?.takeIf { it.startsWith(Constants.R_HEADS) }
        if (resolved != null && local != null) {
            val tracking = BranchTrackingStatus.of(repo, repo.branch)
            appendLine("tracking(${repo.branch}): ${tracking?.let { "ahead=${it.aheadCount} behind=${it.behindCount} of ${it.remoteTrackingBranch}" } ?: "no upstream configured"}")
        }
    }
    section("local branches") {
        git.branchList().call().forEach { appendLine(refLine(it.name, it.objectId.name)) }
    }
    section("remote-tracking refs") {
        repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES).forEach { appendLine(refLine(it.name, it.objectId.name)) }
    }
    section("live remote heads (ls-remote)") {
        val heads = git.lsRemote().setRemote(config.remoteName).setHeads(true)
            .also(configureLsRemote).call()
        if (heads.isEmpty()) appendLine("<none>")
        heads.sortedBy { it.name }.forEach { appendLine(refLine(it.name, it.objectId.name)) }
        val want = "${Constants.R_HEADS}${config.remoteBranch}"
        appendLine("remote has $want: ${heads.any { it.name == want }}")
    }
}

private fun refLine(name: String, sha: String) = "$name $sha"

private inline fun StringBuilder.section(title: String, body: StringBuilder.() -> Unit) {
    appendLine("-- $title")
    try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        appendLine("<failed: ${e::class.simpleName}: ${e.message}>")
    }
}
