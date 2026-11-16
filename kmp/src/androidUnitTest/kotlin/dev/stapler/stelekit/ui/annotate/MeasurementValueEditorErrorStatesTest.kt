// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.MeasurementValue
import dev.stapler.stelekit.model.NormalizedPoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F13 error states, the edited marker and the entry-unit chip (Task 3.5c, X-67). */
@RunWith(RobolectricTestRunner::class)
class MeasurementValueEditorErrorStatesTest {
    @get:Rule
    val rule = createComposeRule()

    private var applied: ValueEditorResult? = null

    private fun ctx(pixelLength: Double = 430.0, legacy: Boolean = false, scale: Double? = 0.0021, calibrated: Boolean = true) =
        ValueEditorContext(
            initialMeters = 0.9, displayUnit = MeasurementUnit.CENTIMETERS, photoCalibrated = calibrated,
            segmentPixelLength = pixelLength, currentMetersPerPixel = scale, otherMeasurementCount = 2, legacyBasis = legacy,
        )

    private fun show(context: ValueEditorContext, saveFailed: Boolean = false, footer: String? = null) {
        rule.setContent {
            MeasurementValueEditorContent(context, onApply = { applied = it }, onDismiss = {}, saveFailed = saveFailed, solverFooter = footer)
        }
    }

    private fun option(label: String) = hasText(label, substring = true) and isSelectable()

    @Test
    fun saveFailure_should_ShowInlineErrorAndKeepInput() {
        show(ctx(calibrated = false), saveFailed = true)
        rule.onNodeWithText(ValueEditorRules.SAVE_FAILED).assertExists()
        assertEquals("90.0 cm", rule.onNode(hasSetTextAction()).editableText())
        rule.onNodeWithText("Apply").assertIsEnabled()
    }

    @Test
    fun implausibleRatio_should_WarnNonBlockingWithApplyAnyway() {
        show(ctx(calibrated = false))
        rule.onNode(hasSetTextAction()).performTextReplacement("360") // 4x the current scale
        rule.onNodeWithText("This is 4x the current scale. Check the unit.").assertExists()
        rule.onNodeWithText("Apply anyway").assertIsEnabled().performClick()
        assertEquals(3.6, applied?.meters ?: error("not applied"), 1e-9)
    }

    @Test
    fun implausibleRatio_should_NotWarnInLabelOnlyMode() {
        show(ctx())
        rule.onNode(hasSetTextAction()).performTextReplacement("360")
        rule.onNode(option("Change label only")).performClick()
        rule.onNodeWithText("This is", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Apply").assertIsEnabled()
    }

    @Test
    fun smallRatio_should_BeWorded() {
        assertEquals("This is 1/4 of the current scale. Check the unit.", ValueEditorRules.implausibleScaleMessage(0.25))
        assertNull(ValueEditorRules.implausibleScaleMessage(2.9))
    }

    @Test
    fun zeroLengthSegment_should_DisableKnownDistanceButAllowLabelOnly() {
        show(ctx(pixelLength = 0.0))
        rule.onNodeWithText(ValueEditorRules.ZERO_LENGTH_REASON).assertExists()
        rule.onNode(option("Use as known distance")).assertIsNotEnabled()
        rule.onNode(option("Change label only")).assertIsEnabled()
        rule.onNodeWithText("Apply").assertIsEnabled().performClick()
        assertEquals(ValueEditMode.LABEL_ONLY, applied?.mode)
    }

    @Test
    fun legacyBasis_should_DisableKnownDistanceWithReason() {
        show(ctx(legacy = true))
        rule.onNodeWithText(CalibrationStatePresenter.LEGACY_BASIS_REASON).assertExists()
        rule.onNode(option("Use as known distance")).assertIsNotEnabled()
    }

    @Test
    fun solverFailure_should_ShowOneFooterLine() {
        show(ctx(), footer = "Scale only: the distances are in a line")
        rule.onNodeWithText("Scale only: the distances are in a line").assertExists()
    }

    @Test
    fun editedValue_should_ShowPencilGlyphAndAccessibleEdited() {
        val m = MeasurementAnnotation(
            uuid = "00000000-0000-4000-8000-000000000001", imageUuid = "11111111-1111-4111-8111-111111111111",
            annotationType = AnnotationType.DISTANCE, normalizedPoints = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.5)),
            valueDisplay = "90 cm",
        )
        assertEquals("90 cm", m.valueLabelWithMarker())
        val edited = m.withValue(MeasurementValue.Overridden(0.9, 0.8))
        assertTrue(edited.isValueEdited())
        assertEquals("✎ 90 cm", edited.valueLabelWithMarker())
        assertTrue(!m.withValue(MeasurementValue.Computed(0.8)).isValueEdited())
    }

    @Test
    fun editedValue_should_AddEditedToTalkBackName() {
        val edited = MeasurementAnnotation(
            uuid = "00000000-0000-4000-8000-000000000001", imageUuid = "11111111-1111-4111-8111-111111111111",
            annotationType = AnnotationType.DISTANCE, normalizedPoints = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.5)),
            valueDisplay = "90 cm",
        ).withValue(MeasurementValue.Overridden(0.9, 0.8))
        rule.setContent {
            AnnotationSemanticOverlay(listOf(edited), androidx.compose.ui.unit.IntSize(400, 400), onAnnotationSelect = {})
        }
        rule.onNode(hasContentDescription("Distance measurement, 90 cm (edited)")).assertExists()
    }

    @Test
    fun entryUnitChip_should_SetEntryUnitOnlyAndNeverChangeDisplayUnitOrStoredMetres() {
        show(ctx(calibrated = false, scale = null))
        rule.onNode(hasContentDescription("Entry unit: cm. This entry only")).performClick()
        rule.onNodeWithText("Feet (ft)").performClick()
        rule.onNode(hasContentDescription("Entry unit: ft. This entry only")).assertExists()
        rule.onNode(hasSetTextAction()).performTextReplacement("2.5")
        // The echo is in the display unit (cm), which the chip never changes.
        rule.onNodeWithText("Reads as 76.2 cm").assertExists()
        rule.onNodeWithText("Apply").performClick()
        val r = applied ?: error("not applied")
        assertEquals(0.762, r.meters, 1e-9)
        assertEquals(MeasurementUnit.FEET, r.entryUnit)
    }

    @Test
    fun typedUnit_should_WinOverChip() {
        show(ctx(calibrated = false, scale = null))
        rule.onNode(hasSetTextAction()).performTextReplacement("5'3\"")
        rule.onNodeWithText("Reads as 160.02 cm").assertExists()
    }
}
