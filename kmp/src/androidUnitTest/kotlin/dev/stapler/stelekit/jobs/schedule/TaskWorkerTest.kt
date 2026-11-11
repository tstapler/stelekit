// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TaskWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val periodicKind = TaskKind("sched-test")
    private val oneShotKind = TaskKind("model-download")
    private val periodicKey = TaskKey(periodicKind, TaskScope("g1"))
    private val oneShotKey = TaskKey(oneShotKind, TaskScope(""))

    private class MapSettings : Settings {
        val store = mutableMapOf<String, String>()
        var failReads = false
        var failWrites = false
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String): String {
            if (failReads) throw IllegalStateException("read failed")
            return store[key] ?: defaultValue
        }
        override fun putString(key: String, value: String) {
            if (failWrites) throw IllegalStateException("write failed")
            store[key] = value
        }
        override fun containsKey(key: String): Boolean {
            if (failReads) throw IllegalStateException("read failed")
            return store.containsKey(key)
        }
    }

    private class RecordingLog : TaskRunLog {
        val records = mutableListOf<TaskRunRecord>()
        override suspend fun append(record: TaskRunRecord) { records += record }
        override suspend fun recent(key: TaskKey) = records.filter { it.key == key }.reversed()
        override suspend fun lastSuccessAtMs(key: TaskKey): Long? = null
        val results get() = records.map { it.result }
    }

    private class ThrowingLog(private val error: Throwable) : TaskRunLog {
        override suspend fun append(record: TaskRunRecord) { throw error }
        override suspend fun recent(key: TaskKey) = emptyList<TaskRunRecord>()
        override suspend fun lastSuccessAtMs(key: TaskKey): Long? = null
    }

    private val settings = MapSettings()
    private val runLog = RecordingLog()
    private val registry = TaskRegistry()
    private var seedCalls = 0
    private var network: NetworkSnapshotProvider = NetworkSnapshotProvider { NetworkSnapshot(online = true, isMetered = false) }
    private var knownScopes: KnownScopes = KnownScopes { KnownScopesResult.Unknown }
    private val hooks = mutableMapOf<TaskKind, TaskAdapterHooks>()
    private var logOverride: TaskRunLog? = null
    private val health = SettingsReadHealth(context.getSharedPreferences("task_worker_test_health", Context.MODE_PRIVATE))
    private var bodyCalls = 0
    private var bodyResult: () -> TaskRunResult = { TaskRunResult.Success }

    private val defaultConfig = TaskConfig(enabled = false, schedule = Schedule.of(15, 0), constraints = TaskConstraints())

    @Before
    fun setUp() {
        health.clear()
        TaskWorkerRuntime.depsOverride = { _ ->
            TaskWorkerDeps(
                registry = registry,
                store = TaskSettingsStore(settings, { seedCalls++; null }, registry),
                runLog = logOverride ?: runLog,
                network = network,
                knownScopes = knownScopes,
                hooks = hooks::get,
                health = health,
                clock = { 1_000L },
            )
        }
    }

    @After
    fun tearDown() {
        TaskWorkerRuntime.depsOverride = null
    }

    private fun registerPeriodic(allowedInColdPath: Boolean = true) {
        registry.register(
            periodicKind,
            TaskSpec(null, "Periodic", TaskShape.Periodic(Schedule.of(15, 0)), defaultConfig, allowedInColdPath, oneShotRetryCap = 0),
            { _, _ -> bodyCalls++; bodyResult() },
        )
    }

    private fun registerOneShot(cap: Int = 5) {
        registry.register(
            oneShotKind,
            TaskSpec(null, "One shot", TaskShape.OneShot, defaultConfig, allowedInColdPath = true, oneShotRetryCap = cap),
            { _, _ -> bodyCalls++; bodyResult() },
        )
    }

    private suspend fun enablePeriodic(network: NetworkPolicy = NetworkPolicy.Any, enabled: Boolean = true) {
        TaskSettingsStore(settings, LegacyConfigSeed.None, registry)
            .write(periodicKey, TaskEntry(enabled, TaskOverrides(Schedule.of(15, 0), TaskConstraints(network))))
    }

    private fun input(key: TaskKey, policy: String? = null): Data = workDataOf(
        TaskWorkKeys.KEY_TASK_KIND to key.kind.id,
        TaskWorkKeys.KEY_TASK_SCOPE to key.scope.value,
        *(if (policy != null) arrayOf(TaskWorkKeys.KEY_NETWORK_POLICY to policy) else emptyArray()),
    )

    private fun run(key: TaskKey, policy: String? = null, attempt: Int = 0): Result = runBlocking {
        TestListenableWorkerBuilder<TaskWorker>(context)
            .setInputData(input(key, policy))
            .setRunAttemptCount(attempt)
            .build()
            .doWork()
    }

    private fun Result.error(): String? = (this as? Result.Failure)?.outputData?.getString(TaskWorkKeys.KEY_ERROR)

    @Test
    fun doWork_should_skipMeteredAndSucceed_When_periodicUnmeteredPolicyOnMeteredNetwork() = runBlocking {
        registerPeriodic(); enablePeriodic(NetworkPolicy.Unmetered)
        network = NetworkSnapshotProvider { NetworkSnapshot(online = true, isMetered = true) }
        assertEquals(Result.success(), run(periodicKey))
        assertEquals(0, bodyCalls)
        assertEquals(listOf<TaskRunResult>(TaskRunResult.Skipped("metered")), runLog.results)
    }

    @Test
    fun doWork_should_skipUnknownNetwork_When_providerThrows() = runBlocking {
        registerPeriodic(); enablePeriodic(NetworkPolicy.Unmetered)
        network = NetworkSnapshotProvider { throw IllegalStateException("no connectivity manager") }
        assertEquals(Result.success(), run(periodicKey))
        assertEquals(0, bodyCalls)
        assertEquals(listOf<TaskRunResult>(TaskRunResult.Skipped("unknown-network")), runLog.results)
    }

    @Test
    fun doWork_should_skipUnknownNetwork_When_isMeteredNull() = runBlocking {
        registerPeriodic(); enablePeriodic(NetworkPolicy.Unmetered)
        network = NetworkSnapshotProvider { NetworkSnapshot(online = true, isMetered = null) }
        assertEquals(Result.success(), run(periodicKey))
        assertEquals(0, bodyCalls)
        assertEquals(listOf<TaskRunResult>(TaskRunResult.Skipped("unknown-network")), runLog.results)
    }

    @Test
    fun doWork_should_retryBelowCapThenFailTerminal_When_oneShotSkippedForMetered() {
        registerOneShot()
        network = NetworkSnapshotProvider { NetworkSnapshot(online = true, isMetered = true) }
        assertEquals(Result.retry(), run(oneShotKey, "unmetered", attempt = 0))
        assertEquals(Result.retry(), run(oneShotKey, "unmetered", attempt = 3))
        val terminal = run(oneShotKey, "unmetered", attempt = 4)
        assertEquals("skipped: metered", terminal.error())
        assertEquals(0, bodyCalls)
        assertTrue(runLog.results.all { it == TaskRunResult.Skipped("metered") })
    }

    @Test
    fun doWork_should_shareOneBudget_When_skipsThenTransientFailuresMix() {
        registerOneShot()
        network = NetworkSnapshotProvider { NetworkSnapshot(online = true, isMetered = true) }
        repeat(3) { assertEquals(Result.retry(), run(oneShotKey, "unmetered", attempt = it)) }
        network = NetworkSnapshotProvider { NetworkSnapshot(online = true, isMetered = false) }
        bodyResult = { TaskRunResult.Failed("net", retryable = true) }
        // 4th attempt (count 3) is still within the 5-attempt budget; the 5th (count 4) and any later one is terminal.
        assertEquals(Result.retry(), run(oneShotKey, "unmetered", attempt = 3))
        assertEquals("net", run(oneShotKey, "unmetered", attempt = 4).error())
        assertEquals("net", run(oneShotKey, "unmetered", attempt = 5).error())
    }

    @Test
    fun doWork_should_failClosed_When_oneShotMeteredUnknownEvenIfConstraintSatisfied() {
        registerOneShot()
        network = NetworkSnapshotProvider { NetworkSnapshot(online = true, isMetered = null) }
        assertEquals(Result.retry(), run(oneShotKey, "unmetered", attempt = 0))
        assertEquals("skipped: unknown-network", run(oneShotKey, "unmetered", attempt = 4).error())
        assertEquals(0, bodyCalls)
    }

    @Test
    fun doWork_should_failWithoutRetry_When_failedNotRetryable() {
        registerOneShot()
        bodyResult = { TaskRunResult.Failed("auth", retryable = false) }
        assertEquals("auth", run(oneShotKey, "any").error())
    }

    @Test
    fun doWork_should_retry_When_oneShotFailedRetryable() {
        registerOneShot()
        bodyResult = { TaskRunResult.Failed("net", retryable = true) }
        assertEquals(Result.retry(), run(oneShotKey, "any"))
    }

    @Test
    fun doWork_should_neverRetry_When_periodicFailedEvenIfRetryable() = runBlocking {
        registerPeriodic(); enablePeriodic()
        bodyResult = { TaskRunResult.Failed("net", retryable = true) }
        assertTrue(run(periodicKey) is Result.Failure)
    }

    @Test
    fun doWork_should_recordFailedAndReturnFailure_When_bodyThrowsOutOfMemoryError() = runBlocking {
        registerPeriodic(); enablePeriodic()
        bodyResult = { throw OutOfMemoryError("boom") }
        assertTrue(run(periodicKey) is Result.Failure)
        assertEquals(listOf<TaskRunResult>(TaskRunResult.Failed("boom", retryable = false)), runLog.results)
    }

    @Test
    fun doWork_should_rethrow_When_bodyThrowsCancellationException() = runBlocking {
        registerPeriodic(); enablePeriodic()
        bodyResult = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { run(periodicKey) }
        Unit
    }

    @Test
    fun doWork_should_recordCancelledRun_When_bodyThrowsCancellationException() = runBlocking {
        registerPeriodic(); enablePeriodic()
        bodyResult = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { run(periodicKey) }
        assertEquals(listOf<TaskRunResult>(TaskRunResult.Skipped("cancelled")), runLog.results)
    }

    @Test
    fun doWork_should_truncateErrorToWorkManagerDataLimit_When_failureMessageIsHuge() {
        registerOneShot(cap = 1)
        bodyResult = { TaskRunResult.Failed("x".repeat(50_000), retryable = false) }
        val result = run(oneShotKey, "unmetered")
        val error = assertNotNull(result.error(), "a Failure result with an oversized message must still carry output data")
        assertTrue(error.length < 5_000, "message bounded, was ${error.length}")
        assertTrue(error.startsWith("xxx"), "the task's own message is kept, not WorkManager's size error: ${error.take(60)}")
    }

    @Test
    fun doWork_should_redactCredentialsFromOutputAndRecord_When_bodyThrowsWithSecretInMessage() {
        registerOneShot(cap = 1)
        bodyResult = { throw IllegalStateException("fetch https://user:hunter2pw@example.com/r.git ghp_AbCdEf0123456789xyz") }
        val result = run(oneShotKey, "unmetered")
        val error = assertNotNull(result.error())
        assertTrue("hunter2pw" !in error && "ghp_AbCdEf0123456789xyz" !in error, error)
        val recorded = (runLog.results.single() as TaskRunResult.Failed).message
        assertTrue("hunter2pw" !in recorded && "ghp_AbCdEf0123456789xyz" !in recorded, recorded)
    }

    @Test
    fun doWork_should_succeedWithNotRegisteredRecord_When_registryEmpty() {
        assertEquals(Result.success(), run(periodicKey))
        assertEquals(listOf<TaskRunResult>(TaskRunResult.Skipped("task-not-registered")), runLog.results)
    }

    @Test
    fun doWork_should_shortCircuitDisabledWithoutSeed_When_periodicEntryExists() = runBlocking {
        registerPeriodic(); enablePeriodic(enabled = false)
        assertEquals(Result.success(), run(periodicKey))
        assertEquals(0, bodyCalls)
        assertEquals(0, seedCalls)
        assertEquals(listOf<TaskRunResult>(TaskRunResult.Skipped("disabled")), runLog.results)
    }

    @Test
    fun doWork_should_neverCallSeedForExistingEntry_When_periodicRuns() = runBlocking {
        registerPeriodic(); enablePeriodic()
        assertEquals(Result.success(), run(periodicKey))
        assertEquals(0, seedCalls)
        assertEquals(1, bodyCalls)
    }

    @Test
    fun doWork_should_callSeedOnce_When_entryAndMarkerBothAbsent() {
        registerPeriodic()
        run(periodicKey)
        assertEquals(1, seedCalls)
        assertEquals(0, bodyCalls) // seed returned null -> disabled
    }

    @Test
    fun doWork_should_runBody_When_oneShotHasNoStoreEntry() {
        registerOneShot()
        assertEquals(Result.success(), run(oneShotKey, "unmetered"))
        assertEquals(1, bodyCalls)
        assertEquals(0, seedCalls)
    }

    @Test
    fun doWork_should_runBody_When_oneShotEntryExplicitlyDisabled() = runBlocking {
        registerOneShot()
        TaskSettingsStore(settings, LegacyConfigSeed.None, registry)
            .write(oneShotKey, TaskEntry(false, TaskOverrides(null, TaskConstraints(NetworkPolicy.Unmetered))))
        settings.failReads = true // a locked/broken store must not stop a user-initiated download
        assertEquals(Result.success(), run(oneShotKey, "unmetered"))
        assertEquals(1, bodyCalls)
    }

    @Test
    fun doWork_should_useInputPolicyNotStore_When_oneShotInputAnyOverStoredUnmetered() = runBlocking {
        registerOneShot()
        TaskSettingsStore(settings, LegacyConfigSeed.None, registry)
            .write(oneShotKey, TaskEntry(true, TaskOverrides(null, TaskConstraints(NetworkPolicy.Unmetered))))
        network = NetworkSnapshotProvider { NetworkSnapshot(online = true, isMetered = true) }
        assertEquals(Result.success(), run(oneShotKey, "any"))
        assertEquals(1, bodyCalls)
    }

    @Test
    fun doWork_should_failClosedToUnmetered_When_oneShotInputPolicyAbsent() {
        registerOneShot()
        network = NetworkSnapshotProvider { NetworkSnapshot(online = true, isMetered = true) }
        assertEquals(Result.retry(), run(oneShotKey, policy = null))
        assertEquals(0, bodyCalls)
    }

    @Test
    fun doWork_should_skipSettingsUnreadableAndSetHealth_When_periodicReadFails() = runBlocking {
        registerPeriodic()
        settings.failReads = true
        assertEquals(Result.success(), run(periodicKey))
        assertEquals(0, bodyCalls)
        assertEquals(listOf<TaskRunResult>(TaskRunResult.Skipped("settings-unreadable")), runLog.results)
        assertEquals(1_000L, health.lastFailureMs())
    }

    @Test
    fun doWork_should_clearHealth_When_nextPeriodicReadSucceeds() = runBlocking {
        registerPeriodic(); enablePeriodic()
        health.recordFailure(5L)
        run(periodicKey)
        assertNull(health.lastFailureMs())
    }

    @Test
    fun doWork_should_skipColdPathNotAllowed_When_specDisallowsIt() = runBlocking {
        registerPeriodic(allowedInColdPath = false); enablePeriodic()
        assertEquals(Result.success(), run(periodicKey))
        assertEquals(0, bodyCalls)
        assertEquals(listOf<TaskRunResult>(TaskRunResult.Skipped("cold-path-not-allowed")), runLog.results)
    }

    @Test
    fun doWork_should_cancelOwnWorkAndSucceed_When_scopeAbsentFromKnownRegistry() = runBlocking {
        registerPeriodic(); enablePeriodic()
        knownScopes = KnownScopes { KnownScopesResult.Known(setOf(TaskScope("other"))) }
        val ghost = TaskKey(periodicKind, TaskScope("ghost"))
        val harness = WorkManagerTestHarness()
        harness.wm.enqueueUniqueWork(
            ghost.workName(), androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequest.Builder(TaskWorker::class.java)
                .setConstraints(androidx.work.Constraints.Builder().setRequiresCharging(true).build()).build(),
        ).result.get()
        assertEquals(Result.success(), run(ghost))
        assertEquals(0, bodyCalls)
        assertEquals(androidx.work.WorkInfo.State.CANCELLED, harness.infos(ghost.workName()).single().state)
    }

    @Test
    fun doWork_should_runTask_When_knownScopesUnknownOrThrowing() = runBlocking {
        registerPeriodic()
        val ghost = TaskKey(periodicKind, TaskScope("ghost"))
        TaskSettingsStore(settings, LegacyConfigSeed.None, registry)
            .write(ghost, TaskEntry(true, TaskOverrides(Schedule.of(15, 0), TaskConstraints())))
        knownScopes = KnownScopes { KnownScopesResult.Unknown }
        run(ghost)
        knownScopes = KnownScopes { throw IllegalStateException("corrupt") }
        run(ghost)
        assertEquals(2, bodyCalls)
    }

    @Test
    fun doWork_should_neverCancelAppWideScope_When_knownScopesKnown() {
        registerOneShot()
        knownScopes = KnownScopes { KnownScopesResult.Known(setOf(TaskScope("g1"))) }
        assertEquals(Result.success(), run(oneShotKey, "any"))
        assertEquals(1, bodyCalls)
    }

    @Test
    fun doWork_should_runHooksInOrder_When_periodicKindHasHooks() = runBlocking {
        registerPeriodic(); enablePeriodic()
        val order = mutableListOf<String>()
        bodyResult = { order += "body"; TaskRunResult.Success }
        hooks[periodicKind] = object : TaskAdapterHooks {
            override suspend fun preRun(context: Context, key: TaskKey, input: Data): String? { order += "preRun"; return null }
            override suspend fun postRun(context: Context, key: TaskKey, input: Data, result: TaskRunResult) { order += "postRun" }
        }
        network = NetworkSnapshotProvider { order += "gate"; NetworkSnapshot(true, false) }
        run(periodicKey)
        assertEquals(listOf("preRun", "gate", "body", "postRun"), order)
    }

    @Test
    fun doWork_should_skipCloneRunning_When_preRunYields() = runBlocking {
        registerPeriodic(); enablePeriodic()
        hooks[periodicKind] = object : TaskAdapterHooks {
            override suspend fun preRun(context: Context, key: TaskKey, input: Data): String? = "clone-running"
        }
        assertEquals(Result.success(), run(periodicKey))
        assertEquals(0, bodyCalls)
        assertEquals(listOf<TaskRunResult>(TaskRunResult.Skipped("clone-running")), runLog.results)
    }

    @Test
    fun doWork_should_neverPromoteToForeground_When_periodicRuns() = runBlocking {
        registerPeriodic(); enablePeriodic()
        val promoted = mutableListOf<TaskKind>()
        val deps = TaskWorkerRuntime.depsOverride!!(context)
        val outcome = runTaskWork(context, periodicKey, input(periodicKey), 0, deps) { promoted += it }
        assertEquals(TaskWorkOutcome.Success, outcome)
        assertTrue(promoted.isEmpty())
    }

    @Test
    fun runTaskWork_should_promoteToForeground_When_oneShotRuns() = runBlocking {
        registerOneShot()
        val promoted = mutableListOf<TaskKind>()
        val deps = TaskWorkerRuntime.depsOverride!!(context)
        runTaskWork(context, oneShotKey, input(oneShotKey, "any"), 0, deps) { promoted += it }
        assertEquals(listOf(oneShotKind), promoted)
    }

    @Test
    fun recordRun_should_leaveResultUnchangedAndLog_When_runLogThrowsThrowable() = runBlocking {
        registerPeriodic(); enablePeriodic()
        logOverride = ThrowingLog(OutOfMemoryError("log full"))
        assertEquals(Result.success(), run(periodicKey))
        assertEquals(1, bodyCalls)
    }

    @Test
    fun recordRun_should_swallowThrowableButRethrowCancellation() = runBlocking {
        val record = TaskRunRecord(periodicKey, RunSource.Background, null, 1L, TaskRunResult.Success, ConstraintState(true, false, null))
        recordRun(ThrowingLog(OutOfMemoryError("x")), record)
        assertFailsWith<CancellationException> { recordRun(ThrowingLog(CancellationException("c")), record) }
        Unit
    }

    @Test
    fun recordRun_should_leaveResultUnchanged_When_realSettingsWriteThrows() = runBlocking {
        registerPeriodic(); enablePeriodic()
        val throwingSettings = MapSettings().also { it.failWrites = true }
        logOverride = SettingsTaskRunLog(throwingSettings)
        assertEquals(Result.success(), run(periodicKey))
        assertEquals(1, bodyCalls)
    }
}
