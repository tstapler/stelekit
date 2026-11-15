// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.IntSize
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun SemanticsNodeInteraction.editableText(): String? =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

/** Story 3.5 base criteria (3.5b): content, the one preselection rule, preview, apply, dismiss and Distance-only scope. */
@RunWith(RobolectricTestRunner::class)
class MeasurementValueEditorTest {
    @get:Rule
    val rule = createComposeRule()

    private fun ctx(
        calibrated: Boolean = true,
        lastChoice: ValueEditMode? = null,
        others: Int = 3,
        currentScale: Double? = 0.0021,
    ) = ValueEditorContext(
        initialMeters = 0.9,
        displayUnit = MeasurementUnit.CENTIMETERS,
        photoCalibrated = calibrated,
        segmentPixelLength = 430.0,
        currentMetersPerPixel = currentScale,
        otherMeasurementCount = others,
        lastChoice = lastChoice,
    )

    private var applied: ValueEditorResult? = null
    private var dismissed = 0

    private fun show(context: ValueEditorContext) {
        rule.setContent {
            MeasurementValueEditorContent(context = context, onApply = { applied = it }, onDismiss = { dismissed++ })
        }
    }

    private fun option(label: String) = hasText(label, substring = true) and isSelectable()

    @Test
    fun opens_should_ShowPrefilledValueUnitChipAndBothMeaningsWithConsequences() {
        show(ctx())
        assertEquals("90.0 cm", rule.onNode(hasSetTextAction()).editableText())
        rule.onNode(hasContentDescription("Entry unit: cm. This entry only")).assertExists()
        rule.onNodeWithText("Use as known distance").assertExists()
        rule.onNodeWithText("Change label only").assertExists()
        rule.onNodeWithText("3 other measurements will change", substring = true).assertExists()
        rule.onNodeWithText(ValueEditorRules.LABEL_ONLY_CONSEQUENCE).assertExists()
        rule.onNodeWithText("Just relabel").assertDoesNotExist()
    }

    @Test
    fun notCalibrated_should_PreselectKnownDistance() {
        show(ctx(calibrated = false, currentScale = null))
        rule.onNode(option("Use as known distance")).assert(isSelected())
        rule.onNodeWithText("Apply").assertIsEnabled()
    }

    @Test
    fun calibratedFirstEdit_should_PreselectNothingAndDisableApplyUntilChosen() {
        show(ctx())
        rule.onNode(option("Use as known distance")).assert(isNotSelected())
        rule.onNode(option("Change label only")).assert(isNotSelected())
        rule.onNodeWithText("Apply").assertIsNotEnabled()
        rule.onNode(option("Change label only")).performClick()
        rule.onNodeWithText("Apply").assertIsEnabled()
    }

    @Test
    fun calibratedLaterEdit_should_PreselectLastChoiceAndStayChangeable() {
        show(ctx(lastChoice = ValueEditMode.LABEL_ONLY))
        rule.onNode(option("Change label only")).assert(isSelected())
        rule.onNodeWithText("Apply").assertIsEnabled()
        rule.onNode(option("Use as known distance")).performClick()
        rule.onNode(option("Use as known distance")).assert(isSelected())
    }

    @Test
    fun knownDistance_should_PreviewScaleBeforeAndAfter() {
        show(ctx(calibrated = false, currentScale = 0.0021))
        rule.onNode(hasSetTextAction()).performTextReplacement("90")
        // 0.9 m over 430 px is 0.21 cm per px, the same as the current scale shown with 2 decimals.
        rule.onNodeWithText("Scale: 1 px = 0.21 cm -> 0.21 cm", substring = true).assertExists()
    }

    @Test
    fun apply_should_ReturnMetersAndMode() {
        show(ctx(calibrated = false, currentScale = null))
        rule.onNode(hasSetTextAction()).performTextReplacement("150")
        rule.onNodeWithText("Apply").performClick()
        val r = applied ?: error("onApply not called")
        assertEquals(1.5, r.meters, 1e-9)
        assertEquals(ValueEditMode.KNOWN_DISTANCE, r.mode)
    }

    @Test
    fun invalidInput_should_DisableApplyAndKeepTextWithReason() {
        show(ctx(calibrated = false, currentScale = null))
        rule.onNode(hasSetTextAction()).performTextReplacement("0")
        rule.onNodeWithText("Apply").assertIsNotEnabled()
        rule.onNodeWithText(ValueEditorRules.NEEDS_POSITIVE).assertExists()
        rule.onNode(hasSetTextAction()).performTextReplacement("abc")
        rule.onNodeWithText("Apply").assertIsNotEnabled()
        assertEquals("abc", rule.onNode(hasSetTextAction()).editableText())
        assertNull(applied)
    }

    @Test
    fun cancelAndClose_should_DismissWithoutApplying() {
        show(ctx())
        rule.onNodeWithText("Cancel").performClick()
        rule.onNode(hasContentDescription("Close")).performClick()
        assertEquals(2, dismissed)
        assertNull(applied)
    }

    @Test
    fun rules_should_FollowThePreselectionTable() {
        assertEquals(ValueEditMode.KNOWN_DISTANCE, ValueEditorRules.initialChoice(ctx(calibrated = false)))
        assertNull(ValueEditorRules.initialChoice(ctx()))
        assertEquals(ValueEditMode.KNOWN_DISTANCE, ValueEditorRules.initialChoice(ctx(lastChoice = ValueEditMode.KNOWN_DISTANCE)))
    }

    private fun measurement(type: AnnotationType, n: Int) = MeasurementAnnotation(
        uuid = "00000000-0000-4000-8000-00000000000$n",
        imageUuid = "11111111-1111-4111-8111-111111111111",
        annotationType = type,
        normalizedPoints = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.5), NormalizedPoint(0.9, 0.2))
            .take(if (type == AnnotationType.DISTANCE) 2 else 3),
        valueDisplay = "90 cm",
    )

    @Test
    fun editValueAction_should_ExistOnDistanceOnly() {
        val edits = mutableListOf<String>()
        val all = listOf(measurement(AnnotationType.DISTANCE, 1), measurement(AnnotationType.AREA, 2), measurement(AnnotationType.ANGLE, 3))
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                AnnotationSemanticOverlay(all, IntSize(400, 400), onAnnotationSelect = {}, onEditValue = { edits += it })
            }
        }
        fun actions(prefix: String) = rule.onNode(
            SemanticsMatcher("desc starts with $prefix") {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith(prefix) == true
            },
        ).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val distance = actions("Distance measurement").single { it.label == "Edit value" }
        assertTrue(actions("Area measurement").none { it.label == "Edit value" })
        assertTrue(actions("Angle measurement").none { it.label == "Edit value" })
        rule.runOnUiThread { distance.action() }
        assertEquals(listOf(all[0].uuid), edits)
    }
}
