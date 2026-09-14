// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitSyncBusyCounter
import dev.stapler.stelekit.git.GitWriteBackQueue
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        flush = flush,
    )

    private fun newStrategy(
        busyCounter: GitSyncBusyCounter,
        target: ShadowWorktreeQuiesceTarget?,
    ): AndroidGraphMoveQuiesceStrategy = AndroidGraphMoveQuiesceStrategy(
        gitSyncBusyCounter = busyCounter,
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
        // on GraphRelocationCoordinator's outer 30s QUIESCE_TIMEOUT_MS to stop it).
        assertTrue(flush.calls in 1..10, "expected a small bounded number of retry attempts, got ${flush.calls}")
        assertFalse(queue.isEmpty(), "the persistently-failing path is never dequeued by design")
        assertIs<Either.Left<DomainError.StorageError>>(result)
        assertIs<DomainError.StorageError.SourceInFlight>(result.value)

        strategy.release(op)
    }
}
