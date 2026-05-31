// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun CameraPermissionRationaleDialog(
    onNotNow: () -> Unit,
    onContinue: () -> Unit,
    isPermanentlyDenied: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onNotNow,
        icon = {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text("Allow camera access")
        },
        text = {
            Text("SteleKit needs camera access to photograph construction sites directly from the app. You can also import existing photos without camera access.")
        },
        dismissButton = {
            TextButton(onClick = onNotNow) {
                Text("Not now")
            }
        },
        confirmButton = {
            Button(onClick = onContinue) {
                Text(if (isPermanentlyDenied) "Open Settings" else "Continue")
            }
        },
    )
}

@Composable
fun BlePermissionRationaleDialog(
    onUseDrawMethod: () -> Unit,
    onAllowBluetooth: () -> Unit,
    requiresLocation: Boolean = false,
    isPermanentlyDenied: Boolean = false,
) {
    val bodyText = when {
        isPermanentlyDenied -> "Bluetooth access was denied. Go to Settings → Permissions to allow Bluetooth for laser meter measurements."
        requiresLocation -> "To scan for nearby Bluetooth devices, Android also requires location access. This is an Android requirement — SteleKit does not use your GPS location."
        else -> "To connect your Bluetooth laser distance meter, SteleKit needs Bluetooth access. This is only used to receive measurements from your meter — the app never uses Bluetooth for anything else."
    }

    AlertDialog(
        onDismissRequest = onUseDrawMethod,
        icon = {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text("Allow Bluetooth access")
        },
        text = {
            Text(bodyText)
        },
        dismissButton = {
            TextButton(onClick = onUseDrawMethod) {
                Text("Use draw method instead")
            }
        },
        confirmButton = {
            Button(onClick = onAllowBluetooth) {
                Text(if (isPermanentlyDenied) "Open Settings" else "Allow Bluetooth")
            }
        },
    )
}

@Composable
fun DeleteAnnotationConfirmationDialog(
    annotationLabel: String?,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val bodyText = if (annotationLabel != null && annotationLabel.isNotBlank()) {
        "Delete '$annotationLabel'? This cannot be undone."
    } else {
        "Delete this annotation? This cannot be undone."
    }

    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text("Delete annotation?")
        },
        text = {
            Text(bodyText)
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Delete")
            }
        },
    )
}
