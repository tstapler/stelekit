// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class PermissionRationaleDialogTest {
    @get:Rule
    val rule = createComposeRule()

    // ── Camera ────────────────────────────────────────────────────────────────

    @Test
    fun camera_default_showsContinue_andFiresCallbacks() {
        var notNow = 0
        var cont = 0
        rule.setContent {
            CameraPermissionRationaleDialog(onNotNow = { notNow++ }, onContinue = { cont++ })
        }
        rule.onNodeWithText("Allow camera access").assertExists()
        rule.onNodeWithText("Open Settings").assertDoesNotExist()
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Not now").performClick()
        assertEquals(1, cont)
        assertEquals(1, notNow)
    }

    @Test
    fun camera_permanentlyDenied_showsOpenSettings() {
        rule.setContent {
            CameraPermissionRationaleDialog(onNotNow = {}, onContinue = {}, isPermanentlyDenied = true)
        }
        rule.onNodeWithText("Open Settings").assertExists()
        rule.onNodeWithText("Continue").assertDoesNotExist()
    }

    // ── BLE ───────────────────────────────────────────────────────────────────

    @Test
    fun ble_default_showsBluetoothBody_andFiresCallbacks() {
        var draw = 0
        var allow = 0
        rule.setContent {
            BlePermissionRationaleDialog(onUseDrawMethod = { draw++ }, onAllowBluetooth = { allow++ })
        }
        rule.onNodeWithText("Allow Bluetooth access").assertExists()
        rule.onNodeWithText(
            "To connect your Bluetooth laser distance meter, SteleKit needs Bluetooth access. " +
                "This is only used to receive measurements from your meter — the app never uses Bluetooth for anything else.",
        ).assertExists()
        rule.onNodeWithText("Allow Bluetooth").performClick()
        rule.onNodeWithText("Use draw method instead").performClick()
        assertEquals(1, allow)
        assertEquals(1, draw)
    }

    @Test
    fun ble_requiresLocation_showsLocationBody() {
        rule.setContent {
            BlePermissionRationaleDialog(onUseDrawMethod = {}, onAllowBluetooth = {}, requiresLocation = true)
        }
        rule.onNodeWithText(
            "To scan for nearby Bluetooth devices, Android also requires location access. " +
                "This is an Android requirement — SteleKit does not use your GPS location.",
        ).assertExists()
    }

    @Test
    fun ble_permanentlyDenied_winsOverLocation_andShowsOpenSettings() {
        rule.setContent {
            BlePermissionRationaleDialog(
                onUseDrawMethod = {}, onAllowBluetooth = {},
                requiresLocation = true, isPermanentlyDenied = true,
            )
        }
        rule.onNodeWithText(
            "Bluetooth access was denied. Go to Settings → Permissions to allow Bluetooth for laser meter measurements.",
        ).assertExists()
        rule.onNodeWithText("Open Settings").assertExists()
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    fun delete_withLabel_namesTheAnnotation_andFiresCallbacks() {
        var cancel = 0
        var delete = 0
        rule.setContent {
            DeleteAnnotationConfirmationDialog(
                annotationLabel = "North wall", onCancel = { cancel++ }, onDelete = { delete++ },
            )
        }
        rule.onNodeWithText("Delete 'North wall'? This cannot be undone.").assertExists()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, delete)
        assertEquals(1, cancel)
    }

    @Test
    fun delete_nullOrBlankLabel_usesGenericBody() {
        rule.setContent {
            DeleteAnnotationConfirmationDialog(annotationLabel = "  ", onCancel = {}, onDelete = {})
        }
        rule.onNodeWithText("Delete this annotation? This cannot be undone.").assertExists()
    }
}
