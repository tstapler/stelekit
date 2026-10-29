// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.platform.google.AnnotatedImageRenderer
import dev.stapler.stelekit.platform.google.DriveExportService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the annotation editor's "Export to Drive" button: renders the annotated JPEG, uploads it
 * and the `.measure.json` sidecar through [DriveExportService], and reports progress as a
 * [DriveExportUiState].
 *
 * Holds no scope: the caller launches [export] and cancels that job to stop an upload ("Uploading…
 * Cancel"), which returns the button to [DriveExportUiState.Idle].
 */
class AnnotationDriveExporter(
    private val service: DriveExportService,
    private val renderer: AnnotatedImageRenderer,
) {
    private val logger = Logger("AnnotationDriveExporter")
    private val _state = MutableStateFlow<DriveExportUiState>(DriveExportUiState.Idle)
    val state: StateFlow<DriveExportUiState> = _state.asStateFlow()

    suspend fun export(imageAnnotation: ImageAnnotation, measurements: List<MeasurementAnnotation>) {
        val before = _state.value
        if (before is DriveExportUiState.Loading || !_state.compareAndSet(before, DriveExportUiState.Loading)) return
        try {
            val jpeg = renderer.renderJpeg(imageAnnotation, measurements).fold(
                ifLeft = { err ->
                    logger.warn("Drive export: rendering failed: ${err.message}")
                    _state.value = DriveExportUiState.Error(err.message)
                    return
                },
                ifRight = { it },
            )
            service.exportToDrive(imageAnnotation, measurements, jpeg).fold(
                ifLeft = { err ->
                    logger.warn("Drive export failed: ${err.message}")
                    _state.value = DriveExportUiState.Error(err.message)
                },
                ifRight = { _state.value = DriveExportUiState.Success(it.imageLink) },
            )
        } catch (e: CancellationException) {
            _state.value = DriveExportUiState.Idle
            throw e
        }
    }

    /** Clears a finished Success/Error so the button is ready for another export. */
    fun reset() {
        if (_state.value !is DriveExportUiState.Loading) _state.value = DriveExportUiState.Idle
    }
}
