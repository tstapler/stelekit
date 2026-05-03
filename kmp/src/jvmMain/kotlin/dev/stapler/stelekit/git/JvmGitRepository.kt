// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.ConflictHunk
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import java.io.File
import java.time.Instant

/**
 * JVM (Desktop) implementation of GitRepository using JGit 7.x.
 * All I/O runs on PlatformDispatcher.IO.
 */
class JvmGitRepository(
    private val credentialStore: CredentialStore = CredentialStore(),
) : GitRepository {

    override suspend fun isGitRepo(path: String): Boolean = withContext(PlatformDispatcher.IO) {
        try {
            val gitDir = File(path, ".git")
            if (gitDir.exists()) return@withContext true
            // Also handle bare repos
            val builder = FileRepositoryBuilder()
            builder.setMustExist(true)
            builder.findGitDir(File(path))
            builder.gitDir != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            try {
                Git.init().setDirectory(File(repoRoot)).call().close()
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
            val cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(File(localPath))
                .setProgressMonitor(object : org.eclipse.jgit.lib.ProgressMonitor {
                    override fun start(totalTasks: Int) {}
                    override fun beginTask(title: String, totalWork: Int) { onProgress(title) }
                    override fun update(completed: Int) {}
                    override fun endTask() {}
                    override fun isCancelled() = false
                    override fun showDuration(enabled: Boolean) {}
                })

            configureAuth(cmd, auth)
            cmd.call().close()
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
                openGit(config.repoRoot).use { git ->
                    val repo = git.repository
                    val headBefore = repo.resolve("HEAD")

                    git.fetch()
                        .setRemote(config.remoteName)
                        .also { configureAuthFromConfig(it, config) }
                        .call()

                    val remoteRef = repo.resolve("${config.remoteName}/${config.remoteBranch}")
                    val hasChanges = remoteRef != null && remoteRef != headBefore

                    val remoteCommitCount = if (hasChanges && headBefore != null && remoteRef != null) {
                        try {
                            val commits = git.log()
                                .addRange(headBefore, remoteRef)
                                .setMaxCount(100)
                                .call()
                                .toList()
                            commits.size
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
                openGit(config.repoRoot).use { git ->
                    val statusResult = git.status()
                        .also { cmd ->
                            if (config.wikiSubdir.isNotEmpty()) {
                                cmd.addPath(config.wikiSubdir)
                            }
                        }
                        .call()

                    val modified = (statusResult.modified + statusResult.changed).toList()
                    val untracked = statusResult.untracked.toList()
                    val hasChanges = !statusResult.isClean

                    GitStatus(
                        hasLocalChanges = hasChanges,
                        untrackedFiles = untracked,
                        modifiedFiles = modified,
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
                openGit(config.repoRoot).use { git ->
                    val pattern = if (config.wikiSubdir.isEmpty()) "." else "${config.wikiSubdir}/"
                    git.add().addFilepattern(pattern).call()
                    // Also stage deletions
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
                openGit(config.repoRoot).use { git ->
                    val commit = git.commit()
                        .setMessage(message)
                        .call()
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
                openGit(config.repoRoot).use { git ->
                    val repo = git.repository
                    val remoteRef = repo.resolve("${config.remoteName}/${config.remoteBranch}")
                        ?: return@withContext DomainError.GitError.FetchFailed(
                            "Remote ref ${config.remoteName}/${config.remoteBranch} not found"
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
                            val absolutePath = "${config.repoRoot}/$filePath"
                            val wikiRelPath = if (config.wikiSubdir.isNotEmpty() &&
                                filePath.startsWith("${config.wikiSubdir}/")) {
                                filePath.removePrefix("${config.wikiSubdir}/")
                            } else {
                                filePath
                            }
                            ConflictFile(
                                filePath = absolutePath,
                                wikiRelativePath = wikiRelPath,
                                hunks = emptyList(), // parsed by ConflictResolver later
                            )
                        } ?: emptyList()
                    } else {
                        emptyList()
                    }

                    // Determine changed files by comparing HEAD before and after merge
                    val changedFiles = try {
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
                                diffFormatter.scan(parentCommit.tree, headCommit.tree)
                                    .map { "${config.repoRoot}/${it.newPath}" }
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

                    val wikiChangedFiles = if (config.wikiSubdir.isNotEmpty()) {
                        changedFiles.filter { it.startsWith("${config.repoRoot}/${config.wikiSubdir}/") }
                    } else {
                        changedFiles
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
                openGit(config.repoRoot).use { git ->
                    val pushResults = git.push()
                        .setRemote(config.remoteName)
                        .also { configureAuthFromConfig(it, config) }
                        .call()

                    for (result in pushResults) {
                        for (update in result.remoteUpdates) {
                            if (update.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ||
                                update.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_OTHER_REASON) {
                                return@withContext DomainError.GitError.PushFailed(
                                    "Push rejected: ${update.status}"
                                ).left()
                            }
                        }
                    }
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
                openGit(config.repoRoot).use { git ->
                    val commits = git.log()
                        .setMaxCount(maxCount)
                        .call()
                        .map { revCommit ->
                            GitCommit(
                                sha = revCommit.name,
                                shortMessage = revCommit.shortMessage,
                                authorName = revCommit.authorIdent.name,
                                timestamp = revCommit.authorIdent.whenAsInstant.toEpochMilli(),
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
                openGit(config.repoRoot).use { git ->
                    git.reset()
                        .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.MERGE)
                        .call()
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
            openGit(config.repoRoot).use { git ->
                val stage = when (side) {
                    MergeSide.LOCAL -> org.eclipse.jgit.api.CheckoutCommand.Stage.OURS
                    MergeSide.REMOTE -> org.eclipse.jgit.api.CheckoutCommand.Stage.THEIRS
                }
                git.checkout()
                    .setStage(stage)
                    .addPath(filePath.removePrefix("${config.repoRoot}/"))
                    .call()
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
                openGit(config.repoRoot).use { git ->
                    val relativePath = filePath.removePrefix("${config.repoRoot}/")
                    git.add().addFilepattern(relativePath).call()
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
                openGit(config.repoRoot).use { git ->
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
                val lockFile = File(config.repoRoot, ".git/index.lock")
                if (!lockFile.exists()) return@withContext Unit.right()

                val ageMs = System.currentTimeMillis() - lockFile.lastModified()
                return@withContext if (ageMs > 60_000L) {
                    if (lockFile.delete()) {
                        Unit.right()
                    } else {
                        DomainError.GitError.StaleLockFile(lockFile.absolutePath).left()
                    }
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

    private fun openGit(repoRoot: String): Git {
        return Git.open(File(repoRoot))
    }

    private fun configureAuthFromConfig(
        cmd: org.eclipse.jgit.api.TransportCommand<*, *>,
        config: GitConfig,
    ) {
        when (config.authType) {
            GitAuthType.HTTPS_TOKEN -> {
                val token = config.httpsTokenKey?.let { credentialStore.retrieve(it) } ?: return
                cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider("", token))
            }
            GitAuthType.SSH_KEY -> {
                val keyPath = config.sshKeyPath ?: return
                val sshFactory = SshdSessionFactoryBuilder()
                    .setPreferredAuthentications("publickey")
                    .setHomeDirectory(File(System.getProperty("user.home")))
                    .setSshDirectory(File(keyPath).parentFile ?: File(System.getProperty("user.home"), ".ssh"))
                    .build(null)
                cmd.setTransportConfigCallback { transport ->
                    if (transport is org.eclipse.jgit.transport.SshTransport) {
                        transport.sshSessionFactory = sshFactory
                    }
                }
            }
            GitAuthType.NONE -> {}
        }
    }

    private fun configureAuth(
        cmd: org.eclipse.jgit.api.TransportCommand<*, *>,
        auth: GitAuth,
    ) {
        when (auth) {
            is GitAuth.HttpsToken -> {
                // For clone, we can use blocking token retrieval since we're already in IO context
                val token = runCatching {
                    kotlinx.coroutines.runBlocking { auth.tokenProvider() }
                }.getOrNull() ?: return
                cmd.setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(auth.username, token)
                )
            }
            is GitAuth.SshKey -> {
                val sshFactory = SshdSessionFactoryBuilder()
                    .setPreferredAuthentications("publickey")
                    .setHomeDirectory(File(System.getProperty("user.home")))
                    .setSshDirectory(File(auth.keyPath).parentFile ?: File(System.getProperty("user.home"), ".ssh"))
                    .build(null)
                cmd.setTransportConfigCallback { transport ->
                    if (transport is org.eclipse.jgit.transport.SshTransport) {
                        transport.sshSessionFactory = sshFactory
                    }
                }
            }
            is GitAuth.None -> { /* no auth configuration needed */ }
        }
    }
}
