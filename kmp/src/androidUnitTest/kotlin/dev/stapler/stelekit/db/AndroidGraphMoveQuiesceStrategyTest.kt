// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitSyncBusyCounter
import dev.stapler.stelekit.git.GitWriteBackQueue
import dev.stapler.stelekit.git.RepoOpGate
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.platform.GitWorktreeLocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task 3.1.3e: [AndroidGraphMoveQuiesceStrategy] composes [GitSyncBusyCounter] and a
 * [GitWriteBackQueue] drain — it must not return from [AndroidGraphMoveQuiesceStrategy.quiesce]
 * until the busy counter is idle and the write-back queue is empty. Modeled on
 * `GitSyncBusyCounterTest`'s launch+`advanceUntilIdle` style for asserting "does not complete yet."
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidGraphMoveQuiesceStrategyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newQueue(): GitWriteBackQueue =
        GitWriteBackQueue(tempFolder.newFile("queue-${System.nanoTime()}.txt"))

    private fun newTarget(
        queue: GitWriteBackQueue,
        flush: suspend () -> List<Either<DomainError.GitError, Unit>>,
    ): ShadowWorktreeQuiesceTarget = ShadowWorktreeQuiesceTarget(
        shadowKey = "shadow-key-${System.nanoTime()}",
        queue = queue,
        flushWhileLocked = flush,
    )

    private fun newStrategy(
        busyCounter: GitSyncBusyCounter,
        target: ShadowWorktreeQuiesceTarget?,
    ): AndroidGraphMoveQuiesceStrategy = AndroidGraphMoveQuiesceStrategy(
        gitSyncBusyCounter = busyCounter,
        gate = RepoOpGate(),
        gateKeyFor = { null },
        shadowWorktreeTarget = { target },
    )

    private val op: StorageMoveOperation = StorageMoveOperation.Relocate(
        graphId = "g1",
        source = StorageLocation.SafFolder("g1", "content://tree/x"),
        destination = StorageLocation.AppOwned("g1"),
        deleteSourceAfterVerify = true,
    )

    private class RecordingFlush(private val queue: GitWriteBackQueue) {
        var calls = 0
            private set

        suspend fun invoke(): List<Nothing> {
            calls++
            queue.dequeue("pages/foo.md")
            return emptyList()
        }
    }

    @Test
    fun release_should_ResumeBackgroundSync_When_TheCallerIsAlreadyCancelled() = runTest {
        var resumed = false
        val strategy = AndroidGraphMoveQuiesceStrategy(
            gitSyncBusyCounter = GitSyncBusyCounter(),
            gate = RepoOpGate(),
            gateKeyFor = { null },
            shadowWorktreeTarget = { null },
            resumeBackgroundSync = { resumed = true },
        )

        launch {
            cancel()
            strategy.release(op)
        }.join()

        assertTrue(resumed, "a cancelled caller must still resume background sync")
    }

    @Test
    fun quiesce_should_AwaitBusyCounterIdleAndDrainWriteBackQueue_When_GitWriteBackQueueHasInFlightEntry() = runTest {
        val queue = newQueue()
        queue.enqueue("pages/foo.md")
        assertFalse(queue.isEmpty())

        val busyCounter = GitSyncBusyCounter()
        busyCounter.begin()

        val flush = RecordingFlush(queue)
        val target = newTarget(queue) { flush.invoke() }
        val strategy = newStrategy(busyCounter, target)

        var quiesceCompleted = false
        val job = launch {
            strategy.quiesce(op)
            quiesceCompleted = true
        }

        advanceUntilIdle()
        assertFalse(quiesceCompleted, "quiesce must not complete while the busy counter is still non-zero")
        assertEquals(0, flush.calls, "the write-back queue must not be drained before the busy counter is idle")

        busyCounter.end()
        advanceUntilIdle()
        job.join()

        assertTrue(quiesceCompleted, "quiesce should complete once the busy counter is idle and the queue is drained")
        assertTrue(flush.calls >= 1, "the write-back queue must be drained via flush() before quiesce returns")
        assertTrue(queue.isEmpty(), "the write-back queue must be empty by the time quiesce returns")

        strategy.release(op)
    }

    @Test
    fun quiesce_should_ReturnImmediately_When_OperationDoesNotTouchAGitShadowWorktree() = runTest {
        val busyCounter = GitSyncBusyCounter()
        val strategy = AndroidGraphMoveQuiesceStrategy(
            gitSyncBusyCounter = busyCounter,
            gate = RepoOpGate(),
            gateKeyFor = { null },
            shadowWorktreeTarget = { null },
        )

        strategy.quiesce(op) // must not suspend forever
        strategy.release(op) // must not throw when nothing was locked
    }

    /** Always fails every queued path with the given [DomainError.GitError] factory, without dequeuing. */
    private class FailingFlush(
        private val queue: GitWriteBackQueue,
        private val error: (path: String) -> DomainError.GitError,
    ) {
        var calls = 0
            private set

        suspend fun invoke(): List<Either<DomainError.GitError, Unit>> {
            calls++
            return queue.getAll().map { error(it).left() }
        }
    }

    @Test
    fun quiesce_should_ReturnLeftPromptly_When_FlushReportsWorkingTreeConcurrentEditDetected() = runTest {
        val queue = newQueue()
        queue.enqueue("pages/foo.md")

        val busyCounter = GitSyncBusyCounter()
        val flush = FailingFlush(queue) { path ->
            DomainError.GitError.WorkingTreeConcurrentEditDetected(path)
        }
        val target = newTarget(queue) { flush.invoke() }
        val strategy = newStrategy(busyCounter, target)

        val result = strategy.quiesce(op)

        assertEquals(1, flush.calls, "a concurrent-edit failure must surface after a single flush, not a busy-loop")
        assertIs<Either.Left<DomainError.StorageError>>(result)
        assertIs<DomainError.StorageError.SourceInFlight>(result.value)

        strategy.release(op)
    }

    @Test
    fun quiesce_should_BoundRetriesAndReturnLeft_When_FlushPersistentlyReportsWorkingTreeWriteBackFailed() = runTest {
        val queue = newQueue()
        queue.enqueue("pages/foo.md")

        val busyCounter = GitSyncBusyCounter()
        val flush = FailingFlush(queue) { path ->
            DomainError.GitError.WorkingTreeWriteBackFailed(path, "SAF write failed for $path")
        }
        val target = newTarget(queue) { flush.invoke() }
        val strategy = newStrategy(busyCounter, target)

        val result = strategy.quiesce(op)

        // Bounded: a persistently-retryable failure must not spin unboundedly (this is the
        // regression this test guards — previously the drain loop retried forever, relying only
        // on GraphRelocationCoordinator's outer 30s QUIESCE_TIMEOUT_MS to stop it). Exact count,
        // not just an upper bound (an in..10 range would still pass if a regression dropped the
        // loop to e.g. 1 retry) — must match MAX_WRITE_BACK_FLUSH_ATTEMPTS in
        // GraphMoveQuiesceStrategy.android.kt (private, so not directly referenceable here).
        assertEquals(10, flush.calls, "expected exactly MAX_WRITE_BACK_FLUSH_ATTEMPTS retry attempts")
        assertFalse(queue.isEmpty(), "the persistently-failing path is never dequeued by design")
        assertIs<Either.Left<DomainError.StorageError>>(result)
        assertIs<DomainError.StorageError.SourceInFlight>(result.value)

        strategy.release(op)
    }

    /**
     * Fails every attempt until [succeedOnAttempt], which dequeues and succeeds — covers the case
     * the always-succeed and always-fail-forever tests above don't: that a mid-loop recovery
     * actually drains the queue and returns success, not just that the loop eventually gives up.
     */
    private class EventuallySucceedingFlush(
        private val queue: GitWriteBackQueue,
        private val succeedOnAttempt: Int,
        private val error: (path: String) -> DomainError.GitError,
    ) {
        var calls = 0
            private set

        suspend fun invoke(): List<Either<DomainError.GitError, Unit>> {
            calls++
            if (calls < succeedOnAttempt) {
                return queue.getAll().map { error(it).left() }
            }
            queue.dequeue("pages/foo.md")
            return emptyList()
        }
    }

    @Test
    fun quiesce_should_SucceedAfterRetrying_When_FlushFailsThenSucceedsOnItsLastAllowedAttempt() = runTest {
        val queue = newQueue()
        queue.enqueue("pages/foo.md")

        val busyCounter = GitSyncBusyCounter()
        // Fails on attempts 1-9, succeeds on attempt 10 — the last attempt the bounded loop
        // allows — proving the loop recovers correctly rather than only ever hitting the bound.
        val flush = EventuallySucceedingFlush(queue, succeedOnAttempt = 10) { path ->
            DomainError.GitError.WorkingTreeWriteBackFailed(path, "transient SAF write failure for $path")
        }
        val target = newTarget(queue) { flush.invoke() }
        val strategy = newStrategy(busyCounter, target)

        val result = strategy.quiesce(op)

        assertEquals(10, flush.calls, "flush should retry until it succeeds, not stop early or spin past success")
        assertTrue(queue.isEmpty(), "a successful flush must drain the queue")
        assertIs<Either.Right<Unit>>(result)

        strategy.release(op)
    }

    // ── Task 1.3c: gate first, then the worktree lock ───────────────────────────────────────────

    private val gateKey = "repo-key-1"

    private fun gatedStrategy(
        gate: RepoOpGate,
        target: ShadowWorktreeQuiesceTarget?,
        busyCounter: GitSyncBusyCounter = GitSyncBusyCounter(),
    ) = AndroidGraphMoveQuiesceStrategy(
        gitSyncBusyCounter = busyCounter,
        gate = gate,
        gateKeyFor = { gateKey },
        shadowWorktreeTarget = { target },
    )

    private suspend fun awaitCondition(what: String, condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) yield() } // real-clock hang guard: awaits real threads
    }

    // runBlocking: quiesce hops to Dispatchers.IO and blocks on real locks; virtual time cannot order it
    @Test
    fun quiesce_should_WaitForGateWithoutHoldingWorktreeLock_AndCompleteAfterSync() = runBlocking {
        val gate = RepoOpGate()
        val queue = newQueue()
        val target = newTarget(queue) { emptyList() }
        val worktreeLock = GitWorktreeLocks.lockFor(target.shadowKey)
        val syncLease = checkNotNull(gate.tryAcquire(gateKey, SyncOrigin.UiPress))
        val strategy = gatedStrategy(gate, target)

        var done = false
        val job = launch(Dispatchers.Default) { strategy.quiesce(op); done = true }

        // The fake sync needs the worktree lock inside fetch; it must be takeable while quiesce waits.
        repeat(50) { yield() }
        assertFalse(done, "quiesce must wait for the running sync")
        assertTrue(worktreeLock.tryLock(), "quiesce must not hold the worktree lock while waiting for the gate")
        worktreeLock.unlock()

        gate.releaseOrHandOff(syncLease)
        withTimeout(5_000) { job.join() } // real-clock hang guard: awaits real threads
        assertTrue(done)
        assertEquals(SyncOrigin.Quiesce, gate.heldBy(gateKey))
        assertTrue(worktreeLock.isLocked)

        strategy.release(op)
        assertFalse(gate.isHeld(gateKey), "release must free the gate lease")
        assertFalse(worktreeLock.isLocked, "release must free the worktree lock")
    }

    @Test
    fun quiesce_should_CoalesceTriggers_AndNeverSetRerun_While_QuiesceLeaseHeld() = runTest {
        val gate = RepoOpGate()
        val strategy = gatedStrategy(gate, newTarget(newQueue()) { emptyList() })

        strategy.quiesce(op)

        assertEquals(null, gate.tryAcquire(gateKey, SyncOrigin.UiPress), "a trigger must coalesce")
        assertFalse(gate.requestRerun(gateKey), "a press must not set the rerun bit against a Quiesce hold")
        assertEquals(SyncOrigin.Quiesce, gate.heldBy(gateKey))

        strategy.release(op)
        assertEquals(null, gate.releaseOrHandOff(checkNotNull(gate.tryAcquire(gateKey, SyncOrigin.UiPress))), "no follow-up fires after release")
    }

    // runBlocking: quiesce hops to Dispatchers.IO and blocks on real locks; virtual time cannot order it
    @Test
    fun quiesce_should_ReleaseLease_When_CancelledWhileWaitingForWorktreeMutex() = runBlocking {
        val gate = RepoOpGate()
        val target = newTarget(newQueue()) { emptyList() }
        val worktreeLock = GitWorktreeLocks.lockFor(target.shadowKey)
        worktreeLock.lock()
        val strategy = gatedStrategy(gate, target)

        val job = launch(Dispatchers.Default) { strategy.quiesce(op) }
        withTimeout(5_000) { awaitCondition("lease taken") { gate.isHeld(gateKey) } } // real-clock hang guard: awaits real threads

        job.cancel(CancellationException("timeout"))
        withTimeout(5_000) { job.join() } // real-clock hang guard: awaits real threads
        assertFalse(gate.isHeld(gateKey), "the lease must be released when the mutex wait is cancelled")
        worktreeLock.unlock()

        val fresh = gate.tryAcquire(gateKey, SyncOrigin.UiPress)
        assertTrue(fresh != null, "a fresh sync must acquire after the cancelled quiesce")
    }

    @Test
    fun quiesce_should_ReleaseBothOnRelease_When_DrainReturnsError() = runTest {
        val gate = RepoOpGate()
        val queue = newQueue().also { it.enqueue("pages/foo.md") }
        val flush = FailingFlush(queue) { DomainError.GitError.WorkingTreeConcurrentEditDetected(it) }
        val target = newTarget(queue) { flush.invoke() }
        val strategy = gatedStrategy(gate, target)

        val result = strategy.quiesce(op)
        assertIs<Either.Left<DomainError.StorageError>>(result)
        strategy.release(op)

        assertFalse(gate.isHeld(gateKey))
        assertFalse(GitWorktreeLocks.lockFor(target.shadowKey).isLocked)
    }

    @Test
    fun quiesce_should_UseNoGate_When_GateKeyUnresolvable() = runTest {
        val gate = RepoOpGate()
        val strategy = AndroidGraphMoveQuiesceStrategy(
            gitSyncBusyCounter = GitSyncBusyCounter(),
            gate = gate,
            gateKeyFor = { null },
            shadowWorktreeTarget = { null },
        )
        strategy.quiesce(op)
        assertFalse(gate.isHeld(gateKey))
        strategy.release(op)
    }
}
