// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.platform.measurement.DeviceConnectionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Render coverage for the internal composables that make up [AnnotationEditorScreen]. */
@RunWith(RobolectricTestRunner::class)
class AnnotateEditorPartsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun cal(method: CalibrationMethod, pct: Int = 90) = Calibration(method, 100.0, pct)

    // ── CalibrationStatusBar ──────────────────────────────────────────────────

    @Test
    fun statusBar_labelsEveryCalibrationMethod() {
        val expected = mapOf(
            CalibrationMethod.BLE_LASER to "±1mm BLE",
            CalibrationMethod.MANUAL_REFERENCE to "±manual ref",
            CalibrationMethod.ARCORE_DEPTH to "±8cm ARCore",
            CalibrationMethod.LIDAR_DEPTH to "±2cm LiDAR",
            CalibrationMethod.EXIF_FOCAL to "±15% EXIF estimate",
            CalibrationMethod.MONOCULAR_ML to "±15% AI estimate",
        )
        var current by androidx.compose.runtime.mutableStateOf<Calibration?>(null)
        rule.setContent { CalibrationStatusBar(current) }
        rule.onNodeWithText("Not calibrated").assertExists()
        rule.onNodeWithText("% confidence", substring = true).assertDoesNotExist()
        expected.forEach { (method, label) ->
            current = cal(method, 77)
            rule.waitForIdle()
            rule.onNodeWithText(label).assertExists()
            rule.onNodeWithText("77% confidence").assertExists()
        }
        current = cal(CalibrationMethod.NONE)
        rule.waitForIdle()
        rule.onNodeWithText("Not calibrated").assertExists()
        rule.onNodeWithText("% confidence", substring = true).assertDoesNotExist()
    }

    // ── GpsMetadataRow ────────────────────────────────────────────────────────

    @Test
    fun gps_formatsCardinalDirectionsAndAltitude() {
        rule.setContent { GpsMetadataRow(latLng = 49.2827 to -123.1207, altitudeM = 45.2) }
        rule.onNodeWithText("49.2827° N, 123.1207° W  ↑ 45.2 m").assertExists()
    }

    @Test
    fun gps_southEast_withoutAltitude() {
        rule.setContent { GpsMetadataRow(latLng = -33.5 to 151.0, altitudeM = null) }
        rule.onNodeWithText("33.5000° S, 151.0000° E").assertExists()
    }

    // ── DeviceConnectionChip ──────────────────────────────────────────────────

    @Test
    fun deviceChip_showsEachConnectionState() {
        val labels = mapOf(
            DeviceConnectionState.CONNECTED to "Connected",
            DeviceConnectionState.CONNECTING to "Connecting…",
            DeviceConnectionState.DISCONNECTED to "Disconnected",
            DeviceConnectionState.ERROR to "Error",
        )
        var state by androidx.compose.runtime.mutableStateOf(DeviceConnectionState.CONNECTED)
        rule.setContent {
            DeviceConnectionChip("Leica", state, showSetFromLaserButton = false, onSetFromLaser = {})
        }
        labels.forEach { (s, label) ->
            state = s
            rule.waitForIdle()
            rule.onNodeWithText("Leica · $label").assertExists()
        }
        rule.onNodeWithText("Set from laser").assertDoesNotExist()
    }

    @Test
    fun deviceChip_setFromLaserInvokesCallback() {
        var clicks = 0
        rule.setContent {
            DeviceConnectionChip("Leica", DeviceConnectionState.CONNECTED, true, onSetFromLaser = { clicks++ })
        }
        rule.onNodeWithText("Set from laser").performClick()
        assertEquals(1, clicks)
    }

    // ── DriveExportButton ─────────────────────────────────────────────────────

    @Test
    fun driveExport_idleAndErrorInvokeExport_loadingDoesNot_successShowsChip() {
        var exports = 0
        var state by androidx.compose.runtime.mutableStateOf<DriveExportUiState>(DriveExportUiState.Idle)
        rule.setContent { DriveExportButton(state, onExport = { exports++ }) }
        rule.onNodeWithContentDescription("Export to Google Drive").performClick()
        state = DriveExportUiState.Error("boom")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Export to Google Drive").performClick()
        assertEquals(2, exports)

        state = DriveExportUiState.Loading
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Export to Google Drive").assertDoesNotExist()

        state = DriveExportUiState.Success("https://drive/x")
        rule.waitForIdle()
        rule.onNodeWithText("Saved to Drive").assertExists()
    }

    // ── GridRefCalibrationDialog ──────────────────────────────────────────────

    @Test
    fun gridRefDialog_applyDisabledUntilValid_andUnitMenuExcludesFeetInches() {
        var confirmed = 0
        var unit: MeasurementUnit? = null
        var text by androidx.compose.runtime.mutableStateOf("")
        rule.setContent {
            GridRefCalibrationDialog(
                lengthText = text,
                selectedUnit = MeasurementUnit.METERS,
                onLengthTextChange = { text = it },
                onUnitChange = { unit = it },
                onConfirm = { confirmed++ },
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Set reference length").assertExists()
        rule.onNodeWithText("Apply calibration").assertIsNotEnabled()
        text = "2.5"
        rule.waitForIdle()
        rule.onNodeWithText("Apply calibration").performClick()
        assertEquals(1, confirmed)

        rule.onNodeWithText(MeasurementUnit.METERS.symbol()).performClick()
        rule.onNodeWithText(MeasurementUnit.CENTIMETERS.symbol()).performClick()
        assertEquals(MeasurementUnit.CENTIMETERS, unit)
    }

    // ── DepthEstimationPanel ──────────────────────────────────────────────────

    @Test
    fun depthPanel_absent_showsDownload() {
        var downloads = 0
        rule.setContent {
            DepthEstimationPanel(DepthModelUiState.Absent, false, null, onDownload = { downloads++ })
        }
        rule.onNodeWithText("Download depth model (~100MB)").performClick()
        assertEquals(1, downloads)
    }

    @Test
    fun depthPanel_downloading_showsProgressAndCancel() {
        var cancels = 0
        rule.setContent {
            DepthEstimationPanel(DepthModelUiState.Downloading(42), false, null, onDownload = {}, onCancel = { cancels++ })
        }
        rule.onNodeWithText("Downloading model… 42%").assertExists()
        rule.onNodeWithContentDescription("Cancel model download").performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun depthPanel_downloadingIndeterminate() {
        rule.setContent {
            DepthEstimationPanel(DepthModelUiState.Downloading(-1), false, null, onDownload = {})
        }
        rule.onNodeWithText("Downloading model…").assertExists()
    }

    @Test
    fun depthPanel_failed_withAndWithoutReason() {
        var state by androidx.compose.runtime.mutableStateOf<DepthModelUiState>(DepthModelUiState.Failed("Stalled."))
        rule.setContent { DepthEstimationPanel(state, false, null, onDownload = {}) }
        rule.onNodeWithText("Stalled. Tap to retry.").assertExists()
        state = DepthModelUiState.Failed()
        rule.waitForIdle()
        rule.onNodeWithText("Download failed — tap to retry").assertExists()
    }

    @Test
    fun depthPanel_ready_enabledWithWarning_orComingSoon() {
        var estimates = 0
        var wired by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            DepthEstimationPanel(
                DepthModelUiState.Ready, false, null, onDownload = {},
                onEstimate = if (wired) ({ estimates++ }) else null,
            )
        }
        rule.onNodeWithText("Low confidence — verify with reference object").assertExists()
        rule.onNodeWithText("Estimate depth (AI)").performClick()
        assertEquals(1, estimates)
        wired = false
        rule.waitForIdle()
        rule.onNodeWithText("Estimation coming soon").assertExists()
        rule.onNodeWithText("Low confidence — verify with reference object").assertDoesNotExist()
    }

    @Test
    fun depthPanel_inferenceRunning_hidesErrorAndShowsSpinnerText() {
        rule.setContent {
            DepthEstimationPanel(DepthModelUiState.Ready, true, "bad", onDownload = {})
        }
        rule.onNodeWithText("Estimating depth…").assertExists()
        rule.onNodeWithText("bad").assertDoesNotExist()
    }

    @Test
    fun depthPanel_errorShownWhenIdle() {
        rule.setContent {
            DepthEstimationPanel(DepthModelUiState.Absent, false, "model failed", onDownload = {})
        }
        rule.onNodeWithText("model failed").assertExists()
    }

    // ── CalibrationConfidenceBadge ────────────────────────────────────────────

    @Test
    fun confidenceBadge_rendersForEveryMethodAndNull() {
        var current by androidx.compose.runtime.mutableStateOf<Calibration?>(null)
        rule.setContent { CalibrationConfidenceBadge(current) }
        val labels = mapOf(
            CalibrationMethod.BLE_LASER to "Scale set",
            CalibrationMethod.ARCORE_DEPTH to "Scale estimated",
            CalibrationMethod.EXIF_FOCAL to "Scale uncertain",
            CalibrationMethod.NONE to "Not calibrated",
        )
        labels.forEach { (method, label) ->
            current = cal(method)
            rule.waitForIdle()
            rule.onNodeWithText(label).assertExists()
        }
        current = null
        rule.waitForIdle()
        rule.onNodeWithText("Not calibrated").assertExists()
    }

    // ── TagEditorPanel ────────────────────────────────────────────────────────

    @Test
    fun tagPanel_showsExistingTags() {
        rule.setContent {
            TagEditorPanel(
                tags = listOf("kitchen", "wall"),
                onAddTag = {},
                onRemoveTag = {},
                existingTagSuggestions = listOf("bath"),
            )
        }
        rule.onNodeWithText("Tags").assertExists()
        rule.onNodeWithText("kitchen").assertExists()
        rule.onNodeWithText("wall").assertExists()
    }
}
