// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.JitterPreset
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.NetworkPolicyCopy
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.SchedulerCapabilities
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskOverrides
import dev.stapler.stelekit.jobs.schedule.TaskScheduleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 4.1.1b/g: pure copy, presets and summaries behind the per-graph, per-kind Settings UI. */
class ScheduledSyncPresentationTest {
    private val android = SchedulerCapabilities(
        minIntervalMinutes = 15, minFlexMinutes = 5, supportsUnmetered = true, supportsCharging = true,
        supportsJitter = true, runsWhenClosed = true, schedulesWork = true,
    )
    private val desktop = SchedulerCapabilities(
        minIntervalMinutes = 1, minFlexMinutes = 0, supportsUnmetered = false, supportsCharging = false,
        supportsJitter = true, runsWhenClosed = false, schedulesWork = true,
    )
    private val base = TaskConfig(enabled = false, schedule = Schedule.of(60, 6), constraints = TaskConstraints())

    private val intervals = listOf(5, 15, 30, 60, 120, 360, 1_440)

    @Test
    fun presetOf_should_roundTripMinutesFor_When_AnyPresetAndIntervalListed() {
        for (interval in intervals) {
            for (preset in JitterPreset.entries) {
                val schedule = Schedule.of(interval, preset.minutesFor(interval))
                assertEquals(preset, JitterPreset.presetOf(schedule), "interval=$interval preset=$preset")
            }
        }
    }

    @Test
    fun intervalPresets_should_disableFiveMinutes_When_CapabilityMinIs15() {
        assertEquals(listOf(15, 30, 60, 120, 360, 1_440), scheduledIntervalPresets(android))
        assertTrue(showsMinIntervalHelper(android))
        assertEquals("Android runs at most every 15 minutes.", ANDROID_MIN_INTERVAL_HELPER)
    }

    @Test
    fun intervalPresets_should_includeFiveMinutes_When_DesktopCapabilities() {
        assertEquals(listOf(5, 15, 30, 60, 120, 360, 1_440), scheduledIntervalPresets(desktop))
        assertFalse(showsMinIntervalHelper(desktop))
    }

    @Test
    fun jitterMinutes_should_recomputeFromPreset_When_IntervalChangesFrom60To120() {
        val at60 = Schedule.of(60, 6)
        assertEquals(JitterPreset.ALittle, JitterPreset.presetOf(at60))
        val at120 = scheduleForInterval(at60, 120)
        assertEquals(Schedule.of(120, 12), at120)
        val info = TaskScheduleInfo(120, false, null, minGapMinutes = 120, maxGapMinutes = 132, appliesAfterNextRun = false)
        assertEquals("Runs 120-132 min apart", spreadSummary(info, at120, desktop))
    }

    @Test
    fun scheduleForSpread_should_mapPresetsToPercentOfInterval_When_IntervalIs60() {
        val s = Schedule.of(60, 0)
        assertEquals(0, scheduleForSpread(s, JitterPreset.Off).jitterMinutes)
        assertEquals(6, scheduleForSpread(s, JitterPreset.ALittle).jitterMinutes)
        assertEquals(15, scheduleForSpread(s, JitterPreset.ALot).jitterMinutes)
    }

    @Test
    fun spreadSummary_should_deriveFromDescribeGapFields_When_DesktopOrWorkManagerAdapter() {
        val schedule = Schedule.of(60, 6)
        val desktopInfo = TaskScheduleInfo(60, false, null, 60, 66, appliesAfterNextRun = false)
        assertEquals("Runs 60-66 min apart", spreadSummary(desktopInfo, schedule, desktop))
        val androidInfo = TaskScheduleInfo(60, false, null, 54, 60, appliesAfterNextRun = true)
        assertEquals(
            "About every hour; start times vary by up to 6 min. Takes effect after the next run",
            spreadSummary(androidInfo, schedule, android),
        )
        assertNull(spreadSummary(null, schedule, desktop))
        assertNull(spreadSummary(desktopInfo, Schedule.of(60, 0), desktop))
    }

    @Test
    fun clampSummary_should_sayRunsEvery15_When_RequestedBelowFloor() {
        assertEquals("Runs every 15 min on this device", clampSummary(5, android))
        assertNull(clampSummary(15, android))
        assertNull(clampSummary(5, desktop))
    }

    @Test
    fun networkLabels_should_beExactAndNeverContainWifiOnly_When_AllLocalizedStringsScanned() {
        assertEquals("Any network", networkLabel(NetworkPolicy.Any))
        assertEquals("Unmetered networks only", networkLabel(NetworkPolicy.Unmetered))
        assertEquals(NetworkPolicyCopy.UNMETERED_ONLY_LABEL, NETWORK_UNMETERED_LABEL)
        val scanned = listOf(
            NETWORK_ANY_LABEL, NETWORK_UNMETERED_LABEL, NetworkPolicyCopy.UNMETERED_ONLY_HELPER,
            NetworkPolicyCopy.WAITING_FOR_UNMETERED, UNMETERED_DESKTOP_REASON, ANDROID_MIN_INTERVAL_HELPER,
        )
        scanned.forEach { assertFalse(it.contains("Wi-Fi only", ignoreCase = true), it) }
    }

    @Test
    fun unmetered_should_beSelectableOnlyWhereSupported_When_CapabilitiesVary() {
        assertTrue(isUnmeteredSelectable(android))
        assertFalse(isUnmeteredSelectable(desktop))
        assertEquals(
            "Not available on desktop: SteleKit can't tell if a network is metered",
            UNMETERED_DESKTOP_REASON,
        )
    }

    @Test
    fun customSummary_should_listOnlyDifferingValues_When_OneTwoOrThreeDiffer() {
        val unmetered = TaskConstraints(network = NetworkPolicy.Unmetered)
        assertEquals("Custom: every 6 h", customSummary(TaskOverrides(Schedule.of(360, 36), TaskConstraints()), base))
        assertEquals(
            "Custom: every 2 h, unmetered networks only",
            customSummary(TaskOverrides(Schedule.of(120, 12), unmetered), base),
        )
        assertEquals("Custom: 3 changes", customSummary(TaskOverrides(Schedule.of(360, 90), unmetered), base))
        assertEquals("Custom: spread out a lot", customSummary(TaskOverrides(Schedule.of(60, 15), TaskConstraints()), base))
        assertEquals("Custom", customSummary(TaskOverrides(base.schedule, base.constraints), base))
    }

    @Test
    fun customSummary_should_countExactlyTheDifferingDimensions_When_AllCombinationsTried() {
        for (interval in listOf(60, 120, 360)) {
            for (network in NetworkPolicy.entries) {
                for (preset in JitterPreset.entries) {
                    val overrides = TaskOverrides(
                        Schedule.of(interval, preset.minutesFor(interval)),
                        TaskConstraints(network = network),
                    )
                    val differing = listOf(
                        interval != 60,
                        network != NetworkPolicy.Any,
                        preset != JitterPreset.ALittle,
                    ).count { it }
                    val text = customSummary(overrides, base)
                    when {
                        differing == 0 -> assertEquals("Custom", text)
                        differing > 2 -> assertEquals("Custom: $differing changes", text)
                        else -> assertEquals(differing, text.removePrefix("Custom: ").split(", ").size, text)
                    }
                }
            }
        }
    }

    @Test
    fun graphRowSummary_should_sayUsingDefaultOnlyWithoutOverrides_When_RowsBuilt() {
        assertEquals("Using default", graphRowSummary(null, base))
        assertTrue(graphRowSummary(TaskOverrides(Schedule.of(360, 36), TaskConstraints()), base).startsWith("Custom: "))
    }

    @Test
    fun graphRowDescription_should_carryFullUntruncatedText_When_OnOrOff() {
        val config = TaskConfig(true, Schedule.of(60, 6), TaskConstraints(network = NetworkPolicy.Unmetered))
        assertEquals(
            "Git sync, Work notes, on, about every hour, unmetered networks only, Using default",
            graphRowDescription("Git sync", "Work notes", config, enabled = true, summary = "Using default"),
        )
        assertEquals(
            "Git sync, Archive, off, Using default",
            graphRowDescription("Git sync", "Archive", config, enabled = false, summary = "Using default"),
        )
    }

    @Test
    fun intervalPhrases_should_readNaturally_When_MinutesHoursAndDays() {
        assertEquals("15 min", intervalLabel(15))
        assertEquals("1 hour", intervalLabel(60))
        assertEquals("2 hours", intervalLabel(120))
        assertEquals("Daily", intervalLabel(1_440))
        assertEquals("about every hour", aboutEveryPhrase(60))
        assertEquals("about every 15 min", aboutEveryPhrase(15))
        assertEquals("every 6 h", everyPhrase(360))
    }

    @Test
    fun firstCheck_should_useAboutAndNeverClockTime_When_Rendered() {
        val text = firstCheckText(15)
        assertEquals("First check in about 15 min", text)
        assertFalse(Regex("\\d{1,2}:\\d{2}").containsMatchIn(text))
    }

    @Test
    fun kindSummary_should_beOneLine_When_ManyGraphs() {
        assertEquals(
            "About every hour - Any network - on for 2 of 3 graphs",
            kindSummary(TaskConfig(false, Schedule.of(60, 6), TaskConstraints()), 2, 3),
        )
    }

    @Test
    fun lateStatus_should_neverBeAllowed_When_DesktopCapabilities() {
        assertFalse(lateStatusAllowed(desktop))
        assertTrue(lateStatusAllowed(android))
    }
}
