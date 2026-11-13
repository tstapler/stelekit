// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskConfigCodecTest {
    private val unmetered = TaskConstraints(NetworkPolicy.Unmetered)

    @Test
    fun decodeEntry_should_roundTrip_When_EntryHasOverrides() {
        val e = TaskEntry(true, TaskOverrides(Schedule.of(60, 10), unmetered))
        assertEquals(e, TaskConfigCodec.decodeEntry(TaskConfigCodec.encodeEntry(e)))
        assertTrue(TaskConfigCodec.encodeEntry(e).startsWith("v1|"))
    }

    @Test
    fun decodeEntry_should_returnNull_When_StoredStringIsGarbage() {
        assertNull(TaskConfigCodec.decodeEntry("garbage"))
        assertNull(TaskConfigCodec.decodeEntry("v2|1|-"))
        assertNull(TaskConfigCodec.decodeEntry("v1|1|O|x,y|any,0,0"))
    }

    @Test
    fun decodeEntry_should_roundTripNullScheduleOverride_When_OneShotKind() {
        val e = TaskEntry(false, TaskOverrides(null, TaskConstraints(NetworkPolicy.Any)))
        assertEquals(e, TaskConfigCodec.decodeEntry(TaskConfigCodec.encodeEntry(e)))
    }

    @Test
    fun decodeDefaults_should_roundTrip_When_DefaultsStored() {
        val d = TaskDefaults(Schedule.of(120, 0), unmetered)
        assertEquals(d, TaskConfigCodec.decodeDefaults(TaskConfigCodec.encodeDefaults(d)))
        val oneShot = TaskDefaults(null, TaskConstraints())
        assertEquals(oneShot, TaskConfigCodec.decodeDefaults(TaskConfigCodec.encodeDefaults(oneShot)))
    }

    @Test
    fun settingsKeys_should_beDisjointFamilies_When_SameKey() {
        val key = TaskKey(TaskKind("git-sync"), TaskScope("g1"))
        val keys = listOf(
            TaskSettingsKeys.entry(key), TaskSettingsKeys.migrated(key), TaskSettingsKeys.applied(key),
            TaskSettingsKeys.defaults(key.kind),
        )
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.map { it.substringBefore('_', "") + "_" + it.substringAfter('_').substringBefore('_') }.toSet().size == keys.size)
    }

    @Test
    fun codec_should_roundTripAnyEntry_When_Generated() = runTest {
        checkAll(Arb.boolean(), Arb.boolean(), Arb.int(1..10_000), Arb.int(0..10_000), Arb.boolean(), Arb.boolean(), Arb.boolean()) { enabled, hasOv, i, j, unm, chg, bat ->
            val ov = if (hasOv) TaskOverrides(Schedule.coerce(i, j), TaskConstraints(if (unm) NetworkPolicy.Unmetered else NetworkPolicy.Any, chg, bat)) else null
            val e = TaskEntry(enabled, ov)
            assertEquals(e, TaskConfigCodec.decodeEntry(TaskConfigCodec.encodeEntry(e)))
        }
    }

    @Test
    fun decodeEntry_should_neverThrow_When_RandomGarbage() = runTest {
        checkAll(Arb.string(0..40)) { s ->
            val decoded = TaskConfigCodec.decodeEntry(s)
            if (decoded != null) assertTrue(s.startsWith("v1|"))
        }
    }
}
