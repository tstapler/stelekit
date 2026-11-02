// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.FakeCredentialAccess
import dev.stapler.stelekit.git.testsupport.FakeSafFileSystem
import dev.stapler.stelekit.model.RepoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.RepositoryState
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Root cause under test: [AndroidGitRepository.abortMerge] runs `ResetType.HARD`, which discards unstaged edits
 * to non-conflicted tracked files, but the Android repository did not override the `GuardedMergeAbort` hooks
 * ([GitRepository.editsAtRiskOfReset] and friends), so the interface defaults (empty list) made the guard a no-op.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidGitAbortPreservationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dirs = mutableListOf<File>()

    @After
    fun tearDown() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun repository(fs: dev.stapler.stelekit.platform.FileSystem = FakeSafFileSystem()) = AndroidGitRepository(
        context = context,
        sshKeyProvider = null,
        credentialAccess = FakeCredentialAccess(),
        pathResolver = { null },
        fileSystem = fs,
    )

    private fun config(root: String) = GitConfig(
        graphId = "g", repoRoot = RepoRoot(root), wikiSubdir = null, remoteBranch = "main", authType = GitAuthType.NONE,
    )

    /** An app-owned (non-shadow) clone in MERGING with `journal.md` conflicted and `notes.md` tracked and clean. */
    private fun mergingRepo(): File {
        val dir = File(context.filesDir, "graphs/g-abort-${System.nanoTime()}").also { it.mkdirs(); dirs += it }
        Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
            git.repository.config.apply { setString("user", null, "name", "T"); setString("user", null, "email", "t@example.com"); save() }
            File(dir, "journal.md").writeText("base\n")
            File(dir, "notes.md").writeText("base notes\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("base").call()
            git.checkout().setCreateBranch(true).setName("other").call()
            File(dir, "journal.md").writeText("remote\n")
            git.commit().setAll(true).setMessage("remote").call()
            git.checkout().setName("main").call()
            File(dir, "journal.md").writeText("local\n")
            git.commit().setAll(true).setMessage("local").call()
            val result = git.merge().include(git.repository.resolve("other")).call()
            assertEquals(MergeResult.MergeStatus.CONFLICTING, result.mergeStatus)
            assertEquals(RepositoryState.MERGING, git.repository.repositoryState)
        }
        return dir
    }

    @Test
    fun `editsAtRiskOfReset lists a modified non-conflicted file and excludes the conflicted one`() = runTest {
        val dir = mergingRepo()
        File(dir, "notes.md").writeText("my edit\n")

        val atRisk = repository().editsAtRiskOfReset(config(dir.absolutePath))

        assertEquals(listOf("notes.md"), atRisk.getOrNull())
    }

    @Test
    fun `guarded abort keeps an unstaged edit byte for byte and aborts the merge`() = runTest {
        val dir = mergingRepo()
        val mine = "my unrelated edit é\n".encodeToByteArray()
        File(dir, "notes.md").writeBytes(mine)
        val repo = repository()
        val guarded = GuardedMergeAbort.create(repo, FakeFileSystem(), clock = { 42L })
        var shown: AbortPreservation? = null

        val result = guarded.abort("g", config(dir.absolutePath)) { shown = it; true }

        assertTrue(result.isRight(), "abort failed: $result")
        assertEquals(listOf("notes.md"), shown?.paths)
        assertContentEquals(mine, File(dir, "notes.md").readBytes())
        assertEquals("local\n", File(dir, "journal.md").readText())
        Git.open(dir).use { assertEquals(RepositoryState.SAFE, it.repository.repositoryState) }
    }

    @Test
    fun `a saf shadow-backed graph reports nothing at risk because the reset never touches saf`() = runTest {
        val root = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki-risk"

        val atRisk = repository().editsAtRiskOfReset(config(root))

        assertEquals(emptyList(), atRisk.getOrNull())
    }
}
