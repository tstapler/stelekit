// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.calibration.CalibrationFailure
import dev.stapler.stelekit.calibration.GoldenSceneGenerator
import dev.stapler.stelekit.calibration.SceneFamily
import dev.stapler.stelekit.calibration.TierRequest
import dev.stapler.stelekit.calibration.ConstraintTuning
import dev.stapler.stelekit.calibration.constraint
import dev.stapler.stelekit.calibration.describe
import dev.stapler.stelekit.calibration.toConstraint
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.FitTier
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.calibration.LensMode
import dev.stapler.stelekit.model.CalibrationTick
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.PlaneKind
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.7: re-fit integration, history and undo, precedence-table wiring and safe fallback, through the real view model. */
class AnnotationEditorViewModelConstraintTest {
    private class MapSettings(vararg initial: Pair<String, Boolean>) : Settings {
        private val values = mutableMapOf<String, Any>().apply { initial.forEach { put(it.first, it.second) } }
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun containsKey(key: String) = key in values
    }

    private val image = ImageAnnotation(
        uuid = "img-constraint", blockUuid = "blk", pageUuid = "page", graphPath = "/test", filePath = "/test/i.jpg",
    )

    /** 5000 px per metre on a 4000 x 3000 photo: a 1000 px line is 0.2 m. */
    private val c1 = constraint("c1", 0.10, 0.20, 0.35, 0.20, 0.20)
    private val c2 = constraint("c2", 0.50, 0.50, 0.50, 0.70, 0.12)
    private val c3 = constraint("c3", 0.60, 0.80, 0.85, 0.80, 0.20)
    private val c4 = constraint("c4", 0.05, 0.60, 0.05, 0.85, 0.15)

    private fun vm(settings: Settings? = null, width: Double = 4000.0, height: Double = 3000.0) = AnnotationEditorViewModel(
        measurementRepository = InMemoryMeasurementAnnotationRepository(),
        imageWidthPx = width,
        imageHeightPx = height,
        settings = settings,
    ).also { it.initialize(image) }

    /** Native-basis 5000 px/m reference; a legacy-basis photo cannot take known distances. */
    private fun AnnotationEditorViewModel.calibrate() {
        confirmMultiPointCalibration(listOf(CalibrationTick(0.1, 0.9, 0.0), CalibrationTick(0.475, 0.9, 0.3)), LensMode.Off)
    }

    private fun AnnotationEditorViewModel.draw(x: Double, y: Double = 0.5): MeasurementAnnotation {
        selectTool(AnnotationTool.DISTANCE)
        addPoint(NormalizedPoint(x, y))
        addPoint(NormalizedPoint(x + 0.25, y))
        return state.value.committedAnnotations.last { it.annotationType == AnnotationType.DISTANCE }
    }

    private val AnnotationEditorViewModel.geometry get() = state.value.calibration?.geometry
    private val AnnotationEditorViewModel.calibration get() = assertNotNull(state.value.calibration)

    @Test
    fun addingAThirdConstraint_should_RefitStoreTheReportAndLogTheTier() {
        val vm = vm().also { it.calibrate() }
        vm.confirmConstraintCalibration(listOf(c1, c2))
        vm.confirmConstraintCalibration(listOf(c1, c2, c3))

        assertEquals(3, vm.geometry?.constraints?.size)
        assertEquals(FitTier.SCALE, vm.geometry?.fit?.tier)
        assertEquals(3, vm.geometry?.fit?.n)
        assertEquals(3, vm.constraintFitResult.value?.report?.n)
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, vm.calibration.method)
        assertEquals(5000.0, vm.calibration.pixelsPerMeter, 5000.0 * 0.005)
        assertTrue(LogManager.logs.value.any { it.message.contains("calibration fit tier=SCALE n=3") })
        vm.close()
    }

    @Test
    fun calibrationHistory_should_ReturnAllConstraintsAndThePreviousFit_When_AConstraintWasDeleted() {
        val vm = vm().also { it.calibrate() }
        vm.confirmConstraintCalibration(listOf(c1, c2, c3))
        val withThree = vm.calibration
        vm.confirmConstraintCalibration(listOf(c1, c2))
        assertEquals(2, vm.geometry?.constraints?.size)
        assertTrue(vm.canUndoCalibration.value)

        vm.undoCalibration()

        assertEquals(withThree, vm.calibration)
        assertEquals(listOf(c1, c2, c3), vm.geometry?.constraints)
        assertEquals(3, vm.geometry?.fit?.n)
        vm.close()
    }

    @Test
    fun deletingEveryConstraint_should_RestoreTheReplacedBasis() {
        val vm = vm().also { it.calibrate() }
        val original = vm.calibration
        vm.confirmConstraintCalibration(listOf(c1, c2))
        assertEquals(original.pixelsPerMeter, vm.geometry?.fit?.priorBasis?.pixelsPerMeter)

        vm.confirmConstraintCalibration(emptyList())

        assertEquals(original.pixelsPerMeter, vm.calibration.pixelsPerMeter)
        assertNull(vm.geometry?.fit)
        assertTrue(vm.geometry?.constraints.isNullOrEmpty())
        vm.close()
    }

    @Test
    fun newLineCalibration_should_ClearConstraintsFitAndPlaneButKeepTheHeightOffset() {
        val vm = vm().also { it.calibrate() }
        vm.setGeometry(GeometryCalibration(0.01))
        vm.confirmConstraintCalibration(listOf(c1, c2, c3))
        assertNotNull(vm.geometry?.fit)

        vm.calibrate()

        assertTrue(vm.geometry?.constraints.isNullOrEmpty())
        assertNull(vm.geometry?.fit)
        assertNull(vm.geometry?.plane)
        assertEquals(0.01, vm.geometry?.heightOffsetMeters)
        vm.close()
    }

    @Test
    fun heightOffset_should_KeepConstraintsAndFit_EvenWhenEditedTwice() {
        val vm = vm().also { it.calibrate() }
        vm.confirmConstraintCalibration(listOf(c1, c2, c3))
        vm.setGeometry(GeometryCalibration(0.01))
        assertEquals(3, vm.geometry?.constraints?.size)
        assertNotNull(vm.geometry?.fit)

        vm.setGeometry(null)

        assertEquals(3, vm.geometry?.constraints?.size, "a SCALE fit has no plane, so only the predicate kept it")
        assertNotNull(vm.geometry?.fit)
        vm.close()
    }

    @Test
    fun failedRicherTier_should_FallBackToScaleWithAReasonAndKeepMeasuring() {
        val vm = vm().also { it.calibrate() }

        vm.confirmConstraintCalibration(listOf(c1, c2, c3), TierRequest.Only(FitTier.GRADIENT))

        val result = assertNotNull(vm.constraintFitResult.value)
        assertNotNull(result.fallbackNote)
        assertEquals(FitTier.SCALE, vm.geometry?.fit?.tier)
        assertNull(vm.geometry?.plane)
        val drawn = vm.draw(0.2)
        assertNotNull(drawn.valueMeters)
        vm.close()
    }

    @Test
    fun noFitAtAll_should_ChangeNothingAndSayWhy() {
        val vm = vm().also { it.calibrate() }
        val before = vm.calibration
        val degenerate = CalibrationConstraint("z", NormalizedPoint(0.3, 0.3), NormalizedPoint(0.3, 0.3), 0.2)

        vm.confirmConstraintCalibration(listOf(degenerate))

        assertEquals(before, vm.calibration)
        assertEquals(CalibrationFailure.ZeroLengthSegment("z").describe(), vm.constraintFitResult.value?.fallbackNote)
        vm.close()
    }

    @Test
    fun undoCalibration_should_KeepAnnotationsDrawnAndEditedAfterTheFit() {
        val vm = vm().also { it.calibrate() }
        vm.confirmConstraintCalibration(listOf(c1, c2))
        val before = vm.calibration
        vm.confirmConstraintCalibration(listOf(c1, c2, c3))
        val drawn = vm.draw(0.2, 0.3)
        val edited = vm.draw(0.2, 0.4)
        vm.submitValueEdit(edited.uuid, "0.90", ValueEditMode.LABEL_ONLY, MeasurementUnit.METERS)
        val removed = vm.draw(0.2, 0.6)
        vm.deleteAnnotation(removed.uuid)

        vm.undoCalibration()

        assertEquals(before, vm.calibration)
        val after = vm.state.value.committedAnnotations.associateBy { it.uuid }
        assertNotNull(after[drawn.uuid], "a line drawn after the fit must survive the undo")
        assertEquals(0.90, after[edited.uuid]?.valueMeters)
        assertNull(after[removed.uuid], "a line deleted after the fit must stay deleted")
        assertNotNull(after[drawn.uuid]?.valueMeters)
        vm.close()
    }

    @Test
    fun promotingAnOverriddenLine_should_DropTheOldOverrideSoItFollowsTheFit() {
        val vm = vm().also { it.calibrate() }
        val target = vm.draw(0.2)
        vm.submitValueEdit(target.uuid, "0.50", ValueEditMode.LABEL_ONLY, MeasurementUnit.METERS)
        vm.submitValueEdit(target.uuid, "1.20", ValueEditMode.KNOWN_DISTANCE, MeasurementUnit.METERS)

        val promoted = vm.state.value.committedAnnotations.single { it.uuid == target.uuid }
        assertEquals(dev.stapler.stelekit.model.ValueSource.COMPUTED, promoted.valueSource)
        assertEquals(1.20, promoted.valueMeters!!, 0.01)
        vm.close()
    }

    @Test
    fun overrides_should_SurviveAddingAConstraint() {
        val vm = vm().also { it.calibrate() }
        val target = vm.draw(0.2)
        vm.submitValueEdit(target.uuid, "0.90", ValueEditMode.LABEL_ONLY, MeasurementUnit.METERS)
        val other = vm.draw(0.2, 0.3)

        vm.confirmConstraintCalibration(listOf(c1, c2, c4))

        val edited = vm.state.value.committedAnnotations.single { it.uuid == target.uuid }
        assertEquals(0.90, edited.valueMeters)
        assertNotNull(vm.state.value.committedAnnotations.single { it.uuid == other.uuid }.valueMeters)
        vm.close()
    }

    @Test
    fun gradientTier_should_ReachTheCalibratorOnlyWhenTheSettingIsOn() {
        val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(5), 6, 9.0, 0.2, spreadFraction = 0.6))
        val constraints = scene.training.map { it.toConstraint() }
        val key = ConstraintTuning.SETTINGS_KEY_GRADIENT_TIER

        val on = vm(MapSettings(key to true), scene.widthPx, scene.heightPx)
        on.confirmConstraintCalibration(constraints)
        assertEquals(PlaneKind.CONSTRAINT_FIT, on.geometry?.plane?.kind)
        assertEquals(FitTier.GRADIENT, on.geometry?.fit?.tier)

        val off = vm(MapSettings(key to false), scene.widthPx, scene.heightPx)
        off.confirmConstraintCalibration(constraints)
        assertNull(off.geometry?.plane)
        assertEquals(FitTier.SCALE, off.geometry?.fit?.tier)

        val absent = vm(null, scene.widthPx, scene.heightPx)
        absent.confirmConstraintCalibration(constraints)
        assertEquals(FitTier.SCALE, absent.geometry?.fit?.tier)
        on.close()
        off.close()
        absent.close()
    }

    @Test
    fun scaleFitOnAnUncalibratedPhoto_should_WriteTheExistingManualReferenceMethod() {
        val vm = vm()
        vm.confirmConstraintCalibration(listOf(c1))
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, vm.calibration.method)
        assertEquals(100, vm.calibration.confidencePercent)
        assertEquals(5000.0, vm.calibration.pixelsPerMeter, 1.0)
        vm.close()
    }
}
