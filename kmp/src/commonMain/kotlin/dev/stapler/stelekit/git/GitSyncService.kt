// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.git.merge.JournalMergeService
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.model.wikiRoot
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Orchestrates the full git sync cycle: commit local changes, fetch from remote,
 * merge, push, and reload changed files into the database.
 *
 * Owns its own [CoroutineScope] — never accept rememberCoroutineScope().
 *
 * Created and owned by [GraphManager]; one instance per active graph.
 */
class GitSyncService(
    private val gitRepository: GitRepository,
    private val graphLoader: GraphLoader,
    private val graphWriter: GraphWriter,
    private val editLock: EditLock,
    private val configRepository: GitConfigRepository,
    private val networkMonitor: NetworkMonitor,
    private val fileSystem: FileSystem,
    /** Returns the active [CredentialAccess] for checking vault availability before sync. Null means always available. */
    private val credentialAccessProvider: (() -> CredentialAccess)? = null,
    private val journalMergeService: JournalMergeService? = null,
) {
    private val logger = Logger("GitSyncService")

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Owns its own scope — never accept rememberCoroutineScope()
    // CoroutineExceptionHandler guards against uncaught Throwable crashing the Android process.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.error("GitSyncService uncaught error", throwable)
        _syncState.value = SyncState.Error(
            DomainError.GitError.FetchFailed("Unexpected error: ${throwable.message}")
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + PlatformDispatcher.IO + exceptionHandler)

    @kotlin.concurrent.Volatile private var periodicSyncJob: Job? = null

    /**
     * Full sync sequence:
     * 1. Network check
     * 2. Load config
     * 3. Safety checks (detached HEAD, stale lock)
     * 4. Flush pending writes + await idle editing
     * 5. Commit local changes
     * 6. Fetch
     * 7. Merge (if remote changes available) — with watcher suppression
     * 8. Reload merged files
     * 9. Push
     */
    suspend fun sync(graphId: String): Either<DomainError.GitError, SyncState.Success> =
        withContext(PlatformDispatcher.IO) {
            // 1. Network check
            if (!networkMonitor.isOnline) {
                val err = DomainError.GitError.Offline
                _syncState.value = SyncState.Error(err)
                return@withContext err.left()
            }

            // 1.5 Check credential availability (vault may be locked)
            val credAccess = try { credentialAccessProvider?.invoke() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (credAccess != null && !credAccess.isAvailable()) {
                _syncState.value = SyncState.CredentialVaultLocked
                return@withContext DomainError.GitError.AuthFailed("Vault is locked — unlock the vault to resume sync").left()
            }

            // 2. Load config
            val config = when (val result = configRepository.getConfig(graphId)) {
                is Either.Left -> {
                    val err = DomainError.GitError.FetchFailed("Failed to load git config: ${result.value.message}")
                    _syncState.value = SyncState.Error(err)
                    return@withContext err.left()
                }
                is Either.Right -> result.value
            } ?: run {
                // No config — nothing to sync
                return@withContext SyncState.Success(0, 0, Clock.System.now().toEpochMilliseconds()).right()
            }

            // 3a. Detached HEAD check
            if (gitRepository.hasDetachedHead(config)) {
                val err = DomainError.GitError.DetachedHead(config.repoRoot)
                _syncState.value = SyncState.Error(err)
                return@withContext err.left()
            }

            // 3b. Remove stale lock file
            gitRepository.removeStaleLockFile(config).onLeft { lockErr ->
                _syncState.value = SyncState.Error(lockErr)
                return@withContext lockErr.left()
            }

            // 4. Flush pending writes + await idle editing
            graphWriter.flush()
            editLock.awaitIdle()

            // 5. Commit local changes
            var localCommitsMade = 0
            _syncState.value = SyncState.Committing
            val statusResult = gitRepository.status(config)
            if (statusResult is Either.Right && statusResult.value.hasLocalChanges) {
                gitRepository.stageSubdir(config).onLeft { err ->
                    _syncState.value = SyncState.Error(err)
                    return@withContext err.left()
                }
                val message = buildCommitMessage(config)
                gitRepository.commit(config, message).onLeft { err ->
                    _syncState.value = SyncState.Error(err)
                    return@withContext err.left()
                }
                localCommitsMade = 1
            }

            // 6. Fetch
            _syncState.value = SyncState.Fetching
            val fetchResult = when (val r = gitRepository.fetch(config)) {
                is Either.Left -> {
                    val err = r.value
                    if (err is DomainError.GitError.AuthFailed && config.authType == GitAuthType.GITHUB_OAUTH) {
                        _syncState.value = SyncState.CredentialExpired(graphId)
                    } else {
                        _syncState.value = SyncState.Error(err)
                    }
                    return@withContext r.value.left()
                }
                is Either.Right -> r.value
            }

            // 7. Merge (if remote changes available)
            var remoteCommitsMerged = 0
            if (fetchResult.hasRemoteChanges) {
                _syncState.value = SyncState.Merging
                val mergeResult = when (val r = gitRepository.merge(config)) {
                    is Either.Left -> {
                        _syncState.value = SyncState.Error(r.value)
                        return@withContext r.value.left()
                    }
                    is Either.Right -> r.value
                }

                if (mergeResult.hasConflicts) {
                    // Try algorithmic journal merge for journal files
                    val journalConflicts = mergeResult.conflicts.filter {
                        JournalMergeService.isJournalFile(
                            it.filePath.substringAfterLast('/').substringAfterLast('\\')
                        )
                    }
                    // Only attempt algorithmic merge for a single journal conflict — multiple
                    // conflicts require sequential resolution and must go through the manual screen.
                    if (journalConflicts.size == 1 && journalMergeService != null) {
                        val proposal = try {
                            journalMergeService.propose(journalConflicts.first(), config.wikiRoot)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        if (proposal != null) {
                            _syncState.value = SyncState.JournalMergeReady(graphId, proposal)
                            val conflictErr = DomainError.GitError.MergeConflict(
                                conflictCount = mergeResult.conflicts.size,
                                conflictPaths = mergeResult.conflicts.map { it.filePath },
                            )
                            return@withContext conflictErr.left()
                        }
                    }
                    val conflictErr = DomainError.GitError.MergeConflict(
                        conflictCount = mergeResult.conflicts.size,
                        conflictPaths = mergeResult.conflicts.map { it.filePath },
                    )
                    _syncState.value = SyncState.ConflictPending(mergeResult.conflicts)
                    return@withContext conflictErr.left()
                }

                // 8. Reload merged files with watcher suppression
                graphLoader.beginGitMerge(mergeResult.changedFiles)
                try {
                    graphLoader.reloadFiles(mergeResult.changedFiles.map { FilePath(it) })
                } finally {
                    graphLoader.endGitMerge()
                }

                remoteCommitsMerged = fetchResult.remoteCommitCount
            }

            // 9. Push
            _syncState.value = SyncState.Pushing
            gitRepository.push(config).onLeft { err ->
                _syncState.value = SyncState.Error(err)
                return@withContext err.left()
            }

            val success = SyncState.Success(
                localCommitsMade = localCommitsMade,
                remoteCommitsMerged = remoteCommitsMerged,
                lastSyncAt = Clock.System.now().toEpochMilliseconds(),
            )
            _syncState.value = success
            success.right()
        }

    /**
     * Fetches from remote only — does not merge or push.
     * Emits [SyncState.MergeAvailable] if remote changes were found.
     * Used by background schedulers and manual "check for updates" actions.
     */
    suspend fun fetchOnly(graphId: String): Either<DomainError.GitError, FetchResult> =
        withContext(PlatformDispatcher.IO) {
            if (!networkMonitor.isOnline) {
                val err = DomainError.GitError.Offline
                _syncState.value = SyncState.Error(err)
                return@withContext err.left()
            }

            val credAccess = try { credentialAccessProvider?.invoke() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (credAccess != null && !credAccess.isAvailable()) {
                _syncState.value = SyncState.CredentialVaultLocked
                return@withContext DomainError.GitError.AuthFailed("Vault is locked").left()
            }

            val config = when (val result = configRepository.getConfig(graphId)) {
                is Either.Left -> {
                    return@withContext DomainError.GitError.FetchFailed(
                        "Failed to load git config: ${result.value.message}"
                    ).left()
                }
                is Either.Right -> result.value
            } ?: return@withContext FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()

            _syncState.value = SyncState.Fetching
            when (val result = gitRepository.fetch(config)) {
                is Either.Left -> {
                    val err = result.value
                    if (err is DomainError.GitError.AuthFailed && config.authType == GitAuthType.GITHUB_OAUTH) {
                        _syncState.value = SyncState.CredentialExpired(graphId)
                    } else {
                        _syncState.value = SyncState.Error(err)
                    }
                    err.left()
                }
                is Either.Right -> {
                    val fetchResult = result.value
                    _syncState.value = if (fetchResult.hasRemoteChanges) {
                        SyncState.MergeAvailable(fetchResult.remoteCommitCount)
                    } else {
                        SyncState.Idle
                    }
                    fetchResult.right()
                }
            }
        }

    /**
     * Stages and commits any outstanding local changes in the wiki subdirectory.
     * Returns the commit SHA on success, or null if there was nothing to commit.
     */
    suspend fun commitLocalChanges(graphId: String): Either<DomainError.GitError, String?> =
        withContext(PlatformDispatcher.IO) {
            val config = when (val result = configRepository.getConfig(graphId)) {
                is Either.Left -> return@withContext DomainError.GitError.CommitFailed(
                    result.value.message
                ).left()
                is Either.Right -> result.value
            } ?: return@withContext (null as String?).right()

            val status = when (val r = gitRepository.status(config)) {
                is Either.Left -> return@withContext r.value.left()
                is Either.Right -> r.value
            }

            if (!status.hasLocalChanges) return@withContext (null as String?).right()

            _syncState.value = SyncState.Committing
            gitRepository.stageSubdir(config).onLeft { return@withContext it.left() }
            val sha = when (val r = gitRepository.commit(config, buildCommitMessage(config))) {
                is Either.Left -> return@withContext r.value.left()
                is Either.Right -> r.value
            }
            _syncState.value = SyncState.Idle
            sha.right()
        }

    /**
     * Applies conflict resolutions to disk, marks files as resolved, and completes
     * the merge commit. Used by the ConflictResolutionScreen "Finish Merge" flow.
     */
    suspend fun resolveConflict(
        graphId: String,
        resolution: ConflictResolution,
    ): Either<DomainError.GitError, Unit> = withContext(PlatformDispatcher.IO) {
        val config = when (val result = configRepository.getConfig(graphId)) {
            is Either.Left -> return@withContext DomainError.GitError.CommitFailed(
                result.value.message
            ).left()
            is Either.Right -> result.value
        } ?: return@withContext DomainError.GitError.CommitFailed("No git config for $graphId").left()

        val resolver = ConflictResolver()
        for ((filePath, hunks) in resolution.fileResolutions) {
            val content = fileSystem.readFile(filePath)
                ?: return@withContext DomainError.GitError.CommitFailed(
                    "Cannot read conflicted file: $filePath"
                ).left()

            val resolvedContent = when (val r = resolver.applyResolutions(content, hunks)) {
                is Either.Left -> return@withContext r.value.left()
                is Either.Right -> r.value
            }

            val wrote = fileSystem.writeFile(filePath, resolvedContent)
            if (!wrote) {
                return@withContext DomainError.GitError.CommitFailed(
                    "Failed to write resolved content to: $filePath"
                ).left()
            }

            gitRepository.markResolved(config, filePath).onLeft { return@withContext it.left() }
        }

        // Commit the merge
        val message = buildCommitMessage(config, isMerge = true)
        gitRepository.commit(config, message).onLeft { return@withContext it.left() }

        // Reload resolved files
        val resolvedPaths = resolution.fileResolutions.keys.toList()
        graphLoader.beginGitMerge(resolvedPaths)
        try {
            graphLoader.reloadFiles(resolvedPaths.map { FilePath(it) })
        } finally {
            graphLoader.endGitMerge()
        }

        _syncState.value = SyncState.Idle
        Unit.right()
    }

    /**
     * Resolves each conflicting file by accepting either the local or remote side,
     * then commits the merge. Simpler than [resolveConflict] — no hunk-level parsing needed.
     */
    suspend fun resolveConflictBySide(
        graphId: String,
        fileResolutions: Map<String, MergeSide>,
    ): Either<DomainError.GitError, Unit> = withContext(PlatformDispatcher.IO) {
        val config = when (val result = configRepository.getConfig(graphId)) {
            is Either.Left -> return@withContext DomainError.GitError.CommitFailed(
                result.value.message
            ).left()
            is Either.Right -> result.value
        } ?: return@withContext DomainError.GitError.CommitFailed("No git config for $graphId").left()

        for ((filePath, side) in fileResolutions) {
            gitRepository.checkoutFile(config, filePath, side).onLeft { return@withContext it.left() }
            gitRepository.markResolved(config, filePath).onLeft { return@withContext it.left() }
        }

        val message = buildCommitMessage(config, isMerge = true)
        gitRepository.commit(config, message).onLeft { return@withContext it.left() }

        val resolvedPaths = fileResolutions.keys.toList()
        graphLoader.beginGitMerge(resolvedPaths)
        try {
            graphLoader.reloadFiles(resolvedPaths.map { FilePath(it) })
        } finally {
            graphLoader.endGitMerge()
        }

        _syncState.value = SyncState.Idle
        Unit.right()
    }

    /**
     * Applies an algorithmically-merged journal file: writes [mergedContent] to disk,
     * marks the file as resolved, commits the merge, reloads the page into the DB,
     * and pushes.  Called after the user approves the [JournalMergeReviewScreen].
     */
    suspend fun applyJournalMerge(
        graphId: String,
        filePath: String,
        mergedContent: String,
    ): Either<DomainError.GitError, Unit> = withContext(PlatformDispatcher.IO) {
        val config = when (val result = configRepository.getConfig(graphId)) {
            is Either.Left -> return@withContext DomainError.GitError.CommitFailed(
                result.value.message
            ).left()
            is Either.Right -> result.value
        } ?: return@withContext DomainError.GitError.CommitFailed("No git config for $graphId").left()

        if (!fileSystem.writeFile(filePath, mergedContent)) {
            return@withContext DomainError.GitError.CommitFailed(
                "Failed to write merged content to: $filePath"
            ).left()
        }

        gitRepository.markResolved(config, filePath).onLeft { return@withContext it.left() }

        val message = buildCommitMessage(config, isMerge = true)
        gitRepository.commit(config, message).onLeft { return@withContext it.left() }

        graphLoader.beginGitMerge(listOf(filePath))
        try {
            graphLoader.reloadFiles(listOf(dev.stapler.stelekit.model.FilePath(filePath)))
        } finally {
            graphLoader.endGitMerge()
        }

        _syncState.value = SyncState.Pushing
        gitRepository.push(config).onLeft { err ->
            _syncState.value = SyncState.Error(err)
            return@withContext err.left()
        }

        _syncState.value = SyncState.Success(
            localCommitsMade = 0,
            remoteCommitsMerged = 1,
            lastSyncAt = Clock.System.now().toEpochMilliseconds(),
        )
        Unit.right()
    }

    /**
     * Aborts an in-progress git merge, leaving the repository in its pre-merge state.
     * Call this when the user cancels conflict resolution.
     */
    suspend fun abortActiveMerge(graphId: String): Either<DomainError.GitError, Unit> =
        withContext(PlatformDispatcher.IO) {
            val config = when (val result = configRepository.getConfig(graphId)) {
                is Either.Left -> return@withContext DomainError.GitError.CommitFailed(
                    result.value.message
                ).left()
                is Either.Right -> result.value
            } ?: return@withContext Unit.right()

            gitRepository.abortMerge(config).also { result ->
                if (result.isRight()) _syncState.value = SyncState.Idle
            }
        }

    /**
     * Starts a periodic sync loop that calls [fetchOnly] every [intervalMinutes] minutes.
     * Cancels any existing periodic sync before starting.
     */
    fun startPeriodicSync(graphId: String, intervalMinutes: Int) {
        stopPeriodicSync()
        if (intervalMinutes <= 0) return
        periodicSyncJob = scope.launch {
            while (true) {
                delay(intervalMinutes * 60_000L)
                fetchOnly(graphId)
            }
        }
    }

    /** Cancels the periodic sync loop without shutting down the service. */
    fun stopPeriodicSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = null
    }

    /** Shuts down this service, cancelling all coroutines. */
    fun shutdown() {
        scope.cancel()
    }

}

/**
 * Resolution data passed to [GitSyncService.resolveConflict].
 * Maps file path → list of resolved hunks.
 */
data class ConflictResolution(
    val fileResolutions: Map<String, List<dev.stapler.stelekit.git.model.ConflictHunk>>,
)
