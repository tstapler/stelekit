package dev.stapler.stelekit.annotate

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.measurement.DeviceConnectionState
import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import dev.stapler.stelekit.platform.measurement.MeasurementReading
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.annotate.AnnotationEditorState
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.ui.annotate.AnnotationTool
import dev.stapler.stelekit.ui.annotate.DepthCoordinatorState
import dev.stapler.stelekit.ui.annotate.DepthModelUiState
import dev.stapler.stelekit.ui.annotate.isOpenEnded
import dev.stapler.stelekit.ui.annotate.requiredPointCount
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeDevice(
    private val readings: Flow<MeasurementReading>,
    override val deviceId: String = "fake-ble",
) : ExternalMeasurementDevice {
    override val deviceName = "Fake laser"
    override val connectionState: StateFlow<DeviceConnectionState> =
        MutableStateFlow(DeviceConnectionState.CONNECTED)
    override suspend fun connect(): Either<DomainError, Unit> = Unit.right()
    override suspend fun disconnect() {}
    override fun measurementFlow(): Flow<MeasurementReading> = readings
}

/** Branch-gap coverage for [AnnotationEditorViewModel] — complements [AnnotationEditorViewModelTest]. */
class AnnotationEditorViewModelBranchTest {

    private fun image(
        ppm: Double = 100.0,
        method: CalibrationMethod = CalibrationMethod.MANUAL_REFERENCE,
        tags: List<String> = emptyList(),
    ) = ImageAnnotation(
        uuid = "img-001",
        blockUuid = "blk-001",
        pageUuid = "page-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(method = method, pixelsPerMeter = ppm, confidencePercent = 100),
        unit = MeasurementUnit.METERS,
        tags = tags,
    )

    private fun vm(
        img: ImageAnnotation = image(),
        imageRepo: InMemoryImageAnnotationRepository? = null,
        measurementRepo: InMemoryMeasurementAnnotationRepository = InMemoryMeasurementAnnotationRepository(),
    ) = AnnotationEditorViewModel(
        measurementRepository = measurementRepo,
        imageAnnotationRepository = imageRepo,
        imageWidthPx = 1000.0,
        imageHeightPx = 1000.0,
    ).also { it.initialize(img) }

    private suspend fun <T> awaitState(flow: StateFlow<T>, cond: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(10_000) { flow.first(cond) } }

    private fun AnnotationEditorViewModel.drawDistance(x2: Double = 0.1) {
        selectTool(AnnotationTool.DISTANCE)
        addPoint(NormalizedPoint(0.0, 0.0))
        addPoint(NormalizedPoint(x2, 0.0))
    }

    // ── GRID_REF dialog ───────────────────────────────────────────────────────

    private fun AnnotationEditorViewModel.openGridRefDialog() {
        selectTool(AnnotationTool.GRID_REF)
        addPoint(NormalizedPoint(0.0, 0.0))
        addPoint(NormalizedPoint(0.5, 0.0))
    }

    @Test
    fun gridRef_twoPoints_opensDialogWithoutCommitting() {
        val v = vm()
        v.openGridRefDialog()
        val s = v.state.value
        assertTrue(s.isGridRefDialogVisible)
        assertEquals(2, s.pendingGridRefPoints.size)
        assertTrue(s.committedAnnotations.isEmpty())
    }

    @Test
    fun gridRef_tooShortLine_isIgnored() {
        val v = vm()
        v.selectTool(AnnotationTool.GRID_REF)
        v.addPoint(NormalizedPoint(0.0, 0.0))
        v.addPoint(NormalizedPoint(0.001, 0.0))
        assertFalse(v.state.value.isGridRefDialogVisible)
        assertTrue(v.state.value.inProgressPoints.isEmpty())
    }

    @Test
    fun confirmGridRef_success_appliesCalibrationAndClosesDialog() {
        val v = vm()
        v.openGridRefDialog()
        v.updateGridRefUnit(MeasurementUnit.METERS)
        v.updateGridRefLengthText("2")
        v.confirmGridRefCalibration()
        val s = v.state.value
        assertFalse(s.isGridRefDialogVisible)
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, s.calibration?.method)
        assertEquals(250.0, s.calibration!!.pixelsPerMeter, 1e-9) // 500px / 2m
    }

    @Test
    fun confirmGridRef_blankOrInvalidLength_isNoOp() {
        val v = vm()
        v.openGridRefDialog()
        for (text in listOf("", "abc", "0", "-3")) {
            v.updateGridRefLengthText(text)
            v.confirmGridRefCalibration()
            assertTrue(v.state.value.isGridRefDialogVisible, "dialog should stay open for '$text'")
            assertEquals(100.0, v.state.value.calibration!!.pixelsPerMeter, 1e-9)
        }
    }

    @Test
    fun confirmGridRef_withoutPendingPoints_isNoOp() {
        val v = vm()
        v.updateGridRefLengthText("2")
        v.confirmGridRefCalibration()
        assertEquals(100.0, v.state.value.calibration!!.pixelsPerMeter, 1e-9)
    }

    @Test
    fun dismissGridRef_clearsDialogState() {
        val v = vm()
        v.openGridRefDialog()
        v.updateGridRefLengthText("5")
        v.dismissGridRefDialog()
        val s = v.state.value
        assertFalse(s.isGridRefDialogVisible)
        assertTrue(s.pendingGridRefPoints.isEmpty())
        assertEquals("", s.gridRefLengthText)
    }

    // ── updateCalibration ─────────────────────────────────────────────────────

    @Test
    fun updateCalibration_rejectsInvalidPixelsPerMeter() {
        val v = vm()
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -5.0)) {
            v.updateCalibration(Calibration(CalibrationMethod.MANUAL_REFERENCE, bad, 100))
        }
        assertEquals(100.0, v.state.value.calibration!!.pixelsPerMeter, 1e-9)
        assertFalse(v.canUndoCalibration.value)
    }

    @Test
    fun updateCalibration_withPriorCalibration_enablesUndo_andRestores() {
        val v = vm()
        v.updateCalibration(Calibration(CalibrationMethod.MANUAL_REFERENCE, 200.0, 100))
        assertTrue(v.canUndoCalibration.value)
        v.undoCalibration()
        assertEquals(100.0, v.state.value.calibration!!.pixelsPerMeter, 1e-9)
        assertFalse(v.canUndoCalibration.value)
        assertEquals("Scale restored", v.calibrationChangeMessage.value)
    }

    @Test
    fun updateCalibration_priorMethodNone_doesNotEnableUndo() {
        val v = vm(image(ppm = 100.0, method = CalibrationMethod.NONE))
        v.updateCalibration(Calibration(CalibrationMethod.MANUAL_REFERENCE, 200.0, 100))
        assertFalse(v.canUndoCalibration.value)
    }

    @Test
    fun updateCalibration_noAnnotations_setsNoMessage() {
        val v = vm()
        v.updateCalibration(Calibration(CalibrationMethod.MANUAL_REFERENCE, 200.0, 100))
        assertNull(v.calibrationChangeMessage.value)
    }

    @Test
    fun updateCalibration_messageSingularAndPlural_thenClear() {
        val v = vm()
        v.drawDistance()
        v.updateCalibration(Calibration(CalibrationMethod.MANUAL_REFERENCE, 200.0, 100))
        assertEquals("Scale updated — 1 measurement recalculated", v.calibrationChangeMessage.value)
        v.drawDistance(0.2)
        v.updateCalibration(Calibration(CalibrationMethod.MANUAL_REFERENCE, 300.0, 100))
        assertEquals("Scale updated — 2 measurements recalculated", v.calibrationChangeMessage.value)
        v.clearCalibrationMessage()
        assertNull(v.calibrationChangeMessage.value)
    }

    @Test
    fun undoCalibration_withoutHistory_isNoOp() {
        val v = vm()
        v.undoCalibration()
        assertNull(v.calibrationChangeMessage.value)
    }

    @Test
    fun isCalibrated_falseForNoneMethod() {
        assertTrue(vm().isCalibrated())
        assertFalse(vm(image(method = CalibrationMethod.NONE)).isCalibrated())
    }

    // ── injectMeasurementFromDevice ───────────────────────────────────────────

    @Test
    fun inject_noDevice_isNoOp() {
        val v = vm()
        v.drawDistance()
        v.injectMeasurementFromDevice()
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, v.state.value.calibration?.method)
    }

    @Test
    fun inject_noDistanceAnnotation_isNoOp() {
        val v = vm()
        v.setActiveDevice(FakeDevice(flowOf(MeasurementReading(1.0, "fake-ble"))))
        v.injectMeasurementFromDevice()
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, v.state.value.calibration?.method)
    }

    @Test
    fun inject_deviceReading_updatesCalibrationToBleLaser() = runTest {
        val v = vm()
        v.drawDistance(0.1) // 100 px
        v.setActiveDevice(FakeDevice(flowOf(MeasurementReading(2.0, "fake-ble"))))
        v.injectMeasurementFromDevice()

        val s = awaitState(v.state) { it.calibration?.method == CalibrationMethod.BLE_LASER }
        assertEquals(50.0, s.calibration!!.pixelsPerMeter, 1e-9) // 100px / 2m
        val ann = s.committedAnnotations.single { it.annotationType == AnnotationType.DISTANCE }
        assertEquals("fake-ble", ann.bleDeviceId)
        assertEquals(2.0, ann.valueMeters!!, 1e-9)
        v.close()
    }

    @Test
    fun inject_nonPositiveReading_leavesCalibration() = runTest {
        val v = vm()
        v.drawDistance()
        v.setActiveDevice(FakeDevice(flowOf(MeasurementReading(0.0, "fake-ble"))))
        v.injectMeasurementFromDevice()
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(200) }
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, v.state.value.calibration?.method)
        v.close()
    }

    @Test
    fun deviceConnectionState_reflectsActiveDevice() {
        val v = vm()
        assertEquals(DeviceConnectionState.DISCONNECTED, v.deviceConnectionState.value)
        v.setActiveDevice(FakeDevice(flowOf()))
        assertEquals(DeviceConnectionState.CONNECTED, v.deviceConnectionState.value)
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    @Test
    fun addTag_blankIgnored_sanitised_dedupedAndPersisted() = runTest {
        val imageRepo = InMemoryImageAnnotationRepository()
        val v = vm(imageRepo = imageRepo)
        v.addTag("   ")
        assertTrue(v.state.value.imageAnnotation!!.tags.isEmpty())
        v.addTag("Wall Paint!")
        assertEquals(listOf("wall-paint-"), v.state.value.imageAnnotation!!.tags)
        v.addTag("wall-paint-")
        assertEquals(1, v.state.value.imageAnnotation!!.tags.size)
        v.close()
    }

    @Test
    fun removeTag_presentRemoved_absentNoOp() {
        val v = vm(image(tags = listOf("a", "b")))
        v.removeTag("zzz")
        assertEquals(listOf("a", "b"), v.state.value.imageAnnotation!!.tags)
        v.removeTag("a")
        assertEquals(listOf("b"), v.state.value.imageAnnotation!!.tags)
    }

    // ── AREA closing ──────────────────────────────────────────────────────────

    @Test
    fun area_tapNearFirstPoint_closesPolygon() {
        val v = vm()
        v.selectTool(AnnotationTool.AREA)
        listOf(
            NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.1), NormalizedPoint(0.5, 0.5),
            NormalizedPoint(0.11, 0.1),
        ).forEach { v.addPoint(it) }
        val s = v.state.value
        assertEquals(1, s.committedAnnotations.size)
        assertEquals(3, s.committedAnnotations.single().normalizedPoints.size)
    }

    @Test
    fun area_tapFarFromFirst_keepsOpen() {
        val v = vm()
        v.selectTool(AnnotationTool.AREA)
        listOf(
            NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.1), NormalizedPoint(0.5, 0.5),
            NormalizedPoint(0.1, 0.5),
        ).forEach { v.addPoint(it) }
        assertTrue(v.state.value.committedAnnotations.isEmpty())
        assertEquals(4, v.state.value.inProgressPoints.size)
    }

    @Test
    fun closePolygon_requiresAreaToolAndThreePoints() {
        val v = vm()
        v.selectTool(AnnotationTool.AREA)
        v.addPoint(NormalizedPoint(0.1, 0.1))
        v.addPoint(NormalizedPoint(0.5, 0.1))
        v.closePolygon()
        assertTrue(v.state.value.committedAnnotations.isEmpty())
        v.addPoint(NormalizedPoint(0.5, 0.5))
        v.closePolygon()
        assertEquals(1, v.state.value.committedAnnotations.size)
    }

    // ── Label / selection / undo ──────────────────────────────────────────────

    @Test
    fun label_confirmCommitsAndDismissClears() {
        val v = vm()
        v.selectTool(AnnotationTool.LABEL)
        v.addPoint(NormalizedPoint(0.3, 0.3))
        assertTrue(v.state.value.isLabelInputVisible)
        v.updateLabelText("Door")
        v.confirmLabel()
        assertEquals("Door", v.state.value.committedAnnotations.single().label)

        v.addPoint(NormalizedPoint(0.4, 0.4))
        v.dismissLabelInput()
        assertFalse(v.state.value.isLabelInputVisible)
        assertEquals(1, v.state.value.committedAnnotations.size)
    }

    @Test
    fun confirmLabel_withoutPendingPoint_isNoOp() {
        val v = vm()
        v.confirmLabel()
        assertTrue(v.state.value.committedAnnotations.isEmpty())
    }

    @Test
    fun selectAndDeleteSelected_removesAnnotation_andUndoRedoRoundTrip() {
        val v = vm()
        v.drawDistance()
        val uuid = v.state.value.committedAnnotations.single().uuid
        v.deleteSelectedAnnotation() // nothing selected → no-op
        assertEquals(1, v.state.value.committedAnnotations.size)
        v.selectAnnotation(uuid)
        v.deleteSelectedAnnotation()
        assertTrue(v.state.value.committedAnnotations.isEmpty())
        assertNull(v.state.value.selectedAnnotationUuid)
        v.undo()
        assertEquals(1, v.state.value.committedAnnotations.size)
        v.redo()
        assertTrue(v.state.value.committedAnnotations.isEmpty())
        v.clearSelection()
    }

    @Test
    fun undoRedo_onEmptyStacks_areNoOps() {
        val v = vm()
        v.undo()
        v.redo()
        assertFalse(v.canUndo.value)
        assertFalse(v.canRedo.value)
    }

    @Test
    fun cancelInProgress_clearsPoints() {
        val v = vm()
        v.selectTool(AnnotationTool.ANGLE)
        v.addPoint(NormalizedPoint(0.1, 0.1))
        v.cancelInProgress()
        assertTrue(v.state.value.inProgressPoints.isEmpty())
    }

    @Test
    fun angleTool_commitsAfterThreePoints() {
        val v = vm()
        v.selectTool(AnnotationTool.ANGLE)
        v.addPoint(NormalizedPoint(0.1, 0.0))
        v.addPoint(NormalizedPoint(0.0, 0.0))
        v.addPoint(NormalizedPoint(0.0, 0.1))
        val ann = v.state.value.committedAnnotations.single()
        assertEquals(90.0, ann.valueMeters!!, 1e-6)
    }

    // ── initialize ────────────────────────────────────────────────────────────

    @Test
    fun initialize_loadsPersistedMeasurements_andSecondCallKeepsUserEdits() = runTest {
        val repo = InMemoryMeasurementAnnotationRepository()
        val v = vm(measurementRepo = repo)
        v.drawDistance()
        val committed = awaitState(v.state) { it.committedAnnotations.size == 1 }
        // Second initialize: updates image/calibration, must not re-run the load or drop edits.
        v.initialize(image(ppm = 250.0))
        val s = v.state.value
        assertEquals(250.0, s.calibration!!.pixelsPerMeter, 1e-9)
        assertEquals(committed.committedAnnotations.map { it.uuid }, s.committedAnnotations.map { it.uuid })
        assertNotNull(s.imageAnnotation)
        v.close()
    }

    @Test
    fun state_default_hasNoImage() {
        assertNull(AnnotationEditorState().imageAnnotation)
    }

    // ── Tool metadata / recalculation across every annotation type ───────────

    @Test
    fun toolMetadata_coversEveryTool() {
        val expected = mapOf(
            AnnotationTool.SELECT to 1, AnnotationTool.DISTANCE to 2, AnnotationTool.AREA to 3,
            AnnotationTool.ANGLE to 3, AnnotationTool.LABEL to 1, AnnotationTool.GRID_REF to 2,
        )
        AnnotationTool.entries.forEach {
            assertEquals(expected.getValue(it), it.requiredPointCount())
            assertEquals(it == AnnotationTool.AREA, it.isOpenEnded())
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    @Test
    fun updateCalibration_rederivesEveryAnnotationType() = runTest {
        val repo = InMemoryMeasurementAnnotationRepository()
        val gridRef = MeasurementAnnotation(
            uuid = UuidGenerator.generateV7(),
            imageUuid = "img-001",
            annotationType = AnnotationType.GRID_REF,
            normalizedPoints = listOf(NormalizedPoint(0.0, 0.0)),
        )
        repo.saveMeasurementAnnotation(gridRef)
        val v = vm(measurementRepo = repo)
        awaitState(v.state) { it.committedAnnotations.size == 1 }

        v.drawDistance()
        v.selectTool(AnnotationTool.AREA)
        listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.1), NormalizedPoint(0.5, 0.5))
            .forEach { v.addPoint(it) }
        v.closePolygon()
        v.selectTool(AnnotationTool.ANGLE)
        v.addPoint(NormalizedPoint(0.1, 0.0))
        v.addPoint(NormalizedPoint(0.0, 0.0))
        v.addPoint(NormalizedPoint(0.0, 0.1))
        v.selectTool(AnnotationTool.LABEL)
        v.addPoint(NormalizedPoint(0.3, 0.3))
        v.confirmLabel()
        assertEquals(5, v.state.value.committedAnnotations.size)

        v.updateCalibration(Calibration(CalibrationMethod.MANUAL_REFERENCE, 500.0, 100))
        val byType = v.state.value.committedAnnotations.associateBy { it.annotationType }
        assertEquals(setOf(AnnotationType.GRID_REF, AnnotationType.DISTANCE, AnnotationType.AREA,
            AnnotationType.ANGLE, AnnotationType.LABEL), byType.keys)
        assertNull(byType.getValue(AnnotationType.GRID_REF).valueMeters) // single anchor point
        assertNull(byType.getValue(AnnotationType.LABEL).valueMeters)
        assertEquals(90.0, byType.getValue(AnnotationType.ANGLE).valueMeters!!, 1e-6)
        assertNotNull(byType.getValue(AnnotationType.AREA).valueMeters)
        v.close()
    }

    @Test
    fun uncalibrated_distanceAndAreaShowPixelFallbacks() {
        val v = vm(image(ppm = 0.0001, method = CalibrationMethod.NONE).copy(
            calibration = Calibration(CalibrationMethod.NONE, 0.0, 0),
        ))
        v.drawDistance()
        assertEquals("100 px", v.state.value.committedAnnotations.single().valueDisplay)
        v.selectTool(AnnotationTool.AREA)
        listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.1), NormalizedPoint(0.5, 0.5))
            .forEach { v.addPoint(it) }
        v.closePolygon()
        assertEquals("uncalibrated", v.state.value.committedAnnotations.last().valueDisplay)
    }

    @Test
    fun distanceTooShort_isDiscarded() {
        val v = vm()
        v.drawDistance(0.001)
        assertTrue(v.state.value.committedAnnotations.isEmpty())
        assertTrue(v.state.value.inProgressPoints.isEmpty())
    }

    @Test
    fun selectToolAndLabelNoImage_commitIsNoOp() {
        val v = AnnotationEditorViewModel(InMemoryMeasurementAnnotationRepository())
        v.selectTool(AnnotationTool.DISTANCE)
        v.addPoint(NormalizedPoint(0.0, 0.0))
        v.addPoint(NormalizedPoint(0.5, 0.0))
        assertTrue(v.state.value.committedAnnotations.isEmpty())
        v.addTag("x")
        v.removeTag("x")
        v.injectMeasurementFromDevice()
    }

    // ── State equality ────────────────────────────────────────────────────────

    @Test
    fun depthCoordinatorState_equalityAndHash() {
        val a = DepthCoordinatorState(depthMap = floatArrayOf(1f, 2f), depthEstimationError = "e")
        val b = DepthCoordinatorState(depthMap = floatArrayOf(1f, 2f), depthEstimationError = "e")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == b.copy(depthMap = floatArrayOf(9f)))
        assertFalse(a == b.copy(depthMap = null))
        assertFalse(DepthCoordinatorState() == a)
        assertFalse(a == b.copy(isDepthInferenceRunning = true))
        assertFalse(a == b.copy(depthEstimationError = null))
        assertFalse(a == b.copy(depthModelUiState = DepthModelUiState.Ready))
        assertFalse(a.equals("x"))
        assertTrue(a.equals(a))
        assertEquals(DepthCoordinatorState().hashCode(), DepthCoordinatorState().hashCode())
        assertTrue(DepthCoordinatorState(depthMap = null).equals(DepthCoordinatorState(depthMap = null)))
    }

    @Test
    fun depthModelUiState_propagatesThroughViewModel() = runTest {
        val v = vm()
        v.updateDepthModelUiState(DepthModelUiState.Downloading(40))
        val s = awaitState(v.state) { it.depthModelUiState == DepthModelUiState.Downloading(40) }
        assertEquals(DepthModelUiState.Downloading(40), s.depthModelUiState)
        v.close()
    }

    // ── Remaining guards ──────────────────────────────────────────────────────

    @Test
    fun undoHistory_isBoundedAt50() {
        val v = vm()
        repeat(55) { v.drawDistance(); }
        assertEquals(55, v.state.value.committedAnnotations.size)
        var undone = 0
        while (v.canUndo.value) { v.undo(); undone++ }
        assertEquals(50, undone)
        assertTrue(v.canRedo.value)
    }

    @Test
    fun uninitialized_calibrationAndGridRefPaths_areSafe() = runTest {
        val v = AnnotationEditorViewModel(InMemoryMeasurementAnnotationRepository(), imageWidthPx = 1000.0, imageHeightPx = 1000.0)
        assertFalse(v.isCalibrated())
        v.updateCalibration(Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100))
        assertTrue(v.isCalibrated())
        v.updateCalibration(Calibration(CalibrationMethod.MANUAL_REFERENCE, 200.0, 100))
        v.undoCalibration()
        v.selectTool(AnnotationTool.GRID_REF)
        v.addPoint(NormalizedPoint(0.0, 0.0))
        v.addPoint(NormalizedPoint(0.5, 0.0))
        assertEquals(MeasurementUnit.METERS, v.state.value.gridRefUnit)
        v.deleteAnnotation("missing")
        v.close()
    }

    @Test
    fun closePolygon_ignoredForNonAreaTool() {
        val v = vm()
        v.selectTool(AnnotationTool.ANGLE)
        v.addPoint(NormalizedPoint(0.1, 0.1))
        v.addPoint(NormalizedPoint(0.2, 0.2))
        v.closePolygon()
        assertTrue(v.state.value.committedAnnotations.isEmpty())
    }

    @Test
    fun addTag_withoutRepository_updatesStateOnly() {
        val v = vm()
        v.addTag("kitchen")
        assertEquals(listOf("kitchen"), v.state.value.imageAnnotation!!.tags)
    }

    @OptIn(DirectRepositoryWrite::class)
    @Test
    fun inject_singlePointAnnotation_isIgnored() = runTest {
        val repo = InMemoryMeasurementAnnotationRepository()
        repo.saveMeasurementAnnotation(
            MeasurementAnnotation(
                uuid = UuidGenerator.generateV7(), imageUuid = "img-001",
                annotationType = AnnotationType.GRID_REF,
                normalizedPoints = listOf(NormalizedPoint(0.0, 0.0)),
            ),
        )
        val v = vm(measurementRepo = repo)
        awaitState(v.state) { it.committedAnnotations.size == 1 }
        v.setActiveDevice(FakeDevice(flowOf(MeasurementReading(1.0, "fake-ble"))))
        v.injectMeasurementFromDevice()
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(200) }
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, v.state.value.calibration?.method)
        v.close()
    }
}
