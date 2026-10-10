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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
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
 * Best-effort semantic tag for [GitTransportRetryState.NonRetryableFailure.reason]
 * (git-sync-resilience Story 4.1.3's AC7) — distinguishes a repo-not-found
 * ([NoRemoteRepositoryException]) failure from every other [GitFailureClass.Permanent] shape (an
 * auth failure or an unrecognized one, both bucketed the same way [classifyGitFailure] already
 * fail-closes them), so `GitSetupStep5TestAndSave.kt` can pick between two authored copies
 * ("Authentication failed…" vs. "Repository not found…") without ever rendering `e.message`.
 */
fun permanentFailureReasonTag(e: Exception): String {
    var cause: Throwable? = e
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_CHAIN_DEPTH) {
        if (cause is NoRemoteRepositoryException) return NonRetryableReason.NOT_FOUND
        cause = cause.cause
        depth++
    }
    // Only a TransportException is auth-shaped (matches runGitTransportOp's routing); anything
    // else (full disk IOException, JGitInternalException, ...) must not render auth copy.
    return if (e is TransportException) NonRetryableReason.AUTH else NonRetryableReason.OTHER
}

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
 * immediately (routed to [onAuthFailed] for a [TransportException], [onFailed] otherwise) and [GitFailureClass.Cancelled] rethrows as
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
 *
 * [maxElapsed] (Story 3.1.4, Task 3.1.4a) bounds total retry wall-clock time independently of
 * [schedule]'s own attempt-count termination — well under Android 14's ~6-hour `dataSync`
 * aggregate cap, so a sustained outage doesn't hold [GitCloneWorker]'s foreground
 * notification/wakelock alive indefinitely. Checked before *taking* each retry delay (i.e. a
 * retry whose delay would push cumulative elapsed time past [maxElapsed] is refused, not one that
 * merely started before the deadline) — tracked as the sum of every [Schedule.Decision.Continue.delay]
 * actually awaited by this loop, not a wall-clock timestamp, so it advances correctly under
 * `kotlinx-coroutines-test`'s virtual time in tests.
 *
 * [onStateChange] (Story 4.1.2, Task 4.1.2b) is produced *inside* this loop, driven by [onAttempt]'s
 * same attempt/retry/terminal transitions, rather than derived separately at each call site — this
 * keeps `AndroidGitRepository`/`JvmGitRepository`'s call sites simple (they only need to supply
 * [currentProgress]) and guarantees Step 5 and the notification (both downstream consumers of the
 * same [GitTransportRetryState] stream) can never observe a transition this loop itself didn't go
 * through. Emitted once per discrete transition (attempt start / retry scheduled / terminal
 * outcome), never per raw progress tick — [currentProgress] is polled only at those points, which
 * is also where `design/ux.md`'s "announce at transitions, not every tick" debouncing naturally
 * lives (Task 4.1.3b verifies no additional per-tick noise is introduced downstream). Defaults to a
 * no-op so every pre-Story-4.1.2 caller (including every existing `businessTest` in
 * `GitTransportRetryTest.kt`) keeps compiling and passing unchanged.
 */
suspend fun <T, D> runGitTransportOpWithRetry(
    schedule: Schedule<Throwable, D>,
    onAttempt: (attempt: Int, failure: GitFailureClass?) -> Unit = { _, _ -> },
    onStateChange: (GitTransportRetryState) -> Unit = {},
    beforeRetry: suspend () -> Unit,
    onAuthFailed: (Exception) -> DomainError.GitError,
    onFailed: (Exception) -> DomainError.GitError,
    onExhausted: (attempts: Int, last: DomainError.GitError) -> DomainError.GitError,
    maxElapsed: Duration = DEFAULT_GIT_TRANSPORT_RETRY_MAX_ELAPSED,
    maxAttempts: Int = GIT_TRANSPORT_RETRY_MAX_ATTEMPTS,
    currentProgress: () -> CloneProgress? = { null },
    op: suspend () -> Either<DomainError.GitError, T>,
): Either<DomainError.GitError, T> {
    var step = schedule.step
    var attempt = 1
    var retries = 0
    var elapsed = Duration.ZERO
    fun giveUp(lastError: DomainError.GitError): Either<DomainError.GitError, T> {
        onStateChange(GitTransportRetryState.Exhausted(lastError.message, maxAttempts = maxAttempts))
        return onExhausted(retries, lastError).left()
    }
    onStateChange(GitTransportRetryState.Attempting(currentProgress() ?: CloneProgress("", 0, 0)))
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
                    onStateChange(GitTransportRetryState.NonRetryableFailure(permanentFailureReasonTag(e)))
                    val redacted = redactedTransportException(e)
                    return if (e is TransportException) onAuthFailed(redacted).left() else onFailed(redacted).left()
                }
                GitFailureClass.Transient -> {
                    onAttempt(attempt, failureClass)
                    val lastError = onFailed(redactedTransportException(e))
                    when (val decision = step(e)) {
                        is Schedule.Decision.Done -> return giveUp(lastError)
                        is Schedule.Decision.Continue -> if (elapsed + decision.delay > maxElapsed) {
                            return giveUp(lastError)
                        } else {
                            retries++
                            onStateChange(GitTransportRetryState.Retrying(attempt = retries, max = maxAttempts, progress = currentProgress()))
                            beforeRetry()
                            delay(decision.delay)
                            elapsed += decision.delay
                            step = decision.step
                            attempt++
                        }
                    }
                }
            }
        }
    }
}

/** Default [runGitTransportOpWithRetry] `maxElapsed` (Story 3.1.4) — comfortably under Android
 * 14's ~6-hour `dataSync` foreground-service aggregate cap. */
val DEFAULT_GIT_TRANSPORT_RETRY_MAX_ELAPSED: Duration = 10.minutes

/**
 * Retry budget [RetryPolicies.gitTransportTransient] grants (its own kdoc: "five retries (~1s,
 * 2s, 4s, 8s, 16s)" — matching `DomainError.GitError.RetryExhausted(attempts=5,...)`, already
 * asserted by `GitTransportRetryTest.kt`'s pre-existing Story 1.2.2 tests). Used only to populate
 * [GitTransportRetryState.Retrying]'s `max` field for Step 5/the notification — the generic
 * `Schedule<Throwable, D>` type has no way to report its own attempt count, so this mirrors the one
 * schedule actually used for clone/fetch/push rather than being derived from `schedule` itself.
 */
const val GIT_TRANSPORT_RETRY_MAX_ATTEMPTS = 5

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
 * Clone's `beforeRetry` cleanup (Story 2.1.3): deletes [dir]'s contents, not [dir] itself, so a
 * retry after an interrupted first attempt doesn't hit JGit's "already exists and is not an empty
 * directory" guard. Re-transfers the bounded shallow pack from zero (ADR-001), not a partial-pack
 * splice. Only runs for an automatic retry, never a manual cancel — structural, via
 * [runGitTransportOpWithRetry]'s own `Cancelled`-skips-`beforeRetry` behavior.
 */
fun deleteDirectoryContentsForRetry(dir: File) {
    if (!dir.exists()) return
    dir.listFiles()?.forEach { it.deleteRecursively() }
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
 * Story 2.1.5's shallow-history/merge-base collision guard, shared by
 * [AndroidGitRepository.doMerge]/[JvmGitRepository.doMerge]: true when [repo]'s shallow history
 * makes a merge against [remoteRef] unsafe to attempt. A no-op (`false`) when [repo] has no
 * shallow boundary at all. Two distinct unsafe shapes (pre-mortem.md P1 #2 — JGit's own
 * documented caveat that its merge-base search "cannot be counted on to work as expected" near a
 * shallow boundary):
 *  1. **Absent** — no merge base found within the shallow history at all.
 *  2. **Wrong-but-present** — `RevWalk` *does* return a merge-base commit, but at least one of its
 *     parents is missing from the local object database and isn't itself a shallow root — exactly
 *     the shape JGit's caveat warns can surface a spurious ancestor instead of cleanly failing.
 *
 * The merge-base search itself can also throw [MissingObjectException] rather than cleanly
 * returning — the real best-common-ancestor algorithm ([RevFilter.MERGE_BASE]) continues walking
 * past a candidate to rule out a more recent one, which can dereference a missing parent before
 * ever returning from [RevWalk.next]. That is caught here and also treated as insufficient — the
 * plan's Task 2.1.5b explicitly names this as an acceptable alternative to the pre-return parent
 * check above, and both shapes must fail closed identically.
 */
fun isShallowHistoryInsufficientForMerge(repo: Repository, remoteRef: ObjectId): Boolean {
    val shallowCommits = repo.objectDatabase.shallowCommits
    if (shallowCommits.isEmpty()) return false
    val headId = repo.resolve("HEAD") ?: return false

    return try {
        RevWalk(repo).use { walk ->
            walk.revFilter = RevFilter.MERGE_BASE
            walk.markStart(walk.parseCommit(headId))
            walk.markStart(walk.parseCommit(remoteRef))
            val mergeBase: RevCommit = walk.next() ?: return true

            for (i in 0 until mergeBase.parentCount) {
                val parentId = mergeBase.getParent(i)
                if (!repo.objectDatabase.has(parentId) && parentId !in shallowCommits) {
                    return true
                }
            }
        }
        false
    } catch (e: CancellationException) {
        throw e
    } catch (_: org.eclipse.jgit.errors.MissingObjectException) {
        true
    }
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

/**
 * Ref-divergence guard before [org.eclipse.jgit.api.FetchCommand.setUnshallow] (Story 2.1.4,
 * Task 2.1.4d): compares [config]'s remote branch as advertised right now (a cheap `ls-remote`,
 * reusing [testRemoteViaLsRemote]'s shape) against the locally-known remote-tracking ref (as of
 * the last fetch). Conservative by construction — any mismatch, including "can't tell" (a missing
 * local ref, no matching advertised ref, or the `ls-remote` call itself failing), is treated as
 * diverged, since this app has no way to distinguish "safe to widen" from "already resolved
 * cleanly" without doing the full unshallow anyway. Never throws.
 */
fun hasRemoteDivergedSinceShallowClone(
    git: Git,
    config: GitConfig,
    configureAuth: (TransportCommand<*, *>) -> Unit,
): Boolean = try {
    val locallyKnownOid = git.repository.exactRef("refs/remotes/${config.remoteName}/${config.remoteBranch}")?.objectId
    val advertisedRefs = git.lsRemote()
        .setRemote(config.remoteName)
        .setHeads(true)
        .setTimeout(TEST_REMOTE_TIMEOUT_SECONDS)
        .also { configureAuth(it) }
        .call()
    val advertisedOid = advertisedRefs
        .firstOrNull { it.name == "refs/heads/${config.remoteBranch}" }
        ?.objectId
    locallyKnownOid == null || advertisedOid == null || locallyKnownOid != advertisedOid
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    true
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
