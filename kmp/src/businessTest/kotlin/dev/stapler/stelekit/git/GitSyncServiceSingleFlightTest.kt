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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 1.4 acquire/release structure at the service (U-E1-16..21, the acquire/release half). */
class GitSyncServiceSingleFlightTest {
    private val gitDir: File = Files.createTempDirectory("single_flight_service_").toFile()
    private val config: GitConfig = sampleConfig.copy(repoRoot = RepoRoot(gitDir.absolutePath))
    private val gate = RepoOpGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val services = mutableListOf<GitSyncService>()

    @AfterTest
    fun tearDown() {
        services.forEach { it.shutdown() }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        gitDir.deleteRecursively()
    }

    private fun service(git: GitRepository): GitSyncService =
        buildGitSyncTestService(
            gitRepository = git,
            configRepository = StubConfigRepository(Either.Right(config)),
            isOnline = { true },
            gate = gate,
        ).also { services += it }

    private fun key(): String = checkNotNull(RepoOpGate.keyForOrNull("${gitDir.absolutePath}/.git") { File(it).canonicalFile.path })

    /** Every repository call a sync, fetch, commit, resolve or abort can make succeeds; a verdict and hooks are scriptable. */
    private open class OkRepository : StubGitRepository() {
        var verdict: LockVerdict = LockVerdict.NoLock
        var onBegin: () -> Unit = {}
        var onEnd: () -> Unit = {}
        var begins = 0
        var ends = 0
        val calls = mutableListOf<String>()

        override fun gateKeyFor(config: GitConfig): String? =
            RepoOpGate.keyForOrNull("${config.repoRoot.value}/.git") { File(it).canonicalFile.path }
        override suspend fun beginOwnedOperation(config: GitConfig, info: OwnedOperationInfo): Either<DomainError.GitError, LockVerdict> {
            begins++
            onBegin()
            return verdict.right()
        }
        override suspend fun endOwnedOperation(config: GitConfig) {
            ends++
            onEnd()
        }
        override suspend fun status(config: GitConfig) = GitStatus(false, emptyList(), emptyList()).right()
        override suspend fun fetch(config: GitConfig) = FetchResult(false, 0).right().also { calls += "fetch" }
        override suspend fun push(config: GitConfig) = Unit.right().also { calls += "push" }
        override suspend fun commit(config: GitConfig, message: String) = "sha".right().also { calls += "commit" }
        override suspend fun unshallow(config: GitConfig) = Unit.right().also { calls += "unshallow" }
        override suspend fun abortMerge(config: GitConfig) = Unit.right().also { calls += "abort" }
    }

    @Test
    fun everyMutatingEntryPoint_should_RunWithTheGateHeld_When_RepositoryAssertsIt() = runBlocking<Unit> {
        val git = GateAssertingGitRepository(OkRepository(), gate, keyFor = { c -> checkNotNull(RepoOpGate.keyForOrNull("${c.repoRoot.value}/.git") { File(it).canonicalFile.path }) })
        val svc = service(git)
        suspend fun noGateViolation(name: String, call: suspend () -> Any?) {
            try {
                call()
            } catch (e: AssertionError) {
                throw AssertionError("$name ran without the gate: ${e.message}", e)
            } catch (_: CancellationException) {
                throw IllegalStateException("$name was cancelled")
            } catch (_: Exception) {
                // An unscripted collaborator may fail the call; only a gate violation matters here.
            }
        }
        noGateViolation("sync") { svc.sync("g") }
        noGateViolation("fetchOnly") { svc.fetchOnly("g") }
        noGateViolation("commitLocalChanges") { svc.commitLocalChanges("g") }
        noGateViolation("resolveConflicts") { svc.resolveConflicts("g", emptyList()) }
        noGateViolation("applyJournalMerge") { svc.applyJournalMerge("g", "${gitDir.absolutePath}/j.md", "- a") }
        noGateViolation("abortActiveMerge") { svc.abortActiveMerge("g") }
        noGateViolation("deepen") { svc.deepen("g") }
        noGateViolation("repairBranch") { svc.repairBranch(BranchRepairProposal("g", "main", "master", listOf("master"))) }
        noGateViolation("changeBackBranch") { svc.changeBackBranch() }
        assertFalse(gate.isHeld(key()), "every entry point must release")
    }

    @Test
    fun gate_should_BeFreeAndSyncRunnable_When_BeginOwnedOperationThrows() = runBlocking<Unit> {
        val git = OkRepository().apply { onBegin = { throw IllegalStateException("record write failed") } }
        val svc = service(git)

        assertFailsWith<IllegalStateException> { svc.sync("g") }
        assertFalse(gate.isHeld(key()))
        assertNull(svc.inFlight.value)

        git.onBegin = {}
        assertTrue(svc.sync("g").isRight(), "a fresh sync must acquire and run")
    }

    @Test
    fun gate_should_BeFree_When_EndOwnedOperationThrowsAnything() = runBlocking<Unit> {
        for (failure in listOf<() -> Throwable>({ RuntimeException("boom") }, { CancellationException("cancelled") })) {
            val git = OkRepository().apply { onEnd = { throw failure() } }
            val svc = service(git)

            runCatching { svc.sync("g") }

            assertFalse(gate.isHeld(key()), "release must follow a throwing record end")
            git.onEnd = {}
            assertTrue(svc.sync("g").isRight(), "a fresh sync must acquire and run")
        }
    }

    @Test
    fun gate_should_BeFreeAndSyncRunnable_When_RunIsCancelledMidCommit() = runBlocking<Unit> {
        val git = GateAwareFakeGitRepository(gitDir)
        val svc = service(git)
        git.blockAt(FakeGitOp.Commit)
        val first = scope.async { svc.sync("g") }
        withTimeout(20_000) { git.awaitBlocked() }
        assertTrue(gate.isHeld(key()))

        first.cancelAndJoin()

        assertFalse(gate.isHeld(key()), "cancellation must release the lease")
        assertNull(svc.inFlight.value)
        assertTrue(withTimeout(20_000) { svc.sync("g") }.isRight())
    }

    @Test
    fun secondPress_should_LeaveInFlightAndStateUnchanged_When_FirstIsCommitting() = runBlocking<Unit> {
        val git = GateAwareFakeGitRepository(gitDir)
        val svc = service(git)
        git.blockAt(FakeGitOp.Commit)
        val first = scope.async { svc.sync("g") }
        withTimeout(20_000) { git.awaitBlocked() }
        val before = assertNotNull(svc.inFlight.value)
        assertEquals(SyncOrigin.UiPress, before.origin)
        assertEquals(SyncState.Committing, svc.syncState.value)

        val second = withTimeout(20_000) { svc.sync("g") }

        assertEquals(DomainError.GitError.SyncInProgress(SyncOrigin.UiPress), second.leftOrNull())
        assertEquals(before, svc.inFlight.value)
        assertEquals(SyncState.Committing, svc.syncState.value)
        git.releaseBlocked()
        withTimeout(20_000) { first.await() }
        assertNull(svc.inFlight.value)
    }

    @Test
    fun claim_should_RefuseWithSyncInProgressAndRunNothing_When_OwnerAlive() = runBlocking<Unit> {
        val git = OkRepository().apply { verdict = LockVerdict.OwnerAlive }
        val svc = service(git)

        val result = svc.sync("g")

        assertEquals(DomainError.GitError.SyncInProgress(null), result.leftOrNull())
        assertEquals(emptyList(), git.calls)
        assertEquals(0, git.ends, "an unclaimed run must not end a record it did not write")
        assertFalse(svc.syncState.value is SyncState.Error, "a live other owner is not an error")
        assertFalse(gate.isHeld(key()))
    }

    @Test
    fun claim_should_PublishStaleLockFileWithVerdictAndDeleteNothing_When_OwnerUnknown() = runBlocking<Unit> {
        val verdict = LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported)
        val git = OkRepository().apply { this.verdict = verdict }
        val svc = service(git)

        val result = svc.sync("g")

        val err = assertIs<DomainError.GitError.StaleLockFile>(result.leftOrNull())
        assertEquals(verdict, err.verdict)
        assertEquals(SyncState.Error(err), svc.syncState.value)
        assertEquals(emptyList(), git.calls)
        assertFalse(gate.isHeld(key()))
    }

    @Test
    fun record_should_BeEndedExactlyOncePerClaimedRun_AndNeverForACoalescedCall() = runBlocking<Unit> {
        val git = OkRepository()
        val svc = service(git)

        assertTrue(svc.sync("g").isRight())
        assertEquals(1, git.begins)
        assertEquals(1, git.ends)

        val holder = checkNotNull(gate.tryAcquire(key(), SyncOrigin.Worker))
        assertIs<DomainError.GitError.SyncInProgress>(svc.fetchOnly("g").leftOrNull())
        assertEquals(1, git.begins, "a coalesced call must not claim")
        gate.releaseOrHandOff(holder)
    }
}
