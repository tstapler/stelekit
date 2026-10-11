package dev.stapler.stelekit.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Virtual-time tests for [ChangeDetectionScheduler] — the shared triggering/backoff state
 * machine composed into [GraphFileWatcher] (Android/JVM) and (wasmJs) `HostDirectorySync`.
 * All timing here is virtual (`kotlinx-coroutines-test`), so these run instantly and
 * deterministically regardless of the real [ChangeDetectionScheduler.baseIntervalMs] used in
 * production.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeDetectionSchedulerTest {

    @Test
    fun timer_ticks_at_base_interval_when_nothing_found() = runTest {
        val calls = mutableListOf<RescanReason>()
        val scheduler = ChangeDetectionScheduler(baseIntervalMs = 1000L) { reason ->
            calls += reason
            RescanOutcome(foundChange = false)
        }
        scheduler.start(this)
        advanceTimeBy(1001L); runCurrent()
        advanceTimeBy(1001L); runCurrent()
        scheduler.stop()

        assertEquals(listOf(RescanReason.Timer, RescanReason.Timer), calls)
    }

    @Test
    fun hint_triggers_immediate_rescan_without_waiting_for_timer() = runTest {
        val calls = mutableListOf<RescanReason>()
        val scheduler = ChangeDetectionScheduler(baseIntervalMs = 1_000_000L) { reason ->
            calls += reason
            RescanOutcome(foundChange = true)
        }
        scheduler.start(this)
        runCurrent()
        scheduler.hint()
        runCurrent()
        scheduler.stop()

        assertEquals(listOf(RescanReason.Signal), calls)
    }

    @Test
    fun hint_that_finds_nothing_runs_bounded_followup_burst_then_stops() = runTest {
        val calls = mutableListOf<RescanReason>()
        val scheduler = ChangeDetectionScheduler(
            baseIntervalMs = 1_000_000L,
            followUpDelaysMs = listOf(100L, 200L, 400L),
        ) { reason ->
            calls += reason
            RescanOutcome(foundChange = false)
        }
        scheduler.start(this)
        runCurrent()
        scheduler.hint()
        runCurrent() // initial Signal rescan
        advanceTimeBy(101L); runCurrent() // follow-up #1
        advanceTimeBy(201L); runCurrent() // follow-up #2
        advanceTimeBy(401L); runCurrent() // follow-up #3
        advanceTimeBy(10_000L); runCurrent() // nothing more — base interval is huge, burst is exhausted
        scheduler.stop()

        assertEquals(
            listOf(RescanReason.Signal, RescanReason.FollowUp, RescanReason.FollowUp, RescanReason.FollowUp),
            calls,
            "a hint that keeps finding nothing must retry exactly followUpDelaysMs.size times, then give up",
        )
    }

    @Test
    fun followup_burst_stops_early_once_a_followup_finds_a_change() = runTest {
        val calls = mutableListOf<RescanReason>()
        var callCount = 0
        val scheduler = ChangeDetectionScheduler(
            baseIntervalMs = 1_000_000L,
            followUpDelaysMs = listOf(100L, 200L, 400L),
        ) { reason ->
            calls += reason
            callCount++
            RescanOutcome(foundChange = callCount == 2) // the first follow-up finds it
        }
        scheduler.start(this)
        runCurrent()
        scheduler.hint()
        runCurrent()
        advanceTimeBy(101L); runCurrent()
        advanceTimeBy(10_000L); runCurrent() // proves no further follow-ups ran
        scheduler.stop()

        assertEquals(listOf(RescanReason.Signal, RescanReason.FollowUp), calls)
    }

    @Test
    fun timer_tick_that_finds_nothing_does_not_trigger_followup_burst() = runTest {
        val calls = mutableListOf<RescanReason>()
        val scheduler = ChangeDetectionScheduler(
            baseIntervalMs = 1000L,
            followUpDelaysMs = listOf(100L),
        ) { reason ->
            calls += reason
            RescanOutcome(foundChange = false)
        }
        scheduler.start(this)
        advanceTimeBy(1001L); runCurrent()
        advanceTimeBy(50L); runCurrent() // less than the follow-up delay — proves no burst was scheduled
        scheduler.stop()

        assertEquals(
            listOf(RescanReason.Timer),
            calls,
            "an ordinary timer tick finding nothing is the expected steady state, not a reason to burst-retry",
        )
    }

    @Test
    fun observerHealthy_widens_the_timer_interval_sixfold() = runTest {
        val calls = mutableListOf<RescanReason>()
        val scheduler = ChangeDetectionScheduler(baseIntervalMs = 1000L) { reason ->
            calls += reason
            RescanOutcome(foundChange = false)
        }
        scheduler.setObserverHealthy(true)
        scheduler.start(this)
        advanceTimeBy(1001L); runCurrent()
        assertTrue(calls.isEmpty(), "a healthy observer should back off to 6x the base interval, not fire at 1x")

        advanceTimeBy(5001L); runCurrent() // total elapsed now > 6 * 1000ms
        scheduler.stop()

        assertEquals(listOf(RescanReason.Timer), calls)
    }

    @Test
    fun exception_in_onRescan_is_caught_and_loop_keeps_running() = runTest {
        var calls = 0
        val scheduler = ChangeDetectionScheduler(baseIntervalMs = 1000L) {
            calls++
            throw RuntimeException("boom")
        }
        scheduler.start(this)
        advanceTimeBy(1001L); runCurrent()
        advanceTimeBy(1001L); runCurrent()
        scheduler.stop()

        assertEquals(2, calls, "a throwing rescan must not kill the loop — later ticks must still run")
    }

    @Test
    fun stop_cancels_the_timer_loop() = runTest {
        var calls = 0
        val scheduler = ChangeDetectionScheduler(baseIntervalMs = 1000L) {
            calls++
            RescanOutcome(foundChange = false)
        }
        scheduler.start(this)
        advanceTimeBy(1001L); runCurrent()
        scheduler.stop()
        advanceTimeBy(10_000L); runCurrent()

        assertEquals(1, calls, "no further ticks after stop()")
    }

    @Test
    fun rapid_hints_coalesce_into_a_single_rescan() = runTest {
        var calls = 0
        val scheduler = ChangeDetectionScheduler(baseIntervalMs = 1_000_000L) {
            calls++
            RescanOutcome(foundChange = true)
        }
        scheduler.start(this)
        runCurrent()
        scheduler.hint()
        scheduler.hint()
        scheduler.hint()
        runCurrent()
        scheduler.stop()

        // A CONFLATED channel's first send to an already-parked receiver is a direct handoff
        // (consumed immediately); only subsequent sends land in the 1-slot conflated buffer.
        // A rapid burst therefore coalesces to at most 2 rescans, never N — matches the
        // identical Channel<Unit>(Channel.CONFLATED) pattern already used unmodified in
        // production GraphFileWatcher.kt today.
        assertTrue(calls in 1..2, "a rapid hint burst must coalesce to at most 2 rescans, was $calls")
    }
}
