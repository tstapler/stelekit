// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ConstraintFitRecord
import dev.stapler.stelekit.model.MeasurementAnnotation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val ONLY_DISTANCE_EDITABLE = "Only distance values can be edited"
internal const val OVERRIDE_DRAG_QUESTION = "This value was edited by you. Clear it and use the measured length?"
private const val SCOPE_MESSAGE_MILLIS = 2_000L

/** Screen-local state of the value editor, the Known distances sheet and the strip message; the ViewModel owns everything persisted. */
internal class ValueEditHostState {
    var editingUuid by mutableStateOf<String?>(null)
        private set
    var showKnownDistances by mutableStateOf(false)
    var saveFailed by mutableStateOf(false)
        private set

    /** Shown in the status strip for [SCOPE_MESSAGE_MILLIS] after tapping an Area or Angle value. */
    var scopeMessage by mutableStateOf<String?>(null)
        private set

    /** The editor was opened by "Draw a known distance": Cancel there also removes the drawn line. */
    private var fromKnownFlow = false

    /** The sheet started the draw, so it comes back once the editor closes. */
    private var reopenSheet = false
    var lastChoice: ValueEditMode? = null
        private set
    private var deletedConstraints: List<CalibrationConstraint>? = null
    private var useAnywayFit: ConstraintFitRecord? = null
    private var useAnywayIds: Set<String> = emptySet()

    val isModalOpen: Boolean get() = editingUuid != null || showKnownDistances

    fun onValueTap(annotation: MeasurementAnnotation) {
        when (annotation.annotationType) {
            AnnotationType.DISTANCE -> openEditor(annotation.uuid)
            AnnotationType.AREA, AnnotationType.ANGLE -> scopeMessage = ONLY_DISTANCE_EDITABLE
            AnnotationType.LABEL, AnnotationType.GRID_REF -> Unit
        }
    }

    fun openEditor(uuid: String, choice: ValueEditMode? = lastChoice) {
        saveFailed = false
        scopeMessage = null
        lastChoice = choice
        editingUuid = uuid
    }

    fun beginKnownDistanceDraw() {
        showKnownDistances = false
        reopenSheet = true
    }

    fun openFromKnownFlow(uuid: String) {
        fromKnownFlow = true
        openEditor(uuid, ValueEditMode.KNOWN_DISTANCE)
    }

    fun applied(mode: ValueEditMode) {
        lastChoice = mode
        close()
    }

    fun failedToSave() {
        saveFailed = true
    }

    fun dismissEditor(viewModel: AnnotationEditorViewModel) {
        val uuid = editingUuid ?: return
        if (fromKnownFlow) viewModel.cancelKnownDistance(uuid)
        close()
    }

    fun clearScopeMessage() {
        scopeMessage = null
    }

    fun rememberDeleted(constraints: List<CalibrationConstraint>) {
        deletedConstraints = constraints
    }

    fun takeDeleted(): List<CalibrationConstraint>? = deletedConstraints.also { deletedConstraints = null }

    fun markUseAnyway(fit: ConstraintFitRecord?, id: String) {
        useAnywayIds = if (fit == useAnywayFit) useAnywayIds + id else setOf(id)
        useAnywayFit = fit
    }

    /** "Use anyway" lasts for the fit it was chosen on; any re-fit flags the row again if it still disagrees. */
    fun applyUseAnyway(fit: ConstraintFitRecord?, rows: List<ConstraintRow>): List<ConstraintRow> =
        if (fit != useAnywayFit || useAnywayIds.isEmpty()) rows
        else rows.map { if (it.id in useAnywayIds) it.copy(flagged = false, flagText = null) else it }

    private fun close() {
        editingUuid = null
        fromKnownFlow = false
        saveFailed = false
        if (reopenSheet) {
            reopenSheet = false
            showKnownDistances = true
        }
    }
}

@Composable
internal fun rememberValueEditHost(): ValueEditHostState = remember { ValueEditHostState() }

/** The pill and sheet's shared presentation, derived from the calibration's known distances and the last fit. */
@Composable
internal fun rememberCalibrationPresentation(
    viewModel: AnnotationEditorViewModel,
    state: AnnotationEditorState,
    host: ValueEditHostState,
): CalibrationPresentation {
    val fitResult by viewModel.constraintFitResult.collectAsState()
    val geometry = state.calibration?.geometry
    return remember(state.calibration, fitResult, host.showKnownDistances) {
        val base = CalibrationStatePresenter.present(
            calibration = state.calibration,
            constraints = geometry?.constraints.orEmpty(),
            fit = geometry?.fit,
            report = fitResult?.report,
        )
        base.copy(rows = host.applyUseAnyway(geometry?.fit, base.rows))
    }
}

/**
 * Hosts the value editor, Known distances sheet and override-drag confirm. [assistiveServiceActive] stays false until a platform
 * accessibility source reaches commonMain; the Undo snackbar then times out normally instead of staying up (UX-61).
 */
@Composable
internal fun AnnotationValueHooks(
    viewModel: AnnotationEditorViewModel,
    state: AnnotationEditorState,
    host: ValueEditHostState,
    presentation: CalibrationPresentation,
    snackbar: SnackbarHostState,
    onCalibrateLegacy: () -> Unit,
    onUseBle: () -> Unit,
    assistiveServiceActive: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val fitResult by viewModel.constraintFitResult.collectAsState()
    val dragConfirm by viewModel.overrideDragConfirm.collectAsState()
    val footer = fitResult?.fallbackNote

    LaunchedEffect(Unit) { viewModel.openValueEditor.collect { host.openFromKnownFlow(it) } }
    LaunchedEffect(host.scopeMessage) {
        if (host.scopeMessage != null) {
            delay(SCOPE_MESSAGE_MILLIS)
            host.clearScopeMessage()
        }
    }

    val editing = host.editingUuid?.let { uuid -> state.committedAnnotations.firstOrNull { it.uuid == uuid && it.annotationType == AnnotationType.DISTANCE } }
    if (editing != null) {
        MeasurementValueEditorSheet(
            context = valueEditorContext(viewModel, state, editing, host),
            onApply = { result ->
                viewModel.submitValueEdit(editing.uuid, entryText(result.meters), result.mode, result.entryUnit).fold(
                    { host.failedToSave() },
                    {
                        host.applied(result.mode)
                        // A known-distance apply already raises the "Scale updated" snackbar with its own Undo.
                        if (result.mode == ValueEditMode.LABEL_ONLY) {
                            scope.launch { if (snackbar.showUndo("Value changed", assistiveServiceActive)) viewModel.undo() }
                        }
                    },
                )
            },
            onDismiss = { host.dismissEditor(viewModel) },
            saveFailed = host.saveFailed,
            solverFooter = footer,
        )
    }

    if (host.showKnownDistances) {
        val constraints = state.calibration?.geometry?.constraints.orEmpty()
        KnownDistancesSheet(
            presentation = presentation,
            displayUnit = state.selectedUnit,
            actions = knownDistancesActions(viewModel, state, host, constraints, onCalibrateLegacy, onUseBle),
            affectedMeasurementCount = state.committedAnnotations.count { it.annotationType != AnnotationType.LABEL },
            footer = footer,
            assistiveServiceActive = assistiveServiceActive,
        )
    }

    if (dragConfirm != null) {
        AlertDialog(
            onDismissRequest = { viewModel.resolveOverrideDrag(clearOverride = false) },
            text = { Text(OVERRIDE_DRAG_QUESTION) },
            confirmButton = { TextButton(onClick = { viewModel.resolveOverrideDrag(clearOverride = true) }) { Text("Clear and recompute") } },
            dismissButton = { TextButton(onClick = { viewModel.resolveOverrideDrag(clearOverride = false) }) { Text("Keep my value") } },
        )
    }
}

private fun knownDistancesActions(
    viewModel: AnnotationEditorViewModel,
    state: AnnotationEditorState,
    host: ValueEditHostState,
    constraints: List<CalibrationConstraint>,
    onCalibrateLegacy: () -> Unit,
    onUseBle: () -> Unit,
): KnownDistancesActions {
    fun measurementOf(id: String): String? = constraints.firstOrNull { it.id == id }?.measurementUuid
        ?.takeIf { uuid -> state.committedAnnotations.any { it.uuid == uuid } }
    return KnownDistancesActions(
        onDismiss = { host.showKnownDistances = false },
        onDrawKnownDistance = {
            host.beginKnownDistanceDraw()
            viewModel.beginKnownDistance()
        },
        onUsePaper = {
            host.showKnownDistances = false
            viewModel.beginPlaneSession()
        },
        onEditValue = { id ->
            measurementOf(id)?.let { uuid ->
                host.showKnownDistances = false
                host.openEditor(uuid, ValueEditMode.KNOWN_DISTANCE)
            }
        },
        onRename = { id, name ->
            viewModel.confirmConstraintCalibration(constraints.map { if (it.id == id) it.copy(label = name) else it })
        },
        onDeleteConfirmed = { id ->
            host.rememberDeleted(constraints)
            viewModel.confirmConstraintCalibration(constraints.filterNot { it.id == id })
        },
        onUndoDelete = { host.takeDeleted()?.let { viewModel.confirmConstraintCalibration(it) } },
        onUseAnyway = { id -> host.markUseAnyway(state.calibration?.geometry?.fit, id) },
        onRemeasure = { id ->
            // Select the line so its ends can be dragged; the re-fit happens through the editor or the next known distance.
            measurementOf(id)?.let { viewModel.selectAnnotation(it) }
            host.showKnownDistances = false
        },
        onCalibrateLegacy = {
            host.showKnownDistances = false
            onCalibrateLegacy()
        },
        onUseBle = {
            host.showKnownDistances = false
            onUseBle()
        },
        onMarkRulerTicks = {
            host.showKnownDistances = false
            viewModel.beginRulerSession()
        },
    )
}

private fun valueEditorContext(
    viewModel: AnnotationEditorViewModel,
    state: AnnotationEditorState,
    annotation: MeasurementAnnotation,
    host: ValueEditHostState,
): ValueEditorContext {
    val calibration = state.calibration?.takeIf { it.method != CalibrationMethod.NONE && it.pixelsPerMeter > 0.0 }
    return ValueEditorContext(
        initialMeters = annotation.valueMeters,
        displayUnit = state.selectedUnit,
        photoCalibrated = calibration != null,
        segmentPixelLength = viewModel.segmentPixelLength(annotation.uuid),
        currentMetersPerPixel = calibration?.let { 1.0 / it.pixelsPerMeter },
        otherMeasurementCount = state.committedAnnotations.count { it.uuid != annotation.uuid && it.annotationType != AnnotationType.LABEL },
        legacyBasis = calibration?.isLegacyBasis == true,
        lastChoice = host.lastChoice,
    )
}

/** Plain decimal text the editor's own parser reads back; millimetres below 1 m avoid exponent notation for tiny values. */
private fun entryText(meters: Double): String = if (meters < 1.0) "${meters * 1000.0} mm" else "$meters m"
