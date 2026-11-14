// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.git.GitSyncWorkerBinding
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Two adapter instances (app root + a feature's own) must not interleave their check-then-act sequences (#653). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WorkManagerTaskSchedulerConcurrencyTest {
    private lateinit var h: WorkManagerTestHarness
    private lateinit var registry: TaskRegistry
    private lateinit var store: TaskSettingsStore
    private val g1 = TaskKey(TaskKind("git-sync"), TaskScope("g1"))
    private val name1 = "stelekit_git_sync_g1"

    /** Parks the first write whose key contains [armedFor] until [release]; the caller's thread blocks inside the ledger. */
    private class ParkingSettings(private val delegate: Settings, private val armedFor: String) : Settings by delegate {
        val armed = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        override fun putString(key: String, value: String) {
            delegate.putString(key, value)
            if (key.contains(armedFor) && armed.compareAndSet(true, false)) {
                entered.complete(Unit)
                release.await(30, TimeUnit.SECONDS)
            }
        }
    }

    @Before
    fun setUp() {
        h = WorkManagerTestHarness()
        WorkManagerTaskScheduler.clearProcessStatic()
        AppliedConfigLedger.clearProcessStatic()
        registry = TaskRegistry()
        val c = TaskConfig(true, Schedule.of(15, 0), TaskConstraints())
        registry.register(
            TaskKind("git-sync"),
            TaskSpec(JobKind.GitSync, "Git sync", TaskShape.Periodic(c.schedule), c, false, 0),
        ) { _, _ -> TaskRunResult.Success }
        store = TaskSettingsStore(ContractSettings(), LegacyConfigSeed.None, registry)
    }

    private fun scheduler(settings: Settings) = WorkManagerTaskScheduler(
        h.context, registry, store, AppliedConfigLedger(settings),
        workers = GitSyncWorkerBinding.resolver, nowMs = FixedClock()::currentTimeMillis,
    )

    private fun cfg(interval: Int) = TaskConfig(true, Schedule.of(interval, 0), TaskConstraints())

    @Test
    fun apply_should_leaveLedgerAndLiveWorkInAgreement_When_TwoInstancesApplyConcurrently() = runBlocking<Unit> {
        val shared = ContractSettings()
        val parking = ParkingSettings(shared, "applied")
        val a = scheduler(parking)
        val b = scheduler(shared)

        parking.armed.set(true)
        val first = async(Dispatchers.Default) { a.apply(g1, cfg(45)) } // parks after recordApplied(45), before enqueue
        parking.entered.await()
        val second = async(Dispatchers.Default) { b.apply(g1, cfg(90)) }

        // Unlocked, `second` completes while `first` is parked; locked, it is queued behind it. A bounded negative wait.
        val overtook = withTimeoutOrNull(1_500) { second.await() } != null
        parking.release.countDown()
        first.await()
        second.await()

        val live = assertNotNull(h.active(name1))
        val liveMinutes = TimeUnit.MILLISECONDS.toMinutes(live.periodicityInfo!!.repeatIntervalMillis)
        val recorded = assertNotNull(AppliedConfigLedger(shared).lastApplied(g1)).schedule.intervalMinutes
        assertEquals(false, overtook, "second instance ran its whole sequence inside the first one's critical section")
        assertEquals(90L, liveMinutes)
        assertEquals(90, recorded)
        assertNull(AppliedConfigLedger(shared).pausedSnapshot(g1))
    }
}
