// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** A tree left half-merged must never be committed: that pushed `<<<<<<<` into the remote wiki. */
class GitSyncServiceConflictMarkerGuardTest {

    private val markered = "- mine\n<<<<<<< HEAD\n- a\n=======\n- b\n>>>>>>> c7d739709\n"

    private fun service(fileContent: String, calls: MutableList<String>): GitSyncService {
        val repo = object : StubGitRepository() {
            override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
                GitStatus(true, emptyList(), listOf("journals/2026_10_08.md")).right()

            override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide) = Unit.right()

            override suspend fun markResolved(config: GitConfig, filePath: String) = Unit.right()

            override suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String>): Either<DomainError.GitError, Unit> {
                calls += "stage"
                return Unit.right()
            }

            override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> {
                calls += "commit"
                return "sha".right()
            }

            override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> {
                calls += "push"
                return Unit.right()
            }
        }
        val fs = object : StubFileSystem() {
            override fun readFile(path: String): String? = fileContent
        }
        return GitSyncService(
            gitRepository = repo,
            graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = EditLock(),
            configRepository = StubConfigRepository(sampleConfig.right()),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            graphId = "test-graph",
        )
    }

    @Test
    fun `sync refuses to commit or push a file that still has conflict markers`() = runBlocking {
        assumeTrue("Skipped: requires network access for the NetworkMonitor.isOnline check", NetworkMonitor().isOnline)
        val calls = mutableListOf<String>()
        val svc = service(markered, calls)

        val result = svc.sync("test-graph")

        assertIs<DomainError.GitError.MergeConflict>((result as Either.Left).value)
        assertIs<SyncState.ConflictPending>(svc.syncState.value)
        assertEquals(emptyList(), calls, "nothing may be staged, committed or pushed")
        svc.shutdown()
    }

    @Test
    fun `sync commits normally when the changed file has no conflict markers`() = runBlocking {
        assumeTrue("Skipped: requires network access for the NetworkMonitor.isOnline check", NetworkMonitor().isOnline)
        val calls = mutableListOf<String>()
        val svc = service("- clean\n", calls)

        svc.sync("test-graph")

        assertEquals(listOf("stage", "commit"), calls.take(2))
        svc.shutdown()
    }

    private fun guard(fileContent: String) = ConflictMarkerGuard(object : StubFileSystem() {
        override fun readFile(path: String): String? = fileContent
    })

    private val status = GitStatus(true, emptyList(), listOf("journals/2026_10_08.md", "assets/x.png"))

    @Test
    fun `finds a changed markdown file whose content has conflict markers`() {
        val found = guard(markered).findUnresolved(sampleConfig, status)!!.files

        assertEquals(listOf("/repo/journals/2026_10_08.md"), found.map { it.filePath })
        assertTrue(found.single().rawContent == null || found.single().rawContent!!.contains("<<<<<<<"))
    }

    @Test
    fun `ignores changed files without conflict markers`() {
        val clean = guard("- clean\n=======\n- divider alone is not a conflict\n")

        assertEquals(null, clean.findUnresolved(sampleConfig, status))
    }

    @Test
    fun `applyJournalMerge refuses merged content that still has conflict markers`() = runBlocking {
        val calls = mutableListOf<String>()
        val svc = service("", calls)

        val result = svc.applyJournalMerge("test-graph", "/repo/journals/2026_10_08.md", markered)

        assertIs<DomainError.GitError.CommitFailed>((result as Either.Left).value)
        assertEquals(emptyList(), calls, "nothing may be committed or pushed")
        svc.shutdown()
    }

    @Test
    fun `resolveConflicts refuses to commit when a resolved file still has conflict markers`() = runBlocking {
        val calls = mutableListOf<String>()
        val svc = service(markered, calls)

        val result = svc.resolveConflicts(
            "test-graph", emptyList(), sideResolutions = mapOf("/repo/journals/2026_10_08.md" to MergeSide.LOCAL),
        )

        assertIs<DomainError.GitError.CommitFailed>((result as Either.Left).value)
        assertEquals(emptyList(), calls, "nothing may be committed")
        svc.shutdown()
    }
}
