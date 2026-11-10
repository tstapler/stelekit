// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.jobs.JobKind
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TaskKeyTest {
    private fun key(kind: String, scope: String) = TaskKey(TaskKind(kind), TaskScope(scope))

    @Test
    fun gitSyncKeepsLegacyWorkName() {
        assertEquals("stelekit_git_sync_graph-a", key("git-sync", "graph-a").workName())
    }

    @Test
    fun otherKindsUseHashedScope() {
        val name = key("lfs-transfer", "My Graph/2").workName()
        assertEquals("stelekit_task_lfs-transfer_" + stableHex("My Graph/2"), name)
        assertTrue(Regex("stelekit_task_lfs-transfer_[0-9a-f]{16}").matches(name))
    }

    @Test
    fun fnvHashMatchesKnownVectors() {
        // FNV-1a 64-bit reference vectors
        assertEquals("cbf29ce484222325", stableHex(""))
        assertEquals("af63dc4c8601ec8c", stableHex("a"))
    }

    @Test
    fun modelDownloadKeepsLegacyWorkName() {
        assertEquals("depth-model-download", key("model-download", "").workName())
    }

    @Test
    fun keysAreInjectiveAcrossKindScopeSplit() {
        val a = key("a", "b_c")
        val b = key("a-b", "c")
        assertNotEquals(a.workName(), b.workName())
        assertNotEquals(TaskKeyEncoding.settingsKey("task_entry", a), TaskKeyEncoding.settingsKey("task_entry", b))
        assertNotEquals(TaskKeyEncoding.settingsKey("task_run_log", a), TaskKeyEncoding.settingsKey("task_run_log", b))
    }

    @Test
    fun hexLookingScopeNeverCollidesWithHashedScope() {
        val victim = key("lfs-transfer", "x")
        val lookalike = key("lfs-transfer", stableHex("x"))
        assertNotEquals(victim.workName(), lookalike.workName())
    }

    @Test
    fun settingsKeyUsesLengthPrefix() {
        assertEquals("task_entry_git-sync.3.a_b", TaskKeyEncoding.settingsKey("task_entry", key("git-sync", "a_b")))
    }

    @Test
    fun kindCharsetIsRestricted() {
        assertFailsWith<IllegalArgumentException> { TaskKind("a_b") }
        assertFailsWith<IllegalArgumentException> { TaskKind("A") }
        assertFailsWith<IllegalArgumentException> { TaskKind("") }
        TaskKind("a-b-9")
    }

    @Test
    fun hashedWorkNamesUseSafeCharsetAndSettingsKeysAreInjectiveForAnyScope() = runTest {
        val scopes = Arb.string(0..12)
        val seenNames = HashMap<String, TaskKey>()
        val seenSettings = HashMap<String, TaskKey>()
        fun record(seen: HashMap<String, TaskKey>, name: String, k: TaskKey) {
            val previous = seen.put(name, k)
            assertTrue(previous == null || previous == k, "collision on $name")
        }
        checkAll(scopes, scopes) { s1, s2 ->
            for (k in listOf(key("lfs-transfer", s1), key("a-b", s2), key("a", s1 + "_" + s2))) {
                assertTrue(Regex("[A-Za-z0-9_-]+").matches(k.workName()), k.workName())
                record(seenNames, k.workName(), k)
                record(seenSettings, TaskKeyEncoding.settingsKey("task_entry", k), k)
            }
        }
    }

    @Test
    fun specialScopesStayDistinct() {
        val scopes = listOf("", "_", ".", "1", "1.1", "16", stableHex("1"), "a.1.b", "3.a_b")
        val keys = scopes.map { key("a", it) }
        assertEquals(keys.size, keys.map { TaskKeyEncoding.settingsKey("task_entry", it) }.toSet().size)
        assertEquals(keys.size, keys.map { it.workName() }.toSet().size)
    }

    @Test
    fun failedResultIsNotRetryableByDefault() {
        assertFalse(TaskRunResult.Failed("auth").retryable)
    }

    @Test
    fun scheduleRejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { Schedule.of(-5, 3) }
        assertFailsWith<IllegalArgumentException> { Schedule.of(10, 30) }
        assertFailsWith<IllegalArgumentException> { Schedule.of(0, 0) }
        assertFailsWith<IllegalArgumentException> { Schedule.of(10, -1) }
        assertEquals(60, Schedule.of(60, 0).intervalMinutes)
    }

    @Test
    fun scheduleCoerceAlwaysValid() = runTest {
        val c = Schedule.coerce(-5, 30)
        assertTrue(c.intervalMinutes >= 1 && c.jitterMinutes in 0..c.intervalMinutes)
        checkAll(Arb.int(), Arb.int()) { i, j ->
            val s = Schedule.coerce(i, j)
            assertTrue(s.intervalMinutes >= 1)
            assertTrue(s.jitterMinutes in 0..s.intervalMinutes)
        }
    }

    @Test
    fun scheduleHasValueEquality() {
        assertEquals(Schedule.of(60, 6), Schedule.of(60, 6))
        assertNotEquals(Schedule.of(60, 6), Schedule.of(60, 0))
    }

    @Test
    fun oneShotOverridesAndNullScheduleFallbackAreRepresentable() {
        val o = TaskOverrides(schedule = null, constraints = TaskConstraints())
        val d = TaskDefaults(schedule = null, constraints = TaskConstraints(network = NetworkPolicy.Unmetered))
        assertNull(o.schedule)
        assertNull(d.schedule)
        val entry = TaskEntry(enabled = false, overrides = o)
        assertNull(entry.overrides?.schedule)
    }

    private val periodicSpec = TaskSpec(
        jobKind = JobKind.GitSync,
        title = "Git sync",
        shape = TaskShape.Periodic(Schedule.of(60, 6)),
        defaultConfig = TaskConfig(enabled = false, schedule = Schedule.of(60, 6), constraints = TaskConstraints()),
        allowedInColdPath = true,
        oneShotRetryCap = 0,
    )
    private val noopTask = BackgroundTask { _, _ -> TaskRunResult.Success }

    @Test
    fun registryIsIdempotentForEqualSpecWithDifferentTaskInstance() {
        val registry = TaskRegistry()
        val kind = TaskKind("git-sync")
        val first = BackgroundTask { _, _ -> TaskRunResult.Success }
        val second = BackgroundTask { _, _ -> TaskRunResult.Success }
        registry.register(kind, periodicSpec, first)
        registry.register(kind, periodicSpec.copy(), second)
        assertSame(first, registry.taskFor(kind))
        assertEquals(periodicSpec, registry.specFor(kind))
        assertEquals(setOf(kind), registry.kinds())
    }

    @Test
    fun registryRejectsConflictingSpec() {
        val registry = TaskRegistry()
        val kind = TaskKind("git-sync")
        registry.register(kind, periodicSpec, noopTask)
        assertFailsWith<IllegalStateException> {
            registry.register(kind, periodicSpec.copy(title = "Other"), noopTask)
        }
    }

    @Test
    fun registryLookupOfUnknownKindIsNull() {
        val registry = TaskRegistry()
        assertNull(registry.taskFor(TaskKind("nope")))
        assertNull(registry.specFor(TaskKind("nope")))
    }

    @Test
    fun portAcceptsRunOnceWithAndWithoutConflictMode() = runTest {
        val fake = RecordingScheduler()
        val k = key("model-download", "")
        fake.runOnce(k, TaskConstraints())
        fake.runOnce(k, TaskConstraints(), OneShotConflict.Replace)
        assertEquals(listOf(OneShotConflict.Keep, OneShotConflict.Replace), fake.conflicts)
    }

    private class RecordingScheduler : BackgroundTaskScheduler {
        val conflicts = mutableListOf<OneShotConflict>()
        override val capabilities = SchedulerCapabilities(
            minIntervalMinutes = 15, minFlexMinutes = 5, supportsUnmetered = true, supportsCharging = true,
            supportsJitter = true, runsWhenClosed = true, schedulesWork = true,
        )
        override suspend fun apply(key: TaskKey, config: TaskConfig) = Unit
        override suspend fun runOnce(key: TaskKey, constraints: TaskConstraints, onConflict: OneShotConflict) {
            conflicts += onConflict
        }
        override suspend fun cancel(key: TaskKey) = Unit
        override suspend fun cancelAll(scope: TaskScope) = Unit
        override suspend fun pause(scope: TaskScope) = Unit
        override suspend fun resume(scope: TaskScope) = Unit
        override suspend fun describe(key: TaskKey): TaskScheduleInfo? = null
    }
}
