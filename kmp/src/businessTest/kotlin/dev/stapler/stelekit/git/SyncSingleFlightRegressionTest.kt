// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 1.1a regression tests for single-flight sync (I-E1-01, I-E1-02 and the coalesce-during-state-write
 * ACs). They are written against today's tree, so they compile without the gate types and are expected to
 * FAIL until Story 1.4 lands: on `main` a second `sync` runs concurrently, deletes an old live lock and
 * rewrites `syncState`.
 */
class SyncSingleFlightRegressionTest {
    private val gitDir: File = Files.createTempDirectory("single_flight_").toFile()
    private val repo = GateAwareFakeGitRepository(gitDir)
    private var online = true
    private var vaultAvailable = true
    private val states = CopyOnWriteArrayList<SyncState>()
    private val config: GitConfig = sampleConfig.copy(repoRoot = RepoRoot(gitDir.absolutePath))

    private val service: GitSyncService = buildGitSyncTestService(
        gitRepository = repo,
        configRepository = StubConfigRepository(Either.Right(config)),
        credentialAccessProvider = { VaultStub { vaultAvailable } },
        isOnline = { online },
    )

    @AfterTest
    fun tearDown() {
        repo.releaseBlocked()
        service.shutdown()
        gitDir.deleteRecursively()
    }

    private class VaultStub(private val available: () -> Boolean) : dev.stapler.stelekit.platform.security.CredentialAccess {
        override fun retrieve(key: String): String? = null
        override fun store(key: String, value: String) {}
        override fun delete(key: String) {}
        override fun isAvailable(): Boolean = available()
    }

    private fun isSyncInProgress(r: Either<DomainError.GitError, *>): Boolean =
        r.leftOrNull() is DomainError.GitError.SyncInProgress

    private fun startFirstSync(): Deferred<Either<DomainError.GitError, SyncState.Success>> =
        runBlocking { CoroutineScopeHolder.scope.async(start = CoroutineStart.DEFAULT) { service.sync("g", SyncTrigger.Manual) } }

    private fun recordStates(): () -> List<SyncState> {
        val collector = CoroutineScopeHolder.scope.async { service.syncState.collect { states += it } }
        return { collector.cancel(); states.toList() }
    }

    @Test
    fun secondManualSync_should_ReturnSyncInProgress_AndCreateNoLock_When_FirstBlockedInCommit() = runBlocking<Unit> {
        repo.blockAt(FakeGitOp.Commit)
        val first = startFirstSync()
        withTimeout(TIMEOUT_MS) { repo.awaitBlocked() }
        val checksBefore = repo.removeStaleLockCalls
        val stop = recordStates()

        val second = withTimeout(TIMEOUT_MS) { service.sync("g", SyncTrigger.Manual) }
        val seen = stop()

        assertTrue(isSyncInProgress(second), "second trigger must coalesce, got $second")
        assertEquals(checksBefore, repo.removeStaleLockCalls, "second call must not reach the lock recovery step")
        assertTrue(seen.none { it is SyncState.Error }, "a coalesced trigger must never publish Error, saw $seen")
        assertEquals(1, repo.maxConcurrent, "repository calls must never overlap")
        repo.releaseBlocked()
        withTimeout(TIMEOUT_MS) { first.await() }
    }

    @Test
    fun liveLockOlderThan60s_should_NotBeDeleted_When_HeldByInFlightRun() = runBlocking<Unit> {
        repo.blockAt(FakeGitOp.Commit)
        val first = startFirstSync()
        withTimeout(TIMEOUT_MS) { repo.awaitBlocked() }
        assertTrue(repo.indexLock.setLastModified(System.currentTimeMillis() - 90_000L))

        withTimeout(TIMEOUT_MS) { service.sync("g", SyncTrigger.Manual) }

        assertTrue(repo.indexLock.exists(), "a live run's index.lock must survive a second trigger")
        repo.releaseBlocked()
        withTimeout(TIMEOUT_MS) { first.await() }
    }

    @Test
    fun secondManualSync_should_CoalesceAndLeaveStateUnchanged_When_FirstIsMergingAndRepositoryMidMerge() = runBlocking<Unit> {
        repo.hasRemoteChanges = true
        repo.blockAt(FakeGitOp.Merge)
        val first = startFirstSync()
        withTimeout(TIMEOUT_MS) { repo.awaitBlocked() }
        repo.mergeInProgress = true
        assertEquals(SyncState.Merging, service.syncState.value)

        val second = withTimeout(TIMEOUT_MS) { service.sync("g", SyncTrigger.Manual) }

        assertTrue(isSyncInProgress(second), "second trigger must coalesce, got $second")
        assertEquals(SyncState.Merging, service.syncState.value, "state must not become Error(RepairNeeded)")
        repo.releaseBlocked()
        withTimeout(TIMEOUT_MS) { first.await() }
    }

    @Test
    fun secondManualSync_should_CoalesceAndLeaveStateUnchanged_When_FirstFetchingAndOfflineAndVaultLocked() = runBlocking<Unit> {
        repo.blockAt(FakeGitOp.Fetch)
        val first = startFirstSync()
        withTimeout(TIMEOUT_MS) { repo.awaitBlocked() }
        online = false
        vaultAvailable = false
        assertEquals(SyncState.Fetching, service.syncState.value)

        val second = withTimeout(TIMEOUT_MS) { service.sync("g", SyncTrigger.Manual) }

        assertTrue(isSyncInProgress(second), "second trigger must coalesce, got $second")
        assertEquals(SyncState.Fetching, service.syncState.value, "state must not become Error(Offline) or CredentialVaultLocked")
        assertNotNull(second.leftOrNull())
        assertFalse(service.syncState.value is SyncState.Error)
        repo.releaseBlocked()
        withTimeout(TIMEOUT_MS) { first.await() }
    }

    private object CoroutineScopeHolder {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
