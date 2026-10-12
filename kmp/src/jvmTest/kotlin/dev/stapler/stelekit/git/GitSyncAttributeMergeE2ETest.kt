// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.platform.AppPrivateFileSystem
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import arrow.core.Either
import arrow.core.right
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.transport.URIish
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real-repo, real-JGit, real-`GitSyncService.sync()` coverage of `.gitattributes`-driven merging:
 * two devices diverge on a journal, then one syncs against a bare origin. Asserts on the actual
 * working-tree file, git state, and what landed on the remote — not on stubbed collaborators.
 */
class GitSyncAttributeMergeE2ETest {
    private lateinit var root: File
    private lateinit var origin: File
    private lateinit var local: File
    private lateinit var other: File

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("stelekit_attr_merge_e2e_").toFile()
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
        save()
    }

    private fun commitAll(dir: File, message: String) = Git.open(dir).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage(message).call()
    }

    private fun push(dir: File) = Git.open(dir).use { it.push().setRemote("origin").add(BRANCH).call() }

    /** Seeds origin from [local] with [files], then clones [other] from origin. */
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

    private fun otherCommitsAndPushes(path: String, text: String) {
        File(other, path).writeText(text)
        commitAll(other, "remote edit")
        push(other)
    }

    private fun service(): GitSyncService {
        val config = GitConfig(graphId = GRAPH_ID, repoRoot = local.absolutePath, wikiSubdir = null, authType = GitAuthType.NONE)
        val fs = AppPrivateFileSystem()
        val graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository())
        val configRepository = StubConfigRepository(config.right())
        return GitSyncService(
            gitRepository = JvmGitRepository(),
            graphLoader = graphLoader,
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = EditLock(),
            configRepository = configRepository,
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            isOnline = { true },
        )
    }

    private fun originFile(path: String): String? {
        val verifyDir = File(root, "verify-${System.nanoTime()}")
        return Git.cloneRepository().setURI(origin.absolutePath).setDirectory(verifyDir).call().use { git ->
            val file = File(git.repository.workTree, path)
            if (file.isFile) file.readText() else null
        }
    }

    private val journal = "journals/2026_10_10.md"

    private companion object {
        const val BRANCH = "main"
        const val GRAPH_ID = "g"
        const val ATTRIBUTES_FILE = ".gitattributes"
        const val MINE = "- mine"
        const val THEIRS = "- theirs"
        const val SHARED_BASE = "- keep\n- shared\n"
        const val SHARED_THEIRS = "- keep\n- shared theirs\n"
        const val SHARED_MINE = "- keep\n- shared mine\n"
        fun syncFailed(result: Any?) = "sync failed: $result"
        fun mergedWas(text: String) = "merged file was:\n$text"
    }
    private val unionAttr = ATTRIBUTES_FILE to "*.md merge=logseq-union\n"

    @Test
    fun `logseq-union port keeps both sides of concurrent journal appends and pushes the merge`() = runTest {
        seed(mapOf(unionAttr, journal to "- base\n"))
        otherCommitsAndPushes(journal, "- base\n- theirs\n")
        File(local, journal).writeText("- base\n- mine\n") // uncommitted: sync commits it

        val result = service().sync(GRAPH_ID)

        assertTrue(result.isRight(), syncFailed(result))
        // Ported fallback strategy: local lines then remote-only lines, no trailing newline.
        assertEquals("- base\n- mine\n- theirs", File(local, journal).readText())
        Git.open(local).use { assertEquals(RepositoryState.SAFE, it.repository.repositoryState) }
        assertEquals("- base\n- mine\n- theirs", originFile(journal))
    }

    @Test
    fun `logseq-union port auto-merges adjacent-line edits that JGit alone reports as a conflict`() = runTest {
        seed(mapOf(unionAttr, journal to "- one\n- two\n"))
        otherCommitsAndPushes(journal, "- one\n- TWO\n")
        File(local, journal).writeText("- ONE\n- two\n")

        val result = service().sync(GRAPH_ID)

        assertTrue(result.isRight(), syncFailed(result))
        assertEquals("- ONE\n- TWO", File(local, journal).readText())
        Git.open(local).use { assertEquals(RepositoryState.SAFE, it.repository.repositoryState) }
    }

    @Test
    fun `logseq-union port surfaces a same-line edit as a conflict and never commits its markers`() = runTest {
        seed(mapOf(unionAttr, journal to SHARED_BASE))
        otherCommitsAndPushes(journal, SHARED_THEIRS)
        File(local, journal).writeText(SHARED_MINE)

        val svc = service()
        val result = svc.sync(GRAPH_ID)

        assertIs<Either.Left<*>>(result)
        val state = assertIs<SyncState.ConflictPending>(svc.syncState.value)
        val conflict = state.conflicts.single()
        assertFalse(conflict.isAutoResolved)
        val hunk = conflict.hunks.single()
        assertEquals(listOf("- shared mine"), hunk.localLines)
        assertEquals(listOf("- shared theirs"), hunk.remoteLines)
        assertFalse("<<<<<<<" in (originFile(journal) ?: ""), "markers must never reach origin")
    }

    @Test
    fun `an unknown custom driver keeps both versions when both sides edit the same block`() = runTest {
        seed(mapOf(ATTRIBUTES_FILE to "*.md merge=some-custom-driver\n", journal to SHARED_BASE))
        otherCommitsAndPushes(journal, SHARED_THEIRS)
        File(local, journal).writeText(SHARED_MINE)

        val result = service().sync(GRAPH_ID)

        assertTrue(result.isRight(), syncFailed(result))
        val text = File(local, journal).readText()
        assertTrue("shared mine" in text && "shared theirs" in text && "<<<<<<<" !in text, mergedWas(text))
    }

    @Test
    fun `without an attribute an edit of the same block still surfaces as a conflict`() = runTest {
        seed(mapOf(journal to SHARED_BASE))
        otherCommitsAndPushes(journal, SHARED_THEIRS)
        File(local, journal).writeText(SHARED_MINE)

        val svc = service()
        val result = svc.sync(GRAPH_ID)

        assertIs<Either.Left<*>>(result)
        val state = assertIs<SyncState.ConflictPending>(svc.syncState.value)
        val conflict = state.conflicts.single()
        assertEquals(1, conflict.hunks.size)
        assertFalse(conflict.isAutoResolved)
    }

    @Test
    fun `without an attribute concurrent appends to markdown still merge automatically`() = runTest {
        seed(mapOf(journal to "- base\n"))
        otherCommitsAndPushes(journal, "- base\n- theirs\n")
        File(local, journal).writeText("- base\n- mine\n")

        val result = service().sync(GRAPH_ID)

        assertTrue(result.isRight(), syncFailed(result))
        val text = File(local, journal).readText()
        assertTrue(MINE in text && THEIRS in text, mergedWas(text))
    }

    @Test
    fun `merge=text opts a markdown file out of auto-merge`() = runTest {
        seed(mapOf(ATTRIBUTES_FILE to "*.md merge=text\n", journal to SHARED_BASE))
        otherCommitsAndPushes(journal, SHARED_THEIRS)
        File(local, journal).writeText(SHARED_MINE)

        val svc = service()
        svc.sync(GRAPH_ID)

        assertIs<SyncState.ConflictPending>(svc.syncState.value)
    }
}
