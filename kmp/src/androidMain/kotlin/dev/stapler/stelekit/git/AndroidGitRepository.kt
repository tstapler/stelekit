// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevCommit
import java.io.File

/**
 * Android implementation of GitRepository using JGit 7.3.0 (matches Desktop) + mwiede/jsch fork of
 * jsch for SSH key format support (ED25519/ECDSA/OpenSSH). All I/O runs on PlatformDispatcher.IO.
 * See [AndroidGitShadowSupport] for shadow-worktree/SAF resolution, [AndroidGitAuthConfigurer]
 * for transport auth, and [AndroidGitMergeSupport] for conflict derivation/write-back — split
 * out to keep this class under the file-size guideline.
 *
 * @param context Used to derive the per-graph shadow-worktree storage root
 *                (`context.filesDir/graphs/$shadowKey/gitshadow`) for SAF-only users who lack
 *                `MANAGE_EXTERNAL_STORAGE` — see [GitShadowWorktree] and ADR-018.
 * @param sshKeyProvider Optional provider for SSH private key bytes, used for
 *                       configurable key loading (from user-configured path or Android storage).
 * @param fileSystem Used to list/read SAF content for shadow-worktree sync (`ensureFresh`).
 */
class AndroidGitRepository(
    private val context: Context,
    private val sshKeyProvider: (() -> ByteArray)? = null,
    credentialAccess: CredentialAccess = CredentialStore(),
    private val pathResolver: (String) -> String? = { null },
    // Public (not internal/private): `MainActivityGitRepositoryWiringTest` (`:androidApp` module,
    // a different Gradle module) must read this back to assert it's reference-identical to the
    // app's real fileSystem instance — Kotlin's `internal` doesn't extend across a
    // `project(":kmp")` dependency edge, so only `public` compiles there (plan.md Task 5.1.2a).
    val fileSystem: FileSystem,
) : GitRepository {

    private val logger = Logger("AndroidGitRepository")
    private val shadow = AndroidGitShadowSupport(context, pathResolver, fileSystem, logger)

    @Volatile var credentialAccess: CredentialAccess = credentialAccess
        internal set

    private val authConfigurer = AndroidGitAuthConfigurer(sshKeyProvider, { this.credentialAccess }, logger)
    private val mergeSupport = AndroidGitMergeSupport(fileSystem, logger, shadow::resolveForJGit)

    override fun setCredentialAccess(access: CredentialAccess) { credentialAccess = access }

    override suspend fun isGitRepo(path: String): Boolean = withContext(PlatformDispatcher.IO) {
        File(shadow.resolveForJGit(path), ".git").exists()
    }

    override suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.CloneFailed("init failed: ${e.message}") }) {
                val worktree = shadow.shadowWorktreeFor(repoRoot)
                shadow.insufficientShadowStorageError(worktree, repoRoot)?.let { return@runGitOp it.left() }
                // GitConfig.remoteBranch defaults to "main" — JGit's own default initial branch
                // is "master" regardless of the host's `init.defaultBranch` git config, so a
                // freshly-init'd (non-cloned) repo would otherwise never match the branch name
                // fetch()/merge() look for.
                Git.init().setDirectory(File(shadow.resolveForJGit(repoRoot))).setInitialBranch("main").call().use { git ->
                    shadow.syncShadowAfterInitOrClone(repoRoot, git)
                }
                Unit.right()
            }
        }

    override suspend fun clone(
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (String) -> Unit,
    ): Either<DomainError.GitError, Unit> = withContext(PlatformDispatcher.IO) {
        runGitTransportOp(
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
        ) {
            val worktree = shadow.shadowWorktreeFor(localPath)
            shadow.insufficientShadowStorageError(worktree, localPath)?.let { return@runGitTransportOp it.left() }
            // Resolve suspend credentials before entering JGit's synchronous territory
            val preResolvedToken: String? = if (auth is GitAuth.HttpsToken) auth.tokenProvider() else null
            val job = coroutineContext[kotlinx.coroutines.Job]

            val cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(File(shadow.resolveForJGit(localPath)))
                .setProgressMonitor(object : org.eclipse.jgit.lib.ProgressMonitor {
                    override fun start(totalTasks: Int) {}
                    override fun beginTask(title: String, totalWork: Int) { onProgress(title) }
                    override fun update(completed: Int) {}
                    override fun endTask() {}
                    override fun isCancelled() = job?.isCancelled == true
                    // showDuration added in JGit 7.x; Bazel resolves to 7.x on Android too
                    override fun showDuration(enabled: Boolean) {}
                })

            authConfigurer.configureAuth(cmd, auth, preResolvedToken)
            cmd.call().use { git ->
                shadow.syncShadowAfterInitOrClone(localPath, git)
            }
            logger.info("clone: done localPath=$localPath")
            Unit.right()
        }
    }

    override suspend fun testRemote(url: String, auth: GitAuth): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            testRemoteViaLsRemote(url, auth, authConfigurer::configureAuth)
        }

    override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> =
        withContext(PlatformDispatcher.IO) {
            runGitTransportOp(
                onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
                onFailed = { e -> DomainError.GitError.FetchFailed(e.message ?: "Fetch failed") },
            ) {
                openGitWithoutFreshnessCheck(config).use { git -> doFetch(git, config) }
            }
        }

    private fun doFetch(git: Git, config: GitConfig): Either<DomainError.GitError, FetchResult> {
        val repo = git.repository
        val headBefore = repo.resolve("HEAD")

        git.fetch()
            .setRemote(config.remoteName)
            .also { authConfigurer.configureTransport(it, config) }
            .call()

        val remoteRef = repo.resolve("${config.remoteName}/${config.remoteBranch}")
        val hasChanges = remoteRef != null && remoteRef != headBefore
        val remoteCommitCount = if (hasChanges && headBefore != null && remoteRef != null) {
            countRemoteCommitsBestEffort(git, headBefore, remoteRef)
        } else {
            0
        }

        return FetchResult(hasRemoteChanges = hasChanges, remoteCommitCount = remoteCommitCount).right()
    }

    override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.FetchFailed("Status failed: ${e.message}") }) {
                openGit(config).use { git -> buildGitStatus(git, config) }
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
                openGit(config).use { git -> stageWikiSubdir(git, config) }
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
                openGit(config).use { git ->
                    val commit = git.commit().setMessage(message).call()
                    commit.name.right()
                }
            }
        }

    override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.FetchFailed("Merge failed: ${e.message}") }) {
                val worktree = shadow.shadowWorktreeFor(config.repoRoot)
                openGit(config).use { git -> doMerge(git, config, worktree) }
            }
        }

    private suspend fun doMerge(
        git: Git,
        config: GitConfig,
        worktree: GitShadowWorktree?,
    ): Either<DomainError.GitError, MergeResult> {
        val repo = git.repository
        val remoteRef = repo.resolve("${config.remoteName}/${config.remoteBranch}")
            ?: return DomainError.GitError.FetchFailed("Remote ref not found").left()

        val mergeResult = git.merge()
            .include(remoteRef)
            .setStrategy(MergeStrategy.RECURSIVE)
            .setFastForward(MergeCommand.FastForwardMode.NO_FF)
            .call()

        val hasConflicts = mergeResult.mergeStatus == org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING
        val conflictFiles = mergeSupport.buildConflictFiles(repo, mergeResult, config, worktree)

        // Git-relative paths (e.g. "pages/foo.md"), pre-mapping to SAF-facing form — this is what
        // GitWriteBackQueue/GitShadowFlushActor operate on (Task 3.2.1a).
        val wikiChangedGitRelativePaths = wikiSubdirFilteredChangedPaths(repo, config)

        // Task 3.2.1a: write changed files back to SAF before returning MergeResult — this
        // ordering guarantees GitSyncService.sync()'s subsequent reloadFiles() (which runs on the
        // caller's mapped/SAF paths) reads content that is actually on disk in SAF. Task 3.2.1b: a
        // concurrent SAF edit is surfaced distinctly, not masked as a generic FetchFailed.
        if (worktree != null && wikiChangedGitRelativePaths.isNotEmpty()) {
            val concurrentEdit = mergeSupport.flushAndCheckConcurrentEdit(
                worktree, wikiChangedGitRelativePaths, config.repoRoot, "merge",
            )
            if (concurrentEdit != null) return concurrentEdit.left()
        }

        return MergeResult(
            hasConflicts = hasConflicts,
            conflicts = conflictFiles,
            changedFiles = toUserFacingPaths(wikiChangedGitRelativePaths, worktree, config),
        ).right()
    }

    private fun wikiSubdirFilteredChangedPaths(repo: Repository, config: GitConfig): List<String> {
        val changedGitRelativePaths = computeChangedGitRelativePaths(repo)
        return if (!config.wikiSubdir.isNullOrEmpty()) {
            changedGitRelativePaths.filter { it.startsWith("${config.wikiSubdir}/") }
        } else {
            changedGitRelativePaths
        }
    }

    private fun toUserFacingPaths(
        relPaths: List<String>,
        worktree: GitShadowWorktree?,
        config: GitConfig,
    ): List<String> = relPaths.map { relPath ->
        // relPath is shadow-relative, not repoRoot-relative — see AndroidGitMergeSupport.buildConflictFiles.
        worktree?.toUserFacingPath("${worktree.worktreeRootPath}/$relPath") ?: "${config.repoRoot}/$relPath"
    }

    override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            runGitTransportOp(
                onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Push authentication failed") },
                onFailed = { e -> DomainError.GitError.PushFailed(e.message ?: "Push failed") },
            ) {
                openGitWithoutFreshnessCheck(config).use { git -> doPush(git, config) }
            }
        }

    private fun doPush(git: Git, config: GitConfig): Either<DomainError.GitError, Unit> {
        git.push()
            .setRemote(config.remoteName)
            .also { authConfigurer.configureTransport(it, config) }
            .call()
        return Unit.right()
    }

    override suspend fun log(config: GitConfig, maxCount: Int): Either<DomainError.GitError, List<GitCommit>> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.FetchFailed("Log failed: ${e.message}") }) {
                openGitWithoutFreshnessCheck(config).use { git -> buildGitLog(git, maxCount) }
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
        timestamp = revCommit.authorIdent.`when`.time,
    )

    override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.CommitFailed("Abort merge failed: ${e.message}") }) {
                openGitWithoutFreshnessCheck(config).use { git -> doAbortMerge(git, config) }
            }
        }

    /**
     * ResetType.HARD, not MERGE: JGit 7.3.0's `ResetCommand` never implements MERGE/KEEP at all —
     * both throw `UnsupportedOperationException` unconditionally (verified by disassembling
     * `ResetCommand.class`: its `ResetType` switch routes MERGE and KEEP to the same throw), a
     * real pre-existing JGit limitation. HARD's merge-state cleanup (MERGE_HEAD/MERGE_MSG
     * removal, RepositoryState MERGING -> SAFE) is unconditional on any type other than SOFT, so
     * it correctly aborts the merge — empirically confirmed against a real conflict. Its one
     * semantic gap vs. real `git reset --merge` (HARD discards ANY uncommitted local edit, not
     * just ones differing from pre-merge HEAD) is covered by the post-reset SAF reconciliation
     * below, since SAF — not the shadow tree — is this app's source of truth for uncommitted work
     * (Task 4.2.1a). `force = true` because the reset just stamped a fresh "now" mtime on every
     * file it touched, which the ordinary per-file mtime-skip would otherwise misread as "already
     * fresh" — see [GitShadowWorktree.syncFromSafRoot]'s `force` doc for why.
     */
    private suspend fun doAbortMerge(git: Git, config: GitConfig): Either<DomainError.GitError, Unit> {
        git.reset()
            .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
            .call()

        val worktree = shadow.shadowWorktreeFor(config.repoRoot)
        if (worktree != null) {
            worktree.syncFromSafRoot(
                listRecursive = { root -> fileSystem.listFilesRecursiveWithModTimes(root) },
                readSafFile = { relPath -> fileSystem.readFile("${config.repoRoot}/$relPath") },
                force = true,
            )
        }

        return Unit.right()
    }

    override suspend fun checkoutFile(
        config: GitConfig,
        filePath: String,
        side: MergeSide,
    ): Either<DomainError.GitError, Unit> = withContext(PlatformDispatcher.IO) {
        runGitOp({ e -> DomainError.GitError.CommitFailed("Checkout file failed: ${e.message}") }) {
            val worktree = shadow.shadowWorktreeFor(config.repoRoot)
            val gitRelativePath = worktree?.toGitRelativePath(filePath)
                ?: filePath.removePrefix("${config.repoRoot}/")
            openGit(config).use { git -> doCheckoutFile(git, config, worktree, gitRelativePath, side) }
        }
    }

    private suspend fun doCheckoutFile(
        git: Git,
        config: GitConfig,
        worktree: GitShadowWorktree?,
        gitRelativePath: String,
        side: MergeSide,
    ): Either<DomainError.GitError, Unit> {
        val stage = when (side) {
            MergeSide.LOCAL -> org.eclipse.jgit.api.CheckoutCommand.Stage.OURS
            MergeSide.REMOTE -> org.eclipse.jgit.api.CheckoutCommand.Stage.THEIRS
        }
        git.checkout()
            .setStage(stage)
            .addPath(gitRelativePath)
            .call()

        // Task 3.2.2a: write the checked-out content back to SAF before returning —
        // otherwise resolveConflictBySide()'s side-based choice never reaches SAF.
        if (worktree != null) {
            val concurrentEdit = mergeSupport.flushAndCheckConcurrentEdit(
                worktree, listOf(gitRelativePath), config.repoRoot, "checkoutFile",
            )
            if (concurrentEdit != null) return concurrentEdit.left()
        }

        return Unit.right()
    }

    override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            runGitOp({ e -> DomainError.GitError.CommitFailed("Mark resolved failed: ${e.message}") }) {
                val worktree = shadow.shadowWorktreeFor(config.repoRoot)
                val gitRelativePath = worktree?.toGitRelativePath(filePath)
                    ?: filePath.removePrefix("${config.repoRoot}/")
                // Task 4.1.1a: the caller (GitSyncService.resolveConflict/applyJournalMerge)
                // already wrote the resolved content straight to SAF via fileSystem.writeFile
                // before calling markResolved() — but nothing has told the shadow tree about it,
                // so without this pull-then-stage step git.add() below would stage whatever is
                // still in the shadow copy (stale content, possibly still containing conflict
                // markers). Pull the current SAF content into the shadow tree first.
                if (worktree != null) {
                    val safContent = fileSystem.readFile(filePath)
                        ?: return@runGitOp DomainError.GitError.CommitFailed(
                            "Cannot refresh shadow before staging: $filePath"
                        ).left()
                    worktree.writeShadowFile(gitRelativePath, safContent)
                }
                openGit(config).use { git -> stageResolvedFile(git, gitRelativePath) }
            }
        }

    private fun stageResolvedFile(git: Git, gitRelativePath: String): Either<DomainError.GitError, Unit> {
        git.add().addFilepattern(gitRelativePath).call()
        return Unit.right()
    }

    override suspend fun hasDetachedHead(config: GitConfig): Boolean =
        withContext(PlatformDispatcher.IO) {
            runGitOpOrFalse {
                openGitWithoutFreshnessCheck(config).use { git ->
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

    private fun deleteStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit> {
        val lockFile = File(shadow.resolveForJGit(config.repoRoot), ".git/index.lock")
        if (!lockFile.exists()) return Unit.right()

        val ageMs = System.currentTimeMillis() - lockFile.lastModified()
        return if (ageMs > 60_000L) {
            if (lockFile.delete()) Unit.right() else DomainError.GitError.StaleLockFile(lockFile.absolutePath).left()
        } else {
            DomainError.GitError.StaleLockFile(lockFile.absolutePath).left()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves (or lazily creates and caches) the shadow worktree for [repoRoot]. Delegates to
     * [AndroidGitShadowSupport.shadowWorktreeFor]; kept as a thin member here because tests
     * exercise it directly (`AndroidGitRepositoryShadowWorktreeTest`, `GitPathResolverChainTest`,
     * `AndroidGitRepositoryStorageGuardTest`, `AndroidGitRepositoryAppOwnedCloneTest`).
     */
    internal fun shadowWorktreeFor(repoRoot: String): GitShadowWorktree? = shadow.shadowWorktreeFor(repoRoot)

    /**
     * Resolves saf:// URIs to real filesystem paths for JGit's File-based API. Delegates to
     * [AndroidGitShadowSupport.resolveForJGit]; kept as a thin member here because
     * `AndroidGitRepositoryShadowWorktreeTest` exercises it directly.
     */
    internal fun resolveForJGit(path: String): String = shadow.resolveForJGit(path)

    /**
     * Single choke point for every working-tree-touching JGit call that reads working-tree
     * content before acting. Runs the shadow worktree's freshness precondition — a structural
     * enforcement, not a documented calling convention (plan.md design decision #4) — before
     * opening the repository. See [openGitWithoutFreshnessCheck] for callers that don't need this.
     */
    private suspend fun openGit(config: GitConfig): Git {
        shadow.shadowWorktreeFor(config.repoRoot)?.ensureFresh(
            listRecursive = { root -> fileSystem.listFilesRecursiveWithModTimes(root) },
            readSafFile = { relPath -> fileSystem.readFile("${config.repoRoot}/$relPath") },
        )
        return Git.open(File(shadow.resolveForJGit(config.repoRoot)))
    }

    /**
     * Opens the repository without the shadow-worktree freshness precondition — for callers that
     * don't read working-tree content before acting (`fetch`, `log`, `push`, `hasDetachedHead`)
     * or that need a *post-op* reconciliation instead of a pre-op check (`abortMerge`). A second
     * named function rather than a boolean flag on [openGit] (Fowler's Remove Flag Argument):
     * these really are two different operations, not one operation with an option.
     */
    private fun openGitWithoutFreshnessCheck(config: GitConfig): Git =
        Git.open(File(shadow.resolveForJGit(config.repoRoot)))
}
