// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.git.model.DEFAULT_CLONE_DEPTH
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.platform.security.CredentialStore
import dev.stapler.stelekit.resilience.RetryPolicies
import kotlin.concurrent.Volatile
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.RefSpec
import java.io.File

/**
 * JVM (Desktop) implementation of GitRepository using JGit 7.x. All I/O runs on
 * PlatformDispatcher.IO. See [JvmGitRepositoryAuth] for transport auth and
 * [JvmGitConflictSupport] for conflict derivation — split out to keep this class under the
 * file-size guideline.
 */
class JvmGitRepository(
    credentialAccess: CredentialAccess = CredentialStore(),
) : GitRepository {

    private val logger = Logger("JvmGitRepository")

    /**
     * Active credential store — swapped to [VaultCredentialStore] when paranoid mode is on
     * and the vault is unlocked, or back to [CredentialStore] (PBKDF2) when locked.
     * @Volatile ensures visibility across the IO dispatcher thread pool.
     */
    @Volatile var credentialAccess: CredentialAccess = credentialAccess
        internal set

    private val authConfigurer = JvmGitRepositoryAuth({ this.credentialAccess }, logger)

    override fun setCredentialAccess(access: CredentialAccess) { credentialAccess = access }

    override suspend fun isGitRepo(path: String): Boolean = withContext(PlatformDispatcher.IO) {
        runGitOpOrFalse {
            val gitDir = File(path, ".git")
            if (gitDir.exists()) return@runGitOpOrFalse true
            // Also handle bare repos
            val builder = FileRepositoryBuilder()
            builder.setMustExist(true)
            builder.findGitDir(File(path))
            builder.gitDir != null
        }
    }

    override suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.CloneFailed("init failed: ${e.message}") }) {
                // GitConfig.remoteBranch defaults to "main" — JGit's own default initial branch
                // is "master" regardless of the host's `init.defaultBranch` git config, so a
                // freshly-init'd (non-cloned) repo would otherwise never match the branch name
                // fetch()/merge() look for.
                Git.init().setDirectory(File(repoRoot)).setInitialBranch("main").call().close()
                Unit.right()
            }
        }

    override suspend fun clone(
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (CloneProgress) -> Unit,
        onStateChange: (GitTransportRetryState) -> Unit,
    ): Either<DomainError.GitError, Unit> = withContext(PlatformDispatcher.IO) {
        // Task 4.1.1c: see AndroidGitRepository.clone()'s identical rationale.
        val progressTracker = CloneProgressTracker()
        runGitTransportOpWithRetry(
            schedule = RetryPolicies.gitTransportTransient,
            onStateChange = onStateChange,
            currentProgress = { progressTracker.current },
            // Story 2.1.3: a first attempt whose fetch succeeded but was interrupted before
            // returning leaves a partially-populated, non-empty target directory — JGit's
            // CloneCommand refuses to clone into a non-empty directory, so a retry would
            // otherwise fail deterministically on attempt 2 without ever reaching the network
            // again. Wipe the directory's contents (not the directory itself) before retrying;
            // per ADR-001 this re-transfers the bounded shallow pack rather than splicing a
            // partial one. Manual cancel never reaches this hook (runGitTransportOpWithRetry
            // only runs beforeRetry on a Transient-classified automatic retry).
            beforeRetry = { deleteDirectoryContentsForRetry(File(localPath)) },
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
            onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
        ) {
            // Resolve suspend credentials before entering JGit's synchronous territory
            val preResolvedToken: String? = if (auth is GitAuth.HttpsToken) auth.tokenProvider() else null
            val job = coroutineContext[kotlinx.coroutines.Job]

            val cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(File(localPath))
                // Story 2.1.1/ADR-001: shallow by default — bounds a retry's cost to a small,
                // fixed-size unit instead of the full repository history.
                .setDepth(DEFAULT_CLONE_DEPTH)
                .setTimeout(GIT_TRANSPORT_TIMEOUT_SECONDS)
                .setProgressMonitor(object : org.eclipse.jgit.lib.ProgressMonitor {
                    override fun start(totalTasks: Int) {}
                    override fun beginTask(title: String, totalWork: Int) {
                        onProgress(progressTracker.onBeginTask(title, totalWork))
                    }
                    override fun update(completed: Int) {
                        onProgress(progressTracker.onUpdate(completed))
                    }
                    override fun endTask() {}
                    override fun isCancelled() = job?.isCancelled == true
                    override fun showDuration(enabled: Boolean) {}
                })

            authConfigurer.configureAuth(cmd, auth, preResolvedToken)
            cmd.call().close()
            Unit.right()
        }
    }

    override suspend fun testRemote(url: String, auth: GitAuth): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            testRemoteViaLsRemote(url, auth, authConfigurer::configureAuth)
        }

    override suspend fun detectDefaultBranch(url: String, auth: GitAuth): DefaultBranchDetection =
        withContext(PlatformDispatcher.IO) {
            detectDefaultBranchViaLsRemote(url, auth, authConfigurer::configureAuth)
        }

    override suspend fun unshallow(config: GitConfig): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            val diverged = openGit(config.repoRoot).use { git ->
                hasRemoteDivergedSinceShallowClone(git, config) { authConfigurer.configureTransport(it, config) }
            }
            if (diverged) {
                return@withContext DomainError.GitError.FetchFailed(
                    "Remote has diverged since the shallow clone — full history unavailable via automatic widen"
                ).left()
            }

            val retryLoopStartMs = System.currentTimeMillis()
            runGitTransportOpWithRetry(
                schedule = RetryPolicies.gitTransportTransient,
                beforeRetry = { deleteLockFileIfStaleForRetry(indexLockFile(config), retryLoopStartMs) },
                onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
                onFailed = { e -> DomainError.GitError.FetchFailed(e.message ?: "Unshallow failed") },
                onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
            ) {
                openGit(config.repoRoot).use { git -> doUnshallow(git, config) }
            }
        }

    private fun doUnshallow(git: Git, config: GitConfig): Either<DomainError.GitError, Unit> {
        git.fetch()
            .setRemote(config.remoteName)
            .setUnshallow(true)
            .setTimeout(GIT_TRANSPORT_TIMEOUT_SECONDS)
            .also { authConfigurer.configureTransport(it, config) }
            .call()
        return Unit.right()
    }

    override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> =
        withContext(PlatformDispatcher.IO) {
            val retryLoopStartMs = System.currentTimeMillis()
            runGitTransportOpWithRetry(
                schedule = RetryPolicies.gitTransportTransient,
                beforeRetry = { deleteLockFileIfStaleForRetry(indexLockFile(config), retryLoopStartMs) },
                onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
                onFailed = { e -> DomainError.GitError.FetchFailed(e.message ?: "Fetch failed") },
                onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
            ) {
                openGit(config.repoRoot).use { git -> doFetch(git, config) }
            }
        }

    private fun doFetch(git: Git, config: GitConfig): Either<DomainError.GitError, FetchResult> {
        val repo = git.repository
        val headBefore = repo.resolve("HEAD")

        val fetched = git.fetch()
            .setRemote(config.remoteName)
            .setRemoveDeletedRefs(true)
            .setTimeout(GIT_TRANSPORT_TIMEOUT_SECONDS)
            .also { authConfigurer.configureTransport(it, config) }
            .call()

        val remoteTip = resolveRemoteTrackingRef(
            repo, config.remoteName, config.remoteBranch, branchShortNames(fetched.advertisedRefs),
        ).getOrElse { return it.left() }
        val hasChanges = isRemoteAhead(repo, headBefore, remoteTip)
        val remoteCommitCount = if (hasChanges && headBefore != null) {
            countRemoteCommitsBestEffort(git, headBefore, remoteTip)
        } else {
            0
        }

        return FetchResult(hasRemoteChanges = hasChanges, remoteCommitCount = remoteCommitCount).right()
    }

    override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.FetchFailed("Status failed: ${e.message}") }) {
                openGit(config.repoRoot).use { git -> buildGitStatus(git, config) }
            }
        }

    private fun buildGitStatus(git: Git, config: GitConfig): Either<DomainError.GitError, GitStatus> {
        val statusCommand = git.status()
        if (!config.wikiSubdir.isNullOrEmpty()) statusCommand.addPath(config.wikiSubdir)
        val statusResult = statusCommand.call()

        return GitStatus(
            hasLocalChanges = !statusResult.isClean,
            untrackedFiles = statusResult.untracked.toList(),
            modifiedFiles = (statusResult.modified + statusResult.changed).toList(),
        ).right()
    }

    override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.CommitFailed("Stage failed: ${e.message}") }) {
                openGit(config.repoRoot).use { git -> stageWikiSubdir(git, config) }
            }
        }

    private fun stageWikiSubdir(git: Git, config: GitConfig): Either<DomainError.GitError, Unit> {
        val pattern = if (config.wikiSubdir.isNullOrEmpty()) "." else "${config.wikiSubdir}/"
        git.add().addFilepattern(pattern).call()
        // Also stage deletions
        git.add().setUpdate(true).addFilepattern(pattern).call()
        return Unit.right()
    }

    override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.CommitFailed(e.message ?: "Commit failed") }) {
                openGit(config.repoRoot).use { git ->
                    val commit = git.commit()
                        .setMessage(message)
                        .call()
                    commit.name.right()
                }
            }
        }

    override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.FetchFailed("Merge failed: ${e.message}") }) {
                openGit(config.repoRoot).use { git -> doMerge(git, config) }
            }
        }

    private fun doMerge(git: Git, config: GitConfig): Either<DomainError.GitError, MergeResult> {
        val repo = git.repository
        val remoteRef = resolveRemoteTrackingRef(repo, config.remoteName, config.remoteBranch)
            .getOrElse { return it.left() }
        val headBefore = repo.resolve("HEAD")

        // Story 2.1.5: fail closed rather than let JGit's shallow-history merge-base limitation
        // silently produce a degraded/wrong merge.
        if (isShallowHistoryInsufficientForMerge(repo, remoteRef)) {
            return DomainError.GitError.ShallowHistoryInsufficient.left()
        }

        val mergeResult = git.merge()
            .include(remoteRef)
            .setStrategy(MergeStrategy.RECURSIVE)
            .setFastForward(MergeCommand.FastForwardMode.NO_FF)
            .call()

        val hasConflicts = mergeResult.mergeStatus == org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING
        val conflictFiles = JvmGitConflictSupport.buildConflictFiles(repo, mergeResult, config)

        return MergeResult(
            hasConflicts = hasConflicts,
            conflicts = conflictFiles,
            changedFiles = wikiSubdirFilteredChangedFiles(repo, config),
            mergedCommitCount = countMergedCommits(repo, headBefore, repo.resolve("HEAD"), remoteRef),
        ).right()
    }

    /** Absolute paths, under `config.repoRoot`, changed between HEAD's pre- and post-merge parent. */
    private fun wikiSubdirFilteredChangedFiles(repo: Repository, config: GitConfig): List<String> {
        val changedFiles = computeChangedGitRelativePaths(repo).map { "${config.repoRoot}/$it" }
        return if (!config.wikiSubdir.isNullOrEmpty()) {
            changedFiles.filter { it.startsWith("${config.repoRoot}/${config.wikiSubdir}/") }
        } else {
            changedFiles
        }
    }

    override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            val retryLoopStartMs = System.currentTimeMillis()
            runGitTransportOpWithRetry(
                schedule = RetryPolicies.gitTransportTransient,
                beforeRetry = { deleteLockFileIfStaleForRetry(indexLockFile(config), retryLoopStartMs) },
                onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Push authentication failed") },
                onFailed = { e -> DomainError.GitError.PushFailed(e.message ?: "Push failed") },
                onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
            ) {
                openGit(config.repoRoot).use { git -> doPush(git, config) }
            }
        }

    private fun doPush(git: Git, config: GitConfig): Either<DomainError.GitError, Unit> {
        val localBranch = git.repository.fullBranch
            ?.takeIf { it.startsWith(Constants.R_HEADS) }
            ?: return DomainError.GitError.DetachedHead(config.repoRoot).left()
        val pushResults = git.push()
            .setRemote(config.remoteName)
            .setRefSpecs(RefSpec("$localBranch:${Constants.R_HEADS}${config.remoteBranch}"))
            .setTimeout(GIT_TRANSPORT_TIMEOUT_SECONDS)
            .also { authConfigurer.configureTransport(it, config) }
            .call()

        val rejected = findRejectedUpdate(pushResults)
        if (rejected != null) {
            return DomainError.GitError.PushFailed("Push rejected: ${rejected.status}").left()
        }
        return Unit.right()
    }

    /** First remote ref update JGit rejected as a non-fast-forward or other conflict, if any. */
    private fun findRejectedUpdate(
        pushResults: Iterable<org.eclipse.jgit.transport.PushResult>,
    ): org.eclipse.jgit.transport.RemoteRefUpdate? {
        for (result in pushResults) {
            for (update in result.remoteUpdates) {
                if (update.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ||
                    update.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_OTHER_REASON) {
                    return update
                }
            }
        }
        return null
    }

    override suspend fun log(config: GitConfig, maxCount: Int): Either<DomainError.GitError, List<GitCommit>> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.FetchFailed("Log failed: ${e.message}") }) {
                openGit(config.repoRoot).use { git -> buildGitLog(git, maxCount) }
            }
        }

    private fun buildGitLog(git: Git, maxCount: Int): Either<DomainError.GitError, List<GitCommit>> {
        val commits = git.log().setMaxCount(maxCount).call().map { revCommit -> toGitCommit(revCommit) }
        return commits.right()
    }

    private fun toGitCommit(revCommit: RevCommit): GitCommit = GitCommit(
        sha = revCommit.name,
        shortMessage = revCommit.shortMessage,
        authorName = revCommit.authorIdent.name,
        timestamp = revCommit.authorIdent.whenAsInstant.toEpochMilli(),
    )

    override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.CommitFailed("Abort merge failed: ${e.message}") }) {
                openGit(config.repoRoot).use { git -> doAbortMerge(git) }
            }
        }

    /**
     * ResetType.HARD, not MERGE: JGit 7.3.0's `ResetCommand` never implements MERGE/KEEP at all —
     * both throw `UnsupportedOperationException` unconditionally (verified by disassembling
     * `ResetCommand.class`: its `ResetType` switch routes MERGE and KEEP to the same throw), a
     * real pre-existing JGit limitation. HARD's merge-state cleanup (MERGE_HEAD/MERGE_MSG
     * removal, RepositoryState MERGING -> SAFE) is unconditional on any type other than SOFT, so
     * it correctly aborts the merge — empirically confirmed against a real conflict. See
     * [AndroidGitRepository]'s `doAbortMerge` for the full rationale; this platform has no
     * shadow-worktree reconciliation step to add, since Desktop reads/writes the real working
     * tree directly.
     */
    private fun doAbortMerge(git: Git): Either<DomainError.GitError, Unit> {
        git.reset()
            .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
            .call()
        return Unit.right()
    }

    override suspend fun checkoutFile(
        config: GitConfig,
        filePath: String,
        side: MergeSide,
    ): Either<DomainError.GitError, Unit> = withContext(PlatformDispatcher.IO) {
        runGitOp({ e -> DomainError.GitError.CommitFailed("Checkout file failed: ${e.message}") }) {
            openGit(config.repoRoot).use { git -> doCheckoutFile(git, config, filePath, side) }
        }
    }

    private fun doCheckoutFile(
        git: Git,
        config: GitConfig,
        filePath: String,
        side: MergeSide,
    ): Either<DomainError.GitError, Unit> {
        val stage = when (side) {
            MergeSide.LOCAL -> org.eclipse.jgit.api.CheckoutCommand.Stage.OURS
            MergeSide.REMOTE -> org.eclipse.jgit.api.CheckoutCommand.Stage.THEIRS
        }
        git.checkout()
            .setStage(stage)
            .addPath(filePath.removePrefix("${config.repoRoot}/"))
            .call()
        return Unit.right()
    }

    override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.CommitFailed("Mark resolved failed: ${e.message}") }) {
                openGit(config.repoRoot).use { git ->
                    val relativePath = filePath.removePrefix("${config.repoRoot}/")
                    git.add().addFilepattern(relativePath).call()
                    Unit.right()
                }
            }
        }

    override suspend fun describeRefs(config: GitConfig): String =
        withContext(PlatformDispatcher.IO) {
            try {
                openGit(config.repoRoot).use { git ->
                    describeGitRefs(git, config) { authConfigurer.configureTransport(it, config) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "<failed to open repo: ${e::class.simpleName}: ${redactSecrets(e.message.orEmpty())}>"
            }
        }

    override suspend fun hasDetachedHead(config: GitConfig): Boolean =
        withContext(PlatformDispatcher.IO) {
            runGitOpOrFalse {
                openGit(config.repoRoot).use { git ->
                    val fullBranch = git.repository.fullBranch ?: return@use false
                    !fullBranch.startsWith("refs/heads/")
                }
            }
        }

    override suspend fun removeStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ _ -> DomainError.GitError.StaleLockFile("${config.repoRoot}/.git/index.lock") }) {
                deleteStaleLockFile(config)
            }
        }

    /** `.git/index.lock` path for [config]'s repo — shared by [deleteStaleLockFile] (user-initiated,
     * fixed 60s age) and [runGitTransportOpWithRetry]'s `beforeRetry` cleanup (retry-loop-relative
     * age, Task 1.2.2c). */
    private fun indexLockFile(config: GitConfig): File = File(config.repoRoot, ".git/index.lock")

    private fun deleteStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit> {
        val lockFile = indexLockFile(config)
        if (!lockFile.exists()) return Unit.right()

        val ageMs = System.currentTimeMillis() - lockFile.lastModified()
        return if (ageMs > 60_000L) {
            if (lockFile.delete()) Unit.right() else DomainError.GitError.StaleLockFile(lockFile.absolutePath).left()
        } else {
            DomainError.GitError.StaleLockFile(lockFile.absolutePath).left()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun openGit(repoRoot: String): Git = Git.open(File(repoRoot))
}
