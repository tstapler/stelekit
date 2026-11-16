// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** U-E1-12: only the origins that call `sync()` carry a trigger, and only the user's own presses may lift guards. */
class SyncOriginTriggerMappingTest {
    @Test
    fun toTrigger_should_MapFiveSyncCallingOrigins_AndLeaveOthersUnmapped() {
        val manual = setOf(SyncOrigin.UiPress, SyncOrigin.FollowUp)
        val automatic = setOf(SyncOrigin.Scheduled, SyncOrigin.Worker, SyncOrigin.Resume, SyncOrigin.RateLimitRetry)

        SyncOrigin.entries.forEach { origin ->
            val expected = when (origin) {
                in manual -> SyncTrigger.Manual
                in automatic -> SyncTrigger.Automatic
                else -> null
            }
            assertEquals(expected, origin.toTrigger(), "trigger for $origin")
        }
        assertNull(SyncOrigin.Quiesce.toTrigger())
        assertEquals(6, SyncOrigin.entries.count { it.toTrigger() != null })
    }
}
