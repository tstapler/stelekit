// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.model.RepoRoot
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.lfs.LfsOid
import dev.stapler.stelekit.git.lfs.LfsPointer
import dev.stapler.stelekit.git.lfs.LfsPointerCodec
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.FakeCredentialAccess
import dev.stapler.stelekit.git.testsupport.FakeSafFileSystem
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Android half of the LFS safety floor, on [AndroidGitRepository]'s app-owned (plain path) repository:
 * a raw blob at a `filter=lfs` path is held back by `stageSubdir`, and one staged behind its back is
 * refused at `commit` and un-staged, so no raw binary can reach a commit.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidGitRepositoryLfsGuardTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var config: GitConfig
    private lateinit var repository: AndroidGitRepository

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(3_000)
    private val pointer = LfsPointerCodec.serialize(
        LfsPointer(LfsOid("4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393"), 3_000_000),
    )

    @Before
    fun setUp() = runBlocking {
        root = context.filesDir.resolve("graphs/lfs-guard-${System.nanoTime()}")
        root.mkdirs()
        repository = AndroidGitRepository(
            context = context,
            sshKeyProvider = null,
            credentialAccess = FakeCredentialAccess(),
            pathResolver = { null },
            fileSystem = FakeSafFileSystem(),
        )
        assertTrue(repository.init(RepoRoot(root.absolutePath)).isRight())
        Git.open(root).use { git ->
            git.repository.config.apply {
                setString("user", null, "name", "Stelekit Test")
                setString("user", null, "email", "stelekit-test@example.com")
                // A global filter.lfs.* on a developer machine would make JGit convert raw adds.
                setString("filter", "lfs", "clean", "cat")
                setString("filter", "lfs", "smudge", "cat")
                setBoolean("filter", "lfs", "required", false)
                setString("core", null, "autocrlf", "false")
                save()
            }
        }
        File(root, ".gitattributes").writeText("assets/** filter=lfs\n")
        config = GitConfig(graphId = "g", repoRoot = RepoRoot(root.absolutePath), wikiSubdir = null, authType = GitAuthType.NONE)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(path: String, bytes: ByteArray) {
        File(root, path).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
    }

    private fun headPaths(): Set<String> = Git.open(root).use { git ->
        val head = git.repository.resolve("HEAD") ?: return@use emptySet()
        org.eclipse.jgit.treewalk.TreeWalk(git.repository).use { tw ->
            tw.addTree(org.eclipse.jgit.revwalk.RevWalk(git.repository).parseCommit(head).tree)
            tw.isRecursive = true
            buildSet { while (tw.next()) add(tw.pathString) }
        }
    }

    @Test
    fun stageSubdir_should_HoldTheRawBinaryBackAndCommitTheRest_When_PathIsLfsTracked() = runBlocking {
        write("assets/photo.png", png)
        write("pages/a.md", "# a".encodeToByteArray())

        assertTrue(repository.stageSubdir(config, setOf("assets/photo.png")).isRight())
        val commit = repository.commit(config, "sync")

        assertTrue(commit.isRight(), "$commit")
        assertEquals(setOf(".gitattributes", "pages/a.md"), headPaths())
        assertContentEquals(png, File(root, "assets/photo.png").readBytes(), "the held file stays on disk")
    }

    @Test
    fun commit_should_RefuseAndUnstage_When_ARawBlobWasStagedWithoutTheGuard() = runBlocking {
        write("assets/photo.png", png)
        assertTrue(repository.stageSubdir(config, emptySet()).isRight())

        val result = repository.commit(config, "bypass")

        val error = assertIs<DomainError.GitError.LfsRawBinaryRefused>(assertIs<Either.Left<DomainError.GitError>>(result).value)
        assertEquals(listOf("assets/photo.png"), error.paths)
        assertEquals(emptySet(), headPaths(), "no commit may exist")
        assertEquals(emptyList(), repository.stagedLfsViolations(config), "the offender was un-staged by the refusal")
    }

    @Test
    fun stageSubdir_should_CommitAPointerNormally() = runBlocking {
        write("assets/p.png", pointer)

        assertTrue(repository.stageSubdir(config, emptySet()).isRight())

        assertTrue(repository.commit(config, "pointer").isRight())
        assertTrue("assets/p.png" in headPaths())
    }

    @Test
    fun stagedLfsViolations_should_ReportAndUnstage_When_ARawBlobIsInTheIndex() = runBlocking {
        write("assets/photo.png", png)
        repository.stageSubdir(config, emptySet())

        val violations = repository.stagedLfsViolations(config)

        assertEquals(listOf("assets/photo.png"), violations.map { it.path })
        assertEquals(emptyList(), repository.stagedLfsViolations(config), "a second check finds nothing: the offender is out of the index")
    }
}
