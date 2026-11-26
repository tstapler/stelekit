// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 1.7c: automatic paused retry, "Retry now" budget rules, durability and escalation (UX-51, UX-52). */
@OptIn(ExperimentalCoroutinesApi::class)
class PausedRetryNowAndDurabilityTest {

    // ---- controller, virtual time ----

    private class Harness(val scope: TestScope) {
        val attempts = AtomicInteger()
        var result = PausedAttemptResult.StillUnsure
        var parked: CompletableDeferred<Unit>? = null
        var now: () -> Long = { scope.testScheduler.currentTime }
        val controller = PausedRetryController(
            scope.backgroundScope, { now() }, PausedRetryPolicy(), awaitFree = {},
            attempt = {
                attempts.incrementAndGet()
                parked?.await()
                result
            },
        )
    }

    @Test
    fun unsure_should_ScheduleTryOneThenTryTwoThenEscalateToFailed() = runTest {
        val h = Harness(this)
        h.controller.onUnsure("g", PausedSource.User)
        assertEquals(PausedRetryInfo(30_000, 1), h.controller.pending.value)

        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(1, h.attempts.get())
        assertEquals(2, h.controller.pending.value?.tryNumber)

        advanceTimeBy(120_001)
        runCurrent()
        assertEquals(2, h.attempts.get())
        assertNull(h.controller.pending.value, "both tries spent: Failed")

        advanceTimeBy(600_000)
        assertEquals(2, h.attempts.get(), "no third automatic attempt")
    }

    @Test
    fun success_should_ClearPending() = runTest {
        val h = Harness(this).apply { result = PausedAttemptResult.Settled }
        h.controller.onUnsure("g", PausedSource.User)
        advanceTimeBy(30_001)
        runCurrent()
        assertNull(h.controller.pending.value)
    }

    @Test
    fun fiveRapidRetryNow_should_RunOneAttemptAtATime_KeepTheTryNumberAndNeverReachFailed() = runTest {
        val h = Harness(this).apply { parked = CompletableDeferred() }
        h.controller.onUnsure("g", PausedSource.User)
        val coalesced = AtomicInteger()

        repeat(5) { h.controller.retryNow { coalesced.incrementAndGet() } }
        runCurrent()
        assertEquals(1, h.attempts.get(), "presses while an attempt runs coalesce")
        assertEquals(4, coalesced.get())

        h.parked!!.complete(Unit)
        runCurrent()
        val info = assertNotNull(h.controller.pending.value, "still paused, not Failed")
        assertEquals(1, info.tryNumber)
        assertEquals(30_000, info.nextRetryAtMs - testScheduler.currentTime, "re-armed with the original delay")
    }

    @Test
    fun repeatedRetryNow_should_NeverAddRetriesOrReachFailed() = runTest {
        val h = Harness(this)
        h.controller.onUnsure("g", PausedSource.User)
        repeat(10) {
            h.controller.retryNow()
            runCurrent()
        }
        assertEquals(10, h.attempts.get())
        assertEquals(1, h.controller.pending.value?.tryNumber)
    }

    @Test
    fun retryNowThatCoalescesAgainstAHeldGate_should_AnnounceAndKeepTheTry() = runTest {
        val h = Harness(this).apply { result = PausedAttemptResult.Coalesced }
        h.controller.onUnsure("g", PausedSource.User)
        var announced = 0

        h.controller.retryNow { announced++ }
        runCurrent()

        assertEquals(1, announced)
        assertEquals(1, h.controller.pending.value?.tryNumber)
    }

    @Test
    fun syncNowFromFailed_should_RestartTheBudget_butABackgroundTickShouldNot() = runTest {
        val h = Harness(this)
        h.controller.onUnsure("g", PausedSource.User)
        advanceTimeBy(30_001)
        runCurrent()
        advanceTimeBy(120_001)
        runCurrent()
        assertNull(h.controller.pending.value)

        h.controller.onUnsure("g", PausedSource.Background)
        assertNull(h.controller.pending.value, "a worker tick must not restart a spent budget")

        h.controller.onUnsure("g", PausedSource.User)
        assertEquals(1, h.controller.pending.value?.tryNumber, "Sync now restarts at try 1")
    }

    @Test
    fun backgroundTickWhilePaused_should_NotConsumeOrRestartTheBudget() = runTest {
        val h = Harness(this)
        h.controller.onUnsure("g", PausedSource.User)
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(2, h.controller.pending.value?.tryNumber)

        h.controller.onUnsure("g", PausedSource.Background)

        assertEquals(2, h.controller.pending.value?.tryNumber)
    }

    @Test
    fun overdueRetry_should_RunOnceWhenPendingIsCollectedAgain() = runTest {
        val h = Harness(this)
        h.controller.onUnsure("g", PausedSource.User)
        h.now = { testScheduler.currentTime + 31_000 }

        h.controller.observed.first()
        runCurrent()
        assertEquals(1, h.attempts.get())

        h.now = { testScheduler.currentTime }
        h.controller.observed.first()
        runCurrent()
        assertEquals(1, h.attempts.get(), "the re-armed try is in the future, so a second collection runs nothing")
    }

    @Test
    fun timedRetry_should_WaitForTheGateToBeFree() = runTest {
        val free = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val controller = PausedRetryController(
            backgroundScope, { testScheduler.currentTime }, PausedRetryPolicy(), awaitFree = { free.await() },
            attempt = { attempts.incrementAndGet(); PausedAttemptResult.Settled },
        )
        controller.onUnsure("g", PausedSource.User)
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(0, attempts.get(), "the retry never runs while the gate is held")
        free.complete(Unit)
        runCurrent()
        assertEquals(1, attempts.get())
    }

    // ---- service, real time with a short policy ----

    private val gitDir: File = Files.createTempDirectory("paused_retry_").toFile()
    private val config: GitConfig = sampleConfig.copy(repoRoot = RepoRoot(gitDir.absolutePath))
    private val services = mutableListOf<GitSyncService>()

    @AfterTest
    fun tearDown() {
        services.forEach { it.shutdown() }
        gitDir.deleteRecursively()
    }

    private class Repo : StubGitRepository() {
        @Volatile var verdict: LockVerdict = LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported)
        val claims = AtomicInteger()
        override fun gateKeyFor(config: GitConfig): String? =
            RepoOpGate.keyForOrNull("${config.repoRoot.value}/.git") { File(it).canonicalFile.path }
        override suspend fun beginOwnedOperation(config: GitConfig, info: OwnedOperationInfo): Either<DomainError.GitError, LockVerdict> {
            claims.incrementAndGet()
            return verdict.right()
        }
        override suspend fun endOwnedOperation(config: GitConfig) = Unit
        override suspend fun status(config: GitConfig) = GitStatus(false, emptyList(), emptyList()).right()
        override suspend fun fetch(config: GitConfig) = FetchResult(false, 0).right()
        override suspend fun push(config: GitConfig) = Unit.right()
        override suspend fun commit(config: GitConfig, message: String) = "sha".right()
    }

    private fun service(repo: Repo, vararg delays: Long): GitSyncService =
        buildGitSyncTestService(
            gitRepository = repo,
            configRepository = StubConfigRepository(Either.Right(config)),
            isOnline = { true },
            pausedRetryPolicy = PausedRetryPolicy(delays.toList()),
        ).also { services += it }

    private suspend fun until(check: () -> Boolean) = withTimeout(20_000) { while (!check()) delay(10) } // real-clock hang guard: awaits real threads

    // runBlocking: this test times or races work on real dispatchers; virtual time would not wait for it
    @Test
    fun service_should_RetryTwiceAutomaticallyThenStayFailedWithTheLockError() = runBlocking<Unit> {
        val repo = Repo()
        val svc = service(repo, 400, 400)

        val first = svc.sync("g")

        assertIs<DomainError.GitError.StaleLockFile>(first.leftOrNull())
        assertEquals(1, svc.pausedRetry.value?.tryNumber)
        until { repo.claims.get() == 3 && svc.pausedRetry.value == null }
        delay(1_000)
        assertEquals(3, repo.claims.get(), "exactly two automatic retries after the first attempt")
        assertIs<SyncState.Error>(svc.syncState.value)
    }

    // runBlocking: until's withTimeout must run on the real clock while the retry fires on a real dispatcher
    @Test
    fun service_should_ClearPausedAndSync_When_TheOwnerBecomesKnownBeforeTheRetry() = runBlocking<Unit> {
        val repo = Repo()
        val svc = service(repo, 400, 400)
        svc.sync("g")
        repo.verdict = LockVerdict.NoLock

        until { svc.pausedRetry.value == null && svc.syncState.value !is SyncState.Error }
    }

    // runBlocking: this test times or races work on real dispatchers; virtual time would not wait for it
    @Test
    fun retryNow_should_RunExtraAttemptsWithoutMovingTheTryNumber() = runBlocking<Unit> {
        val repo = Repo()
        val svc = service(repo, 60_000, 120_000)
        svc.sync("g")
        assertEquals(1, repo.claims.get())

        repeat(5) {
            svc.retryNow()
            delay(20)
        }

        until { repo.claims.get() > 1 }
        assertEquals(1, svc.pausedRetry.value?.tryNumber)
        assertIs<DomainError.GitError.StaleLockFile>((svc.syncState.value as SyncState.Error).error)
    }

    @Test
    fun rebuiltService_should_HaveNoPausedStateAndClaimAgain() = runTest {
        val repo = Repo()
        val dead = service(repo, 60_000, 120_000)
        dead.sync("g")
        assertNotNull(dead.pausedRetry.value)
        dead.shutdown()

        repo.verdict = LockVerdict.ClearedDeadOwner
        val reborn = service(repo, 60_000, 120_000)

        assertNull(reborn.pausedRetry.value, "nothing is persisted across process death")
        assertTrue(reborn.sync("g").isRight(), "the next sync claims again and clears a dead owner")
    }
}
