// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class CalibrationSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val peer = Calibration(CalibrationMethod.MANUAL_REFERENCE, 120.0, 100)

    @Test
    fun withoutPeerCalibration_hidesUseScaleButton() {
        rule.setContent { CalibrationSheet(onDismiss = {}, onDrawReference = {}, onUseBle = {}) }
        rule.onNodeWithText("Calibrate Scale").assertExists()
        rule.onNodeWithText("Use scale from Kitchen", substring = true).assertDoesNotExist()
    }

    @Test
    fun peerWithoutCallback_hidesUseScaleButton() {
        rule.setContent {
            CalibrationSheet(
                onDismiss = {}, onDrawReference = {}, onUseBle = {},
                peerCalibration = "Kitchen" to peer, onUsePeerCalibration = null,
            )
        }
        rule.onNodeWithText("Use scale from Kitchen").assertDoesNotExist()
    }

    @Test
    fun withPeerCalibration_showsButton_andPassesPeerCalibration() {
        var received: Calibration? = null
        rule.setContent {
            CalibrationSheet(
                onDismiss = {}, onDrawReference = {}, onUseBle = {},
                peerCalibration = "Kitchen" to peer, onUsePeerCalibration = { received = it },
            )
        }
        rule.onNodeWithText("Use scale from Kitchen").performClick()
        assertEquals(peer, received)
    }

    @Test
    fun drawAndBleButtons_invokeCallbacks() {
        var draw = 0
        var ble = 0
        rule.setContent {
            CalibrationSheet(onDismiss = {}, onDrawReference = { draw++ }, onUseBle = { ble++ })
        }
        rule.onNodeWithText("Draw on photo").performClick()
        rule.onNodeWithText("Use BLE laser").performClick()
        assertEquals(1, draw)
        assertEquals(1, ble)
    }

    @Test
    fun howToToggle_expandsAndCollapsesSteps() {
        rule.setContent { CalibrationSheet(onDismiss = {}, onDrawReference = {}, onUseBle = {}) }
        val step = "Find an object of known length in the photo (a door, brick, tape measure)."
        rule.onNodeWithText(step).assertDoesNotExist()
        rule.onNodeWithText("How to calibrate").performClick()
        rule.onNodeWithText(step).assertExists()
        rule.onNodeWithText("How to calibrate").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(step).assertDoesNotExist()
    }
}
