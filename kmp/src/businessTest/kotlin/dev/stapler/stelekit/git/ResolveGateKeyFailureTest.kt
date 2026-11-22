// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** U-E1-13: an unreadable config or git dir fails closed; no repository method runs under any other key. */
class ResolveGateKeyFailureTest {
    private val gitDir: File = Files.createTempDirectory("resolve_gate_key_").toFile()
    private val config: GitConfig = sampleConfig.copy(repoRoot = RepoRoot(gitDir.absolutePath))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        gitDir.deleteRecursively()
    }

    /** Fails the first [failures] reads with [failure] (a thrown exception, or a `Left` when it returns null). */
    private class FlakyConfigRepository(
        private val config: GitConfig?,
        var failures: Int,
        private val failure: () -> Throwable?,
    ) : GitConfigRepository by StubConfigRepository(Either.Right(config)) {
        var reads = 0

        override suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?> {
            reads++
            if (failures > 0) {
                failures--
                failure()?.let { throw it }
                return Either.Left(DomainError.DatabaseError.ReadFailed("db closed"))
            }
            return config.right()
        }
    }

    private open class RecordingRepository(private val keyOverride: (GitConfig) -> String?) : StubGitRepository() {
        val calls = mutableListOf<String>()
        override fun gateKeyFor(config: GitConfig): String? = keyOverride(config)
        override suspend fun hasDetachedHead(config: GitConfig): Boolean = false.also { calls += "detached" }
        override suspend fun beginOwnedOperation(config: GitConfig, info: OwnedOperationInfo) =
            super.beginOwnedOperation(config, info).also { calls += "begin" }
        override suspend fun status(config: GitConfig) =
            GitStatus(false, emptyList(), emptyList()).right().also { calls += "status" }
        override suspend fun fetch(config: GitConfig) =
            FetchResult(false, 0).right().also { calls += "fetch" }
        override suspend fun push(config: GitConfig) = Unit.right().also { calls += "push" }
    }

    private fun service(configs: GitConfigRepository, git: GitRepository, gate: RepoOpGate = RepoOpGate()) =
        buildGitSyncTestService(gitRepository = git, configRepository = configs, isOnline = { true }, gate = gate)

    private fun repoKey(): String = checkNotNull(RepoOpGate.keyForOrNull("${gitDir.absolutePath}/.git") { File(it).canonicalFile.path })

    @Test
    fun resolveGateKey_should_FailClosedAndMakeNoGitCall_When_ConfigReadThrowsTwice() = runBlocking<Unit> {
        val git = RecordingRepository { repoKey() }
        val configs = FlakyConfigRepository(config, failures = 2) { IllegalStateException("db exploded") }
        val gate = RepoOpGate()
        val holder = checkNotNull(gate.tryAcquire(repoKey(), SyncOrigin.Worker))
        val svc = service(configs, git, gate)

        val result = svc.sync("g")

        assertEquals(2, configs.reads, "one retry, then give up")
        assertIs<DomainError.GitError.FetchFailed>(result.leftOrNull())
        assertEquals(emptyList(), git.calls, "no repository method may run when the key cannot be resolved")
        assertTrue(gate.isHeld(repoKey()), "the other holder's lease is untouched")
        gate.releaseOrHandOff(holder)
    }

    @Test
    fun resolveGateKey_should_FailClosed_When_ConfigReadReturnsLeftTwice() = runBlocking<Unit> {
        val git = RecordingRepository { repoKey() }
        val configs = FlakyConfigRepository(config, failures = 2) { null }
        val svc = service(configs, git)

        val result = svc.sync("g")

        assertEquals(2, configs.reads)
        assertEquals(DomainError.GitError.FetchFailed("Failed to load git config: db closed"), result.leftOrNull())
        assertEquals(SyncState.Error(DomainError.GitError.FetchFailed("Failed to load git config: db closed")), svc.syncState.value)
        assertEquals(emptyList(), git.calls)
    }

    @Test
    fun resolveGateKey_should_Resolve_When_OnlyTheFirstReadFails() = runBlocking<Unit> {
        val git = RecordingRepository { repoKey() }
        val configs = FlakyConfigRepository(config, failures = 1) { IllegalStateException("transient") }
        val svc = service(configs, git)

        assertTrue(svc.sync("g").isRight(), "the retry must recover a transient failure")
        assertTrue("fetch" in git.calls)
    }

    @Test
    fun resolveGateKey_should_FailClosedAndMakeNoGitCall_When_GitDirCannotBeCanonicalised() = runBlocking<Unit> {
        val git = RecordingRepository { null }
        val svc = service(StubConfigRepository(Either.Right(config)), git)

        val result = svc.sync("g")

        assertIs<DomainError.GitError.FetchFailed>(result.leftOrNull())
        assertEquals(emptyList(), git.calls)
    }

    @Test
    fun resolveGateKey_should_FailClosedAndMakeNoGitCall_When_CanonicalisationThrows() = runBlocking<Unit> {
        val git = RecordingRepository { throw java.io.IOException("canonicalisation failed") }
        val svc = service(StubConfigRepository(Either.Right(config)), git)

        assertIs<DomainError.GitError.FetchFailed>(svc.sync("g").leftOrNull())
        assertEquals(emptyList(), git.calls)
    }

    @Test
    fun resolveGateKey_should_Coalesce_When_ServiceIsRunningAndConfigBecomesUnreadable() = runBlocking<Unit> {
        val git = GateAwareFakeGitRepository(gitDir)
        val configs = FlakyConfigRepository(config, failures = 0) { IllegalStateException("db exploded") }
        val svc = service(configs, git)
        git.blockAt(FakeGitOp.Commit)
        val first = scope.async { svc.sync("g") }
        withTimeout(20_000) { git.awaitBlocked() }
        configs.failures = Int.MAX_VALUE
        val stateBefore = svc.syncState.value

        val second = withTimeout(20_000) { svc.sync("g") }

        assertEquals(DomainError.GitError.SyncInProgress(SyncOrigin.UiPress), second.leftOrNull())
        assertEquals(stateBefore, svc.syncState.value, "a coalesced call must not overwrite the running sync's state")
        git.releaseBlocked()
        withTimeout(20_000) { first.await() }
        svc.shutdown()
    }

    @Test
    fun resolveGateKey_should_RethrowCancellation_When_ConfigReadIsCancelled() = runBlocking<Unit> {
        val git = RecordingRepository { repoKey() }
        val configs = FlakyConfigRepository(config, failures = 1) { CancellationException("cancelled") }
        val svc = service(configs, git)

        assertFailsWith<CancellationException> { svc.sync("g") }
        assertEquals(1, configs.reads, "a cancellation is not retried")
        assertNull(svc.inFlight.value)
    }
}
