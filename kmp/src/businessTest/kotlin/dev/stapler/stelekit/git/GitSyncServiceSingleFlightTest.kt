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
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var fetchGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

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
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
            calls += "fetch"
            fetchGate?.takeIf { calls.count { it == "fetch" } == 1 }?.await()
            return FetchResult(false, 0).right()
        }
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

        // The commit is non-cancellable (1.5a): cancellation lands once the blocked call returns, and the gate stays held until then.
        first.cancel()
        assertTrue(gate.isHeld(key()), "the gate is held while the blocking call runs")
        git.releaseBlocked()
        withTimeout(20_000) { first.join() }

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
        assertEquals(before.copy(followUpQueued = true), svc.inFlight.value, "only the queued-follow-up bit may change")
        assertEquals(SyncState.Committing, svc.syncState.value)
        git.releaseBlocked()
        withTimeout(20_000) { first.await() }
        // The queued press runs once after the first run, so the gate is free only after that follow-up.
        withTimeout(20_000) { while (svc.inFlight.value != null) kotlinx.coroutines.yield() }
    }

    private class MapSettings : dev.stapler.stelekit.platform.Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private fun coldRunner(git: GitRepository) = ColdSyncRunner(
        git, StubConfigRepository(Either.Right(config)), SyncStalenessStore(MapSettings()), gate, { key() }, { 1L },
    )

    @Test
    fun servicePress_should_CoalesceAndNotInterruptTheFetch_When_AColdRunHoldsTheGate() = runBlocking<Unit> {
        val cold = OkRepository().apply { fetchGate = kotlinx.coroutines.CompletableDeferred() }
        val running = scope.async { coldRunner(cold).run("g") }
        withTimeout(20_000) { while (cold.calls.isEmpty()) kotlinx.coroutines.yield() }
        val live = OkRepository()
        val svc = service(live)

        val pressed = withTimeout(20_000) { svc.sync("g") }

        assertEquals(DomainError.GitError.SyncInProgress(SyncOrigin.Cold), pressed.leftOrNull())
        assertEquals(emptyList(), live.calls, "the coalesced press must not touch the repository")
        assertFalse(running.isCompleted, "the cold fetch keeps running")
        cold.fetchGate!!.complete(Unit)
        assertIs<ColdSyncOutcome.Fetched>(withTimeout(20_000) { running.await() })
        assertEquals(listOf("fetch"), cold.calls)
    }

    @Test
    fun coldRun_should_ReturnBusyWithoutWaiting_When_ALiveSyncHoldsTheGate() = runBlocking<Unit> {
        val live = OkRepository().apply { fetchGate = kotlinx.coroutines.CompletableDeferred() }
        val svc = service(live)
        val first = scope.async { svc.sync("g") }
        withTimeout(20_000) { while (live.calls.isEmpty()) kotlinx.coroutines.yield() }
        val cold = OkRepository()

        val outcome = withTimeout(20_000) { coldRunner(cold).run("g") }

        assertEquals(ColdSyncOutcome.Error(DomainError.GitError.SyncInProgress(SyncOrigin.UiPress)), outcome)
        assertEquals(emptyList(), cold.calls, "no second fetch is issued")
        live.fetchGate!!.complete(Unit)
        withTimeout(20_000) { first.await() }
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

    private suspend fun awaitFetches(git: OkRepository, n: Int) {
        withTimeout(20_000) { while (git.calls.count { it == "fetch" } < n) kotlinx.coroutines.delay(10) }
    }

    private suspend fun settle() = kotlinx.coroutines.delay(300)

    private suspend fun GitSyncService.startBlockedFirstSync(git: OkRepository): kotlinx.coroutines.Deferred<*> {
        git.fetchGate = kotlinx.coroutines.CompletableDeferred()
        val first = scope.async { sync("g") }
        awaitFetches(git, 1)
        return first
    }

    @Test
    fun threeUiPresses_should_YieldExactlyOneFollowUp_AndScheduledIsDropped() = runBlocking<Unit> {
        val git = OkRepository()
        val svc = service(git)
        val first = svc.startBlockedFirstSync(git)

        repeat(3) { assertIs<DomainError.GitError.SyncInProgress>(svc.sync("g").leftOrNull()) }
        assertIs<DomainError.GitError.SyncInProgress>(svc.sync("g", SyncTrigger.Automatic).leftOrNull())
        git.fetchGate?.complete(Unit)
        withTimeout(20_000) { first.await() }
        awaitFetches(git, 2)
        settle()

        assertEquals(2, git.calls.count { it == "fetch" }, "the original run plus exactly one follow-up")
        assertEquals(2, git.begins)
        assertFalse(gate.isHeld(key()))
    }

    @Test
    fun scheduledAlone_should_NotQueueAFollowUp() = runBlocking<Unit> {
        val git = OkRepository()
        val svc = service(git)
        val first = svc.startBlockedFirstSync(git)

        assertIs<DomainError.GitError.SyncInProgress>(svc.sync("g", SyncTrigger.Automatic).leftOrNull())
        git.fetchGate?.complete(Unit)
        withTimeout(20_000) { first.await() }
        settle()

        assertEquals(1, git.calls.count { it == "fetch" })
    }

    @Test
    fun liftedPress_should_BeRefusedAndNeverQueueAFollowUp() = runBlocking<Unit> {
        val git = OkRepository()
        val svc = service(git)
        val first = svc.startBlockedFirstSync(git)

        val lifted = svc.sync("g", SyncTrigger.Manual, liftMassChangeGuard = true)
        git.fetchGate?.complete(Unit)
        withTimeout(20_000) { first.await() }
        settle()

        assertEquals(DomainError.GitError.SyncInProgress(SyncOrigin.UiPress), lifted.leftOrNull())
        assertEquals(1, git.calls.count { it == "fetch" }, "the lift flag must not ride a rerun")
    }

    @Test
    fun pressAgainstQuiesceHold_should_NeverSetTheRerunBit() = runBlocking<Unit> {
        val git = OkRepository()
        val svc = service(git)
        val hold = checkNotNull(gate.tryAcquire(key(), SyncOrigin.Quiesce))

        val press = svc.sync("g")
        val followUp = gate.releaseOrHandOff(hold)
        settle()

        assertEquals(DomainError.GitError.SyncInProgress(SyncOrigin.Quiesce), press.leftOrNull())
        assertNull(followUp)
        assertEquals(emptyList(), git.calls)
    }

    @Test
    fun heartbeat_should_TouchWhileRunningAndStopBeforeTheRecordEnds() = runBlocking<Unit> {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val git = object : OkRepository() {
            override suspend fun touchOwnedOperation(config: GitConfig) { events += "touch" }
            override suspend fun endOwnedOperation(config: GitConfig) { events += "end" }
        }
        val flight = SyncSingleFlight(
            gate, git, StubConfigRepository(Either.Right(config)), GitSyncBusyCounter(),
            kotlinx.coroutines.flow.MutableStateFlow(null), { 0L }, publishError = {},
            launchFollowUp = { _, _ -> }, scope = scope, heartbeatIntervalMs = 10,
        )

        flight.run(RunSpec("g", SyncOrigin.UiPress, "sync"), fail = { it }, unreadable = { null }) {
            kotlinx.coroutines.delay(300)
            null
        }
        kotlinx.coroutines.delay(100)

        assertTrue(events.count { it == "touch" } >= 3, "heartbeat must beat during the run: $events")
        assertEquals("end", events.last(), "no touch may follow the record end: $events")
        assertEquals(1, events.count { it == "end" })
    }

    @Test
    fun inFlight_should_CarryFollowUpQueuedOnlyForAQueuedUiPress() = runBlocking<Unit> {
        val git = OkRepository()
        val svc = service(git)
        val first = svc.startBlockedFirstSync(git)
        assertEquals(false, svc.inFlight.value?.followUpQueued)

        svc.sync("g", SyncTrigger.Automatic)
        svc.sync("g", SyncTrigger.Manual, liftMassChangeGuard = true)
        assertEquals(false, svc.inFlight.value?.followUpQueued, "a scheduled or lifted loser queues nothing")

        svc.sync("g")
        assertEquals(true, svc.inFlight.value?.followUpQueued)
        assertEquals(SyncOrigin.UiPress, svc.inFlight.value?.origin)

        git.fetchGate?.complete(Unit)
        withTimeout(20_000) { first.await() }
        awaitFetches(git, 2)
        settle()
        assertNull(svc.inFlight.value)
    }

    @Test
    fun isGateHeld_should_FollowTheRepositoryGate_IncludingAForeignHolder() = runBlocking<Unit> {
        val flight = SyncSingleFlight(
            gate, OkRepository(), StubConfigRepository(Either.Right(config)), GitSyncBusyCounter(),
            kotlinx.coroutines.flow.MutableStateFlow(null), { 0L }, publishError = {},
            launchFollowUp = { _, _ -> }, scope = scope,
        )
        assertFalse(flight.isGateHeld())
        flight.resolveGateKey("g")
        assertFalse(flight.isGateHeld())

        val foreign = checkNotNull(gate.tryAcquire(key(), SyncOrigin.Worker))
        assertTrue(flight.isGateHeld(), "a worker's hold must make the periodic driver skip")
        gate.releaseOrHandOff(foreign)
        assertFalse(flight.isGateHeld())
    }

    /** In-memory byte store so the abort's recovery snapshot can be written and verified. */
    private class MemoryFs : dev.stapler.stelekit.git.testsupport.StubFileSystem() {
        private val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        override fun writeFileBytes(path: String, data: ByteArray): Boolean { files[path] = data; return true }
        override fun readFileBytes(path: String): ByteArray? = files[path]
    }

    private class AbortRepository : OkRepository() {
        @Volatile var bytes: ByteArray = "mine".toByteArray()
        override suspend fun editsAtRiskOfReset(config: GitConfig) = listOf("a.md").right()
        override suspend fun readWorkingBytes(config: GitConfig, path: String): ByteArray = bytes
        override suspend fun writeWorkingBytes(config: GitConfig, path: String, bytes: ByteArray): Boolean = true
    }

    private fun abortService(git: GitRepository): GitSyncService =
        buildGitSyncTestService(
            gitRepository = git,
            configRepository = StubConfigRepository(Either.Right(config)),
            fileSystem = MemoryFs(),
            isOnline = { true },
            gate = gate,
        ).also { services += it }

    @Test
    fun confirmDialog_should_NotHoldGate_When_AwaitingUserConfirm() = runBlocking<Unit> {
        val git = AbortRepository()
        val svc = abortService(git)
        val confirmShown = kotlinx.coroutines.CompletableDeferred<Unit>()
        val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()

        val abort = scope.async { svc.abortActiveMerge("g") { confirmShown.complete(Unit); answer.await() } }
        withTimeout(20_000) { confirmShown.await() }

        assertFalse(gate.isHeld(key()), "an open dialog must not hold the gate")
        assertTrue(svc.sync("g").isRight(), "a tick during the dialog must run")

        answer.complete(true)
        assertTrue(withTimeout(20_000) { abort.await() }.isRight())
        assertTrue("abort" in git.calls)
        assertFalse(gate.isHeld(key()))
    }

    @Test
    fun abort_should_RefuseAndResetNothing_When_FilesChangeWhileTheDialogIsOpen() = runBlocking<Unit> {
        val git = AbortRepository()
        val svc = abortService(git)

        val result = svc.abortActiveMerge("g") { git.bytes = "edited later".toByteArray(); true }

        assertTrue(result.isLeft())
        assertFalse("abort" in git.calls, "unconfirmed edits must not be reset")
        assertFalse(gate.isHeld(key()))
    }
}
