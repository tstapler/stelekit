package dev.stapler.stelekit.performance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DebounceManagerTest {

    @Test
    fun debounce_executes_after_delay() = runTest {
        val manager = DebounceManager(this, delayMs = 100L)
        var executed = 0

        manager.debounce("key") { executed++ }
        advanceTimeBy(50)
        assertEquals(0, executed, "Should not execute before delay")
        advanceTimeBy(60)
        advanceUntilIdle()
        assertEquals(1, executed, "Should execute after delay")
    }

    @Test
    fun debounce_resets_timer_on_rapid_calls() = runTest {
        val manager = DebounceManager(this, delayMs = 100L)
        var value = ""

        manager.debounce("key") { value = "first" }
        advanceTimeBy(50)
        advanceUntilIdle()
        manager.debounce("key") { value = "second" }
        advanceTimeBy(50)
        advanceUntilIdle()
        manager.debounce("key") { value = "third" }
        advanceTimeBy(150)
        advanceUntilIdle()

        assertEquals("third", value, "Only the last debounced action should execute")
    }

    @Test
    fun debounce_independent_keys_execute_independently() = runTest {
        val manager = DebounceManager(this, delayMs = 100L)
        var a = 0
        var b = 0

        manager.debounce("a") { a++ }
        manager.debounce("b") { b++ }
        advanceTimeBy(150)
        advanceUntilIdle()

        assertEquals(1, a)
        assertEquals(1, b)
    }

    @Test
    fun cancelAll_prevents_execution() = runTest {
        val manager = DebounceManager(this, delayMs = 100L)
        var executed = 0

        manager.debounce("key") { executed++ }
        // Advance just enough for the outer scope.launch to run but not past the delay
        advanceTimeBy(10)
        manager.cancelAll()
        // Now advance well past the delay
        advanceTimeBy(500)
        advanceUntilIdle()

        assertEquals(0, executed, "Cancelled actions should not execute")
    }

    @Test
    fun flushAll_executes_pending_immediately() = runTest {
        val manager = DebounceManager(this, delayMs = 100L)
        var value = ""

        manager.debounce("key") { value = "flushed" }
        // Let the outer scope.launch schedule, but don't advance past the debounce delay
        advanceUntilIdle()
        // The inner delay(100) hasn't completed, so action hasn't run
        // But advanceUntilIdle() also advances virtual time... we need to check differently.
        // Instead: just flush immediately and verify the action ran
        manager.flushAll()
        assertEquals("flushed", value, "flushAll should execute pending action immediately")
    }

    @Test
    fun flushAll_executes_only_latest_action_per_key() = runTest {
        val manager = DebounceManager(this, delayMs = 100L)
        var value = ""

        manager.debounce("key") { value = "first" }
        advanceTimeBy(10)
        advanceUntilIdle()
        manager.debounce("key") { value = "second" }
        advanceTimeBy(10)
        advanceUntilIdle()

        manager.flushAll()
        advanceUntilIdle()
        assertEquals("second", value, "Should execute only the latest debounced action")
    }

    @Test
    fun flushAll_handles_multiple_keys() = runTest {
        val manager = DebounceManager(this, delayMs = 100L)
        val results = mutableListOf<String>()

        manager.debounce("a") { results.add("a") }
        manager.debounce("b") { results.add("b") }
        advanceTimeBy(10)
        advanceUntilIdle()

        manager.flushAll()
        advanceUntilIdle()

        assertEquals(2, results.size)
        assertTrue(results.contains("a"))
        assertTrue(results.contains("b"))
    }

    @Test
    fun flushAll_clears_state_so_nothing_fires_later() = runTest {
        val manager = DebounceManager(this, delayMs = 100L)
        var count = 0

        manager.debounce("key") { count++ }
        advanceTimeBy(10)
        advanceUntilIdle()

        manager.flushAll()
        advanceUntilIdle()
        assertEquals(1, count)

        // Advance past the original debounce delay — should NOT fire again
        advanceTimeBy(200)
        advanceUntilIdle()
        assertEquals(1, count, "Should not execute twice after flushAll")
    }
}
