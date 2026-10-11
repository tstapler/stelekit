package dev.stapler.stelekit.git

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GitSyncBusyCounterTest {

    @Test
    fun isBusy_should_ReflectFalse_When_NeverBegun() = runTest {
        val counter = GitSyncBusyCounter()
        assertFalse(counter.isBusy.value)
    }

    @Test
    fun isBusy_should_ReflectTrue_When_BeginCalledWithoutMatchingEnd() = runTest {
        val counter = GitSyncBusyCounter()
        counter.begin()
        // isBusy is derived via stateIn() on the counter's own background scope, so it can lag
        // a beat behind begin()/end() — await the value via the flow instead of reading
        // isBusy.value synchronously, which would race the background collector.
        assertTrue(counter.isBusy.first { it })
    }

    @Test
    fun isBusy_should_ReflectFalse_When_EndMatchesEveryBegin() = runTest {
        val counter = GitSyncBusyCounter()
        counter.begin()
        counter.begin()
        assertTrue(counter.isBusy.first { it })
        counter.end()
        counter.end()
        assertFalse(counter.isBusy.first { !it })
    }

    @Test
    fun end_should_NotUnderflowBelowZero_When_CalledWithoutMatchingBegin() = runTest {
        val counter = GitSyncBusyCounter()
        counter.end()
        counter.begin()
        counter.end()
        // A stray end() must not push the counter negative — otherwise a legitimate begin()
        // afterwards would still read as idle.
        assertFalse(counter.isBusy.first { !it })
    }

    @Test
    fun awaitIdle_should_ReturnImmediately_When_CounterAlreadyZero() = runTest {
        val counter = GitSyncBusyCounter()
        counter.awaitIdle() // must not suspend forever
    }

    @Test
    fun awaitIdle_should_SuspendUntilCounterReturnsToZero_When_SyncInFlight() = runTest {
        val counter = GitSyncBusyCounter()
        counter.begin()

        var awaitIdleCompleted = false
        val waiter = launch {
            counter.awaitIdle()
            awaitIdleCompleted = true
        }

        advanceUntilIdle()
        assertFalse(awaitIdleCompleted, "awaitIdle should not complete while sync is in flight")

        counter.end()
        advanceUntilIdle()

        assertTrue(awaitIdleCompleted, "awaitIdle should complete once the counter returns to zero")
        waiter.join()
        assertFalse(counter.isBusy.first { !it })
    }
}
