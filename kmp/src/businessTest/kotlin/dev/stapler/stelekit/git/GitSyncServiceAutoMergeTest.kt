// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.merge.MergePolicy
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.ConflictHunk
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [GitSyncService.sync]'s handling of files the merge engine already resolved ([ConflictFile.autoResolvedBy]). */
class GitSyncServiceAutoMergeTest {

    private val clean = ConflictFile(
        filePath = "/repo/clean.md",
        wikiRelativePath = "clean.md",
        hunks = emptyList(),
        rawContent = "- merged clean\n",
        autoResolvedBy = MergePolicy.LogseqUnion,
    )
    private val real = ConflictFile(
        filePath = "/repo/real.md",
        wikiRelativePath = "real.md",
        hunks = listOf(ConflictHunk(id = "h", localLines = listOf("- a"), remoteLines = listOf("- b"))),
        rawContent = "<<<<<<< LOCAL\n- a\n=======\n- b\n>>>>>>> REMOTE\n",
    )

    private class RecordingFs(private val writeOk: Boolean = true) : StubFileSystem() {
        val written = mutableMapOf<String, String>()
        override fun writeFile(path: String, content: String): Boolean {
            if (writeOk) written[path] = content
            return writeOk
        }
    }

    private class MergingRepo(
        private val conflicts: List<ConflictFile>,
        private val markResolvedResult: Either<DomainError.GitError, Unit> = Unit.right(),
        private val commitResult: Either<DomainError.GitError, String> = "sha".right(),
    ) : StubGitRepository() {
        val markedResolved = mutableListOf<String>()
        val commitMessages = mutableListOf<String>()
        var abortCalls = 0
            private set

        override suspend fun status(config: GitConfig) =
            GitStatus(hasLocalChanges = false, untrackedFiles = emptyList(), modifiedFiles = emptyList()).right()
        override suspend fun fetch(config: GitConfig) = FetchResult(hasRemoteChanges = true, remoteCommitCount = 1).right()
        override suspend fun merge(config: GitConfig) =
            MergeResult(hasConflicts = conflicts.isNotEmpty(), conflicts = conflicts, changedFiles = emptyList()).right()
        override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> {
            markedResolved += filePath
            return markResolvedResult
        }
        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> {
            commitMessages += message
            return commitResult
        }
        override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> {
            abortCalls++
            return Unit.right()
        }
        override suspend fun push(config: GitConfig) = Unit.right()
    }

    private fun service(repo: GitRepository, fs: StubFileSystem) = buildGitSyncTestService(
        gitRepository = repo,
        configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        fileSystem = fs,
        isOnline = { true },
    )

    @Test
    fun `only the real conflict is pending when a clean merge sits beside it`() = runTest {
        val repo = MergingRepo(listOf(clean, real))
        val fs = RecordingFs()
        val svc = service(repo, fs)

        val result = svc.sync("test-graph")

        assertIs<Either.Left<*>>(result)
        val state = assertIs<SyncState.ConflictPending>(svc.syncState.value)
        assertEquals(listOf(real.filePath), state.conflicts.map { it.filePath })
        assertEquals(listOf(clean.filePath), repo.markedResolved)
        assertEquals("- merged clean\n", fs.written[clean.filePath])
        assertTrue(repo.commitMessages.isEmpty(), "must not commit while a real conflict is pending")
    }

    @Test
    fun `a failed markResolved leaves the file pending for the user`() = runTest {
        val repo = MergingRepo(listOf(clean), markResolvedResult = DomainError.GitError.CommitFailed("index locked").left())
        val svc = service(repo, RecordingFs())

        svc.sync("test-graph")

        val state = assertIs<SyncState.ConflictPending>(svc.syncState.value)
        assertEquals(listOf(clean.filePath), state.conflicts.map { it.filePath })
        assertTrue(repo.commitMessages.isEmpty())
    }

    @Test
    fun `a failed write leaves the file pending and is never staged`() = runTest {
        val repo = MergingRepo(listOf(clean))
        val svc = service(repo, RecordingFs(writeOk = false))

        svc.sync("test-graph")

        assertIs<SyncState.ConflictPending>(svc.syncState.value)
        assertTrue(repo.markedResolved.isEmpty())
    }

    @Test
    fun `a failed merge commit aborts the merge and reports the error`() = runTest {
        val repo = MergingRepo(listOf(clean), commitResult = DomainError.GitError.CommitFailed("boom").left())
        val svc = service(repo, RecordingFs())

        val result = svc.sync("test-graph")

        assertIs<Either.Left<*>>(result)
        assertIs<SyncState.Error>(svc.syncState.value)
        assertEquals(1, repo.abortCalls)
    }

    @Test
    fun `an all-clean auto-merge commits with the merged paths named and reports them`() = runTest {
        val repo = MergingRepo(listOf(clean))
        val svc = service(repo, RecordingFs())

        val result = svc.sync("test-graph")

        val success = assertIs<Either.Right<SyncState.Success>>(result).value
        assertEquals(listOf("clean.md"), success.autoMergedPaths)
        assertTrue("Auto-merged (logseq-union): clean.md" in repo.commitMessages.single(), repo.commitMessages.toString())
        assertEquals(0, repo.abortCalls)
    }
}
