// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PR #682 review C1: cancellation between a successful claim and `claimed = true` must still end the run. */
class SyncSingleFlightCancellationTest {
    private val gitDir: File = Files.createTempDirectory("single_flight_cancel_").toFile()
    private val inner = GateAwareFakeGitRepository(gitDir)
    private val entered = CompletableDeferred<Unit>()
    private val latch = CountDownLatch(1)
    private val began = AtomicInteger(0)
    private val ended = AtomicInteger(0)
    private val pushEntered = CompletableDeferred<Unit>()
    @Volatile private var pushHangs = false
    private val config: GitConfig = sampleConfig.copy(repoRoot = RepoRoot(gitDir.absolutePath))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The claim does blocking IO inside withContext(IO), like JvmGitRepository.beginOwnedOperation.
    private val repo = object : GitRepository by inner {
        override suspend fun beginOwnedOperation(config: GitConfig, info: OwnedOperationInfo) = withContext(Dispatchers.IO) {
            entered.complete(Unit)
            latch.await()
            began.incrementAndGet()
            Either.Right(LockVerdict.NoLock)
        }

        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> {
            if (pushHangs) {
                pushEntered.complete(Unit)
                awaitCancellation()
            }
            return inner.push(config)
        }

        override suspend fun endOwnedOperation(config: GitConfig) {
            if (began.get() > 0) ended.incrementAndGet()
        }
    }

    private val service = buildGitSyncTestService(
        gitRepository = repo,
        configRepository = StubConfigRepository(Either.Right(config)),
        isOnline = { true },
    )

    @AfterTest
    fun tearDown() {
        latch.countDown()
        service.shutdown()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        gitDir.deleteRecursively()
    }

    @Test
    fun sync_should_EndTheOwnedOperation_When_CancelledWhileTheClaimIsInFlight() = runBlocking<Unit> {
        val job = scope.launch { service.sync("g", SyncTrigger.Manual) }
        withTimeout(TIMEOUT_MS) { entered.await() }

        job.cancel()
        latch.countDown()
        withTimeout(TIMEOUT_MS) { job.join() }

        assertTrue(began.get() == 1, "the claim must have completed")
        assertEquals(1, ended.get(), "a claim that completed must be ended even though the caller was cancelled")
    }

    // PR #682 review M2: a hung push must not hold the gate forever once the sync is cancelled.
    @Test
    fun sync_should_ReleaseTheGate_When_CancelledDuringAHungPush() = runBlocking<Unit> {
        latch.countDown()
        pushHangs = true
        val job = scope.launch { service.sync("g", SyncTrigger.Manual) }
        withTimeout(TIMEOUT_MS) { pushEntered.await() }

        job.cancel()
        withTimeout(TIMEOUT_MS) { job.join() }

        assertEquals(1, ended.get())
        pushHangs = false
        val again = withTimeout(TIMEOUT_MS) { service.sync("g", SyncTrigger.Manual) }
        assertTrue(again.leftOrNull() !is DomainError.GitError.SyncInProgress, "gate must be free again, got $again")
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
