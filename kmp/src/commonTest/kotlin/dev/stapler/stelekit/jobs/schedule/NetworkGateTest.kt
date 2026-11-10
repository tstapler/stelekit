// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class NetworkGateTest {
    private val proceed = NetworkGateResult.Proceed
    private fun skip(r: NetworkSkipReason) = NetworkGateResult.Skip(r)

    @Test
    fun evaluate_should_proceedOnlyWhenOnlineAndNotMetered_When_PolicyUnmetered() {
        val u = NetworkPolicy.Unmetered
        val a = NetworkPolicy.Any
        // (policy, online, isMetered) -> expected: the full 12-row table
        val table = listOf(
            Triple(u, true, false) to proceed,
            Triple(u, true, true) to skip(NetworkSkipReason.MeteredNetwork),
            Triple(u, true, null) to skip(NetworkSkipReason.UnknownNetwork),
            Triple(u, false, false) to skip(NetworkSkipReason.Offline),
            Triple(u, false, true) to skip(NetworkSkipReason.Offline),
            Triple(u, false, null) to skip(NetworkSkipReason.Offline),
            Triple(a, true, false) to proceed,
            Triple(a, true, true) to proceed,
            Triple(a, true, null) to proceed,
            Triple(a, false, false) to skip(NetworkSkipReason.Offline),
            Triple(a, false, true) to skip(NetworkSkipReason.Offline),
            Triple(a, false, null) to skip(NetworkSkipReason.Offline),
        )
        assertEquals(12, table.size)
        table.forEach { (input, expected) ->
            assertEquals(expected, NetworkGate.evaluate(input.first, input.second, input.third), "$input")
        }
    }

    @Test
    fun evaluate_should_skipUnknownNetwork_When_UnmeteredAndMeteredIsNull() {
        assertEquals(skip(NetworkSkipReason.UnknownNetwork), NetworkGate.evaluate(NetworkPolicy.Unmetered, true, null))
    }

    @Test
    fun evaluate_should_neverProceedOffline_When_AnyInputs() = runTest {
        checkAll(Arb.enum<NetworkPolicy>(), Arb.boolean(), Arb.of<Boolean?>(true, false, null)) { p, online, m ->
            if (NetworkGate.evaluate(p, online, m) == proceed) assertTrue(online)
        }
    }

    @Test
    fun evaluateMetered_should_equalEvaluateWithOnlineTrue_When_CalledByLfs() {
        listOf<Boolean?>(true, false, null).forEach { m ->
            NetworkPolicy.entries.forEach { p ->
                assertEquals(NetworkGate.evaluate(p, online = true, isMetered = m), NetworkGate.evaluateMetered(p, m))
            }
        }
        assertEquals(skip(NetworkSkipReason.MeteredNetwork), NetworkGate.evaluateMetered(NetworkPolicy.Unmetered, true))
        assertEquals(proceed, NetworkGate.evaluateMetered(NetworkPolicy.Unmetered, false))
        assertEquals(skip(NetworkSkipReason.UnknownNetwork), NetworkGate.evaluateMetered(NetworkPolicy.Unmetered, null))
        listOf<Boolean?>(true, false, null).forEach { assertEquals(proceed, NetworkGate.evaluateMetered(NetworkPolicy.Any, it)) }
    }

    @Test
    fun skip_should_mapToSkippedNeverFailed_When_NetworkSkipReasonMapped() {
        assertEquals(TaskRunResult.Skipped("metered"), skip(NetworkSkipReason.MeteredNetwork).toTaskRunResult())
        assertEquals(TaskRunResult.Skipped("offline"), skip(NetworkSkipReason.Offline).toTaskRunResult())
        assertEquals(TaskRunResult.Skipped("unknown-network"), skip(NetworkSkipReason.UnknownNetwork).toTaskRunResult())
        NetworkSkipReason.all.forEach { assertIs<TaskRunResult.Skipped>(skip(it).toTaskRunResult()) }
    }
}
