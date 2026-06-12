package dev.stapler.stelekit.cli

import arrow.core.Either
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.CredentialStore
import dev.stapler.stelekit.git.JvmGitRepository
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val rootJob = Job()
    Runtime.getRuntime().addShutdownHook(Thread {
        rootJob.cancel()
        runBlocking { rootJob.join() }
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
        System.err.println(e.message)
        exitProcess(e.exitCode)
    }

    val output = SyncOutput(syncArgs.jsonOutput)
    val fileSystem = PlatformFileSystem()
    val settings = PlatformSettings()

    val graphPath = syncArgs.graphPath
        ?: settings.getString("lastGraphPath", "").takeIf { it.isNotEmpty() }
        ?: run {
            output.error("No graph path specified and no last opened graph found. Use --graph <path>.")
            exitProcess(4)
        }

    output.info("Syncing graph at: $graphPath")

    val credentialStore = CredentialStore()
    val gitRepository = JvmGitRepository(credentialStore)
    val graphManager = GraphManager(settings, DriverFactory(), fileSystem)
    val graphId = graphManager.addGraph(graphPath)
    graphManager.switchGraph(graphId)
    graphManager.awaitPendingMigration()

    val gitConfigRepo = graphManager.createGitConfigRepository()
    if (gitConfigRepo == null) {
        output.error("Could not open database for graph. Ensure the graph is valid.")
        exitProcess(5)
    }

    val configResult = gitConfigRepo.getConfig(graphId)
    val config = when (configResult) {
        is Either.Left -> {
            output.error("Failed to load git config: ${configResult.value.message}")
            exitProcess(5)
        }
        is Either.Right -> configResult.value
    }

    if (config == null) {
        output.error("No git sync config found for this graph. Set up sync in the app first.")
        output.result(SyncResult(graph = graphPath, branch = "", localCommits = 0, remoteCommits = 0, status = "no_config"))
        exitProcess(4)
    }

    // Env var token override for CI/headless usage
    val envToken = System.getenv("STELEKIT_GIT_TOKEN")
    if (envToken != null && config.httpsTokenKey != null) {
        credentialStore.store(config.httpsTokenKey, envToken)
    }

    if (syncArgs.dryRun) {
        runDryRun(gitRepository, config, output, graphPath)
        graphManager.shutdown()
        return
    }

    when {
        syncArgs.commitOnly -> runCommitOnly(gitRepository, config, output, graphPath)
        syncArgs.fetchOnly -> runFetchOnly(gitRepository, config, output, graphPath)
        else -> runFullSync(gitRepository, config, output, graphPath)
    }
    graphManager.shutdown()
}

private suspend fun runDryRun(
    gitRepository: JvmGitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: String,
) {
    output.info("Dry run mode — no changes will be made")
    val statusResult = gitRepository.status(config)
    val localChanges = (statusResult as? Either.Right)?.value?.hasLocalChanges ?: false
    output.info("Local changes: $localChanges")
    output.result(SyncResult(graph = graphPath, branch = config.remoteBranch, localCommits = 0, remoteCommits = 0, status = "success"))
}

private suspend fun runCommitOnly(
    gitRepository: JvmGitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: String,
) {
    output.info("Committing local changes...")
    val statusResult = gitRepository.status(config)
    if ((statusResult as? Either.Right)?.value?.hasLocalChanges == true) {
        gitRepository.stageSubdir(config)
        gitRepository.commit(config, "SteleKit: ${java.time.LocalDate.now()}")
        output.info("Committed local changes")
        output.result(SyncResult(graph = graphPath, branch = config.remoteBranch, localCommits = 1, remoteCommits = 0, status = "success"))
    } else {
        output.info("No local changes to commit")
        output.result(SyncResult(graph = graphPath, branch = config.remoteBranch, localCommits = 0, remoteCommits = 0, status = "success"))
    }
}

private suspend fun runFetchOnly(
    gitRepository: JvmGitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: String,
) {
    output.info("Fetching remote changes...")
    when (val fetchResult = gitRepository.fetch(config)) {
        is Either.Left -> handleGitError(fetchResult.value, output, graphPath, config.remoteBranch)
        is Either.Right -> {
            val fr = fetchResult.value
            output.info("Remote commits: ${fr.remoteCommitCount}")
            output.result(SyncResult(graph = graphPath, branch = config.remoteBranch, localCommits = 0, remoteCommits = fr.remoteCommitCount, status = "success"))
        }
    }
}

private suspend fun runFullSync(
    gitRepository: JvmGitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: String,
) {
    output.info("Starting full sync...")
    val localCommits = commitLocalChanges(gitRepository, config, output)
    val remoteCommits = fetchAndMerge(gitRepository, config, output, graphPath, localCommits) ?: return
    pushChanges(gitRepository, config, output, graphPath, localCommits, remoteCommits)
}

private suspend fun commitLocalChanges(
    gitRepository: JvmGitRepository,
    config: GitConfig,
    output: SyncOutput,
): Int {
    val statusResult = gitRepository.status(config)
    return if ((statusResult as? Either.Right)?.value?.hasLocalChanges == true) {
        gitRepository.stageSubdir(config)
        gitRepository.commit(config, "SteleKit: ${java.time.LocalDate.now()}")
        output.info("Committed local changes")
        1
    } else {
        0
    }
}

private suspend fun fetchAndMerge(
    gitRepository: JvmGitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: String,
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
    gitRepository: JvmGitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: String,
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
                output.result(SyncResult(graph = graphPath, branch = config.remoteBranch, localCommits = localCommits, remoteCommits = 0, conflicts = mr.conflicts.map { it.filePath }, status = "conflicts"))
                exitProcess(1)
            }
            remoteCommitCount
        }
    }
}

private suspend fun pushChanges(
    gitRepository: JvmGitRepository,
    config: GitConfig,
    output: SyncOutput,
    graphPath: String,
    localCommits: Int,
    remoteCommits: Int,
) {
    when (val pushResult = gitRepository.push(config)) {
        is Either.Left -> handleGitError(pushResult.value, output, graphPath, config.remoteBranch)
        is Either.Right -> {
            output.info("Sync complete. Local: $localCommits, Remote: $remoteCommits")
            output.result(SyncResult(graph = graphPath, branch = config.remoteBranch, localCommits = localCommits, remoteCommits = remoteCommits, status = "success"))
        }
    }
}

private fun handleGitError(
    err: DomainError.GitError,
    output: SyncOutput,
    graphPath: String,
    branch: String,
): Nothing {
    val (status, exitCode) = when (err) {
        is DomainError.GitError.AuthFailed -> "auth_error" to 2
        is DomainError.GitError.Offline -> "network_error" to 3
        is DomainError.GitError.FetchFailed -> "network_error" to 3
        else -> "error" to 5
    }
    output.error(err.message)
    output.result(SyncResult(graph = graphPath, branch = branch, localCommits = 0, remoteCommits = 0, status = status))
    exitProcess(exitCode)
}
