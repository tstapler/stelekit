// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.git.model.CloneDepthState
import dev.stapler.stelekit.git.model.DEFAULT_CLONE_DEPTH
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec

/**
 * git-sync-resilience Story 2.1.2 (Task 2.1.2g) — [SqlDelightGitConfigRepository]'s
 * [CloneDepthState] round trip and fail-closed SQL parse rule, plus a real
 * [JvmGitRepository.clone] + [SqlDelightGitConfigRepository] integration proving Task 2.1.2f's
 * post-clone checkpoint write. Against a real in-memory SQLDelight DB — no mocks — since the
 * parse rule's whole point is the raw-column boundary, not the in-memory sealed type alone.
 */
class GitConfigCloneDepthMappingTest {

    private data class Repo(
        val gitConfigRepository: SqlDelightGitConfigRepository,
        val database: SteleDatabase,
        val scope: CoroutineScope,
    )

    private fun buildRepo(): Repo {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository(), scope = scope)
        return Repo(SqlDelightGitConfigRepository(database, actor), database, scope)
    }

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun baseConfig(graphId: String, repoRoot: String) = GitConfig(
        graphId = graphId,
        repoRoot = repoRoot,
        wikiSubdir = null,
        authType = GitAuthType.NONE,
    )

    /** Bypasses the repository's own write path — simulates a corrupt/hand-edited row, since
     * this app's own writes always go through [CloneDepthState.toRawState]/`toRawDepth` and could
     * never produce an inconsistent (raw state, raw depth) pair themselves. */
    private suspend fun insertRawGitConfigRow(
        database: SteleDatabase,
        graphId: String,
        rawCloneDepthState: String,
        rawShallowDepth: Long?,
    ) {
        database.steleDatabaseQueries.insertOrReplaceGitConfig(
            graph_id = graphId,
            repo_root = "/tmp/$graphId",
            wiki_subdir = "",
            remote_name = "origin",
            remote_branch = "main",
            auth_type = "NONE",
            ssh_key_path = null,
            ssh_key_passphrase_key = null,
            https_token_key = null,
            oauth_token_key = null,
            poll_interval_minutes = 5,
            auto_commit = 1,
            commit_message_template = "SteleKit: {date}",
            clone_depth_state = rawCloneDepthState,
            shallow_depth = rawShallowDepth,
        )
    }

    @Test
    fun `GitConfig round-trips cloneDepthState=Shallow(depth=50) through SqlDelightGitConfigRepository getConfig-saveConfig`() = runBlocking {
        val (repo, _, scope) = buildRepo()
        try {
            val config = baseConfig("graph-1", "/tmp/graph-1")
                .copy(cloneDepthState = CloneDepthState.Shallow(DEFAULT_CLONE_DEPTH))

            val saveResult = repo.saveConfig(config)
            assertIs<arrow.core.Either.Right<Unit>>(saveResult, "saveConfig failed: $saveResult")

            val readBack = repo.getConfig("graph-1")
            assertIs<arrow.core.Either.Right<GitConfig?>>(readBack, "getConfig failed: $readBack")
            assertEquals(CloneDepthState.Shallow(DEFAULT_CLONE_DEPTH), readBack.value?.cloneDepthState)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `SqlDelightGitConfigRepository maps an unrecognized clone_depth_state, or SHALLOW with a null shallow_depth, to CloneDepthState None`() = runBlocking {
        val (repo, database, scope) = buildRepo()
        try {
            insertRawGitConfigRow(database, "graph-shallow-null-depth", "SHALLOW", null)
            insertRawGitConfigRow(database, "graph-unrecognized-state", "BOGUS", 12)
            insertRawGitConfigRow(database, "graph-full-history-stale-depth", "FULL_HISTORY", 50)

            val shallowNullDepth = repo.getConfig("graph-shallow-null-depth")
            assertIs<arrow.core.Either.Right<GitConfig?>>(shallowNullDepth)
            assertEquals(
                CloneDepthState.None, shallowNullDepth.value?.cloneDepthState,
                "SHALLOW with a null shallow_depth must fail closed to None, not throw or fabricate a depth",
            )

            val unrecognized = repo.getConfig("graph-unrecognized-state")
            assertIs<arrow.core.Either.Right<GitConfig?>>(unrecognized)
            assertEquals(
                CloneDepthState.None, unrecognized.value?.cloneDepthState,
                "an unrecognized clone_depth_state string must fail closed to None",
            )

            val fullHistoryStaleDepth = repo.getConfig("graph-full-history-stale-depth")
            assertIs<arrow.core.Either.Right<GitConfig?>>(fullHistoryStaleDepth)
            assertEquals(
                CloneDepthState.FullHistory, fullHistoryStaleDepth.value?.cloneDepthState,
                "FULL_HISTORY with a stale non-null shallow_depth must ignore it, not resurrect a Shallow depth",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a fresh git_config row for a graph with no clone yet defaults to CloneDepthState None`() = runBlocking {
        val (repo, _, scope) = buildRepo()
        try {
            val saveResult = repo.saveConfig(baseConfig("graph-fresh", "/tmp/graph-fresh"))
            assertIs<arrow.core.Either.Right<Unit>>(saveResult)

            val readBack = repo.getConfig("graph-fresh")
            assertIs<arrow.core.Either.Right<GitConfig?>>(readBack)
            assertEquals(CloneDepthState.None, readBack.value?.cloneDepthState)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Task 2.1.2f: exercises a real [JvmGitRepository.clone] against a local fixture remote,
     * then persists and reads back `cloneDepthState = Shallow(DEFAULT_CLONE_DEPTH)` via
     * [SqlDelightGitConfigRepository] — the same two steps `GitSetupScreenSaveLogic
     * .performCloneAndSave` performs (clone, then `resolveAndSaveConfig` with the Shallow
     * checkpoint), against real JGit and a real SQLDelight DB rather than the UI-layer stub.
     */
    @Test
    fun `a successful shallow clone for graph-1 persists cloneDepthState=Shallow(depth=50), readable back via getConfig`() = runBlocking {
        val (repo, _, scope) = buildRepo()
        try {
            val bareOrigin = createTempDirectory("stelekit_clone_depth_origin_").toFile().also { tempDirs += it }
            Git.init().setBare(true).setDirectory(bareOrigin).setInitialBranch("main").call().close()
            val seedWorkDir = createTempDirectory("stelekit_clone_depth_seed_").toFile().also { tempDirs += it }
            Git.cloneRepository().setURI(bareOrigin.absolutePath).setDirectory(seedWorkDir).call().use { seedGit ->
                seedGit.repository.config.apply {
                    setString("user", null, "name", "Stelekit Test")
                    setString("user", null, "email", "stelekit-test@example.com")
                    save()
                }
                File(seedWorkDir, "journal.md").writeText("# seed\n")
                seedGit.add().addFilepattern(".").call()
                seedGit.commit().setMessage("seed commit").call()
                seedGit.push().setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
            }

            val destination = createTempDirectory("stelekit_clone_depth_dest_").toFile().also { tempDirs += it }
            destination.delete() // clone() requires an absent/empty target
            val jvmGitRepository = JvmGitRepository()
            val cloneResult = jvmGitRepository.clone(
                url = bareOrigin.absolutePath,
                localPath = destination.absolutePath,
                auth = GitAuth.None,
                onProgress = {},
                onStateChange = {},
            )
            assertIs<arrow.core.Either.Right<Unit>>(cloneResult, "clone failed: $cloneResult")

            val config = baseConfig("graph-1", destination.absolutePath)
                .copy(cloneDepthState = CloneDepthState.Shallow(DEFAULT_CLONE_DEPTH))
            val saveResult = repo.saveConfig(config)
            assertIs<arrow.core.Either.Right<Unit>>(saveResult, "saveConfig failed: $saveResult")

            val readBack = repo.getConfig("graph-1")
            assertIs<arrow.core.Either.Right<GitConfig?>>(readBack)
            assertEquals(CloneDepthState.Shallow(DEFAULT_CLONE_DEPTH), readBack.value?.cloneDepthState)
        } finally {
            scope.cancel()
        }
    }
}
