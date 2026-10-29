// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.platform.SCHEDULED_SYNC_ENABLED_KEY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Picker model and status-line rules for Settings > Scheduled sync (UT-144, UW-17, UW-66). */
class ScheduledSyncStatusTest {

    private val minute = 60_000L

    @Test
    fun `effective interval should be at least 15 and disable 5 when Android`() {
        val androidFloor = 15
        assertEquals(15, effectiveScheduledIntervalMinutes(5, androidFloor))
        assertEquals(30, effectiveScheduledIntervalMinutes(30, androidFloor))
        assertFalse(isScheduledIntervalSelectable(5, androidFloor))
        assertTrue(listOf(15, 30, 60).all { isScheduledIntervalSelectable(it, androidFloor) })
        assertTrue(isScheduledIntervalClamped(5, androidFloor))
        assertFalse(isScheduledIntervalClamped(15, androidFloor))
    }

    @Test
    fun `every option should be selectable and nothing clamped when desktop`() {
        assertTrue(SCHEDULED_SYNC_INTERVAL_OPTIONS.all { isScheduledIntervalSelectable(it, 1) })
        assertEquals(5, effectiveScheduledIntervalMinutes(5, 1))
        assertEquals(listOf(5, 15, 30, 60), SCHEDULED_SYNC_INTERVAL_OPTIONS)
    }

    @Test
    fun `line should read last checked 3 min ago and next in 12 when a tick succeeded 3 minutes ago at 15`() {
        val now = 10_000_000L
        val last = now - 3 * minute
        val status = ScheduledSyncStatus(running = true, lastAttemptAtMs = last, lastSuccessAtMs = last, nextAtMs = last + 15 * minute)
        val line = scheduledSyncLine(enabled = true, status = status, nowMs = now, stoppingVisible = false)
        assertEquals(ScheduledSyncLine.Checked(lastCheckedMinutesAgo = 3, nextInMinutes = 12), line)
    }

    @Test
    fun `line should be not scheduled when off and stopping only after the delay while a tick runs`() {
        val running = ScheduledSyncStatus(tickInFlight = true)
        assertEquals(ScheduledSyncLine.NotScheduled, scheduledSyncLine(false, running, 0, stoppingVisible = false))
        assertEquals(ScheduledSyncLine.Stopping, scheduledSyncLine(false, running, 0, stoppingVisible = true))
        assertEquals(ScheduledSyncLine.NotScheduled, scheduledSyncLine(false, ScheduledSyncStatus(), 0, stoppingVisible = true))
    }

    @Test
    fun `line should report busy offline paused and never checked`() {
        assertEquals(ScheduledSyncLine.CheckInProgress, scheduledSyncLine(true, ScheduledSyncStatus(lastSkip = SkipReason.Busy), 0, false))
        assertEquals(ScheduledSyncLine.Offline, scheduledSyncLine(true, ScheduledSyncStatus(lastSkip = SkipReason.Offline), 0, false))
        assertEquals(ScheduledSyncLine.NeverChecked, scheduledSyncLine(true, ScheduledSyncStatus(), 0, false))
        val paused = ScheduledSyncStatus(paused = true, consecutiveFailures = 3, lastFailure = "auth")
        assertEquals(ScheduledSyncLine.Paused(3, "auth"), scheduledSyncLine(true, paused, 0, false))
    }

    @Test
    fun `scheduled sync flag key should stay stable`() {
        assertEquals("scheduled_sync_enabled", SCHEDULED_SYNC_ENABLED_KEY)
    }
}
