// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ConstraintFitRecord
import dev.stapler.stelekit.model.FitReport
import dev.stapler.stelekit.model.FitTier
import dev.stapler.stelekit.model.NormalizedPoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** X-31 / X-32 (UX-31, UX-32): the pill is one 48 dp target that opens the sheet, with text and an icon per rung. */
@RunWith(RobolectricTestRunner::class)
class CalibrationPillTest {
    @get:Rule
    val rule = createComposeRule()

    private fun constraints(n: Int) = (0 until n).map { i ->
        CalibrationConstraint(
            id = "c$i", p1 = NormalizedPoint(0.1 + 0.1 * i, 0.1), p2 = NormalizedPoint(0.1 + 0.1 * i, 0.6), distanceMeters = 1.0 + i,
        )
    }

    private fun pill(n: Int, report: FitReport? = null): PillState {
        val fit = if (n >= 3) ConstraintFitRecord(FitTier.SCALE, n, 1.2) else null
        val cal = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100, basisVersion = CalibrationBasis.NATIVE_PIXELS)
        return CalibrationStatePresenter.present(if (n == 0) null else cal, constraints(n), fit, report).pill
    }

    private fun showPill(state: PillState, onClick: () -> Unit = {}) {
        rule.setContent { CalibrationPill(state = state, onClick = onClick) }
    }

    @Test
    fun pill_should_Be48dpAndOpenKnownDistancesSheetInOneTap_ForEachCount() {
        var taps = 0
        var state by mutableStateOf(pill(0))
        rule.setContent { CalibrationPill(state = state, onClick = { taps++ }) }
        for (n in listOf(0, 1, 2, 3)) {
            taps = 0
            rule.runOnIdle { state = pill(n, if (n == 2) FitReport(FitTier.SCALE, 2, 1.0, agreementPct = 2.0) else null) }
            rule.onNode(hasTestTag("calibration_pill")).performClick()
            assertEquals(1, taps, "one tap opens the sheet for n=$n")
            val h = rule.onNode(hasTestTag("calibration_pill")).getUnclippedBoundsInRoot().height
            assertTrue(h >= 48.dp, "pill n=$n is $h tall")
        }
    }

    @Test
    fun pillText_should_MatchLadderWithIconForEachCount() {
        val cases = listOf(
            0 to ("Not calibrated - add a known distance" to PillIcon.NONE),
            1 to ("Scale from 1 distance (approx.)" to PillIcon.INFO),
            3 to ("Calibrated from 3 distances, +/-1.2%" to PillIcon.CHECK),
        )
        var state by mutableStateOf(pill(0))
        rule.setContent { CalibrationPill(state = state, onClick = {}) }
        for ((n, expected) in cases) {
            rule.runOnIdle { state = pill(n) }
            assertEquals(expected.first, state.text)
            assertEquals(expected.second, state.icon)
            rule.onNodeWithText(expected.first, substring = true).assertExists()
            rule.onNode(hasTestTag("pill_icon_${expected.second}"), useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun oneDistance_should_ShowAddAnotherThatAlsoOpensTheSheet() {
        var taps = 0
        showPill(pill(1)) { taps++ }
        rule.onNodeWithText("Add another").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun legacyMethod_should_KeepItsLabelAndNeverReadNotCalibrated() {
        val cal = Calibration(CalibrationMethod.EXIF_FOCAL, 100.0, 40, basisVersion = CalibrationBasis.NATIVE_PIXELS)
        val state = CalibrationStatePresenter.present(cal, emptyList(), null).pill
        showPill(state)
        rule.onNodeWithText("Scale uncertain", substring = true).assertExists()
        rule.onNodeWithText("Not calibrated", substring = true).assertDoesNotExist()
    }

    @Test
    fun banner_should_OpenTheSameSheetAsASingle48dpTarget_WithoutContradictingThePill() {
        var opened = 0
        rule.setContent {
            CalibrationNudgeBanner(isFirstUse = false, onCalibrateClick = {}, onOpenKnownDistances = { opened++ })
        }
        val banner = rule.onNode(hasTestTag("calibration_banner"))
        assertTrue(banner.getUnclippedBoundsInRoot().height >= 48.dp)
        banner.performClick()
        assertEquals(1, opened)
        rule.onNodeWithText(CalibrationStatePresenter.NOT_CALIBRATED, substring = true).assertExists()
    }
}
