// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.lib.Constants

private const val DETECT_TIMEOUT_SECONDS = 15

/**
 * Asks [url] for its default branch via `ls-remote` (HEAD symref, falling back to the branch whose
 * tip equals HEAD). Shared by both platforms; [configureAuth] applies the platform's transport auth.
 * Never throws: any failure is [DefaultBranchDetection.Unreachable], never "branch missing".
 */
suspend fun detectDefaultBranchViaLsRemote(
    url: String,
    auth: GitAuth,
    configureAuth: (TransportCommand<*, *>, GitAuth, String?) -> Unit,
): DefaultBranchDetection = try {
    val token: String? = if (auth is GitAuth.HttpsToken) auth.tokenProvider() else null
    val refs = Git.lsRemoteRepository()
        .setRemote(url)
        .setTimeout(DETECT_TIMEOUT_SECONDS)
        .also { configureAuth(it, auth, token) }
        .callAsMap()
    val heads = refs.filterKeys { it.startsWith(Constants.R_HEADS) }
        .mapKeys { it.key.removePrefix(Constants.R_HEADS) }
        .mapValues { it.value.objectId.name }
    val head = refs[Constants.HEAD]
    classifyDefaultBranch(
        headSymrefTarget = head?.takeIf { it.isSymbolic }?.target?.name,
        headObjectId = head?.objectId?.name,
        heads = heads,
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    DefaultBranchDetection.Unreachable(e.message ?: e::class.simpleName ?: "unreachable")
}
