// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.security.CredentialAccess
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
 * clone/fetch/push/testRemote call, the only operations that touch a remote transport. Every
 * caught exception's message is run through [redactUrlUserinfo] before either callback sees it,
 * so a PAT pasted as `https://ghp_xxx@host/...` (userinfo, not password — JGit's own redaction
 * only strips the password component) never reaches a [DomainError.GitError] and from there the
 * UI/logs.
 */
inline fun <T> runGitTransportOp(
    onAuthFailed: (Exception) -> DomainError.GitError,
    onFailed: (Exception) -> DomainError.GitError,
    op: () -> Either<DomainError.GitError, T>,
): Either<DomainError.GitError, T> =
    try {
        op()
    } catch (e: TransportException) {
        onAuthFailed(redactedTransportException(e)).left()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailed(redactedTransportException(e)).left()
    }

/**
 * Strips a URL's userinfo (`scheme://TOKEN@host/...`) — JGit's `TransportException` redacts the
 * password component but not a bare-userinfo credential (e.g. a PAT pasted as
 * `https://ghp_xxx@host/...`), so this must run before any transport-exception message reaches a
 * log or the UI. `@PublishedApi internal` (rather than plain `internal`) because [runGitTransportOp]
 * is a public inline function and must be able to call it.
 */
@PublishedApi
internal fun redactUrlUserinfo(message: String): String =
    message.replace(Regex("""://[^/@\s]+@"""), "://")

/**
 * Returns [e] with [redactUrlUserinfo] applied to its message, preserving [e] as the cause, or
 * [e] itself unchanged when there's nothing to redact (including a null message).
 */
@PublishedApi
internal fun redactedTransportException(e: Exception): Exception {
    val message = e.message ?: return e
    val redacted = redactUrlUserinfo(message)
    return if (redacted == message) e else RuntimeException(redacted, e)
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

/** `ls-remote` has no default JGit timeout — without one, a black-holed host leaves the "Test
 * connection" UI spinning forever. */
private const val TEST_REMOTE_TIMEOUT_SECONDS = 15

/**
 * Shared body of [AndroidGitRepository.testRemote]/[JvmGitRepository.testRemote] — checks that a
 * remote URL is reachable and [auth] is valid via `ls-remote`, without touching the local
 * filesystem or creating a clone. Was previously byte-identical on both platforms aside from
 * [configureAuth], which applies the platform's own transport auth configurer (JSch on Android,
 * Apache MINA sshd on JVM) for [GitAuth.SshKey] — HTTPS/no-auth is handled identically by both via
 * [configureHttpsOrNoAuth].
 */
suspend fun testRemoteViaLsRemote(
    url: String,
    auth: GitAuth,
    configureAuth: (TransportCommand<*, *>, GitAuth, String?) -> Unit,
): Either<DomainError.GitError, Unit> =
    runGitTransportOp(
        onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
        onFailed = { e -> DomainError.GitError.FetchFailed(e.message ?: "Connection test failed") },
    ) {
        val preResolvedToken: String? = if (auth is GitAuth.HttpsToken) auth.tokenProvider() else null
        Git.lsRemoteRepository()
            .setRemote(url)
            .setTimeout(TEST_REMOTE_TIMEOUT_SECONDS)
            .also { configureAuth(it, auth, preResolvedToken) }
            .call()
        Unit.right()
    }

/**
 * Configures [cmd]'s credentials provider for [config]'s HTTPS_TOKEN/GITHUB_OAUTH/NONE auth
 * types — was byte-identical on [AndroidGitAuthConfigurer.configureTransport] and
 * [JvmGitRepositoryAuth.configureTransport]; only SSH_KEY legitimately differs (JSch on Android
 * vs. Apache MINA sshd on JVM), so callers handle it via [onSshKey], which receives the resolved
 * passphrase (or null — [GitConfig.sshKeyPassphraseKey] is optional).
 */
fun configureTransportAuth(
    cmd: TransportCommand<*, *>,
    config: GitConfig,
    credentialAccess: CredentialAccess,
    onSshKey: (passphrase: String?) -> Unit,
) {
    when (config.authType) {
        GitAuthType.HTTPS_TOKEN -> {
            val token = config.httpsTokenKey?.let { credentialAccess.retrieve(it) } ?: return
            cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider("", token))
        }
        GitAuthType.SSH_KEY -> {
            val passphrase = config.sshKeyPassphraseKey?.let { credentialAccess.retrieve(it) }
            onSshKey(passphrase)
        }
        GitAuthType.GITHUB_OAUTH -> {
            val token = config.oauthTokenKey?.let { credentialAccess.retrieve(it) } ?: return
            cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider("x-oauth-basic", token))
        }
        GitAuthType.NONE -> {}
    }
}
