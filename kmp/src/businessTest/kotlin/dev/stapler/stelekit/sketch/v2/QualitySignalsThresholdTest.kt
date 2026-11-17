// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.AdoptionCounts
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualityCounts
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualityThresholds
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.ThresholdReading
import dev.stapler.stelekit.ui.sketch.v2.migration.SignalsReadoutModel
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Task 6.2.5d: the integer flag-exit thresholds of requirements.md "Flag and legacy-editor exit criteria" and the insufficient-data rule. */
class QualitySignalsThresholdTest {
    private fun crash(sessions: Long, crashes: Long) = QualityThresholds.crashRow(QualityCounts(v2Sessions = sessions, crashes = crashes))

    private fun converge(solves: Long, bad: Long) = QualityThresholds.noConvergenceRow(QualityCounts(solves = solves, noConvergence = bad))

    @Test
    fun crashRow_should_BeInsufficientData_BelowFiftySessions() {
        assertEquals(ThresholdReading.InsufficientData, crash(49, 0))
    }

    @Test
    fun crashRow_should_AllowNoCrash_AtFiftyTo99AndOneAt100To199() {
        assertEquals(ThresholdReading.Met, crash(50, 0))
        assertEquals(ThresholdReading.NotMet, crash(50, 1))
        assertEquals(ThresholdReading.NotMet, crash(99, 1))
        assertEquals(ThresholdReading.Met, crash(100, 1))
        assertEquals(ThresholdReading.NotMet, crash(199, 2))
    }

    @Test
    fun noConvergenceRow_should_AllowFourInTwoHundred() {
        assertEquals(ThresholdReading.InsufficientData, converge(199, 0))
        assertEquals(ThresholdReading.Met, converge(200, 4))
        assertEquals(ThresholdReading.NotMet, converge(200, 5))
        assertEquals(ThresholdReading.Met, converge(250, 5))
    }

    @Test
    fun stopShip_should_FireOnTwoCrashesInTheLast20Sessions() {
        val one = List(20) { it == 3 }
        val two = List(20) { it == 3 || it == 19 }
        assertEquals(false, QualityThresholds.stopShip(one, emptyList()).crashes)
        assertEquals(true, QualityThresholds.stopShip(two, emptyList()).crashes)
        assertEquals(true, QualityThresholds.stopShip(listOf(true, true), emptyList()).crashes, "fewer than 20 sessions still counts")
    }

    @Test
    fun stopShip_should_FireOnElevenNoConvergenceInTheLast100Solves() {
        assertEquals(false, QualityThresholds.stopShip(emptyList(), List(100) { it < 10 }).noConvergence)
        assertEquals(true, QualityThresholds.stopShip(emptyList(), List(100) { it < 11 }).noConvergence)
    }

    @Test
    fun readout_should_ShowReadingsAndStopShipLine() {
        val quiet = SignalsReadoutModel(AdoptionCounts(), QualityCounts(), V2Mode.Off)
        assertEquals(2, quiet.thresholdLines.size)
        assertNull(quiet.stopShipLine)
        val bad = SignalsReadoutModel(AdoptionCounts(), QualityCounts(), V2Mode.On, recentSessions = listOf(true, true))
        assertEquals(SketchStrings.Signals.STOP_SHIP, bad.stopShipLine)
    }
}
