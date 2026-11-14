// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.platform.AppPrivateFileSystem
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression for the stale-journals-after-sync report (Android log 2026-11-14T19:26): a sync that
 * auto-merges a journal but also hits an unrelated conflict returned early and never reloaded the
 * auto-merged file into the DB, leaving the journals view on pre-merge content.
 *
 * Real repo, real JGit, real `GitSyncService.sync()`; asserts on what the DB and the pre-write
 * guard see afterwards, not on stubbed collaborators.
 */
class GitSyncConflictReloadE2ETest {
    private lateinit var root: File
    private lateinit var origin: File
    private lateinit var local: File
    private lateinit var other: File
    private val pages = InMemoryPageRepository()
    private val blocks = InMemoryBlockRepository()
    private lateinit var loader: GraphLoader

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("stelekit_conflict_reload_e2e_").toFile()
        origin = File(root, "origin.git")
        local = File(root, "local")
        other = File(root, "other")
        Git.init().setBare(true).setInitialBranch(BRANCH).setDirectory(origin).call().close()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun identify(git: Git) = git.repository.config.run {
        setString("user", null, "name", "Stelekit Test")
        setString("user", null, "email", "stelekit-test@example.com")
        setBoolean("commit", null, "gpgsign", false)
        save()
    }

    private fun commitAll(dir: File, message: String) = Git.open(dir).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage(message).setSign(false).call()
    }

    private fun push(dir: File) = Git.open(dir).use { it.push().setRemote("origin").add(BRANCH).call() }

    private fun seed(files: Map<String, String>) {
        Git.init().setInitialBranch(BRANCH).setDirectory(local).call().use { git ->
            identify(git)
            git.remoteAdd().setName("origin").setUri(URIish(origin.absolutePath)).call()
        }
        files.forEach { (path, text) -> File(local, path).apply { parentFile.mkdirs() }.writeText(text) }
        commitAll(local, "seed")
        push(local)
        Git.cloneRepository().setURI(origin.absolutePath).setDirectory(other).call().use { identify(it) }
    }

    private fun service(): GitSyncService {
        val config = GitConfig(graphId = GRAPH_ID, repoRoot = RepoRoot(local.absolutePath), wikiSubdir = null, authType = GitAuthType.NONE)
        val fs = AppPrivateFileSystem()
        loader = GraphLoader(fs, pages, blocks)
        return GitSyncService(
            gitRepository = JvmGitRepository(),
            graphLoader = loader,
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = EditLock(),
            configRepository = StubConfigRepository(config.right()),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            isOnline = { true },
        )
    }

    private suspend fun dbContentOf(pageName: String): List<String> {
        val page = pages.getPageByName(pageName).first().getOrNull() ?: return emptyList()
        return blocks.getBlocksForPage(page.uuid).first().getOrNull().orEmpty().map { it.content }
    }

    /** Both sides edit the journal on adjacent lines (union-merged) and a page on the same line (a real conflict). */
    private fun divergeJournalAndConflictingPage() {
        seed(mapOf(ATTRIBUTES to "*.md merge=logseq-union\n", JOURNAL to "- one\n- two\n", CONFLICTED to "- keep\n- shared\n"))
        File(other, JOURNAL).writeText("- one\n- TWO\n")
        File(other, CONFLICTED).writeText("- keep\n- shared theirs\n")
        commitAll(other, "remote edits")
        push(other)
        File(local, JOURNAL).writeText("- ONE\n- two\n")
        File(local, CONFLICTED).writeText("- keep\n- shared mine\n")
    }

    @Test
    fun `an auto-merged journal is reloaded into the DB even when another file conflicts`() = runTest {
        divergeJournalAndConflictingPage()

        val svc = service()
        val result = svc.sync(GRAPH_ID)

        assertIs<Either.Left<*>>(result)
        assertIs<SyncState.ConflictPending>(svc.syncState.value)
        val disk = File(local, JOURNAL).readText()
        assertTrue("ONE" in disk && "TWO" in disk && "<<<<<<<" !in disk, "precondition: the journal was auto-merged on disk, was: $disk")
        val dbBlocks = dbContentOf("2026_10_10")
        assertTrue(dbBlocks.any { "ONE" in it } && dbBlocks.any { "TWO" in it }, "DB must hold the auto-merged journal, was: $dbBlocks")
        assertFalse(loader.isDiskAheadOfDb(File(local, JOURNAL).absolutePath, disk), "a reloaded journal must not stay flagged unapplied")
    }

    @Test
    fun `the conflicted file is left alone - its markers are never fed to the parser`() = runTest {
        divergeJournalAndConflictingPage()
        val svc = service()
        val errors = mutableListOf<Any>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { loader.writeErrors.collect { errors += it } }

        svc.sync(GRAPH_ID)

        assertTrue(errors.isEmpty(), "reloading a conflicted file would emit a conflict-markers WriteError, got: $errors")
    }

    private companion object {
        const val BRANCH = "main"
        const val GRAPH_ID = "g"
        const val ATTRIBUTES = ".gitattributes"
        const val JOURNAL = "journals/2026_10_10.md"
        const val CONFLICTED = "pages/Conflicted.md"
    }
}
