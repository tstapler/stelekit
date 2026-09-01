// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.wikiRoot
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.FS
import java.io.File

/**
 * Android implementation of GitRepository using JGit 7.3.0 (matches Desktop) + mwiede/jsch fork of
 * jsch for SSH key format support (ED25519/ECDSA/OpenSSH).
 * All I/O runs on PlatformDispatcher.IO.
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

    private val shadowWorktrees = java.util.concurrent.ConcurrentHashMap<String, GitShadowWorktree>()

    /**
     * Resolves (or lazily creates and caches) the shadow worktree for *this call's* [repoRoot] —
     * never a value captured once at construction time (plan.md design decision #6). Returns null
     * when [pathResolver] already resolves [repoRoot] directly (fast path active — e.g. Desktop or
     * `MANAGE_EXTERNAL_STORAGE`, no shadow needed) or when [repoRoot] isn't a `saf://` path.
     */
    internal fun shadowWorktreeFor(repoRoot: String): GitShadowWorktree? {
        if (pathResolver(repoRoot) != null) return null // fast path resolves directly, no shadow needed
        if (!repoRoot.startsWith("saf://")) return null
        val key = GitShadowWorktree.shadowKeyForSafPath(repoRoot)
        // .also { touchLastUsed() } refreshes the orphan-sweep liveness signal (Task 6.1.1a) on
        // every real resolution, so GitShadowWorktree.sweepOrphans() never deletes an actively
        // used graph's shadow tree — no per-call-site opt-in required.
        return shadowWorktrees.getOrPut(key) {
            GitShadowWorktree(context, key, repoRoot, fileSystem)
        }.also {
            it.touchLastUsed()
            // Task 5.2.1c: keep PlatformFileSystem's write-behind flush lock-key in sync with the
            // git side's shadowKey derivation on every real resolution, so a concurrent flush for
            // this graph contends on the same GitWorktreeLocks Mutex as syncFromSafRoot().
            fileSystem.setGitShadowKeyProvider { GitShadowWorktree.shadowKeyForSafPath(repoRoot) }
        }
    }

    /**
     * Storage-space guard (plan.md Phase 6, Epic 6.2). Returns a [DomainError.GitError.WorkingTreeSyncFailed]
     * when [worktree] is non-null (shadow-mirror mode active) and its filesystem has less than
     * [MIN_SHADOW_FREE_BYTES] available, so `init()`/`clone()` can fail fast with a diagnosable
     * typed error instead of a raw JGit I/O exception mid-clone. Returns null (proceed) when
     * [worktree] is null (fast path, no shadow needed) or storage is sufficient.
     *
     * Threshold rationale (Task 6.2.0a): the plan's full empirical measurement — materializing
     * `SyntheticGraphGenerator`'s XLARGE fixture (7 978 pages) via a throwaway JVM
     * `git init` + 20 incremental commits and recording actual `.git` object-store growth — was
     * NOT performed for this pass; it's deferred as a documented follow-up (see plan.md Task
     * 6.2.0a) rather than blocking this phase, since standing up that throwaway benchmark harness
     * isn't essential to shipping the guard itself and no fabricated byte counts should stand in
     * for it. In its place, [MIN_SHADOW_FREE_BYTES] is a conservative *estimate*: a typical
     * markdown wiki page runs a few KB, so a working tree at XLARGE's page count is plausibly in
     * the tens of MB; an actively-edited `.git` object store (blob history across incremental
     * commits, pre-gc packfiles) commonly runs several times the working-tree content size — a
     * 3-5x multiplier is a reasonable order-of-magnitude planning basis. 200 MB comfortably covers
     * that multiple with headroom for continued graph growth, without being large enough to
     * needlessly block low-storage devices that have plenty of room for a wiki-scale repo.
     */
    private fun insufficientShadowStorageError(
        worktree: GitShadowWorktree?,
        path: String,
    ): DomainError.GitError.WorkingTreeSyncFailed? {
        if (worktree == null) return null
        val available = android.os.StatFs(worktree.worktreeRootPath).availableBytes
        if (available >= MIN_SHADOW_FREE_BYTES) return null
        return DomainError.GitError.WorkingTreeSyncFailed(
            "clone",
            path,
            "Insufficient storage for git shadow clone",
        )
    }

    @Volatile var credentialAccess: CredentialAccess = credentialAccess
        internal set

    override fun setCredentialAccess(access: CredentialAccess) { credentialAccess = access }

    override suspend fun isGitRepo(path: String): Boolean = withContext(PlatformDispatcher.IO) {
        File(resolveForJGit(path), ".git").exists()
    }

    override suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            try {
                val worktree = shadowWorktreeFor(repoRoot)
                insufficientShadowStorageError(worktree, repoRoot)?.let { return@withContext it.left() }
                Git.init().setDirectory(File(resolveForJGit(repoRoot))).call().use { git ->
                    syncShadowAfterInitOrClone(repoRoot, git)
                }
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.GitError.CloneFailed("init failed: ${e.message}").left()
            }
        }

    override suspend fun clone(
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (String) -> Unit,
    ): Either<DomainError.GitError, Unit> = withContext(PlatformDispatcher.IO) {
        try {
            val worktree = shadowWorktreeFor(localPath)
            insufficientShadowStorageError(worktree, localPath)?.let { return@withContext it.left() }
            // Resolve suspend credentials before entering JGit's synchronous territory
            val preResolvedToken: String? = if (auth is GitAuth.HttpsToken) auth.tokenProvider() else null
            val job = coroutineContext[kotlinx.coroutines.Job]

            val cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(File(resolveForJGit(localPath)))
                .setProgressMonitor(object : org.eclipse.jgit.lib.ProgressMonitor {
                    override fun start(totalTasks: Int) {}
                    override fun beginTask(title: String, totalWork: Int) { onProgress(title) }
                    override fun update(completed: Int) {}
                    override fun endTask() {}
                    override fun isCancelled() = job?.isCancelled == true
                    // showDuration added in JGit 7.x; Bazel resolves to 7.x on Android too
                    override fun showDuration(enabled: Boolean) {}
                })

            configureAuth(cmd, auth, preResolvedToken)
            cmd.call().use { git ->
                syncShadowAfterInitOrClone(localPath, git)
            }
            Unit.right()
        } catch (e: TransportException) {
            DomainError.GitError.AuthFailed(e.message ?: "Authentication failed").left()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.GitError.CloneFailed(e.message ?: "Clone failed").left()
        }
    }

    override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> =
        withContext(PlatformDispatcher.IO) {
            try {
                openGit(config, requiresFreshWorkingTree = false).use { git ->
                    val repo = git.repository
                    val headBefore = repo.resolve("HEAD")

                    git.fetch()
                        .setRemote(config.remoteName)
                        .also { configureTransport(it, config) }
                        .call()

                    val remoteRef = repo.resolve("${config.remoteName}/${config.remoteBranch}")
                    val hasChanges = remoteRef != null && remoteRef != headBefore

                    val remoteCommitCount = if (hasChanges && headBefore != null && remoteRef != null) {
                        try {
                            git.log().addRange(headBefore, remoteRef).setMaxCount(100).call().toList().size
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            0
                        }
                    } else {
                        0
                    }

                    FetchResult(hasRemoteChanges = hasChanges, remoteCommitCount = remoteCommitCount).right()
                }
            } catch (e: TransportException) {
                DomainError.GitError.AuthFailed(e.message ?: "Authentication failed").left()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.GitError.FetchFailed(e.message ?: "Fetch failed").left()
            }
        }

    override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
        withContext(PlatformDispatcher.IO) {
            try {
                openGit(config).use { git ->
                    val statusResult = git.status()
                        .also { cmd ->
                            if (!config.wikiSubdir.isNullOrEmpty()) {
                                cmd.addPath(config.wikiSubdir)
                            }
                        }
                        .call()

                    GitStatus(
                        hasLocalChanges = !statusResult.isClean,
                        untrackedFiles = statusResult.untracked.toList(),
                        modifiedFiles = (statusResult.modified + statusResult.changed).toList(),
                    ).right()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.GitError.FetchFailed("Status failed: ${e.message}").left()
            }
        }

    override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            try {
                openGit(config).use { git ->
                    val pattern = if (config.wikiSubdir.isNullOrEmpty()) "." else "${config.wikiSubdir}/"
                    git.add().addFilepattern(pattern).call()
                    git.add().setUpdate(true).addFilepattern(pattern).call()
                    Unit.right()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.GitError.CommitFailed("Stage failed: ${e.message}").left()
            }
        }

    override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> =
        withContext(PlatformDispatcher.IO) {
            try {
                openGit(config).use { git ->
                    val commit = git.commit().setMessage(message).call()
                    commit.name.right()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.GitError.CommitFailed(e.message ?: "Commit failed").left()
            }
        }

    override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> =
        withContext(PlatformDispatcher.IO) {
            try {
                val worktree = shadowWorktreeFor(config.repoRoot)
                openGit(config).use { git ->
                    val repo = git.repository
                    val remoteRef = repo.resolve("${config.remoteName}/${config.remoteBranch}")
                        ?: return@withContext DomainError.GitError.FetchFailed(
                            "Remote ref not found"
                        ).left()

                    val mergeResult = git.merge()
                        .include(remoteRef)
                        .setStrategy(MergeStrategy.RECURSIVE)
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call()

                    val hasConflicts = mergeResult.mergeStatus ==
                        org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING

                    val conflictFiles = if (hasConflicts) {
                        mergeResult.conflicts?.keys?.map { filePath ->
                            // filePath is git-relative (e.g. "pages/foo.md"), relative to whatever
                            // directory JGit actually opened — the shadow worktree root in
                            // shadow-mirror mode, not config.repoRoot (the SAF root string).
                            // toUserFacingPath() strips worktreeRootPath, so it must be given a
                            // shadow-absolute path, never a repoRoot-prefixed one (fixed after
                            // Phase 3 review flagged the repoRoot-prefixed form as a no-op strip
                            // that doubled the SAF root in the returned path).
                            val absolutePath = worktree?.toUserFacingPath("${worktree.worktreeRootPath}/$filePath")
                                ?: "${config.repoRoot}/$filePath"
                            val wikiRelPath = if (!config.wikiSubdir.isNullOrEmpty() &&
                                filePath.startsWith("${config.wikiSubdir}/")) {
                                filePath.removePrefix("${config.wikiSubdir}/")
                            } else {
                                filePath
                            }

                            // Read the real conflict-marker content JGit just wrote into the
                            // working tree — the shadow tree in shadow-mirror mode, the real
                            // repoRoot-relative file otherwise (mirrors JvmGitRepository, which
                            // has no shadow indirection at all). For markdown, prefer re-deriving
                            // that content via the block-aware merge (tryBlockAwareConflict) over
                            // JGit's own line-level markers — see that function's doc. Falls back
                            // to JGit's line-level marker content, parsed the same way, for
                            // non-markdown/unparseable files or binary/rename-only conflicts.
                            val jgitMarkerContent = worktree?.readShadowFile(filePath)
                                ?: runCatching {
                                    File(resolveForJGit(config.repoRoot), filePath).readText()
                                }.getOrNull()
                            val blockAware = tryBlockAwareConflict(repo, filePath, absolutePath, config.wikiRoot)
                            val markerContent = blockAware?.markerText ?: jgitMarkerContent
                            val hunks = blockAware?.hunks ?: markerContent?.let {
                                ConflictResolver().parseConflictFile(absolutePath, it, config.wikiRoot)
                                    .getOrNull()?.hunks
                            } ?: emptyList()

                            // Keep the shadow tree (and, best-effort, SAF) in sync with what the
                            // app will resolve against — mirrors the pre-existing write-back below,
                            // now seeded with the block-merge text instead of JGit's own when one
                            // was derived.
                            if (worktree != null && blockAware != null) {
                                worktree.writeShadowFile(filePath, blockAware.markerText)
                            }

                            // Best-effort: also write the marker content back to SAF (through the
                            // same write-back actor Phase 3's clean-merge path uses, so a
                            // concurrent SAF edit is detected rather than clobbered) so the file
                            // is externally visible with real conflict markers, matching what a
                            // direct-filesystem git working tree already shows for free. Resolution
                            // itself never depends on this succeeding — it uses the markerContent
                            // captured above via ConflictFile.rawContent, not a later SAF re-read.
                            if (worktree != null && markerContent != null) {
                                worktree.writeBackQueue.enqueue(filePath)
                                GitShadowFlushActor(
                                    fileSystem, worktree, worktree.writeBackQueue, config.repoRoot,
                                ).flush()
                            }

                            ConflictFile(
                                filePath = absolutePath,
                                wikiRelativePath = wikiRelPath,
                                hunks = hunks,
                                rawContent = markerContent,
                            )
                        } ?: emptyList()
                    } else {
                        emptyList()
                    }

                    // Git-relative paths (e.g. "pages/foo.md"), pre-mapping to SAF-facing form —
                    // this is what GitWriteBackQueue/GitShadowFlushActor operate on (Task 3.2.1a).
                    val changedGitRelativePaths = try {
                        val headAfter = repo.resolve("HEAD")
                        if (headAfter != null) {
                            val revWalk = org.eclipse.jgit.revwalk.RevWalk(repo)
                            val headCommit = revWalk.parseCommit(headAfter)
                            val parentCommit = headCommit.parents.firstOrNull()?.let { revWalk.parseCommit(it) }
                            val diffFormatter = org.eclipse.jgit.diff.DiffFormatter(
                                org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE
                            )
                            diffFormatter.setRepository(repo)
                            val files = if (parentCommit != null) {
                                diffFormatter.scan(parentCommit.tree, headCommit.tree).map { it.newPath }
                            } else {
                                emptyList()
                            }
                            diffFormatter.close()
                            revWalk.close()
                            files
                        } else {
                            emptyList()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }

                    val wikiChangedGitRelativePaths = if (!config.wikiSubdir.isNullOrEmpty()) {
                        changedGitRelativePaths.filter { it.startsWith("${config.wikiSubdir}/") }
                    } else {
                        changedGitRelativePaths
                    }

                    // Task 3.2.1a: write changed files back to SAF before returning MergeResult —
                    // this ordering guarantees GitSyncService.sync()'s subsequent reloadFiles()
                    // (which runs on the caller's mapped/SAF paths) reads content that is
                    // actually on disk in SAF.
                    if (worktree != null && wikiChangedGitRelativePaths.isNotEmpty()) {
                        wikiChangedGitRelativePaths.forEach { worktree.writeBackQueue.enqueue(it) }
                        val flushErrors = GitShadowFlushActor(
                            fileSystem, worktree, worktree.writeBackQueue, config.repoRoot,
                        ).flush().flushErrors()

                        // Task 3.2.1b: a concurrent SAF edit is surfaced distinctly, not masked
                        // as a generic FetchFailed.
                        flushErrors.filterIsInstance<DomainError.GitError.WorkingTreeConcurrentEditDetected>()
                            .firstOrNull()
                            ?.let { return@withContext it.left() }

                        // A transient write-back failure is logged and left queued for retry on
                        // the next sync's drain — it must not fail a merge that already
                        // succeeded in the shadow tree.
                        flushErrors.filterIsInstance<DomainError.GitError.WorkingTreeWriteBackFailed>()
                            .forEach {
                                logger.warn("merge: write-back failed for ${it.path}, will retry on next sync")
                            }
                    }

                    val wikiChangedFiles = wikiChangedGitRelativePaths.map { relPath ->
                        // See conflictFiles above: relPath is shadow-relative, not repoRoot-relative.
                        worktree?.toUserFacingPath("${worktree.worktreeRootPath}/$relPath") ?: "${config.repoRoot}/$relPath"
                    }

                    MergeResult(
                        hasConflicts = hasConflicts,
                        conflicts = conflictFiles,
                        changedFiles = wikiChangedFiles,
                    ).right()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.GitError.FetchFailed("Merge failed: ${e.message}").left()
            }
        }

    override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            try {
                openGit(config, requiresFreshWorkingTree = false).use { git ->
                    git.push()
                        .setRemote(config.remoteName)
                        .also { configureTransport(it, config) }
                        .call()
                    Unit.right()
                }
            } catch (e: TransportException) {
                DomainError.GitError.AuthFailed(e.message ?: "Push authentication failed").left()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.GitError.PushFailed(e.message ?: "Push failed").left()
            }
        }

    override suspend fun log(config: GitConfig, maxCount: Int): Either<DomainError.GitError, List<GitCommit>> =
        withContext(PlatformDispatcher.IO) {
            try {
                openGit(config, requiresFreshWorkingTree = false).use { git ->
                    val commits = git.log()
                        .setMaxCount(maxCount)
                        .call()
                        .map { revCommit ->
                            GitCommit(
                                sha = revCommit.name,
                                shortMessage = revCommit.shortMessage,
                                authorName = revCommit.authorIdent.name,
                                timestamp = revCommit.authorIdent.`when`.time,
                            )
                        }
                    commits.right()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.GitError.FetchFailed("Log failed: ${e.message}").left()
            }
        }

    override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            try {
                openGit(config, requiresFreshWorkingTree = false).use { git ->
                    // ResetType.HARD, not MERGE: JGit 7.3.0's ResetCommand never implements
                    // ResetType.MERGE/KEEP at all — both throw UnsupportedOperationException
                    // unconditionally (verified by disassembling ResetCommand.class: its ResetType
                    // switch routes MERGE and KEEP to the same throw; only HARD/MIXED/SOFT are
                    // implemented). This is a real, pre-existing JGit-library limitation, not
                    // something introduced by this project. HARD's merge-state cleanup
                    // (MERGE_HEAD/MERGE_MSG removal, RepositoryState MERGING -> SAFE) is
                    // unconditional on any ResetType other than SOFT, so HARD correctly aborts the
                    // in-progress merge — empirically confirmed against a real conflicted merge.
                    // The one semantic gap vs. real `git reset --merge` is that HARD discards ANY
                    // uncommitted local edit, not just ones that differ from pre-merge HEAD; that
                    // gap is covered by this method's own post-reset SAF reconciliation below, since
                    // SAF (not the shadow tree) is this app's source of truth for uncommitted work.
                    git.reset()
                        .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                        .call()

                    // Task 4.2.1a: the HARD reset above rewrites shadow-tree files that differ
                    // from pre-merge HEAD, but SAF — not the mid-abort JGit state — must remain
                    // the source of truth for any already write-back'd resolution content (see
                    // plan.md Epic 4.2's traced partial-resolveConflictBySide()-then-abort
                    // sequence). Re-sync unconditionally (not gated by isFresh()) since the reset
                    // itself just invalidated the manifest's assumptions about shadow content.
                    // force = true: the reset just stamped a fresh "now" mtime on every file it
                    // touched, which the ordinary per-file mtime-skip would misread as "already
                    // fresh" — see GitShadowWorktree.syncFromSafRoot()'s [force] doc for why.
                    val worktree = shadowWorktreeFor(config.repoRoot)
                    if (worktree != null) {
                        worktree.syncFromSafRoot(
                            listRecursive = { root -> fileSystem.listFilesRecursiveWithModTimes(root) },
                            readSafFile = { relPath -> fileSystem.readFile("${config.repoRoot}/$relPath") },
                            force = true,
                        )
                    }

                    Unit.right()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.GitError.CommitFailed("Abort merge failed: ${e.message}").left()
            }
        }

    override suspend fun checkoutFile(
        config: GitConfig,
        filePath: String,
        side: MergeSide,
    ): Either<DomainError.GitError, Unit> = withContext(PlatformDispatcher.IO) {
        try {
            val worktree = shadowWorktreeFor(config.repoRoot)
            val gitRelativePath = worktree?.toGitRelativePath(filePath)
                ?: filePath.removePrefix("${config.repoRoot}/")
            openGit(config).use { git ->
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
                    worktree.writeBackQueue.enqueue(gitRelativePath)
                    val flushErrors = GitShadowFlushActor(
                        fileSystem, worktree, worktree.writeBackQueue, config.repoRoot,
                    ).flush().flushErrors()

                    flushErrors.filterIsInstance<DomainError.GitError.WorkingTreeConcurrentEditDetected>()
                        .firstOrNull()
                        ?.let { return@withContext it.left() }

                    flushErrors.filterIsInstance<DomainError.GitError.WorkingTreeWriteBackFailed>()
                        .forEach {
                            logger.warn("checkoutFile: write-back failed for ${it.path}, will retry on next sync")
                        }
                }

                Unit.right()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.GitError.CommitFailed("Checkout file failed: ${e.message}").left()
        }
    }

    override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            try {
                val worktree = shadowWorktreeFor(config.repoRoot)
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
                        ?: return@withContext DomainError.GitError.CommitFailed(
                            "Cannot refresh shadow before staging: $filePath"
                        ).left()
                    worktree.writeShadowFile(gitRelativePath, safContent)
                }

                openGit(config).use { git ->
                    git.add().addFilepattern(gitRelativePath).call()
                    Unit.right()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.GitError.CommitFailed("Mark resolved failed: ${e.message}").left()
            }
        }

    override suspend fun hasDetachedHead(config: GitConfig): Boolean =
        withContext(PlatformDispatcher.IO) {
            try {
                openGit(config, requiresFreshWorkingTree = false).use { git ->
                    val fullBranch = git.repository.fullBranch ?: return@use false
                    !fullBranch.startsWith("refs/heads/")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun removeStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            try {
                val lockFile = File(resolveForJGit(config.repoRoot), ".git/index.lock")
                if (!lockFile.exists()) return@withContext Unit.right()

                val ageMs = System.currentTimeMillis() - lockFile.lastModified()
                if (ageMs > 60_000L) {
                    if (lockFile.delete()) Unit.right()
                    else DomainError.GitError.StaleLockFile(lockFile.absolutePath).left()
                } else {
                    DomainError.GitError.StaleLockFile(lockFile.absolutePath).left()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.GitError.StaleLockFile("${config.repoRoot}/.git/index.lock").left()
            }
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts the [DomainError.GitError] values out of a [GitShadowFlushActor.flush] result
     * list's failed entries, for `merge()`/`checkoutFile()` (Tasks 3.2.1b/3.2.2a) to route
     * distinctly by error type instead of masking a write-back failure as a generic error.
     */
    private fun List<Either<DomainError.GitError, Unit>>.flushErrors(): List<DomainError.GitError> =
        mapNotNull { (it as? Either.Left)?.value }

    /**
     * Resolves saf:// URIs to real filesystem paths for JGit's File-based API. Tries the fast
     * path ([pathResolver], e.g. `MANAGE_EXTERNAL_STORAGE` or Desktop) first, then falls back to
     * the shadow worktree's real `java.io.File` root when shadow-mirror mode is active, and only
     * falls through to the raw (unresolvable-by-JGit) [path] string if neither applies.
     */
    internal fun resolveForJGit(path: String): String {
        val resolved = pathResolver(path) ?: shadowWorktreeFor(path)?.worktreeRootPath
        if (resolved == null && path.startsWith("saf://")) {
            // JGit only knows java.io.File — a SAF content:// grant alone can't back that, so this
            // path is unusable for git sync unless "All files access" is granted (Android Settings >
            // Apps > SteleKit > Permissions > All files access), which unlocks resolveSafToRealPath()
            // (see PlatformFileSystem.resolveSafToRealPath). Falling through to the raw saf:// string
            // below is what produces the cryptic "repository not found: /saf:/content%3A..." error.
            logger.warn(
                "Cannot resolve SAF path to a real file for JGit — grant \"All files access\" " +
                    "to SteleKit in Android Settings to use git sync with this folder. path=$path"
            )
        }
        return resolved ?: path
    }

    /**
     * Single choke point for every working-tree-touching JGit call. When [requiresFreshWorkingTree]
     * is true (the default), runs the shadow worktree's freshness precondition — a structural
     * enforcement, not a documented calling convention (plan.md design decision #4) — before
     * opening the repository. Callers that don't read working-tree content before acting (`fetch`,
     * `log`, `push`, `hasDetachedHead`) or that need a *post-op* reconciliation instead of a pre-op
     * check (`abortMerge`) pass `requiresFreshWorkingTree = false`.
     */
    private suspend fun openGit(config: GitConfig, requiresFreshWorkingTree: Boolean = true): Git {
        if (requiresFreshWorkingTree) {
            shadowWorktreeFor(config.repoRoot)?.ensureFresh(
                listRecursive = { root -> fileSystem.listFilesRecursiveWithModTimes(root) },
                readSafFile = { relPath -> fileSystem.readFile("${config.repoRoot}/$relPath") },
            )
        }
        return Git.open(File(resolveForJGit(config.repoRoot)))
    }

    /**
     * After a successful `init()`/`clone()`, when shadow-mirror mode is active for [repoRoot],
     * pulls any pre-existing SAF markdown into the freshly created/cloned shadow tree
     * unconditionally (bypassing the freshness check — there is no prior manifest yet) so it's
     * present before the user's first `status()`/`commit()`, and disables file-mode tracking
     * (pitfall §2.4 — SAF documents carry no Unix permissions). No-op when shadow-mirror mode
     * isn't active for this [repoRoot] (Desktop / `MANAGE_EXTERNAL_STORAGE` fast path).
     */
    private suspend fun syncShadowAfterInitOrClone(repoRoot: String, git: Git) {
        val worktree = shadowWorktreeFor(repoRoot) ?: return
        worktree.syncFromSafRoot(
            listRecursive = { root -> fileSystem.listFilesRecursiveWithModTimes(root) },
            readSafFile = { relPath -> fileSystem.readFile("$repoRoot/$relPath") },
        )
        worktree.disableFileModeTracking(git.repository)
    }

    private fun buildJschSessionFactory(keyPath: String, passphrase: String? = null): JschConfigSessionFactory {
        return object : JschConfigSessionFactory() {
            override fun configure(host: OpenSshConfig.Host, session: Session) {
                session.setConfig("StrictHostKeyChecking", "accept-new")
            }

            override fun createDefaultJSch(fs: FS): JSch {
                val jsch = super.createDefaultJSch(fs)
                val keyBytes = sshKeyProvider?.invoke()
                val passphraseBytes = passphrase?.toByteArray(Charsets.UTF_8)
                if (keyBytes != null) {
                    jsch.addIdentity("stelekit-key", keyBytes, null, passphraseBytes)
                } else if (keyPath.isNotEmpty()) {
                    if (passphraseBytes != null) {
                        jsch.addIdentity(keyPath, passphraseBytes)
                    } else {
                        jsch.addIdentity(keyPath)
                    }
                }
                return jsch
            }
        }
    }

    private fun configureTransport(
        cmd: org.eclipse.jgit.api.TransportCommand<*, *>,
        config: GitConfig,
    ) {
        when (config.authType) {
            GitAuthType.HTTPS_TOKEN -> {
                val token = config.httpsTokenKey?.let { credentialAccess.retrieve(it) } ?: return
                cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider("", token))
            }
            GitAuthType.SSH_KEY -> {
                val passphrase = config.sshKeyPassphraseKey?.let { credentialAccess.retrieve(it) }
                cmd.setTransportConfigCallback { transport ->
                    if (transport is org.eclipse.jgit.transport.SshTransport && config.sshKeyPath != null) {
                        transport.sshSessionFactory = buildJschSessionFactory(config.sshKeyPath, passphrase)
                    }
                }
            }
            GitAuthType.GITHUB_OAUTH -> {
                val token = config.oauthTokenKey?.let { credentialAccess.retrieve(it) } ?: return
                cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider("x-oauth-basic", token))
            }
            GitAuthType.NONE -> {}
        }
    }

    private fun configureAuth(
        cmd: org.eclipse.jgit.api.TransportCommand<*, *>,
        auth: GitAuth,
        preResolvedToken: String?,
    ) {
        when (auth) {
            is GitAuth.HttpsToken -> {
                val token = preResolvedToken ?: run {
                    logger.warn("HTTPS token unavailable for clone — proceeding unauthenticated")
                    return
                }
                cmd.setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(auth.username, token)
                )
            }
            is GitAuth.SshKey -> {
                cmd.setTransportConfigCallback { transport ->
                    if (transport is org.eclipse.jgit.transport.SshTransport) {
                        transport.sshSessionFactory = buildJschSessionFactory(auth.keyPath)
                    }
                }
            }
            is GitAuth.None -> { /* no auth */ }
        }
    }

    companion object {
        /** See [insufficientShadowStorageError] for the sizing rationale (plan.md Task 6.2.0a/6.2.1a). */
        private const val MIN_SHADOW_FREE_BYTES = 200L * 1024 * 1024 // 200 MB
    }
}
