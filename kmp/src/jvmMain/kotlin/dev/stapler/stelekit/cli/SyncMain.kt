package dev.stapler.stelekit.cli

import arrow.core.Either
import arrow.core.getOrElse
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.lastNotesPath
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.LockVerdict
import dev.stapler.stelekit.git.OwnedOperationInfo
import dev.stapler.stelekit.git.OwnerHeartbeat
import dev.stapler.stelekit.git.RepoOpGate
import dev.stapler.stelekit.git.SyncOrigin
import dev.stapler.stelekit.git.buildCommitMessage
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.JvmGitRepository
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

// Thrown instead of exitProcess so that try/finally in runSync always calls graphManager.shutdown().
internal class SyncExitException(val code: Int) : Exception()

/** Another sync (the app or a second CLI) holds the repository; nothing was changed. */
internal const val EXIT_BUSY = 6

fun main(args: Array<String>) {
    val rootJob = Job()
    Runtime.getRuntime().addShutdownHook(Thread {
        rootJob.cancel()
        runBlocking { withTimeoutOrNull(5_000) { rootJob.join() } }
    })
    runBlocking(rootJob) {
        try {
            runSync(args)
        } catch (e: CancellationException) {
            // clean SIGINT path
        }
    }
}

suspend fun runSync(args: Array<String>) {
    val syncArgs = try {
        parseArgs(args)
    } catch (e: ArgParseException) {
        if (e.exitCode == 0) println(e.message) else System.err.println(e.message)
        exitProcess(e.exitCode)
    }

    val output = SyncOutput(syncArgs.jsonOutput)
    val fileSystem = PlatformFileSystem()
    val settings = PlatformSettings()

    val graphPath = resolveNotesPath(syncArgs, settings.lastNotesPath())
        .getOrElse { message -> output.error(message); exitProcess(4) }

    output.info("Syncing graph at: ${graphPath.directory()}")

    val credentialStore = CredentialStore()
    val gitRepository: GitRepository = JvmGitRepository(credentialStore)
    val graphManager = GraphManager(settings, DriverFactory(), fileSystem)

    var exitCode = 0
    try {
        runSyncWithManager(syncArgs, graphManager, gitRepository, output, graphPath, RepoOpGate())
    } catch (e: SyncExitException) {
        exitCode = e.code
    } finally {
        graphManager.shutdown()
    }
    if (exitCode != 0) exitProcess(exitCode)
}

internal suspend fun runSyncWithManager(
    syncArgs: SyncArgs,
    graphManager: GraphManager,
    gitRepository: GitRepository,
    output: SyncOutput,
    graphPath: EffectiveNotesPath,
    gate: RepoOpGate,
    heartbeatIntervalMs: Long = OwnerHeartbeat.DEFAULT_INTERVAL_MS,
) {
    val graphId = graphManager.addGraph(graphPath)
    graphManager.switchGraph(graphId)
    graphManager.awaitPendingMigration()

    val gitConfigRepo = graphManager.createGitConfigRepository()
    if (gitConfigRepo == null) {
        output.error("Could not open database for graph. Ensure the graph is valid.")
        throw SyncExitException(5)
    }

    val configResult = gitConfigRepo.getConfig(graphId.value)
    val config = when (configResult) {
        is Either.Left -> {
            output.error("Failed to load git config: ${configResult.value.message}")
            throw SyncExitException(5)
        }
        is Either.Right -> configResult.value
    }

    if (config == null) {
        output.error("No git sync config found for this graph. Set up sync in the app first.")
        output.result(SyncResult(graph = graphPath.directory(), branch = "", localCommits = 0, remoteCommits = 0, status = "no_config"))
        throw SyncExitException(4)
    }

    // STELEKIT_GIT_TOKEN is handled transparently by JvmCredentialStore.retrieve() as a fallback
    // when no stored credential is found — no explicit store() call needed here.

    if (syncArgs.dryRun) {
        runDryRun(gitRepository, config, output, graphPath)
        return
    }

    runOwned(gate, gitRepository, config, output, graphPath, heartbeatIntervalMs) {
        when {
            syncArgs.commitOnly -> runCommitOnly(gitRepository, config, output, graphPath)
            syncArgs.fetchOnly -> runFetchOnly(gitRepository, config, output, graphPath)
            else -> runFullSync(gitRepository, config, output, graphPath)
        }
    }
}

/**
 * Runs [body] only while this process owns the repository: the process gate, then the cross-process owner record
 * (the same claim protocol the app uses), with the heartbeat running. Anything but a clean claim exits without
 * touching the repository; an unknown owner never deletes a lock.
 */
internal suspend fun runOwned(
    gate: RepoOpGate,
    gitRepository: GitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: EffectiveNotesPath,
    heartbeatIntervalMs: Long = OwnerHeartbeat.DEFAULT_INTERVAL_MS,
    body: suspend () -> Unit,
) {
    val key = gitRepository.gateKeyFor(config)
    if (key == null) {
        output.error("Cannot resolve the git directory for this graph")
        output.result(SyncResult(graph = graphPath.directory(), branch = config.remoteBranch, localCommits = 0, remoteCommits = 0, status = "error"))
        throw SyncExitException(5)
    }
    val lease = gate.tryAcquire(key, SyncOrigin.Cli) ?: busy(output, graphPath, config)
    try {
        val verdict = gitRepository.beginOwnedOperation(config, OwnedOperationInfo("sync", SyncOrigin.Cli))
        verdict.onLeft { err ->
            if (err is DomainError.GitError.SyncInProgress) busy(output, graphPath, config)
            handleGitError(err, output, graphPath, config.remoteBranch)
        }
        when (val v = verdict.getOrElse { LockVerdict.NoRecord }) {
            LockVerdict.NoLock, LockVerdict.ClearedOwn, LockVerdict.ClearedDeadOwner -> Unit
            LockVerdict.OwnerAlive -> busy(output, graphPath, config)
            else -> {
                output.error("A git lock exists and its owner could not be confirmed dead ($v); nothing was deleted")
                output.result(SyncResult(graph = graphPath.directory(), branch = config.remoteBranch, localCommits = 0, remoteCommits = 0, status = "error"))
                throw SyncExitException(5)
            }
        }
        try {
            coroutineScope {
                val heartbeat = launch {
                    while (isActive) {
                        delay(heartbeatIntervalMs)
                        gitRepository.touchOwnedOperation(config)
                    }
                }
                try {
                    body()
                } finally {
                    heartbeat.cancelAndJoin()
                }
            }
        } finally {
            withContext(NonCancellable) { gitRepository.endOwnedOperation(config) }
        }
    } finally {
        gate.releaseOrHandOff(lease)
    }
}

private fun busy(output: SyncOutput, graphPath: EffectiveNotesPath, config: GitConfig): Nothing {
    output.error("Another sync is already running for this graph; try again when it finishes")
    output.result(SyncResult(graph = graphPath.directory(), branch = config.remoteBranch, localCommits = 0, remoteCommits = 0, status = "busy"))
    throw SyncExitException(EXIT_BUSY)
}

internal suspend fun runDryRun(
    gitRepository: GitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: EffectiveNotesPath,
) {
    output.info("Dry run mode — no changes will be made")
    val statusResult = gitRepository.status(config)
    val localChanges = (statusResult as? Either.Right)?.value?.hasLocalChanges ?: false
    output.info("Local changes: $localChanges")
    output.result(SyncResult(graph = graphPath.directory(), branch = config.remoteBranch, localCommits = 0, remoteCommits = 0, status = "success"))
}

/**
 * Stages the wiki subdirectory, then drops any staged file at an LFS-tracked path that is not a pointer
 * (reported, never committed raw). Returns false when nothing is left to commit after the hold-back.
 */
private suspend fun stageHoldingBackLfs(gitRepository: GitRepository, config: GitConfig, output: SyncOutput): Boolean {
    gitRepository.stageSubdir(config).onLeft { err ->
        output.error("Failed to stage changes: ${err.message}")
        throw SyncExitException(5)
    }
    val held = gitRepository.stagedLfsViolations(config)
    held.forEach { output.info("Held back ${it.path}: stored with Git LFS, not committed (SteleKit cannot upload it here yet)") }
    return held.isEmpty() || gitRepository.hasStagedChanges(config)
}

internal suspend fun runCommitOnly(
    gitRepository: GitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: EffectiveNotesPath,
) {
    output.info("Committing local changes...")
    val statusResult = gitRepository.status(config)
    if ((statusResult as? Either.Right)?.value?.hasLocalChanges == true) {
        val commitNeeded = stageHoldingBackLfs(gitRepository, config, output)
        if (commitNeeded) {
            gitRepository.commit(config, buildCommitMessage(config)).onLeft { err ->
                output.error("Failed to commit: ${err.message}")
                throw SyncExitException(5)
            }
            output.info("Committed local changes")
        }
        output.result(SyncResult(graph = graphPath.directory(), branch = config.remoteBranch, localCommits = if (commitNeeded) 1 else 0, remoteCommits = 0, status = "success"))
    } else {
        output.info("No local changes to commit")
        output.result(SyncResult(graph = graphPath.directory(), branch = config.remoteBranch, localCommits = 0, remoteCommits = 0, status = "success"))
    }
}

internal suspend fun runFetchOnly(
    gitRepository: GitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: EffectiveNotesPath,
) {
    output.info("Fetching remote changes...")
    when (val fetchResult = gitRepository.fetch(config)) {
        is Either.Left -> handleGitError(fetchResult.value, output, graphPath, config.remoteBranch)
        is Either.Right -> {
            val fr = fetchResult.value
            output.info("Remote commits: ${fr.remoteCommitCount}")
            output.result(SyncResult(graph = graphPath.directory(), branch = config.remoteBranch, localCommits = 0, remoteCommits = fr.remoteCommitCount, status = "success"))
        }
    }
}

internal suspend fun runFullSync(
    gitRepository: GitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: EffectiveNotesPath,
) {
    output.info("Starting full sync...")
    val localCommits = commitLocalChanges(gitRepository, config, output)
    val remoteCommits = fetchAndMerge(gitRepository, config, output, graphPath, localCommits) ?: return
    pushChanges(gitRepository, config, output, graphPath, localCommits, remoteCommits)
}

private suspend fun commitLocalChanges(
    gitRepository: GitRepository,
    config: GitConfig,
    output: SyncOutput,
): Int {
    val statusResult = gitRepository.status(config)
    return if ((statusResult as? Either.Right)?.value?.hasLocalChanges == true) {
        val commitNeeded = stageHoldingBackLfs(gitRepository, config, output)
        if (commitNeeded) {
            gitRepository.commit(config, buildCommitMessage(config)).onLeft { err ->
                output.error("Failed to commit: ${err.message}")
                throw SyncExitException(5)
            }
            output.info("Committed local changes")
        }
        if (commitNeeded) 1 else 0
    } else {
        0
    }
}

private suspend fun fetchAndMerge(
    gitRepository: GitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: EffectiveNotesPath,
    localCommits: Int,
): Int? {
    return when (val fetchResult = gitRepository.fetch(config)) {
        is Either.Left -> {
            handleGitError(fetchResult.value, output, graphPath, config.remoteBranch)
        }
        is Either.Right -> {
            val fr = fetchResult.value
            if (fr.hasRemoteChanges) {
                mergeRemoteChanges(gitRepository, config, output, graphPath, localCommits, fr.remoteCommitCount)
            } else {
                0
            }
        }
    }
}

private suspend fun mergeRemoteChanges(
    gitRepository: GitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: EffectiveNotesPath,
    localCommits: Int,
    remoteCommitCount: Int,
): Int? {
    return when (val mergeResult = gitRepository.merge(config)) {
        is Either.Left -> handleGitError(mergeResult.value, output, graphPath, config.remoteBranch)
        is Either.Right -> {
            val mr = mergeResult.value
            if (mr.hasConflicts) {
                output.error("Merge conflicts detected")
                mr.conflicts.forEach { output.error("Conflict: ${it.filePath}") }
                output.result(SyncResult(graph = graphPath.directory(), branch = config.remoteBranch, localCommits = localCommits, remoteCommits = 0, conflicts = mr.conflicts.map { it.filePath }, status = "conflicts"))
                throw SyncExitException(1)
            }
            remoteCommitCount
        }
    }
}

private suspend fun pushChanges(
    gitRepository: GitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: EffectiveNotesPath,
    localCommits: Int,
    remoteCommits: Int,
) {
    when (val pushResult = gitRepository.push(config)) {
        is Either.Left -> handleGitError(pushResult.value, output, graphPath, config.remoteBranch)
        is Either.Right -> {
            output.info("Sync complete. Local: $localCommits, Remote: $remoteCommits")
            output.result(SyncResult(graph = graphPath.directory(), branch = config.remoteBranch, localCommits = localCommits, remoteCommits = remoteCommits, status = "success"))
        }
    }
}

internal fun handleGitError(
    err: DomainError.GitError,
    output: SyncOutput,
    graphPath: EffectiveNotesPath,
    branch: String,
): Nothing {
    val (status, exitCode) = when (err) {
        is DomainError.GitError.AuthFailed -> "auth_error" to 2
        is DomainError.GitError.Offline -> "network_error" to 3
        is DomainError.GitError.FetchFailed -> "network_error" to 3
        else -> "error" to 5
    }
    output.error(err.message)
    output.result(SyncResult(graph = graphPath.directory(), branch = branch, localCommits = 0, remoteCommits = 0, status = status))
    throw SyncExitException(exitCode)
}
