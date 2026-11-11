// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.security.Redactor
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskRunLogTest {
    private val key = TaskKey(TaskKind("git-sync"), TaskScope("g1"))
    private val state = ConstraintState(online = true, isMetered = true, charging = null)

    private fun record(at: Long, result: TaskRunResult, source: RunSource = RunSource.Background, k: TaskKey = key) =
        TaskRunRecord(k, source, scheduledAtMs = null, startedAtMs = at, result = result, constraintState = state)

    @Test
    fun recent_should_keepNewest20NewestFirst_When_25Appended() = runTest {
        val log = SettingsTaskRunLog(MapSettings(), cap = 20)
        for (i in 1..25) log.append(record(i.toLong(), TaskRunResult.Success))
        val recent = log.recent(key)
        assertEquals(20, recent.size)
        assertEquals((25L downTo 6L).toList(), recent.map { it.startedAtMs })
    }

    @Test
    fun append_should_persistAndLogResultAndMeteredState_When_Skipped() = runTest {
        val settings = MapSettings()
        val log = SettingsTaskRunLog(settings)
        log.append(record(100, TaskRunResult.Skipped("metered")))
        val persisted = settings.store.getValue(TaskKeyEncoding.runLogKey(RunSource.Background, key))
        assertTrue("result=Skipped:metered" in persisted && "metered=true" in persisted, persisted)
        assertTrue(LogManager.logs.value.any { "result=Skipped:metered" in it.message && "metered=true" in it.message && "kind=git-sync" in it.message })
        assertEquals(record(100, TaskRunResult.Skipped("metered")), log.recent(key).single())
    }

    @Test
    fun lastSuccessAtMs_should_ignoreLaterSkipsAndFailures_When_OlderSuccessExists() = runTest {
        val log = SettingsTaskRunLog(MapSettings())
        log.append(record(50, TaskRunResult.Success))
        log.append(record(100, TaskRunResult.Skipped("offline")))
        log.append(record(200, TaskRunResult.Failed("boom", retryable = true)))
        assertEquals(50L, log.lastSuccessAtMs(key))
        assertNull(log.lastSuccessAtMs(TaskKey(TaskKind("git-sync"), TaskScope("other"))))
    }

    @Test
    fun recent_should_returnEmptyAndAppendNotThrow_When_SettingsThrow() = runTest {
        val settings = MapSettings()
        val log = SettingsTaskRunLog(settings)
        log.append(record(1, TaskRunResult.Success))
        settings.failReads = true
        assertEquals(emptyList(), log.recent(key))
        assertNull(log.lastSuccessAtMs(key))
        log.append(record(2, TaskRunResult.Success))
        settings.failReads = false
        settings.failWrites = true
        log.append(record(3, TaskRunResult.Success))
    }

    @Test
    fun recent_should_mergeBothSources_When_InterleavedWriters() = runTest {
        val log = SettingsTaskRunLog(MapSettings())
        log.append(record(10, TaskRunResult.Success, RunSource.Background))
        log.append(record(20, TaskRunResult.Success, RunSource.Foreground))
        log.append(record(30, TaskRunResult.Skipped("x"), RunSource.Background))
        log.append(record(40, TaskRunResult.Failed("f"), RunSource.Foreground))
        assertEquals(listOf(40L, 30L, 20L, 10L), log.recent(key).map { it.startedAtMs })
        assertEquals(setOf(RunSource.Background, RunSource.Foreground), log.recent(key).map { it.source }.toSet())
        assertEquals(20L, log.lastSuccessAtMs(key))
    }

    @Test
    fun recent_should_capMergedListAt20_When_BothSourcesFull() = runTest {
        val log = SettingsTaskRunLog(MapSettings(), cap = 20)
        for (i in 1..20) {
            log.append(record(i * 2L, TaskRunResult.Success, RunSource.Background))
            log.append(record(i * 2L + 1, TaskRunResult.Success, RunSource.Foreground))
        }
        val recent = log.recent(key)
        assertEquals(20, recent.size)
        assertEquals((41L downTo 22L).toList(), recent.map { it.startedAtMs })
    }

    @Test
    fun runLogKey_should_neverCollide_When_AnyKindScopeAndSource() = runTest {
        checkAll(Arb.string(0..12), Arb.string(0..12)) { s1, s2 ->
            val a = TaskKey(TaskKind("git-sync"), TaskScope(s1))
            val b = TaskKey(TaskKind("git-sync"), TaskScope(s2))
            for (sa in RunSource.entries) for (sb in RunSource.entries) {
                if (sa != sb || s1 != s2) assertNotEquals(TaskKeyEncoding.runLogKey(sa, a), TaskKeyEncoding.runLogKey(sb, b))
            }
        }
        val kinds = listOf("a", "fg", "git-sync", "fg-a").map { TaskKind(it) }
        val keys = kinds.flatMap { k -> listOf("", "x", "fg_x", "1.x").map { TaskKey(k, TaskScope(it)) } }
        val all = keys.flatMap { k -> RunSource.entries.map { TaskKeyEncoding.runLogKey(it, k) } }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun codec_should_roundTrip_When_FreeTextHasSeparators() = runTest {
        val log = SettingsTaskRunLog(MapSettings())
        checkAll(Arb.string(0..20)) { text ->
            // Credential masking intentionally rewrites URL userinfo / query values; this property is about separators.
            if (Redactor.redactGitMessage(text) != text) return@checkAll
            val r = record(7, TaskRunResult.Failed(text, retryable = true))
            val decoded = TaskRunRecordCodec.decode(TaskRunRecordCodec.encode(r), key)
            assertEquals(r, decoded)
            assertTrue('\n' !in TaskRunRecordCodec.encode(r))
        }
        log.append(record(1, TaskRunResult.Partial("a b%20c\nd")))
        assertEquals(TaskRunResult.Partial("a b%20c\nd"), log.recent(key).single().result)
    }

    @Test
    fun append_should_neverPersistOrLogCredentials_When_FailureTextCarriesUrlUserinfoAndToken() = runTest {
        val settings = MapSettings()
        val log = SettingsTaskRunLog(settings)
        val leaky = "fetch failed for https://user:hunter2pw@example.com/repo.git using ghp_AbCdEf0123456789xyz"
        log.append(record(1, TaskRunResult.Failed(leaky)))
        log.append(record(2, TaskRunResult.Partial(leaky)))
        log.append(record(3, TaskRunResult.Skipped(leaky)))

        val persisted = settings.store.getValue(TaskKeyEncoding.runLogKey(RunSource.Background, key))
        for (secret in listOf("hunter2pw", "user:", "ghp_AbCdEf0123456789xyz")) {
            assertTrue(secret !in persisted, "$secret leaked into the stored run record: $persisted")
            assertTrue(LogManager.logs.value.none { secret in it.message }, "$secret leaked into the log line")
        }
        assertTrue("example.com/repo.git" in persisted, "host stays readable: $persisted")
    }

    @Test
    fun append_should_swallowNonExceptionThrowable_When_SettingsThrowError() = runTest {
        val log = SettingsTaskRunLog(object : dev.stapler.stelekit.platform.Settings by MapSettings() {
            override fun getString(key: String, defaultValue: String): String = throw FakeLinkageError("keystore")
        })
        log.append(record(1, TaskRunResult.Success))
    }

    @Test
    fun taskApplyLine_should_carryEffectiveScheduleAndNetwork_When_Enabled() {
        val config = TaskConfig(true, Schedule.of(60, 10), TaskConstraints(network = NetworkPolicy.Unmetered))
        val effective = EffectiveSchedule(intervalMinutes = 60, jitterMinutes = 10, flexMinutes = 10, offsetMinutes = 4, clamped = false)
        assertEquals(
            "TaskApply key=git-sync.2.g1 enabled=true effectiveInterval=60 flex=10 offset=4 network=unmetered",
            TaskApplyLog.line(key, config, effective),
        )
        assertTrue("effectiveInterval=- flex=- offset=-" in TaskApplyLog.line(key, config.copy(enabled = false), null))
    }

    @Test
    fun append_should_loseNoRecord_When_AppendsRace() = runTest {
        val log = SettingsTaskRunLog(SlowReadSettings(MapSettings(), gapMs = 15), cap = 20)
        withContext(Dispatchers.Default) {
            (1..6).map { i -> async { log.append(record(i.toLong(), TaskRunResult.Success)) } }.awaitAll()
        }
        assertEquals(6, log.recent(key).size, "every concurrent append must be kept")
    }
}

/** Stand-in for a JVM linkage Error (e.g. NoClassDefFoundError): an Error that is not an Exception. */
private class FakeLinkageError(message: String) : Error(message)
