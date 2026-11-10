// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** In-memory [Settings] with switchable write failures; guaranteed live (no @Test methods). */
class ContractSettings : Settings {
    val values = mutableMapOf<String, String>()
    var failWrites = false

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
    override fun getString(key: String, defaultValue: String): String = values[key] ?: defaultValue
    override fun putString(key: String, value: String) {
        if (failWrites) throw IllegalStateException("write failed")
        values[key] = value
    }
    override fun containsKey(key: String): Boolean = values.containsKey(key)
}

/** The `PlatformSettings`-without-`SteleKitContext.init` failure mode: writes vanish, reads see nothing. */
class SilentNoOpSettings : Settings {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
    override fun putBoolean(key: String, value: Boolean) {}
    override fun getString(key: String, defaultValue: String): String = defaultValue
    override fun putString(key: String, value: String) {}
    override fun containsKey(key: String): Boolean = false
}

/** A pending one-shot as an adapter reports it to the contract suite. */
data class PendingOneShot(val requestId: String, val network: NetworkPolicy)

/**
 * Invariants every [BackgroundTaskScheduler] adapter must satisfy (the `TargetWriterContractSuite`
 * pattern). Name deliberately does not end in `Test`: an adapter's concrete `*Test` subclass plugs
 * in by implementing the three abstract members. Capability-conditional invariants return early
 * when the capability is absent (no JVM-only `assumeTrue` in commonTest). No real-clock delays.
 */
abstract class BackgroundTaskSchedulerContract {
    /**
     * Builds a fresh adapter whose [AppliedConfigLedger] is `AppliedConfigLedger(ledgerSettings)` and
     * whose resume precedence consults [store]. [registry] holds any tasks the test registered.
     */
    protected abstract fun createScheduler(
        ledgerSettings: Settings,
        store: TaskSettingsStore,
        registry: TaskRegistry,
    ): BackgroundTaskScheduler

    /** Inserts live periodic work the adapter did not `apply` (no ledger, store or legacy flag involved). */
    protected abstract suspend fun seedExternalPeriodic(scheduler: BackgroundTaskScheduler, key: TaskKey, config: TaskConfig)

    /** The pending one-shot for [key], or null. */
    protected abstract suspend fun pendingOneShot(scheduler: BackgroundTaskScheduler, key: TaskKey): PendingOneShot?

    private class Fixture(
        val scheduler: BackgroundTaskScheduler,
        val store: TaskSettingsStore,
        val storeSettings: ContractSettings,
        val ledgerSettings: Settings,
    )

    private fun fixture(ledgerSettings: Settings = ContractSettings()): Fixture {
        val registry = TaskRegistry()
        val storeSettings = ContractSettings()
        val store = TaskSettingsStore(storeSettings, LegacyConfigSeed.None, registry)
        return Fixture(createScheduler(ledgerSettings, store, registry), store, storeSettings, ledgerSettings)
    }

    private val g1 = TaskKey(TaskKind("git-sync"), TaskScope("g1"))
    private val g2 = TaskKey(TaskKind("git-sync"), TaskScope("g2"))
    private val download = TaskKey(TaskKind("model-download"), TaskScope(""))

    private fun config(interval: Int, jitter: Int = 0, constraints: TaskConstraints = TaskConstraints(), enabled: Boolean = true) =
        TaskConfig(enabled, Schedule.of(interval, jitter), constraints)

    @BeforeTest
    fun resetProcessStaticSnapshots() {
        AppliedConfigLedger.clearProcessStatic()
    }

    @Test
    fun apply_should_beIdempotentAndFloorInterval_When_CalledTwice() = runTest {
        val f = fixture()
        val min = f.scheduler.capabilities.minIntervalMinutes
        f.scheduler.apply(g1, config(60))
        val first = f.scheduler.describe(g1)
        f.scheduler.apply(g1, config(60))
        assertEquals(first, f.scheduler.describe(g1))
        assertEquals(maxOf(60, min), first?.effectiveIntervalMinutes)
    }

    @Test
    fun apply_should_removeWork_When_Disabled() = runTest {
        val f = fixture()
        f.scheduler.apply(g1, config(60))
        assertNotNull(f.scheduler.describe(g1))
        f.scheduler.apply(g1, config(60, enabled = false))
        assertNull(f.scheduler.describe(g1))
    }

    @Test
    fun cancel_should_leaveOtherKeys_When_OneKeyCancelled() = runTest {
        val f = fixture()
        f.scheduler.apply(g1, config(60))
        f.scheduler.apply(g2, config(60))
        f.scheduler.cancel(g1)
        assertNull(f.scheduler.describe(g1))
        assertNotNull(f.scheduler.describe(g2))
    }

    @Test
    fun apply_should_reportClamp_When_IntervalBelowMinimum() = runTest {
        val f = fixture()
        val min = f.scheduler.capabilities.minIntervalMinutes
        if (min <= 1) return@runTest
        f.scheduler.apply(g1, config(min - 1))
        val clamped = f.scheduler.describe(g1)
        assertEquals(min, clamped?.effectiveIntervalMinutes)
        assertEquals(true, clamped?.clamped)
        f.scheduler.apply(g1, config(min))
        val atMin = f.scheduler.describe(g1)
        assertEquals(min, atMin?.effectiveIntervalMinutes)
        assertEquals(false, atMin?.clamped)
    }

    @Test
    fun pauseResume_should_restoreAppliedInterval_When_NoStoreEntryExists() = runTest {
        val f = fixture()
        f.scheduler.apply(g1, config(45))
        val before = f.scheduler.describe(g1)
        f.scheduler.pause(g1.scope)
        assertNull(f.scheduler.describe(g1))
        f.scheduler.resume(g1.scope)
        assertEquals(before?.effectiveIntervalMinutes, f.scheduler.describe(g1)?.effectiveIntervalMinutes)
        assertEquals(maxOf(45, f.scheduler.capabilities.minIntervalMinutes), f.scheduler.describe(g1)?.effectiveIntervalMinutes)
        assertFalse(f.storeSettings.values.keys.any { it.startsWith(TaskSettingsKeys.ENTRY) })
    }

    @Test
    fun pauseResume_should_restoreSeededExternalWork_When_NoLedgerOrStoreEntry() = runTest {
        val f = fixture()
        val constraints = TaskConstraints(requiresCharging = f.scheduler.capabilities.supportsCharging)
        seedExternalPeriodic(f.scheduler, g1, config(30, constraints = constraints))
        val before = f.scheduler.describe(g1)
        assertNotNull(before)
        f.scheduler.pause(g1.scope)
        assertNull(f.scheduler.describe(g1))
        f.scheduler.resume(g1.scope)
        assertEquals(before, f.scheduler.describe(g1))
    }

    @Test
    fun pauseResume_should_restoreInProcess_When_SettingsSilentlyNoOp() = runTest {
        val f = fixture(SilentNoOpSettings())
        f.scheduler.apply(g1, config(45))
        val before = f.scheduler.describe(g1)
        f.scheduler.pause(g1.scope)
        f.scheduler.resume(g1.scope)
        assertEquals(before, f.scheduler.describe(g1))
    }

    @Test
    fun apply_should_updateSnapshotNotEnqueue_When_AppliedWhilePaused() = runTest {
        val f = fixture()
        f.scheduler.apply(g1, config(45))
        f.scheduler.pause(g1.scope)
        f.scheduler.apply(g1, config(90))
        assertNull(f.scheduler.describe(g1))
        f.scheduler.resume(g1.scope)
        assertEquals(maxOf(90, f.scheduler.capabilities.minIntervalMinutes), f.scheduler.describe(g1)?.effectiveIntervalMinutes)
    }

    @Test
    fun resume_should_enqueueNothing_When_DisabledWhilePaused() = runTest {
        val f = fixture()
        f.scheduler.apply(g1, config(45))
        f.scheduler.pause(g1.scope)
        f.scheduler.apply(g1, config(45, enabled = false))
        f.scheduler.resume(g1.scope)
        assertNull(f.scheduler.describe(g1))
    }

    @Test
    fun pause_should_stillCancelAndResumeFromMemoryCopy_When_SnapshotWriteThrows() = runTest {
        val settings = ContractSettings()
        val f = fixture(settings)
        f.scheduler.apply(g1, config(45))
        settings.failWrites = true
        f.scheduler.pause(g1.scope)
        assertNull(f.scheduler.describe(g1))
        f.scheduler.resume(g1.scope)
        assertEquals(maxOf(45, f.scheduler.capabilities.minIntervalMinutes), f.scheduler.describe(g1)?.effectiveIntervalMinutes)
    }

    @Test
    fun resume_should_fallBackToLastApplied_When_SnapshotWriteThrowsAndMemoryCopyAbsent() = runTest {
        val settings = ContractSettings()
        val f = fixture(settings)
        f.scheduler.apply(g1, config(45))
        settings.failWrites = true
        f.scheduler.pause(g1.scope)
        assertNull(f.scheduler.describe(g1))
        AppliedConfigLedger.clearProcessStatic()
        settings.failWrites = false
        f.scheduler.resume(g1.scope)
        assertEquals(maxOf(45, f.scheduler.capabilities.minIntervalMinutes), f.scheduler.describe(g1)?.effectiveIntervalMinutes)
    }

    @Test
    fun resume_should_notResurrect_When_StoreEntryExplicitlyDisabled() = runTest {
        val f = fixture()
        f.scheduler.apply(g1, config(45))
        f.scheduler.pause(g1.scope)
        f.store.write(g1, TaskEntry(enabled = false, overrides = null))
        f.scheduler.resume(g1.scope)
        assertNull(f.scheduler.describe(g1))
    }

    @Test
    fun resume_should_notResurrect_When_StoreHoldsTombstone() = runTest {
        val f = fixture()
        f.scheduler.apply(g1, config(45))
        f.scheduler.pause(g1.scope)
        f.store.tombstone(g1)
        f.scheduler.resume(g1.scope)
        assertNull(f.scheduler.describe(g1))
    }

    @Test
    fun resume_should_preferSnapshot_When_StoreHoldsEnabledEntry() = runTest {
        val f = fixture()
        f.scheduler.apply(g1, config(45))
        f.store.write(g1, TaskEntry(true, TaskOverrides(Schedule.of(120, 0), TaskConstraints())))
        f.scheduler.pause(g1.scope)
        f.scheduler.resume(g1.scope)
        assertEquals(maxOf(45, f.scheduler.capabilities.minIntervalMinutes), f.scheduler.describe(g1)?.effectiveIntervalMinutes)
    }

    @Test
    fun resume_should_enqueueNothingAndNotThrow_When_NothingWasPausedOrStored() = runTest {
        val f = fixture()
        f.scheduler.resume(g1.scope)
        assertNull(f.scheduler.describe(g1))
    }

    @Test
    fun runOnce_should_recordUnmeteredOneShotWithoutPeriodicEntry_When_Supported() = runTest {
        val f = fixture()
        f.scheduler.runOnce(download, TaskConstraints(network = NetworkPolicy.Unmetered))
        if (f.scheduler.capabilities.supportsUnmetered) {
            assertEquals(NetworkPolicy.Unmetered, pendingOneShot(f.scheduler, download)?.network)
            assertNull(f.scheduler.describe(download))
        } else {
            val info = f.scheduler.describe(download)
            assertNotNull(info?.downgradeReason)
            assertEquals(NetworkPolicy.Any, pendingOneShot(f.scheduler, download)?.network)
        }
    }

    @Test
    fun runOnce_should_replaceOrKeepPendingRequest_By_OneShotConflict() = runTest {
        val f = fixture()
        val c = TaskConstraints()
        f.scheduler.runOnce(download, c)
        val first = pendingOneShot(f.scheduler, download)
        assertNotNull(first)
        f.scheduler.runOnce(download, c, OneShotConflict.Keep)
        assertEquals(first?.requestId, pendingOneShot(f.scheduler, download)?.requestId)
        f.scheduler.runOnce(download, c, OneShotConflict.Replace)
        val replaced = pendingOneShot(f.scheduler, download)
        assertNotEquals(first?.requestId, replaced?.requestId)
        assertNotNull(replaced)
    }
}
