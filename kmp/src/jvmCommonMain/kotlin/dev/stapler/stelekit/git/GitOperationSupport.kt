// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.resilience.Schedule
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.security.CredentialAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.EOFException
import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
 * Classifies a caught JGit/transport [e] for retry gating, by walking its cause chain (bounded to
 * guard against a cyclic `cause` reference) rather than switching on `e`'s own type alone —
 * [runGitTransportOp] previously routed every [TransportException] to `onAuthFailed`, which
 * misclassified a plain [SocketException] (a transient network drop) as an authentication
 * failure. Unrecognized failure shapes (e.g. `InvalidRemoteException`, `RefNotAdvertisedException`)
 * fail closed as [GitFailureClass.Permanent] — a permanent failure must never be misclassified as
 * retryable, the sharpest regression risk for the retry logic this feeds (Story 1.2.2).
 */
fun classifyGitFailure(e: Exception): GitFailureClass {
    var cause: Throwable? = e
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_CHAIN_DEPTH) {
        when (cause) {
            is CanceledException -> return GitFailureClass.Cancelled
            is SocketException, is SocketTimeoutException, is UnknownHostException, is EOFException ->
                return GitFailureClass.Transient
            is NoRemoteRepositoryException -> return GitFailureClass.Permanent
            else -> {}
        }
        cause = cause.cause
        depth++
    }
    // An auth-shaped message (HTTP 401/403/"not authorized") and every other unrecognized shape
    // both fail closed here — the fail-closed default already covers the auth-message case, so
    // no separate branch is needed.
    return GitFailureClass.Permanent
}

/** Bounds [classifyGitFailure]'s cause-chain walk against a cyclic `cause` reference. */
private const val MAX_CAUSE_CHAIN_DEPTH = 10

/**
 * Sealed classification of a caught git transport failure, produced by [classifyGitFailure] and
 * used to gate [runGitTransportOp]'s `onAuthFailed`/`onFailed` routing and (Story 1.2.2) the
 * retry loop's transient-only gate.
 */
sealed interface GitFailureClass {
    /** A network-level hiccup (dropped socket, DNS blip, read timeout) — safe to retry. */
    data object Transient : GitFailureClass

    /** An auth/not-found/unrecognized failure — never retried; retrying would just repeat it. */
    data object Permanent : GitFailureClass

    /** The operation was cancelled (e.g. user tapped Cancel) — never retried. */
    data object Cancelled : GitFailureClass
}

/**
 * Like [runGitOp], but additionally maps a JGit [TransportException] — thrown for remote
 * auth/connectivity failures — to [onAuthFailed] instead of the generic [onFailed], gated by
 * [classifyGitFailure] so only a [GitFailureClass.Permanent]-classified exception (auth failure,
 * repo-not-found, or an unrecognized shape) routes to `onAuthFailed`; a
 * [GitFailureClass.Transient] `TransportException` (e.g. a bare `SocketException`) routes to
 * `onFailed` instead — this is the fix for the pre-existing misclassification bug (a dropped
 * socket reported to the user as an authentication failure). Used by every clone/fetch/push/
 * testRemote call, the only operations that touch a remote transport. Every caught exception's
 * message is run through [redactUrlUserinfo] before either callback sees it, so a PAT pasted as
 * `https://ghp_xxx@host/...` (userinfo, not password — JGit's own redaction only strips the
 * password component) never reaches a [DomainError.GitError] and from there the UI/logs.
 */
inline fun <T> runGitTransportOp(
    onAuthFailed: (Exception) -> DomainError.GitError,
    onFailed: (Exception) -> DomainError.GitError,
    op: () -> Either<DomainError.GitError, T>,
): Either<DomainError.GitError, T> =
    try {
        op()
    } catch (e: TransportException) {
        if (classifyGitFailure(e) == GitFailureClass.Permanent) {
            onAuthFailed(redactedTransportException(e)).left()
        } else {
            onFailed(redactedTransportException(e)).left()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailed(redactedTransportException(e)).left()
    }

/**
 * Like [runGitTransportOp], but wraps [op] in a bounded, jittered exponential-backoff retry loop
 * (ADR-002: the single retry owner for git transport operations) — only a
 * [GitFailureClass.Transient]-classified failure is retried; [GitFailureClass.Permanent] fails
 * immediately (routed to [onAuthFailed]) and [GitFailureClass.Cancelled] rethrows as
 * [CancellationException] without consuming any retry budget. [beforeRetry] runs once before each
 * retry attempt (not before the first) — the pre-retry stale-lock cleanup (Task 1.2.2c) and,
 * later, clone's directory cleanup (Story 2.1.3).
 *
 * [schedule] is driven by hand (via its own [Schedule.step]) rather than the library's
 * `retryEither`/`retry` helpers: those gate purely on the schedule's own `Input`/`Output`, with no
 * way to fail fast on a non-retryable classification without first consuming a step — here a
 * [GitFailureClass.Permanent]/[GitFailureClass.Cancelled] failure must never touch the schedule at
 * all. [D] (the schedule's `Output` type) is intentionally unconstrained beyond `Schedule<Throwable,
 * D>`: only [Schedule.Decision.Continue.delay] (always a [kotlin.time.Duration], independent of
 * `Output`) is used to drive `delay()`, so [RetryPolicies.gitTransportTransient]
 * (`Schedule<Throwable, Duration>`) and its zero-delay test variant
 * [RetryPolicies.gitTransportTransientImmediate] (`Schedule<Throwable, Long>`) are both accepted.
 *
 * [onExhausted]'s `attempts` count is the number of *retries* granted by [schedule] before it
 * signalled `Done` (i.e. total op invocations minus one) — matching Story 1.2.2's
 * `RetryExhausted(attempts = 5, ...)` acceptance criterion for [RetryPolicies.gitTransportTransient]
 * (5 retries at ~1s/2s/4s/8s/16s, 6 total op invocations).
 */
suspend fun <T, D> runGitTransportOpWithRetry(
    schedule: Schedule<Throwable, D>,
    onAttempt: (attempt: Int, failure: GitFailureClass?) -> Unit = { _, _ -> },
    beforeRetry: suspend () -> Unit,
    onAuthFailed: (Exception) -> DomainError.GitError,
    onFailed: (Exception) -> DomainError.GitError,
    onExhausted: (attempts: Int, last: DomainError.GitError) -> DomainError.GitError,
    op: suspend () -> Either<DomainError.GitError, T>,
): Either<DomainError.GitError, T> {
    var step = schedule.step
    var attempt = 1
    var retries = 0
    while (true) {
        try {
            val result = op()
            onAttempt(attempt, null)
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (val failureClass = classifyGitFailure(e)) {
                GitFailureClass.Cancelled -> {
                    onAttempt(attempt, failureClass)
                    throw CancellationException(e.message, e)
                }
                GitFailureClass.Permanent -> {
                    onAttempt(attempt, failureClass)
                    return onAuthFailed(redactedTransportException(e)).left()
                }
                GitFailureClass.Transient -> {
                    onAttempt(attempt, failureClass)
                    val lastError = onFailed(redactedTransportException(e))
                    when (val decision = step(e)) {
                        is Schedule.Decision.Done -> return onExhausted(retries, lastError).left()
                        is Schedule.Decision.Continue -> {
                            retries++
                            beforeRetry()
                            delay(decision.delay)
                            step = decision.step
                            attempt++
                        }
                    }
                }
            }
        }
    }
}

/**
 * Deletes [lockFile] only if it predates [retryLoopStartMs] — a lock created by this same retry
 * loop's own just-failed attempt (which may still be releasing it as the process unwinds) must
 * never be treated as stale by a fixed age threshold alone, unlike [AndroidGitRepository]/
 * [JvmGitRepository]'s standalone `deleteStaleLockFile` (60s age check for a user-initiated
 * retry). Only a lock that already existed before this retry loop even started is safe to remove.
 * Best-effort and silent: a `beforeRetry` hook must never fail the retry it's guarding.
 */
fun deleteLockFileIfStaleForRetry(lockFile: File, retryLoopStartMs: Long) {
    if (lockFile.exists() && lockFile.lastModified() < retryLoopStartMs) {
        lockFile.delete()
    }
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
 * Applied via `.setTimeout(...)` to every clone/fetch/push [TransportCommand] on both platforms
 * (`AndroidGitRepository`, `JvmGitRepository`) — without it, a stalled connection hangs until the
 * OS tears the socket down instead of failing fast enough to reach the retry logic (Story 1.2.2).
 * Deliberately much larger than [TEST_REMOTE_TIMEOUT_SECONDS]: a shallow clone still needs
 * headroom to complete on a slow-but-healthy connection, unlike a lightweight `ls-remote` check.
 */
const val GIT_TRANSPORT_TIMEOUT_SECONDS = 300

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
