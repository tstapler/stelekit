// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.jobs.JobHandle
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.JobTracker
import dev.stapler.stelekit.jobs.NoJobTracker
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Runs the adapter contract against the in-process scheduler on a virtual dispatcher that never advances. */
class InProcessTaskSchedulerContractTest : BackgroundTaskSchedulerContract() {
    private val created = mutableListOf<InProcessTaskScheduler>()

    @AfterTest
    fun stopSchedulers() = created.forEach { it.stop() }

    override fun createScheduler(ledgerSettings: Settings, store: TaskSettingsStore, registry: TaskRegistry): BackgroundTaskScheduler =
        InProcessTaskScheduler(
            registry = registry,
            store = store,
            ledger = AppliedConfigLedger(ledgerSettings),
            runLog = SettingsTaskRunLog(ContractSettings()),
            clock = { 0L },
            dispatcher = StandardTestDispatcher(),
        ).also { created += it }

    override suspend fun seedExternalPeriodic(scheduler: BackgroundTaskScheduler, key: TaskKey, config: TaskConfig) =
        (scheduler as InProcessTaskScheduler).seedExternalPeriodic(key, config)

    override suspend fun pendingOneShot(scheduler: BackgroundTaskScheduler, key: TaskKey): PendingOneShot? =
        (scheduler as InProcessTaskScheduler).pendingOneShot(key)?.let { (id, network) -> PendingOneShot(id, network) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class InProcessTaskSchedulerTest {
    private val key = TaskKey(TaskKind("model-download"), TaskScope(""))
    private val minute = 60_000L

    private class Harness(scope: TestScope, val key: TaskKey, val task: BackgroundTask, state: () -> ConstraintState, seed: Int = 42, jobTracker: JobTracker = NoJobTracker, appendOverride: (suspend (TaskRunRecord) -> Unit)? = null) {
        val runLog = SettingsTaskRunLog(ContractSettings())
        private val schedulerLog = object : TaskRunLog {
            override suspend fun append(record: TaskRunRecord) { appendOverride?.invoke(record); runLog.append(record) }
            override suspend fun recent(key: TaskKey) = runLog.recent(key)
            override suspend fun lastSuccessAtMs(key: TaskKey) = runLog.lastSuccessAtMs(key)
        }
        val registry = TaskRegistry().apply {
            val config = TaskConfig(true, Schedule.of(15, 5), TaskConstraints())
            register(key.kind, TaskSpec(JobKind.ModelDownload, "Fake", TaskShape.Periodic(config.schedule), config, false, 0), task)
        }
        val scheduler = InProcessTaskScheduler(
            registry = registry,
            store = TaskSettingsStore(ContractSettings(), LegacyConfigSeed.None, registry),
            ledger = AppliedConfigLedger(ContractSettings()),
            runLog = schedulerLog,
            jobTracker = jobTracker,
            clock = { scope.currentTime },
            random = Random(seed),
            constraintState = state,
            dispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }

    private val online = { ConstraintState(online = true, isMetered = false, charging = null) }

    @Test
    fun tick_should_runWithJitteredGapsInWindow_When_ThreeIntervalsElapse() = runTest {
        val times = mutableListOf<Long>()
        val h = Harness(this, key, { _, _ -> times += currentTime; TaskRunResult.Success }, online)
        h.scheduler.apply(key, TaskConfig(true, Schedule.of(15, 5), TaskConstraints()))
        advanceTimeBy(60 * minute)
        runCurrent()
        h.scheduler.stop()
        assertTrue(times.size in 3..4, "ran ${times.size} times")
        val gaps = (listOf(0L) + times).zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it in 15 * minute..20 * minute }, "gaps $gaps")
    }

    @Test
    fun tick_should_skipWithoutRunningBody_When_UnmeteredAndMeteringUnknown() = runTest {
        var runs = 0
        val h = Harness(this, key, { _, _ -> runs++; TaskRunResult.Success }, { ConstraintState(true, null, null) })
        h.scheduler.apply(key, TaskConfig(true, Schedule.of(15, 0), TaskConstraints(network = NetworkPolicy.Unmetered)))
        advanceTimeBy(15 * minute)
        runCurrent()
        h.scheduler.stop()
        assertEquals(0, runs)
        assertEquals(TaskRunResult.Skipped("unknown-network"), h.runLog.recent(key).single().result)
    }

    @Test
    fun tick_should_logFailureAndKeepLooping_When_TaskThrowsOutOfMemoryOnce() = runTest {
        var calls = 0
        val h = Harness(this, key, { _, _ -> if (calls++ == 0) throw OutOfMemoryError("boom") else TaskRunResult.Success }, online)
        h.scheduler.apply(key, TaskConfig(true, Schedule.of(15, 0), TaskConstraints()))
        advanceTimeBy(30 * minute)
        runCurrent()
        h.scheduler.stop()
        val results = h.runLog.recent(key).map { it.result }.reversed()
        assertEquals(2, calls)
        assertIs<TaskRunResult.Failed>(results[0])
        assertEquals(TaskRunResult.Success, results[1])
    }

    @Test
    fun apply_should_logTaskApplyLineWithEffectiveSchedule_When_Enabled() = runTest {
        val h = Harness(this, key, { _, _ -> TaskRunResult.Success }, online)
        h.scheduler.apply(key, TaskConfig(true, Schedule.of(15, 5), TaskConstraints(network = NetworkPolicy.Unmetered)))
        h.scheduler.apply(key, TaskConfig(false, Schedule.of(15, 5), TaskConstraints()))
        h.scheduler.stop()
        val lines = dev.stapler.stelekit.logging.LogManager.logs.value.map { it.message }.filter { it.startsWith("TaskApply key=${TaskKeyEncoding.encode(key)}") }
        assertTrue(lines.any { "enabled=true effectiveInterval=15 flex=" in it && "network=unmetered" in it }, lines.toString())
        assertTrue(lines.any { "enabled=false effectiveInterval=-" in it }, lines.toString())
    }

    @Test
    fun tick_should_keepLooping_When_JobTrackerThrows() = runTest {
        var runs = 0
        val tracker = object : JobTracker {
            override fun begin(kind: JobKind, title: String, onCancel: (() -> Unit)?): JobHandle = throw IllegalStateException("tracker down")
        }
        val h = Harness(this, key, { _, _ -> runs++; TaskRunResult.Success }, online, jobTracker = tracker)
        h.scheduler.apply(key, TaskConfig(true, Schedule.of(15, 0), TaskConstraints()))
        advanceTimeBy(45 * minute)
        runCurrent()
        h.scheduler.stop()
        assertEquals(3, runs, "a throwing tracker must not kill the loop")
        assertEquals(3, h.runLog.recent(key).size)
    }

    @Test
    fun tick_should_keepLooping_When_RunLogAppendThrows() = runTest {
        var runs = 0
        val h = Harness(this, key, { _, _ -> runs++; TaskRunResult.Success }, online, appendOverride = { throw IllegalStateException("log down") })
        h.scheduler.apply(key, TaskConfig(true, Schedule.of(15, 0), TaskConstraints()))
        advanceTimeBy(45 * minute)
        runCurrent()
        h.scheduler.stop()
        assertEquals(3, runs, "a throwing run log must not kill the loop")
    }

    @Test
    fun tick_should_stopLoop_When_TaskThrowsCancellation() = runTest {
        var calls = 0
        val h = Harness(this, key, { _, _ -> calls++; throw CancellationException("cancelled") }, online)
        h.scheduler.apply(key, TaskConfig(true, Schedule.of(15, 0), TaskConstraints()))
        advanceTimeBy(45 * minute)
        runCurrent()
        h.scheduler.stop()
        assertEquals(1, calls)
    }

    @Test
    fun stop_should_endTicking_When_Called() = runTest {
        var runs = 0
        val h = Harness(this, key, { _, _ -> runs++; TaskRunResult.Success }, online)
        h.scheduler.apply(key, TaskConfig(true, Schedule.of(15, 0), TaskConstraints()))
        advanceTimeBy(15 * minute)
        runCurrent()
        h.scheduler.stop()
        advanceTimeBy(60 * minute)
        runCurrent()
        assertEquals(1, runs)
    }

    @Test
    fun runOnce_should_runTaskAndClearPending_When_Executed() = runTest {
        var runs = 0
        val h = Harness(this, key, { _, _ -> runs++; TaskRunResult.Success }, online)
        h.scheduler.runOnce(key, TaskConstraints())
        runCurrent()
        h.scheduler.stop()
        assertEquals(1, runs)
        assertEquals(null, h.scheduler.pendingOneShot(key))
    }
}
