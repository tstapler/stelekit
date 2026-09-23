// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.HunkResolution
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for [GitSyncService.resolveConflicts] and [GitSyncService.refreshLocalStatus] —
 * split out of the original 751-line `GitSyncServiceTest.kt` (kotlin-file-size finding). See
 * [GitSyncServiceTest] for early-return scenarios and [GitSyncServiceErrorRoutingTest] for
 * RateLimited/MergeConflict/CredentialExpired routing. Shared stubs live in
 * `GitSyncServiceTestFixtures.kt`.
 */
class GitSyncServiceConflictResolutionTest {

    // ── resolveConflicts(): unified side + hunk resolution, one commit ────────────

    /**
     * [StubGitRepository] recording `checkoutFile`/`markResolved`/`commit` call counts — the
     * common happy-path shape the `resolveConflicts` tests below assert against.
     */
    private open class ResolvingGitRepository : StubGitRepository() {
        var checkoutCalls = 0
            private set
        var markResolvedCalls = 0
            private set
        var commitCalls = 0
            private set

        override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide): Either<DomainError.GitError, Unit> {
            checkoutCalls++
            return Unit.right()
        }

        override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> {
            markResolvedCalls++
            return Unit.right()
        }

        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> {
            commitCalls++
            return "sha-$commitCalls".right()
        }
    }

    /**
     * A whole-file "keep mine"/"use remote" resolution must checkout the chosen side and mark
     * it resolved, then commit exactly once.
     */
    @Test
    fun `resolveConflicts with only side resolutions checks out each side and commits once`() = runTest {
        val gitRepository = object : ResolvingGitRepository() {
            override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide): Either<DomainError.GitError, Unit> {
                assertEquals(MergeSide.REMOTE, side)
                return super.checkoutFile(config, filePath, side)
            }
        }

        val service = buildGitSyncTestService(
            gitRepository = gitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        )

        val conflicts = listOf(
            ConflictFile(filePath = "/repo/a.md", wikiRelativePath = "a.md", hunks = emptyList()),
        )
        val result = service.resolveConflicts(
            graphId = "test-graph",
            conflicts = conflicts,
            sideResolutions = mapOf("/repo/a.md" to MergeSide.REMOTE),
        )

        assertIs<Either.Right<*>>(result)
        assertEquals(1, gitRepository.checkoutCalls)
        assertEquals(1, gitRepository.markResolvedCalls)
        assertEquals(1, gitRepository.commitCalls)
        assertIs<SyncState.Idle>(service.syncState.value)
    }

    /** [StubFileSystem] recording the last [writeFile] call, with a fixed [readFile] result. */
    private class RecordingFileSystem(
        private val readResult: String? = null,
    ) : StubFileSystem() {
        var writtenPath: String? = null
            private set
        var writtenContent: String? = null
            private set

        override fun readFile(path: String): String? = readResult
        override fun writeFile(path: String, content: String): Boolean {
            writtenPath = path
            writtenContent = content
            return true
        }
    }

    /**
     * A hunk-level resolution must apply the resolved hunks against the [ConflictFile.rawContent]
     * captured at merge time — not re-read the file from disk — write the result, mark it
     * resolved, then commit.
     */
    @Test
    fun `resolveConflicts with hunk resolutions applies them against captured rawContent and commits`() = runTest {
        val gitRepository = ResolvingGitRepository()
        val fileSystem = RecordingFileSystem(readResult = "SHOULD NOT BE READ — rawContent must be used instead")

        val service = buildGitSyncTestService(
            gitRepository = gitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
            fileSystem = fileSystem,
        )

        val rawContent = "before\n<<<<<<< HEAD\nlocal\n=======\nremote\n>>>>>>> origin/main\nafter"
        val hunks = conflictHunksFor("/repo/b.md", rawContent)
        val conflicts = listOf(
            ConflictFile(filePath = "/repo/b.md", wikiRelativePath = "b.md", hunks = hunks, rawContent = rawContent),
        )
        val resolvedHunks = hunks.map { it.copy(resolution = HunkResolution.AcceptLocal) }

        val result = service.resolveConflicts(
            graphId = "test-graph",
            conflicts = conflicts,
            hunkResolutions = mapOf("/repo/b.md" to resolvedHunks),
        )

        assertIs<Either.Right<*>>(result)
        assertEquals("/repo/b.md", fileSystem.writtenPath)
        assertEquals("before\nlocal\nafter", fileSystem.writtenContent)
        assertEquals(1, gitRepository.commitCalls)
    }

    /** Parses [rawContent] via [ConflictResolver] and returns its hunks — shared by the tests above. */
    private fun conflictHunksFor(filePath: String, rawContent: String) =
        (ConflictResolver().parseConflictFile(filePath, rawContent, "/repo") as Either.Right).value.hunks

    /**
     * Mixed resolution — some files by whole side, some by hunk — must still complete with
     * exactly one commit covering every resolved file, not one commit per resolution kind.
     */
    @Test
    fun `resolveConflicts with a mix of side and hunk resolutions commits exactly once`() = runTest {
        var commitCalls = 0
        val gitRepository = object : StubGitRepository() {
            override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide): Either<DomainError.GitError, Unit> = Unit.right()
            override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> = Unit.right()
            override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> {
                commitCalls++
                return "sha789".right()
            }
        }
        val fileSystem = object : StubFileSystem() {
            override fun writeFile(path: String, content: String): Boolean = true
        }

        val service = buildGitSyncTestService(
            gitRepository = gitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
            fileSystem = fileSystem,
        )

        val rawContent = "<<<<<<< HEAD\nlocal\n=======\nremote\n>>>>>>> origin/main"
        val hunks = conflictHunksFor("/repo/b.md", rawContent)

        val result = service.resolveConflicts(
            graphId = "test-graph",
            conflicts = listOf(
                ConflictFile("/repo/a.md", "a.md", emptyList()),
                ConflictFile("/repo/b.md", "b.md", hunks, rawContent),
            ),
            sideResolutions = mapOf("/repo/a.md" to MergeSide.LOCAL),
            hunkResolutions = mapOf(
                "/repo/b.md" to hunks.map { it.copy(resolution = HunkResolution.AcceptRemote) },
            ),
        )

        assertIs<Either.Right<*>>(result)
        assertEquals(1, commitCalls, "expected exactly one merge commit covering both files")
    }

    // ── refreshLocalStatus: lightweight status check, independent of the sync pipeline ────────

    @Test
    fun `refreshLocalStatus populates localStatus without touching syncState`() = runTest {
        val statusResult = GitStatus(
            hasLocalChanges = true,
            untrackedFiles = listOf("new.md"),
            modifiedFiles = listOf("edited.md"),
        )
        val gitRepository = object : StubGitRepository() {
            override suspend fun status(config: GitConfig) = statusResult.right()
        }
        val service = buildGitSyncTestService(
            gitRepository = gitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        )

        val syncStateBefore = service.syncState.value
        assertEquals(null, service.localStatus.value, "no refresh has happened yet")

        service.refreshLocalStatus("test-graph")

        assertEquals(statusResult, service.localStatus.value)
        assertEquals(syncStateBefore, service.syncState.value, "refreshLocalStatus must never mutate syncState")
    }

    @Test
    fun `refreshLocalStatus leaves localStatus null when there is no git config`() = runTest {
        val service = buildGitSyncTestService(
            configRepository = StubConfigRepository(Either.Right(null)),
        )

        service.refreshLocalStatus("test-graph")

        assertEquals(null, service.localStatus.value)
    }
}
