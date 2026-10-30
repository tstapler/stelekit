// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.cli

import dev.stapler.stelekit.model.RepoRoot
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.FetchResult
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitStatus
import dev.stapler.stelekit.git.MergeResult
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The JSON `graph` field of every sync result is the notes directory — the contract the typed path carries. */
class SyncCommandsTest {

    private val notesDir = "/graphs/notes/logseq"
    private val notesPath = EffectiveNotesPath(notesDir)
    private val config = GitConfig(graphId = "g", repoRoot = RepoRoot("/graphs/notes"), wikiSubdir = "logseq", authType = GitAuthType.NONE)
    private val json = SyncOutput(jsonMode = true)

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private val originalOut = System.out
    private val originalErr = System.err
    private val captured = ByteArrayOutputStream()

    @BeforeTest
    fun captureStreams() {
        System.setOut(PrintStream(captured, true))
        System.setErr(PrintStream(ByteArrayOutputStream(), true))
    }

    @AfterTest
    fun restoreStreams() {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    private fun result(): JsonObject =
        Json.parseToJsonElement(captured.toString().lines().last { it.startsWith("{") }).jsonObject

    private fun JsonObject.field(name: String) = getValue(name).jsonPrimitive.content

    private fun repo(
        status: GitStatus = GitStatus(hasLocalChanges = false, untrackedFiles = emptyList(), modifiedFiles = emptyList()),
        fetch: Either<DomainError.GitError, FetchResult> = FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right(),
        merge: Either<DomainError.GitError, MergeResult> = MergeResult(false, emptyList(), emptyList()).right(),
        push: Either<DomainError.GitError, Unit> = Unit.right(),
    ): GitRepository = object : StubGitRepository() {
        override suspend fun status(config: GitConfig) = status.right()
        override suspend fun fetch(config: GitConfig) = fetch
        override suspend fun merge(config: GitConfig) = merge
        override suspend fun push(config: GitConfig) = push
        override suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String>) = Unit.right()
        override suspend fun commit(config: GitConfig, message: String) = "abc123".right()
    }

    private val dirty = GitStatus(hasLocalChanges = true, untrackedFiles = emptyList(), modifiedFiles = listOf("a.md"))

    @Test
    fun runDryRun_should_ReportTheNotesDirectory() = runTest {
        runDryRun(repo(), config, json, notesPath)

        assertEquals(notesDir, result().field("graph"))
        assertEquals("success", result().field("status"))
    }

    @Test
    fun runCommitOnly_should_CountOneLocalCommit_When_ThereAreLocalChanges() = runTest {
        runCommitOnly(repo(status = dirty), config, json, notesPath)

        assertEquals(notesDir, result().field("graph"))
        assertEquals("1", result().field("localCommits"))
    }

    @Test
    fun runCommitOnly_should_CountNoCommits_When_TheTreeIsClean() = runTest {
        runCommitOnly(repo(), config, json, notesPath)

        assertEquals(notesDir, result().field("graph"))
        assertEquals("0", result().field("localCommits"))
    }

    @Test
    fun runFetchOnly_should_ReportRemoteCommits() = runTest {
        runFetchOnly(repo(fetch = FetchResult(true, 3).right()), config, json, notesPath)

        assertEquals(notesDir, result().field("graph"))
        assertEquals("3", result().field("remoteCommits"))
    }

    @Test
    fun runFetchOnly_should_ExitWithNetworkError_When_TheRemoteIsUnreachable() = runTest {
        val failure = assertFailsWith<SyncExitException> {
            runFetchOnly(repo(fetch = DomainError.GitError.FetchFailed("down").left()), config, json, notesPath)
        }

        assertEquals(3, failure.code)
        assertEquals(notesDir, result().field("graph"))
        assertEquals("network_error", result().field("status"))
    }

    @Test
    fun runFullSync_should_ReportConflicts_And_ExitOne() = runTest {
        val conflict = ConflictFile(filePath = "$notesDir/pages/a.md", wikiRelativePath = "pages/a.md", hunks = emptyList())
        val git = repo(
            fetch = FetchResult(true, 2).right(),
            merge = MergeResult(true, listOf(conflict), emptyList()).right(),
        )

        val failure = assertFailsWith<SyncExitException> { runFullSync(git, config, json, notesPath) }

        assertEquals(1, failure.code)
        assertEquals(notesDir, result().field("graph"))
        assertEquals("conflicts", result().field("status"))
        assertEquals(listOf("$notesDir/pages/a.md"), result().getValue("conflicts").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun runFullSync_should_ReportSuccess_AfterCommitMergeAndPush() = runTest {
        runFullSync(repo(status = dirty, fetch = FetchResult(true, 2).right()), config, json, notesPath)

        assertEquals(notesDir, result().field("graph"))
        assertEquals("success", result().field("status"))
        assertEquals("1", result().field("localCommits"))
        assertEquals("2", result().field("remoteCommits"))
    }

    @Test
    fun runFullSync_should_ExitWithAuthError_When_PushIsRejected() = runTest {
        val failure = assertFailsWith<SyncExitException> {
            runFullSync(repo(push = DomainError.GitError.AuthFailed("denied").left()), config, json, notesPath)
        }

        assertEquals(2, failure.code)
        assertEquals(notesDir, result().field("graph"))
        assertEquals("auth_error", result().field("status"))
    }

    @Test
    fun handleGitError_should_ReportGenericFailures_WithExitFive() {
        val failure = assertFailsWith<SyncExitException> {
            handleGitError(DomainError.GitError.CommitFailed("boom"), json, notesPath, "main")
        }

        assertEquals(5, failure.code)
        assertEquals("error", result().field("status"))
        assertEquals("main", result().field("branch"))
    }

    @Test
    fun runSyncWithManager_should_ReportNoConfig_ForAGraphWithoutGitSync() = runTest {
        val dataDir = Files.createTempDirectory("sync_cmd_data_").toFile()
        val root = Files.createTempDirectory("sync_cmd_root_").toFile().also { File(it, "pages").mkdirs(); File(it, "journals").mkdirs() }
        val originalDevDataDir = System.getProperty("stelekit.devDataDir")
        System.setProperty("stelekit.devDataDir", dataDir.absolutePath)
        val manager = GraphManager(MapSettings(), DriverFactory(), PlatformFileSystem().also { it.registerGraphRoot(root.absolutePath) }, GraphBackend.SQLDELIGHT)
        try {
            val failure = assertFailsWith<SyncExitException> {
                runSyncWithManager(SyncArgs(jsonOutput = true), manager, StubGitRepository(), json, EffectiveNotesPath(root.absolutePath))
            }

            assertEquals(4, failure.code)
            assertEquals(root.absolutePath, result().field("graph"))
            assertEquals("no_config", result().field("status"))
        } finally {
            manager.shutdown()
            originalDevDataDir?.let { System.setProperty("stelekit.devDataDir", it) } ?: System.clearProperty("stelekit.devDataDir")
            dataDir.deleteRecursively()
            root.deleteRecursively()
        }
    }
}
