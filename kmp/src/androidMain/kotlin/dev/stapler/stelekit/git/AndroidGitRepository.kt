// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.transport.JschConfigSessionFactory
import org.eclipse.jgit.transport.OpenSshConfig
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.FS
import java.io.File

/**
 * Android implementation of GitRepository using JGit 5.13.x + mwiede/jsch for SSH.
 * All I/O runs on PlatformDispatcher.IO.
 *
 * @param sshKeyProvider Optional provider for SSH private key bytes, used for
 *                       configurable key loading (from user-configured path or Android storage).
 */
class AndroidGitRepository(
    private val sshKeyProvider: (() -> ByteArray)? = null,
) : GitRepository {

    override suspend fun isGitRepo(path: String): Boolean = withContext(PlatformDispatcher.IO) {
        File(path, ".git").exists()
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
                openGit(config.repoRoot).use { git ->
                    val statusResult = git.status()
                        .also { cmd ->
                            if (config.wikiSubdir.isNotEmpty()) {
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
                openGit(config.repoRoot).use { git ->
                    val pattern = if (config.wikiSubdir.isEmpty()) "." else "${config.wikiSubdir}/"
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
                openGit(config.repoRoot).use { git ->
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
                openGit(config.repoRoot).use { git ->
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
                                hunks = emptyList(),
                            )
                        } ?: emptyList()
                    } else {
                        emptyList()
                    }

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

                    MergeResult(
                        hasConflicts = hasConflicts,
                        conflicts = conflictFiles,
                        changedFiles = changedFiles,
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
                openGit(config.repoRoot).use { git ->
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
                    git.add().addFilepattern(filePath.removePrefix("${config.repoRoot}/")).call()
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

    private fun openGit(repoRoot: String): Git = Git.open(File(repoRoot))

    private fun buildJschSessionFactory(keyPath: String): JschConfigSessionFactory {
        return object : JschConfigSessionFactory() {
            override fun configure(host: OpenSshConfig.Host, session: Session) {
                session.setConfig("StrictHostKeyChecking", "accept-new")
            }

            override fun createDefaultJSch(fs: FS): JSch {
                val jsch = super.createDefaultJSch(fs)
                val keyBytes = sshKeyProvider?.invoke()
                if (keyBytes != null) {
                    jsch.addIdentity("stelekit-key", keyBytes, null, null)
                } else if (keyPath.isNotEmpty()) {
                    jsch.addIdentity(keyPath)
                }
                return jsch
            }
        }
    }

    private fun configureTransport(
        cmd: org.eclipse.jgit.api.TransportCommand<*, *>,
        config: GitConfig,
    ) {
        // Defer to configureAuth with None since we configure per-operation via transport callback
        cmd.setTransportConfigCallback { transport ->
            if (transport is org.eclipse.jgit.transport.SshTransport && config.sshKeyPath != null) {
                transport.sshSessionFactory = buildJschSessionFactory(config.sshKeyPath)
            }
        }
    }

    private fun configureAuth(
        cmd: org.eclipse.jgit.api.TransportCommand<*, *>,
        auth: GitAuth,
    ) {
        when (auth) {
            is GitAuth.HttpsToken -> {
                val token = runCatching {
                    kotlinx.coroutines.runBlocking { auth.tokenProvider() }
                }.getOrNull() ?: return
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
}
