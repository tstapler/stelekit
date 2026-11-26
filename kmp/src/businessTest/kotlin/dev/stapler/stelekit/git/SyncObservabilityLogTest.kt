// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.logging.LogSink
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 1.8 AC "Log lines present": every sync decision and every lock verdict reaches the Logs sink as one line. */
class SyncObservabilityLogTest {
    private val repoDir: File = Files.createTempDirectory("sync_observability_").toFile()
    private val config: GitConfig = sampleConfig.copy(repoRoot = RepoRoot(repoDir.absolutePath))
    private val gate = RepoOpGate()
    private val lines = CopyOnWriteArrayList<String>()
    private val sink = LogManager.addSink(object : LogSink {
        override fun write(entry: LogEntry, formatted: String) {
            if (entry.tag == "SyncSingleFlight") lines += entry.message
        }
    })
    private val services = mutableListOf<GitSyncService>()

    @AfterTest
    fun tearDown() {
        sink.close()
        services.forEach { it.shutdown() }
        repoDir.deleteRecursively()
    }

    private class ScriptedRepository(var verdict: LockVerdict) : StubGitRepository() {
        override fun gateKeyFor(config: GitConfig): String? =
            RepoOpGate.keyForOrNull("${config.repoRoot.value}/.git") { File(it).canonicalFile.path }

        override suspend fun beginOwnedOperation(config: GitConfig, info: OwnedOperationInfo): Either<DomainError.GitError, LockVerdict> =
            verdict.right()

        override suspend fun status(config: GitConfig) = GitStatus(false, emptyList(), emptyList()).right()
        override suspend fun fetch(config: GitConfig) = FetchResult(false, 0).right()
        override suspend fun push(config: GitConfig) = Unit.right()
    }

    private fun service(repo: GitRepository): GitSyncService =
        buildGitSyncTestService(
            gitRepository = repo,
            configRepository = StubConfigRepository(Either.Right(config)),
            isOnline = { true },
            gate = gate,
        ).also { services += it }

    private fun logged(fragment: String) = lines.any { fragment in it }

    // runBlocking: GitSyncService does its work on its own real-dispatcher scope, which virtual time cannot drive
    @Test
    fun everyLockVerdict_should_LogOneLockVerdictLine() = runBlocking {
        val verdicts = listOf(
            LockVerdict.NoLock, LockVerdict.ClearedOwn, LockVerdict.ClearedDeadOwner, LockVerdict.OwnerAlive,
            LockVerdict.OwnerUnknown(UnknownReason.TornRecord), LockVerdict.NoRecord,
        )
        val repo = ScriptedRepository(LockVerdict.NoLock)
        val svc = service(repo)
        verdicts.forEach { verdict ->
            repo.verdict = verdict
            svc.sync("g")
            assertEquals(1, lines.count { it == "lock verdict=$verdict" }, "verdict $verdict: $lines")
        }
    }

    // runBlocking: GitSyncService does its work on its own real-dispatcher scope, which virtual time cannot drive
    @Test
    fun startedDecision_should_BeLogged_When_TheClaimSucceeds() = runBlocking {
        service(ScriptedRepository(LockVerdict.NoLock)).sync("g")
        assertTrue(logged("sync origin=UiPress decision=started"), "$lines")
    }

    // runBlocking: GitSyncService does its work on its own real-dispatcher scope, which virtual time cannot drive
    @Test
    fun skippedDecision_should_BeLogged_When_TheOwnerIsUnknown() = runBlocking {
        val result = service(ScriptedRepository(LockVerdict.OwnerUnknown(UnknownReason.TornRecord))).sync("g")
        assertIs<Either.Left<*>>(result)
        assertTrue(logged("sync origin=UiPress decision=skipped reason=StaleLockFile"), "$lines")
    }

    // runBlocking: GitSyncService does its work on its own real-dispatcher scope, which virtual time cannot drive
    @Test
    fun coalescedDecision_should_BeLogged_When_AnotherRunHoldsTheGate() = runBlocking {
        val repo = ScriptedRepository(LockVerdict.NoLock)
        val svc = service(repo)
        val key = checkNotNull(repo.gateKeyFor(config))
        val held = checkNotNull(gate.tryAcquire(key, SyncOrigin.Scheduled))
        try {
            val result = svc.sync("g")
            assertIs<Either.Left<*>>(result)
            assertTrue(logged("sync origin=UiPress decision=coalesced"), "$lines")
        } finally {
            gate.releaseOrHandOff(held)
        }
    }
}
