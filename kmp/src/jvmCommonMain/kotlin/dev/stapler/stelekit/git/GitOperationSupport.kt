// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

/**
 * Runs [op] (which already returns the operation's own [Either]), converting any thrown failure
 * to a typed [DomainError.GitError] via [onFailed]. Cancellation always propagates unconverted.
 * Shared by [AndroidGitRepository] and [JvmGitRepository] — every non-transport JGit call
 * (init/status/commit/merge/etc.) wraps its body this way instead of repeating the same
 * two-catch boilerplate.
 */
inline fun <T> runGitOp(
    onFailed: (Exception) -> DomainError.GitError,
    op: () -> Either<DomainError.GitError, T>,
): Either<DomainError.GitError, T> =
    try {
        op()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailed(e).left()
    }

/**
 * Like [runGitOp], but additionally maps a JGit [TransportException] — thrown for remote
 * auth/connectivity failures — to [onAuthFailed] instead of the generic [onFailed]. Used by every
 * clone/fetch/push/testRemote call, the only operations that touch a remote transport.
 */
inline fun <T> runGitTransportOp(
    onAuthFailed: (Exception) -> DomainError.GitError,
    onFailed: (Exception) -> DomainError.GitError,
    op: () -> Either<DomainError.GitError, T>,
): Either<DomainError.GitError, T> =
    try {
        op()
    } catch (e: TransportException) {
        onAuthFailed(e).left()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailed(e).left()
    }

/**
 * Runs [op], propagating cancellation and treating any other failure as `false`. Used by
 * [AndroidGitRepository.hasDetachedHead]/[JvmGitRepository.hasDetachedHead]/
 * [JvmGitRepository.isGitRepo], which report absence rather than a typed error on failure.
 */
inline fun runGitOpOrFalse(op: () -> Boolean): Boolean =
    try {
        op()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

/**
 * Number of commits between [from] and [to] (capped at 100), or 0 if the count can't be
 * computed. Used by [AndroidGitRepository.fetch]/[JvmGitRepository.fetch] to populate
 * `FetchResult.remoteCommitCount` — best-effort: a failure here must not fail a fetch that
 * already succeeded.
 */
fun countRemoteCommitsBestEffort(git: Git, from: ObjectId, to: ObjectId): Int = try {
    git.log().addRange(from, to).setMaxCount(100).call().toList().size
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    0
}

/**
 * Configures [cmd]'s credentials provider for [auth] when it's [GitAuth.HttpsToken] or
 * [GitAuth.None] — the two auth kinds handled identically by [AndroidGitAuthConfigurer.configureAuth]
 * and [JvmGitRepositoryAuth.configureAuth]. Returns `true` when handled; callers configure
 * [GitAuth.SshKey] themselves afterward, since Android (JSch) and JVM (Apache MINA sshd) use
 * different SSH transport libraries for it. A missing/unresolved token invokes [onMissingToken]
 * and leaves [cmd] unauthenticated rather than failing outright, matching the pre-existing
 * best-effort clone behavior on both platforms.
 */
fun configureHttpsOrNoAuth(
    cmd: TransportCommand<*, *>,
    auth: GitAuth,
    preResolvedToken: String?,
    onMissingToken: () -> Unit,
): Boolean = when (auth) {
    is GitAuth.HttpsToken -> {
        val token = preResolvedToken ?: run { onMissingToken(); return true }
        cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(auth.username, token))
        true
    }
    is GitAuth.None -> true
    is GitAuth.SshKey -> false
}
