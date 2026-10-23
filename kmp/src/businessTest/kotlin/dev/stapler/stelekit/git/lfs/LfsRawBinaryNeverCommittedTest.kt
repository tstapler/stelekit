// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.model.RepoRoot
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.JvmGitRepository
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Safety-floor regression: with LFS transfer disabled, a raw binary at an LFS-tracked path never reaches
 * a commit through [JvmGitRepository] - neither via the hold-back stage path nor via a direct `git add`.
 */
class LfsRawBinaryNeverCommittedTest {
    private val rules = "assets/** filter=lfs\n"

    private fun config(f: LfsRepoFixture) =
        GitConfig(graphId = "g", repoRoot = RepoRoot(f.dir.absolutePath), wikiSubdir = null, authType = GitAuthType.NONE)

    /** Asserts the invariant from the outside: walk every blob in HEAD. */
    private fun assertNoRawBlobAtTrackedPath(f: LfsRepoFixture) {
        val policy = LfsPathPolicy(AttributeRuleSet(listOf(GitAttributesParser.parse(rules))))
        for ((path, bytes) in f.headBlobs()) {
            if (!policy.isTracked(path)) continue
            assertTrue(
                bytes.isEmpty() || LfsPointerCodec.parse(bytes) is LfsPointerParse.Valid,
                "raw blob committed at LFS-tracked path $path (${bytes.size} bytes)",
            )
        }
    }

    @Test
    fun stageSubdirWithHoldBack_should_CommitTheTextAndNeverTheRawBinary() = runBlocking {
        LfsRepoFixture().use { f ->
            f.write(".gitattributes", rules)
            f.write("assets/a.bin", LfsRepoFixture.PNG)
            f.write("pages/a.md", "# a")
            val repo = JvmGitRepository()

            assertTrue(repo.stageSubdir(config(f), excludePaths = setOf("assets/a.bin")).isRight())
            val sha = repo.commit(config(f), "sync")

            assertTrue(sha.isRight(), "$sha")
            assertEquals(setOf(".gitattributes", "pages/a.md"), f.headBlobs().keys)
            assertContentEquals(LfsRepoFixture.PNG, java.io.File(f.dir, "assets/a.bin").readBytes(), "the held file stays on disk")
            assertNoRawBlobAtTrackedPath(f)
        }
    }

    @Test
    fun commit_should_RefuseAndUnstage_When_ARawBinaryWasAddedDirectly() = runBlocking {
        LfsRepoFixture().use { f ->
            f.write(".gitattributes", rules)
            f.write("pages/a.md", "# a")
            f.add(".")
            f.commit("base")
            val headBefore = f.repo.resolve("HEAD")
            f.write("assets/a.bin", LfsRepoFixture.PNG)
            f.add("assets/a.bin")

            val result = JvmGitRepository().commit(config(f), "bypass")

            val error = assertIs<DomainError.GitError.LfsRawBinaryRefused>(assertIs<Either.Left<DomainError.GitError>>(result).value)
            assertEquals(listOf("assets/a.bin"), error.paths)
            assertEquals(headBefore, f.repo.resolve("HEAD"), "no commit may exist")
            assertTrue("assets/a.bin" !in f.stagedPaths(), "the refused file is un-staged")
            assertNoRawBlobAtTrackedPath(f)
        }
    }

    @Test
    fun commit_should_SucceedAfterTheRefusal_When_TheRemainingStageIsClean() = runBlocking {
        LfsRepoFixture().use { f ->
            f.write(".gitattributes", rules)
            f.write("assets/a.bin", LfsRepoFixture.PNG)
            f.write("pages/a.md", "# a")
            f.add(".")
            val repo = JvmGitRepository()
            val first = repo.commit(config(f), "first")
            assertTrue(first.isLeft())

            val second = repo.commit(config(f), "second")

            assertTrue(second.isRight(), "the offending file is gone from the index, so the retry commits the rest: $second")
            assertNotNull(f.repo.resolve("HEAD"))
            assertNoRawBlobAtTrackedPath(f)
        }
    }

    @Test
    fun stageSubdir_should_StagePointersNormally_When_TheTrackedFileIsAPointer() = runBlocking {
        LfsRepoFixture().use { f ->
            f.write(".gitattributes", rules)
            f.write("assets/p.png", LfsRepoFixture.POINTER)
            val repo = JvmGitRepository()

            repo.stageSubdir(config(f), emptySet())
            val sha = repo.commit(config(f), "pointer")

            assertTrue(sha.isRight(), "$sha")
            assertContentEquals(LfsRepoFixture.POINTER, f.headBlobs().getValue("assets/p.png"))
        }
    }

    @Test
    fun markResolved_should_Refuse_When_TheResolvedFileIsRawAtATrackedPath() = runBlocking {
        LfsRepoFixture().use { f ->
            f.write(".gitattributes", rules)
            f.write("assets/a.bin", LfsRepoFixture.PNG)

            val result = JvmGitRepository().markResolved(config(f), "${f.dir.absolutePath}/assets/a.bin")

            assertIs<DomainError.GitError.LfsRawBinaryRefused>(assertIs<Either.Left<DomainError.GitError>>(result).value)
            assertTrue("assets/a.bin" !in f.stagedPaths())
        }
    }

    @Test
    fun hasStagedChanges_should_BeFalse_When_HoldBackLeftTheIndexEqualToHead() = runBlocking {
        LfsRepoFixture().use { f ->
            f.write(".gitattributes", rules)
            f.write("pages/a.md", "# a")
            f.add(".")
            f.commit("base")
            f.write("assets/a.bin", LfsRepoFixture.PNG)
            val repo = JvmGitRepository()

            repo.stageSubdir(config(f), setOf("assets/a.bin"))

            assertEquals(false, repo.hasStagedChanges(config(f)))
        }
    }
}
