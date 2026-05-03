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
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.model.wikiRoot
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.NetworkMonitor
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
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Owns its own scope — never accept rememberCoroutineScope()
    private val scope = CoroutineScope(SupervisorJob() + PlatformDispatcher.IO)

    private var periodicSyncJob: Job? = null

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
                    _syncState.value = SyncState.Error(r.value)
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
                    graphLoader.reloadFiles(mergeResult.changedFiles)
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
                    _syncState.value = SyncState.Error(result.value)
                    result.value.left()
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
            graphLoader.reloadFiles(resolvedPaths)
        } finally {
            graphLoader.endGitMerge()
        }

        _syncState.value = SyncState.Idle
        Unit.right()
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

    private fun buildCommitMessage(config: GitConfig, isMerge: Boolean = false): String {
        val date = Clock.System.now().toString().take(10) // yyyy-MM-dd
        val base = config.commitMessageTemplate
            .replace("{date}", date)
        return if (isMerge) "$base (merge)" else base
    }
}

/**
 * Resolution data passed to [GitSyncService.resolveConflict].
 * Maps file path → list of resolved hunks.
 */
data class ConflictResolution(
    val fileResolutions: Map<String, List<dev.stapler.stelekit.git.model.ConflictHunk>>,
)
