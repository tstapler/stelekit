// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.cli.EXIT_BUSY
import dev.stapler.stelekit.cli.SyncExitException
import dev.stapler.stelekit.cli.SyncOutput
import dev.stapler.stelekit.cli.runFullSync
import dev.stapler.stelekit.cli.runOwned
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.platform.AppPrivateFileSystem
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Child JVM of `CrossProcessSyncOverDeadRecordTest`; line protocol on stdin/stdout, other stdout lines are logs.
 *
 * `seed <repoDir>`: claims the repository through the real owner-record protocol, leaves an `index.lock`, prints
 * `SEEDED` and blocks until the parent kills it (so the record and lock outlive a dead owner).
 * `service` / `cli`: per `ROUND <repoDir>` line prints `READY`, waits for `GO`, then runs one sync through
 * `GitSyncService.sync` (the app) or `runOwned` + `runFullSync` (SyncMain) and prints
 * `RESULT SYNCED`, `RESULT COALESCED` or `RESULT OTHER <detail>`.
 */
object CrossProcessSyncChildMain {
    private const val GRAPH_ID = "g"

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "seed" -> seed(File(args[1]))
            "service", "cli" -> rounds(args[0])
            else -> error("unknown mode ${args.toList()}")
        }
    }

    private fun configFor(repoDir: File) =
        GitConfig(graphId = GRAPH_ID, repoRoot = RepoRoot(repoDir.absolutePath), wikiSubdir = null, authType = GitAuthType.NONE)

    private fun seed(repoDir: File) = runBlocking {
        val verdict = JvmGitRepository().beginOwnedOperation(configFor(repoDir), OwnedOperationInfo("sync", SyncOrigin.UiPress))
        check(verdict.getOrNull() == LockVerdict.NoLock) { "seed claim was $verdict" }
        File(repoDir, ".git/index.lock").writeText("held by the seed process")
        println("SEEDED")
        System.out.flush()
        System.`in`.read()
    }

    private fun rounds(mode: String) {
        val input = System.`in`.bufferedReader()
        while (true) {
            val line = input.readLine() ?: return
            if (!line.startsWith("ROUND ")) continue
            val repoDir = File(line.removePrefix("ROUND "))
            println("READY")
            System.out.flush()
            while (input.readLine() != "GO") Unit
            println(runBlocking { runOnce(mode, repoDir) })
            System.out.flush()
        }
    }

    private suspend fun runOnce(mode: String, repoDir: File): String = try {
        if (mode == "service") serviceSync(repoDir) else cliSync(repoDir)
    } catch (e: SyncExitException) {
        if (e.code == EXIT_BUSY) "RESULT COALESCED" else "RESULT OTHER exit ${e.code}"
    } catch (e: Throwable) {
        "RESULT OTHER ${e::class.simpleName}: ${e.message}"
    }

    private suspend fun serviceSync(repoDir: File): String {
        val fs = AppPrivateFileSystem()
        val service = GitSyncService(
            gitRepository = JvmGitRepository(),
            graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = EditLock(),
            configRepository = StubConfigRepository(configFor(repoDir).right()),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            isOnline = { true },
            gate = RepoOpGate(),
        )
        return when (val result = service.sync(GRAPH_ID)) {
            is Either.Right -> "RESULT SYNCED"
            is Either.Left -> if (result.value is DomainError.GitError.SyncInProgress) "RESULT COALESCED" else "RESULT OTHER ${result.value.message}"
        }
    }

    private suspend fun cliSync(repoDir: File): String {
        val config = configFor(repoDir)
        val repository = JvmGitRepository()
        val output = SyncOutput(jsonMode = true)
        val notes = EffectiveNotesPath(repoDir.absolutePath)
        runOwned(RepoOpGate(), repository, config, output, notes) { runFullSync(repository, config, output, notes) }
        return "RESULT SYNCED"
    }
}
