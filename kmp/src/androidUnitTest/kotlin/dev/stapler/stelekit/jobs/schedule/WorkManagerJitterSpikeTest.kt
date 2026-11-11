package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Clock
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import dev.stapler.stelekit.git.GitSyncWorker
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Spike 0.1.1 (background-task-scheduling): how `PeriodicWorkRequest` flex, initial delay and the
 * `UPDATE` policy behave on androidx.work 2.9.1. Kept as a regression test.
 *
 * Every observed outcome is printed as a `SPIKE|` line (read them from the Gradle test report's
 * system-out) and recorded in ADR-002. Where an API or behaviour is unavailable the test records
 * that fact instead of failing; only invariants the plan depends on (enqueue works, read-back of the
 * fields ADR-002 names) are asserted.
 *
 * Uses `WorkManagerTestInitHelper`, so the test driver has no virtual clock: a controllable
 * [Clock] is injected through `Configuration` to give run times a deterministic timeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WorkManagerJitterSpikeTest {

    class SpikeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            runStarts += clock.currentTimeMillis()
            overrideNext?.let { target ->
                // setNextScheduleTimeOverride is absent from 2.9.1's ListenableWorker (checked with javap),
                // so it is probed reflectively and the outcome is recorded rather than failing.
                val m = ListenableWorker::class.java.methods.firstOrNull { it.name == "setNextScheduleTimeOverride" }
                overrideApiPresent = m != null
                m?.invoke(this, target)
            }
            return Result.success()
        }
    }

    /** Stand-in for the future generic `TaskWorker` (it does not exist yet at spike time). */
    class StandInTaskWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }

    class BlockingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            started.countDown()
            workerStoppedDuringRun.set(false)
            try {
                // Poll so a stop signal from WorkManager is observable.
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline && !release.await(20, TimeUnit.MILLISECONDS)) {
                    if (isStopped) {
                        workerStoppedDuringRun.set(true)
                        return Result.success()
                    }
                }
                return Result.success()
            } finally {
                exited.countDown()
            }
        }
    }

    class FakeClock(start: Long) : Clock {
        val now = AtomicLong(start)
        override fun currentTimeMillis(): Long = now.get()
    }

    private lateinit var context: Context
    private val name = "stelekit_git_sync_g1"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clock = FakeClock(1_700_000_000_000L)
        runStarts = mutableListOf()
        overrideNext = null
        initWorkManager(SynchronousExecutor())
    }

    private fun initWorkManager(executor: java.util.concurrent.Executor) {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(executor).setClock(clock).build(),
        )
    }

    private val wm: WorkManager get() = WorkManager.getInstance(context)
    private val driver: TestDriver get() = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))

    private fun record(label: String, value: Any?) = println("SPIKE|$label|$value")

    private fun min(m: Long) = TimeUnit.MINUTES.toMillis(m)

    private fun periodic(
        intervalMin: Long,
        flexMin: Long,
        initialDelayMin: Long,
        unmetered: Boolean = true,
        input: Data = Data.EMPTY,
        worker: Class<out androidx.work.ListenableWorker> = SpikeWorker::class.java,
    ): PeriodicWorkRequest =
        PeriodicWorkRequest.Builder(worker, intervalMin, TimeUnit.MINUTES, flexMin, TimeUnit.MINUTES)
            .setInitialDelay(initialDelayMin, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(input)
            .build()

    private fun enqueue(req: PeriodicWorkRequest, policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE) =
        wm.enqueueUniquePeriodicWork(name, policy, req).result.get()

    private fun info(): WorkInfo = wm.getWorkInfosForUniqueWork(name).get().single { !it.state.isFinished }

    /** Room is not on the test classpath, so `workDatabase` is read reflectively; returns (workerClassName, input). */
    private fun spec(id: UUID): Pair<String, Data> {
        val db = WorkManagerImpl.getInstance(context).javaClass.getMethod("getWorkDatabase").invoke(WorkManagerImpl.getInstance(context))
        val dao = db.javaClass.getMethod("workSpecDao").invoke(db)
        val ws = dao.javaClass.getMethod("getWorkSpec", String::class.java).invoke(dao, id.toString())
        val cls = ws.javaClass.getField("workerClassName").get(ws) as String
        val input = ws.javaClass.getField("input").get(ws) as Data
        return cls to input
    }

    @Test
    fun readBack_should_ExposeIntervalFlexInitialDelayAndConstraints() {
        val before = clock.currentTimeMillis()
        enqueue(periodic(60, 10, 7))
        val i = info()
        val p = assertNotNull(i.periodicityInfo, "periodicityInfo available on 2.9.1")
        record("readback.repeatIntervalMillis", p.repeatIntervalMillis)
        record("readback.flexIntervalMillis", p.flexIntervalMillis)
        record("readback.initialDelayMillis", i.initialDelayMillis)
        record("readback.requiredNetworkType", i.constraints.requiredNetworkType)
        record("readback.nextScheduleTimeMillis.minusEnqueue(min)", TimeUnit.MILLISECONDS.toMinutes(i.nextScheduleTimeMillis - before))
        record("readback.state", i.state)
        assertEquals(min(60), p.repeatIntervalMillis)
        assertEquals(min(10), p.flexIntervalMillis)
        assertEquals(min(7), i.initialDelayMillis)
        assertEquals(NetworkType.UNMETERED, i.constraints.requiredNetworkType)
    }

    @Test
    fun update_should_RecordWhichFieldsChangeAndWhetherIdSurvives() {
        val before = clock.currentTimeMillis()
        val first = periodic(60, 10, 7)
        enqueue(first)
        val i1 = info()
        val next1 = i1.nextScheduleTimeMillis

        clock.now.addAndGet(min(5)) // time passes between the two applies
        val second = periodic(120, 20, 9)
        enqueue(second)
        val i2 = info()
        val p2 = assertNotNull(i2.periodicityInfo)

        record("update.idSurvives(reusesFirstId)", i2.id == first.id)
        record("update.idIsSecondRequestId", i2.id == second.id)
        record("update.interval(min)", TimeUnit.MILLISECONDS.toMinutes(p2.repeatIntervalMillis))
        record("update.flex(min)", TimeUnit.MILLISECONDS.toMinutes(p2.flexIntervalMillis))
        record("update.initialDelay(min)", TimeUnit.MILLISECONDS.toMinutes(i2.initialDelayMillis))
        record("update.nextRun.beforeUpdate(min after t0)", TimeUnit.MILLISECONDS.toMinutes(next1 - before))
        record("update.nextRun.afterUpdate(min after t0)", TimeUnit.MILLISECONDS.toMinutes(i2.nextScheduleTimeMillis - before))
        record("update.nextRunUnchanged", next1 == i2.nextScheduleTimeMillis)
        record("update.activeWorkCount", wm.getWorkInfosForUniqueWork(name).get().count { !it.state.isFinished })
        record("update.allWorkInfoStates", wm.getWorkInfosForUniqueWork(name).get().map { it.id == first.id to it.state })
        // The plan only needs these two to be a definite, recorded answer.
        assertTrue(wm.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished })
    }

    @Test
    fun update_should_RecordWorkerClassNameAndRunHistory_When_ClassChanges() {
        val first = periodic(60, 10, 7, worker = GitSyncWorker::class.java)
        enqueue(first)
        val classBefore = spec(first.id).first
        val second = periodic(60, 10, 7, worker = StandInTaskWorker::class.java)
        enqueue(second)
        val i = info()
        val classAfter = spec(i.id).first
        record("class.before", classBefore)
        record("class.after", classAfter)
        record("class.changedByUpdate", classBefore != classAfter)
        record("class.idKept", i.id == first.id)
        record("class.idIsSecond", i.id == second.id)
        record("class.afterIsStandIn", classAfter == StandInTaskWorker::class.java.name)
        assertEquals(GitSyncWorker::class.java.name, classBefore)
    }

    @Test
    fun update_should_RecordBehaviour_When_PeriodicWorkIsRunning() {
        release = CountDownLatch(1)
        started = CountDownLatch(1)
        exited = CountDownLatch(1)
        workerStoppedDuringRun = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(4)
        try {
            initWorkManager(pool)
            val first = periodic(60, 10, 0, unmetered = false, worker = BlockingWorker::class.java)
            enqueue(first)
            driver.setAllConstraintsMet(first.id)
            driver.setInitialDelayMet(first.id)
            val ran = started.await(5, TimeUnit.SECONDS)
            val stateWhileRunning = wm.getWorkInfoById(first.id).get().state
            record("running.workerStarted", ran)
            record("running.stateBeforeUpdate", stateWhileRunning)

            val second = periodic(120, 20, 9, unmetered = false, worker = BlockingWorker::class.java)
            val updateOutcome = runCatching { enqueue(second) }
            // Await the worker leaving doWork (stopped by the update, or not: the bound only caps a no-stop outcome).
            exited.await(2, TimeUnit.SECONDS)
            val all = wm.getWorkInfosForUniqueWork(name).get()
            record("running.updateCall", updateOutcome.fold({ "ok" }, { "threw ${it::class.simpleName}: ${it.message}" }))
            record("running.workerSawStop(isStopped)", workerStoppedDuringRun.get())
            record("running.statesAfterUpdate", all.map { (if (it.id == first.id) "first" else "second") + ":" + it.state })
            record("running.activePeriodicInterval(min)", all.firstOrNull { !it.state.isFinished }?.periodicityInfo?.let { TimeUnit.MILLISECONDS.toMinutes(it.repeatIntervalMillis) })
            record("running.activeId", all.firstOrNull { !it.state.isFinished }?.let { if (it.id == first.id) "first" else "second" })
        } finally {
            release.countDown()
            exited.await(2, TimeUnit.SECONDS)
            pool.shutdownNow()
            initWorkManager(SynchronousExecutor())
        }
    }

    @Test
    fun observedGap_should_BeRecordedOverSeveralPeriods() {
        enqueue(periodic(60, 10, 7, unmetered = false))
        val id = info().id
        val enqueuedAt = clock.currentTimeMillis()
        val starts = mutableListOf<Long>()
        val nexts = mutableListOf<Long>()
        repeat(5) {
            clock.now.set(maxOf(clock.now.get(), info().nextScheduleTimeMillis)) // jump to the earliest eligible time
            if (it == 0) driver.setInitialDelayMet(id) else driver.setPeriodDelayMet(id)
            driver.setAllConstraintsMet(id)
            starts += clock.currentTimeMillis()
            nexts += info().nextScheduleTimeMillis
        }
        val runStartsMin = runStarts.map { TimeUnit.MILLISECONDS.toMinutes(it - enqueuedAt) }
        val gaps = runStarts.zipWithNext { a, b -> TimeUnit.MILLISECONDS.toMinutes(b - a) }
        val nextAfterRunMin = nexts.map { TimeUnit.MILLISECONDS.toMinutes(it - enqueuedAt) }
        record("gap.runStarts(min after enqueue)", runStartsMin)
        record("gap.nextScheduleTimeAfterEachRun(min after enqueue)", nextAfterRunMin)
        record("gap.consecutiveRunGaps(min)", gaps)
        record("gap.nextMinusRunStart(min)", nexts.zip(starts) { n, s -> TimeUnit.MILLISECONDS.toMinutes(n - s) })
        record("gap.runCount", runStarts.size)
        record(
            "gap.note",
            "TestDriver has no real OS scheduler: it runs on demand, so gaps reflect the clock jumps taken from nextScheduleTimeMillis (the earliest the OS may run it). Window per period is [next, next+flex].",
        )
        assertTrue(runStarts.isNotEmpty(), "worker ran at least once via the test driver")
    }

    @Test
    fun update_should_RecordWhetherInputDataIsReplaced() {
        val firstData = Data.Builder().putString("network_policy", "Any").putString("graph_id", "g1").build()
        val secondData = Data.Builder().putString("network_policy", "Unmetered").putString("graph_id", "g1").build()
        val first = periodic(60, 10, 7, input = firstData)
        enqueue(first)
        enqueue(periodic(60, 10, 7, input = secondData))
        val i = info()
        val stored = spec(i.id).second
        record("input.networkPolicyAfterUpdate", stored.getString("network_policy"))
        record("input.replacedByUpdate", stored.getString("network_policy") == "Unmetered")
        record("input.idKept", i.id == first.id)
    }

    @Test
    fun nextScheduleTimeOverride_and_periodicityInfo_should_BeProbed() {
        enqueue(periodic(60, 10, 0, unmetered = false))
        val id = info().id
        record("api.WorkInfo.periodicityInfo", "available (WorkInfo.periodicityInfo.repeatIntervalMillis/flexIntervalMillis)")
        record("api.WorkInfo.nextScheduleTimeMillis", "available")
        record("api.WorkInfo.initialDelayMillis", "available")
        val target = clock.currentTimeMillis() + min(33)
        overrideNext = target
        driver.setInitialDelayMet(id)
        driver.setAllConstraintsMet(id)
        val i = info()
        record("override.api.setNextScheduleTimeOverride", if (overrideApiPresent == true) "available" else "UNAVAILABLE on 2.9.1 (no such method on ListenableWorker)")
        record("override.targetMinAfterNow", 33)
        record("override.nextScheduleTimeMillis.minusNow(min)", TimeUnit.MILLISECONDS.toMinutes(i.nextScheduleTimeMillis - clock.currentTimeMillis()))
        record("override.readBackEqualsTarget", i.nextScheduleTimeMillis == target)
        record("override.workerRan", runStarts.size)
        assertTrue(runStarts.isNotEmpty())
    }

    companion object {
        @Volatile var clock: FakeClock = FakeClock(1_700_000_000_000L)
        @Volatile var overrideApiPresent: Boolean? = null
        @Volatile var runStarts: MutableList<Long> = mutableListOf()
        @Volatile var overrideNext: Long? = null
        @Volatile var release = CountDownLatch(1)
        @Volatile var started = CountDownLatch(1)
        @Volatile var exited = CountDownLatch(1)
        @Volatile var workerStoppedDuringRun = AtomicBoolean(false)
    }

}
