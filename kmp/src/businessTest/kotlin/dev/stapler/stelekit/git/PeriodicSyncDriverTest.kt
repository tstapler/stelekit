// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogLevel
import dev.stapler.stelekit.logging.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Driver behaviors (UT-17, UT-18, UT-33, UT-34, UT-35, UT-94, UT-123, UT-145), virtual time only. */
@OptIn(ExperimentalCoroutinesApi::class)
class PeriodicSyncDriverTest {

    private val interval = 15
    private val intervalMs = interval * 60_000L
    private var wallClock = 1_000_000L
    private var driverScope: CoroutineScope? = null

    @AfterTest
    fun tearDown() {
        driverScope?.cancel()
    }

    /**
     * runTest drains the shared scheduler when the body ends, and an interval loop never goes idle,
     * so the driver scope must be cancelled inside the body or virtual time runs forever.
     */
    private fun driverTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            driverScope?.cancel()
        }
    }

    private fun TestScope.driver(
        label: String,
        busy: () -> Boolean = { false },
        tick: suspend () -> TickOutcome,
    ): PeriodicSyncDriver {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        driverScope = scope
        return PeriodicSyncDriver(tick = tick, scope = scope, clock = { wallClock }, isBusy = busy, label = unique(label))
    }

    /** LogManager is process-global and this class also runs inside AllBusinessTests, so labels carry a per-instance id. */
    private val instanceId = System.nanoTime()
    private fun unique(label: String) = "$label-$instanceId"

    private fun logsFor(label: String): List<LogEntry> =
        LogManager.logs.value.filter { it.tag == "PeriodicSyncDriver" && it.message.contains("graph=${unique(label)} ") }

    @Test
    fun `driver should run an immediate first tick then every interval when started with immediateFirstTick`() = driverTest {
        var ticks = 0
        val d = driver("ut17") { ticks++; TickOutcome.Success }
        d.start(interval, immediateFirstTick = true)
        runCurrent()
        assertEquals(1, ticks)
        advanceTimeBy(intervalMs)
        runCurrent()
        assertEquals(2, ticks)
        advanceTimeBy(intervalMs)
        runCurrent()
        assertEquals(3, ticks)
    }

    @Test
    fun `driver should wait a full interval for the first tick when not immediate`() = driverTest {
        var ticks = 0
        val d = driver("late") { ticks++; TickOutcome.Success }
        d.start(interval)
        advanceTimeBy(intervalMs - 1)
        runCurrent()
        assertEquals(0, ticks)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, ticks)
    }

    @Test
    fun `outcomes should be sealed types and status should be a StateFlow of ScheduledSyncStatus`() = driverTest {
        val d = driver("ut123") { TickOutcome.Success }
        assertEquals(ScheduledSyncStatus(), d.status.value)
        val outcomes: List<TickOutcome> = listOf(
            TickOutcome.Success, TickOutcome.Failure("x"), TickOutcome.Skip(SkipReason.Busy), TickOutcome.Skip(SkipReason.Offline),
        )
        val names = outcomes.map { o ->
            when (o) {
                is TickOutcome.Success -> "success"
                is TickOutcome.Failure -> "failure"
                is TickOutcome.Skip -> when (o.reason) {
                    SkipReason.Busy -> "busy"
                    SkipReason.Offline -> "offline"
                }
            }
        }
        assertEquals(listOf("success", "failure", "busy", "offline"), names)
    }

    @Test
    fun `driver should log a redacted warning and run the next tick when a tick throws`() = driverTest {
        var calls = 0
        val d = driver("ut18") {
            calls++
            if (calls == 1) throw IllegalStateException("boom https://user:ghp_secret@host/repo.git")
            TickOutcome.Success
        }
        d.start(interval)
        advanceTimeBy(intervalMs)
        runCurrent()
        assertEquals(1, calls)
        val warn = logsFor("ut18").single { it.level == LogLevel.WARN }
        assertTrue(warn.message.contains("IllegalStateException"))
        assertFalse(warn.message.contains("ghp_secret"), "secret must be redacted: ${warn.message}")
        advanceTimeBy(intervalMs)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(0, d.status.value.consecutiveFailures)
    }

    @Test
    fun `driver should propagate cancellation and stop ticking when a tick is cancelled`() = driverTest {
        var calls = 0
        val d = driver("cancel") {
            calls++
            if (calls == 2) throw CancellationException("cancelled")
            TickOutcome.Success
        }
        d.start(interval)
        repeat(4) {
            advanceTimeBy(intervalMs)
            runCurrent()
        }
        assertEquals(2, calls, "a CancellationException ends the loop; it is never swallowed as a failure")
        assertEquals(0, d.status.value.consecutiveFailures)
    }

    @Test
    fun `driver should skip with Busy without calling the tick when a manual sync is running`() = driverTest {
        var calls = 0
        var busy = true
        val d = driver("ut34", busy = { busy }) { calls++; TickOutcome.Success }
        d.start(interval)
        advanceTimeBy(intervalMs)
        runCurrent()
        assertEquals(0, calls)
        assertEquals(SkipReason.Busy, d.status.value.lastSkip)
        assertTrue(logsFor("ut34").any { it.level == LogLevel.INFO && it.message.contains("outcome=skip(busy)") })
        busy = false
        advanceTimeBy(intervalMs)
        runCurrent()
        assertEquals(1, calls, "the skipped tick is dropped, not queued")
        assertNull(d.status.value.lastSkip)
    }

    @Test
    fun `driver should report wall-clock last success and next time when a tick succeeds`() = driverTest {
        val d = driver("ut35") { TickOutcome.Success }
        d.start(interval)
        wallClock = 5_000_000L
        advanceTimeBy(intervalMs)
        runCurrent()
        val s = d.status.value
        assertEquals(5_000_000L, s.lastSuccessAtMs)
        assertEquals(5_000_000L, s.lastAttemptAtMs)
        assertEquals(5_000_000L + intervalMs, s.nextAtMs)
    }

    @Test
    fun `driver should pause and expose paused status when three consecutive ticks fail`() = driverTest {
        var calls = 0
        val d = driver("ut33") { calls++; TickOutcome.Failure("fetch failed") }
        d.start(interval)
        repeat(5) {
            advanceTimeBy(intervalMs)
            runCurrent()
        }
        assertEquals(3, calls, "the loop stops after the third failure")
        val s = d.status.value
        assertTrue(s.paused)
        assertFalse(s.running)
        assertEquals(3, s.consecutiveFailures)
        assertEquals("fetch failed", s.lastFailure)
        assertNull(s.nextAtMs)
        assertEquals(1, logsFor("ut33").count { it.message.contains("sync.paused") && it.level == LogLevel.WARN })
    }

    @Test
    fun `resume should clear the pause and restart the loop when its one tick succeeds`() = driverTest {
        var fail = true
        var calls = 0
        val d = driver("ut145a") { calls++; if (fail) TickOutcome.Failure("down") else TickOutcome.Success }
        d.start(interval)
        repeat(3) { advanceTimeBy(intervalMs); runCurrent() }
        assertTrue(d.status.value.paused)
        fail = false
        d.resume()
        assertEquals(4, calls, "resume runs exactly one tick")
        assertFalse(d.status.value.paused)
        assertEquals(0, d.status.value.consecutiveFailures)
        runCurrent()
        advanceTimeBy(intervalMs)
        runCurrent()
        assertEquals(5, calls, "the loop is running again")
    }

    @Test
    fun `resume should stay paused with the new cause when its one tick fails`() = driverTest {
        var cause = "first"
        val d = driver("ut145b") { TickOutcome.Failure(cause) }
        d.start(interval)
        repeat(3) { advanceTimeBy(intervalMs); runCurrent() }
        cause = "second"
        d.resume()
        assertTrue(d.status.value.paused)
        assertEquals("second", d.status.value.lastFailure)
    }

    @Test
    fun `driver should log one SyncTick line per tick with graph decision outcome and last success`() = driverTest {
        val d = driver("ut94") { TickOutcome.Success }
        d.start(interval)
        repeat(2) { advanceTimeBy(intervalMs); runCurrent() }
        val lines = logsFor("ut94").filter { it.message.startsWith("SyncTick") }
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.message.contains("decision=fetch") && it.message.contains("outcome=success") })
        assertTrue(lines.last().message.contains("lastSuccess=$wallClock"))
    }

    @Test
    fun `stop should end the loop and leave no further ticks`() = driverTest {
        var calls = 0
        val d = driver("stop") { calls++; TickOutcome.Success }
        d.start(interval)
        advanceTimeBy(intervalMs); runCurrent()
        d.stop()
        advanceTimeBy(intervalMs * 3); runCurrent()
        assertEquals(1, calls)
        assertFalse(d.status.value.running)
    }
}
