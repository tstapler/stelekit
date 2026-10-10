// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The "review first sync" gate: automatic triggers wait for consent, manual sync is the consent. */
class GitSyncServiceFirstSyncGateTest {

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private val graphId = "test-graph"

    private class Fixture(val service: GitSyncService, val firstSync: FirstSyncConfirmation, val fetchCalls: IntArray)

    private fun fixture(reviewPending: Boolean): Fixture {
        val settings = MapSettings()
        val firstSync = FirstSyncConfirmation(settings)
        if (reviewPending) firstSync.markReviewPending(graphId, previousBranch = "main")
        val fetchCalls = IntArray(1)
        val repo = object : StubGitRepository() {
            // The service refreshes status asynchronously on construction; a throwing stub would race the assertions with an Error state.
            override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
                GitStatus(hasLocalChanges = false, untrackedFiles = emptyList(), modifiedFiles = emptyList()).right()

            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
                fetchCalls[0]++
                return super.fetch(config)
            }
        }
        val fs = StubFileSystem()
        val service = GitSyncService(
            gitRepository = repo,
            graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = EditLock(),
            configRepository = StubConfigRepository(sampleConfig.right()),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            graphId = graphId,
            settings = settings,
        )
        return Fixture(service, firstSync, fetchCalls)
    }

    @Test
    fun `automatic sync is skipped while a first-sync review is pending and leaves state untouched`() = runBlocking {
        val f = fixture(reviewPending = true)
        val before = f.service.syncState.value

        val result = f.service.sync(graphId, SyncTrigger.Automatic)

        assertEquals(DomainError.GitError.FirstSyncReviewPending, (result as Either.Left).value)
        assertEquals(before, f.service.syncState.value)
        assertEquals(0, f.fetchCalls[0], "no fetch may run")
        assertTrue(f.firstSync.isReviewPending(graphId))
        f.service.shutdown()
    }

    @Test
    fun `manual sync is not blocked by a pending review`() = runBlocking {
        val f = fixture(reviewPending = true)

        val result = f.service.sync(graphId)

        // Offline machines stop at the network check, which is still past the gate.
        assertFalse(result is Either.Left && result.value is DomainError.GitError.FirstSyncReviewPending)
        f.service.shutdown()
    }

    @Test
    fun `automatic sync resumes once the review is confirmed`() = runBlocking {
        val f = fixture(reviewPending = true)
        f.firstSync.confirm(graphId, sampleConfig.remoteName, sampleConfig.remoteBranch)

        val result = f.service.sync(graphId, SyncTrigger.Automatic)

        assertFalse(result is Either.Left && result.value is DomainError.GitError.FirstSyncReviewPending)
        f.service.shutdown()
    }

    @Test
    fun `error type is the typed gate error`() {
        assertIs<DomainError.GitError>(DomainError.GitError.FirstSyncReviewPending)
    }
}
