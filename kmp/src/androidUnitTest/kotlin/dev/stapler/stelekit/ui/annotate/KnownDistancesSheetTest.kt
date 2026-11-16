// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ConstraintFitRecord
import dev.stapler.stelekit.model.ConstraintResidual
import dev.stapler.stelekit.model.FitReport
import dev.stapler.stelekit.model.FitTier
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 3.6 sheet criteria (3.6e): empty state, rows, flagging, delete with Undo, hints, legacy, text scale. */
@RunWith(RobolectricTestRunner::class)
class KnownDistancesSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun actions() = KnownDistancesActions(
        onDismiss = { calls += "dismiss" },
        onDrawKnownDistance = { calls += "draw" },
        onUsePaper = { calls += "paper" },
        onEditValue = { calls += "edit:$it" },
        onRename = { id, name -> calls += "rename:$id:$name" },
        onDeleteConfirmed = { calls += "delete:$it" },
        onUndoDelete = { calls += "undo" },
        onUseAnyway = { calls += "anyway:$it" },
        onRemeasure = { calls += "remeasure:$it" },
        onCalibrateLegacy = { calls += "calibrate" },
        onUseBle = { calls += "ble" },
        onMarkRulerTicks = { calls += "ticks" },
    )

    private fun seg(i: Int, label: String? = null, m: Double = 1.0 + i) = CalibrationConstraint(
        id = "c$i", p1 = NormalizedPoint(0.1 + 0.2 * i, 0.1), p2 = NormalizedPoint(0.1 + 0.2 * i, 0.6), distanceMeters = m, label = label,
    )

    private val native = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100, basisVersion = CalibrationBasis.NATIVE_PIXELS)

    private fun present(
        constraints: List<CalibrationConstraint>,
        report: FitReport? = null,
        calibration: Calibration? = native,
        gradient: Boolean = false,
    ) = CalibrationStatePresenter.present(
        calibration = if (constraints.isEmpty() && calibration === native) null else calibration,
        constraints = constraints,
        fit = if (constraints.size >= 3) ConstraintFitRecord(FitTier.SCALE, constraints.size, 1.2) else null,
        report = report,
        gradientTierEnabled = gradient,
    )

    private fun show(p: CalibrationPresentation, affected: Int = 4, footer: String? = null) {
        rule.setContent {
            KnownDistancesContent(presentation = p, displayUnit = MeasurementUnit.CENTIMETERS, actions = actions(), affectedMeasurementCount = affected, footer = footer)
        }
    }

    private val threeRows get() = listOf(seg(0, "Door width", 0.9), seg(1, "Door height", 2.03), seg(2, "Shelf diagonal", 1.2))

    @Test
    fun zeroRows_should_ShowExplanationAndBothActionsNeverAnEmptyList() {
        show(present(emptyList()))
        rule.onNodeWithText(CalibrationStatePresenter.EMPTY_EXPLANATION).assertExists()
        rule.onNodeWithText("Draw a known distance").performClick()
        rule.onNodeWithText("Use a card or sheet of paper").performClick()
        assertEquals(listOf("draw", "paper"), calls)
        rule.onNode(hasTestTag("known_distance_rows")).assertDoesNotExist()
    }

    @Test
    fun zeroRowsWithLegacyCalibration_should_HeadWithTheLegacyMethodLabel() {
        show(present(emptyList(), calibration = Calibration(CalibrationMethod.EXIF_FOCAL, 100.0, 40, basisVersion = CalibrationBasis.NATIVE_PIXELS)))
        rule.onNode(hasTestTag("sheet_heading")).assertExists()
        rule.onNodeWithText("Scale uncertain").assertExists()
        rule.onNodeWithText("Not calibrated", substring = true).assertDoesNotExist()
    }

    @Test
    fun rows_should_ShowNameValueResidualAndAnEditableValue() {
        val report = FitReport(FitTier.SCALE, 3, 1.2, perConstraint = listOf(ConstraintResidual("c0", 0.8), ConstraintResidual("c1", 0.9), ConstraintResidual("c2", 1.2)))
        show(present(threeRows, report))
        rule.onNodeWithText("Door width").assertExists()
        rule.onNodeWithText("90.0 cm", substring = true).performClick()
        assertEquals(listOf("edit:c0"), calls)
        rule.onNodeWithText("0.8% off", substring = true).assertExists()
    }

    @Test
    fun worstRow_should_BeFlaggedWithUseAnyway() {
        val report = FitReport(
            FitTier.SCALE, 3, 1.5,
            perConstraint = listOf(ConstraintResidual("c0", 0.8), ConstraintResidual("c1", 0.9), ConstraintResidual("c2", 6.0)),
        )
        show(present(threeRows, report))
        rule.onNodeWithText("disagrees by 6.0% - re-measure or remove?", substring = true).assertExists()
        rule.onNodeWithText("Use anyway").performClick()
        rule.onNodeWithText("Re-measure").performClick()
        assertEquals(listOf("anyway:c2", "remeasure:c2"), calls)
    }

    @Test
    fun deleteRow_should_ShowAffectedCountAndUndoRestoreListAndFit() {
        show(present(threeRows), affected = 4)
        rule.onAllNodes(hasContentDescription("Delete Door width"))[0].performClick()
        rule.onNodeWithText("You have 4 measurements", substring = true).assertExists()
        assertTrue(calls.isEmpty(), "nothing is deleted before the dialog is confirmed")
        rule.onNodeWithText("Change scale").performClick()
        assertEquals(listOf("delete:c0"), calls)
        rule.onNodeWithText("Undo").performClick()
        assertEquals(listOf("delete:c0", "undo"), calls)
    }

    @Test
    fun deleteRow_should_KeepEverythingWhenTheDialogIsDeclined() {
        show(present(threeRows), affected = 1)
        rule.onAllNodes(hasContentDescription("Delete Door width"))[0].performClick()
        rule.onNodeWithText("Keep current scale").performClick()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun addThirdDistance_should_TakeAtMostFourStepsAndReturnToSheetWithRow() {
        // Steps 1 (tap Draw) is the sheet's; the drag, the typed value and Apply belong to the host. The sheet's own contract is
        // that Draw fires once and that a new constraint shows as a row on re-render.
        show(present(listOf(seg(0, "A"), seg(1, "B"))))
        rule.onNodeWithText("Draw a known distance").performClick()
        assertEquals(listOf("draw"), calls)
    }

    @Test
    fun strings_should_NotContainLensDistortionHeadline() {
        show(present(threeRows))
        val texts = rule.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }
        assertTrue(texts.none { it.contains("lens distortion", ignoreCase = true) }, "found: $texts")
        assertTrue(texts.isNotEmpty())
    }

    @Test
    fun collinearOnly_should_ShowHintAndNotBlockMeasuring() {
        val collinear = listOf(seg(0), seg(1), seg(2)).map { it.copy(p1 = NormalizedPoint(0.4, 0.1 + 0.01 * it.id.last().digitToInt()), p2 = NormalizedPoint(0.45, 0.6)) }
        show(present(collinear, gradient = true))
        rule.onNodeWithText(CalibrationStatePresenter.POSITION_HINT).assertExists()
        rule.onNodeWithText("Draw a known distance").performClick()
        assertEquals(listOf("draw"), calls)
    }

    @Test
    fun legacyBasis_should_DisableAddingAndLinkToCalibrate() {
        val legacy = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100, basisVersion = CalibrationBasis.LEGACY_DECODE_RELATIVE)
        show(present(emptyList(), calibration = legacy))
        rule.onNodeWithText(CalibrationStatePresenter.LEGACY_BASIS_REASON).assertExists()
        rule.onNodeWithText("Draw a known distance").assertIsNotEnabled()
        rule.onNodeWithText("Calibrate").performClick()
        assertEquals(listOf("calibrate"), calls)
    }

    @Test
    fun moreWays_should_ExpandTheExistingOptions() {
        show(present(emptyList()))
        rule.onNodeWithText("Mark ruler ticks", substring = true).assertDoesNotExist()
        rule.onNodeWithText("More ways").performClick()
        rule.onNodeWithText("Mark ruler ticks (most accurate)").performClick()
        rule.onNodeWithText("Use BLE laser").performClick()
        assertEquals(listOf("ticks", "ble"), calls)
    }

    @Test
    fun rows_should_GrowAndWrapNames_At360dpFontScale2AndRtl() {
        val long = "A very long descriptive name for the door frame distance on the left wall"
        val rows = listOf(seg(0, long), seg(1, long + " two"), seg(2, long + " three"))
        val p = present(rows)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    KnownDistancesContent(presentation = p, displayUnit = MeasurementUnit.CENTIMETERS, actions = actions(), affectedMeasurementCount = 0)
                }
            }
        }
        val delete = rule.onAllNodes(hasContentDescription("Delete", substring = true))[0]
        delete.performScrollTo().assertIsDisplayed()
        val b = delete.getUnclippedBoundsInRoot()
        assertTrue(b.height >= 48.dp, "delete target ${b.height}")
        assertTrue(b.left >= 0.dp && b.right <= 360.dp + 0.5.dp, "delete clipped: $b")
        val row = rule.onAllNodes(hasTestTag("known_distance_row"))[0].getUnclippedBoundsInRoot()
        assertTrue(row.height >= 56.dp, "row min height ${row.height}")
        assertTrue(row.right <= 360.dp + 0.5.dp)
    }
}
